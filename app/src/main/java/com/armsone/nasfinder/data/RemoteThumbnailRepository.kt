package com.armsone.nasfinder.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import com.armsone.nasfinder.model.RemoteConnection
import com.armsone.nasfinder.model.RemoteFileItem
import com.armsone.nasfinder.network.HttpRangeContract
import com.armsone.nasfinder.network.RemoteFileService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.EmptyCoroutineContext

data class RemoteThumbnailCacheStatistics(
    val fileCount: Int,
    val totalBytes: Long,
    val automaticLimitBytes: Long,
)

object RemoteThumbnailCachePolicy {
    val automaticLimitOptions = listOf(
        128L * 1024 * 1024,
        256L * 1024 * 1024,
        512L * 1024 * 1024,
    )
    const val DEFAULT_AUTOMATIC_LIMIT_BYTES = 256L * 1024 * 1024
    private const val PREFERENCES = "remote_thumbnail_cache"
    private const val LIMIT_KEY = "automatic_limit_bytes"

    fun automaticLimitBytes(context: Context): Long =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getLong(LIMIT_KEY, DEFAULT_AUTOMATIC_LIMIT_BYTES)
            .takeIf(automaticLimitOptions::contains)
            ?: DEFAULT_AUTOMATIC_LIMIT_BYTES

    fun setAutomaticLimitBytes(context: Context, bytes: Long) {
        require(bytes in automaticLimitOptions) { "지원하지 않는 썸네일 캐시 기준입니다." }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putLong(LIMIT_KEY, bytes).apply()
    }
}

