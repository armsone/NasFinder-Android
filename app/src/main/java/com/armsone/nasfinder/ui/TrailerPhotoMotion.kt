package com.armsone.nasfinder.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix as AndroidMatrix
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlMatrixTransformation
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.ln


enum class PhotoMotionMode {
    /** Explicit 맞추기: whole photo always visible; gentle zoom and drift inside the letterbox margin. */
    FIT_MOTION,
    /** 채우기 camera path on a photo wider than the output: mostly horizontal travel plus push/pull zoom. */
    PAN_HORIZONTAL,
    /** 채우기 camera path on a photo taller than the output: mostly vertical travel plus push/pull zoom. */
    PAN_VERTICAL,
    /** 채우기 camera path on a near-output-aspect photo: travel comes from zooming in. */
    ZOOM,
    /** 채우기 camera path that ends on the user's chosen focus frame. */
    MANUAL_FILL,
    /** No motion; [PhotoMotionPlan.reason] says why. */
    STILL,
}

/** Normalized photo rectangle (0..1, y down). */
data class PhotoNormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun union(other: PhotoNormRect) = PhotoNormRect(
        minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom),
    )

    companion object {
        val WHOLE = PhotoNormRect(0f, 0f, 1f, 1f)
    }
}

/**
 * Visible window of the photo in normalized photo coordinates: center ([centerX], [centerY]) and extent
 * ([width] x [height]) in photo-width / photo-height units. Extents above 1 mean letterbox around the photo.
 */
data class PhotoViewport(val centerX: Float, val centerY: Float, val width: Float, val height: Float) {
    val left: Float get() = centerX - width / 2f
    val right: Float get() = centerX + width / 2f
    val top: Float get() = centerY - height / 2f
    val bottom: Float get() = centerY + height / 2f

    fun contains(rect: PhotoNormRect, eps: Float = 1e-4f): Boolean =
        left <= rect.left + eps && right >= rect.right - eps && top <= rect.top + eps && bottom >= rect.bottom - eps

    /**
     * Column-major GL matrix that maps the photo frame's NDC to the output frame's NDC so this viewport fills
     * the output exactly (photo NDC y is up, normalized y is down).
     */
    fun toGlMatrix(): FloatArray {
        val m = FloatArray(16)
        m[0] = 1f / width
        m[5] = 1f / height
        m[10] = 1f
        m[15] = 1f
        m[12] = (1f - 2f * centerX) / width
        m[13] = (2f * centerY - 1f) / height
        return m
    }
}

/**
 * One camera stop: at [time] (0..1 of the scene) the viewport is centered at ([centerX], [centerY]) with [width]
 * (photo-width units; height follows the aspect at render time). [pull] (0..1) pulls the camera back toward the
 * cover baseline in the middle of the move that arrives here, so long moves read as pull-back, travel, push-in.
 */
data class PhotoKeyframe(val time: Float, val centerX: Float, val centerY: Float, val width: Float, val pull: Float = 0f)

/**
 * Photo camera path shared by export ([PhotoMotionGlTransformation]) and the editor preview: keyframes joined by
 * smoothstep moves. With [cover] (채우기) every rendered frame is clamped to the photo itself at render time — the
 * width never exceeds the cover baseline and the center stays inside the half extents — so no frame can show
 * anything outside the photo (no letterbox), whatever the keyframes, aspect rounding or pull-back say.
 */
data class PhotoMotionPlan(
    val mode: PhotoMotionMode,
    val keyframes: List<PhotoKeyframe>,
    val cover: Boolean,
    val reason: String = "",
) {
    val isMoving: Boolean
        get() = keyframes.any { key ->
            val first = keyframes.first()
            abs(key.centerX - first.centerX) > 1e-4f || abs(key.centerY - first.centerY) > 1e-4f ||
                abs(key.width - first.width) > 1e-4f
        }

    fun viewportAt(fraction: Float, photoAspect: Float, outputAspect: Float): PhotoViewport {
        val k = if (photoAspect > 0f && outputAspect > 0f) photoAspect / outputAspect else 1f
        val p = fraction.coerceIn(0f, 1f)
        if (keyframes.isEmpty()) return PhotoViewport(0.5f, 0.5f, 1f, k)
        var i = 0
        while (i < keyframes.size - 2 && p > keyframes[i + 1].time) i++
        val a = keyframes[i]
        val b = keyframes.getOrElse(i + 1) { a }
        val span = b.time - a.time
        val u = if (span > 1e-6f) ((p - a.time) / span).coerceIn(0f, 1f) else 1f
        val e = u * u * (3f - 2f * u)
        var width = a.width + (b.width - a.width) * e
        var cx = a.centerX + (b.centerX - a.centerX) * e
        var cy = a.centerY + (b.centerY - a.centerY) * e
        if (cover) {
            val coverW = minOf(1f, 1f / k)
            if (b.pull > 0f) width += (coverW - width).coerceAtLeast(0f) * b.pull * 4f * e * (1f - e)
            width = width.coerceIn(1e-3f, coverW)
            val height = width * k
            cx = cx.coerceIn(width / 2f, 1f - width / 2f)
            cy = cy.coerceIn(height / 2f, 1f - height / 2f)
        }
        return PhotoViewport(cx, cy, width, width * k)
    }

    companion object {
        /** Whole photo, centered, no motion: only for unknown geometry or a too-short 맞추기 scene. */
        fun still(photoAspect: Float, outputAspect: Float, reason: String): PhotoMotionPlan {
            val k = if (photoAspect > 0f && outputAspect > 0f) photoAspect / outputAspect else 1f
            return PhotoMotionPlan(PhotoMotionMode.STILL, listOf(PhotoKeyframe(0f, 0.5f, 0.5f, maxOf(1f, 1f / k))), cover = false, reason = reason)
        }
    }
}

