package com.armsone.nasfinder.ui

import java.time.ZoneId
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Samples the whole camera path (every 1% of the scene) the way the exporter renders it: through the GL matrix
 * applied to photo NDC. 채우기 must cover the output with photo pixels in EVERY frame (no letterbox, whatever the
 * people/analysis/focus/length), start at a stable random framing, show every separated main person at some time
 * and end framed on the primary person (else main subject, else center). Explicit 맞추기 keeps the whole photo.
 * Time-aware: at every instant the rendered camera stays under the pan/zoom rate limits, so short scenes move
 * gently near their target and only long scenes travel to far people.
 */
class PhotoMotionGeometryTest {
    private val samples = (0..100).map { it / 100f }
    private val seeds = (0..7).toList()

    private fun plan(
        photoW: Int, photoH: Int, outW: Int, outH: Int,
        faces: List<PhotoNormRect> = emptyList(),
        objects: List<PhotoNormRect> = emptyList(),
        reliable: Boolean = true,
        fit: SceneFit = SceneFit.FILL,
        focusX: Float = 0f,
        focusY: Float = 0f,
        duration: Double = 4.0,
        seed: Int = 0,
    ) = PhotoMotionGeometry.plan(photoW, photoH, outW, outH, fit, focusX, focusY, duration, faces, objects, reliable, seed)

    /** Photo-normalized point -> output NDC through the same matrix the GL effect uses. */
    private fun toOutput(m: FloatArray, u: Float, v: Float): Pair<Float, Float> {
        val x = 2f * u - 1f
        val y = 1f - 2f * v
        return (m[0] * x + m[12]) to (m[5] * y + m[13])
    }

    private fun visibleAt(plan: PhotoMotionPlan, s: Float, pa: Float, oa: Float, rect: PhotoNormRect): Boolean {
        val m = plan.viewportAt(s, pa, oa).toGlMatrix()
        return listOf(rect.left to rect.top, rect.right to rect.top, rect.left to rect.bottom, rect.right to rect.bottom).all { (u, v) ->
            val (x, y) = toOutput(m, u, v)
            x in -1.0001f..1.0001f && y in -1.0001f..1.0001f
        }
    }

    /** 채우기: in every sampled frame the projected photo edges lie on or outside the output frame. */
    private fun assertCoversEveryFrame(plan: PhotoMotionPlan, pa: Float, oa: Float) {
        for (s in samples) {
            val m = plan.viewportAt(s, pa, oa).toGlMatrix()
            val (left, top) = toOutput(m, 0f, 0f)
            val (right, bottom) = toOutput(m, 1f, 1f)
            assertTrue("photo left edge visible at s=$s ($left) ${plan.mode}", left <= -0.9999f)
            assertTrue("photo right edge visible at s=$s ($right) ${plan.mode}", right >= 0.9999f)
            assertTrue("photo top edge visible at s=$s ($top) ${plan.mode}", top >= 0.9999f)
            assertTrue("photo bottom edge visible at s=$s ($bottom) ${plan.mode}", bottom <= -0.9999f)
        }
    }

    private fun assertSeenSometime(plan: PhotoMotionPlan, pa: Float, oa: Float, rect: PhotoNormRect) {
        assertTrue("never fully shown: $rect ${plan.reason}", samples.any { visibleAt(plan, it, pa, oa, rect) })
    }

    /** Relative zoom range and center travel (in viewport units) over the whole path. */
    private fun zoomRange(plan: PhotoMotionPlan, pa: Float, oa: Float): Float {
        val widths = samples.map { plan.viewportAt(it, pa, oa).width }
        return (widths.max() - widths.min()) / widths.max()
    }

    private fun travelX(plan: PhotoMotionPlan, pa: Float, oa: Float): Float {
        val xs = samples.map { plan.viewportAt(it, pa, oa).let { v -> v.centerX / v.width } }
        return xs.max() - xs.min()
    }

