package com.armsone.nasfinder.ui

import java.io.File
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
    val timestamp: Long = System.currentTimeMillis(),
) {
    val displayTitle: String get() {
        return if (isMusicOverlayMode) {
            val musicName = musicTrack?.name ?: "음악 미선택"
            "뮤직비디오 (${sources.size}개 미디어 · $musicName)"
        } else {
            val validOutput = output?.takeIf { it.exists() && it.length() > 0 }
            if (validOutput != null) "완성된 예고편 (${sources.size}개 미디어)"
            else "예고편 편집 (${sources.size}개 미디어)"
        }
    }

    fun isValid(): Boolean {
        return directory.exists() && directory.isDirectory &&
                sources.isNotEmpty() && sources.all { it.file.exists() && it.file.length() > 0 }
    }
}

object TrailerDraftStore {
    private val drafts = mutableMapOf<String, TrailerDraft>()
    private var canonicalTrailersRoot: String? = null

    @Synchronized
    fun configureTrailersRoot(cacheDir: File) {
        canonicalTrailersRoot = runCatching { File(cacheDir, "trailers").canonicalPath }.getOrNull()
    }

    @Synchronized
    fun saveDraft(draft: TrailerDraft) {
        if (!draft.isValid()) return
        val existing = drafts.values.firstOrNull { it.id == draft.id || it.directory.canonicalPath == draft.directory.canonicalPath }
        if (existing != null) {
            drafts.remove(existing.id)
            if (existing.directory.canonicalPath != draft.directory.canonicalPath) {
                safeDeletePrivateTrailerDirectory(existing.directory)
            }
        }
        drafts[draft.id] = draft
    }

    @Synchronized
    fun detachDraft(draftId: String): TrailerDraft? {
        val target = drafts.remove(draftId)
        return target
    }

    @Synchronized
    fun getAllDrafts(): List<TrailerDraft> {
        return drafts.values.sortedByDescending { it.timestamp }
    }

    @Synchronized
    fun draftCount(): Int {
        return drafts.size
    }

    @Synchronized
    fun hasAnyDraft(): Boolean = drafts.isNotEmpty()

    @Synchronized
    fun removeDraft(draft: TrailerDraft) {
        val target = drafts.remove(draft.id)
            ?: drafts.values.firstOrNull { it.directory.canonicalPath == draft.directory.canonicalPath }?.also { drafts.remove(it.id) }
        val dirToDelete = target?.directory ?: return
        safeDeletePrivateTrailerDirectory(dirToDelete)
    }

    @Synchronized
    fun isDirectoryManagedAsDraft(directory: File): Boolean {
        val canonical = runCatching { directory.canonicalPath }.getOrNull() ?: directory.absolutePath
        return drafts.values.any { runCatching { it.directory.canonicalPath }.getOrNull() == canonical }
    }

    fun isSafePrivateTrailerDirectory(directory: File): Boolean {
        val root = canonicalTrailersRoot ?: return false
        return runCatching {
            val canonicalDir = directory.canonicalFile
            val canonicalParent = canonicalDir.parentFile ?: return false
            if (canonicalParent.canonicalPath != root) return false
            if (canonicalParent.name != "trailers") return false
            val canonicalPath = canonicalDir.canonicalPath
            if (!canonicalPath.contains("/trailers/") || canonicalPath.endsWith("/trailers")) return false
            if (canonicalPath.contains("/phonehard") || canonicalPath.contains("/files/phonehard")) return false

            // UUID leaf guard: trailer session directories are generated as UUIDs (using canonicalDir.name)
            val leafName = canonicalDir.name
            runCatching { UUID.fromString(leafName) }.isSuccess
        }.getOrDefault(false)
    }

    @Synchronized
    fun safeDeletePrivateTrailerDirectory(directory: File): Boolean {
        if (!isSafePrivateTrailerDirectory(directory)) return false
        val canonical = runCatching { directory.canonicalPath }.getOrNull()
        if (canonical != null && drafts.values.any { runCatching { it.directory.canonicalPath }.getOrNull() == canonical }) {
            return false
        }
        return directory.deleteRecursively()
    }
}
