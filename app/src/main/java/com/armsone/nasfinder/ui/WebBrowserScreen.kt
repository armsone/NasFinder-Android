@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.armsone.nasfinder.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.webkit.WebChromeClient
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.armsone.nasfinder.model.BrowserFavorite
import com.armsone.nasfinder.model.BrowserUrlPolicy
import com.armsone.nasfinder.model.RemoteConnection
import com.armsone.nasfinder.util.DownloadCacheContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

private const val MAX_WEB_DOWNLOAD_BYTES = 512L * 1024L * 1024L
private const val MAX_WEB_DOWNLOAD_REDIRECTS = 8

private data class ActiveWebDownload(val filename: String, val received: Long, val total: Long?)
private data class PendingWebDownload(val file: File, val filename: String, val mimeType: String?)
private data class WebShortcut(val url: String, val isImage: Boolean)
private data class BrowserFullscreenContent(
    val view: View,
    val callback: WebChromeClient.CustomViewCallback,
)

private val webDownloadClient = OkHttpClient.Builder().followRedirects(false).build()

@Composable
private fun BrowserCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    emphasized: Boolean = false,
    selected: Boolean = false,
) {
    val side = if (androidx.compose.ui.platform.LocalConfiguration.current.fontScale >= 1.3f) 40.dp else 32.dp
    val background = when {
        emphasized -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = .16f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val tint = if (emphasized) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier.size(side).background(background, CircleShape).combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
            onLongClickLabel = onLongClick?.let { "길게 누르기" },
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(if (side > 32.dp) 20.dp else 18.dp), tint = tint)
    }
}

private suspend fun downloadWebFile(
    cacheDirectory: File,
    rawUrl: String,
    userAgent: String?,
    cookie: String?,
    contentDisposition: String?,
    suggestedMimeType: String?,
    suggestedLength: Long,
    onProgress: suspend (ActiveWebDownload) -> Unit,
): PendingWebDownload = withContext(Dispatchers.IO) {
    var current = rawUrl.toHttpUrlOrNull()?.requireSafeWebUrl()
        ?: throw IllegalArgumentException("HTTP 또는 HTTPS 다운로드 주소만 사용할 수 있습니다.")
    val initialOrigin = current.originKey()
    if (suggestedLength > MAX_WEB_DOWNLOAD_BYTES) throw IllegalStateException("512MB보다 큰 파일은 웹 브라우저에서 받을 수 없습니다.")
    var redirects = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val request = Request.Builder().url(current).apply {
            if (!userAgent.isNullOrBlank()) header("User-Agent", userAgent.take(512))
            if (!cookie.isNullOrBlank() && current.originKey() == initialOrigin) header("Cookie", cookie.take(16_384))
        }.build()
        val call = webDownloadClient.newCall(request)
        val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        try {
            call.execute().use { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    if (++redirects > MAX_WEB_DOWNLOAD_REDIRECTS) error("다운로드 리디렉션이 너무 많습니다.")
                    val location = response.header("Location") ?: error("다운로드 이동 주소가 없습니다.")
                    current = current.resolve(location)?.requireSafeWebUrl()
                        ?: error("안전하지 않은 다운로드 이동 주소입니다.")
                    return@use
                }
                if (!response.isSuccessful) error("다운로드 서버 오류 (${response.code})")
                val body = response.body
                val responseLength = body.contentLength().takeIf { it >= 0 }
                val total = responseLength ?: suggestedLength.takeIf { it >= 0 }
                if (total != null && total > MAX_WEB_DOWNLOAD_BYTES) error("512MB보다 큰 파일은 웹 브라우저에서 받을 수 없습니다.")
                val mimeType = response.header("Content-Type")?.substringBefore(';')?.trim()
                    ?.takeIf(::isSafeMimeType)
                    ?: suggestedMimeType?.substringBefore(';')?.trim()?.takeIf(::isSafeMimeType)
                val disposition = response.header("Content-Disposition") ?: contentDisposition
                val filename = safeWebFilename(URLUtil.guessFileName(current.toString(), disposition, mimeType))
                val cacheRoot = cacheDirectory.canonicalFile
                val requestedRoot = File(cacheRoot, "web-downloads")
                check(!java.nio.file.Files.isSymbolicLink(requestedRoot.toPath())) { "다운로드 임시 폴더가 안전하지 않습니다." }
                check(requestedRoot.mkdirs() || requestedRoot.isDirectory) { "다운로드 임시 폴더를 만들 수 없습니다." }
                val root = requestedRoot.canonicalFile
                check(root.parentFile == cacheRoot && !java.nio.file.Files.isSymbolicLink(requestedRoot.toPath())) {
                    "다운로드 임시 폴더가 안전하지 않습니다."
                }
                val directory = File(root, UUID.randomUUID().toString()).canonicalFile
                check(directory.parentFile == root && directory.mkdir()) { "다운로드 임시 폴더를 만들 수 없습니다." }
                val destination = File(directory, filename).canonicalFile
                check(destination.parentFile == directory) { "안전하지 않은 다운로드 파일명입니다." }
                val partial = File(directory, ".$filename.part")
                try {
                    var received = 0L
                    var lastReportedBytes = 0L
                    var lastReportedAt = android.os.SystemClock.elapsedRealtime()
                    body.byteStream().use { input ->
                        FileOutputStream(partial).buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                received += count
                                if (received > MAX_WEB_DOWNLOAD_BYTES) error("512MB 다운로드 제한에 도달했습니다.")
                                output.write(buffer, 0, count)
                                val now = android.os.SystemClock.elapsedRealtime()
                                if (received == total || received - lastReportedBytes >= 128L * 1024L || now - lastReportedAt >= 100L) {
                                    onProgress(ActiveWebDownload(filename, received, total))
                                    lastReportedBytes = received
                                    lastReportedAt = now
                                }
                            }
                        }
                    }
                    if (received != lastReportedBytes) onProgress(ActiveWebDownload(filename, received, total))
                    check(partial.renameTo(destination)) { "다운로드 파일을 확정하지 못했습니다." }
                    return@withContext PendingWebDownload(destination, filename, mimeType)
                } catch (failure: Throwable) {
                    partial.delete()
                    destination.delete()
                    directory.delete()
                    throw failure
                }
            }
        } finally {
            cancellation?.dispose()
        }
    }
    error("다운로드를 완료하지 못했습니다.")
}

