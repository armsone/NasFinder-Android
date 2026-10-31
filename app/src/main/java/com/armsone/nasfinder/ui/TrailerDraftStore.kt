package com.armsone.nasfinder.ui

import com.armsone.nasfinder.data.PrivacyMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class TrailerDraft(
    val id: String = UUID.randomUUID().toString(),
    val isMusicOverlayMode: Boolean,
    val directory: File,
    val sources: List<TrailerSource>,
    val total: Double,
    val clipLength: Double,
    val photoClipLength: Double,
    val format: TrailerFormat,
    val musicTrack: TrailerMusicTrack?,
    val musicPolicy: TrailerMusicPolicy,
    val musicFitCount: Int,
    val autoDucking: Boolean = true,
    val output: File?,
    val showingSettings: Boolean = false,
    val orderMode: String? = "추가순",
    val timestamp: Long = System.currentTimeMillis(),
    val customTitle: String? = null,
    // Explicit scene state; null for drafts saved before scenes were persisted (then derived from the plan).
    val editor: EditorProject? = null,
    // Last failed move of a legacy cache project into durable storage (not persisted; retried on every launch).
    val migrationIssue: String? = null,
    val revision: Long? = null,
    val outputRevision: Long? = null,
    // 비공개 편집본(기본 false, 예전 데이터와 같다). 비공개 입력이 한 번 들어오면 풀리지 않는 오염 플래그이며
    // 출력(폰하드 파일)·컬렉션 영상으로 이어진다. 공개로 되돌리는 것은 사용자의 명시적 전환뿐이다.
    val isPrivate: Boolean = false,
) {
    val displayTitle: String get() {
        if (!customTitle.isNullOrBlank()) return customTitle
        return if (isMusicOverlayMode) {
            val musicName = musicTrack?.name ?: "음악 미선택"
            "뮤직비디오 (${sources.size}개 미디어 · $musicName)"
        } else {
            val validOutput = output?.takeIf { it.exists() && it.length() > 0 }
            if (validOutput != null) "완성된 예고편 (${sources.size}개 미디어)"
            else "예고편 편집 (${sources.size}개 미디어)"
        }
    }

    /** True when the project can be opened as-is (folder and every source file present). */
    fun isValid(): Boolean {
        return directory.exists() && directory.isDirectory &&
                sources.isNotEmpty() && sources.all { it.file.exists() && it.file.length() > 0 }
    }

    /** User-visible reasons this project cannot be opened right now (empty when [isValid]). */
    fun unavailableDetails(): List<String> {
        val details = mutableListOf<String>()
        if (!directory.isDirectory) details += "프로젝트 폴더를 찾을 수 없습니다."
        if (sources.isEmpty()) details += "재료 정보가 없습니다."
        sources.filter { !it.file.isFile || it.file.length() <= 0L }.forEach { details += "재료 없음: ${it.name}" }
        return details
    }
}

object TrailerDraftStore {
    private const val DRAFTS_META_FILE = "drafts_meta.json"
    private const val DURABLE_PROJECTS_DIR = "trailer_projects"
    // Written first into a migration copy and removed only after its metadata was committed.
    private const val MIGRATION_MARKER = ".nasfinder-migrating"
    private val drafts = mutableMapOf<String, TrailerDraft>()
    // Metadata entries this build could not parse: written back verbatim so they are never lost.
    private val preservedRawRecords = mutableListOf<Any>()
    // Legacy project root (cache/trailers) and the durable root (files/trailer_projects) used by new projects.
    private var canonicalTrailersRoot: String? = null
    private var canonicalDurableRoot: String? = null
    private var canonicalCacheRoot: String? = null
    private var draftsStorageDir: File? = null
    private var metadataProblem: String? = null
    // True when unreadable metadata could not be set aside: then nothing may overwrite it.
    private var persistBlocked = false
    // Answers whether another store (collections) still points at a file inside a directory.
    private var externalReferenceGuard: ((File) -> Boolean)? = null

