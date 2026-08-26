package com.armsone.nasfinder.data

import com.armsone.nasfinder.model.ConnectionKind
import com.armsone.nasfinder.model.RemoteConnection
import com.armsone.nasfinder.model.RemoteFavorite
import com.armsone.nasfinder.model.RemoteFileItem
import com.armsone.nasfinder.network.RemoteFileService
import com.armsone.nasfinder.ui.BuiltInPreviewKind
import com.armsone.nasfinder.ui.BuiltInPreviewPolicy
import com.armsone.nasfinder.ui.RemoteMediaRoute
import com.armsone.nasfinder.ui.RemoteMediaRoutePolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import org.json.JSONObject

class FavoriteItemResolverTest {

    private val connection = RemoteConnection(
        id = "conn-1",
        name = "My NAS",
        kind = ConnectionKind.SYNOLOGY,
        host = "nas.local",
        username = "tester",
    )

    private val cloudConnection = RemoteConnection(
        id = "conn-cloud",
        name = "OneDrive",
        kind = ConnectionKind.ONEDRIVE,
        host = "onedrive.live.com",
        username = "tester",
    )

    @Test
    fun `path backend resolves authoritative live metadata`() = runBlocking {
        val serverItem = RemoteFileItem(
            id = "/photos/family.jpg",
            name = "family.jpg",
            path = "/photos/family.jpg",
            isDirectory = false,
            size = 2_048_100L,
            modifiedAt = Instant.parse("2026-08-26T10:00:00Z"),
            mimeType = "image/jpeg",
        )
        val service = FakeFileService(mapOf("/photos" to listOf(serverItem)))

        val favorite = RemoteFavorite(
            connectionId = connection.id,
            path = "/photos/family.jpg",
            name = "family.jpg",
            isDirectory = false,
        )

        val resolved = FavoriteItemResolver.resolve(favorite, connection, service)

        assertEquals("/photos/family.jpg", resolved.id)
        assertEquals("family.jpg", resolved.name)
        assertEquals("/photos/family.jpg", resolved.path)
        assertEquals(2_048_100L, resolved.size)
        assertEquals(Instant.parse("2026-08-26T10:00:00Z"), resolved.modifiedAt)
        assertEquals("image/jpeg", resolved.mimeType)
    }

    @Test
    fun `cloud backend resolves opaque item ID for OneDrive and Google Drive`() = runBlocking {
        val cloudOpaqueId = "01BYE5RZ65NXGQ4S6V6NFK2L4G"
        val serverItem = RemoteFileItem(
            id = cloudOpaqueId,
            name = "drone_flight.mp4",
            path = "/Videos/drone_flight.mp4",
            isDirectory = false,
            size = 45_000_000L,
            modifiedAt = Instant.parse("2026-08-25T14:30:00Z"),
            mimeType = "video/mp4",
        )
        listOf(ConnectionKind.ONEDRIVE, ConnectionKind.GOOGLE_DRIVE).forEach { kind ->
            val provider = cloudConnection.copy(kind = kind, name = kind.title)
            val legacyFavorite = RemoteFavorite(
                connectionId = provider.id,
                path = "/Videos/drone_flight.mp4",
                name = "drone_flight.mp4",
                isDirectory = false,
            )
            val resolved = FavoriteItemResolver.resolve(
                legacyFavorite,
                provider,
                FakeFileService(mapOf("/Videos" to listOf(serverItem))),
            )
            assertEquals(cloudOpaqueId, resolved.id)
            assertNotEquals(legacyFavorite.path, resolved.id)
            assertEquals(45_000_000L, resolved.size)
            assertEquals("video/mp4", resolved.mimeType)
        }
    }

    @Test
    fun `legacy favorite without size or modified time populates full metadata`() = runBlocking {
        val serverItem = RemoteFileItem(
            id = "/docs/manual.pdf",
            name = "manual.pdf",
            path = "/docs/manual.pdf",
            isDirectory = false,
            size = 512_000L,
            modifiedAt = Instant.parse("2026-08-20T08:00:00Z"),
            mimeType = "application/pdf",
        )
        val service = FakeFileService(mapOf("/docs" to listOf(serverItem)))

        val legacy = RemoteFavorite(
            id = "fav-legacy",
            connectionId = connection.id,
            path = "/docs/manual.pdf",
            name = "manual.pdf",
            isDirectory = false,
            remoteItemId = null,
            size = null,
            modifiedAt = null,
            mimeType = null,
        )

        val resolved = FavoriteItemResolver.resolve(legacy, connection, service)

        assertEquals(512_000L, resolved.size)
        assertNotNull(resolved.modifiedAt)
        assertEquals("application/pdf", resolved.mimeType)
        assertTrue(resolved.isPdf)
    }

