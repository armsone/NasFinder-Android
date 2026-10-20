package com.armsone.nasfinder.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class CollectionItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val localFilePath: String,
    val durationSeconds: Double,
    val timestamp: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false,
    val posterFilePath: String? = null,
    val isReference: Boolean = false,
    val sourceUri: String? = null,
    val connectionId: String? = null,
    val sourceDraftId: String? = null,
    // Actual byte size of [localFilePath] when it was recorded (0 = unknown legacy record).
    val fileSizeBytes: Long = 0L,
    // Set after a validated 용량 줄이기: the preset title, and whether the original was an external reference.
    val compressedPreset: String? = null,
    val compressedFromReference: Boolean = false,
    // The record used to point at a temporary cache/staging file and now owns a permanent app copy of it.
    val promotedFromEphemeral: Boolean = false,
    // Not persisted: whether a content:// reference still holds its persisted read grant (filled in by the store).
    val referenceGranted: Boolean = false,
) {
    /** A content:// reference kept only because a persistable read grant was actually obtained. */
    val isContentReference: Boolean get() = isReference && localFilePath.startsWith("content://")

    val isValid: Boolean get() {
        if (isContentReference) return referenceGranted
        val f = File(localFilePath)
        return f.exists() && f.length() > 0
    }

    /** User-visible storage mode. */
    val storageModeLabel: String get() = when {
        isContentReference -> "외부 참조 · 읽기 권한 유지"
        isReference -> "외부 참조"
        compressedPreset != null && compressedFromReference -> "압축 사본 · 앱 보관"
        compressedPreset != null -> "압축본 · 앱 보관"
        promotedFromEphemeral -> "앱 보관 · 임시 위치에서 옮김"
        else -> "앱 보관"
    }

    /** Label when the video cannot be read right now (the record itself is always kept). */
    val unavailableLabel: String get() = when {
        isContentReference -> "읽기 권한 없음 · 다시 연결 필요"
        else -> "파일 없음 · 다시 연결 필요"
    }
}

enum class CollectionReplaceResult { REPLACED, ITEM_CHANGED, FAILED }

object DurableCollectionStore {
    private const val STORE_FILE_NAME = "collections_store.json"
    private const val COMPRESSION_PARTIAL_PREFIX = "compress-"
    private const val COMPRESSION_PARTIAL_SUFFIX = ".partial.mp4"
    private const val IMPORT_STAGING_DIR = "collection_import"
    private val items = mutableListOf<CollectionItem>()
    private var storeDir: File? = null
    private var appContext: Context? = null
    private var canonicalCacheRoot: String? = null
    private var isInitialized = false
    // Unreadable store file: kept aside, and nothing overwrites it while this is set.
    private var loadProblem: String? = null
    private var persistBlocked = false

    @Synchronized
    fun initialize(context: Context) {
        if (isInitialized) return
        appContext = context.applicationContext
        canonicalCacheRoot = runCatching { context.cacheDir.canonicalPath }.getOrNull()
        val dir = File(context.filesDir, "collections").also { it.mkdirs() }
        storeDir = dir
        loadFromDisk()
        discardInterruptedCompressionOutputs(dir)
        isInitialized = true
    }

    @Synchronized
    fun loadProblem(): String? = loadProblem

    /** Items with the live state of each content:// reference grant filled in. */
    @Synchronized
    fun getItems(): List<CollectionItem> {
        val granted = persistedReadGrants()
        return items.map { if (it.isContentReference) it.copy(referenceGranted = it.localFilePath in granted) else it }
    }

    @Synchronized
    fun item(id: String): CollectionItem? = getItems().firstOrNull { it.id == id }

    private fun persistedReadGrants(): Set<String> = runCatching {
        appContext?.contentResolver?.persistedUriPermissions.orEmpty().filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
    }.getOrDefault(emptySet())

    /** True only when this app currently HOLDS a persisted read grant for [uri] (survives restarts). */
    fun hasPersistedReadGrant(uri: String): Boolean = uri in persistedReadGrants()

