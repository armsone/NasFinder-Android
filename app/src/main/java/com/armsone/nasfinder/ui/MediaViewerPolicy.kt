package com.armsone.nasfinder.ui

import android.content.Context
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class MediaViewerGestureMode {
    NONE,
    SEEK,
    VOLUME,
    DISMISS,
}

enum class MediaPlaybackMode(val title: String) {
    ALL_REPEAT("전체 반복"),
    SHUFFLE("임의 재생"),
    ONE_REPEAT("한 항목 반복");

    fun next(): MediaPlaybackMode = when (this) {
        ALL_REPEAT -> SHUFFLE
        SHUFFLE -> ONE_REPEAT
        ONE_REPEAT -> ALL_REPEAT
    }
}

internal object BuiltInPreviewPolicy {
    private val externalOnlyVideoExtensions = setOf("avi", "asf", "wmv")
    private val imageExtensions = setOf(
        "jpg", "jpeg", "jpe", "png", "gif", "heic", "heif", "webp", "bmp", "dib",
        "tif", "tiff", "avif", "svg",
    )
    private val videoExtensions = setOf(
        "3g2", "3gp", "flv", "fmp4", "m2ts", "m4v", "mkv", "mov", "mpe",
        "mpeg", "mpg", "mp4", "mts", "ogv", "qt", "ts", "vob", "webm",
    )
    private val audioExtensions = setOf("mp3", "m4a", "aac", "adts", "wav", "ogg", "oga", "flac", "opus", "amr")

    fun kind(filename: String, mimeType: String?): BuiltInPreviewKind? {
        val extension = filename.substringAfterLast('.', "").lowercase()
        val mime = mimeType.orEmpty().substringBefore(';').trim().lowercase()
        return when {
            extension in externalOnlyVideoExtensions -> null
            mime.startsWith("image/") || extension in imageExtensions -> BuiltInPreviewKind.IMAGE
            mime.startsWith("video/") || extension in videoExtensions -> BuiltInPreviewKind.VIDEO
            mime == "application/pdf" || extension == "pdf" -> BuiltInPreviewKind.PDF
            mime.startsWith("audio/") || extension in audioExtensions -> BuiltInPreviewKind.AUDIO
            else -> null
        }
    }
}

object MediaSlideshowPolicy {
    val INTERVAL_OPTIONS = listOf(1, 2, 3, 5, 10, 15, 30)
    const val DEFAULT_INTERVAL_SECONDS = 5

    fun nextInterval(currentSeconds: Int): Int {
        val index = INTERVAL_OPTIONS.indexOf(currentSeconds)
        return if (index < 0 || index == INTERVAL_OPTIONS.lastIndex) {
            INTERVAL_OPTIONS.first()
        } else {
            INTERVAL_OPTIONS[index + 1]
        }
    }

    fun nextIndex(
        currentIndex: Int,
        totalCount: Int,
        mode: MediaPlaybackMode,
        randomIndexProvider: (Int) -> Int = { (0 until it).random() },
    ): Int {
        if (totalCount <= 1) return 0
        return when (mode) {
            MediaPlaybackMode.ONE_REPEAT -> currentIndex
            MediaPlaybackMode.ALL_REPEAT -> (currentIndex + 1) % totalCount
            MediaPlaybackMode.SHUFFLE -> {
                var next = randomIndexProvider(totalCount)
                if (next == currentIndex && totalCount > 1) {
                    next = (currentIndex + 1) % totalCount
                }
                next
            }
        }
    }
}

object MediaViewerPreferences {
    private const val PREFS_NAME = "media_viewer_preferences"
    private const val KEY_PLAYBACK_MODE = "playback_mode"
    private const val KEY_SLIDESHOW_INTERVAL = "slideshow_interval_seconds"

