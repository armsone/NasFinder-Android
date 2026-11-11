package com.armsone.nasfinder.ui

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// =============================================================================
// Manual Field Metadata & Bitmask
// =============================================================================

/**
 * Individual field categories the user manually modified on an [EditorScene].
 * Marked fields are protected from automatic recommendations, beat sync, and budget fitting.
 */
enum class ManualField(val mask: Int) {
    TIMING(1 shl 0),      // 1: start / length / actionStart / actionEnd
    AUDIO(1 shl 1),       // 2: sourceVolume
    FRAMING(1 shl 2),     // 4: fit / focusX / focusY / color / keyframes
    CAPTION(1 shl 3),     // 8: static caption / timed caption cues / suppressions
    ORDER(1 shl 4),       // 16: scene order
    EXCLUSION(1 shl 5),   // 32: user-toggled inclusion/exclusion
    ADJUST(1 shl 6);      // 64: rate / transition (does not freeze start/length window)
}

/**
 * Immutable bitmask of [ManualField]s.
 */
data class ManualFields(val rawMask: Int = 0) {
    val timing: Boolean get() = (rawMask and ManualField.TIMING.mask) != 0
    val audio: Boolean get() = (rawMask and ManualField.AUDIO.mask) != 0
    val framing: Boolean get() = (rawMask and ManualField.FRAMING.mask) != 0
    val caption: Boolean get() = (rawMask and ManualField.CAPTION.mask) != 0
    val order: Boolean get() = (rawMask and ManualField.ORDER.mask) != 0
    val exclusion: Boolean get() = (rawMask and ManualField.EXCLUSION.mask) != 0
    val adjust: Boolean get() = (rawMask and ManualField.ADJUST.mask) != 0

    fun contains(field: ManualField): Boolean = (rawMask and field.mask) != 0
    fun with(field: ManualField): ManualFields = ManualFields(rawMask or field.mask)
    fun with(vararg fields: ManualField): ManualFields = ManualFields(rawMask or fields.fold(0) { acc, f -> acc or f.mask })
    fun without(field: ManualField): ManualFields = ManualFields(rawMask and field.mask.inv())
    fun plus(other: ManualFields): ManualFields = ManualFields(rawMask or other.rawMask)
    fun intersect(other: ManualFields): ManualFields = ManualFields(rawMask and other.rawMask)
    fun hasManualContent(): Boolean = (rawMask and CONTENT_MASK) != 0
    fun isEmpty(): Boolean = rawMask == 0

    companion object {
        val NONE = ManualFields(0)
        val CONTENT_MASK = ManualField.AUDIO.mask or ManualField.FRAMING.mask or ManualField.CAPTION.mask or ManualField.ADJUST.mask
        val ALL_MASK = (1 shl 7) - 1
        fun fromInt(raw: Int): ManualFields = ManualFields(raw and ALL_MASK)
    }
}

// =============================================================================
// Timed Caption Cues
// =============================================================================

enum class CueOrigin(val value: String) {
    MANUAL("manual"),
    GENERATED("generated");

    companion object {
        fun fromString(raw: String?): CueOrigin =
            if (raw?.equals("generated", ignoreCase = true) == true) GENERATED else MANUAL
    }
}

/**
 * Timed caption cue representing a caption segment tied to a source clip.
 */