    private fun isUnderCache(file: File): Boolean {
        val cache = canonicalCacheRoot ?: return false
        return runCatching { file.canonicalPath.startsWith(cache + File.separator) }.getOrDefault(false)
    }

    /** True when any record (video or poster) points at a file inside [directory]; used before deleting app folders. */
    @Synchronized
    fun referencesInside(directory: File): Boolean {
        val prefix = runCatching { directory.canonicalPath + File.separator }.getOrNull() ?: return true
        return items.any { item ->
            listOfNotNull(item.localFilePath.takeIf { !it.startsWith("content://") }, item.posterFilePath).any { path ->
                runCatching { File(path).canonicalPath.startsWith(prefix) }.getOrDefault(true)
            }
        }
    }

    /** Partial output path owned by ONE compression operation (deleted only by that operation or its interruption cleanup). */
    @Synchronized
    fun compressionPartialFile(operationId: String): File? {
        val dir = storeDir ?: return null
        runCatching { UUID.fromString(operationId) }.getOrNull() ?: return null
        return File(dir, "$COMPRESSION_PARTIAL_PREFIX$operationId$COMPRESSION_PARTIAL_SUFFIX")
    }

    /** True only for a compression partial file name this store hands out (strict UUID pattern, inside the store). */
    @Synchronized
    fun isOwnedCompressionPartial(file: File): Boolean {
        val dir = storeDir ?: return false
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return false
        if (canonical.parentFile != runCatching { dir.canonicalFile }.getOrNull()) return false
        val name = canonical.name
        if (!name.startsWith(COMPRESSION_PARTIAL_PREFIX) || !name.endsWith(COMPRESSION_PARTIAL_SUFFIX)) return false
        val id = name.removePrefix(COMPRESSION_PARTIAL_PREFIX).removeSuffix(COMPRESSION_PARTIAL_SUFFIX)
        return runCatching { UUID.fromString(id) }.isSuccess
    }

    private fun discardInterruptedCompressionOutputs(dir: File) {
        dir.listFiles()?.forEach { candidate ->
            if (candidate.isFile && isOwnedCompressionPartial(candidate)) candidate.delete()
        }
    }

    /**
     * Stores a permanent app-owned copy of [exportedFile] (which may be a temporary cache/staging file). The origin
     * ([sourceUri]/[connectionId]) is recorded as information only; the external original is never touched.
     */
    @Synchronized
    fun addVideo(
        title: String,
        exportedFile: File,
        durationSeconds: Double,
        posterFile: File? = null,
        sourceDraftId: String? = null,
        sourceUri: String? = null,
        connectionId: String? = null
    ): CollectionItem? {
        val dir = storeDir ?: return null
        if (!exportedFile.exists() || exportedFile.length() <= 0L) return null

        val id = UUID.randomUUID().toString()
        val createdFiles = mutableListOf<File>()
        val destVideo = File(dir, "video_$id.${exportedFile.extension.ifBlank { "mp4" }}")
        copyAtomically(exportedFile, destVideo) ?: return null
        createdFiles += destVideo

        var destPoster: File? = null
        if (posterFile != null && posterFile.exists()) {
            destPoster = copyAtomically(posterFile, File(dir, "poster_$id.jpg"))?.also { createdFiles += it }
        }

        val item = CollectionItem(
            id = id,
            title = title.ifBlank { "완성된 영상" },
            localFilePath = destVideo.absolutePath,
            durationSeconds = durationSeconds,
            timestamp = System.currentTimeMillis(),
            posterFilePath = destPoster?.absolutePath,
            isReference = false,
            sourceUri = sourceUri,
            connectionId = connectionId,
            sourceDraftId = sourceDraftId,
            fileSizeBytes = destVideo.length(),
        )
        if (!insertAndPersist(item)) {
            createdFiles.forEach { it.delete() }
            return null
        }
        return item
    }