private fun HttpUrl.requireSafeWebUrl(): HttpUrl = apply {
    require(scheme == "http" || scheme == "https") { "HTTP 또는 HTTPS 다운로드 주소만 사용할 수 있습니다." }
    require(username.isEmpty() && password.isEmpty()) { "계정 정보가 포함된 다운로드 주소는 사용할 수 없습니다." }
}

private fun HttpUrl.originKey(): String = "$scheme://${host.lowercase()}:$port"

private fun isSafeMimeType(value: String): Boolean =
    value.length <= 255 && '/' in value && value.none { it.code < 0x20 || it == '\u007f' }

private fun safeWebFilename(raw: String): String {
    return DownloadCacheContract.safeFilename(raw)
}

private fun cleanupLocalWebDownload(cacheDirectory: File, file: File) {
    val cacheRoot = cacheDirectory.canonicalFile
    val requestedRoot = File(cacheRoot, "web-downloads")
    if (java.nio.file.Files.isSymbolicLink(requestedRoot.toPath())) return
    val root = requestedRoot.canonicalFile
    if (root.parentFile != cacheRoot) return
    val source = file.canonicalFile
    val directory = source.parentFile ?: return
    if (directory.parentFile != root || runCatching { UUID.fromString(directory.name) }.isFailure ||
        java.nio.file.Files.isSymbolicLink(directory.toPath()) || java.nio.file.Files.isSymbolicLink(source.toPath())
    ) return
    if (source.isFile) source.delete()
    directory.listFiles()?.forEach { sibling ->
        if (!java.nio.file.Files.isSymbolicLink(sibling.toPath()) && sibling.isFile) sibling.delete()
    }
    directory.delete()
}

class WebBrowserSessionController : java.io.Closeable {
    private val handler = Handler(Looper.getMainLooper())
    private var retained: WebView? = null
    private var inactiveAt: Long? = null
    private var expiry: Runnable? = null
    private var expiredSinceTake = false
    private val destroyedViews = java.util.Collections.newSetFromMap(
        java.util.IdentityHashMap<WebView, Boolean>()
    )

    data class TakeResult(val webView: WebView?, val expired: Boolean)

    fun retain(webView: WebView) {
        if (webView in destroyedViews) return
        pausePlayback(webView)
        retained = webView
        inactiveAt = android.os.SystemClock.elapsedRealtime()
        scheduleExpiry(webView)
    }

