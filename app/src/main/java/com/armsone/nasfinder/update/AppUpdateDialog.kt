package com.armsone.nasfinder.update

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AppUpdateDialog(state: AppUpdateState, onDownload: () -> Unit, onInstall: () -> Unit, onLater: () -> Unit, onCancel: () -> Unit, onRetry: () -> Unit) {
    when (state) {
        AppUpdateState.Idle -> Unit
        is AppUpdateState.Checking -> if (state.isManual) ProgressDialog("최신 버전 확인 중", null, null)
        is AppUpdateState.Latest -> AlertDialog(onDismissRequest = onLater, title = { Text("최신 버전입니다") }, text = { Text("현재 NasFinder가 최신 버전입니다.") }, confirmButton = { TextButton(onClick = onLater) { Text("확인") } })
        is AppUpdateState.Available -> AlertDialog(onDismissRequest = onLater, title = { Text("새 버전이 있습니다") }, text = { Text(state.message ?: "${state.release.tagName} · ${formatBytes(state.release.assetSizeBytes)}\n${state.release.releaseNotes.ifBlank { "새 버전을 다운로드할 수 있습니다." }}") }, confirmButton = { TextButton(onClick = onDownload) { Text("다운로드") } }, dismissButton = { TextButton(onClick = onLater) { Text("나중에") } })
        is AppUpdateState.Downloading -> ProgressDialog("업데이트 받는 중", state.progressPercent, onCancel)
        is AppUpdateState.Ready -> AlertDialog(onDismissRequest = onLater, title = { Text("설치 준비됨") }, text = { Text("서명과 SHA-256 확인을 마쳤습니다. Android 설치 화면에서 설치를 눌러 주세요.") }, confirmButton = { TextButton(onClick = onInstall) { Text("설치 화면 열기") } }, dismissButton = { TextButton(onClick = onLater) { Text("나중에") } })
        is AppUpdateState.Failed -> AlertDialog(onDismissRequest = onLater, title = { Text("업데이트 오류") }, text = { Text(state.message) }, confirmButton = { TextButton(onClick = onRetry) { Text("다시 시도") } }, dismissButton = { TextButton(onClick = onLater) { Text("닫기") } })
    }
}

@Composable private fun ProgressDialog(title: String, percent: Int?, onCancel: (() -> Unit)?) {
    AlertDialog(onDismissRequest = {}, title = { Text(title) }, text = { Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) { if (percent == null) CircularProgressIndicator() else CircularProgressIndicator(progress = { percent / 100f }); Text(percent?.let { "$it% 다운로드 중" } ?: "잠시만 기다려 주세요.") } }, confirmButton = { if (onCancel != null) TextButton(onClick = onCancel) { Text("취소") } })
}

private fun formatBytes(bytes: Long): String = if (bytes >= 1_048_576) "%.1f MB".format(bytes / 1_048_576.0) else "${bytes / 1024} KB"