data class TrailerTimedCaptionCue(
    val stableId: UUID = UUID.randomUUID(),
    val sourceId: Int = 0,
    val sourceStart: Double = 0.0,
    val sourceEnd: Double = 0.0,
    val text: String = "",
    val style: SceneCaption = SceneCaption(text = ""),
    val origin: CueOrigin = CueOrigin.MANUAL,
    val manuallyEdited: Boolean = false,
) {
    val id: UUID get() = stableId
    val sourceDuration: Double get() = max(0.0, sourceEnd - sourceStart)

    fun toJson(): JSONObject = JSONObject().apply {
        put("stableId", stableId.toString())
        put("sourceId", sourceId)
        put("sourceStart", sourceStart)
        put("sourceEnd", sourceEnd)
        put("text", text)
        put("style", style.toJson())
        put("origin", origin.value)
        put("manuallyEdited", manuallyEdited)
    }

    companion object {
        fun fromJson(obj: JSONObject?): TrailerTimedCaptionCue? {
            if (obj == null) return null
            val idStr = obj.optString("stableId").ifBlank { obj.optString("id") }
            val id = runCatching { UUID.fromString(idStr) }.getOrNull() ?: UUID.randomUUID()
            val sId = obj.optInt("sourceId", 0)
            val rawStart = obj.optDouble("sourceStart", 0.0)
            val rawEnd = obj.optDouble("sourceEnd", 0.0)
            val sStart = if (rawStart.isFinite()) max(0.0, rawStart) else 0.0
            val sEnd = if (rawEnd.isFinite()) max(sStart, rawEnd) else sStart
            val txt = obj.optString("text", "")
            val style = sceneCaptionFromJson(obj.optJSONObject("style"), allowBlank = true) ?: SceneCaption(text = "")
            val origin = CueOrigin.fromString(obj.optString("origin", "manual"))
            val edited = obj.optBoolean("manuallyEdited", false)
            return TrailerTimedCaptionCue(
                stableId = id,
                sourceId = sId,
                sourceStart = sStart,
                sourceEnd = sEnd,
                text = txt,
                style = style,
                origin = origin,
                manuallyEdited = edited,
            )
        }

        /**
         * Merges existing cues with newly generated cues.
         * - Manual-owned / edited cues persist and block overlapping generated candidates.
         * - Suppressions block generated cues in their range (manual cues remain exempt).
         * - Reuses matching existing generated IDs by source/time identity (within 0.1s tolerance).
         * - Returns a deterministic, sorted, deduplicated cue list.
         */
        fun merge(
            existingCues: List<TrailerTimedCaptionCue>,
            newGeneratedCues: List<TrailerTimedCaptionCue>,
            suppressions: List<TrailerCueSuppression> = emptyList(),
        ): List<TrailerTimedCaptionCue> {
            val manualCues = mutableListOf<TrailerTimedCaptionCue>()
            val existingGeneratedCues = mutableListOf<TrailerTimedCaptionCue>()

            for (cue in existingCues) {
                if (cue.origin == CueOrigin.MANUAL || cue.manuallyEdited) {
                    manualCues.add(cue.copy(origin = CueOrigin.MANUAL, manuallyEdited = true))
                } else {
                    existingGeneratedCues.add(cue)
                }
            }

            val acceptedGeneratedCues = mutableListOf<TrailerTimedCaptionCue>()
            for (candidate in newGeneratedCues) {
                val cStart = candidate.sourceStart
                val cEnd = candidate.sourceEnd
                if (cEnd <= cStart) continue

                val isSuppressed = suppressions.any { sup -> sup.overlaps(candidate.sourceId, cStart, cEnd) }
                if (isSuppressed) continue

                val overlapsManual = manualCues.any { manual ->
                    manual.sourceId == candidate.sourceId &&
                        max(manual.sourceStart, cStart) < min(manual.sourceEnd, cEnd)
                }
                if (overlapsManual) continue

                var matchedId = candidate.stableId
                val matchingExisting = existingGeneratedCues.firstOrNull { existing ->
                    existing.sourceId == candidate.sourceId &&
                        abs(existing.sourceStart - cStart) < 0.1 &&
                        abs(existing.sourceEnd - cEnd) < 0.1
                }
                if (matchingExisting != null) {
                    matchedId = matchingExisting.stableId
                }

                acceptedGeneratedCues.add(
                    candidate.copy(
                        stableId = matchedId,
                        origin = CueOrigin.GENERATED,
                        manuallyEdited = false,
                    )
                )
            }

            val combined = (manualCues + acceptedGeneratedCues).sortedWith(
                compareBy<TrailerTimedCaptionCue> { it.sourceId }
                    .thenBy { it.sourceStart }
                    .thenBy { it.sourceEnd }
                    .thenBy { it.stableId.toString() }
            )

            val deduplicated = mutableListOf<TrailerTimedCaptionCue>()
            val seenIds = mutableSetOf<UUID>()
            for (cue in combined) {
                if (seenIds.add(cue.stableId)) {
                    deduplicated.add(cue)
                }
            }
            return deduplicated
        }
    }
}

// =============================================================================
// Cue Suppression Tombstone
// =============================================================================

/**
 * Tombstone indicating an auto-deleted cue range that should stay deleted during regeneration.
 */
