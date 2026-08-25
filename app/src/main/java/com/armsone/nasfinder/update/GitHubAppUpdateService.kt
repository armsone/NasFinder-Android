package com.armsone.nasfinder.update

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

data class GitHubAppRelease(
    val versionCode: Int,
    val tagName: String,
    val assetName: String,
    val apkUrl: URL,
    val assetSizeBytes: Long,
    val sha256: String = "",
    val releaseNotes: String = "",
)

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data class Checking(val isManual: Boolean) : AppUpdateState
    data class Latest(val currentVersionCode: Int) : AppUpdateState
    data class Available(val release: GitHubAppRelease, val message: String? = null) : AppUpdateState
    data class Downloading(val release: GitHubAppRelease, val progressPercent: Int?, val isAutomatic: Boolean) : AppUpdateState
    data class Ready(val release: GitHubAppRelease, val apkFile: File) : AppUpdateState
    data class Failed(val message: String, val canRetry: Boolean = true) : AppUpdateState
}

/** Optional, user-confirmed updater for the project's official GitHub Releases. */
class GitHubAppUpdateService(context: Context, private val currentVersionCode: Int) : Closeable {
    private val appContext = context.applicationContext
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)
    private val preferences = appContext.getSharedPreferences("app_update", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("NasFinderUpdate"))
    private val mutableState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    private val checkStarted = AtomicBoolean(false)
    private var downloadJob: Job? = null
    private var activeDownloadId: Long? = null

    val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()
    var automaticDownloadEnabled: Boolean
        get() = preferences.getBoolean("automatic_download_enabled", true)
        set(value) { preferences.edit().putBoolean("automatic_download_enabled", value).apply() }

    fun checkForUpdate(isManual: Boolean = false) {
        if (mutableState.value is AppUpdateState.Downloading || !checkStarted.compareAndSet(false, true)) return
        mutableState.value = AppUpdateState.Checking(isManual)
        scope.launch {
            try {
                val result = runCatching { fetchLatestRelease() }; val release = result.getOrNull()
                when {
                    result.isFailure -> mutableState.value = if (isManual) AppUpdateState.Failed("최신 버전을 확인하지 못했습니다. 인터넷 연결을 확인한 뒤 다시 시도해 주세요.") else AppUpdateState.Idle
                    release == null || release.versionCode <= currentVersionCode -> mutableState.value = if (isManual) AppUpdateState.Latest(currentVersionCode) else AppUpdateState.Idle
                    !isManual && automaticDownloadEnabled -> { mutableState.value = AppUpdateState.Available(release); startDownload(release, true) }
                    else -> mutableState.value = AppUpdateState.Available(release)
                }
            } finally { checkStarted.set(false) }
        }
    }

    fun download(release: GitHubAppRelease) = startDownload(release, false)
    fun cancelDownload() {
        activeDownloadId?.let { downloadManager.remove(it) }; activeDownloadId = null; downloadJob?.cancel(); downloadJob = null
        val release = (mutableState.value as? AppUpdateState.Downloading)?.release
        mutableState.value = release?.let { AppUpdateState.Available(it, "다운로드를 취소했습니다. 다시 받을 수 있습니다.") } ?: AppUpdateState.Idle
    }
    fun dismiss() { if (mutableState.value !is AppUpdateState.Downloading) mutableState.value = AppUpdateState.Idle }

    private fun startDownload(release: GitHubAppRelease, automatic: Boolean) {
        if (downloadJob?.isActive == true) return
        val current = mutableState.value
        if (current !is AppUpdateState.Available || current.release != release) return
        val request = DownloadManager.Request(Uri.parse(release.apkUrl.toExternalForm())).setTitle("NasFinder ${release.versionCode}").setDescription("업데이트 다운로드 중").setMimeType(APK_MIME).setAllowedOverMetered(!automatic).setAllowedOverRoaming(false).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        val id = runCatching { downloadManager.enqueue(request) }.getOrElse { mutableState.value = AppUpdateState.Failed("다운로드를 시작하지 못했습니다. 다시 시도해 주세요."); return }
        activeDownloadId = id; mutableState.value = AppUpdateState.Downloading(release, 0, automatic)
        downloadJob = scope.launch {
            try { monitorDownload(id, release, automatic) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { downloadManager.remove(id); mutableState.value = AppUpdateState.Failed("업데이트를 받거나 검증하지 못했습니다. 다시 시도해 주세요.") }
            finally { activeDownloadId = null }
        }
    }

    override fun close() { downloadJob?.cancel(); scope.cancel() }

    private suspend fun monitorDownload(id: Long, release: GitHubAppRelease, automatic: Boolean) {
        while (true) {
            val snapshot = queryDownload(id)
            when (snapshot.first) {
                DownloadManager.STATUS_SUCCESSFUL -> { val uri = downloadManager.getUriForDownloadedFile(id) ?: throw IOException("Downloaded file unavailable"); val verified = copyAndVerify(uri, release); downloadManager.remove(id); mutableState.value = AppUpdateState.Ready(release, verified); return }
                DownloadManager.STATUS_FAILED -> throw IOException("DownloadManager failed")
                else -> mutableState.value = AppUpdateState.Downloading(release, snapshot.second, automatic)
            }
            delay(300)
        }
    }

    private fun queryDownload(id: Long): Pair<Int, Int?> = downloadManager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
        if (!cursor.moveToFirst()) throw IOException("Download disappeared")
        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)); val done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)); val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
        status to if (total > 0) ((done * 100L) / total).toInt().coerceIn(0, 100) else null
    } ?: throw IOException("Download query failed")

    private fun copyAndVerify(uri: Uri, release: GitHubAppRelease): File {
        val directory = File(appContext.cacheDir, UPDATE_DIRECTORY).apply { mkdirs() }; val partial = File(directory, "${release.assetName.removeSuffix(".apk")}.partial.apk"); val destination = File(directory, release.assetName)
        partial.delete(); val digest = MessageDigest.getInstance("SHA-256"); var total = 0L
        appContext.contentResolver.openInputStream(uri)?.use { input -> partial.outputStream().buffered().use { output -> val buffer = ByteArray(DEFAULT_BUFFER_SIZE); while (true) { val count = input.read(buffer); if (count < 0) break; total += count; if (total > MAX_APK_BYTES) throw IOException("APK too large"); digest.update(buffer, 0, count); output.write(buffer, 0, count) } } } ?: throw IOException("Downloaded file cannot be opened")
        if (total != release.assetSizeBytes || digest.digest().joinToString("") { "%02x".format(it) } != release.sha256) { partial.delete(); throw IOException("APK digest mismatch") }
        verifyDownloadedApk(partial, release.versionCode); destination.delete(); if (!partial.renameTo(destination)) throw IOException("Cannot finalize APK"); return destination
    }

    private fun fetchLatestRelease(): GitHubAppRelease? {
        val connection = openConnection(LATEST_RELEASE_URL)
        return try {
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (status !in 200..299) throw IOException("GitHub returned HTTP $status")
            val payload = connection.inputStream.use {
                readBoundedUtf8(it, MAX_RELEASE_JSON_CHARS)
            }
            GitHubReleaseDecoder.decode(payload)
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadAndVerify(release: GitHubAppRelease): File {
        val directory = File(appContext.cacheDir, UPDATE_DIRECTORY).apply {
            if (!isDirectory && !mkdirs()) throw IOException("Cannot create update directory")
        }
        val temporaryDirectory = File(appContext.cacheDir, UPDATE_TEMP_DIRECTORY).apply {
            if (!isDirectory && !mkdirs()) throw IOException("Cannot create update temporary directory")
        }
        directory.listFiles()?.forEach { if (it.name != release.assetName) it.delete() }
        temporaryDirectory.listFiles()?.forEach(File::delete)
        val destination = File(directory, release.assetName)
        val partial = File(temporaryDirectory, "${release.assetName.removeSuffix(".apk")}.partial.apk")
        partial.delete()
        if (!GitHubUpdatePolicy.isApprovedApkAsset(
                release.assetName,
                release.apkUrl.toExternalForm(),
                GitHubUpdatePolicy.productVersion(release.tagName) ?: throw IOException("Invalid release tag"),
                release.assetSizeBytes,
            )
        ) throw IOException("Release asset is no longer approved")
        val connection = openDownloadConnection(release.apkUrl)
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("APK download returned HTTP $status")
            if (connection.contentLengthLong > MAX_APK_BYTES) throw IOException("APK is too large")
            var total = 0L
            connection.inputStream.use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_APK_BYTES) throw IOException("APK is too large")
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (total <= 0L || (release.assetSizeBytes > 0 && total != release.assetSizeBytes)) {
                throw IOException("APK size does not match release metadata")
            }
            verifyDownloadedApk(partial, release.versionCode)
            destination.delete()
            if (!partial.renameTo(destination)) throw IOException("Cannot finalize APK")
            return destination
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyDownloadedApk(apkFile: File, expectedVersionCode: Int) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archive = appContext.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: throw IOException("Android could not parse downloaded APK")
        if (archive.packageName != appContext.packageName) throw IOException("APK package mismatch")
        val archiveVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode
        } else archive.versionCode.toLong()
        if (archiveVersion != expectedVersionCode.toLong() || archiveVersion <= currentVersionCode) {
            throw IOException("APK version mismatch")
        }
        val installed = appContext.packageManager.getPackageInfo(appContext.packageName, flags)
        val installedCertificates = installed.currentSigningCertificates()
        val archiveCertificates = archive.currentSigningCertificates()
        if (installedCertificates.isEmpty() || installedCertificates != archiveCertificates) {
            throw IOException("APK signing certificate mismatch")
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.currentSigningCertificates(): Set<String> {
        val certificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners
        } else {
            signatures
        }
        return certificates.orEmpty().mapTo(mutableSetOf()) { it.toCharsString() }
    }

    private fun openConnection(url: URL): HttpURLConnection {
        require(url.protocol == "https")
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = NETWORK_TIMEOUT_MILLIS
            readTimeout = NETWORK_TIMEOUT_MILLIS
            instanceFollowRedirects = false
            useCaches = false
            doInput = true
            setRequestProperty("User-Agent", "NasFinder-Android/$currentVersionCode")
        }
    }

    private fun openDownloadConnection(initialUrl: URL): HttpURLConnection {
        var current = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val connection = openConnection(current)
            val status = connection.responseCode
            if (status !in REDIRECT_STATUS_CODES) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (redirectCount >= MAX_REDIRECTS || location.isNullOrBlank()) {
                throw IOException("Too many or invalid APK redirects")
            }
            val redirected = runCatching { URL(current, location) }
                .getOrElse { throw IOException("Invalid APK redirect", it) }
            if (!GitHubUpdatePolicy.isApprovedDownloadRedirect(redirected)) {
                throw IOException("Unapproved APK redirect")
            }
            current = redirected
        }
        throw IOException("Too many APK redirects")
    }

    private companion object {
        val LATEST_RELEASE_URL = URL("https://api.github.com/repos/armsone/NasFinder-Android/releases/latest")
        const val UPDATE_DIRECTORY = "updates"
        const val UPDATE_TEMP_DIRECTORY = "update-temp"
        const val NETWORK_TIMEOUT_MILLIS = 15_000
        const val MAX_RELEASE_JSON_CHARS = 1_000_000
        const val MAX_APK_BYTES = 250L * 1_024L * 1_024L
        const val MAX_REDIRECTS = 5
        const val APK_MIME = "application/vnd.android.package-archive"
        val REDIRECT_STATUS_CODES = setOf(
            HttpURLConnection.HTTP_MOVED_PERM,
            HttpURLConnection.HTTP_MOVED_TEMP,
            HttpURLConnection.HTTP_SEE_OTHER,
            307,
            308,
        )
    }
}

