package com.armsone.nasfinder.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player
import androidx.media3.transformer.CompositionPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * The single preview engine: ONE Media3 [CompositionPlayer] plays the SAME prepared composition the exporter renders
 * (dissolves, voice takes, music, rate, cards). It is driven by the workspace's [ClipPlaybackState] (the one shared
 * state/clock): the player position is mapped back to scene/card + elapsed time through the canonical time mapping.
 */
@Stable
internal class ProjectPreviewHost {
    var player by mutableStateOf<CompositionPlayer?>(null)
    var preparing by mutableStateOf(false)
    var status by mutableStateOf("미리보기 준비 중…")
    var progress by mutableStateOf<Float?>(null)
    var error by mutableStateOf<String?>(null)
    internal var built: TrailerAdvancedComposer.BuiltTimelineComposition? = null
    internal var builtKey by mutableStateOf<List<Any?>?>(null)
    internal var token = 0
    // Healthy originals play directly; only the scene keys implicated by a real decode error take the intermediate (recovery) route.
    // The set is immutable (replaced, never mutated) and lives for the project: revisions keep it, a project switch or a
    // changed source/window of that clip drops it.
    internal var recoveryKeys by mutableStateOf<Set<Long>>(emptySet())
    internal var recoverySigs: Map<Long, List<Any?>> = emptyMap()
    internal var recoveryProject: String? = null
    // The recovery set the current [built] composition was prepared with.
    internal var builtRecoveryKeys: Set<Long> = emptySet()

    /** Releases the player and the composition's own temporary files (idempotent). */
    fun releaseAll() {
        token++
        player?.let { runCatching { it.release() } }
        player = null
        built?.let { b -> runCatching { b.releaseOwnedTempFiles() } }
        built = null
        builtKey = null
        builtRecoveryKeys = emptySet()
        preparing = false
        progress = null
    }
}

private const val CARD_INTRO_KEY = -999L
private const val CARD_OUTRO_KEY = -998L

internal fun previewKeyOf(session: TrailerSession): List<Any?> = listOf(
    session.directory.absolutePath, session.revision, session.musicTrack?.file?.absolutePath, session.musicPolicy,
    session.musicFitCount, session.autoDucking, session.editor?.musicVolume,
)

/** Edits the raw source frame cannot show (rate, color, keyframes, dissolve, timed cues, voice takes) need the composed frame. */
private fun TrailerSession.needsComposedPreview(): Boolean =
    editor?.let { p -> p.voiceovers.isNotEmpty() || p.scenes.any { !it.excluded && it.adjustments != null } } == true

private const val PAUSED_PREPARE_DEBOUNCE_MS = 600L

// Preview frames are bounded to this long side (same aspect); export always renders full resolution.
private const val PREVIEW_MAX_LONG_SIDE = 960

/** Output-timeline seconds of the shared playback position, or null when it has no mapping. */
private fun globalSecondsOf(maps: List<TrailerClipTimeMapping>, clip: ClipPlaybackState): Double? {
    // A gap / voiceover tail position is already output time (the one clock keeps it past the last visual).
    clip.unmappedOutputMs?.let { return it / 1000.0 }
    val slot = clip.card
    if (slot != null) {
        val m = maps.firstOrNull { it.sceneKey == if (slot == TitleSlot.INTRO) CARD_INTRO_KEY else CARD_OUTRO_KEY } ?: return null
        return m.outputStart + clip.cardPositionMs / 1000.0
    }
    val m = maps.firstOrNull { it.sceneKey == clip.sceneKey } ?: return null
    val local = (clip.positionMs / 1000.0 / m.rate).coerceIn(0.0, m.outputDuration)
    return m.outputStart + local
}

/** Stable scene key of each scene (scene.sceneKey, else its position as buildClipInputs assigns) with the source+window identity. */
private fun recoverySigsOf(scenes: List<TrailerScene>): Map<Long, List<Any?>> =
    scenes.withIndex().associate { (i, sc) ->
        (sc.sceneKey ?: (i + 1).toLong()) to listOf(sc.source.id, sc.source.file.absolutePath, sc.start, sc.length)
    }

/** Scene keys actually on screen at output time [t] (both during a dissolve); title cards (negative keys) are excluded. */
private fun activeSceneKeysAt(maps: List<TrailerClipTimeMapping>, t: Double): Set<Long> =
    maps.filter { it.sceneKey >= 0L && it.outputStart <= t + 1e-6 && t < it.outputStart + it.outputDuration - 1e-6 }
        .map { it.sceneKey }.toSet()