data class TrailerCueSuppression(
    val sourceId: Int = 0,
    val sourceStart: Double = 0.0,
    val sourceEnd: Double = 0.0,
) {
    val id: String get() = "$sourceId:$sourceStart-$sourceEnd"

    fun overlaps(sourceId: Int, start: Double, end: Double): Boolean {
        if (this.sourceId != sourceId) return false
        val s = if (start.isFinite()) max(0.0, start) else 0.0
        val e = if (end.isFinite()) max(s, end) else s
        val myS = if (sourceStart.isFinite()) max(0.0, sourceStart) else 0.0
        val myE = if (sourceEnd.isFinite()) max(myS, sourceEnd) else myS
        return max(myS, s) < min(myE, e)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("sourceId", sourceId)
        put("sourceStart", sourceStart)
        put("sourceEnd", sourceEnd)
    }

    companion object {
        fun fromJson(obj: JSONObject?): TrailerCueSuppression? {
            if (obj == null) return null
            val sId = obj.optInt("sourceId", 0)
            val rawStart = obj.optDouble("sourceStart", 0.0)
            val rawEnd = obj.optDouble("sourceEnd", 0.0)
            val s = if (rawStart.isFinite()) max(0.0, rawStart) else 0.0
            val e = if (rawEnd.isFinite()) max(s, rawEnd) else s
            return TrailerCueSuppression(sourceId = sId, sourceStart = s, sourceEnd = e)
        }
    }
}

// =============================================================================
// Voiceover Take
// =============================================================================

/**
 * Voiceover audio track take for a trailer project.
 */