    private fun travelY(plan: PhotoMotionPlan, pa: Float, oa: Float): Float {
        val ys = samples.map { plan.viewportAt(it, pa, oa).let { v -> v.centerY / v.height } }
        return ys.max() - ys.min()
    }

    /** Fastest pan (viewport widths/heights per second) and zoom (|d ln width|/s) of the rendered path. */
    private fun peakRates(plan: PhotoMotionPlan, seconds: Double, pa: Float, oa: Float): Pair<Double, Double> {
        val n = 2000
        var prev = plan.viewportAt(0f, pa, oa)
        var pan = 0.0
        var zoom = 0.0
        for (i in 1..n) {
            val v = plan.viewportAt(i.toFloat() / n, pa, oa)
            val dt = seconds / n
            pan = maxOf(pan, maxOf(abs(v.centerX - prev.centerX) / minOf(v.width, prev.width), abs(v.centerY - prev.centerY) / minOf(v.height, prev.height)) / dt)
            zoom = maxOf(zoom, abs(kotlin.math.ln(v.width / prev.width)) / dt)
            prev = v
        }
        return pan to zoom
    }

    private fun assertGentle(plan: PhotoMotionPlan, seconds: Double, pa: Float, oa: Float) {
        val (pan, zoom) = peakRates(plan, seconds, pa, oa)
        assertTrue("pan $pan/s over ${seconds}s ${plan.reason}", pan <= PhotoMotionGeometry.MAX_PAN_SPEED * 1.01)
        assertTrue("zoom $zoom/s over ${seconds}s ${plan.reason}", zoom <= PhotoMotionGeometry.MAX_ZOOM_RATE * 1.01)
    }

    // 16:9 photo, three people left / middle / right; output 9:16 (cover crop is ~32% of the photo width).
    private val wideW = 1920
    private val wideH = 1080
    private val wideAspect = 1920f / 1080f
    private val storyAspect = 1080f / 1920f
    private val left = PhotoNormRect(0.08f, 0.30f, 0.16f, 0.46f)
    private val middle = PhotoNormRect(0.47f, 0.25f, 0.57f, 0.45f) // biggest face = primary person
    private val right = PhotoNormRect(0.84f, 0.32f, 0.92f, 0.48f)

    @Test
    fun wideToStoryCoversEveryFrameVisitsAllPeopleAndEndsOnPrimary() {
        // Long enough to cross the photo at a calm pace: every person is shown, the primary last.
        for (seed in seeds) {
            val p = plan(wideW, wideH, 1080, 1920, faces = listOf(left, middle, right), duration = 24.0, seed = seed)
            assertEquals(PhotoMotionMode.PAN_HORIZONTAL, p.mode)
            assertCoversEveryFrame(p, wideAspect, storyAspect)
            assertGentle(p, 24.0, wideAspect, storyAspect)
            assertEquals("인물 3/3곳 · 마지막 주인공", p.reason)
            for (face in listOf(left, middle, right)) assertSeenSometime(p, wideAspect, storyAspect, face)
            // The camera settles on the primary (largest) person: shown through the whole final hold.
            for (s in listOf(0.85f, 0.95f, 1f)) assertTrue("primary at s=$s", visibleAt(p, s, wideAspect, storyAspect, middle))
            assertTrue("pushes and pulls", zoomRange(p, wideAspect, storyAspect) >= 0.08f)
            assertTrue("travels sideways", travelX(p, wideAspect, storyAspect) >= 0.5f)
        }
    }

