package com.armsone.nasfinder.ui

import java.time.ZoneId
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Samples the whole camera path (every 1% of the scene) the way the exporter renders it: through the GL matrix
 * applied to photo NDC. 채우기 must cover the output with photo pixels in EVERY frame (no letterbox, whatever the
 * people/analysis/focus/length), start at a stable random framing, show every separated main person at some time
 * and end framed on the primary person (else main subject, else a stable random legal point). Explicit 맞추기 keeps the whole photo.
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
                    val zoom = zoomRange(p, pa, oa)
                    assertTrue("gentle short zoom $zoom", zoom > 0f && zoom <= 0.04f + 1e-5f)
                }
            }
        }
        // 16:9 -> 9:16 with nothing detected and time to spare: both sides get on screen (the end is a random point).
        for (seed in seeds) {
            val p = plan(wideW, wideH, 1080, 1920, reliable = false, duration = 24.0, seed = seed)
            assertGentle(p, 24.0, wideAspect, storyAspect)
            assertSeenSometime(p, wideAspect, storyAspect, PhotoNormRect(0.02f, 0.4f, 0.10f, 0.6f))
            assertSeenSometime(p, wideAspect, storyAspect, PhotoNormRect(0.90f, 0.4f, 0.98f, 0.6f))
        }
    }

    @Test
    fun noTargetFillAimsAtAStableRandomLegalPoint() {
        // Wide photo -> portrait has a horizontal range, tall photo -> landscape a vertical one.
        for ((photo, out) in listOf((wideW to wideH) to (1080 to 1920), (1080 to 1920) to (1920 to 1080))) {
            val pa = photo.first.toFloat() / photo.second
            val oa = out.first.toFloat() / out.second
            for (d in listOf(0.3, 1.0, 3.0, 8.0, 24.0)) {
                val signs = mutableSetOf<Boolean>()
                val ends = seeds.map { seed ->
                    val p = plan(photo.first, photo.second, out.first, out.second, duration = d, seed = seed)
                    assertEquals(p, plan(photo.first, photo.second, out.first, out.second, duration = d, seed = seed))
                    assertCoversEveryFrame(p, pa, oa)
                    val start = p.viewportAt(0f, pa, oa)
                    val end = p.viewportAt(1f, pa, oa)
                    if (d <= 3.0) {
                        assertTrue(p.reason, p.reason.contains("랜덤 구도 살짝"))
                        signs += end.width < start.width
                        for (s in samples) {
                            val v = p.viewportAt(s, pa, oa)
                            assertEquals(start.centerX, v.centerX, 1e-6f)
                            assertEquals(start.centerY, v.centerY, 1e-6f)
                        }
                    } else {
                        assertTrue(p.reason, p.reason.endsWith("랜덤 위치로 이동"))
                    }
                    if (pa > oa) end.centerX else end.centerY
                }
                if (d <= 3.0) assertEquals("both directions ${d}s", setOf(true, false), signs)
                assertTrue("end varies by photo ${d}s $ends", ends.map { (it * 100).toInt() }.distinct().size >= 3)
            }
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
        // Every positive short length keeps a face-safe, time-scaled push-in or pull-out (<= 4% per 3 s) and never pans.
        // Push-in or pull-out is stable per photo seed; both occur across seeds, same center either way. The seeds
        // include sequential sources named IMG_0001.jpg.. (id * 31 + name.hashCode()), whose raw low bits all agree.
        val sequentialSeeds = (1..6).map { i -> 31 * i + "IMG_%04d.jpg".format(i).hashCode() }
        for (fit in listOf(SceneFit.FILL, SceneFit.FIT)) for (d in listOf(0.04, 0.2, 0.4, 3.0)) {
            val signs = mutableSetOf<Boolean>()
            val sequentialSigns = mutableSetOf<Boolean>()
            for (seed in seeds + sequentialSeeds) {
                val p = plan(wideW, wideH, 1080, 1920, faces = listOf(middle), fit = fit, duration = d, seed = seed)
                assertEquals(p, plan(wideW, wideH, 1080, 1920, faces = listOf(middle), fit = fit, duration = d, seed = seed))
                assertNotEquals("short $fit ${d}s ${p.reason}", PhotoMotionMode.STILL, p.mode)
                val start = p.viewportAt(0f, wideAspect, storyAspect)
                val end = p.viewportAt(1f, wideAspect, storyAspect)
                val zoomIn = end.width < start.width
                signs += zoomIn
                if (seed in sequentialSeeds) sequentialSigns += zoomIn
                assertTrue(p.reason, p.reason.endsWith(if (zoomIn) "살짝 확대" else "살짝 축소"))
                for (s in samples) {
                    val v = p.viewportAt(s, wideAspect, storyAspect)
                    assertEquals("no pan x $fit ${d}s s=$s", start.centerX, v.centerX, 1e-6f)
                    assertEquals("no pan y $fit ${d}s s=$s", start.centerY, v.centerY, 1e-6f)
                }
                val zoom = zoomRange(p, wideAspect, storyAspect)
                assertTrue("zooms at $fit ${d}s", zoom > 0f)
                assertTrue("zoom $zoom scaled to ${d}s", zoom <= 0.04f * (d / 3.0).toFloat() + 1e-5f)
                if (fit == SceneFit.FILL) {
                    assertCoversEveryFrame(p, wideAspect, storyAspect)
                    for (s in samples) assertTrue("face kept at s=$s ${d}s", visibleAt(p, s, wideAspect, storyAspect, middle))
                } else {
                    for (s in samples) assertTrue("whole photo at s=$s ${d}s", visibleAt(p, s, wideAspect, storyAspect, PhotoNormRect.WHOLE))
                }
            }
            assertEquals("both directions $fit ${d}s", setOf(true, false), signs)
            assertEquals("sequential IMG_000n both directions $fit ${d}s", setOf(true, false), sequentialSigns)
        }
        // Automatic focus is never random: an off-center person beats a conflicting subject, the subject is used only
        // without people, also from a partial (unreliable) analysis. Width direction matches the reason, both occur
        // across seeds; same center either way.
        val subject = PhotoNormRect(0.10f, 0.30f, 0.30f, 0.80f)
        for ((faces, target) in listOf(listOf(right) to right, emptyList<PhotoNormRect>() to subject)) {
            for (reliable in listOf(true, false)) for (d in listOf(1.0, 3.0)) {
                val signs = mutableSetOf<Boolean>()
                val ends = seeds.map { seed ->
                    val p = plan(wideW, wideH, 1080, 1920, faces = faces, objects = listOf(subject), reliable = reliable, duration = d, seed = seed)
                    val start = p.viewportAt(0f, wideAspect, storyAspect)
                    val zoomIn = p.viewportAt(1f, wideAspect, storyAspect).width < start.width
                    signs += zoomIn
                    assertTrue(p.reason, p.reason.endsWith(if (zoomIn) "살짝 확대" else "살짝 축소"))
                    assertCoversEveryFrame(p, wideAspect, storyAspect)
                    for (s in samples) {
                        val v = p.viewportAt(s, wideAspect, storyAspect)
                        assertEquals(start.centerX, v.centerX, 1e-6f)
                        assertTrue("$target kept at s=$s ${p.reason}", visibleAt(p, s, wideAspect, storyAspect, target))
                    }
                    start.centerX
                }
                assertEquals("both directions $target ${d}s", setOf(true, false), signs)
                for (e in ends) assertEquals(ends[0], e, 1e-6f)
                assertTrue("aims at $target, not the other side", abs(ends[0] - target.centerX) < 0.15f)
            }
        }
        assertEquals(PhotoMotionMode.STILL, plan(wideW, wideH, 1080, 1920, faces = listOf(middle), duration = 0.0).mode)
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

    @Test
    fun protectedActionWindowsSurviveMusicBudgetShrink() {
        val video = TrailerSource(1, "a.mp4", java.io.File("a.mp4"), 20.0, 1920, 1080, AudioAnalysisResult(emptyList(), 0.0), null)
        val last = video.copy(id = 2, name = "b.mp4")
        val project = EditorProject(
            listOf(
                EditorScene(1, 1, 4.0, 6.0, actionStart = 4.0, actionEnd = 10.0),
                EditorScene(2, 2, 2.0, 6.0, actionStart = 2.0, actionEnd = 8.0),
            ),
        )
        // 고정 출력 예산 5초가 보호 구간(12초)보다 짧으면 두 샷 모두 남기고 동작 중심으로 좁혀 예산을 지킨다.
        val (scenes, total) = redistributeManualTotal(project, listOf(video, last), 5.0)
        assertEquals(listOf(1, 2), scenes.map { it.sourceId })
        assertEquals(5.0, total, 1.0 / 30.0)
        assertTrue(scenes.all { it.start <= (it.actionStart!! + it.actionEnd!!) / 2 && it.start + it.length >= (it.actionStart!! + it.actionEnd!!) / 2 })
        // 앵커 없는 수동 트림은 기존대로 줄어든다.
        val manual = redistributeManualTotal(EditorProject(project.scenes.map { it.copy(actionStart = null, actionEnd = null) }), listOf(video, last), 5.0)
        assertTrue(manual.second < 12.0)
    }

    @Test
    fun quietImpactBeforeLouderSustainedPeakStaysInsideProtectedWindow() {
        // 22.7s source: quiet isolated club click at 3.61s, then a loud sustained noise whose verified peak is at 5.63s.
        val wave = List(192) { if (it in 40..57) 0.8 else 0.04 }
        fun source(transients: List<Double>) = TrailerSource(
            1, "a.mp4", java.io.File("a.mp4"), 22.749, 1920, 1080,
            AudioAnalysisResult(wave, 5.628, listOf(5.628, 3.614), verifiedPeakTimesSeconds = listOf(5.628), transientTimesSeconds = transients), null
        )
        val withClick = recommendTrailerPlan(listOf(source(listOf(3.614))))!!.scenes.single()
        assertTrue(withClick.start <= 3.614 - ACTION_LEAD_SECONDS + 1e-6)
        assertTrue(withClick.start + withClick.length >= 5.628 + ACTION_FOLLOW_SECONDS - 1e-6)
        assertEquals(3.614 - ACTION_LEAD_SECONDS, withClick.actionStart!!, 1e-6)
        assertEquals(5.628 + ACTION_FOLLOW_SECONDS, withClick.actionEnd!!, 1e-6)
        // 앞선 임팩트 근거가 없으면 기존 피크 기준 보호 구간, 클릭 없는 소리라 뒤(소리 없는 실제 스트로크·결과)는 더 길게.
        val plain = recommendTrailerPlan(listOf(source(emptyList())))!!.scenes.single()
        assertEquals(5.628 - ACTION_LEAD_SECONDS, plain.actionStart!!, 1e-6)
        assertEquals(5.628 + WEAK_ACTION_FOLLOW_SECONDS, plain.actionEnd!!, 1e-6)
    }

    @Test
    fun rebuiltCopyKeepsEverySeparateVerifiedActionOfASingleClipSource() {
        // 22.7s source with two putts: quiet click 3.61s + verified 5.63s, then a click at 16.16s followed by verified 18.29/18.76s.
        // An unverified loud sound at 12.0s must not become a scene. The original project has ONE scene (around the first putt).
        val wave = List(192) { if (it in 40..57 || it in 150..160 || it in 98..104) 0.8 else 0.04 }
        val analysis = AudioAnalysisResult(
            wave, 5.628, listOf(5.628, 3.614, 18.29, 18.76, 12.0),
            verifiedPeakTimesSeconds = listOf(5.628, 18.29, 18.76), transientTimesSeconds = listOf(3.614, 16.16),
        )
        val putts = TrailerSource(1, "a.mp4", java.io.File("a.mp4"), 22.749, 1920, 1080, analysis, null)
        val other = putts.copy(id = 2, name = "b.mp4", duration = 9.0, analysis = AudioAnalysisResult(emptyList(), 0.0))
        val caption = SceneCaption("퍼팅")
        val original = EditorProject(
            listOf(
                EditorScene(1, 1, 3.0, 4.0, fit = SceneFit.FIT, sourceVolume = 0.5f, caption = caption),
                EditorScene(2, 2, 0.0, 3.0),
            ),
        )
        val rebuilt = original.rebuiltFromRecommendation(listOf(putts, other))
        // Original is untouched; the copy has the source's two actions in source order, then the other source's scene.
        assertEquals(2, original.scenes.size)
        assertEquals(listOf(1, 1, 2), rebuilt.scenes.map { it.sourceId })
        val (first, second) = rebuilt.scenes.take(2)
        assertEquals(1L, first.key)
        assertEquals(caption, first.caption)
        // The added action scene inherits framing/volume, has its own key and no duplicated caption.
        assertTrue(second.key != first.key && rebuilt.scenes.map { it.key }.distinct().size == 3 && rebuilt.nextKey > rebuilt.scenes.maxOf { it.key })
        assertEquals(SceneFit.FIT, second.fit)
        assertEquals(0.5f, second.sourceVolume, 0f)
        assertEquals(null, second.caption)
        // Each scene holds its whole protected action: first putt from the quiet click, second from its click through the verified hit.
        assertEquals(3.614 - ACTION_LEAD_SECONDS, first.actionStart!!, 1e-6)
        assertEquals(16.16 - ACTION_LEAD_SECONDS, second.actionStart!!, 1e-6)
        assertTrue(first.actionEnd!! >= 5.628 + ACTION_FOLLOW_SECONDS - 1e-6)
        assertTrue(second.actionEnd!! >= 18.29 + ACTION_FOLLOW_SECONDS - 1e-6)
        for (scene in rebuilt.scenes.take(2)) {
            assertTrue(scene.start <= scene.actionStart!! + 1e-6 && scene.start + scene.length >= scene.actionEnd!! - 1e-6)
        }
        assertTrue(first.start + first.length <= second.start + 1e-6)
        // A fixed budget shorter than the protected total narrows around each action's centre instead of overrunning it.
        val (fitted, total) = redistributeManualTotal(rebuilt, listOf(putts, other), 5.0)
        assertTrue(fitted.take(2).all { val mid = (it.actionStart!! + it.actionEnd!!) / 2; it.start <= mid && it.start + it.length >= mid })
        assertTrue(total <= 5.0 + 1.0 / 30.0 + rebuilt.scenes.drop(2).sumOf { it.length })
        // A project that already has a scene per action gets no extra scene (counts only grow for omitted actions).
        val both = EditorProject(listOf(EditorScene(1, 1, 3.0, 4.0), EditorScene(2, 1, 15.0, 4.0)))
        assertEquals(2, both.rebuiltFromRecommendation(listOf(putts)).scenes.size)
        val single = EditorProject(listOf(EditorScene(1, 1, 3.0, 4.0)))
        // A merely loud second sound (not verified, no isolated click leading into it) adds nothing.
        val loudOnly = putts.copy(analysis = analysis.copy(verifiedPeakTimesSeconds = listOf(5.628), transientTimesSeconds = listOf(3.614)))
        assertEquals(1, single.rebuiltFromRecommendation(listOf(loudOnly)).scenes.size)
        // The isolated click before the second hit alone is enough independent support.
        val clickOnly = putts.copy(analysis = analysis.copy(verifiedPeakTimesSeconds = listOf(5.628)))
        assertEquals(2, single.rebuiltFromRecommendation(listOf(clickOnly)).scenes.size)
    }

    @Test
    fun everyIsolatedHitOfALongRoundIsKeptBeyondThePerSourceLimit() {
        // 300s first-person round: 12 quiet isolated clicks (shots) the loudness ranking does not list, plus loud wind/cart peaks.
        val hits = (0 until 12).map { 10.0 + it * 24.0 }
        val loud = (0 until 12).map { 21.0 + it * 24.0 }
        val wave = List(600) { i -> if (loud.any { kotlin.math.abs(i / 2.0 - it) < 0.6 }) 0.9 else 0.05 }
        val round = TrailerSource(
            1, "round.mp4", java.io.File("round.mp4"), 300.0, 1080, 1920,
            AudioAnalysisResult(wave, loud.first(), loud, transientTimesSeconds = hits), null,
        )
        val scenes = recommendTrailerPlan(listOf(round))!!.scenes
        // Each hit keeps its lead-in and follow-through; the loud peaks only fill up to the old per-source limit (8).
        hits.forEach { t -> assertTrue("hit $t", scenes.any { it.start <= t - ACTION_LEAD_SECONDS + 1e-6 && it.start + it.length >= t + ACTION_FOLLOW_SECONDS - 1e-6 }) }
        assertEquals(12, scenes.size)
        assertTrue(scenes.zipWithNext().all { (a, b) -> a.start + a.length <= b.start + 1e-6 })
        // The rebuilt copy of a one-scene project gets the same actions (the original scene keeps the first in time).
        val rebuilt = EditorProject(listOf(EditorScene(1, 1, 0.0, 4.0))).rebuiltFromRecommendation(listOf(round))
        assertEquals(12, rebuilt.scenes.size)
        hits.forEach { t -> assertTrue("rebuilt hit $t", rebuilt.scenes.any { it.start <= t - 1.0 && it.start + it.length >= t + 1.0 }) }
        // Without the clicks it is the old loudness plan: at most 8 cuts.
        val windy = round.copy(analysis = round.analysis.copy(transientTimesSeconds = emptyList()))
        assertTrue(recommendTrailerPlan(listOf(windy))!!.scenes.size <= 8)
    }

    @Test
    fun automaticRegenerationWithAShortBudgetKeepsWholeShotsWithinIt() {
        // Two 22.7s sources with two putts each (click + verified hit); the user's 18s budget holds only two of their ~8s protected putts.
        fun putts(id: Int) = TrailerSource(
            id, "p$id.mp4", java.io.File("p$id.mp4"), 22.749, 1920, 1080,
            AudioAnalysisResult(List(192) { if (it in 40..57 || it in 150..160) 0.8 else 0.04 }, 5.628, listOf(5.628, 18.29),
                verifiedPeakTimesSeconds = listOf(5.628, 18.29), transientTimesSeconds = listOf(3.614, 16.16)), null,
        )
        val sources = listOf(putts(1), putts(2))
        val session = TrailerSession(
            // The recommended clip keeps whole protected actions (a chosen number would cut each to exactly that long).
            directory = java.io.File("."), sources = sources, total = 18.0, clipLength = recommendTrailerPlan(sources)!!.clipLength, busy = false, prepared = true,
            musicPolicy = TrailerMusicPolicy.LOOP_MUSIC, editor = EditorProject(listOf(EditorScene(1, 1, 0.0, 6.0)), autoPlan = true, budgetTarget = 18.0),
        )
        // No chosen length (an older automatic total of 18s, e.g. before sources were added): every shot stays.
        val unbudgeted = session.copy(editor = session.editor!!.copy(budgetTarget = null)).withRegeneratedPlan()
        assertEquals(4, unbudgeted.editor!!.scenes.size)
        assertTrue(unbudgeted.editor!!.budgetReserve.isEmpty())
        assertEquals(unbudgeted.editor!!.scenes.sumOf { it.length }, unbudgeted.total, 1e-6)
        val regenerated = session.withRegeneratedPlan()
        val project = regenerated.editor!!
        // The budget is hard: whole less important scenes leave instead of the total growing, and every kept action stays whole.
        assertTrue(regenerated.total <= 18.0 + 1e-6)
        assertEquals(project.scenes.sumOf { it.length }, regenerated.total, 1e-6)
        assertTrue(project.budgetReserve.isNotEmpty())
        assertEquals(4, project.scenes.size + project.budgetReserve.size)
        assertTrue(project.scenes.all { it.start <= it.actionStart!! + 1e-6 && it.start + it.length >= it.actionEnd!! - 1e-6 })
        // Each source keeps one shot before any source loses its last.
        assertEquals(setOf(1, 2), project.scenes.map { it.sourceId }.toSet())
        assertTrue(regenerated.status.contains("뺐어요"))
    }

    @Test
    fun budgetFitReservesWholeScenesAndRestoresThemWithTheirEdits() {
        val video = TrailerSource(1, "a.mp4", java.io.File("a.mp4"), 100.0, 1920, 1080, AudioAnalysisResult(emptyList(), 0.0), null)
        val scenes = (0 until 6).map {
            EditorScene(it + 1L, 1, it * 15.0, 6.0, sourceVolume = 0.5f, caption = SceneCaption("s$it"), actionStart = it * 15.0, actionEnd = it * 15.0 + 5.0)
        }
        val full = EditorProject(scenes, autoPlan = false)
        val short = fitEditorToBudget(full, listOf(video), 12.0)!!
        // Two whole 5s actions fit; the next most important scene is narrowed into the remaining ~2s (>= its physical floor) instead of left out.
        assertEquals(3, short.included)
        assertEquals(3, short.excluded)
        assertTrue(short.total <= 12.0 + 1e-6)
        assertEquals(2, short.project.scenes.count { it.length >= 5.0 - 1e-6 })
        assertTrue(short.project.scenes.all { it.length >= 1.0 / 30.0 - 1e-6 })
        // Longer again: every reserved scene comes back in its own place with its caption/volume.
        val long = fitEditorToBudget(short.project, listOf(video), 40.0)!!
        assertEquals(0, long.excluded)
        assertEquals(scenes.map { it.key }, long.project.scenes.map { it.key })
        assertEquals(scenes.map { it.caption }, long.project.scenes.map { it.caption })
        assertTrue(long.project.scenes.all { it.sourceVolume == 0.5f })
        assertEquals(40.0, long.total, 1e-6)
        // Short again: the same choice.
        val again = fitEditorToBudget(long.project, listOf(video), 12.0)!!
        assertEquals(short.project.scenes.map { it.key }, again.project.scenes.map { it.key })
        // Not even one whole action fits: the best scene is narrowed around its action instead of refusing.
        val narrowed = fitEditorToBudget(full, listOf(video), 4.0)!!
        assertEquals(1, narrowed.included)
        assertEquals(4.0, narrowed.total, 1.0 / 30.0)
        // The shortest achievable body is now the hard one-output-frame floor (context and action are narrowed, not refused).
        assertEquals(1.0 / 30.0, budgetBodyBounds(short.project, listOf(video)).first, 1e-6)
    }

    @Test
    fun verifiedHitNextToAKeptShotWidensItInsteadOfBeingDropped() {
        // Classifier-verified swing at 5.17s, 3.45s before an isolated click at 8.62s: one action [5.17-1.8, 8.62+2.0], not one dropped.
        val wave = List(100) { if (it in 16..18 || it in 33..35) 0.9 else 0.05 }
        val source = TrailerSource(
            1, "a.mp4", java.io.File("a.mp4"), 50.0, 1080, 1920,
            AudioAnalysisResult(wave, 8.62, listOf(8.62, 5.17), verifiedPeakTimesSeconds = listOf(8.62, 5.17), transientTimesSeconds = listOf(8.62)), null,
        )
        val scene = recommendTrailerPlan(listOf(source))!!.scenes.single()
        assertEquals(5.17 - ACTION_LEAD_SECONDS, scene.actionStart!!, 1e-6)
        assertEquals(8.62 + ACTION_FOLLOW_SECONDS, scene.actionEnd!!, 1e-6)
        assertTrue(scene.start <= scene.actionStart!! + 1e-6 && scene.start + scene.length >= scene.actionEnd!! - 1e-6)
    }

    @Test
    fun beatFitKeepsTheChosenRepeatCountAndBudget() {
        // 10s music, beats every 2s starting at 1s: five scenes whose protected length (2s) fills the one-repeat budget exactly.
        val video = TrailerSource(1, "a.mp4", java.io.File("a.mp4"), 100.0, 1920, 1080, AudioAnalysisResult(emptyList(), 0.0), null)
        val scenes = (0 until 5).map { EditorScene(it.toLong(), 1, it * 10.0, 2.0, actionStart = it * 10.0, actionEnd = it * 10.0 + 2.0) }
        val beats = listOf(1.0, 3.0, 5.0, 7.0, 9.0)
        val project = EditorProject(scenes)
        val one = syncToBeatsWithMinimumRepeats(project, listOf(video), beats, 10.0, TrailerMusicPolicy.FIT_MUSIC_LENGTH, 1)
        // 고른 반복 횟수 그대로이고 음악 길이를 넘지 않으며 보호 길이는 지켜진다.
        assertEquals(1, one.repeatCount)
        assertTrue(one.scenes.sumOf { it.length } <= 10.0 + 1e-6)
        assertTrue(one.scenes.all { it.length >= 2.0 - 1e-6 })
        // 다른 정책도 지금 총길이를 넘기지 않는다.
        val loose = EditorProject(scenes.map { it.copy(length = 3.0, actionStart = null, actionEnd = null) })
        val loop = syncToBeatsWithMinimumRepeats(loose, listOf(video), beats, 10.0, TrailerMusicPolicy.LOOP_MUSIC, 1)
        assertEquals(1, loop.repeatCount)
        assertTrue(loop.scenes.sumOf { it.length } <= 15.0 + 1e-6)
    }

    @Test
    fun numericClipIsExactEvenWhenItEqualsTheRecommendationAndMusicFitOnlyCapsIt() {
        // One putt source (click 3.614s + verified 5.628s): its whole protected action is longer than 4s.
        val source = TrailerSource(
            1, "p.mp4", java.io.File("p.mp4"), 22.749, 1920, 1080,
            AudioAnalysisResult(List(192) { if (it in 40..57) 0.8 else 0.04 }, 5.628, listOf(5.628),
                verifiedPeakTimesSeconds = listOf(5.628), transientTimesSeconds = listOf(3.614)), null,
        )
        val action = recommendTrailerPlan(listOf(source))!!.scenes.single()
        val scene = EditorScene(1, 1, action.start, action.length, actionStart = action.actionStart, actionEnd = action.actionEnd)
        // 추천 4 keeps the whole action; the number 4 is exactly 4s and still holds the impact (3.614s).
        val recommended = clipLengthScenes(listOf(scene), listOf(source), 4.0, recommended = true).single()
        val numeric = clipLengthScenes(listOf(scene), listOf(source), 4.0, recommended = false).single()
        assertTrue(recommended.length >= action.actionEnd!! - action.actionStart!! - 1e-6)
        assertEquals(4.0, numeric.length, 1e-9)
        assertTrue(numeric.start <= 3.614 && numeric.start + numeric.length >= 3.614)
        // Number 1 under a 10s music fit (1 repeat): the music only caps it, the 1s cut is not stretched back.
        val one = clipLengthScenes(listOf(scene), listOf(source), 1.0, recommended = false)
        val session = TrailerSession(
            directory = java.io.File("."), sources = listOf(source), total = 1.0, clipLength = 1.0, busy = false, prepared = true,
            musicTrack = TrailerMusicTrack(java.io.File("m.mp3"), "m", 10.0), musicPolicy = TrailerMusicPolicy.FIT_MUSIC_LENGTH, musicFitCount = 1,
            editor = EditorProject(one, autoPlan = false, clipChoiceExplicit = true),
        )
        val fitted = session.withBudgetMusicFit()!!
        assertEquals(1.0, fitted.editor!!.scenes.single().length, 1e-9)
        assertEquals(1, fitted.musicFitCount)
        // 추천 again: the music length is filled.
        val filled = session.copy(editor = session.editor!!.copy(clipChoiceExplicit = false)).withBudgetMusicFit()!!
        assertEquals(10.0, filled.totalDuration, 1e-6)
    }
}