/**
 * Renders a full (orientation-applied) photo directly into the output size following [plan]. Replaces the
 * Presentation step for photo scenes, so zoom and pan are applied in photo coordinates before any crop.
 */
@OptIn(UnstableApi::class)
class PhotoMotionGlTransformation(
    private val plan: PhotoMotionPlan,
    private val durationUs: Long,
    private val outputWidth: Int,
    private val outputHeight: Int,
    plannedPhotoAspect: Float,
) : GlMatrixTransformation {
    private var baseTimeUs: Long = -1L
    private var photoAspect: Float = plannedPhotoAspect
    private val outputAspect: Float = outputWidth.toFloat() / outputHeight.coerceAtLeast(1)

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        if (inputWidth > 0 && inputHeight > 0) photoAspect = inputWidth.toFloat() / inputHeight
        return Size(outputWidth, outputHeight)
    }

    override fun getGlMatrixArray(presentationTimeUs: Long): FloatArray {
        if (baseTimeUs < 0L) {
            baseTimeUs = presentationTimeUs
        }
        val elapsedUs = (presentationTimeUs - baseTimeUs).coerceAtLeast(0L)
        val progress = if (durationUs > 0) (elapsedUs.toFloat() / durationUs).coerceIn(0f, 1f) else 0f
        return plan.viewportAt(progress, photoAspect, outputAspect).toGlMatrix()
    }
}

/** Pure camera-path geometry (no Android types) so it can be checked in JVM unit tests. */
internal object PhotoMotionGeometry {
    private const val MIN_MOTION_SECONDS = 0.5
    /** Tightest end framing (cover / 1.5) so the final person stays sharp and legible. */
    private const val MAX_END_ZOOM = 1.5f
    private const val MAX_STOP_ZOOM = 1.3f
    private const val MAX_START_ZOOM = 1.3f
    private const val START_HOLD_SECONDS = 0.15
    private const val STOP_HOLD_SECONDS = 0.35
    /** Other people/points the camera may pass before the primary one, when the scene is long enough. */
    private const val MAX_STOPS = 4
    /** Scenes at least this long get a start hold and a slow final push-in. */
    private const val LONG_SCENE_SECONDS = 2.0
    private const val RATE_SAMPLES = 120
    /** Scenes up to this long never pan: one fixed center, at most a gentle push-in (pans felt nauseating). */
    private const val GENTLE_SCENE_SECONDS = 3.0
    /** Total push-in of a [GENTLE_SCENE_SECONDS] scene; shorter scenes get proportionally less. */
    private const val GENTLE_MAX_ZOOM = 0.04f

    private fun gentleZoom(durationSeconds: Double): Float =
        GENTLE_MAX_ZOOM * (durationSeconds / GENTLE_SCENE_SECONDS).toFloat().coerceIn(0f, 1f)

    /**
     * Fastest camera travel at any instant of a 채우기 move, in viewport widths (horizontal) or heights (vertical)
     * per second: a point crosses the frame in no less than 2.5 s, a calm pan rather than a whip.
     */
    const val MAX_PAN_SPEED = 0.4f
    /** Fastest push/pull at any instant: |d ln(viewport width)/dt| — about 12% zoom per second at the peak. */
    const val MAX_ZOOM_RATE = 0.12f

    /** Gentle zoom amount for explicit 맞추기: ~4% for very short scenes up to 10%. */
    private fun zoomAmount(durationSeconds: Double, cap: Float): Float =
        (0.035f * durationSeconds.toFloat()).coerceIn(0.04f, cap)

    private fun PhotoNormRect.isValid(): Boolean =
        left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            left >= 0f && top >= 0f && right <= 1f && bottom <= 1f && left < right && top < bottom

    /** Face box widened to a head-and-shoulders frame so the focused person is never cut at the hairline/chin. */
    private fun headFrame(face: PhotoNormRect) = PhotoNormRect(
        (face.left - face.width * 0.35f).coerceAtLeast(0f),
        (face.top - face.height * 0.45f).coerceAtLeast(0f),
        (face.right + face.width * 0.35f).coerceAtMost(1f),
        (face.bottom + face.height * 0.35f).coerceAtMost(1f),
    )