data class TrailerVoiceoverTake(
    val stableId: UUID = UUID.randomUUID(),
    val relativeFile: String = "",
    val outputStart: Double = 0.0,
    val sourceIn: Double = 0.0,
    val sourceOut: Double = 0.0,
    val volume: Double = 1.0,
) {
    val id: UUID get() = stableId

    data class EffectivePlacement(
        val outputStart: Double,
        val outputDuration: Double,
        val sourceIn: Double,
        val sourceOut: Double,
        val volume: Double,
    )

    fun hardOutputRange(): ClosedFloatingPointRange<Double>? {
        if (!isValidRelativeFilePath(relativeFile)) return null
        if (!outputStart.isFinite() || outputStart < 0.0) return null
        if (!sourceIn.isFinite() || !sourceOut.isFinite() || sourceOut <= sourceIn) return null
        val duration = sourceOut - sourceIn
        val end = outputStart + duration
        if (!end.isFinite()) return null
        return outputStart..end
    }

    fun requiredOutputEnd(): Double? = hardOutputRange()?.endInclusive

    fun effectivePlacement(projectDuration: Double): EffectivePlacement? {
        if (!isValidRelativeFilePath(relativeFile)) return null
        if (!projectDuration.isFinite() || projectDuration <= 0.0) return null
        if (!sourceIn.isFinite() || !sourceOut.isFinite() || sourceOut <= sourceIn) return null
        val takeDuration = sourceOut - sourceIn
        val takeOutputEnd = outputStart + takeDuration
        if (!takeOutputEnd.isFinite()) return null
        if (outputStart >= projectDuration || takeOutputEnd <= 0.0) return null

        val effStart = max(0.0, outputStart)
        val offsetFromTakeStart = effStart - outputStart
        val effSourceIn = sourceIn + offsetFromTakeStart
        val effEnd = min(projectDuration, takeOutputEnd)
        val effDuration = max(0.0, effEnd - effStart)
        if (effDuration <= 0.0001) return null
        val effSourceOut = effSourceIn + effDuration

        return EffectivePlacement(
            outputStart = effStart,
            outputDuration = effDuration,
            sourceIn = effSourceIn,
            sourceOut = effSourceOut,
            volume = volume,
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("stableId", stableId.toString())
        put("relativeFile", relativeFile)
        put("outputStart", outputStart)
        put("sourceIn", sourceIn)
        put("sourceOut", sourceOut)
        put("volume", volume)
    }

    companion object {
        fun isValidRelativeFilePath(path: String): Boolean {
            val trimmed = path.trim()
            if (trimmed.isEmpty()) return false
            if (trimmed.startsWith("/")) return false
            val parts = trimmed.split("/")
            for (p in parts) {
                if (p == ".." || p == ".") return false
            }
            return true
        }

        fun fromJson(obj: JSONObject?): TrailerVoiceoverTake? {
            if (obj == null) return null
            val idStr = obj.optString("stableId").ifBlank { obj.optString("id") }
            val id = runCatching { UUID.fromString(idStr) }.getOrNull() ?: UUID.randomUUID()
            val relFile = obj.optString("relativeFile", "")
            val outStart = obj.optDouble("outputStart", 0.0)
            val sIn = obj.optDouble("sourceIn", 0.0)
            val sOut = obj.optDouble("sourceOut", 0.0)
            val vol = obj.optDouble("volume", 1.0)
            val safeFile = if (isValidRelativeFilePath(relFile)) relFile else ""
            val safeOutStart = if (outStart.isFinite()) max(0.0, outStart) else 0.0
            val safeIn = if (sIn.isFinite()) max(0.0, sIn) else 0.0
            val safeOut = if (sOut.isFinite()) max(safeIn, sOut) else safeIn
            val safeVol = if (vol.isFinite()) vol.coerceIn(0.0, 2.0) else 1.0
            return TrailerVoiceoverTake(
                stableId = id,
                relativeFile = safeFile,
                outputStart = safeOutStart,
                sourceIn = safeIn,
                sourceOut = safeOut,
                volume = safeVol,
            )
        }
    }
}

// =============================================================================
// Color Adjustment
// =============================================================================

/**
 * Color adjustments for brightness, contrast, and saturation.
 */
data class TrailerColorAdjustment(
    val brightness: Double = 0.0, // neutral 0.0, clamp [-1.0, 1.0]
    val contrast: Double = 1.0,   // neutral 1.0, clamp [0.25, 4.0]
    val saturation: Double = 1.0, // neutral 1.0, clamp [0.0, 3.0]
) {
    val isNeutral: Boolean
        get() = abs(brightness) < 0.0001 && abs(contrast - 1.0) < 0.0001 && abs(saturation - 1.0) < 0.0001

    fun toJson(): JSONObject = JSONObject().apply {
        put("brightness", brightness)
        put("contrast", contrast)
        put("saturation", saturation)
    }

    companion object {
        val NEUTRAL = TrailerColorAdjustment()

        fun fromJson(obj: JSONObject?): TrailerColorAdjustment? {
            if (obj == null) return null
            val b = obj.optDouble("brightness", 0.0)
            val c = obj.optDouble("contrast", 1.0)
            val s = obj.optDouble("saturation", 1.0)
            return TrailerColorAdjustment(
                brightness = if (b.isFinite()) b.coerceIn(-1.0, 1.0) else 0.0,
                contrast = if (c.isFinite()) c.coerceIn(0.25, 4.0) else 1.0,
                saturation = if (s.isFinite()) s.coerceIn(0.0, 3.0) else 1.0,
            )
        }
    }
}

// =============================================================================
// Framing & Opacity Keyframes
// =============================================================================

/**
 * Keyframe for source-time spatial framing (offset, scale) and opacity.
 */
data class TrailerTransformKeyframe(
    val stableId: UUID = UUID.randomUUID(),
    val sourceTime: Double = 0.0,
    val offsetX: Double = 0.0, // normalized [-1.0, 1.0]
    val offsetY: Double = 0.0, // normalized [-1.0, 1.0]
    val scale: Double = 1.0,   // positive default 1.0, bounded [0.1, 10.0]
    val opacity: Double = 1.0, // [0.0, 1.0]
) {
    val id: UUID get() = stableId

    data class InterpolatedTransform(
        val offsetX: Double,
        val offsetY: Double,
        val scale: Double,
        val opacity: Double,
    ) {
        companion object {
            val IDENTITY = InterpolatedTransform(0.0, 0.0, 1.0, 1.0)
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("stableId", stableId.toString())
        put("sourceTime", sourceTime)
        put("offsetX", offsetX)
        put("offsetY", offsetY)
        put("scale", scale)
        put("opacity", opacity)
    }

    companion object {
        fun fromJson(obj: JSONObject?): TrailerTransformKeyframe? {
            if (obj == null) return null
            val idStr = obj.optString("stableId").ifBlank { obj.optString("id") }
            val id = runCatching { UUID.fromString(idStr) }.getOrNull() ?: UUID.randomUUID()
            val st = obj.optDouble("sourceTime", 0.0)
            val ox = obj.optDouble("offsetX", 0.0)
            val oy = obj.optDouble("offsetY", 0.0)
            val sc = obj.optDouble("scale", 1.0)
            val op = obj.optDouble("opacity", 1.0)
            return TrailerTransformKeyframe(
                stableId = id,
                sourceTime = if (st.isFinite()) max(0.0, st) else 0.0,
                offsetX = if (ox.isFinite()) ox.coerceIn(-1.0, 1.0) else 0.0,
                offsetY = if (oy.isFinite()) oy.coerceIn(-1.0, 1.0) else 0.0,
                scale = if (sc.isFinite()) sc.coerceIn(0.1, 10.0) else 1.0,
                opacity = if (op.isFinite()) op.coerceIn(0.0, 1.0) else 1.0,
            )
        }

        fun sanitizeAndSort(keyframes: List<TrailerTransformKeyframe>): List<TrailerTransformKeyframe> {
            val sorted = keyframes.sortedWith(
                compareBy<TrailerTransformKeyframe> { it.sourceTime }
                    .thenBy { it.stableId.toString() }
            )
            val result = mutableListOf<TrailerTransformKeyframe>()
            for (kf in sorted) {
                if (result.isNotEmpty() && result.last().sourceTime == kf.sourceTime) {
                    continue
                }
                result.add(kf)
            }
            return result
        }

        fun interpolate(keyframes: List<TrailerTransformKeyframe>, at: Double): InterpolatedTransform {
            if (keyframes.isEmpty()) return InterpolatedTransform.IDENTITY
            val sorted = sanitizeAndSort(keyframes)
            if (sorted.isEmpty()) return InterpolatedTransform.IDENTITY
            if (sorted.size == 1 || at <= sorted.first().sourceTime) {
                val first = sorted.first()
                return InterpolatedTransform(first.offsetX, first.offsetY, first.scale, first.opacity)
            }
            if (at >= sorted.last().sourceTime) {
                val last = sorted.last()
                return InterpolatedTransform(last.offsetX, last.offsetY, last.scale, last.opacity)
            }
            for (i in 0 until sorted.size - 1) {
                val k0 = sorted[i]
                val k1 = sorted[i + 1]
                if (at >= k0.sourceTime && at <= k1.sourceTime) {
                    val span = k1.sourceTime - k0.sourceTime
                    if (span <= 0.00001) {
                        return InterpolatedTransform(k0.offsetX, k0.offsetY, k0.scale, k0.opacity)
                    }
                    val t = (at - k0.sourceTime) / span
                    val ox = k0.offsetX + t * (k1.offsetX - k0.offsetX)
                    val oy = k0.offsetY + t * (k1.offsetY - k0.offsetY)
                    val sc = k0.scale + t * (k1.scale - k0.scale)
                    val op = k0.opacity + t * (k1.opacity - k0.opacity)
                    return InterpolatedTransform(ox, oy, sc, op)
                }
            }
            return InterpolatedTransform.IDENTITY
        }
    }
}

// =============================================================================
// Transitions
// =============================================================================

/**
 * Transition configuration for clip boundaries.
 */
data class TrailerTransition(
    val dissolveDuration: Double = 0.0, // default 0.0 (cut)
) {
    val duration: Double get() = dissolveDuration

    fun toJson(): JSONObject = JSONObject().apply {
        put("dissolveDuration", dissolveDuration)
    }

    companion object {
        val CUT = TrailerTransition(0.0)

        fun fromJson(obj: JSONObject?): TrailerTransition? {
            if (obj == null) return null
            val d = obj.optDouble("dissolveDuration", 0.0)
            return TrailerTransition(if (d.isFinite()) max(0.0, d) else 0.0)
        }
    }
}

// =============================================================================
// Clip Adjustments Bundle
// =============================================================================

/**
 * Stored adjustments for an individual clip.
 */
data class TrailerClipAdjustments(
    val rate: Double = 1.0,
    val color: TrailerColorAdjustment? = null,
    val keyframes: List<TrailerTransformKeyframe> = emptyList(),
    val transition: TrailerTransition? = null,
    val cues: List<TrailerTimedCaptionCue> = emptyList(),
    val suppressions: List<TrailerCueSuppression> = emptyList(),
) {
    val manualCueSourceRanges: List<ClosedFloatingPointRange<Double>>
        get() = cues.mapNotNull { cue ->
            if (cue.origin == CueOrigin.MANUAL || cue.manuallyEdited) {
                if (cue.sourceEnd > cue.sourceStart) cue.sourceStart..cue.sourceEnd else null
            } else null
        }

    val requestedKeyframeSourceTimes: List<Double>
        get() = keyframes.map { it.sourceTime }.sorted()

    val keyframeSourceEnvelope: ClosedFloatingPointRange<Double>?
        get() {
            if (keyframes.isEmpty()) return null
            val minT = keyframes.minOf { it.sourceTime }
            val maxT = keyframes.maxOf { it.sourceTime }
            return minT..maxT
        }

    /**
     * Source interval anchor spanning all manually placed cues and keyframes overlapping [windowStart..windowEnd].
     * Returns null if no manual cues or keyframes fall in this window.
     */
    fun anchorSpan(windowStart: Double, windowEnd: Double): Pair<Double, Double>? {
        var lo: Double? = null
        var hi: Double? = null

        for (cue in cues) {
            if (cue.origin == CueOrigin.MANUAL || cue.manuallyEdited) {
                val s = cue.sourceStart
                val e = cue.sourceEnd
                if (e > s && max(s, windowStart) < min(e, windowEnd)) {
                    lo = if (lo == null) s else min(lo, s)
                    hi = if (hi == null) e else max(hi, e)
                }
            }
        }

        for (kf in keyframes) {
            val t = kf.sourceTime
            if (t in (windowStart - 1e-6)..(windowEnd + 1e-6)) {
                lo = if (lo == null) t else min(lo, t)
                hi = if (hi == null) t else max(hi, t)
            }
        }

        return if (lo != null && hi != null) lo to hi else null
    }

    fun toJson(): JSONObject = JSONObject().apply {
        if (rate != 1.0) put("rate", rate)
        color?.let { if (!it.isNeutral) put("color", it.toJson()) }
        if (keyframes.isNotEmpty()) {
            put("keyframes", JSONArray().apply { keyframes.forEach { put(it.toJson()) } })
        }
        transition?.let { if (it.dissolveDuration > 0.0) put("transition", it.toJson()) }
        if (cues.isNotEmpty()) {
            put("cues", JSONArray().apply { cues.forEach { put(it.toJson()) } })
        }
        if (suppressions.isNotEmpty()) {
            put("suppressions", JSONArray().apply { suppressions.forEach { put(it.toJson()) } })
        }
    }

    companion object {
        fun fromJson(obj: JSONObject?): TrailerClipAdjustments? {
            if (obj == null) return null
            val r = obj.optDouble("rate", 1.0)
            val safeRate = if (r.isFinite() && r in 0.25..4.0) r else 1.0
            val color = obj.optJSONObject("color")?.let { TrailerColorAdjustment.fromJson(it) }
            val keyframes = obj.optJSONArray("keyframes")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { TrailerTransformKeyframe.fromJson(it) }
                }
            }.orEmpty()
            val transition = obj.optJSONObject("transition")?.let { TrailerTransition.fromJson(it) }
            val cues = obj.optJSONArray("cues")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { TrailerTimedCaptionCue.fromJson(it) }
                }
            }.orEmpty()
            val suppressions = obj.optJSONArray("suppressions")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { TrailerCueSuppression.fromJson(it) }
                }
            }.orEmpty()
            return TrailerClipAdjustments(
                rate = safeRate,
                color = color,
                keyframes = keyframes,
                transition = transition,
                cues = cues,
                suppressions = suppressions,
            )
        }
    }
}

