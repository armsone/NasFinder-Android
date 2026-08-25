package com.armsone.nasfinder.data

import android.graphics.Bitmap
import com.armsone.nasfinder.model.RemoteFileItem
import java.util.ArrayDeque

internal data class SuperThumbnailTraversalNode(val path: String, val depth: Int)

internal data class SuperThumbnailBudget(
    val maxItems: Int = 10_000,
    val maxDepth: Int = 24,
    val maxEstimatedBytes: Long = 256L * 1024L * 1024L,
) {
    init {
        require(maxItems > 0 && maxDepth >= 0 && maxEstimatedBytes > 0)
    }

    fun acceptsItem(visitedItems: Int): Boolean = visitedItems < maxItems
    fun acceptsDirectory(depth: Int): Boolean = depth < maxDepth
    fun acceptsBytes(consumed: Long, nextItemBytes: Long): Boolean {
        val safeNext = nextItemBytes.coerceAtLeast(1L)
        return consumed <= maxEstimatedBytes && safeNext <= maxEstimatedBytes - consumed
    }
}

internal data class SuperThumbnailRuntimeConstraints(
    val requiresUnmeteredNetwork: Boolean,
    val requiresExternalPower: Boolean,
    val requiresBatteryNotLow: Boolean,
) {
    companion object {
        fun forRun(allowsConstrainedRun: Boolean): SuperThumbnailRuntimeConstraints =
            SuperThumbnailRuntimeConstraints(
                requiresUnmeteredNetwork = !allowsConstrainedRun,
                requiresExternalPower = !allowsConstrainedRun,
                requiresBatteryNotLow = true,
            )
    }
}

internal enum class SuperThumbnailVideoFrameChoice { PRIMARY, RETRY }

internal object SuperThumbnailVideoPolicy {
    const val PRIMARY_TIME_NUMERATOR = 3L
    const val RETRY_TIME_NUMERATOR = 6L
    const val TIME_DENOMINATOR = 13L

    const val BLACK_PIXEL_THRESHOLD = 13 // round(0.05 * 255), shared with Apple grayscale policy
    const val BLACK_FRAME_THRESHOLD_PERCENT = 50
    const val DEFAULT_SAMPLE_GRID_SIZE = 32

    fun primaryTimeUs(durationUs: Long): Long {
        require(durationUs >= 0) { "영상 재생 시간은 0 이상이어야 합니다." }
        return durationUs * PRIMARY_TIME_NUMERATOR / TIME_DENOMINATOR
    }

    fun primaryTimeMs(durationMs: Long): Long {
        require(durationMs >= 0) { "영상 재생 시간은 0 이상이어야 합니다." }
        return durationMs * PRIMARY_TIME_NUMERATOR / TIME_DENOMINATOR
    }

    fun retryTimeUs(durationUs: Long): Long {
        require(durationUs >= 0) { "영상 재생 시간은 0 이상이어야 합니다." }
        return durationUs * RETRY_TIME_NUMERATOR / TIME_DENOMINATOR
    }

    fun retryTimeMs(durationMs: Long): Long {
        require(durationMs >= 0) { "영상 재생 시간은 0 이상이어야 합니다." }
        return durationMs * RETRY_TIME_NUMERATOR / TIME_DENOMINATOR
    }

    fun isBlackPixel(r: Int, g: Int, b: Int, threshold: Int = BLACK_PIXEL_THRESHOLD): Boolean {
        if (r !in 0..255 || g !in 0..255 || b !in 0..255) return false
        val grayscaleLuminance = (299L * r + 587L * g + 114L * b + 500L) / 1000L
        return grayscaleLuminance <= threshold
    }

    fun isBlackPixel(color: Int, threshold: Int = BLACK_PIXEL_THRESHOLD): Boolean {
        val r = (color shr 16) and 0xff
        val g = (color shr 8) and 0xff
        val b = color and 0xff
        return isBlackPixel(r, g, b, threshold)
    }

    fun isBlackFrame(blackPixelCount: Int, totalSamples: Int): Boolean {
        if (totalSamples <= 0) return false
        return (blackPixelCount.toLong() * 100L) >= (BLACK_FRAME_THRESHOLD_PERCENT.toLong() * totalSamples.toLong())
    }

    fun sampleCoordinates(
        width: Int,
        height: Int,
        gridCols: Int = DEFAULT_SAMPLE_GRID_SIZE,
        gridRows: Int = DEFAULT_SAMPLE_GRID_SIZE,
    ): List<Pair<Int, Int>> {
        require(width > 0 && height > 0 && gridCols > 0 && gridRows > 0)
        val cellWidth = width / gridCols
        val cellHeight = height / gridRows
        val halfW = (cellWidth / 2).coerceAtLeast(0)
        val halfH = (cellHeight / 2).coerceAtLeast(0)
        return buildList(gridCols * gridRows) {
            for (row in 0 until gridRows) {
                val y = (row * cellHeight + halfH).coerceIn(0, height - 1)
                for (col in 0 until gridCols) {
                    val x = (col * cellWidth + halfW).coerceIn(0, width - 1)
                    add(x to y)
                }
            }
        }
    }