    /**
     * @param faces individual detected faces; @param objects individual detected subjects (normalized, oriented photo).
     * @param analysisReliable false when analysis failed/unavailable: 채우기 still covers, aiming at the photo center.
     */
    fun plan(
        photoWidth: Int,
        photoHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
        fit: SceneFit,
        focusX: Float,
        focusY: Float,
        durationSeconds: Double,
        faces: List<PhotoNormRect>,
        objects: List<PhotoNormRect>,
        analysisReliable: Boolean,
        seed: Int,
    ): PhotoMotionPlan {
        val photoAspect = if (photoWidth > 0 && photoHeight > 0) photoWidth.toFloat() / photoHeight else 0f
        val outputAspect = if (outputWidth > 0 && outputHeight > 0) outputWidth.toFloat() / outputHeight else 0f
        if (!photoAspect.isFinite() || !outputAspect.isFinite() || photoAspect <= 0f || outputAspect <= 0f) {
            return PhotoMotionPlan.still(photoAspect, outputAspect, "사진 크기를 알 수 없음")
        }
        val shortScene = !durationSeconds.isFinite() || durationSeconds < MIN_MOTION_SECONDS
        val k = photoAspect / outputAspect
        if (fit == SceneFit.FIT) {
            return when {
                shortScene -> PhotoMotionPlan.still(photoAspect, outputAspect, "장면이 너무 짧음")
                durationSeconds <= GENTLE_SCENE_SECONDS -> {
                    // Whole photo stays visible: centered, no drift, zooming in from slightly wide to exactly fit.
                    val fitW = maxOf(1f, 1f / k)
                    PhotoMotionPlan(
                        PhotoMotionMode.FIT_MOTION,
                        listOf(
                            PhotoKeyframe(0f, 0.5f, 0.5f, fitW * (1f + gentleZoom(durationSeconds))),
                            PhotoKeyframe(1f, 0.5f, 0.5f, fitW),
                        ),
                        cover = false,
                        reason = "맞추기 선택 · 짧은 장면 살짝 확대",
                    )
                }
                else -> fitMotion(k, durationSeconds, seed, "맞추기 선택")
            }
        }
        return CoverPlanner(k, durationSeconds, seed).plan(
            focusX, focusY, shortScene,
            faces.filter { it.isValid() },
            objects.filter { it.isValid() },
            analysisReliable,
        )
    }

    private fun fitMotion(k: Float, durationSeconds: Double, seed: Int, reason: String): PhotoMotionPlan {
        val fitW = maxOf(1f, 1f / k)
        val outW = fitW * (1f + zoomAmount(durationSeconds, 0.10f))
        val zoomIn = (seed ushr 1) and 1 == 0
        val forward = seed and 1 == 0
        val dir = if (forward) 1f else -1f
        // Drift along the photo's long axis inside the margin opened by the zoomed-out window.
        val horizontal = k >= 1f
        fun containRange(extent: Float) = (1f - extent / 2f) to maxOf(1f - extent / 2f, extent / 2f)
        fun at(range: Pair<Float, Float>, q: Float) = range.first + (range.second - range.first) * q.coerceIn(0f, 1f)
        fun key(time: Float, width: Float, drift: Float): PhotoKeyframe {
            val xr = containRange(width)
            val yr = containRange(width * k)
            return if (horizontal) PhotoKeyframe(time, at(xr, 0.5f + drift), at(yr, 0.5f), width)
            else PhotoKeyframe(time, at(xr, 0.5f), at(yr, 0.5f + drift), width)
        }
        val fitKey = key(if (zoomIn) 1f else 0f, fitW, -0.35f * dir)
        val outKey = key(if (zoomIn) 0f else 1f, outW, 0.35f * dir)
        return PhotoMotionPlan(PhotoMotionMode.FIT_MOTION, listOf(fitKey, outKey).sortedBy { it.time }, cover = false, reason = reason)
    }

    /** One stop of the camera: where it looks and how tight. */
    private data class Frame(val cx: Float, val cy: Float, val w: Float)

    /**
     * 채우기 camera path: random legal start (stable per-photo seed) → other people / major points → the primary
     * person (else main subject, else photo center). All frames lie inside the photo; zoom-out stops at cover.
     * Time-aware: no instant pans faster than [MAX_PAN_SPEED] or zooms faster than [MAX_ZOOM_RATE]; stops that do
     * not fit the scene at that pace are skipped, and the start is only as far from the first target as time allows.
     */
    private class CoverPlanner(private val k: Float, private val duration: Double, seed: Int) {
        private val coverW = minOf(1f, 1f / k)
        private val coverH = coverW * k
        private val random = kotlin.random.Random(seed)