// =============================================================================
// Timeline Clip Mapping & Factory
// =============================================================================

data class TrailerClipInput(
    val sceneKey: Long,
    val sourceId: Int,
    val sourceStart: Double,
    val sourceLength: Double,
    val rate: Double = 1.0,
    val requestedOutgoingOverlap: Double = 0.0,
)

data class TrailerClipTimeMapping(
    val sceneKey: Long,
    val sourceId: Int,
    val sourceStart: Double,
    val sourceLength: Double,
    val rate: Double,
    val outputStart: Double,
    val outputDuration: Double,
    val incomingOverlap: Double = 0.0,
    val outgoingOverlap: Double = 0.0,
) {
    fun outputTime(forSourceTime: Double): Double =
        outputStart + (forSourceTime - sourceStart) / rate

    fun sourceTime(forOutputTime: Double): Double =
        sourceStart + (forOutputTime - outputStart) * rate

    fun mapAndClipCue(cue: TrailerTimedCaptionCue): Pair<Double, Double>? {
        if (cue.sourceId != this.sourceId) return null
        val effStart = max(cue.sourceStart, this.sourceStart)
        val effEnd = min(cue.sourceEnd, this.sourceStart + this.sourceLength)
        if (effEnd <= effStart) return null
        return outputTime(effStart) to outputTime(effEnd)
    }
}

