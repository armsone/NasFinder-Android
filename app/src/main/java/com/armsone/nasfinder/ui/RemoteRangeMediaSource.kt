@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.armsone.nasfinder.ui

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.armsone.nasfinder.model.RemoteFileItem
import com.armsone.nasfinder.network.RemoteFileService
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking

internal enum class RemoteMediaRoute { RANGE_MEDIA3, DOWNLOAD_MEDIA3, EXTERNAL_PLAYER }

internal object RemoteMediaRoutePolicy {
    private val rangeExtensions = setOf(
        "mp4", "m4a", "m4v", "fmp4", "webm", "mkv", "mp3", "ogg", "oga", "wav",
        "ts", "m2ts", "mts", "mpeg", "mpg", "vob", "flv", "aac", "adts", "flac",
        "amr", "3gp", "3g2",
    )
    private val externalOnlyExtensions = setOf("avi", "asf", "wmv")

    fun route(filename: String, supportsRange: Boolean): RemoteMediaRoute {
        val extension = filename.substringAfterLast('.', "").lowercase()
        return when {
            extension in externalOnlyExtensions -> RemoteMediaRoute.EXTERNAL_PLAYER
            supportsRange && extension in rangeExtensions -> RemoteMediaRoute.RANGE_MEDIA3
            else -> RemoteMediaRoute.DOWNLOAD_MEDIA3
        }
    }
}

internal object RemoteSubtitlePolicy {
    private val preference = listOf("srt", "vtt", "ass", "ssa")

    fun match(media: RemoteFileItem, siblings: List<RemoteFileItem>): RemoteFileItem? {
        val parent = media.path.substringBeforeLast('/', "")
        val basename = media.name.substringBeforeLast('.', media.name)
        return preference.firstNotNullOfOrNull { extension ->
            siblings.firstOrNull { candidate ->
                !candidate.isDirectory &&
                    candidate.path.substringBeforeLast('/', "") == parent &&
                    candidate.name.substringAfterLast('.', "").equals(extension, ignoreCase = true) &&
                    candidate.name.substringBeforeLast('.', candidate.name) == basename
            }
        }
    }

    fun mimeType(item: RemoteFileItem): String = when (item.extension) {
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        else -> MimeTypes.TEXT_UNKNOWN
    }
}

internal fun interface RemoteRangeReader {
    suspend fun read(item: RemoteFileItem, offset: Long, length: Int): ByteArray
}

internal class RemoteRangeReadSession(
    private val item: RemoteFileItem,
    private val reader: RemoteRangeReader,
) {
    private val closed = AtomicBoolean(false)
    private val activeRead = AtomicReference<Job?>(null)
    private var position = 0L
    private var remaining = 0L
    private var cachedOffset = 0L
    private var cachedBytes = ByteArray(0)

    fun open(position: Long, requestedLength: Long): Long {
        if (position < 0 || (item.size > 0 && position > item.size)) throw EOFException()
        this.position = position
        val available = if (item.size > 0) item.size - position else C.LENGTH_UNSET.toLong()
        remaining = when {
            requestedLength != C.LENGTH_UNSET.toLong() && available != C.LENGTH_UNSET.toLong() -> minOf(requestedLength, available)
            requestedLength != C.LENGTH_UNSET.toLong() -> requestedLength
            else -> available
        }
        closed.set(false)
        cachedOffset = position
        cachedBytes = ByteArray(0)
        return remaining
    }

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (closed.get()) throw CancellationException("Remote media source closed")
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val requested = minOf(
            length,
            remaining.takeIf { it != C.LENGTH_UNSET.toLong() }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
                ?: Int.MAX_VALUE,
        )
        val cachedEnd = cachedOffset + cachedBytes.size
        if (position < cachedOffset || position >= cachedEnd) {
            val fetchLength = minOf(
                RemoteRangeDataSource.PLAYBACK_CHUNK_BYTES,
                remaining.takeIf { it != C.LENGTH_UNSET.toLong() }
                    ?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: Int.MAX_VALUE,
            )
            val readJob = Job()
            activeRead.set(readJob)
            cachedBytes = try {
                runBlocking(readJob + Dispatchers.IO) { reader.read(item, position, fetchLength) }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Remote range read interrupted").apply { initCause(error) }
            } catch (error: CancellationException) {
                throw InterruptedIOException("Remote range read cancelled").apply { initCause(error) }
            } finally {
                activeRead.compareAndSet(readJob, null)
            }
            cachedOffset = position
        }
        if (closed.get()) throw CancellationException("Remote media source closed")
        if (cachedBytes.isEmpty()) {
            remaining = 0
            return C.RESULT_END_OF_INPUT
        }
        val sourceIndex = (position - cachedOffset).toInt()
        val count = minOf(cachedBytes.size - sourceIndex, requested)
        cachedBytes.copyInto(buffer, offset, sourceIndex, sourceIndex + count)
        position += count
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= count
        return count
    }

    fun close() {
        closed.set(true)
        activeRead.getAndSet(null)?.cancel()
    }
}

internal class RemoteRangeDataSource(
    private val itemsByUri: Map<Uri, RemoteFileItem>,
    private val reader: RemoteRangeReader,
) : BaseDataSource(false) {
    private var item: RemoteFileItem? = null
    private var session: RemoteRangeReadSession? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        val selected = itemsByUri[dataSpec.uri] ?: throw IOException("Unknown remote media URI")
        item = selected
        transferInitializing(dataSpec)
        val remaining = RemoteRangeReadSession(selected, reader).also { session = it }
            .open(dataSpec.position, dataSpec.length)
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = session?.read(buffer, offset, length) ?: throw IOException("Remote media source is not open")
        if (count > 0) bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = item?.let(::uriFor)

    override fun close() {
        session?.close()
        session = null
        item = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    companion object {
        const val PLAYBACK_CHUNK_BYTES = 8 * 1024 * 1024
        fun uriFor(item: RemoteFileItem): Uri = Uri.Builder()
            .scheme("nasfinder-range")
            .authority("media")
            .appendPath(item.id)
            .build()
    }
}

class RemoteMediaStreamSpec(
    val item: RemoteFileItem,
    val subtitle: RemoteFileItem?,
    private val service: RemoteFileService,
) {
    val sourceKey: String = "${item.id}:${item.path}"

    fun createMediaSource(): MediaSource {
        val allItems = listOfNotNull(item, subtitle)
        val itemsByUri = allItems.associateBy(RemoteRangeDataSource::uriFor)
        val factory = DataSource.Factory {
            RemoteRangeDataSource(itemsByUri) { remote, offset, length ->
                service.readRange(remote, offset, length)
            }
        }
        val builder = MediaItem.Builder()
            .setUri(RemoteRangeDataSource.uriFor(item))
            .setMediaId(sourceKey)
        subtitle?.let { sidecar ->
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(RemoteRangeDataSource.uriFor(sidecar))
                        .setMimeType(RemoteSubtitlePolicy.mimeType(sidecar))
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build(),
                ),
            )
        }
        return ProgressiveMediaSource.Factory(factory).createMediaSource(builder.build())
    }
}
