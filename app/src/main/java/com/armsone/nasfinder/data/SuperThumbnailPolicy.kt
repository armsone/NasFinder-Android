package com.armsone.nasfinder.data

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

internal object SuperThumbnailEligibility {
    private const val SERVER_THUMBNAIL_ESTIMATE = 512L * 1024L
    private const val UNKNOWN_IMAGE_ESTIMATE = 4L * 1024L * 1024L
    private const val SPARSE_VIDEO_ESTIMATE = 8L * 1024L * 1024L

    /** Returns a bounded traffic estimate, or null when the repository cannot load the item. */
    fun estimatedBytes(item: RemoteFileItem, supportsRangeStreaming: Boolean): Long? =
        when (RemoteThumbnailFetchPolicy.source(item, supportsRangeStreaming)) {
            RemoteThumbnailSource.NONE -> null
            RemoteThumbnailSource.SERVER_THUMBNAIL -> SERVER_THUMBNAIL_ESTIMATE
            RemoteThumbnailSource.ORIGINAL_IMAGE -> item.size.takeIf { it > 0 } ?: UNKNOWN_IMAGE_ESTIMATE
            RemoteThumbnailSource.SPARSE_VIDEO -> minOf(item.size, SPARSE_VIDEO_ESTIMATE).coerceAtLeast(1L)
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