internal object GitHubReleaseDecoder {
    fun decode(payload: String): GitHubAppRelease? {
        val root = JSONObject(payload)
        if (root.optBoolean("draft", true) || root.optBoolean("prerelease", true)) return null
        val tag = root.optString("tag_name")
        val productVersion = GitHubUpdatePolicy.productVersion(tag) ?: return null
        val releaseNotes = root.optString("body")
        val versionCode = GitHubUpdatePolicy.versionCode(releaseNotes) ?: return null
        val assets = root.optJSONArray("assets") ?: return null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            val size = asset.optLong("size", -1L)
            if (GitHubUpdatePolicy.isApprovedApkAsset(name, url, productVersion, size)) {
                val digest = GitHubUpdatePolicy.sha256(asset.optString("digest")) ?: continue
                return GitHubAppRelease(versionCode, tag, name, URL(url), size, digest, releaseNotes)
            }
        }
        return null
    }
}

internal object GitHubUpdatePolicy {
    private val tagPattern = Regex("^android-v(\\d+\\.\\d+\\.\\d+)$")
    private val versionCodePattern = Regex("(?m)^Android-Version-Code:\\s*([1-9]\\d*)\\s*$")
    private val digestPattern = Regex("^sha256:([0-9a-f]{64})$")

    fun productVersion(tagName: String): String? = tagPattern.matchEntire(tagName)
        ?.groupValues?.get(1)
    fun versionCode(releaseNotes: String): Int? = versionCodePattern.find(releaseNotes)
        ?.groupValues?.get(1)?.toIntOrNull()
    fun sha256(value: String): String? = digestPattern.matchEntire(value)?.groupValues?.get(1)