        /** Frame containing [target] when it fits (head room above faces), else centered on it; inside the photo. */
        fun frameFor(target: PhotoNormRect, preferredW: Float, minW: Float): Frame {
            val w = preferredW.coerceIn(minW, coverW)
            val h = w * k
            fun axis(lo: Float, hi: Float, extent: Float, wanted: Float): Float {
                val inLo = maxOf(extent / 2f, hi - extent / 2f)
                val inHi = minOf(1f - extent / 2f, lo + extent / 2f)
                return if (inLo <= inHi) wanted.coerceIn(inLo, inHi) else wanted.coerceIn(extent / 2f, 1f - extent / 2f)
            }
            return Frame(
                axis(target.left, target.right, w, target.centerX),
                axis(target.top, target.bottom, h, target.centerY + 0.08f * h),
                w,
            )
        }

        /** Width that frames [target] with breathing room, between cover/[maxZoom] and cover. */
        fun fitWidth(target: PhotoNormRect, maxZoom: Float): Float =
            (maxOf(target.width, target.height / k) * 1.15f).coerceIn(coverW / maxZoom, coverW)

        fun fits(target: PhotoNormRect): Boolean = target.width <= coverW * 0.98f && target.height <= coverH * 0.98f

        /** Groups head frames greedily (largest face first) into clusters that still fit one cover frame. */
        fun faceClusters(faces: List<PhotoNormRect>): List<PhotoNormRect> {
            val biggest = faces.maxOf { it.height }
            // Tiny background faces are not "main people".
            val main = faces.filter { it.height >= biggest * 0.35f }.sortedByDescending { it.width * it.height }
            val clusters = mutableListOf<PhotoNormRect>()
            for (face in main) {
                val head = headFrame(face)
                val index = clusters.indices
                    .filter { fits(clusters[it].union(head)) }
                    .minByOrNull { abs(clusters[it].centerX - head.centerX) + abs(clusters[it].centerY - head.centerY) }
                if (index != null) clusters[index] = clusters[index].union(head) else clusters.add(head)
            }
            return clusters
        }

        fun stopFrame(target: PhotoNormRect) = frameFor(target, fitWidth(target, MAX_STOP_ZOOM), coverW / MAX_STOP_ZOOM)

        /** [f] with its center moved inside the photo for its width. */
        fun legal(f: Frame): Frame {
            val h = f.w * k
            return Frame(f.cx.coerceIn(f.w / 2f, 1f - f.w / 2f), f.cy.coerceIn(h / 2f, 1f - h / 2f), f.w)
        }

        fun lerp(a: Frame, b: Frame, f: Float) = Frame(a.cx + (b.cx - a.cx) * f, a.cy + (b.cy - a.cy) * f, a.w + (b.w - a.w) * f)

        /** A smoothstep move to the next frame taking [seconds], pulling back mid-way by [pull]. */
        class Move(val seconds: Double, val pull: Float)

        /**
         * Shortest time for the rendered move a→b — sampled through the same [PhotoMotionPlan.viewportAt] the preview
         * and the exporter use, cover clamp included — that keeps every instant under [MAX_PAN_SPEED] and
         * [MAX_ZOOM_RATE]. Of no / some / strong pull-back, the one needing the least time is used.
         */
        fun move(a: Frame, b: Frame): Move {
            if (a == b) return Move(0.0, 0f)
            return listOf(0f, 0.5f, 0.85f).map { pull ->
                val leg = PhotoMotionPlan(
                    PhotoMotionMode.ZOOM,
                    listOf(PhotoKeyframe(0f, a.cx, a.cy, a.w), PhotoKeyframe(1f, b.cx, b.cy, b.w, pull)),
                    cover = true,
                )
                var prev = leg.viewportAt(0f, k, 1f)
                var worst = 0.0
                for (i in 1..RATE_SAMPLES) {
                    val v = leg.viewportAt(i.toFloat() / RATE_SAMPLES, k, 1f)
                    val pan = maxOf(
                        abs(v.centerX - prev.centerX) / minOf(v.width, prev.width),
                        abs(v.centerY - prev.centerY) / minOf(v.height, prev.height),
                    )
                    val zoom = abs(ln(v.width / prev.width))
                    worst = maxOf(worst, pan.toDouble() / MAX_PAN_SPEED, zoom.toDouble() / MAX_ZOOM_RATE)
                    prev = v
                }
                // Small margin over the sampled peak so the rendered peak stays under the limits.
                Move(worst * RATE_SAMPLES * 1.03, pull)
            }.minBy { it.seconds }
        }