/** Loads bounded remote image/server-video thumbnails without retaining credentials or source payloads. */
class RemoteThumbnailRepository(
    context: Context,
    client: OkHttpClient = OkHttpClient(),
    private val maxPixelSize: Int = DEFAULT_MAX_PIXEL_SIZE,
    trafficLimits: RemoteThumbnailTrafficLimits = RemoteThumbnailTrafficLimits(),
) : Closeable {
    private val appContext = context.applicationContext
    private val httpClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightMutex = Mutex()
    // MediaMetadataRetriever performs many small random reads. If every visible poster
    // does that simultaneously, the shared budget can be exhausted before one frame wins.
    private val thumbnailFetchPermits = Semaphore(2)
    private val inFlight = mutableMapOf<String, Deferred<Bitmap?>>()
    private val negativeUntil = ConcurrentHashMap<String, Long>()
    private val trafficBudget = RemoteThumbnailTrafficBudget(trafficLimits)
    val trafficSnapshot: StateFlow<RemoteThumbnailTrafficSnapshot> = trafficBudget.snapshot.asStateFlow()
    private val memory = WeightedLruCache<String, Bitmap>(MEMORY_CACHE_BYTES) { bitmap ->
        bitmap.allocationByteCount.coerceAtLeast(1).toLong()
    }
    private val disk = ThumbnailDiskCache(
        File(context.cacheDir, "remote-thumbnails"),
        RemoteThumbnailCachePolicy.automaticLimitBytes(appContext),
        DISK_MAX_AGE_MILLIS,
        DISK_MAX_FILE_COUNT,
    )

    fun cacheStatistics(): RemoteThumbnailCacheStatistics = disk.statistics()

    fun setAutomaticCacheLimit(bytes: Long): RemoteThumbnailCacheStatistics {
        RemoteThumbnailCachePolicy.setAutomaticLimitBytes(appContext, bytes)
        disk.updateMaxBytes(bytes)
        return disk.statistics()
    }

    fun clearDiskCache(): RemoteThumbnailCacheStatistics {
        memory.clear()
        negativeUntil.clear()
        disk.clear()
        return disk.statistics()
    }

    suspend fun load(
        connection: RemoteConnection,
        item: RemoteFileItem,
        activeService: RemoteFileService,
    ): Bitmap? {
        if (RemoteThumbnailFetchPolicy.source(item, activeService.supportsRangeStreaming) == RemoteThumbnailSource.NONE) {
            return null
        }
        require(maxPixelSize in 1..MAX_ALLOWED_PIXEL_SIZE) { "올바르지 않은 썸네일 크기입니다." }
        val key = RemoteThumbnailCacheKey.create(connection.id, item, maxPixelSize)
        memory[key]?.let { return it }
        if ((negativeUntil[key] ?: 0L) > System.currentTimeMillis()) return null

        val shared = inFlightMutex.withLock {
            inFlight[key] ?: scope.async {
                loadInternal(key, connection, item, activeService)
            }.also { deferred ->
                inFlight[key] = deferred
                deferred.invokeOnCompletion {
                    scope.launch { inFlightMutex.withLock { if (inFlight[key] === deferred) inFlight.remove(key) } }
                }
            }
        }
        return shared.await()
    }

    fun hasCached(connection: RemoteConnection, item: RemoteFileItem): Boolean {
        val key = RemoteThumbnailCacheKey.create(connection.id, item, maxPixelSize)
        return memory[key] != null || disk.get(key) != null
    }

    /** Reuses the bounded thumbnail cache and exports only a small JPEG for NAS Vault storage. */
    suspend fun loadJpegData(
        connection: RemoteConnection,
        item: RemoteFileItem,
        activeService: RemoteFileService,
    ): ByteArray? {
        val bitmap = load(connection, item, activeService) ?: return null
        currentCoroutineContext().ensureActive()
        val output = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)) return null
        return output.toByteArray().takeIf { it.isNotEmpty() && it.size <= MAX_VAULT_JPEG_BYTES }
    }

    /** Validates and imports a Vault JPEG into the owned thumbnail cache. */
    suspend fun storeJpegData(
        connection: RemoteConnection,
        item: RemoteFileItem,
        data: ByteArray,
    ): Boolean {
        if (!RemoteThumbnailPayloadPolicy.isVaultJpeg(data)) return false
        val key = RemoteThumbnailCacheKey.create(connection.id, item, maxPixelSize)
        val source = disk.temporaryFile("vault-source")
        val encoded = disk.temporaryFile("vault-encoded")
        return try {
            currentCoroutineContext().ensureActive()
            source.writeBytes(data)
            val bitmap = decodeBounded(source) ?: return false
            encodeThumbnail(bitmap, encoded)
            currentCoroutineContext().ensureActive()
            disk.commit(key, encoded)
            memory.put(key, bitmap)
            negativeUntil.remove(key)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            false
        } finally {
            source.delete()
            encoded.delete()
        }
    }

    /** Starts a new refresh/session only when no request is still using the current accounting window. */
    suspend fun resetTrafficBudget(): Boolean = inFlightMutex.withLock {
        if (inFlight.isNotEmpty()) false else {
            trafficBudget.reset()
            true
        }
    }

    private suspend fun loadInternal(
        key: String,
        connection: RemoteConnection,
        item: RemoteFileItem,
        activeService: RemoteFileService,
    ): Bitmap? {
        memory[key]?.let { return it }
        disk.get(key)?.let { cached ->
            decodeBounded(cached)?.let { bitmap ->
                memory.put(key, bitmap)
                return bitmap
            }
            disk.remove(key)
        }

        val source = disk.temporaryFile("source")
        val encoded = disk.temporaryFile("encoded")
        try {
            currentCoroutineContext().ensureActive()
            val sourcePolicy = RemoteThumbnailFetchPolicy.source(item, activeService.supportsRangeStreaming)
            val bitmap = thumbnailFetchPermits.withPermit { when (sourcePolicy) {
                RemoteThumbnailSource.SPARSE_VIDEO -> sparseVideoFrame(item, activeService)
                RemoteThumbnailSource.SERVER_THUMBNAIL -> try {
                    fetchSource(connection, item, activeService, source, sourcePolicy)
                    currentCoroutineContext().ensureActive()
                    decodeBounded(source) ?: throw IOException("썸네일 이미지 형식을 읽지 못했습니다.")
                } catch (error: CancellationException) {
                    throw error
                } catch (error: RemoteThumbnailTrafficLimitReached) {
                    throw error
                } catch (serverError: Throwable) {
                    // DSM and other backends do not produce previews for every media codec.
                    // Match the iPhone app: keep the server thumbnail as the first choice,
                    // then use a bounded sparse video reader (or the bounded image original)
                    // instead of turning a missing optimized preview into a permanent icon.
                    source.delete()
                    when (val fallback = RemoteThumbnailFetchPolicy.fallbackAfterServerFailure(item, activeService.supportsRangeStreaming)) {
                        RemoteThumbnailSource.SPARSE_VIDEO -> sparseVideoFrame(item, activeService)
                        RemoteThumbnailSource.ORIGINAL_IMAGE -> {
                            fetchSource(connection, item, activeService, source, fallback)
                            currentCoroutineContext().ensureActive()
                            decodeBounded(source) ?: throw IOException("원격 이미지에서 썸네일을 만들지 못했습니다.", serverError)
                        }
                        else -> throw serverError
                    }
                }
                RemoteThumbnailSource.ORIGINAL_IMAGE -> {
                    fetchSource(connection, item, activeService, source, sourcePolicy)
                    currentCoroutineContext().ensureActive()
                    decodeBounded(source) ?: throw IOException("썸네일 이미지 형식을 읽지 못했습니다.")
                }
                RemoteThumbnailSource.NONE -> throw IOException("이 파일은 썸네일을 지원하지 않습니다.")
            } }
            encodeThumbnail(bitmap, encoded)
            currentCoroutineContext().ensureActive()
            disk.commit(key, encoded)
            memory.put(key, bitmap)
            negativeUntil.remove(key)
            return bitmap
        } catch (error: CancellationException) {
            throw error
        } catch (_: RemoteThumbnailTrafficLimitReached) {
            return null
        } catch (_: Throwable) {
            negativeUntil[key] = System.currentTimeMillis() + NEGATIVE_CACHE_MILLIS
            return null
        } finally {
            source.delete()
            // Synology/WebDAV resume downloads use a sibling part file. This random source
            // name is never reused, so retaining it after a cancelled/failed thumbnail leaks cache bytes.
            HttpRangeContract.partialFile(source).delete()
            encoded.delete()
        }
    }

    private suspend fun fetchSource(
        connection: RemoteConnection,
        item: RemoteFileItem,
        activeService: RemoteFileService,
        destination: File,
        sourcePolicy: RemoteThumbnailSource,
    ) {
        val thumbnail = item.thumbnailUrl?.toHttpUrlOrNull()
        if (sourcePolicy == RemoteThumbnailSource.SERVER_THUMBNAIL) {
            if (thumbnail == null) throw IOException("썸네일 서버 주소가 올바르지 않습니다.")
            val ticket = trafficBudget.reserve(SERVER_THUMBNAIL_EXPECTED_BYTES)
                ?: throw RemoteThumbnailTrafficLimitReached()
            val endpoint = connection.endpoint.toHttpUrlOrNull()
                ?: throw IOException("썸네일 서버 주소가 올바르지 않습니다.")
            if (!thumbnail.isHttps && thumbnail.scheme != "http") throw IOException("지원하지 않는 썸네일 주소입니다.")
            if (thumbnail.scheme != endpoint.scheme || !thumbnail.host.equals(endpoint.host, true) ||
                thumbnail.port != endpoint.port
            ) throw IOException("썸네일 서버 경계가 올바르지 않습니다.")
            val call = httpClient.newCall(Request.Builder().url(thumbnail).get().build())
            val cancellation = currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion {
                if (it is CancellationException) call.cancel()
            }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("썸네일 요청이 거부되었습니다.")
                    val contentType = response.body.contentType()?.toString()?.lowercase().orEmpty()
                    if (RemoteThumbnailPayloadPolicy.isErrorContentType(contentType)) {
                        throw IOException("썸네일 서버가 이미지 대신 오류 응답을 반환했습니다.")
                    }
                    val length = response.body.contentLength()
                    if (length > MAX_SERVER_THUMBNAIL_BYTES) throw IOException("서버 썸네일이 너무 큽니다.")
                    response.body.byteStream().use { input ->
                        FileOutputStream(destination).use { output ->
                            val buffer = ByteArray(COPY_BUFFER_BYTES)
                            var copied = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                copied += count
                                if (copied > MAX_SERVER_THUMBNAIL_BYTES) throw IOException("서버 썸네일이 너무 큽니다.")
                                if (!trafficBudget.recordActual(ticket, count.toLong())) {
                                    throw RemoteThumbnailTrafficLimitReached()
                                }
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                    }
                }
            } finally {
                cancellation?.dispose()
            }
            if (looksLikeErrorPayload(destination)) {
                throw IOException("썸네일 서버가 이미지 대신 오류 응답을 반환했습니다.")
            }
            return
        }

        if (sourcePolicy != RemoteThumbnailSource.ORIGINAL_IMAGE) {
            throw IOException("원격 원본을 썸네일 생성에 사용할 수 없습니다.")
        }
        if (item.size > MAX_SOURCE_BYTES) throw IOException("원격 이미지가 너무 큽니다.")
        val ticket = trafficBudget.reserve(item.size.takeIf { it > 0 } ?: UNKNOWN_ORIGINAL_EXPECTED_BYTES)
            ?: throw RemoteThumbnailTrafficLimitReached()
        var accounted = 0L
        activeService.download(item, destination) { completed, _ ->
            val delta = (completed - accounted).coerceAtLeast(0)
            accounted = maxOf(accounted, completed)
            if (delta > 0 && !trafficBudget.recordActual(ticket, delta)) {
                throw RemoteThumbnailTrafficLimitReached()
            }
        }
        val remaining = (destination.length() - accounted).coerceAtLeast(0)
        if (remaining > 0 && !trafficBudget.recordActual(ticket, remaining)) {
            throw RemoteThumbnailTrafficLimitReached()
        }
        currentCoroutineContext().ensureActive()
        if (!destination.isFile || destination.length() > MAX_SOURCE_BYTES) {
            throw IOException("원격 이미지 다운로드 크기가 올바르지 않습니다.")
        }
    }

    private suspend fun sparseVideoFrame(
        item: RemoteFileItem,
        activeService: RemoteFileService,
    ): Bitmap {
        if (!item.isVideo || item.isDirectory || item.size <= 0 || !activeService.supportsRangeStreaming) {
            throw IOException("영상 범위 썸네일을 만들 수 없습니다.")
        }
        val session = SparseVideoRangeSession(
            itemSize = item.size,
            trafficBudget = trafficBudget,
            ownerJob = currentCoroutineContext()[Job],
        ) { offset, length -> activeService.readRange(item, offset, length) }
        val source = SparseRemoteMediaDataSource(session)
        val retriever = MediaMetadataRetriever()
        try {
            currentCoroutineContext().ensureActive()
            retriever.setDataSource(source)
            currentCoroutineContext().ensureActive()
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: throw IOException("영상에서 썸네일 프레임을 찾지 못했습니다.")
            currentCoroutineContext().ensureActive()
            if (frame.width <= 0 || frame.height <= 0 ||
                frame.width > MAX_SOURCE_DIMENSION || frame.height > MAX_SOURCE_DIMENSION
            ) {
                frame.recycle()
                throw IOException("영상 썸네일 크기가 올바르지 않습니다.")
            }
            return scaleBounded(frame)
        } finally {
            source.close()
            runCatching { retriever.release() }
        }
    }

    private fun looksLikeErrorPayload(file: File): Boolean {
        if (!file.isFile) return true
        val prefix = file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128)
            val count = input.read(buffer)
            if (count <= 0) "" else buffer.copyOf(count).toString(Charsets.UTF_8).trimStart()
        }
        return RemoteThumbnailPayloadPolicy.looksLikeErrorPrefix(prefix)
    }

    private fun decodeBounded(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth > MAX_SOURCE_DIMENSION || bounds.outHeight > MAX_SOURCE_DIMENSION
        ) return null
        var sample = 1
        while (bounds.outWidth / sample > maxPixelSize * 2 || bounds.outHeight / sample > maxPixelSize * 2) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        return scaleBounded(decoded)
    }

    private fun scaleBounded(decoded: Bitmap): Bitmap {
        val largest = maxOf(decoded.width, decoded.height)
        if (largest <= maxPixelSize) return decoded
        val ratio = maxPixelSize.toFloat() / largest.toFloat()
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * ratio).toInt().coerceAtLeast(1),
            (decoded.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun encodeThumbnail(bitmap: Bitmap, target: File) {
        FileOutputStream(target).use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw IOException("썸네일 캐시를 인코딩하지 못했습니다.")
            }
            output.fd.sync()
        }
    }

    override fun close() {
        scope.cancel()
        memory.clear()
        negativeUntil.clear()
    }

    private companion object {
        const val DEFAULT_MAX_PIXEL_SIZE = 1024
        const val MAX_ALLOWED_PIXEL_SIZE = 1024
        const val MAX_SOURCE_DIMENSION = 65_536
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val MAX_SERVER_THUMBNAIL_BYTES = 4L * 1024 * 1024
        const val SERVER_THUMBNAIL_EXPECTED_BYTES = 512L * 1024
        const val UNKNOWN_ORIGINAL_EXPECTED_BYTES = 4L * 1024 * 1024
        const val MAX_SOURCE_BYTES = 256L * 1024 * 1024
        const val MAX_VAULT_JPEG_BYTES = 8 * 1024 * 1024
        const val MEMORY_CACHE_BYTES = 32L * 1024 * 1024
        val DISK_MAX_AGE_MILLIS = TimeUnit.DAYS.toMillis(30)
        const val DISK_MAX_FILE_COUNT = 5_000
        val NEGATIVE_CACHE_MILLIS = TimeUnit.SECONDS.toMillis(30)
    }
}

