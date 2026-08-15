package com.armsone.nasfinder.data

import androidx.work.WorkInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class SuperThumbnailCacheResetStatus {
    AVAILABLE,
    BLOCKED_RUNNING_WORK,
    BLOCKED_RESET_IN_PROGRESS,
    BLOCKED_WORK_STATE_UNKNOWN,
    COMPLETED,
    PARTIAL,
}

data class SuperThumbnailCacheResetAvailability(
    val status: SuperThumbnailCacheResetStatus,
    val activeWorkCount: Int,
) {
    val canReset: Boolean get() = status == SuperThumbnailCacheResetStatus.AVAILABLE
}

data class SuperThumbnailCacheResetResult(
    val status: SuperThumbnailCacheResetStatus,
    val activeWorkCount: Int = 0,
    val trackedKeyCount: Int = 0,
    val removedFileCount: Int = 0,
    val removedBytes: Long = 0,
    val alreadyMissingCount: Int = 0,
    val failedKeys: Set<String> = emptySet(),
    val clearedSessionCount: Int = 0,
    val sessionStateCleared: Boolean = false,
    val trackingStateUpdated: Boolean = false,
    val trafficBudgetReset: Boolean = false,
    val errors: List<String> = emptyList(),
) {
    val completed: Boolean get() = status == SuperThumbnailCacheResetStatus.COMPLETED
}

internal object SuperThumbnailCacheResetPolicy {
    fun activeWorkCount(states: Iterable<WorkInfo.State>): Int = states.count { state ->
        state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING || state == WorkInfo.State.BLOCKED
    }
}

internal object SuperThumbnailCacheResetCoordinator {
    private var resetting = false

    @Synchronized fun tryBegin(): Boolean {
        if (resetting) return false
        resetting = true
        return true
    }

    @Synchronized fun end() {
        resetting = false
    }

    @Synchronized fun isResetting(): Boolean = resetting

    @Synchronized fun <T> withEnqueueAllowed(block: () -> T): T {
        check(!resetting) { "Super Cache 초기화가 끝난 뒤 다시 시작해 주세요." }
        return block()
    }
}

/** Persistent allow-list of cache keys written by Super Thumbnail, never Browser-only keys. */
internal class SuperThumbnailCacheKeyStore(private val storageFile: File) {
    fun keys(): Set<String> = synchronized(FILE_LOCK) { load() }

    fun record(key: String) = synchronized(FILE_LOCK) {
        require(isValidKey(key)) { "올바르지 않은 Super Thumbnail 캐시 키입니다." }
        val keys = load()
        if (keys.add(key)) save(keys)
    }

    fun remove(keys: Set<String>) = synchronized(FILE_LOCK) {
        if (keys.isEmpty()) return@synchronized
        val retained = load().apply { removeAll(keys) }
        save(retained)
    }

    private fun load(): MutableSet<String> {
        if (!storageFile.isFile) return linkedSetOf()
        val array = JSONObject(storageFile.readText()).optJSONArray("keys") ?: JSONArray()
        return buildSet {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf(::isValidKey)?.let(::add)
            }
        }.toMutableSet()
    }

    private fun save(keys: Set<String>) {
        storageFile.parentFile?.mkdirs()
        val temporary = File(storageFile.parentFile, ".${storageFile.name}.part")
        temporary.writeText(
            JSONObject().put("version", 1).put("keys", JSONArray(keys.sorted())).toString()
        )
        try {
            Files.move(
                temporary.toPath(), storageFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), storageFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    private fun isValidKey(key: String): Boolean = key.matches(KEY_PATTERN)

    private companion object {
        val FILE_LOCK = Any()
        val KEY_PATTERN = Regex("[0-9a-f]{64}")
    }
}

internal data class SuperThumbnailTrackedRemoval(
    val removedFileCount: Int,
    val removedBytes: Long,
    val alreadyMissingCount: Int,
    val failedKeys: Set<String>,
) {
    val resolvedKeyCount: Int get() = removedFileCount + alreadyMissingCount
}
