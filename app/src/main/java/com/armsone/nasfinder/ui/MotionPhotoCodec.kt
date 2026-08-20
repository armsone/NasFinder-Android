package com.armsone.nasfinder.ui

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

internal data class MotionPhotoLayout(
    val primaryLength: Int,
    val videoOffset: Int,
    val videoLength: Int,
    val presentationTimestampUs: Long,
)

/** Google Motion Photo Format 1.0 JPEG/XMP reader and writer. */
internal object MotionPhotoCodec {
    private val XMP_PREAMBLE = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
    private const val CAMERA_NS = "http://ns.google.com/photos/1.0/camera/"
    private const val CONTAINER_NS = "http://ns.google.com/photos/1.0/container/"
    private const val ITEM_NS = "http://ns.google.com/photos/1.0/container/item/"
    private const val RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private const val MAX_XMP_BYTES = 64 * 1024
    private const val MAX_COMPONENT_BYTES = 1024 * 1024 * 1024
    private val VIDEO_MIME_TYPES = setOf("video/mp4", "video/quicktime")

    fun parse(bytes: ByteArray): MotionPhotoLayout? = runCatching {
        require(bytes.size >= 16 && bytes.size <= MAX_COMPONENT_BYTES)
        val xmp = extractStandardXmp(bytes)
        val metadata = parseXmp(xmp)
        require(metadata.videoLength in 8 until bytes.size)
        val videoOffset = bytes.size - metadata.videoLength
        require(videoOffset > 2 && bytes[videoOffset - 2] == 0xff.toByte() && bytes[videoOffset - 1] == 0xd9.toByte()) {
            "Motion Photo의 JPEG 경계가 올바르지 않습니다."
        }
        require(hasMp4FtypAt(bytes, videoOffset, metadata.videoLength)) {
            "Motion Photo 영상 시작점이 올바르지 않습니다."
        }
        MotionPhotoLayout(
            primaryLength = videoOffset,
            videoOffset = videoOffset,
            videoLength = metadata.videoLength,
            presentationTimestampUs = metadata.presentationTimestampUs,
        )
    }.getOrNull()

    fun primaryImage(bytes: ByteArray, layout: MotionPhotoLayout): ByteArray =
        removeStandardXmp(bytes.copyOfRange(0, layout.primaryLength))

    fun motionVideo(bytes: ByteArray, layout: MotionPhotoLayout): ByteArray =
        bytes.copyOfRange(layout.videoOffset, layout.videoOffset + layout.videoLength)

    fun create(
        primaryJpeg: ByteArray,
        motionVideo: ByteArray,
        presentationTimestampUs: Long = -1,
        videoMimeType: String = "video/mp4",
    ): ByteArray {
        require(primaryJpeg.size in 4..MAX_COMPONENT_BYTES)
        require(motionVideo.size in 8..MAX_COMPONENT_BYTES)
        require(primaryJpeg[0] == 0xff.toByte() && primaryJpeg[1] == 0xd8.toByte()) { "대표 사진은 JPEG여야 합니다." }
        require(primaryJpeg[primaryJpeg.lastIndex - 1] == 0xff.toByte() && primaryJpeg.last() == 0xd9.toByte()) {
            "대표 사진의 JPEG가 완전하지 않습니다."
        }
        require(hasMp4FtypAt(motionVideo, 0, motionVideo.size)) { "움직임 영상은 ISO-BMFF 형식이어야 합니다." }
        require(presentationTimestampUs >= -1) { "표시 시간이 올바르지 않습니다." }
        require(videoMimeType in VIDEO_MIME_TYPES) { "움직임 영상 형식이 올바르지 않습니다." }

        val cleanJpeg = removeStandardXmp(primaryJpeg)
        val xmp = buildXmp(motionVideo.size, presentationTimestampUs, videoMimeType)
        val payload = XMP_PREAMBLE + xmp
        require(payload.size + 2 <= 0xffff) { "Motion Photo XMP가 너무 큽니다." }
        val segment = ByteArrayOutputStream(payload.size + 4).apply {
            write(0xff)
            write(0xe1)
            val declared = payload.size + 2
            write(declared ushr 8)
            write(declared and 0xff)
            write(payload)
        }.toByteArray()
        val outputLength = cleanJpeg.size.toLong() + segment.size + motionVideo.size
        require(outputLength <= MAX_COMPONENT_BYTES) { "생성할 Motion Photo가 너무 큽니다." }
        return ByteArrayOutputStream(outputLength.toInt()).apply {
            write(cleanJpeg, 0, 2)
            write(segment)
            write(cleanJpeg, 2, cleanJpeg.size - 2)
            write(motionVideo)
        }.toByteArray()
    }

    private data class ParsedXmp(val videoLength: Int, val presentationTimestampUs: Long)

