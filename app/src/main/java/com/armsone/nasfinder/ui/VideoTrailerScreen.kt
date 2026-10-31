package com.armsone.nasfinder.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

/**
 * Hosts the current project: staging/analysis progress until the confirmed selection is prepared, then the adaptive
 * editing workspace. Closing always keeps the project (it is autosaved as a draft).
 */
@Composable
internal fun VideoTrailerScreen(session: TrailerSession, model: NasFinderViewModel, browsingForMusic: Boolean = false) {
    val currentSession by rememberUpdatedState(session)
    var confirmClose by remember { mutableStateOf(false) }
    var fullscreenPlayback by rememberSaveable(session.directory.absolutePath) { mutableStateOf(false) }

    fun requestClose() {
        when {
            fullscreenPlayback -> fullscreenPlayback = false
            currentSession.busy || currentSession.export?.running == true || currentSession.pendingAdds.any { it.phase != EditorAddPhase.FAILED } -> confirmClose = true
            else -> model.suspendTrailerDraft()
        }
    }

    if (browsingForMusic) return
    val context = LocalContext.current
    DisposableEffect(context, fullscreenPlayback) {
        val window = context.findHostActivity()?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        if (controller != null) {
            if (fullscreenPlayback) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.statusBars())
            } else {
                controller.show(WindowInsetsCompat.Type.statusBars())
            }
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.statusBars())
        }
    }
    // Editor-scoped: the keyboard arrives as IME insets (resize) instead of panning the whole window, so the header,
    // status bar area and preview stay put and only the inspector scrolls to the focused field. Restored on close.
    DisposableEffect(context) {
        val window = context.findHostActivity()?.window
        val previousMode = window?.attributes?.softInputMode
        if (window != null && previousMode != null) {
            @Suppress("DEPRECATION")
            val resize = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            window.setSoftInputMode((previousMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or resize)
        }
        onDispose {
            if (window != null && previousMode != null) window.setSoftInputMode(previousMode)
        }
    }

    Surface(Modifier.fillMaxSize().zIndex(80f), color = MaterialTheme.colorScheme.background) {
        if (session.prepared && session.editor != null) {
            AdaptiveEditorWorkspace(
                session = session,
                model = model,
                onRequestClose = ::requestClose,
                fullscreenPlayback = fullscreenPlayback,
                onFullscreenChange = { fullscreenPlayback = it },
            )
        } else {
            BackHandler { requestClose() }
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = ::requestClose, modifier = Modifier.size(IconHitTarget)) { PackIcon(PackGlyph.BACK, "뒤로", Modifier.size(ToolbarActionIcon)) }
                    Text(
                        if (session.isMusicOverlayMode) "뮤직비디오 준비" else "영상 편집 준비",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                TrailerStatusCard(session = session, onRetry = model::retryTrailer)
                if (session.sources.isNotEmpty()) {
                    Text("${session.sources.size}개 재료를 준비했습니다.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (confirmClose) AlertDialog(
        onDismissRequest = { confirmClose = false },
        title = { Text("진행 중인 작업을 멈추고 닫을까요?") },
        text = {
            Text(
                if (session.prepared) "내보내기나 재료 준비가 취소됩니다. 지금까지 편집한 프로젝트는 저장되어 나중에 이어서 편집할 수 있습니다."
                else "재료 준비가 취소됩니다. 아직 준비되지 않은 선택은 저장되지 않습니다."
            )
        },
        dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("계속") } },
        confirmButton = {
            TextButton(onClick = {
                confirmClose = false
                model.suspendTrailerDraft(wasCancelled = true)
            }) { Text("멈추고 닫기") }
        },
    )
}

@Composable
private fun TrailerStatusCard(session: TrailerSession, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .8f)),
    ) {
        Column(
            Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (session.busy) {
                val progress = session.progress
                if (progress != null) {
                    val percent = (progress * 100).toInt().coerceIn(0, 100)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text(if (session.status.contains("%")) session.status else "${session.status} ($percent%)", style = MaterialTheme.typography.bodyMedium)
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Text(session.status, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (session.error != null) {
                Text(session.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onRetry) { Text("다시 시도") }
            }
        }
    }
}
