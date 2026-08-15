package com.armsone.nasfinder.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.armsone.nasfinder.NasFinderApplication
import com.armsone.nasfinder.model.AppTheme
import com.armsone.nasfinder.model.BrowserPreferences
import com.armsone.nasfinder.model.BrowserFavorite
import com.armsone.nasfinder.model.BrowserUrlPolicy
import com.armsone.nasfinder.model.CloudOAuthProvider
import com.armsone.nasfinder.model.OAuthSecurityPolicy
import com.armsone.nasfinder.model.RemoteConnection
import com.armsone.nasfinder.model.RemoteFavorite
import com.armsone.nasfinder.model.RemoteFileItem
import com.armsone.nasfinder.model.sortedWith
import com.armsone.nasfinder.data.DownloadCache
import com.armsone.nasfinder.data.RemoteThumbnailRepository
import com.armsone.nasfinder.data.RemoteThumbnailCacheStatistics
import com.armsone.nasfinder.data.RemoteThumbnailTrafficSnapshot
import com.armsone.nasfinder.data.SuperThumbnailWorkController
import com.armsone.nasfinder.data.SuperThumbnailWorkSnapshot
import com.armsone.nasfinder.data.SuperThumbnailWorkStatus
import com.armsone.nasfinder.data.SuperThumbnailSessionReport
import com.armsone.nasfinder.data.SuperThumbnailDataController
import com.armsone.nasfinder.data.SuperThumbnailCacheResetStatus
import com.armsone.nasfinder.data.SuperThumbnailVaultOptions
import com.armsone.nasfinder.data.SuperThumbnailVaultTiming
import com.armsone.nasfinder.data.ScreenAwakeMode
import com.armsone.nasfinder.network.RemoteFileService
import com.armsone.nasfinder.network.RemoteFileServiceFactory
import com.armsone.nasfinder.network.SftpHostKeyTrustRequired
import com.armsone.nasfinder.platform.NasFinderShareIntentFactory
import com.armsone.nasfinder.platform.NasFinderAppWidgetProvider
import com.armsone.nasfinder.platform.InboxBatchContracts
import com.armsone.nasfinder.platform.InboxUploadOutcome
import com.armsone.nasfinder.platform.AppIconChangeResult
import com.armsone.nasfinder.platform.LauncherIconVariant
import com.armsone.nasfinder.platform.OAuthCallbackActivity
import com.armsone.nasfinder.platform.OAuthCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.json.JSONObject

data class AppState(
    val connections: List<RemoteConnection> = emptyList(),
    val preferredId: String? = null,
    val screen: Screen = Screen.Dashboard,
    val theme: AppTheme = AppTheme.SYSTEM,
    val launcherIcon: LauncherIconVariant = LauncherIconVariant.DEFAULT,
    val pendingLauncherIcon: LauncherIconVariant? = null,
    val isBusy: Boolean = false,
    val message: String? = null,
    val inboxErrorMessage: String? = null,
    val inboxFiles: List<InboxDisplayItem> = emptyList(),
    val download: RemoteDownloadState? = null,
    val remoteFavorites: List<RemoteFavorite> = emptyList(),
    val browserFavorites: List<BrowserFavorite> = emptyList(),
    val pendingTransfer: PendingRemoteTransfer? = null,
    val pendingInboxUpload: PendingInboxUpload? = null,
    val pendingLocalUpload: PendingLocalUpload? = null,
    val downloadCacheBytes: Long? = null,
    val appVersion: String = "",
    val remoteThumbnails: Map<String, Bitmap> = emptyMap(),
    val thumbnailGeneration: Long = 0L,
    val imagePreview: ImagePreviewState? = null,
    val superThumbnailConnectionId: String? = null,
    val superThumbnailPath: String? = null,
    val superThumbnailTitle: String? = null,
    val superThumbnailHistory: List<SuperThumbnailLocation> = emptyList(),
    val superThumbnailPicker: SuperThumbnailPickerState = SuperThumbnailPickerState(),
    val superThumbnailWorkLocation: SuperThumbnailLocation? = null,
    val superThumbnailWorkLocations: Map<String, SuperThumbnailLocation> = emptyMap(),
    val superThumbnailWork: SuperThumbnailWorkSnapshot? = null,
    val superThumbnailSessionReport: SuperThumbnailSessionReport? = null,
    val superThumbnailReportLocationId: String? = null,
    val superThumbnailVaultEnabled: Boolean = true,
    val superThumbnailVaultTiming: SuperThumbnailVaultTiming = SuperThumbnailVaultTiming.NOW,
    val isRemovingSuperThumbnailVault: Boolean = false,
    val superThumbnailVaultResultMessage: String? = null,
    val oauthClientIds: Map<CloudOAuthProvider, String> = emptyMap(),
    val oauthConnectedConnectionIds: Set<String> = emptySet(),
    val oauthPendingConnectionId: String? = null,
    val thumbnailTraffic: RemoteThumbnailTrafficSnapshot = RemoteThumbnailTrafficSnapshot(),
    val thumbnailCacheStatistics: RemoteThumbnailCacheStatistics? = null,
    val screenAwakeMode: ScreenAwakeMode = ScreenAwakeMode.AUTOMATIC,
)

data class SuperThumbnailLocation(
    val connectionId: String,
    val path: String,
    val title: String,
) {
    val id: String get() = "$connectionId\u0000$path"
}

