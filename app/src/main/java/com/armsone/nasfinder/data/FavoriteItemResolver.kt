package com.armsone.nasfinder.data

import com.armsone.nasfinder.model.RemoteConnection
import com.armsone.nasfinder.model.RemoteFavorite
import com.armsone.nasfinder.model.RemoteFileItem
import com.armsone.nasfinder.network.RemoteFileService
import com.armsone.nasfinder.ui.BuiltInPreviewPolicy
import java.io.IOException

object FavoriteItemResolver {

    fun parentDirectory(path: String, root: String = "/"): String {
        val normalized = path.trimEnd('/')
        val parent = normalized.substringBeforeLast('/', "")
        return when {
            parent.isEmpty() -> if (path.startsWith('/')) "/" else root
            else -> parent
        }
    }

    suspend fun resolve(
        favorite: RemoteFavorite,
        connection: RemoteConnection,
        activeService: RemoteFileService,
    ): RemoteFileItem {
        if (favorite.isDirectory) {
            val root = connection.normalizedRootPath
            if (favorite.path == root || favorite.path == "/" || favorite.path == ".") {
                return RemoteFileItem(
                    id = favorite.remoteItemId?.takeIf { it.isNotBlank() } ?: favorite.path,
                    name = favorite.name,
                    path = favorite.path,
                    isDirectory = true,
                )
            }
            val parent = parentDirectory(favorite.path, root)
            val parentItems = runCatching { activeService.list(parent) }.getOrNull()
            if (parentItems != null) {
                val matched = matchItem(favorite, parentItems)
                if (matched != null) return matched
            }
            return RemoteFileItem(
                id = favorite.remoteItemId?.takeIf { it.isNotBlank() } ?: favorite.path,
                name = favorite.name,
                path = favorite.path,
                isDirectory = true,
            )
        }

        val parent = parentDirectory(favorite.path, connection.normalizedRootPath)
        val items = try {
            activeService.list(parent)
        } catch (error: Throwable) {
            throw IOException("즐겨찾기 상위 폴더 ‘$parent’을(를) 읽지 못했습니다: ${error.message ?: "연결 오류"}", error)
        }

        return matchItem(favorite, items)
            ?: throw NoSuchElementException("즐겨찾기 파일 ‘${favorite.name}’을(를) 찾을 수 없습니다. 파일이 이동되었거나 삭제되었을 수 있습니다.")
    }

    private fun matchItem(favorite: RemoteFavorite, items: List<RemoteFileItem>): RemoteFileItem? {
        val targetPath = favorite.path.trimEnd('/')
        items.firstOrNull { it.path.trimEnd('/') == targetPath && it.isDirectory == favorite.isDirectory }?.let { return it }

        if (!favorite.remoteItemId.isNullOrBlank()) {
            items.firstOrNull { it.id == favorite.remoteItemId && it.isDirectory == favorite.isDirectory }?.let { return it }
        }

        items.firstOrNull { it.name == favorite.name && it.isDirectory == favorite.isDirectory }?.let { return it }

        return null
    }

    fun hasMetadataChanged(favorite: RemoteFavorite, item: RemoteFileItem): Boolean {
        if (favorite.path != item.path) return true
        if (favorite.name != item.name) return true
        if (favorite.isDirectory != item.isDirectory) return true
        if (favorite.remoteItemId != item.id && (favorite.remoteItemId != null || item.id != item.path)) return true
        if (favorite.size != item.size) return true
        if (favorite.modifiedAt != item.modifiedAt) return true
        if (favorite.mimeType != item.mimeType) return true
        if (favorite.thumbnailUrl != item.thumbnailUrl) return true
        return false
    }

    fun buildFavoritePreviewSequence(
        activeFavorite: RemoteFavorite,
        resolvedActiveItem: RemoteFileItem,
        allFavorites: List<RemoteFavorite>,
    ): List<RemoteFileItem> {
        val sameConnectionFavorites = allFavorites.filter {
            it.connectionId == activeFavorite.connectionId && !it.isDirectory
        }
        return sameConnectionFavorites.map { favorite ->
            if (favorite.id == activeFavorite.id) {
                resolvedActiveItem
            } else {
                favorite.toRemoteFileItem()
            }
        }.filter { BuiltInPreviewPolicy.kind(it.name, it.mimeType) != null }
    }

    suspend fun resolveFavoritePreviewSequence(
        activeFavorite: RemoteFavorite,
        resolvedActiveItem: RemoteFileItem,
        allFavorites: List<RemoteFavorite>,
        connection: RemoteConnection,
        activeService: RemoteFileService,
    ): List<RemoteFileItem> {
        val listings = mutableMapOf<String, List<RemoteFileItem>?>()
        val resolved = mutableListOf<RemoteFileItem>()
        allFavorites
            .filter { it.connectionId == activeFavorite.connectionId && !it.isDirectory }
            .forEach { favorite ->
                val item = if (favorite.id == activeFavorite.id) {
                    resolvedActiveItem
                } else {
                    val parent = parentDirectory(favorite.path, connection.normalizedRootPath)
                    val items = if (listings.containsKey(parent)) {
                        listings[parent]
                    } else {
                        runCatching { activeService.list(parent) }.getOrNull().also { listings[parent] = it }
                    }
                    items?.let { matchItem(favorite, it) }
                }
                if (item != null && BuiltInPreviewPolicy.kind(item.name, item.mimeType) != null) {
                    resolved += item
                }
            }
        return resolved.ifEmpty { listOf(resolvedActiveItem) }
    }
}
