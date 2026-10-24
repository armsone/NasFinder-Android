package com.armsone.nasfinder.model

import java.net.URI
import java.time.Instant
import java.util.UUID

data class RemoteFavorite(
    val id: String = UUID.randomUUID().toString(),
    val connectionId: String,
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val createdAt: Long = System.currentTimeMillis(),
    val remoteItemId: String? = null,
    val size: Long? = null,
    val modifiedAt: Instant? = null,
    val mimeType: String? = null,
    val thumbnailUrl: String? = null,
    /** 비공개 즐겨찾기(기본 false). 같은 대상당 레코드는 하나이며 플래그만 바뀐다. */
    val isPrivate: Boolean = false,
) {
    fun toRemoteFileItem(): RemoteFileItem = RemoteFileItem(
        id = remoteItemId?.takeIf { it.isNotBlank() } ?: path,
        name = name,
        path = path,
        isDirectory = isDirectory,
        size = size ?: 0L,
        modifiedAt = modifiedAt,
        mimeType = mimeType,
        thumbnailUrl = thumbnailUrl,
    )
}

data class BrowserFavorite(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val isHomepage: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    /** 비공개 웹 즐겨찾기(기본 false). 비공개 항목은 홈페이지가 될 수 없다. */
    val isPrivate: Boolean = false,
)

object BrowserUrlPolicy {
    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val explicitScheme = SCHEME.find(trimmed)?.groupValues?.get(1)?.lowercase()
        if (explicitScheme != null && explicitScheme !in setOf("http", "https")) return null
        val candidate = if (explicitScheme != null) trimmed else "https://$trimmed"
        return runCatching {
            val uri = URI(candidate)
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) null
            else uri.normalize().toASCIIString()
        }.getOrNull()
    }

    fun canOpenInsideApp(url: String): Boolean = normalize(url) != null

    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
}
