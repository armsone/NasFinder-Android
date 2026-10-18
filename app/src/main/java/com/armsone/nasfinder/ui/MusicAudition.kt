package com.armsone.nasfinder.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Controller managing single-audio audition playback across music picker rows.
 * Stops previous audio when switching tracks, prepares asynchronously off-main,
 * guards stale sessions, and cleanly releases native MediaPlayer on disposal or stop.
 */
class MusicAuditionController {
    private var mediaPlayer: MediaPlayer? = null
    private var sessionToken: Long = 0L

    var playingPath by mutableStateOf<String?>(null)
        private set
    var isPreparing by mutableStateOf(false)
        private set
    var playbackError by mutableStateOf<String?>(null)
        private set

    fun toggleAudition(track: TrailerMusicTrack) {
        val targetPath = track.file.absolutePath
        if (playingPath == targetPath && (mediaPlayer != null || isPreparing)) {
            stop()
            return
        }

        stopInternal()
        val currentToken = ++sessionToken
        playingPath = targetPath
        isPreparing = true
        playbackError = null

        val player = MediaPlayer()
        mediaPlayer = player
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            player.setDataSource(targetPath)
            player.setOnPreparedListener { mp ->
                if (sessionToken == currentToken && mediaPlayer === mp) {
                    isPreparing = false
                    try {
                        mp.start()
                    } catch (e: Exception) {
                        playbackError = "재생 시작 실패: ${e.message ?: "알 수 없는 오류"}"
                        stopInternal()
                    }
                } else {
                    runCatching { mp.release() }
                }
            }
            player.setOnCompletionListener { mp ->
                if (sessionToken == currentToken && mediaPlayer === mp) {
                    stopInternal()
                }
            }
            player.setOnErrorListener { mp, what, extra ->
                if (sessionToken == currentToken && mediaPlayer === mp) {
                    playbackError = "재생 오류 ($what, $extra)"
                    stopInternal()
                }
                true
            }
            player.prepareAsync()
        } catch (e: Exception) {
            playbackError = "파일 열기 실패: ${e.message ?: "알 수 없는 오류"}"
            stopInternal()
        }
    }

    private fun stopInternal() {
        sessionToken++
        isPreparing = false
        playingPath = null
        mediaPlayer?.let { player ->
            mediaPlayer = null
            runCatching {
                if (player.isPlaying) player.stop()
                player.reset()
                player.release()
            }
        }
    }

    fun stop() {
        stopInternal()
        playbackError = null
    }

    fun release() {
        stopInternal()
        playbackError = null
    }
}

@Composable
fun rememberMusicAuditionController(): MusicAuditionController {
    val controller = remember { MusicAuditionController() }
    DisposableEffect(controller) {
        onDispose {
            controller.release()
        }
    }
    return controller
}

@Composable
fun MusicPickerRow(
    track: TrailerMusicTrack,
    isSelected: Boolean,
    auditionController: MusicAuditionController,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackPath = track.file.absolutePath
    val isAuditioning = auditionController.playingPath == trackPath
    val isPreparingThis = isAuditioning && auditionController.isPreparing
    val errorThis = if (isAuditioning) auditionController.playbackError else null

    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Distinct Play/Stop button for auditioning
            IconButton(
                onClick = { auditionController.toggleAudition(track) },
                modifier = Modifier.size(36.dp),
            ) {
                when {
                    isPreparingThis -> CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    isAuditioning -> Icon(
                        Icons.Default.Stop,
                        contentDescription = "미리듣기 정지",
                        tint = MaterialTheme.colorScheme.error,
                    )
                    else -> Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "미리듣기 재생",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.width(6.dp))

            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    track.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        trailerLengthTitle(track.durationSeconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (errorThis != null) {
                        Text(
                            errorThis,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else if (isAuditioning && !isPreparingThis) {
                        Text(
                            "재생 중",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