    /**
     * Records an external content:// video WITHOUT copying it, only when this app actually holds a persisted read
     * grant for it (checked here, after the caller requested one). Returns null otherwise; nothing is pretended.
     * [durationSeconds] must come from an actually read video track; a zero/NaN length is rejected.
     */
    @Synchronized
    fun addContentReference(
        title: String,
        uri: String,
        durationSeconds: Double,
        sizeBytes: Long,
        posterFile: File? = null,
    ): CollectionItem? {
        val dir = storeDir ?: return null
        require(durationSeconds.isFinite() && durationSeconds > 0.0) { "영상 길이를 확인하지 못해 참조로 추가하지 않았습니다. 다시 시도하거나 앱 안에 사본으로 보관해 주세요." }
        if (!uri.startsWith("content://") || !hasPersistedReadGrant(uri)) return null
        val id = UUID.randomUUID().toString()
        val destPoster = posterFile?.takeIf { it.exists() }?.let { copyAtomically(it, File(dir, "poster_$id.jpg")) }
        val item = CollectionItem(
            id = id,
            title = title.ifBlank { "외부 영상" },
            localFilePath = uri,
            durationSeconds = durationSeconds,
            timestamp = System.currentTimeMillis(),
            posterFilePath = destPoster?.absolutePath,
            isReference = true,
            sourceUri = uri,
            fileSizeBytes = sizeBytes.coerceAtLeast(0L),
            referenceGranted = true,
        )
        if (!insertAndPersist(item)) {
            destPoster?.delete()
            return null
        }
        return item
    }

    private fun insertAndPersist(item: CollectionItem): Boolean {
        // Insert at beginning of unpinned items or top
        val firstUnpinnedIndex = items.indexOfFirst { !it.isPinned }
        if (firstUnpinnedIndex >= 0) items.add(firstUnpinnedIndex, item) else items.add(item)
        if (!persistToDisk()) {
            items.remove(item)
            return false
        }
        return true
    }

    /** Records pointing at a temporary cache/staging file that still exists, snapshotted for [promoteEphemeralRecord]. */
    @Synchronized
    fun ephemeralRecordsToPromote(): List<CollectionItem> = items.filter { item ->
        !item.isContentReference && File(item.localFilePath).let { it.isFile && it.length() > 0L && isUnderCache(it) }
    }

    /**
     * Gives a record that points at a temporary cache file its own permanent copy. The copy is made outside the lock,
     * then committed only if the record still points at the same path. The old file is removed only when it is this
     * app's own import staging file and no record references it. Returns true when the record now owns a copy.
     */
    fun promoteEphemeralRecord(snapshot: CollectionItem): Boolean {
        val dir = synchronized(this) { storeDir } ?: return false
        val oldFile = File(snapshot.localFilePath)
        if (!oldFile.isFile || oldFile.length() <= 0L || !isUnderCache(oldFile)) return false
        val dest = File(dir, "video_${snapshot.id}_${UUID.randomUUID()}.${oldFile.extension.ifBlank { "mp4" }}")
        copyAtomically(oldFile, dest) ?: return false
        return commitOwnedCopy(snapshot.id, snapshot.localFilePath, dest, promoted = true, deleteOldStaging = oldFile)
    }

    /**
     * Re-links an unavailable record to a fresh, validated copy obtained by the caller ([freshCopy], e.g. re-read from
     * its recorded origin). The record keeps its id, title, poster and origin info.
     */
    fun relinkWithCopy(id: String, expectedPath: String, freshCopy: File): Boolean {
        val dir = synchronized(this) { storeDir } ?: return false
        if (!freshCopy.isFile || freshCopy.length() <= 0L) return false
        val dest = File(dir, "video_${id}_${UUID.randomUUID()}.${freshCopy.extension.ifBlank { "mp4" }}")
        copyAtomically(freshCopy, dest) ?: return false
        return commitOwnedCopy(id, expectedPath, dest, promoted = false, deleteOldStaging = null)
    }