sealed class TrailerTimeMappingException(message: String) : Exception(message) {
    class EmptyClips : TrailerTimeMappingException("Cannot build timeline mappings from an empty clips list.")
    class InvalidSourceRange(sceneKey: Long, start: Double, length: Double) :
        TrailerTimeMappingException("Invalid source range for sceneKey $sceneKey: start=$start, length=$length. Length must be positive.")
    class NonFiniteInput(sceneKey: Long, desc: String) :
        TrailerTimeMappingException("Non-finite input parameter in sceneKey $sceneKey: $desc.")
}

object TrailerClipTimeMappingFactory {
    /**
     * Builds concrete derived timeline mappings for sequential clips.
     * Clamps requested overlaps to prevent triple-overlap (incoming + outgoing <= duration)
     * and adjacent duration limits (0 <= o_i < min(d_i, d_(i+1))).
     * Uses O_0 = 0, O_(i+1) = O_i + d_i - o_i.
     */
    fun buildTimelineMappings(clips: List<TrailerClipInput>): List<TrailerClipTimeMapping> {
        if (clips.isEmpty()) {
            throw TrailerTimeMappingException.EmptyClips()
        }

        class ValidatedClip(
            val input: TrailerClipInput,
            val safeRate: Double,
            val outputDuration: Double,
        )

        val validated = ArrayList<ValidatedClip>(clips.size)

        for (clip in clips) {
            if (!clip.sourceStart.isFinite()) throw TrailerTimeMappingException.NonFiniteInput(clip.sceneKey, "sourceStart is non-finite")
            if (!clip.sourceLength.isFinite()) throw TrailerTimeMappingException.NonFiniteInput(clip.sceneKey, "sourceLength is non-finite")
            if (!clip.rate.isFinite()) throw TrailerTimeMappingException.NonFiniteInput(clip.sceneKey, "rate is non-finite")
            if (!clip.requestedOutgoingOverlap.isFinite()) throw TrailerTimeMappingException.NonFiniteInput(clip.sceneKey, "requestedOutgoingOverlap is non-finite")
            if (clip.sourceStart < 0.0 || clip.sourceLength <= 0.0001) {
                throw TrailerTimeMappingException.InvalidSourceRange(clip.sceneKey, clip.sourceStart, clip.sourceLength)
            }
            val safeRate = if (clip.rate in 0.25..4.0) clip.rate else 1.0
            val outputDuration = clip.sourceLength / safeRate
            if (!outputDuration.isFinite() || outputDuration <= 0.0) {
                throw TrailerTimeMappingException.NonFiniteInput(clip.sceneKey, "outputDuration is non-finite or overflowed")
            }
            validated.add(ValidatedClip(clip, safeRate, outputDuration))
        }

        val overlapCount = max(0, validated.size - 1)
        val effectiveOverlaps = DoubleArray(overlapCount)

        for (i in 0 until overlapCount) {
            val dCurr = validated[i].outputDuration
            val dNext = validated[i + 1].outputDuration
            val incomingCurr = if (i > 0) effectiveOverlaps[i - 1] else 0.0

            val rawOverlap = validated[i].input.requestedOutgoingOverlap
            val safeRawOverlap = if (rawOverlap > 0.0) rawOverlap else 0.0

            val maxAllowedByCurrent = max(0.0, dCurr - incomingCurr)
            val maxAdjacentLimit = max(0.0, min(dCurr * 0.5, dNext * 0.5))
            val clamped = minOf(safeRawOverlap, maxAllowedByCurrent, maxAdjacentLimit)
            if (!clamped.isFinite()) {
                throw TrailerTimeMappingException.NonFiniteInput(validated[i].input.sceneKey, "clamped overlap is non-finite")
            }
            effectiveOverlaps[i] = clamped
        }

        val mappings = ArrayList<TrailerClipTimeMapping>(validated.size)
        var currentOutputStart = 0.0

        for (i in 0 until validated.size) {
            val v = validated[i]
            val incoming = if (i > 0) effectiveOverlaps[i - 1] else 0.0
            val outgoing = if (i < overlapCount) effectiveOverlaps[i] else 0.0

            if (!currentOutputStart.isFinite()) {
                throw TrailerTimeMappingException.NonFiniteInput(v.input.sceneKey, "outputStart is non-finite")
            }

            mappings.add(
                TrailerClipTimeMapping(
                    sceneKey = v.input.sceneKey,
                    sourceId = v.input.sourceId,
                    sourceStart = v.input.sourceStart,
                    sourceLength = v.input.sourceLength,
                    rate = v.safeRate,
                    outputStart = currentOutputStart,
                    outputDuration = v.outputDuration,
                    incomingOverlap = incoming,
                    outgoingOverlap = outgoing,
                )
            )

            val advance = v.outputDuration - outgoing
            if (!advance.isFinite() || !(currentOutputStart + advance).isFinite()) {
                throw TrailerTimeMappingException.NonFiniteInput(v.input.sceneKey, "outputStart arithmetic overflow")
            }
            currentOutputStart += advance
        }

        return mappings
    }
}

