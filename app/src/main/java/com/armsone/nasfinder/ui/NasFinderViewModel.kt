package com.armsone.nasfinder.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.armsone.nasfinder.NasFinderApplication
import com.armsone.nasfinder.model.AppTheme
import com.armsone.nasfinder.model.InterfaceIconPack
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
import com.armsone.nasfinder.data.FavoriteItemResolver
import com.armsone.nasfinder.data.PrivacyMode
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
import com.armsone.nasfinder.googlephotos.GooglePhotosAccountController
import com.armsone.nasfinder.googlephotos.GooglePhotosContentDownloader
import com.armsone.nasfinder.googlephotos.GooglePhotosImportCoordinator
import com.armsone.nasfinder.googlephotos.GooglePhotosImportOutcome
import com.armsone.nasfinder.googlephotos.GooglePhotosPickerFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit
import com.armsone.nasfinder.data.SharedInboxStore
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
    val iconPack: InterfaceIconPack = InterfaceIconPack.REALISTIC,
    val launcherIcon: LauncherIconVariant = LauncherIconVariant.DEFAULT,
    val pendingLauncherIcon: LauncherIconVariant? = null,
    val isBusy: Boolean = false,
    val message: String? = null,
    val inboxErrorMessage: String? = null,
    val inboxFiles: List<InboxDisplayItem> = emptyList(),
    val download: RemoteDownloadState? = null,
    val networkDownloadProgress: NetworkDownloadProgress? = null,
    val isDownloadingNetwork: Boolean = false,
    val initialInboxSubpath: String? = null,
    val browserSelectRequest: BrowserSelectRequest? = null,
    val homeFavoriteAction: HomeFavoriteAction? = null,
    val isLocalThumbnailRunning: Boolean = false,
    val localThumbnailProgress: Pair<Int, Int>? = null,
    val remoteFavorites: List<RemoteFavorite> = emptyList(),
    val browserFavorites: List<BrowserFavorite> = emptyList(),
    val pendingTransfer: PendingRemoteTransfer? = null,
    val pendingInboxUpload: PendingInboxUpload? = null,
    val pendingLocalUpload: PendingLocalUpload? = null,
    val downloadCacheBytes: Long? = null,
    val appVersion: String = "",
    val remoteThumbnails: Map<String, Bitmap> = emptyMap(),
    val thumbnailGeneration: Long = 0L,
    val favoriteThumbnails: Map<String, Bitmap> = emptyMap(),
    val favoriteThumbnailGeneration: Long = 0L,
    val imagePreview: ImagePreviewState? = null,
    val trailer: TrailerSession? = null,
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
    val googlePhotosVisible: Boolean = true,
    val googlePhotosName: String = "Google 포토",
    val googlePhotosConnected: Boolean = false,
    val googlePhotosDisclosureRequired: Boolean = false,
    val googlePhotosPhase: GooglePhotosPhase = GooglePhotosPhase.IDLE,
    val googlePhotosImportedCount: Int = 0,
    val mediaPick: MediaPickRequest? = null,
    val mediaPickStaging: MediaPickStaging? = null,
    val mediaPickDelivery: MediaPickDelivery? = null,
    val folderCountCache: Map<String, FolderChildCountState> = emptyMap(),
    val folderCountGeneration: Long = 0L,
    val trailerDraftCount: Int = 0,
    val trailerDraftPicker: List<TrailerDraft>? = null,
    val trailerDraftStaleNotice: String? = null,
    // Bumped whenever draft records change without the count changing (migration, availability recheck).
    val trailerDraftsVersion: Long = 0L,
    val collectionCopyOffer: CollectionCopyOffer? = null,
    val pendingCollectionImport: PendingCollectionImport? = null,
    val trailerConflict: TrailerConflictConfirmation? = null,
    val inlineAudioPlayback: InlineAudioPlaybackState? = null,
    val activeMainTab: MainNavigationDestination = MainNavigationDestination.HOME,
    val activeVideoTab: VideoNavigationDestination = VideoNavigationDestination.CREATE,
    val materials: List<MaterialItem> = emptyList(),
    // 작업대: pockets (always contains the default 기존 재료 pocket), the pocket on screen and the shared item selection.
    val pockets: List<MaterialPocket> = listOf(MaterialPocket(DEFAULT_POCKET_ID, DEFAULT_POCKET_NAME, 0L)),
    val workbenchPocketId: String = DEFAULT_POCKET_ID,
    val workbenchSelected: Set<String> = emptySet(),
    val workbenchAdd: WorkbenchAddRequest? = null,
    val workbenchRun: WorkbenchRunState? = null,
    val workbenchReconnect: WorkbenchReconnect? = null,
    // Set by 미디어 전송 on the 작업대; the transfer screen consumes it to open straight in 보내기.
    val workbenchSendRequested: Boolean = false,
    val collections: List<CollectionItem> = emptyList(),
    // The open editor is kept alive (export/adds continue) while the user visits Finder/Tools from its rail.
    val trailerMinimized: Boolean = false,
    val compression: CollectionCompressionState? = null,
    // Shares of photos/videos waiting for the user's exact two-way choice (이미지 선별 / 영상 만들기), oldest first.
    // Each share is already copied into app-private storage (see PendingShareStaging), so it survives exit/permission expiry.
    val pendingMediaShares: List<PendingMediaShare> = emptyList(),
    // Shares the user dismissed with "나중에": still pending (never deleted), just not asked again this run.
    val deferredShareIds: Set<String> = emptySet(),
    // Shares being copied right now (the share arrived, copies are not finished yet).
    val shareIntakeInProgress: Int = 0,
)

enum class MainNavigationDestination {
    HOME,
    // Independent top-level destination directly below 홈 (outside the Finder / Video / Tools groups).
    WORKBENCH,
    FINDER,
    VIDEO,
    TOOLS
}

enum class VideoNavigationDestination {
    CREATE,
    // Compatibility alias: opens the 작업대. It is no longer a separate tab of the 영상 hub.
    MATERIALS,
    DRAFTS,
    COLLECTIONS
}

data class InlineAudioPlaybackState(
    val key: String,
    val title: String,
    val isPreparing: Boolean = false,
    val downloadFraction: Float? = null,
    val isPlaying: Boolean = false,
    val progress: Float = 0f,
    val durationMillis: Long = 0L,
    val positionMillis: Long = 0L,
    val errorMessage: String? = null,
)

/** An external reference that cannot be kept durably: explains why and offers an app-owned copy instead. */
data class CollectionCopyOffer(
    val message: String,
    val copy: () -> Unit,
)

/** One picked video waiting for the user's 사본/참조 choice before anything is added to the collection. */
data class PendingImportEntry(
    val uri: String,
    val title: String,
    val sizeBytes: Long,
    // Read flag the picker actually returned as persistable (0 when none was offered or the flags are unknown).
    val persistableReadFlag: Int,
    // Why a durable reference is impossible for this entry; null when it is supported.
    val referenceBlocker: String?,
)

/** Picked videos kept until the user adds them (copy by default) or cancels; a failed reference attempt keeps them here. */
data class PendingCollectionImport(
    val entries: List<PendingImportEntry>,
    val working: Boolean = false,
    val problem: String? = null,
    val excludedNote: String? = null,
) {
    val referenceSupported: Boolean get() = entries.isNotEmpty() && entries.all { it.referenceBlocker == null }
}

data class TrailerConflictConfirmation(
    val title: String,
    val message: String,
    val onResumeExisting: () -> Unit,
    val onStartNew: () -> Unit,
)

sealed interface FolderChildCountState {
    data object Loading : FolderChildCountState
    data class Ready(val folderCount: Int, val fileCount: Int) : FolderChildCountState
    data object Error : FolderChildCountState
}

enum class GooglePhotosPhase { IDLE, AUTHORIZING, PREPARING, WAITING_FOR_SELECTION, IMPORTING, FINISHED, CANCELLED, ERROR }

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
    val relativePath: String,
    val durationMillis: Long? = null,
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
    // 시작할 때 고정한 출처. true이면 일반 모드 화면에는 파일 이름이 든 진행 표시를 내놓지 않는다(작업은 계속된다).
    val originPrivate: Boolean = false,
) {
    val fraction: Float?
        get() = totalBytes.takeIf { it > 0 }
            ?.let { (completedBytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
}

data class ImagePreviewState(
    val connection: RemoteConnection?,
    val images: List<RemoteFileItem>,
    val index: Int,
    val kind: BuiltInPreviewKind,
    val bitmap: Bitmap? = null,
    val cachedFile: File,
    val localFiles: List<File> = emptyList(),
    val remoteStream: RemoteMediaStreamSpec? = null,
    val pdfPageIndex: Int = 0,
    val pdfPageCount: Int = 0,
    /** True when [cachedFile] is a multi-frame GIF that the viewer should animate; [bitmap] stays the static fallback. */
    val animatedImage: Boolean = false,
)

enum class BuiltInPreviewKind { IMAGE, VIDEO, AUDIO, PDF }

enum class LocalMediaEditIntent { NONE, TRAILER, MUSIC }

/**
 * 선택 chosen from the home favorite task panel: the folder [parentPath] of [connectionId] ("phonehard" for PhoneHard) opens
 * and selects only the entry with [itemId] (remote item ID, PhoneHard file UUID or PhoneHard folder path) of the same kind.
 */
data class BrowserSelectRequest(
    val connectionId: String,
    val parentPath: String?,
    val itemId: String,
    val isDirectory: Boolean,
    val name: String,
)

/**
 * Home favorite hold-release task panel shown over the unchanged home screen. [favorite] is the immutable original entry and
 * is only ever unregistered; file commands use the validated fresh [target] exclusively.
 */
data class HomeFavoriteAction(
    val token: Long,
    val favorite: RemoteFavorite,
    val target: HomeFavoriteTarget,
)

sealed interface HomeFavoriteTarget {
    data object Preparing : HomeFavoriteTarget
    data class Remote(
        val connection: RemoteConnection,
        val parentPath: String,
        val item: RemoteFileItem,
        val preferences: BrowserPreferences,
    ) : HomeFavoriteTarget
    data class PhoneHardFolder(val folder: com.armsone.nasfinder.data.PhoneHardItem) : HomeFavoriteTarget
    data class PhoneHardFile(val file: InboxDisplayItem) : HomeFavoriteTarget
    /** File commands stay closed; only the original favorite's removal remains available. */
    data class Unavailable(val reason: String) : HomeFavoriteTarget
}

/** Network browse launched from the 영상 만들기 PLUS menu; selected media returns to [returnScreen]'s basket. */
data class MediaPickRequest(val returnScreen: Screen.LocalPhotoLibrary)
/** References waiting for the user's destination-pocket choice. Nothing is read or copied from the originals. */
data class WorkbenchAddRequest(
    val candidates: List<MaterialItem>,
    val note: String? = null,
    // Runs after the references were saved, with the pocket they went to (e.g. to select them for a transfer).
    val afterAdd: ((pocketId: String) -> Unit)? = null,
)

data class WorkbenchRunLeaf(
    val item: MaterialItem,
    val stored: Boolean,
    val parentName: String? = null,
    // Id of the saved reference this leaf came from (itself, or the folder it was found in).
    val rootId: String = item.id,
)
data class WorkbenchRunIssue(val name: String, val reason: String)
data class WorkbenchRunPlan(
    val leaves: List<WorkbenchRunLeaf>,
    val issues: List<WorkbenchRunIssue>,
    // Informational lines (e.g. what a folder expanded to); shown with the issues, never blocking by themselves.
    val notes: List<String> = emptyList(),
)

/** One readable input of a 전송 run. */
internal class WorkbenchSendEntry(val leaf: WorkbenchRunLeaf, val media: WorkbenchTransferMedia)

internal class WorkbenchSendMaterialization(
    val entries: List<WorkbenchSendEntry>,
    val failures: List<WorkbenchRunIssue>,
    // Temporary workspace owned by this run (NAS downloads). The caller deletes exactly this folder when the run ends.
    val runDirectory: File,
)
enum class WorkbenchRunKind { VIDEO, SEND }

/** Items that could not be read while the inputs were being fetched: the run waits for the user's explicit choice. */
data class WorkbenchPartial(
    val failures: List<WorkbenchRunIssue>,
    // How many inputs were fetched successfully (what the run would continue with).
    val available: Int,
    val decision: kotlinx.coroutines.CompletableDeferred<Boolean>,
)

/** One explicit 영상 제작 / 전송 run: scanning folders, then (when something is unsupported) waiting for the user's decision. */
data class WorkbenchRunState(
    val kind: WorkbenchRunKind,
    val phase: String,
    val current: Int = 0,
    val total: Int = 0,
    val name: String = "",
    val awaiting: WorkbenchRunPlan? = null,
    val partial: WorkbenchPartial? = null,
    // 작업을 시작할 때 고정한 출처. true이면 일반 모드 화면에서는 이름·보고를 가리고(작업은 계속) 산출물도 비공개로 시작한다.
    val originPrivate: Boolean = false,
)

/** A 다시 연결 replacement the user has to confirm before the reference is changed. */
data class WorkbenchReconnect(val itemId: String, val oldName: String, val replacement: MaterialItem)

data class MediaPickStaging(val current: Int, val total: Int, val name: String, val progress: Float? = null)
data class MediaPickDelivery(val id: Long, val uris: List<String>, val failedCount: Int, val message: String)

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
    data object PhotoTransfer : Screen
    data object PhotoSelection : Screen
    /** Formal 즐겨찾기 destination of the wide-window Finder group (the same stored list and reordering as the shelves). */
    data object Favorites : Screen
    data object GooglePhotos : Screen
    data object Settings : Screen
    data object ThumbnailCache : Screen
    data object SuperThumbnail : Screen
    data object SuperThumbnailFolderPicker : Screen
    data object SuperThumbnailProgress : Screen
    data object SuperThumbnailReport : Screen
    data class LocalPhotoLibrary(
        val initialIntent: LocalMediaEditIntent = LocalMediaEditIntent.NONE,
        val googlePhotosSource: Boolean = false,
    ) : Screen
    data object WebBrowser : Screen
    data object WebHard : Screen
    data object MaterialBasket : Screen
    data object VideoDrafts : Screen
    data object VideoCollections : Screen
}

class NasFinderViewModel(private val application: NasFinderApplication) : ViewModel() {
    private val repository = application.connections
    private val favoriteRepository = application.favorites
    private val settingsRepository = application.settings
    private val inboxStore = application.inbox
    private val networkDownloadCoordinator: NetworkDownloadCoordinator = NetworkDownloadCoordinator.create(application, inboxStore)
    private var networkDownloadJob: Job? = null
    private val downloadCache = DownloadCache(application)
    private val thumbnailRepository = RemoteThumbnailRepository(
        application,
        unlimitedTraffic = { com.armsone.nasfinder.data.SuperThumbnailNetworkPolicy.isWifi(application) },
    )
    private val superThumbnailDataController = SuperThumbnailDataController(application)
    private var service: RemoteFileService? = null
    private var thumbnailGeneration = 0L
    private val thumbnailRequests = mutableSetOf<String>()
    private val thumbnailJobs = mutableMapOf<String, Job>()
    private val thumbnailFailures = mutableMapOf<String, Int>()
    private var favoriteThumbnailGeneration = 0L
    private val favoriteThumbnailRequests = mutableSetOf<String>()
    private val favoriteThumbnailJobs = mutableMapOf<String, Job>()
    private var previewRequestGeneration = 0L
    private var previewJob: Job? = null
    private val trackedOwnedPreviewFiles = mutableSetOf<File>()
    private var inlineAudioPlayer: androidx.media3.exoplayer.ExoPlayer? = null
    private var inlineAudioStagingJob: Job? = null
    private var inlineAudioProgressJob: Job? = null
    private var inlineAudioGeneration = 0L
    private val folderCountSemaphore = kotlinx.coroutines.sync.Semaphore(2)
    private var folderCountGeneration = 0L
    private val folderCountRequests = mutableSetOf<String>()
    private var connectionOpenJob: Job? = null
    private val dropboxAccountRequests = mutableSetOf<String>()
    private var googlePhotosJob: Job? = null
    private val googlePhotosAccount = GooglePhotosAccountController(application)
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
            iconPack = settingsRepository.iconPack(),
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
                "폰하드 파일 목록을 읽지 못했습니다: ${it.message ?: "알 수 없는 오류"}"
            },
            remoteFavorites = favoriteRepository.remoteFavorites(),
            browserFavorites = favoriteRepository.browserFavorites(),
            oauthClientIds = CloudOAuthProvider.entries.mapNotNull { provider ->
                repository.oauthClients.clientId(provider)?.let { provider to it }
            }.toMap(),
            googlePhotosVisible = repository.googlePhotosVisible(),
            googlePhotosName = repository.googlePhotosName(),
            googlePhotosConnected = googlePhotosAccount.isConnected(),
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
    private var shareRestoreJob: Job? = null

    /** The one active 사진 선별 batch; its staging copies are app-private and removed only on finish/cancel. */
    internal val photoSelection = PhotoSelectionSession(application, viewModelScope) { batchId, photos, pocketId ->
        addPhotoSelectionToMaterials(batchId, photos, pocketId)
    }

    init {
        // Shares that arrived before an exit/restart come back as pending (their copies are durable).
        shareRestoreJob = viewModelScope.launch {
            val restored = PendingShareStaging.loadAll(application)
            _state.update { current ->
                val known = current.pendingMediaShares.map { it.id }.toSet()
                current.copy(pendingMediaShares = (restored.filter { it.id !in known } + current.pendingMediaShares).sortedBy { it.createdAt })
            }
        }
        refreshDropboxAccountLabels()
        DurableMaterialStore.initialize(application)
        publishMaterials()
        DurableCollectionStore.initialize(application)
        TrailerDraftStore.configureTrailersRoot(application.cacheDir, application.filesDir)
        // A project folder is never deleted while a collection record still points inside it.
        TrailerDraftStore.setExternalReferenceGuard { directory -> DurableCollectionStore.referencesInside(directory) }
        migrateEphemeralProjectData()
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
    }

    fun show(screen: Screen) {
        // Old route: the standalone 영상 재료 screen is the 작업대 now.
        if (screen == Screen.MaterialBasket) {
            selectMainDestination(MainNavigationDestination.WORKBENCH)
            return
        }
        stopInlineAudio()
        invalidateImagePreviewRequests()
        if (screen == Screen.Dashboard) {
            if (_state.value.screen != Screen.Dashboard) {
                invalidateFavoriteThumbnails()
            }
            val hasOpenEditor = _state.value.trailer != null
            if (hasOpenEditor) {
                _state.value.trailer?.takeIf { it.prepared && it.editor != null }?.let { scheduleEditorAutosave(it.directory, immediate = true) }
            }
            if (_state.value.mediaPick != null) {
                mediaPickJob?.cancel()
                mediaPickJob = null
            }
            _state.update {
                it.copy(
                    screen = Screen.Dashboard,
                    activeMainTab = MainNavigationDestination.HOME,
                    trailerMinimized = if (hasOpenEditor) true else it.trailerMinimized,
                    message = null,
                    mediaPick = null,
                    mediaPickStaging = null,
                    browserSelectRequest = null,
                )
            }
            return
        }
        if (screen !is Screen.Browser && _state.value.mediaPick != null) {
            mediaPickJob?.cancel(); mediaPickJob = null
            _state.update { it.copy(screen = screen, message = null, mediaPick = null, mediaPickStaging = null, browserSelectRequest = null) }
            return
        }
        _state.update { it.copy(screen = screen, message = null, browserSelectRequest = null) }
    }

    private var mediaPickJob: Job? = null
    private var mediaPickDeliverySeq = 0L
    // provider kind + connection + remote item path -> already staged own copy (no duplicate downloads).
    private val stagedMediaByIdentity = java.util.concurrent.ConcurrentHashMap<String, File>()

    fun beginNetworkMediaPick(connection: RemoteConnection, returnScreen: Screen.LocalPhotoLibrary, startPath: String = connection.normalizedRootPath) {
        if (_state.value.mediaPickStaging != null) return
        _state.update { it.copy(mediaPick = MediaPickRequest(returnScreen)) }
        openConnection(connection, startPath)
    }

    /** Adds one favorite network media file to the basket; the original and the basket stay untouched on failure. */
    fun addFavoriteFileToBasket(favorite: RemoteFavorite, returnScreen: Screen.LocalPhotoLibrary) {
        if (_state.value.mediaPickStaging != null) return
        val connection = _state.value.connections.firstOrNull { it.id == favorite.connectionId }
        if (connection == null) {
            _state.update { it.copy(message = "‘${favorite.name}’의 네트워크 연결을 찾을 수 없습니다. 연결을 확인해 주세요.") }
            return
        }
        _state.update { it.copy(mediaPickStaging = MediaPickStaging(0, 1, favorite.name)) }
        mediaPickJob = viewModelScope.launch {
            val favoriteService = try {
                withContext(Dispatchers.IO) {
                    val credential = repository.credentials.read(connection.id).orEmpty()
                    RemoteFileServiceFactory.create(connection, credential)
                }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(mediaPickStaging = null) }; throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(mediaPickStaging = null, message = "‘${favorite.name}’에 연결하지 못했습니다: ${error.message ?: "연결 오류"}") }
                return@launch
            }
            val item = try {
                withContext(Dispatchers.IO) { FavoriteItemResolver.resolve(favorite, connection, favoriteService) }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(mediaPickStaging = null) }; throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(mediaPickStaging = null, message = error.message ?: "즐겨찾기 파일을 찾을 수 없습니다.") }
                return@launch
            }
            if (item.isDirectory || !(item.isVideo || item.isImage)) {
                _state.update { it.copy(mediaPickStaging = null, message = "‘${favorite.name}’은(는) 사진이나 영상이 아니라 담을 수 없습니다.") }
                return@launch
            }
            stageMedia(listOf(item), connection, favoriteService, returnScreen)
        }
    }

    /** Leaves the network browser without touching the existing basket. */
    fun cancelNetworkMediaPick() {
        mediaPickJob?.cancel(); mediaPickJob = null
        connectionOpenJob?.cancel(); connectionOpenJob = null
        thumbnailGeneration++
        val returnScreen = _state.value.mediaPick?.returnScreen ?: Screen.Dashboard
        _state.update { it.copy(screen = returnScreen, mediaPick = null, mediaPickStaging = null, isBusy = false, message = null) }
    }

    /** Cancels only the running staging; the browser and the prior basket stay as they are. */
    fun cancelMediaPickStaging() { mediaPickJob?.cancel() }

    fun consumeMediaPickDelivery(id: Long) {
        _state.update { if (it.mediaPickDelivery?.id == id) it.copy(mediaPickDelivery = null) else it }
    }

    /** Downloads only the selected remote media into own cache copies and returns them to the basket screen. */
    fun stageRemoteMediaForBasket(items: List<RemoteFileItem>) {
        val request = _state.value.mediaPick ?: return
        val browser = _state.value.screen as? Screen.Browser ?: return
        val activeService = service ?: return
        if (_state.value.mediaPickStaging != null) return
        val media = items.filter { !it.isDirectory && (it.isVideo || it.isImage) }.distinctBy { it.path }
        if (media.isEmpty()) { _state.update { it.copy(message = "사진 또는 영상 파일을 선택해 주세요.") }; return }
        mediaPickJob = viewModelScope.launch { stageMedia(media, browser.connection, activeService, request.returnScreen) }
    }

    private suspend fun stageMedia(media: List<RemoteFileItem>, connection: RemoteConnection, activeService: RemoteFileService, returnScreen: Screen.LocalPhotoLibrary) {
        val root = File(application.cacheDir, "media_basket").also { it.mkdirs() }
        run {
            val staged = mutableListOf<String>()
            val created = mutableListOf<File>()
            var failed = 0
            try {
                media.forEachIndexed { index, item ->
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    _state.update { it.copy(mediaPickStaging = MediaPickStaging(index + 1, media.size, item.name)) }
                    val identity = "${connection.kind.name}\u0000${connection.id}\u0000${item.path}"
                    val existing = stagedMediaByIdentity[identity]?.takeIf { it.isFile && it.length() > 0 && (item.size <= 0 || it.length() == item.size) }
                    if (existing != null) { staged += Uri.fromFile(existing).toString(); return@forEachIndexed }
                    val directory = File(root, UUID.randomUUID().toString()).also { it.mkdirs() }
                    val safeName = item.name.substringAfterLast('/').replace(Regex("[\\\\:*?\"<>|]"), "_").ifBlank { "media" }
                    val destination = File(directory, safeName)
                    created += directory
                    try {
                        activeService.download(item, destination) { received, total ->
                            _state.update { current ->
                                val live = current.mediaPickStaging ?: return@update current
                                current.copy(mediaPickStaging = live.copy(progress = if (total > 0) received.toFloat() / total else null))
                            }
                        }
                        check(destination.length() > 0 && (item.size <= 0 || destination.length() == item.size)) { "파일을 끝까지 받지 못했습니다." }
                        item.modifiedAt?.toEpochMilli()?.let { destination.setLastModified(it) }
                        stagedMediaByIdentity[identity] = destination
                        staged += Uri.fromFile(destination).toString()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failed++
                        directory.deleteRecursively()
                    }
                }
                mediaPickJob = null
                val message = when {
                    staged.isEmpty() -> "선택한 파일을 가져오지 못했습니다."
                    failed == 0 -> "${staged.size}개를 영상 만들기에 추가했습니다."
                    else -> "${staged.size}개를 추가했고 ${failed}개는 가져오지 못했습니다."
                }
                _state.update {
                    if (staged.isEmpty()) it.copy(mediaPickStaging = null, message = message)
                    else it.copy(
                        screen = returnScreen, mediaPick = null, mediaPickStaging = null, message = null, isBusy = false,
                        mediaPickDelivery = MediaPickDelivery(++mediaPickDeliverySeq, staged.toList(), failed, message),
                    )
                }
            } catch (cancelled: CancellationException) {
                // Only copies created by this run are removed; earlier staged basket items stay.
                withContext(NonCancellable) { created.filter { dir -> stagedMediaByIdentity.values.none { it.parentFile == dir } }.forEach { it.deleteRecursively() } }
                _state.update { it.copy(mediaPickStaging = null, message = "가져오기를 취소했습니다.") }
            } catch (error: Exception) {
                withContext(NonCancellable) { created.filter { dir -> stagedMediaByIdentity.values.none { it.parentFile == dir } }.forEach { it.deleteRecursively() } }
                _state.update { it.copy(mediaPickStaging = null, message = "선택한 파일을 가져오지 못했습니다.") }
            }
        }
    }

    /** Read-only file URIs for PhoneHard media favorites/selections; missing files are skipped. */
    fun phoneHardFileUris(relativePaths: List<String>): List<String> = relativePaths.mapNotNull { path ->
        resolvePhoneHardDiskFile(path).takeIf { it.isFile }?.let { Uri.fromFile(it).toString() }
    }

    /** Stages several favorite media files of ONE connection with a single explicit commit; the basket stays untouched on failure. */
    fun addFavoriteFilesToBasket(favorites: List<RemoteFavorite>, returnScreen: Screen.LocalPhotoLibrary) {
        if (_state.value.mediaPickStaging != null || favorites.isEmpty()) return
        val connection = _state.value.connections.firstOrNull { it.id == favorites.first().connectionId }
        if (connection == null) {
            _state.update { it.copy(message = "네트워크 연결을 찾을 수 없습니다. 연결을 확인해 주세요.") }
            return
        }
        _state.update { it.copy(mediaPickStaging = MediaPickStaging(0, favorites.size, favorites.first().name)) }
        mediaPickJob = viewModelScope.launch {
            try {
                val (favoriteService, items) = withContext(Dispatchers.IO) {
                    val credential = repository.credentials.read(connection.id).orEmpty()
                    val created = RemoteFileServiceFactory.create(connection, credential)
                    created to favorites.mapNotNull { favorite ->
                        runCatching { FavoriteItemResolver.resolve(favorite, connection, created) }.getOrNull()
                            ?.takeIf { !it.isDirectory && (it.isVideo || it.isImage) }
                    }
                }
                if (items.isEmpty()) {
                    _state.update { it.copy(mediaPickStaging = null, message = "선택한 즐겨찾기 파일을 찾을 수 없습니다.") }
                    return@launch
                }
                stageMedia(items, connection, favoriteService, returnScreen)
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(mediaPickStaging = null) }; throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(mediaPickStaging = null, message = "즐겨찾기에 연결하지 못했습니다: ${error.message ?: "연결 오류"}") }
            }
        }
    }

    /** PhoneHard photo/video files for the basket picker; referenced in place, never re-imported. */
    fun phoneHardMediaForBasket(): List<InboxDisplayItem> = inboxFiles().filter {
        val kind = BuiltInPreviewPolicy.kind(it.originalFilename, it.mimeType)
        (kind == BuiltInPreviewKind.IMAGE || kind == BuiltInPreviewKind.VIDEO) && it.file.isFile
    }

    /**
     * Called when the app has left the foreground (not for configuration changes). Only transient
     * navigation, viewer and browser state is dropped; connections, credentials, settings,
     * downloads, caches and files are untouched so the next foreground return starts on the dashboard.
     */
    fun returnToDashboardAfterBackground() {
        stopInlineAudio()
        // System media pickers and editor interruptions must retain the shared selection.
        // 사진 선별 and 사진·영상 전송 launch full-screen pickers/scanners and own staged state, so they stay open too.
        if (_state.value.screen is Screen.LocalPhotoLibrary || _state.value.screen == Screen.PhotoSelection ||
            _state.value.screen == Screen.PhotoTransfer || _state.value.trailer != null || _state.value.mediaPick != null ||
            // 작업대도 시스템 파일·폴더 선택기를 연다. 다녀와도 고른 포켓과 선택을 유지한다.
            (_state.value.screen == Screen.Dashboard && _state.value.activeMainTab == MainNavigationDestination.WORKBENCH)
        ) return
        connectionOpenJob?.cancel()
        connectionOpenJob = null
        thumbnailGeneration++
        invalidateFavoriteThumbnails()
        invalidateImagePreviewRequests()
        _state.update {
            it.copy(
                screen = Screen.Dashboard,
                activeMainTab = MainNavigationDestination.HOME,
                isBusy = false,
            )
        }
    }
    fun dismissMessage() { _state.update { it.copy(message = null) } }
    fun showMessage(text: String) { _state.update { it.copy(message = text) } }
    fun dismissInboxError() { _state.update { it.copy(inboxErrorMessage = null) } }
    fun showInboxErrorMessage(message: String) { _state.update { it.copy(inboxErrorMessage = message) } }

    fun saveGooglePhotosLocation(name: String, visible: Boolean = true) {
        val displayName = name.trim().ifBlank { "Google 포토" }
        repository.saveGooglePhotosLocation(displayName, visible)
        _state.update { it.copy(googlePhotosName = displayName, googlePhotosVisible = visible) }
    }

    private fun refreshDropboxAccountLabels(connection: RemoteConnection? = null) {
        val targets = connection?.let(::listOf) ?: _state.value.connections
        targets.filter { it.kind == com.armsone.nasfinder.model.ConnectionKind.DROPBOX }
            .filter { dropboxAccountRequests.add(it.id) }.forEach { target ->
                viewModelScope.launch {
                    try {
                        val tokenSnapshot = repository.oauthTokens.read(target.id)
                        val manualTokenSnapshot = repository.credentials.read(target.id)
                        val label = withContext(Dispatchers.IO) {
                            val token = com.armsone.nasfinder.platform.OAuthAccessTokenProvider(application)
                                .accessToken(target.id, CloudOAuthProvider.from(target.kind)!!, persistRefresh = false)
                                ?: manualTokenSnapshot.orEmpty()
                            com.armsone.nasfinder.network.CloudDriveFileService(target, token).use { it.dropboxAccountDisplay() }
                        }
                        // Preserve edits, ordering, identity and removals that happened during the request.
                        val current = _state.value.connections
                        if (current.any { it == target } &&
                            repository.oauthTokens.read(target.id) == tokenSnapshot &&
                            repository.credentials.read(target.id) == manualTokenSnapshot
                        ) {
                            val updated = current.map { if (it == target) it.copy(username = label) else it }
                            repository.save(updated)
                            _state.update { it.copy(connections = updated) }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // Account lookup must never remove or invalidate an existing connection.
                    } finally {
                        dropboxAccountRequests.remove(target.id)
                    }
                }
            }
    }

    fun requestGooglePhotosImport() {
        _state.update {
            it.copy(
                screen = Screen.LocalPhotoLibrary(googlePhotosSource = true),
                googlePhotosDisclosureRequired = false,
                message = null,
            )
        }
    }

    fun dismissGooglePhotosDisclosure() {
        _state.update { it.copy(googlePhotosDisclosureRequired = false) }
    }

    fun confirmGooglePhotosDisclosure() {
        _state.update { it.copy(googlePhotosDisclosureRequired = false) }
        if (_state.value.googlePhotosConnected) startGooglePhotosPicker() else beginGooglePhotosAuthorization()
    }

    fun beginGooglePhotosAuthorization() {
        runCatching {
            val url = googlePhotosAccount.beginAuthorization()
            application.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            _state.update { it.copy(screen = Screen.GooglePhotos, googlePhotosPhase = GooglePhotosPhase.AUTHORIZING, message = null) }
        }.onFailure { error ->
            _state.update { it.copy(screen = Screen.GooglePhotos, googlePhotosPhase = GooglePhotosPhase.ERROR, message = safeGooglePhotosMessage(error, "Google 포토 로그인을 시작하지 못했습니다. Google Drive client ID를 먼저 설정해 주세요.")) }
        }
    }

    fun disconnectGooglePhotos() {
        googlePhotosJob?.cancel()
        googlePhotosJob = viewModelScope.launch {
            googlePhotosAccount.disconnect()
            _state.update { it.copy(googlePhotosConnected = false, googlePhotosPhase = GooglePhotosPhase.IDLE, googlePhotosImportedCount = 0, message = "Google 포토 연결을 해제했습니다.") }
        }
    }

    fun cancelGooglePhotosImport() {
        googlePhotosJob?.cancel()
        googlePhotosJob = null
        _state.update { it.copy(googlePhotosPhase = GooglePhotosPhase.CANCELLED, message = "Google 포토 가져오기를 취소했습니다.") }
    }

    fun retryGooglePhotosImport() = startGooglePhotosPicker()

    private fun startGooglePhotosPicker() {
        googlePhotosJob?.cancel()
        googlePhotosJob = viewModelScope.launch {
            var sessionId: String? = null
            try {
                _state.update { it.copy(screen = Screen.GooglePhotos, googlePhotosPhase = GooglePhotosPhase.PREPARING, googlePhotosImportedCount = 0, message = null) }
                val picker = googlePhotosAccount.pickerClient()
                val session = picker.createSession()
                sessionId = session.id
                val pickerUri = session.pickerUri?.takeIf { URI(it).scheme == "https" } ?: error("Google 포토 선택 화면 주소가 올바르지 않습니다.")
                application.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pickerUri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                _state.update { it.copy(googlePhotosPhase = GooglePhotosPhase.WAITING_FOR_SELECTION) }
                GooglePhotosPickerFlow(picker).awaitSelection(session) { googlePhotosJob?.isActive == true }
                val items = picker.allMediaItems(session.id)
                _state.update { it.copy(googlePhotosPhase = GooglePhotosPhase.IMPORTING) }
                val downloader = GooglePhotosContentDownloader()
                // 가져오기를 시작한 순간의 모드를 고정한다. 도중에 모드가 바뀌어도 폰하드 파일의 공개 범위는 그대로다.
                val importPrivate = PrivacyMode.isActive
                val coordinator = GooglePhotosImportCoordinator(
                    download = { item ->
                        val suffix = item.mediaFile?.filename?.substringAfterLast('.', "")?.takeIf { it.length in 1..10 && it.all(Char::isLetterOrDigit) }?.let { ".$it" }.orEmpty()
                        val target = File(application.cacheDir, "google-photos/${UUID.randomUUID()}$suffix")
                        downloader.download(picker.contentRequest(item), target)
                    },
                    importFile = { file, item ->
                        try {
                            file.inputStream().use { input -> inboxStore.import(item.mediaFile?.filename ?: "Google 포토 파일", item.mediaFile?.mimeType, input, isPrivate = importPrivate) }
                        } finally { file.delete() }
                    },
                )
                val summary = coordinator.import(items)
                val failed = summary.outcomes.count { it is GooglePhotosImportOutcome.Failed || it is GooglePhotosImportOutcome.Skipped }
                val files = inboxFiles()
                _state.update { it.copy(screen = Screen.Inbox, inboxFiles = files, googlePhotosPhase = GooglePhotosPhase.FINISHED, googlePhotosImportedCount = summary.importedCount,
                    message = if (failed == 0) "Google 포토에서 ${summary.importedCount}개를 폰하드로 가져왔습니다." else "${summary.importedCount}개를 가져왔고 ${failed}개는 가져오지 못했습니다.") }
                NasFinderAppWidgetProvider.updateAll(application)
            } catch (_: CancellationException) {
                _state.update { it.copy(googlePhotosPhase = GooglePhotosPhase.CANCELLED) }
            } catch (error: Throwable) {
                _state.update { it.copy(screen = Screen.GooglePhotos, googlePhotosPhase = GooglePhotosPhase.ERROR, message = safeGooglePhotosMessage(error, "Google 포토 가져오기를 완료하지 못했습니다. 다시 시도해 주세요.")) }
            } finally {
                sessionId?.let { id -> withContext(NonCancellable) { runCatching { googlePhotosAccount.pickerClient().deleteSession(id) } } }
            }
        }
    }

    private fun safeGooglePhotosMessage(error: Throwable, fallback: String): String = when (error) {
        is ActivityNotFoundException -> "브라우저를 열 수 없습니다. 브라우저 앱을 확인해 주세요."
        is com.armsone.nasfinder.googlephotos.GooglePhotosOAuthException.ReauthorizationRequired -> "Google 포토에 다시 로그인해 주세요."
        else -> fallback
    }

    fun refreshInbox() {
        runCatching { inboxFiles() }.fold(
            onSuccess = { files ->
                // 비공개 폴더 표시 파일이 손상되면 일반 모드 목록이 비므로, 이유와 복구 방법을 중립적으로 알린다(파일 이름은 담지 않는다).
                val metadataNotice = "비공개 폴더 표시 파일을 읽을 수 없어 일반 모드에서는 폰하드 목록을 숨겼습니다. 프라이버시 모드에서 확인하거나 파일을 복구해 주세요."
                    .takeIf { inboxStore.privateMetadataUnreadable && !PrivacyMode.isActive }
                _state.update { it.copy(inboxFiles = files, inboxErrorMessage = metadataNotice) }
            },
            onFailure = { error ->
                _state.update {
                    it.copy(
                        inboxErrorMessage = "폰하드 파일 목록을 읽지 못했습니다: ${error.message ?: "알 수 없는 오류"}",
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
        refreshDropboxAccountLabels(connection)
        invalidateFavoriteThumbnails()
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
        favoriteRepository.removeFavoritesOf(connection.id)
        if (_state.value.preferredId == connection.id) repository.setPreferred(null)
        invalidateFavoriteThumbnails()
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

    fun selectSuperThumbnailFolder(connectionId: String, path: String, title: String? = null) {
        val connection = _state.value.connections.firstOrNull { it.id == connectionId } ?: return
        val effectiveTitle = title ?: pathTitle(path, connection.name)
        selectSuperThumbnailLocation(
            SuperThumbnailLocation(connection.id, path, effectiveTitle)
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
        // 시작할 때 저장된 선택을 복원하는 호출(recordHistory=false)은 아무것도 다시 쓰지 않는다:
        // 일반 모드에서 숨겨진 연결의 저장된 선택이 대체 연결로 덮어써지지 않게 한다.
        if (recordHistory) {
            superThumbnailPreferences.edit()
                .putString(KEY_SUPER_THUMBNAIL_CONNECTION, location.connectionId)
                .putString(KEY_SUPER_THUMBNAIL_PATH, location.path)
                .putString(KEY_SUPER_THUMBNAIL_TITLE, location.title)
                .apply()
        }
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
                    invalidateFavoriteThumbnails()
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
                    .firstOrNull { it.state.isWorkActive }
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
                    val info = values.firstOrNull { it.state.isWorkActive }
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

    private val WorkInfo.State.isWorkActive: Boolean
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

    /** 모드 때문에 지금 보이지 않는 연결(저장은 되어 있음)의 id. 표시용 목록을 통째로 저장하다 이 기록을 지우지 않게 한다. */
    private fun hiddenConnectionIds(): Set<String> {
        val visible = repository.load().mapTo(hashSetOf()) { it.id }
        return repository.loadAll().map { it.id }.filter { it !in visible }.toSet()
    }

    private fun persistSuperThumbnailHistory(entries: List<SuperThumbnailLocation>) {
        val hidden = hiddenConnectionIds()
        val json = org.json.JSONArray().apply {
            entries.forEach { entry ->
                put(
                    org.json.JSONObject()
                        .put("connectionId", entry.connectionId)
                        .put("path", entry.path)
                        .put("title", entry.title)
                )
            }
            // 숨겨진 연결의 기존 기록은 원래 값 그대로 이어 붙인다(표시 목록만 넘겼다고 영구히 사라지지 않게).
            if (hidden.isNotEmpty()) {
                val shown = entries.mapTo(hashSetOf()) { it.connectionId to it.path }
                runCatching {
                    val previous = org.json.JSONArray(superThumbnailPreferences.getString(KEY_SUPER_THUMBNAIL_HISTORY, null).orEmpty())
                    for (index in 0 until previous.length()) {
                        val value = previous.opt(index)
                        val owner = when (value) {
                            is org.json.JSONObject -> value.optString("connectionId")
                            is String -> value
                            else -> null
                        }
                        if (owner == null || owner !in hidden) continue
                        if (value is org.json.JSONObject && (owner to value.optString("path")) in shown) continue
                        put(value)
                    }
                }
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
        // 숨겨진 연결의 작업 위치는 원래 값 그대로 이어 붙인다.
        val hidden = hiddenConnectionIds()
        if (hidden.isNotEmpty()) {
            runCatching {
                val previous = org.json.JSONObject(superThumbnailPreferences.getString(KEY_SUPER_THUMBNAIL_WORK_LOCATIONS, null).orEmpty())
                previous.keys().forEach { connectionId ->
                    if (connectionId in hidden && !json.has(connectionId)) json.put(connectionId, previous.get(connectionId))
                }
            }
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
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    RemoteFileServiceFactory.create(
                        connection,
                        repository.credentials.read(connection.id).orEmpty(),
                    ).also {
                        candidate = it
                        if (generation == superThumbnailPickerGeneration) superThumbnailPickerService = it
                    }.list(connection.normalizedRootPath)
                }
            }
            result.onSuccess { items ->
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
            val result = withContext(Dispatchers.IO) {
                runCatching { activeService.list(path) }
            }
            result.onSuccess { items ->
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
            }.onFailure { error ->
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
            val result = withContext(Dispatchers.IO) {
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
                }
            }
            result.onSuccess {
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
        stopInlineAudio()
        invalidateImagePreviewRequests()
        connectionOpenJob?.cancel()
        val generation = ++thumbnailGeneration
        val countGen = ++folderCountGeneration
        thumbnailJobs.values.forEach(Job::cancel)
        thumbnailJobs.clear()
        thumbnailFailures.clear()
        thumbnailRequests.clear()
        folderCountRequests.clear()
        val preferences = repository.browserPreferences()
        _state.update {
            it.copy(
                screen = Screen.Browser(connection, path, emptyList(), preferences),
                isBusy = true,
                message = null,
                browserSelectRequest = null,
                remoteThumbnails = emptyMap(),
                thumbnailGeneration = generation,
                folderCountCache = emptyMap(),
                folderCountGeneration = countGen,
            )
        }
        connectionOpenJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    service?.close()
                    val credential = repository.credentials.read(connection.id).orEmpty()
                    val newService = RemoteFileServiceFactory.create(connection, credential)
                    service = newService
                    newService.list(path)
                }
            }
            result.onSuccess { items ->
                if (generation != thumbnailGeneration) return@onSuccess
                repository.setLastPath(connection.id, path)
                val currentPreferences = repository.browserPreferences()
                _state.update {
                    if (it.thumbnailGeneration != generation) it
                    else it.copy(
                        isBusy = false,
                        screen = Screen.Browser(connection, path, items.sortedWith(currentPreferences), currentPreferences),
                    )
                }
            }.onFailure { error ->
                if (generation != thumbnailGeneration) return@onFailure
                if (fallbackToRoot && path != connection.normalizedRootPath) {
                    repository.setLastPath(connection.id, connection.normalizedRootPath)
                    openConnection(connection, connection.normalizedRootPath)
                    return@onFailure
                }
                _state.update {
                    if (it.thumbnailGeneration != generation) it
                    else it.copy(isBusy = false, message = error.message ?: "폴더를 열 수 없습니다.")
                }
            }
        }
    }

    fun resumeConnection(connection: RemoteConnection) {
        openConnection(
            connection,
            connection.normalizedRootPath,
            fallbackToRoot = false,
        )
    }

    /** The connection [resumeLastLocation] would reopen, or null when there is no saved last folder (same conditions, nothing new stored). */
    fun lastLocationConnection(): RemoteConnection? {
        val connectionId = repository.lastConnectionId() ?: return null
        repository.lastPath(connectionId) ?: return null
        return _state.value.connections.firstOrNull { it.id == connectionId }
    }

    fun resumeLastLocation() {
        // 일반 모드에서는 비공개 출처 편집 세션을 되살리지 않는다(내보내기·저장은 계속 돌고, 프라이버시 모드에서만 돌아올 수 있다).
        val minimizedSession = _state.value.trailer?.takeIf { _state.value.trailerMinimized }
        if (minimizedSession != null && PrivacyMode.isVisible(minimizedSession.originPrivate)) {
            restoreEditor()
            return
        }
        val connectionId = repository.lastConnectionId()
        val connection = connectionId?.let { id -> _state.value.connections.firstOrNull { it.id == id } }
        val path = connectionId?.let { repository.lastPath(it) }
        if (connection != null && !path.isNullOrBlank()) {
            openConnection(connection, path, fallbackToRoot = true)
        } else {
            selectMainDestination(MainNavigationDestination.FINDER)
            _state.update { it.copy(message = "이전 위치를 열 수 없습니다. 파인더에서 연결을 선택해 주세요.") }
        }
    }

    fun loadRemoteThumbnail(item: RemoteFileItem) {
        if (!item.isDirectory && !item.isImage && !item.isVideo) return
        val browser = _state.value.screen as? Screen.Browser ?: return
        if (browser.items.none { it.id == item.id && it.path == item.path }) return
        val activeService = service ?: return
        val generation = thumbnailGeneration
        val requestKey = "$generation\u0000${browser.connection.id}\u0000${item.id}\u0000${item.path}\u0000${item.size}\u0000${item.modifiedAt}"
        if (!thumbnailRequests.add(requestKey)) return
        val connectionId = browser.connection.id
        val path = browser.path
        val job = viewModelScope.launch {
            val bitmap = try {
                thumbnailRepository.load(browser.connection, item, activeService)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // Traffic limit or network error must not crash the UI; allow a bounded number of later re-requests.
                if (generation == thumbnailGeneration) {
                    val failures = (thumbnailFailures[requestKey] ?: 0) + 1
                    thumbnailFailures[requestKey] = failures
                    if (failures < MAX_THUMBNAIL_UI_ATTEMPTS) thumbnailRequests.remove(requestKey)
                }
                null
            } ?: return@launch
            val current = _state.value.screen as? Screen.Browser ?: return@launch
            if (generation != thumbnailGeneration || current.connection.id != connectionId || current.path != path ||
                current.items.none { it.id == item.id && it.path == item.path }
            ) return@launch
            _state.update { state ->
                if (generation != state.thumbnailGeneration) state
                else state.copy(remoteThumbnails = state.remoteThumbnails + (item.id to bitmap))
            }
        }
        thumbnailJobs[requestKey] = job
        job.invokeOnCompletion { viewModelScope.launch { if (thumbnailJobs[requestKey] === job) thumbnailJobs.remove(requestKey) } }
    }

    fun loadFolderChildCount(item: RemoteFileItem) {
        if (!item.isDirectory) return
        val browser = _state.value.screen as? Screen.Browser ?: return
        if (browser.items.none { it.id == item.id && it.path == item.path }) return
        val activeConnection = browser.connection
        val preferencesSnapshot = browser.preferences
        val generation = folderCountGeneration
        val cacheKey = "${activeConnection.id}\u0000${item.path}\u0000${preferencesSnapshot.showHiddenFiles}\u0000$generation"

        if (!folderCountRequests.add(cacheKey)) return

        _state.update { current ->
            if (current.folderCountGeneration != generation) current
            else current.copy(
                folderCountCache = current.folderCountCache + (item.id to FolderChildCountState.Loading)
            )
        }

        viewModelScope.launch {
            folderCountSemaphore.withPermit {
                val currentBrowser = _state.value.screen as? Screen.Browser
                if (generation != folderCountGeneration || currentBrowser == null ||
                    currentBrowser.connection.id != activeConnection.id ||
                    currentBrowser.items.none { it.id == item.id && it.path == item.path }
                ) return@withPermit

                val countState = withContext(Dispatchers.IO) {
                    try {
                        val activeService = service ?: run {
                            val credential = repository.credentials.read(activeConnection.id).orEmpty()
                            RemoteFileServiceFactory.create(activeConnection, credential).also { service = it }
                        }
                        val rawEntries = activeService.list(item.path)
                        val visibleEntries = rawEntries.filter { entry ->
                            if (entry.name.isBlank() || entry.name == "." || entry.name == "..") return@filter false
                            val normEntryPath = entry.path.trimEnd('/')
                            val normItemPath = item.path.trimEnd('/')
                            if (normEntryPath == normItemPath) return@filter false
                            if (!preferencesSnapshot.showHiddenFiles && entry.name.startsWith('.')) return@filter false
                            true
                        }
                        val folderCount = visibleEntries.count { it.isDirectory }
                        val fileCount = visibleEntries.count { !it.isDirectory }
                        FolderChildCountState.Ready(folderCount, fileCount)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        FolderChildCountState.Error
                    }
                }

                val nowBrowser = _state.value.screen as? Screen.Browser
                if (generation != folderCountGeneration || nowBrowser == null ||
                    nowBrowser.connection.id != activeConnection.id ||
                    nowBrowser.items.none { it.id == item.id && it.path == item.path }
                ) return@withPermit

                _state.update { current ->
                    if (current.folderCountGeneration != generation) current
                    else current.copy(
                        folderCountCache = current.folderCountCache + (item.id to countState)
                    )
                }
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
        stopInlineAudio()
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
        val filesToClean = synchronized(trackedOwnedPreviewFiles) {
            val list = trackedOwnedPreviewFiles.toList()
            trackedOwnedPreviewFiles.clear()
            list
        }
        viewModelScope.launch(Dispatchers.IO) {
            filesToClean.forEach { runCatching { it.delete() } }
        }
        _state.update {
            it.copy(
                imagePreview = null,
                download = if (clearsPreviewDownload) null else it.download,
            )
        }
    }

    fun selectImagePreviewIndex(targetIndex: Int) {
        val preview = _state.value.imagePreview ?: return
        if (preview.images.isEmpty()) return
        val index = targetIndex.coerceIn(0, preview.images.lastIndex)
        if (index == preview.index) return
        val connection = preview.connection
        if (connection != null) {
            prepareRemoteFile(
                item = preview.images[index],
                action = RemoteFileAction.PREVIEW,
                connection = connection,
                imageSequence = preview.images,
            )
        } else {
            prepareLocalPreview(preview.images, preview.localFiles, index)
        }
    }

    fun moveImagePreview(offset: Int) {
        val preview = _state.value.imagePreview ?: return
        selectImagePreviewIndex(preview.index + offset)
    }

    fun shareImagePreview() {
        val preview = _state.value.imagePreview ?: return
        val item = preview.images.getOrNull(preview.index) ?: return
        preview.remoteStream?.let {
            val connection = preview.connection ?: return
            prepareRemoteFile(item, RemoteFileAction.SHARE, connection = connection)
            return
        }
        runCatching {
            launchPreparedFile(item, preview.cachedFile, RemoteFileAction.SHARE)
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
        val item = preview.images.getOrNull(preview.index) ?: return
        val connection = preview.connection
        if (preview.remoteStream != null && connection != null) {
            invalidateImagePreviewRequests()
            prepareRemoteFile(item, RemoteFileAction.PREVIEW, connection = connection, forceExternal = true)
            return
        }
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
                remoteItemId = item.id,
                size = item.size,
                modifiedAt = item.modifiedAt,
                mimeType = item.mimeType,
                thumbnailUrl = item.thumbnailUrl,
            )
        )
        _state.update { it.copy(remoteFavorites = favorites) }
        invalidateFavoriteThumbnails()
    }

    fun removeFavorite(favorite: RemoteFavorite) {
        val remaining = _state.value.remoteFavorites.filterNot { it.id == favorite.id }
        if (remaining.size == _state.value.remoteFavorites.size) return
        favoriteRepository.saveRemote(remaining)
        favoriteThumbnailJobs.remove(favorite.id)?.cancel()
        _state.update {
            it.copy(
                remoteFavorites = remaining,
                favoriteThumbnails = it.favoriteThumbnails - favorite.id,
            )
        }
    }

    fun moveFavorite(favoriteId: String, offset: Int) {
        val values = _state.value.remoteFavorites.toMutableList()
        val from = values.indexOfFirst { it.id == favoriteId }
        if (from < 0) return
        val to = (from + offset).coerceIn(0, values.lastIndex)
        if (from == to) return
        values.add(to, values.removeAt(from))
        favoriteRepository.saveRemote(values)
        _state.update { it.copy(remoteFavorites = values) }
    }

    /**
     * Service owned by one home favorite action flow. It is created from the favorite's own connection and never shares,
     * swaps or closes the browser's [service]. Every user (the visible panel/pickers flow, the background lookup and each
     * deferred command) holds a lease; the service closes only after the last lease is released.
     */
    private class FavoriteActionServiceOwner(val connection: RemoteConnection, val service: RemoteFileService) {
        private var leases = 1
        private var closed = false

        @Synchronized fun acquire(): Boolean {
            if (closed) return false
            leases++
            return true
        }

        /** Returns true exactly once, when this release ended the last lease and the caller must close [service]. */
        @Synchronized fun release(): Boolean {
            if (closed) return false
            leases--
            if (leases > 0) return false
            closed = true
            return true
        }

        @Synchronized fun forceClose(): Boolean {
            if (closed) return false
            closed = true
            return true
        }
    }

    private var homeFavoriteToken = 0L
    private var homeFavoritePrepJob: Job? = null
    /** UI lease of the current home flow's owner; null while preparing, for PhoneHard and for unavailable targets. */
    private var homeFavoriteOwner: FavoriteActionServiceOwner? = null
    private val liveFavoriteOwners = java.util.Collections.synchronizedSet(mutableSetOf<FavoriteActionServiceOwner>())

    private fun createFavoriteActionOwner(connection: RemoteConnection): FavoriteActionServiceOwner {
        check(_state.value.connections.any { it.id == connection.id }) {
            "연결 정보를 찾을 수 없습니다. 연결을 다시 연 뒤 시도해 주세요."
        }
        val credential = repository.credentials.read(connection.id)
        val needsStoredToken = connection.kind == com.armsone.nasfinder.model.ConnectionKind.DROPBOX ||
            connection.kind == com.armsone.nasfinder.model.ConnectionKind.ONEDRIVE ||
            connection.kind == com.armsone.nasfinder.model.ConnectionKind.GOOGLE_DRIVE
        check(!needsStoredToken || !credential.isNullOrBlank()) {
            "${connection.name}의 저장된 로그인 정보를 읽을 수 없습니다. 다시 로그인한 뒤 시도해 주세요."
        }
        return FavoriteActionServiceOwner(connection, RemoteFileServiceFactory.create(connection, credential.orEmpty()))
            .also { liveFavoriteOwners.add(it) }
    }

    private fun releaseFavoriteOwner(owner: FavoriteActionServiceOwner) {
        if (!owner.release()) return
        liveFavoriteOwners.remove(owner)
        viewModelScope.launch(Dispatchers.IO + NonCancellable) { runCatching { owner.service.close() } }
    }

    /** Runs [block] with a fresh lease on [previous] or, once that flow has fully closed, with a new owner of its connection. */
    private fun withRenewedFavoriteOwner(previous: FavoriteActionServiceOwner, block: (FavoriteActionServiceOwner) -> Unit) {
        if (previous.acquire()) {
            block(previous)
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { createFavoriteActionOwner(previous.connection) } }
                .onSuccess(block)
                .onFailure { error -> _state.update { it.copy(message = error.message ?: "원격 연결을 다시 열 수 없습니다.") } }
        }
    }

    /**
     * Long-press-release on a home favorite: shows the standard item task panel over the unchanged home screen. Screen,
     * history, browser path and the browser's own [service] are never touched; the containing folder is only listed in the
     * background by a dedicated owner to validate the fresh current target (same connection, ID and kind).
     */
    fun openFavoriteActions(favorite: RemoteFavorite) {
        endFavoriteActions()
        val token = ++homeFavoriteToken
        _state.update { it.copy(homeFavoriteAction = HomeFavoriteAction(token, favorite, HomeFavoriteTarget.Preparing)) }
        if (favorite.connectionId == "phonehard") {
            prepareHomePhoneHardFavorite(token, favorite)
            return
        }
        val connection = _state.value.connections.firstOrNull { it.id == favorite.connectionId }
        if (connection == null) {
            setHomeFavoriteTarget(token, HomeFavoriteTarget.Unavailable("이 즐겨찾기의 네트워크 연결을 찾을 수 없습니다."))
            return
        }
        val parent = remoteParentPath(favorite.path, connection.normalizedRootPath)
        if (parent == null) {
            setHomeFavoriteTarget(token, HomeFavoriteTarget.Unavailable("연결의 최상위 폴더 즐겨찾기는 파일 작업을 쓸 수 없습니다."))
            return
        }
        val preferences = repository.browserPreferences()
        homeFavoritePrepJob = viewModelScope.launch {
            var created: FavoriteActionServiceOwner? = null
            try {
                val items = withContext(Dispatchers.IO) {
                    val owner = createFavoriteActionOwner(connection)
                    created = owner
                    owner.service.list(parent)
                }
                // A favorite holding a remote ID binds only to the fresh item with that same ID and kind (its current path/name
                // may differ after a rename); an ID-less legacy favorite binds only to the exact path and kind. No other fallback.
                val itemId = favorite.remoteItemId?.takeIf { it.isNotBlank() }
                val target = if (itemId != null) {
                    items.firstOrNull { it.id == itemId && it.isDirectory == favorite.isDirectory }
                } else {
                    items.firstOrNull { it.path == favorite.path && it.isDirectory == favorite.isDirectory }
                }
                if (token != homeFavoriteToken) return@launch
                if (target == null) {
                    setHomeFavoriteTarget(token, HomeFavoriteTarget.Unavailable("‘${favorite.name}’ 항목을 원래 폴더에서 찾을 수 없습니다."))
                } else {
                    homeFavoriteOwner = created
                    created = null
                    setHomeFavoriteTarget(token, HomeFavoriteTarget.Remote(connection, parent, target, preferences))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (token == homeFavoriteToken) {
                    setHomeFavoriteTarget(token, HomeFavoriteTarget.Unavailable(error.message ?: "즐겨찾기 항목을 확인하지 못했습니다."))
                }
            } finally {
                created?.let(::releaseFavoriteOwner)
            }
        }
    }

    private fun prepareHomePhoneHardFavorite(token: Long, favorite: RemoteFavorite) {
        homeFavoritePrepJob = viewModelScope.launch {
            val path = favorite.path.trim().removePrefix("/").removeSuffix("/")
            val parent = path.substringBeforeLast('/', "").ifEmpty { null }
            // Same main-thread read as refreshInbox(); only the folder listing below runs on IO.
            val loadedFiles = runCatching { inboxFiles() }
            val resolved = withContext(Dispatchers.IO) {
                runCatching {
                    val files = loadedFiles.getOrThrow()
                    // Folders have no stable ID: they keep the explicit legacy folder-path branch. For files a VALID canonical UUID
                    // is recognised first and binds UUID-only, even when the stored path happens to equal that UUID text. Only a
                    // blank ID or the legacy serialized form (`id ?: norm`, i.e. the ID equals the normalized path) may bind by
                    // exact relative path; any other non-UUID ID fails closed.
                    val rawFileId = favorite.remoteItemId
                    val fileUuid = rawFileId?.takeIf { it.isNotBlank() }?.let { raw ->
                        runCatching { UUID.fromString(raw) }.getOrNull()?.takeIf { it.toString() == raw.lowercase() }
                    }
                    val legacyPathKeyed = fileUuid == null &&
                        (rawFileId.isNullOrBlank() || rawFileId.trim().removePrefix("/").removeSuffix("/") == path)
                    val target: HomeFavoriteTarget? = if (favorite.isDirectory) {
                        inboxStore.listPhoneHardFolder(parent)
                            .firstOrNull { it.kind == com.armsone.nasfinder.data.PhoneHardEntryKind.DIRECTORY && it.relativePath == path }
                            ?.let { HomeFavoriteTarget.PhoneHardFolder(it) }
                    } else if (fileUuid != null) {
                        files.firstOrNull { it.id == fileUuid }?.let { HomeFavoriteTarget.PhoneHardFile(it) }
                    } else if (legacyPathKeyed) {
                        files.firstOrNull { it.relativePath == path }?.let { HomeFavoriteTarget.PhoneHardFile(it) }
                    } else {
                        null
                    }
                    files to target
                }
            }
            if (token != homeFavoriteToken) return@launch
            resolved.onSuccess { (files, target) ->
                _state.update { it.copy(inboxFiles = files) }
                setHomeFavoriteTarget(
                    token,
                    target ?: HomeFavoriteTarget.Unavailable("‘${favorite.name}’ 항목을 폰하드에서 찾을 수 없습니다."),
                )
            }.onFailure { error ->
                setHomeFavoriteTarget(token, HomeFavoriteTarget.Unavailable(error.message ?: "폰하드 항목을 확인하지 못했습니다."))
            }
        }
    }

    private fun setHomeFavoriteTarget(token: Long, target: HomeFavoriteTarget) {
        _state.update { current ->
            val action = current.homeFavoriteAction
            if (action == null || action.token != token) current
            else current.copy(homeFavoriteAction = action.copy(target = target))
        }
    }

    /**
     * Ends the home favorite flow [token] (or the current one): the panel/picker UI lease is released, so the dedicated
     * service closes now unless a deferred command still holds its own lease. The home screen itself is never changed.
     */
    fun endFavoriteActions(token: Long? = null) {
        if (token != null && token != homeFavoriteToken) return
        homeFavoritePrepJob?.cancel()
        homeFavoritePrepJob = null
        homeFavoriteOwner?.let(::releaseFavoriteOwner)
        homeFavoriteOwner = null
        _state.update {
            if (it.homeFavoriteAction == null || (token != null && it.homeFavoriteAction.token != token)) it
            else it.copy(homeFavoriteAction = null)
        }
    }

    private fun homeRemoteTarget(token: Long): HomeFavoriteTarget.Remote? =
        _state.value.homeFavoriteAction?.takeIf { it.token == token }?.target as? HomeFavoriteTarget.Remote

    /** A command lease on the current flow's owner, or null (with a message) when the flow is no longer current. */
    private fun leaseHomeFavoriteOwner(token: Long): FavoriteActionServiceOwner? {
        val owner = homeFavoriteOwner?.takeIf { token == homeFavoriteToken && it.acquire() }
        if (owner == null) _state.update { it.copy(message = "즐겨찾기 작업이 끝났습니다. 다시 길게 눌러 주세요.") }
        return owner
    }

    /** 선택: opens the fresh target's own folder (an explicit command, never the hold itself) with only that entry selected. */
    fun homeFavoriteSelect(token: Long) {
        val action = _state.value.homeFavoriteAction?.takeIf { it.token == token } ?: return
        when (val target = action.target) {
            is HomeFavoriteTarget.Remote -> {
                endFavoriteActions(token)
                openConnection(target.connection, target.parentPath)
                _state.update {
                    it.copy(browserSelectRequest = BrowserSelectRequest(target.connection.id, target.parentPath, target.item.id, target.item.isDirectory, target.item.name))
                }
            }
            is HomeFavoriteTarget.PhoneHardFolder -> selectInPhoneHard(token, target.folder.relativePath, target.folder.relativePath, true, target.folder.name)
            is HomeFavoriteTarget.PhoneHardFile -> selectInPhoneHard(token, target.file.id.toString(), target.file.relativePath, false, target.file.originalFilename)
            else -> Unit
        }
    }

    private fun selectInPhoneHard(token: Long, itemId: String, relativePath: String, isDirectory: Boolean, name: String) {
        val parent = relativePath.trim().removePrefix("/").removeSuffix("/").substringBeforeLast('/', "").ifEmpty { null }
        endFavoriteActions(token)
        show(Screen.Inbox)
        _state.update {
            it.copy(initialInboxSubpath = parent, browserSelectRequest = BrowserSelectRequest("phonehard", parent, itemId, isDirectory, name))
        }
    }

    /** Drops [request] only if it is still the pending one, so a newer request is never cleared by an older browser. */
    fun consumeBrowserSelectRequest(request: BrowserSelectRequest, missingMessage: String? = null) {
        _state.update {
            if (it.browserSelectRequest !== request) it
            else it.copy(browserSelectRequest = null, message = missingMessage ?: it.message)
        }
    }

    /** 복사/이동: the existing destination flow is the browser, so this explicit command opens the target's folder with it pending. */
    fun homeFavoriteBeginTransfer(token: Long, action: RemoteTransferAction) {
        val target = homeRemoteTarget(token) ?: return
        endFavoriteActions(token)
        openConnection(target.connection, target.parentPath)
        _state.update {
            it.copy(
                pendingTransfer = PendingRemoteTransfer(target.connection.id, listOf(target.item), action),
                pendingInboxUpload = null,
            )
        }
    }

    fun homeFavoriteRename(token: Long, newName: String) {
        val target = homeRemoteTarget(token) ?: return
        val owner = leaseHomeFavoriteOwner(token) ?: return
        runHomeFavoriteMutation(owner, "이름을 변경했습니다.") { it.rename(target.item, newName.trim()) }
    }

    fun homeFavoriteDelete(token: Long) {
        val target = homeRemoteTarget(token) ?: return
        val owner = leaseHomeFavoriteOwner(token) ?: return
        runHomeFavoriteMutation(owner, if (target.item.isDirectory) "폴더를 삭제했습니다." else "파일을 삭제했습니다.") {
            it.delete(listOf(target.item))
        }
    }

    /** Runs a mutation on the leased owner service; the browser's listing/service stay untouched and the lease always ends. */
    private fun runHomeFavoriteMutation(
        owner: FavoriteActionServiceOwner,
        successMessage: String,
        operation: suspend (RemoteFileService) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                _state.update { it.copy(isBusy = true, message = null) }
                val result = withContext(Dispatchers.IO) {
                    try {
                        Result.success(operation(owner.service))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                }
                _state.update {
                    it.copy(isBusy = false, message = result.exceptionOrNull()?.let { error -> error.message ?: "원격 파일 작업을 완료하지 못했습니다." } ?: successMessage)
                }
            } finally {
                releaseFavoriteOwner(owner)
            }
        }
    }

    fun homeFavoriteShare(token: Long) {
        val target = homeRemoteTarget(token) ?: return
        val owner = leaseHomeFavoriteOwner(token) ?: return
        shareRemoteItems(target.connection, listOf(target.item), onFinished = { releaseFavoriteOwner(owner) }) { block -> block(owner.service) }
    }

    fun homeFavoriteDownload(token: Long, destination: NetworkDownloadDestination) {
        val target = homeRemoteTarget(token) ?: return
        downloadRemoteItems(target.connection, listOf(target.item), destination)
    }

    fun homeFavoriteTrailer(token: Long) {
        val target = homeRemoteTarget(token) ?: return
        val owner = leaseHomeFavoriteOwner(token) ?: return
        startRemoteTrailer(listOf(target.item), owner.service, owner)
    }

    fun homeFavoriteMusicOverlay(token: Long) {
        val target = homeRemoteTarget(token) ?: return
        val owner = leaseHomeFavoriteOwner(token) ?: return
        startRemoteMusicOverlay(listOf(target.item), null, activeService = owner.service, owner = owner)
    }

    /** Checks the folder target for subfolders on the owner service; [onResult] runs only while flow [token] is current. */
    fun homeFavoriteCheckSubfolders(token: Long, onResult: (Boolean) -> Unit, onFailure: () -> Unit) {
        val target = homeRemoteTarget(token)?.takeIf { it.item.isDirectory } ?: return onFailure()
        val owner = leaseHomeFavoriteOwner(token) ?: return onFailure()
        checkRemoteFolderHasSubfolders(
            folder = target.item,
            connection = target.connection,
            preferences = target.preferences,
            serviceFor = { owner.service },
            isStillCurrent = { token == homeFavoriteToken },
            onFinished = { releaseFavoriteOwner(owner) },
            onFailure = onFailure,
            onResult = onResult,
        )
    }

    fun homeFavoriteFolderTrailer(token: Long, includeSubfolders: Boolean) {
        val target = homeRemoteTarget(token)?.takeIf { it.item.isDirectory } ?: return
        val owner = leaseHomeFavoriteOwner(token) ?: return
        startRemoteFolderTrailer(target.item, includeSubfolders, target.connection, target.preferences, owner)
    }

    fun homeFavoriteFolderMusicOverlay(token: Long, includeSubfolders: Boolean) {
        val target = homeRemoteTarget(token)?.takeIf { it.item.isDirectory } ?: return
        val owner = leaseHomeFavoriteOwner(token) ?: return
        startRemoteFolderMusicOverlay(target.item, includeSubfolders, target.connection, target.preferences, owner)
    }

    private fun invalidateFavoriteThumbnails() {
        favoriteThumbnailGeneration++
        favoriteThumbnailJobs.values.forEach { it.cancel() }
        favoriteThumbnailJobs.clear()
        favoriteThumbnailRequests.clear()
        _state.update {
            it.copy(
                favoriteThumbnails = emptyMap(),
                favoriteThumbnailGeneration = favoriteThumbnailGeneration,
            )
        }
    }

    private fun resolvePhoneHardDiskFile(relativePath: String): File {
        val rootCanonical = inboxStore.rootDirectory.canonicalFile
        val norm = relativePath.trim().removePrefix("/").removeSuffix("/")
        val file = if (norm.isEmpty()) rootCanonical else File(rootCanonical, norm).canonicalFile
        val isContained = file.canonicalPath == rootCanonical.canonicalPath ||
            file.canonicalPath.startsWith(rootCanonical.canonicalPath + File.separator)
        return if (isContained) file else File(rootCanonical, "__invalid_phonehard_path__")
    }

    fun loadFavoriteThumbnail(favorite: RemoteFavorite) {
        if (favorite.connectionId == "phonehard") {
            val generation = favoriteThumbnailGeneration
            val requestKey = "$generation\u0000phonehard\u0000${favorite.id}\u0000${favorite.path}"
            if (!favoriteThumbnailRequests.add(requestKey)) return
            favoriteThumbnailJobs[favorite.id] = viewModelScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    val diskFile = resolvePhoneHardDiskFile(favorite.path)
                    if (diskFile.exists() && !favorite.isDirectory) {
                        val uri = runCatching {
                            androidx.core.content.FileProvider.getUriForFile(application, "${application.packageName}.sharefiles", diskFile)
                        }.getOrNull()
                        decodeInboxThumbnail(
                            application,
                            uri,
                            diskFile,
                            isImage = favorite.mimeType?.startsWith("image/") == true || isPhoneHardImage(diskFile.name),
                            isVideo = favorite.mimeType?.startsWith("video/") == true || isPhoneHardVideo(diskFile.name),
                            isPdf = favorite.mimeType == "application/pdf" || diskFile.name.endsWith(".pdf", ignoreCase = true),
                            maxPixelSize = 160,
                        )
                    } else null
                }
                if (bitmap != null && generation == favoriteThumbnailGeneration) {
                    _state.update { state ->
                        if (generation != state.favoriteThumbnailGeneration) state
                        else state.copy(favoriteThumbnails = state.favoriteThumbnails + (favorite.id to bitmap))
                    }
                }
            }
            return
        }
        val connection = _state.value.connections.firstOrNull { it.id == favorite.connectionId } ?: return
        val generation = favoriteThumbnailGeneration
        val requestKey = "$generation\u0000${favorite.connectionId}\u0000${favorite.id}\u0000${favorite.path}"
        if (!favoriteThumbnailRequests.add(requestKey)) return

        favoriteThumbnailJobs[favorite.id] = viewModelScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                runCatching {
                    val credential = repository.credentials.read(connection.id).orEmpty()
                    RemoteFileServiceFactory.create(connection, credential).use { activeService ->
                        val resolvedItem = FavoriteItemResolver.resolve(favorite, connection, activeService)
                        resolvedItem to thumbnailRepository.load(connection, resolvedItem, activeService)
                    }
                }.getOrNull()
            }
            if (resolved != null && generation == favoriteThumbnailGeneration) {
                val (resolvedItem, bitmap) = resolved
                if (FavoriteItemResolver.hasMetadataChanged(favorite, resolvedItem)) {
                    updateFavoriteMetadata(favorite, resolvedItem)
                }
                if (bitmap == null) return@launch
                _state.update { state ->
                    if (generation != state.favoriteThumbnailGeneration) state
                    else state.copy(favoriteThumbnails = state.favoriteThumbnails + (favorite.id to bitmap))
                }
            }
        }
    }

    private fun updateFavoriteMetadata(favorite: RemoteFavorite, item: RemoteFileItem) {
        val updatedList = favoriteRepository.updateRemoteMetadata(favorite.id, item)
        _state.update { it.copy(remoteFavorites = updatedList) }
    }

    fun openFavorite(favorite: RemoteFavorite) {
        if (favorite.connectionId == "phonehard") {
            if (favorite.isDirectory) {
                _state.update { it.copy(initialInboxSubpath = favorite.path) }
                show(Screen.Inbox)
            } else {
                val file = _state.value.inboxFiles.firstOrNull { it.id.toString() == favorite.remoteItemId || it.relativePath == favorite.path }
                if (file != null) {
                    previewInboxFile(file)
                } else {
                    val diskFile = resolvePhoneHardDiskFile(favorite.path)
                    if (diskFile.exists()) {
                        val displayItem = InboxDisplayItem(
                            id = runCatching { java.util.UUID.fromString(favorite.remoteItemId) }.getOrDefault(java.util.UUID.randomUUID()),
                            originalFilename = favorite.name,
                            file = diskFile,
                            relativePath = favorite.path,
                            byteCount = favorite.size ?: diskFile.length(),
                            importedAt = favorite.modifiedAt ?: java.time.Instant.ofEpochMilli(diskFile.lastModified()),
                            mimeType = favorite.mimeType,
                        )
                        previewInboxFile(displayItem)
                    } else {
                        _state.update { it.copy(message = "폰하드 파일을 찾을 수 없습니다: ${favorite.name}") }
                    }
                }
            }
            return
        }
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
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    service?.close()
                    val credential = repository.credentials.read(connection.id).orEmpty()
                    val activeService = RemoteFileServiceFactory.create(
                        connection,
                        credential,
                    ).also { service = it }
                    val item = FavoriteItemResolver.resolve(favorite, connection, activeService)
                    val sequence = FavoriteItemResolver.resolveFavoritePreviewSequence(
                        activeFavorite = favorite,
                        resolvedActiveItem = item,
                        allFavorites = _state.value.remoteFavorites,
                        connection = connection,
                        activeService = activeService,
                    )
                    if (FavoriteItemResolver.hasMetadataChanged(favorite, item)) {
                        updateFavoriteMetadata(favorite, item)
                    }
                    item to sequence
                }
            }
            result.onSuccess { (item, sequence) ->
                _state.update { it.copy(isBusy = false) }
                prepareRemoteFile(
                    item = item,
                    action = RemoteFileAction.PREVIEW,
                    connection = connection,
                    imageSequence = sequence,
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
        if (!item.isDirectory) {
            prepareRemoteFile(item, RemoteFileAction.SHARE)
        } else {
            shareItems(listOf(item))
        }
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
        if (items.isEmpty()) return
        val browser = _state.value.screen as? Screen.Browser ?: return
        // Captured up front so a later connection switch cannot redirect this share.
        val connection = browser.connection
        shareRemoteItems(connection, items) { block -> withDedicatedRemoteService(connection, block) }
    }

    /** Shares [items] of [connection] using the service lent by [withService]; [onFinished] runs once the share is prepared or failed. */
    private fun shareRemoteItems(
        connection: RemoteConnection,
        items: List<RemoteFileItem>,
        onFinished: () -> Unit = {},
        withService: suspend (suspend (RemoteFileService) -> List<File>) -> List<File>,
    ) {
        val selected = items.distinctBy(RemoteFileItem::id)
        val hasDirectory = selected.any(RemoteFileItem::isDirectory)
        viewModelScope.launch {
            try {
                _state.update { it.copy(isBusy = true, message = if (hasDirectory) "공유 항목을 압축하는 중입니다…" else null) }
                // Folder or mixed selection: package all into ONE isolated unique zip
                val zipFile = if (hasDirectory) {
                    val sharesDir = File(application.cacheDir, "shares").apply { mkdirs() }
                    File(sharesDir, "remote_share_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}.zip")
                } else null
                val result = runCatching {
                    val prepared = withContext(Dispatchers.IO) {
                        withService { ownService ->
                            if (zipFile == null) {
                                selected.map { item ->
                                    downloadCache.resolve(
                                        connectionId = connection.id,
                                        item = item,
                                        progress = { _, _ -> },
                                        download = { destination, progress -> ownService.download(item, destination, progress) },
                                    )
                                }
                            } else {
                                writeRemoteShareZip(connection, ownService, selected, zipFile)
                                listOf(zipFile)
                            }
                        }
                    }
                    val shareIntent = if (prepared.size == 1) {
                        NasFinderShareIntentFactory.create(application, prepared)
                    } else {
                        NasFinderShareIntentFactory.createMultiple(application, prepared)
                    }
                    application.startActivity(
                        Intent.createChooser(shareIntent, if (zipFile != null) "압축 파일 공유" else "파일 공유")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                if (result.isFailure) zipFile?.delete()
                _state.update {
                    it.copy(
                        isBusy = false,
                        message = result.exceptionOrNull()?.let { error -> error.message ?: "선택한 항목을 공유하지 못했습니다." },
                    )
                }
            } finally {
                onFinished()
            }
        }
    }

    /**
     * Runs [block] with a service that belongs only to this operation. The browser's shared
     * [service] is never read, reassigned or closed here; the owned service is always closed.
     */
    private suspend fun <T> withDedicatedRemoteService(
        connection: RemoteConnection,
        block: suspend (RemoteFileService) -> T,
    ): T = openDedicatedRemoteService(connection).use { ownService -> block(ownService) }

    /** A service of its own for [connection]; the caller closes it. Fails with a readable reason when the login is unavailable. */
    private suspend fun openDedicatedRemoteService(connection: RemoteConnection): RemoteFileService {
        check(_state.value.connections.any { it.id == connection.id }) {
            "연결 정보를 찾을 수 없습니다. 연결을 다시 연 뒤 시도해 주세요."
        }
        val credential = repository.credentials.read(connection.id)
        val needsStoredToken = connection.kind == com.armsone.nasfinder.model.ConnectionKind.DROPBOX ||
            connection.kind == com.armsone.nasfinder.model.ConnectionKind.ONEDRIVE ||
            connection.kind == com.armsone.nasfinder.model.ConnectionKind.GOOGLE_DRIVE
        check(!needsStoredToken || !credential.isNullOrBlank()) {
            "${connection.name}의 저장된 로그인 정보를 읽을 수 없습니다. 다시 로그인한 뒤 시도해 주세요."
        }
        return RemoteFileServiceFactory.create(connection, credential.orEmpty())
    }

    /** Reserves an entry name that is unique inside [parentPrefix]; folders and files share one namespace. */
    private fun reserveZipEntryName(used: MutableSet<String>, parentPrefix: String, displayName: String): String {
        val safe = displayName.replace('/', '_').replace('\\', '_').trim()
            .takeUnless { it.isEmpty() || it == "." || it == ".." } ?: "이름 없음"
        val dot = safe.lastIndexOf('.')
        val stem = if (dot > 0) safe.substring(0, dot) else safe
        val extension = if (dot > 0) safe.substring(dot) else ""
        var candidate = safe
        var suffix = 2
        while (!used.add((parentPrefix + candidate).lowercase())) {
            candidate = "$stem ($suffix)$extension"
            suffix++
        }
        return parentPrefix + candidate
    }

    /**
     * Writes every selected file and the full tree of every selected folder (hidden and empty
     * folders included) into [zipFile]. Depth overflow, a revisited folder or an unreadable
     * listing throws, so the caller never shares a silently truncated archive.
     */
    private suspend fun writeRemoteShareZip(
        connection: RemoteConnection,
        ownService: RemoteFileService,
        selected: List<RemoteFileItem>,
        zipFile: File,
    ) {
        val maxDepth = DefaultNetworkDownloadCoordinator.MAX_REMOTE_DIRECTORY_DEPTH
        val selectedDirPaths = selected.filter(RemoteFileItem::isDirectory).map { it.path.trimEnd('/') }
        val roots = selected.filter { candidate ->
            val path = candidate.path.trimEnd('/')
            selectedDirPaths.none { it != path && path.startsWith("$it/") }
        }
        java.util.zip.ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
            val usedNames = mutableSetOf<String>()
            val visitedPaths = mutableSetOf<String>()
            val visitedIds = mutableSetOf<String>()
            // remote folder, zip entry name ending in '/', depth below its selected root
            val queue = java.util.ArrayDeque<Triple<RemoteFileItem, String, Int>>()
            suspend fun addFile(item: RemoteFileItem, entryName: String) {
                val diskFile = downloadCache.resolve(
                    connectionId = connection.id,
                    item = item,
                    progress = { _, _ -> },
                    download = { destination, progress -> ownService.download(item, destination, progress) },
                )
                zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                diskFile.inputStream().buffered().use { it.copyTo(zos) }
                zos.closeEntry()
            }
            for (item in roots) {
                if (item.isDirectory) queue.add(Triple(item, reserveZipEntryName(usedNames, "", item.name) + "/", 0))
                else addFile(item, reserveZipEntryName(usedNames, "", item.name))
            }
            while (queue.isNotEmpty()) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val (folder, prefix, depth) = queue.removeFirst()
                val folderPath = folder.path.trimEnd('/')
                if (depth > maxDepth) {
                    throw java.io.IOException("폴더가 ${maxDepth}단계보다 깊어 공유할 수 없습니다: ${folder.path}")
                }
                if (!visitedPaths.add(folderPath) || (folder.id.isNotBlank() && !visitedIds.add(folder.id))) {
                    throw java.io.IOException("같은 폴더가 반복되는 구조여서 공유할 수 없습니다: ${folder.path}")
                }
                zos.putNextEntry(java.util.zip.ZipEntry(prefix))
                zos.closeEntry()
                for (child in ownService.list(folder.path)) {
                    if (child.name.isBlank() || child.name == "." || child.name == "..") continue
                    if (child.isDirectory) {
                        // Some servers list the folder itself; that is not a child.
                        if (child.path.trimEnd('/') == folderPath) continue
                        queue.add(Triple(child, reserveZipEntryName(usedNames, prefix, child.name) + "/", depth + 1))
                    } else {
                        addFile(child, reserveZipEntryName(usedNames, prefix, child.name))
                    }
                }
            }
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
            _state.update { it.copy(message = "보낼 폰하드 파일을 찾을 수 없습니다.") }
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
        // 가져오기를 시작하는 순간의 출처를 고정한다(복사가 끝나 저장할 때의 모드가 아니다).
        val jobPrivate = PrivacyMode.isActive
        val imported = withContext(Dispatchers.IO) {
            runCatching {
                val source = validateWebDownload(file)
                val testItem = RemoteFileItem(filename, filename, filename, false, mimeType = mimeType)
                val destinationSubdir = if (testItem.isAudio) SharedInboxStore.MUSIC_DIRECTORY_NAME else null
                source.inputStream().buffered().use { input ->
                    inboxStore.import(filename, mimeType, input, destinationSubdirectory = destinationSubdir, isPrivate = jobPrivate)
                }
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
                        screen = if (connection == null && it.trailer == null) Screen.Inbox else it.screen,
                        message = if (connection == null) "폰하드에 저장했습니다." else null,
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
                    require(item.file.isFile) { "보낼 폰하드 파일을 찾을 수 없습니다." }
                    val stagedFile = withContext(Dispatchers.IO) {
                        val uploadsRoot = File(application.cacheDir, "inbox-uploads").canonicalFile
                        check(uploadsRoot.mkdirs() || uploadsRoot.isDirectory) { "업로드 준비 공간을 만들 수 없습니다." }
                        val directory = File(uploadsRoot, item.id.toString()).canonicalFile
                        check(directory.parentFile == uploadsRoot) { "안전하지 않은 업로드 준비 경로입니다." }
                        check(directory.mkdirs() || directory.isDirectory) { "업로드 준비 폴더를 만들 수 없습니다." }
                        stagedDirectory = directory
                        val file = File(directory, item.originalFilename).canonicalFile
                        check(file.parentFile == directory) { "안전하지 않은 폰하드 파일 이름입니다." }
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
            val countGen = ++folderCountGeneration
            folderCountRequests.clear()
            _state.update {
                it.copy(
                    isBusy = false,
                    screen = browser.copy(items = items.sortedWith(browser.preferences)),
                    pendingTransfer = if (clearTransfer) null else it.pendingTransfer,
                    folderCountCache = emptyMap(),
                    folderCountGeneration = countGen,
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
        forceExternal: Boolean = false,
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
        val previewImages = if (internalPreview) {
            imageSequence ?: browser?.items
                ?.filter { candidate -> !candidate.isDirectory && candidate.builtInPreviewKind() != null }
                .orEmpty()
                .ifEmpty { listOf(item) }
        } else emptyList()
        val previewIndex = previewImages.indexOfFirst { it.id == item.id && it.path == item.path }
            .takeIf { it >= 0 } ?: 0
        val mediaRoute = if (previewKind in setOf(BuiltInPreviewKind.AUDIO, BuiltInPreviewKind.VIDEO)) {
            RemoteMediaRoutePolicy.route(item.name, activeService.supportsRangeStreaming && item.size > 0)
        } else null
        if (!forceExternal && internalPreview && mediaRoute == RemoteMediaRoute.RANGE_MEDIA3) {
            previewJob?.cancel()
            ++previewRequestGeneration
            val subtitle = RemoteSubtitlePolicy.match(item, browser?.items.orEmpty())
            _state.update {
                it.copy(
                    message = null,
                    download = null,
                    imagePreview = ImagePreviewState(
                        connection = activeConnection,
                        images = previewImages,
                        index = previewIndex,
                        kind = previewKind!!,
                        cachedFile = File(application.cacheDir, "remote-stream-${item.id}"),
                        remoteStream = RemoteMediaStreamSpec(item, subtitle, activeService),
                    ),
                )
            }
            return
        }
        val usesInternalDownloadedPreview = internalPreview && !forceExternal && mediaRoute != RemoteMediaRoute.EXTERNAL_PLAYER
        val requestGeneration = if (usesInternalDownloadedPreview) {
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
                    originPrivate = PrivacyMode.capturedOrigin(PrivacyMode.isPrivateOrigin(activeConnection.id, item.path)),
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
                            if (usesInternalDownloadedPreview && requestGeneration != previewRequestGeneration) return@update current
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
                if (usesInternalDownloadedPreview) {
                    if (requestGeneration != previewRequestGeneration) return@onSuccess
                    val decoded = withContext(Dispatchers.IO) { decodeBuiltInPreview(file, previewKind) }
                    if (requestGeneration != previewRequestGeneration) return@onSuccess
                    if (decoded != null) {
                        _state.update {
                            it.copy(
                                download = null,
                                imagePreview = ImagePreviewState(
                                    connection = activeConnection,
                                    images = previewImages,
                                    index = previewIndex,
                                    kind = previewKind,
                                    bitmap = decoded.bitmap,
                                    cachedFile = file,
                                    pdfPageCount = decoded.pageCount,
                                    animatedImage = decoded.animatedImage,
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
                if (usesInternalDownloadedPreview && requestGeneration != previewRequestGeneration) return@onFailure
                _state.update {
                    it.copy(
                        download = null,
                        message = error.message ?: "${item.name}을(를) 내려받지 못했습니다.",
                    )
                }
            }
        }
        if (usesInternalDownloadedPreview) previewJob = job
    }

    private data class PreviewDecode(val bitmap: Bitmap? = null, val pageCount: Int = 0, val animatedImage: Boolean = false)

    private fun RemoteFileItem.builtInPreviewKind(): BuiltInPreviewKind? =
        BuiltInPreviewPolicy.kind(name, mimeType)

    private fun prepareLocalPreview(
        items: List<RemoteFileItem>,
        files: List<File>,
        index: Int,
    ) {
        val item = items.getOrNull(index) ?: return
        val file = files.getOrNull(index)?.takeIf(File::isFile) ?: run {
            _state.update { it.copy(inboxErrorMessage = "폰하드 파일을 찾을 수 없습니다.") }
            return
        }
        val kind = item.builtInPreviewKind() ?: run {
            runCatching { launchPreparedFile(item, file, RemoteFileAction.PREVIEW) }
                .onFailure { error -> _state.update { it.copy(inboxErrorMessage = error.message ?: "파일을 열 수 없습니다.") } }
            return
        }
        val requestGeneration = ++previewRequestGeneration
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val decoded = withContext(Dispatchers.IO) { decodeBuiltInPreview(file, kind) }
            if (requestGeneration != previewRequestGeneration) return@launch
            if (decoded == null) {
                runCatching { launchPreparedFile(item, file, RemoteFileAction.PREVIEW) }
                    .onFailure { error -> _state.update { it.copy(inboxErrorMessage = error.message ?: "파일을 열 수 없습니다.") } }
                return@launch
            }
            _state.update {
                it.copy(
                    inboxErrorMessage = null,
                    imagePreview = ImagePreviewState(
                        connection = null,
                        images = items,
                        index = index,
                        kind = kind,
                        bitmap = decoded.bitmap,
                        cachedFile = file,
                        localFiles = files,
                        pdfPageCount = decoded.pageCount,
                        animatedImage = decoded.animatedImage,
                    ),
                )
            }
        }
    }

    private fun decodeBuiltInPreview(file: File, kind: BuiltInPreviewKind): PreviewDecode? = when (kind) {
        BuiltInPreviewKind.IMAGE -> decodePreviewBitmap(file)?.let {
            PreviewDecode(it, animatedImage = AnimatedImagePolicy.isAnimatedGif(file))
        }
        BuiltInPreviewKind.PDF -> renderPdfPage(file, 0)?.let { PreviewDecode(it.first, it.second) }
        BuiltInPreviewKind.AUDIO, BuiltInPreviewKind.VIDEO -> if (validateMedia(file)) PreviewDecode() else null
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
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return@runCatching null
        val orientation = runCatching {
            ExifInterface(file).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val transform = exifPixelTransform(orientation)
        if (transform == ExifPixelTransform.NORMAL) decoded else {
            val matrix = Matrix().apply {
                if (transform.rotationDegrees != 0f) postRotate(transform.rotationDegrees)
                if (transform.mirrorHorizontal) postScale(-1f, 1f)
            }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
                if (it !== decoded) decoded.recycle()
            }
        }
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
                val self = ComponentName(application, com.armsone.nasfinder.MainActivity::class.java)
                val hasExternalHandler = application.packageManager
                    .queryIntentActivities(viewIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                    .any { info -> ComponentName(info.activityInfo.packageName, info.activityInfo.name) != self }
                if (!hasExternalHandler) {
                    throw IllegalStateException("이 파일 형식을 열 수 있는 앱이 설치되어 있지 않습니다.")
                }
                try {
                    val chooser = Intent.createChooser(viewIntent, "${item.name} 열기")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(
                            Intent.EXTRA_EXCLUDE_COMPONENTS,
                            arrayOf(self),
                        )
                    application.startActivity(chooser)
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
        val showHiddenChanged = browser.preferences.showHiddenFiles != preferences.showHiddenFiles
        val countGen = if (showHiddenChanged) ++folderCountGeneration else folderCountGeneration
        if (showHiddenChanged) folderCountRequests.clear()
        repository.setBrowserPreferences(preferences)
        _state.update {
            it.copy(
                screen = browser.copy(items = browser.items.sortedWith(preferences), preferences = preferences),
                folderCountCache = if (showHiddenChanged) emptyMap() else it.folderCountCache,
                folderCountGeneration = countGen,
            )
        }
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

    fun setIconPack(pack: InterfaceIconPack) {
        settingsRepository.setIconPack(pack)
        _state.update { it.copy(iconPack = pack) }
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
                        message = "앱 아이콘을 저장했습니다. 다음 앱 실행부터 적용됩니다.",
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
                    invalidateFavoriteThumbnails()
                    _state.update {
                        it.copy(
                            isBusy = false,
                            thumbnailCacheStatistics = statistics,
                            message = "썸네일 캐시를 비웠습니다.",
                        )
                    }
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
            if (incoming.getBooleanExtra(OAuthCallbackActivity.EXTRA_GOOGLE_PHOTOS_CALLBACK, false)) {
                _state.update {
                    it.copy(
                        screen = Screen.GooglePhotos,
                        googlePhotosConnected = succeeded && googlePhotosAccount.isConnected(),
                        googlePhotosPhase = if (succeeded) GooglePhotosPhase.IDLE else GooglePhotosPhase.ERROR,
                        message = if (succeeded) "Google 포토 연결이 완료되었습니다. 사진 선택을 시작해 주세요."
                        else "Google 포토 로그인이 취소되었거나 완료되지 않았습니다.",
                    )
                }
                return
            }
            if (succeeded) refreshDropboxAccountLabels()
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
                inboxErrorMessage = if (selected == null) "요청한 폰하드 파일을 찾을 수 없습니다." else null,
            )
        }
    }

    private fun importViewedUri(intent: Intent) {
        val uri = intent.data ?: run {
            _state.update { it.copy(message = "가져올 파일 주소가 없습니다.") }
            return
        }
        val deliveryId = intent.getLongExtra(com.armsone.nasfinder.MainActivity.EXTRA_DELIVERY_ID, -1L)
        val deliveryKey = if (deliveryId != -1L) "delivery:$deliveryId" else "filter:${intent.filterHashCode()}"
        val signature = "$deliveryKey:${intent.action}:$uri"
        if (signature == importedIntentSignature) return
        importedIntentSignature = signature
        val jobPrivate = PrivacyMode.isActive
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = queryName(uri) ?: uri.lastPathSegment?.substringAfterLast('/')
                        ?.takeIf { it.isNotBlank() } ?: "폰하드 파일"
                    val mimeType = application.contentResolver.getType(uri)
                    require(BuiltInPreviewPolicy.kind(name, mimeType) != null) {
                        "NasFinder 내장 뷰어가 지원하지 않는 파일입니다."
                    }
                    application.contentResolver.openInputStream(uri)?.use { input ->
                        inboxStore.import(name, mimeType, input, isPrivate = jobPrivate)
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
                selected?.let(::previewInboxFile)
                NasFinderAppWidgetProvider.updateAll(application)
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        screen = Screen.Inbox,
                        message = null,
                        inboxErrorMessage = error.message ?: "파일을 폰하드로 가져오지 못했습니다.",
                    )
                }
            }
        }
    }

    fun importSharedIntent(intent: Intent?) {
        val sharedIntent = intent ?: return
        if (sharedIntent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return
        // Explicit delivery ID or filter hash forms the key: a later delivery that reuses the same URI has a new delivery ID,
        // while configuration changes or recompositions reusing the same delivery ID are skipped.
        val deliveryId = sharedIntent.getLongExtra(com.armsone.nasfinder.MainActivity.EXTRA_DELIVERY_ID, -1L)
        val deliveryKey = if (deliveryId != -1L) "delivery:$deliveryId" else "filter:${sharedIntent.filterHashCode()}"
        val signature = "$deliveryKey:${sharedIntent.action}:${sharedIntent.clipData}:${sharedIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)}"
        if (signature == importedIntentSignature) return
        importedIntentSignature = signature
        // Photos/videos are never capped or silently dropped: every URI is kept for the user's two-way choice.
        val allUris = buildList {
            sharedIntent.clipData?.let { clip -> for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(::add) }
            if (isEmpty()) sharedIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(::add)
        }.distinct()
        if (allUris.isEmpty()) return
        val images = mutableListOf<Uri>()
        val videos = mutableListOf<Uri>()
        val others = mutableListOf<Uri>()
        allUris.forEach { uri ->
            val type = runCatching { application.contentResolver.getType(uri) }.getOrNull()
                ?: sharedIntent.type?.takeIf { allUris.size == 1 && !it.endsWith("/*") }
            when {
                type?.startsWith("image/") == true -> images += uri
                type?.startsWith("video/") == true -> videos += uri
                else -> others += uri
            }
        }
        if (images.isNotEmpty() || videos.isNotEmpty()) {
            // The grant on these URIs is temporary: copy this batch (only this batch) into app-private storage right
            // now, keeping order, kind and the original URI, before the two-way choice is even shown.
            val imageSet = images.toHashSet()
            val videoSet = videos.toHashSet()
            val ordered = allUris.filter { it in imageSet || it in videoSet }
            val sources = ordered.map { uri -> ShareIntakeSource(uri, runCatching { application.contentResolver.getType(uri) }.getOrNull(), uri in videoSet) }
            _state.update { it.copy(shareIntakeInProgress = it.shareIntakeInProgress + 1) }
            viewModelScope.launch {
                shareRestoreJob?.join()
                // The same URI can carry new content, so a share is never judged "already pending" from URIs alone:
                // it is always snapshotted first, and only an identical (URI, snapshot hash) delivery is folded away.
                val result = runCatching { PendingShareStaging.intake(application, sources) }
                val incoming = result.getOrNull()
                val duplicateOf = incoming?.let { fresh ->
                    _state.value.pendingMediaShares.firstOrNull { isSameShareRevision(it, fresh) }
                }
                if (incoming != null && duplicateOf != null) {
                    // Redelivery of the same intent (e.g. after process recreation): the existing durable copy has identical bytes.
                    withContext(Dispatchers.IO) { PendingShareStaging.delete(application, incoming.id) }
                    _state.update { it.copy(shareIntakeInProgress = (it.shareIntakeInProgress - 1).coerceAtLeast(0)) }
                    return@launch
                }
                _state.update { current ->
                    val share = incoming
                    current.copy(
                        shareIntakeInProgress = (current.shareIntakeInProgress - 1).coerceAtLeast(0),
                        pendingMediaShares = if (share != null) current.pendingMediaShares + share else current.pendingMediaShares,
                        message = if (share == null) "공유받은 미디어를 보관하지 못했습니다. ${result.exceptionOrNull()?.message.orEmpty()} 보낸 앱에서 다시 공유해 주세요." else current.message,
                    )
                }
            }
        }
        if (others.isNotEmpty()) {
            importInboxUris(others.take(50), successMessage = "받았습니다", partialMessage = "받았지만")
        }
    }

    /** The share the choice dialog is about: the oldest pending one the user has not postponed. */
    private fun visibleShare(): PendingMediaShare? =
        _state.value.pendingMediaShares.firstOrNull { it.id !in _state.value.deferredShareIds }

    private fun hideShare(id: String) {
        _state.update { it.copy(pendingMediaShares = it.pendingMediaShares.filterNot { share -> share.id == id }) }
    }

    /** Puts a share back after an unfinished action; its durable copies were never touched. */
    private fun restoreShare(share: PendingMediaShare) {
        _state.update { current ->
            if (current.pendingMediaShares.any { it.id == share.id }) current
            else current.copy(pendingMediaShares = (current.pendingMediaShares + share).sortedBy { it.createdAt })
        }
    }

    /**
     * 이미지 선별: this share's own copies become the photo-selection batch (videos are excluded and counted).
     * The share's copies are released only after the batch is fully staged; cancel/failure puts the share back.
     */
    fun chooseShareImageSelection() {
        val share = visibleShare() ?: return
        if (share.images.isEmpty()) return
        if (photoSelection.state.value.phase != PhotoSelectionPhase.IDLE) {
            _state.update { it.copy(message = "진행 중인 사진 선별이 있습니다. 먼저 완료하거나 취소한 뒤 다시 선택해 주세요. 공유받은 항목은 그대로 대기 중입니다.") }
            return
        }
        hideShare(share.id)
        _state.update {
            it.copy(activeMainTab = MainNavigationDestination.TOOLS, screen = Screen.PhotoSelection, message = null)
        }
        photoSelection.start(
            sources = share.images.sortedBy { it.order }.map { PhotoSelectionSource(Uri.fromFile(it.file), it.name, it.originalUri) },
            skippedVideos = share.videos.size,
            extraIssues = share.failures.map { PhotoSelectionIssue(it.name, it.reason) },
            onStaged = { viewModelScope.launch(Dispatchers.IO) { PendingShareStaging.delete(application, share.id) } },
            onAbort = { restoreShare(share) },
        )
    }

    /** 영상 만들기: appends to the 작업대 only (no auto draft/editor). The share is released only after every copy is prepared. */
    fun chooseShareVideoMaking() {
        val share = visibleShare() ?: return
        val items = (share.images + share.videos).sortedBy { it.order }
        if (items.isEmpty()) return
        hideShare(share.id)
        val sharePocket = _state.value.workbenchPocketId
        viewModelScope.launch {
            val candidates = items.map { item ->
                MaterialItem(
                    // Batch + item identity (not the reusable provider URI): a retry of this share dedupes, a new share
                    // that reuses the same URI is kept. The original URI stays in the identity for reference.
                    sourceIdentity = "share:${share.id}:${item.order}:${item.originalUri}",
                    name = item.name,
                    sourceType = MaterialSourceType.LOCAL_LIBRARY,
                    isVideo = item.isVideo,
                    uriString = Uri.fromFile(item.file).toString(),
                    state = MaterialPreparationState.READY,
                    // This share is already an app-owned copy (the sender's grant is temporary): it goes to the pocket on screen.
                    pocketId = sharePocket,
                    origin = "SHARE",
                )
            }
            val added = withContext(Dispatchers.IO) { DurableMaterialStore.addItems(candidates) }
            if (added == null) {
                restoreShare(share)
                _state.update { it.copy(message = materialAddMessage(null, "")) }
                return@launch
            }
            val identities = candidates.map { it.sourceIdentity }.toSet()
            val fresh = DurableMaterialStore.itemsIn(sharePocket).filter { it.sourceIdentity in identities && it.localFilePath == null }
            val prepared = if (fresh.isEmpty()) emptyList() else prepareMaterialItems(fresh)
            val failed = prepared.count { it.state != MaterialPreparationState.READY }
            refreshMaterials()
            if (failed == 0) {
                withContext(Dispatchers.IO) { PendingShareStaging.delete(application, share.id) }
            } else {
                restoreShare(share)
            }
            _state.update {
                it.copy(
                    activeMainTab = MainNavigationDestination.WORKBENCH,
                    screen = Screen.Dashboard,
                    message = (if (added == 0) "이미 작업대에 담겨 있는 항목입니다." else "${added}개를 작업대에 담았습니다. 자동으로 편집본을 만들지는 않았습니다.") +
                        (if (failed > 0) " ${failed}개는 사본을 만들지 못해 ‘다시 준비’가 필요하며, 공유받은 항목은 대기 목록에 남아 있습니다." else "") +
                        (if (share.failures.isNotEmpty()) " 공유 중 ${share.failures.size}개는 복사하지 못해 담지 못했습니다." else ""),
                )
            }
        }
    }

    /** "나중에": keeps the share pending (nothing deleted); it is asked again after a restart or from 사진 선별. */
    fun deferPendingMediaShare() {
        val share = visibleShare() ?: return
        _state.update { it.copy(deferredShareIds = it.deferredShareIds + share.id) }
    }

    /** Re-opens every postponed share, e.g. from the 사진 선별 pending list. */
    fun reopenPendingMediaShares() {
        _state.update { it.copy(deferredShareIds = emptySet()) }
    }

    /** Explicit cancel: removes only this share's own copies; other pending shares, batches and the inbox stay. */
    fun cancelPendingMediaShare() {
        val share = visibleShare() ?: return
        hideShare(share.id)
        viewModelScope.launch(Dispatchers.IO) { PendingShareStaging.delete(application, share.id) }
    }

    /** Explicit cancel of one named pending share (pending list). */
    fun discardPendingMediaShare(id: String) {
        hideShare(id)
        viewModelScope.launch(Dispatchers.IO) { PendingShareStaging.delete(application, id) }
    }

    /** Adds the confirmed photo-selection results to the chosen pocket as app-owned copies; returns a user message. */
    internal suspend fun addPhotoSelectionToMaterials(batchId: String, photos: List<StagedPhoto>, pocketId: String = _state.value.workbenchPocketId): Result<String> {
        if (photos.isEmpty()) return Result.failure(IllegalStateException("추가할 사진이 없습니다."))
        val candidates = photos.map { photo ->
            MaterialItem(
                sourceIdentity = "photo-select:$batchId:${photo.id}",
                name = photo.displayName,
                sourceType = MaterialSourceType.LOCAL_LIBRARY,
                isVideo = false,
                uriString = Uri.fromFile(photo.file).toString(),
                state = MaterialPreparationState.READY,
                pocketId = pocketId,
                origin = "PHOTO_SELECTION",
            )
        }
        val added = withContext(Dispatchers.IO) { DurableMaterialStore.addItems(candidates) }
            ?: return Result.failure(IllegalStateException(materialAddMessage(null, "")))
        val identities = candidates.map { it.sourceIdentity }.toSet()
        val fresh = DurableMaterialStore.itemsIn(pocketId).filter { it.sourceIdentity in identities && it.localFilePath == null }
        val prepared = if (fresh.isEmpty()) emptyList() else prepareMaterialItems(fresh)
        val failed = prepared.count { it.state != MaterialPreparationState.READY }
        refreshMaterials()
        return Result.success(
            when {
                failed > 0 -> "${added - failed}개를 작업대에 담았고 ${failed}개는 사본을 만들지 못했습니다. 작업대에서 다시 확인해 주세요."
                added == 0 -> "이미 작업대에 담겨 있는 사진입니다."
                else -> "${added}장을 작업대에 담았습니다. 자동으로 편집본을 만들지는 않았습니다."
            },
        )
    }

    fun importPickedFiles(uris: List<Uri>) {
        val selected = uris.distinct().take(InboxBatchContracts.MAX_SELECTED_ITEMS)
        if (selected.isEmpty()) return
        importInboxUris(selected, successMessage = "가져왔습니다", partialMessage = "가져왔지만")
    }

    private var localMediaCopyJob: Job? = null

    fun copyLocalMediaToInbox(
        uris: List<Uri>,
        destinationSubdirectory: String? = null,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onComplete: (successCount: Int, failedCount: Int) -> Unit = { _, _ -> },
    ) {
        val selected = uris.distinct()
        if (selected.isEmpty() || localMediaCopyJob?.isActive == true) return
        // 복사 묶음 전체가 같은 출처를 갖도록 시작할 때 한 번 고정한다(도중에 모드를 바꿔도 섞이지 않는다).
        val jobPrivate = PrivacyMode.isActive
        localMediaCopyJob = viewModelScope.launch {
            val total = selected.size
            var importedCount = 0
            var failedCount = 0
            withContext(Dispatchers.IO) {
                selected.forEachIndexed { index, uri ->
                    runCatching {
                        val name = copyDisplayName(uri, index)
                        application.contentResolver.openInputStream(uri)?.use { input ->
                            // Resolve and validate the selected folder through the existing PhoneHard store.
                            inboxStore.listPhoneHardFolder(destinationSubdirectory)
                            inboxStore.import(name, application.contentResolver.getType(uri), input, destinationSubdirectory = destinationSubdirectory, isPrivate = jobPrivate)
                        } ?: error("파일을 열 수 없습니다.")
                        importedCount++
                    }.onFailure { failedCount++ }
                    withContext(Dispatchers.Main) {
                        onProgress(index + 1, total)
                    }
                }
            }
            val files = withContext(Dispatchers.IO) { inboxFiles() }
            _state.update {
                it.copy(
                    inboxFiles = files,
                    message = if (failedCount == 0) "${importedCount}개 파일을 폰하드에 복사했습니다." else null,
                    inboxErrorMessage = when {
                        failedCount == 0 -> null
                        importedCount == 0 -> "${failedCount}개 파일을 복사하지 못했습니다."
                        else -> "${importedCount}개 파일을 복사했지만 ${failedCount}개는 복사하지 못했습니다."
                    },
                )
            }
            NasFinderAppWidgetProvider.updateAll(application)
            onComplete(importedCount, failedCount)
        }
    }

    private fun importInboxUris(uris: List<Uri>, successMessage: String, partialMessage: String) {
        val jobPrivate = PrivacyMode.isActive
        viewModelScope.launch {
            val (count, failed, files) = withContext(Dispatchers.IO) {
                var importedCount = 0
                var failedCount = 0
                uris.forEachIndexed { index, uri ->
                    runCatching {
                        val name = copyDisplayName(uri, index)
                        application.contentResolver.openInputStream(uri)?.use { input ->
                            inboxStore.import(name, application.contentResolver.getType(uri), input, isPrivate = jobPrivate)
                        } ?: error("파일을 열 수 없습니다.")
                        importedCount++
                    }.onFailure { failedCount++ }
                }
                Triple(importedCount, failedCount, inboxFiles())
            }
            _state.update {
                it.copy(
                    screen = Screen.Inbox,
                    inboxFiles = files,
                    message = if (failed == 0) "${count}개 파일을 $successMessage." else null,
                    inboxErrorMessage = when {
                        failed == 0 -> null
                        count == 0 -> "${failed}개 파일을 가져오지 못했습니다."
                        else -> "${count}개 파일을 $partialMessage ${failed}개는 가져오지 못했습니다."
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

    private var trailerJob: Job? = null
    private var trailerRetry: (() -> Unit)? = null

    private fun checkRemoteFolderHasSubfolders(
        folder: RemoteFileItem,
        onResult: (hasSubfolders: Boolean) -> Unit,
    ) {
        if (!folder.isDirectory) return
        val browser = _state.value.screen as? Screen.Browser ?: return
        val activeConnection = browser.connection
        checkRemoteFolderHasSubfolders(
            folder = folder,
            connection = activeConnection,
            preferences = browser.preferences,
            serviceFor = { browserServiceFor(activeConnection) },
            isStillCurrent = { (_state.value.screen as? Screen.Browser)?.connection?.id == activeConnection.id },
            onResult = onResult,
        )
    }

    /** The browser's own lazily reopened [service]; only browser-originated commands use it. */
    private fun browserServiceFor(connection: RemoteConnection): RemoteFileService = service ?: run {
        val credential = repository.credentials.read(connection.id).orEmpty()
        RemoteFileServiceFactory.create(connection, credential).also { service = it }
    }

    private fun checkRemoteFolderHasSubfolders(
        folder: RemoteFileItem,
        connection: RemoteConnection,
        preferences: BrowserPreferences,
        serviceFor: () -> RemoteFileService,
        isStillCurrent: () -> Boolean,
        onFinished: () -> Unit = {},
        onFailure: () -> Unit = {},
        onResult: (hasSubfolders: Boolean) -> Unit,
    ) {
        val requestedPath = folder.path
        val preferencesSnapshot = preferences
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, message = null) }
            val checkResult = try {
                withContext(Dispatchers.IO) {
                    try {
                        val activeService = serviceFor()
                        val rawEntries = activeService.list(requestedPath)
                        val visibleChildFolders = rawEntries.filter { entry ->
                            if (!entry.isDirectory) return@filter false
                            if (entry.name.isBlank() || entry.name == "." || entry.name == "..") return@filter false
                            val normEntryPath = entry.path.trimEnd('/')
                            val normReqPath = requestedPath.trimEnd('/')
                            if (normEntryPath == normReqPath) return@filter false
                            if (!preferencesSnapshot.showHiddenFiles && entry.name.startsWith('.')) return@filter false
                            true
                        }
                        Result.success(visibleChildFolders.isNotEmpty())
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
            } finally {
                onFinished()
            }
            _state.update { it.copy(isBusy = false) }
            if (!isStillCurrent()) {
                return@launch
            }
            checkResult.onSuccess { hasSubfolders ->
                onResult(hasSubfolders)
            }.onFailure { error ->
                _state.update { it.copy(message = error.message ?: "폴더 내용을 불러오지 못했습니다.") }
                onFailure()
            }
        }
    }

    fun prepareRemoteFolderTrailer(
        folder: RemoteFileItem,
        onPrompt: (hasSubfolders: Boolean) -> Unit,
    ) {
        checkRemoteFolderHasSubfolders(folder) { hasSubfolders ->
            if (hasSubfolders) {
                onPrompt(true)
            } else {
                startRemoteFolderTrailer(folder, includeSubfolders = false)
            }
        }
    }

    fun prepareRemoteFolderMusicOverlay(
        folder: RemoteFileItem,
        onPrompt: (hasSubfolders: Boolean) -> Unit,
    ) {
        checkRemoteFolderHasSubfolders(folder) { hasSubfolders ->
            if (hasSubfolders) {
                onPrompt(true)
            } else {
                startRemoteFolderMusicOverlay(folder, includeSubfolders = false)
            }
        }
    }

    fun startRemoteFolderTrailer(folder: RemoteFileItem, includeSubfolders: Boolean = false) {
        if (!folder.isDirectory) return
        val browser = _state.value.screen as? Screen.Browser ?: return
        startRemoteFolderTrailer(folder, includeSubfolders, browser.connection, browser.preferences, owner = null)
    }

    /**
     * Collects [folder]'s media and stages it into a trailer. With [owner] (a held lease) every listing and download uses the
     * owner's service and the lease ends when staging finishes; without it the browser's own [service] is used as before.
     */
    private fun startRemoteFolderTrailer(
        folder: RemoteFileItem,
        includeSubfolders: Boolean,
        activeConnection: RemoteConnection,
        preferencesSnapshot: BrowserPreferences,
        owner: FavoriteActionServiceOwner?,
    ) {
        if (!folder.isDirectory) {
            owner?.let(::releaseFavoriteOwner)
            return
        }
        viewModelScope.launch {
            var stagingOwner = owner
            try {
                _state.update { it.copy(isBusy = true, message = null) }
                val (mediaCandidates, skippedCount) = withContext(Dispatchers.IO) {
                    try {
                        val activeService = owner?.service ?: browserServiceFor(activeConnection)
                        val media = mutableListOf<RemoteFileItem>()
                        var nonMediaCount = 0
                        if (includeSubfolders) {
                            val queue = java.util.ArrayDeque<String>()
                            val visitedPaths = mutableSetOf<String>()
                            queue.add(folder.path)
                            visitedPaths.add(folder.path.trimEnd('/'))
                            while (queue.isNotEmpty()) {
                                val currentPath = queue.removeFirst()
                                val entries = activeService.list(currentPath)
                                for (entry in entries) {
                                    if (entry.name.isBlank() || entry.name == "." || entry.name == "..") continue
                                    if (!preferencesSnapshot.showHiddenFiles && entry.name.startsWith('.')) continue
                                    val normPath = entry.path.trimEnd('/')
                                    if (entry.isDirectory) {
                                        if (normPath != currentPath.trimEnd('/') && visitedPaths.add(normPath)) {
                                            queue.add(entry.path)
                                        }
                                    } else if (entry.isVideo || entry.isImage) {
                                        media.add(entry)
                                    } else {
                                        nonMediaCount++
                                    }
                                }
                            }
                        } else {
                            val children = activeService.list(folder.path)
                            val validChildren = children.filter { entry ->
                                if (entry.name.isBlank() || entry.name == "." || entry.name == "..") false
                                else if (!preferencesSnapshot.showHiddenFiles && entry.name.startsWith('.')) false
                                else entry.path.trimEnd('/') != folder.path.trimEnd('/')
                            }
                            media.addAll(validChildren.filter { !it.isDirectory && (it.isVideo || it.isImage) })
                            nonMediaCount = validChildren.count { !it.isDirectory && !it.isVideo && !it.isImage }
                        }
                        media to nonMediaCount
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        null to 0
                    }
                }
                _state.update { it.copy(isBusy = false) }
                if (mediaCandidates == null) {
                    _state.update { it.copy(message = "폴더 내용을 불러오지 못했습니다.") }
                    return@launch
                }
                if (mediaCandidates.isEmpty()) {
                    _state.update { it.copy(message = "예고편을 만들 수 있는 사진이나 영상이 없습니다.") }
                    return@launch
                }
                trailerRetry = if (owner == null) {
                    { startRemoteFolderTrailer(folder, includeSubfolders) }
                } else {
                    { withRenewedFavoriteOwner(owner) { renewed -> startRemoteFolderTrailer(folder, includeSubfolders, activeConnection, preferencesSnapshot, renewed) } }
                }
                val activeService = owner?.service ?: service ?: return@launch
                val candidatesWithTime = mediaCandidates.map { it to (it.modifiedAt?.toEpochMilli() ?: 0L) }
                stagingOwner = null
                launchTrailerStaging(owner) {
                    prepareTrailer(
                        names = candidatesWithTime.map { it.first.name },
                        captureTimes = candidatesWithTime.map { it.second },
                        initialExcludedCount = skippedCount,
                        stage = { index, destination ->
                            val item = candidatesWithTime[index].first
                            activeService.download(item, destination) { received, total ->
                                _state.update { current ->
                                    if (current.trailer?.directory != destination.parentFile) return@update current
                                    current.copy(trailer = current.trailer?.copy(progress = if (total > 0) received.toFloat() / total else null))
                                }
                            }
                            check(destination.length() > 0 && (item.size <= 0 || destination.length() == item.size)) { "파일을 끝까지 받지 못했습니다. 다시 시도해 주세요." }
                        }
                    )
                }
            } finally {
                stagingOwner?.let(::releaseFavoriteOwner)
            }
        }
    }

    /**
     * Runs [start] (which may begin a new [trailerJob]); a held [owner] lease then ends exactly when that staging job
     * completes, or immediately when no new job was started.
     */
    private fun launchTrailerStaging(owner: FavoriteActionServiceOwner?, start: () -> Unit) {
        val previous = trailerJob
        try {
            start()
        } finally {
            if (owner != null) {
                val job = trailerJob
                if (job != null && job !== previous) job.invokeOnCompletion { releaseFavoriteOwner(owner) }
                else releaseFavoriteOwner(owner)
            }
        }
    }

    fun startRemoteTrailer(items: List<RemoteFileItem>) {
        val activeService = service ?: return
        startRemoteTrailer(items, activeService, owner = null)
    }

    private fun startRemoteTrailer(items: List<RemoteFileItem>, activeService: RemoteFileService, owner: FavoriteActionServiceOwner?) {
        val media = items.filter { !it.isDirectory && (it.isVideo || it.isImage) }
        if (media.size != items.size || media.isEmpty()) {
            owner?.let(::releaseFavoriteOwner)
            return
        }
        trailerRetry = if (owner == null) {
            { startRemoteTrailer(media) }
        } else {
            { withRenewedFavoriteOwner(owner) { renewed -> startRemoteTrailer(media, renewed.service, renewed) } }
        }
        val captureTimes = media.map { it.modifiedAt?.toEpochMilli() ?: 0L }
        // Origin identity is only recorded when the connection is certain (the open browser); favorites stay null.
        val originConnectionId = if (owner == null) (_state.value.screen as? Screen.Browser)?.connection?.id else null
        launchTrailerStaging(owner) {
            prepareTrailer(media.map { it.name }, captureTimes = captureTimes, originKeys = media.map { item -> originConnectionId?.let { editorOriginRemote(it, item.path) } }) { index, destination ->
                val item = media[index]
                activeService.download(item, destination) { received, total ->
                    _state.update { current ->
                        if (current.trailer?.directory != destination.parentFile) return@update current
                        current.copy(trailer = current.trailer?.copy(progress = if (total > 0) received.toFloat() / total else null))
                    }
                }
                check(destination.length() > 0 && (item.size <= 0 || destination.length() == item.size)) { "파일을 끝까지 받지 못했습니다. 다시 시도해 주세요." }
        }
        }
    }

    fun previewLocalMediaUri(uri: Uri) {
        val requestGeneration = ++previewRequestGeneration
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            _state.update { it.copy(isBusy = true, message = null) }
            var createdTempFile: File? = null
            val itemAndFile = withContext(Dispatchers.IO) {
                runCatching {
                    val name = queryName(uri) ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "미디어 파일"
                    val mimeType = application.contentResolver.getType(uri)
                    val isVideo = mimeType?.startsWith("video/") == true ||
                        name.substringAfterLast('.', "").lowercase() in setOf("mp4", "mov", "m4v", "mkv", "webm", "3gp", "ts")
                    val isImage = mimeType?.startsWith("image/") == true ||
                        name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp")
                    val ext = if (isImage) "jpg" else if (isVideo) "mp4" else name.substringAfterLast('.', "dat")
                    val tempDir = File(application.cacheDir, "local_preview").also { it.mkdirs() }
                    val tempFile = File(tempDir, "preview-${UUID.randomUUID()}.$ext")
                    createdTempFile = tempFile
                    application.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: error("파일을 읽을 수 없습니다.")
                    val item = RemoteFileItem(
                        id = uri.toString(),
                        name = name,
                        path = tempFile.absolutePath,
                        isDirectory = false,
                        size = tempFile.length(),
                        mimeType = mimeType,
                    )
                    item to tempFile
                }
            }
            if (requestGeneration != previewRequestGeneration) {
                withContext(Dispatchers.IO) { createdTempFile?.delete() }
                return@launch
            }
            _state.update { it.copy(isBusy = false) }
            itemAndFile.onSuccess { (item, file) ->
                synchronized(trackedOwnedPreviewFiles) { trackedOwnedPreviewFiles.add(file) }
                prepareLocalPreview(listOf(item), listOf(file), 0)
            }.onFailure { error ->
                withContext(Dispatchers.IO) { createdTempFile?.delete() }
                _state.update { it.copy(message = error.message ?: "미리보기를 열 수 없습니다.") }
            }
        }
    }

    /** Capture time of a content:// / file:// source in epoch millis (0 when unknown). */
    private fun localCaptureTime(uri: Uri): Long =
        runCatching {
            val projection = arrayOf(
                android.provider.MediaStore.MediaColumns.DATE_TAKEN,
                android.provider.MediaStore.MediaColumns.DATE_ADDED,
                android.provider.MediaStore.MediaColumns.DATE_MODIFIED,
            )
            application.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val taken = cursor.getLong(0)
                    if (taken > 0L) taken
                    else {
                        val added = cursor.getLong(1)
                        if (added > 0L) added * 1000L
                        else cursor.getLong(2) * 1000L
                    }
                } else 0L
            } ?: 0L
        }.getOrDefault(0L).takeIf { it > 0L }
            ?: if (uri.scheme == "file") uri.path?.let { File(it).lastModified() } ?: 0L else 0L

    fun startLocalMediaTrailer(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val activeUris = uris.distinct()
        trailerRetry = { startLocalMediaTrailer(activeUris) }
        val names = activeUris.map { uri ->
            queryName(uri) ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "미디어"
        }
        val captureTimes = activeUris.map { uri -> localCaptureTime(uri) }
        prepareTrailer(names, captureTimes = captureTimes, motionSourceUris = activeUris, originKeys = activeUris.map { editorOriginLocal(it.toString()) }) { index, destination ->
            val uri = activeUris[index]
            kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
                application.contentResolver.openInputStream(uri)?.use { input ->
                    destination.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw CancellationException()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: error("파일을 열 수 없습니다: ${names[index]}")
            }
            check(destination.length() > 0) { "파일을 끝까지 읽지 못했습니다: ${names[index]}" }
        }
    }

    fun startInboxTrailer(ids: List<UUID>) {
        val selected = runCatching { selectedInboxItems(ids) }.getOrElse { return }
        if (selected.isEmpty() || selected.any { val item = it.asPreviewItem(); !item.isVideo && !item.isImage }) return
        trailerRetry = { startInboxTrailer(ids) }
        val captureTimes = selected.map { it.importedAt.toEpochMilli() }
        prepareTrailer(selected.map { it.originalFilename }, captureTimes = captureTimes, originKeys = selected.map { editorOriginPhoneHard(it.id.toString()) }) { index, destination ->
            kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
                selected[index].file.inputStream().buffered().use { input ->
                    destination.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw CancellationException()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
        }
    }

    fun phoneHardAudioFiles(): List<TrailerMusicTrack> {
        val files = inboxFiles()
        val musicCanonicalDir = inboxStore.musicDirectory.canonicalFile
        val musicCanonicalPath = musicCanonicalDir.path
        return files.filter { item ->
            if (!item.file.exists() || !item.asPreviewItem().isAudio) return@filter false
            val itemCanonicalPath = runCatching { item.file.canonicalFile.path }.getOrNull() ?: return@filter false
            itemCanonicalPath == musicCanonicalPath ||
                itemCanonicalPath.startsWith(musicCanonicalPath + File.separator)
        }.mapNotNull { item ->
            runCatching {
                val retriever = android.media.MediaMetadataRetriever()
                val duration = try {
                    retriever.setDataSource(item.file.absolutePath)
                    retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toDoubleOrNull()?.div(1000.0) ?: 0.0
                } finally {
                    retriever.release()
                }
                TrailerMusicTrack(
                    file = item.file,
                    name = item.originalFilename,
                    durationSeconds = duration,
                )
            }.getOrNull()
        }
    }

    suspend fun listRemoteAudioFiles(
        connection: RemoteConnection,
        path: String,
    ): Result<List<RemoteFileItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val credential = repository.credentials.read(connection.id).orEmpty()
            RemoteFileServiceFactory.create(connection, credential).use { svc ->
                val allItems = svc.list(path)
                allItems.filter { it.isDirectory || it.isAudio }
            }
        }
    }

    suspend fun downloadRemoteAudioToCache(
        connection: RemoteConnection,
        item: RemoteFileItem,
        destinationFile: File,
        progress: (Long, Long) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            destinationFile.parentFile?.mkdirs()
            val credential = repository.credentials.read(connection.id).orEmpty()
            RemoteFileServiceFactory.create(connection, credential).use { svc ->
                svc.download(item, destinationFile, progress)
            }
            check(destinationFile.exists() && destinationFile.length() > 0L) { "오디오 파일을 내려받지 못했습니다." }
        }
    }

    suspend fun importAudioFileToMusic(
        sourceFile: File,
        originalFilename: String,
        mimeType: String?,
    ): Result<TrailerMusicTrack> {
        val jobPrivate = PrivacyMode.isActive
        return withContext(Dispatchers.IO) {
        runCatching {
            require(sourceFile.exists() && sourceFile.length() > 0L) { "가져올 음악 파일이 비어있습니다." }
            val retriever = android.media.MediaMetadataRetriever()
            val duration = try {
                retriever.setDataSource(sourceFile.absolutePath)
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toDoubleOrNull()?.div(1000.0) ?: 0.0
            } finally {
                retriever.release()
            }
            check(duration > 0.0) { "오디오 재생 시간을 확인할 수 없거나 손상된 파일입니다." }
            val record = sourceFile.inputStream().buffered().use { input ->
                inboxStore.import(
                    originalFilename = originalFilename,
                    mimeType = mimeType,
                    input = input,
                    destinationSubdirectory = SharedInboxStore.MUSIC_DIRECTORY_NAME,
                    isPrivate = jobPrivate,
                )
            }
            val destination = inboxStore.file(record)
            _state.update { it.copy(inboxFiles = inboxFiles()) }
            TrailerMusicTrack(
                file = destination,
                name = record.originalFilename,
                durationSeconds = duration,
            )
        }
        }
    }


    fun startRemoteMusicOverlay(
        items: List<RemoteFileItem>,
        musicTrack: TrailerMusicTrack?,
        musicPolicy: TrailerMusicPolicy = TrailerMusicPolicy.LOOP_MUSIC,
        musicFitCount: Int = 1,
    ) {
        val activeService = service ?: return
        startRemoteMusicOverlay(items, musicTrack, musicPolicy, musicFitCount, activeService, owner = null)
    }

    private fun startRemoteMusicOverlay(
        items: List<RemoteFileItem>,
        musicTrack: TrailerMusicTrack?,
        musicPolicy: TrailerMusicPolicy = TrailerMusicPolicy.LOOP_MUSIC,
        musicFitCount: Int = 1,
        activeService: RemoteFileService,
        owner: FavoriteActionServiceOwner?,
    ) {
        val media = items.filter { !it.isDirectory && (it.isVideo || it.isImage) }
        if (media.size != items.size || media.isEmpty()) {
            owner?.let(::releaseFavoriteOwner)
            return
        }
        trailerRetry = if (owner == null) {
            { startRemoteMusicOverlay(media, musicTrack, musicPolicy, musicFitCount) }
        } else {
            { withRenewedFavoriteOwner(owner) { renewed -> startRemoteMusicOverlay(media, musicTrack, musicPolicy, musicFitCount, renewed.service, renewed) } }
        }
        val captureTimes = media.map { it.modifiedAt?.toEpochMilli() ?: 0L }
        launchTrailerStaging(owner) {
            prepareTrailer(
                names = media.map { it.name },
                captureTimes = captureTimes,
                isMusicOverlayMode = true,
                initialMusicTrack = musicTrack,
                initialMusicPolicy = musicPolicy,
                initialMusicFitCount = musicFitCount,
            ) { index, destination ->
                val item = media[index]
                activeService.download(item, destination) { received, total ->
                    _state.update { current ->
                        if (current.trailer?.directory != destination.parentFile) return@update current
                        current.copy(trailer = current.trailer?.copy(progress = if (total > 0) received.toFloat() / total else null))
                    }
                }
                check(destination.length() > 0 && (item.size <= 0 || destination.length() == item.size)) { "파일을 끝까지 받지 못했습니다. 다시 시도해 주세요." }
        }
        }
    }

    fun startInboxMusicOverlay(
        ids: List<UUID>,
        musicTrack: TrailerMusicTrack?,
        musicPolicy: TrailerMusicPolicy = TrailerMusicPolicy.LOOP_MUSIC,
        musicFitCount: Int = 1,
    ) {
        val selected = runCatching { selectedInboxItems(ids) }.getOrElse { return }
        if (selected.isEmpty() || selected.any { val item = it.asPreviewItem(); !item.isVideo && !item.isImage }) return
        trailerRetry = { startInboxMusicOverlay(ids, musicTrack, musicPolicy, musicFitCount) }
        val captureTimes = selected.map { it.importedAt.toEpochMilli() }
        prepareTrailer(
            names = selected.map { it.originalFilename },
            captureTimes = captureTimes,
            isMusicOverlayMode = true,
            initialMusicTrack = musicTrack,
            initialMusicPolicy = musicPolicy,
            initialMusicFitCount = musicFitCount,
        ) { index, destination ->
            kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
                selected[index].file.inputStream().buffered().use { input ->
                    destination.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw CancellationException()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
        }
    }

    fun startLocalMusicOverlay(
        uris: List<Uri>,
        musicTrack: TrailerMusicTrack?,
        musicPolicy: TrailerMusicPolicy = TrailerMusicPolicy.LOOP_MUSIC,
        musicFitCount: Int = 1,
    ) {
        if (uris.isEmpty()) return
        val activeUris = uris.distinct()
        trailerRetry = { startLocalMusicOverlay(activeUris, musicTrack, musicPolicy, musicFitCount) }
        val names = activeUris.map { uri ->
            queryName(uri) ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "미디어"
        }
        val captureTimes = activeUris.map { uri ->
            runCatching {
                val projection = arrayOf(
                    android.provider.MediaStore.MediaColumns.DATE_TAKEN,
                    android.provider.MediaStore.MediaColumns.DATE_ADDED,
                    android.provider.MediaStore.MediaColumns.DATE_MODIFIED,
                )
                application.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val taken = cursor.getLong(0)
                        if (taken > 0L) taken
                        else {
                            val added = cursor.getLong(1)
                            if (added > 0L) added * 1000L
                            else cursor.getLong(2) * 1000L
                        }
                    } else 0L
                } ?: 0L
            }.getOrDefault(0L)
        }
        prepareTrailer(
            names = names,
            captureTimes = captureTimes,
            motionSourceUris = activeUris,
            isMusicOverlayMode = true,
            initialMusicTrack = musicTrack,
            initialMusicPolicy = musicPolicy,
            initialMusicFitCount = musicFitCount,
        ) { index, destination ->
            val uri = activeUris[index]
            kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
                application.contentResolver.openInputStream(uri)?.use { input ->
                    destination.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw CancellationException()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: error("파일을 열 수 없습니다: ${names[index]}")
            }
            check(destination.length() > 0) { "파일을 끝까지 읽지 못했습니다: ${names[index]}" }
        }
    }

    // Optionally improves a staged video's time-0 thumbnail using the already-computed audio
    // highlight anchor: samples up to 3 nearby frames and keeps the sharpest/best-exposed one.
    // Never touches analysis, scene selection, export or NAS traffic; keeps old thumbnail on any failure.
    private suspend fun improveVideoThumbnail(file: File, duration: Double, anchorSeconds: Double, fallback: Bitmap?): Bitmap? {
        if (!duration.isFinite() || duration <= 0.0) return fallback
        return withContext(Dispatchers.IO) {
            // Excludes the exact end-of-stream timestamp, which some decoders refuse to seek to.
            val maxSeekable = Math.nextDown(duration).coerceAtLeast(0.0)
            val candidates = ArrayList<Bitmap?>(3)
            try {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val anchor = anchorSeconds.takeIf { it.isFinite() }?.coerceIn(0.0, maxSeekable) ?: 0.0
                val candidateTimes = listOf(anchor, (anchor - .25).coerceIn(0.0, maxSeekable), (anchor + .25).coerceIn(0.0, maxSeekable))
                val reader = android.media.MediaMetadataRetriever()
                try {
                    reader.setDataSource(file.absolutePath)
                    for (t in candidateTimes) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val micros = (t * 1_000_000).toLong()
                        val frame = try {
                            if (android.os.Build.VERSION.SDK_INT >= 27) {
                                reader.getScaledFrameAtTime(micros, android.media.MediaMetadataRetriever.OPTION_CLOSEST, 180, 180)
                            } else {
                                reader.getFrameAtTime(micros, android.media.MediaMetadataRetriever.OPTION_CLOSEST)?.let { original ->
                                    android.graphics.Bitmap.createScaledBitmap(original, 180, 180, true).also { if (it !== original) original.recycle() }
                                }
                            }
                        } catch (interrupted: InterruptedException) {
                            throw interrupted
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: Exception) {
                            null
                        }
                        candidates += frame
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    }
                } finally {
                    reader.release()
                }
                val bestIndex = selectBestVideoFrameIndex(candidates)
                if (bestIndex == null) {
                    candidates.forEach { it?.recycle() }
                    return@withContext fallback
                }
                val best = candidates[bestIndex]
                candidates.forEachIndexed { index, bitmap -> if (index != bestIndex) bitmap?.recycle() }
                if (best != null && fallback != null && fallback !== best) fallback.recycle()
                best ?: fallback
            } catch (interrupted: InterruptedException) {
                candidates.forEach { it?.recycle() }
                throw interrupted
            } catch (cancelled: CancellationException) {
                candidates.forEach { it?.recycle() }
                throw cancelled
            } catch (e: Exception) {
                candidates.forEach { it?.recycle() }
                fallback
            }
        }
    }

    private fun prepareTrailer(
        names: List<String>,
        captureTimes: List<Long> = emptyList(),
        motionSourceUris: List<Uri?> = emptyList(),
        initialExcludedCount: Int = 0,
        isMusicOverlayMode: Boolean = false,
        initialMusicTrack: TrailerMusicTrack? = null,
        initialMusicPolicy: TrailerMusicPolicy = TrailerMusicPolicy.LOOP_MUSIC,
        initialMusicFitCount: Int = 1,
        forceNew: Boolean = false,
        originKeys: List<String?> = emptyList(),
        // 시작할 때 고정한 출처. null이면 지금 모드를 한 번 읽는다(명시한 false는 모드를 다시 읽지 않는 공개 출처다).
        jobPrivate: Boolean? = null,
        // Called after every source was staged and before the project is finished; false = the user declined, close the project.
        beforeFinish: (suspend (staged: Int) -> Boolean)? = null,
        stage: suspend (Int, File) -> Unit,
    ) {
        if (_state.value.trailer?.busy == true) return
        if (_state.value.trailer?.export?.running == true) {
            _state.update { it.copy(message = "내보내는 중인 프로젝트가 있습니다. 내보내기가 끝나거나 취소한 뒤 새 영상을 만들어 주세요.") }
            return
        }

        stopInlineAudio()
        // An open, prepared project is saved as a draft before a newly confirmed selection replaces it; when that
        // save fails the open project is kept (closing an unregistered project would remove its directory).
        _state.value.trailer?.takeIf { it.prepared && it.editor != null }?.let { open ->
            if (!saveEditorDraftNow(open)) {
                _state.update {
                    it.copy(
                        trailerMinimized = false,
                        message = "열려 있는 ‘${open.displayTitle}’을(를) 저장하지 못해 새 영상을 시작하지 않았습니다. 저장 공간을 확인해 주세요.",
                    )
                }
                return
            }
        }
        closeTrailer(clearRetry = false)
        val directory = TrailerDraftStore.newProjectDirectory(application.cacheDir)
        _state.update {
            it.copy(
                trailer = TrailerSession(
                    directory = directory,
                    isMusicOverlayMode = isMusicOverlayMode,
                    musicTrack = initialMusicTrack,
                    musicPolicy = initialMusicPolicy,
                    musicFitCount = initialMusicFitCount,
                    highlightAnalyzed = !isMusicOverlayMode,
                    originPrivate = (jobPrivate ?: PrivacyMode.isActive) || originKeys.any(PrivacyMode::isPrivateOriginKey),
                ),
                imagePreview = null,
                trailerMinimized = false,
                trailerDraftCount = TrailerDraftStore.draftCount(),
            )
        }
        trailerJob = viewModelScope.launch {
            try {
                val sources = mutableListOf<TrailerSource>()
                var undecodableCount = 0
                names.forEachIndexed { index, name ->
                    var sourceName = name
                    var isImage = com.armsone.nasfinder.model.RemoteFileItem(name, name, name, false).isImage
                    updateTrailer(directory) { it.copy(status = "${index + 1}/${names.size} · $name · ${if (isImage) "이미지" else "영상"}를 준비하는 중…", progress = null) }
                    var file = File(directory, "source-$index.${name.substringAfterLast('.', if (isImage) "jpg" else "mp4")}")
                    stage(index, file)
                    val primaryStagedFile = file
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (isImage) motionSourceUris.getOrNull(index)?.let { uri ->
                        val chosen = TrailerMotionSelection.choose(application, uri, file)
                        if (chosen.file != file) {
                            // Retain the staged representative until the chosen form decodes successfully.
                            file = chosen.file
                            isImage = chosen.isImage
                            sourceName = "${name.substringBeforeLast('.', name)}.${chosen.file.extension}"
                        }
                    }
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (!isMusicOverlayMode) {
                        updateTrailer(directory) { it.copy(status = "${index + 1}/${names.size} · 한양이 장면을 고르는 중…", progress = null) }
                    }
                    suspend fun decodeStagedSource() = decodeStagedTrailerFile(file, name, isImage, captureTimes.getOrNull(index) ?: 0L)
                    var decodedResult = decodeStagedSource()
                    if (decodedResult == null && file != primaryStagedFile) {
                        file.delete()
                        file = primaryStagedFile
                        isImage = true
                        sourceName = name
                        decodedResult = decodeStagedSource()
                        android.util.Log.d("TrailerMotionSelection", "mode=representative_fallback downstream_decode=true")
                    }
                    if (decodedResult != null && file != primaryStagedFile) primaryStagedFile.delete()
                    if (decodedResult == null) {
                        undecodableCount++
                        file.delete()
                    } else {
                        val (metadata, thumbnail, capTime) = decodedResult
                        val analysis = if (isImage || isMusicOverlayMode) {
                            // "영상에 뮤직비디오" keeps whole original clips (musicOverlayScenes ignores
                            // highlight data), and ducking at export reads raw PCM independently
                            // (TrailerExporter/AutoDuckingAudioProcessor), so skip the costly HanYang
                            // highlight analysis here.
                            AudioAnalysisResult(
                                waveform = emptyList(),
                                peakTimeSeconds = 0.0,
                                peakTimesSeconds = listOf(0.0),
                                audioAvailability = ClipAudioAvailability.NoTrack,
                                highlightSource = ClipHighlightSource.Fallback
                            )
                        } else {
                            AudioAnalysisService.analyze(application, Uri.fromFile(file), metadata.first, (metadata.first.coerceAtMost(1024.0) * 2).toInt().coerceIn(192, 2048))
                        }
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val finalThumbnail = if (!isImage && !isMusicOverlayMode) {
                            improveVideoThumbnail(file, metadata.first, analysis.peakTimeSeconds, thumbnail)
                        } else {
                            thumbnail
                        }
                        val sourceIndex = sources.size
                        sources += TrailerSource(sourceIndex, sourceName, file, metadata.first, metadata.second, metadata.third, analysis, finalThumbnail, isImage = isImage, captureTimeMillis = capTime, originKey = originKeys.getOrNull(index))
                        updateTrailer(directory) { it.copy(sources = sources.toList()) }
                    }
                }
                if (sources.isEmpty()) {
                    val totalSkipped = initialExcludedCount + undecodableCount
                    val err = if (totalSkipped > 0) "사용 가능한 사진이나 영상이 없습니다 (지원되지 않거나 손상된 ${totalSkipped}개 제외)."
                    else "사용 가능한 사진이나 영상이 없습니다."
                    updateTrailer(directory) { it.copy(busy = false, error = err, progress = null) }
                    return@launch
                }
                if (beforeFinish != null && !beforeFinish(sources.size)) {
                    // Declined: only this run's project folder is removed (closeTrailer); references and originals are untouched.
                    closeTrailer(clearRetry = false)
                    _state.update { it.copy(message = "취소했습니다. 읽지 못한 항목은 작업대에 그대로 남아 있고 ‘다시 연결’할 수 있습니다. 이번 실행의 임시 파일만 정리했습니다.") }
                    return@launch
                }
                finishTrailerPreparation(directory, sources.toList(), initialExcludedCount + undecodableCount, isMusicOverlayMode, initialMusicTrack, initialMusicPolicy, initialMusicFitCount)
                trailerJob = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { kotlinx.coroutines.currentCoroutineContext().ensureActive(); updateTrailer(directory) { it.copy(busy = false, error = failure.message ?: "예고편을 만들지 못했습니다.", progress = null) } }
        }
    }

    /** Reads display-oriented size, capture time and a thumbnail of one staged file; null when it is not usable media. */
    private suspend fun decodeStagedTrailerFile(
        file: File,
        name: String,
        isImage: Boolean,
        fallbackCaptureTime: Long,
    ): Triple<Triple<Double, Int, Int>, Bitmap, Long>? = withContext(Dispatchers.IO) {
                        try {
                            if (isImage) {
                                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
                                var width = options.outWidth
                                var height = options.outHeight
                                if (width <= 0 || height <= 0) {
                                    val decoded = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                                    checkNotNull(decoded) { "분석할 수 있는 이미지가 아닙니다: $name" }
                                    width = decoded.width
                                    height = decoded.height
                                    decoded.recycle()
                                }
                                var parsedCaptureTime = fallbackCaptureTime
                                runCatching {
                                    val exif = androidx.exifinterface.media.ExifInterface(file.absolutePath)
                                    val orientation = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
                                    if (orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 || orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270) {
                                        val tmp = width; width = height; height = tmp
                                    }
                                    if (parsedCaptureTime <= 0L) {
                                        val dateStr = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL)
                                            ?: exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME)
                                        if (dateStr != null) {
                                            val format = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
                                            format.parse(dateStr)?.time?.let { parsedCaptureTime = it }
                                        }
                                    }
                                }
                                val thumbOptions = android.graphics.BitmapFactory.Options().apply {
                                    inSampleSize = maxOf(1, maxOf(width, height) / 180)
                                }
                                val rawThumb = android.graphics.BitmapFactory.decodeFile(file.absolutePath, thumbOptions)
                                val thumb = rawThumb?.let { original ->
                                    android.graphics.Bitmap.createScaledBitmap(original, 180, 180, true).also { if (it !== original) original.recycle() }
                                }
                                checkNotNull(thumb) { "이미지 썸네일을 생성할 수 없습니다: $name" }
                                Triple(Triple(0.0, width, height), thumb, parsedCaptureTime)
                            } else {
                                val reader = android.media.MediaMetadataRetriever()
                                try {
                                    reader.setDataSource(file.absolutePath)
                                    val duration = reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000) ?: 0.0
                                    check(duration.isFinite() && duration > 0 && reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes") { "분석할 수 있는 영상이 아닙니다: $name" }
                                    var width = reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
                                    var height = reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
                                    val rotation = reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                                    if (rotation % 180 != 0) { val old = width; width = height; height = old }

                                    var parsedCaptureTime = fallbackCaptureTime
                                    if (parsedCaptureTime <= 0L) {
                                        reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DATE)?.let { dateStr ->
                                            val formats = listOf(
                                                java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") },
                                                java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") },
                                                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US),
                                            )
                                            for (fmt in formats) {
                                                val parsed = runCatching { fmt.parse(dateStr) }.getOrNull()
                                                if (parsed != null) {
                                                    parsedCaptureTime = parsed.time
                                                    break
                                                }
                                            }
                                        }
                                    }

                                    // Verify video track and decoder support using MediaExtractor / MediaCodecList
                                    val extractor = android.media.MediaExtractor()
                                    try {
                                        extractor.setDataSource(file.absolutePath)
                                        var hasSupportedVideoTrack = false
                                        for (trackIndex in 0 until extractor.trackCount) {
                                            val format = extractor.getTrackFormat(trackIndex)
                                            val mime = format.getString(android.media.MediaFormat.KEY_MIME).orEmpty()
                                            if (mime.startsWith("video/")) {
                                                val codecList = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
                                                if (codecList.findDecoderForFormat(format) != null) {
                                                    hasSupportedVideoTrack = true
                                                    break
                                                }
                                            }
                                        }
                                        check(hasSupportedVideoTrack) { "디코더를 지원하지 않는 비디오 형식입니다: $name" }
                                    } finally {
                                        extractor.release()
                                    }

                                    val thumb = if (android.os.Build.VERSION.SDK_INT >= 27) reader.getScaledFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 180, 180)
                                    else reader.getFrameAtTime(0)?.let { original ->
                                        android.graphics.Bitmap.createScaledBitmap(original, 180, 180, true).also { if (it !== original) original.recycle() }
                                    }
                                    checkNotNull(thumb) { "영상 프레임을 추출할 수 없습니다: $name" }
                                    Triple(Triple(duration, width, height), thumb, parsedCaptureTime)
                                } finally { reader.release() }
                            }
                        } catch (c: CancellationException) {
                            throw c
                        } catch (e: Exception) {
                            null
                        }
    }

    /**
     * Turns the staged/analysed sources into the first project: recommended plan materialized as explicit editor
     * scenes, a draft id, and an immediate autosave so the confirmed selection survives process death.
     */
    private suspend fun finishTrailerPreparation(
        directory: File,
        stagedSources: List<TrailerSource>,
        totalExcluded: Int,
        isMusicOverlayMode: Boolean,
        initialMusicTrack: TrailerMusicTrack?,
        initialMusicPolicy: TrailerMusicPolicy,
        initialMusicFitCount: Int,
    ) {
                // Retain immutable original addition order as default
                val sortedSources = stagedSources

                val preparedSources = if (!isMusicOverlayMode && sortedSources.any { it.isImage }) {
                    updateTrailer(directory) { it.copy(status = "사진을 비교하는 중…", progress = null) }
                    analyzeTrailerPhotos(application, sortedSources)
                } else {
                    sortedSources
                }

                val videoDurations = preparedSources.filter { !it.isImage }.map { it.duration }
                val minVideoDuration = videoDurations.minOrNull() ?: 4.0
                val trailerDefaults = if (isMusicOverlayMode) null else recommendedTrailerDefaults(preparedSources)
                val length = trailerDefaults?.clipLength ?: minVideoDuration.coerceAtLeast(1.0)
                val hasVideos = preparedSources.any { !it.isImage }
                val photoLength = if (isMusicOverlayMode) 1.0 else defaultPhotoClipLength(length, hasVideos)
                val initialTotal = trailerDefaults?.total
                    ?: preparedSources.sumOf { if (it.isImage) photoLength else it.duration }
                val statusMessage = if (isMusicOverlayMode) {
                    "음악을 선택해 주세요."
                } else if (totalExcluded > 0) {
                    "장면을 분석했습니다 (${totalExcluded}개 제외됨). 설정을 확인해 주세요."
                } else {
                    "장면을 분석했습니다. 설정을 확인해 주세요."
                }
                updateTrailer(directory) {
                    it.copy(
                        status = statusMessage,
                        sources = preparedSources,
                        total = initialTotal,
                        clipLength = length,
                        photoClipLength = photoLength,
                        format = TrailerFormat.ORIGINAL,
                        orderMode = "추가순",
                        busy = false,
                        isMusicOverlayMode = isMusicOverlayMode,
                        musicTrack = initialMusicTrack,
                        musicPolicy = initialMusicPolicy,
                        musicFitCount = initialMusicFitCount,
                        awaitingInitialSettings = true,
                        prepared = true,
                        highlightAnalyzed = !isMusicOverlayMode,
                    ).let { prepared ->
                        prepared.copy(
                            editor = EditorProject.fromPlan(prepared.autoPlanScenes(), autoPlan = true),
                            draftId = prepared.draftId ?: UUID.randomUUID().toString(),
                            revision = prepared.revision + 1,
                        )
                    }
                }
                scheduleEditorAutosave(directory, immediate = true)
    }

    // ---------------------------------------------------------------------------------------------
    // Current-project editor: explicit scenes, undo/redo, autosave, Finder adds and snapshot export.
    // ---------------------------------------------------------------------------------------------

    private val editorHistories = mutableMapOf<String, EditorHistory>()
    private val editorFinderSelections = mutableMapOf<String, SnapshotStateMap<String, EditorAddCandidate>>()
    private var editorAutosaveJob: Job? = null
    private var editorExportJob: Job? = null
    private var editorAddJob: Job? = null
    private val pendingAddCandidates = linkedMapOf<String, EditorAddCandidate>()
    // 추가를 요청한 순간 고정한 출처(현재 모드 또는 후보의 비공개 출처). 추가가 끝날 때 프로젝트를 영구히 오염시킨다.
    private val pendingAddPrivate = mutableMapOf<String, Boolean>()

    private fun editorHistory(directory: File): EditorHistory =
        editorHistories.getOrPut(directory.absolutePath) { EditorHistory() }

    fun editorFinderSelection(directoryKey: String): SnapshotStateMap<String, EditorAddCandidate> =
        editorFinderSelections.getOrPut(directoryKey) { mutableStateMapOf() }

    private fun draftFromSession(session: TrailerSession, showingSettings: Boolean = false): TrailerDraft? {
        val draftId = session.draftId ?: return null
        if (!session.prepared || session.sources.isEmpty()) return null
        val validOutput = session.output?.takeIf { it.exists() && it.length() > 0 }
        return TrailerDraft(
            id = draftId,
            isMusicOverlayMode = session.isMusicOverlayMode,
            directory = session.directory,
            sources = session.sources,
            total = session.total,
            clipLength = session.clipLength,
            photoClipLength = session.photoClipLength,
            format = session.format,
            // An unavailable stored music choice is saved as-is; only an explicit remove/replace clears it.
            musicTrack = session.musicTrack ?: session.unavailableMusic,
            musicPolicy = session.musicPolicy,
            musicFitCount = session.musicFitCount,
            autoDucking = session.autoDucking,
            output = validOutput,
            showingSettings = showingSettings,
            orderMode = session.orderMode,
            timestamp = System.currentTimeMillis(),
            customTitle = session.title,
            editor = session.editor,
            revision = session.revision,
            outputRevision = if (validOutput != null) session.outputRevision else null,
            isPrivate = session.originPrivate,
        )
    }

    /** Writes the project now (main-thread callers that are about to close it). */
    private fun saveEditorDraftNow(session: TrailerSession): Boolean {
        val draft = draftFromSession(session) ?: return false
        return TrailerDraftStore.saveDraft(draft)
    }

    /** Debounced autosave of the CURRENT project with saving/saved/failed state. */
    private fun scheduleEditorAutosave(directory: File, immediate: Boolean = false) {
        editorAutosaveJob?.cancel()
        updateTrailer(directory) { it.copy(saveState = EditorSaveState.SAVING, saveError = null) }
        editorAutosaveJob = viewModelScope.launch {
            if (!immediate) kotlinx.coroutines.delay(700)
            val session = _state.value.trailer?.takeIf { it.directory == directory } ?: return@launch
            val revision = session.revision
            val draft = draftFromSession(session)
            val saved = draft != null && withContext(Dispatchers.IO) { TrailerDraftStore.saveDraft(draft) }
            _state.update { current ->
                val live = current.trailer?.takeIf { it.directory == directory } ?: return@update current
                current.copy(
                    trailer = live.copy(
                        saveState = when {
                            !saved -> EditorSaveState.FAILED
                            live.revision == revision -> EditorSaveState.SAVED
                            else -> EditorSaveState.SAVING
                        },
                        saveError = if (saved) null else "자동 저장하지 못했습니다. 저장 공간을 확인한 뒤 다시 시도해 주세요.",
                    ),
                    trailerDraftCount = TrailerDraftStore.draftCount(),
                )
            }
        }
    }

    fun retryEditorAutosave() {
        val session = _state.value.trailer ?: return
        scheduleEditorAutosave(session.directory, immediate = true)
    }

    /**
     * Applies one editor action atomically: [transform] must be pure. The prior state goes to undo history, the
     * revision is bumped and an autosave is scheduled. Returns false when nothing changed.
     */
    private fun editProject(transform: (TrailerSession) -> TrailerSession?): Boolean {
        var before: EditorSnapshot? = null
        var directory: File? = null
        _state.update { current ->
            val live = current.trailer ?: return@update current
            if (!live.prepared || live.editor == null || live.busy) return@update current
            val updated = transform(live) ?: return@update current
            val snapshot = live.editorSnapshot()
            if (updated.editorSnapshot() == snapshot) return@update current
            before = snapshot
            directory = live.directory
            current.copy(trailer = updated.copy(revision = live.revision + 1, error = null))
        }
        val dir = directory ?: return false
        val history = editorHistory(dir)
        before?.let(history::record)
        updateTrailer(dir) { it.copy(canUndo = history.canUndo, canRedo = history.canRedo) }
        scheduleEditorAutosave(dir)
        return true
    }

    fun editorUndo() = editorStep(undo = true)
    fun editorRedo() = editorStep(undo = false)

    private fun editorStep(undo: Boolean) {
        val session = _state.value.trailer ?: return
        if (!session.prepared || session.busy) return
        val history = editorHistory(session.directory)
        val target = (if (undo) history.undo(session.editorSnapshot()) else history.redo(session.editorSnapshot())) ?: return
        updateTrailer(session.directory) {
            it.applyEditorSnapshot(target).copy(revision = it.revision + 1, canUndo = history.canUndo, canRedo = history.canRedo)
        }
        scheduleEditorAutosave(session.directory)
    }

    private fun TrailerSession.withManualScenes(scenes: List<EditorScene>): TrailerSession? {
        val project = editor ?: return null
        return copy(editor = project.copy(scenes = scenes, autoPlan = false))
    }

    /** Regenerates the scenes from the recommended plan; only valid while the project is still automatic. */
    private fun TrailerSession.withRegeneratedPlan(): TrailerSession {
        val project = editor ?: return this
        return copy(editor = EditorProject.fromPlan(autoPlanScenes(), autoPlan = true, musicVolume = project.musicVolume, firstKey = project.nextKey))
    }

    private fun TrailerSession.defaultSceneFor(source: TrailerSource, key: Long): EditorScene {
        val (start, length) = when {
            source.isImage -> 0.0 to if (isMusicOverlayMode) 1.0 else source.weightedPhotoLength(clipLength)
            isMusicOverlayMode -> 0.0 to source.duration
            else -> {
                val recommended = recommendTrailerPlan(listOf(source))?.scenes?.firstOrNull()
                (recommended?.start ?: 0.0) to kotlin.math.min(clipLength, source.duration)
            }
        }
        val (safeStart, safeLength) = clampSceneWindow(source, start, length)
        return EditorScene(key, source.id, safeStart, safeLength)
    }

    fun editorMoveScene(key: Long, delta: Int) {
        editProject { session ->
            val scenes = session.editor?.scenes ?: return@editProject null
            val from = scenes.indexOfFirst { it.key == key }
            val to = from + delta
            if (from < 0 || to !in scenes.indices) return@editProject null
            session.withManualScenes(scenes.toMutableList().apply { add(to, removeAt(from)) })
        }
    }

    fun editorMoveSceneTo(key: Long, targetIndex: Int) {
        editProject { session ->
            val scenes = session.editor?.scenes ?: return@editProject null
            val from = scenes.indexOfFirst { it.key == key }
            val to = targetIndex.coerceIn(0, scenes.lastIndex)
            if (from < 0 || from == to) return@editProject null
            session.withManualScenes(scenes.toMutableList().apply { add(to, removeAt(from)) })
        }
    }

    /** Duplicates a scene right after itself; returns the new scene key (or null). */
    fun editorDuplicateScene(key: Long): Long? {
        var newKey: Long? = null
        editProject { session ->
            val project = session.editor ?: return@editProject null
            val index = project.scenes.indexOfFirst { it.key == key }
            if (index < 0) return@editProject null
            val copyScene = project.scenes[index].copy(key = project.nextKey)
            newKey = copyScene.key
            session.copy(editor = project.copy(
                scenes = project.scenes.toMutableList().apply { add(index + 1, copyScene) },
                autoPlan = false,
                nextKey = project.nextKey + 1,
            ))
        }
        return newKey
    }

    /** Removes a scene from the project only; the source file and other scenes using it stay. */
    fun editorRemoveScene(key: Long) {
        editProject { session ->
            val scenes = session.editor?.scenes ?: return@editProject null
            if (scenes.none { it.key == key }) return@editProject null
            session.withManualScenes(scenes.filterNot { it.key == key })
        }
    }

    fun editorTrimScene(key: Long, start: Double, length: Double) {
        editProject { session ->
            val project = session.editor ?: return@editProject null
            val scene = project.scene(key) ?: return@editProject null
            val source = session.sources.firstOrNull { it.id == scene.sourceId } ?: return@editProject null
            val (safeStart, safeLength) = clampSceneWindow(source, start, length)
            session.withManualScenes(project.scenes.map { if (it.key == key) it.copy(start = safeStart, length = safeLength) else it })
        }
    }

    fun editorSetSceneFraming(key: Long, fit: SceneFit, focusX: Float, focusY: Float) {
        editProject { session ->
            val project = session.editor ?: return@editProject null
            if (project.scene(key) == null) return@editProject null
            session.withManualScenes(project.scenes.map {
                if (it.key == key) it.copy(fit = fit, focusX = focusX.coerceIn(-1f, 1f), focusY = focusY.coerceIn(-1f, 1f)) else it
            })
        }
    }

    fun editorSetSceneVolume(key: Long, volume: Float) {
        editProject { session ->
            val project = session.editor ?: return@editProject null
            if (project.scene(key) == null) return@editProject null
            session.withManualScenes(project.scenes.map { if (it.key == key) it.copy(sourceVolume = volume.coerceIn(0f, 2f)) else it })
        }
    }

    /** Whole-video aspect; separate from each scene's fit/fill/position. */
    fun editorSetFormat(format: TrailerFormat) {
        editProject { it.copy(format = format) }
    }

    fun editorSetMusicVolume(volume: Float) {
        editProject { session ->
            val project = session.editor ?: return@editProject null
            session.copy(editor = project.copy(musicVolume = volume.coerceIn(0f, 2f)))
        }
    }

    fun editorSetAutoDucking(enabled: Boolean) {
        editProject { it.copy(autoDucking = enabled) }
    }

    /** Explicit user choice (select, replace or remove): this is the only path that discards an unavailable stored music choice. */
    fun editorSetMusic(track: TrailerMusicTrack?, policy: TrailerMusicPolicy, fitCount: Int) {
        editProject { session ->
            var next = session.copy(
                musicTrack = track,
                unavailableMusic = null,
                musicExportPrompt = false,
                musicPolicy = policy,
                musicFitCount = fitCount.coerceAtLeast(1),
            )
            if (track != null && policy == TrailerMusicPolicy.FIT_MUSIC_LENGTH && track.durationSeconds > 0.0) {
                next = next.copy(total = track.durationSeconds * fitCount.coerceAtLeast(1))
            }
            if (session.editor?.autoPlan == true) next.withRegeneratedPlan() else next
        }
    }

    /**
     * "다시 확인" for a stored music choice whose file was unreadable: when the same file is readable again it becomes
     * the active music (same descriptor, policy, volume and ducking). Nothing is cleared when it is still missing.
     */
    fun editorRecheckMusic(silent: Boolean = false) {
        val session = _state.value.trailer ?: return
        val stored = session.unavailableMusic ?: return
        val directory = session.directory
        viewModelScope.launch {
            val readable = withContext(Dispatchers.IO) { isMusicFileReadable(stored) }
            if (readable) {
                updateTrailer(directory) { live ->
                    if (live.unavailableMusic != stored) live
                    else live.copy(musicTrack = stored, unavailableMusic = null, musicExportPrompt = false)
                }
                if (!silent) _state.update { it.copy(message = "음악 ‘${stored.name}’을(를) 다시 찾아 연결했습니다.") }
            } else if (!silent) {
                _state.update { it.copy(message = "음악 ‘${stored.name}’ 파일을 아직 찾을 수 없습니다. 음악 설정은 그대로 보관합니다. 다른 음악을 고르거나, 필요 없으면 직접 선택을 해제해 주세요.") }
            }
        }
    }

    private fun isMusicFileReadable(track: TrailerMusicTrack): Boolean =
        runCatching { track.file.isFile && track.file.canRead() && track.file.length() > 0L && track.durationSeconds > 0.0 }.getOrDefault(false)

    fun dismissMusicExportPrompt() {
        val directory = _state.value.trailer?.directory ?: return
        updateTrailer(directory) { it.copy(musicExportPrompt = false) }
    }

    /** Explicit one-time choice from the export prompt: render without the unavailable music; the stored choice stays saved. */
    fun exportEditorProjectWithoutUnavailableMusic() {
        dismissMusicExportPrompt()
        exportEditorProjectInternal(skipUnavailableMusic = true)
    }

    /** Plan parameters only regenerate scenes while the project is automatic; manual edits are never overwritten. */
    fun editorSetPlanTotal(total: Double) {
        editProject { session ->
            if (session.editor?.autoPlan != true || !total.isFinite()) return@editProject null
            session.copy(
                total = total.coerceIn(1.0, 7200.0),
                musicPolicy = if (session.musicTrack != null) TrailerMusicPolicy.SINGLE else session.musicPolicy,
            ).withRegeneratedPlan()
        }
    }

    fun editorSetPlanClipLength(length: Double) {
        editProject { session ->
            if (session.editor?.autoPlan != true || !length.isFinite() || length <= 0.0) return@editProject null
            session.copy(clipLength = length, photoClipLength = defaultPhotoClipLength(length, session.hasVideos)).withRegeneratedPlan()
        }
    }

    /** Order presets: regenerate the automatic plan, or reorder the explicit scenes keeping their trims. */
    fun editorApplyOrder(mode: String) {
        editProject { session ->
            fun sorted(sources: List<TrailerSource>): List<TrailerSource> = when (mode) {
                "추가순" -> sources.sortedBy { it.id }
                "시간순" -> sources.sortedWith(compareBy<TrailerSource> {
                    if (it.captureTimeMillis > 0L) it.captureTimeMillis else it.file.lastModified().takeIf { t -> t > 0L } ?: Long.MAX_VALUE
                }.thenBy { it.id })
                "가나다순" -> sources.sortedWith(compareBy<TrailerSource, String>(NaturalOrderComparator) { it.name }.thenBy { it.id })
                "랜덤" -> sources.shuffled()
                else -> sources
            }
            val orderedSources = sorted(session.sources)
            val project = session.editor ?: return@editProject null
            if (project.autoPlan) {
                session.copy(sources = orderedSources, orderMode = mode).withRegeneratedPlan()
            } else {
                val rank = orderedSources.mapIndexed { index, source -> source.id to index }.toMap()
                val scenes = if (mode == "랜덤") project.scenes.shuffled()
                else project.scenes.sortedWith(compareBy<EditorScene> { rank[it.sourceId] ?: Int.MAX_VALUE }.thenBy { it.start })
                session.copy(sources = orderedSources, orderMode = mode, editor = project.copy(scenes = scenes))
            }
        }
    }

    /** Explicitly replaces manual scenes with a fresh recommendation (undoable). */
    fun editorResetToAutoPlan() {
        editProject { session ->
            val project = session.editor ?: return@editProject null
            val defaults = if (session.isMusicOverlayMode) null else recommendedTrailerDefaults(session.sources)
            val base = if (defaults != null) session.copy(
                clipLength = defaults.clipLength,
                total = defaults.total,
                photoClipLength = defaultPhotoClipLength(defaults.clipLength, session.hasVideos),
            ) else session
            base.copy(editor = EditorProject.fromPlan(base.autoPlanScenes(), autoPlan = true, musicVolume = project.musicVolume, firstKey = project.nextKey))
        }
    }

    fun editorRename(title: String) {
        editProject { it.copy(title = title.trim().takeIf(String::isNotBlank)) }
    }

    /** Intentionally uses an already-present source as one more scene (no hash/duplicate inference). */
    fun editorUseSourceAgain(sourceId: Int, afterKey: Long? = null): Long? {
        var newKey: Long? = null
        editProject { session ->
            val project = session.editor ?: return@editProject null
            val source = session.sources.firstOrNull { it.id == sourceId } ?: return@editProject null
            val scene = session.defaultSceneFor(source, project.nextKey)
            newKey = scene.key
            val insertAt = afterKey?.let { key -> project.scenes.indexOfFirst { it.key == key }.takeIf { it >= 0 }?.plus(1) } ?: project.scenes.size
            session.copy(editor = project.copy(
                scenes = project.scenes.toMutableList().apply { add(insertAt, scene) },
                autoPlan = false,
                nextKey = project.nextKey + 1,
            ))
        }
        return newKey
    }

    /** Lists one NAS/cloud folder for the editor Finder with a dedicated service; the browser screen is untouched. */
    suspend fun listEditorFinderFolder(connection: RemoteConnection, path: String): Result<List<RemoteFileItem>> = withContext(Dispatchers.IO) {
        runCatching {
            withDedicatedRemoteService(connection) { svc ->
                svc.list(path).filter { entry ->
                    entry.name.isNotBlank() && entry.name != "." && entry.name != ".." &&
                        !entry.name.startsWith('.') && entry.path.trimEnd('/') != path.trimEnd('/') &&
                        (entry.isDirectory || entry.isVideo || entry.isImage)
                }.sortedWith(compareBy<RemoteFileItem> { !it.isDirectory }.thenBy(NaturalOrderComparator) { it.name })
            }
        }
    }

    /**
     * Adds Finder selections to the CURRENT project: each item gets its own visible prepare/download/analysis state,
     * is copied into this project's directory, then appended as scenes (after [afterKey] when given).
     */
    fun editorAddCandidates(candidates: List<EditorAddCandidate>, afterKey: Long? = null) {
        val session = _state.value.trailer ?: return
        if (!session.prepared || session.editor == null) return
        val directory = session.directory
        val pending = candidates.distinctBy { it.originKey }.map { candidate ->
            val id = UUID.randomUUID().toString()
            pendingAddCandidates[id] = candidate
            pendingAddPrivate[id] = PrivacyMode.capturedOrigin(PrivacyMode.isPrivateOriginKey(candidate.originKey))
            EditorPendingAdd(id, candidate.originKey, candidate.name, candidate.isVideo)
        }
        if (pending.isEmpty()) return
        updateTrailer(directory) { it.copy(pendingAdds = it.pendingAdds + pending) }
        pendingAddAnchor = afterKey
        runEditorAddQueue(directory)
    }

    private var pendingAddAnchor: Long? = null

    fun retryEditorAdd(id: String) {
        val session = _state.value.trailer ?: return
        if (session.pendingAdds.none { it.id == id && it.phase == EditorAddPhase.FAILED }) return
        updateTrailer(session.directory) { live ->
            live.copy(pendingAdds = live.pendingAdds.map { if (it.id == id) it.copy(phase = EditorAddPhase.QUEUED, progress = null, error = null) else it })
        }
        runEditorAddQueue(session.directory)
    }

    /** Explicit skip of a queued/failed item; the original source is never touched. */
    fun dismissEditorAdd(id: String) {
        val session = _state.value.trailer ?: return
        val item = session.pendingAdds.firstOrNull { it.id == id } ?: return
        if (item.phase != EditorAddPhase.FAILED && item.phase != EditorAddPhase.QUEUED) return
        pendingAddCandidates.remove(id)
        pendingAddPrivate.remove(id)
        updateTrailer(session.directory) { live -> live.copy(pendingAdds = live.pendingAdds.filterNot { it.id == id }) }
    }

    private fun runEditorAddQueue(directory: File) {
        if (editorAddJob?.isActive == true) return
        editorAddJob = viewModelScope.launch {
            while (true) {
                val live = _state.value.trailer?.takeIf { it.directory == directory } ?: break
                val next = live.pendingAdds.firstOrNull { it.phase == EditorAddPhase.QUEUED } ?: break
                val candidate = pendingAddCandidates[next.id]
                if (candidate == null) {
                    updateTrailer(directory) { s -> s.copy(pendingAdds = s.pendingAdds.filterNot { it.id == next.id }) }
                    continue
                }
                try {
                    val source = stageEditorCandidate(directory, next, candidate)
                    appendPreparedSource(directory, next.id, source)
                    pendingAddCandidates.remove(next.id)
                    pendingAddPrivate.remove(next.id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    setPendingAdd(directory, next.id) { it.copy(phase = EditorAddPhase.FAILED, progress = null, error = failure.message ?: "재료를 준비하지 못했습니다.") }
                }
            }
            editorAddJob = null
        }
    }

    private fun setPendingAdd(directory: File, id: String, transform: (EditorPendingAdd) -> EditorPendingAdd) {
        updateTrailer(directory) { live -> live.copy(pendingAdds = live.pendingAdds.map { if (it.id == id) transform(it) else it }) }
    }

    /** Prepares one candidate through the existing material preparation helper and copies it into the project. */
    private suspend fun stageEditorCandidate(directory: File, pending: EditorPendingAdd, candidate: EditorAddCandidate): TrailerSource {
        setPendingAdd(directory, pending.id) { it.copy(phase = EditorAddPhase.PREPARING, progress = null, error = null) }
        val material = when (candidate) {
            is EditorAddCandidate.Remote -> MaterialItem(
                id = pending.id, sourceIdentity = candidate.originKey, name = candidate.name, sourceType = MaterialSourceType.REMOTE_FILE,
                isVideo = candidate.isVideo, uriString = candidate.item.path, connectionId = candidate.connection.id,
                remotePath = candidate.item.path, fileSize = candidate.item.size, state = MaterialPreparationState.MISSING,
            )
            is EditorAddCandidate.PhoneHard -> MaterialItem(
                id = pending.id, sourceIdentity = candidate.originKey, name = candidate.name, sourceType = MaterialSourceType.PHONE_HARD,
                isVideo = candidate.isVideo, uriString = Uri.fromFile(candidate.item.file).toString(), localFilePath = candidate.item.file.absolutePath,
            )
            is EditorAddCandidate.LocalUri -> MaterialItem(
                id = pending.id, sourceIdentity = candidate.originKey, name = candidate.name, sourceType = MaterialSourceType.LOCAL_LIBRARY,
                isVideo = candidate.isVideo, uriString = candidate.uri.toString(), captureTimeMillis = candidate.captureTimeMillis,
            )
            is EditorAddCandidate.WebFile -> MaterialItem(
                id = pending.id, sourceIdentity = candidate.originKey, name = candidate.name, sourceType = MaterialSourceType.PHONE_HARD,
                isVideo = candidate.isVideo, uriString = Uri.fromFile(candidate.file).toString(), localFilePath = candidate.file.absolutePath,
            )
        }
        // A fresh in-memory candidate: prepared for this project only, never registered in the basket.
        val prepared = prepareMaterialItems(listOf(material), onItemProgress = { _, fraction ->
            setPendingAdd(directory, pending.id) { it.copy(phase = EditorAddPhase.DOWNLOADING, progress = fraction) }
        }, registeredInBasket = false).single()
        check(prepared.state == MaterialPreparationState.READY) { prepared.errorMessage ?: "재료를 준비하지 못했습니다." }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()

        val extension = candidate.name.substringAfterLast('.', if (candidate.isVideo) "mp4" else "jpg")
            .takeIf { it.isNotBlank() && it.length <= 5 && it.all(Char::isLetterOrDigit) } ?: if (candidate.isVideo) "mp4" else "jpg"
        val destination = File(directory, "source-add-${UUID.randomUUID()}.$extension")
        val partial = File(directory, "${destination.name}.part")
        val materialsRoot = File(application.filesDir, "materials_cache").canonicalFile
        try {
            withContext(Dispatchers.IO) {
                val staged = prepared.localFilePath?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
                // A copy the helper created for THIS add only (fresh id, not in the basket) is moved; anything else
                // (PhoneHard files, basket copies, web downloads) is copied so the original is never moved or altered.
                val ownTemporaryCopy = staged != null && runCatching { staged.canonicalFile.parentFile == materialsRoot }.getOrDefault(false) &&
                    (staged.name.startsWith("remote_${pending.id}.") || staged.name.startsWith("mat_${pending.id}.")) &&
                    DurableMaterialStore.getAllItems().none { it.localFilePath == staged.absolutePath }
                when {
                    staged != null && ownTemporaryCopy && staged.renameTo(destination) -> Unit
                    staged != null -> staged.inputStream().buffered().use { input -> partial.outputStream().buffered().use { input.copyTo(it) } }
                    else -> {
                        val uri = Uri.parse(prepared.uriString)
                        application.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                            partial.outputStream().buffered().use { input.copyTo(it) }
                        } ?: error("미디어를 열 수 없습니다: ${candidate.name}")
                    }
                }
                if (partial.exists()) {
                    check(partial.length() > 0L && partial.renameTo(destination)) { "재료를 프로젝트에 복사하지 못했습니다." }
                }
            }
            check(destination.isFile && destination.length() > 0L) { "재료를 프로젝트에 복사하지 못했습니다." }

            setPendingAdd(directory, pending.id) { it.copy(phase = EditorAddPhase.ANALYZING, progress = null) }
            val isImage = !candidate.isVideo
            val captureTime = (candidate as? EditorAddCandidate.LocalUri)?.captureTimeMillis
                ?: (candidate as? EditorAddCandidate.Remote)?.item?.modifiedAt?.toEpochMilli()
                ?: (candidate as? EditorAddCandidate.PhoneHard)?.item?.importedAt?.toEpochMilli() ?: 0L
            val decoded = decodeStagedTrailerFile(destination, candidate.name, isImage, captureTime)
                ?: error("지원되지 않거나 손상된 미디어입니다: ${candidate.name}")
            val (metadata, thumbnail, capTime) = decoded
            val live = _state.value.trailer?.takeIf { it.directory == directory } ?: throw CancellationException("편집이 닫혔습니다.")
            val analysis = if (isImage || live.isMusicOverlayMode) {
                AudioAnalysisResult(emptyList(), 0.0, listOf(0.0), ClipAudioAvailability.NoTrack, ClipHighlightSource.Fallback)
            } else {
                AudioAnalysisService.analyze(application, Uri.fromFile(destination), metadata.first, (metadata.first.coerceAtMost(1024.0) * 2).toInt().coerceIn(192, 2048))
            }
            val finalThumbnail = if (!isImage && !live.isMusicOverlayMode) improveVideoThumbnail(destination, metadata.first, analysis.peakTimeSeconds, thumbnail) else thumbnail
            return TrailerSource(
                id = -1, name = candidate.name, file = destination, duration = metadata.first,
                width = metadata.second, height = metadata.third, analysis = analysis, thumbnail = finalThumbnail,
                isImage = isImage, captureTimeMillis = capTime, originKey = candidate.originKey,
            )
        } catch (failure: Throwable) {
            // Only this add's own partial/copied file inside the project directory is removed.
            withContext(NonCancellable + Dispatchers.IO) {
                partial.delete()
                if (_state.value.trailer?.sources?.none { it.file == destination } != false) destination.delete()
            }
            throw failure
        }
    }

    private suspend fun appendPreparedSource(directory: File, pendingId: String, staged: TrailerSource) {
        val live = _state.value.trailer?.takeIf { it.directory == directory } ?: return
        // 비공개 출처 미디어가 한 번이라도 들어오면 프로젝트는 영구히 비공개 출처가 된다(공개로 되돌아가지 않는다).
        val addPrivate = pendingAddPrivate[pendingId] == true || PrivacyMode.isPrivateOriginKey(staged.originKey)
        val newSource = staged.copy(id = (live.sources.maxOfOrNull { it.id } ?: -1) + 1)
        val analysedSources = if (!live.isMusicOverlayMode && live.editor?.autoPlan == true && newSource.isImage) {
            setPendingAdd(directory, pendingId) { it.copy(phase = EditorAddPhase.ANALYZING) }
            analyzeTrailerPhotos(application, live.sources + newSource)
        } else live.sources + newSource
        val anchor = pendingAddAnchor
        val changed = editProject { session ->
            if (session.directory != directory || session.sources.any { it.id == newSource.id }) return@editProject null
            val project = session.editor ?: return@editProject null
            val withSources = session.copy(
                sources = analysedSources.filter { candidate -> candidate.id == newSource.id || session.sources.any { it.id == candidate.id } },
                originPrivate = session.originPrivate || addPrivate,
            )
            if (project.autoPlan && !session.isMusicOverlayMode) {
                val defaults = recommendedTrailerDefaults(withSources.sources)
                withSources.copy(
                    clipLength = defaults.clipLength,
                    total = defaults.total,
                    photoClipLength = defaultPhotoClipLength(defaults.clipLength, withSources.hasVideos),
                ).withRegeneratedPlan()
            } else {
                val scene = withSources.defaultSceneFor(newSource, project.nextKey)
                val insertAt = anchor?.let { key -> project.scenes.indexOfFirst { it.key == key }.takeIf { it >= 0 }?.plus(1) } ?: project.scenes.size
                withSources.copy(editor = project.copy(
                    scenes = project.scenes.toMutableList().apply { add(insertAt, scene) },
                    nextKey = project.nextKey + 1,
                ))
            }
        }
        if (changed) {
            if (anchor != null) pendingAddAnchor = (_state.value.trailer?.editor?.scenes?.firstOrNull { it.sourceId == newSource.id }?.key) ?: anchor
            updateTrailer(directory) { s -> s.copy(pendingAdds = s.pendingAdds.filterNot { it.id == pendingId }) }
        } else {
            setPendingAdd(directory, pendingId) { it.copy(phase = EditorAddPhase.FAILED, error = "편집 중인 작업이 있어 추가하지 못했습니다. 다시 시도해 주세요.") }
        }
    }

    /**
     * Exports a FIXED snapshot of the current project. Editing continues; the running composition is never mutated,
     * and a later edit simply makes the result outdated (next export reflects it).
     */
    fun exportEditorProject() = exportEditorProjectInternal(skipUnavailableMusic = false)

    private fun exportEditorProjectInternal(skipUnavailableMusic: Boolean) {
        val session = _state.value.trailer ?: return
        if (!session.prepared || session.busy || session.export?.running == true) return
        val scenes = session.scenes
        val directory = session.directory
        if (scenes.isEmpty()) {
            updateTrailer(directory) { it.copy(export = EditorExportState(false, it.revision, "", error = "내보낼 장면이 없습니다. 재료를 추가해 주세요.")) }
            return
        }
        // A stored but unreadable music choice is never silently dropped from the soundtrack: the user resolves it
        // (다시 확인 / 다른 음악) or explicitly chooses to export without it (trailer mode only).
        if (session.musicTrack == null && session.unavailableMusic != null && (!skipUnavailableMusic || session.isMusicOverlayMode)) {
            updateTrailer(directory) { it.copy(musicExportPrompt = true) }
            return
        }
        if (session.isMusicOverlayMode && session.musicTrack == null) {
            updateTrailer(directory) { it.copy(export = EditorExportState(false, it.revision, "", error = "뮤직비디오는 배경 음악을 먼저 선택해 주세요.")) }
            return
        }
        val revision = session.revision
        val format = session.format
        val musicTrack = session.musicTrack
        val musicPolicy = session.musicPolicy
        val musicFitCount = session.musicFitCount
        val autoDucking = session.autoDucking
        val musicVolume = session.editor?.musicVolume ?: 1f
        updateTrailer(directory) { it.copy(export = EditorExportState(true, revision, "장면을 준비하는 중…")) }
        editorExportJob = viewModelScope.launch {
            val self = coroutineContext[Job]
            val output = File(directory, "trailer-${UUID.randomUUID()}.mp4")
            try {
                TrailerExporter.export(
                    context = application,
                    scenes = scenes,
                    format = format,
                    destination = output,
                    musicTrack = musicTrack,
                    musicPolicy = musicPolicy,
                    musicFitCount = musicFitCount,
                    autoDucking = autoDucking,
                    aspectSources = scenes.map { it.source },
                    musicVolume = musicVolume,
                ) { status, progress ->
                    updateTrailer(directory) { live -> live.copy(export = live.export?.copy(status = status, progress = progress)) }
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val previous = _state.value.trailer?.takeIf { it.directory == directory }?.output
                updateTrailer(directory) { it.copy(output = output, outputRevision = revision, export = null, saved = false) }
                val live = _state.value.trailer?.takeIf { it.directory == directory }
                val saved = live != null && withContext(Dispatchers.IO) { draftFromSession(live)?.let(TrailerDraftStore::saveDraft) == true }
                // The superseded render of this same project is removed only after the new one is recorded.
                if (saved && previous != null && previous != output && previous.parentFile == directory && previous.name.startsWith("trailer-")) {
                    withContext(Dispatchers.IO) { previous.delete() }
                }
                updateTrailer(directory) { it.copy(saveState = if (saved) it.saveState else EditorSaveState.FAILED) }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { output.delete() }
                throw cancelled
            } catch (failure: Exception) {
                withContext(NonCancellable + Dispatchers.IO) { output.delete() }
                updateTrailer(directory) { it.copy(export = EditorExportState(false, revision, "", error = failure.message ?: "내보내지 못했습니다. 다시 시도해 주세요.")) }
            } finally {
                if (editorExportJob === self) editorExportJob = null
            }
        }
    }

    fun cancelEditorExport() {
        val session = _state.value.trailer ?: return
        if (session.export?.running != true) return
        editorExportJob?.cancel()
        updateTrailer(session.directory) { it.copy(export = null) }
    }

    fun dismissEditorExportError() {
        val session = _state.value.trailer ?: return
        updateTrailer(session.directory) { if (it.export?.running == true) it else it.copy(export = null) }
    }

    /** Keeps the open project alive (export/adds continue) while the rail opens another destination. */
    fun minimizeEditor(destination: MainNavigationDestination) {
        if (_state.value.trailer == null) return
        _state.value.trailer?.takeIf { it.prepared && it.editor != null }?.let { scheduleEditorAutosave(it.directory, immediate = true) }
        _state.update { it.copy(trailerMinimized = true) }
        selectMainDestination(destination)
    }

    fun restoreEditor() {
        val session = _state.value.trailer ?: return
        if (!PrivacyMode.isVisible(session.originPrivate)) return
        val currentScreen = _state.value.screen
        val resolvedScreen = if (currentScreen == Screen.WebBrowser) Screen.Dashboard else currentScreen
        _state.update { it.copy(trailerMinimized = false, imagePreview = null, screen = resolvedScreen) }
        editorRecheckMusic(silent = true)
    }

    private var trailerModeSwitchToken = 0L

    // Switches the open editor between trailer and music-video modes in place. Keeps the raw sources,
    // their order, the chosen music and ducking; only the derived output is cleared. Music-origin
    // sources lack highlight/photo analysis, so switching to trailer runs the real analysis first.
    fun switchTrailerMode(toMusicVideo: Boolean, order: List<Int>) {
        val session = _state.value.trailer ?: return
        if (session.busy || !session.prepared || session.isMusicOverlayMode == toMusicVideo) return
        val directory = session.directory
        val byId = session.sources.associateBy { it.id }
        val ordered = order.distinct().mapNotNull { byId[it] }.ifEmpty { session.sources }
        if (ordered.isEmpty()) return
        val token = ++trailerModeSwitchToken

        fun modeSession(base: TrailerSession, list: List<TrailerSource>): TrailerSession {
            val videoDurations = list.filter { !it.isImage }.map { it.duration }
            val minVideo = videoDurations.minOrNull() ?: 4.0
            val hasVideos = list.any { !it.isImage }
            val trailerDefaults = if (toMusicVideo) null else recommendedTrailerDefaults(list)
            val length = trailerDefaults?.clipLength ?: minVideo.coerceAtLeast(1.0)
            val photoLength = if (toMusicVideo) 1.0 else defaultPhotoClipLength(length, hasVideos)
            val total = trailerDefaults?.total
                ?: list.sumOf { if (it.isImage) photoLength else it.duration }
            // The previous render stays as the (now outdated) output; the new mode starts from its own recommendation.
            return base.copy(
                sources = list,
                isMusicOverlayMode = toMusicVideo,
                clipLength = length,
                photoClipLength = photoLength,
                total = total,
                saved = false,
                error = null,
                progress = null,
                busy = false,
                awaitingInitialSettings = true,
                highlightAnalyzed = base.highlightAnalyzed || !toMusicVideo,
                status = if (toMusicVideo) "음악을 선택해 주세요." else "장면을 분석했습니다. 설정을 확인해 주세요.",
            ).let { switched ->
                switched.copy(editor = EditorProject.fromPlan(
                    switched.autoPlanScenes(),
                    autoPlan = true,
                    musicVolume = base.editor?.musicVolume ?: 1f,
                    firstKey = base.editor?.nextKey ?: 1L,
                ))
            }
        }
        val before = session.editorSnapshot()
        fun commitModeSwitch() {
            val history = editorHistory(directory)
            history.record(before)
            updateTrailer(directory) { it.copy(revision = it.revision + 1, canUndo = history.canUndo, canRedo = history.canRedo) }
            scheduleEditorAutosave(directory)
        }

        if (toMusicVideo || session.highlightAnalyzed) {
            _state.update { current ->
                val live = current.trailer?.takeIf { it.directory == directory && !it.busy } ?: return@update current
                current.copy(trailer = modeSession(live, ordered))
            }
            commitModeSwitch()
            return
        }

        updateTrailer(directory) { it.copy(busy = true, progress = null, error = null, status = "한양이 장면을 고르는 중…") }
        trailerJob = viewModelScope.launch {
            try {
                val analyzed = ordered.mapIndexed { index, source ->
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (source.isImage) source else {
                        updateTrailer(directory) { it.copy(status = "${index + 1}/${ordered.size} · 한양이 장면을 고르는 중…") }
                        val analysis = AudioAnalysisService.analyze(application, Uri.fromFile(source.file), source.duration, (source.duration.coerceAtMost(1024.0) * 2).toInt().coerceIn(192, 2048))
                        source.copy(analysis = analysis)
                    }
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val prepared = if (analyzed.any { it.isImage }) {
                    updateTrailer(directory) { it.copy(status = "사진을 비교하는 중…") }
                    analyzeTrailerPhotos(application, analyzed)
                } else analyzed
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (token != trailerModeSwitchToken) return@launch
                _state.update { current ->
                    val live = current.trailer?.takeIf { it.directory == directory && it.busy && it.isMusicOverlayMode } ?: return@update current
                    current.copy(trailer = modeSession(live, prepared).copy(highlightAnalyzed = true))
                }
                commitModeSwitch()
                trailerJob = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (token != trailerModeSwitchToken) return@launch
                updateTrailer(directory) { it.copy(busy = false, progress = null, status = "음악을 선택해 주세요.") }
                _state.update { it.copy(message = failure.message ?: "예고편 분석에 실패했습니다.") }
            }
        }
    }

    fun retryTrailer() {
        val session = _state.value.trailer ?: return
        if (session.prepared && session.editor != null) exportEditorProject()
        else trailerRetry?.invoke()
    }

    fun saveTrailer() {
        val session = _state.value.trailer ?: return
        val output = session.output ?: return
        val directory = session.directory
        if (session.busy || session.saved) return
        _state.update { it.copy(trailer = session.copy(busy = true, status = "폰하드에 저장하는 중…", error = null)) }
        trailerJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    output.inputStream().buffered().use {
                        inboxStore.import("예고편-${System.currentTimeMillis()}.mp4", "video/mp4", it, isPrivate = session.originPrivate)
                    }
                }
                val files = inboxFiles()
                _state.update { current ->
                    current.copy(inboxFiles = files, trailer = current.trailer?.let { if (it.directory == directory) it.copy(busy = false, saved = true) else it })
                }
                NasFinderAppWidgetProvider.updateAll(application)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { kotlinx.coroutines.currentCoroutineContext().ensureActive(); updateTrailer(directory) { it.copy(busy = false, error = failure.message ?: "저장하지 못했습니다. 다시 시도해 주세요.") } }
        }
    }

    /**
     * Stores the current render as an immutable collection copy after title/poster confirmation. The editable project
     * stays open and saved; the poster is the user-confirmed candidate (or the first frame when none was chosen).
     */
    fun completeTrailerToCollection(title: String, posterCandidate: File? = null) {
        val session = _state.value.trailer ?: return
        val output = session.output ?: return
        val directory = session.directory
        if (session.busy || session.export?.running == true || !output.exists() || output.length() <= 0L) return

        updateTrailer(directory) { it.copy(busy = true, status = "컬렉션에 보관하는 중…", error = null) }
        viewModelScope.launch {
            val createdPosters = mutableListOf<File>()
            try {
                val cleanTitle = title.ifBlank { "완성된 영상" }
                val saved = withContext(Dispatchers.IO) {
                    val duration = readVideoDurationSeconds(output)
                    var poster = posterCandidate?.takeIf { it.isFile && it.length() > 0L }
                    if (poster == null) {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(output.absolutePath)
                            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { frame ->
                                val tempPoster = File(directory, "poster-auto-${UUID.randomUUID()}.jpg")
                                tempPoster.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                                createdPosters += tempPoster
                                poster = tempPoster
                            }
                        } catch (_: Exception) {
                        } finally { runCatching { retriever.release() } }
                    }
                    DurableCollectionStore.addVideo(
                        title = cleanTitle,
                        exportedFile = output,
                        durationSeconds = duration,
                        posterFile = poster,
                        sourceDraftId = session.draftId,
                        isPrivate = session.originPrivate,
                    )
                }
                refreshCollections()
                updateTrailer(directory) { it.copy(busy = false) }
                _state.value.trailer?.takeIf { it.directory == directory }?.let { scheduleEditorAutosave(directory, immediate = true) }
                _state.update {
                    it.copy(message = if (saved != null) "‘${saved.title}’을(를) 컬렉션에 추가했습니다. 편집 중인 프로젝트는 그대로 유지됩니다." else "컬렉션 저장에 실패했습니다. 저장 공간을 확인해 주세요.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                updateTrailer(directory) { it.copy(busy = false, error = failure.message ?: "컬렉션에 보관하지 못했습니다.") }
            } finally {
                // Temporary poster files of this action only (the store keeps its own copy).
                withContext(NonCancellable + Dispatchers.IO) {
                    createdPosters.forEach { it.delete() }
                    posterCandidate?.takeIf { it.parentFile == directory && it.name.startsWith("poster-candidate-") }?.delete()
                }
            }
        }
    }

    private fun readVideoDurationSeconds(file: File): Double {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000.0) ?: 0.0
        } catch (_: Exception) {
            0.0
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun dismissTrailer() {
        suspendTrailerDraft(showingSettings = false)
    }

    private fun closeTrailer(clearRetry: Boolean) {
        val session = _state.value.trailer
        val running = trailerJob
        _state.update { it.copy(trailer = null, trailerMinimized = false) }
        trailerJob = null
        stopEditorBackgroundWork(session)
        if (clearRetry) trailerRetry = null
        if (session != null) {
            val isManaged = TrailerDraftStore.isDirectoryManagedAsDraft(session.directory)
            if (!isManaged) {
                if (running?.isActive == true) {
                    running.invokeOnCompletion { TrailerDraftStore.safeDeletePrivateTrailerDirectory(session.directory) }
                    running.cancel()
                } else TrailerDraftStore.safeDeletePrivateTrailerDirectory(session.directory)
            } else if (running?.isActive == true) {
                running.cancel()
            }
        }
    }

    /** Cancels this project's export/add/autosave jobs; their own partial files are removed by those jobs. */
    private fun stopEditorBackgroundWork(session: TrailerSession?) {
        editorExportJob?.cancel(); editorExportJob = null
        editorAddJob?.cancel(); editorAddJob = null
        editorAutosaveJob?.cancel(); editorAutosaveJob = null
        session?.pendingAdds?.forEach { pendingAddCandidates.remove(it.id); pendingAddPrivate.remove(it.id) }
        pendingAddAnchor = null
        session?.let {
            editorHistories.remove(it.directory.absolutePath)
            editorFinderSelections.remove(it.directory.absolutePath)
        }
    }

    /** Saves the CURRENT project from the session itself and closes the editor (nothing is discarded). */
    fun suspendTrailerDraft(
        showingSettings: Boolean = false,
        wasCancelled: Boolean = false,
    ) {
        val session = _state.value.trailer ?: return
        if (session.prepared) {
            // Save BEFORE stopping anything, so a failed save can keep the project (and its running work) open.
            val sessionWithId = if (session.draftId == null) session.copy(draftId = UUID.randomUUID().toString()) else session
            val saved = sessionWithId.sources.isNotEmpty() &&
                draftFromSession(sessionWithId, showingSettings)?.let(TrailerDraftStore::saveDraft) == true
            if (!saved && !wasCancelled) {
                // Never drop unsaved edits of a prepared project silently: keep the editor open with a visible failure.
                // (An explicit "멈추고 닫기" closes without deleting anything; the project files stay on disk.)
                updateTrailer(session.directory) {
                    it.copy(draftId = sessionWithId.draftId, saveState = EditorSaveState.FAILED, saveError = "프로젝트를 저장하지 못해 닫지 않았습니다. 저장 공간을 확인해 주세요.")
                }
                _state.update { it.copy(trailerMinimized = false) }
                return
            }
            trailerJob?.cancel()
            trailerJob = null
            stopEditorBackgroundWork(session)
            _state.update {
                it.copy(
                    trailer = null,
                    trailerMinimized = false,
                    trailerDraftCount = TrailerDraftStore.draftCount(),
                    message = if (!saved) "마지막 변경을 저장하지 못했습니다. 프로젝트 파일은 지우지 않았습니다." else it.message,
                )
            }
            return
        }

        val running = trailerJob
        trailerJob = null
        val wasRunning = running?.isActive == true
        if (wasRunning) running?.cancel()
        stopEditorBackgroundWork(session)

        // Staging/analysis never finished: there is no valid plan to keep, so only this
        // session's own partial directory is removed and no draft is stored.
        run {
            if (!TrailerDraftStore.isDirectoryManagedAsDraft(session.directory)) {
                if (wasRunning) running?.invokeOnCompletion { TrailerDraftStore.safeDeletePrivateTrailerDirectory(session.directory) }
                else TrailerDraftStore.safeDeletePrivateTrailerDirectory(session.directory)
            }
            val cancelMsg = if (session.isMusicOverlayMode) "뮤직비디오가 취소되었습니다." else "예고편 만들기가 취소되었습니다."
            _state.update {
                it.copy(
                    trailer = null,
                    trailerMinimized = false,
                    trailerDraftCount = TrailerDraftStore.draftCount(),
                    message = if (wasCancelled) cancelMsg else it.message,
                )
            }
        }
    }

    /** True when [directory] is the open project's folder (migration and cleanup never touch it). */
    private fun isTrailerDirectoryInUse(directory: File): Boolean {
        val open = _state.value.trailer?.directory ?: return false
        return runCatching { open.canonicalPath == directory.canonicalPath }.getOrDefault(true)
    }

    /**
     * Background, startup-time move of legacy cache data into durable storage: interrupted earlier migration copies
     * are cleaned, legacy cache drafts are copied+validated+committed, and collection records that point at
     * temporary cache files get their own permanent copy. Every failure keeps the original record and files.
     */
    private fun migrateEphemeralProjectData() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { TrailerDraftStore.cleanupInterruptedMigrations() }
            runCatching { TrailerDraftStore.migrateLegacyDrafts(::isTrailerDirectoryInUse) }
            runCatching { DurableCollectionStore.ephemeralRecordsToPromote().forEach { DurableCollectionStore.promoteEphemeralRecord(it) } }
            _state.update {
                it.copy(
                    trailerDraftCount = TrailerDraftStore.draftCount(),
                    trailerDraftsVersion = it.trailerDraftsVersion + 1,
                    trailerDraftPicker = it.trailerDraftPicker?.let { TrailerDraftStore.getAllDrafts() },
                    collections = DurableCollectionStore.getItems(),
                )
            }
        }
    }

    /** "다시 확인": re-reads availability and retries any pending legacy migration (nothing is deleted). */
    fun recheckTrailerDrafts() {
        migrateEphemeralProjectData()
    }

    fun resumeTrailerDraft(requested: TrailerDraft) {
        // The list a user tapped may predate a migration: always open the latest stored record.
        val draft = TrailerDraftStore.draft(requested.id) ?: requested
        _state.value.trailer?.let { open ->
            if (open.draftId == draft.id) {
                restoreEditor()
                return
            }
            if (open.export?.running == true || open.busy) {
                _state.update { it.copy(message = "‘${open.displayTitle}’ 작업이 진행 중입니다. 끝나거나 취소한 뒤 다른 편집을 열어 주세요.", trailerDraftPicker = null) }
                return
            }
            // Explicit open of another project: the current one is saved first, never discarded.
            suspendTrailerDraft()
            if (_state.value.trailer != null) return
        }
        stopInlineAudio()
        if (!draft.isValid()) {
            // Never delete anything here: the record and every surviving file stay, so the user can reconnect,
            // retry later, or remove the project explicitly from 편집본.
            val details = draft.unavailableDetails().take(6).joinToString("\n") { "· $it" }
            _state.update {
                it.copy(
                    trailerDraftStaleNotice = "‘${draft.displayTitle}’을(를) 지금 열 수 없습니다.\n$details\n\n" +
                        "편집 정보와 남아 있는 파일은 지우지 않았습니다. 파일이 있는 저장소를 다시 연결한 뒤 편집본에서 ‘다시 확인’을 누르거나, 필요 없으면 직접 삭제해 주세요.",
                    trailerDraftCount = TrailerDraftStore.draftCount(),
                    trailerDraftsVersion = it.trailerDraftsVersion + 1,
                    trailerDraftPicker = null,
                )
            }
            return
        }
        val musicAvailable = draft.musicTrack?.let(::isMusicFileReadable)
        val availableOutput = draft.output?.takeIf { it.exists() && it.length() > 0 }

        // The draft stays registered while open, so its durable directory is protected and autosave updates it in place.
        val restoredSession = TrailerSession(
            directory = draft.directory,
            sources = draft.sources,
            total = draft.total,
            clipLength = draft.clipLength,
            photoClipLength = draft.photoClipLength,
            format = draft.format,
            output = availableOutput,
            busy = false,
            status = if (availableOutput != null) "예고편을 불러왔습니다." else "편집을 이어갑니다.",
            progress = null,
            error = null,
            saved = false,
            isMusicOverlayMode = draft.isMusicOverlayMode,
            musicTrack = draft.musicTrack?.takeIf { musicAvailable == true },
            // Kept, displayed as unavailable and saved back unchanged until the user reconnects, replaces or removes it.
            unavailableMusic = draft.musicTrack?.takeIf { musicAvailable == false },
            musicPolicy = draft.musicPolicy,
            musicFitCount = draft.musicFitCount,
            autoDucking = draft.autoDucking,
            originPrivate = draft.isPrivate,
            awaitingInitialSettings = draft.showingSettings || availableOutput == null,
            prepared = true,
            draftId = draft.id,
            highlightAnalyzed = !draft.isMusicOverlayMode,
            orderMode = draft.orderMode,
            title = draft.customTitle,
            saveState = EditorSaveState.SAVED,
            revision = draft.revision ?: 0L,
            outputRevision = if (availableOutput != null) draft.outputRevision else null,
        ).let { restored ->
            // Older drafts had no explicit scenes: derive them once from their stored plan parameters.
            restored.copy(
                editor = draft.editor ?: EditorProject.fromPlan(restored.autoPlanScenes(), autoPlan = true),
            )
        }
        editorHistories.remove(draft.directory.absolutePath)

        _state.update {
            it.copy(
                trailer = restoredSession,
                imagePreview = null,
                trailerMinimized = false,
                trailerDraftPicker = null,
                trailerDraftCount = TrailerDraftStore.draftCount(),
                message = if (musicAvailable == false) {
                    "음악 ‘${draft.musicTrack?.name}’ 파일을 지금 찾을 수 없습니다. 음악 선택과 길이·볼륨·더킹 설정은 그대로 보관합니다. " +
                        "소리 탭에서 ‘다시 확인’하거나 다른 음악을 고를 수 있으며, 해결하기 전에는 내보낼 때 확인을 요청합니다."
                } else it.message,
            )
        }
    }

    fun duplicateTrailerDraft(draft: TrailerDraft) {
        val duplicated = TrailerDraftStore.duplicateDraft(draft)
        if (duplicated != null) {
            val remaining = TrailerDraftStore.getAllDrafts()
            _state.update {
                it.copy(
                    trailerDraftCount = remaining.size,
                    trailerDraftPicker = remaining,
                    message = "‘${draft.displayTitle}’ 사본을 만들었습니다."
                )
            }
        } else {
            _state.update { it.copy(message = "사본을 만들지 못했습니다. 재료 파일과 저장 공간을 확인해 주세요. 원래 편집본은 그대로입니다.") }
        }
    }

    fun renameTrailerDraft(draft: TrailerDraft, newTitle: String) {
        TrailerDraftStore.renameDraft(draft.id, newTitle)
        val remaining = TrailerDraftStore.getAllDrafts()
        _state.update {
            it.copy(
                trailerDraftPicker = remaining,
                trailerDraftCount = remaining.size
            )
        }
    }

    fun discardTrailerDraft(draft: TrailerDraft) {
        if (_state.value.trailer?.let { it.draftId == draft.id || it.directory.absolutePath == draft.directory.absolutePath } == true) {
            _state.update { it.copy(message = "지금 열려 있는 편집은 삭제할 수 없습니다. 편집을 닫은 뒤 삭제해 주세요.") }
            return
        }
        val removed = TrailerDraftStore.removeDraft(draft)
        if (removed) {
            editorFinderSelections.remove(draft.directory.absolutePath)
        }
        val remaining = TrailerDraftStore.getAllDrafts()
        _state.update {
            it.copy(
                trailerDraftCount = remaining.size,
                trailerDraftsVersion = it.trailerDraftsVersion + 1,
                trailerDraftPicker = remaining,
                message = if (!removed) "편집본 정보를 저장하지 못해 삭제하지 않았습니다. 프로젝트 파일도 그대로입니다." else it.message,
            )
        }
    }

    fun selectMainDestination(destination: MainNavigationDestination) {
        val hasOpenEditor = _state.value.trailer != null
        if (destination == MainNavigationDestination.HOME && hasOpenEditor) {
            _state.value.trailer?.takeIf { it.prepared && it.editor != null }?.let { scheduleEditorAutosave(it.directory, immediate = true) }
        }
        _state.update {
            it.copy(
                activeMainTab = destination,
                screen = Screen.Dashboard,
                trailerMinimized = if (destination == MainNavigationDestination.HOME && hasOpenEditor) true else it.trailerMinimized,
            )
        }
    }

    fun selectVideoDestination(destination: VideoNavigationDestination) {
        // The former 영상 재료 tab is the 작업대 now; the old destination keeps working as an alias.
        if (destination == VideoNavigationDestination.MATERIALS) {
            selectMainDestination(MainNavigationDestination.WORKBENCH)
            return
        }
        _state.update {
            it.copy(
                activeMainTab = MainNavigationDestination.VIDEO,
                activeVideoTab = destination,
                screen = Screen.Dashboard,
            )
        }
    }

    /** Explicit 미디어 고르기 entry of the 영상 hub: the only way from the hub into the existing media picker / editor path. */
    fun chooseMediaForVideo() = show(Screen.LocalPhotoLibrary(LocalMediaEditIntent.TRAILER))

    // ---- 작업대 (workbench): pockets of REFERENCES ----------------------------------------------------------------
    // Adding stores location / identity / permission / metadata only. Originals are read again only when the user
    // explicitly starts 영상 제작 or 전송, into a workspace owned by that run. Removing a reference or a pocket never
    // deletes an original, a legacy copy or a previous material cache.

    private var materialCheckJob: Job? = null
    private var workbenchRunJob: Job? = null

    private fun publishMaterials() {
        val all = DurableMaterialStore.getItems()
        val pockets = DurableMaterialStore.pockets()
        val ids = all.map { it.id }.toSet()
        _state.update { current ->
            current.copy(
                materials = all,
                pockets = pockets,
                workbenchPocketId = current.workbenchPocketId.takeIf { id -> pockets.any { it.id == id } } ?: DEFAULT_POCKET_ID,
                workbenchSelected = current.workbenchSelected.intersect(ids),
            )
        }
    }

    /** Publishes the saved references at once, then re-checks reachability off the main thread (never copies or downloads). */
    fun refreshMaterials() {
        publishMaterials()
        materialCheckJob?.cancel()
        materialCheckJob = viewModelScope.launch {
            val saveFailed = withContext(Dispatchers.IO) {
                var failed = false
                DurableMaterialStore.getItems().forEach { item ->
                    ensureActive()
                    val updated = verifyMaterialAccess(item) ?: return@forEach
                    if (!DurableMaterialStore.updateState(updated.id, updated.state, updated.downloadProgress, updated.localFilePath, updated.errorMessage)) failed = true
                }
                failed
            }
            publishMaterials()
            if (saveFailed) _state.update { it.copy(message = MATERIAL_SAVE_ERROR) }
        }
    }

    /**
     * Whether the provider still answers for this reference, judged ONLY from metadata (a display-name query or the tree
     * document itself). It never opens a stream or an asset descriptor: for Google / cloud providers that can trigger a full
     * download. The real readable check happens when the user starts 영상 제작 or 전송. A revoked / expired grant shows up as
     * a SecurityException here and is reported as "reconnect needed", nothing is copied.
     */
    private fun localReferenceReadable(item: MaterialItem): Boolean = runCatching {
        val uri = Uri.parse(item.uriString)
        when {
            uri.scheme == "file" -> File(uri.path.orEmpty()).let { if (item.isFolder) it.isDirectory else it.isFile }
            item.isFolder -> {
                val doc = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
                application.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } ?: false
            }
            else -> application.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { it.moveToFirst() } ?: false
        }
    }.getOrDefault(false)

    /** The item with its corrected state, or null when nothing changes. Only reads the reference; never writes a file. */
    private fun verifyMaterialAccess(item: MaterialItem): MaterialItem? {
        if (item.state == MaterialPreparationState.EXCLUDED || item.state == MaterialPreparationState.DOWNLOADING) return null
        val failure: String? = when (item.sourceType) {
            MaterialSourceType.LOCAL_LIBRARY -> {
                // An app-owned legacy copy keeps the item usable even when the original is no longer reachable.
                val hasOwnedCopy = ownedMaterialCopy(item.localFilePath, getMaterialsDirectory()) != null
                if (hasOwnedCopy || localReferenceReadable(item)) null
                else if (item.grantPersisted == false) LOCAL_MATERIAL_GRANT_ERROR else LOCAL_MATERIAL_ACCESS_ERROR
            }
            MaterialSourceType.PHONE_HARD -> {
                val available = if (item.isFolder) resolvePhoneHardDiskFile(item.remotePath.orEmpty()).isDirectory
                else item.localFilePath?.let { File(it) }?.let { it.exists() && it.length() > 0L } == true
                if (available) null else "폰하드 항목이 삭제되었거나 이동되었습니다."
            }
            // NAS / cloud references are checked when a run starts; no network access just for opening the list.
            MaterialSourceType.REMOTE_FILE -> {
                val hasLocal = item.localFilePath?.let { File(it) }?.let { it.exists() && it.length() > 0L } == true
                return if (hasLocal && item.state == MaterialPreparationState.MISSING && item.errorMessage == WORKBENCH_REMOTE_NOT_FETCHED) {
                    item.copy(state = MaterialPreparationState.READY, errorMessage = null)
                } else null
            }
        }
        val accessFailureMessage = item.errorMessage == null || item.errorMessage == LOCAL_MATERIAL_ACCESS_ERROR ||
            item.errorMessage == LOCAL_MATERIAL_GRANT_ERROR || item.errorMessage.startsWith("폰하드")
        return when {
            failure == null && item.state == MaterialPreparationState.MISSING && accessFailureMessage ->
                item.copy(state = MaterialPreparationState.READY, errorMessage = null)
            failure != null && item.state == MaterialPreparationState.READY ->
                item.copy(state = MaterialPreparationState.MISSING, errorMessage = failure)
            else -> null
        }
    }

    // pockets ----------------------------------------------------------------------------------------------------

    fun selectWorkbenchPocket(id: String) {
        if (DurableMaterialStore.pockets().any { it.id == id }) _state.update { it.copy(workbenchPocketId = id) }
    }

    fun renameWorkbenchPocket(id: String, name: String) {
        val saved = DurableMaterialStore.renamePocket(id, name)
        publishMaterials()
        if (!saved) _state.update { it.copy(message = POCKET_SAVE_ERROR) }
    }

    /** Deletes the pocket and ONLY its references; originals, legacy copies and other pockets stay. */
    fun deleteWorkbenchPocket(id: String) {
        val saved = DurableMaterialStore.deletePocket(id)
        publishMaterials()
        _state.update {
            it.copy(message = if (saved) "포켓을 삭제했습니다. 원본 파일과 다른 포켓의 항목은 그대로입니다." else POCKET_DELETE_ERROR)
        }
    }

    /** Creates an (empty) pocket from the 작업대 screen. */
    fun createWorkbenchPocket(name: String) {
        val pocket = DurableMaterialStore.createPocket(name)
        publishMaterials()
        if (pocket == null) _state.update { it.copy(message = POCKET_SAVE_ERROR) }
        else _state.update { it.copy(workbenchPocketId = pocket.id) }
    }

    /** Creates a pocket and returns its id, or null (with a visible message) when it could not be saved. */
    fun createWorkbenchPocketNow(name: String): String? {
        val pocket = DurableMaterialStore.createPocket(name)
        publishMaterials()
        if (pocket == null) _state.update { it.copy(message = POCKET_SAVE_ERROR) }
        return pocket?.id
    }

    // adding references -------------------------------------------------------------------------------------------

    fun setWorkbenchSelected(ids: Set<String>) { _state.update { it.copy(workbenchSelected = ids) } }

    fun toggleWorkbenchSelected(id: String) {
        _state.update { it.copy(workbenchSelected = if (id in it.workbenchSelected) it.workbenchSelected - id else it.workbenchSelected + id) }
    }

    private fun offerWorkbenchAdd(candidates: List<MaterialItem>, afterAdd: ((String) -> Unit)? = null, note: String? = null) {
        if (candidates.isEmpty()) {
            _state.update { it.copy(message = note ?: "작업대에 담을 항목이 없습니다.") }
            return
        }
        _state.update { it.copy(workbenchAdd = WorkbenchAddRequest(candidates, note, afterAdd)) }
    }

    fun dismissWorkbenchAdd() { _state.update { it.copy(workbenchAdd = null) } }

    /** Saves the pending references into [pocketId]. A failed save keeps the request open and every prior pocket unchanged. */
    fun confirmWorkbenchAdd(pocketId: String) {
        val request = _state.value.workbenchAdd ?: return
        viewModelScope.launch {
            val pocketName = DurableMaterialStore.pockets().firstOrNull { it.id == pocketId }?.name ?: DEFAULT_POCKET_NAME
            val added = withContext(Dispatchers.IO) { DurableMaterialStore.addItems(request.candidates.map { it.copy(pocketId = pocketId) }) }
            if (added == null) {
                _state.update { it.copy(message = materialAddMessage(null, "")) }
                return@launch
            }
            val identities = request.candidates.map { it.sourceIdentity }.toSet()
            val wanted = DurableMaterialStore.itemsIn(pocketId).filter { it.sourceIdentity in identities }.map { it.id }
            val skipped = request.candidates.size - added
            publishMaterials()
            _state.update {
                it.copy(
                    workbenchAdd = null,
                    workbenchPocketId = pocketId,
                    workbenchSelected = it.workbenchSelected + wanted,
                    message = (if (added > 0) "${added}개를 ‘$pocketName’에 담았습니다. 원본은 복사하지 않았습니다." else "이미 ‘$pocketName’에 담겨 있는 항목입니다.") +
                        (if (added > 0 && skipped > 0) " 이미 있던 ${skipped}개는 건너뛰었습니다." else "") +
                        (request.note?.let { n -> " $n" } ?: ""),
                )
            }
            request.afterAdd?.invoke(pocketId)
        }
    }

    /** 새 포켓을 만들어 바로 담기: the pocket is created first; if that cannot be saved nothing is added. */
    fun confirmWorkbenchAddToNewPocket(name: String) {
        if (_state.value.workbenchAdd == null) return
        val pocket = DurableMaterialStore.createPocket(name)
        if (pocket == null) {
            _state.update { it.copy(message = POCKET_SAVE_ERROR) }
            return
        }
        publishMaterials()
        confirmWorkbenchAdd(pocket.id)
    }

    private fun isDeviceMediaStoreUri(uri: Uri): Boolean =
        uri.authority == MediaStore.AUTHORITY && !uri.path.orEmpty().startsWith("/picker")

    /** One reference for a content:// / file:// source. Tries a persistable read grant only where the provider supports it. */
    private fun buildLocalReference(uri: Uri, meta: LocalMediaItem?, origin: String?): MaterialItem {
        val uriStr = uri.toString()
        val name = meta?.displayName ?: queryName(uri) ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "미디어"
        val mime = if (uri.scheme == "content") runCatching { application.contentResolver.getType(uri) }.getOrNull() else null
        val leaf = classifyWorkbenchLeaf(name, mime)
        val isVideo = meta?.isVideo ?: (leaf == WorkbenchLeafKind.VIDEO)
        val kind = if (meta != null || leaf != WorkbenchLeafKind.OTHER) MaterialEntryKind.MEDIA else MaterialEntryKind.FILE
        var grant: Boolean? = null
        if (uri.scheme == "content" && !isDeviceMediaStoreUri(uri)) {
            grant = runCatching { application.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); true }.getOrDefault(false)
        }
        // Metadata only: opening the original here could download a cloud / Google Photos item. Real reads happen at a run.
        val accessible = localReferenceReadable(
            MaterialItem(sourceIdentity = "local:$uriStr", name = name, sourceType = MaterialSourceType.LOCAL_LIBRARY, isVideo = isVideo, uriString = uriStr),
        )
        return MaterialItem(
            sourceIdentity = "local:$uriStr",
            name = name,
            sourceType = MaterialSourceType.LOCAL_LIBRARY,
            isVideo = isVideo,
            uriString = uriStr,
            durationMs = meta?.durationMs,
            state = if (accessible) MaterialPreparationState.READY else MaterialPreparationState.MISSING,
            errorMessage = if (accessible) null else LOCAL_MATERIAL_GRANT_ERROR,
            entryKind = kind,
            origin = origin ?: when {
                isPickerUri(uri) -> "GOOGLE_PHOTOS"
                uri.scheme == "file" -> "PHONE_HARD"
                else -> "MY_MEDIA"
            },
            grantPersisted = grant,
            mimeType = mime,
        )
    }

    private fun isPickerUri(uri: Uri): Boolean =
        uri.authority == MediaStore.AUTHORITY && uri.path.orEmpty().startsWith("/picker")

    /** 작업대에 담기 for content:// / file:// sources (내 미디어, Google 포토, 파일, 달력, 폰하드 선택 등). */
    internal fun requestWorkbenchAddLocal(
        uris: List<Uri>,
        items: List<LocalMediaItem> = emptyList(),
        origin: String? = null,
        afterAdd: ((String) -> Unit)? = null,
    ) {
        val distinct = uris.distinct()
        if (distinct.isEmpty()) return
        // NasFinder's own NAS download copies are temporary (cache): they are not references to the original.
        val stagedRoot = File(application.cacheDir, "media_basket").path
        val (temporary, referable) = distinct.partition { it.scheme == "file" && it.path.orEmpty().startsWith(stagedRoot) }
        viewModelScope.launch {
            val candidates = withContext(Dispatchers.IO) {
                referable.map { uri -> buildLocalReference(uri, items.firstOrNull { it.uri.toString() == uri.toString() }, origin) }
            }
            offerWorkbenchAdd(
                candidates,
                afterAdd,
                note = if (temporary.isNotEmpty()) "NAS에서 고른 항목 ${temporary.size}개는 임시 사본이라 담지 않았습니다. NAS 화면에서 ‘작업대에 담기’로 담아 주세요." else null,
            )
        }
    }

    /** 폴더(문서 트리) 참조: nothing under the folder is read or expanded until a run starts. */
    internal fun requestWorkbenchAddFolderTree(treeUri: Uri, afterAdd: ((String) -> Unit)? = null) {
        viewModelScope.launch {
            val candidate = withContext(Dispatchers.IO) {
                val grant = runCatching { application.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION); true }.getOrDefault(false)
                val name = runCatching {
                    val doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
                    application.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: "폴더"
                val readable = localReferenceReadable(MaterialItem(sourceIdentity = "tree:$treeUri", name = name, sourceType = MaterialSourceType.LOCAL_LIBRARY, isVideo = false, uriString = treeUri.toString(), entryKind = MaterialEntryKind.FOLDER))
                MaterialItem(
                    sourceIdentity = "tree:$treeUri",
                    name = name,
                    sourceType = MaterialSourceType.LOCAL_LIBRARY,
                    isVideo = false,
                    uriString = treeUri.toString(),
                    state = if (readable) MaterialPreparationState.READY else MaterialPreparationState.MISSING,
                    errorMessage = if (readable) null else LOCAL_MATERIAL_GRANT_ERROR,
                    entryKind = MaterialEntryKind.FOLDER,
                    origin = "FILES",
                    grantPersisted = grant,
                )
            }
            offerWorkbenchAdd(listOf(candidate), afterAdd)
        }
    }

    /** 폰하드 files and folders (Finder selection). Files stay where they are. */
    fun requestWorkbenchAddPhoneHard(files: List<InboxDisplayItem>, folderPaths: List<String> = emptyList()) =
        offerWorkbenchAdd(phoneHardReferences(files, folderPaths))

    private fun phoneHardReferences(files: List<InboxDisplayItem>, folderPaths: List<String> = emptyList()): List<MaterialItem> {
        val fileRefs = files.map { item ->
            val leaf = classifyWorkbenchLeaf(item.originalFilename, item.mimeType)
            val accessible = item.file.exists() && item.file.length() > 0L
            MaterialItem(
                sourceIdentity = "phonehard:${item.id}",
                name = item.originalFilename,
                sourceType = MaterialSourceType.PHONE_HARD,
                isVideo = leaf == WorkbenchLeafKind.VIDEO,
                uriString = Uri.fromFile(item.file).toString(),
                localFilePath = item.file.absolutePath,
                state = if (accessible) MaterialPreparationState.READY else MaterialPreparationState.MISSING,
                errorMessage = if (accessible) null else "폰하드 파일을 찾을 수 없습니다.",
                fileSize = item.byteCount,
                entryKind = if (leaf == WorkbenchLeafKind.OTHER) MaterialEntryKind.FILE else MaterialEntryKind.MEDIA,
                origin = "PHONE_HARD",
                mimeType = item.mimeType,
            )
        }
        val folderRefs = folderPaths.map { relative ->
            val dir = resolvePhoneHardDiskFile(relative)
            MaterialItem(
                sourceIdentity = "phonehard-folder:$relative",
                name = relative.trim('/').substringAfterLast('/').ifBlank { "폰하드" },
                sourceType = MaterialSourceType.PHONE_HARD,
                isVideo = false,
                uriString = Uri.fromFile(dir).toString(),
                remotePath = relative,
                state = if (dir.isDirectory) MaterialPreparationState.READY else MaterialPreparationState.MISSING,
                errorMessage = if (dir.isDirectory) null else "폰하드 폴더를 찾을 수 없습니다.",
                entryKind = MaterialEntryKind.FOLDER,
                origin = "PHONE_HARD",
            )
        }
        return fileRefs + folderRefs
    }

    /** NAS / cloud files and folders. Nothing is downloaded or listed here. */
    fun requestWorkbenchAddRemote(connection: RemoteConnection, remoteItems: List<RemoteFileItem>, origin: String = "NAS") =
        offerWorkbenchAdd(remoteReferences(connection, remoteItems, origin))

    private fun remoteReferences(connection: RemoteConnection, remoteItems: List<RemoteFileItem>, origin: String = "NAS"): List<MaterialItem> {
        val candidates = remoteItems.map { item ->
            val leaf = classifyWorkbenchLeaf(item.name, item.mimeType)
            MaterialItem(
                sourceIdentity = "remote:${connection.id}:${item.path}",
                name = item.name,
                sourceType = MaterialSourceType.REMOTE_FILE,
                isVideo = !item.isDirectory && leaf == WorkbenchLeafKind.VIDEO,
                uriString = item.path,
                connectionId = connection.id,
                remotePath = item.path,
                fileSize = item.size.takeIf { !item.isDirectory && it > 0L },
                captureTimeMillis = item.modifiedAt?.toEpochMilli() ?: 0L,
                entryKind = when {
                    item.isDirectory -> MaterialEntryKind.FOLDER
                    leaf == WorkbenchLeafKind.OTHER -> MaterialEntryKind.FILE
                    else -> MaterialEntryKind.MEDIA
                },
                origin = origin,
                mimeType = item.mimeType,
            )
        }
        return candidates
    }

    /** The 영상 편집 재료 찾기 panel: what was selected there can also go to a pocket (references only, web downloads excluded). */
    internal fun requestWorkbenchAddEditorCandidates(candidates: List<EditorAddCandidate>) {
        val remote = candidates.filterIsInstance<EditorAddCandidate.Remote>()
        val phoneHard = candidates.filterIsInstance<EditorAddCandidate.PhoneHard>()
        val local = candidates.filterIsInstance<EditorAddCandidate.LocalUri>()
        val skippedWeb = candidates.count { it is EditorAddCandidate.WebFile }
        viewModelScope.launch {
            val references = withContext(Dispatchers.IO) {
                remote.groupBy { it.connection.id }.values.flatMap { group -> remoteReferences(group.first().connection, group.map { it.item }) } +
                    phoneHardReferences(phoneHard.map { it.item }) +
                    local.map { buildLocalReference(it.uri, null, null) }
            }
            offerWorkbenchAdd(
                references,
                note = if (skippedWeb > 0) "웹 다운로드 ${skippedWeb}개는 임시 파일이라 담지 않았습니다." else null,
            )
        }
    }

    /** 즐겨찾기 files and folders: the favorite already records its connection and path, so this is metadata only. */
    fun requestWorkbenchAddFavorites(favorites: List<RemoteFavorite>, origin: String = "FAVORITE") {
        val candidates = favorites.map { favorite ->
            val leaf = classifyWorkbenchLeaf(favorite.name, favorite.mimeType)
            val kind = when {
                favorite.isDirectory -> MaterialEntryKind.FOLDER
                leaf == WorkbenchLeafKind.OTHER -> MaterialEntryKind.FILE
                else -> MaterialEntryKind.MEDIA
            }
            if (favorite.connectionId == "phonehard") {
                val file = resolvePhoneHardDiskFile(favorite.path)
                val available = if (favorite.isDirectory) file.isDirectory else file.isFile && file.length() > 0L
                MaterialItem(
                    sourceIdentity = if (favorite.isDirectory) "phonehard-folder:${favorite.path}" else "phonehard-path:${favorite.path}",
                    name = favorite.name,
                    sourceType = MaterialSourceType.PHONE_HARD,
                    isVideo = !favorite.isDirectory && leaf == WorkbenchLeafKind.VIDEO,
                    uriString = Uri.fromFile(file).toString(),
                    localFilePath = if (favorite.isDirectory) null else file.absolutePath,
                    remotePath = favorite.path,
                    state = if (available) MaterialPreparationState.READY else MaterialPreparationState.MISSING,
                    errorMessage = if (available) null else "폰하드 항목을 찾을 수 없습니다.",
                    fileSize = favorite.size,
                    entryKind = kind,
                    origin = origin,
                    mimeType = favorite.mimeType,
                )
            } else {
                MaterialItem(
                    sourceIdentity = "remote:${favorite.connectionId}:${favorite.path}",
                    name = favorite.name,
                    sourceType = MaterialSourceType.REMOTE_FILE,
                    isVideo = !favorite.isDirectory && leaf == WorkbenchLeafKind.VIDEO,
                    uriString = favorite.path,
                    connectionId = favorite.connectionId,
                    remotePath = favorite.path,
                    fileSize = favorite.size,
                    captureTimeMillis = favorite.modifiedAt?.toEpochMilli() ?: 0L,
                    entryKind = kind,
                    origin = origin,
                    mimeType = favorite.mimeType,
                )
            }
        }
        offerWorkbenchAdd(candidates)
    }

    /** PhoneHard files chosen by relative path (picker page): references to the files where they are. */
    fun requestWorkbenchAddPhoneHardPaths(relativePaths: List<String>) = requestWorkbenchAddFavorites(
        relativePaths.map { path ->
            val file = resolvePhoneHardDiskFile(path)
            RemoteFavorite(connectionId = "phonehard", path = path, name = path.trim('/').substringAfterLast('/'), isDirectory = file.isDirectory, size = file.takeIf { it.isFile }?.length())
        },
        origin = "PHONE_HARD",
    )

    // compatibility entry points (earlier call sites / deep links) ------------------------------------------------------

    internal fun addLocalMediaToMaterialBasket(uris: List<Uri>, items: List<LocalMediaItem> = emptyList()) = requestWorkbenchAddLocal(uris, items)
    fun addPhoneHardToMaterialBasket(phoneHardItems: List<InboxDisplayItem>) = requestWorkbenchAddPhoneHard(phoneHardItems)
    fun addRemoteFilesToMaterialBasket(connection: RemoteConnection, remoteItems: List<RemoteFileItem>) = requestWorkbenchAddRemote(connection, remoteItems)

    /** [added] null = nothing could be saved (the workbench is unchanged), never reported as success or duplicate. */
    private fun materialAddMessage(added: Int?, duplicateMessage: String): String = when {
        added == null -> DurableMaterialStore.loadProblem()?.takeIf { DurableMaterialStore.isPersistBlocked() }
            ?.let { "작업대에 담지 못했습니다. $it" } ?: "작업대에 담지 못했습니다. ${MATERIAL_SAVE_ERROR}"
        added > 0 -> "${added}개를 작업대에 담았습니다."
        else -> duplicateMessage
    }

    // item actions ----------------------------------------------------------------------------------------------

    /** Removes the REFERENCE only; the original and any copy it points at stay. */
    fun removeMaterialItem(id: String) {
        val removed = !DurableMaterialStore.contains(id) || DurableMaterialStore.removeItem(id)
        publishMaterials()
        if (!removed) _state.update { it.copy(message = "항목을 빼지 못했습니다. ${MATERIAL_SAVE_ERROR}") }
    }

    fun reorderMaterialItem(from: Int, to: Int) {
        val moved = DurableMaterialStore.reorder(from, to)
        publishMaterials()
        if (!moved) _state.update { it.copy(message = "순서를 바꾸지 못했습니다. ${MATERIAL_SAVE_ERROR}") }
    }

    /** Moves an item inside ITS pocket; other pockets keep their own order. */
    fun moveWorkbenchItem(pocketId: String, from: Int, to: Int) {
        val moved = DurableMaterialStore.reorderInPocket(pocketId, from, to)
        publishMaterials()
        if (!moved) _state.update { it.copy(message = "순서를 바꾸지 못했습니다. ${MATERIAL_SAVE_ERROR}") }
    }

    /** 다시 확인: re-reads the reference state (no copy, no download). NAS items are simply set to be retried at the next run. */
    fun recheckMaterial(item: MaterialItem) {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val current = DurableMaterialStore.getItems().firstOrNull { it.id == item.id } ?: return@withContext true
                if (current.sourceType == MaterialSourceType.REMOTE_FILE) {
                    current.state != MaterialPreparationState.MISSING ||
                        DurableMaterialStore.updateState(current.id, MaterialPreparationState.READY, errorMessage = null)
                } else {
                    val verified = verifyMaterialAccess(current.copy(state = MaterialPreparationState.READY, errorMessage = null))
                    val target = verified ?: current.copy(state = MaterialPreparationState.READY, errorMessage = null)
                    DurableMaterialStore.updateState(current.id, target.state, errorMessage = target.errorMessage)
                }
            }
            publishMaterials()
            if (!saved) _state.update { it.copy(message = MATERIAL_SAVE_ERROR) }
        }
    }

    fun setMaterialExcluded(id: String, excluded: Boolean) {
        val targetState = if (excluded) MaterialPreparationState.EXCLUDED else MaterialPreparationState.READY
        val saved = DurableMaterialStore.updateState(id, targetState)
        publishMaterials()
        if (!saved) _state.update { it.copy(message = "항목 상태를 바꾸지 못했습니다. ${MATERIAL_SAVE_ERROR}") }
        else refreshMaterials()
    }

    // 다시 연결 --------------------------------------------------------------------------------------------------

    /**
     * The user picked a replacement for a reference that is gone / expired. Unless it is the very same identity the
     * change waits for an explicit confirmation; only this reference changes, never another pocket's.
     */
    internal fun reconnectLocalReference(item: MaterialItem, newUri: Uri) {
        viewModelScope.launch {
            val replacement = withContext(Dispatchers.IO) { buildLocalReference(newUri, null, item.origin) }
            if (replacement.sourceIdentity == item.sourceIdentity) {
                val saved = withContext(Dispatchers.IO) { DurableMaterialStore.replaceReference(item.id, replacement.copy(pocketId = item.pocketId)) }
                publishMaterials()
                _state.update { it.copy(message = if (saved) "‘${item.displayLabel}’ 연결을 다시 확인했습니다." else MATERIAL_SAVE_ERROR) }
            } else {
                _state.update { it.copy(workbenchReconnect = WorkbenchReconnect(item.id, item.displayLabel, replacement)) }
            }
        }
    }

    internal fun reconnectFolderReference(item: MaterialItem, treeUri: Uri) {
        viewModelScope.launch {
            val replacement = withContext(Dispatchers.IO) {
                runCatching { application.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                item.copy(sourceIdentity = "tree:$treeUri", uriString = treeUri.toString(), grantPersisted = true)
            }
            if (replacement.sourceIdentity == item.sourceIdentity) {
                val saved = withContext(Dispatchers.IO) { DurableMaterialStore.replaceReference(item.id, replacement) }
                publishMaterials()
                _state.update { it.copy(message = if (saved) "‘${item.displayLabel}’ 폴더 연결을 다시 확인했습니다." else MATERIAL_SAVE_ERROR) }
            } else {
                _state.update { it.copy(workbenchReconnect = WorkbenchReconnect(item.id, item.displayLabel, replacement)) }
            }
        }
    }

    fun confirmWorkbenchReconnect() {
        val pending = _state.value.workbenchReconnect ?: return
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { DurableMaterialStore.replaceReference(pending.itemId, pending.replacement) }
            publishMaterials()
            _state.update {
                it.copy(
                    workbenchReconnect = null,
                    message = if (saved) "‘${pending.oldName}’ 참조를 선택한 항목으로 바꿨습니다. 원본 파일은 건드리지 않았습니다."
                    else "참조를 바꾸지 못했습니다. 같은 포켓에 이미 같은 항목이 있거나 저장에 실패했습니다. 기존 참조는 그대로입니다.",
                )
            }
        }
    }

    fun dismissWorkbenchReconnect() { _state.update { it.copy(workbenchReconnect = null) } }

    /** NAS reference: the connection is missing -> go to the Finder to restore it; otherwise open the folder that held the file. */
    fun reconnectRemoteReference(item: MaterialItem) {
        val connection = _state.value.connections.firstOrNull { it.id == item.connectionId }
        if (connection == null) {
            selectMainDestination(MainNavigationDestination.FINDER)
            _state.update { it.copy(message = "‘${item.displayLabel}’의 NAS 연결을 찾을 수 없습니다. 연결을 다시 추가하거나 복구한 뒤 다시 시도해 주세요. 작업대 항목은 그대로 남아 있습니다.") }
            return
        }
        val parent = item.remotePath.orEmpty().trimEnd('/').substringBeforeLast('/', connection.normalizedRootPath).ifBlank { connection.normalizedRootPath }
        openConnection(connection, parent)
        _state.update { it.copy(message = "항목이 옮겨졌거나 지워졌는지 확인한 뒤, 다시 담거나 작업대에서 항목을 직접 빼 주세요.") }
    }

    // explicit runs -------------------------------------------------------------------------------------------------

    private fun runProgress(name: String, found: Int) {
        _state.update { cur -> cur.workbenchRun?.let { run -> cur.copy(workbenchRun = run.copy(name = name, current = found)) } ?: cur }
    }

    /**
     * Expands the CURRENT content of the selected references at run start: folders are listed now (recursively, in name
     * order, cancellable), media leaves are kept, everything else is reported. Nothing is downloaded or copied here.
     */
    private suspend fun planWorkbenchRun(items: List<MaterialItem>, onScan: (String, Int) -> Unit): WorkbenchRunPlan {
        val leaves = mutableListOf<WorkbenchRunLeaf>()
        val issues = mutableListOf<WorkbenchRunIssue>()
        val notes = mutableListOf<String>()
        for (item in items) {
            kotlin.coroutines.coroutineContext.ensureActive()
            when {
                item.state == MaterialPreparationState.EXCLUDED -> issues += WorkbenchRunIssue(item.displayLabel, "제외된 항목이라 쓰지 않습니다.")
                item.entryKind == MaterialEntryKind.FILE -> issues += WorkbenchRunIssue(item.displayLabel, "미디어가 아닌 파일이라 지원하지 않습니다.")
                item.entryKind == MaterialEntryKind.FOLDER -> expandWorkbenchFolder(item, leaves, issues, notes, onScan)
                else -> leaves += WorkbenchRunLeaf(item, stored = true)
            }
        }
        return WorkbenchRunPlan(leaves, issues, notes)
    }

    private suspend fun expandWorkbenchFolder(
        folder: MaterialItem,
        leaves: MutableList<WorkbenchRunLeaf>,
        issues: MutableList<WorkbenchRunIssue>,
        notes: MutableList<String>,
        onScan: (String, Int) -> Unit,
    ) {
        val before = leaves.size
        var skippedOther = 0
        var unsafe = 0
        val unsafeNames = mutableListOf<String>()
        fun markUnsafe(name: String) { unsafe++; if (unsafeNames.size < 3) unsafeNames += name }
        fun leafOf(parent: MaterialItem, name: String, mime: String?, build: (Boolean) -> MaterialItem) {
            val kind = classifyWorkbenchLeaf(name, mime)
            if (kind == WorkbenchLeafKind.OTHER) { skippedOther++; return }
            leaves += WorkbenchRunLeaf(build(kind == WorkbenchLeafKind.VIDEO), stored = false, parentName = parent.displayLabel, rootId = parent.id)
            onScan(parent.displayLabel, leaves.size - before)
        }
        try {
            when (folder.sourceType) {
                // SAF: children come only from buildChildDocumentsUriUsingTree(grantedTree, parentId), so the framework keeps every
                // read inside the granted tree. Document ids are tracked so a provider that links folders can not loop.
                MaterialSourceType.LOCAL_LIBRARY -> withContext(Dispatchers.IO) {
                    val tree = Uri.parse(folder.uriString)
                    val pending = ArrayDeque<Pair<String, Int>>().apply { add(DocumentsContract.getTreeDocumentId(tree) to 0) }
                    val seenDirs = mutableSetOf<String>()
                    val seenDocs = mutableSetOf<String>()
                    while (pending.isNotEmpty()) {
                        ensureActive()
                        val (parentId, depth) = pending.removeFirst()
                        if (!seenDirs.add(parentId)) continue
                        if (depth > WORKBENCH_MAX_FOLDER_DEPTH) { markUnsafe(parentId.substringAfterLast('/')); continue }
                        val children = mutableListOf<Array<String?>>()
                        application.contentResolver.query(
                            DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId),
                            arrayOf(
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                DocumentsContract.Document.COLUMN_MIME_TYPE,
                                DocumentsContract.Document.COLUMN_SIZE,
                            ),
                            null, null, null,
                        )?.use { c ->
                            while (c.moveToNext()) children += arrayOf(c.getString(0), c.getString(1), c.getString(2), if (c.isNull(3)) null else c.getLong(3).toString())
                        } ?: throw java.io.IOException("폴더를 읽을 수 없습니다.")
                        val ordered = children.sortedBy { it[1].orEmpty().lowercase() }
                        // Files of this folder first (name order), then its sub-folders, so the result order is predictable.
                        ordered.filter { it[2] != DocumentsContract.Document.MIME_TYPE_DIR }.forEach { child ->
                            if (!seenDocs.add(child[0].orEmpty())) return@forEach
                            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, child[0].orEmpty())
                            leafOf(folder, child[1].orEmpty(), child[2]) { video ->
                                MaterialItem(
                                    sourceIdentity = "tree-doc:$doc", name = child[1].orEmpty(), sourceType = MaterialSourceType.LOCAL_LIBRARY,
                                    isVideo = video, uriString = doc.toString(), fileSize = child[3]?.toLongOrNull(), pocketId = folder.pocketId,
                                    origin = folder.origin, mimeType = child[2],
                                )
                            }
                        }
                        ordered.filter { it[2] == DocumentsContract.Document.MIME_TYPE_DIR }.reversed().forEach { pending.addFirst(it[0].orEmpty() to depth + 1) }
                    }
                }
                // PhoneHard: the owned-root resolution (canonical path inside the PhoneHard root) is applied to the referenced
                // folder and to every entry; symbolic links and anything whose canonical path leaves the folder are not followed.
                MaterialSourceType.PHONE_HARD -> withContext(Dispatchers.IO) {
                    val canonicalRoot = resolvePhoneHardDiskFile(folder.remotePath.orEmpty()).canonicalFile
                    val rootPrefix = canonicalRoot.path + File.separator
                    fun inside(file: File): Boolean = file.canonicalFile.let { it == canonicalRoot || it.path.startsWith(rootPrefix) }
                    if (!canonicalRoot.isDirectory) throw java.io.IOException("폰하드 폴더를 찾을 수 없습니다.")
                    val pending = ArrayDeque<Pair<String, Int>>().apply { add(folder.remotePath.orEmpty() to 0) }
                    val seenDirs = mutableSetOf<String>()
                    while (pending.isNotEmpty()) {
                        ensureActive()
                        val (relative, depth) = pending.removeFirst()
                        val dir = resolvePhoneHardDiskFile(relative)
                        if (!inside(dir) || !seenDirs.add(dir.canonicalPath)) continue
                        if (depth > WORKBENCH_MAX_FOLDER_DEPTH) { markUnsafe(dir.name); continue }
                        val entries = inboxStore.listPhoneHardFolder(relative.ifBlank { null }).sortedBy { it.name.lowercase() }
                        val safe = entries.filter { entry ->
                            val ok = !java.nio.file.Files.isSymbolicLink(entry.file.toPath()) && inside(entry.file)
                            if (!ok) markUnsafe(entry.name)
                            ok
                        }
                        safe.filter { it.kind != com.armsone.nasfinder.data.PhoneHardEntryKind.DIRECTORY }.forEach { entry ->
                            leafOf(folder, entry.name, entry.mimeType) { video ->
                                MaterialItem(
                                    sourceIdentity = "phonehard-path:${entry.relativePath}", name = entry.name, sourceType = MaterialSourceType.PHONE_HARD,
                                    isVideo = video, uriString = Uri.fromFile(entry.file).toString(), localFilePath = entry.file.absolutePath,
                                    fileSize = entry.byteCount, pocketId = folder.pocketId, origin = folder.origin, mimeType = entry.mimeType,
                                )
                            }
                        }
                        safe.filter { it.kind == com.armsone.nasfinder.data.PhoneHardEntryKind.DIRECTORY }.reversed().forEach { pending.addFirst(it.relativePath to depth + 1) }
                    }
                }
                // NAS / cloud: every listed path is normalized (dot / repeated separators) and must stay inside the referenced
                // folder; a path that leaves it is skipped and reported. The backend gives no symlink flag, so loops are bounded
                // by the visited set and the depth limit and reported instead of trusted.
                MaterialSourceType.REMOTE_FILE -> {
                    val connection = _state.value.connections.firstOrNull { it.id == folder.connectionId }
                        ?: throw IllegalStateException("NAS 연결을 찾을 수 없습니다.")
                    val root = normalizeWorkbenchRemotePath(folder.remotePath.orEmpty())
                        ?: throw IllegalStateException("폴더 경로가 올바르지 않습니다.")
                    withDedicatedRemoteService(connection) { svc ->
                        val pending = ArrayDeque<Triple<String, String, Int>>().apply { add(Triple(folder.remotePath.orEmpty(), root, 0)) }
                        val seen = mutableSetOf<String>()
                        while (pending.isNotEmpty()) {
                            kotlin.coroutines.coroutineContext.ensureActive()
                            val (listPath, normalized, depth) = pending.removeFirst()
                            if (!seen.add(normalized)) continue
                            if (depth > WORKBENCH_MAX_FOLDER_DEPTH) { markUnsafe(normalized.substringAfterLast('/')); continue }
                            val entries = svc.list(listPath)
                                .filter { it.name.isNotBlank() && it.name != "." && it.name != ".." && !it.name.startsWith('.') }
                                .sortedBy { it.name.lowercase() }
                            val safe = entries.mapNotNull { entry ->
                                val childNormalized = normalizeWorkbenchRemotePath(entry.path)
                                if (childNormalized == null || !isInsideWorkbenchRoot(root, childNormalized)) { markUnsafe(entry.name); null }
                                else if (childNormalized == normalized) null
                                else entry to childNormalized
                            }
                            safe.filter { !it.first.isDirectory }.forEach { (entry, _) ->
                                leafOf(folder, entry.name, entry.mimeType) { video ->
                                    MaterialItem(
                                        sourceIdentity = "remote:${connection.id}:${entry.path}", name = entry.name, sourceType = MaterialSourceType.REMOTE_FILE,
                                        isVideo = video, uriString = entry.path, connectionId = connection.id, remotePath = entry.path,
                                        fileSize = entry.size.takeIf { it > 0L }, captureTimeMillis = entry.modifiedAt?.toEpochMilli() ?: 0L,
                                        pocketId = folder.pocketId, origin = folder.origin, mimeType = entry.mimeType,
                                    )
                                }
                            }
                            safe.filter { it.first.isDirectory }.reversed().forEach { (entry, childNormalized) -> pending.addFirst(Triple(entry.path, childNormalized, depth + 1)) }
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val reason = error.message ?: "폴더를 읽지 못했습니다."
            // The reference stays; it is marked so the user sees 다시 연결.
            withContext(Dispatchers.IO) { DurableMaterialStore.updateState(folder.id, MaterialPreparationState.MISSING, errorMessage = reason) }
            issues += WorkbenchRunIssue(folder.displayLabel, "폴더를 읽지 못했습니다: $reason")
            return
        }
        val found = leaves.size - before
        if (found == 0) issues += WorkbenchRunIssue(folder.displayLabel, "폴더 안에 지금 사용할 수 있는 미디어가 없습니다.")
        else notes += "폴더 ‘${folder.displayLabel}’: 미디어 ${found}개"
        // Skipped content is an explicit decision point (listed in the confirmation), not a completion note.
        if (skippedOther > 0) issues += WorkbenchRunIssue(folder.displayLabel, "미디어가 아닌 파일 ${skippedOther}개는 대상이 아니라 건너뜁니다.")
        if (unsafe > 0) issues += WorkbenchRunIssue(folder.displayLabel, "안전하지 않거나 범위를 벗어난 경로 ${unsafe}개(${unsafeNames.joinToString()})는 따라가지 않았습니다. 심볼릭 링크·폴더 밖 경로·너무 깊은 하위 폴더입니다.")
        if (folder.state == MaterialPreparationState.MISSING && !folder.errorMessage.isNullOrBlank()) {
            withContext(Dispatchers.IO) { DurableMaterialStore.updateState(folder.id, MaterialPreparationState.READY, errorMessage = null) }
        }
    }

    private fun planSummary(plan: WorkbenchRunPlan): String =
        (plan.issues.map { "${it.name}: ${it.reason}" } + plan.notes).joinToString(" / ")

    /** Reads the whole plan, then asks once when something is unsupported; never silently drops an item. */
    fun startWorkbenchVideo(items: List<MaterialItem>, inheritedPrivate: Boolean = false) {
        if (_state.value.workbenchRun != null) return
        val snapshot = items.toList()
        if (snapshot.isEmpty()) {
            _state.update { it.copy(message = "영상 제작에 사용할 항목을 먼저 선택해 주세요.") }
            return
        }
        // 첫 번째 중단점 전에 출처를 한 번 고정한다: 지금 모드 또는 입력(항목·포켓·연결·경로) 중 하나라도 비공개이면 비공개.
        val jobPrivate = inheritedPrivate || PrivacyMode.capturedOrigin(snapshot.any(DurableMaterialStore::isEffectivelyPrivate))
        trailerRetry = {
            // 다시 시도할 때는 숨겨진 항목도 조용히 빠지지 않도록 전체 항목에서 찾고, 처음 고정한 출처를 이어받는다.
            val fresh = snapshot.mapNotNull { old -> DurableMaterialStore.getAllItems().firstOrNull { it.id == old.id } }
            startWorkbenchVideo(fresh, inheritedPrivate = jobPrivate)
        }
        workbenchRunJob = viewModelScope.launch {
            _state.update { it.copy(workbenchRun = WorkbenchRunState(WorkbenchRunKind.VIDEO, "항목을 확인하는 중…", originPrivate = jobPrivate)) }
            val plan = try {
                planWorkbenchRun(snapshot) { name, found -> runProgress(name, found) }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(workbenchRun = null, message = "취소했습니다. 작업대 항목은 그대로 남아 있습니다.") }
                throw cancelled
            }
            if (plan.issues.isNotEmpty()) {
                _state.update { cur -> cur.workbenchRun?.let { run -> cur.copy(workbenchRun = run.copy(phase = "사용할 수 없는 항목이 있습니다.", awaiting = plan)) } ?: cur }
            } else {
                proceedWorkbenchVideo(plan, jobPrivate)
            }
        }
    }

    /** 미디어 전송 from the 작업대: the pocket and its selection are kept; the transfer screen still waits for the QR and an explicit 보내기. */
    fun openWorkbenchSend(pocketId: String) {
        _state.update { it.copy(workbenchPocketId = pocketId, workbenchSendRequested = true) }
        show(Screen.PhotoTransfer)
    }

    fun consumeWorkbenchSendRequest() { _state.update { it.copy(workbenchSendRequested = false) } }

    fun confirmWorkbenchRun() {
        val run = _state.value.workbenchRun ?: return
        // Fetch failures: the user chose to continue with the inputs that were read.
        run.partial?.let { it.decision.complete(true); return }
        val plan = run.awaiting ?: return
        if (run.kind == WorkbenchRunKind.VIDEO) proceedWorkbenchVideo(plan, run.originPrivate)
        else _state.update { it.copy(workbenchRun = null) }
    }

    /** Cancels scanning or declines the unsupported-items prompt. References and selection stay as they were. */
    fun cancelWorkbenchRun() {
        // Fetch failures: declining ends the run (the waiting run removes only its own temporary workspace).
        _state.value.workbenchRun?.partial?.let { it.decision.complete(false); return }
        workbenchRunJob?.cancel()
        workbenchRunJob = null
        _state.update { it.copy(workbenchRun = null, message = "취소했습니다. 작업대 항목은 그대로 남아 있습니다.") }
    }

    /** Suspends the run until the user decides whether to continue with fewer inputs. Never continues on its own. */
    private suspend fun askWorkbenchPartial(kind: WorkbenchRunKind, failures: List<WorkbenchRunIssue>, available: Int, originPrivate: Boolean): Boolean {
        val decision = kotlinx.coroutines.CompletableDeferred<Boolean>()
        _state.update { it.copy(workbenchRun = WorkbenchRunState(kind, "일부 항목을 읽지 못했습니다.", partial = WorkbenchPartial(failures, available, decision), originPrivate = originPrivate)) }
        return try {
            decision.await()
        } finally {
            _state.update { it.copy(workbenchRun = null) }
        }
    }

    private fun workbenchCaptureTime(item: MaterialItem): Long {
        if (item.captureTimeMillis > 0L) return item.captureTimeMillis
        return when (item.sourceType) {
            MaterialSourceType.LOCAL_LIBRARY -> localCaptureTime(Uri.parse(item.uriString))
            MaterialSourceType.PHONE_HARD -> item.localFilePath?.let { File(it).lastModified() } ?: 0L
            MaterialSourceType.REMOTE_FILE -> 0L
        }
    }

    private fun proceedWorkbenchVideo(plan: WorkbenchRunPlan, jobPrivate: Boolean) {
        _state.update { it.copy(workbenchRun = null) }
        if (plan.leaves.isEmpty()) {
            _state.update { it.copy(message = "영상 제작에 쓸 수 있는 미디어가 없습니다. " + planSummary(plan)) }
            return
        }
        val leaves = plan.leaves
        val services = mutableMapOf<String, RemoteFileService>()
        val failures = mutableListOf<WorkbenchRunIssue>()
        var stateSaveFailed = false
        val motionUris: MutableList<Uri?> = leaves.map { leaf ->
            leaf.item.takeIf { it.sourceType == MaterialSourceType.LOCAL_LIBRARY && !it.isVideo }?.let { Uri.parse(it.uriString) }
        }.toMutableList()
        prepareTrailer(
            names = leaves.map { it.item.displayLabel },
            captureTimes = leaves.map { workbenchCaptureTime(it.item) },
            motionSourceUris = motionUris,
            originKeys = leaves.map { leaf ->
                val item = leaf.item
                when (item.sourceType) {
                    MaterialSourceType.LOCAL_LIBRARY -> editorOriginLocal(item.uriString)
                    MaterialSourceType.REMOTE_FILE -> editorOriginRemote(item.connectionId.orEmpty(), item.remotePath.orEmpty())
                    MaterialSourceType.PHONE_HARD -> item.sourceIdentity.takeIf { it.startsWith("phonehard:") }?.let { editorOriginPhoneHard(it.removePrefix("phonehard:")) }
                }
            },
            // Fetch / permission errors never turn into a quietly smaller project: the user decides first.
            beforeFinish = { staged -> failures.isEmpty() || askWorkbenchPartial(WorkbenchRunKind.VIDEO, failures.toList(), staged, jobPrivate) },
            jobPrivate = jobPrivate,
        ) { index, destination ->
            val leaf = leaves[index]
            try {
                stageWorkbenchLeaf(leaf.item, destination, services)
                if (leaf.stored && leaf.item.state != MaterialPreparationState.READY) {
                    if (!withContext(Dispatchers.IO) { DurableMaterialStore.updateState(leaf.item.id, MaterialPreparationState.READY, errorMessage = null) }) stateSaveFailed = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val reason = error.message ?: "읽을 수 없습니다."
                runCatching { destination.delete() }
                motionUris[index] = null
                failures += WorkbenchRunIssue(leaf.item.displayLabel, reason)
                if (leaf.stored && !withContext(Dispatchers.IO) { DurableMaterialStore.updateState(leaf.item.id, MaterialPreparationState.MISSING, errorMessage = reason) }) stateSaveFailed = true
            }
        }
        trailerJob?.invokeOnCompletion {
            services.values.forEach { runCatching { it.close() } }
            viewModelScope.launch {
                publishMaterials()
                val notice = (failures.map { "${it.name}: ${it.reason}" } + plan.notes + listOfNotNull(MATERIAL_SAVE_ERROR.takeIf { stateSaveFailed })).joinToString(" / ")
                if (notice.isNotBlank()) _state.update { cur -> cur.copy(message = (if (failures.isNotEmpty()) "읽지 못한 항목이 있습니다. 작업대에는 그대로 남아 ‘다시 연결’할 수 있습니다. " else "") + notice) }
            }
        }
    }

    /** Copies / downloads ONE reference into [destination] inside the run's own project folder. Never touches the original. */
    private suspend fun stageWorkbenchLeaf(item: MaterialItem, destination: File, services: MutableMap<String, RemoteFileService>) {
        val legacyCopy = item.localFilePath?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
        when {
            legacyCopy != null -> withContext(Dispatchers.IO) { legacyCopy.copyTo(destination, overwrite = true) }
            item.sourceType == MaterialSourceType.LOCAL_LIBRARY -> kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
                application.contentResolver.openInputStream(Uri.parse(item.uriString))?.use { input ->
                    destination.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw CancellationException()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: error("원본을 열 수 없습니다. ‘다시 연결’이 필요할 수 있습니다.")
            }
            item.sourceType == MaterialSourceType.PHONE_HARD -> error("폰하드 파일을 찾을 수 없습니다.")
            else -> {
                val connection = _state.value.connections.firstOrNull { it.id == item.connectionId }
                    ?: error("NAS 연결을 찾을 수 없습니다 (${item.connectionId.orEmpty()}).")
                val service = services.getOrPut(connection.id) { openDedicatedRemoteService(connection) }
                service.download(
                    RemoteFileItem(id = item.id, name = item.name, path = item.remotePath.orEmpty(), isDirectory = false, size = item.fileSize ?: 0L),
                    destination,
                )
                val expected = item.fileSize ?: 0L
                check(destination.length() > 0 && (expected <= 0 || destination.length() == expected)) { "파일을 끝까지 받지 못했습니다." }
            }
        }
        check(destination.length() > 0) { "파일을 끝까지 읽지 못했습니다." }
    }

    /** Plans (folder expansion, unsupported report) for a 전송 run; the caller shows [WorkbenchRunPlan.issues] before sending. */
    internal suspend fun planWorkbenchSend(items: List<MaterialItem>, onScan: (String, Int) -> Unit): WorkbenchRunPlan = planWorkbenchRun(items, onScan)

    internal suspend fun materializeWorkbenchForSend(
        leaves: List<WorkbenchRunLeaf>,
        onProgress: (index: Int, total: Int, name: String) -> Unit,
    ): WorkbenchSendMaterialization {
        val runDirectory = File(application.cacheDir, "workbench_send/${UUID.randomUUID()}")
        val services = mutableMapOf<String, RemoteFileService>()
        val entries = mutableListOf<WorkbenchSendEntry>()
        val failures = mutableListOf<WorkbenchRunIssue>()
        try {
            withContext(Dispatchers.IO) { runDirectory.mkdirs() }
            leaves.forEachIndexed { index, leaf ->
                kotlin.coroutines.coroutineContext.ensureActive()
                val item = leaf.item
                onProgress(index, leaves.size, item.displayLabel)
                try {
                    val legacyCopy = item.localFilePath?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
                    val source: Uri = when {
                        legacyCopy != null -> Uri.fromFile(legacyCopy)
                        item.sourceType == MaterialSourceType.LOCAL_LIBRARY -> Uri.parse(item.uriString)
                        item.sourceType == MaterialSourceType.PHONE_HARD -> error("폰하드 파일을 찾을 수 없습니다.")
                        else -> {
                            val safeName = item.name.substringAfterLast('/').replace(Regex("[\\\\:*?\"<>|]"), "_").ifBlank { "media" }
                            val target = File(File(runDirectory, index.toString()).also { it.mkdirs() }, safeName)
                            stageWorkbenchLeaf(item, target, services)
                            Uri.fromFile(target)
                        }
                    }
                    val media = withContext(Dispatchers.IO) { describeTransferMedia(application, source, item.name, item.mimeType) }
                        ?: error("읽을 수 없거나 미디어가 아닙니다.")
                    entries += WorkbenchSendEntry(leaf, media)
                    if (leaf.stored && item.state == MaterialPreparationState.MISSING) {
                        withContext(Dispatchers.IO) { DurableMaterialStore.updateState(item.id, MaterialPreparationState.READY, errorMessage = null) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val reason = error.message ?: "읽을 수 없습니다."
                    failures += WorkbenchRunIssue(item.displayLabel, reason)
                    if (leaf.stored) withContext(Dispatchers.IO) { DurableMaterialStore.updateState(item.id, MaterialPreparationState.MISSING, errorMessage = reason) }
                }
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { deleteOwnedSendRunDirectory(runDirectory) }
            throw error
        } finally {
            services.values.forEach { runCatching { it.close() } }
            withContext(NonCancellable) { publishMaterials() }
        }
        return WorkbenchSendMaterialization(entries, failures, runDirectory)
    }

    /** Removes exactly the temporary workspace of one finished / failed / cancelled 전송 run. */
    internal fun finishWorkbenchSend(runDirectory: File?) {
        if (runDirectory == null) return
        viewModelScope.launch(Dispatchers.IO + NonCancellable) { deleteOwnedSendRunDirectory(runDirectory) }
    }

    /**
     * Recursive delete that only ever touches one run workspace: an immediate child of cacheDir/workbench_send whose name is a
     * run UUID, resolved canonically and not a symbolic link. Anything else is left alone.
     */
    private fun deleteOwnedSendRunDirectory(runDirectory: File) {
        runCatching {
            val root = File(application.cacheDir, "workbench_send").canonicalFile
            val target = runDirectory.canonicalFile
            val ownedName = runCatching { UUID.fromString(target.name) }.isSuccess
            if (target.parentFile == root && ownedName && !java.nio.file.Files.isSymbolicLink(runDirectory.toPath())) target.deleteRecursively()
        }
    }

    private fun getMaterialsDirectory(): File {
        return File(application.filesDir, "materials_cache").also { it.mkdirs() }
    }

    /** [file] when it is a regular file directly inside this app's materials copy folder (symlinks resolved), else null. */
    private fun ownedMaterialCopy(path: String?, stagingDir: File): File? {
        val file = path?.takeIf { it.isNotBlank() }?.let(::File) ?: return null
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        val root = runCatching { stagingDir.canonicalFile }.getOrNull() ?: return null
        return canonical.takeIf { it.parentFile == root && it.isFile && it.length() > 0L }
    }

    /** True when [file] is a non-empty readable photo (decodable size) or video (video track with a real length). */
    private fun isReadableMediaFile(file: File, isVideo: Boolean): Boolean {
        if (!file.isFile || !file.canRead() || file.length() <= 0L) return false
        return if (isVideo) {
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(file.absolutePath)
                val durationMs = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: 0.0
                reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes" && durationMs.isFinite() && durationMs > 0.0
            } catch (_: Exception) {
                false
            } finally {
                runCatching { reader.release() }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { BitmapFactory.decodeFile(file.absolutePath, bounds) }
            if (bounds.outWidth > 0 && bounds.outHeight > 0) true
            else runCatching { BitmapFactory.decodeFile(file.absolutePath)?.let { it.recycle(); true } ?: false }.getOrDefault(false)
        }
    }

    /**
     * Prepares materials for use. With [registeredInBasket] every item is a [DurableMaterialStore] record and is
     * reported READY only when that state was actually saved. Without it the items are fresh in-memory editor
     * candidates that are not (and must not be) put into the basket; their result is returned without the store.
     */
    suspend fun prepareMaterialItems(
        itemsToPrepare: List<MaterialItem>,
        onProgress: ((completed: Int, total: Int, currentName: String) -> Unit)? = null,
        onItemProgress: ((itemId: String, fraction: Float?) -> Unit)? = null,
        registeredInBasket: Boolean = true,
    ): List<MaterialItem> = withContext(Dispatchers.IO) {
        val stagingDir = getMaterialsDirectory()
        val total = itemsToPrepare.size
        // Records [prepared] for a basket item. A READY that could not be saved is not success: the copy this
        // operation created ([ownedNewFile]) is removed again and the item is reported as a retryable failure.
        fun commit(item: MaterialItem, prepared: MaterialItem, ownedNewFile: File? = null): MaterialItem {
            if (!registeredInBasket) return prepared
            if (DurableMaterialStore.updateState(item.id, prepared.state, prepared.downloadProgress, prepared.localFilePath, prepared.errorMessage)) return prepared
            ownedNewFile?.delete()
            val reason = when {
                DurableMaterialStore.isPersistBlocked() -> "작업대 목록 파일 문제로 저장이 멈춰 있어 준비 결과를 기록하지 못했습니다. 앱을 다시 열어 주세요."
                !DurableMaterialStore.contains(item.id) -> "작업대에서 빠진 항목이라 준비 결과를 기록하지 못했습니다."
                else -> "준비 결과를 저장하지 못했습니다. 저장 공간을 확인한 뒤 다시 시도해 주세요."
            }
            return if (prepared.state == MaterialPreparationState.READY) {
                item.copy(state = MaterialPreparationState.MISSING, downloadProgress = null, errorMessage = reason)
            } else {
                prepared.copy(errorMessage = listOfNotNull(prepared.errorMessage, reason).joinToString(" "))
            }
        }
        itemsToPrepare.mapIndexed { index, item ->
            ensureActive()
            onProgress?.invoke(index, total, item.name)

            when (item.sourceType) {
                MaterialSourceType.LOCAL_LIBRARY -> {
                    val existingCopy = ownedMaterialCopy(item.localFilePath, stagingDir)?.takeIf { isReadableMediaFile(it, item.isVideo) }
                    if (existingCopy != null) {
                        // A valid app-owned copy stays usable even when the original is no longer reachable.
                        commit(item, item.copy(state = MaterialPreparationState.READY, downloadProgress = null, errorMessage = null))
                    } else {
                        // Copy into a partial owned by THIS operation; it becomes the item's copy only after validation.
                        val ext = item.name.substringAfterLast('.', "").takeIf { it.isNotBlank() && it.length <= 5 && it.all(Char::isLetterOrDigit) }
                            ?: if (item.isVideo) "mp4" else "jpg"
                        val finalFile = File(stagingDir, "mat_${item.id}.${UUID.randomUUID()}.$ext")
                        val partial = File(stagingDir, "${finalFile.name}.part")
                        var failure: String? = null
                        try {
                            val opened = runCatching { application.contentResolver.openInputStream(Uri.parse(item.uriString)) }.getOrNull()
                            if (opened == null) {
                                failure = LOCAL_MATERIAL_ACCESS_ERROR
                            } else {
                                opened.use { input ->
                                    partial.outputStream().use { output ->
                                        val buffer = ByteArray(64 * 1024)
                                        while (true) {
                                            ensureActive()
                                            val read = input.read(buffer)
                                            if (read < 0) break
                                            output.write(buffer, 0, read)
                                        }
                                        output.flush()
                                        output.fd.sync()
                                    }
                                }
                                failure = when {
                                    partial.length() <= 0L -> "복사된 미디어가 비어 있습니다. 다시 시도해 주세요."
                                    !isReadableMediaFile(partial, item.isVideo) -> "지원되지 않거나 손상된 미디어입니다. 원본을 확인하거나 이 항목을 제외해 주세요."
                                    !partial.renameTo(finalFile) -> "준비한 사본을 저장하지 못했습니다. 저장 공간을 확인한 뒤 다시 시도해 주세요."
                                    else -> null
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            failure = "미디어를 복사하지 못했습니다: ${error.message ?: "알 수 없는 오류"}. 다시 시도해 주세요."
                        } finally {
                            if (partial.exists()) partial.delete()
                        }
                        if (failure == null) {
                            commit(item, item.copy(state = MaterialPreparationState.READY, localFilePath = finalFile.absolutePath, downloadProgress = null, errorMessage = null), ownedNewFile = finalFile)
                        } else {
                            // The original and any existing copy stay as they were.
                            commit(item, item.copy(state = MaterialPreparationState.MISSING, downloadProgress = null, errorMessage = failure))
                        }
                    }
                }
                MaterialSourceType.PHONE_HARD -> {
                    val file = item.localFilePath?.let { File(it) }
                    val accessible = file != null && file.exists() && file.length() > 0L
                    if (!accessible) {
                        commit(item, item.copy(state = MaterialPreparationState.MISSING, downloadProgress = null, errorMessage = "폰하드 파일이 삭제되었거나 누락되었습니다."))
                    } else {
                        commit(item, item.copy(state = MaterialPreparationState.READY, downloadProgress = null, errorMessage = null))
                    }
                }
                MaterialSourceType.REMOTE_FILE -> {
                    // Check if already downloaded
                    val existingFile = item.localFilePath?.let { File(it) }
                    if (existingFile != null && existingFile.exists() && existingFile.length() > 0L) {
                        commit(item, item.copy(state = MaterialPreparationState.READY, downloadProgress = null, errorMessage = null))
                    } else {
                        // Download remote file using dedicated service
                        val connectionId = item.connectionId ?: item.sourceIdentity.removePrefix("remote:").substringBefore(':')
                        val remotePath = item.remotePath ?: item.sourceIdentity.removePrefix("remote:").substringAfter(':')
                        val connection = _state.value.connections.firstOrNull { it.id == connectionId }

                        if (connection == null) {
                            val errorMsg = "원격 연결을 찾을 수 없습니다 (${connectionId})"
                            commit(item, item.copy(state = MaterialPreparationState.MISSING, downloadProgress = null, errorMessage = errorMsg))
                        } else {
                            if (registeredInBasket) DurableMaterialStore.updateState(item.id, MaterialPreparationState.DOWNLOADING, progress = 0f, errorMessage = null)
                            onItemProgress?.invoke(item.id, 0f)
                            val ext = item.name.substringAfterLast('.', "").takeIf { it.isNotBlank() && it.length <= 5 && it.all(Char::isLetterOrDigit) }
                                ?: if (item.isVideo) "mp4" else "jpg"
                            // Names unique to THIS operation: only its own partial is ever deleted here.
                            val targetFile = File(stagingDir, "remote_${item.id}.${UUID.randomUUID()}.$ext")
                            val tempFile = File(stagingDir, "${targetFile.name}.part")

                            var downloadSuccess = false
                            var errorDesc: String? = null
                            try {
                                withDedicatedRemoteService(connection) { service ->
                                    val remoteItem = RemoteFileItem(
                                        id = item.id,
                                        name = item.name,
                                        path = remotePath,
                                        isDirectory = false,
                                        size = item.fileSize ?: 0L
                                    )
                                    service.download(remoteItem, tempFile) { transferred, totalBytes ->
                                        val p = if (totalBytes > 0) transferred.toFloat() / totalBytes else 0.5f
                                        if (registeredInBasket) DurableMaterialStore.updateState(item.id, MaterialPreparationState.DOWNLOADING, progress = p)
                                        onItemProgress?.invoke(item.id, if (totalBytes > 0) p else null)
                                    }
                                }
                                if (!tempFile.exists() || tempFile.length() <= 0L) {
                                    errorDesc = "다운로드된 파일이 비어 있습니다."
                                } else if (!isReadableMediaFile(tempFile, item.isVideo)) {
                                    errorDesc = "지원되지 않거나 손상된 미디어입니다. 원본을 확인하거나 이 항목을 제외해 주세요."
                                } else if (tempFile.renameTo(targetFile)) {
                                    downloadSuccess = true
                                } else {
                                    errorDesc = "임시 파일 저장에 실패했습니다."
                                }
                            } catch (cancelled: CancellationException) {
                                // Interrupted: the record goes back to retry-needed; only this operation's partial is removed.
                                if (registeredInBasket) DurableMaterialStore.updateState(item.id, MaterialPreparationState.MISSING, errorMessage = "가져오기가 중단되었습니다. 다시 시도해 주세요.")
                                throw cancelled
                            } catch (e: Exception) {
                                errorDesc = e.message ?: "다운로드 중 오류가 발생했습니다."
                            } finally {
                                if (tempFile.exists()) tempFile.delete()
                            }

                            if (downloadSuccess) {
                                commit(item, item.copy(state = MaterialPreparationState.READY, localFilePath = targetFile.absolutePath, downloadProgress = null, errorMessage = null), ownedNewFile = targetFile)
                            } else {
                                commit(item, item.copy(state = MaterialPreparationState.MISSING, downloadProgress = null, errorMessage = errorDesc))
                            }
                        }
                    }
                }
            }
        }.also {
            if (registeredInBasket) {
                withContext(Dispatchers.Main) {
                    refreshMaterials()
                }
            }
        }
    }

    // ---- 프라이버시 모드 -----------------------------------------------------------------------------------------------
    // 추가형 가시성: 일반 모드 = 공개만, 프라이버시 모드 = 공개 + 비공개. 메모리에만 있고 앱을 새로 열면 항상 일반 모드다.

    fun togglePrivacyMode() = setPrivacyMode(!PrivacyMode.isActive)

    fun setPrivacyMode(active: Boolean) {
        if (PrivacyMode.isActive == active) return
        PrivacyMode.setActive(active)
        reloadForPrivacyMode()
    }

    /** 프라이버시 모드에서 로고를 한 번 누르면: 모드를 끄고 홈으로 간다(머리글 글자는 NasFinder로 돌아온다). */
    fun exitPrivacyToHome() = setPrivacyMode(false)

    /**
     * 모드가 바뀌면 보이는 목록을 다시 읽고 화면·시트·선택을 비운다. 진행 중인 편집·내보내기·다운로드는 취소하거나 지우지 않는다
     * (편집기는 접어 두기만 한다). 이미 시작한 작업의 공개 범위는 시작 시점에 고정되어 바뀌지 않는다.
     */
    private fun reloadForPrivacyMode() {
        stopInlineAudio()
        val files = runCatching { inboxFiles() }.getOrDefault(emptyList())
        val visibleConnections = repository.load()
        val visibleConnectionIds = visibleConnections.mapTo(hashSetOf()) { it.id }
        val history = loadSuperThumbnailHistory()
        val workLocations = loadSuperThumbnailWorkLocations()
        _state.update { current ->
            // 슈퍼썸네일의 선택 위치·작업 위치·보고서가 이제 보이지 않는 연결을 가리키면 화면에 남지 않게 비운다(저장된 기록은 그대로).
            val selectionHidden = current.superThumbnailConnectionId != null && current.superThumbnailConnectionId !in visibleConnectionIds
            val workHidden = current.superThumbnailWorkLocation?.let { it.connectionId !in visibleConnectionIds } == true
            val reportHidden = current.superThumbnailReportLocationId?.let { id ->
                (current.superThumbnailHistory + listOfNotNull(current.superThumbnailWorkLocation)).firstOrNull { it.id == id }
                    ?.let { it.connectionId !in visibleConnectionIds } ?: selectionHidden
            } == true
            current.copy(
                connections = visibleConnections,
                superThumbnailHistory = history,
                superThumbnailWorkLocations = workLocations,
                superThumbnailConnectionId = if (selectionHidden) null else current.superThumbnailConnectionId,
                superThumbnailPath = if (selectionHidden) null else current.superThumbnailPath,
                superThumbnailTitle = if (selectionHidden) null else current.superThumbnailTitle,
                superThumbnailWork = if (selectionHidden) null else current.superThumbnailWork,
                superThumbnailWorkLocation = if (workHidden) null else current.superThumbnailWorkLocation,
                superThumbnailSessionReport = if (reportHidden || selectionHidden) null else current.superThumbnailSessionReport,
                superThumbnailReportLocationId = if (reportHidden || selectionHidden) null else current.superThumbnailReportLocationId,
                preferredId = repository.preferredId(),
                remoteFavorites = favoriteRepository.remoteFavorites(),
                browserFavorites = favoriteRepository.browserFavorites(),
                inboxFiles = files,
                inboxErrorMessage = null,
                collections = DurableCollectionStore.getItems(),
                trailerDraftCount = TrailerDraftStore.draftCount(),
                trailerDraftPicker = null,
                screen = Screen.Dashboard,
                activeMainTab = MainNavigationDestination.HOME,
                imagePreview = null,
                homeFavoriteAction = null,
                browserSelectRequest = null,
                initialInboxSubpath = null,
                pendingTransfer = null,
                pendingInboxUpload = null,
                pendingLocalUpload = null,
                workbenchAdd = null,
                workbenchSelected = emptySet(),
                workbenchReconnect = null,
                pendingCollectionImport = null,
                collectionCopyOffer = null,
                trailerMinimized = current.trailer != null || current.trailerMinimized,
                message = null,
            )
        }
        publishMaterials()
    }

    // 명시적인 공개/비공개 전환. 일반 모드에서 비공개로 바꾸면 그 기록이 열려 있던 화면·선택을 즉시 닫는다.
    private val hidesOnPrivate: (Boolean) -> Boolean get() = { isPrivate -> isPrivate && !PrivacyMode.isActive }

    fun setConnectionPrivate(connection: RemoteConnection, isPrivate: Boolean) {
        repository.setPrivate(connection.id, isPrivate)
        val hiding = hidesOnPrivate(isPrivate)
        _state.update { current ->
            val viewingIt = hiding && (current.screen as? Screen.Browser)?.connection?.id == connection.id
            current.copy(
                connections = repository.load(),
                preferredId = repository.preferredId(),
                remoteFavorites = favoriteRepository.remoteFavorites(),
                screen = if (viewingIt) Screen.Dashboard else current.screen,
                homeFavoriteAction = if (hiding) null else current.homeFavoriteAction,
            )
        }
    }

    fun setRemoteFavoritePrivate(favoriteId: String, isPrivate: Boolean) {
        val list = favoriteRepository.setRemotePrivate(favoriteId, isPrivate)
        _state.update { it.copy(remoteFavorites = list, homeFavoriteAction = if (hidesOnPrivate(isPrivate)) null else it.homeFavoriteAction) }
    }

    fun setBrowserFavoritePrivate(favoriteId: String, isPrivate: Boolean) {
        _state.update { it.copy(browserFavorites = favoriteRepository.setBrowserPrivate(favoriteId, isPrivate)) }
    }

    fun setPocketPrivate(pocketId: String, isPrivate: Boolean) {
        if (!DurableMaterialStore.setPocketPrivate(pocketId, isPrivate)) {
            _state.update { it.copy(message = MATERIAL_SAVE_ERROR) }
            return
        }
        publishMaterials()
    }

    fun setMaterialPrivate(itemId: String, isPrivate: Boolean) {
        if (!DurableMaterialStore.setItemPrivate(itemId, isPrivate)) {
            _state.update { it.copy(message = MATERIAL_SAVE_ERROR) }
            return
        }
        publishMaterials()
    }

    fun setCollectionPrivate(itemId: String, isPrivate: Boolean) {
        DurableCollectionStore.setPrivate(itemId, isPrivate)
        refreshCollections()
    }

    fun setDraftPrivate(draftId: String, isPrivate: Boolean) {
        TrailerDraftStore.setDraftPrivate(draftId, isPrivate)
        _state.update {
            it.copy(
                trailerDraftCount = TrailerDraftStore.draftCount(),
                trailerDraftsVersion = it.trailerDraftsVersion + 1,
                trailerDraftPicker = it.trailerDraftPicker?.let { TrailerDraftStore.getAllDrafts() },
            )
        }
    }

    fun setPhoneHardPrivate(relativePath: String, isDirectory: Boolean, isPrivate: Boolean) {
        if (!inboxStore.setPrivate(relativePath, isDirectory, isPrivate)) {
            _state.update { it.copy(message = "공개 범위를 바꾸지 못했습니다.") }
            return
        }
        val hiding = hidesOnPrivate(isPrivate)
        refreshInbox()
        if (hiding) _state.update { it.copy(initialInboxSubpath = null) }
    }

    fun refreshCollections() {
        _state.update { it.copy(collections = DurableCollectionStore.getItems()) }
    }

    fun addExportedVideoToCollection(
        title: String,
        file: File,
        durationSeconds: Double,
        posterFile: File? = null,
        sourceDraftId: String? = null
    ): CollectionItem? {
        val saved = DurableCollectionStore.addVideo(
            title = title,
            exportedFile = file,
            durationSeconds = durationSeconds,
            posterFile = posterFile,
            sourceDraftId = sourceDraftId
        )
        refreshCollections()
        if (saved != null) {
            _state.update { it.copy(message = "‘${saved.title}’을(를) 컬렉션에 저장했습니다.") }
        }
        return saved
    }

    fun removeCollectionVideo(id: String) {
        DurableCollectionStore.removeVideo(id)
        refreshCollections()
    }

    fun toggleCollectionPin(id: String) {
        DurableCollectionStore.togglePin(id)
        refreshCollections()
    }

    fun moveCollectionItem(id: String, offset: Int) {
        DurableCollectionStore.moveItem(id, offset)
        refreshCollections()
    }

    fun renameCollectionVideo(id: String, newTitle: String) {
        DurableCollectionStore.rename(id, newTitle)
        refreshCollections()
    }

    /** Stores the user-confirmed 추천 썸네일 candidate; cancelling never reaches here, so the prior poster stays. */
    fun confirmCollectionPoster(id: String, frame: Bitmap) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val temp = File(application.cacheDir, "poster-confirm-${UUID.randomUUID()}.jpg")
                try {
                    temp.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    DurableCollectionStore.updatePoster(id, temp)
                } catch (_: Exception) {
                    false
                } finally {
                    temp.delete()
                }
            }
            refreshCollections()
            if (!ok) _state.update { it.copy(message = "썸네일을 저장하지 못했습니다. 이전 썸네일은 그대로입니다.") }
        }
    }

    private var compressionJob: Job? = null

    suspend fun collectionVideoFacts(item: CollectionItem): VideoFileFacts? = withContext(Dispatchers.IO) {
        collectionReadableFile(item)?.let(::readVideoFacts)
    }

    /**
     * A local file to read the collection video from: the owned copy itself, or for a granted content:// reference a
     * temporary working copy in cache (never recorded as the item's storage; the external original is only read).
     * Must be called off the main thread. Null when the video cannot be read now.
     */
    private fun collectionReadableFile(item: CollectionItem): File? {
        if (!item.isContentReference) return File(item.localFilePath).takeIf { it.isFile && it.length() > 0L }
        if (!DurableCollectionStore.hasPersistedReadGrant(item.localFilePath)) return null
        runCatching { UUID.fromString(item.id) }.getOrNull() ?: return null
        val dir = File(application.cacheDir, "collection_reference_use").also { it.mkdirs() }
        val target = File(dir, "${item.id}.mp4")
        if (target.isFile && target.length() > 0L && item.fileSizeBytes > 0L && target.length() == item.fileSizeBytes) return target
        val partial = File(dir, "${item.id}.mp4.part")
        return runCatching {
            application.contentResolver.openInputStream(Uri.parse(item.localFilePath))?.use { input ->
                java.io.FileOutputStream(partial).use { output -> input.copyTo(output); output.fd.sync() }
            } ?: error("외부 원본을 열 수 없습니다.")
            check(partial.length() > 0L && partial.renameTo(target))
            target
        }.getOrElse {
            partial.delete()
            null
        }
    }

    /**
     * 용량 줄이기: snapshots the item and preset, renders into this operation's own partial file, validates it and
     * commits only a smaller playable result. Any failure/cancel/interruption leaves the old video and record untouched.
     */
    fun startCollectionCompression(itemId: String, preset: CompressionPreset) {
        if (_state.value.compression?.running == true) {
            _state.update { it.copy(message = "다른 영상의 용량을 줄이는 중입니다. 끝난 뒤 다시 시도해 주세요.") }
            return
        }
        val item = DurableCollectionStore.item(itemId) ?: return
        val operationId = UUID.randomUUID().toString()
        val output = DurableCollectionStore.compressionPartialFile(operationId) ?: return
        _state.update {
            it.copy(compression = CollectionCompressionState(itemId, operationId, item.title, preset, CompressionPhase.PREPARING, originalBytes = item.fileSizeBytes, originPrivate = item.isPrivate || PrivacyMode.isActive))
        }
        fun update(transform: (CollectionCompressionState) -> CollectionCompressionState) {
            _state.update { current ->
                val live = current.compression?.takeIf { it.operationId == operationId } ?: return@update current
                current.copy(compression = transform(live))
            }
        }
        compressionJob = viewModelScope.launch {
            val self = coroutineContext[Job]
            try {
                val input = withContext(Dispatchers.IO) { collectionReadableFile(item) }
                    ?: error("원본 영상을 읽을 수 없습니다. 파일이 이동되었거나 읽기 권한이 없습니다.")
                val facts = withContext(Dispatchers.IO) { readVideoFacts(input) }
                    ?: error("원본 영상을 읽을 수 없습니다. 파일이 이동되었거나 지원되지 않는 형식입니다.")
                val (width, height) = compressionTargetSize(facts, preset)
                update { it.copy(phase = CompressionPhase.RUNNING, progress = 0f, originalBytes = facts.sizeBytes) }
                CollectionCompressor.compress(application, input, output, width, height, preset.videoBitrate) { fraction ->
                    update { it.copy(progress = fraction) }
                }
                update { it.copy(phase = CompressionPhase.VALIDATING, progress = null) }
                val problem = withContext(Dispatchers.IO) { validateCompressedOutput(output, facts, width, height) }
                if (problem != null) error("$problem 원본은 그대로 유지했습니다.")
                val resultBytes = output.length()
                if (resultBytes >= facts.sizeBytes) {
                    withContext(Dispatchers.IO) { if (DurableCollectionStore.isOwnedCompressionPartial(output)) output.delete() }
                    update {
                        it.copy(
                            phase = CompressionPhase.NO_GAIN, resultBytes = resultBytes,
                            message = "더 작은 결과를 만들지 못했습니다 (원본 ${formatBytes(facts.sizeBytes)} → 결과 ${formatBytes(resultBytes)}). 원본을 그대로 유지했습니다.",
                        )
                    }
                    return@launch
                }
                update { it.copy(phase = CompressionPhase.COMMITTING, resultBytes = resultBytes) }
                val result = withContext(Dispatchers.IO) {
                    DurableCollectionStore.replaceWithCompressed(itemId, item.localFilePath, output, readVideoFacts(output)?.durationSeconds ?: facts.durationSeconds, preset.title)
                }
                when (result) {
                    CollectionReplaceResult.REPLACED -> update {
                        it.copy(phase = CompressionPhase.DONE, progress = 1f, message = "${formatBytes(facts.sizeBytes)} → ${formatBytes(resultBytes)}로 줄였습니다.")
                    }
                    CollectionReplaceResult.ITEM_CHANGED -> {
                        withContext(Dispatchers.IO) { if (DurableCollectionStore.isOwnedCompressionPartial(output)) output.delete() }
                        update { it.copy(phase = CompressionPhase.FAILED, message = "작업 중에 이 영상이 바뀌었거나 삭제되어 적용하지 않았습니다.") }
                    }
                    CollectionReplaceResult.FAILED -> {
                        withContext(Dispatchers.IO) { if (DurableCollectionStore.isOwnedCompressionPartial(output)) output.delete() }
                        update { it.copy(phase = CompressionPhase.FAILED, message = "압축본을 저장하지 못했습니다. 저장 공간을 확인해 주세요. 원본은 그대로입니다.") }
                    }
                }
                refreshCollections()
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { if (DurableCollectionStore.isOwnedCompressionPartial(output)) output.delete() }
                update { it.copy(phase = CompressionPhase.CANCELLED, progress = null, message = "취소했습니다. 원본은 그대로입니다.") }
                throw cancelled
            } catch (failure: Exception) {
                withContext(NonCancellable + Dispatchers.IO) { if (DurableCollectionStore.isOwnedCompressionPartial(output)) output.delete() }
                val reason = when (failure) {
                    is androidx.media3.transformer.ExportException -> "이 기기에서 이 영상 형식을 변환할 수 없습니다 (오류 ${failure.errorCode}). 원본은 그대로입니다."
                    else -> failure.message ?: "용량을 줄이지 못했습니다. 원본은 그대로입니다."
                }
                update { it.copy(phase = CompressionPhase.FAILED, progress = null, message = reason) }
            } finally {
                if (compressionJob === self) compressionJob = null
            }
        }
    }

    fun cancelCollectionCompression() {
        compressionJob?.cancel()
    }

    fun dismissCollectionCompression() {
        if (_state.value.compression?.running == true) return
        _state.update { it.copy(compression = null) }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var index = -1
        do { size /= 1024; index++ } while (size >= 1024 && index < units.lastIndex)
        return "%.1f %s".format(size, units[index])
    }

    private val collectionUnavailableMessage = "영상을 읽을 수 없습니다. 메뉴의 ‘다시 연결’을 이용하거나, 필요 없으면 직접 삭제해 주세요. 컬렉션 정보는 그대로 보관합니다."

    fun shareCollectionVideo(item: CollectionItem) {
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) { collectionReadableFile(item) }
            if (file == null) {
                _state.update { it.copy(message = collectionUnavailableMessage) }
                return@launch
            }
            val sendIntent = NasFinderShareIntentFactory.create(application, listOf(file))
            application.startActivity(
                Intent.createChooser(sendIntent, "${item.title} 공유")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun previewCollectionVideo(item: CollectionItem) {
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) { collectionReadableFile(item) }
            if (file == null) {
                _state.update { it.copy(message = collectionUnavailableMessage) }
                return@launch
            }
            previewCollectionFile(item, file)
        }
    }

    private fun previewCollectionFile(item: CollectionItem, file: File) {
        stopInlineAudio()
        val remote = RemoteFileItem(
            id = item.id,
            name = item.title + (if (item.title.endsWith(".mp4", ignoreCase = true)) "" else ".mp4"),
            path = file.absolutePath,
            isDirectory = false,
            size = file.length(),
            modifiedAt = java.time.Instant.ofEpochMilli(item.timestamp),
            mimeType = "video/mp4"
        )
        prepareLocalPreview(listOf(remote), listOf(file), 0)
    }

    fun importVideoUriToCollection(uri: Uri, copyToStorage: Boolean = true) {
        if (!copyToStorage) {
            // A reference is only ever made through the confirmation dialog, which re-checks support and the grant.
            stageCollectionImport(listOf(uri to null))
            return
        }
        val jobPrivate = PrivacyMode.isActive
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, message = "영상을 가져오는 중…") }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = queryName(uri) ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "가져온 영상"
                    val cleanTitle = name.substringBeforeLast('.')
                    val tempDir = File(application.cacheDir, "collection_import").also { it.mkdirs() }
                    val tempVideo = File(tempDir, "import-${UUID.randomUUID()}.mp4")
                    try {
                        application.contentResolver.openInputStream(uri)?.use { input ->
                            tempVideo.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("영상을 읽을 수 없습니다.")
                        if (tempVideo.length() <= 0L) error("빈 영상 파일입니다.")
                    } catch (failure: Exception) {
                        tempVideo.delete()
                        throw failure
                    }
                    
                    var durationSec = 0.0
                    var posterFile: File? = null
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(tempVideo.absolutePath)
                        durationSec = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000.0) ?: 0.0
                        val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        if (frame != null) {
                            val pFile = File(tempDir, "poster-${UUID.randomUUID()}.jpg")
                            pFile.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                            posterFile = pFile
                        }
                    } catch (_: Exception) {}
                    finally {
                        runCatching { retriever.release() }
                    }

                    // The store keeps its own permanent copy; the temporary import staging files are this call's own.
                    try {
                        DurableCollectionStore.addVideo(
                            title = cleanTitle,
                            exportedFile = tempVideo,
                            durationSeconds = durationSec,
                            posterFile = posterFile,
                            sourceUri = uri.toString(),
                            isPrivate = jobPrivate,
                        ) ?: error("컬렉션에 저장하지 못했습니다. 저장 공간을 확인해 주세요.")
                    } finally {
                        tempVideo.delete()
                        posterFile?.delete()
                    }
                }
            }
            _state.update { it.copy(isBusy = false) }
            result.onSuccess { item ->
                refreshCollections()
                _state.update { it.copy(message = "‘${item.title}’을(를) 컬렉션에 추가했습니다 (앱 보관).") }
            }.onFailure { error ->
                _state.update { it.copy(message = error.message ?: "영상을 가져오지 못했습니다.") }
            }
        }
    }

    /**
     * Collection import entry for picked content: stages the items for the 사본/참조 confirmation dialog (copy is the
     * default). [picked] pairs each uri with the flags its picker result actually returned, or null when the caller
     * only has the uri (then a reference is offered only if a persisted read grant is already held).
     */
    fun stageCollectionImport(picked: List<Pair<Uri, Int?>>) {
        if (picked.isEmpty()) return
        if (_state.value.pendingCollectionImport?.working == true) {
            _state.update { it.copy(message = "이전 컬렉션 추가가 진행 중입니다. 끝난 뒤 다시 시도해 주세요.") }
            return
        }
        viewModelScope.launch {
            var skippedImages = 0
            val entries = withContext(Dispatchers.IO) {
                picked.distinctBy { it.first.toString() }.mapNotNull { (uri, flags) ->
                    val mime = runCatching { application.contentResolver.getType(uri) }.getOrNull()
                    if (mime?.startsWith("image/") == true) {
                        skippedImages++
                        return@mapNotNull null
                    }
                    val name = queryName(uri) ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "가져온 영상"
                    val size = if (uri.scheme == "content") runCatching {
                        application.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
                        }
                    }.getOrNull() ?: 0L else runCatching { uri.path?.let(::File)?.length() }.getOrNull() ?: 0L
                    val persistableRead = if (flags != null && flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
                        flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                    } else 0
                    val blocker = when {
                        uri.scheme != "content" ->
                            "앱 안의 임시 파일이나 기기 경로는 앱을 다시 열었을 때 계속 읽을 수 있다고 보장되지 않습니다."
                        flags != null && persistableRead == 0 ->
                            "이 영상을 제공한 앱이 재시작 뒤에도 유지되는 읽기 권한을 주지 않았습니다."
                        flags == null && !DurableCollectionStore.hasPersistedReadGrant(uri.toString()) ->
                            "이 항목은 지금만 읽을 수 있는 임시 권한으로 선택되었습니다."
                        else -> null
                    }
                    PendingImportEntry(uri.toString(), name.substringBeforeLast('.').ifBlank { name }, size, persistableRead, blocker)
                }
            }
            if (entries.isEmpty()) {
                _state.update { it.copy(message = if (skippedImages > 0) "컬렉션에는 영상만 추가할 수 있습니다. 선택한 사진은 제외했습니다." else "추가할 영상을 읽지 못했습니다.") }
                return@launch
            }
            _state.update {
                it.copy(pendingCollectionImport = PendingCollectionImport(
                    entries = entries,
                    excludedNote = if (skippedImages > 0) "사진 ${skippedImages}개는 컬렉션 대상이 아니라 제외했습니다." else null,
                ))
            }
        }
    }

    fun cancelCollectionImport() {
        if (_state.value.pendingCollectionImport?.working == true) return
        _state.update { it.copy(pendingCollectionImport = null) }
    }

    /**
     * Confirmation of the staged import. Copy (default) stores app-owned copies. Reference keeps the external original
     * without copying, and only for entries whose persisted read grant is verified after taking it as the picker allowed;
     * any entry that cannot be verified is NOT recorded and stays pending with a retry / copy choice.
     */
    fun confirmCollectionImport(asReference: Boolean) {
        val pending = _state.value.pendingCollectionImport ?: return
        if (pending.working) return
        if (!asReference) {
            _state.update { it.copy(pendingCollectionImport = null) }
            pending.entries.forEach { entry -> importVideoUriToCollection(Uri.parse(entry.uri), copyToStorage = true) }
            return
        }
        if (!pending.referenceSupported) {
            _state.update { it.copy(pendingCollectionImport = pending.copy(problem = "선택한 영상 중 원본 참조를 지원하지 않는 항목이 있습니다. 앱 안에 사본으로 보관해 주세요.")) }
            return
        }
        _state.update { it.copy(pendingCollectionImport = pending.copy(working = true, problem = null)) }
        val jobPrivate = PrivacyMode.isActive
        viewModelScope.launch {
            val added = mutableListOf<CollectionItem>()
            val notGranted = mutableListOf<PendingImportEntry>()
            val failed = mutableListOf<Pair<PendingImportEntry, String>>()
            withContext(Dispatchers.IO) {
                pending.entries.forEach { entry ->
                    runCatching { addVerifiedContentReference(entry, jobPrivate) }
                        .onSuccess { item -> if (item != null) added += item else notGranted += entry }
                        .onFailure { error -> failed += entry to (error.message ?: "컬렉션 정보를 저장하지 못했습니다.") }
                }
            }
            if (added.isNotEmpty()) refreshCollections()
            val remaining = notGranted + failed.map { it.first }
            if (remaining.isEmpty()) {
                _state.update {
                    it.copy(
                        pendingCollectionImport = null,
                        message = if (added.size == 1) "‘${added.first().title}’을(를) 원본 참조로 추가했습니다. 원본을 옮기거나 지우면 다시 연결이 필요합니다."
                        else "${added.size}개 영상을 원본 참조로 추가했습니다. 원본을 옮기거나 지우면 다시 연결이 필요합니다.",
                    )
                }
            } else {
                val problem = buildList {
                    if (added.isNotEmpty()) add("${added.size}개는 원본 참조로 추가했습니다.")
                    if (notGranted.isNotEmpty()) add("${notGranted.size}개는 재시작 뒤에도 유지되는 읽기 권한을 확인하지 못해 추가하지 않았습니다.")
                    failed.firstOrNull()?.let { (_, reason) -> add("${failed.size}개는 저장하지 못했습니다: $reason") }
                    add("다시 시도하거나 앱 안에 사본으로 보관할 수 있습니다.")
                }.joinToString(" ")
                _state.update { it.copy(pendingCollectionImport = pending.copy(entries = remaining, working = false, problem = problem)) }
            }
        }
    }

    /**
     * Takes the persistable READ grant only when the picker returned it as persistable, then verifies the grant is
     * actually held before recording. Returns null (nothing recorded) when it is not; throws only on store failure.
     */
    private fun addVerifiedContentReference(entry: PendingImportEntry, jobPrivate: Boolean): CollectionItem? {
        val uri = Uri.parse(entry.uri)
        if (uri.scheme != "content") return null
        if (entry.persistableReadFlag != 0) {
            runCatching { application.contentResolver.takePersistableUriPermission(uri, entry.persistableReadFlag) }
        }
        if (!DurableCollectionStore.hasPersistedReadGrant(entry.uri)) return null
        val tempDir = File(application.cacheDir, "collection_import").also { it.mkdirs() }
        var durationSec = 0.0
        var hasVideoTrack = false
        var posterFile: File? = null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(application, uri)
            durationSec = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000.0) ?: 0.0
            hasVideoTrack = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            if (hasVideoTrack && durationSec.isFinite() && durationSec > 0.0) {
                runCatching { retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }.getOrNull()?.let { frame ->
                    val pFile = File(tempDir, "poster-${UUID.randomUUID()}.jpg")
                    runCatching { pFile.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
                        .onSuccess { posterFile = pFile }.onFailure { pFile.delete() }
                }
            }
        } catch (_: Exception) {
            hasVideoTrack = false
        } finally {
            runCatching { retriever.release() }
        }
        // Only a readable video track with a real length is recorded; anything else stays pending with retry / copy.
        if (!hasVideoTrack || !durationSec.isFinite() || durationSec <= 0.0) {
            posterFile?.delete()
            error("영상 정보를 읽지 못했습니다(영상 트랙 또는 길이 확인 실패). 지원되지 않거나 손상된 영상일 수 있습니다. 다시 시도하거나 앱 안에 사본으로 보관해 보세요.")
        }
        return try {
            DurableCollectionStore.addContentReference(entry.title, entry.uri, durationSec, entry.sizeBytes, posterFile, isPrivate = jobPrivate)
                ?: error("컬렉션 정보를 저장하지 못했습니다. 저장 공간을 확인해 주세요.")
        } finally {
            posterFile?.delete()
        }
    }

    fun acceptCollectionCopyOffer() {
        val offer = _state.value.collectionCopyOffer ?: return
        _state.update { it.copy(collectionCopyOffer = null) }
        offer.copy()
    }

    fun dismissCollectionCopyOffer() {
        _state.update { it.copy(collectionCopyOffer = null) }
    }

    /**
     * "다시 연결" for a record whose video cannot be read: re-reads its recorded origin (content/file URI, or the
     * remote path on its saved connection) into a fresh owned copy. The record is never removed; on failure it stays
     * as-is with an explanation.
     */
    fun relinkCollectionVideo(id: String) {
        val item = DurableCollectionStore.item(id) ?: return
        if (item.isValid) {
            _state.update { it.copy(message = "이 영상은 지금 재생할 수 있습니다.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, message = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val origin = item.sourceUri
                    val connection = item.connectionId?.let { cid -> _state.value.connections.firstOrNull { it.id == cid } }
                    val tempDir = File(application.cacheDir, "collection_import").also { it.mkdirs() }
                    val temp = File(tempDir, "relink-${UUID.randomUUID()}.mp4")
                    try {
                        when {
                            origin != null && (origin.startsWith("content://") || origin.startsWith("file://")) -> {
                                val opened = try {
                                    application.contentResolver.openInputStream(Uri.parse(origin))
                                } catch (_: SecurityException) {
                                    error("원본을 읽을 권한이 더 이상 없습니다. 원본 영상을 다시 가져온 뒤 이 항목을 직접 삭제해 주세요.")
                                } ?: error("원본을 열 수 없습니다. 원본이 이동되었거나 삭제되었을 수 있습니다.")
                                opened.use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
                            }
                            origin != null && connection != null -> withDedicatedRemoteService(connection) { service ->
                                service.download(
                                    RemoteFileItem(id = origin, name = origin.substringAfterLast('/'), path = origin, isDirectory = false, size = 0L),
                                    temp,
                                )
                            }
                            item.connectionId != null -> error("이 영상을 가져온 연결을 찾을 수 없습니다. 연결을 다시 추가한 뒤 시도해 주세요.")
                            else -> error("원본 위치 정보가 없어 다시 연결할 수 없습니다. 원본 영상을 다시 가져온 뒤 이 항목을 직접 삭제해 주세요.")
                        }
                        check(temp.isFile && temp.length() > 0L) { "원본에서 영상을 읽지 못했습니다." }
                        check(DurableCollectionStore.relinkWithCopy(item.id, item.localFilePath, temp)) { "컬렉션 정보를 저장하지 못했습니다. 저장 공간을 확인해 주세요." }
                    } finally {
                        temp.delete()
                    }
                }
            }
            refreshCollections()
            _state.update {
                it.copy(
                    isBusy = false,
                    message = result.fold(
                        onSuccess = { "‘${item.title}’을(를) 다시 연결해 앱 안에 보관했습니다." },
                        onFailure = { error -> (error.message ?: "다시 연결하지 못했습니다.") + " 컬렉션 정보는 그대로 보관합니다." },
                    ),
                )
            }
        }
    }

    fun importRemoteFilesToCollection(connection: RemoteConnection, remoteItems: List<RemoteFileItem>, copyToStorage: Boolean = true) {
        val videos = remoteItems.filter { !it.isDirectory && it.isVideo }
        if (videos.isEmpty()) {
            _state.update { it.copy(message = "추가할 영상 파일이 선택되지 않았습니다.") }
            return
        }
        if (!copyToStorage) {
            // A NAS/cloud path is only playable through a live connection and a download; it is not a durable local
            // reference, so it is never recorded as one. Explain and offer the app-owned copy instead.
            _state.update {
                it.copy(
                    collectionCopyOffer = CollectionCopyOffer(
                        message = "원격 저장소의 영상은 연결 상태에 따라 재생할 수 없게 되므로 외부 참조로 보관할 수 없습니다.\n\n" +
                            "대신 앱 안에 사본을 보관할까요? 원격 원본은 바꾸거나 지우지 않으며, 가져온 위치 정보는 함께 기록됩니다.",
                        copy = { importRemoteFilesToCollection(connection, remoteItems, copyToStorage = true) },
                    ),
                )
            }
            return
        }
        val jobPrivate = PrivacyMode.capturedOrigin(connection.isPrivate || PrivacyMode.isPrivateConnection(connection.id))
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, message = "${videos.size}개 영상을 가져오는 중…") }
            var successCount = 0
            withContext(Dispatchers.IO) {
                videos.forEach { item ->
                    runCatching {
                        val tempDir = File(application.cacheDir, "collection_import").also { it.mkdirs() }
                        val tempVideo = File(tempDir, "import-${UUID.randomUUID()}.mp4")
                        try {
                            withDedicatedRemoteService(connection) { dedicatedSvc ->
                                dedicatedSvc.download(item, tempVideo)
                            }
                        } catch (failure: Exception) {
                            tempVideo.delete()
                            throw failure
                        }
                        if (tempVideo.length() > 0L) {
                            var durationSec = 0.0
                            var posterFile: File? = null
                            val retriever = MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(tempVideo.absolutePath)
                                durationSec = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000.0) ?: 0.0
                                val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                if (frame != null) {
                                    val pFile = File(tempDir, "poster-${UUID.randomUUID()}.jpg")
                                    pFile.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                                    posterFile = pFile
                                }
                            } catch (_: Exception) {}
                            finally { runCatching { retriever.release() } }

                            val cleanTitle = item.name.substringBeforeLast('.')
                            val saved = DurableCollectionStore.addVideo(
                                title = cleanTitle,
                                exportedFile = tempVideo,
                                durationSeconds = durationSec,
                                posterFile = posterFile,
                                sourceUri = item.path,
                                connectionId = connection.id,
                                isPrivate = jobPrivate,
                            )
                            if (saved != null) successCount++
                            posterFile?.delete()
                        }
                        // Only this call's own download staging file; the store already holds its own copy (or nothing).
                        tempVideo.delete()
                    }
                }
            }
            _state.update { it.copy(isBusy = false) }
            refreshCollections()
            val failed = videos.size - successCount
            _state.update {
                it.copy(message = "${successCount}개 영상을 컬렉션에 추가했습니다 (앱 보관)." + if (failed > 0) " ${failed}개는 내려받거나 저장하지 못했습니다." else "")
            }
        }
    }

    fun createProjectFromCollectionVideo(item: CollectionItem) {
        val live = DurableCollectionStore.item(item.id) ?: item
        if (live.isContentReference) {
            if (!live.isValid) {
                _state.update { it.copy(message = "외부 원본을 읽을 권한이 없습니다. 메뉴의 ‘다시 연결’을 이용하거나 원본을 다시 가져와 주세요.") }
                return
            }
            // The project stages its own copy from the granted reference; the external original is only read.
            startLocalMediaTrailer(listOf(Uri.parse(live.localFilePath)))
            return
        }
        val file = File(live.localFilePath)
        if (!file.exists() || file.length() <= 0L) {
            _state.update { it.copy(message = "영상 파일을 찾을 수 없습니다. 메뉴의 ‘다시 연결’을 이용하거나 직접 삭제해 주세요.") }
            return
        }
        val uri = Uri.fromFile(file)
        startLocalMediaTrailer(listOf(uri))
    }

    fun openPhoneHardLongTouch() {
        if (_state.value.trailer != null) return
        val drafts = TrailerDraftStore.getAllDrafts()
        _state.update { it.copy(trailerDraftPicker = drafts) }
    }

    fun dismissTrailerDraftPicker() {
        _state.update { it.copy(trailerDraftPicker = null) }
    }

    fun dismissTrailerDraftStaleNotice() {
        _state.update { it.copy(trailerDraftStaleNotice = null) }
    }

    fun dismissTrailerConflict() {
        _state.update { it.copy(trailerConflict = null) }
    }

    private fun updateTrailer(directory: File, transform: (TrailerSession) -> TrailerSession) {
        _state.update { current ->
            val session = current.trailer?.takeIf { it.directory == directory } ?: return@update current
            current.copy(trailer = transform(session))
        }
    }

    fun previewInboxFile(item: InboxDisplayItem) {
        stopInlineAudio()
        val supported = _state.value.inboxFiles.mapNotNull { candidate ->
            val remote = candidate.asPreviewItem()
            if (remote.builtInPreviewKind() == null) null else remote to candidate.file
        }
        val index = supported.indexOfFirst { (remote, _) -> remote.id == item.id.toString() }
        if (index >= 0) {
            prepareLocalPreview(supported.map { it.first }, supported.map { it.second }, index)
        } else {
            val remote = item.asPreviewItem()
            runCatching { launchPreparedFile(remote, item.file, RemoteFileAction.PREVIEW) }
                .onFailure { error ->
                    _state.update { it.copy(message = null, inboxErrorMessage = error.message ?: "파일을 열 수 없습니다.") }
                }
        }
    }

    private fun InboxDisplayItem.asPreviewItem() = RemoteFileItem(
        id = id.toString(),
        name = originalFilename,
        path = id.toString(),
        isDirectory = false,
        size = byteCount,
        modifiedAt = importedAt,
        mimeType = mimeType,
    )

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
                    message = if (failed == 0) "${deleted}개 파일을 폰하드에서 삭제했습니다." else null,
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
                Intent.createChooser(sendIntent, "폰하드 파일 공유")
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
                Intent.createChooser(sendIntent, "폰하드 파일 공유")
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
            available[id] ?: throw IllegalArgumentException("선택한 폰하드 파일을 찾을 수 없습니다.")
        }
    }

    // Keeps the original display name and extension: provider name, then file-URI/path segment, then a generic label;
    // the MIME-derived extension is appended only when the resolved name has none.
    private fun copyDisplayName(uri: Uri, index: Int): String {
        val base = queryName(uri)?.takeIf { it.isNotBlank() }
            ?: uri.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "폰하드 파일 ${index + 1}"
        if (base.substringAfterLast('.', "").isNotEmpty()) return base
        val extension = runCatching { application.contentResolver.getType(uri) }.getOrNull()
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?.takeIf { it.isNotBlank() } ?: return base
        return "$base.$extension"
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
    private val phoneHardDurationCache = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var durationPrefetchJob: Job? = null

    private fun durationCacheKey(idOrPath: String, modifiedAt: Long, byteCount: Long): String =
        "$idOrPath:$modifiedAt:$byteCount"

    private fun extractMediaDuration(file: File): Long? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
        } finally {
            retriever.release()
        }
    }.getOrNull()

    private fun prefetchInboxDurations(items: List<InboxDisplayItem>) {
        val candidates = items.filter { item ->
            val kind = BuiltInPreviewPolicy.kind(item.originalFilename, item.mimeType)
            (kind == BuiltInPreviewKind.VIDEO || kind == BuiltInPreviewKind.AUDIO) &&
                !phoneHardDurationCache.containsKey(durationCacheKey(item.id.toString(), item.file.lastModified(), item.byteCount))
        }
        if (candidates.isEmpty()) return

        durationPrefetchJob?.cancel()
        durationPrefetchJob = viewModelScope.launch(Dispatchers.IO) {
            var anyChanged = false
            for (item in candidates) {
                if (coroutineContext[Job]?.isActive != true) break
                val key = durationCacheKey(item.id.toString(), item.file.lastModified(), item.byteCount)
                val dur = extractMediaDuration(item.file)
                phoneHardDurationCache[key] = dur ?: -1L
                if (dur != null) anyChanged = true
            }
            if (anyChanged && coroutineContext[Job]?.isActive != false) {
                _state.update { current ->
                    current.copy(
                        inboxFiles = current.inboxFiles.map { file ->
                            if (file.durationMillis == null) {
                                val key = durationCacheKey(file.id.toString(), file.file.lastModified(), file.byteCount)
                                val cached = phoneHardDurationCache[key]
                                if (cached != null && cached > 0L) file.copy(durationMillis = cached) else file
                            } else file
                        }
                    )
                }
            }
        }
    }

    private fun inboxFiles(): List<InboxDisplayItem> {
        val loaded = inboxStore.records()
            .sortedWith(compareByDescending<com.armsone.nasfinder.data.SharedInboxRecord> { it.importedAt }.thenBy { it.originalFilename })
            .mapNotNull { record ->
                runCatching {
                    val file = inboxStore.file(record)
                    val kind = BuiltInPreviewPolicy.kind(record.originalFilename, record.mimeType)
                    val isMedia = kind == BuiltInPreviewKind.VIDEO || kind == BuiltInPreviewKind.AUDIO
                    val key = durationCacheKey(record.id.toString(), file.lastModified(), record.byteCount)
                    val cachedDuration = if (isMedia) phoneHardDurationCache[key]?.takeIf { it > 0L } else null
                    InboxDisplayItem(
                        id = record.id,
                        originalFilename = record.originalFilename,
                        mimeType = record.mimeType,
                        byteCount = record.byteCount,
                        importedAt = record.importedAt,
                        file = file,
                        relativePath = record.storedFilename,
                        durationMillis = cachedDuration,
                    )
                }.getOrNull()
            }
        prefetchInboxDurations(loaded)
        return loaded
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

    fun loadPhoneHardFolder(subpath: String? = null): List<com.armsone.nasfinder.data.PhoneHardItem> {
        return runCatching {
            inboxStore.listPhoneHardFolder(subpath)
        }.getOrElse { emptyList() }
    }

    /** 미디어 소스 탐색 전용: 실패를 빈 목록으로 숨기지 않고 Result로 돌려준다. */
    fun loadPhoneHardFolderResult(subpath: String?): Result<List<com.armsone.nasfinder.data.PhoneHardItem>> {
        return try {
            Result.success(inboxStore.listPhoneHardFolder(subpath))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun createPhoneHardFolder(name: String, parentSubdirectory: String?, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                inboxStore.createFolder(name, parentSubdirectory)
            }
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    refreshInbox()
                    onResult(true, null)
                } else {
                    onResult(false, result.exceptionOrNull()?.message ?: "폴더 생성에 실패했습니다.")
                }
            }
        }
    }

    fun movePhoneHardTargets(
        targets: List<com.armsone.nasfinder.data.PhoneHardTarget>,
        destinationSubdirectory: String?,
        onResult: (com.armsone.nasfinder.data.PhoneHardOperationResult) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = inboxStore.moveTargets(targets, destinationSubdirectory)
            var updated = _state.value.remoteFavorites
            for (mapping in res.pathMappings) {
                val src = mapping.sourcePath.trim().removePrefix("/").removeSuffix("/")
                val dst = mapping.destinationPath.trim().removePrefix("/").removeSuffix("/")
                updated = updated.map { fav ->
                    if (fav.connectionId == "phonehard") {
                        if (fav.path == src) fav.copy(path = dst)
                        else if (mapping.isDirectory && fav.path.startsWith("$src/")) {
                            fav.copy(path = "$dst/${fav.path.removePrefix("$src/")}")
                        } else fav
                    } else fav
                }
            }
            if (updated != _state.value.remoteFavorites) {
                favoriteRepository.saveRemote(updated)
            }
            withContext(Dispatchers.Main) {
                _state.update { it.copy(remoteFavorites = updated) }
                refreshInbox()
                onResult(res)
            }
        }
    }

    fun copyPhoneHardTargets(
        targets: List<com.armsone.nasfinder.data.PhoneHardTarget>,
        destinationSubdirectory: String?,
        onResult: (com.armsone.nasfinder.data.PhoneHardOperationResult) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = inboxStore.copyTargets(targets, destinationSubdirectory)
            withContext(Dispatchers.Main) {
                refreshInbox()
                onResult(res)
            }
        }
    }

    fun deletePhoneHardTargets(
        targets: List<com.armsone.nasfinder.data.PhoneHardTarget>,
        onResult: (com.armsone.nasfinder.data.PhoneHardOperationResult) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = inboxStore.deleteTargets(targets)
            val removedSet = if (res.removedPaths.isNotEmpty()) {
                res.removedPaths.map { it.trim().removePrefix("/").removeSuffix("/") }.toSet()
            } else {
                res.succeeded.map { it.relativePath.trim().removePrefix("/").removeSuffix("/") }.toSet()
            }
            val remaining = _state.value.remoteFavorites.filterNot { fav ->
                fav.connectionId == "phonehard" && removedSet.any { dp -> fav.path == dp || fav.path.startsWith("$dp/") }
            }
            if (remaining.size != _state.value.remoteFavorites.size) {
                favoriteRepository.saveRemote(remaining)
            }
            withContext(Dispatchers.Main) {
                _state.update { it.copy(remoteFavorites = remaining) }
                refreshInbox()
                onResult(res)
            }
        }
    }

    fun downloadRemoteItems(
        connection: RemoteConnection,
        items: List<RemoteFileItem>,
        destination: NetworkDownloadDestination,
    ) {
        if (items.isEmpty()) return
        networkDownloadJob?.cancel()
        // 시작하는 순간의 출처를 고정한다(프라이버시 모드, 비공개 연결, 비공개 경로의 항목).
        val progressPrivate = PrivacyMode.capturedOrigin(
            connection.isPrivate || PrivacyMode.isPrivateConnection(connection.id) ||
                items.any { PrivacyMode.isPrivateOrigin(connection.id, it.path) },
        )
        _state.update {
            it.copy(
                isDownloadingNetwork = true,
                networkDownloadProgress = NetworkDownloadProgress(
                    originPrivate = progressPrivate,
                    currentFileName = "",
                    completedFiles = 0,
                    totalFiles = items.size,
                    currentFileBytes = 0L,
                    currentFileTotalBytes = 0L,
                    totalBytesDownloaded = 0L,
                    totalBytesExpected = 0L,
                ),
            )
        }
        // Started lazily so the job is registered before its own cleanup can compare against it.
        val job = viewModelScope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val ownJob = coroutineContext[Job]
            var result: NetworkDownloadResult = NetworkDownloadResult.Cancelled(0, 0L)
            try {
                result = runCatching {
                    withDedicatedRemoteService(connection) { ownService ->
                        networkDownloadCoordinator.download(
                            service = ownService,
                            items = items,
                            destination = destination,
                            onProgress = { progress ->
                                if (ownJob?.isActive == true) _state.update { it.copy(networkDownloadProgress = progress.copy(originPrivate = progressPrivate)) }
                            },
                            // 시작 순간에 고정한 값을 그대로 쓴다(코디네이터가 현재 모드를 다시 읽지 않는다).
                            originPrivate = progressPrivate,
                        )
                    }
                }.getOrElse { e ->
                    if (e is CancellationException) {
                        NetworkDownloadResult.Cancelled(0, 0L)
                    } else {
                        NetworkDownloadResult.Failure(e.message ?: "다운로드 중 오류가 발생했습니다.", e)
                    }
                }
            } finally {
                // A cancelled job must still surface the files it already committed.
                withContext(NonCancellable + Dispatchers.Main) {
                    if (destination is NetworkDownloadDestination.PhoneHard) {
                        refreshInbox()
                    }
                    // A newer download owns the progress fields once it has replaced this job.
                    if (networkDownloadJob === ownJob) {
                        networkDownloadJob = null
                        // 비공개로 시작한 작업의 결과가 일반 모드에서 끝나면 이름·경로·오류 세부를 전역 메시지에 싣지 않는다.
                        // 결과(성공/실패/취소/건너뜀)는 그대로 알리고, 작업은 취소·삭제하지 않는다.
                        val maskPrivate = progressPrivate && !PrivacyMode.isActive
                        val msg = when (val finished = result) {
                            is NetworkDownloadResult.Success ->
                                if (maskPrivate) "비공개 다운로드를 완료했습니다. 프라이버시 모드에서 확인해 주세요." else finished.message
                            is NetworkDownloadResult.Failure ->
                                if (maskPrivate) "비공개 다운로드에 실패했습니다. 프라이버시 모드에서 다시 확인해 주세요." else finished.message
                            is NetworkDownloadResult.Cancelled ->
                                if (maskPrivate) "비공개 다운로드가 취소되었습니다." else finished.message
                            is NetworkDownloadResult.Skipped ->
                                if (maskPrivate) "비공개 다운로드에서 처리하지 않은 항목이 있습니다. 프라이버시 모드에서 확인해 주세요." else finished.reason
                        }
                        _state.update {
                            it.copy(
                                isDownloadingNetwork = false,
                                networkDownloadProgress = null,
                                message = msg,
                            )
                        }
                    }
                }
            }
        }
        networkDownloadJob = job
        job.start()
    }

    fun cancelNetworkDownload() {
        networkDownloadJob?.cancel()
        _state.update {
            it.copy(
                isDownloadingNetwork = false,
                networkDownloadProgress = null,
                message = "사용자에 의해 다운로드가 취소되었습니다.",
            )
        }
    }

    fun startRemoteFolderMusicOverlay(folder: RemoteFileItem, includeSubfolders: Boolean = false) {
        if (!folder.isDirectory) return
        val browser = _state.value.screen as? Screen.Browser ?: return
        startRemoteFolderMusicOverlay(folder, includeSubfolders, browser.connection, browser.preferences, owner = null)
    }

    /** Same ownership rule as the folder trailer: a held [owner] lease covers listing and staging, then ends. */
    private fun startRemoteFolderMusicOverlay(
        folder: RemoteFileItem,
        includeSubfolders: Boolean,
        activeConnection: RemoteConnection,
        preferencesSnapshot: BrowserPreferences,
        owner: FavoriteActionServiceOwner?,
    ) {
        if (!folder.isDirectory) {
            owner?.let(::releaseFavoriteOwner)
            return
        }
        viewModelScope.launch {
            var stagingOwner = owner
            try {
                _state.update { it.copy(isBusy = true, message = null) }
                val (mediaCandidates, _) = withContext(Dispatchers.IO) {
                    try {
                        val activeService = owner?.service ?: browserServiceFor(activeConnection)
                        val media = mutableListOf<RemoteFileItem>()
                        var nonMediaCount = 0
                        if (includeSubfolders) {
                            val queue = java.util.ArrayDeque<String>()
                            val visitedPaths = mutableSetOf<String>()
                            queue.add(folder.path)
                            visitedPaths.add(folder.path.trimEnd('/'))
                            while (queue.isNotEmpty()) {
                                val currentPath = queue.removeFirst()
                                val entries = activeService.list(currentPath)
                                for (entry in entries) {
                                    if (entry.name.isBlank() || entry.name == "." || entry.name == "..") continue
                                    if (!preferencesSnapshot.showHiddenFiles && entry.name.startsWith('.')) continue
                                    val normPath = entry.path.trimEnd('/')
                                    if (entry.isDirectory) {
                                        if (normPath != currentPath.trimEnd('/') && visitedPaths.add(normPath)) {
                                            queue.add(entry.path)
                                        }
                                    } else if (entry.isVideo || entry.isImage) {
                                        media.add(entry)
                                    } else {
                                        nonMediaCount++
                                    }
                                }
                            }
                        } else {
                            val entries = activeService.list(folder.path)
                            for (entry in entries) {
                                if (entry.name.isBlank() || entry.name == "." || entry.name == "..") continue
                                if (!preferencesSnapshot.showHiddenFiles && entry.name.startsWith('.')) continue
                                if (entry.isDirectory) continue
                                if (entry.isVideo || entry.isImage) {
                                    media.add(entry)
                                } else {
                                    nonMediaCount++
                                }
                            }
                        }
                        media to nonMediaCount
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        emptyList<RemoteFileItem>() to 0
                    }
                }
                _state.update { it.copy(isBusy = false) }
                if (mediaCandidates.isEmpty()) {
                    _state.update { it.copy(message = "폴더에 음악을 입힐 사진이나 영상이 없습니다.") }
                    return@launch
                }
                if (owner == null) {
                    startRemoteMusicOverlay(mediaCandidates, null)
                } else {
                    stagingOwner = null
                    startRemoteMusicOverlay(mediaCandidates, null, activeService = owner.service, owner = owner)
                }
            } finally {
                stagingOwner?.let(::releaseFavoriteOwner)
            }
        }
    }

    fun startInboxFolderTrailer(folderRelativePath: String, includeSubfolders: Boolean = false) {
        val norm = folderRelativePath.trim().removePrefix("/").removeSuffix("/")
        val candidates = _state.value.inboxFiles.filter { item ->
            val itemRel = item.relativePath.trim().removePrefix("/").removeSuffix("/")
            val parent = itemRel.substringBeforeLast('/', "")
            val inFolder = if (includeSubfolders) itemRel.startsWith("$norm/") else parent == norm
            inFolder && (item.isTrailerVideo || BuiltInPreviewPolicy.kind(item.originalFilename, item.mimeType) in setOf(BuiltInPreviewKind.VIDEO, BuiltInPreviewKind.IMAGE))
        }
        if (candidates.isEmpty()) {
            _state.update { it.copy(message = "폴더에 재생 가능한 사진이나 영상이 없습니다.") }
            return
        }
        startInboxTrailer(candidates.map { it.id })
    }

    fun startInboxFolderMusicOverlay(folderRelativePath: String, includeSubfolders: Boolean = false) {
        val norm = folderRelativePath.trim().removePrefix("/").removeSuffix("/")
        val candidates = _state.value.inboxFiles.filter { item ->
            val itemRel = item.relativePath.trim().removePrefix("/").removeSuffix("/")
            val parent = itemRel.substringBeforeLast('/', "")
            val inFolder = if (includeSubfolders) itemRel.startsWith("$norm/") else parent == norm
            inFolder && (item.isTrailerVideo || BuiltInPreviewPolicy.kind(item.originalFilename, item.mimeType) in setOf(BuiltInPreviewKind.VIDEO, BuiltInPreviewKind.IMAGE))
        }
        if (candidates.isEmpty()) {
            _state.update { it.copy(message = "폴더에 음악을 입힐 사진이나 영상이 없습니다.") }
            return
        }
        startInboxMusicOverlay(candidates.map { it.id }, null)
    }

    fun hasInboxSubfolders(folderRelativePath: String): Boolean {
        val norm = folderRelativePath.trim().removePrefix("/").removeSuffix("/")
        val subItems = loadPhoneHardFolder(norm)
        return subItems.any { it.kind == com.armsone.nasfinder.data.PhoneHardEntryKind.DIRECTORY }
    }

    fun renamePhoneHardItem(relativePath: String, newName: String, onResult: (Boolean, String?) -> Unit) {
        val trimmedName = newName.trim()
        if (trimmedName.isBlank() || trimmedName == "." || trimmedName == ".." || '/' in trimmedName || '\\' in trimmedName) {
            onResult(false, "올바르지 않은 이름입니다.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val res = inboxStore.renamePhoneHardTarget(relativePath, trimmedName)
            if (res.isSuccess) {
                var updated = _state.value.remoteFavorites
                for (mapping in res.pathMappings) {
                    val src = mapping.sourcePath.trim().removePrefix("/").removeSuffix("/")
                    val dst = mapping.destinationPath.trim().removePrefix("/").removeSuffix("/")
                    val newNameOnly = dst.substringAfterLast('/')
                    updated = updated.map { fav ->
                        if (fav.connectionId == "phonehard") {
                            if (fav.path == src) fav.copy(path = dst, name = newNameOnly)
                            else if (mapping.isDirectory && fav.path.startsWith("$src/")) {
                                fav.copy(path = "$dst/${fav.path.removePrefix("$src/")}")
                            } else fav
                        } else fav
                    }
                }
                if (updated != _state.value.remoteFavorites) {
                    favoriteRepository.saveRemote(updated)
                }
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(remoteFavorites = updated) }
                    refreshInbox()
                    onResult(true, null)
                }
            } else {
                val err = res.failed.firstOrNull()?.second ?: "이름 변경에 실패했습니다."
                withContext(Dispatchers.Main) {
                    onResult(false, err)
                }
            }
        }
    }

    fun exportPhoneHardTargetsToExternalTree(
        targets: List<com.armsone.nasfinder.data.PhoneHardTarget>,
        treeUri: Uri,
        onResult: (NetworkDownloadResult) -> Unit,
    ) {
        // 요청 순간의 보기 범위를 고정해 코디네이터의 개수 산정·복사 전체에 같은 값을 쓴다.
        val privateVisible = PrivacyMode.isActive
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                networkDownloadCoordinator.exportPhoneHardTargetsToExternalTree(targets, treeUri, privateVisible = privateVisible)
            }.getOrElse { e ->
                if (e is CancellationException) NetworkDownloadResult.Cancelled(0, 0L)
                else NetworkDownloadResult.Failure(e.message ?: "외부 저장소 내보내기 실패", e)
            }
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }

    fun clearInitialInboxSubpath() {
        _state.update { it.copy(initialInboxSubpath = null) }
    }

    fun isPhoneHardFavorite(relativePath: String): Boolean {
        val norm = relativePath.trim().removePrefix("/").removeSuffix("/")
        return _state.value.remoteFavorites.any { it.connectionId == "phonehard" && it.path == norm }
    }

    fun togglePhoneHardFavorite(
        relativePath: String,
        name: String,
        isDirectory: Boolean,
        id: String? = null,
        size: Long? = null,
        modifiedAt: java.time.Instant? = null,
        mimeType: String? = null,
    ) {
        val norm = relativePath.trim().removePrefix("/").removeSuffix("/")
        val favorites = favoriteRepository.toggleRemote(
            RemoteFavorite(
                connectionId = "phonehard",
                path = norm,
                name = name,
                isDirectory = isDirectory,
                remoteItemId = id ?: norm,
                size = size,
                modifiedAt = modifiedAt,
                mimeType = mimeType,
                thumbnailUrl = null,
            )
        )
        _state.update { it.copy(remoteFavorites = favorites) }
        invalidateFavoriteThumbnails()
    }

    fun sharePhoneHardTargets(folderPaths: List<String>, fileIds: List<UUID>) {
        if (folderPaths.isEmpty() && fileIds.isEmpty()) return
        if (folderPaths.isEmpty()) {
            val toShare = _state.value.inboxFiles.filter { it.id in fileIds }
            if (toShare.isEmpty()) return
            if (toShare.size == 1) shareInboxFile(toShare.first())
            else shareInboxFiles(fileIds)
            return
        }
        // 요청 순간의 보기 범위를 고정한다: 비동기 압축 중 모드가 바뀌어도 포함 범위가 중간에 달라지지 않는다.
        val privateVisible = PrivacyMode.isActive
        viewModelScope.launch(Dispatchers.IO) {
            val sharesDir = File(application.cacheDir, "shares").apply { mkdirs() }
            val uniqueZipName = "phonehard_share_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}.zip"
            val zipFile = File(sharesDir, uniqueZipName)
            try {
                val maxDepth = DefaultNetworkDownloadCoordinator.MAX_REMOTE_DIRECTORY_DEPTH
                val rootCanonical = inboxStore.rootDirectory.canonicalFile
                val rootPath = rootCanonical.path.trimEnd(File.separatorChar)
                fun isInsidePhoneHard(file: File) = file.path.startsWith("$rootPath${File.separator}")
                val inboxSnapshot = _state.value.inboxFiles
                val displayNames = inboxSnapshot.associate { it.file.canonicalPath to it.originalFilename }

                val folders = folderPaths.map { folderPath ->
                    val norm = folderPath.trim().removePrefix("/").removeSuffix("/")
                    val requested = if (norm.isEmpty()) rootCanonical else File(rootCanonical, norm)
                    if (java.nio.file.Files.isSymbolicLink(requested.toPath())) {
                        throw java.io.IOException("심볼릭 링크 폴더는 공유할 수 없습니다: $norm")
                    }
                    val folderFile = requested.canonicalFile
                    if (folderFile.path != rootPath && !isInsidePhoneHard(folderFile)) {
                        throw java.io.IOException("폰하드 밖의 폴더는 공유할 수 없습니다: $norm")
                    }
                    if (!folderFile.isDirectory) throw java.io.IOException("공유할 폴더를 찾을 수 없습니다: $norm")
                    folderFile
                }.distinctBy { it.path }
                // A selection nested inside another selected folder is already covered by its ancestor.
                val topFolders = folders.filter { candidate ->
                    folders.none { it.path != candidate.path && candidate.path.startsWith("${it.path}${File.separator}") }
                }
                val topFiles = fileIds.distinct().map { fileId ->
                    val fileItem = inboxSnapshot.firstOrNull { it.id == fileId }
                        ?: throw java.io.IOException("선택한 파일을 폰하드에서 찾을 수 없습니다.")
                    if (java.nio.file.Files.isSymbolicLink(fileItem.file.toPath())) {
                        throw java.io.IOException("심볼릭 링크는 공유할 수 없습니다: ${fileItem.originalFilename}")
                    }
                    val canonical = fileItem.file.canonicalFile
                    if (!isInsidePhoneHard(canonical) || !canonical.isFile) {
                        throw java.io.IOException("공유할 파일을 찾을 수 없습니다: ${fileItem.originalFilename}")
                    }
                    canonical to fileItem.originalFilename
                }.filter { (file, _) ->
                    topFolders.none { it.path == rootPath || file.path.startsWith("${it.path}${File.separator}") }
                }

                var entryCount = 0
                java.util.zip.ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                    val usedNames = mutableSetOf<String>()
                    fun addFile(file: File, entryName: String) {
                        zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                        file.inputStream().buffered().use { input -> input.copyTo(zos) }
                        zos.closeEntry()
                        entryCount++
                    }
                    fun addFolder(folder: File, prefix: String, depth: Int, ancestors: Set<String>) {
                        ensureActive()
                        if (depth > maxDepth) {
                            throw java.io.IOException("폴더가 ${maxDepth}단계보다 깊어 공유할 수 없습니다: ${folder.name}")
                        }
                        if (folder.path in ancestors) {
                            throw java.io.IOException("같은 폴더가 반복되는 구조여서 공유할 수 없습니다: ${folder.name}")
                        }
                        // Every folder gets its own entry so empty ones survive.
                        zos.putNextEntry(java.util.zip.ZipEntry(prefix))
                        zos.closeEntry()
                        entryCount++
                        val children = folder.listFiles()
                            ?: throw java.io.IOException("폴더를 읽을 수 없습니다: ${folder.name}")
                        val nextAncestors = ancestors + folder.path
                        for (child in children.sortedBy { it.name }) {
                            if (java.nio.file.Files.isSymbolicLink(child.toPath())) {
                                throw java.io.IOException("심볼릭 링크가 들어 있는 폴더는 공유할 수 없습니다: ${child.name}")
                            }
                            val canonical = child.canonicalFile
                            if (!isInsidePhoneHard(canonical)) {
                                throw java.io.IOException("폰하드 밖을 가리키는 항목은 공유할 수 없습니다: ${child.name}")
                            }
                            // 일반 모드에서 폴더를 통째로 공유할 때 보이지 않는 비공개 하위 항목은 함께 나가지 않는다.
                            if (!privateVisible && PrivacyMode.isPhoneHardAbsolutePathPrivate(canonical.path)) continue
                            when {
                                canonical.isDirectory ->
                                    addFolder(canonical, reserveZipEntryName(usedNames, prefix, child.name) + "/", depth + 1, nextAncestors)
                                canonical.isFile ->
                                    addFile(canonical, reserveZipEntryName(usedNames, prefix, displayNames[canonical.path] ?: child.name))
                                else -> throw java.io.IOException("공유할 수 없는 항목이 있습니다: ${child.name}")
                            }
                        }
                    }
                    for (folderFile in topFolders) {
                        addFolder(folderFile, reserveZipEntryName(usedNames, "", folderFile.name) + "/", 0, emptySet())
                    }
                    for ((file, displayName) in topFiles) {
                        ensureActive()
                        addFile(file, reserveZipEntryName(usedNames, "", displayName))
                    }
                }
                if (entryCount == 0) {
                    zipFile.delete()
                    withContext(Dispatchers.Main) {
                        _state.update { it.copy(inboxErrorMessage = "공유할 항목이 없습니다.") }
                    }
                    return@launch
                }
                val sendIntent = NasFinderShareIntentFactory.create(application, listOf(zipFile))
                withContext(Dispatchers.Main) {
                    application.startActivity(
                        Intent.createChooser(sendIntent, "항목 압축 파일 공유")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            } catch (cancelled: CancellationException) {
                zipFile.delete()
                throw cancelled
            } catch (e: Exception) {
                zipFile.delete()
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(inboxErrorMessage = e.message ?: "항목을 공유하지 못했습니다.") }
                }
            }
        }
    }

    fun shareInboxFolder(relativePath: String) {
        sharePhoneHardTargets(listOf(relativePath), emptyList())
    }

    private var localThumbnailJob: Job? = null

    fun startLocalThumbnailGeneration(
        target: com.armsone.nasfinder.data.PhoneHardTarget,
        onProgress: ((Int, Int) -> Unit)? = null,
        onComplete: ((Int) -> Unit)? = null,
    ) {
        if (localThumbnailJob?.isActive == true) {
            cancelLocalThumbnailGeneration()
            return
        }
        // 요청 순간의 보기 범위를 고정한다(생성 도중 모드가 바뀌어도 대상 범위가 달라지지 않는다).
        val privateVisible = PrivacyMode.isActive
        _state.update { it.copy(isLocalThumbnailRunning = true) }
        // Started lazily so the job is registered before its own cleanup can compare against it.
        val job = viewModelScope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val ownJob = coroutineContext[Job]
            try {
                val rootCanonical = inboxStore.rootDirectory.canonicalFile
                val norm = target.relativePath.trim().removePrefix("/").removeSuffix("/")
                val targetFile = if (norm.isEmpty()) rootCanonical else File(rootCanonical, norm).canonicalFile

                val mediaFiles = if (target.isDirectory) {
                    targetFile.walkTopDown()
                        // 일반 모드에서는 보이지 않는 비공개 하위 파일의 썸네일을 만들지 않는다.
                        .filter { privateVisible || !PrivacyMode.isPhoneHardAbsolutePathPrivate(it.path) }
                        .filter { it.isFile && (isPhoneHardImage(it.name) || isPhoneHardVideo(it.name) || it.name.endsWith(".pdf", ignoreCase = true)) }
                        .toList()
                } else {
                    if (targetFile.isFile && (isPhoneHardImage(targetFile.name) || isPhoneHardVideo(targetFile.name) || targetFile.name.endsWith(".pdf", ignoreCase = true))) {
                        listOf(targetFile)
                    } else emptyList()
                }

                if (mediaFiles.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        _state.update { it.copy(isLocalThumbnailRunning = false, inboxErrorMessage = "사진 또는 영상 파일이 없습니다.") }
                    }
                    return@launch
                }

                val total = mediaFiles.size
                var generatedCount = 0
                val thumbDir = File(application.cacheDir, "inbox_thumbs").apply { mkdirs() }
                for ((index, file) in mediaFiles.withIndex()) {
                    ensureActive()
                    withContext(Dispatchers.Main) {
                        _state.update { it.copy(localThumbnailProgress = (index + 1) to total) }
                        onProgress?.invoke(index + 1, total)
                    }
                    val isImage = isPhoneHardImage(file.name)
                    val isVideo = isPhoneHardVideo(file.name)
                    val isPdf = file.name.endsWith(".pdf", ignoreCase = true)
                    val uri = runCatching {
                        androidx.core.content.FileProvider.getUriForFile(application, "${application.packageName}.sharefiles", file)
                    }.getOrNull()

                    val baseBitmap = decodeInboxThumbnail(application, uri, file, isImage, isVideo, isPdf, 512)
                    if (baseBitmap != null) {
                        val maxDim = maxOf(baseBitmap.width, baseBitmap.height).coerceAtLeast(1)
                        for (targetSize in listOf(112, 240, 420, 512)) {
                            val scaled = if (targetSize >= maxDim || targetSize == 512) {
                                baseBitmap
                            } else {
                                val scale = targetSize.toFloat() / maxDim
                                val w = (baseBitmap.width * scale).toInt().coerceAtLeast(1)
                                val h = (baseBitmap.height * scale).toInt().coerceAtLeast(1)
                                android.graphics.Bitmap.createScaledBitmap(baseBitmap, w, h, true)
                            }
                            val key = "${file.canonicalPath}:${file.lastModified()}:${file.length()}:$targetSize"
                            InboxThumbnailMemoryCache.put(key, scaled)
                            val diskThumbFile = File(thumbDir, "${file.canonicalPath.hashCode()}_${file.lastModified()}_${file.length()}_$targetSize.png")
                            val tmp = File(thumbDir, "${diskThumbFile.name}.tmp")
                            runCatching {
                                tmp.outputStream().buffered().use { out ->
                                    scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out)
                                }
                                tmp.renameTo(diskThumbFile)
                            }
                        }
                        generatedCount++
                    }
                }
                withContext(Dispatchers.Main) {
                    invalidateFavoriteThumbnails()
                    refreshInbox()
                    _state.update { it.copy(isLocalThumbnailRunning = false, localThumbnailProgress = null) }
                    onComplete?.invoke(generatedCount)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(isLocalThumbnailRunning = false, localThumbnailProgress = null, inboxErrorMessage = "썸네일 생성 중 오류: ${e.message}") }
                }
            } finally {
                // Only the job that still owns the slot may clear it; a restarted run keeps its own state.
                withContext(NonCancellable + Dispatchers.Main) {
                    if (localThumbnailJob === ownJob) {
                        localThumbnailJob = null
                        _state.update { it.copy(isLocalThumbnailRunning = false, localThumbnailProgress = null) }
                    }
                }
            }
        }
        localThumbnailJob = job
        job.start()
    }

    fun cancelLocalThumbnailGeneration() {
        localThumbnailJob?.cancel()
        localThumbnailJob = null
        _state.update { it.copy(isLocalThumbnailRunning = false, localThumbnailProgress = null) }
    }

    fun inlineRemoteAudioKey(connectionId: String, item: RemoteFileItem): String =
        "remote:$connectionId:${item.id}:${item.path}"

    fun inlineInboxAudioKey(item: InboxDisplayItem): String =
        "inbox:${item.id}"

    fun stopInlineAudio() {
        inlineAudioGeneration++
        inlineAudioStagingJob?.cancel()
        inlineAudioStagingJob = null
        inlineAudioProgressJob?.cancel()
        inlineAudioProgressJob = null
        inlineAudioPlayer?.let { player ->
            runCatching {
                player.stop()
                player.release()
            }
        }
        inlineAudioPlayer = null
        if (_state.value.inlineAudioPlayback != null) {
            _state.update { it.copy(inlineAudioPlayback = null) }
        }
    }

    fun toggleInlineRemoteAudio(item: RemoteFileItem) {
        if (item.isDirectory || !item.isAudio) return
        val browser = _state.value.screen as? Screen.Browser ?: return
        val connection = browser.connection
        val key = inlineRemoteAudioKey(connection.id, item)
        val current = _state.value.inlineAudioPlayback
        if (current != null && current.key == key) {
            handleActiveInlineAudioToggle(current)
            return
        }

        val activeService = service ?: run {
            _state.update { it.copy(message = "원격 연결을 다시 연 뒤 시도해 주세요.") }
            return
        }

        stopInlineAudio()
        val generation = ++inlineAudioGeneration
        val route = RemoteMediaRoutePolicy.route(item.name, activeService.supportsRangeStreaming && item.size > 0)
        if (route == RemoteMediaRoute.RANGE_MEDIA3) {
            _state.update {
                it.copy(
                    inlineAudioPlayback = InlineAudioPlaybackState(
                        key = key,
                        title = item.name,
                        isPreparing = true,
                    )
                )
            }
            val streamSpec = RemoteMediaStreamSpec(item, null, activeService)
            startInlineExoPlayer(
                generation = generation,
                key = key,
                title = item.name,
                configureSource = { player ->
                    player.setMediaSource(streamSpec.createMediaSource())
                },
            )
            return
        }

        _state.update {
            it.copy(
                inlineAudioPlayback = InlineAudioPlaybackState(
                    key = key,
                    title = item.name,
                    isPreparing = true,
                    downloadFraction = 0f,
                )
            )
        }
        inlineAudioStagingJob = viewModelScope.launch {
            val resolved = runCatching {
                downloadCache.resolve(
                    connectionId = connection.id,
                    item = item,
                    progress = { completed, reportedTotal ->
                        val total = reportedTotal.takeIf { it > 0 }
                            ?: item.size.takeIf { it > 0 }
                            ?: 0L
                        val fraction = if (total > 0L) {
                            (completed.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
                        } else null
                        if (generation == inlineAudioGeneration) {
                            _state.update { state ->
                                val active = state.inlineAudioPlayback
                                if (active == null || active.key != key) state
                                else state.copy(inlineAudioPlayback = active.copy(downloadFraction = fraction))
                            }
                        }
                    },
                    download = { destination, progress ->
                        activeService.download(item, destination, progress)
                    },
                )
            }
            if (generation != inlineAudioGeneration) return@launch
            resolved.onSuccess { file ->
                startInlineExoPlayer(
                    generation = generation,
                    key = key,
                    title = item.name,
                    configureSource = { player ->
                        player.setMediaItem(androidx.media3.common.MediaItem.fromUri(Uri.fromFile(file)))
                    },
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                stopInlineAudio()
                _state.update {
                    it.copy(message = error.message ?: "${item.name}을(를) 재생할 수 없습니다.")
                }
            }
        }
    }

    fun toggleInlineInboxAudio(item: InboxDisplayItem) {
        val isAudio = BuiltInPreviewPolicy.kind(item.originalFilename, item.mimeType) == BuiltInPreviewKind.AUDIO ||
            item.asPreviewItem().isAudio
        if (!isAudio) return
        val key = inlineInboxAudioKey(item)
        val current = _state.value.inlineAudioPlayback
        if (current != null && current.key == key) {
            handleActiveInlineAudioToggle(current)
            return
        }
        if (!item.file.isFile) {
            _state.update { it.copy(inboxErrorMessage = "폰하드 음악 파일을 찾을 수 없습니다: ${item.originalFilename}") }
            return
        }

        stopInlineAudio()
        val generation = ++inlineAudioGeneration
        _state.update {
            it.copy(
                inlineAudioPlayback = InlineAudioPlaybackState(
                    key = key,
                    title = item.originalFilename,
                    isPreparing = true,
                )
            )
        }
        startInlineExoPlayer(
            generation = generation,
            key = key,
            title = item.originalFilename,
            configureSource = { player ->
                player.setMediaItem(androidx.media3.common.MediaItem.fromUri(Uri.fromFile(item.file)))
            },
        )
    }

    private fun handleActiveInlineAudioToggle(current: InlineAudioPlaybackState) {
        if (current.isPreparing) {
            stopInlineAudio()
            return
        }
        val player = inlineAudioPlayer ?: run {
            stopInlineAudio()
            return
        }
        if (player.isPlaying) {
            player.pause()
            updateInlineAudioProgressFromPlayer(player, isPlayingOverride = false)
        } else {
            if (player.playbackState == androidx.media3.common.Player.STATE_ENDED) {
                player.seekTo(0L)
            }
            player.play()
            updateInlineAudioProgressFromPlayer(player, isPlayingOverride = true)
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun startInlineExoPlayer(
        generation: Long,
        key: String,
        title: String,
        configureSource: (androidx.media3.exoplayer.ExoPlayer) -> Unit,
    ) {
        if (generation != inlineAudioGeneration) return
        val player = runCatching {
            androidx.media3.exoplayer.ExoPlayer.Builder(application)
                .setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true,
                )
                .setHandleAudioBecomingNoisy(true)
                .build()
        }.getOrElse { error ->
            stopInlineAudio()
            _state.update { it.copy(message = error.message ?: "$title 재생기를 준비하지 못했습니다.") }
            return
        }

        inlineAudioPlayer = player
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (generation != inlineAudioGeneration) return
                when (playbackState) {
                    androidx.media3.common.Player.STATE_BUFFERING -> {
                        _state.update { state ->
                            val active = state.inlineAudioPlayback
                            if (active == null || active.key != key) state
                            else state.copy(inlineAudioPlayback = active.copy(isPreparing = true))
                        }
                    }
                    androidx.media3.common.Player.STATE_READY -> {
                        updateInlineAudioProgressFromPlayer(player, isPreparingOverride = false)
                    }
                    androidx.media3.common.Player.STATE_ENDED -> {
                        stopInlineAudio()
                    }
                    androidx.media3.common.Player.STATE_IDLE -> Unit
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (generation != inlineAudioGeneration) return
                updateInlineAudioProgressFromPlayer(player, isPlayingOverride = isPlaying)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (generation != inlineAudioGeneration) return
                stopInlineAudio()
                _state.update {
                    it.copy(message = "$title 파일을 재생할 수 없습니다.")
                }
            }
        })

        runCatching {
            configureSource(player)
            player.prepare()
            player.playWhenReady = true
        }.onFailure { error ->
            stopInlineAudio()
            _state.update { it.copy(message = error.message ?: "$title 파일을 재생할 수 없습니다.") }
            return
        }

        inlineAudioProgressJob?.cancel()
        inlineAudioProgressJob = viewModelScope.launch {
            while (generation == inlineAudioGeneration && inlineAudioPlayer === player) {
                updateInlineAudioProgressFromPlayer(player)
                kotlinx.coroutines.delay(150L)
            }
        }
    }

    private fun updateInlineAudioProgressFromPlayer(
        player: androidx.media3.exoplayer.ExoPlayer,
        isPlayingOverride: Boolean? = null,
        isPreparingOverride: Boolean? = null,
    ) {
        val duration = player.duration.takeIf { it > 0L } ?: 0L
        val position = player.currentPosition.coerceAtLeast(0L)
        val fraction = if (duration > 0L) {
            (position.toDouble() / duration.toDouble()).coerceIn(0.0, 1.0).toFloat()
        } else 0f
        val isBuffering = isPreparingOverride ?: (player.playbackState == androidx.media3.common.Player.STATE_BUFFERING)
        val playing = isPlayingOverride ?: player.isPlaying
        _state.update { state ->
            val active = state.inlineAudioPlayback ?: return@update state
            state.copy(
                inlineAudioPlayback = active.copy(
                    isPreparing = isBuffering,
                    downloadFraction = if (isBuffering) active.downloadFraction else null,
                    isPlaying = playing,
                    progress = fraction,
                    durationMillis = duration,
                    positionMillis = position,
                )
            )
        }
    }

    override fun onCleared() {
        stopInlineAudio()
        localThumbnailJob?.cancel()
        dismissTrailer()
        networkDownloadJob?.cancel()
        _state.value.pendingLocalUpload?.let { pending -> runCatching { cleanupOwnedWebDownload(pending.file) } }
        previewJob?.cancel()
        connectionOpenJob?.cancel()
        thumbnailTrafficObservation?.cancel()
        superThumbnailStartJob?.cancel()
        superThumbnailObservation?.cancel()
        superThumbnailPickerJob?.cancel()
        closeSuperThumbnailPickerService()
        homeFavoritePrepJob?.cancel()
        liveFavoriteOwners.toList().forEach { owner -> if (owner.forceClose()) runCatching { owner.service.close() } }
        liveFavoriteOwners.clear()
        service?.close()
        thumbnailRepository.close()
    }

    private companion object {
        // Access-type material failures; only these clear by themselves when the original becomes readable again.
        const val LOCAL_MATERIAL_ACCESS_ERROR = "미디어에 접근할 수 없습니다 (권한 필요)"
        const val LOCAL_MATERIAL_GRANT_ERROR = "미디어 접근 권한이 확인되지 않았습니다."
        const val MATERIAL_SAVE_ERROR = "작업대 정보를 저장하지 못했습니다. 저장 공간을 확인한 뒤 다시 시도해 주세요."
        const val POCKET_DELETE_ERROR = "포켓을 삭제하지 못했습니다. 저장에 실패해도 참조는 지워지지 않으며, 일부 항목이 ‘기존 재료’ 포켓에 표시될 수 있습니다. 원본 파일은 그대로입니다."
        const val POCKET_SAVE_ERROR = "포켓 정보를 저장하지 못했습니다. 기존 포켓과 항목은 그대로입니다. 저장 공간을 확인한 뒤 다시 시도해 주세요."
        const val MAX_THUMBNAIL_UI_ATTEMPTS = 3
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
            ?: return AppEntryRoute.Rejected("폰하드 파일 ID가 올바르지 않습니다.")
        return AppEntryRoute.Inbox(id)
    }

    private fun decode(value: String): String? = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrNull()
}


internal fun isPhoneHardImage(filename: String): Boolean {
    val ext = filename.substringAfterLast('.', "").lowercase()
    return ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
}

internal fun isPhoneHardVideo(filename: String): Boolean {
    val ext = filename.substringAfterLast('.', "").lowercase()
    return ext in setOf("mp4", "mkv", "mov", "webm", "avi", "3gp", "ts", "m4v")
}