    @Test
    fun tallToWideCoversEveryFrameAndMovesVerticallyToThePerson() {
        val tallAspect = 1080f / 1920f
        val wideOut = 1920f / 1080f
        val top = PhotoNormRect(0.40f, 0.08f, 0.60f, 0.16f)
        val bottom = PhotoNormRect(0.30f, 0.80f, 0.55f, 0.92f) // primary
        for (seed in seeds) {
            val p = plan(1080, 1920, 1920, 1080, faces = listOf(top, bottom), duration = 16.0, seed = seed)
            assertEquals(PhotoMotionMode.PAN_VERTICAL, p.mode)
            assertCoversEveryFrame(p, tallAspect, wideOut)
            assertGentle(p, 16.0, tallAspect, wideOut)
            assertSeenSometime(p, tallAspect, wideOut, top)
            assertTrue(visibleAt(p, 1f, tallAspect, wideOut, bottom))
            assertTrue("travels vertically", travelY(p, tallAspect, wideOut) >= 0.5f)
            assertTrue(zoomRange(p, tallAspect, wideOut) >= 0.08f)
        }
    }

    @Test
    fun randomStartIsStablePerSeedAndDiffersBetweenPhotos() {
        val a = plan(wideW, wideH, 1080, 1920, faces = listOf(middle), seed = 42)
        val b = plan(wideW, wideH, 1080, 1920, faces = listOf(middle), seed = 42)
        assertEquals("same photo, same path on re-render/reopen/preview", a, b)
        val starts = seeds.map { plan(wideW, wideH, 1080, 1920, faces = listOf(middle), seed = it).viewportAt(0f, wideAspect, storyAspect) }
        assertTrue("starts vary between photos", starts.map { (it.centerX * 100).toInt() to (it.width * 100).toInt() }.distinct().size >= 4)
        // The start is never the end framing.
        for (seed in seeds) {
            val p = plan(wideW, wideH, 1080, 1920, faces = listOf(middle), seed = seed)
            assertNotEquals(p.viewportAt(0f, wideAspect, storyAspect), p.viewportAt(1f, wideAspect, storyAspect))
        }
    }

    @Test
    fun groupWiderThanTheFrameIsToldOverTimeNeverLetterboxed() {
        // A row of people across the whole wide photo: impossible to show at once in 9:16, so they are visited.
        val row = (0 until 6).map { i -> PhotoNormRect(0.05f + i * 0.155f, 0.30f, 0.12f + i * 0.155f, 0.42f) }
        for (seed in seeds) {
            for (d in listOf(6.0, 30.0)) {
                val p = plan(4000, 3000, 1080, 1920, faces = row, duration = d, seed = seed)
                assertTrue(p.cover)
                assertCoversEveryFrame(p, 4f / 3f, storyAspect)
                assertGentle(p, d, 4f / 3f, storyAspect)
                assertTrue(p.isMoving)
                assertTrue(row.any { visibleAt(p, 1f, 4f / 3f, storyAspect, it) })
                if (d >= 30.0) assertTrue(travelX(p, 4f / 3f, storyAspect) >= 1.0f)
            }
        }
    }

    @Test
    fun withoutPeopleTheCameraEndsOnTheMainSubject() {
        val subject = PhotoNormRect(0.70f, 0.40f, 0.85f, 0.75f)
        for (seed in seeds) {
            val p = plan(wideW, wideH, 1080, 1920, objects = listOf(subject, PhotoNormRect(0.1f, 0.6f, 0.13f, 0.65f)), seed = seed)
            assertCoversEveryFrame(p, wideAspect, storyAspect)
            assertTrue(visibleAt(p, 1f, wideAspect, storyAspect, subject))
        }
    }