internal data class SparseVideoRangeLimits(
    val maxRequests: Int = 64,
    val maxExpectedBytes: Long = 4L * 1024 * 1024,
    val maxActualBytes: Long = 4L * 1024 * 1024,
    val maxRequestBytes: Int = 8 * 1024 * 1024,
) {
    init {
        require(maxRequests > 0 && maxExpectedBytes > 0 && maxActualBytes > 0)
        require(maxRequestBytes in 1..8 * 1024 * 1024)
    }
}

internal data class SparseVideoRangeSnapshot(
    val requestCount: Int,
    val expectedBytes: Long,
    val actualBytes: Long,
    val closed: Boolean,
)

/** Pure bounded adapter shared by MediaDataSource and JVM policy tests. */
internal class SparseVideoRangeSession(
    val itemSize: Long,
    private val trafficBudget: RemoteThumbnailTrafficBudget,
    private val ownerJob: Job? = null,
    private val limits: SparseVideoRangeLimits = SparseVideoRangeLimits(),
    private val fetch: suspend (offset: Long, length: Int) -> ByteArray,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val sessionJob = SupervisorJob(ownerJob)
    private val mutex = Mutex()
    private val blocks = LinkedHashMap<Long, ByteArray>(16, .75f, true)
    @Volatile private var requestCount = 0
    @Volatile private var expectedBytes = 0L
    @Volatile private var actualBytes = 0L

    init { require(itemSize > 0) { "영상 파일 크기가 필요합니다." } }

    suspend fun read(position: Long, requestedLength: Int): ByteArray = mutex.withLock {
        currentCoroutineContext().ensureActive()
        sessionJob.ensureActive()
        if (closed.get()) throw CancellationException("영상 범위 소스가 닫혔습니다.")
        if (position < 0 || requestedLength < 0) throw IOException("영상 seek 범위가 올바르지 않습니다.")
        if (requestedLength == 0 || position >= itemSize) return@withLock ByteArray(0)

        blocks.entries.firstOrNull { (start, bytes) ->
            position >= start && position < start + bytes.size
        }?.let { (start, bytes) ->
            val offset = (position - start).toInt()
            val count = minOf(requestedLength, bytes.size - offset, (itemSize - position).toInt())
            if (count > 0) return@withLock bytes.copyOfRange(offset, offset + count)
        }

        if (requestCount >= limits.maxRequests || expectedBytes >= limits.maxExpectedBytes ||
            actualBytes >= limits.maxActualBytes
        ) throw RemoteThumbnailTrafficLimitReached()

        // Read ahead once and serve MediaMetadataRetriever's overlapping tiny reads from
        // memory. Without this, a single MOV can issue hundreds of network Range calls.
        val readAhead = minOf(RANGE_READ_AHEAD_BYTES, limits.maxRequestBytes)
        val boundedLength = minOf(
            maxOf(requestedLength, readAhead).toLong(),
            limits.maxRequestBytes.toLong(),
            itemSize - position,
            limits.maxExpectedBytes - expectedBytes,
        ).toInt()
        if (boundedLength <= 0) throw RemoteThumbnailTrafficLimitReached()
        val ticket = trafficBudget.reserve(boundedLength.toLong())
            ?: throw RemoteThumbnailTrafficLimitReached()
        requestCount += 1
        expectedBytes += boundedLength

        val bytes = fetch(position, boundedLength)
        currentCoroutineContext().ensureActive()
        sessionJob.ensureActive()
        val globallyAllowed = trafficBudget.recordActual(ticket, bytes.size.toLong())
        actualBytes += bytes.size
        if (!globallyAllowed || bytes.size > boundedLength || actualBytes > limits.maxActualBytes) {
            throw RemoteThumbnailTrafficLimitReached()
        }
        if (bytes.isNotEmpty()) {
            blocks[position] = bytes
            while (blocks.size > MAX_RANGE_BLOCKS) blocks.remove(blocks.entries.first().key)
        }
        bytes.copyOfRange(0, minOf(requestedLength, bytes.size))
    }

    fun readBlocking(position: Long, requestedLength: Int): ByteArray =
        runBlocking(sessionJob.takeIf { it.isActive } ?: EmptyCoroutineContext) {
            read(position, requestedLength)
        }

    fun snapshot(): SparseVideoRangeSnapshot = synchronized(this) {
        SparseVideoRangeSnapshot(requestCount, expectedBytes, actualBytes, closed.get())
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) sessionJob.cancel()
    }

    private companion object {
        const val RANGE_READ_AHEAD_BYTES = 64 * 1024
        const val MAX_RANGE_BLOCKS = 32
    }
}