    private fun parseXmp(xmp: ByteArray): ParsedXmp {
        require(xmp.size in 1..MAX_XMP_BYTES)
        require(!xmp.toString(Charsets.UTF_8).contains("<!DOCTYPE", ignoreCase = true))
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching {
                setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            }
            runCatching {
                setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
            }
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xmp))
        val descriptions = document.getElementsByTagNameNS(RDF_NS, "Description")
        require(descriptions.length == 1)
        val description = descriptions.item(0) as Element
        require(description.getAttributeNS(CAMERA_NS, "MotionPhoto") == "1")
        require(description.getAttributeNS(CAMERA_NS, "MotionPhotoVersion") == "1")
        val timestamp = description.getAttributeNS(CAMERA_NS, "MotionPhotoPresentationTimestampUs").toLong()
        require(timestamp >= -1)

        val directories = description.getElementsByTagNameNS(CONTAINER_NS, "Directory")
        require(directories.length == 1)
        val sequences = (directories.item(0) as Element).getElementsByTagNameNS(RDF_NS, "Seq")
        require(sequences.length == 1)
        val items = (sequences.item(0) as Element).getElementsByTagNameNS(RDF_NS, "li")
        require(items.length == 2) { "Motion Photo 디렉터리는 두 항목이어야 합니다." }
        val primary = items.item(0) as Element
        val video = items.item(1) as Element
        require(primary.getAttributeNS(ITEM_NS, "Mime") == "image/jpeg")
        require(primary.getAttributeNS(ITEM_NS, "Semantic") == "Primary")
        require(video.getAttributeNS(ITEM_NS, "Mime") in VIDEO_MIME_TYPES)
        require(video.getAttributeNS(ITEM_NS, "Semantic") == "MotionPhoto")
        require(primary.getAttributeNS(ITEM_NS, "Padding").let { it.isEmpty() || it == "0" })
        require(primary.getAttributeNS(ITEM_NS, "Length").isEmpty())
        val videoLength = video.getAttributeNS(ITEM_NS, "Length").toInt()
        require(videoLength > 0)
        require(video.getAttributeNS(ITEM_NS, "Padding").let { it.isEmpty() || it == "0" })
        return ParsedXmp(videoLength, timestamp)
    }

    private fun buildXmp(videoLength: Int, timestampUs: Long, videoMimeType: String): ByteArray =
        ("""<x:xmpmeta xmlns:x="adobe:ns:meta/">""" +
            """<rdf:RDF xmlns:rdf="$RDF_NS"><rdf:Description """ +
            """xmlns:Camera="$CAMERA_NS" xmlns:Container="$CONTAINER_NS" xmlns:Item="$ITEM_NS" """ +
            """Camera:MotionPhoto="1" Camera:MotionPhotoVersion="1" """ +
            """Camera:MotionPhotoPresentationTimestampUs="$timestampUs">""" +
            """<Container:Directory><rdf:Seq>""" +
            """<rdf:li rdf:parseType="Resource" Item:Mime="image/jpeg" Item:Semantic="Primary"/>""" +
            """<rdf:li rdf:parseType="Resource" Item:Mime="$videoMimeType" Item:Semantic="MotionPhoto" Item:Length="$videoLength"/>""" +
            """</rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>""")
            .toByteArray(Charsets.UTF_8)

    private fun extractStandardXmp(jpeg: ByteArray): ByteArray {
        var found: ByteArray? = null
        walkJpegSegments(jpeg) { marker, start, payloadStart, payloadLength ->
            if (marker == 0xe1 && payloadLength >= XMP_PREAMBLE.size &&
                jpeg.regionMatches(payloadStart, XMP_PREAMBLE)
            ) {
                require(found == null) { "중복 XMP 패킷입니다." }
                found = jpeg.copyOfRange(payloadStart + XMP_PREAMBLE.size, payloadStart + payloadLength)
            }
        }
        return requireNotNull(found) { "Motion Photo XMP가 없습니다." }
    }

    private fun removeStandardXmp(jpeg: ByteArray): ByteArray {
        val removals = mutableListOf<IntRange>()
        walkJpegSegments(jpeg) { marker, start, payloadStart, payloadLength ->
            if (marker == 0xe1 && payloadLength >= XMP_PREAMBLE.size && jpeg.regionMatches(payloadStart, XMP_PREAMBLE)) {
                removals += start until (payloadStart + payloadLength)
            }
        }
        if (removals.isEmpty()) return jpeg
        val output = ByteArrayOutputStream(jpeg.size)
        var cursor = 0
        removals.forEach { range ->
            output.write(jpeg, cursor, range.first - cursor)
            cursor = range.last + 1
        }
        output.write(jpeg, cursor, jpeg.size - cursor)
        return output.toByteArray()
    }

    private inline fun walkJpegSegments(
        jpeg: ByteArray,
        visit: (marker: Int, start: Int, payloadStart: Int, payloadLength: Int) -> Unit,
    ) {
        require(jpeg.size >= 4 && jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte())
        var cursor = 2
        while (cursor + 1 < jpeg.size) {
            require(jpeg[cursor] == 0xff.toByte()) { "손상된 JPEG 마커입니다." }
            val start = cursor
            while (cursor < jpeg.size && jpeg[cursor] == 0xff.toByte()) cursor++
            require(cursor < jpeg.size)
            val marker = jpeg[cursor].toInt() and 0xff
            cursor++
            if (marker == 0xda || marker == 0xd9) return
            require(marker !in setOf(0x00, 0x01) && marker !in 0xd0..0xd7)
            require(cursor + 2 <= jpeg.size)
            val declared = ((jpeg[cursor].toInt() and 0xff) shl 8) or (jpeg[cursor + 1].toInt() and 0xff)
            require(declared >= 2 && cursor + declared <= jpeg.size)
            visit(marker, start, cursor + 2, declared - 2)
            cursor += declared
        }
        error("완전한 JPEG 헤더가 아닙니다.")
    }

    private fun ByteArray.regionMatches(offset: Int, expected: ByteArray): Boolean =
        offset >= 0 && offset + expected.size <= size && expected.indices.all { this[offset + it] == expected[it] }

    private fun hasMp4FtypAt(bytes: ByteArray, offset: Int, length: Int): Boolean {
        if (offset < 0 || length < 8 || offset + length > bytes.size) return false
        val boxSize = ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)
        return boxSize in 8..length.toLong() &&
            bytes[offset + 4] == 'f'.code.toByte() && bytes[offset + 5] == 't'.code.toByte() &&
            bytes[offset + 6] == 'y'.code.toByte() && bytes[offset + 7] == 'p'.code.toByte()
    }
}
