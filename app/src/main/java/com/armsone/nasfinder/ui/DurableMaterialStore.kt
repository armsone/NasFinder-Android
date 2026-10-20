package com.armsone.nasfinder.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

enum class MaterialSourceType {
    LOCAL_LIBRARY,
    PHONE_HARD,
    REMOTE_FILE
}

enum class MaterialPreparationState {
    READY,
    DOWNLOADING,
    MISSING,
    EXCLUDED
}

data class MaterialItem(
    val id: String = UUID.randomUUID().toString(),
    val sourceIdentity: String,
    val name: String,
    val sourceType: MaterialSourceType,
    val isVideo: Boolean,
    val uriString: String,
    val localFilePath: String? = null,
    val durationMs: Long? = null,
    val state: MaterialPreparationState = MaterialPreparationState.READY,
    val downloadProgress: Float? = null,
    val captureTimeMillis: Long = 0L,
    val addedTimeMillis: Long = System.currentTimeMillis(),
    val connectionId: String? = null,
    val remotePath: String? = null,
    val fileSize: Long? = null,
    val errorMessage: String? = null
) {
    val displayLabel: String get() = name.ifBlank { "미디어" }
}

object DurableMaterialStore {
    private const val STORE_FILE_NAME = "materials_store.json"
    private val items = mutableListOf<MaterialItem>()
    private var storeDir: File? = null
    private var isInitialized = false
    // Unreadable store file: kept aside, and nothing overwrites it while persistence is blocked.
    private var loadProblem: String? = null
    private var persistBlocked = false
    // Store entries this build could not parse: written back verbatim so they are never lost.
    private val preservedRawRecords = mutableListOf<Any>()

    @Synchronized
    fun initialize(context: Context) {
        if (isInitialized) return
        val dir = File(context.filesDir, "materials").also { it.mkdirs() }
        storeDir = dir
        loadFromDisk()
        isInitialized = true
    }

    /** Non-null when the saved basket could not be (fully) read; the basket is then NOT an intentionally empty one. */
    @Synchronized
    fun loadProblem(): String? = loadProblem

    /** True when saving is stopped so an unreadable store file is never overwritten. */
    @Synchronized
    fun isPersistBlocked(): Boolean = persistBlocked

    @Synchronized
    fun getItems(): List<MaterialItem> {
        return items.toList()
    }

    @Synchronized
    fun count(): Int = items.size

    @Synchronized
    fun contains(id: String): Boolean = items.any { it.id == id }

    /** Returns the number of newly added items, or null when they could not be saved (nothing is added then). */
    @Synchronized
    fun addItems(newItems: List<MaterialItem>): Int? {
        val backup = items.toList()
        var addedCount = 0
        newItems.forEach { candidate ->
            val exists = items.any { it.sourceIdentity == candidate.sourceIdentity }
            if (!exists) {
                items.add(candidate)
                addedCount++
            }
        }
        if (addedCount > 0) {
            val success = persistToDisk()
            if (!success) {
                items.clear()
                items.addAll(backup)
                return null
            }
        }
        return addedCount
    }

