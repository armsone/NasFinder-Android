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
    val orderMode: String? = "촬영순",
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
    // Master list of all raw entries in exact file order; untouched entries are never re-serialized.
    private val rawRecords = mutableListOf<Any>()
    // Maps accepted draft IDs to their underlying raw record instances in [rawRecords].
    private val acceptedRawRecords = mutableMapOf<String, Any>()
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
    private const val RECOVERY_BACKUP_DIR = "trailer_recovery_backups"
    private const val MAX_RECOVERIES_PER_RUN = 16
    // 로드 시점에 id를 차지한 개정본의 지문. 이후 이름 변경 등과 무관하게 "원래 로드된 개정본"을 식별한다.
    private val loadedFingerprints = mutableMapOf<String, String>()
    // 목록 파일 자체가 복원/격리된 경우(신뢰할 수 없음). 이때는 중복 복구를 하지 않는다.
    private var metadataUntrusted = false

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
        val updated = draft.copy(migrationIssue = null, isPrivate = tainted)
        drafts[draft.id] = updated
        val replaceRaw = existing?.id?.let { acceptedRawRecords[it] }
        if (!persistDraftsToDisk(DraftMutation.Upsert(updated, replaceRaw = replaceRaw, oldDraftId = existing?.id))) {
            drafts.clear()
            drafts.putAll(previous)
            return false
        }
        return true
    }

    @Synchronized
    fun detachDraft(draftId: String): TrailerDraft? {
        val target = drafts.remove(draftId) ?: return null
        val targetRaw = acceptedRawRecords[draftId]
        if (!persistDraftsToDisk(DraftMutation.Remove(targetRaw = targetRaw, draftId = draftId))) {
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
        val updated = existing.copy(isPrivate = isPrivate)
        drafts[id] = updated
        val targetRaw = acceptedRawRecords[id]
        if (!persistDraftsToDisk(DraftMutation.Upsert(updated, replaceRaw = targetRaw, oldDraftId = id))) {
            drafts[id] = existing
            return false
        }
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
        val targetRaw = acceptedRawRecords[target.id]
        if (!persistDraftsToDisk(DraftMutation.Remove(targetRaw = targetRaw, draftId = target.id))) {
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
        val updated = existing.copy(customTitle = newTitle.ifBlank { null })
        drafts[draftId] = updated
        val targetRaw = acceptedRawRecords[draftId]
        if (!persistDraftsToDisk(DraftMutation.Upsert(updated, replaceRaw = targetRaw, oldDraftId = draftId))) {
            drafts[draftId] = existing
        }
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
        if (metadataUntrusted || persistBlocked) return
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
        val targetRaw = acceptedRawRecords[original.id]
        if (!persistDraftsToDisk(DraftMutation.Upsert(migrated, replaceRaw = targetRaw, oldDraftId = original.id))) {
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
    // Duplicate-id revision recovery.
    // ---------------------------------------------------------------------------------------------

    class DuplicateRecoveryResult(val recovered: Int, val failed: Int, val exactDuplicatesLeftRaw: Int)

    private class RecoveryPlan(val raw: JSONObject, val parsed: TrailerDraft, val fingerprint: String)

    private fun fingerprintOf(draft: TrailerDraft): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(draftJson(draft).toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * Recovers raw records whose id is already held by another draft but whose content differs: each is copied into its
     * own new project folder and listed as a new draft ("(복구)"). Run on a background thread, after
     * [cleanupInterruptedMigrations]. Exact duplicates of the loaded revision are left in raw untouched. A raw record is
     * removed only after an exact copy was written to a separate recovery backup file and the metadata commit succeeded;
     * any failure keeps the raw record and original media as they were. Original media are never deleted.
     */
    fun recoverDuplicateRevisions(inUse: (File) -> Boolean): DuplicateRecoveryResult {
        val root: File
        val plans: List<RecoveryPlan>
        var exactLeft = 0
        synchronized(this) {
            if (metadataUntrusted || persistBlocked || draftsStorageDir == null) return DuplicateRecoveryResult(0, 0, 0)
            root = canonicalDurableRoot?.let(::File) ?: return DuplicateRecoveryResult(0, 0, 0)
            val seen = mutableMapOf<String, MutableSet<String>>()
            loadedFingerprints.forEach { (id, fp) -> if (drafts.containsKey(id)) seen[id] = mutableSetOf(fp) }
            val candidates = mutableListOf<RecoveryPlan>()
            for (raw in preservedRawRecords) {
                val obj = raw as? JSONObject ?: continue
                val parsed = runCatching { parseDraft(obj, -1) }.getOrNull() ?: continue
                val known = seen[parsed.id] ?: continue
                val fp = fingerprintOf(parsed)
                if (known.add(fp)) candidates += RecoveryPlan(obj, parsed, fp) else exactLeft++
            }
            plans = candidates.take(MAX_RECOVERIES_PER_RUN)
        }
        var recovered = 0
        var failed = 0
        plans.forEach { plan ->
            if (runCatching { recoverOne(plan, root, inUse) }.getOrDefault(false)) recovered++ else failed++
        }
        return DuplicateRecoveryResult(recovered, failed, exactLeft)
    }

    private fun recoverOne(plan: RecoveryPlan, root: File, inUse: (File) -> Boolean): Boolean {
        val newId = UUID.nameUUIDFromBytes("recovered:${plan.fingerprint}".toByteArray()).toString()
        val dirName = UUID.nameUUIDFromBytes("recovered-dir:${plan.fingerprint}".toByteArray()).toString()
        // Already committed earlier (raw not yet removed): no second copy, only the raw bookkeeping.
        if (synchronized(this) { drafts.containsKey(newId) }) return commitRecovery(plan, null, newId)
        if (runCatching { inUse(plan.parsed.directory) }.getOrDefault(true)) return false
        if (!plan.parsed.isValid()) return false
        val newDir = File(root, dirName)
        // A leftover folder of an interrupted attempt is removed by cleanupInterruptedMigrations; never reuse it here.
        if (newDir.exists()) return false
        val copied = copyDraftForRecovery(plan.parsed.copy(id = newId), newDir) ?: return false
        return commitRecovery(plan, copied, newId)
    }

    /** Copies the owned files (those inside the old folder, nested paths kept) into a NEW marked folder; null on any failure. */
    private fun copyDraftForRecovery(original: TrailerDraft, newDir: File): TrailerDraft? {
        if (!newDir.mkdirs()) return null
        return try {
            File(newDir, MIGRATION_MARKER).writeText(original.id)
            val oldRoot = original.directory.canonicalFile
            val newRoot = newDir.canonicalFile
            val usedPaths = mutableSetOf(MIGRATION_MARKER)
            val copiedBySource = mutableMapOf<String, File>()
            fun copyOwned(file: File): File {
                if (!isInside(file, original.directory)) return file
                val canonical = file.canonicalFile
                copiedBySource[canonical.path]?.let { return it }
                var relative = canonical.relativeTo(oldRoot).path
                if (relative.isBlank() || relative.startsWith("..")) return file
                if (relative.endsWith(".part")) relative += ".media"
                var index = 1
                while (!usedPaths.add(relative)) {
                    val parent = canonical.relativeTo(oldRoot).parent
                    relative = (if (parent != null) parent + File.separator else "") + "${index++}-${canonical.name.removeSuffix(".part")}"
                }
                val destination = File(newRoot, relative)
                check(destination.canonicalPath.startsWith(newRoot.path + File.separator)) { "복사 경로가 폴더를 벗어납니다: $relative" }
                destination.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "하위 폴더를 만들지 못했습니다." } }
                return copyValidated(canonical, destination).also { copiedBySource[canonical.path] = it }
            }
            val sources = original.sources.map { it.copy(file = copyOwned(it.file)) }
            val output = original.output?.let { out ->
                when {
                    !isInside(out, original.directory) -> out
                    out.isFile && out.length() > 0L -> copyOwned(out)
                    else -> null
                }
            }
            val music = original.musicTrack?.let { track ->
                if (track.file.isFile && track.file.length() > 0L) track.copy(file = copyOwned(track.file)) else track
            }
            val voiceovers = original.editor?.voiceovers?.map { take ->
                val voiceFile = File(original.directory, take.relativeFile)
                if (voiceFile.isFile && isInside(voiceFile, original.directory)) {
                    val copiedFile = copyOwned(voiceFile)
                    val newRelative = copiedFile.relativeTo(newDir).path
                    take.copy(relativeFile = newRelative)
                } else {
                    take
                }
            }.orEmpty()
            val editor = original.editor?.copy(voiceovers = voiceovers)
            original.copy(
                directory = newDir, sources = sources, output = output, musicTrack = music,
                outputRevision = if (output != null) original.outputRevision else null,
                migrationIssue = null,
                editor = editor,
            )
        } catch (_: Exception) {
            synchronized(this) { safeDeletePrivateTrailerDirectory(newDir) }
            null
        }
    }

    /** Writes the exact raw record to its own backup file (never overwritten with different content). */
    private fun writeRecoveryBackup(raw: JSONObject, name: String): Boolean = runCatching {
        val storage = draftsStorageDir ?: return@runCatching false
        val dir = File(storage, RECOVERY_BACKUP_DIR).also { it.mkdirs() }
        val target = File(dir, "$name.json")
        val content = JSONArray().put(raw).toString(2)
        if (target.exists()) return@runCatching target.readText(Charsets.UTF_8) == content
        val temp = File(dir, "$name.json.tmp")
        FileOutputStream(temp).use { fos ->
            fos.write(content.toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
        if (temp.renameTo(target)) true else { temp.delete(); false }
    }.getOrDefault(false)

    @Synchronized
    private fun commitRecovery(plan: RecoveryPlan, copied: TrailerDraft?, newId: String): Boolean {
        fun discardCopy() { copied?.let { safeDeletePrivateTrailerDirectory(it.directory) } }
        val rawIndex = preservedRawRecords.indexOfFirst { it === plan.raw }
        val winner = drafts[plan.parsed.id]
        if (rawIndex < 0 || winner == null || (copied == null && !drafts.containsKey(newId))) { discardCopy(); return false }
        // Exact backup first; without it the raw record stays and nothing is replaced.
        if (!writeRecoveryBackup(plan.raw, "$newId-${plan.fingerprint.take(16)}")) { discardCopy(); return false }
        val previousDrafts = drafts.toMap()
        val previousRaw = preservedRawRecords.toList()
        val recoveredDraft = if (copied != null) {
            val sharesPrivateFolder = drafts.values.any { sameDirectory(it.directory, plan.parsed.directory) && it.isPrivate }
            copied.copy(
                customTitle = "${plan.parsed.displayTitle} (복구)",
                isPrivate = plan.parsed.isPrivate || winner.isPrivate || sharesPrivateFolder,
            ).also { drafts[newId] = it }
        } else null
        preservedRawRecords.removeAt(rawIndex)
        val mutation = if (recoveredDraft != null) {
            DraftMutation.Upsert(recoveredDraft, replaceRaw = plan.raw, oldDraftId = null)
        } else {
            DraftMutation.Remove(targetRaw = plan.raw, draftId = null)
        }
        if (!persistDraftsToDisk(mutation)) {
            drafts.clear(); drafts.putAll(previousDrafts)
            preservedRawRecords.clear(); preservedRawRecords.addAll(previousRaw)
            discardCopy() // only the folder this call created; no draft references it after the rollback
            return false
        }
        copied?.let { File(it.directory, MIGRATION_MARKER).delete() }
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
        // Marks scenes saved after protection existed, so a manual trim (no anchors) is never re-protected on reload.
        put("actionAnchored", true)
        if (scene.actionStart != null && scene.actionEnd != null) {
            put("actionStart", scene.actionStart)
            put("actionEnd", scene.actionEnd)
        }
        if (scene.excluded) put("excluded", true)
        if (scene.budgetExcluded) put("budgetExcluded", true)
        put("automationProtected", scene.automationProtected)
        if (!scene.manual.isEmpty()) put("manual", scene.manual.rawMask)
        scene.adjustments?.let { put("adjustments", it.toJson()) }
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
                editor.budgetTarget?.let { put("budgetTarget", it) }
                if (editor.clipChoiceExplicit) put("clipChoiceExplicit", true)
                if (editor.budgetReserve.isNotEmpty()) {
                    put("budgetReserve", JSONArray().apply { editor.budgetReserve.forEach { put(sceneJson(it)) } })
                    put("budgetOrder", JSONArray().apply { editor.budgetOrder.forEach { put(it) } })
                }
                if (editor.introCard.enabled || editor.introCard.title.isNotEmpty() || editor.introCard.subtitle.isNotEmpty() || editor.introCard.duration != 3.0 || !editor.introCard.usesFirstOrLastImage || editor.introCard.backgroundSourceId != null || editor.introCard.backgroundTimestamp != null || editor.introCard.titleStyle != null || editor.introCard.subtitleStyle != null) {
                    put("introCard", editor.introCard.toJson())
                }
                if (editor.outroCard.enabled || editor.outroCard.title.isNotEmpty() || editor.outroCard.subtitle.isNotEmpty() || editor.outroCard.duration != 3.0 || !editor.outroCard.usesFirstOrLastImage || editor.outroCard.backgroundSourceId != null || editor.outroCard.backgroundTimestamp != null || editor.outroCard.titleStyle != null || editor.outroCard.subtitleStyle != null) {
                    put("outroCard", editor.outroCard.toJson())
                }
                if (editor.voiceovers.isNotEmpty()) {
                    put("voiceovers", JSONArray().apply { editor.voiceovers.forEach { put(it.toJson()) } })
                }
                editor.batchCaption?.let { put("batchCaption", it.toJson()) }
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
                if (src.isImportant) put("isImportant", true)
                storedAnalysisJson(src.analysis)?.let { put("analysis", it) }
            })
        }
        put("sources", sourcesArr)
    }

    private sealed class DraftMutation {
        data class Upsert(val draft: TrailerDraft, val replaceRaw: Any? = null, val oldDraftId: String? = null) : DraftMutation()
        data class Remove(val targetRaw: Any?, val draftId: String? = null) : DraftMutation()
    }

    /** Writes metadata atomically, preserving untouched raw records and modifying only the target entry. */
    private fun persistDraftsToDisk(mutation: DraftMutation): Boolean {
        if (persistBlocked) return false
        val storage = draftsStorageDir ?: return false
        val target = File(storage, DRAFTS_META_FILE)
        val temp = File(storage, "$DRAFTS_META_FILE.tmp")
        val backup = File(storage, "$DRAFTS_META_FILE.bak")
        return runCatching {
            val nextRawRecords = rawRecords.toMutableList()
            val nextAcceptedRaw = acceptedRawRecords.toMutableMap()

            when (mutation) {
                is DraftMutation.Upsert -> {
                    val newJson = draftJson(mutation.draft)
                    val replaceTarget = mutation.replaceRaw ?: nextAcceptedRaw[mutation.draft.id]
                    val replaceIndex = replaceTarget?.let { raw -> nextRawRecords.indexOfFirst { it === raw } } ?: -1
                    if (replaceIndex >= 0) {
                        nextRawRecords[replaceIndex] = newJson
                    } else {
                        nextRawRecords.add(newJson)
                    }
                    if (mutation.oldDraftId != null && mutation.oldDraftId != mutation.draft.id) {
                        nextAcceptedRaw.remove(mutation.oldDraftId)
                    }
                    nextAcceptedRaw[mutation.draft.id] = newJson
                }
                is DraftMutation.Remove -> {
                    val removeTarget = mutation.targetRaw ?: mutation.draftId?.let { nextAcceptedRaw[it] }
                    val removeIndex = removeTarget?.let { raw -> nextRawRecords.indexOfFirst { it === raw } } ?: -1
                    if (removeIndex >= 0) {
                        nextRawRecords.removeAt(removeIndex)
                    }
                    if (mutation.draftId != null) {
                        nextAcceptedRaw.remove(mutation.draftId)
                    }
                }
            }

            val array = JSONArray()
            nextRawRecords.forEach { array.put(it) }
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

            rawRecords.clear()
            rawRecords.addAll(nextRawRecords)
            acceptedRawRecords.clear()
            acceptedRawRecords.putAll(nextAcceptedRaw)
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
        // A repeated configure (a new activity/view model in the same process) reloads the saved list instead of treating every
        // record as a duplicate of the copy already in memory (which would then be written back twice on the next save).
        drafts.clear()
        preservedRawRecords.clear()
        rawRecords.clear()
        acceptedRawRecords.clear()
        loadedFingerprints.clear()
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
                    metadataUntrusted = true
                    metadataProblem = "편집본 목록 파일을 읽지 못했습니다. 파일을 덮어쓰지 않도록 저장을 멈췄습니다. 저장 공간을 확인한 뒤 앱을 다시 열어 주세요."
                    return
                }
                if (!file.exists() && !backup.renameTo(file)) runCatching { backup.copyTo(file, overwrite = false) }
                array = fromBackup
                if (keptMain != null) metadataUntrusted = true
                if (keptMain != null) metadataProblem = "최근 편집본 목록 파일이 손상되어 직전 백업으로 복원했습니다. 손상된 파일은 $keptMain 이름으로 보존했습니다."
            } else if (!mainPresent) {
                mainUnreadable = true
            }
        }
        if (array == null) {
            if (!mainUnreadable) return // No metadata at all: a genuinely empty library.
            metadataUntrusted = true
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
        var duplicateIds = 0
        for (i in 0 until array.length()) {
            val raw = array.opt(i) ?: continue
            rawRecords += raw
            val parsed = (raw as? JSONObject)?.let { obj -> runCatching { parseDraft(obj, i) }.getOrNull() }
            if (parsed == null || drafts.containsKey(parsed.id)) {
                preservedRawRecords += raw
                if (parsed == null) unparsed++ else duplicateIds++
            } else {
                drafts[parsed.id] = parsed
                loadedFingerprints[parsed.id] = fingerprintOf(parsed)
                acceptedRawRecords[parsed.id] = raw
            }
        }
        if (unparsed > 0 && metadataProblem == null) {
            metadataProblem = "편집본 ${unparsed}개의 정보를 이 버전에서 읽지 못했습니다. 해당 정보는 지우지 않고 원래 내용 그대로 보관합니다."
        } else if (duplicateIds > 0 && metadataProblem == null) {
            metadataProblem = "같은 식별자를 가진 편집본 기록 ${duplicateIds}개를 따로 보관했습니다."
        }
    }

    /** Parses one record. Missing folders/files are KEPT (shown as unavailable), never dropped. */
    private fun parseDraft(obj: JSONObject, index: Int): TrailerDraft? {
        val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
        val dirPath = obj.optString("directory").takeIf { it.isNotBlank() } ?: return null
        val directory = File(dirPath)
        val outPath = obj.optString("output")
        val musicTrack = obj.optString("musicFile").takeIf { it.isNotBlank() }?.let(::File)
            ?.let { TrailerMusicTrack(it, obj.optString("musicName", it.name), AudioAnalysisService.playableMusicSeconds(it, obj.optDouble("musicDuration", 0.0))) }

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
                    analysis = storedAnalysis(sObj),
                    thumbnail = null,
                    isImage = sObj.optBoolean("isImage", false),
                    captureTimeMillis = sObj.optLong("captureTimeMillis", 0L),
                    photoDurationRatio = sObj.optDouble("photoDurationRatio", DEFAULT_PHOTO_DURATION_RATIO),
                    photoPolicyVersion = sObj.optInt("photoPolicyVersion", 1),
                    originKey = sObj.optString("originKey").takeIf { it.isNotBlank() },
                    isImportant = sObj.optBoolean("isImportant", false),
                )
            )
        }
        if (sources.isEmpty()) return null

        val editor = obj.optJSONObject("editor")?.let { editorObj ->
            val sourceIds = sources.map { it.id }.toSet()
            fun scenesFrom(scenesArr: JSONArray) = (0 until scenesArr.length()).mapNotNull { sceneIndex ->
                val sc = scenesArr.optJSONObject(sceneIndex) ?: return@mapNotNull null
                val sourceId = sc.optInt("sourceId", -1)
                if (sourceId !in sourceIds) return@mapNotNull null
                val isExcluded = sc.optBoolean("excluded", false)
                val budgetFlag = sc.optBoolean("budgetExcluded", false)
                val isAutoProtected = if (sc.has("automationProtected")) sc.optBoolean("automationProtected") else true
                val manualFields = if (sc.has("manual")) ManualFields.fromInt(sc.optInt("manual", 0)) else ManualFields.NONE
                val adjustments = sc.optJSONObject("adjustments")?.let { TrailerClipAdjustments.fromJson(it) }

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
                    actionStart = storedActionInterval(sc)?.first,
                    actionEnd = storedActionInterval(sc)?.second,
                    excluded = isExcluded,
                    budgetExcluded = isExcluded && budgetFlag,
                    automationProtected = isAutoProtected,
                    manual = manualFields,
                    adjustments = adjustments,
                ).let { scene ->
                    // 레거시 초안(보호 구간 필드 없음)의 영상 장면은 저장된 창 전체를 보호 구간으로 이관한다(수동 트림 포함, 보수적).
                    if (!sc.has("actionAnchored") && scene.actionStart == null && scene.actionEnd == null && sources.firstOrNull { it.id == sourceId }?.isImage == false &&
                        scene.start.isFinite() && scene.length.isFinite() && scene.length > 0.0
                    ) scene.copy(actionStart = scene.start, actionEnd = scene.start + scene.length) else scene
                }
            }
            val scenes = scenesFrom(editorObj.optJSONArray("scenes") ?: JSONArray())
            // Scenes a budget fit left out (2.39.82+); older drafts have none. Keys must stay unique next to the scenes.
            val sceneKeys = scenes.map { it.key }.toSet()
            val budgetReserve = scenesFrom(editorObj.optJSONArray("budgetReserve") ?: JSONArray()).filter { it.key !in sceneKeys }.distinctBy { it.key }
            val budgetOrder = editorObj.optJSONArray("budgetOrder")?.let { arr -> (0 until arr.length()).map { arr.optLong(it, -1L) } }.orEmpty()
            val introCard = editorObj.optJSONObject("introCard")?.let { TrailerTitleCard.fromJson(it) } ?: TrailerTitleCard()
            val outroCard = editorObj.optJSONObject("outroCard")?.let { TrailerTitleCard.fromJson(it) } ?: TrailerTitleCard()
            val voiceovers = (editorObj.optJSONArray("voiceovers") ?: obj.optJSONArray("voiceovers"))?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { TrailerVoiceoverTake.fromJson(it) }
                }
            }.orEmpty()
            val batchCaption = sceneCaptionFromJson(editorObj.optJSONObject("batchCaption") ?: obj.optJSONObject("batchCaption"), allowBlank = true)
            if (scenes.isEmpty()) null
            else EditorProject(
                scenes = scenes,
                autoPlan = editorObj.optBoolean("autoPlan", true),
                musicVolume = editorObj.optDouble("musicVolume", 1.0).toFloat(),
                nextKey = maxOf(editorObj.optLong("nextKey", 1L), ((scenes + budgetReserve).maxOf { it.key }) + 1L),
                introCard = introCard,
                outroCard = outroCard,
                budgetReserve = budgetReserve,
                budgetOrder = if (budgetReserve.isEmpty()) emptyList() else budgetOrder,
                budgetTarget = editorObj.optDouble("budgetTarget", Double.NaN).takeIf { it.isFinite() && it > 0.0 },
                clipChoiceExplicit = editorObj.optBoolean("clipChoiceExplicit", false),
                voiceovers = voiceovers,
                batchCaption = batchCaption,
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
            orderMode = obj.optString("orderMode", "촬영순"),
            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
            customTitle = obj.optString("customTitle").takeIf { it.isNotBlank() },
            editor = editor,
            revision = if (obj.has("revision")) obj.optLong("revision") else null,
            outputRevision = if (obj.has("outputRevision")) obj.optLong("outputRevision") else null,
            isPrivate = obj.optBoolean("isPrivate", false),
        )
    }
}