    fun getPlaybackMode(context: Context): MediaPlaybackMode {
        val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PLAYBACK_MODE, MediaPlaybackMode.ALL_REPEAT.name)
        return runCatching { MediaPlaybackMode.valueOf(name ?: MediaPlaybackMode.ALL_REPEAT.name) }
            .getOrDefault(MediaPlaybackMode.ALL_REPEAT)
    }

    fun setPlaybackMode(context: Context, mode: MediaPlaybackMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PLAYBACK_MODE, mode.name).apply()
    }

    fun getSlideshowInterval(context: Context): Int {
        val value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SLIDESHOW_INTERVAL, MediaSlideshowPolicy.DEFAULT_INTERVAL_SECONDS)
        return if (value in MediaSlideshowPolicy.INTERVAL_OPTIONS) value else MediaSlideshowPolicy.DEFAULT_INTERVAL_SECONDS
    }

    fun setSlideshowInterval(context: Context, intervalSeconds: Int) {
        val safe = if (intervalSeconds in MediaSlideshowPolicy.INTERVAL_OPTIONS) intervalSeconds else MediaSlideshowPolicy.DEFAULT_INTERVAL_SECONDS
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_SLIDESHOW_INTERVAL, safe).apply()
    }
}

object MediaViewerGesturePolicy {
    const val DEFAULT_TOUCH_SLOP_DP = 12f
    const val DEFAULT_DISMISS_THRESHOLD_DP = 120f
    const val DEFAULT_IMAGE_NAVIGATION_THRESHOLD_DP = 60f
    const val MAX_IMAGE_SCALE = 6.0f
    const val MIN_IMAGE_SCALE = 1.0f
    const val DOUBLE_TAP_ZOOM_SCALE = 2.5f

    fun logicalDistancePx(distanceDp: Float, density: Float): Float =
        distanceDp * density.coerceAtLeast(0.1f)

    fun determineGestureMode(
        totalDragX: Float,
        totalDragY: Float,
        touchSlop: Float,
    ): MediaViewerGestureMode {
        val distance = hypot(totalDragX, totalDragY)
        if (distance < touchSlop) return MediaViewerGestureMode.NONE
        return if (abs(totalDragX) >= abs(totalDragY)) {
            MediaViewerGestureMode.SEEK
        } else if (totalDragY < 0f) {
            MediaViewerGestureMode.VOLUME
        } else {
            MediaViewerGestureMode.DISMISS
        }
    }

    fun computeVolume(
        startVolume: Int,
        maxVolume: Int,
        accumulatedDragY: Float,
        trackHeight: Float,
    ): Int {
        if (maxVolume <= 0) return 0
        val effectiveHeight = trackHeight.coerceAtLeast(100f)
        val deltaFraction = -accumulatedDragY / effectiveHeight
        val startFraction = startVolume.toFloat() / maxVolume.toFloat()
        val newFraction = (startFraction + deltaFraction).coerceIn(0f, 1f)
        return (newFraction * maxVolume).roundToInt().coerceIn(0, maxVolume)
    }

    fun computeVolumePercent(
        startVolume: Int,
        maxVolume: Int,
        accumulatedDragY: Float,
        trackHeight: Float,
    ): Int {
        if (maxVolume <= 0) return 0
        val volume = computeVolume(startVolume, maxVolume, accumulatedDragY, trackHeight)
        return (volume * 100f / maxVolume).roundToInt()
    }

    fun computeSeekPosition(
        startPositionMs: Int,
        durationMs: Int,
        accumulatedDragX: Float,
        trackWidth: Float,
    ): Int {
        if (durationMs <= 0) return 0
        val effectiveWidth = trackWidth.coerceAtLeast(100f)
        val deltaFraction = accumulatedDragX / effectiveWidth
        val deltaMs = deltaFraction * durationMs
        return (startPositionMs + deltaMs).roundToInt().coerceIn(0, durationMs)
    }

    fun computeDismissProgress(
        translationY: Float,
        dismissThreshold: Float,
    ): Float {
        if (translationY <= 0f) return 1f
        val maxFadeDistance = dismissThreshold * 2.5f
        return (1f - (translationY / maxFadeDistance)).coerceIn(0.15f, 1f)
    }

    fun shouldDismiss(
        translationY: Float,
        dismissThreshold: Float,
    ): Boolean = translationY >= dismissThreshold

    fun shouldNavigateImage(
        dragX: Float,
        scale: Float,
        threshold: Float,
    ): Int {
        if (scale > 1.05f) return 0
        return when {
            dragX > threshold -> -1 // previous
            dragX < -threshold -> 1  // next
            else -> 0
        }
    }