private class SparseRemoteMediaDataSource(
    private val session: SparseVideoRangeSession,
) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (offset < 0 || size < 0 || offset > buffer.size - size) {
            throw IOException("MediaDataSource 버퍼 범위가 올바르지 않습니다.")
        }
        if (size == 0) return 0
        val bytes = session.readBlocking(position, size)
        if (bytes.isEmpty()) return -1
        bytes.copyInto(buffer, destinationOffset = offset)
        return bytes.size
    }

    override fun getSize(): Long = session.itemSize

    override fun close() = session.close()
}

data class RemoteThumbnailTrafficLimits(
    // Bytes remain the primary safety boundary; MOV/MP4 metadata parsing legitimately
    // uses many tiny Range reads even though the transferred payload stays small.
    val maxRequests: Int = 1_024,
    val maxExpectedBytes: Long = 64L * 1024 * 1024,
    val maxActualBytes: Long = 64L * 1024 * 1024,
) {
    init {
        require(maxRequests > 0 && maxExpectedBytes > 0 && maxActualBytes > 0)
    }
}

data class RemoteThumbnailTrafficSnapshot(
    val session: Long = 0,
    val requestCount: Int = 0,
    val expectedBytes: Long = 0,
    val actualBytes: Long = 0,
    val limitReached: Boolean = false,
)