    @Synchronized
    private fun commitOwnedCopy(id: String, expectedPath: String, dest: File, promoted: Boolean, deleteOldStaging: File?): Boolean {
        val index = items.indexOfFirst { it.id == id }
        val item = items.getOrNull(index)
        if (item == null || item.localFilePath != expectedPath || persistBlocked) {
            dest.delete()
            return false
        }
        items[index] = item.copy(
            localFilePath = dest.absolutePath,
            isReference = false,
            fileSizeBytes = dest.length(),
            promotedFromEphemeral = promoted || item.promotedFromEphemeral,
            // A former reference keeps its own location as origin info.
            sourceUri = item.sourceUri ?: if (item.isReference && !isUnderCache(File(item.localFilePath))) item.localFilePath else null,
            referenceGranted = false,
        )
        if (!persistToDisk()) {
            items[index] = item
            dest.delete()
            return false
        }
        deleteOldStaging?.let(::deleteImportStagingIfUnreferenced)
        return true
    }

    /** Deletes [file] only when it is this app's own cache import staging file and no record references it. */
    private fun deleteImportStagingIfUnreferenced(file: File) {
        val cache = canonicalCacheRoot ?: return
        runCatching {
            val canonical = file.canonicalFile
            if (canonical.parentFile?.canonicalPath != File(cache, IMPORT_STAGING_DIR).canonicalPath) return
            val referenced = items.any { other ->
                other.localFilePath == file.absolutePath || runCatching { File(other.localFilePath).canonicalFile == canonical }.getOrDefault(true)
            }
            if (!referenced) canonical.delete()
        }
    }

    @Synchronized
    fun removeVideo(id: String): Boolean {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return false
        val item = items.removeAt(index)
        if (!persistToDisk()) {
            items.add(index, item)
            return false
        }
        if (!item.isReference) deleteOwnedFileIfUnreferenced(File(item.localFilePath))
        item.posterFilePath?.let { deleteOwnedFileIfUnreferenced(File(it)) }
        return true
    }