    fun clampImageTranslation(
        currentTranslation: Offset,
        scale: Float,
        containerWidth: Float,
        containerHeight: Float,
    ): Offset {
        if (scale <= 1.0f) return Offset.Zero
        val maxTranslateX = (containerWidth * (scale - 1f)) / 2f
        val maxTranslateY = (containerHeight * (scale - 1f)) / 2f
        return Offset(
            currentTranslation.x.coerceIn(-maxTranslateX, maxTranslateX),
            currentTranslation.y.coerceIn(-maxTranslateY, maxTranslateY),
        )
    }
}

internal object AdaptiveOverflowPolicy {
    const val MIN_WIDTH_DP = 600f
    const val MIN_HEIGHT_DP = 320f
    const val MIN_LANDSCAPE_ASPECT = 1.20f

    fun shouldUseAutomaticOverflow(
        contentWidthDp: Float,
        contentHeightDp: Float,
        itemCount: Int,
        selectionActive: Boolean,
    ): Boolean = itemCount > 0 &&
        !selectionActive &&
        contentWidthDp >= MIN_WIDTH_DP &&
        contentHeightDp >= MIN_HEIGHT_DP &&
        contentWidthDp / contentHeightDp.coerceAtLeast(1f) >= MIN_LANDSCAPE_ASPECT
}

internal object CoverFlowLayoutPolicy {
    const val VISIBLE_CARD_COUNT_PER_SIDE = 7
    const val CHROME_HEIGHT_DP = 56f
    const val BOTTOM_SAFE_RESERVE_DP = 18f

    fun baseCardSide(containerWidth: Float, centralCardSide: Float): Float =
        min(max(containerWidth * 0.23f, 180f), centralCardSide * 0.80f).coerceAtLeast(1f)

    fun centralCardSide(
        containerWidth: Float,
        containerHeight: Float,
        topContentInset: Float,
        bottomContentInset: Float,
    ): Float {
        val contentTop = topContentInset + CHROME_HEIGHT_DP
        val contentBottom = bottomContentInset + BOTTOM_SAFE_RESERVE_DP
        val availableHeight = (containerHeight - contentTop - contentBottom).coerceAtLeast(1f)
        // The reflection occupies the lower edge of the same visual group. Keep the
        // square centered while leaving enough room for that reflection on short phones.
        val heightBound = availableHeight * 0.92f
        val widthBound = containerWidth * 0.48f
        return min(widthBound, heightBound).coerceAtLeast(1f)
    }

    fun cardStep(containerWidth: Float): Float = (containerWidth * 0.05f).coerceIn(42f, 66f)

    fun renderedCardSide(distance: Float, baseCardSide: Float, centralCardSide: Float): Float {
        val absoluteDistance = abs(distance)
        val surroundingScale = if (absoluteDistance <= 1f) {
            1.08f - 0.16f * absoluteDistance
        } else {
            max(0.60f, 0.92f - 0.08f * (absoluteDistance - 1f))
        }
        val focus = (1f - absoluteDistance).coerceAtLeast(0f)
        val easedFocus = smoothstep(focus)
        val side = baseCardSide * surroundingScale * 0.80f
        return side + (centralCardSide - side) * easedFocus
    }

    fun horizontalPush(distance: Float, baseCardSide: Float, centralCardSide: Float): Float {
        if (distance == 0f) return 0f
        val travel = smoothstep(abs(distance).coerceIn(0f, 1f))
        val fullPush = max((centralCardSide - baseCardSide * 0.92f * 0.80f) / 2f + 18f, 44f)
        return if (distance < 0f) -fullPush * travel else fullPush * travel
    }

    fun opacity(distance: Float): Float {
        val absoluteDistance = abs(distance)
        return if (absoluteDistance > 5f) {
            max(0f, (8f - absoluteDistance) * 0.14f)
        } else {
            max(0.16f, 1f - absoluteDistance * 0.115f)
        }
    }

    fun continuousPosition(
        itemIndex: Int,
        itemCenter: Float,
        viewportCenter: Float,
        itemSize: Float,
    ): Float = itemIndex + (viewportCenter - itemCenter) / itemSize.coerceAtLeast(1f)

    private fun smoothstep(value: Float): Float {
        val clamped = value.coerceIn(0f, 1f)
        return clamped * clamped * (3f - 2f * clamped)
    }
}