    fun markBackgrounded(webView: WebView) {
        pausePlayback(webView)
        retained = webView
        inactiveAt = android.os.SystemClock.elapsedRealtime()
        scheduleExpiry(webView)
    }

    fun take(): TakeResult {
        val view = retained
        val expired = expiredSinceTake || (view != null && isExpired())
        if (view != null && isExpired()) expire(view)
        val result = if (expired) null else retained
        retained = null
        inactiveAt = null
        expiry?.let(handler::removeCallbacks)
        expiry = null
        expiredSinceTake = false
        return TakeResult(result, expired)
    }

    fun resumeActive(webView: WebView): Boolean {
        val alreadyExpired = expiredSinceTake
        val expired = alreadyExpired || isExpired()
        expiry?.let(handler::removeCallbacks)
        expiry = null
        retained = null
        inactiveAt = null
        expiredSinceTake = false
        if (expired && !alreadyExpired) expire(webView)
        return expired
    }

    private fun scheduleExpiry(webView: WebView) {
        expiry?.let(handler::removeCallbacks)
        Runnable {
            if (retained === webView && isExpired()) {
                expire(webView)
                retained = null
                inactiveAt = null
                expiredSinceTake = true
            }
        }.also { runnable ->
            expiry = runnable
            handler.postDelayed(runnable, RETENTION_MILLIS)
        }
    }

    private fun isExpired(): Boolean = inactiveAt?.let {
        android.os.SystemClock.elapsedRealtime() - it >= RETENTION_MILLIS
    } == true

