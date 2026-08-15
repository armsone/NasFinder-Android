package com.armsone.nasfinder.data

import com.armsone.nasfinder.model.RemoteFileItem
import kotlinx.coroutines.CancellationException
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
    fun `video prefers server thumbnail then sparse range while image keeps original fallback`() {
        val video = RemoteFileItem("v", "movie.mp4", "/movie.mp4", false, size = 1_000_000)
        val image = RemoteFileItem("i", "photo.jpg", "/photo.jpg", false, size = 1_000_000)

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
}