    @Synchronized
    fun removeItem(id: String): Boolean {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) {
            val removedItem = items.removeAt(index)
            val success = persistToDisk()
            if (!success) {
                items.add(index, removedItem)
                return false
            }
            return true
        }
        return false
    }

    @Synchronized
    fun reorder(fromIndex: Int, toIndex: Int): Boolean {
        if (fromIndex in items.indices && toIndex in items.indices && fromIndex != toIndex) {
            val item = items.removeAt(fromIndex)
            items.add(toIndex, item)
            val success = persistToDisk()
            if (!success) {
                items.removeAt(toIndex)
                items.add(fromIndex, item)
                return false
            }
        }
        return true
    }

    @Synchronized
    fun updateState(
        id: String,
        state: MaterialPreparationState,
        progress: Float? = null,
        localPath: String? = null,
        errorMessage: String? = null
    ): Boolean {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) {
            val curr = items[index]
            val updated = curr.copy(
                state = state,
                downloadProgress = progress,
                localFilePath = localPath ?: curr.localFilePath,
                errorMessage = errorMessage
            )
            items[index] = updated
            val success = persistToDisk()
            if (!success) {
                items[index] = curr
                return false
            }
            return true
        }
        return false
    }

    @Synchronized
    fun updateBatchStates(updates: Map<String, MaterialPreparationState>): Boolean {
        val backup = items.toList()
        var changed = false
        updates.forEach { (id, state) ->
            val index = items.indexOfFirst { it.id == id }
            if (index >= 0 && items[index].state != state) {
                items[index] = items[index].copy(state = state)
                changed = true
            }
        }
        if (changed) {
            val success = persistToDisk()
            if (!success) {
                items.clear()
                items.addAll(backup)
                return false
            }
        }
        return true
    }

    @Synchronized
    fun clear(): Boolean {
        val backup = items.toList()
        items.clear()
        val success = persistToDisk()
        if (!success) {
            items.addAll(backup)
        }
        return success
    }

    private fun persistToDisk(): Boolean {
        if (persistBlocked) return false
        val dir = storeDir ?: return false
        val target = File(dir, STORE_FILE_NAME)
        val temp = File(dir, "$STORE_FILE_NAME.tmp")
        val backup = File(dir, "$STORE_FILE_NAME.bak")
        return runCatching {
            val array = JSONArray()
            items.forEach { item ->
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("sourceIdentity", item.sourceIdentity)
                    put("name", item.name)
                    put("sourceType", item.sourceType.name)
                    put("isVideo", item.isVideo)
                    put("uriString", item.uriString)
                    put("localFilePath", item.localFilePath ?: "")
                    put("durationMs", item.durationMs ?: -1L)
                    put("state", item.state.name)
                    put("captureTimeMillis", item.captureTimeMillis)
                    put("addedTimeMillis", item.addedTimeMillis)
                    item.connectionId?.let { put("connectionId", it) }
                    item.remotePath?.let { put("remotePath", it) }
                    item.fileSize?.let { put("fileSize", it) }
                    item.errorMessage?.let { put("errorMessage", it) }
                }
                array.put(obj)
            }
            preservedRawRecords.forEach { array.put(it) }
            FileOutputStream(temp).use { fos ->
                fos.write(array.toString(2).toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            if (!temp.exists() || temp.length() == 0L) {
                temp.delete()
                return false
            }
            // Safe atomic replacement with rollback; the previous file stays as .bak until the new one is in place.
            if (target.exists()) {
                if (backup.exists()) backup.delete()
                if (!target.renameTo(backup)) {
                    temp.delete()
                    return false
                }
            }
            if (!temp.renameTo(target)) {
                // Rollback from backup if rename failed
                if (backup.exists() && !target.exists()) {
                    backup.renameTo(target)
                }
                temp.delete()
                return false
            }
            // Success: remove backup
            if (backup.exists()) backup.delete()
            true
        }.getOrDefault(false)
    }

    private fun readStoreArray(file: File): JSONArray? =
        if (!file.isFile || file.length() <= 0L) null else runCatching { JSONArray(file.readText(Charsets.UTF_8)) }.getOrNull()

    private fun loadFromDisk() {
        val dir = storeDir ?: return
        val file = File(dir, STORE_FILE_NAME)
        val backup = File(dir, "$STORE_FILE_NAME.bak")
        var array = readStoreArray(file)
        val mainUnreadable = array == null && file.isFile && file.length() > 0L
        if (array == null) {
            val fromBackup = readStoreArray(backup)
            if (fromBackup != null) {
                if (mainUnreadable) {
                    val kept = File(dir, "$STORE_FILE_NAME.unreadable-${System.currentTimeMillis()}")
                    if (!file.renameTo(kept)) {
                        persistBlocked = true
                        loadProblem = "재료함 목록 파일을 읽지 못해 덮어쓰지 않도록 저장을 멈췄습니다. 저장 공간을 확인한 뒤 앱을 다시 열어 주세요."
                        return
                    }
                    loadProblem = "재료함 목록 파일이 손상되어 직전 백업으로 복원했습니다. 손상된 파일은 ${kept.name} 이름으로 보존했습니다."
                }
                if (!backup.renameTo(file)) runCatching { backup.copyTo(file, overwrite = false) }
                array = fromBackup
            } else if (mainUnreadable || (backup.isFile && backup.length() > 0L)) {
                // Nothing readable: keep the files aside, never treat the basket as intentionally empty.
                val keptNames = listOf(file, backup).filter { it.isFile && it.length() > 0L }.map { source ->
                    val kept = File(dir, "${source.name}.unreadable-${System.currentTimeMillis()}")
                    if (source.renameTo(kept)) kept.name else null
                }
                if (keptNames.any { it == null }) {
                    persistBlocked = true
                    loadProblem = "재료함 목록 파일을 읽지 못해 덮어쓰지 않도록 저장을 멈췄습니다. 저장 공간을 확인한 뒤 앱을 다시 열어 주세요."
                } else {
                    loadProblem = "재료함 목록 파일을 읽지 못했습니다. 기존 파일(${keptNames.filterNotNull().joinToString()})은 지우지 않고 보존했으며, 재료를 다시 담을 수 있습니다."
                }
                return
            } else {
                return
            }
        }
        val loadedArray = array ?: return
        val loaded = mutableListOf<MaterialItem>()
        for (i in 0 until loadedArray.length()) {
            val raw = loadedArray.opt(i) ?: continue
            val parsed = (raw as? JSONObject)?.let { obj -> runCatching { parseItem(obj) }.getOrNull() }
            if (parsed == null) preservedRawRecords += raw else loaded += parsed
        }
        items.clear()
        items.addAll(loaded)
        if (preservedRawRecords.isNotEmpty() && loadProblem == null) {
            loadProblem = "재료 ${preservedRawRecords.size}개의 정보를 이 버전에서 읽지 못했습니다. 해당 정보는 지우지 않고 원래 내용 그대로 보관합니다."
        }
    }

    /** Parses one stored entry; returns null for an entry this build cannot use (it is then preserved verbatim). */
    private fun parseItem(obj: JSONObject): MaterialItem? {
        val id = obj.optString("id", UUID.randomUUID().toString())
        val sourceIdentity = obj.optString("sourceIdentity", "")
        if (sourceIdentity.isBlank()) return null
        val name = obj.optString("name", "미디어")
        val rawSourceType = obj.optString("sourceType")
        // An unknown (newer) source type is kept verbatim instead of being rewritten as a local item.
        val sourceType = if (rawSourceType.isBlank()) MaterialSourceType.LOCAL_LIBRARY
            else runCatching { MaterialSourceType.valueOf(rawSourceType) }.getOrNull() ?: return null
        val isVideo = obj.optBoolean("isVideo", false)
        val uriString = obj.optString("uriString", "")
        val localFilePath = obj.optString("localFilePath").takeIf { it.isNotBlank() }
        val durationMs = obj.optLong("durationMs", -1L).takeIf { it >= 0 }
        var state = runCatching { MaterialPreparationState.valueOf(obj.optString("state")) }.getOrDefault(MaterialPreparationState.READY)
        // Interrupted downloading resets to retry-needed (MISSING) on launch
        if (state == MaterialPreparationState.DOWNLOADING) {
            state = MaterialPreparationState.MISSING
        }
        val captureTimeMillis = obj.optLong("captureTimeMillis", 0L)
        val addedTimeMillis = obj.optLong("addedTimeMillis", 0L)
        var connectionId = obj.optString("connectionId").takeIf { it.isNotBlank() }
        var remotePath = obj.optString("remotePath").takeIf { it.isNotBlank() }
        val fileSize = obj.optLong("fileSize", -1L).takeIf { it >= 0 }
        val errorMessage = obj.optString("errorMessage").takeIf { it.isNotBlank() }

        // Legacy remote recovery from sourceIdentity ("remote:<connectionId>:<remotePath>")
        if (sourceType == MaterialSourceType.REMOTE_FILE) {
            if (connectionId == null || remotePath == null) {
                if (sourceIdentity.startsWith("remote:")) {
                    val parts = sourceIdentity.removePrefix("remote:").split(":", limit = 2)
                    if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                        if (connectionId == null) connectionId = parts[0]
                        if (remotePath == null) remotePath = parts[1]
                    }
                }
            }
        }

        // If remote file has no valid localFilePath, it cannot be considered READY
        if (sourceType == MaterialSourceType.REMOTE_FILE) {
            val hasValidLocalFile = localFilePath?.let { File(it).exists() && File(it).length() > 0L } == true
            if (!hasValidLocalFile && state == MaterialPreparationState.READY) {
                state = MaterialPreparationState.MISSING
            }
        }

        return MaterialItem(
            id = id,
            sourceIdentity = sourceIdentity,
            name = name,
            sourceType = sourceType,
            isVideo = isVideo,
            uriString = uriString,
            localFilePath = localFilePath,
            durationMs = durationMs,
            state = state,
            captureTimeMillis = captureTimeMillis,
            addedTimeMillis = addedTimeMillis,
            connectionId = connectionId,
            remotePath = remotePath,
            fileSize = fileSize,
            errorMessage = errorMessage
        )
    }
}