    @Synchronized
    fun configureTrailersRoot(cacheDir: File, durableFilesDir: File? = null) {
        canonicalTrailersRoot = runCatching { File(cacheDir, "trailers").canonicalPath }.getOrNull()
        canonicalCacheRoot = runCatching { cacheDir.canonicalPath }.getOrNull()
        if (durableFilesDir != null) {
            canonicalDurableRoot = runCatching { File(durableFilesDir, DURABLE_PROJECTS_DIR).also { it.mkdirs() }.canonicalPath }.getOrNull()
            draftsStorageDir = File(durableFilesDir, "trailer_drafts").also { it.mkdirs() }
            loadDraftsFromDisk()
        }
    }

    @Synchronized
    fun setExternalReferenceGuard(guard: (File) -> Boolean) {
        externalReferenceGuard = guard
    }

    /** Non-null when the saved project list could not be read; the unreadable file is kept, never treated as empty. */
    @Synchronized
    fun metadataProblem(): String? = metadataProblem

    /** A fresh UUID project directory under the durable root (not the OS-evictable cache). */
    @Synchronized
    fun newProjectDirectory(fallbackCacheDir: File): File {
        val root = canonicalDurableRoot?.let(::File) ?: File(fallbackCacheDir, "trailers")
        return File(root, UUID.randomUUID().toString()).also { it.mkdirs() }
    }

    /**
     * Inserts or replaces a draft and writes the metadata atomically. Returns false when it could not be persisted.
     * A replaced record's former directory is never deleted here (it may still hold the only copy of its files).
     */
    @Synchronized
    fun saveDraft(draft: TrailerDraft): Boolean {
        if (!draft.directory.isDirectory || draft.sources.isEmpty()) return false
        val previous = drafts.toMap()
        val existing = drafts.values.firstOrNull { it.id == draft.id || sameDirectory(it.directory, draft.directory) }
        if (existing != null) drafts.remove(existing.id)
        // 오염은 저장 중에도 풀리지 않는다. 프라이버시 모드에서 새로 생긴 편집본은 비공개로 시작한다.
        val tainted = draft.isPrivate || existing?.isPrivate == true || (existing == null && PrivacyMode.isActive)
        drafts[draft.id] = draft.copy(migrationIssue = null, isPrivate = tainted)
        if (!persistDraftsToDisk()) {
            drafts.clear()
            drafts.putAll(previous)
            return false
        }
        return true
    }

    @Synchronized
    fun detachDraft(draftId: String): TrailerDraft? {
        val target = drafts.remove(draftId) ?: return null
        if (!persistDraftsToDisk()) {
            drafts[draftId] = target
            return null
        }
        return target
    }

    /** 지금 모드에서 보이는 편집본. */
    @Synchronized
    fun getAllDrafts(): List<TrailerDraft> {
        return drafts.values.filter { PrivacyMode.isVisible(it.isPrivate) }.sortedByDescending { it.timestamp }
    }

    /** 비공개를 포함한 전체 편집본(정리·소유권 계산용). 화면 목록에는 쓰지 않는다. */
    @Synchronized
    fun getEveryDraft(): List<TrailerDraft> = drafts.values.toList()

    @Synchronized
    fun draft(id: String): TrailerDraft? = drafts[id]?.takeIf { PrivacyMode.isVisible(it.isPrivate) }

    @Synchronized
    fun isDraftPrivate(id: String): Boolean = drafts[id]?.isPrivate == true

    /** 명시적인 공개/비공개 전환. 편집본 내용은 그대로 두고 플래그만 바꾼다. */
    @Synchronized
    fun setDraftPrivate(id: String, isPrivate: Boolean): Boolean {
        val existing = drafts[id] ?: return false
        if (existing.isPrivate == isPrivate) return true
        drafts[id] = existing.copy(isPrivate = isPrivate)
        if (!persistDraftsToDisk()) { drafts[id] = existing; return false }
        return true
    }

    @Synchronized
    fun draftCount(): Int {
        return drafts.values.count { PrivacyMode.isVisible(it.isPrivate) }
    }

    @Synchronized
    fun hasAnyDraft(): Boolean = drafts.values.any { PrivacyMode.isVisible(it.isPrivate) }

