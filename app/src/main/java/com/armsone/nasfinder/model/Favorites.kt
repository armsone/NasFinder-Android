package com.armsone.nasfinder.model

import java.net.URI
import java.util.UUID

data class RemoteFavorite(
    val id: String = UUID.randomUUID().toString(),
    val connectionId: String,
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val createdAt: Long = System.currentTimeMillis(),
)

data class BrowserFavorite(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val isHomepage: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
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