internal data class RemoteThumbnailTrafficTicket(val session: Long)

internal class RemoteThumbnailTrafficBudget(private val limits: RemoteThumbnailTrafficLimits) {
    val snapshot = MutableStateFlow(RemoteThumbnailTrafficSnapshot())

    @Synchronized fun reserve(expectedBytes: Long): RemoteThumbnailTrafficTicket? {
        val current = snapshot.value
        val expected = expectedBytes.coerceAtLeast(0)
        if (current.limitReached || current.requestCount >= limits.maxRequests ||
            expected > limits.maxExpectedBytes - current.expectedBytes
        ) {
            snapshot.value = current.copy(limitReached = true)
            return null
        }
        val updated = current.copy(
            requestCount = current.requestCount + 1,
            expectedBytes = current.expectedBytes + expected,
        ).withLimit(limits)
        snapshot.value = updated
        return RemoteThumbnailTrafficTicket(updated.session)
    }

    @Synchronized fun recordActual(ticket: RemoteThumbnailTrafficTicket, bytes: Long): Boolean {
        require(bytes >= 0)
        val current = snapshot.value
        if (ticket.session != current.session) return false
        val updated = current.copy(actualBytes = current.actualBytes + bytes).withLimit(limits)
        snapshot.value = updated
        return updated.actualBytes <= limits.maxActualBytes
    }