    /**
     * Explicit user removal: the record goes first (atomically); only then its own app directory is deleted, and only
     * when it is a strict app-owned project folder that no other draft or collection record still references.
     * Returns false when nothing was removed.
     */
    @Synchronized
    fun removeDraft(draft: TrailerDraft): Boolean {
        val target = drafts.remove(draft.id)
            ?: drafts.values.firstOrNull { sameDirectory(it.directory, draft.directory) }?.also { drafts.remove(it.id) }
            ?: return false
        if (!persistDraftsToDisk()) {
            // Metadata still lists it: keep the files so the project stays openable.
            drafts[target.id] = target
            return false
        }
        safeDeletePrivateTrailerDirectory(target.directory)
        return true
    }

    @Synchronized
    fun duplicateDraft(draft: TrailerDraft): TrailerDraft? {
        if (!draft.isValid()) return null
        val root = canonicalDurableRoot ?: canonicalTrailersRoot ?: return null
        val newId = UUID.randomUUID().toString()
        val newDir = File(root, UUID.randomUUID().toString()).also { it.mkdirs() }

        return runCatching {
            draft.directory.copyRecursively(newDir, overwrite = true)
            File(newDir, MIGRATION_MARKER).delete()
            val newSources = draft.sources.map { src ->
                // Only files inside the project folder were copied; anything else keeps its own path.
                val newFile = if (isInside(src.file, draft.directory)) File(newDir, src.file.name) else src.file
                src.copy(file = newFile)
            }
            check(newSources.all { it.file.isFile && it.file.length() > 0L }) { "사본 재료를 확인하지 못했습니다." }
            val newOutput = draft.output?.let { out ->
                val f = File(newDir, out.name)
                if (f.exists()) f else null
            }
            val newMusic = draft.musicTrack?.let { track ->
                if (isInside(track.file, draft.directory)) track.copy(file = File(newDir, track.file.name)) else track
            }
            val duplicated = draft.copy(
                id = newId,
                directory = newDir,
                sources = newSources,
                output = newOutput,
                outputRevision = if (newOutput != null) draft.outputRevision else null,
                musicTrack = newMusic,
                customTitle = "${draft.displayTitle} (사본)",
                timestamp = System.currentTimeMillis(),
                migrationIssue = null,
            )
            check(saveDraft(duplicated)) { "사본 정보를 저장하지 못했습니다." }
            duplicated
        }.getOrElse {
            // Only the directory this call just created is removed.
            if (drafts.values.none { sameDirectory(it.directory, newDir) }) safeDeletePrivateTrailerDirectory(newDir)
            null
        }
    }

    @Synchronized
    fun renameDraft(draftId: String, newTitle: String) {
        val existing = drafts[draftId] ?: return
        drafts[draftId] = existing.copy(customTitle = newTitle.ifBlank { null })
        if (!persistDraftsToDisk()) drafts[draftId] = existing
    }

    @Synchronized
    fun isDirectoryManagedAsDraft(directory: File): Boolean {
        return drafts.values.any { sameDirectory(it.directory, directory) }
    }

    private fun sameDirectory(a: File, b: File): Boolean {
        val left = runCatching { a.canonicalPath }.getOrNull() ?: a.absolutePath
        val right = runCatching { b.canonicalPath }.getOrNull() ?: b.absolutePath
        return left == right
    }

    private fun isInside(file: File, directory: File): Boolean = runCatching {
        file.canonicalPath.startsWith(directory.canonicalPath + File.separator)
    }.getOrDefault(false)

    private fun isUnderCache(file: File): Boolean {
        val cache = canonicalCacheRoot ?: return false
        return runCatching { file.canonicalPath.startsWith(cache + File.separator) }.getOrDefault(false)
    }

    /** Projects created before durable storage live in cache/trailers; their cache files are app-owned staging copies. */
    private fun isLegacyCacheProject(draft: TrailerDraft): Boolean = isUnderCache(draft.directory)