/** 저장된 동작 구간은 둘 다 있고 유한하며 순서가 맞을 때만 읽는다. 아니면 보호 없음(구버전과 같음). */
private fun storedActionInterval(scene: JSONObject): Pair<Double, Double>? {
    if (!scene.has("actionStart") || !scene.has("actionEnd")) return null
    val start = scene.optDouble("actionStart", Double.NaN)
    val end = scene.optDouble("actionEnd", Double.NaN)
    return if (start.isFinite() && end.isFinite() && start >= 0.0 && end > start) start to end else null
}

// 소스 분석(파형·피크·검증 피크·고립 타격)을 함께 저장해, 다시 열자마자 재추천/초기화해도 조용한 타격 증거가 살아 있게 한다.
// 파형은 줄바꿈 들여쓰기로 파일이 부풀지 않게 쉼표 문자열로 쓴다. 유한하지 않은 값이 있으면 저장하지 않는다(레거시와 같이 재분석).
private const val MAX_STORED_WAVEFORM = 4096

private fun storedAnalysisJson(analysis: AudioAnalysisResult): JSONObject? {
    val lists = listOf(analysis.peakTimesSeconds, analysis.verifiedPeakTimesSeconds, analysis.transientTimesSeconds)
    if (analysis.waveform.isEmpty() || analysis.waveform.size > MAX_STORED_WAVEFORM) return null
    if (!analysis.peakTimeSeconds.isFinite() || analysis.waveform.any { !it.isFinite() } || lists.any { l -> l.any { !it.isFinite() } }) return null
    fun rounded(value: Double) = Math.round(value * 1e4) / 1e4
    fun times(values: List<Double>) = JSONArray().apply { values.forEach { put(rounded(it)) } }
    return JSONObject().apply {
        put("waveform", analysis.waveform.joinToString(",") { rounded(it).toString() })
        put("peakTime", rounded(analysis.peakTimeSeconds))
        put("peakTimes", times(analysis.peakTimesSeconds))
        put("verifiedPeakTimes", times(analysis.verifiedPeakTimesSeconds))
        put("transientTimes", times(analysis.transientTimesSeconds))
        put("audioAvailability", analysis.audioAvailability.name)
        put("highlightSource", analysis.highlightSource.name)
    }
}

