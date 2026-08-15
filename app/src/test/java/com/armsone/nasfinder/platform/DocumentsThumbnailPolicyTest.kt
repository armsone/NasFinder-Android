package com.armsone.nasfinder.platform

import com.armsone.nasfinder.model.RemoteFileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DocumentsThumbnailPolicyTest {
    @Test fun `requested size is square bounded and defensive`() {
        assertEquals(64, DocumentsThumbnailPolicy.requestedPixelSize(0, -10))
        assertEquals(512, DocumentsThumbnailPolicy.requestedPixelSize(120, 512))
        assertEquals(1_024, DocumentsThumbnailPolicy.requestedPixelSize(8_000, 4_000))
    }

    @Test fun `thumbnail advertisement follows item and active service capability`() {
        assertTrue(DocumentsThumbnailPolicy.supports(item("photo.jpg"), supportsRangeStreaming = false))
        assertFalse(DocumentsThumbnailPolicy.supports(item("movie.mp4"), supportsRangeStreaming = false))
        assertTrue(DocumentsThumbnailPolicy.supports(item("movie.mp4"), supportsRangeStreaming = true))
        assertTrue(
            DocumentsThumbnailPolicy.supports(
                item("movie.mp4", thumbnailUrl = "https://nas.test/thumb"),
                supportsRangeStreaming = false,
            )
        )
        assertFalse(DocumentsThumbnailPolicy.supports(item("folder.jpg", directory = true), true))
        assertFalse(DocumentsThumbnailPolicy.supports(item("notes.txt"), true))
        assertFalse(DocumentsThumbnailPolicy.supports(item("empty.mp4").copy(size = 0), true))
    }

    @Test fun `cache key is opaque stable and changes with content identity`() {
        val item = item("photo.jpg").copy(
            path = "/photos/photo.jpg",
            size = 42,
            modifiedAt = Instant.parse("2026-08-14T00:00:00Z"),
        )
        val original = DocumentsThumbnailPolicy.cacheKey("connection", item, 512)
        assertEquals(original, DocumentsThumbnailPolicy.cacheKey("connection", item, 512))
        assertTrue(original.matches(Regex("[0-9a-f]{64}")))
        assertFalse(original.contains("connection") || original.contains("photo"))
        assertNotEquals(original, DocumentsThumbnailPolicy.cacheKey("connection", item.copy(size = 43), 512))
        assertNotEquals(original, DocumentsThumbnailPolicy.cacheKey("connection", item, 1_024))
    }

    private fun item(name: String, directory: Boolean = false, thumbnailUrl: String? = null) = RemoteFileItem(
        id = name,
        name = name,
        path = "/$name",
        isDirectory = directory,
        size = 1,
        thumbnailUrl = thumbnailUrl,
    )
}