        /**
         * Scenes up to [GENTLE_SCENE_SECONDS]: no pan, no travelling start, no pull-back. The widest (cover) frame
         * centered on the user's frame, else the primary head group, else the main subject, else the photo center;
         * that center is legal at cover width so it stays legal (no clamp drift) for every narrower keyframe. Then a
         * monotonic push-in of at most [gentleZoom], only as far as the kept people/subject stay fully inside;
         * none when they do not fit tighter than cover. Under [MIN_MOTION_SECONDS]: the same frame, still.
         */
        fun gentlePlan(
            manual: Boolean,
            focusX: Float,
            focusY: Float,
            still: Boolean,
            clusters: List<PhotoNormRect>,
            majorObjects: List<PhotoNormRect>,
            analysisReliable: Boolean,
        ): PhotoMotionPlan {
            val cx: Float
            val cy: Float
            val keep: PhotoNormRect?
            val label: String
            when {
                manual -> {
                    cx = 0.5f + focusX.coerceIn(-1f, 1f) * (1f - coverW) / 2f
                    cy = 0.5f + focusY.coerceIn(-1f, 1f) * (1f - coverH) / 2f
                    val userFrame = PhotoViewport(cx, cy, coverW, coverH)
                    keep = clusters.filter { userFrame.contains(it) }.reduceOrNull { a, b -> a.union(b) }
                    label = "지정 구도"
                }
                clusters.isNotEmpty() || majorObjects.isNotEmpty() -> {
                    val target = clusters.firstOrNull() ?: majorObjects.first()
                    val frame = frameFor(target, coverW, coverW)
                    cx = frame.cx
                    cy = frame.cy
                    keep = target
                    label = if (clusters.isNotEmpty()) "주인공" else "주요 피사체"
                }
                else -> {
                    cx = 0.5f
                    cy = 0.5f
                    keep = null
                    label = if (analysisReliable) "가운데" else "분석 결과 없음 · 가운데"
                }
            }
            var endW = coverW / (1f + gentleZoom(duration))
            if (keep != null) {
                val need = maxOf(2f * maxOf(cx - keep.left, keep.right - cx), 2f * maxOf(cy - keep.top, keep.bottom - cy) / k)
                endW = if (need >= coverW) coverW else maxOf(endW, need)
            }
            if (still || endW >= coverW - 1e-4f) {
                return PhotoMotionPlan(
                    PhotoMotionMode.STILL,
                    listOf(PhotoKeyframe(0f, cx, cy, coverW)),
                    cover = true,
                    reason = if (still) "장면이 너무 짧음" else "짧은 장면 · $label 고정",
                )
            }
            return PhotoMotionPlan(
                if (manual) PhotoMotionMode.MANUAL_FILL else PhotoMotionMode.ZOOM,
                listOf(PhotoKeyframe(0f, cx, cy, coverW), PhotoKeyframe(1f, cx, cy, endW)),
                cover = true,
                reason = "짧은 장면 · $label 살짝 확대",
            )
        }

        fun orders(items: List<Int>): List<List<Int>> =
            if (items.size <= 1) listOf(items) else items.flatMap { first -> orders(items - first).map { listOf(first) + it } }