    @Test
    fun failedOrEmptyAnalysisStillCoversAndSweepsTheWidePhoto() {
        for ((w, h) in listOf(4000 to 3000, 3000 to 4000, 2000 to 2000, 1920 to 1080, 1080 to 1920)) {
            val pa = w.toFloat() / h
            for ((outW, outH) in listOf(1080 to 1920, 1920 to 1080, 1080 to 1080)) {
                val oa = outW.toFloat() / outH
                for (seed in seeds) for (reliable in listOf(true, false)) {
                    val p = plan(w, h, outW, outH, reliable = reliable, duration = 3.0, seed = seed)
                    assertTrue(p.cover)
                    assertCoversEveryFrame(p, pa, oa)
                    assertGentle(p, 3.0, pa, oa)
                    assertTrue("moves ${w}x$h -> ${outW}x$outH", p.isMoving)
                    assertTrue(zoomRange(p, pa, oa) >= 0.05f)
                }
            }
        }
        // 16:9 -> 9:16 with nothing detected and time to spare: both sides and the middle get on screen.
        for (seed in seeds) {
            val p = plan(wideW, wideH, 1080, 1920, reliable = false, duration = 24.0, seed = seed)
            assertGentle(p, 24.0, wideAspect, storyAspect)
            assertSeenSometime(p, wideAspect, storyAspect, PhotoNormRect(0.02f, 0.4f, 0.10f, 0.6f))
            assertSeenSometime(p, wideAspect, storyAspect, PhotoNormRect(0.90f, 0.4f, 0.98f, 0.6f))
            assertSeenSometime(p, wideAspect, storyAspect, PhotoNormRect(0.46f, 0.4f, 0.54f, 0.6f))
        }
    }

    @Test
    fun manualFocusEndsOnTheUserFrameAndNeverLetterboxes() {
        val face = PhotoNormRect(0.80f, 0.30f, 0.95f, 0.60f) // right edge, user frame pinned left
        for (seed in seeds) {
            val p = plan(4000, 3000, 1080, 1920, faces = listOf(face), focusX = -1f, duration = 10.0, seed = seed)
            assertEquals(PhotoMotionMode.MANUAL_FILL, p.mode)
            assertCoversEveryFrame(p, 4f / 3f, storyAspect)
            assertGentle(p, 10.0, 4f / 3f, storyAspect)
            assertSeenSometime(p, 4f / 3f, storyAspect, face)
            val end = p.viewportAt(1f, 4f / 3f, storyAspect)
            // Focus -1 = the left-most cover frame; the face-safe push-in keeps that frame's center.
            val coverW = storyAspect / (4f / 3f)
            assertEquals("ends on the left (user) frame", coverW / 2f, end.centerX, 1e-3f)
            assertTrue(end.width <= coverW + 1e-4f)
        }
        val leftFace = PhotoNormRect(0.05f, 0.30f, 0.20f, 0.55f)
        val pushIn = plan(4000, 3000, 1080, 1920, faces = listOf(leftFace), focusX = -1f)
        assertTrue(visibleAt(pushIn, 1f, 4f / 3f, storyAspect, leftFace))
        assertCoversEveryFrame(plan(4000, 3000, 1080, 1920, reliable = false, focusX = 1f, focusY = 0.5f), 4f / 3f, storyAspect)
    }

    @Test
    fun shortScenesMoveGentlyNearTheTargetAndLongScenesTravelFarther() {
        val cases = listOf(
            Triple(wideW to wideH, 1080 to 1920, listOf(left, middle, right)), // 16:9 -> 9:16, primary = middle
            Triple(1080 to 1920, 1920 to 1080, listOf(PhotoNormRect(0.40f, 0.08f, 0.60f, 0.16f), PhotoNormRect(0.30f, 0.80f, 0.55f, 0.92f))),
        )
        for ((photo, out, faces) in cases) {
            val pa = photo.first.toFloat() / photo.second
            val oa = out.first.toFloat() / out.second
            val primary = faces.maxBy { it.width * it.height }
            val travels = mutableListOf<Float>()
            for (d in listOf(0.6, 1.0, 2.0, 3.0, 5.5, 10.0)) {
                for (seed in seeds) {
                    val p = plan(photo.first, photo.second, out.first, out.second, faces = faces, duration = d, seed = seed)
                    assertCoversEveryFrame(p, pa, oa)
                    assertGentle(p, d, pa, oa)
                    assertTrue("moves at ${d}s", p.isMoving)
                    assertTrue("ends on the primary at ${d}s", visibleAt(p, 1f, pa, oa, primary))
                }
                travels += seeds.maxOf { seed ->
                    val p = plan(photo.first, photo.second, out.first, out.second, faces = faces, duration = d, seed = seed)
                    // Farthest the camera is from its final framing, in final-viewport units.
                    val end = p.viewportAt(1f, pa, oa)
                    samples.maxOf { s ->
                        val v = p.viewportAt(s, pa, oa)
                        maxOf(abs(v.centerX - end.centerX) / end.width, abs(v.centerY - end.centerY) / end.height)
                    }
                }
            }
            // A 0.6 s scene stays close to its target; longer scenes are allowed to travel farther.
            assertTrue("short scene travel ${travels.first()}", travels.first() <= 0.15f)
            assertTrue("travel grows with time $travels", travels.last() > travels.first() * 4f)
        }
    }