    @Test
    fun `extensionless media resolves mime type and built-in preview kind`() = runBlocking {
        val streamItem = RemoteFileItem(
            id = "/streams/live_recording",
            name = "live_recording",
            path = "/streams/live_recording",
            isDirectory = false,
            size = 12_000_000L,
            modifiedAt = Instant.parse("2026-08-26T12:00:00Z"),
            mimeType = "video/mp4; codecs=\"avc1.42E01E\"",
        )
        val service = FakeFileService(mapOf("/streams" to listOf(streamItem)))

        val favorite = RemoteFavorite(
            connectionId = connection.id,
            path = "/streams/live_recording",
            name = "live_recording",
            isDirectory = false,
        )

        val resolved = FavoriteItemResolver.resolve(favorite, connection, service)

        assertTrue(resolved.isVideo)
        assertFalse(resolved.isImage)
        assertEquals(BuiltInPreviewKind.VIDEO, BuiltInPreviewPolicy.kind(resolved.name, resolved.mimeType))
    }

    @Test
    fun `missing or deleted item throws NoSuchElementException with descriptive message`() = runBlocking {
        val service = FakeFileService(mapOf("/photos" to emptyList()))

        val favorite = RemoteFavorite(
            connectionId = connection.id,
            path = "/photos/deleted_photo.jpg",
            name = "deleted_photo.jpg",
            isDirectory = false,
        )

        val exception = assertThrows(NoSuchElementException::class.java) {
            runBlocking {
                FavoriteItemResolver.resolve(favorite, connection, service)
            }
        }

        assertTrue(exception.message?.contains("deleted_photo.jpg") == true)
        assertTrue(exception.message?.contains("삭제") == true || exception.message?.contains("찾을 수 없습니다") == true)
    }

    @Test
    fun `parent directory extraction handles root, nested, and relative paths`() {
        assertEquals("/", FavoriteItemResolver.parentDirectory("/photo.jpg"))
        assertEquals("/photos", FavoriteItemResolver.parentDirectory("/photos/img.png"))
        assertEquals("/photos/2026", FavoriteItemResolver.parentDirectory("/photos/2026/img.png"))
        assertEquals("/photos/2026", FavoriteItemResolver.parentDirectory("/photos/2026/img.png/"))
        assertEquals("/", FavoriteItemResolver.parentDirectory("photo.jpg", "/"))
    }

    @Test
    fun `hasMetadataChanged detects size, modified timestamp, remote item id, and name differences`() {
        val favorite = RemoteFavorite(
            id = "fav-1",
            connectionId = "c1",
            path = "/p.jpg",
            name = "p.jpg",
            isDirectory = false,
            remoteItemId = "opaque-1",
            size = 100L,
            modifiedAt = Instant.parse("2026-08-20T00:00:00Z"),
            mimeType = "image/jpeg",
        )

        val identicalItem = RemoteFileItem(
            id = "opaque-1",
            name = "p.jpg",
            path = "/p.jpg",
            isDirectory = false,
            size = 100L,
            modifiedAt = Instant.parse("2026-08-20T00:00:00Z"),
            mimeType = "image/jpeg",
        )
        assertFalse(FavoriteItemResolver.hasMetadataChanged(favorite, identicalItem))

        val sizeChanged = identicalItem.copy(size = 200L)
        assertTrue(FavoriteItemResolver.hasMetadataChanged(favorite, sizeChanged))

        val modifiedChanged = identicalItem.copy(modifiedAt = Instant.parse("2026-08-26T00:00:00Z"))
        assertTrue(FavoriteItemResolver.hasMetadataChanged(favorite, modifiedChanged))

        val idChanged = identicalItem.copy(id = "opaque-2")
        assertTrue(FavoriteItemResolver.hasMetadataChanged(favorite, idChanged))

        val nameChanged = identicalItem.copy(name = "p_renamed.jpg")
        assertTrue(FavoriteItemResolver.hasMetadataChanged(favorite, nameChanged))

        val canonicalPathChanged = identicalItem.copy(path = "/Photos/p.jpg")
        assertTrue(FavoriteItemResolver.hasMetadataChanged(favorite, canonicalPathChanged))
    }