// =============================================================================
// Scene Editor Helpers
// =============================================================================

/**
 * Compares two scene states and returns the mask of fields that actually changed.
 */
fun changedManualFields(before: EditorScene, after: EditorScene): ManualFields {
    var mask = 0
    if (before.start != after.start || before.length != after.length ||
        before.actionStart != after.actionStart || before.actionEnd != after.actionEnd
    ) {
        mask = mask or ManualField.TIMING.mask
    }
    if (before.sourceVolume != after.sourceVolume) {
        mask = mask or ManualField.AUDIO.mask
    }
    if (before.fit != after.fit || before.focusX != after.focusX || before.focusY != after.focusY) {
        mask = mask or ManualField.FRAMING.mask
    }
    if (before.caption != after.caption) {
        mask = mask or ManualField.CAPTION.mask
    }
    val oldAdjust = before.adjustments ?: TrailerClipAdjustments()
    val newAdjust = after.adjustments ?: TrailerClipAdjustments()
    if (oldAdjust.rate != newAdjust.rate || oldAdjust.transition != newAdjust.transition) {
        mask = mask or ManualField.ADJUST.mask
    }
    if (oldAdjust.color != newAdjust.color || oldAdjust.keyframes != newAdjust.keyframes) {
        mask = mask or ManualField.FRAMING.mask
    }
    if (oldAdjust.cues != newAdjust.cues || oldAdjust.suppressions != newAdjust.suppressions) {
        mask = mask or ManualField.CAPTION.mask
    }
    if (before.excluded != after.excluded) {
        mask = mask or ManualField.EXCLUSION.mask
    }
    return ManualFields(mask)
}