    private fun pausePlayback(webView: WebView) {
        webView.onPause()
        webView.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(item){try{item.pause()}catch(e){}})", null)
    }

    private fun expire(webView: WebView) {
        if (!destroyedViews.add(webView)) return
        webView.stopLoading()
        pausePlayback(webView)
        webView.clearHistory()
        webView.clearFormData()
        webView.clearSslPreferences()
        webView.clearCache(true)
        WebStorage.getInstance().deleteAllData()
        WebViewDatabase.getInstance(webView.context).apply {
            clearFormData()
            clearHttpAuthUsernamePassword()
        }
        CookieManager.getInstance().apply {
            removeSessionCookies(null)
            flush()
        }
        webView.loadUrl("about:blank")
        webView.removeAllViews()
        webView.destroy()
    }

    override fun close() {
        expiry?.let(handler::removeCallbacks)
        expiry = null
        retained?.let(::expire)
        retained = null
        inactiveAt = null
    }

    private companion object { const val RETENTION_MILLIS = 30L * 60L * 1000L }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebBrowserScreen(
    sessionController: WebBrowserSessionController,
    initialUrl: String = "https://www.google.com",
    favorites: List<BrowserFavorite> = emptyList(),
    connections: List<RemoteConnection> = emptyList(),
    preferredConnectionId: String? = null,
    resumedDownload: PendingLocalUpload? = null,
    onClose: () -> Unit,
    onToggleFavorite: (title: String, url: String) -> Unit,
    onSetHomepage: (BrowserFavorite) -> Unit,
    onEditFavorite: (BrowserFavorite, title: String, url: String) -> Unit,
    onDeleteFavorite: (BrowserFavorite) -> Unit,
    onSaveDownloadedFile: suspend (File, String, String?) -> Boolean,
    onSendDownloadedFile: suspend (File, String, String?, RemoteConnection) -> Boolean,
    onDiscardDownloadedFile: (File) -> Unit,
) {
    val context = LocalContext.current
    val composeView = LocalView.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(BrowserUrlPolicy.normalize(initialUrl).orEmpty()) }
    var pageTitle by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showFavorites by remember { mutableStateOf(false) }
    var editingFavorite by remember { mutableStateOf<BrowserFavorite?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editUrl by remember { mutableStateOf("") }
    var deletingFavorite by remember { mutableStateOf<BrowserFavorite?>(null) }
    var sessionNotice by remember { mutableStateOf<String?>(null) }
    var sessionGeneration by remember { mutableIntStateOf(0) }
    var activeDownload by remember { mutableStateOf<ActiveWebDownload?>(null) }
    var pendingDownload by remember(resumedDownload?.file) {
        mutableStateOf(resumedDownload?.let { PendingWebDownload(it.file, it.filename, it.mimeType) })
    }
    var handedOffFile by remember { mutableStateOf(resumedDownload?.file) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var downloadActionInFlight by remember { mutableStateOf(false) }
    var chooseNetworkConnection by remember { mutableStateOf(false) }
    var shortcut by remember { mutableStateOf<WebShortcut?>(null) }
    var fullscreenContent by remember { mutableStateOf<BrowserFullscreenContent?>(null) }
    val latestFullscreenContent by rememberUpdatedState(fullscreenContent)
    val lifecycleOwner = LocalLifecycleOwner.current
    val normalizedAddress = BrowserUrlPolicy.normalize(address)
    val currentIsFavorite = normalizedAddress != null && favorites.any { it.url == normalizedAddress }
    val browserControlHeight = if (androidx.compose.ui.platform.LocalConfiguration.current.fontScale >= 1.3f) 40.dp else 32.dp

    fun startDownload(
        url: String,
        userAgent: String? = webView?.settings?.userAgentString,
        disposition: String? = null,
        mimeType: String? = null,
        length: Long = -1,
    ) {
        if (downloadJob?.isActive == true) {
            error = "이미 다운로드 중입니다. 현재 다운로드를 취소한 뒤 다시 시도해 주세요."
            return
        }
        val normalized = url.toHttpUrlOrNull()?.runCatching { requireSafeWebUrl() }?.getOrNull()
        if (normalized == null) {
            error = "HTTP 또는 HTTPS 파일만 다운로드할 수 있습니다."
            return
        }
        val initialName = safeWebFilename(URLUtil.guessFileName(url, disposition, mimeType))
        activeDownload = ActiveWebDownload(initialName, 0, length.takeIf { it >= 0 })
        downloadJob = scope.launch {
            runCatching {
                downloadWebFile(
                    cacheDirectory = context.cacheDir,
                    rawUrl = normalized.toString(),
                    userAgent = userAgent,
                    cookie = CookieManager.getInstance().getCookie(normalized.toString()),
                    contentDisposition = disposition,
                    suggestedMimeType = mimeType,
                    suggestedLength = length,
                    onProgress = { state -> withContext(Dispatchers.Main.immediate) { activeDownload = state } },
                )
            }.onSuccess { downloaded ->
                pendingDownload?.file?.let { cleanupLocalWebDownload(context.cacheDir, it) }
                pendingDownload = downloaded
            }.onFailure { failure ->
                if (failure !is CancellationException) error = failure.message ?: "파일을 다운로드하지 못했습니다."
            }
            activeDownload = null
            downloadJob = null
        }
    }

    fun navigate() {
        val normalized = BrowserUrlPolicy.normalize(address)
        if (normalized == null) error = "HTTP 또는 HTTPS 웹 주소를 입력해 주세요."
        else {
            error = null
            address = normalized
            webView?.loadUrl(normalized)
        }
    }

    fun closeFullscreenVideo() {
        fullscreenContent?.callback?.onCustomViewHidden()
        fullscreenContent = null
    }

    BackHandler {
        when {
            fullscreenContent != null -> closeFullscreenVideo()
            webView?.canGoBack() == true -> webView?.goBack()
            else -> onClose()
        }
    }

    DisposableEffect(fullscreenContent, composeView) {
        val isFullscreen = fullscreenContent != null
        val window = context.findActivity()?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, composeView) }
        if (isFullscreen) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (isFullscreen) controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(lifecycleOwner, webView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> webView?.let(sessionController::markBackgrounded)
                Lifecycle.Event.ON_START -> webView?.let { active ->
                    if (sessionController.resumeActive(active)) {
                        webView = null
                        address = BrowserUrlPolicy.normalize(initialUrl).orEmpty()
                        pageTitle = ""
                        canGoBack = false
                        sessionNotice = "30분 동안 사용하지 않아 웹 세션을 정리하고 홈페이지로 돌아왔습니다."
                        sessionGeneration++
                    } else active.onResume()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val latestPendingDownload by rememberUpdatedState(pendingDownload)
    val latestDownloadJob by rememberUpdatedState(downloadJob)
    DisposableEffect(Unit) {
        onDispose {
            latestDownloadJob?.cancel()
            latestPendingDownload?.file?.takeUnless { it == handedOffFile }
                ?.let { cleanupLocalWebDownload(context.cacheDir, it) }
        }
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .94f), contentColor = MaterialTheme.colorScheme.onSurface, tonalElevation = 3.dp) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        BrowserCircleButton(
                            icon = if (canGoBack) Icons.Default.ArrowBack else Icons.Default.Close,
                            description = if (canGoBack) "뒤로" else "닫기",
                            onClick = { if (webView?.canGoBack() == true) webView?.goBack() else onClose() },
                            onLongClick = onClose,
                        )
                        Row(
                            Modifier.weight(1f).height(browserControlHeight).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(browserControlHeight / 2)).padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicTextField(
                                value = address,
                                onValueChange = { address = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = { navigate() }),
                                decorationBox = { inner ->
                                    Box(contentAlignment = Alignment.CenterStart) {
                                        if (address.isBlank()) Text("웹 주소", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        inner()
                                    }
                                },
                            )
                            if (address.isNotEmpty()) {
                                IconButton(onClick = { address = "" }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Cancel, "주소 지우기", Modifier.size(16.dp)) }
                            }
                        }
                        BrowserCircleButton(
                            icon = if (loading) Icons.Default.Close else Icons.Default.ArrowForward,
                            description = if (loading) "중지" else "이동",
                            emphasized = true,
                            onClick = { if (loading) webView?.stopLoading() else navigate() },
                        )
                        BrowserCircleButton(Icons.Default.Refresh, "새로고침", onClick = { webView?.reload() })
                        BrowserCircleButton(
                            icon = Icons.Default.Bookmarks,
                            description = "저장된 즐겨찾기",
                            onClick = { showFavorites = !showFavorites },
                            onLongClick = {
                                if (BrowserUrlPolicy.canOpenInsideApp(address)) {
                                    onToggleFavorite(pageTitle.ifBlank { address }, address)
                                }
                            },
                            selected = currentIsFavorite,
                        )
                    }
                    if (loading) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
        key(sessionGeneration) { AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                val retained = sessionController.take()
                if (retained.expired) sessionNotice = "30분 동안 사용하지 않아 웹 세션을 정리하고 홈페이지로 돌아왔습니다."
                (retained.webView ?: WebView(viewContext)).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.setSupportMultipleWindows(false)
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                            loading = newProgress < 100
                        }
                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            pageTitle = title.orEmpty()
                        }
                        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                            if (view == null || callback == null) return
                            fullscreenContent?.callback?.onCustomViewHidden()
                            fullscreenContent = BrowserFullscreenContent(view, callback)
                        }
                        override fun onHideCustomView() {
                            closeFullscreenVideo()
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val target = request.url.toString()
                            if (BrowserUrlPolicy.canOpenInsideApp(target)) return false
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
                                .onFailure { error = "이 링크를 열 수 있는 앱이 없습니다." }
                            return true
                        }
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            loading = true
                            url?.let { address = it }
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            loading = false
                            canGoBack = view?.canGoBack() == true
                            url?.let { address = it }
                        }
                    }
                    setDownloadListener { url, agent, disposition, mime, length ->
                        if (url.isNullOrBlank()) error = "다운로드 주소가 없습니다."
                        else startDownload(url, agent, disposition, mime, length)
                    }
                    setOnLongClickListener { view ->
                        val hit = (view as WebView).hitTestResult
                        val target = hit.extra?.let(BrowserUrlPolicy::normalize)
                        when (hit.type) {
                            WebView.HitTestResult.SRC_ANCHOR_TYPE -> target?.let { shortcut = WebShortcut(it, false) }
                            WebView.HitTestResult.IMAGE_TYPE,
                            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> target?.let { shortcut = WebShortcut(it, true) }
                        }
                        target != null
                    }
                    webView = this
                    onResume()
                    if (retained.webView == null) {
                        loadUrl(BrowserUrlPolicy.normalize(initialUrl).orEmpty())
                    } else {
                        url?.let { address = it }
                        pageTitle = title.orEmpty()
                        canGoBack = canGoBack()
                    }
                }
            },
            update = { webView = it },
            onRelease = { released ->
                latestFullscreenContent?.callback?.onCustomViewHidden()
                fullscreenContent = null
                sessionController.retain(released)
                if (webView === released) webView = null
            },
        ) }
        activeDownload?.let { download ->
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 5.dp,
                shadowElevation = 3.dp,
            ) {
                Row(
                    Modifier.widthIn(max = 420.dp).padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(download.filename, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        if (download.total != null && download.total > 0) {
                            LinearProgressIndicator(
                                progress = { (download.received.toFloat() / download.total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            if (download.total != null) "${formatWebBytes(download.received)} / ${formatWebBytes(download.total)}"
                            else "${formatWebBytes(download.received)} 받는 중",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { downloadJob?.cancel() }) { Icon(Icons.Default.Close, "다운로드 취소") }
                }
            }
        }
        }
    }

    pendingDownload?.takeUnless { chooseNetworkConnection }?.let { downloaded ->
        AlertDialog(
            onDismissRequest = {
                if (!downloadActionInFlight && !chooseNetworkConnection) {
                    if (downloaded.file == handedOffFile) onDiscardDownloadedFile(downloaded.file)
                    else cleanupLocalWebDownload(context.cacheDir, downloaded.file)
                    pendingDownload = null
                }
            },
            title = { Text("어디에 저장할까요?") },
            text = { Text(downloaded.filename, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(
                        enabled = !downloadActionInFlight,
                        onClick = {
                            downloadActionInFlight = true
                            scope.launch {
                                val saved = onSaveDownloadedFile(downloaded.file, downloaded.filename, downloaded.mimeType)
                                if (saved) { handedOffFile = null; pendingDownload = null }
                                downloadActionInFlight = false
                            }
                        },
                    ) { Text("받은 파일에 저장") }
                    TextButton(
                        enabled = !downloadActionInFlight && connections.isNotEmpty(),
                        onClick = { chooseNetworkConnection = true },
                    ) { Text(if (connections.isEmpty()) "저장 연결 없음" else "네트워크 위치 선택") }
                    TextButton(
                        enabled = !downloadActionInFlight,
                        onClick = {
                            if (downloaded.file == handedOffFile) onDiscardDownloadedFile(downloaded.file)
                            else cleanupLocalWebDownload(context.cacheDir, downloaded.file)
                            handedOffFile = null
                            pendingDownload = null
                        },
                    ) { Text("취소") }
                }
            },
        )
    }
    if (chooseNetworkConnection) {
        AlertDialog(
            onDismissRequest = { chooseNetworkConnection = false },
            title = { Text("저장 연결 선택") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(
                        connections.sortedByDescending { it.id == preferredConnectionId },
                        key = { it.id },
                    ) { connection ->
                        ListItem(
                            headlineContent = { Text(connection.name) },
                            supportingContent = { Text(connection.kind.title) },
                            leadingContent = { Icon(Icons.Default.Storage, null) },
                            trailingContent = if (connection.id == preferredConnectionId) {
                                { Icon(Icons.Default.Star, "기본 연결", tint = MaterialTheme.colorScheme.primary) }
                            } else null,
                            modifier = Modifier.clickable(enabled = !downloadActionInFlight) {
                                val downloaded = pendingDownload ?: return@clickable
                                downloadActionInFlight = true
                                scope.launch {
                                    val saved = onSendDownloadedFile(
                                        downloaded.file,
                                        downloaded.filename,
                                        downloaded.mimeType,
                                        connection,
                                    )
                                    if (saved) {
                                        handedOffFile = downloaded.file
                                        pendingDownload = null
                                        chooseNetworkConnection = false
                                    }
                                    downloadActionInFlight = false
                                }
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    enabled = !downloadActionInFlight,
                    onClick = { chooseNetworkConnection = false },
                ) { Text("취소") }
            },
        )
    }
    shortcut?.let { selected ->
        ModalBottomSheet(onDismissRequest = { shortcut = null }) {
            Text(
                if (selected.isImage) "이미지 메뉴" else "링크 메뉴",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Text(
                selected.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            ListItem(
                headlineContent = { Text(if (selected.isImage) "이미지 열기" else "링크 열기") },
                leadingContent = { Icon(Icons.Default.OpenInBrowser, null) },
                modifier = Modifier.clickable {
                    webView?.loadUrl(selected.url)
                    shortcut = null
                },
            )
            ListItem(
                headlineContent = { Text(if (selected.isImage) "이미지 다운로드" else "링크 다운로드") },
                leadingContent = { Icon(Icons.Default.Download, null) },
                modifier = Modifier.clickable {
                    startDownload(selected.url)
                    shortcut = null
                },
            )
            ListItem(
                headlineContent = { Text("주소 복사") },
                leadingContent = { Icon(Icons.Default.ContentCopy, null) },
                modifier = Modifier.clickable {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText("웹 주소", selected.url))
                    shortcut = null
                },
            )
            Spacer(Modifier.navigationBarsPadding())
        }
    }
    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            confirmButton = { TextButton(onClick = { error = null }) { Text("확인") } },
            title = { Text("브라우저 오류") },
            text = { Text(message) },
        )
    }
    sessionNotice?.let { message ->
        AlertDialog(
            onDismissRequest = { sessionNotice = null },
            confirmButton = { TextButton(onClick = { sessionNotice = null }) { Text("확인") } },
            title = { Text("웹 세션 만료") },
            text = { Text("$message\n\n세션 쿠키와 웹 저장소·폼·HTTP 인증·페이지 캐시를 지웠습니다. 만료되지 않는 쿠키는 유지됩니다.") },
        )
    }
    if (showFavorites) {
        ModalBottomSheet(onDismissRequest = { showFavorites = false }) {
            Text(
                "즐겨찾기",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (favorites.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Default.Bookmarks, null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text("저장된 즐겨찾기가 없습니다", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(5.dp))
                    Text("페이지를 연 뒤 별 버튼을 눌러 추가하세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                ) {
                    items(favorites, key = { it.id }) { favorite ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                address = favorite.url
                                error = null
                                webView?.loadUrl(favorite.url)
                                showFavorites = false
                            }.padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (favorite.isHomepage) Icons.Default.Home else Icons.Default.Language,
                                if (favorite.isHomepage) "홈페이지" else null,
                                tint = if (favorite.isHomepage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(favorite.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(favorite.url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { onSetHomepage(favorite) }, enabled = !favorite.isHomepage) {
                                Icon(Icons.Default.Home, if (favorite.isHomepage) "현재 홈페이지" else "홈페이지로 지정")
                            }
                            IconButton(onClick = {
                                editTitle = favorite.title
                                editUrl = favorite.url
                                editingFavorite = favorite
                                showFavorites = false
                            }) { Icon(Icons.Default.Edit, "편집") }
                            IconButton(onClick = { deletingFavorite = favorite; showFavorites = false }) {
                                Icon(Icons.Default.Delete, "삭제")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    editingFavorite?.let { favorite ->
        AlertDialog(
            onDismissRequest = { editingFavorite = null },
            title = { Text("즐겨찾기 편집") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(editTitle, { editTitle = it }, label = { Text("이름") }, singleLine = true)
                    OutlinedTextField(editUrl, { editUrl = it }, label = { Text("웹 주소") }, singleLine = true)
                }
            },
            dismissButton = { TextButton(onClick = { editingFavorite = null }) { Text("취소") } },
            confirmButton = {
                TextButton(
                    onClick = { editingFavorite = null; onEditFavorite(favorite, editTitle, editUrl) },
                    enabled = editTitle.trim().isNotEmpty() && BrowserUrlPolicy.normalize(editUrl) != null,
                ) { Text("저장") }
            },
        )
    }
    deletingFavorite?.let { favorite ->
        AlertDialog(
            onDismissRequest = { deletingFavorite = null },
            title = { Text("즐겨찾기를 삭제할까요?") },
            text = { Text("${favorite.title}을(를) 즐겨찾기에서 삭제합니다.") },
            dismissButton = { TextButton(onClick = { deletingFavorite = null }) { Text("취소") } },
            confirmButton = {
                TextButton(
                    onClick = { deletingFavorite = null; onDeleteFavorite(favorite) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("삭제") }
            },
        )
    }
    fullscreenContent?.let { content ->
        key(content.view) {
            BrowserFullscreenVideo(
                modifier = Modifier.fillMaxSize().zIndex(10f),
                content = content,
            )
        }
    }
    }
}

@Composable
private fun BrowserFullscreenVideo(
    modifier: Modifier,
    content: BrowserFullscreenContent,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            FrameLayout(context).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                (content.view.parent as? ViewGroup)?.removeView(content.view)
                addView(
                    content.view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        },
        onRelease = { container -> container.removeView(content.view) },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun formatWebBytes(value: Long): String = when {
    value >= 1024L * 1024L * 1024L -> "%.1fGB".format(value / (1024.0 * 1024.0 * 1024.0))
    value >= 1024L * 1024L -> "%.1fMB".format(value / (1024.0 * 1024.0))
    value >= 1024L -> "%.1fKB".format(value / 1024.0)
    else -> "${value.coerceAtLeast(0)}B"
}