        fun plan(
            focusX: Float,
            focusY: Float,
            shortScene: Boolean,
            faces: List<PhotoNormRect>,
            objects: List<PhotoNormRect>,
            analysisReliable: Boolean,
        ): PhotoMotionPlan {
            val clusters = if (faces.isNotEmpty()) faceClusters(faces) else emptyList()
            val majorObjects = objects.sortedByDescending { it.width * it.height }.let { sorted ->
                val top = sorted.firstOrNull()?.let { it.width * it.height } ?: 0f
                sorted.filter { it.width * it.height >= top * 0.25f }
            }
            val manual = focusX != 0f || focusY != 0f
            val mode = when {
                manual -> PhotoMotionMode.MANUAL_FILL
                k > 1.02f -> PhotoMotionMode.PAN_HORIZONTAL
                k < 0.98f -> PhotoMotionMode.PAN_VERTICAL
                else -> PhotoMotionMode.ZOOM
            }
            if (shortScene || duration <= GENTLE_SCENE_SECONDS) {
                return gentlePlan(manual, focusX, focusY, shortScene, clusters, majorObjects, analysisReliable)
            }

            // Final target: the user's frame, else the primary person, else the main subject, else the center.
            // Other people/points (most important first) are optional stops on the way.
            val end: Frame
            val stops: List<Frame>
            val describe: (visited: Int) -> String
            when {
                manual -> {
                    val cx = 0.5f + focusX.coerceIn(-1f, 1f) * (1f - coverW) / 2f
                    val cy = 0.5f + focusY.coerceIn(-1f, 1f) * (1f - coverH) / 2f
                    val userFrame = PhotoViewport(cx, cy, coverW, coverH)
                    val inside = clusters.filter { userFrame.contains(it) }
                    // Face-safe push-in: only as far as every person inside the user's frame stays inside.
                    val tight = legal(Frame(cx, cy, coverW / 1.12f))
                    end = if (inside.all { PhotoViewport(tight.cx, tight.cy, tight.w, tight.w * k).contains(it) }) tight
                    else Frame(cx, cy, coverW)
                    stops = clusters.filter { !userFrame.contains(it) }.map { stopFrame(it) }
                    describe = { n -> if (n == 0) "지정 구도로 이동" else "인물 ${n}곳 거쳐 지정 구도로" }
                }
                clusters.isNotEmpty() -> {
                    val primary = clusters.first()
                    end = frameFor(primary, fitWidth(primary, MAX_END_ZOOM), coverW / MAX_END_ZOOM)
                    stops = clusters.drop(1).map { stopFrame(it) }
                    describe = { n -> if (clusters.size == 1) "인물에 맞춰 이동" else "인물 ${n + 1}/${clusters.size}곳 · 마지막 주인공" }
                }
                majorObjects.isNotEmpty() -> {
                    val primary = majorObjects.first()
                    end = frameFor(primary, fitWidth(primary, MAX_END_ZOOM), coverW / MAX_END_ZOOM)
                    stops = majorObjects.drop(1)
                        .filter { abs(it.centerX - primary.centerX) + abs(it.centerY - primary.centerY) > 0.15f }
                        .map { stopFrame(it) }
                    describe = { _ -> "인물 없음 · 주요 피사체로 이동" }
                }
                else -> {
                    end = frameFor(PhotoNormRect(0.5f, 0.5f, 0.5f, 0.5f), coverW / 1.15f, coverW / 1.15f)
                    // Nothing detected but much of the photo is off screen: both far sides are optional stops.
                    val w = end.w
                    val sides = when {
                        1f - coverW > 0.35f -> listOf(Frame(w / 2f, 0.5f, w), Frame(1f - w / 2f, 0.5f, w))
                        1f - coverH > 0.35f -> listOf(Frame(0.5f, w * k / 2f, w), Frame(0.5f, 1f - w * k / 2f, w))
                        else -> emptyList()
                    }
                    stops = if (random.nextBoolean()) sides else sides.reversed()
                    describe = { _ -> if (analysisReliable) "인물·피사체 없음 · 가운데로 이동" else "분석 결과 없음 · 가운데로 이동" }
                }
            }
            // Long scenes open with a brief hold and close with a slow push-in onto the final framing.
            val longScene = duration >= LONG_SCENE_SECONDS
            val arrive = if (longScene) legal(Frame(end.cx, end.cy, minOf(coverW, end.w * 1.06f))) else end
            val startHold = if (longScene) START_HOLD_SECONDS else 0.0
            val minFinalHold = if (longScene) maxOf(move(arrive, end).seconds, (0.2 * duration).coerceIn(0.6, 2.0)) else 0.25 * duration

            // As many of the most important stops as fit at a calm pace (best order, ending on the primary); the rest
            // are skipped rather than rushed. Each move takes at least its rate-limited time.
            val candidates = stops.take(MAX_STOPS)
            val frames = candidates + arrive
            val moves = frames.map { a -> frames.map { b -> move(a, b) } }
            fun routeSeconds(order: List<Int>) =
                (order + candidates.size).zipWithNext { i, j -> moves[i][j].seconds }.sum() + order.size * STOP_HOLD_SECONDS
            var route = emptyList<Int>()
            for (n in candidates.size downTo 1) {
                val best = orders((0 until n).toList()).minBy { routeSeconds(it) }
                if (startHold + routeSeconds(best) + minFinalHold <= duration) {
                    route = best
                    break
                }
            }
            val anchor = route.firstOrNull()?.let { candidates[it] } ?: arrive
            val free = duration - startHold - routeSeconds(route) - minFinalHold

            // Random legal start (stable per photo) away from the first target, then drawn back toward it until the
            // opening move fits the time left: a short scene opens near its target, a long one may open far away.
            fun spread(c: Float, extent: Float, target: Float): Float {
                val lo = extent / 2f
                val hi = 1f - extent / 2f
                val room = hi - lo
                if (room < 0.02f || abs(c - target) >= 0.3f * room) return c
                val mirrored = lo + hi - c
                return if (abs(mirrored - target) >= 0.3f * room) mirrored else if (target < 0.5f) hi else lo
            }
            var w0 = coverW / (1f + random.nextFloat() * (MAX_START_ZOOM - 1f))
            if (abs(w0 - anchor.w) < 0.1f * coverW) w0 = if (anchor.w < 0.88f * coverW) coverW else coverW / MAX_START_ZOOM
            val h0 = w0 * k
            val far = Frame(
                spread(w0 / 2f + (1f - w0) * random.nextFloat(), w0, anchor.cx),
                spread(h0 / 2f + (1f - h0) * random.nextFloat(), h0, anchor.cy),
                w0,
            )
            var reach = 0f
            if (free > 0.0) {
                if (move(far, anchor).seconds <= free) {
                    reach = 1f
                } else {
                    var hi = 1f
                    repeat(12) {
                        val mid = (reach + hi) / 2f
                        if (move(lerp(anchor, far, mid), anchor).seconds <= free) reach = mid else hi = mid
                    }
                }
            }
            val start = lerp(anchor, far, reach)

            // Legs: start → stops → arrival at the primary. Spare time slows them further (up to 1.6x), the rest
            // lengthens the final push-in hold.
            val legs = mutableListOf(anchor to move(start, anchor))
            (route + candidates.size).zipWithNext { i, j -> legs.add(frames[j] to moves[i][j]) }
            val moveSeconds = legs.sumOf { it.second.seconds }
            val spare = (duration - startHold - moveSeconds - route.size * STOP_HOLD_SECONDS - minFinalHold).coerceAtLeast(0.0)
            val stretch = if (moveSeconds > 0.0) 1.0 + minOf(0.6, spare * 0.6 / moveSeconds) else 1.0

            val keys = mutableListOf(PhotoKeyframe(0f, start.cx, start.cy, start.w))
            var t = 0.0
            fun add(frame: Frame, pull: Float = 0f) {
                keys.add(PhotoKeyframe((t / duration).toFloat().coerceIn(0f, 1f), frame.cx, frame.cy, frame.w, pull))
            }
            if (startHold > 0.0) {
                t += startHold
                add(start)
            }
            legs.forEachIndexed { index, (to, leg) ->
                if (leg.seconds > 0.0) {
                    t += leg.seconds * stretch
                    add(to, leg.pull)
                }
                if (index < legs.lastIndex) {
                    t += STOP_HOLD_SECONDS
                    add(to)
                }
            }
            keys.add(PhotoKeyframe(1f, end.cx, end.cy, end.w))
            return PhotoMotionPlan(mode, keys, cover = true, reason = describe(route.size))
        }
    }
}