    @Synchronized fun reset() {
        snapshot.value = RemoteThumbnailTrafficSnapshot(session = snapshot.value.session + 1)
    }

    private fun RemoteThumbnailTrafficSnapshot.withLimit(limits: RemoteThumbnailTrafficLimits): RemoteThumbnailTrafficSnapshot =
        copy(
            limitReached = requestCount >= limits.maxRequests || expectedBytes >= limits.maxExpectedBytes ||
                actualBytes >= limits.maxActualBytes,
        )
}

private class RemoteThumbnailTrafficLimitReached : IOException("썸네일 네트워크 예산에 도달했습니다.")

internal enum class RemoteThumbnailSource { NONE, SERVER_THUMBNAIL, ORIGINAL_IMAGE, SPARSE_VIDEO }

internal object RemoteThumbnailFetchPolicy {
    fun source(item: RemoteFileItem, supportsRangeStreaming: Boolean = false): RemoteThumbnailSource = when {
        item.isDirectory -> RemoteThumbnailSource.NONE
        item.isImage && item.thumbnailUrl != null -> RemoteThumbnailSource.SERVER_THUMBNAIL
        item.isImage -> RemoteThumbnailSource.ORIGINAL_IMAGE
        item.isVideo && item.thumbnailUrl != null -> RemoteThumbnailSource.SERVER_THUMBNAIL
        item.isVideo && supportsRangeStreaming && item.size > 0 -> RemoteThumbnailSource.SPARSE_VIDEO
        else -> RemoteThumbnailSource.NONE
    }

