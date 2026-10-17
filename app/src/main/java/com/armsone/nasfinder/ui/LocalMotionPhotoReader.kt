package com.armsone.nasfinder.ui

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.ensureActive

/** Read-only, seek-based inspection. Only a selected preview's video range is copied. */
internal object LocalMotionPhotoReader {
    data class VideoRange(val offset: Long, val length: Long)
    private data class Cached(val range: VideoRange?)
    private val cache = object : LinkedHashMap<String, Cached>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 4096
    }

    fun inspect(context: Context, uri: Uri, size: Long? = null, modified: Long? = null): VideoRange? {
        var revision = modified
        var bytes = size
        if (revision == null || bytes == null) {
            runCatching { context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)?.use {
                if (it.moveToFirst()) { bytes = it.getLong(0); revision = it.getLong(1) }
            } }
        }
        val canonical = runCatching { context.contentResolver.canonicalize(uri) }.getOrNull() ?: uri
        val key = "$canonical|$bytes|$revision"
        if (bytes != null && revision != null) synchronized(cache) { cache[key] }?.let { return it.range }
        val range = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { asset ->
            FileInputStream(asset.fileDescriptor).use { stream ->
                val channel = stream.channel
                val base = asset.startOffset
                val length = asset.declaredLength.takeIf { it >= 0 } ?: (channel.size() - base)
                if (length < 24) return@use null
                fun read(offset: Long, count: Int): ByteArray {
                    require(offset >= 0 && count >= 0 && offset <= length - count)
                    val result = ByteArray(count)
                    val buffer = ByteBuffer.wrap(result)
                    while (buffer.hasRemaining()) {
                        val n = channel.read(buffer, base + offset + buffer.position())
                        check(n > 0) { "Incomplete motion metadata" }
                    }
                    return result
                }
                fun validated(offset: Long, count: Long): VideoRange? {
                    if (offset < 2 || count < 24 || offset > length - count) return null
                    val header = read(offset, minOf(40L, count).toInt())
                    if (ascii(header, 4, 4) != "ftyp") return null
                    val boxLength = uint(header, 0)
                    if (boxLength !in 16..count || ascii(header, 8, 4) in setOf("heic", "heix", "mif1", "msf1", "avif", "avis")) return null
                    val retriever = MediaMetadataRetriever()
                    return try {
                        retriever.setDataSource(asset.fileDescriptor, base + offset, count)
                        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                        if (duration > 0 && width > 0) VideoRange(offset, count) else null
                    } catch (_: RuntimeException) { null } finally { retriever.release() }
                }
                // Samsung SEF directory points to a named MotionPhoto_Data field, never a filename guess.
                val tailSize = minOf(length, 128L * 1024).toInt()
                val tailStart = length - tailSize
                val tail = read(tailStart, tailSize)
                if (ascii(tail, tail.size - 4, 4) == "SEFT") {
                    val directoryStart = length - uint(tail, tail.size - 8, little = true) - 8
                    if (directoryStart >= tailStart && directoryStart <= length - 20) {
                        val local = (directoryStart - tailStart).toInt()
                        if (ascii(tail, local, 4) == "SEFH") {
                            val entries = uint(tail, local + 8, little = true).coerceAtMost(1024).toInt()
                            for (index in 0 until entries) {
                                val entry = local + 12 + index * 12
                                if (entry + 12 > tail.size - 8) break
                                val fieldOffset = directoryStart - uint(tail, entry + 4, little = true)
                                val fieldSize = uint(tail, entry + 8, little = true)
                                if (fieldOffset < 0 || fieldSize < 24 || fieldOffset > length - fieldSize) continue
                                val fieldHeader = read(fieldOffset, minOf(fieldSize, 64L).toInt())
                                val nameSize = uint(fieldHeader, 4, little = true)
                                if (nameSize != 16L || ascii(fieldHeader, 8, 16) != "MotionPhoto_Data") continue
                                val data = fieldOffset + 8 + nameSize
                                if (ascii(fieldHeader, 24, 4) == "mpv2" && fieldSize >= 36) {
                                    val mpv = read(data + 4, 8)
                                    validated(uint(mpv, 0), uint(mpv, 4))?.let { return@use it }
                                } else validated(data, fieldSize - 8 - nameSize)?.let { return@use it }
                            }
                        }
                    }
                }
                val header = read(0, minOf(length, 32L).toInt())
                if (ascii(header, 4, 4) == "ftyp") {
                    // HEIC/AVIF XMP can live megabytes into the image. Skip boxes by length,
                    // inspecting headers only; mpvd explicitly contains the appended video.
                    var offset = 0L
                    repeat(512) {
                        if (offset > length - 8) return@repeat
                        val box = read(offset, minOf(16L, length - offset).toInt())
                        var count = uint(box, 0)
                        var headerLength = 8L
                        if (count == 1L) { count = ByteBuffer.wrap(box, 8, 8).long; headerLength = 16 }
                        if (count == 0L) count = length - offset
                        if (count < headerLength || count > length - offset) return@use null
                        if (ascii(box, 4, 4) == "mpvd") validated(offset + headerLength, count - headerLength)?.let { return@use it }
                        offset += count
                    }
                    return@use null
                }
                // JPEG APP1 is bounded by the JPEG segment format. No image pixels are read.
                if (header[0] != 0xff.toByte() || header[1] != 0xd8.toByte()) return@use null
                var offset = 2L
                var inspected = 0L
                while (offset < length - 4 && inspected < 1024 * 1024) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    val marker = read(offset, 4)
                    if (marker[0] != 0xff.toByte()) break
                    val kind = marker[1].toInt() and 255
                    if (kind == 0xda || kind == 0xd9) break
                    if (kind in 0xd0..0xd8 || kind == 1) { offset += 2; inspected += 2; continue }
                    val count = ((marker[2].toInt() and 255) shl 8) + (marker[3].toInt() and 255)
                    if (count < 2 || offset + 2 + count > length) break
                    if (kind == 0xe1) {
                        val segment = read(offset + 4, count - 2)
                        val prefix = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
                        if (segment.size > prefix.size && segment.copyOfRange(0, prefix.size).contentEquals(prefix)) {
                            xmpVideoLength(segment.copyOfRange(prefix.size, segment.size))?.let { videoLength ->
                                validated(length - videoLength, videoLength)?.let { return@use it }
                            }
                        }
                    }
                    offset += count + 2; inspected += count + 2
                }
                null
            }
        }
        if (bytes != null && revision != null) synchronized(cache) { cache[key] = Cached(range) }
        return range
    }

    suspend fun extractPreview(context: Context, uri: Uri, range: VideoRange, retain: (File) -> Unit): File {
        val output = File.createTempFile("library-motion-", ".mp4", context.cacheDir)
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")!!.use { asset ->
                FileInputStream(asset.fileDescriptor).use { source ->
                    val base = asset.startOffset
                    val physicalLength = source.channel.size()
                    require(base >= 0 && base <= physicalLength) { "Invalid motion source range" }
                    val available = asset.declaredLength.takeIf { it >= 0 }?.coerceAtMost(physicalLength - base) ?: (physicalLength - base)
                    require(range.offset >= 0 && range.length > 0 && range.length <= available && range.offset <= available - range.length) { "Motion source changed" }
                    require(range.offset <= Long.MAX_VALUE - base) { "Invalid motion source offset" }
                    source.channel.position(base + range.offset)
                    output.outputStream().use { target ->
                        var remaining = range.length
                        val buffer = ByteArray(64 * 1024)
                        while (remaining > 0) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                            val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            check(count > 0) { "Incomplete motion video" }
                            target.write(buffer, 0, count); remaining -= count
                        }
                    }
                }
            }
            retain(output)
            return output
        } catch (failure: Throwable) { output.delete(); throw failure }
    }

    private fun xmpVideoLength(bytes: ByteArray): Long? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(bytes.inputStream())
        val camera = "http://ns.google.com/photos/1.0/camera/"
        val item = "http://ns.google.com/photos/1.0/container/item/"
        val elements = document.getElementsByTagName("*")
        var enabled = false
        var legacy: Long? = null
        var motion: Long? = null
        for (index in 0 until elements.length) {
            val element = elements.item(index) as? org.w3c.dom.Element ?: continue
            val flag = element.getAttributeNS(camera, "MotionPhoto")
            if (flag.isNotEmpty() && flag != "1") return@runCatching null
            if (flag == "1" || element.getAttributeNS(camera, "MicroVideo") == "1") enabled = true
            element.getAttributeNS(camera, "MicroVideoOffset").toLongOrNull()?.takeIf { it > 0 }?.let { legacy = it }
            if (element.getAttributeNS(item, "Semantic") == "MotionPhoto" && element.getAttributeNS(item, "Mime") in setOf("video/mp4", "video/quicktime")) {
                motion = element.getAttributeNS(item, "Length").toLongOrNull()?.takeIf { it > 0 }
            }
        }
        if (enabled) motion ?: legacy else null
    }.getOrNull()

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        if (offset < 0 || offset > bytes.size - length) "" else String(bytes, offset, length, Charsets.US_ASCII)
    private fun uint(bytes: ByteArray, offset: Int, little: Boolean = false): Long =
        if (offset < 0 || offset > bytes.size - 4) -1 else ByteBuffer.wrap(bytes, offset, 4).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
}