/**
 * Toggles automation lock for scenes in [keys].
 * When unlocking, clears whole-manual protection while preserving authored content (caption, volume, framing, adjustments).
 */
fun setAutomationProtected(
    isProtected: Boolean,
    keys: Set<Long>,
    scenes: List<EditorScene>,
): List<EditorScene> {
    return scenes.map { scene ->
        if (scene.key !in keys) scene
        else if (isProtected) {
            scene.copy(automationProtected = true)
        } else {
            var contentMask = scene.manual.rawMask and ManualFields.CONTENT_MASK
            if (scene.automationProtected) {
                if (scene.caption != null) contentMask = contentMask or ManualField.CAPTION.mask
                if (scene.sourceVolume != 1f) contentMask = contentMask or ManualField.AUDIO.mask
                if (scene.hasCustomFraming) contentMask = contentMask or ManualField.FRAMING.mask
                val adj = scene.adjustments
                if (adj != null) {
                    if (adj.rate != 1.0 || adj.transition != null) contentMask = contentMask or ManualField.ADJUST.mask
                    if (adj.color != null || adj.keyframes.isNotEmpty()) contentMask = contentMask or ManualField.FRAMING.mask
                    if (adj.cues.isNotEmpty() || adj.suppressions.isNotEmpty()) contentMask = contentMask or ManualField.CAPTION.mask
                }
            }
            scene.copy(
                automationProtected = false,
                manual = ManualFields(contentMask),
            )
        }
    }
}

/**
 * Merges newly generated caption cues into the scene identified by [sceneKey].
 * Preserves user-edited cues and deletion tombstones without promoting generated cues to manual protection.
 */
fun mergeGeneratedCues(
    generated: List<TrailerTimedCaptionCue>,
    sceneKey: Long,
    scenes: List<EditorScene>,
): List<EditorScene> {
    return scenes.map { scene ->
        if (scene.key != sceneKey) scene
        else {
            val currentAdj = scene.adjustments ?: TrailerClipAdjustments()
            val mergedCues = TrailerTimedCaptionCue.merge(
                existingCues = currentAdj.cues,
                newGeneratedCues = generated,
                suppressions = currentAdj.suppressions,
            )
            val updatedAdj = currentAdj.copy(cues = mergedCues)
            scene.copy(adjustments = if (updatedAdj == TrailerClipAdjustments()) null else updatedAdj)
        }
    }
}

/**
 * True if any scene in [scenes] has user manual edits or whole-manual protection.
 */
fun keepsManualScenesAsIs(scenes: List<EditorScene>?): Boolean {
    return scenes?.any { !it.isPureAutomatic } ?: false
}
