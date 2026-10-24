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
import java.util.concurrent.atomic.AtomicLong

class MainActivity : ComponentActivity() {
    private val incomingIntent = mutableStateOf<Intent?>(null)
    private var intentConsumed = false
    private val appUpdateService by lazy { GitHubAppUpdateService(this, installedVersionCode()) }
    private var pendingUpdateInstallFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 소비 플래그는 같은 프로세스의 구성 변경에서만 신뢰한다. 프로세스가 새로 만들어졌다면 미완료 intake를 다시 평가한다.
        intentConsumed = savedInstanceState != null &&
            savedInstanceState.getLong(KEY_PROCESS_SESSION, 0L) == processSession &&
            savedInstanceState.getBoolean(KEY_INTENT_CONSUMED, false)
        val currentIntent = intent
        if (currentIntent != null && !intentConsumed) {
            if (!currentIntent.hasExtra(EXTRA_DELIVERY_ID)) {
                currentIntent.putExtra(EXTRA_DELIVERY_ID, nextDeliveryId())
            }
            incomingIntent.value = currentIntent
        }
        NasFinderShortcuts.installDynamic(this)
        enableEdgeToEdge()
        setContent {
            val nasApplication = this@MainActivity.application as NasFinderApplication
            val model: NasFinderViewModel = viewModel {
                NasFinderViewModel(nasApplication)
            }
            val updateState by appUpdateService.state.collectAsStateWithLifecycle()
            var ignoredUpdateVersion by rememberSaveable { mutableStateOf<Int?>(null) }
            var automaticDownloadEnabled by rememberSaveable { mutableStateOf(appUpdateService.automaticDownloadEnabled) }
            NasFinderTheme(nasApplication.settings.theme(), nasApplication.settings.iconPack()) {
                NasFinderApp(
                    model,
                    automaticDownloadEnabled,
                    {
                        automaticDownloadEnabled = it
                        appUpdateService.automaticDownloadEnabled = it
                    },
                    {
                        ignoredUpdateVersion = null
                        appUpdateService.checkForUpdate(isManual = true)
                    },
                )
                val updateVersion = when (val value = updateState) {
                    is AppUpdateState.Available -> value.release.versionCode
                    is AppUpdateState.Downloading -> value.release.versionCode
                    is AppUpdateState.Ready -> value.release.versionCode
                    is AppUpdateState.Checking, is AppUpdateState.Latest, is AppUpdateState.Failed, AppUpdateState.Idle -> null
                }
                val showUpdateDialog = when (val value = updateState) {
                    is AppUpdateState.Checking -> value.isManual
                    is AppUpdateState.Latest, is AppUpdateState.Failed -> true
                    else -> updateVersion != null && ignoredUpdateVersion != updateVersion
                }
                if (showUpdateDialog) {
                    AppUpdateDialog(
                        state = updateState,
                        onDownload = {
                            (updateState as? AppUpdateState.Available)?.let { appUpdateService.download(it.release) }
                        },
                        onInstall = {
                            (updateState as? AppUpdateState.Ready)?.let { requestUpdateInstall(it.apkFile) }
                        },
                        onCancel = appUpdateService::cancelDownload,
                        onRetry = { appUpdateService.checkForUpdate(isManual = true) },
                        onLater = { ignoredUpdateVersion = updateVersion; appUpdateService.dismiss() },
                    )
                }
                LaunchedEffect(incomingIntent.value) {
                    val entry = incomingIntent.value ?: return@LaunchedEffect
                    try {
                        when (ExternalEntryRouteParser.parse(entry.action, entry.dataString)) {
                            ExternalEntryRoute.Inbox -> model.show(Screen.Inbox)
                            ExternalEntryRoute.WebHard -> model.show(Screen.Inbox)
                            ExternalEntryRoute.WebBrowser -> model.show(Screen.WebBrowser)
                            ExternalEntryRoute.PassThrough, ExternalEntryRoute.Rejected -> model.handleEntryIntent(entry)
                        }
                    } finally {
                        intentConsumed = true
                        incomingIntent.value = null
                    }
                }
            }
        }
        appUpdateService.checkForUpdate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentConsumed = false
        val deliveryId = nextDeliveryId()
        intent.putExtra(EXTRA_DELIVERY_ID, deliveryId)
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_INTENT_CONSUMED, intentConsumed)
        outState.putLong(KEY_PROCESS_SESSION, processSession)
    }

    override fun onDestroy() {
        appUpdateService.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_DELIVERY_ID = "com.armsone.nasfinder.extra.DELIVERY_ID"
        private const val KEY_INTENT_CONSUMED = "com.armsone.nasfinder.state.INTENT_CONSUMED"
        private const val KEY_PROCESS_SESSION = "com.armsone.nasfinder.state.PROCESS_SESSION"
        private val processSession: Long = System.nanoTime() xor System.currentTimeMillis()
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private val deliverySequence = AtomicLong(System.currentTimeMillis())
        private fun nextDeliveryId(): Long = deliverySequence.incrementAndGet()
    }
}
