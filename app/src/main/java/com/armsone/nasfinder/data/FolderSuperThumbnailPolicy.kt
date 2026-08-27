package com.armsone.nasfinder.data

import com.armsone.nasfinder.model.BrowserPreferences
import com.armsone.nasfinder.model.RemoteFileItem
import com.armsone.nasfinder.model.SortDirection
import com.armsone.nasfinder.model.SortField
import com.armsone.nasfinder.model.sortedWith
import java.security.MessageDigest
import java.text.Normalizer
import kotlin.math.abs

/** Pure policy and contract rules for Folder Super Thumbnails. */
object FolderSuperThumbnailPolicy {
    const val ENGINE_VERSION = 1
    const val SHEET_PIXEL_SIZE = 384
    const val TILE_PIXEL_SIZE = 128
    const val GRID_DIMENSION = 3
    const val MAX_CHILDREN = 9
    const val SAMPLE_GRID_SIZE = 12
    const val TOTAL_SAMPLES = SAMPLE_GRID_SIZE * SAMPLE_GRID_SIZE // 144

    // Blur ramps linearly with skin-tone share once it exceeds NO_BLUR_SHARE, reaching
    // MAX_BLUR_RADIUS_DP at 100% share. This is the only blur ever baked into a Folder
    // Super Thumbnail sheet, so the app-owned folder blur radius can never exceed this cap.
    const val NO_BLUR_SHARE = 0.20f
    const val FULL_BLUR_SHARE_RANGE = 1f - NO_BLUR_SHARE
    const val MAX_BLUR_RADIUS_DP = 2.5f
    const val REFERENCE_DP_SIZE = 192f
    val PIXELS_PER_DP: Float get() = SHEET_PIXEL_SIZE / REFERENCE_DP_SIZE
    val MAX_BLUR_RADIUS_PX: Float get() = MAX_BLUR_RADIUS_DP * PIXELS_PER_DP

    const val MISSING_FOLDER_NEGATIVE_MILLIS = 60_000L // 60s
    const val EMPTY_MARKER_NEGATIVE_MILLIS = 600_000L // 10m (600s)
    const val FILE_NEGATIVE_CACHE_MILLIS = 30_000L // 30s

    fun digest(folderName: String): String {
        val normalized = Normalizer.normalize(folderName, Normalizer.Form.NFC)
        val identity = "engine=$ENGINE_VERSION|kind=folder|name=$normalized"
        return MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun jpegFilename(folderName: String): String = "v1-folder-${digest(folderName)}.jpg"

    fun emptyMarkerFilename(folderName: String): String = "v1-folder-${digest(folderName)}.empty"

    fun leaseDirectoryName(folderName: String): String = ".claim-v1-folder-${digest(folderName)}"

    fun isSkinTone(r: Int, g: Int, b: Int): Boolean {
        val maxVal = maxOf(r, g, b)
        val minVal = minOf(r, g, b)
        return r > 70 &&
            g > 35 &&
            b > 20 &&
            r > g &&
            r > b &&
            (maxVal - minVal) > 24 &&
            abs(r - g) > 8
    }

    fun isSkinTone(color: Int): Boolean {
        val r = (color shr 16) and 0xff
        val g = (color shr 8) and 0xff
        val b = color and 0xff
        return isSkinTone(r, g, b)
    }

    fun sampleCoordinates(
        width: Int = SHEET_PIXEL_SIZE,
        height: Int = SHEET_PIXEL_SIZE,
        gridSize: Int = SAMPLE_GRID_SIZE,
    ): List<Pair<Int, Int>> {
        val cellWidth = width / gridSize
        val cellHeight = height / gridSize
        val halfW = cellWidth / 2
        val halfH = cellHeight / 2
        return buildList(gridSize * gridSize) {
            for (row in 0 until gridSize) {
                val y = row * cellHeight + halfH
                for (col in 0 until gridSize) {
                    val x = col * cellWidth + halfW
                    add(x to y)
                }
            }
        }
    }

    fun skinToneShare(skinToneCount: Int, totalSamples: Int = TOTAL_SAMPLES): Float {
        if (totalSamples <= 0) return 0f
        return skinToneCount.toFloat() / totalSamples.toFloat()
    }

    /** clamp((share - 0.20) / 0.80, 0..1) * 2.5dp */
    fun blurRadiusDp(skinToneShare: Float): Float {
        if (!skinToneShare.isFinite()) return 0f
        val normalized = ((skinToneShare - NO_BLUR_SHARE) / FULL_BLUR_SHARE_RANGE).coerceIn(0f, 1f)
        return normalized * MAX_BLUR_RADIUS_DP
    }

    fun blurRadiusDp(skinToneCount: Int, totalSamples: Int = TOTAL_SAMPLES): Float =
        blurRadiusDp(skinToneShare(skinToneCount, totalSamples))

    fun blurRadiusPx(skinToneCount: Int, totalSamples: Int = TOTAL_SAMPLES): Float =
        blurRadiusDp(skinToneCount, totalSamples) * PIXELS_PER_DP

    fun selectChildren(items: List<RemoteFileItem>): List<RemoteFileItem> {
        val visible = items.filterNot { it.name.startsWith('.') || it.name == SuperThumbnailVault.DIRECTORY_NAME }
        return visible.sortedWith(
            BrowserPreferences(
                foldersFirst = true,
                sortField = SortField.NAME,
                sortDirection = SortDirection.ASCENDING,
                showHiddenFiles = false,
            )
        ).take(MAX_CHILDREN)
    }
}