    fun isSafePrivateTrailerDirectory(directory: File): Boolean {
        val roots = listOfNotNull(canonicalTrailersRoot, canonicalDurableRoot)
        if (roots.isEmpty()) return false
        return runCatching {
            if (java.nio.file.Files.isSymbolicLink(directory.toPath())) return false
            val canonicalDir = directory.canonicalFile
            val canonicalParent = canonicalDir.parentFile ?: return false
            if (canonicalParent.canonicalPath !in roots) return false
            if (canonicalParent.name != "trailers" && canonicalParent.name != DURABLE_PROJECTS_DIR) return false
            val canonicalPath = canonicalDir.canonicalPath
            if (canonicalPath.contains("/phonehard") || canonicalPath.contains("/files/phonehard")) return false

            val leafName = canonicalDir.name
            runCatching { UUID.fromString(leafName) }.isSuccess
        }.getOrDefault(false)
    }

    /**
     * Deletes an app-owned project folder only when it is a strict UUID folder under a project root, no draft lists
     * it, and no collection record points at a file inside it. Anything uncertain is retained.
     */
    @Synchronized
    fun safeDeletePrivateTrailerDirectory(directory: File): Boolean {
        if (!isSafePrivateTrailerDirectory(directory)) return false
        if (drafts.values.any { sameDirectory(it.directory, directory) }) return false
        if (drafts.values.any { draft ->
                draft.sources.any { isInside(it.file, directory) } ||
                    draft.output?.let { isInside(it, directory) } == true ||
                    draft.musicTrack?.let { isInside(it.file, directory) } == true
            }) return false
        if (preservedRawRecords.any { rawRecordReferencesInside(it, directory) }) return false
        if (runCatching { externalReferenceGuard?.invoke(directory) == true }.getOrDefault(true)) return false
        return directory.deleteRecursively()
    }

    /** True when an unparsed stored record (kept verbatim) may point into [directory]; unknown shapes count as referencing. */
    private fun rawRecordReferencesInside(raw: Any, directory: File): Boolean = runCatching {
        val obj = raw as? JSONObject ?: return@runCatching raw.toString().contains(directory.name)
        val paths = mutableListOf(obj.optString("directory"), obj.optString("output"), obj.optString("musicFile"))
        obj.optJSONArray("sources")?.let { arr ->
            for (i in 0 until arr.length()) {
                val src = arr.opt(i)
                if (src is JSONObject) paths += src.optString("file") else if (src != null && src.toString().contains(directory.name)) return@runCatching true
            }
        }
        paths.filter { it.isNotBlank() }.any { path -> sameDirectory(File(path), directory) || isInside(File(path), directory) }
    }.getOrDefault(true)

    // ---------------------------------------------------------------------------------------------
    // Legacy cache project migration (cache/trailers -> files/trailer_projects).
    // ---------------------------------------------------------------------------------------------

    /**
     * Removes migration copies left by an interrupted earlier attempt: a marked folder that no record lists is this
     * app's own partial copy (the original record and files were never changed). A marked folder that IS listed was
     * committed; only its marker is cleared. Skipped entirely when the metadata itself could not be read.
     */
    @Synchronized
    fun cleanupInterruptedMigrations() {
        if (metadataProblem != null || persistBlocked) return
        val root = canonicalDurableRoot?.let(::File) ?: return
        root.listFiles()?.forEach { candidate ->
            val marker = File(candidate, MIGRATION_MARKER)
            if (!candidate.isDirectory || !marker.isFile) return@forEach
            if (drafts.values.any { sameDirectory(it.directory, candidate) }) marker.delete()
            else safeDeletePrivateTrailerDirectory(candidate)
        }
    }

