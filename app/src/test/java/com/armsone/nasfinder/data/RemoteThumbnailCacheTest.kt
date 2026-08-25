package com.armsone.nasfinder.data

import com.armsone.nasfinder.model.RemoteFileItem
import com.armsone.nasfinder.network.RemoteFileService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Instant

class RemoteThumbnailCacheTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `key is stable and changes at every content identity boundary`() {
        val item = RemoteFileItem(
            id = "server-id",
            name = "photo.jpg",
            path = "/photos/photo.jpg",
            isDirectory = false,
            size = 42,
            modifiedAt = Instant.parse("2026-08-14T12:00:00Z"),
        )
        val original = RemoteThumbnailCacheKey.create("connection-a", item, 1024)

        assertEquals(original, RemoteThumbnailCacheKey.create("connection-a", item.copy(id = "different-id"), 1024))
        assertNotEquals(original, RemoteThumbnailCacheKey.create("connection-b", item, 1024))
        assertNotEquals(original, RemoteThumbnailCacheKey.create("connection-a", item.copy(path = "/other.jpg"), 1024))
        assertNotEquals(original, RemoteThumbnailCacheKey.create("connection-a", item.copy(size = 43), 1024))
        assertNotEquals(
            original,
            RemoteThumbnailCacheKey.create("connection-a", item.copy(modifiedAt = item.modifiedAt?.plusSeconds(1)), 1024),
        )
        assertNotEquals(original, RemoteThumbnailCacheKey.create("connection-a", item, 512))
        assertTrue(original.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `weighted memory LRU evicts least recently used entry`() {
        val cache = WeightedLruCache<String, String>(5) { it.length.toLong() }
        cache.put("a", "aaa")
        cache.put("b", "bb")
        assertEquals("aaa", cache["a"])

        cache.put("c", "cc")

        assertEquals(listOf("a", "c"), cache.keys())
        assertNull(cache["b"])
    }

    @Test
    fun `disk cache atomically commits and evicts oldest bytes`() {
        var clock = 1_000L
        val cache = ThumbnailDiskCache(temporary.newFolder("disk"), 5, 10_000) { clock }
        val firstKey = "1".repeat(64)
        val secondKey = "2".repeat(64)
        cache.commit(firstKey, cache.temporaryFile("first").apply { writeBytes(byteArrayOf(1, 2, 3)) })
        clock += 1
        cache.commit(secondKey, cache.temporaryFile("second").apply { writeBytes(byteArrayOf(4, 5, 6)) })

        assertNull(cache.get(firstKey))
        assertEquals(3L, cache.get(secondKey)?.length())
        assertTrue(temporary.root.walkTopDown().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `disk cache expires stale entry and rejects unsafe key`() {
        var clock = 100L
        val cache = ThumbnailDiskCache(temporary.newFolder("expiry"), 100, 10) { clock }
        val key = "a".repeat(64)
        cache.commit(key, cache.temporaryFile("entry").apply { writeText("x") })
        assertTrue(cache.get(key)?.isFile == true)

        clock += 11
        assertNull(cache.get(key))
        assertFalse(temporary.root.walkTopDown().any { it.name == "$key.thumb" })
        try {
            cache.get("../outside")
            throw AssertionError("unsafe key must fail")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun `disk cache enforces file count and reports limit changes`() {
        var clock = 1_000L
        val cache = ThumbnailDiskCache(
            root = temporary.newFolder("entry-count"),
            maxBytes = 100,
            maxAgeMillis = 10_000,
            maxEntries = 2,
            now = { clock },
        )
        repeat(3) { index ->
            clock += 1
            cache.commit("${index + 1}".repeat(64), cache.temporaryFile("entry-$index").apply { writeText("x") })
        }

        assertEquals(2, cache.statistics().fileCount)
        cache.updateMaxBytes(1)
        assertEquals(1L, cache.statistics().automaticLimitBytes)
        cache.clear()
        assertEquals(RemoteThumbnailCacheStatistics(0, 0, 1), cache.statistics())
    }

    @Test
    fun `traffic budget reports expected and actual bytes and resets sessions`() {
        val budget = RemoteThumbnailTrafficBudget(
            RemoteThumbnailTrafficLimits(maxRequests = 2, maxExpectedBytes = 10, maxActualBytes = 5),
        )
        val first = budget.reserve(4)!!
        assertTrue(budget.recordActual(first, 3))
        val second = budget.reserve(6)!!

        assertEquals(2, budget.snapshot.value.requestCount)
        assertEquals(10L, budget.snapshot.value.expectedBytes)
        assertEquals(3L, budget.snapshot.value.actualBytes)
        assertTrue(budget.snapshot.value.limitReached)
        assertNull(budget.reserve(0))
        assertTrue(budget.recordActual(second, 2))

        budget.reset()
        assertEquals(1, budget.snapshot.value.session)
        assertEquals(0, budget.snapshot.value.requestCount)
        assertFalse(budget.recordActual(first, 1))
    }

    @Test
    fun `vault download reserves expected bytes and records successful transfer`() = runBlocking {
        val payload = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
        val budget = RemoteThumbnailTrafficBudget(
            RemoteThumbnailTrafficLimits(maxRequests = 2, maxExpectedBytes = 16, maxActualBytes = 16),
        )
        val destination = temporary.newFile("vault-success.jpg")

        assertTrue(
            RemoteThumbnailVaultDownload.download(
                item = RemoteFileItem("vault", "stored.jpg", "/vault/stored.jpg", false, size = payload.size.toLong()),
                destination = destination,
                service = vaultDownloadService(payload),
                trafficBudget = budget,
                maxBytes = 8,
            )
        )
        assertEquals(1, budget.snapshot.value.requestCount)
        assertEquals(payload.size.toLong(), budget.snapshot.value.expectedBytes)
        assertEquals(payload.size.toLong(), budget.snapshot.value.actualBytes)
    }

    @Test
    fun `vault download accounts partial bytes on failure and cancellation`() {
        val failedBudget = RemoteThumbnailTrafficBudget(
            RemoteThumbnailTrafficLimits(maxRequests = 2, maxExpectedBytes = 16, maxActualBytes = 16),
        )
        assertThrows(IOException::class.java) {
            runBlocking {
                RemoteThumbnailVaultDownload.download(
                    item = RemoteFileItem("failed", "failed.jpg", "/vault/failed.jpg", false, size = 4),
                    destination = temporary.newFile("vault-failed.jpg"),
                    service = vaultDownloadService(byteArrayOf(1, 2), IOException("network failed")),
                    trafficBudget = failedBudget,
                    maxBytes = 8,
                )
            }
        }
        assertEquals(4L, failedBudget.snapshot.value.expectedBytes)
        assertEquals(2L, failedBudget.snapshot.value.actualBytes)

        val cancelledBudget = RemoteThumbnailTrafficBudget(
            RemoteThumbnailTrafficLimits(maxRequests = 2, maxExpectedBytes = 16, maxActualBytes = 16),
        )
        assertThrows(CancellationException::class.java) {
            runBlocking {
                RemoteThumbnailVaultDownload.download(
                    item = RemoteFileItem("cancelled", "cancelled.jpg", "/vault/cancelled.jpg", false, size = 4),
                    destination = temporary.newFile("vault-cancelled.jpg"),
                    service = vaultDownloadService(byteArrayOf(1, 2), CancellationException("cancelled")),
                    trafficBudget = cancelledBudget,
                    maxBytes = 8,
                )
            }
        }
        assertEquals(4L, cancelledBudget.snapshot.value.expectedBytes)
        assertEquals(2L, cancelledBudget.snapshot.value.actualBytes)
    }

    @Test
    fun `vault download stops at traffic and payload boundaries`() {
        val trafficBudget = RemoteThumbnailTrafficBudget(
            RemoteThumbnailTrafficLimits(maxRequests = 2, maxExpectedBytes = 16, maxActualBytes = 3),
        )
        assertThrows(RemoteThumbnailTrafficLimitReached::class.java) {
            runBlocking {
                RemoteThumbnailVaultDownload.download(
                    item = RemoteFileItem("limited", "limited.jpg", "/vault/limited.jpg", false, size = 4),
                    destination = temporary.newFile("vault-limited.jpg"),
                    service = vaultDownloadService(byteArrayOf(1, 2, 3, 4)),
                    trafficBudget = trafficBudget,
                    maxBytes = 8,
                )
            }
        }
        assertEquals(4L, trafficBudget.snapshot.value.actualBytes)
        assertTrue(trafficBudget.snapshot.value.limitReached)

        val payloadBudget = RemoteThumbnailTrafficBudget(
            RemoteThumbnailTrafficLimits(maxRequests = 2, maxExpectedBytes = 16, maxActualBytes = 16),
        )
        assertFalse(
            runBlocking {
                RemoteThumbnailVaultDownload.download(
                    item = RemoteFileItem("oversize", "oversize.jpg", "/vault/oversize.jpg", false, size = 9),
                    destination = temporary.newFile("vault-oversize.jpg"),
                    service = vaultDownloadService(ByteArray(9)),
                    trafficBudget = payloadBudget,
                    maxBytes = 8,
                )
            }
        )
        assertEquals(0, payloadBudget.snapshot.value.requestCount)
        assertEquals(0L, payloadBudget.snapshot.value.actualBytes)
    }

    @Test
    fun `video prefers server thumbnail then sparse range while image keeps original fallback`() {
        val video = RemoteFileItem("v", "movie.mp4", "/movie.mp4", false, size = 1_000_000)
        val image = RemoteFileItem("i", "photo.jpg", "/photo.jpg", false, size = 1_000_000)
        val folder = RemoteFileItem("f", "photos", "/photos", true)

        assertEquals(RemoteThumbnailSource.FOLDER_VAULT, RemoteThumbnailFetchPolicy.source(folder))
        assertEquals(RemoteThumbnailSource.FOLDER_VAULT, RemoteThumbnailFetchPolicy.source(folder, true))
        assertEquals(RemoteThumbnailSource.NONE, RemoteThumbnailFetchPolicy.source(video))
        assertEquals(RemoteThumbnailSource.SPARSE_VIDEO, RemoteThumbnailFetchPolicy.source(video, true))
        assertEquals(
            RemoteThumbnailSource.SERVER_THUMBNAIL,
            RemoteThumbnailFetchPolicy.source(video.copy(thumbnailUrl = "https://nas.test/thumb"), true),
        )
        assertEquals(RemoteThumbnailSource.ORIGINAL_IMAGE, RemoteThumbnailFetchPolicy.source(image))
        assertEquals(
            RemoteThumbnailSource.SERVER_THUMBNAIL,
            RemoteThumbnailFetchPolicy.source(image.copy(thumbnailUrl = "https://nas.test/thumb")),
        )
        assertEquals(
            RemoteThumbnailSource.SPARSE_VIDEO,
            RemoteThumbnailFetchPolicy.fallbackAfterServerFailure(video, supportsRangeStreaming = true),
        )
        assertEquals(
            RemoteThumbnailSource.NONE,
            RemoteThumbnailFetchPolicy.fallbackAfterServerFailure(video, supportsRangeStreaming = false),
        )
        assertEquals(
            RemoteThumbnailSource.ORIGINAL_IMAGE,
            RemoteThumbnailFetchPolicy.fallbackAfterServerFailure(image, supportsRangeStreaming = false),
        )
        assertTrue(RemoteThumbnailPayloadPolicy.isErrorContentType("application/json; charset=utf-8"))
        assertTrue(RemoteThumbnailPayloadPolicy.looksLikeErrorPrefix("  {\"success\":false}"))
        assertFalse(RemoteThumbnailPayloadPolicy.isErrorContentType("image/jpeg"))
        assertFalse(RemoteThumbnailPayloadPolicy.looksLikeErrorPrefix("\u00ff\u00d8jpeg"))
        assertTrue(RemoteThumbnailPayloadPolicy.isVaultJpeg(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())))
        assertFalse(RemoteThumbnailPayloadPolicy.isVaultJpeg("not-jpeg".toByteArray()))
        assertFalse(RemoteThumbnailPayloadPolicy.isVaultJpeg(ByteArray(8 * 1024 * 1024 + 1)))
    }

    @Test
    fun `sparse video range session clamps seeks to item and per request bounds`() = runBlocking {
        val traffic = RemoteThumbnailTrafficBudget(
            RemoteThumbnailTrafficLimits(maxRequests = 10, maxExpectedBytes = 100, maxActualBytes = 100),
        )
        val calls = mutableListOf<Pair<Long, Int>>()
        val session = SparseVideoRangeSession(
            itemSize = 6,
            trafficBudget = traffic,
            limits = SparseVideoRangeLimits(
                maxRequests = 4, maxExpectedBytes = 10, maxActualBytes = 10, maxRequestBytes = 3,
            ),
        ) { offset, length ->
            calls += offset to length
            ByteArray(length) { index -> (offset + index).toByte() }
        }

        assertArrayEquals(byteArrayOf(1, 2, 3), session.read(1, 8))
        assertArrayEquals(byteArrayOf(4, 5), session.read(4, 8))
        assertArrayEquals(ByteArray(0), session.read(6, 8))
        assertEquals(listOf(1L to 3, 4L to 2), calls)
        assertEquals(SparseVideoRangeSnapshot(2, 5, 5, false), session.snapshot())
        assertEquals(2, traffic.snapshot.value.requestCount)
        assertEquals(5L, traffic.snapshot.value.actualBytes)

        session.close()
        assertThrows(CancellationException::class.java) { runBlocking { session.read(0, 1) } }
        Unit
    }

    @Test
    fun `sparse video range session accounts over response before rejecting it`() {
        val traffic = RemoteThumbnailTrafficBudget(
            RemoteThumbnailTrafficLimits(maxRequests = 10, maxExpectedBytes = 100, maxActualBytes = 100),
        )
        val session = SparseVideoRangeSession(
            itemSize = 100,
            trafficBudget = traffic,
            limits = SparseVideoRangeLimits(maxRequests = 4, maxExpectedBytes = 100, maxActualBytes = 100, maxRequestBytes = 8),
        ) { _, length ->
            ByteArray(length + 1)
        }

        assertThrows(IOException::class.java) { runBlocking { session.read(0, 8) } }
        assertEquals(9L, traffic.snapshot.value.actualBytes)
        session.close()
    }

    @Test
    fun `sparse video range session coalesces overlapping tiny reads`() = runBlocking {
        val traffic = RemoteThumbnailTrafficBudget(
            RemoteThumbnailTrafficLimits(maxRequests = 10, maxExpectedBytes = 200_000, maxActualBytes = 200_000),
        )
        val calls = mutableListOf<Pair<Long, Int>>()
        val session = SparseVideoRangeSession(itemSize = 200_000, trafficBudget = traffic) { offset, length ->
            calls += offset to length
            ByteArray(length) { index -> ((offset + index) and 0xff).toByte() }
        }

        assertArrayEquals(ByteArray(8) { (100 + it).toByte() }, session.read(100, 8))
        assertArrayEquals(ByteArray(8) { (104 + it).toByte() }, session.read(104, 8))
        assertEquals(listOf(100L to 64 * 1024), calls)
        assertEquals(1, traffic.snapshot.value.requestCount)
        session.close()
    }

    @Test
    fun `vault directory cache coalesces concurrent requests and caches within ttl`() = runBlocking {
        var clock = 1000L
        val listCount = java.util.concurrent.atomic.AtomicInteger(0)
        val cache = RemoteThumbnailVaultDirectoryCache(maxEntries = 10, ttlMillis = 10_000, now = { clock })

        val fakeService = object : com.armsone.nasfinder.network.RemoteFileService {
            override suspend fun testConnection() = Unit
            override suspend fun list(path: String): List<RemoteFileItem> {
                listCount.incrementAndGet()
                kotlinx.coroutines.delay(50)
                return listOf(
                    RemoteFileItem("1", "v1-thumb.jpg", "$path/v1-thumb.jpg", false, size = 100),
                    RemoteFileItem("2", "subfolder", "$path/subfolder", true),
                )
            }
            override suspend fun download(item: RemoteFileItem, destination: java.io.File, progress: (Long, Long) -> Unit) = Unit
            override suspend fun createFolder(parent: String, name: String) = Unit
            override suspend fun upload(parent: String, source: java.io.File) = Unit
            override suspend fun rename(item: RemoteFileItem, newName: String) = Unit
            override suspend fun delete(items: List<RemoteFileItem>) = Unit
            override fun close() = Unit
        }

        val deferred1 = async { cache.listVault("connection-a", "/vault", fakeService, this) }
        val deferred2 = async { cache.listVault("connection-a", "/vault", fakeService, this) }
        val results = kotlinx.coroutines.awaitAll(deferred1, deferred2)

        assertEquals(1, listCount.get())
        assertEquals(1, results[0]?.size)
        assertTrue(results[0]?.containsKey("v1-thumb.jpg") == true)
        assertEquals(results[0], results[1])

        clock += 5_000
        val cached = cache.listVault("connection-a", "/vault", fakeService, this)
        assertEquals(1, listCount.get())
        assertEquals(1, cached?.size)

        clock += 15_000
        val expired = cache.listVault("connection-a", "/vault", fakeService, this)
        assertEquals(2, listCount.get())
        assertEquals(1, expired?.size)

        cache.clear()
        val afterClear = cache.listVault("connection-a", "/vault", fakeService, this)
        assertEquals(3, listCount.get())
        assertEquals(1, afterClear?.size)
    }

    @Test
    fun `vault cache key is distinct from standard cache key and both are valid hex strings`() {
        val item = RemoteFileItem(
            id = "photo-1",
            name = "photo.jpg",
            path = "/photos/photo.jpg",
            isDirectory = false,
            size = 2048,
            modifiedAt = Instant.parse("2026-08-25T10:00:00Z"),
        )
        val standardKey = RemoteThumbnailCacheKey.create("conn-1", item, 1024)
        val vaultKey = RemoteThumbnailCacheKey.createVault("conn-1", item, 1024)

        assertNotEquals(standardKey, vaultKey)
        assertTrue(standardKey.matches(Regex("[0-9a-f]{64}")))
        assertTrue(vaultKey.matches(Regex("[0-9a-f]{64}")))
        assertEquals(standardKey, RemoteThumbnailCacheKey.create("conn-1", item, 1024, isVault = false))
        assertEquals(vaultKey, RemoteThumbnailCacheKey.create("conn-1", item, 1024, isVault = true))
    }

    @Test
    fun `vault cache key changes at every content identity boundary`() {
        val item = RemoteFileItem(
            id = "photo-1",
            name = "photo.jpg",
            path = "/photos/photo.jpg",
            isDirectory = false,
            size = 2048,
            modifiedAt = Instant.parse("2026-08-25T10:00:00Z"),
        )
        val original = RemoteThumbnailCacheKey.createVault("conn-1", item, 1024)

        assertEquals(original, RemoteThumbnailCacheKey.createVault("conn-1", item.copy(id = "different-id"), 1024))
        assertNotEquals(original, RemoteThumbnailCacheKey.createVault("conn-2", item, 1024))
        assertNotEquals(original, RemoteThumbnailCacheKey.createVault("conn-1", item.copy(path = "/other.jpg"), 1024))
        assertNotEquals(original, RemoteThumbnailCacheKey.createVault("conn-1", item.copy(size = 2049), 1024))
        assertNotEquals(
            original,
            RemoteThumbnailCacheKey.createVault("conn-1", item.copy(modifiedAt = item.modifiedAt?.plusSeconds(1)), 1024),
        )
        assertNotEquals(original, RemoteThumbnailCacheKey.createVault("conn-1", item, 512))
    }

    @Test
    fun `priority policy selects warm vault then nas vault restore before warm standard`() {
        // 1. Warm Vault in memory/disk -> WARM_VAULT
        assertEquals(
            RemoteThumbnailResolutionStage.WARM_VAULT,
            RemoteThumbnailPriorityPolicy.evaluateStage(
                hasWarmVault = true,
                hasVaultOnNas = true,
                hasWarmStandard = true,
            ),
        )
        assertEquals(
            RemoteThumbnailResolutionStage.WARM_VAULT,
            RemoteThumbnailPriorityPolicy.evaluateStage(
                hasWarmVault = true,
                hasVaultOnNas = false,
                hasWarmStandard = false,
            ),
        )

        // 2. NAS Vault Super Thumbnail exists even when standard thumbnail is warm -> NAS_VAULT_RESTORE
        assertEquals(
            RemoteThumbnailResolutionStage.NAS_VAULT_RESTORE,
            RemoteThumbnailPriorityPolicy.evaluateStage(
                hasWarmVault = false,
                hasVaultOnNas = true,
                hasWarmStandard = true,
            ),
        )

        // 3. Vault miss when standard thumbnail is warm -> WARM_STANDARD fallback
        assertEquals(
            RemoteThumbnailResolutionStage.WARM_STANDARD,
            RemoteThumbnailPriorityPolicy.evaluateStage(
                hasWarmVault = false,
                hasVaultOnNas = false,
                hasWarmStandard = true,
            ),
        )

        // 4. Cold cache and Vault miss -> STANDARD_FETCH
        assertEquals(
            RemoteThumbnailResolutionStage.STANDARD_FETCH,
            RemoteThumbnailPriorityPolicy.evaluateStage(
                hasWarmVault = false,
                hasVaultOnNas = false,
                hasWarmStandard = false,
            ),
        )
    }

    @Test
    fun `disk cache commits vault and standard keys independently without collision`() {
        val cache = ThumbnailDiskCache(temporary.newFolder("vault-standard-disk"), 100, 10_000)
        val item = RemoteFileItem(
            id = "photo-1",
            name = "photo.jpg",
            path = "/photos/photo.jpg",
            isDirectory = false,
            size = 100,
            modifiedAt = Instant.parse("2026-08-25T10:00:00Z"),
        )
        val standardKey = RemoteThumbnailCacheKey.create("conn-1", item, 1024)
        val vaultKey = RemoteThumbnailCacheKey.createVault("conn-1", item, 1024)

        cache.commit(standardKey, cache.temporaryFile("std").apply { writeBytes(byteArrayOf(1, 2)) })
        assertTrue(cache.contains(standardKey))
        assertFalse(cache.contains(vaultKey))

        cache.commit(vaultKey, cache.temporaryFile("vlt").apply { writeBytes(byteArrayOf(3, 4, 5)) })
        assertTrue(cache.contains(standardKey))
        assertTrue(cache.contains(vaultKey))
        assertEquals(2L, cache.get(standardKey)?.length())
        assertEquals(3L, cache.get(vaultKey)?.length())

        // Tracked removal removes both when requested
        val removal = cache.removeTracked(setOf(standardKey, vaultKey))
        assertEquals(2, removal.removedFileCount)
        assertEquals(5L, removal.removedBytes)
        assertFalse(cache.contains(standardKey))
        assertFalse(cache.contains(vaultKey))
    }

    private fun vaultDownloadService(payload: ByteArray, failure: Throwable? = null): RemoteFileService =
        object : RemoteFileService {
            override suspend fun testConnection() = Unit
            override suspend fun list(path: String): List<RemoteFileItem> = emptyList()
            override suspend fun download(
                item: RemoteFileItem,
                destination: java.io.File,
                progress: (Long, Long) -> Unit,
            ) {
                destination.writeBytes(payload)
                progress(payload.size.toLong(), item.size)
                if (failure != null) throw failure
            }
            override suspend fun createFolder(parent: String, name: String) = Unit
            override suspend fun upload(parent: String, source: java.io.File) = Unit
            override suspend fun rename(item: RemoteFileItem, newName: String) = Unit
            override suspend fun delete(items: List<RemoteFileItem>) = Unit
            override fun close() = Unit
        }
}
