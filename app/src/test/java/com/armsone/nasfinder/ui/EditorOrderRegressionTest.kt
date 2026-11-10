package com.armsone.nasfinder.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

class EditorOrderRegressionTest {

    private fun makeSource(
        id: Int,
        name: String,
        captureTimeMillis: Long,
        lastModified: Long,
    ): TrailerSource {
        val file = File.createTempFile("test_source_$id", ".mp4").apply {
            if (lastModified > 0L) setLastModified(lastModified)
            deleteOnExit()
        }
        return TrailerSource(
            id = id,
            name = name,
            file = file,
            duration = 10.0,
            width = 1920,
            height = 1080,
            analysis = AudioAnalysisResult(
                waveform = emptyList(),
                peakTimeSeconds = 0.0,
                peakTimesSeconds = emptyList(),
                audioAvailability = ClipAudioAvailability.Present,
                highlightSource = ClipHighlightSource.Audio,
            ),
            thumbnail = null,
            isImage = false,
            captureTimeMillis = captureTimeMillis,
        )
    }

    @Test
    fun testDefaultOrderModeIsCaptureOrder() {
        val session = TrailerSession(directory = File("/dummy"))
        assertEquals("촬영순", session.orderMode)

        val draft = TrailerDraft(
            isMusicOverlayMode = false,
            directory = File("/dummy"),
            sources = emptyList(),
            total = 60.0,
            clipLength = 4.0,
            photoClipLength = 2.0,
            format = TrailerFormat.ORIGINAL,
            musicTrack = null,
            musicPolicy = TrailerMusicPolicy.LOOP_MUSIC,
            musicFitCount = 1,
            output = null,
        )
        assertEquals("촬영순", draft.orderMode)
    }

    @Test
    fun testProductionSortTrailerSourcesAndFallback() {
        val s1 = makeSource(0, "video_a.mp4", captureTimeMillis = 3000L, lastModified = 5000L)
        val s2 = makeSource(1, "video_b.mp4", captureTimeMillis = 1000L, lastModified = 2000L)
        val s3 = makeSource(2, "video_c.mp4", captureTimeMillis = 0L, lastModified = 6000L)
        val sources = listOf(s1, s2, s3)

        // "촬영순" calls production helper sortTrailerSources
        val sortedCapture = sortTrailerSources(sources, "촬영순")
        // s2 (1000ms) < s1 (3000ms) < s3 (captureTime 0 -> fallback to lastModified 6000ms)
        assertEquals(listOf(s2, s1, s3), sortedCapture)

        // Legacy "시간순" alias yields same result
        assertEquals(sortedCapture, sortTrailerSources(sources, "시간순"))

        // "추가순" sorts by source id
        val addedOrder = sortTrailerSources(sources, "추가순")
        assertEquals(listOf(s1, s2, s3), addedOrder)

        // "가나다순" sorts by NaturalOrderComparator
        val nameOrder = sortTrailerSources(sources, "가나다순")
        assertEquals(listOf(s1, s2, s3), nameOrder)
    }

    @Test
    fun testExplicitRandomStability() {
        val s1 = makeSource(0, "video_a.mp4", 1000L, 1000L)
        val s2 = makeSource(1, "video_b.mp4", 2000L, 2000L)
        val s3 = makeSource(2, "video_c.mp4", 3000L, 3000L)
        val sources = listOf(s1, s2, s3)

        val run1 = sortTrailerSources(sources, "랜덤", Random(42))
        val run2 = sortTrailerSources(sources, "랜덤", Random(42))
        assertEquals(run1, run2)
    }

    @Test
    fun testOrderChangeWithBudgetReserveRestored() {
        val s0 = makeSource(0, "src0.mp4", 1000L, 1000L)
        val s1 = makeSource(1, "src1.mp4", 2000L, 2000L)
        val sources = listOf(s0, s1)

        val scene0_early = EditorScene(key = 1L, sourceId = 0, start = 1.0, length = 3.0)
        val scene0_late = EditorScene(key = 2L, sourceId = 0, start = 5.0, length = 3.0)
        val scene1_early = EditorScene(key = 3L, sourceId = 1, start = 2.0, length = 4.0)
        val scene1_late = EditorScene(key = 4L, sourceId = 1, start = 7.0, length = 2.0)

        // Initial project: scene0_early, scene1_early are active; scene0_late, scene1_late are in reserve
        val initialProject = EditorProject(
            scenes = listOf(scene0_early, scene1_early),
            budgetReserve = listOf(scene0_late, scene1_late),
            budgetOrder = listOf(1L, 2L, 3L, 4L),
        )

        // Reorder sources: s1 first, then s0
        val orderedSources = listOf(s1, s0)
        val reorderedProject = initialProject.withReorderedScenes(orderedSources)

        // Active scenes sorted by new source order without resurrecting reserved clips:
        assertEquals(listOf(scene1_early.key, scene0_early.key), reorderedProject.scenes.map { it.key })
        // Reserved scenes sorted by new source order:
        assertEquals(listOf(scene1_late.key, scene0_late.key), reorderedProject.budgetReserve.map { it.key })
        // budgetOrder contains explicit new order:
        assertEquals(listOf(3L, 4L, 1L, 2L), reorderedProject.budgetOrder)

        // Restoring budget reserve restores clips in the NEW order:
        val restored = reorderedProject.withBudgetReserveRestored()
        assertEquals(listOf(3L, 4L, 1L, 2L), restored.scenes.map { it.key })
        assertTrue(restored.budgetReserve.isEmpty())
        assertTrue(restored.budgetOrder.isEmpty())
    }

    @Test
    fun testDraftOrderModeStoreRoundTrip() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "draft_store_test_${System.currentTimeMillis()}").also { it.mkdirs() }
        try {
            val projectDir = File(tempDir, "project1").also { it.mkdirs() }
            val mediaFile = File(projectDir, "clip.mp4").apply { writeBytes(ByteArray(64)) }
            val source = makeSource(0, "clip.mp4", 1000L, 1000L).copy(file = mediaFile)

            TrailerDraftStore.configureTrailersRoot(tempDir, tempDir)

            val draft = TrailerDraft(
                isMusicOverlayMode = false,
                directory = projectDir,
                sources = listOf(source),
                total = 60.0,
                clipLength = 4.0,
                photoClipLength = 2.0,
                format = TrailerFormat.ORIGINAL,
                musicTrack = null,
                musicPolicy = TrailerMusicPolicy.LOOP_MUSIC,
                musicFitCount = 1,
                output = null,
                orderMode = "가나다순",
            )

            assertTrue(TrailerDraftStore.saveDraft(draft))
            val loaded = TrailerDraftStore.draft(draft.id)
            assertEquals("가나다순", loaded?.orderMode)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