    /**
     * Copies every legacy draft that still lives in the OS-evictable cache into a fresh owned project folder. Each
     * copy is validated before the metadata is atomically switched to it; on any failure the original record and all
     * of its files stay exactly as they were and the draft shows a retryable issue. [inUse] protects the open project.
     * Returns the number of drafts moved.
     */
    fun migrateLegacyDrafts(inUse: (File) -> Boolean): Int {
        val candidates = synchronized(this) {
            if (metadataProblem != null || persistBlocked || canonicalDurableRoot == null) return 0
            drafts.values.filter { isLegacyCacheProject(it) }
        }
        var moved = 0
        candidates.forEach { original ->
            if (runCatching { inUse(original.directory) }.getOrDefault(true)) return@forEach
            if (!original.isValid()) {
                markMigrationIssue(original, "일부 재료 파일이 없어 안전한 저장 위치로 옮기지 못했습니다. 기존 편집 정보와 남은 파일은 그대로 보존했습니다.")
                return@forEach
            }
            val result = runCatching { copyDraftForMigration(original) }
            val migrated = result.getOrNull()
            if (migrated == null) {
                markMigrationIssue(original, "저장 공간 부족 등으로 안전한 저장 위치로 옮기지 못했습니다. 기존 편집은 그대로이며 다시 시도할 수 있습니다.")
                return@forEach
            }
            if (commitMigration(original, migrated, inUse)) moved++
        }
        return moved
    }

    @Synchronized
    private fun markMigrationIssue(original: TrailerDraft, issue: String) {
        val current = drafts[original.id] ?: return
        if (current === original) drafts[original.id] = current.copy(migrationIssue = issue)
    }

    /** Builds the migrated record inside a NEW marked folder; returns null (after removing that folder) on any failure. */
    private fun copyDraftForMigration(original: TrailerDraft): TrailerDraft? {
        val root = canonicalDurableRoot?.let(::File) ?: return null
        val newDir = File(root, UUID.randomUUID().toString())
        if (!newDir.mkdirs()) return null
        return try {
            File(newDir, MIGRATION_MARKER).writeText(original.id)
            val usedNames = mutableSetOf(MIGRATION_MARKER)
            fun copyOwned(file: File): File {
                if (!isUnderCache(file)) return file
                var name = file.name.ifBlank { "media" }
                var index = 1
                while (!usedNames.add(name)) name = "${index++}-${file.name}"
                return copyValidated(file, File(newDir, name))
            }
            val sources = original.sources.map { it.copy(file = copyOwned(it.file)) }
            val output = original.output?.takeIf { it.isFile && it.length() > 0L }?.let(::copyOwned)
            val music = original.musicTrack?.let { track ->
                if (track.file.isFile && track.file.length() > 0L) track.copy(file = copyOwned(track.file)) else track
            }
            original.copy(directory = newDir, sources = sources, output = output, musicTrack = music, migrationIssue = null)
        } catch (_: Exception) {
            synchronized(this) { safeDeletePrivateTrailerDirectory(newDir) }
            null
        }
    }