data class SuperThumbnailPickerState(
    val connectionId: String? = null,
    val path: String? = null,
    val items: List<RemoteFileItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

data class InboxDisplayItem(
    val id: java.util.UUID,
    val originalFilename: String,
    val mimeType: String?,
    val byteCount: Long,
    val importedAt: java.time.Instant,
    val file: File,
)

enum class RemoteTransferAction { COPY, MOVE }

data class PendingRemoteTransfer(
    val connectionId: String,
    val items: List<RemoteFileItem>,
    val action: RemoteTransferAction,
)

data class PendingInboxUpload(
    val items: List<InboxDisplayItem>,
    val connectionId: String,
    val isUploading: Boolean = false,
)

data class PendingLocalUpload(
    val file: File,
    val filename: String,
    val mimeType: String?,
    val connectionId: String,
    val isUploading: Boolean = false,
)

enum class RemoteFileAction(val progressTitle: String) {
    PREVIEW("미리보기를 준비하는 중…"),
    SHARE("공유할 파일을 준비하는 중…"),
}

data class RemoteDownloadState(
    val filename: String,
    val action: RemoteFileAction,
    val completedBytes: Long = 0,
    val totalBytes: Long = 0,
) {
    val fraction: Float?
        get() = totalBytes.takeIf { it > 0 }
            ?.let { (completedBytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
}

data class ImagePreviewState(
    val connection: RemoteConnection,
    val images: List<RemoteFileItem>,
    val index: Int,
    val kind: BuiltInPreviewKind,
    val bitmap: Bitmap? = null,
    val cachedFile: File,
    val pdfPageIndex: Int = 0,
    val pdfPageCount: Int = 0,
)

enum class BuiltInPreviewKind { IMAGE, VIDEO, AUDIO, PDF }

sealed interface Screen {
    data object Dashboard : Screen
    data class AddConnection(val editing: RemoteConnection? = null) : Screen
    data class Browser(
        val connection: RemoteConnection,
        val path: String,
        val items: List<RemoteFileItem> = emptyList(),
        val preferences: BrowserPreferences = BrowserPreferences(),
    ) : Screen
    data object Inbox : Screen
    data object Settings : Screen
    data object ThumbnailCache : Screen
    data object SuperThumbnail : Screen
    data object SuperThumbnailFolderPicker : Screen
    data object SuperThumbnailProgress : Screen
    data object SuperThumbnailReport : Screen
    data object WebBrowser : Screen
    data object WebHard : Screen
}

class NasFinderViewModel(private val application: NasFinderApplication) : ViewModel() {
    private val repository = application.connections
    private val favoriteRepository = application.favorites
    private val settingsRepository = application.settings
    private val inboxStore = application.inbox
    private val downloadCache = DownloadCache(application)
    private val thumbnailRepository = RemoteThumbnailRepository(application)
    private val superThumbnailDataController = SuperThumbnailDataController(application)
    private var service: RemoteFileService? = null
    private var thumbnailGeneration = 0L
    private val thumbnailRequests = mutableSetOf<String>()
    private var previewRequestGeneration = 0L
    private var previewJob: Job? = null
    private var connectionOpenJob: Job? = null
    private var thumbnailTrafficObservation: Job? = null
    private var superThumbnailObservation: Job? = null
    private var superThumbnailStartJob: Job? = null
    private var superThumbnailPickerJob: Job? = null
    private var superThumbnailPickerService: RemoteFileService? = null
    private var superThumbnailPickerGeneration = 0
    private val superThumbnailPreferences =
        application.getSharedPreferences("super_thumbnail_ui", android.content.Context.MODE_PRIVATE)
    private val initialInboxLoad = runCatching { inboxFiles() }
    private val _state = MutableStateFlow(
        AppState(
            connections = repository.load(),
            preferredId = repository.preferredId(),
            theme = settingsRepository.theme(),
            launcherIcon = settingsRepository.icon(),
            screenAwakeMode = settingsRepository.screenAwakeMode(),
            superThumbnailHistory = loadSuperThumbnailHistory(),
            superThumbnailWorkLocations = loadSuperThumbnailWorkLocations(),
            superThumbnailVaultEnabled = superThumbnailPreferences.getBoolean(KEY_SUPER_THUMBNAIL_VAULT_ENABLED, true),
            superThumbnailVaultTiming = runCatching {
                SuperThumbnailVaultTiming.valueOf(
                    superThumbnailPreferences.getString(KEY_SUPER_THUMBNAIL_VAULT_TIMING, null).orEmpty()
                )
            }.getOrDefault(SuperThumbnailVaultTiming.NOW),
            inboxFiles = initialInboxLoad.getOrDefault(emptyList()),
            inboxErrorMessage = initialInboxLoad.exceptionOrNull()?.let {
                "받은 파일 목록을 읽지 못했습니다: ${it.message ?: "알 수 없는 오류"}"
            },
            remoteFavorites = favoriteRepository.remoteFavorites(),
            browserFavorites = favoriteRepository.browserFavorites(),
            oauthClientIds = CloudOAuthProvider.entries.mapNotNull { provider ->
                repository.oauthClients.clientId(provider)?.let { provider to it }
            }.toMap(),
            oauthConnectedConnectionIds = repository.load().mapNotNull { connection ->
                connection.id.takeIf { repository.oauthTokens.read(it) != null }
            }.toSet(),
            appVersion = runCatching {
                application.packageManager.getPackageInfo(application.packageName, 0).versionName
            }.getOrNull().orEmpty().ifBlank { "알 수 없음" },
        )
    )
    val state: StateFlow<AppState> = _state.asStateFlow()
    private var importedIntentSignature: String? = null

    init {
        runCatching(::cleanupOrphanedWebDownloads)
        refreshDownloadCacheSize()
        refreshThumbnailCacheStatistics()
        thumbnailTrafficObservation = viewModelScope.launch {
            thumbnailRepository.trafficSnapshot.collect { snapshot ->
                _state.update { it.copy(thumbnailTraffic = snapshot) }
            }
        }
        val savedId = superThumbnailPreferences.getString(KEY_SUPER_THUMBNAIL_CONNECTION, null)
        val selectedId = savedId?.takeIf { id -> _state.value.connections.any { it.id == id } }
            ?: _state.value.preferredId?.takeIf { id -> _state.value.connections.any { it.id == id } }
            ?: _state.value.connections.firstOrNull()?.id
        selectedId?.let { id ->
            val connection = _state.value.connections.first { it.id == id }
            val savedPath = superThumbnailPreferences.getString(KEY_SUPER_THUMBNAIL_PATH, null)
                ?.takeIf(String::isNotBlank) ?: connection.normalizedRootPath
            val savedTitle = superThumbnailPreferences.getString(KEY_SUPER_THUMBNAIL_TITLE, null)
                ?.takeIf(String::isNotBlank) ?: pathTitle(savedPath, connection.name)
            selectSuperThumbnailLocation(SuperThumbnailLocation(id, savedPath, savedTitle), recordHistory = false)
        }
        val workConnectionId = superThumbnailPreferences.getString(KEY_SUPER_THUMBNAIL_WORK_CONNECTION, null)
        val workPath = superThumbnailPreferences.getString(KEY_SUPER_THUMBNAIL_WORK_PATH, null)
        if (!workConnectionId.isNullOrBlank() && !workPath.isNullOrBlank() &&
            _state.value.connections.any { it.id == workConnectionId }
        ) {
            _state.update {
                val legacy = SuperThumbnailLocation(
                    workConnectionId,
                    workPath,
                    superThumbnailPreferences.getString(KEY_SUPER_THUMBNAIL_WORK_TITLE, null)
                        ?.takeIf(String::isNotBlank)
                        ?: pathTitle(workPath, "Super Thumbnail"),
                )
                it.copy(
                    superThumbnailWorkLocation = legacy,
                    superThumbnailWorkLocations = it.superThumbnailWorkLocations + (workConnectionId to legacy),
                )
            }
        }
        _state.value.connections.firstOrNull { it.id == _state.value.preferredId }?.let { connection ->
            openConnection(connection, connection.normalizedRootPath, fallbackToRoot = false)
        }
    }

    fun show(screen: Screen) {
        invalidateImagePreviewRequests()
        _state.update { it.copy(screen = screen, message = null) }
    }
    fun dismissMessage() { _state.update { it.copy(message = null) } }
    fun dismissInboxError() { _state.update { it.copy(inboxErrorMessage = null) } }

    fun refreshInbox() {
        runCatching { inboxFiles() }.fold(
            onSuccess = { files ->
                _state.update { it.copy(inboxFiles = files, inboxErrorMessage = null) }
            },
            onFailure = { error ->
                _state.update {
                    it.copy(
                        inboxErrorMessage = "받은 파일 목록을 읽지 못했습니다: ${error.message ?: "알 수 없는 오류"}",
                    )
                }
            },
        )
    }

    fun toggleBrowserFavorite(title: String, url: String) {
        val normalized = BrowserUrlPolicy.normalize(url) ?: run {
            _state.update { it.copy(message = "HTTP 또는 HTTPS 웹 주소만 즐겨찾기에 저장할 수 있습니다.") }
            return
        }
        val values = _state.value.browserFavorites.toMutableList()
        val existing = values.indexOfFirst { it.url == normalized }
        val added = existing < 0
        if (added) {
            values += BrowserFavorite(title = title.trim().ifBlank { normalized }, url = normalized)
        } else {
            values.removeAt(existing)
        }
        favoriteRepository.saveBrowser(values)
        _state.update {
            it.copy(
                browserFavorites = values,
                message = if (added) "즐겨찾기에 추가했습니다." else "즐겨찾기에서 제거했습니다.",
            )
        }
    }

    fun setBrowserHomepage(favorite: BrowserFavorite) {
        if (_state.value.browserFavorites.none { it.id == favorite.id }) {
            _state.update { it.copy(message = "홈페이지로 지정할 즐겨찾기를 찾을 수 없습니다.") }
            return
        }
        val values = _state.value.browserFavorites.map { it.copy(isHomepage = it.id == favorite.id) }
        favoriteRepository.saveBrowser(values)
        _state.update { it.copy(browserFavorites = values, message = "${favorite.title}을(를) 홈페이지로 지정했습니다.") }
    }

    fun editBrowserFavorite(favorite: BrowserFavorite, title: String, url: String) {
        val normalized = BrowserUrlPolicy.normalize(url) ?: run {
            _state.update { it.copy(message = "HTTP 또는 HTTPS 웹 주소를 입력해 주세요.") }
            return
        }
        val current = _state.value.browserFavorites
        if (current.any { it.id != favorite.id && it.url == normalized }) {
            _state.update { it.copy(message = "같은 주소가 이미 즐겨찾기에 있습니다.") }
            return
        }
        val values = current.map {
            if (it.id == favorite.id) it.copy(title = title.trim().ifBlank { normalized }, url = normalized) else it
        }
        if (values == current) {
            _state.update { it.copy(message = "편집할 즐겨찾기를 찾을 수 없습니다.") }
            return
        }
        favoriteRepository.saveBrowser(values)
        _state.update { it.copy(browserFavorites = values, message = "즐겨찾기를 수정했습니다.") }
    }

    fun deleteBrowserFavorite(favorite: BrowserFavorite) {
        val values = _state.value.browserFavorites.filterNot { it.id == favorite.id }
        if (values.size == _state.value.browserFavorites.size) {
            _state.update { it.copy(message = "삭제할 즐겨찾기를 찾을 수 없습니다.") }
            return
        }
        favoriteRepository.saveBrowser(values)
        _state.update { it.copy(browserFavorites = values, message = "즐겨찾기를 삭제했습니다.") }
    }

    fun saveConnection(connection: RemoteConnection, password: String) {
        val current = _state.value.connections.toMutableList()
        val index = current.indexOfFirst { it.id == connection.id }
        if (index >= 0) current[index] = connection else current += connection
        repository.save(current)
        if (password.isNotBlank()) repository.credentials.save(connection.id, password)
        _state.update { it.copy(connections = current, screen = Screen.Dashboard) }
        if (_state.value.superThumbnailConnectionId == null) selectSuperThumbnailConnection(connection.id)
        NasFinderAppWidgetProvider.updateAll(application)
    }

    fun removeConnection(connection: RemoteConnection) {
        val remaining = _state.value.connections.filterNot { it.id == connection.id }
        val remainingFavorites = _state.value.remoteFavorites.filterNot {
            it.connectionId == connection.id
        }
        repository.save(remaining); repository.credentials.delete(connection.id)
        favoriteRepository.saveRemote(remainingFavorites)
        if (_state.value.preferredId == connection.id) repository.setPreferred(null)
        _state.update {
            it.copy(
                connections = remaining,
                preferredId = it.preferredId.takeUnless { id -> id == connection.id },
                superThumbnailHistory = it.superThumbnailHistory.filterNot { entry -> entry.connectionId == connection.id },
                superThumbnailWorkLocation = it.superThumbnailWorkLocation?.takeUnless { entry -> entry.connectionId == connection.id },
                superThumbnailWorkLocations = it.superThumbnailWorkLocations - connection.id,
                remoteFavorites = remainingFavorites,
                oauthConnectedConnectionIds = it.oauthConnectedConnectionIds - connection.id,
                oauthPendingConnectionId = it.oauthPendingConnectionId.takeUnless { id -> id == connection.id },
            )
        }
        persistSuperThumbnailHistory(_state.value.superThumbnailHistory)
        persistSuperThumbnailWorkLocations(_state.value.superThumbnailWorkLocations)
        if (_state.value.superThumbnailConnectionId == connection.id) {
            val replacementId = remaining.firstOrNull { it.id == _state.value.preferredId }?.id
                ?: remaining.firstOrNull()?.id
            if (replacementId == null) {
                superThumbnailStartJob?.cancel()
                superThumbnailObservation?.cancel()
                superThumbnailPreferences.edit()
                    .remove(KEY_SUPER_THUMBNAIL_CONNECTION)
                    .remove(KEY_SUPER_THUMBNAIL_PATH)
                    .remove(KEY_SUPER_THUMBNAIL_TITLE)
                    .apply()
                _state.update { it.copy(superThumbnailConnectionId = null, superThumbnailWork = null) }
            } else {
                selectSuperThumbnailConnection(replacementId)
            }
        }
        NasFinderAppWidgetProvider.updateAll(application)
    }

    fun selectSuperThumbnailConnection(connectionId: String) {
        val connection = _state.value.connections.firstOrNull { it.id == connectionId } ?: return
        selectSuperThumbnailLocation(
            SuperThumbnailLocation(connection.id, connection.normalizedRootPath, connection.name)
        )
    }

    private fun selectSuperThumbnailLocation(
        location: SuperThumbnailLocation,
        recordHistory: Boolean = true,
    ) {
        if (_state.value.connections.none { it.id == location.connectionId }) return
        if (recordHistory) saveSuperThumbnailHistory(location)
        val sameConnection = _state.value.superThumbnailConnectionId == location.connectionId
        superThumbnailStartJob?.cancel()
        superThumbnailPreferences.edit()
            .putString(KEY_SUPER_THUMBNAIL_CONNECTION, location.connectionId)
            .putString(KEY_SUPER_THUMBNAIL_PATH, location.path)
            .putString(KEY_SUPER_THUMBNAIL_TITLE, location.title)
            .apply()
        _state.update {
            it.copy(
                superThumbnailConnectionId = location.connectionId,
                superThumbnailPath = location.path,
                superThumbnailTitle = location.title,
                superThumbnailWorkLocation = it.superThumbnailWorkLocations[location.connectionId],
                superThumbnailWork = if (sameConnection) it.superThumbnailWork else null,
            )
        }
        if (!sameConnection || superThumbnailObservation?.isActive != true) {
            observeSuperThumbnail(location.connectionId)
        }
        refreshSuperThumbnailSessionReport(location)
    }

    fun removeSuperThumbnailHistory(location: SuperThumbnailLocation) {
        val entries = _state.value.superThumbnailHistory.filterNot { it.id == location.id }
        persistSuperThumbnailHistory(entries)
        _state.update { it.copy(superThumbnailHistory = entries) }
    }

    fun setSuperThumbnailVaultEnabled(enabled: Boolean) {
        superThumbnailPreferences.edit().putBoolean(KEY_SUPER_THUMBNAIL_VAULT_ENABLED, enabled).apply()
        _state.update { it.copy(superThumbnailVaultEnabled = enabled) }
    }

    fun setSuperThumbnailVaultTiming(timing: SuperThumbnailVaultTiming) {
        superThumbnailPreferences.edit().putString(KEY_SUPER_THUMBNAIL_VAULT_TIMING, timing.name).apply()
        _state.update { it.copy(superThumbnailVaultTiming = timing) }
    }

    fun dismissSuperThumbnailVaultResult() {
        _state.update { it.copy(superThumbnailVaultResultMessage = null) }
    }

    fun resetSuperCache() {
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, message = null) }
            val result = runCatching { superThumbnailDataController.resetSuperCache() }.getOrElse { error ->
                _state.update {
                    it.copy(
                        isBusy = false,
                        message = error.message ?: "Super Cache를 초기화하지 못했습니다.",
                    )
                }
                return@launch
            }
            when (result.status) {
                SuperThumbnailCacheResetStatus.COMPLETED -> {
                    superThumbnailObservation?.cancel()
                    superThumbnailObservation = null
                    persistSuperThumbnailWorkLocations(emptyMap())
                    superThumbnailPreferences.edit()
                        .remove(KEY_SUPER_THUMBNAIL_WORK_CONNECTION)
                        .remove(KEY_SUPER_THUMBNAIL_WORK_PATH)
                        .remove(KEY_SUPER_THUMBNAIL_WORK_TITLE)
                        .remove(KEY_SUPER_THUMBNAIL_WORK_LOCATIONS)
                        .apply()
                    _state.update {
                        it.copy(
                            isBusy = false,
                            message = "Super Cache ${result.removedFileCount}개와 작업 기록 ${result.clearedSessionCount}개를 초기화했습니다.",
                            remoteThumbnails = emptyMap(),
                            thumbnailGeneration = it.thumbnailGeneration + 1,
                            superThumbnailWorkLocation = null,
                            superThumbnailWorkLocations = emptyMap(),
                            superThumbnailWork = null,
                            superThumbnailSessionReport = null,
                            superThumbnailReportLocationId = null,
                        )
                    }
                }
                SuperThumbnailCacheResetStatus.PARTIAL -> _state.update {
                    it.copy(
                        isBusy = false,
                        message = buildString {
                            append("Super Cache 일부만 초기화했습니다.")
                            if (result.errors.isNotEmpty()) append(" ${result.errors.joinToString(" ")}")
                        },
                    )
                }
                SuperThumbnailCacheResetStatus.BLOCKED_RUNNING_WORK -> _state.update {
                    it.copy(isBusy = false, message = "실행 중인 Super Thumbnail 작업이 있어 초기화할 수 없습니다.")
                }
                SuperThumbnailCacheResetStatus.BLOCKED_RESET_IN_PROGRESS -> _state.update {
                    it.copy(isBusy = false, message = "Super Cache를 이미 초기화하고 있습니다.")
                }
                SuperThumbnailCacheResetStatus.BLOCKED_WORK_STATE_UNKNOWN -> _state.update {
                    it.copy(isBusy = false, message = "작업 상태를 확인하지 못해 Super Cache를 초기화하지 않았습니다.")
                }
                SuperThumbnailCacheResetStatus.AVAILABLE -> _state.update {
                    it.copy(isBusy = false, message = "Super Cache 초기화 결과를 확인하지 못했습니다.")
                }
            }
        }
    }

    fun startSuperThumbnail(
        allowsConstrainedRun: Boolean = false,
        resumeExisting: Boolean = false,
    ) {
        val connectionId = _state.value.superThumbnailConnectionId ?: run {
            _state.update { it.copy(message = "Super Thumbnail을 만들 연결을 선택해 주세요.") }
            return
        }
        val connection = _state.value.connections.firstOrNull { it.id == connectionId } ?: return
        val path = _state.value.superThumbnailPath?.takeIf(String::isNotBlank)
            ?: connection.normalizedRootPath
        val location = SuperThumbnailLocation(
            connectionId,
            path,
            _state.value.superThumbnailTitle?.takeIf(String::isNotBlank)
                ?: pathTitle(path, connection.name),
        )
        val shouldUseVault = _state.value.superThumbnailVaultEnabled ||
            (_state.value.superThumbnailSessionReport?.vaultFolders?.isNotEmpty() == true &&
                _state.value.superThumbnailReportLocationId == location.id)
        if (shouldUseVault != _state.value.superThumbnailVaultEnabled) {
            setSuperThumbnailVaultEnabled(shouldUseVault)
        }
        val status = _state.value.superThumbnailWork?.status
        if (superThumbnailStartJob?.isActive == true || status == SuperThumbnailWorkStatus.WAITING || status == SuperThumbnailWorkStatus.RUNNING) return
        saveSuperThumbnailHistory(location)
        val workLocations = _state.value.superThumbnailWorkLocations + (connectionId to location)
        persistSuperThumbnailWorkLocations(workLocations)
        superThumbnailPreferences.edit()
            .putString(KEY_SUPER_THUMBNAIL_WORK_CONNECTION, location.connectionId)
            .putString(KEY_SUPER_THUMBNAIL_WORK_PATH, location.path)
            .putString(KEY_SUPER_THUMBNAIL_WORK_TITLE, location.title)
            .apply()
        _state.update {
            it.copy(
                screen = Screen.SuperThumbnailProgress,
                superThumbnailWorkLocation = location,
                superThumbnailWorkLocations = workLocations,
                superThumbnailWork = emptySuperThumbnailSnapshot(SuperThumbnailWorkStatus.WAITING),
            )
        }
        superThumbnailStartJob = viewModelScope.launch {
            runCatching {
                val manager = WorkManager.getInstance(application)
                val existing = manager
                    .getWorkInfosForUniqueWorkFlow(SuperThumbnailWorkController.uniqueName(connectionId))
                    .first()
                    .firstOrNull { it.state.isActive }
                if (existing == null) {
                    superThumbnailDataController.enqueue(
                        connectionId = connectionId,
                        rootPath = path,
                        allowsConstrainedRun = allowsConstrainedRun,
                        vaultOptions = SuperThumbnailVaultOptions(
                            enabled = shouldUseVault,
                            timing = _state.value.superThumbnailVaultTiming,
                        ),
                        resumeExisting = resumeExisting,
                    )
                } else if (_state.value.superThumbnailConnectionId == connectionId) {
                    _state.update { it.copy(superThumbnailWork = SuperThumbnailWorkController.snapshot(existing)) }
                }
            }
                .onFailure { error ->
                    if (error !is CancellationException) {
                        _state.update {
                            it.copy(
                                superThumbnailWork = emptySuperThumbnailSnapshot(SuperThumbnailWorkStatus.FAILED),
                                message = "Super Thumbnail 작업을 시작하지 못했습니다.",
                            )
                        }
                    }
                }
        }
    }

    fun cancelSuperThumbnail() {
        val connectionId = _state.value.superThumbnailConnectionId ?: return
        val status = _state.value.superThumbnailWork?.status
        val pendingStart = superThumbnailStartJob?.isActive == true
        if (!pendingStart && status != SuperThumbnailWorkStatus.WAITING && status != SuperThumbnailWorkStatus.RUNNING) return
        superThumbnailStartJob?.cancel()
        superThumbnailStartJob = null
        if (pendingStart) {
            _state.update { it.copy(superThumbnailWork = emptySuperThumbnailSnapshot(SuperThumbnailWorkStatus.CANCELLED)) }
        }
        runCatching { SuperThumbnailWorkController.cancel(application, connectionId) }
            .onFailure { _state.update { it.copy(message = "Super Thumbnail 작업을 취소하지 못했습니다.") } }
    }

    private fun emptySuperThumbnailSnapshot(status: SuperThumbnailWorkStatus) =
        SuperThumbnailWorkSnapshot(status, 0, 0, 0, 0L, false)

    private fun observeSuperThumbnail(connectionId: String) {
        superThumbnailObservation?.cancel()
        superThumbnailObservation = viewModelScope.launch {
            WorkManager.getInstance(application)
                .getWorkInfosForUniqueWorkFlow(SuperThumbnailWorkController.uniqueName(connectionId))
                .collect { values ->
                    if (_state.value.superThumbnailConnectionId != connectionId) return@collect
                    val info = values.firstOrNull { it.state.isActive }
                        ?: values.lastOrNull()
                    if (info == null) return@collect
                    _state.update {
                        it.copy(superThumbnailWork = SuperThumbnailWorkController.snapshot(info))
                    }
                    val current = _state.value
                    if (current.superThumbnailConnectionId == connectionId) {
                        current.superThumbnailPath?.let { path ->
                            refreshSuperThumbnailSessionReport(
                                SuperThumbnailLocation(
                                    connectionId,
                                    path,
                                    current.superThumbnailTitle ?: pathTitle(path, "Super Thumbnail"),
                                )
                            )
                        }
                    }
                }
        }
    }

    private val WorkInfo.State.isActive: Boolean
        get() = this == WorkInfo.State.ENQUEUED || this == WorkInfo.State.BLOCKED || this == WorkInfo.State.RUNNING

    private fun loadSuperThumbnailHistory(): List<SuperThumbnailLocation> {
        val validIds = repository.load().mapTo(hashSetOf()) { it.id }
        val raw = superThumbnailPreferences.getString(KEY_SUPER_THUMBNAIL_HISTORY, null).orEmpty()
        return runCatching {
            val json = org.json.JSONArray(raw)
            buildList<SuperThumbnailLocation> {
                for (index in 0 until json.length()) {
                    val value = json.opt(index)
                    val location = when (value) {
                        is org.json.JSONObject -> SuperThumbnailLocation(
                            connectionId = value.optString("connectionId"),
                            path = value.optString("path"),
                            title = value.optString("title"),
                        )
                        is String -> repository.load().firstOrNull { it.id == value }?.let {
                            SuperThumbnailLocation(it.id, it.normalizedRootPath, it.name)
                        }
                        else -> null
                    }
                    location?.takeIf { candidate ->
                        candidate.connectionId in validIds && candidate.path.isNotBlank() &&
                            none { saved: SuperThumbnailLocation ->
                                saved.connectionId == candidate.connectionId && saved.path == candidate.path
                            }
                    }?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveSuperThumbnailHistory(location: SuperThumbnailLocation) {
        val entries = (listOf(location) + _state.value.superThumbnailHistory)
            .distinctBy(SuperThumbnailLocation::id)
            .take(MAX_SUPER_THUMBNAIL_HISTORY)
        persistSuperThumbnailHistory(entries)
        _state.update { it.copy(superThumbnailHistory = entries) }
    }

    private fun persistSuperThumbnailHistory(entries: List<SuperThumbnailLocation>) {
        val json = org.json.JSONArray().apply {
            entries.forEach { entry ->
                put(
                    org.json.JSONObject()
                        .put("connectionId", entry.connectionId)
                        .put("path", entry.path)
                        .put("title", entry.title)
                )
            }
        }.toString()
        superThumbnailPreferences.edit().putString(KEY_SUPER_THUMBNAIL_HISTORY, json).apply()
    }

    private fun loadSuperThumbnailWorkLocations(): Map<String, SuperThumbnailLocation> {
        val validIds = repository.load().mapTo(hashSetOf()) { it.id }
        val raw = superThumbnailPreferences.getString(KEY_SUPER_THUMBNAIL_WORK_LOCATIONS, null).orEmpty()
        return runCatching {
            val json = org.json.JSONObject(raw)
            buildMap<String, SuperThumbnailLocation> {
                json.keys().forEach { connectionId ->
                    val value = json.optJSONObject(connectionId) ?: return@forEach
                    val path = value.optString("path")
                    if (connectionId in validIds && path.isNotBlank()) {
                        put(
                            connectionId,
                            SuperThumbnailLocation(
                                connectionId,
                                path,
                                value.optString("title").takeIf(String::isNotBlank)
                                    ?: pathTitle(path, "Super Thumbnail"),
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun persistSuperThumbnailWorkLocations(entries: Map<String, SuperThumbnailLocation>) {
        val json = org.json.JSONObject()
        entries.forEach { (connectionId, location) ->
            json.put(
                connectionId,
                org.json.JSONObject()
                    .put("path", location.path)
                    .put("title", location.title),
            )
        }
        superThumbnailPreferences.edit().putString(KEY_SUPER_THUMBNAIL_WORK_LOCATIONS, json.toString()).apply()
    }

    fun openSuperThumbnailFolderPicker() {
        closeSuperThumbnailPickerService()
        _state.update {
            it.copy(
                screen = Screen.SuperThumbnailFolderPicker,
                superThumbnailPicker = SuperThumbnailPickerState(),
            )
        }
    }

    fun openSuperThumbnailPickerConnection(connectionId: String) {
        val connection = _state.value.connections.firstOrNull { it.id == connectionId } ?: return
        superThumbnailPickerJob?.cancel()
        closeSuperThumbnailPickerService()
        val generation = ++superThumbnailPickerGeneration
        superThumbnailPickerJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    superThumbnailPicker = SuperThumbnailPickerState(
                        connectionId = connection.id,
                        path = connection.normalizedRootPath,
                        isLoading = true,
                    )
                )
            }
            var candidate: RemoteFileService? = null
            runCatching {
                RemoteFileServiceFactory.create(
                    connection,
                    repository.credentials.read(connection.id).orEmpty(),
                ).also {
                    candidate = it
                    if (generation == superThumbnailPickerGeneration) superThumbnailPickerService = it
                }.list(connection.normalizedRootPath)
            }.onSuccess { items ->
                if (generation != superThumbnailPickerGeneration) {
                    candidate?.close()
                    return@onSuccess
                }
                _state.update {
                    it.copy(
                        superThumbnailPicker = it.superThumbnailPicker.copy(
                            items = visibleSuperThumbnailFolders(items),
                            isLoading = false,
                            error = null,
                        )
                    )
                }
            }.onFailure { error ->
                candidate?.close()
                if (generation != superThumbnailPickerGeneration || error is CancellationException) return@onFailure
                if (superThumbnailPickerService === candidate) superThumbnailPickerService = null
                _state.update {
                    it.copy(
                        superThumbnailPicker = it.superThumbnailPicker.copy(
                            items = emptyList(),
                            isLoading = false,
                            error = error.message ?: "폴더를 열 수 없습니다.",
                        )
                    )
                }
            }
        }
    }

    fun openSuperThumbnailPickerFolder(item: RemoteFileItem) {
        val picker = _state.value.superThumbnailPicker
        if (!item.isDirectory || picker.items.none { it.id == item.id && it.path == item.path }) return
        loadSuperThumbnailPickerPath(item.path)
    }

    fun openSuperThumbnailPickerParent() {
        val picker = _state.value.superThumbnailPicker
        val connection = _state.value.connections.firstOrNull { it.id == picker.connectionId } ?: return
        val path = picker.path ?: return
        if (path == connection.normalizedRootPath) {
            closeSuperThumbnailPickerService()
            _state.update { it.copy(superThumbnailPicker = SuperThumbnailPickerState()) }
            return
        }
        val parent = parentRemotePath(path, connection.normalizedRootPath)
        loadSuperThumbnailPickerPath(parent)
    }

    fun selectCurrentSuperThumbnailFolder() {
        val picker = _state.value.superThumbnailPicker
        val connection = _state.value.connections.firstOrNull { it.id == picker.connectionId } ?: return
        val path = picker.path?.takeIf(String::isNotBlank) ?: return
        val location = SuperThumbnailLocation(connection.id, path, pathTitle(path, connection.name))
        closeSuperThumbnailPickerService()
        selectSuperThumbnailLocation(location)
        _state.update { it.copy(screen = Screen.SuperThumbnail, superThumbnailPicker = SuperThumbnailPickerState()) }
    }

    fun closeSuperThumbnailFolderPicker() {
        superThumbnailPickerJob?.cancel()
        closeSuperThumbnailPickerService()
        _state.update { it.copy(screen = Screen.SuperThumbnail, superThumbnailPicker = SuperThumbnailPickerState()) }
    }

    fun showSuperThumbnailReport(location: SuperThumbnailLocation) {
        selectSuperThumbnailLocation(location, recordHistory = false)
        _state.update { it.copy(screen = Screen.SuperThumbnailReport) }
        refreshSuperThumbnailSessionReport(location)
    }

    fun closeSuperThumbnailProgressOrReport() {
        _state.update { it.copy(screen = Screen.SuperThumbnail) }
    }

    fun refreshSelectedSuperThumbnailReport() {
        val state = _state.value
        val connectionId = state.superThumbnailConnectionId ?: return
        val path = state.superThumbnailPath ?: return
        refreshSuperThumbnailSessionReport(
            SuperThumbnailLocation(connectionId, path, state.superThumbnailTitle ?: pathTitle(path, "Super Thumbnail"))
        )
    }

    fun removeSelectedSuperThumbnailVault() {
        val state = _state.value
        val connection = state.connections.firstOrNull { it.id == state.superThumbnailConnectionId } ?: return
        val path = state.superThumbnailPath?.takeIf(String::isNotBlank) ?: return
        if (state.isRemovingSuperThumbnailVault) return
        viewModelScope.launch {
            _state.update { it.copy(isRemovingSuperThumbnailVault = true, superThumbnailVaultResultMessage = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    superThumbnailDataController.removeVaults(connection.id, path)
                }
            }
            result.onSuccess { removal ->
                val message = when {
                    removal.cancelled -> "NAS 보관본 삭제를 중단했습니다. 삭제된 파일 ${removal.removedFiles}개는 복구되지 않습니다."
                    removal.failures.isNotEmpty() -> "NAS 보관본 ${removal.removedFiles}개를 삭제했고 ${removal.failures.size}개는 삭제하지 못했습니다."
                    removal.removedFiles == 0 -> "삭제할 NAS 보관본이 없습니다."
                    else -> "NAS 보관본 ${removal.removedFiles}개를 삭제했습니다. 이 Android 기기의 캐시는 유지됩니다."
                }
                _state.update { it.copy(isRemovingSuperThumbnailVault = false, superThumbnailVaultResultMessage = message) }
                refreshSelectedSuperThumbnailReport()
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isRemovingSuperThumbnailVault = false,
                        superThumbnailVaultResultMessage = "NAS 보관본을 삭제하지 못했습니다. ${error.message ?: "연결을 확인해 주세요."}",
                    )
                }
            }
        }
    }

    private fun refreshSuperThumbnailSessionReport(location: SuperThumbnailLocation) {
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) {
                runCatching {
                    superThumbnailDataController.report(location.connectionId, location.path)
                }.getOrNull()
            }
            val current = _state.value
            if (current.superThumbnailConnectionId == location.connectionId && current.superThumbnailPath == location.path) {
                _state.update {
                    it.copy(
                        superThumbnailSessionReport = report,
                        superThumbnailReportLocationId = location.id,
                    )
                }
            }
        }
    }

    private fun loadSuperThumbnailPickerPath(path: String) {
        val picker = _state.value.superThumbnailPicker
        val activeService = superThumbnailPickerService ?: return
        superThumbnailPickerJob?.cancel()
        val generation = ++superThumbnailPickerGeneration
        superThumbnailPickerJob = viewModelScope.launch {
            _state.update { it.copy(superThumbnailPicker = picker.copy(path = path, items = emptyList(), isLoading = true, error = null)) }
            runCatching { activeService.list(path) }
                .onSuccess { items ->
                    if (generation != superThumbnailPickerGeneration) return@onSuccess
                    _state.update {
                        it.copy(
                            superThumbnailPicker = it.superThumbnailPicker.copy(
                                path = path,
                                items = visibleSuperThumbnailFolders(items),
                                isLoading = false,
                                error = null,
                            )
                        )
                    }
                }
                .onFailure { error ->
                    if (generation != superThumbnailPickerGeneration || error is CancellationException) return@onFailure
                    _state.update {
                        it.copy(
                            superThumbnailPicker = it.superThumbnailPicker.copy(
                                path = path,
                                items = emptyList(),
                                isLoading = false,
                                error = error.message ?: "폴더를 열 수 없습니다.",
                            )
                        )
                    }
                }
        }
    }

    private fun visibleSuperThumbnailFolders(items: List<RemoteFileItem>): List<RemoteFileItem> =
        items.filter { it.isDirectory && !it.name.startsWith('.') }
            .sortedBy { it.name.lowercase() }

    private fun closeSuperThumbnailPickerService() {
        superThumbnailPickerGeneration++
        superThumbnailPickerService?.close()
        superThumbnailPickerService = null
    }

    private fun parentRemotePath(path: String, root: String): String {
        val normalized = path.trimEnd('/')
        val candidate = normalized.substringBeforeLast('/', missingDelimiterValue = root)
            .ifBlank { "/" }
        return if (root == "/" || (root == "." && !candidate.startsWith('/')) ||
            candidate == root || candidate.startsWith("${root.trimEnd('/')}/")
        ) {
            candidate
        } else root
    }

    private fun pathTitle(path: String, fallback: String): String =
        path.trimEnd('/').substringAfterLast('/').takeIf(String::isNotBlank) ?: fallback

    fun moveConnection(connection: RemoteConnection, offset: Int) {
        val values = _state.value.connections.toMutableList()
        val from = values.indexOfFirst { it.id == connection.id }
        val to = (from + offset).coerceIn(0, values.lastIndex)
        if (from < 0 || from == to) return
        values.add(to, values.removeAt(from)); repository.save(values)
        _state.update { it.copy(connections = values) }
    }

    fun setPreferred(connection: RemoteConnection?) {
        repository.setPreferred(connection?.id)
        _state.update { it.copy(preferredId = connection?.id) }
        NasFinderAppWidgetProvider.updateAll(application)
    }

    fun testConnection(
        connection: RemoteConnection,
        password: String,
        synologyOtp: String = "",
        onTrustRequired: (SftpHostKeyTrustRequired) -> Unit = {},
        onSuccess: () -> Unit = {},
    ) {
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, message = null) }
            runCatching {
                val credential = password.ifBlank { repository.credentials.read(connection.id).orEmpty() }
                val requestCredential = if (connection.kind == com.armsone.nasfinder.model.ConnectionKind.SYNOLOGY && synologyOtp.isNotBlank()) {
                    require(synologyOtp.length in 6..8 && synologyOtp.all(Char::isDigit)) {
                        "OTP는 6~8자리 숫자로 입력해 주세요."
                    }
                    JSONObject()
                        .put("_nasfinder", "synology-v1")
                        .put("password", credential)
                        .put("otp", synologyOtp)
                        .toString()
                } else {
                    credential
                }
                RemoteFileServiceFactory.create(connection, requestCredential).use { it.testConnection() }
            }.onSuccess {
                _state.update { it.copy(isBusy = false, message = "연결에 성공했습니다.") }; onSuccess()
            }.onFailure { error ->
                if (error is SftpHostKeyTrustRequired) {
                    _state.update { it.copy(isBusy = false, message = null) }
                    onTrustRequired(error)
                } else {
                    _state.update { it.copy(isBusy = false, message = error.message ?: "연결하지 못했습니다.") }
                }
            }
        }
    }

    fun saveOAuthClientId(kind: com.armsone.nasfinder.model.ConnectionKind, clientId: String) {
        val provider = CloudOAuthProvider.from(kind) ?: return
        runCatching {
            val normalized = OAuthSecurityPolicy.requireValidClientId(clientId)
            repository.oauthClients.setClientId(provider, normalized)
            _state.update {
                it.copy(
                    oauthClientIds = it.oauthClientIds + (provider to normalized),
                    message = "${kind.title} client ID를 저장했습니다.",
                )
            }
        }.onFailure {
            _state.update { state -> state.copy(message = "client ID를 확인해 주세요. 줄바꿈이나 빈 값은 사용할 수 없습니다.") }
        }
    }

    fun beginOAuthLogin(connection: RemoteConnection, clientId: String) {
        val provider = CloudOAuthProvider.from(connection.kind) ?: return
        runCatching {
            val normalized = OAuthSecurityPolicy.requireValidClientId(clientId)
            repository.oauthClients.setClientId(provider, normalized)
            val connections = _state.value.connections.toMutableList().also { values ->
                val index = values.indexOfFirst { it.id == connection.id }
                if (index >= 0) values[index] = connection else values += connection
            }
            repository.save(connections)
            val authorizationUrl = OAuthCoordinator(application).begin(provider, connection.id)
                ?: error("OAuth 로그인을 시작할 설정이 없습니다.")
            application.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(authorizationUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            _state.update {
                it.copy(
                    connections = connections,
                    screen = Screen.AddConnection(connection),
                    oauthClientIds = it.oauthClientIds + (provider to normalized),
                    oauthPendingConnectionId = connection.id,
                    message = null,
                )
            }
        }.onFailure { error ->
            _state.update {
                it.copy(
                    oauthPendingConnectionId = null,
                    message = when (error) {
                        is ActivityNotFoundException -> "로그인 브라우저를 열 수 없습니다. 브라우저 앱을 확인해 주세요."
                        else -> "OAuth 로그인을 시작하지 못했습니다. client ID와 연결 이름을 확인해 주세요."
                    },
                )
            }
        }
    }

    fun cancelOAuthLogin() {
        _state.update {
            it.copy(
                oauthPendingConnectionId = null,
                message = "OAuth 로그인 대기를 취소했습니다. 저장된 토큰은 변경하지 않았습니다.",
            )
        }
    }

    fun deleteOAuthToken(connection: RemoteConnection) {
        repository.oauthTokens.delete(connection.id)
        _state.update {
            it.copy(
                oauthConnectedConnectionIds = it.oauthConnectedConnectionIds - connection.id,
                oauthPendingConnectionId = null,
                message = "${connection.kind.title} 로그인 토큰을 삭제했습니다.",
            )
        }
    }

    fun openConnection(
        connection: RemoteConnection,
        path: String = connection.normalizedRootPath,
        fallbackToRoot: Boolean = false,
    ) {
        invalidateImagePreviewRequests()
        connectionOpenJob?.cancel()
        val generation = ++thumbnailGeneration
        thumbnailRequests.clear()
        _state.update { it.copy(remoteThumbnails = emptyMap(), thumbnailGeneration = generation) }
        connectionOpenJob = viewModelScope.launch {
            _state.update { it.copy(isBusy = true, message = null) }
            runCatching {
                service?.close()
                service = RemoteFileServiceFactory.create(connection, repository.credentials.read(connection.id).orEmpty())
                service!!.list(path)
            }.onSuccess { items ->
                if (generation != thumbnailGeneration) return@onSuccess
                repository.setLastPath(connection.id, path)
                val preferences = repository.browserPreferences()
                _state.update { it.copy(isBusy = false, screen = Screen.Browser(connection, path, items.sortedWith(preferences), preferences)) }
            }.onFailure { error ->
                if (generation != thumbnailGeneration) return@onFailure
                if (fallbackToRoot && path != connection.normalizedRootPath) {
                    repository.setLastPath(connection.id, connection.normalizedRootPath)
                    openConnection(connection, connection.normalizedRootPath)
                    return@onFailure
                }
                _state.update { it.copy(isBusy = false, message = error.message ?: "폴더를 열 수 없습니다.") }
            }
        }
    }

    fun resumeConnection(connection: RemoteConnection) {
        openConnection(
            connection,
            repository.lastPath(connection.id) ?: connection.normalizedRootPath,
            fallbackToRoot = true,
        )
    }

    fun resumeLastLocation() {
        val connectionId = repository.lastConnectionId() ?: return
        val connection = _state.value.connections.firstOrNull { it.id == connectionId } ?: return
        val path = repository.lastPath(connectionId) ?: return
        openConnection(connection, path, fallbackToRoot = true)
    }

    fun loadRemoteThumbnail(item: RemoteFileItem) {
        if (item.isDirectory || (!item.isImage && !item.isVideo)) return
        val browser = _state.value.screen as? Screen.Browser ?: return
        if (browser.items.none { it.id == item.id && it.path == item.path }) return
        val activeService = service ?: return
        val generation = thumbnailGeneration
        val requestKey = "$generation\u0000${browser.connection.id}\u0000${item.id}\u0000${item.path}\u0000${item.size}\u0000${item.modifiedAt}"
        if (!thumbnailRequests.add(requestKey)) return
        val connectionId = browser.connection.id
        val path = browser.path
        viewModelScope.launch {
            val bitmap = thumbnailRepository.load(browser.connection, item, activeService) ?: return@launch
            val current = _state.value.screen as? Screen.Browser ?: return@launch
            if (generation != thumbnailGeneration || current.connection.id != connectionId || current.path != path ||
                current.items.none { it.id == item.id && it.path == item.path }
            ) return@launch
            _state.update { state ->
                if (generation != state.thumbnailGeneration) state
                else state.copy(remoteThumbnails = state.remoteThumbnails + (item.id to bitmap))
            }
        }
    }

    fun refreshBrowser() {
        val browser = _state.value.screen as? Screen.Browser ?: return
        viewModelScope.launch {
            if (!thumbnailRepository.resetTrafficBudget()) {
                _state.update { it.copy(message = "썸네일 요청이 진행 중이라 새로고침하지 않았습니다. 완료된 뒤 다시 시도해 주세요.") }
                return@launch
            }
            openConnection(browser.connection, browser.path)
        }
    }

    fun openItem(item: RemoteFileItem) {
        val browser = _state.value.screen as? Screen.Browser ?: return
        if (item.isDirectory) openConnection(browser.connection, item.path)
        else prepareRemoteFile(item, RemoteFileAction.PREVIEW)
    }

    fun closeImagePreview() {
        invalidateImagePreviewRequests()
    }

    private fun invalidateImagePreviewRequests() {
        val clearsPreviewDownload = previewJob?.isActive == true || _state.value.imagePreview != null
        previewRequestGeneration++
        previewJob?.cancel()
        previewJob = null
        _state.update {
            it.copy(
                imagePreview = null,
                download = if (clearsPreviewDownload) null else it.download,
            )
        }
    }

    fun moveImagePreview(offset: Int) {
        val preview = _state.value.imagePreview ?: return
        if (preview.kind != BuiltInPreviewKind.IMAGE) return
        val targetIndex = (preview.index + offset).coerceIn(0, preview.images.lastIndex)
        if (targetIndex == preview.index) return
        prepareRemoteFile(
            item = preview.images[targetIndex],
            action = RemoteFileAction.PREVIEW,
            connection = preview.connection,
            imageSequence = preview.images,
        )
    }

    fun shareImagePreview() {
        val preview = _state.value.imagePreview ?: return
        runCatching {
            launchPreparedFile(preview.images[preview.index], preview.cachedFile, RemoteFileAction.SHARE)
        }.onFailure { error ->
            _state.update { it.copy(message = error.message ?: "이미지를 공유할 수 없습니다.") }
        }
    }

    fun movePdfPreview(offset: Int) {
        val preview = _state.value.imagePreview ?: return
        if (preview.kind != BuiltInPreviewKind.PDF || preview.pdfPageCount <= 0) return
        val target = (preview.pdfPageIndex + offset).coerceIn(0, preview.pdfPageCount - 1)
        if (target == preview.pdfPageIndex) return
        val requestGeneration = ++previewRequestGeneration
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val rendered = withContext(Dispatchers.IO) { renderPdfPage(preview.cachedFile, target) }
            if (requestGeneration != previewRequestGeneration) return@launch
            if (rendered == null) {
                fallbackPreviewToExternal()
            } else {
                _state.update {
                    it.copy(imagePreview = preview.copy(bitmap = rendered.first, pdfPageIndex = target, pdfPageCount = rendered.second))
                }
            }
        }
    }

    fun fallbackPreviewToExternal() {
        val preview = _state.value.imagePreview ?: return
        val item = preview.images[preview.index]
        val file = preview.cachedFile
        invalidateImagePreviewRequests()
        runCatching { launchPreparedFile(item, file, RemoteFileAction.PREVIEW) }
            .onFailure { error -> _state.update { it.copy(message = error.message ?: "파일을 재생하거나 열 수 없습니다.") } }
    }

    fun toggleFavorite(item: RemoteFileItem) {
        val browser = _state.value.screen as? Screen.Browser ?: return
        val favorites = favoriteRepository.toggleRemote(
            RemoteFavorite(
                connectionId = browser.connection.id,
                path = item.path,
                name = item.name,
                isDirectory = item.isDirectory,
            )
        )
        _state.update { it.copy(remoteFavorites = favorites) }
    }

    fun openFavorite(favorite: RemoteFavorite) {
        val connection = _state.value.connections.firstOrNull {
            it.id == favorite.connectionId
        }
        if (connection == null) {
            _state.update {
                it.copy(message = "이 즐겨찾기의 네트워크 연결을 찾을 수 없습니다.")
            }
            return
        }
        if (favorite.isDirectory) {
            openConnection(connection, favorite.path)
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, message = null) }
            runCatching {
                service?.close()
                RemoteFileServiceFactory.create(
                    connection,
                    repository.credentials.read(connection.id).orEmpty(),
                ).also { service = it }
            }.onSuccess {
                _state.update { it.copy(isBusy = false) }
                prepareRemoteFile(
                    item = RemoteFileItem(
                        id = favorite.path,
                        name = favorite.name,
                        path = favorite.path,
                        isDirectory = false,
                    ),
                    action = RemoteFileAction.PREVIEW,
                    connection = connection,
                )
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isBusy = false,
                        message = error.message ?: "즐겨찾기 파일을 열 수 없습니다.",
                    )
                }
            }
        }
    }

    fun shareItem(item: RemoteFileItem) {
        if (!item.isDirectory) prepareRemoteFile(item, RemoteFileAction.SHARE)
    }

    fun createFolder(name: String) {
        val browser = _state.value.screen as? Screen.Browser ?: return
        mutateBrowser("폴더를 만들었습니다.") { it.createFolder(browser.path, name.trim()) }
    }

    fun renameItem(item: RemoteFileItem, newName: String) {
        mutateBrowser("이름을 변경했습니다.") { it.rename(item, newName.trim()) }
    }

    fun deleteItem(item: RemoteFileItem) {
        mutateBrowser(if (item.isDirectory) "폴더를 삭제했습니다." else "파일을 삭제했습니다.") {
            it.delete(listOf(item))
        }
    }

    fun beginTransfer(item: RemoteFileItem, action: RemoteTransferAction) {
        beginTransfer(listOf(item), action)
    }

    fun beginTransfer(items: List<RemoteFileItem>, action: RemoteTransferAction) {
        val browser = _state.value.screen as? Screen.Browser ?: return
        if (items.isEmpty()) return
        _state.update {
            it.copy(
                pendingTransfer = PendingRemoteTransfer(browser.connection.id, items.distinctBy(RemoteFileItem::id), action),
                pendingInboxUpload = null,
                message = null,
            )
        }
    }

    fun deleteItems(items: List<RemoteFileItem>) {
        if (items.isEmpty()) return
        mutateBrowser("${items.size}개 항목을 삭제했습니다.") { it.delete(items.distinctBy(RemoteFileItem::id)) }
    }

    fun shareItems(items: List<RemoteFileItem>) {
        val browser = _state.value.screen as? Screen.Browser ?: return
        val files = items.filterNot(RemoteFileItem::isDirectory).distinctBy(RemoteFileItem::id)
        if (files.isEmpty() || files.size != items.size) {
            _state.update { it.copy(message = "공유는 파일만 선택할 수 있습니다.") }
            return
        }
        val activeService = service ?: run {
            _state.update { it.copy(message = "원격 연결을 다시 연 뒤 시도해 주세요.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, message = null) }
            val prepared = mutableListOf<File>()
            val result = runCatching {
                for (item in files) {
                    prepared += downloadCache.resolve(
                        connectionId = browser.connection.id,
                        item = item,
                        progress = { _, _ -> },
                        download = { destination, progress -> activeService.download(item, destination, progress) },
                    )
                }
                val shareIntent = NasFinderShareIntentFactory.createMultiple(application, prepared)
                application.startActivity(
                    Intent.createChooser(shareIntent, "원격 파일 공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            _state.update { it.copy(isBusy = false, message = result.exceptionOrNull()?.message) }
        }
    }

    fun cancelTransfer() {
        _state.update { it.copy(pendingTransfer = null) }
    }

    fun beginInboxUpload(ids: Iterable<UUID>, connection: RemoteConnection) {
        if (_state.value.connections.none { it.id == connection.id }) {
            _state.update { it.copy(message = "선택한 저장 연결을 찾을 수 없습니다.") }
            return
        }
        val items = runCatching { selectedInboxItems(ids) }.getOrElse { error ->
            _state.update { it.copy(message = error.message ?: "선택한 파일이 올바르지 않습니다.") }
            return
        }
        if (items.isEmpty() || items.any { !it.file.isFile }) {
            _state.update { it.copy(message = "보낼 받은 파일을 찾을 수 없습니다.") }
            return
        }
        _state.update {
            it.copy(
                pendingInboxUpload = PendingInboxUpload(items, connection.id),
                pendingTransfer = null,
                message = null,
            )
        }
        openConnection(connection)
    }

    suspend fun importWebDownloadToInbox(file: File, filename: String, mimeType: String?): Boolean =
        importWebDownload(file, filename, mimeType, connection = null)

    suspend fun importWebDownloadToNas(
        file: File,
        filename: String,
        mimeType: String?,
        connection: RemoteConnection,
    ): Boolean {
        if (_state.value.connections.none { it.id == connection.id }) {
            _state.update { it.copy(message = "선택한 저장 연결을 찾을 수 없습니다.") }
            return false
        }
        val valid = withContext(Dispatchers.IO) { runCatching { validateWebDownload(file) }.isSuccess }
        if (!valid) {
            _state.update { it.copy(message = "웹 다운로드 임시 파일이 올바르지 않습니다.") }
            return false
        }
        _state.update {
            it.copy(
                pendingLocalUpload = PendingLocalUpload(file, filename, mimeType, connection.id),
                pendingInboxUpload = null,
                pendingTransfer = null,
                message = null,
            )
        }
        openConnection(connection)
        return true
    }

    private suspend fun importWebDownload(
        file: File,
        filename: String,
        mimeType: String?,
        connection: RemoteConnection?,
    ): Boolean {
        if (connection != null && _state.value.connections.none { it.id == connection.id }) {
            _state.update { it.copy(message = "선택한 저장 연결을 찾을 수 없습니다.") }
            return false
        }
        _state.update { it.copy(isBusy = true, message = null) }
        val imported = withContext(Dispatchers.IO) {
            runCatching {
                val source = validateWebDownload(file)
                source.inputStream().buffered().use { input -> inboxStore.import(filename, mimeType, input) }
            }
        }
        return imported.fold(
            onSuccess = { record ->
                withContext(Dispatchers.IO) { cleanupOwnedWebDownload(file) }
                val files = inboxFiles()
                _state.update {
                    it.copy(
                        isBusy = false,
                        inboxFiles = files,
                        pendingLocalUpload = null,
                        screen = if (connection == null) Screen.Inbox else it.screen,
                        message = if (connection == null) "받은 파일에 저장했습니다." else null,
                    )
                }
                NasFinderAppWidgetProvider.updateAll(application)
                if (connection != null) beginInboxUpload(listOf(record.id), connection)
                true
            },
            onFailure = { failure ->
                _state.update {
                    it.copy(isBusy = false, message = failure.message ?: "웹 다운로드 파일을 저장하지 못했습니다.")
                }
                false
            },
        )
    }

    fun discardWebDownload(file: File) {
        val pending = _state.value.pendingLocalUpload
        if (pending != null && runCatching { pending.file.canonicalFile == file.canonicalFile }.getOrDefault(false)) {
            runCatching { cleanupOwnedWebDownload(pending.file) }
            _state.update { it.copy(pendingLocalUpload = null) }
        }
    }

    fun cancelLocalUploadDestination() {
        if (_state.value.pendingLocalUpload == null) return
        _state.update { it.copy(screen = Screen.WebBrowser, message = null) }
    }

    fun applyLocalUploadDestination() {
        val browser = _state.value.screen as? Screen.Browser ?: return
        val pending = _state.value.pendingLocalUpload ?: return
        if (pending.connectionId != browser.connection.id) {
            _state.update { it.copy(message = "선택한 연결과 업로드 대상 폴더의 연결이 다릅니다.") }
            return
        }
        val activeService = service ?: run {
            _state.update { it.copy(message = "원격 연결을 다시 연 뒤 시도해 주세요.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, message = null, pendingLocalUpload = pending.copy(isUploading = true)) }
            val result = runCatching {
                val source = withContext(Dispatchers.IO) { validateWebDownload(pending.file) }
                activeService.upload(browser.path, source)
            }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            if (result.isSuccess) withContext(Dispatchers.IO) { cleanupOwnedWebDownload(pending.file) }
            _state.update {
                it.copy(
                    isBusy = false,
                    pendingLocalUpload = if (result.isSuccess) null else pending.copy(isUploading = false),
                    screen = if (result.isSuccess) Screen.WebBrowser else it.screen,
                    message = if (result.isSuccess) "네트워크 위치에 저장했습니다."
                        else result.exceptionOrNull()?.message ?: "네트워크 위치에 저장하지 못했습니다.",
                )
            }
        }
    }

    private fun validateWebDownload(file: File): File {
        val cacheRoot = application.cacheDir.canonicalFile
        val requestedRoot = File(cacheRoot, "web-downloads")
        require(!java.nio.file.Files.isSymbolicLink(requestedRoot.toPath())) { "웹 다운로드 임시 폴더가 안전하지 않습니다." }
        val root = requestedRoot.canonicalFile
        require(root.parentFile == cacheRoot) { "웹 다운로드 임시 폴더가 안전하지 않습니다." }
        val source = file.canonicalFile
        val directory = requireNotNull(source.parentFile) { "웹 다운로드 임시 파일이 올바르지 않습니다." }
        require(source.isFile && directory.parentFile == root && runCatching { UUID.fromString(directory.name) }.isSuccess &&
            !java.nio.file.Files.isSymbolicLink(source.toPath()) && !java.nio.file.Files.isSymbolicLink(directory.toPath())) {
            "웹 다운로드 임시 파일이 올바르지 않습니다."
        }
        return source
    }

    private fun cleanupOwnedWebDownload(file: File) {
        val source = validateWebDownload(file)
        check(source.delete() || !source.exists()) { "웹 다운로드 임시 파일을 삭제하지 못했습니다." }
        val directory = requireNotNull(source.parentFile)
        directory.listFiles()?.forEach { sibling ->
            if (!java.nio.file.Files.isSymbolicLink(sibling.toPath()) && sibling.isFile) sibling.delete()
        }
        directory.delete()
    }

    private fun cleanupOrphanedWebDownloads() {
        val cacheRoot = application.cacheDir.canonicalFile
        val requestedRoot = File(cacheRoot, "web-downloads")
        if (java.nio.file.Files.isSymbolicLink(requestedRoot.toPath())) return
        val root = requestedRoot.canonicalFile
        if (!root.isDirectory || root.parentFile != cacheRoot) return
        root.listFiles()?.forEach { candidate ->
            if (!candidate.isDirectory || java.nio.file.Files.isSymbolicLink(candidate.toPath()) ||
                runCatching { UUID.fromString(candidate.name) }.isFailure || candidate.canonicalFile.parentFile != root
            ) return@forEach
            candidate.listFiles()?.forEach { child ->
                if (!java.nio.file.Files.isSymbolicLink(child.toPath()) && child.isFile) child.delete()
            }
            candidate.delete()
        }
    }

    fun cancelInboxUpload() {
        _state.update { it.copy(pendingInboxUpload = null, screen = Screen.Inbox, message = null) }
    }

    fun applyInboxUploadDestination() {
        val browser = _state.value.screen as? Screen.Browser ?: return
        val pending = _state.value.pendingInboxUpload ?: return
        if (pending.connectionId != browser.connection.id) {
            _state.update { it.copy(message = "선택한 연결과 업로드 대상 폴더의 연결이 다릅니다.") }
            return
        }
        val activeService = service
        if (activeService == null) {
            _state.update { it.copy(message = "원격 연결을 다시 연 뒤 시도해 주세요.") }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isBusy = true,
                    message = null,
                    pendingInboxUpload = pending.copy(isUploading = true),
                )
            }
            val outcomes = mutableListOf<InboxUploadOutcome>()
            for (item in pending.items) {
                var stagedDirectory: File? = null
                val uploaded = runCatching {
                    require(item.file.isFile) { "보낼 받은 파일을 찾을 수 없습니다." }
                    val stagedFile = withContext(Dispatchers.IO) {
                        val uploadsRoot = File(application.cacheDir, "inbox-uploads").canonicalFile
                        check(uploadsRoot.mkdirs() || uploadsRoot.isDirectory) { "업로드 준비 공간을 만들 수 없습니다." }
                        val directory = File(uploadsRoot, item.id.toString()).canonicalFile
                        check(directory.parentFile == uploadsRoot) { "안전하지 않은 업로드 준비 경로입니다." }
                        check(directory.mkdirs() || directory.isDirectory) { "업로드 준비 폴더를 만들 수 없습니다." }
                        stagedDirectory = directory
                        val file = File(directory, item.originalFilename).canonicalFile
                        check(file.parentFile == directory) { "안전하지 않은 받은 파일 이름입니다." }
                        item.file.inputStream().buffered().use { input ->
                            file.outputStream().buffered().use(input::copyTo)
                        }
                        file
                    }
                    activeService.upload(browser.path, stagedFile)
                }
                withContext(Dispatchers.IO) { runCatching { stagedDirectory?.deleteRecursively() } }
                uploaded.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                outcomes += uploaded.fold(
                    onSuccess = { InboxUploadOutcome(item.id, succeeded = true) },
                    onFailure = { error ->
                        InboxUploadOutcome(
                            item.id,
                            succeeded = false,
                            failureMessage = error.message?.takeIf(String::isNotBlank)
                                ?: "${item.originalFilename} 업로드 실패",
                        )
                    },
                )
            }
            val summary = InboxBatchContracts.summarizeSequential(outcomes)
            _state.update {
                it.copy(
                    isBusy = false,
                    pendingInboxUpload = null,
                    screen = Screen.Inbox,
                    inboxFiles = inboxFiles(),
                    message = summary.message,
                )
            }
        }
    }

    fun applyTransferDestination() {
        val browser = _state.value.screen as? Screen.Browser ?: return
        val pending = _state.value.pendingTransfer ?: return
        if (pending.connectionId != browser.connection.id) {
            _state.update { it.copy(message = "복사하거나 이동할 파일과 대상 폴더는 같은 연결에 있어야 합니다.") }
            return
        }
        val verb = if (pending.action == RemoteTransferAction.COPY) "복사" else "이동"
        mutateBrowser("${pending.items.size}개 항목을 ${verb}했습니다.", clearTransfer = true) { remote ->
            when (pending.action) {
                RemoteTransferAction.COPY -> remote.copy(pending.items, browser.path)
                RemoteTransferAction.MOVE -> remote.move(pending.items, browser.path)
            }
        }
    }

    fun uploadDocument(uri: Uri) {
        val browser = _state.value.screen as? Screen.Browser ?: return
        val activeService = service
        if (activeService == null) {
            _state.update { it.copy(message = "원격 연결을 다시 연 뒤 시도해 주세요.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, message = null) }
            var temporary: File? = null
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val directory = File(application.cacheDir, "remote-uploads").apply { mkdirs() }
                    val requestedName = queryName(uri).orEmpty()
                        .replace(Regex("[/\\\\\u0000-\u001F]"), "_")
                        .take(180)
                        .ifBlank { "업로드 파일" }
                    temporary = uniqueFile(directory, requestedName)
                    application.contentResolver.openInputStream(uri)?.use { input ->
                        temporary!!.outputStream().buffered().use(input::copyTo)
                    } ?: throw IllegalStateException("선택한 파일을 읽을 수 없습니다.")
                    activeService.upload(browser.path, temporary!!)
                }
            }
            temporary?.delete()
            finishBrowserMutation(browser, result, "파일을 업로드했습니다.", clearTransfer = false)
        }
    }

    private fun mutateBrowser(
        successMessage: String,
        clearTransfer: Boolean = false,
        operation: suspend (RemoteFileService) -> Unit,
    ) {
        val browser = _state.value.screen as? Screen.Browser ?: return
        val activeService = service
        if (activeService == null) {
            _state.update { it.copy(message = "원격 연결을 다시 연 뒤 시도해 주세요.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, message = null) }
            val result = runCatching { operation(activeService) }
            finishBrowserMutation(browser, result, successMessage, clearTransfer)
        }
    }

    private suspend fun finishBrowserMutation(
        browser: Screen.Browser,
        result: Result<Unit>,
        successMessage: String,
        clearTransfer: Boolean,
    ) {
        result.onFailure { error ->
            _state.update {
                it.copy(isBusy = false, message = error.message ?: "원격 파일 작업을 완료하지 못했습니다.")
            }
            return
        }
        val refreshed = runCatching { service?.list(browser.path) ?: error("원격 연결이 종료되었습니다.") }
        refreshed.onSuccess { items ->
            _state.update {
                it.copy(
                    isBusy = false,
                    screen = browser.copy(items = items.sortedWith(browser.preferences)),
                    pendingTransfer = if (clearTransfer) null else it.pendingTransfer,
                    message = successMessage,
                )
            }
        }.onFailure { error ->
            _state.update {
                it.copy(
                    isBusy = false,
                    pendingTransfer = if (clearTransfer) null else it.pendingTransfer,
                    message = "$successMessage 하지만 목록을 새로 고치지 못했습니다: ${error.message ?: "알 수 없는 오류"}",
                )
            }
        }
    }

    private fun prepareRemoteFile(
        item: RemoteFileItem,
        action: RemoteFileAction,
        connection: RemoteConnection? = null,
        imageSequence: List<RemoteFileItem>? = null,
    ) {
        val browser = _state.value.screen as? Screen.Browser
        val activeConnection = connection ?: browser?.connection ?: return
        val activeService = service
        if (activeService == null) {
            _state.update { it.copy(message = "원격 연결을 다시 연 뒤 시도해 주세요.") }
            return
        }
        if (_state.value.download != null) {
            _state.update { it.copy(message = "다른 파일을 준비하고 있습니다. 잠시 후 다시 시도해 주세요.") }
            return
        }

        val previewKind = item.builtInPreviewKind()
        val internalPreview = action == RemoteFileAction.PREVIEW && previewKind != null
        val previewImages = if (internalPreview && previewKind == BuiltInPreviewKind.IMAGE) {
            imageSequence ?: browser?.items?.filter { !it.isDirectory && it.isImage }.orEmpty()
                .ifEmpty { listOf(item) }
        } else if (internalPreview) listOf(item) else emptyList()
        val previewIndex = previewImages.indexOfFirst { it.id == item.id && it.path == item.path }
            .takeIf { it >= 0 } ?: 0
        val requestGeneration = if (internalPreview) {
            previewJob?.cancel()
            ++previewRequestGeneration
        } else 0L

        _state.update {
            it.copy(
                message = null,
                download = RemoteDownloadState(
                    filename = item.name,
                    action = action,
                    totalBytes = item.size.coerceAtLeast(0),
                ),
            )
        }
        val job = viewModelScope.launch {
            runCatching {
                downloadCache.resolve(
                    connectionId = activeConnection.id,
                    item = item,
                    progress = { completed, reportedTotal ->
                        val total = reportedTotal.takeIf { it > 0 }
                            ?: item.size.takeIf { it > 0 }
                            ?: 0L
                        _state.update { current ->
                            if (internalPreview && requestGeneration != previewRequestGeneration) return@update current
                            current.copy(
                                download = current.download?.copy(
                                    completedBytes = completed.coerceAtLeast(0),
                                    totalBytes = total,
                                )
                            )
                        }
                    },
                    download = { destination, progress ->
                        activeService.download(item, destination, progress)
                    },
                )
            }.onSuccess { file ->
                if (internalPreview) {
                    if (requestGeneration != previewRequestGeneration) return@onSuccess
                    val decoded = withContext(Dispatchers.IO) {
                        when (previewKind) {
                            BuiltInPreviewKind.IMAGE -> decodePreviewBitmap(file)?.let { PreviewDecode(it) }
                            BuiltInPreviewKind.PDF -> renderPdfPage(file, 0)?.let { PreviewDecode(it.first, it.second) }
                            BuiltInPreviewKind.AUDIO, BuiltInPreviewKind.VIDEO -> if (validateMedia(file)) PreviewDecode() else null
                            null -> null
                        }
                    }
                    if (requestGeneration != previewRequestGeneration) return@onSuccess
                    if (decoded != null) {
                        _state.update {
                            it.copy(
                                download = null,
                                imagePreview = ImagePreviewState(
                                    connection = activeConnection,
                                    images = previewImages,
                                    index = previewIndex,
                                    kind = previewKind!!,
                                    bitmap = decoded.bitmap,
                                    cachedFile = file,
                                    pdfPageCount = decoded.pageCount,
                                ),
                            )
                        }
                    } else {
                        _state.update { it.copy(download = null) }
                        runCatching { launchPreparedFile(item, file, action) }
                            .onFailure { error ->
                                _state.update { it.copy(message = error.message ?: "파일을 열 수 없습니다.") }
                            }
                    }
                } else {
                    _state.update { it.copy(download = null) }
                    runCatching { launchPreparedFile(item, file, action) }
                        .onFailure { error ->
                            _state.update { it.copy(message = error.message ?: "파일을 열 수 없습니다.") }
                        }
                }
            }.onFailure { error ->
                if (internalPreview && requestGeneration != previewRequestGeneration) return@onFailure
                _state.update {
                    it.copy(
                        download = null,
                        message = error.message ?: "${item.name}을(를) 내려받지 못했습니다.",
                    )
                }
            }
        }
        if (internalPreview) previewJob = job
    }

    private data class PreviewDecode(val bitmap: Bitmap? = null, val pageCount: Int = 0)

    private fun RemoteFileItem.builtInPreviewKind(): BuiltInPreviewKind? = when {
        isImage -> BuiltInPreviewKind.IMAGE
        isVideo -> BuiltInPreviewKind.VIDEO
        isPdf -> BuiltInPreviewKind.PDF
        extension in setOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "flac", "opus", "amr") -> BuiltInPreviewKind.AUDIO
        else -> null
    }

    private fun validateMedia(file: File): Boolean = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() != null
        } finally {
            retriever.release()
        }
    }.getOrDefault(false)

    private fun renderPdfPage(file: File, pageIndex: Int): Pair<Bitmap, Int>? = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (pageIndex !in 0 until renderer.pageCount) return@runCatching null
                renderer.openPage(pageIndex).use { page ->
                    val maxSide = 2048f
                    val ratio = minOf(maxSide / page.width, maxSide / page.height, 1f)
                    val bitmap = Bitmap.createBitmap(
                        (page.width * ratio).toInt().coerceAtLeast(1),
                        (page.height * ratio).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap to renderer.pageCount
                }
            }
        }
    }.getOrNull()

    private fun decodePreviewBitmap(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / sample > 4096 || bounds.outHeight / sample > 4096) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }.getOrNull()

    private fun launchPreparedFile(item: RemoteFileItem, file: File, action: RemoteFileAction) {
        when (action) {
            RemoteFileAction.SHARE -> {
                val sendIntent = NasFinderShareIntentFactory.create(application, listOf(file))
                application.startActivity(
                    Intent.createChooser(sendIntent, "${item.name} 공유")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            RemoteFileAction.PREVIEW -> {
                val uri = FileProvider.getUriForFile(
                    application,
                    "${application.packageName}.sharefiles",
                    file,
                )
                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType(item, file))
                    clipData = ClipData.newUri(application.contentResolver, file.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                if (viewIntent.resolveActivity(application.packageManager) == null) {
                    throw IllegalStateException("이 파일 형식을 열 수 있는 앱이 설치되어 있지 않습니다.")
                }
                try {
                    application.startActivity(
                        Intent.createChooser(viewIntent, "${item.name} 열기")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: ActivityNotFoundException) {
                    throw IllegalStateException("이 파일 형식을 열 수 있는 앱이 설치되어 있지 않습니다.")
                }
            }
        }
    }

    private fun mimeType(item: RemoteFileItem, file: File): String {
        item.mimeType?.takeIf { it.contains('/') }?.let { return it }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: when {
                item.isImage -> "image/*"
                item.isVideo -> "video/*"
                item.isPdf -> "application/pdf"
                else -> "application/octet-stream"
            }
    }

    fun updateBrowserPreferences(preferences: BrowserPreferences) {
        val browser = _state.value.screen as? Screen.Browser ?: return
        repository.setBrowserPreferences(preferences)
        _state.update { it.copy(screen = browser.copy(items = browser.items.sortedWith(preferences), preferences = preferences)) }
    }

    fun setTheme(theme: AppTheme) {
        when (val result = settingsRepository.setTheme(theme)) {
            is AppIconChangeResult.AlreadyApplied,
            is AppIconChangeResult.Applied -> _state.update { it.copy(theme = theme, launcherIcon = settingsRepository.icon(), message = null) }
            is AppIconChangeResult.RolledBack -> _state.update { it.copy(message = "앱 아이콘을 바꾸지 못해 이전 테마를 유지했습니다.") }
            is AppIconChangeResult.RollbackFailed -> _state.update { it.copy(message = "앱 아이콘 변경과 복구에 실패했습니다. 런처에서 아이콘을 확인해 주세요.") }
            is AppIconChangeResult.Unavailable -> _state.update { it.copy(message = "이 기기에서는 앱 아이콘을 변경할 수 없습니다.") }
            is AppIconChangeResult.PreferenceWriteFailed -> _state.update {
                it.copy(message = if (result.iconRollbackSucceeded) "테마를 저장하지 못해 이전 테마를 유지했습니다."
                else "테마 저장과 앱 아이콘 복구에 실패했습니다. 런처에서 아이콘을 확인해 주세요.")
            }
        }
    }

    fun setLauncherIcon(icon: LauncherIconVariant) {
        if (_state.value.pendingLauncherIcon != null || _state.value.launcherIcon == icon) return
        _state.update { it.copy(pendingLauncherIcon = icon, message = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { settingsRepository.setIcon(icon) }
            when (result) {
                is AppIconChangeResult.AlreadyApplied,
                is AppIconChangeResult.Applied -> _state.update {
                    it.copy(
                        launcherIcon = icon,
                        pendingLauncherIcon = null,
                        message = "앱 아이콘을 변경했습니다. 런처에 반영되기까지 잠시 걸릴 수 있습니다.",
                    )
                }
                is AppIconChangeResult.RolledBack -> _state.update {
                    it.copy(launcherIcon = result.restored, pendingLauncherIcon = null, message = "앱 아이콘을 바꾸지 못해 이전 아이콘으로 복구했습니다.")
                }
                is AppIconChangeResult.RollbackFailed -> _state.update { it.copy(pendingLauncherIcon = null, message = "앱 아이콘 변경과 복구에 실패했습니다. 런처에서 아이콘을 확인해 주세요.") }
                is AppIconChangeResult.Unavailable -> _state.update { it.copy(pendingLauncherIcon = null, message = "이 기기에서는 앱 아이콘을 변경할 수 없습니다.") }
                is AppIconChangeResult.PreferenceWriteFailed -> _state.update {
                    it.copy(
                        launcherIcon = settingsRepository.icon(),
                        pendingLauncherIcon = null,
                        message = if (result.iconRollbackSucceeded) "아이콘 설정을 저장하지 못해 이전 아이콘으로 복구했습니다."
                        else "아이콘 설정 저장과 복구에 실패했습니다. 런처에서 아이콘을 확인해 주세요.",
                    )
                }
            }
        }
    }

    fun setScreenAwakeMode(mode: ScreenAwakeMode) {
        settingsRepository.setScreenAwakeMode(mode)
        _state.update { it.copy(screenAwakeMode = mode) }
    }

    fun refreshDownloadCacheSize() {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { downloadCacheSize() }
            _state.update { it.copy(downloadCacheBytes = bytes) }
        }
    }

    fun refreshThumbnailCacheStatistics() {
        viewModelScope.launch {
            val statistics = withContext(Dispatchers.IO) { thumbnailRepository.cacheStatistics() }
            _state.update { it.copy(thumbnailCacheStatistics = statistics) }
        }
    }

    fun setThumbnailCacheLimit(bytes: Long) {
        viewModelScope.launch {
            val statistics = withContext(Dispatchers.IO) { thumbnailRepository.setAutomaticCacheLimit(bytes) }
            _state.update { it.copy(thumbnailCacheStatistics = statistics) }
        }
    }

    fun clearThumbnailCache() {
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, message = null) }
            runCatching { withContext(Dispatchers.IO) { thumbnailRepository.clearDiskCache() } }
                .onSuccess { statistics ->
                    _state.update { it.copy(isBusy = false, thumbnailCacheStatistics = statistics, message = "썸네일 캐시를 비웠습니다.") }
                }
                .onFailure { error ->
                    val statistics = withContext(Dispatchers.IO) { thumbnailRepository.cacheStatistics() }
                    _state.update {
                        it.copy(
                            isBusy = false,
                            thumbnailCacheStatistics = statistics,
                            message = error.message ?: "썸네일 캐시를 모두 비우지 못했습니다.",
                        )
                    }
                }
        }
    }

    fun clearDownloadCache() {
        if (_state.value.download != null) {
            _state.update { it.copy(message = "파일을 준비하는 동안에는 다운로드 캐시를 지울 수 없습니다.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, message = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    val root = downloadCacheDirectory()
                    root.listFiles().orEmpty().forEach { entry ->
                        check(entry.deleteRecursively()) { "캐시 항목을 삭제하지 못했습니다: ${entry.name}" }
                    }
                }
            }.onSuccess {
                _state.update { it.copy(isBusy = false, downloadCacheBytes = 0L, message = "다운로드 캐시를 모두 지웠습니다.") }
            }.onFailure { error ->
                val remaining = withContext(Dispatchers.IO) { downloadCacheSize() }
                _state.update {
                    it.copy(
                        isBusy = false,
                        downloadCacheBytes = remaining,
                        message = error.message ?: "다운로드 캐시를 모두 지우지 못했습니다.",
                    )
                }
            }
        }
    }

    private fun downloadCacheDirectory(): File {
        val cacheRoot = application.cacheDir.canonicalFile
        return File(cacheRoot, "shares").canonicalFile.also { directory ->
            check(directory.parentFile == cacheRoot) { "안전하지 않은 캐시 경로입니다." }
            check(directory.mkdirs() || directory.isDirectory) { "다운로드 캐시 경로를 만들 수 없습니다." }
        }
    }

    private fun downloadCacheSize(): Long = downloadCacheDirectory()
        .walkTopDown()
        .filter(File::isFile)
        .sumOf(File::length)

    fun handleEntryIntent(intent: Intent?) {
        val incoming = intent ?: return
        if (incoming.action == Intent.ACTION_VIEW || incoming.action == Intent.ACTION_SEND ||
            incoming.action == Intent.ACTION_SEND_MULTIPLE || incoming.hasExtra(OAuthCallbackActivity.EXTRA_OAUTH_SUCCEEDED)
        ) {
            connectionOpenJob?.cancel()
            connectionOpenJob = null
            thumbnailGeneration++
            _state.update { it.copy(isBusy = false) }
        }
        if (incoming.hasExtra(OAuthCallbackActivity.EXTRA_OAUTH_SUCCEEDED)) {
            val succeeded = incoming.getBooleanExtra(OAuthCallbackActivity.EXTRA_OAUTH_SUCCEEDED, false)
            val pendingConnectionId = _state.value.oauthPendingConnectionId
            val connectedIds = _state.value.connections.mapNotNull { connection ->
                connection.id.takeIf { repository.oauthTokens.read(it) != null }
            }.toSet()
            val oauthConnection = _state.value.connections.firstOrNull {
                it.id == pendingConnectionId && it.id in connectedIds
            } ?: _state.value.connections
                .filter { it.kind.oauth && it.id in connectedIds }
                .maxByOrNull(RemoteConnection::createdAt)
            _state.update {
                it.copy(
                    screen = if (succeeded && oauthConnection != null) Screen.AddConnection(oauthConnection) else it.screen,
                    oauthConnectedConnectionIds = connectedIds,
                    oauthPendingConnectionId = null,
                    message = if (succeeded) {
                        "브라우저 로그인이 완료되었습니다. 연결 확인 또는 저장을 진행해 주세요."
                    } else {
                        "OAuth 로그인이 취소되었거나 완료되지 않았습니다. 다시 시도해 주세요."
                    },
                )
            }
            return
        }
        when (val route = AppEntryRouteParser.parse(incoming.action, incoming.dataString)) {
            AppEntryRoute.Ignore -> importSharedIntent(incoming)
            is AppEntryRoute.Rejected -> {
                if (incoming.action == Intent.ACTION_VIEW) {
                    _state.update { it.copy(message = route.message) }
                }
            }
            is AppEntryRoute.Inbox -> openInboxRecord(route.id)
            AppEntryRoute.ImportUri -> importViewedUri(incoming)
        }
    }

    private fun openInboxRecord(id: UUID) {
        val files = inboxFiles()
        val selected = files.firstOrNull { it.id == id }
        _state.update {
            it.copy(
                screen = Screen.Inbox,
                inboxFiles = if (selected == null) files else listOf(selected) + files.filterNot { item -> item.id == id },
                message = null,
                inboxErrorMessage = if (selected == null) "요청한 받은 파일을 찾을 수 없습니다." else null,
            )
        }
    }

    private fun importViewedUri(intent: Intent) {
        val uri = intent.data ?: run {
            _state.update { it.copy(message = "가져올 파일 주소가 없습니다.") }
            return
        }
        val signature = "${intent.action}:$uri"
        if (signature == importedIntentSignature) return
        importedIntentSignature = signature
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = queryName(uri) ?: uri.lastPathSegment?.substringAfterLast('/')
                        ?.takeIf { it.isNotBlank() } ?: "받은 파일"
                    application.contentResolver.openInputStream(uri)?.use { input ->
                        inboxStore.import(name, application.contentResolver.getType(uri), input)
                    } ?: error("파일을 열 수 없습니다.")
                }
            }
            result.onSuccess { record ->
                val files = inboxFiles()
                val selected = files.firstOrNull { it.id == record.id }
                _state.update {
                    it.copy(
                        screen = Screen.Inbox,
                        inboxFiles = if (selected == null) files else listOf(selected) + files.filterNot { item -> item.id == record.id },
                        message = null,
                        inboxErrorMessage = null,
                    )
                }
                NasFinderAppWidgetProvider.updateAll(application)
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        screen = Screen.Inbox,
                        message = null,
                        inboxErrorMessage = error.message ?: "파일을 받은 파일로 가져오지 못했습니다.",
                    )
                }
            }
        }
    }

    fun importSharedIntent(intent: Intent?) {
        val sharedIntent = intent ?: return
        if (sharedIntent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return
        val signature = "${sharedIntent.action}:${sharedIntent.clipData}:${sharedIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)}"
        if (signature == importedIntentSignature) return
        importedIntentSignature = signature
        val uris = buildList {
            sharedIntent.clipData?.let { clip -> for (index in 0 until minOf(clip.itemCount, 50)) clip.getItemAt(index).uri?.let(::add) }
            if (isEmpty()) sharedIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(::add)
        }.distinct().take(50)
        if (uris.isEmpty()) return
        viewModelScope.launch {
            var count = 0
            var failed = 0
            uris.forEachIndexed { index, uri ->
                runCatching {
                    val name = queryName(uri) ?: "받은 파일 ${index + 1}"
                    application.contentResolver.openInputStream(uri)?.use { input ->
                        inboxStore.import(name, application.contentResolver.getType(uri), input)
                    } ?: error("파일을 열 수 없습니다")
                    count++
                }.onFailure { failed++ }
            }
            _state.update {
                it.copy(
                    screen = Screen.Inbox,
                    inboxFiles = inboxFiles(),
                    message = if (failed == 0) "${count}개 파일을 받았습니다." else null,
                    inboxErrorMessage = when {
                        failed == 0 -> null
                        count == 0 -> "${failed}개 파일을 가져오지 못했습니다."
                        else -> "${count}개 파일을 받았지만 ${failed}개는 가져오지 못했습니다."
                    },
                )
            }
            NasFinderAppWidgetProvider.updateAll(application)
        }
    }

    fun deleteInboxFile(item: InboxDisplayItem) {
        val deleted = runCatching { inboxStore.delete(item.id) }.getOrElse {
            _state.update { state ->
                state.copy(
                    message = null,
                    inboxErrorMessage = "${item.originalFilename}을(를) 삭제하지 못했습니다: ${it.message ?: "알 수 없는 오류"}",
                )
            }
            false
        }
        if (deleted) {
            _state.update { it.copy(inboxFiles = inboxFiles()) }
            NasFinderAppWidgetProvider.updateAll(application)
        } else if (_state.value.inboxErrorMessage == null) {
            _state.update {
                it.copy(
                    message = null,
                    inboxErrorMessage = "${item.originalFilename}을(를) 삭제하지 못했습니다.",
                )
            }
        }
    }

    fun previewInboxFile(item: InboxDisplayItem) {
        val remoteItem = RemoteFileItem(
            id = item.id.toString(),
            name = item.originalFilename,
            path = item.id.toString(),
            isDirectory = false,
            size = item.byteCount,
            modifiedAt = item.importedAt,
            mimeType = item.mimeType,
        )
        runCatching { launchPreparedFile(remoteItem, item.file, RemoteFileAction.PREVIEW) }
            .onFailure { error ->
                _state.update {
                    it.copy(
                        message = null,
                        inboxErrorMessage = error.message ?: "파일을 열 수 없습니다.",
                    )
                }
            }
    }

    fun deleteInboxFiles(ids: Iterable<UUID>) {
        val selected = runCatching { InboxBatchContracts.normalizeSelection(ids) }.getOrElse { error ->
            _state.update { it.copy(message = null, inboxErrorMessage = error.message ?: "선택한 파일이 올바르지 않습니다.") }
            return
        }
        if (selected.isEmpty()) return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                var deletedCount = 0
                var failedCount = 0
                selected.forEach { id ->
                    runCatching { inboxStore.delete(id) }
                        .onSuccess { if (it) deletedCount++ else failedCount++ }
                        .onFailure { failedCount++ }
                }
                Triple(deletedCount, failedCount, inboxFiles())
            }
            val (deleted, failed, files) = result
            _state.update {
                it.copy(
                    inboxFiles = files,
                    message = if (failed == 0) "${deleted}개 파일을 받은 파일에서 삭제했습니다." else null,
                    inboxErrorMessage = when {
                        failed == 0 -> null
                        deleted == 0 -> "${failed}개 파일을 삭제하지 못했습니다."
                        else -> "${deleted}개 파일을 삭제했고 ${failed}개는 삭제하지 못했습니다."
                    },
                )
            }
            if (deleted > 0) NasFinderAppWidgetProvider.updateAll(application)
        }
    }

    fun shareInboxFiles(ids: Iterable<UUID>) {
        val items = runCatching { selectedInboxItems(ids) }.getOrElse { error ->
            _state.update { it.copy(message = null, inboxErrorMessage = error.message ?: "선택한 파일이 올바르지 않습니다.") }
            return
        }
        if (items.isEmpty()) return
        runCatching {
            val sendIntent = NasFinderShareIntentFactory.createMultiple(application, items.map { it.file })
            application.startActivity(
                Intent.createChooser(sendIntent, "받은 파일 공유")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { error ->
            _state.update { it.copy(message = null, inboxErrorMessage = error.message ?: "선택한 파일을 공유하지 못했습니다.") }
        }
    }

    fun shareInboxFile(item: InboxDisplayItem) {
        runCatching {
            val sendIntent = NasFinderShareIntentFactory.create(application, listOf(item.file))
            application.startActivity(
                Intent.createChooser(sendIntent, "받은 파일 공유")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { error ->
            _state.update {
                it.copy(
                    message = null,
                    inboxErrorMessage = error.message ?: "${item.originalFilename}을(를) 공유하지 못했습니다.",
                )
            }
        }
    }

    private fun selectedInboxItems(ids: Iterable<UUID>): List<InboxDisplayItem> {
        val normalized = InboxBatchContracts.normalizeSelection(ids)
        val available = _state.value.inboxFiles.associateBy(InboxDisplayItem::id)
        return normalized.map { id ->
            available[id] ?: throw IllegalArgumentException("선택한 받은 파일을 찾을 수 없습니다.")
        }
    }

    private fun queryName(uri: Uri): String? = runCatching {
        application.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()
    private fun inboxFiles(): List<InboxDisplayItem> = inboxStore.records()
        .sortedWith(compareByDescending<com.armsone.nasfinder.data.SharedInboxRecord> { it.importedAt }.thenBy { it.originalFilename })
        .mapNotNull { record ->
            runCatching {
                InboxDisplayItem(
                    id = record.id,
                    originalFilename = record.originalFilename,
                    mimeType = record.mimeType,
                    byteCount = record.byteCount,
                    importedAt = record.importedAt,
                    file = inboxStore.file(record),
                )
            }.getOrNull()
        }

    private fun uniqueFile(parent: File, requested: String): File {
        val root = parent.canonicalFile
        val safe = requested.replace(Regex("[/\\\\\u0000-\u001F]"), "_").ifBlank { "파일" }
        var candidate = File(root, safe).canonicalFile
        var suffix = 2
        while (candidate.exists()) {
            val extension = safe.substringAfterLast('.', "")
            val stem = safe.substringBeforeLast('.', safe)
            val name = if (extension.isBlank()) "$stem $suffix" else "$stem $suffix.$extension"
            candidate = File(root, name).canonicalFile
            suffix++
        }
        check(candidate.parentFile == root) { "안전하지 않은 임시 파일 경로입니다." }
        return candidate
    }

    override fun onCleared() {
        _state.value.pendingLocalUpload?.let { pending -> runCatching { cleanupOwnedWebDownload(pending.file) } }
        previewJob?.cancel()
        connectionOpenJob?.cancel()
        thumbnailTrafficObservation?.cancel()
        superThumbnailStartJob?.cancel()
        superThumbnailObservation?.cancel()
        superThumbnailPickerJob?.cancel()
        closeSuperThumbnailPickerService()
        service?.close()
        thumbnailRepository.close()
    }

    private companion object {
        const val KEY_SUPER_THUMBNAIL_CONNECTION = "selected_connection_id"
        const val KEY_SUPER_THUMBNAIL_PATH = "selected_path_v1"
        const val KEY_SUPER_THUMBNAIL_TITLE = "selected_title_v1"
        const val KEY_SUPER_THUMBNAIL_HISTORY = "selection_history_v1"
        const val KEY_SUPER_THUMBNAIL_WORK_CONNECTION = "work_connection_v1"
        const val KEY_SUPER_THUMBNAIL_WORK_PATH = "work_path_v1"
        const val KEY_SUPER_THUMBNAIL_WORK_TITLE = "work_title_v1"
        const val KEY_SUPER_THUMBNAIL_WORK_LOCATIONS = "work_locations_v1"
        const val KEY_SUPER_THUMBNAIL_VAULT_ENABLED = "vault_enabled_v1"
        const val KEY_SUPER_THUMBNAIL_VAULT_TIMING = "vault_timing_v1"
        const val MAX_SUPER_THUMBNAIL_HISTORY = 10
    }
}

internal sealed interface AppEntryRoute {
    data object Ignore : AppEntryRoute
    data class Rejected(val message: String) : AppEntryRoute
    data class Inbox(val id: UUID) : AppEntryRoute
    data object ImportUri : AppEntryRoute
}

/** JVM-testable policy for all externally supplied app-entry URIs. */
internal object AppEntryRouteParser {
    private const val ACTION_VIEW = "android.intent.action.VIEW"

    fun parse(action: String?, rawUri: String?): AppEntryRoute {
        if (action != ACTION_VIEW) return AppEntryRoute.Ignore
        val uri = rawUri?.takeIf { it.isNotBlank() }?.let { value ->
            runCatching { URI(value) }.getOrNull()
        } ?: return AppEntryRoute.Rejected("올바른 파일 또는 NasFinder 주소가 아닙니다.")
        if (uri.rawFragment != null || uri.userInfo != null || uri.port != -1) {
            return AppEntryRoute.Rejected("허용되지 않은 NasFinder 주소입니다.")
        }
        return when (uri.scheme?.lowercase()) {
            "nasfinder" -> parseInbox(uri)
            "content" -> if (!uri.host.isNullOrBlank() && !uri.rawPath.isNullOrBlank()) {
                AppEntryRoute.ImportUri
            } else AppEntryRoute.Rejected("올바른 content 파일 주소가 아닙니다.")
            "file" -> uri.rawPath.orEmpty().let { path ->
                if (path.startsWith('/') && path.length > 1) AppEntryRoute.ImportUri
                else AppEntryRoute.Rejected("올바른 file 주소가 아닙니다.")
            }
            else -> AppEntryRoute.Rejected("지원하지 않는 주소 형식입니다.")
        }
    }

    private fun parseInbox(uri: URI): AppEntryRoute {
        if (!uri.host.equals("inbox", ignoreCase = true) || (uri.rawPath.orEmpty() !in setOf("", "/"))) {
            return AppEntryRoute.Rejected("허용되지 않은 NasFinder 주소입니다.")
        }
        val query = linkedMapOf<String, String>()
        for (pair in uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty)) {
            val key = decode(pair.substringBefore('='))
                ?: return AppEntryRoute.Rejected("올바르지 않은 NasFinder 주소 인코딩입니다.")
            val value = decode(pair.substringAfter('=', ""))
                ?: return AppEntryRoute.Rejected("올바르지 않은 NasFinder 주소 인코딩입니다.")
            if (query.putIfAbsent(key, value) != null) {
                return AppEntryRoute.Rejected("중복된 NasFinder 주소 항목입니다.")
            }
        }
        if (query.keys.any { it != "id" }) return AppEntryRoute.Rejected("허용되지 않은 NasFinder 주소입니다.")
        val id = query["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return AppEntryRoute.Rejected("받은 파일 ID가 올바르지 않습니다.")
        return AppEntryRoute.Inbox(id)
    }

    private fun decode(value: String): String? = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrNull()
}