    @Synchronized
    fun togglePin(id: String) {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) {
            val backup = items.toList()
            val item = items[index]
            val newPinned = !item.isPinned
            items.removeAt(index)
            val updated = item.copy(isPinned = newPinned)
            if (newPinned) {
                val insertIdx = items.indexOfLast { it.isPinned } + 1
                items.add(insertIdx, updated)
            } else {
                val firstUnpinned = items.indexOfFirst { !it.isPinned }
                if (firstUnpinned >= 0) items.add(firstUnpinned, updated) else items.add(updated)
            }
            if (!persistToDisk()) { items.clear(); items.addAll(backup) }
        }
    }

    @Synchronized
    fun moveItem(id: String, offset: Int) {
        val from = items.indexOfFirst { it.id == id }
        if (from < 0) return
        val currentItem = items[from]
        val targetIndices = if (currentItem.isPinned) {
            items.indices.filter { items[it].isPinned }
        } else {
            items.indices.filter { !items[it].isPinned }
        }
        val posInGroup = targetIndices.indexOf(from)
        if (posInGroup < 0) return
        val newPosInGroup = (posInGroup + offset).coerceIn(0, targetIndices.lastIndex)
        if (posInGroup == newPosInGroup) return
        val to = targetIndices[newPosInGroup]
        val backup = items.toList()
        items.add(to, items.removeAt(from))
        if (!persistToDisk()) { items.clear(); items.addAll(backup) }
    }

    @Synchronized
    fun rename(id: String, newTitle: String) {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) {
            val item = items[index]
            items[index] = item.copy(title = newTitle.ifBlank { item.title })
            if (!persistToDisk()) items[index] = item
        }
    }

    /** Stores a user-confirmed poster as a new owned file; the previous poster is removed only after the record is saved. */
    @Synchronized
    fun updatePoster(id: String, posterFile: File): Boolean {
        val index = items.indexOfFirst { it.id == id }
        val dir = storeDir ?: return false
        if (index < 0 || !posterFile.exists()) return false
        val item = items[index]
        val destPoster = copyAtomically(posterFile, File(dir, "poster_${id}_${UUID.randomUUID()}.jpg")) ?: return false
        items[index] = item.copy(posterFilePath = destPoster.absolutePath)
        if (!persistToDisk()) {
            items[index] = item
            destPoster.delete()
            return false
        }
        item.posterFilePath?.takeIf { it != destPoster.absolutePath }?.let { deleteOwnedFileIfUnreferenced(File(it)) }
        return true
    }

    /**
     * Commits a validated, smaller compression output for [id]. The record must still point at [expectedPath]
     * (otherwise nothing changes). The new owned file is recorded first; the old file is deleted only when it was
     * this store's own copy and no other record references it. External references are never touched.
     */
    @Synchronized
    fun replaceWithCompressed(
        id: String,
        expectedPath: String,
        compressedOutput: File,
        durationSeconds: Double,
        presetTitle: String,
    ): CollectionReplaceResult {
        val dir = storeDir ?: return CollectionReplaceResult.FAILED
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return CollectionReplaceResult.ITEM_CHANGED
        val item = items[index]
        if (item.localFilePath != expectedPath) return CollectionReplaceResult.ITEM_CHANGED
        if (!isOwnedCompressionPartial(compressedOutput) || !compressedOutput.isFile || compressedOutput.length() <= 0L) {
            return CollectionReplaceResult.FAILED
        }
        val finalFile = File(dir, "video_${id}_${UUID.randomUUID()}.mp4")
        if (!compressedOutput.renameTo(finalFile)) return CollectionReplaceResult.FAILED
        items[index] = item.copy(
            localFilePath = finalFile.absolutePath,
            isReference = false,
            durationSeconds = durationSeconds,
            fileSizeBytes = finalFile.length(),
            compressedPreset = presetTitle,
            compressedFromReference = item.compressedFromReference || item.isReference,
            // The origin stays recorded: a reference's own location becomes its sourceUri when none was kept.
            sourceUri = item.sourceUri ?: if (item.isReference) item.localFilePath else null,
        )
        if (!persistToDisk()) {
            items[index] = item
            finalFile.delete()
            return CollectionReplaceResult.FAILED
        }
        if (!item.isReference) deleteOwnedFileIfUnreferenced(File(item.localFilePath))
        return CollectionReplaceResult.REPLACED
    }

    /** Deletes [file] only when it is directly inside this store's directory and no remaining record references it. */
    private fun deleteOwnedFileIfUnreferenced(file: File) {
        val dir = storeDir ?: return
        runCatching {
            val canonical = file.canonicalFile
            if (canonical.parentFile != dir.canonicalFile) return
            val referenced = items.any { other ->
                runCatching { File(other.localFilePath).canonicalFile == canonical }.getOrDefault(other.localFilePath == file.absolutePath) ||
                    other.posterFilePath?.let { p -> runCatching { File(p).canonicalFile == canonical }.getOrDefault(p == file.absolutePath) } == true
            }
            if (!referenced) canonical.delete()
        }
    }

    private fun copyAtomically(source: File, destination: File): File? {
        val partial = File(destination.parentFile, "${destination.name}.part")
        return runCatching {
            source.inputStream().buffered().use { input ->
                FileOutputStream(partial).use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            check(partial.length() == source.length() && partial.renameTo(destination))
            destination
        }.getOrElse {
            partial.delete()
            null
        }
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
                    put("title", item.title)
                    put("localFilePath", item.localFilePath)
                    put("durationSeconds", item.durationSeconds)
                    put("timestamp", item.timestamp)
                    put("isPinned", item.isPinned)
                    put("posterFilePath", item.posterFilePath ?: "")
                    put("isReference", item.isReference)
                    put("sourceUri", item.sourceUri ?: "")
                    item.connectionId?.let { put("connectionId", it) }
                    put("sourceDraftId", item.sourceDraftId ?: "")
                    put("fileSizeBytes", item.fileSizeBytes)
                    item.compressedPreset?.let { put("compressedPreset", it) }
                    put("compressedFromReference", item.compressedFromReference)
                    put("promotedFromEphemeral", item.promotedFromEphemeral)
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
            if (target.exists()) {
                if (backup.exists()) backup.delete()
                if (!target.renameTo(backup)) {
                    temp.delete()
                    return false
                }
            }
            if (!temp.renameTo(target)) {
                if (backup.exists() && !target.exists()) backup.renameTo(target)
                temp.delete()
                return false
            }
            if (backup.exists()) backup.delete()
            true
        }.getOrDefault(false)
    }

    // Store entries this build could not parse: written back verbatim so they are never lost.
    private val preservedRawRecords = mutableListOf<Any>()

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
                        loadProblem = "컬렉션 목록 파일을 읽지 못해 덮어쓰지 않도록 저장을 멈췄습니다. 앱을 다시 열어 주세요."
                        return
                    }
                    loadProblem = "컬렉션 목록 파일이 손상되어 직전 백업으로 복원했습니다. 손상된 파일은 ${kept.name} 이름으로 보존했습니다."
                }
                if (!backup.renameTo(file)) runCatching { backup.copyTo(file, overwrite = false) }
                array = fromBackup
            } else if (mainUnreadable || (backup.isFile && backup.length() > 0L)) {
                // Nothing readable: keep the files aside, never treat the collection as intentionally empty.
                val keptNames = listOf(file, backup).filter { it.isFile && it.length() > 0L }.map { source ->
                    val kept = File(dir, "${source.name}.unreadable-${System.currentTimeMillis()}")
                    if (source.renameTo(kept)) kept.name else null
                }
                if (keptNames.any { it == null }) {
                    persistBlocked = true
                    loadProblem = "컬렉션 목록 파일을 읽지 못해 덮어쓰지 않도록 저장을 멈췄습니다. 앱을 다시 열어 주세요."
                } else {
                    loadProblem = "컬렉션 목록 파일을 읽지 못했습니다. 기존 파일(${keptNames.filterNotNull().joinToString()})과 보관된 영상 파일은 지우지 않았습니다."
                }
                return
            } else {
                return
            }
        }
        val loadedArray = array ?: return
        run {
            val loaded = mutableListOf<CollectionItem>()
            for (i in 0 until loadedArray.length()) {
                val obj = loadedArray.optJSONObject(i)
                val localFilePath = obj?.optString("localFilePath").orEmpty()
                if (obj == null || localFilePath.isBlank()) {
                    loadedArray.opt(i)?.let { preservedRawRecords += it }
                    continue
                }
                val id = obj.optString("id", UUID.randomUUID().toString())
                val title = obj.optString("title", "완성된 영상")
                val durationSeconds = obj.optDouble("durationSeconds", 0.0)
                val timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                val isPinned = obj.optBoolean("isPinned", false)
                val posterFilePath = obj.optString("posterFilePath").takeIf { it.isNotBlank() }
                val isReference = obj.optBoolean("isReference", false)
                val sourceUri = obj.optString("sourceUri").takeIf { it.isNotBlank() }
                val connectionId = if (obj.has("connectionId")) obj.optString("connectionId").takeIf { it.isNotBlank() } else null
                val sourceDraftId = obj.optString("sourceDraftId").takeIf { it.isNotBlank() }

                // Records whose file is currently missing are kept (shown as missing) so the metadata is never lost.
                loaded.add(
                    CollectionItem(
                        id = id,
                        title = title,
                        localFilePath = localFilePath,
                        durationSeconds = durationSeconds,
                        timestamp = timestamp,
                        isPinned = isPinned,
                        posterFilePath = posterFilePath,
                        isReference = isReference,
                        sourceUri = sourceUri,
                        connectionId = connectionId,
                        sourceDraftId = sourceDraftId,
                        fileSizeBytes = obj.optLong("fileSizeBytes", File(localFilePath).takeIf { it.isFile }?.length() ?: 0L),
                        compressedPreset = obj.optString("compressedPreset").takeIf { it.isNotBlank() },
                        compressedFromReference = obj.optBoolean("compressedFromReference", false),
                        promotedFromEphemeral = obj.optBoolean("promotedFromEphemeral", false),
                    )
                )
            }
            items.clear()
            items.addAll(loaded)
        }
    }
}