    /** Copies through a partial file, syncs it and checks the length before the final rename. */
    private fun copyValidated(source: File, destination: File): File {
        val partial = File(destination.parentFile, "${destination.name}.part")
        try {
            source.inputStream().buffered().use { input ->
                FileOutputStream(partial).use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            check(source.length() > 0L && partial.length() == source.length()) { "복사 크기가 다릅니다: ${source.name}" }
            check(partial.renameTo(destination)) { "복사본을 확정하지 못했습니다: ${source.name}" }
            return destination
        } catch (error: Exception) {
            partial.delete()
            throw error
        }
    }

    @Synchronized
    private fun commitMigration(original: TrailerDraft, migrated: TrailerDraft, inUse: (File) -> Boolean): Boolean {
        val current = drafts[original.id]
        val stillSame = current === original || (current != null && current.copy(migrationIssue = null) == original.copy(migrationIssue = null))
        if (!stillSame || runCatching { inUse(original.directory) }.getOrDefault(true)) {
            // The record changed (or the project was opened) while copying: discard only our copy; retried next launch.
            safeDeletePrivateTrailerDirectory(migrated.directory)
            return false
        }
        drafts[original.id] = migrated
        if (!persistDraftsToDisk()) {
            drafts[original.id] = current!!.copy(migrationIssue = "편집 정보를 새 위치로 저장하지 못했습니다. 기존 편집은 그대로이며 다시 시도할 수 있습니다.")
            safeDeletePrivateTrailerDirectory(migrated.directory)
            return false
        }
        File(migrated.directory, MIGRATION_MARKER).delete()
        // The old cache folder is removed only when it is a strict legacy project folder nothing references any more.
        if (!sameDirectory(original.directory, migrated.directory) && runCatching { !inUse(original.directory) }.getOrDefault(false)) {
            safeDeletePrivateTrailerDirectory(original.directory)
        }
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // Metadata file.
    // ---------------------------------------------------------------------------------------------

    private fun sceneJson(scene: EditorScene) = JSONObject().apply {
        put("key", scene.key)
        put("sourceId", scene.sourceId)
        put("start", scene.start)
        put("length", scene.length)
        put("fit", scene.fit.name)
        put("focusX", scene.focusX.toDouble())
        put("focusY", scene.focusY.toDouble())
        put("sourceVolume", scene.sourceVolume.toDouble())
        scene.caption?.normalizedOrNull()?.let { put("caption", it.toJson()) }
    }

    private fun draftJson(draft: TrailerDraft) = JSONObject().apply {
        put("id", draft.id)
        put("isMusicOverlayMode", draft.isMusicOverlayMode)
        put("directory", draft.directory.absolutePath)
        put("total", draft.total)
        put("clipLength", draft.clipLength)
        put("photoClipLength", draft.photoClipLength)
        put("format", draft.format.name)
        put("musicPolicy", draft.musicPolicy.name)
        put("musicFitCount", draft.musicFitCount)
        put("autoDucking", draft.autoDucking)
        put("output", draft.output?.absolutePath ?: "")
        put("showingSettings", draft.showingSettings)
        put("orderMode", draft.orderMode ?: "추가순")
        put("timestamp", draft.timestamp)
        put("customTitle", draft.customTitle ?: "")
        if (draft.isPrivate) put("isPrivate", true)
        draft.revision?.let { put("revision", it) }
        draft.outputRevision?.let { put("outputRevision", it) }
        draft.musicTrack?.let { track ->
            put("musicFile", track.file.absolutePath)
            put("musicName", track.name)
            put("musicDuration", track.durationSeconds)
        }
        draft.editor?.let { editor ->
            put("editor", JSONObject().apply {
                put("autoPlan", editor.autoPlan)
                put("musicVolume", editor.musicVolume.toDouble())
                put("nextKey", editor.nextKey)
                put("scenes", JSONArray().apply { editor.scenes.forEach { put(sceneJson(it)) } })
            })
        }
        val sourcesArr = JSONArray()
        draft.sources.forEach { src ->
            sourcesArr.put(JSONObject().apply {
                put("id", src.id)
                put("name", src.name)
                put("file", src.file.absolutePath)
                put("duration", src.duration)
                put("width", src.width)
                put("height", src.height)
                put("isImage", src.isImage)
                put("captureTimeMillis", src.captureTimeMillis)
                put("photoDurationRatio", src.photoDurationRatio)
                put("photoPolicyVersion", src.photoPolicyVersion)
                src.originKey?.let { put("originKey", it) }
            })
        }
        put("sources", sourcesArr)
    }

    /** Writes EVERY record (including currently unavailable ones and unparsed entries) atomically. */
    private fun persistDraftsToDisk(): Boolean {
        if (persistBlocked) return false
        val storage = draftsStorageDir ?: return false
        val target = File(storage, DRAFTS_META_FILE)
        val temp = File(storage, "$DRAFTS_META_FILE.tmp")
        val backup = File(storage, "$DRAFTS_META_FILE.bak")
        return runCatching {
            val array = JSONArray()
            drafts.values.forEach { array.put(draftJson(it)) }
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
            // Keep the previous metadata as .bak until the new file is in place.
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

    private fun readArray(file: File): JSONArray? {
        if (!file.isFile || file.length() <= 0L) return null
        return JSONArray(file.readText(Charsets.UTF_8))
    }

    /** Renames an unreadable metadata file aside (never deletes it). Returns the kept name, or null if it could not move. */
    private fun quarantine(file: File): String? {
        val kept = File(file.parentFile, "${file.name}.unreadable-${System.currentTimeMillis()}")
        return if (file.renameTo(kept)) kept.name else null
    }

    private fun loadDraftsFromDisk() {
        val storage = draftsStorageDir ?: return
        val file = File(storage, DRAFTS_META_FILE)
        val backup = File(storage, "$DRAFTS_META_FILE.bak")
        val mainPresent = file.isFile && file.length() > 0L
        var array: JSONArray? = null
        var mainUnreadable = false
        if (mainPresent) {
            array = runCatching { readArray(file) }.getOrNull()
            mainUnreadable = array == null
        }
        if (array == null && backup.isFile && backup.length() > 0L) {
            val fromBackup = runCatching { readArray(backup) }.getOrNull()
            if (fromBackup != null) {
                // Set the unreadable main file aside, then put the readable backup in its place.
                val keptMain = if (mainUnreadable) quarantine(file) else null
                if (mainUnreadable && keptMain == null) {
                    persistBlocked = true
                    metadataProblem = "편집본 목록 파일을 읽지 못했습니다. 파일을 덮어쓰지 않도록 저장을 멈췄습니다. 저장 공간을 확인한 뒤 앱을 다시 열어 주세요."
                    return
                }
                if (!file.exists() && !backup.renameTo(file)) runCatching { backup.copyTo(file, overwrite = false) }
                array = fromBackup
                if (keptMain != null) metadataProblem = "최근 편집본 목록 파일이 손상되어 직전 백업으로 복원했습니다. 손상된 파일은 $keptMain 이름으로 보존했습니다."
            } else if (!mainPresent) {
                mainUnreadable = true
            }
        }
        if (array == null) {
            if (!mainUnreadable) return // No metadata at all: a genuinely empty library.
            // Neither the file nor its backup is readable: keep both aside and say so; nothing is treated as deleted.
            val keptMain = if (file.exists()) quarantine(file) else null
            val keptBackup = if (backup.exists()) quarantine(backup) else null
            if ((file.exists() && keptMain == null) || (backup.exists() && keptBackup == null)) {
                persistBlocked = true
                metadataProblem = "편집본 목록 파일을 읽지 못했습니다. 파일을 덮어쓰지 않도록 저장을 멈췄습니다. 저장 공간을 확인한 뒤 앱을 다시 열어 주세요."
                return
            }
            metadataProblem = "편집본 목록 파일을 읽지 못했습니다. 기존 목록 파일(${listOfNotNull(keptMain, keptBackup).joinToString()})과 프로젝트 폴더는 지우지 않고 그대로 보존했습니다."
            return
        }
        var unparsed = 0
        for (i in 0 until array.length()) {
            val raw = array.opt(i) ?: continue
            val parsed = (raw as? JSONObject)?.let { obj -> runCatching { parseDraft(obj, i) }.getOrNull() }
            if (parsed == null || drafts.containsKey(parsed.id)) {
                preservedRawRecords += raw
                unparsed++
            } else {
                drafts[parsed.id] = parsed
            }
        }
        if (unparsed > 0 && metadataProblem == null) {
            metadataProblem = "편집본 ${unparsed}개의 정보를 이 버전에서 읽지 못했습니다. 해당 정보는 지우지 않고 원래 내용 그대로 보관합니다."
        }
    }

    /** Parses one record. Missing folders/files are KEPT (shown as unavailable), never dropped. */
    private fun parseDraft(obj: JSONObject, index: Int): TrailerDraft? {
        val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
        val dirPath = obj.optString("directory").takeIf { it.isNotBlank() } ?: return null
        val directory = File(dirPath)
        val outPath = obj.optString("output")
        val musicTrack = obj.optString("musicFile").takeIf { it.isNotBlank() }?.let(::File)
            ?.let { TrailerMusicTrack(it, obj.optString("musicName", it.name), obj.optDouble("musicDuration", 0.0)) }

        val sourcesArr = obj.optJSONArray("sources") ?: return null
        val sources = mutableListOf<TrailerSource>()
        for (s in 0 until sourcesArr.length()) {
            val sObj = sourcesArr.optJSONObject(s) ?: return null
            val path = sObj.optString("file").takeIf { it.isNotBlank() } ?: return null
            sources.add(
                TrailerSource(
                    id = sObj.optInt("id", s),
                    name = sObj.optString("name", "source-$s"),
                    file = File(path),
                    duration = sObj.optDouble("duration", 0.0),
                    width = sObj.optInt("width", 0),
                    height = sObj.optInt("height", 0),
                    analysis = AudioAnalysisResult(emptyList(), 0.0),
                    thumbnail = null,
                    isImage = sObj.optBoolean("isImage", false),
                    captureTimeMillis = sObj.optLong("captureTimeMillis", 0L),
                    photoDurationRatio = sObj.optDouble("photoDurationRatio", DEFAULT_PHOTO_DURATION_RATIO),
                    photoPolicyVersion = sObj.optInt("photoPolicyVersion", 1),
                    originKey = sObj.optString("originKey").takeIf { it.isNotBlank() },
                )
            )
        }
        if (sources.isEmpty()) return null

        val editor = obj.optJSONObject("editor")?.let { editorObj ->
            val scenesArr = editorObj.optJSONArray("scenes") ?: JSONArray()
            val sourceIds = sources.map { it.id }.toSet()
            val scenes = (0 until scenesArr.length()).mapNotNull { sceneIndex ->
                val sc = scenesArr.optJSONObject(sceneIndex) ?: return@mapNotNull null
                val sourceId = sc.optInt("sourceId", -1)
                if (sourceId !in sourceIds) return@mapNotNull null
                EditorScene(
                    key = sc.optLong("key", sceneIndex + 1L),
                    sourceId = sourceId,
                    start = sc.optDouble("start", 0.0),
                    length = sc.optDouble("length", 1.0),
                    fit = runCatching { SceneFit.valueOf(sc.optString("fit")) }.getOrDefault(SceneFit.FILL),
                    focusX = sc.optDouble("focusX", 0.0).toFloat(),
                    focusY = sc.optDouble("focusY", 0.0).toFloat(),
                    sourceVolume = sc.optDouble("sourceVolume", 1.0).toFloat(),
                    caption = sceneCaptionFromJson(sc.optJSONObject("caption")),
                )
            }
            if (scenes.isEmpty()) null
            else EditorProject(
                scenes = scenes,
                autoPlan = editorObj.optBoolean("autoPlan", true),
                musicVolume = editorObj.optDouble("musicVolume", 1.0).toFloat(),
                nextKey = maxOf(editorObj.optLong("nextKey", 1L), (scenes.maxOf { it.key }) + 1L),
            )
        }

        return TrailerDraft(
            id = id,
            isMusicOverlayMode = obj.optBoolean("isMusicOverlayMode", false),
            directory = directory,
            sources = sources,
            total = obj.optDouble("total", 0.0),
            clipLength = obj.optDouble("clipLength", 4.0),
            photoClipLength = obj.optDouble("photoClipLength", 2.0),
            format = runCatching { TrailerFormat.valueOf(obj.optString("format")) }.getOrDefault(TrailerFormat.ORIGINAL),
            musicTrack = musicTrack,
            musicPolicy = runCatching { TrailerMusicPolicy.valueOf(obj.optString("musicPolicy")) }.getOrDefault(TrailerMusicPolicy.LOOP_MUSIC),
            musicFitCount = obj.optInt("musicFitCount", 1),
            autoDucking = obj.optBoolean("autoDucking", true),
            output = outPath.takeIf { it.isNotBlank() }?.let(::File),
            showingSettings = obj.optBoolean("showingSettings", false),
            orderMode = obj.optString("orderMode", "추가순"),
            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
            customTitle = obj.optString("customTitle").takeIf { it.isNotBlank() },
            editor = editor,
            revision = if (obj.has("revision")) obj.optLong("revision") else null,
            outputRevision = if (obj.has("outputRevision")) obj.optLong("outputRevision") else null,
            isPrivate = obj.optBoolean("isPrivate", false),
        )
    }
}