    @Test
    fun `stale revision updates cache identity away from 0-0 cache key`() {
        val legacyFavorite = RemoteFavorite(
            connectionId = "c1",
            path = "/photos/test.jpg",
            name = "test.jpg",
            isDirectory = false,
            size = null,
            modifiedAt = null,
        )
        val legacyItem = legacyFavorite.toRemoteFileItem()
        val legacyCacheKey = RemoteThumbnailCacheKey.create("c1", legacyItem, 512)

        val authoritativeItem = RemoteFileItem(
            id = "/photos/test.jpg",
            name = "test.jpg",
            path = "/photos/test.jpg",
            isDirectory = false,
            size = 4096L,
            modifiedAt = Instant.parse("2026-08-26T15:00:00Z"),
        )
        val updatedCacheKey = RemoteThumbnailCacheKey.create("c1", authoritativeItem, 512)

        assertNotEquals(legacyCacheKey, updatedCacheKey)
        assertTrue(updatedCacheKey.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `resolved large video uses range media route when backend supports it`() = runBlocking {
        val video = RemoteFileItem(
            id = "opaque-video",
            name = "feature.mp4",
            path = "/video/feature.mp4",
            isDirectory = false,
            size = 2_000_000_000L,
            modifiedAt = Instant.parse("2026-08-26T15:10:00Z"),
            mimeType = "video/mp4",
        )
        val resolved = FavoriteItemResolver.resolve(
            RemoteFavorite(connectionId = "c1", path = video.path, name = video.name, isDirectory = false),
            connection,
            FakeFileService(mapOf("/video" to listOf(video))),
        )
        assertEquals(RemoteMediaRoute.RANGE_MEDIA3, RemoteMediaRoutePolicy.route(resolved.name, resolved.size > 0))
    }

    @Test
    fun `legacy JSON remains readable and enriched metadata round trips`() {
        val legacy = JSONObject()
            .put("id", "legacy")
            .put("connectionId", "c1")
            .put("path", "/photo.jpg")
            .put("name", "photo.jpg")
            .put("isDirectory", false)
            .put("createdAt", 123L)
        val decodedLegacy = RemoteFavoriteJsonCodec.decode(legacy)
        assertEquals(null, decodedLegacy.remoteItemId)
        assertEquals(null, decodedLegacy.size)

        val enriched = decodedLegacy.copy(
            remoteItemId = "opaque-photo",
            size = 4_096L,
            modifiedAt = Instant.parse("2026-08-26T15:12:00Z"),
            mimeType = "image/jpeg",
            thumbnailUrl = "https://example.invalid/thumb",
        )
        assertEquals(enriched, RemoteFavoriteJsonCodec.decode(RemoteFavoriteJsonCodec.encode(enriched)))
    }

    @Test
    fun `buildFavoritePreviewSequence maintains shelf order and connection isolation`() {
        val fav1 = RemoteFavorite(id = "1", connectionId = "conn-a", path = "/a1.jpg", name = "a1.jpg", isDirectory = false)
        val fav2 = RemoteFavorite(id = "2", connectionId = "conn-b", path = "/b1.jpg", name = "b1.jpg", isDirectory = false)
        val fav3 = RemoteFavorite(id = "3", connectionId = "conn-a", path = "/a2.mp4", name = "a2.mp4", isDirectory = false)
        val favFolder = RemoteFavorite(id = "4", connectionId = "conn-a", path = "/folder", name = "folder", isDirectory = true)
        val fav5 = RemoteFavorite(id = "5", connectionId = "conn-a", path = "/a3.png", name = "a3.png", isDirectory = false)

        val allFavorites = listOf(fav1, fav2, fav3, favFolder, fav5)

        val resolvedActiveItem = RemoteFileItem(
            id = "resolved-a1",
            name = "a1.jpg",
            path = "/a1.jpg",
            isDirectory = false,
            size = 9999L,
        )

        val sequence = FavoriteItemResolver.buildFavoritePreviewSequence(
            activeFavorite = fav1,
            resolvedActiveItem = resolvedActiveItem,
            allFavorites = allFavorites,
        )

        assertEquals(3, sequence.size)
        assertEquals(listOf("a1.jpg", "a2.mp4", "a3.png"), sequence.map(RemoteFileItem::name))
        assertEquals("resolved-a1", sequence[0].id)
        assertEquals(9999L, sequence[0].size)
    }

    private class FakeFileService(
        private val listings: Map<String, List<RemoteFileItem>> = emptyMap(),
    ) : RemoteFileService {
        override val supportsRangeStreaming: Boolean = true

        override suspend fun testConnection() = Unit

        override suspend fun list(path: String): List<RemoteFileItem> {
            val normalized = if (path == "/" || path == ".") path else path.trimEnd('/')
            return listings[normalized] ?: listings[path] ?: emptyList()
        }

        override suspend fun download(
            item: RemoteFileItem,
            destination: File,
            progress: (Long, Long) -> Unit,
        ) {
            destination.writeBytes(byteArrayOf(1, 2, 3))
        }

        override suspend fun readRange(item: RemoteFileItem, offset: Long, length: Int): ByteArray = byteArrayOf()
        override suspend fun createFolder(parent: String, name: String) {}
        override suspend fun rename(item: RemoteFileItem, newName: String) {}
        override suspend fun delete(items: List<RemoteFileItem>) {}
        override fun close() {}
    }
}
