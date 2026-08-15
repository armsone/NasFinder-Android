package com.armsone.nasfinder

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.armsone.nasfinder.platform.ExternalEntryRoute
import com.armsone.nasfinder.platform.ExternalEntryRouteParser
import com.armsone.nasfinder.platform.NasFinderShortcuts
import com.armsone.nasfinder.ui.NasFinderApp
import com.armsone.nasfinder.ui.NasFinderViewModel
import com.armsone.nasfinder.ui.Screen
import com.armsone.nasfinder.ui.theme.NasFinderTheme
import com.armsone.nasfinder.update.AppUpdateDialog
import com.armsone.nasfinder.update.AppUpdateState
import com.armsone.nasfinder.update.GitHubAppUpdateService
import java.io.File

class MainActivity : ComponentActivity() {
    private val incomingIntent = mutableStateOf<Intent?>(null)
    private val appUpdateService by lazy { GitHubAppUpdateService(this, installedVersionCode()) }
    private var pendingUpdateInstallFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingIntent.value = intent
        NasFinderShortcuts.installDynamic(this)
        enableEdgeToEdge()
        setContent {
            val nasApplication = this@MainActivity.application as NasFinderApplication
            val model: NasFinderViewModel = viewModel {
                NasFinderViewModel(nasApplication)
            }
            val updateState by appUpdateService.state.collectAsStateWithLifecycle()
            var ignoredUpdateVersion by rememberSaveable { mutableStateOf<Int?>(null) }
            NasFinderTheme(nasApplication.settings.theme()) {
                NasFinderApp(model)
                val updateVersion = when (val value = updateState) {
                    is AppUpdateState.Available -> value.release.versionCode
                    is AppUpdateState.Downloading -> value.release.versionCode
                    is AppUpdateState.Ready -> value.release.versionCode
                    AppUpdateState.Checking, AppUpdateState.Idle -> null
                }
                if (updateVersion != null && ignoredUpdateVersion != updateVersion) {
                    AppUpdateDialog(
                        state = updateState,
                        onDownload = {
                            (updateState as? AppUpdateState.Available)?.let { appUpdateService.download(it.release) }
                        },
                        onInstall = {
                            (updateState as? AppUpdateState.Ready)?.let { requestUpdateInstall(it.apkFile) }
                        },
                        onLater = { ignoredUpdateVersion = updateVersion },
                    )
                }
                LaunchedEffect(incomingIntent.value) {
                    val entry = incomingIntent.value
                    when (ExternalEntryRouteParser.parse(entry?.action, entry?.dataString)) {
                        ExternalEntryRoute.Inbox -> model.show(Screen.Inbox)
                        ExternalEntryRoute.WebHard -> model.show(Screen.WebHard)
                        ExternalEntryRoute.WebBrowser -> model.show(Screen.WebBrowser)
                        ExternalEntryRoute.PassThrough, ExternalEntryRoute.Rejected -> model.handleEntryIntent(entry)
                    }
                }
            }
        }
        appUpdateService.checkForUpdate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingIntent.value = intent
    }

    override fun onResume() {
        super.onResume()
        val pending = pendingUpdateInstallFile ?: return
        if (canRequestPackageInstalls()) window.decorView.post { requestUpdateInstall(pending) }
    }

    private fun requestUpdateInstall(apkFile: File) {
        if (!apkFile.isFile) {
            showToast("업데이트 파일을 찾을 수 없습니다.")
            return
        }
        if (!canRequestPackageInstalls()) {
            pendingUpdateInstallFile = apkFile
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", packageName, null)))
            }.onFailure {
                pendingUpdateInstallFile = null
                showToast("업데이트 설치 권한 설정을 열 수 없습니다.")
            }
            return
        }
        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.sharefiles", apkFile)
        }.getOrElse {
            showToast("업데이트 파일을 열 수 없습니다.")
            return
        }
        pendingUpdateInstallFile = null
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
            .onFailure { showToast("Android 설치 화면을 열 수 없습니다.") }
    }

    private fun canRequestPackageInstalls(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    @Suppress("DEPRECATION")
    private fun installedVersionCode(): Int {
        val info = packageManager.getPackageInfo(packageName, 0)
        val version = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        return version.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun showToast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        appUpdateService.close()
        super.onDestroy()
    }

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