    @Test
    fun shortScenesStayCoveredAndOnlyFitLetterboxes() {
        for (d in listOf(0.1, 0.4, 0.6, 1.0)) {
            val p = plan(wideW, wideH, 1080, 1920, faces = listOf(middle), duration = d)
            assertCoversEveryFrame(p, wideAspect, storyAspect)
            assertTrue(visibleAt(p, 1f, wideAspect, storyAspect, middle))
        }
        assertEquals(PhotoMotionMode.STILL, plan(wideW, wideH, 1080, 1920, faces = listOf(middle), duration = 0.2).mode)
        // Explicit 맞추기 is the only mode that keeps the whole photo (with letterbox) — never chosen automatically.
        val fit = plan(wideW, wideH, 1080, 1920, faces = listOf(left, middle, right), fit = SceneFit.FIT)
        assertEquals(PhotoMotionMode.FIT_MOTION, fit.mode)
        assertFalse(fit.cover)
        for (s in samples) assertTrue(visibleAt(fit, s, wideAspect, storyAspect, PhotoNormRect.WHOLE))
        for (faces in listOf(emptyList(), listOf(left, middle, right), listOf(PhotoNormRect(0f, 0f, 1f, 1f)))) {
            assertTrue(plan(wideW, wideH, 1080, 1920, faces = faces).cover)
        }
        assertEquals(PhotoMotionMode.STILL, plan(0, 0, 1080, 1920).mode)
    }

    @Test
    fun automaticPhotoScenesAreLongEnoughForTheCameraMove() {
        val photo = TrailerSource(1, "a.jpg", java.io.File("a.jpg"), 0.0, 4000, 3000, AudioAnalysisResult(emptyList(), 0.0), null, isImage = true, photoDurationRatio = 0.1)
        assertEquals(MIN_AUTO_PHOTO_SCENE_SECONDS, photo.weightedPhotoLength(4.0), 1e-9)
        val legacyTotal = photo.unflooredPhotoLength(4.0)
        assertTrue("legacy automatic total stays automatic", automaticTrailerPlan(listOf(photo), legacyTotal, 4.0) != null)
        // An explicit short editor scene keeps its length.
        assertEquals(0.4, clampSceneWindow(photo, 0.0, 0.4).second, 1e-9)
    }

    @Test
    fun saveNameUsesLocalTwoDigitYearTimestampAndModeSuffix() {
        val seoul = ZoneId.of("Asia/Seoul")
        // 2026-10-31 10:58:10 KST
        val millis = java.time.ZonedDateTime.of(2026, 10, 31, 10, 58, 10, 0, seoul).toInstant().toEpochMilli()
        assertEquals("261031-105810-예고편", trailerTimestampName(music = false, epochMillis = millis, zone = seoul))
        assertEquals("261031-105810-뮤비", trailerTimestampName(music = true, epochMillis = millis, zone = seoul))
        // Week-based year must not leak in around New Year (2026-12-31 belongs to ISO week-year 2027 in some calendars).
        val newYearsEve = java.time.ZonedDateTime.of(2026, 12, 31, 23, 59, 59, 0, seoul).toInstant().toEpochMilli()
        assertEquals("261231-235959-뮤비", trailerTimestampName(music = true, epochMillis = newYearsEve, zone = seoul))
    }
}
