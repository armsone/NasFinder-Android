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

    @Test
    fun testGeneratedCuesNeverOverrideManualOrTombstones() {
        val manual = TrailerTimedCaptionCue(sourceId = 1, sourceStart = 1.0, sourceEnd = 2.0, text = "직접", origin = CueOrigin.MANUAL, manuallyEdited = true)
        val overlapping = TrailerTimedCaptionCue(sourceId = 1, sourceStart = 1.05, sourceEnd = 2.0, text = "자동", origin = CueOrigin.GENERATED)
        val deleted = TrailerTimedCaptionCue(sourceId = 1, sourceStart = 5.0, sourceEnd = 6.0, text = "삭제된", origin = CueOrigin.GENERATED)
        val fresh = TrailerTimedCaptionCue(sourceId = 1, sourceStart = 8.0, sourceEnd = 9.0, text = "새", origin = CueOrigin.GENERATED)
        val merged = TrailerTimedCaptionCue.merge(listOf(manual), listOf(overlapping, deleted, fresh), listOf(TrailerCueSuppression(1, 5.0, 6.0)))
        assertEquals(listOf("직접", "새"), merged.map { it.text })
        assertEquals(CueOrigin.GENERATED, merged.last().origin)
        assertTrue(!merged.last().manuallyEdited)
    }

    @Test
    fun testCueAndVoiceTakeJsonRoundTrip() {
        val cue = TrailerTimedCaptionCue(sourceId = 3, sourceStart = 1.5, sourceEnd = 2.5, text = "문장", origin = CueOrigin.MANUAL, manuallyEdited = true)
        val back = TrailerTimedCaptionCue.fromJson(cue.toJson())
        assertEquals(cue.copy(style = back!!.style), back)
        val take = TrailerVoiceoverTake(relativeFile = "voice/a.m4a", outputStart = 2.0, sourceIn = 0.0, sourceOut = 3.5, volume = 1.2)
        val takeBack = TrailerVoiceoverTake.fromJson(take.toJson())
        assertEquals(take, takeBack)
        assertEquals(5.5, take.requiredOutputEnd()!!, 1e-9)
        assertEquals(null, TrailerVoiceoverTake(relativeFile = "../x.m4a", sourceOut = 1.0).hardOutputRange())
    }

    @Test
    fun testCueDeleteAndEditLeaveTombstonesSoGeneratedCuesStayGone() {
        val generated = TrailerTimedCaptionCue(sourceId = 1, sourceStart = 2.0, sourceEnd = 3.0, text = "자동", origin = CueOrigin.GENERATED)
        val base = TrailerClipAdjustments(cues = listOf(generated))
        val deleted = deleteCueWithTombstone(base, generated.stableId)
        assertTrue(deleted.cues.isEmpty())
        val regenerated = TrailerTimedCaptionCue.merge(deleted.cues, listOf(generated.copy(stableId = java.util.UUID.randomUUID())), deleted.suppressions)
        assertTrue(regenerated.isEmpty())
        val edited = editCueWithTombstone(base, generated.stableId, "고침", 2.5, 3.5)
        assertEquals(CueOrigin.MANUAL, edited.cues.single().origin)
        assertTrue(edited.cues.single().manuallyEdited)
        assertEquals(1, edited.suppressions.size)
        assertTrue(TrailerTimedCaptionCue.merge(edited.cues, listOf(generated), edited.suppressions).single().text == "고침")
    }

    @Test
    fun testBudgetNarrowsAroundActionPeakNotSourceStart() {
        // Real peak evidence: a verified, sharp spike at 9.0s of a 10s source (waveform index 90 of 100).
        val base = makeSource(0, "a.mp4", 0L, 0L)
        val src = base.copy(analysis = base.analysis.copy(
            waveform = List(100) { if (it == 90) 0.9 else 0.05 },
            peakTimeSeconds = 9.0, peakTimesSeconds = listOf(9.0), verifiedPeakTimesSeconds = listOf(9.0), transientTimesSeconds = listOf(9.0),
        ))
        val scene = EditorScene(key = 1, sourceId = 0, start = 0.0, length = 10.0, actionStart = 8.0, actionEnd = 10.0)
        val fit = fitEditorToBudget(EditorProject(scenes = listOf(scene)), listOf(src), 1.0)!!
        val out = fit.project.scenes.single()
        assertTrue("window ${out.start}+${out.length} must hold the action peak 9.0", out.start <= 9.0 && out.start + out.length >= 9.0)
        assertTrue("action [8,10] narrowed to 1s must not restart at 0 (start ${out.start})", out.start >= 8.0 - 1e-6)
        assertEquals(1.0, out.length, 0.05)
        // A wide action [0,10] still holds the real peak, not the span start.
        val wide = scene.copy(actionStart = 0.0, actionEnd = 10.0)
        val wideOut = fitEditorToBudget(EditorProject(scenes = listOf(wide)), listOf(src), 1.0)!!.project.scenes.single()
        assertTrue("window ${wideOut.start}+${wideOut.length} must hold 9.0", wideOut.start <= 9.0 && wideOut.start + wideOut.length >= 9.0)
        assertTrue(wideOut.start > 0.0)
        // A one-output-frame budget at 2x stays a one-frame (2/30 source) window around the peak, never re-expanded to 0.2s.
        val fast = scene.copy(adjustments = TrailerClipAdjustments(rate = 2.0))
        val tiny = fitEditorToBudget(EditorProject(scenes = listOf(fast)), listOf(src), 1.0 / 30.0)!!.project.scenes.single()
        assertEquals(2.0 / 30.0, tiny.length, 1e-6)
        assertTrue(tiny.start <= 9.0 && tiny.start + tiny.length >= 9.0)
    }

    @Test
    fun adjacentOverlapIsClampedToHalfOfEachOutputDuration() {
        fun m(vararg lens: Double, req: Double) = TrailerClipTimeMappingFactory.buildTimelineMappings(
            lens.mapIndexed { i, l -> TrailerClipInput(i + 1L, 0, 0.0, l, requestedOutgoingOverlap = if (i < lens.size - 1) req else 0.0) })
        val c2 = m(0.5, 0.5, req = 1.0)
        assertEquals(0.25, c2[0].outgoingOverlap, 1e-9)
        assertEquals(0.75, c2[1].outputStart + c2[1].outputDuration, 1e-9)
        val c3 = m(1.0, 1.0, req = 2.0)
        assertEquals(0.5, c3[0].outgoingOverlap, 1e-9)
        assertEquals(1.5, c3[1].outputStart + c3[1].outputDuration, 1e-9)
        val three = m(1.0, 1.0, 1.0, req = 5.0)
        three.forEach { assertTrue(it.incomingOverlap + it.outgoingOverlap <= it.outputDuration + 1e-9) }
        assertEquals(0.5, three[1].incomingOverlap, 1e-9)
        assertEquals(0.5, three[1].outgoingOverlap, 1e-9)
    }
}
