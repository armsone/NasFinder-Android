package com.armsone.nasfinder.platform

import com.armsone.nasfinder.data.RemoteThumbnailFetchPolicy
import com.armsone.nasfinder.data.RemoteThumbnailSource
import com.armsone.nasfinder.model.RemoteFileItem
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object DocumentsThumbnailPolicy {
    private const val MIN_EDGE = 64
    private const val MAX_EDGE = 1_024

    fun requestedPixelSize(width: Int, height: Int): Int =
        maxOf(width, height).coerceIn(MIN_EDGE, MAX_EDGE)

    fun supports(item: RemoteFileItem, supportsRangeStreaming: Boolean): Boolean =
        RemoteThumbnailFetchPolicy.source(item, supportsRangeStreaming) != RemoteThumbnailSource.NONE

    fun cacheKey(connectionId: String, item: RemoteFileItem, pixelSize: Int): String {
        require(connectionId.isNotBlank() && pixelSize in MIN_EDGE..MAX_EDGE)
        val identity = buildString {
            append(connectionId); append('\u0000')
            append(item.path); append('\u0000')
            append(item.size); append('\u0000')
            append(item.modifiedAt?.toEpochMilli() ?: -1L); append('\u0000')
            append(pixelSize)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
