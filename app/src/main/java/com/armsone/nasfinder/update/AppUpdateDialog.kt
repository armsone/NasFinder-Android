package com.armsone.nasfinder.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AppUpdateDialog(
    state: AppUpdateState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onLater: () -> Unit,
) {
    when (state) {
        AppUpdateState.Idle, AppUpdateState.Checking -> Unit
        is AppUpdateState.Available -> AlertDialog(
            onDismissRequest = onLater,
            shape = RoundedCornerShape(22.dp),
            title = { Text("새 버전이 있습니다") },
            text = { Text(state.message ?: "GitHub에서 NasFinder ${state.release.versionCode} 버전을 받습니다. 저장된 연결과 설정은 유지됩니다.") },
            confirmButton = { TextButton(onClick = onDownload) { Text("업데이트") } },
            dismissButton = { TextButton(onClick = onLater) { Text("나중에") } },
        )
        is AppUpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            shape = RoundedCornerShape(22.dp),
            title = { Text("업데이트 받는 중") },
            text = {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    CircularProgressIndicator()
                    Text("공식 Release와 APK 서명을 확인한 뒤 설치 화면을 준비합니다.")
                }
            },
            confirmButton = {},
        )
        is AppUpdateState.Ready -> AlertDialog(
            onDismissRequest = onLater,
            shape = RoundedCornerShape(22.dp),
            title = { Text("업데이트 준비 완료") },
            text = { Text("Android 설치 화면에서 설치를 눌러 주세요.") },
            confirmButton = { TextButton(onClick = onInstall) { Text("설치 화면 열기") } },
            dismissButton = { TextButton(onClick = onLater) { Text("나중에") } },
        )
    }
}