private fun mappingAt(maps: List<TrailerClipTimeMapping>, t: Double): TrailerClipTimeMapping? =
    maps.lastOrNull { it.outputStart <= t + 1e-6 && t < it.outputStart + it.outputDuration - 1e-6 }

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun ProjectPreviewEngine(
    host: ProjectPreviewHost,
    session: TrailerSession,
    clipPlayback: ClipPlaybackState,
    playbackAllowed: Boolean,
    selectedKey: () -> Long?,
    onSelect: (Long) -> Unit,
    heldKeyFor: (TitleSlot) -> Long?,
    prepState: TrailerPreviewPreparationState?,
) {
    val context = LocalContext.current
    val key = previewKeyOf(session)
    val currentSession by rememberUpdatedState(session)
    val currentSelected by rememberUpdatedState(selectedKey)
    val currentSelect by rememberUpdatedState(onSelect)
    val currentHeld by rememberUpdatedState(heldKeyFor)
    DisposableEffect(host) { onDispose { host.releaseAll() } }
    // The latest edit wins: a changed project drops the old composition/player; the next play prepares the new one.
    LaunchedEffect(key) {
        if (host.builtKey != null && host.builtKey != key) host.releaseAll()
        host.error = null
        val project = currentSession.directory.absolutePath
        if (host.recoveryProject != project) {
            host.recoveryProject = project
            host.recoveryKeys = emptySet()
            host.recoverySigs = emptyMap()
        } else if (host.recoveryKeys.isNotEmpty()) {
            // A recovery key stays blocked only while its exact source+window is unchanged.
            val now = recoverySigsOf(currentSession.scenes)
            val kept = host.recoveryKeys.filter { host.recoverySigs[it] != null && host.recoverySigs[it] == now[it] }.toSet()
            if (kept != host.recoveryKeys) {
                host.recoveryKeys = kept
                host.recoverySigs = host.recoverySigs.filterKeys { it in kept }
            }
        }
    }
    // While paused, a timeline/selection move shows that frame of the composed preview too.
    LaunchedEffect(clipPlayback.epoch, clipPlayback.sceneKey, clipPlayback.card, host.player, host.builtKey) {
        val player = host.player ?: return@LaunchedEffect
        val built = host.built ?: return@LaunchedEffect
        if (clipPlayback.playing || host.builtKey != key) return@LaunchedEffect
        val at = globalSecondsOf(built.mappings, clipPlayback) ?: return@LaunchedEffect
        player.seekTo((at * 1000).toLong())
    }
    // Pause (e.g. fullscreen/export screen) keeps the player; stopping the state stops the player.
    val wantsPlay = clipPlayback.playing && playbackAllowed
    LaunchedEffect(wantsPlay, key, host.recoveryKeys) {
        if (!wantsPlay) {
            host.player?.pause()
            // A paused edit shows the current frame of the SAME composition (debounced so a burst of edits prepares once).
            if (!playbackAllowed || !currentSession.needsComposedPreview() || (host.builtKey == key && host.player != null && host.builtRecoveryKeys == host.recoveryKeys)) return@LaunchedEffect
            delay(PAUSED_PREPARE_DEBOUNCE_MS)
        }
        try {
            if (host.built == null || host.builtKey != key || host.builtRecoveryKeys != host.recoveryKeys) {
                host.releaseAll()
                val myToken = ++host.token
                host.preparing = true
                host.error = null
                host.status = "미리보기 준비 중…"
                val s = currentSession
                // Snapshot of the implicated keys this build uses; a later change restarts the effect and cancels this prepare.
                val recovery = host.recoveryKeys
                val built = TrailerExporter.prepareCompositionForPreview(
                    context = context,
                    scenes = s.scenes,
                    format = s.format,
                    musicTrack = s.musicTrack,
                    musicPolicy = s.musicPolicy,
                    musicFitCount = s.musicFitCount,
                    autoDucking = s.autoDucking,
                    aspectSources = s.scenes.map { it.source },
                    musicVolume = s.editor?.musicVolume ?: 1f,
                    introCard = s.editor?.introCard?.takeIf { it.enabled },
                    outroCard = s.editor?.outroCard?.takeIf { it.enabled },
                    cardBackgroundSources = (s.scenes.map { it.source } + s.sources).distinctBy { it.id },
                    voiceovers = s.editor?.voiceovers.orEmpty(),
                    projectDirectory = s.directory,
                    directVideoInputs = true,
                    recoverySceneKeys = recovery,
                    previewMaxLongSide = PREVIEW_MAX_LONG_SIDE,
                    useClipCache = true,
                ) { status, p ->
                    if (host.token == myToken) { host.status = status; host.progress = p }
                }
                if (!coroutineContext.isActive || host.token != myToken) {
                    built.releaseOwnedTempFiles()
                    return@LaunchedEffect
                }
                host.built = built
                host.builtRecoveryKeys = recovery
                host.builtKey = key
                host.preparing = false
            }
            val built = host.built ?: return@LaunchedEffect
            val maps = built.mappings
            if (host.player == null) {
                host.player = CompositionPlayer.Builder(context)
                    .setVideoGraphFactory(androidx.media3.effect.MultipleInputVideoGraph.Factory())
                    .build().apply {
                    addListener(object : Player.Listener {
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            // Only the clip(s) on screen at the failing output time go through the recovery route.
                            val atSec = (host.player?.currentPosition ?: 0L).coerceAtLeast(0L) / 1000.0
                            val active = host.built?.let { activeSceneKeysAt(it.mappings, atSec) }.orEmpty()
                            val implicated = active - host.recoveryKeys
                            if (implicated.isNotEmpty()) {
                                val sigs = recoverySigsOf(currentSession.scenes)
                                host.recoverySigs = host.recoverySigs + implicated.mapNotNull { k -> sigs[k]?.let { k to it } }
                                host.recoveryKeys = host.recoveryKeys + implicated
                                return
                            }
                            host.error = "미리보기를 재생하지 못했습니다 (${error.errorCodeName})"
                            clipPlayback.playing = false
                            clipPlayback.continuous = false
                        }
                    })
                    setComposition(built.composition)
                    prepare()
                }
            }
            val player = host.player ?: return@LaunchedEffect
            val startSec = globalSecondsOf(maps, clipPlayback) ?: 0.0
            player.seekTo((startSec * 1000).toLong())
            if (!wantsPlay) {
                player.playWhenReady = false
                return@LaunchedEffect
            }
            player.playWhenReady = true
            clipPlayback.seekRequestMs = null
            var lastSeekEpoch = clipPlayback.epoch
            val total = built.effectiveTotalDurationSeconds
            while (isActive && clipPlayback.playing) {
                delay(33)
                if (host.player !== player) return@LaunchedEffect
                // A user/timeline seek re-anchors the one player.
                if (clipPlayback.epoch != lastSeekEpoch) {
                    lastSeekEpoch = clipPlayback.epoch
                    val target = globalSecondsOf(maps, clipPlayback) ?: continue
                    player.seekTo((target * 1000).toLong())
                    clipPlayback.seekRequestMs = null
                    continue
                }
                // Another clip picked on the board ends the play there (as before).
                if (currentSelected() != clipPlayback.sceneKey) {
                    clipPlayback.playing = false
                    clipPlayback.continuous = false
                    clipPlayback.card = null
                    break
                }
                val t = player.currentPosition.coerceAtLeast(0L) / 1000.0
                if (player.playbackState == Player.STATE_ENDED || t >= total - 0.02) {
                    if (clipPlayback.continuous) {
                        clipPlayback.playing = false
                        clipPlayback.continuous = false
                    } else {
                        val loop = globalSecondsOf(maps, clipPlayback.also { it.positionMs = 0L }) ?: 0.0
                        player.seekTo((loop * 1000).toLong())
                        player.playWhenReady = true
                    }
                    continue
                }
                val m = mappingAt(maps, t)
                if (m == null) {
                    // No clip/card covers t (a gap, or the voiceover tail past the last visual): keep the real output position.
                    if (clipPlayback.continuous) clipPlayback.unmappedOutputMs = (t * 1000).toLong()
                    continue
                }
                clipPlayback.unmappedOutputMs = null
                if (m.sceneKey < 0L) {
                    val slot = if (m.sceneKey == CARD_INTRO_KEY) TitleSlot.INTRO else TitleSlot.OUTRO
                    if (!clipPlayback.continuous) {
                        // A single-clip loop never wanders onto a card.
                        val loop = globalSecondsOf(maps, clipPlayback.also { it.positionMs = 0L; it.card = null }) ?: 0.0
                        player.seekTo((loop * 1000).toLong())
                        continue
                    }
                    val held = currentHeld(slot) ?: continue
                    if (selectedKey() != held) currentSelect(held)
                    clipPlayback.sceneKey = held
                    clipPlayback.card = slot
                    clipPlayback.cardPositionMs = ((t - m.outputStart) * 1000).toLong().coerceAtLeast(0L)
                    continue
                }
                if (m.sceneKey != clipPlayback.sceneKey || clipPlayback.card != null) {
                    if (!clipPlayback.continuous) {
                        // Single-clip play loops its own clip.
                        clipPlayback.card = null
                        val loop = globalSecondsOf(maps, clipPlayback.also { it.positionMs = 0L }) ?: 0.0
                        player.seekTo((loop * 1000).toLong())
                        continue
                    }
                    currentSelect(m.sceneKey)
                    clipPlayback.card = null
                    clipPlayback.sceneKey = m.sceneKey
                }
                val local = (t - m.outputStart).coerceIn(0.0, m.outputDuration)
                clipPlayback.positionMs = (local * m.rate * 1000).toLong()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Throwable) {
            android.util.Log.w("NFPreview", "preview prepare failed", e)
            host.preparing = false
            host.error = e.message?.takeIf { it.isNotBlank() } ?: "미리보기를 준비하지 못했습니다."
            clipPlayback.playing = false
            clipPlayback.continuous = false
        } finally {
            if (!clipPlayback.playing || !playbackAllowed) host.player?.pause()
        }
    }
}