/** 저장된 분석은 전부 유효할 때만 쓴다. 없거나(레거시) 깨졌으면 빈 분석 = 열린 뒤 다시 분석(needsAnalysis). */
private fun storedAnalysis(source: JSONObject): AudioAnalysisResult {
    val none = AudioAnalysisResult(emptyList(), 0.0)
    val stored = source.optJSONObject("analysis") ?: return none
    val duration = source.optDouble("duration", 0.0)
    if (!duration.isFinite() || duration <= 0.0) return none
    val waveform = stored.optString("waveform").takeIf { it.isNotEmpty() }?.split(',')?.map { it.toDoubleOrNull() ?: return none }
        ?: return none
    if (waveform.size > MAX_STORED_WAVEFORM || waveform.any { !it.isFinite() || it < 0.0 }) return none
    fun times(key: String): List<Double>? {
        val array = stored.optJSONArray(key) ?: return emptyList()
        if (array.length() > MAX_STORED_WAVEFORM) return null
        return (0 until array.length()).map { i ->
            array.optDouble(i, Double.NaN).takeIf { it.isFinite() && it >= 0.0 && it <= duration + 1e-6 } ?: return null
        }
    }
    val peakTime = stored.optDouble("peakTime", Double.NaN).takeIf { it.isFinite() && it >= 0.0 && it <= duration + 1e-6 } ?: return none
    val peakTimes = times("peakTimes") ?: return none
    val verified = times("verifiedPeakTimes") ?: return none
    val transients = times("transientTimes") ?: return none
    val availability = runCatching { ClipAudioAvailability.valueOf(stored.optString("audioAvailability")) }.getOrNull() ?: return none
    val highlight = runCatching { ClipHighlightSource.valueOf(stored.optString("highlightSource")) }.getOrNull() ?: return none
    return AudioAnalysisResult(
        waveform = waveform,
        peakTimeSeconds = peakTime,
        peakTimesSeconds = peakTimes.ifEmpty { listOf(peakTime) },
        audioAvailability = availability,
        highlightSource = highlight,
        verifiedPeakTimesSeconds = verified,
        transientTimesSeconds = transients,
    )
}