    fun fallbackAfterServerFailure(
        item: RemoteFileItem,
        supportsRangeStreaming: Boolean,
    ): RemoteThumbnailSource = when {
        item.isDirectory -> RemoteThumbnailSource.NONE
        item.isVideo && supportsRangeStreaming && item.size > 0 -> RemoteThumbnailSource.SPARSE_VIDEO
        item.isImage -> RemoteThumbnailSource.ORIGINAL_IMAGE
        else -> RemoteThumbnailSource.NONE
    }
}

internal object RemoteThumbnailPayloadPolicy {
    fun isErrorContentType(value: String): Boolean =
        value.lowercase().let { it.contains("json") || it.startsWith("text/") }

    fun looksLikeErrorPrefix(value: String): Boolean {
        val prefix = value.trimStart()
        return prefix.startsWith("{") || prefix.startsWith("[") ||
            prefix.startsWith("<!DOCTYPE", ignoreCase = true) || prefix.startsWith("<html", ignoreCase = true)
    }

    fun isVaultJpeg(data: ByteArray): Boolean = data.size in 4..MAX_VAULT_JPEG_BYTES &&
        data[0] == 0xff.toByte() && data[1] == 0xd8.toByte() &&
        data[data.lastIndex - 1] == 0xff.toByte() && data[data.lastIndex] == 0xd9.toByte()

    private const val MAX_VAULT_JPEG_BYTES = 8 * 1024 * 1024
}