/** Individual detected faces and subjects (normalized, orientation applied); [failed] when analysis did not run. */
internal data class PhotoAnalysisData(
    val faces: List<PhotoNormRect>,
    val objects: List<PhotoNormRect>,
    val failed: Boolean,
) {
    companion object {
        val FAILED = PhotoAnalysisData(emptyList(), emptyList(), failed = true)
    }
}

object TrailerPhotoMotionPlanner {
    private val analysisCache = LruCache<String, PhotoAnalysisData>(128)
    private val cleanupExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TrailerPhotoMotion-Cleanup").apply {
            isDaemon = true
        }
    }

    private class AnalysisResources(
        val bitmap: Bitmap,
        val faceDetector: com.google.mlkit.vision.face.FaceDetector?,
        val objectDetector: com.google.mlkit.vision.objects.ObjectDetector?,
    ) {
        private val pendingTasks = AtomicInteger(0)
        private val isDisposed = AtomicBoolean(false)
        private val needsCleanup = AtomicBoolean(false)

        fun registerTask(task: com.google.android.gms.tasks.Task<*>) {
            pendingTasks.incrementAndGet()
            task.addOnCompleteListener(cleanupExecutor) {
                if (pendingTasks.decrementAndGet() == 0 && needsCleanup.get()) {
                    disposeInternal()
                }
            }
        }

        fun release() {
            needsCleanup.set(true)
            if (pendingTasks.get() == 0) {
                disposeInternal()
            }
        }

        private fun disposeInternal() {
            if (isDisposed.compareAndSet(false, true)) {
                cleanupExecutor.execute {
                    try { bitmap.recycle() } catch (_: Throwable) {}
                    try { faceDetector?.close() } catch (_: Throwable) {}
                    try { objectDetector?.close() } catch (_: Throwable) {}
                }
            }
        }
    }

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitCancellable(): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener(cleanupExecutor) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
            addOnFailureListener(cleanupExecutor) { exception ->
                if (continuation.isActive) continuation.resumeWithException(exception)
            }
            addOnCanceledListener(cleanupExecutor) {
                if (continuation.isActive) continuation.cancel()
            }
        }

    private fun decodeOrientedBitmapForAnalysis(file: File, maxDimension: Int = 1280): Bitmap? {
        if (!file.exists() || !file.isFile || !file.canRead()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val exif = try {
            ExifInterface(file.absolutePath)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ?: ExifInterface.ORIENTATION_NORMAL
        val isRotated90 = orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270
        val (rawW, rawH) = if (isRotated90) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight

        var sample = 1
        var w = rawW
        var h = rawH
        while (maxOf(w, h) > maxDimension) {
            sample *= 2
            w = rawW / sample
            h = rawH / sample
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sample.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = try {
            BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        } ?: return null

        return try {
            val matrix = AndroidMatrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.postScale(-1f, 1f)
                }
            }
            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                if (rotated !== decoded) decoded.recycle()
                rotated
            } else {
                decoded
            }
        } catch (e: Exception) {
            decoded.recycle()
            if (e is CancellationException) throw e
            null
        }
    }

    private suspend fun analyzePhoto(file: File): PhotoAnalysisData = withContext(Dispatchers.Default) {
        val bitmap = decodeOrientedBitmapForAnalysis(file)
            ?: return@withContext PhotoAnalysisData.FAILED

        var faceDetector: com.google.mlkit.vision.face.FaceDetector? = null
        var objectDetector: com.google.mlkit.vision.objects.ObjectDetector? = null
        var resources: AnalysisResources? = null

        try {
            faceDetector = FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .build()
            )
            objectDetector = ObjectDetection.getClient(
                ObjectDetectorOptions.Builder()
                    .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                    .enableMultipleObjects()
                    .build()
            )
            val res = AnalysisResources(bitmap, faceDetector, objectDetector)
            resources = res

            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val faceRects = mutableListOf<PhotoNormRect>()
            val objectRects = mutableListOf<PhotoNormRect>()
            var detectionFailed = false

            try {
                val faceTask = faceDetector.process(inputImage)
                res.registerTask(faceTask)
                val faces = faceTask.awaitCancellable()
                for (face in faces) {
                    val box = face.boundingBox
                    val left = box.left.toFloat() / bitmap.width
                    val top = box.top.toFloat() / bitmap.height
                    val right = box.right.toFloat() / bitmap.width
                    val bottom = box.bottom.toFloat() / bitmap.height
                    if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite() ||
                        box.width() <= 0 || box.height() <= 0 || right <= left || bottom <= top
                    ) {
                        detectionFailed = true
                        break
                    }
                    faceRects.add(
                        PhotoNormRect(
                            left.coerceIn(0f, 1f),
                            top.coerceIn(0f, 1f),
                            right.coerceIn(0f, 1f),
                            bottom.coerceIn(0f, 1f),
                        )
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                detectionFailed = true
            }

            if (!detectionFailed) {
                try {
                    val objectTask = objectDetector.process(inputImage)
                    res.registerTask(objectTask)
                    val objects = objectTask.awaitCancellable()
                    for (obj in objects) {
                        val box = obj.boundingBox
                        val left = box.left.toFloat() / bitmap.width
                        val top = box.top.toFloat() / bitmap.height
                        val right = box.right.toFloat() / bitmap.width
                        val bottom = box.bottom.toFloat() / bitmap.height
                        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite() ||
                            box.width() <= 0 || box.height() <= 0 || right <= left || bottom <= top
                        ) {
                            detectionFailed = true
                            break
                        }
                        objectRects.add(
                            PhotoNormRect(
                                left.coerceIn(0f, 1f),
                                top.coerceIn(0f, 1f),
                                right.coerceIn(0f, 1f),
                                bottom.coerceIn(0f, 1f),
                            )
                        )
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    detectionFailed = true
                }
            }

            if (detectionFailed) {
                return@withContext PhotoAnalysisData.FAILED
            }

            PhotoAnalysisData(faceRects, objectRects, failed = false)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            PhotoAnalysisData.FAILED
        } finally {
            if (resources != null) {
                resources.release()
            } else {
                try { bitmap.recycle() } catch (_: Throwable) {}
                try { faceDetector?.close() } catch (_: Throwable) {}
                try { objectDetector?.close() } catch (_: Throwable) {}
            }
        }
    }

    private suspend fun getOrAnalyzePhoto(file: File): PhotoAnalysisData {
        val cacheKey = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
        val cached = synchronized(analysisCache) { analysisCache.get(cacheKey) }
        if (cached != null) return cached

        val fresh = analyzePhoto(file)
        if (!fresh.failed) {
            synchronized(analysisCache) { analysisCache.put(cacheKey, fresh) }
        }
        return fresh
    }


    private fun seedOf(source: TrailerSource): Int = source.id * 31 + source.name.hashCode()

    suspend fun planMotion(
        source: TrailerSource,
        fit: SceneFit,
        focusX: Float,
        focusY: Float,
        outputWidth: Int,
        outputHeight: Int,
        durationSeconds: Double,
    ): PhotoMotionPlan = withContext(Dispatchers.Default) {
        // FIT keeps the whole photo, so it needs no analysis; FILL and manual focus aim the camera at people/subjects.
        val needsAnalysis = fit == SceneFit.FILL && source.width > 0 && source.height > 0 &&
            outputWidth > 0 && outputHeight > 0
        val analysis = if (needsAnalysis) {
            try {
                getOrAnalyzePhoto(source.file)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                PhotoAnalysisData.FAILED
            }
        } else {
            null
        }
        PhotoMotionGeometry.plan(
            photoWidth = source.width,
            photoHeight = source.height,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            fit = fit,
            focusX = focusX,
            focusY = focusY,
            durationSeconds = durationSeconds,
            faces = analysis?.faces.orEmpty(),
            objects = analysis?.objects.orEmpty(),
            analysisReliable = analysis != null && !analysis.failed,
            seed = seedOf(source),
        )
    }

    /** Plan before analysis finishes (preview placeholder): covers the frame and aims at the center until people are known. */
    fun pendingPlan(
        source: TrailerSource,
        fit: SceneFit,
        focusX: Float,
        focusY: Float,
        outputWidth: Int,
        outputHeight: Int,
        durationSeconds: Double,
    ): PhotoMotionPlan = PhotoMotionGeometry.plan(
        photoWidth = source.width,
        photoHeight = source.height,
        outputWidth = outputWidth,
        outputHeight = outputHeight,
        fit = fit,
        focusX = focusX,
        focusY = focusY,
        durationSeconds = durationSeconds,
        faces = emptyList(),
        objects = emptyList(),
        analysisReliable = false,
        seed = seedOf(source),
    ).let { if (it.cover && it.mode != PhotoMotionMode.STILL) it.copy(reason = "인물 찾는 중") else it }
}
