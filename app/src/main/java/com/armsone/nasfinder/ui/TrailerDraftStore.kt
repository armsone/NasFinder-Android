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
    val autoDucking: Boolean,
    val output: File?,
    val showingSettings: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
) {
    val displayTitle: String get() {
        return if (isMusicOverlayMode) {
            val musicName = musicTrack?.name ?: "음악 미선택"
            "음악 입히기 (${sources.size}개 미디어 · $musicName)"
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
    private var trailerDraft: TrailerDraft? = null
    private var musicOverlayDraft: TrailerDraft? = null

    @Synchronized
    fun saveDraft(draft: TrailerDraft) {
        if (!draft.isValid()) return
        if (draft.isMusicOverlayMode) {
            val old = musicOverlayDraft
            if (old != null && old.directory != draft.directory) {
                old.directory.deleteRecursively()
            }
            musicOverlayDraft = draft
        } else {
            val old = trailerDraft
            if (old != null && old.directory != draft.directory) {
                old.directory.deleteRecursively()
            }
            trailerDraft = draft
        }
    }

    @Synchronized
    fun getDraft(isMusicOverlay: Boolean): TrailerDraft? {
        return if (isMusicOverlay) musicOverlayDraft else trailerDraft
    }

    @Synchronized
    fun getAllDrafts(): List<TrailerDraft> {
        val list = mutableListOf<TrailerDraft>()
        trailerDraft?.let { list.add(it) }
        musicOverlayDraft?.let { list.add(it) }
        return list
    }

    @Synchronized
    fun draftCount(): Int {
        var count = 0
        if (trailerDraft != null) count++
        if (musicOverlayDraft != null) count++
        return count
    }

    @Synchronized
    fun hasAnyDraft(): Boolean = trailerDraft != null || musicOverlayDraft != null

    @Synchronized
    fun removeDraft(draft: TrailerDraft) {
        if (trailerDraft?.id == draft.id || trailerDraft?.directory == draft.directory) {
            trailerDraft = null
            draft.directory.deleteRecursively()
        }
        if (musicOverlayDraft?.id == draft.id || musicOverlayDraft?.directory == draft.directory) {
            musicOverlayDraft = null
            draft.directory.deleteRecursively()
        }
    }

    @Synchronized
    fun clearDraft(isMusicOverlay: Boolean) {
        val target = if (isMusicOverlay) musicOverlayDraft else trailerDraft
        target?.directory?.deleteRecursively()
        if (isMusicOverlay) musicOverlayDraft = null else trailerDraft = null
    }

    @Synchronized
    fun isDirectoryManagedAsDraft(directory: File): Boolean {
        return trailerDraft?.directory == directory || musicOverlayDraft?.directory == directory
    }
}