internal object RemoteThumbnailCacheKey {
    fun create(connectionId: String, item: RemoteFileItem, maxPixelSize: Int): String {
        val value = listOf(
            connectionId,
            item.path,
            item.size.toString(),
            (item.modifiedAt?.toEpochMilli() ?: 0L).toString(),
            maxPixelSize.toString(),
        ).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

internal class WeightedLruCache<K : Any, V : Any>(
    private val maxWeight: Long,
    private val weigh: (V) -> Long,
) {
    private val values = LinkedHashMap<K, V>(16, 0.75f, true)
    private var weight = 0L

    init { require(maxWeight >= 0) }

    @Synchronized operator fun get(key: K): V? = values[key]

    @Synchronized fun put(key: K, value: V) {
        val valueWeight = weigh(value).coerceAtLeast(0)
        values.put(key, value)?.let { weight -= weigh(it).coerceAtLeast(0) }
        weight += valueWeight
        val iterator = values.entries.iterator()
        while (weight > maxWeight && iterator.hasNext()) {
            val entry = iterator.next()
            weight -= weigh(entry.value).coerceAtLeast(0)
            iterator.remove()
        }
    }

    @Synchronized fun clear() {
        values.clear()
        weight = 0
    }

    @Synchronized internal fun keys(): List<K> = values.keys.toList()
}

internal class ThumbnailDiskCache(
    root: File,
    private var maxBytes: Long,
    private val maxAgeMillis: Long,
    private val maxEntries: Int = Int.MAX_VALUE,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val root = root.apply { check(mkdirs() || isDirectory) }.canonicalFile

    init {
        require(maxBytes >= 0 && maxAgeMillis >= 0 && maxEntries >= 0)
        this.root.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".tmp") }.forEach(File::delete)
        trim()
    }

    @Synchronized fun get(key: String): File? {
        val file = cacheFile(key)
        if (!file.isFile) return null
        if (now() - file.lastModified() > maxAgeMillis) {
            file.delete()
            return null
        }
        file.setLastModified(now())
        return file
    }

    @Synchronized fun commit(key: String, temporary: File) {
        require(temporary.canonicalFile.parentFile == root) { "안전하지 않은 썸네일 임시파일입니다." }
        val target = cacheFile(key)
        try {
            Files.move(
                temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        target.setLastModified(now())
        if (target.length() > maxBytes) {
            target.delete()
            return
        }
        trim(excluding = target)
    }

    @Synchronized fun remove(key: String) {
        cacheFile(key).delete()
    }

    @Synchronized fun statistics(): RemoteThumbnailCacheStatistics {
        trim()
        val entries = root.listFiles().orEmpty().filter { it.isFile && it.extension == CACHE_EXTENSION }
        return RemoteThumbnailCacheStatistics(entries.size, entries.sumOf(File::length), maxBytes)
    }

    @Synchronized fun updateMaxBytes(bytes: Long) {
        require(bytes >= 0)
        maxBytes = bytes
        trim()
    }

    @Synchronized fun clear() {
        root.listFiles().orEmpty()
            .filter { it.isFile && (it.extension == CACHE_EXTENSION || it.name.endsWith(".tmp")) }
            .forEach(File::delete)
    }

    @Synchronized fun temporaryFile(purpose: String): File {
        val safePurpose = purpose.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(root, ".$safePurpose-${UUID.randomUUID()}.tmp")
    }

    @Synchronized internal fun trim(excluding: File? = null) {
        val current = now()
        root.listFiles().orEmpty().filter { it.isFile && it.extension == CACHE_EXTENSION }
            .filter { current - it.lastModified() > maxAgeMillis }
            .forEach(File::delete)
        val entries = root.listFiles().orEmpty().filter { it.isFile && it.extension == CACHE_EXTENSION }
            .sortedBy(File::lastModified)
        val overCount = (entries.size - maxEntries).coerceAtLeast(0)
        entries.take(overCount).forEach(File::delete)
        val retained = entries.filter(File::isFile)
        var bytes = retained.sumOf(File::length)
        for (entry in retained) {
            if (bytes <= maxBytes) break
            if (entry == excluding) continue
            val length = entry.length()
            if (entry.delete()) bytes -= length
        }
    }

    private fun cacheFile(key: String): File {
        require(key.matches(Regex("[0-9a-f]{64}"))) { "올바르지 않은 썸네일 캐시 키입니다." }
        return File(root, "$key.$CACHE_EXTENSION").canonicalFile.also {
            require(it.parentFile == root) { "안전하지 않은 썸네일 캐시 경로입니다." }
        }
    }

    private companion object { const val CACHE_EXTENSION = "thumb" }
}