    fun chooseFrame(
        primaryIsBlack: Boolean,
        retryExtracted: Boolean,
        retryIsBlack: Boolean,
    ): SuperThumbnailVideoFrameChoice = when {
        !primaryIsBlack -> SuperThumbnailVideoFrameChoice.PRIMARY
        !retryExtracted -> SuperThumbnailVideoFrameChoice.PRIMARY
        retryIsBlack -> SuperThumbnailVideoFrameChoice.PRIMARY
        else -> SuperThumbnailVideoFrameChoice.RETRY
    }

    fun isBlackFrame(bitmap: Bitmap, threshold: Int = BLACK_PIXEL_THRESHOLD): Boolean {
        val side = DEFAULT_SAMPLE_GRID_SIZE.coerceAtLeast(1)
        val sample = Bitmap.createScaledBitmap(bitmap, side, side, true)
        return try {
            val pixels = IntArray(side * side)
            sample.getPixels(pixels, 0, side, 0, 0, side, side)
            val blackCount = pixels.count { isBlackPixel(it, threshold) }
            isBlackFrame(blackCount, pixels.size)
        } finally {
            if (sample !== bitmap) sample.recycle()
        }
    }
}

internal object SuperThumbnailEligibility {
    private const val SERVER_THUMBNAIL_ESTIMATE = 512L * 1024L
    private const val UNKNOWN_IMAGE_ESTIMATE = 4L * 1024L * 1024L
    private const val SPARSE_VIDEO_ESTIMATE = 8L * 1024L * 1024L
    private const val FOLDER_ESTIMATE = 256L * 1024L

    /** Returns a bounded traffic estimate, or null when the repository cannot load the item. */
    fun estimatedBytes(item: RemoteFileItem, supportsRangeStreaming: Boolean): Long? =
        when {
            item.isDirectory -> FOLDER_ESTIMATE
            item.isVideo -> if (supportsRangeStreaming && item.size > 0) {
                minOf(item.size, SPARSE_VIDEO_ESTIMATE).coerceAtLeast(1L)
            } else null
            item.isImage && item.thumbnailUrl != null -> SERVER_THUMBNAIL_ESTIMATE
            item.isImage -> item.size.takeIf { it > 0 } ?: UNKNOWN_IMAGE_ESTIMATE
            else -> null
        }
}

/** Pure root-boundary and breadth-first queue policy used by the Worker. */
internal class SuperThumbnailTraversal(
    rootPath: String,
    private val budget: SuperThumbnailBudget,
) {
    val root: String = normalize(rootPath)
    private val queue = ArrayDeque<SuperThumbnailTraversalNode>().apply {
        add(SuperThumbnailTraversalNode(root, 0))
    }
    private val seenDirectories = mutableSetOf(root)

    fun nextDirectory(): SuperThumbnailTraversalNode? =
        if (queue.isEmpty()) null else queue.removeFirst()

    fun enqueueDirectory(path: String, parentDepth: Int): Boolean {
        val normalized = requireInsideRoot(path)
        if (!budget.acceptsDirectory(parentDepth)) return false
        if (!seenDirectories.add(normalized)) return false
        queue.addLast(SuperThumbnailTraversalNode(normalized, parentDepth + 1))
        return true
    }

    fun requireInsideRoot(path: String): String {
        val normalized = normalize(path)
        require(sameOrDescendant(normalized, root)) { "원격 시작 위치 밖의 경로입니다." }
        return normalized
    }

    private fun normalize(value: String): String {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty() && trimmed.none { it == '\u0000' || it == '\r' || it == '\n' || it == '\\' }) {
            "안전하지 않은 원격 경로입니다."
        }
        val absolute = trimmed.startsWith('/')
        val dotRoot = !absolute && trimmed == "."
        val components = trimmed.split('/').filter(String::isNotEmpty)
        require(components.none { it == ".." }) { "원격 경로 탈출은 허용되지 않습니다." }
        val clean = components.filter { it != "." }
        return when {
            absolute -> if (clean.isEmpty()) "/" else "/${clean.joinToString("/")}"
            clean.isEmpty() && dotRoot -> "."
            clean.isNotEmpty() -> clean.joinToString("/")
            else -> throw IllegalArgumentException("안전하지 않은 원격 경로입니다.")
        }
    }

    private fun sameOrDescendant(path: String, root: String): Boolean = when (root) {
        "/" -> path.startsWith('/')
        "." -> path == "." || (!path.startsWith('/') && path.isNotEmpty())
        else -> path == root || path.startsWith("$root/")
    }
}