    fun isApprovedApkAsset(assetName: String, urlText: String, productVersion: String, sizeBytes: Long): Boolean {
        if (assetName != "NasFinder-Android-$productVersion.apk") return false
        if (sizeBytes <= 0 || sizeBytes > 250L * 1_024L * 1_024L) return false
        val url = runCatching { URL(urlText) }.getOrNull() ?: return false
        return isStandardHttps(url) && url.host.equals("github.com", ignoreCase = true) &&
            url.path == "/armsone/NasFinder-Android/releases/download/android-v$productVersion/$assetName" &&
            url.query == null && url.ref == null
    }

    fun isApprovedDownloadRedirect(url: URL): Boolean =
        isStandardHttps(url) && url.host.lowercase() in setOf(
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "github-releases.githubusercontent.com",
        ) && url.ref == null

    private fun isStandardHttps(url: URL): Boolean =
        url.protocol.equals("https", ignoreCase = true) &&
            url.userInfo == null &&
            (url.port == -1 || url.port == 443)
}

internal fun readBoundedUtf8(input: java.io.InputStream, maxCharacters: Int): String {
    require(maxCharacters > 0)
    return input.bufferedReader(Charsets.UTF_8).use { reader ->
        val result = StringBuilder(minOf(maxCharacters, 8_192))
        val buffer = CharArray(8_192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            if (count > maxCharacters - result.length) throw IOException("GitHub response is too large")
            result.append(buffer, 0, count)
        }
        result.toString()
    }
}
