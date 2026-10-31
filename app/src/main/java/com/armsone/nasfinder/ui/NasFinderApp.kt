@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.armsone.nasfinder.ui

import com.armsone.nasfinder.ui.theme.LocalInterfaceIconPack

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.ImageDecoder
import android.graphics.Canvas
import android.graphics.Movie
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.widget.ImageView
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.accessibility.AccessibilityManager
import android.view.View
import androidx.annotation.RequiresApi
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.hypot
import kotlin.math.roundToInt
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.armsone.nasfinder.model.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import com.armsone.nasfinder.data.SuperThumbnailWorkStatus
import com.armsone.nasfinder.data.SuperThumbnailVaultTiming
import com.armsone.nasfinder.data.ScreenAwakeMode
import com.armsone.nasfinder.data.RemoteThumbnailCachePolicy
import com.armsone.nasfinder.BuildConfig
import com.armsone.nasfinder.platform.InboxBatchContracts
import com.armsone.nasfinder.platform.LauncherIconVariant
import com.armsone.nasfinder.platform.WebHardFileStore
import com.armsone.nasfinder.R
import com.armsone.nasfinder.ui.theme.NasFinderTheme
import com.armsone.nasfinder.ui.theme.LocalNasFinderTheme
import com.armsone.nasfinder.ui.theme.usesUnifiedLayout
import com.armsone.nasfinder.ui.theme.folderColor
import com.armsone.nasfinder.ui.theme.serviceColor
import com.armsone.nasfinder.ui.theme.serviceForegroundColor
import com.armsone.nasfinder.ui.theme.BrowserOrange
import com.armsone.nasfinder.ui.theme.paletteFor
import com.armsone.nasfinder.ui.theme.themePalette
import com.armsone.nasfinder.ui.theme.ThemeVectors
import com.armsone.nasfinder.ui.theme.LocalThemePalette
import com.armsone.nasfinder.ui.theme.fileMetaColor
import com.armsone.nasfinder.ui.theme.thumbnailBorderColor
import com.armsone.nasfinder.ui.theme.thumbnailSurfaceColor
import androidx.compose.ui.text.withStyle
import androidx.core.content.FileProvider
import android.net.Uri
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import com.armsone.nasfinder.data.PhoneHardItem
import com.armsone.nasfinder.data.PhoneHardTarget
import com.armsone.nasfinder.data.PhoneHardEntryKind
import com.armsone.nasfinder.ui.CreateFolderDialog
import com.armsone.nasfinder.ui.SelectPhoneHardDestinationDialog
import com.armsone.nasfinder.ui.NetworkDownloadDestination
import com.armsone.nasfinder.ui.NetworkDownloadProgress
import androidx.compose.foundation.horizontalScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Keep controls inside the system area even while a player hides the system bars.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Modifier.appSafeArea(): Modifier = windowInsetsPadding(
    WindowInsets.safeDrawing.union(WindowInsets.systemBarsIgnoringVisibility),
)

// App root: the background runs to the physical bottom edge. Each screen's bottom bar (or its Scaffold
// content padding) owns the navigation inset; only the keyboard inset is taken here, once for every screen.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Modifier.appRootSafeArea(): Modifier = windowInsetsPadding(
    WindowInsets.safeDrawing.union(WindowInsets.systemBarsIgnoringVisibility)
        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        .union(WindowInsets.ime),
)

@Composable
fun NasFinderApp(
    model: NasFinderViewModel,
    automaticUpdateDownloadEnabled: Boolean,
    onAutomaticUpdateDownloadChanged: (Boolean) -> Unit,
    onCheckForUpdates: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val privacyEpoch by com.armsone.nasfinder.data.PrivacyMode.epoch.collectAsStateWithLifecycle()
    var showBusyOverlay by remember { mutableStateOf(false) }
    LaunchedEffect(state.isBusy) {
        if (state.isBusy) {
            delay(180)
            showBusyOverlay = true
        } else {
            showBusyOverlay = false
        }
    }
    val hostView = LocalView.current
    val hasActiveWork = state.isBusy || state.download != null || state.networkDownloadProgress != null || state.isDownloadingNetwork ||
        state.pendingInboxUpload?.isUploading == true ||
        state.pendingLocalUpload?.isUploading == true ||
        state.inlineAudioPlayback?.isPlaying == true ||
        state.imagePreview?.kind in setOf(BuiltInPreviewKind.VIDEO, BuiltInPreviewKind.AUDIO) || state.trailer != null ||
        (state.screen == Screen.SuperThumbnailProgress && state.superThumbnailWork?.status in setOf(SuperThumbnailWorkStatus.WAITING, SuperThumbnailWorkStatus.RUNNING))
    val keepScreenOn = state.screenAwakeMode == ScreenAwakeMode.ALWAYS ||
        (state.screenAwakeMode == ScreenAwakeMode.AUTOMATIC && hasActiveWork)
    DisposableEffect(hostView, keepScreenOn) {
        hostView.keepScreenOn = keepScreenOn
        onDispose { if (hostView.keepScreenOn == keepScreenOn) hostView.keepScreenOn = false }
    }
    val webBrowserSession = remember { WebBrowserSessionController() }
    DisposableEffect(webBrowserSession) { onDispose(webBrowserSession::close) }
    val appLifecycleOwner = LocalLifecycleOwner.current
    val appContext = LocalContext.current
    DisposableEffect(appLifecycleOwner, model) {
        // Leaving the foreground (home, recents, another app, screen off) returns the next visit to
        // the dashboard. Configuration changes such as rotation or folding keep the current screen.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && appContext.findHostActivity()?.isChangingConfigurations != true) {
                model.returnToDashboardAfterBackground()
            }
        }
        appLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { appLifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BackHandler(enabled = state.imagePreview != null) {
        model.closeImagePreview()
    }
    BackHandler(enabled = state.imagePreview == null && state.screen != Screen.Dashboard) {
        model.show(Screen.Dashboard)
    }

    val mediaLibraryStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    // The system picker (files/Google 포토) can outlive this process; remember the source screen so a restart returns to it, not the dashboard.
    var libraryRouteRestored by remember { mutableStateOf(false) }
    var libraryRoute by rememberSaveable { mutableStateOf<List<String>?>(null) }
    if (state.screen is Screen.LocalPhotoLibrary) libraryRoute = (state.screen as Screen.LocalPhotoLibrary).let { listOf(it.initialIntent.name, it.googlePhotosSource.toString()) }
    else if (libraryRouteRestored) libraryRoute = null
    LaunchedEffect(Unit) {
        val route = libraryRoute
        if (route != null && state.screen == Screen.Dashboard) {
            val intent = runCatching { LocalMediaEditIntent.valueOf(route[0]) }.getOrDefault(LocalMediaEditIntent.NONE)
            model.show(Screen.LocalPhotoLibrary(intent, route.getOrNull(1) == "true"))
        }
        libraryRouteRestored = true
    }
    // Presentation-only flag reported by BrowserScreen while its existing CoverFlow is showing; the whole app screen is then used.
    var browserCoverFlowFullscreen by remember { mutableStateOf(false) }
    val coverFlowFullscreen = browserCoverFlowFullscreen && state.screen is Screen.Browser && state.imagePreview == null && state.trailer == null
    NasFinderTheme(state.theme, state.iconPack) {
        Box(
            Modifier.fillMaxSize()
                .then(if (coverFlowFullscreen) Modifier.background(Color.Black) else Modifier.background(skyBrush(state.theme)))
                .then(
                    when {
                        coverFlowFullscreen -> Modifier.windowInsetsPadding(
                            WindowInsets.safeDrawing.union(WindowInsets.systemBarsIgnoringVisibility).only(WindowInsetsSides.Horizontal),
                        )
                        state.imagePreview != null || (state.trailer != null && !state.trailerMinimized) -> Modifier
                        else -> Modifier.appRootSafeArea()
                    },
                ),
        ) {
            // 프라이버시 모드가 바뀌면 화면 트리를 새로 만든다: 열린 대화상자·시트·선택·탐색 상태가 비워져 숨겨진 내용이 남지 않는다.
            androidx.compose.runtime.key(privacyEpoch) {
            when (val screen = state.screen) {
                Screen.Dashboard -> DashboardScreen(state, model)
                is Screen.AddConnection -> ConnectionEditor(screen.editing, state, model)
                is Screen.Browser -> PorcelainWideHost(state, model, selectedConnectionId = screen.connection.id.toString(), fullscreen = coverFlowFullscreen) {
                    BrowserScreen(screen, state, model, onCoverFlowFullscreen = { browserCoverFlowFullscreen = it })
                }
                Screen.Inbox, Screen.WebHard -> PorcelainWideHost(state, model, phoneHardSelected = true) { InboxScreen(state, model) }
                // Wide windows keep the persistent navigation beside the content; the transfer content uses the right pane width only.
                Screen.PhotoTransfer -> PorcelainWideHost(state, model) {
                    PhotoTransferScreen(
                        state = state,
                        model = model,
                        onBack = { model.show(Screen.Dashboard) },
                    )
                }
                Screen.Favorites -> {
                    var pendingFavoriteRemoval by remember { mutableStateOf<RemoteFavorite?>(null) }
                    PorcelainWideHost(state, model, favoritesSelected = true) {
                        PorcelainFavoritesDestination(
                            state, model,
                            onRemoveFavorite = { pendingFavoriteRemoval = it },
                            onBack = { model.selectMainDestination(MainNavigationDestination.FINDER) },
                        )
                    }
                    pendingFavoriteRemoval?.let { favorite ->
                        FavoriteRemovalDialog(favorite, onDismiss = { pendingFavoriteRemoval = null }) {
                            pendingFavoriteRemoval = null
                            model.removeFavorite(favorite)
                        }
                    }
                    HomeFavoriteActionHost(state, model)
                }
                Screen.PhotoSelection -> PorcelainWideHost(state, model) {
                    PhotoSelectionScreen(
                        session = model.photoSelection,
                        onBack = { model.show(Screen.Dashboard) },
                        onShowMessage = model::showMessage,
                        pendingShares = state.pendingMediaShares,
                        onOpenPendingShares = model::reopenPendingMediaShares,
                        onDiscardPendingShare = model::discardPendingMediaShare,
                        pockets = state.pockets,
                        pocketCount = { id -> state.materials.count { item -> (if (state.pockets.any { it.id == item.pocketId }) item.pocketId else DEFAULT_POCKET_ID) == id } },
                        activePocketId = state.workbenchPocketId,
                        onCreatePocket = model::createWorkbenchPocketNow,
                    )
                }
                Screen.GooglePhotos, is Screen.LocalPhotoLibrary -> mediaLibraryStateHolder.SaveableStateProvider("media-library") {
                    PorcelainWideHost(state, model) {
                    LocalPhotoLibraryScreen(
                    model = model,
                    initialIntent = (screen as? Screen.LocalPhotoLibrary)?.initialIntent ?: LocalMediaEditIntent.NONE,
                    googlePhotosSource = screen == Screen.GooglePhotos || (screen as? Screen.LocalPhotoLibrary)?.googlePhotosSource == true,
                    onBack = { model.show(Screen.Dashboard) },
                    onPreview = model::previewLocalMediaUri,
                    onCreateTrailer = model::startLocalMediaTrailer,
                    onMusicOverlay = { model.startLocalMusicOverlay(it, null) },
                    onCopyToPhoneHard = model::copyLocalMediaToInbox,
                    )
                    }
                }
                Screen.Settings -> PorcelainWideHost(state, model) {
                    SettingsScreen(state, model, automaticUpdateDownloadEnabled, onAutomaticUpdateDownloadChanged, onCheckForUpdates)
                }
                Screen.ThumbnailCache -> PorcelainWideHost(state, model) { ThumbnailCacheScreen(state, model) }
                Screen.SuperThumbnail -> PorcelainWideHost(state, model) { SuperThumbnailScreen(state, model) }
                Screen.SuperThumbnailFolderPicker -> PorcelainWideHost(state, model) { SuperThumbnailFolderPickerScreen(state, model) }
                Screen.SuperThumbnailProgress -> PorcelainWideHost(state, model) { SuperThumbnailProgressScreen(state, model) }
                Screen.SuperThumbnailReport -> PorcelainWideHost(state, model) { SuperThumbnailReportScreen(state, model) }
                Screen.WebBrowser -> PorcelainWideHost(state, model) { WebBrowserScreen(
                    sessionController = webBrowserSession,
                    initialUrl = state.browserFavorites.firstOrNull { it.isHomepage }?.url
                        ?: state.browserFavorites.firstOrNull()?.url
                        ?: "https://www.google.com",
                    favorites = state.browserFavorites,
                    connections = state.connections,
                    preferredConnectionId = state.preferredId,
                    resumedDownload = state.pendingLocalUpload,
                    trailerActive = state.trailer != null,
                    onClose = { model.show(Screen.Dashboard) },
                    onToggleFavorite = model::toggleBrowserFavorite,
                    onSetHomepage = model::setBrowserHomepage,
                    onEditFavorite = model::editBrowserFavorite,
                    onDeleteFavorite = model::deleteBrowserFavorite,
                    onSaveDownloadedFile = model::importWebDownloadToInbox,
                    onSendDownloadedFile = model::importWebDownloadToNas,
                    onDiscardDownloadedFile = model::discardWebDownload,
                ) }
                Screen.MaterialBasket -> PorcelainWideHost(state, model) { WorkbenchScreen(state, model, embedded = false, onBack = { model.show(Screen.Dashboard) }) }
                Screen.VideoDrafts -> PorcelainWideHost(state, model) { VideoDraftsScreen(state, model, onBack = { model.show(Screen.Dashboard) }) }
                Screen.VideoCollections -> PorcelainWideHost(state, model) { VideoCollectionsScreen(state, model, onBack = { model.show(Screen.Dashboard) }) }
            }
            }

            // 작업대에 담기 / 영상 제작 확인 / 다시 연결 확인: one host for every source screen.
            WorkbenchAddDialog(state, model)
            WorkbenchRunDialog(state, model)
            WorkbenchReconnectDialog(state, model)
            // 일반 모드에서는 비공개 출처 편집 세션의 화면도 '편집으로 돌아가기' 알약(제목 노출)도 만들지 않는다.
            // 세션은 그대로 살아 있어 내보내기·자동 저장은 계속되고, 프라이버시 모드로 돌아오면 다시 나타난다.
            state.trailer?.takeIf { com.armsone.nasfinder.data.PrivacyMode.isVisible(it.originPrivate) }?.let { session ->
                if (!state.trailerMinimized && state.screen != Screen.WebBrowser) {
                    VideoTrailerScreen(session, model, browsingForMusic = false)
                } else {
                    // The project stays open (export/adds keep running) while another destination is visited.
                    Surface(
                        onClick = model::restoreEditor,
                        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp, start = 16.dp, end = 16.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shadowElevation = 6.dp,
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            PackIcon(PackGlyph.VIDEO, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                            val running = session.export?.running == true
                            Text(
                                "편집으로 돌아가기 · ${session.displayTitle}${if (running) " (내보내는 중)" else ""}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            state.imagePreview?.let { preview ->
                BuiltInPreviewScreen(
                    preview = preview,
                    onClose = model::closeImagePreview,
                    onShare = model::shareImagePreview,
                    onPrevious = { model.moveImagePreview(-1) },
                    onNext = { model.moveImagePreview(1) },
                    onSelectIndex = model::selectImagePreviewIndex,
                    onPreviousPdfPage = { model.movePdfPreview(-1) },
                    onNextPdfPage = { model.movePdfPreview(1) },
                    onPlaybackFailure = model::fallbackPreviewToExternal,
                )
            }
            state.networkDownloadProgress?.takeIf { com.armsone.nasfinder.data.PrivacyMode.isVisible(it.originPrivate) }?.let { progress ->
                NetworkDownloadProgressDialog(
                    progress = progress,
                    onCancel = model::cancelNetworkDownload,
                )
            }
            if (showBusyOverlay) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .42f),
                    trackColor = Color.Transparent,
                )
            }
            state.download?.takeIf { com.armsone.nasfinder.data.PrivacyMode.isVisible(it.originPrivate) }?.let { download ->
                if (download.action == RemoteFileAction.PREVIEW) {
                    PreviewLoadProgressStrip(download, Modifier.align(Alignment.BottomCenter))
                } else {
                    DownloadProgressBanner(download, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        state.message?.let { message ->
            AlertDialog(onDismissRequest = model::dismissMessage, confirmButton = { TextButton(onClick = model::dismissMessage) { Text("확인") } }, title = { Text("알림") }, text = { Text(message) })
        }
        if (state.shareIntakeInProgress > 0) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("공유받은 파일 보관 중") },
                text = { Text("공유받은 미디어를 이 앱 안에 안전하게 복사하고 있습니다. 끝나면 선택지가 나타납니다.") },
                confirmButton = {},
            )
        }
        state.pendingMediaShares.firstOrNull { it.id !in state.deferredShareIds }?.let { share ->
            MediaShareChoiceDialog(
                share = share,
                waitingCount = state.pendingMediaShares.size,
                onImageSelection = model::chooseShareImageSelection,
                onMakeVideo = model::chooseShareVideoMaking,
                onLater = model::deferPendingMediaShare,
                onCancel = model::cancelPendingMediaShare,
            )
        }
        state.trailerDraftPicker?.let { drafts ->
            TrailerDraftManager(drafts, model)
        }
        state.trailerDraftStaleNotice?.let { notice ->
            AlertDialog(
                onDismissRequest = model::dismissTrailerDraftStaleNotice,
                title = { Text("편집 재개 안내") },
                text = { Text(notice) },
                confirmButton = {
                    TextButton(onClick = model::dismissTrailerDraftStaleNotice) { Text("확인") }
                }
            )
        }
        state.pendingCollectionImport?.let { pending ->
            PendingCollectionImportDialog(pending, model)
        }
        state.collectionCopyOffer?.let { offer ->
            AlertDialog(
                onDismissRequest = model::dismissCollectionCopyOffer,
                title = { Text("외부 참조로 보관할 수 없습니다") },
                text = { Text(offer.message) },
                confirmButton = { Button(onClick = model::acceptCollectionCopyOffer) { Text("앱 안에 사본 보관") } },
                dismissButton = { TextButton(onClick = model::dismissCollectionCopyOffer) { Text("취소") } },
            )
        }
        state.trailerConflict?.let { conflict ->
            AlertDialog(
                onDismissRequest = model::dismissTrailerConflict,
                title = { Text(conflict.title) },
                text = { Text(conflict.message) },
                confirmButton = {
                    Button(onClick = {
                        val resume = conflict.onResumeExisting
                        model.dismissTrailerConflict()
                        resume()
                    }) { Text("이전 편집 재개") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            val startNew = conflict.onStartNew
                            model.dismissTrailerConflict()
                            startNew()
                        }) { Text("새로 시작") }
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = model::dismissTrailerConflict) { Text("취소") }
                    }
                }
            )
        }
    }
}

@Composable
private fun rememberTouchExplorationEnabled(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    }
    var enabled by remember(manager) { mutableStateOf(manager.isTouchExplorationEnabled) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager.addTouchExplorationStateChangeListener(listener)
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

@Composable
internal fun BuiltInPreviewScreen(
    preview: ImagePreviewState,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelectIndex: (Int) -> Unit,
    onPreviousPdfPage: () -> Unit,
    onNextPdfPage: () -> Unit,
    onPlaybackFailure: (() -> Unit)?,
    showShare: Boolean = true,
    playbackSuspended: Boolean = false,
    accessoryControls: @Composable RowScope.() -> Unit = {},
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val hostView = LocalView.current
    val touchExploration = rememberTouchExplorationEnabled()
    val edgeToEdgePreview = preview.kind == BuiltInPreviewKind.IMAGE || preview.kind == BuiltInPreviewKind.VIDEO
    val previewSourceKey = preview.connection?.id ?: "phonehard"
    var playbackMode by rememberSaveable(previewSourceKey) {
        mutableStateOf(MediaViewerPreferences.getPlaybackMode(context))
    }
    var slideshowInterval by rememberSaveable(previewSourceKey) {
        mutableIntStateOf(MediaViewerPreferences.getSlideshowInterval(context))
    }
    var controlsVisible by rememberSaveable(previewSourceKey) { mutableStateOf(true) }
    var controlInteraction by remember(previewSourceKey) { mutableIntStateOf(0) }
    var slideshowPlaying by rememberSaveable(previewSourceKey) { mutableStateOf(true) }
    // Index reached by end-of-playback auto-advance (not by the user); a failure there must stay visible in the app.
    var implicitAdvanceIndex by remember(previewSourceKey) { mutableStateOf<Int?>(null) }
    LaunchedEffect(preview.index) { if (implicitAdvanceIndex != preview.index) implicitAdvanceIndex = null }
    val focusRequester = remember { FocusRequester() }

    fun revealControls() { controlsVisible = true; controlInteraction++ }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    val activeKeepAwake = slideshowPlaying || (preview.kind == BuiltInPreviewKind.VIDEO)
    DisposableEffect(hostView, activeKeepAwake) {
        if (activeKeepAwake) {
            val previous = hostView.keepScreenOn
            hostView.keepScreenOn = true
            onDispose { hostView.keepScreenOn = previous }
        } else {
            onDispose { }
        }
    }

    DisposableEffect(context, hostView, preview.kind) {
        if (preview.kind == BuiltInPreviewKind.IMAGE || preview.kind == BuiltInPreviewKind.VIDEO) {
            val window = context.findHostActivity()?.window
            val insetsController = window?.let { WindowInsetsControllerCompat(it, hostView) }
            val hiddenInsets = if (preview.kind == BuiltInPreviewKind.VIDEO) {
                WindowInsetsCompat.Type.systemBars()
            } else {
                WindowInsetsCompat.Type.statusBars()
            }
            insetsController?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController?.hide(hiddenInsets)
            onDispose { insetsController?.show(hiddenInsets) }
        } else {
            onDispose { }
        }
    }

    val isAudio = preview.kind == BuiltInPreviewKind.AUDIO
    val effectiveControlsVisible = if (isAudio) true else controlsVisible

    LaunchedEffect(preview.cachedFile.absolutePath, controlsVisible, controlInteraction, isAudio) {
        if (!isAudio && controlsVisible && !touchExploration) { delay(2_500); controlsVisible = false }
    }

    LaunchedEffect(preview.index, slideshowPlaying, slideshowInterval, playbackMode) {
        if (slideshowPlaying && preview.kind == BuiltInPreviewKind.IMAGE) {
            delay(slideshowInterval * 1000L)
            val nextIdx = MediaSlideshowPolicy.nextIndex(preview.index, preview.images.size, playbackMode)
            if (preview.images.size > 1 || playbackMode == MediaPlaybackMode.ONE_REPEAT) {
                onSelectIndex(nextIdx)
            } else {
                slideshowPlaying = false
            }
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            if (effectiveControlsVisible) {
                TopAppBar(
                    modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.systemBarsIgnoringVisibility).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
                    title = {
                        Column {
                            Text(preview.images[preview.index].name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            when {
                                preview.kind == BuiltInPreviewKind.IMAGE && preview.images.size > 1 -> {
                                    Text(
                                        "${preview.index + 1} / ${preview.images.size} • ${playbackMode.title} • ${slideshowInterval}초",
                                        color = Color.White.copy(alpha = .68f),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                preview.kind == BuiltInPreviewKind.PDF -> {
                                    Text("${preview.pdfPageIndex + 1} / ${preview.pdfPageCount}", color = Color.White.copy(alpha = .68f), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, "닫기", tint = Color.White)
                        }
                    },
                    actions = {
                        if (preview.kind == BuiltInPreviewKind.IMAGE && preview.images.size > 1) {
                            IconButton(
                                onClick = {
                                    val nextMode = playbackMode.next()
                                    playbackMode = nextMode
                                    MediaViewerPreferences.setPlaybackMode(context, nextMode)
                                    revealControls()
                                },
                            ) {
                                Icon(
                                    imageVector = when (playbackMode) {
                                        MediaPlaybackMode.ALL_REPEAT -> Icons.Default.Repeat
                                        MediaPlaybackMode.SHUFFLE -> Icons.Default.Shuffle
                                        MediaPlaybackMode.ONE_REPEAT -> Icons.Default.RepeatOne
                                    },
                                    contentDescription = "재생 모드: ${playbackMode.title}",
                                    tint = Color.White,
                                )
                            }
                            TextButton(
                                onClick = {
                                    val nextSec = MediaSlideshowPolicy.nextInterval(slideshowInterval)
                                    slideshowInterval = nextSec
                                    MediaViewerPreferences.setSlideshowInterval(context, nextSec)
                                    revealControls()
                                },
                            ) {
                                Text(
                                    "${slideshowInterval}초",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                        accessoryControls()
                        if (showShare) IconButton(onClick = onShare) {
                            Icon(Icons.Default.Share, "공유", tint = Color.White)
                        }
                        if (preview.kind == BuiltInPreviewKind.PDF && preview.images.size > 1) {
                            IconButton(onClick = onPrevious, enabled = preview.index > 0) {
                                Icon(Icons.Default.SkipPrevious, "이전 파일", tint = Color.White)
                            }
                            IconButton(onClick = onNext, enabled = preview.index < preview.images.lastIndex) {
                                Icon(Icons.Default.SkipNext, "다음 파일", tint = Color.White)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = if (edgeToEdgePreview) Color.Transparent else Color.Black),
                )
            } else if (!edgeToEdgePreview) Spacer(Modifier.height(64.dp).fillMaxWidth().background(Color.Black))
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .then(if (edgeToEdgePreview) Modifier else Modifier.padding(padding))
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        when (keyEvent.key) {
                            Key.Escape, Key.Back -> { onClose(); true }
                            Key.DirectionLeft -> {
                                if (preview.kind != BuiltInPreviewKind.PDF && preview.images.size > 1) { onPrevious(); true }
                                else if (preview.kind == BuiltInPreviewKind.PDF) { onPreviousPdfPage(); true }
                                else false
                            }
                            Key.DirectionRight -> {
                                if (preview.kind != BuiltInPreviewKind.PDF && preview.images.size > 1) { onNext(); true }
                                else if (preview.kind == BuiltInPreviewKind.PDF) { onNextPdfPage(); true }
                                else false
                            }
                            Key.Spacebar, Key.MediaPlayPause -> {
                                if (preview.kind == BuiltInPreviewKind.IMAGE && preview.images.size > 1) {
                                    slideshowPlaying = !slideshowPlaying
                                    revealControls()
                                    true
                                } else false
                            }
                            else -> false
                        }
                    } else false
                },
            contentAlignment = Alignment.Center,
        ) {
            when (preview.kind) {
                BuiltInPreviewKind.IMAGE, BuiltInPreviewKind.PDF -> preview.bitmap?.let { bitmap ->
                    ZoomablePreviewBitmap(
                        bitmap = bitmap,
                        animatedFile = preview.cachedFile.takeIf { preview.kind == BuiltInPreviewKind.IMAGE && preview.animatedImage },
                        description = preview.images[preview.index].name,
                        stateKey = "${preview.cachedFile.absolutePath}:${preview.pdfPageIndex}",
                        onPrevious = onPrevious,
                        onNext = onNext,
                        onClose = onClose,
                        onToggleControls = {
                            if (touchExploration) revealControls()
                            else if (controlsVisible) controlsVisible = false
                            else revealControls()
                        },
                    )
                }
                BuiltInPreviewKind.AUDIO, BuiltInPreviewKind.VIDEO -> {
                    MediaPreviewContent(
                        preview = preview,
                        controlsVisible = effectiveControlsVisible,
                        playbackMode = playbackMode,
                        onPlaybackModeChange = { nextMode ->
                            playbackMode = nextMode
                            MediaViewerPreferences.setPlaybackMode(context, nextMode)
                            revealControls()
                        },
                        onInteraction = ::revealControls,
                        onDismiss = onClose,
                        onPlaybackFailure = onPlaybackFailure,
                        autoRouteOnFailure = implicitAdvanceIndex != preview.index,
                        playbackSuspended = playbackSuspended,
                        onNextMedia = if (preview.images.size > 1) {
                            {
                                slideshowPlaying = true
                                val nextIndex = MediaSlideshowPolicy.nextIndex(preview.index, preview.images.size, playbackMode)
                                implicitAdvanceIndex = nextIndex
                                onSelectIndex(nextIndex)
                            }
                        } else null,
                    )
                }
            }
            if (preview.kind == BuiltInPreviewKind.IMAGE && controlsVisible) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .10f)))
            }
            if (effectiveControlsVisible && preview.kind != BuiltInPreviewKind.PDF && preview.images.size > 1) {
                FilledIconButton(
                    onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onPrevious() },
                    enabled = preview.index > 0,
                    modifier = Modifier.align(Alignment.CenterStart).padding(12.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = .56f), contentColor = Color.White),
                ) { Icon(Icons.Default.ChevronLeft, "이전 파일") }
                FilledIconButton(
                    onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onNext() },
                    enabled = preview.index < preview.images.lastIndex,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(12.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = .56f), contentColor = Color.White),
                ) { Icon(Icons.Default.ChevronRight, "다음 파일") }
                if (preview.kind == BuiltInPreviewKind.IMAGE) {
                    FilledIconButton(
                        onClick = { slideshowPlaying = !slideshowPlaying; revealControls() },
                        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(14.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = .56f), contentColor = Color.White),
                    ) {
                        Icon(
                            if (slideshowPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (slideshowPlaying) "슬라이드쇼 일시정지" else "슬라이드쇼 재생",
                        )
                    }
                }
            }
            if (controlsVisible && preview.kind == BuiltInPreviewKind.PDF && preview.pdfPageCount > 1) {
                FilledIconButton(
                    onClick = onPreviousPdfPage,
                    enabled = preview.pdfPageIndex > 0,
                    modifier = Modifier.align(Alignment.CenterStart).padding(12.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = .56f), contentColor = Color.White),
                ) { Icon(Icons.Default.ChevronLeft, "이전 페이지") }
                FilledIconButton(
                    onClick = onNextPdfPage,
                    enabled = preview.pdfPageIndex < preview.pdfPageCount - 1,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(12.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = .56f), contentColor = Color.White),
                ) { Icon(Icons.Default.ChevronRight, "다음 페이지") }
            }
        }
    }
}

@Composable
private fun ZoomablePreviewBitmap(
    bitmap: android.graphics.Bitmap,
    animatedFile: File?,
    description: String,
    stateKey: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    onToggleControls: () -> Unit,
) {
    val touchExploration = rememberTouchExplorationEnabled()
    var scale by remember(stateKey) { mutableFloatStateOf(1f) }
    var translation by remember(stateKey) { mutableStateOf(Offset.Zero) }
    var dismissTranslationY by remember(stateKey) { mutableFloatStateOf(0f) }
    var dismissOpacity by remember(stateKey) { mutableFloatStateOf(1f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val haptics = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .then(
                if (touchExploration) {
                    Modifier.clickable(onClick = onToggleControls)
                } else {
                    Modifier
                        .pointerInput(stateKey) {
                            detectTapGestures(
                                onTap = { onToggleControls() },
                                onDoubleTap = {
                                    if (scale > 1.05f) {
                                        scale = 1.0f
                                        translation = Offset.Zero
                                    } else {
                                        scale = MediaViewerGesturePolicy.DOUBLE_TAP_ZOOM_SCALE
                                        translation = Offset.Zero
                                    }
                                },
                            )
                        }
                        .pointerInput(stateKey, containerSize) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var totalDrag = Offset.Zero
                                var isMultiTouch = false
                                var gestureMode = MediaViewerGestureMode.NONE
                                val touchSlop = MediaViewerGesturePolicy.logicalDistancePx(
                                    MediaViewerGesturePolicy.DEFAULT_TOUCH_SLOP_DP,
                                    density,
                                )
                                val dismissThreshold = MediaViewerGesturePolicy.logicalDistancePx(
                                    MediaViewerGesturePolicy.DEFAULT_DISMISS_THRESHOLD_DP,
                                    density,
                                )
                                val navigationThreshold = MediaViewerGesturePolicy.logicalDistancePx(
                                    MediaViewerGesturePolicy.DEFAULT_IMAGE_NAVIGATION_THRESHOLD_DP,
                                    density,
                                )

                                do {
                                    val event = awaitPointerEvent()
                                    val pointerCount = event.changes.count { it.pressed }

                                    if (pointerCount >= 2) {
                                        isMultiTouch = true
                                        val zoom = event.calculateZoom()
                                        val pan = event.calculatePan()
                                        val nextScale = (scale * zoom).coerceIn(
                                            MediaViewerGesturePolicy.MIN_IMAGE_SCALE,
                                            MediaViewerGesturePolicy.MAX_IMAGE_SCALE,
                                        )
                                        scale = nextScale
                                        if (nextScale <= 1.0f) {
                                            translation = Offset.Zero
                                        } else {
                                            translation = MediaViewerGesturePolicy.clampImageTranslation(
                                                translation + pan,
                                                nextScale,
                                                containerSize.width.toFloat(),
                                                containerSize.height.toFloat(),
                                            )
                                        }
                                        dismissTranslationY = 0f
                                        dismissOpacity = 1f
                                        event.changes.forEach { it.consume() }
                                    } else if (pointerCount == 1 && !isMultiTouch) {
                                        val change = event.changes.firstOrNull { it.pressed } ?: break
                                        val dragDelta = change.positionChange()
                                        totalDrag += dragDelta

                                        if (scale > 1.05f) {
                                            translation = MediaViewerGesturePolicy.clampImageTranslation(
                                                translation + dragDelta,
                                                scale,
                                                containerSize.width.toFloat(),
                                                containerSize.height.toFloat(),
                                            )
                                            change.consume()
                                        } else {
                                            if (hypot(totalDrag.x, totalDrag.y) >= touchSlop) {
                                                if (gestureMode == MediaViewerGestureMode.NONE) {
                                                    gestureMode = MediaViewerGesturePolicy.determineGestureMode(
                                                        totalDrag.x, totalDrag.y, touchSlop,
                                                    )
                                                }
                                                when (gestureMode) {
                                                    MediaViewerGestureMode.DISMISS -> {
                                                        if (totalDrag.y > 0) {
                                                            dismissTranslationY = totalDrag.y
                                                            dismissOpacity = MediaViewerGesturePolicy.computeDismissProgress(
                                                                dismissTranslationY,
                                                                dismissThreshold,
                                                            )
                                                            change.consume()
                                                        }
                                                    }
                                                    MediaViewerGestureMode.SEEK -> {
                                                        change.consume()
                                                    }
                                                    else -> Unit
                                                }
                                            }
                                        }
                                    }
                                } while (event.changes.any { it.pressed })

                                if (!isMultiTouch && scale <= 1.05f) {
                                    if (gestureMode == MediaViewerGestureMode.DISMISS) {
                                        if (MediaViewerGesturePolicy.shouldDismiss(dismissTranslationY, dismissThreshold)) {
                                            onClose()
                                        } else {
                                            dismissTranslationY = 0f
                                            dismissOpacity = 1f
                                        }
                                    } else if (gestureMode == MediaViewerGestureMode.SEEK) {
                                        val nav = MediaViewerGesturePolicy.shouldNavigateImage(
                                            totalDrag.x,
                                            scale,
                                            navigationThreshold,
                                        )
                                        if (nav < 0) {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onPrevious()
                                        } else if (nav > 0) {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onNext()
                                        }
                                    }
                                }
                            }
                        }
                }
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = translation.x
                translationY = translation.y + dismissTranslationY
                alpha = dismissOpacity
            },
        contentAlignment = Alignment.Center,
    ) {
        if (animatedFile != null) {
            AnimatedGifPreview(file = animatedFile, fallback = bitmap, description = description)
        } else {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = description,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Plays a multi-frame GIF with the platform decoder. The already decoded first frame is shown
 * while the animation loads and stays as the static fallback when the animated decode fails or
 * the OS predates AnimatedImageDrawable (API 28).
 */
@Composable
private fun AnimatedGifPreview(file: File, fallback: android.graphics.Bitmap, description: String) {
    val animated by produceState<AnimatedGifContent?>(initialValue = null, key1 = file.absolutePath) {
        value = withContext(Dispatchers.IO) { decodeAnimatedGif(file) }
    }
    when (val content = animated) {
    null -> {
        Image(
            bitmap = fallback.asImageBitmap(),
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
    is AnimatedGifContent.Framework -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        FrameworkAnimatedGifPreview(content.drawable, description)
    } else {
        Image(
            bitmap = fallback.asImageBitmap(),
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
    is AnimatedGifContent.Legacy -> {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> LegacyGifImageView(ctx) },
            update = { view ->
                view.contentDescription = description
                view.show(content.movie)
            },
            onRelease = LegacyGifImageView::stop,
        )
    }
    }
}

@RequiresApi(Build.VERSION_CODES.P)
@Composable
private fun FrameworkAnimatedGifPreview(drawable: AnimatedImageDrawable, description: String) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx -> ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
        update = { view ->
            view.contentDescription = description
            if (view.drawable !== drawable) {
                view.setImageDrawable(drawable)
                startAnimatedDrawable(drawable)
            }
        },
        onRelease = { view ->
            view.drawable?.let(::stopAnimatedDrawable)
            view.setImageDrawable(null)
        },
    )
}

private sealed interface AnimatedGifContent {
    data class Framework(val drawable: AnimatedImageDrawable) : AnimatedGifContent
    data class Legacy(val movie: Movie) : AnimatedGifContent
}

@Suppress("DEPRECATION")
private fun decodeAnimatedGif(file: File): AnimatedGifContent? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val drawable = runCatching {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                decoder.setTargetSampleSize(AnimatedImagePolicy.sampleSize(info.size.width, info.size.height))
            }
        }.getOrNull() as? AnimatedImageDrawable
        if (drawable != null) return AnimatedGifContent.Framework(drawable)
    }
    return runCatching {
        file.inputStream().buffered().use(Movie::decodeStream)
    }.getOrNull()?.let { AnimatedGifContent.Legacy(it) }
}

@Suppress("DEPRECATION")
private class LegacyGifImageView(context: Context) : View(context) {
    private var movie: Movie? = null
    private var startedAt = 0L
    private var running = false

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    fun show(next: Movie) {
        if (movie !== next) {
            movie = next
            startedAt = SystemClock.uptimeMillis()
        }
        running = true
        postInvalidateOnAnimation()
    }

    fun stop() {
        running = false
        movie = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val animation = movie ?: return
        val movieWidth = animation.width().coerceAtLeast(1)
        val movieHeight = animation.height().coerceAtLeast(1)
        val scale = minOf(width.toFloat() / movieWidth, height.toFloat() / movieHeight)
        val left = (width - movieWidth * scale) / 2f
        val top = (height - movieHeight * scale) / 2f
        val duration = animation.duration().takeIf { it > 0 } ?: 1_000
        animation.setTime(((SystemClock.uptimeMillis() - startedAt) % duration).toInt())
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        animation.draw(canvas, 0f, 0f)
        canvas.restore()
        if (running) postInvalidateOnAnimation()
    }
}

private fun startAnimatedDrawable(drawable: Drawable) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && drawable is AnimatedImageDrawable) {
        drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        drawable.start()
    }
}

private fun stopAnimatedDrawable(drawable: Drawable) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && drawable is AnimatedImageDrawable) drawable.stop()
}

@Composable
private fun MediaPreviewContent(
    preview: ImagePreviewState,
    controlsVisible: Boolean,
    playbackMode: MediaPlaybackMode,
    onPlaybackModeChange: (MediaPlaybackMode) -> Unit,
    onInteraction: () -> Unit,
    onDismiss: () -> Unit,
    onPlaybackFailure: (() -> Unit)?,
    onNextMedia: (() -> Unit)? = null,
    autoRouteOnFailure: Boolean = true,
    playbackSuspended: Boolean = false,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val touchExploration = rememberTouchExplorationEnabled()
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    val mediaSourceKey = preview.remoteStream?.sourceKey ?: preview.cachedFile.absolutePath
    var retryTrigger by remember(mediaSourceKey) { mutableIntStateOf(0) }
    var isBuffering by remember(mediaSourceKey, retryTrigger) { mutableStateOf(true) }
    var bufferedFraction by remember(mediaSourceKey, retryTrigger) { mutableStateOf<Float?>(null) }
    var playbackError by remember(mediaSourceKey, retryTrigger) { mutableStateOf<String?>(null) }
    var player by remember(mediaSourceKey, retryTrigger) { mutableStateOf<ExoPlayer?>(null) }
    var duration by remember(mediaSourceKey, retryTrigger) { mutableIntStateOf(0) }
    var position by rememberSaveable(mediaSourceKey) { mutableIntStateOf(0) }
    var playing by rememberSaveable(mediaSourceKey) { mutableStateOf(true) }
    var scale by rememberSaveable(mediaSourceKey) { mutableFloatStateOf(1f) }
    var translation by remember(mediaSourceKey) { mutableStateOf(Offset.Zero) }
    var dismissTranslationY by remember(mediaSourceKey) { mutableFloatStateOf(0f) }
    var dismissOpacity by remember(mediaSourceKey) { mutableFloatStateOf(1f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var gestureMessage by remember { mutableStateOf<String?>(null) }
    var fineVolumeFraction by remember(mediaSourceKey) { mutableStateOf<Float?>(null) }

    var resumeAfterSuspension by remember(mediaSourceKey) { mutableStateOf(false) }
    LaunchedEffect(player, playbackSuspended) {
        val active = player ?: return@LaunchedEffect
        if (playbackSuspended) {
            resumeAfterSuspension = active.playWhenReady
            active.pause()
        } else if (resumeAfterSuspension) {
            resumeAfterSuspension = false
            active.play()
        }
    }

    val focusChangeListener = remember {
        AudioManager.OnAudioFocusChangeListener { focusChange ->
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                player?.pause()
                playing = false
            }
        }
    }

    LaunchedEffect(player, playing) {
        while (playing) {
            position = player?.currentPosition?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: position
            delay(100)
        }
    }

    LaunchedEffect(player, isBuffering) {
        if (!isBuffering) {
            bufferedFraction = null
            return@LaunchedEffect
        }
        var lastMeaningfulFraction: Float? = null
        var lastAdvanceAt = SystemClock.elapsedRealtime()
        while (true) {
            val activePlayer = player
            val candidate = if (activePlayer == null) null else {
                MediaBufferingProgressPolicy.fraction(
                    bufferedPercentage = activePlayer.bufferedPercentage,
                    bufferedPositionMs = activePlayer.bufferedPosition,
                    durationMs = activePlayer.duration,
                )
            }
            val now = SystemClock.elapsedRealtime()
            bufferedFraction = when {
                candidate == null || candidate <= 0f -> null
                MediaBufferingProgressPolicy.hasAdvanced(lastMeaningfulFraction, candidate) -> {
                    lastMeaningfulFraction = candidate
                    lastAdvanceAt = now
                    candidate
                }
                now - lastAdvanceAt <= MediaBufferingProgressPolicy.STALL_TO_INDETERMINATE_MS ->
                    lastMeaningfulFraction
                else -> null
            }
            delay(200)
        }
    }

    LaunchedEffect(gestureMessage) {
        if (gestureMessage != null) {
            delay(850)
            gestureMessage = null
        }
    }

    DisposableEffect(lifecycleOwner, preview.cachedFile.absolutePath) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                position = player?.currentPosition?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: position
                player?.pause()
                playing = false
                audioManager.abandonAudioFocus(focusChangeListener)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            audioManager.abandonAudioFocus(focusChangeListener)
            position = player?.currentPosition?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: position
            player?.pause()
            player?.stop()
            player?.release()
            player = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it },
        contentAlignment = Alignment.Center,
    ) {
        if (preview.kind == BuiltInPreviewKind.AUDIO) {
            Icon(
                Icons.Default.Audiotrack,
                null,
                tint = Color.White.copy(alpha = .78f),
                modifier = Modifier.size(100.dp),
            )
        }
        key(mediaSourceKey, retryTrigger) {
        AndroidView(
            modifier = if (preview.kind == BuiltInPreviewKind.VIDEO) {
                Modifier
                    .fillMaxSize()
                    .then(
                        if (touchExploration) {
                            Modifier.clickable(onClick = onInteraction)
                        } else {
                            Modifier
                                .pointerInput(mediaSourceKey, retryTrigger) {
                                    detectTapGestures(
                                        onTap = { onInteraction() },
                                        onDoubleTap = {
                                            scale = 1f
                                            translation = Offset.Zero
                                            onInteraction()
                                        },
                                    )
                                }
                                .pointerInput(mediaSourceKey, retryTrigger, containerSize, duration) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        var totalDrag = Offset.Zero
                                        var isMultiTouch = false
                                        var gestureMode = MediaViewerGestureMode.NONE
                                        val touchSlop = MediaViewerGesturePolicy.logicalDistancePx(
                                            MediaViewerGesturePolicy.DEFAULT_TOUCH_SLOP_DP,
                                            density,
                                        )
                                        val dismissThreshold = MediaViewerGesturePolicy.logicalDistancePx(
                                            MediaViewerGesturePolicy.DEFAULT_DISMISS_THRESHOLD_DP,
                                            density,
                                        )
                                        val startPosition = position
                                        val startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                        val startVolumeFraction =
                                            (startVolume.toFloat() / maxVolume * (player?.volume ?: 1f)).coerceIn(0f, 1f)
                                        val volumeFullRangePx = MediaViewerGesturePolicy.logicalDistancePx(
                                            MediaViewerGesturePolicy.VOLUME_FULL_RANGE_DRAG_DP,
                                            density,
                                        )
                                        var appliedVolume = startVolume

                                        do {
                                            val event = awaitPointerEvent()
                                            val pointerCount = event.changes.count { it.pressed }

                                            if (pointerCount >= 2) {
                                                isMultiTouch = true
                                                val zoom = event.calculateZoom()
                                                val pan = event.calculatePan()
                                                val nextScale = (scale * zoom).coerceIn(1f, 4f)
                                                scale = nextScale
                                                translation = MediaViewerGesturePolicy.clampImageTranslation(
                                                    currentTranslation = if (nextScale == 1f) Offset.Zero else translation + pan,
                                                    scale = nextScale,
                                                    containerWidth = containerSize.width.toFloat(),
                                                    containerHeight = containerSize.height.toFloat(),
                                                )
                                                dismissTranslationY = 0f
                                                dismissOpacity = 1f
                                                event.changes.forEach { it.consume() }
                                                onInteraction()
                                            } else if (pointerCount == 1 && !isMultiTouch) {
                                                val change = event.changes.firstOrNull { it.pressed } ?: break
                                                val dragDelta = change.positionChange()
                                                totalDrag += dragDelta

                                                if (scale > 1.05f) {
                                                    translation = MediaViewerGesturePolicy.clampImageTranslation(
                                                        currentTranslation = translation + dragDelta,
                                                        scale = scale,
                                                        containerWidth = containerSize.width.toFloat(),
                                                        containerHeight = containerSize.height.toFloat(),
                                                    )
                                                    change.consume()
                                                } else {
                                                    if (hypot(totalDrag.x, totalDrag.y) >= touchSlop) {
                                                        if (gestureMode == MediaViewerGestureMode.NONE) {
                                                            gestureMode = MediaViewerGesturePolicy.determineGestureMode(
                                                                totalDrag.x, totalDrag.y, touchSlop,
                                                            )
                                                        }
                                                        when (gestureMode) {
                                                            MediaViewerGestureMode.SEEK -> {
                                                                if (duration > 0) {
                                                                    val seekPos = MediaViewerGesturePolicy.computeSeekPosition(
                                                                        startPosition,
                                                                        duration,
                                                                        totalDrag.x,
                                                                        containerSize.width.toFloat(),
                                                                    )
                                                                    position = seekPos
                                                                    player?.seekTo(seekPos.toLong())
                                                                    gestureMessage = "${formatDuration(seekPos)} / ${formatDuration(duration)}"
                                                                    change.consume()
                                                                }
                                                            }
                                                            MediaViewerGestureMode.VOLUME -> {
                                                                // Accumulated drag drives a continuous readout; the stream only
                                                                // moves when the nearest native step actually changes.
                                                                val fraction = MediaViewerGesturePolicy.quantizeVolumeFraction(
                                                                    MediaViewerGesturePolicy.computeVolumeFraction(
                                                                        startVolumeFraction,
                                                                        totalDrag.y,
                                                                        volumeFullRangePx,
                                                                    ),
                                                                )
                                                                val setting = MediaViewerGesturePolicy.fineVolumeSetting(fraction, maxVolume)
                                                                if (setting.streamIndex != appliedVolume) {
                                                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, setting.streamIndex, 0)
                                                                    appliedVolume = setting.streamIndex
                                                                }
                                                                player?.volume = setting.playerGain
                                                                fineVolumeFraction = fraction
                                                                gestureMessage = "볼륨 ${MediaViewerGesturePolicy.volumePercent(fraction)}%"
                                                                change.consume()
                                                            }
                                                            MediaViewerGestureMode.DISMISS -> {
                                                                if (totalDrag.y > 0) {
                                                                    dismissTranslationY = totalDrag.y
                                                                    dismissOpacity = MediaViewerGesturePolicy.computeDismissProgress(
                                                                        dismissTranslationY,
                                                                        dismissThreshold,
                                                                    )
                                                                    gestureMessage = "아래로 놓아 닫기"
                                                                    change.consume()
                                                                }
                                                            }
                                                            else -> Unit
                                                        }
                                                    }
                                                }
                                            }
                                        } while (event.changes.any { it.pressed })

                                        if (!isMultiTouch && scale <= 1.05f && gestureMode == MediaViewerGestureMode.DISMISS) {
                                            if (MediaViewerGesturePolicy.shouldDismiss(dismissTranslationY, dismissThreshold)) {
                                                onDismiss()
                                            } else {
                                                dismissTranslationY = 0f
                                                dismissOpacity = 1f
                                                gestureMessage = null
                                            }
                                        }
                                    }
                                }
                        }
                    )
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = translation.x
                        translationY = translation.y + dismissTranslationY
                        alpha = dismissOpacity
                    }
            } else Modifier.size(1.dp),
            factory = { ctx ->
                var initialPositionRestored = false
                val exoPlayer = ExoPlayer.Builder(ctx).build().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                        true,
                    )
                    playWhenReady = true
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            isBuffering = playbackState == Player.STATE_BUFFERING
                            if (playbackState == Player.STATE_READY) {
                                bufferedFraction = null
                                duration = this@apply.duration.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                                if (!initialPositionRestored) {
                                    initialPositionRestored = true
                                    if (position > 0) {
                                        seekTo(position.toLong().coerceAtMost(this@apply.duration.coerceAtLeast(0)))
                                    }
                                }
                                isBuffering = false
                                playbackError = null
                            } else if (playbackState == Player.STATE_ENDED) {
                                if (playbackMode == MediaPlaybackMode.ONE_REPEAT) {
                                    seekTo(0)
                                    play()
                                } else {
                                    playing = false
                                    position = duration
                                    onNextMedia?.invoke()
                                }
                            }
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            playing = isPlaying
                            if (isPlaying) {
                                isBuffering = false
                                bufferedFraction = null
                                playbackError = null
                            }
                        }

                        override fun onTracksChanged(tracks: Tracks) {
                            // Evidence from the container itself: a video track exists but this device cannot decode any of
                            // them while audio is playable. Sound without a picture is a failure, not a success.
                            if (preview.kind != BuiltInPreviewKind.VIDEO) return
                            val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                            val audioPlayable = tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
                            if (videoGroups.isNotEmpty() && videoGroups.none { it.isSupported } && audioPlayable) {
                                pause()
                                isBuffering = false
                                bufferedFraction = null
                                playing = false
                                playbackError = "이 기기에서는 이 영상의 화면을 표시할 수 없어 소리만 재생될 수 있습니다. 다른 플레이어로 열어 보세요."
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            isBuffering = false
                            bufferedFraction = null
                            playing = false
                            playbackError = error.localizedMessage ?: "동영상을 재생할 수 없습니다."
                        }
                    })
                    preview.remoteStream?.let { setMediaSource(it.createMediaSource()) } ?: run {
                        val factory = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(ctx))
                        setMediaSource(factory.createMediaSource(MediaItem.fromUri(preview.cachedFile.toURI().toString())))
                    }
                    prepare()
                }
                player = exoPlayer
                PlayerView(ctx).apply {
                    useController = false
                    this.player = exoPlayer
                }
            },
            update = { view -> player = view.player as? ExoPlayer },
            onRelease = { released ->
                audioManager.abandonAudioFocus(focusChangeListener)
                val releasedPlayer = released.player as? ExoPlayer
                fineVolumeFraction?.let { fraction ->
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    audioManager.setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        MediaViewerGesturePolicy.nativeVolumeIndex(fraction, maxVolume),
                        0,
                    )
                }
                position = releasedPlayer?.currentPosition?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: position
                released.player = null
                releasedPlayer?.release()
                if (player === releasedPlayer) player = null
            },
        )
        }
        if (isBuffering && playbackError == null) {
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = .55f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.widthIn(min = 210.dp, max = 280.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "동영상 준비 중…",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val progressModifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .semantics { contentDescription = "동영상 준비 진행률" }
                    bufferedFraction?.let { fraction ->
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = progressModifier,
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = .22f),
                        )
                    } ?: LinearProgressIndicator(
                        modifier = progressModifier,
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = .22f),
                    )
                }
            }
        }
        // A video this viewer cannot show is routed to the system's own player chooser right away when the file is already
        // on this device (a remote stream is never downloaded without the user pressing the button below). Closing the
        // chooser returns here, where 닫기 / 다시 시도 stay available. Runtime decoder support is not assumed. An item reached by
        // end-of-playback auto-advance never opens the chooser by itself: the error stays here with the explicit button.
        var autoRoutedToChooser by remember(mediaSourceKey) { mutableStateOf(false) }
        LaunchedEffect(playbackError) {
            if (playbackError != null && !autoRoutedToChooser && preview.kind == BuiltInPreviewKind.VIDEO &&
                preview.remoteStream == null && onPlaybackFailure != null && autoRouteOnFailure
            ) {
                autoRoutedToChooser = true
                onPlaybackFailure()
            }
        }
        playbackError?.let { errorMessage ->
            Surface(
                modifier = Modifier
                    .padding(24.dp)
                    .widthIn(max = 420.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF1E1E1E),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .18f)),
                shadowElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(36.dp),
                    )
                    Text(
                        "재생 오류",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        errorMessage,
                        color = Color.White.copy(alpha = .80f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White.copy(alpha = .80f)),
                        ) {
                            Text("닫기")
                        }
                        OutlinedButton(
                            onClick = {
                                playbackError = null
                                isBuffering = true
                                retryTrigger++
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White.copy(alpha = .90f)),
                        ) {
                            Text("다시 시도")
                        }
                        if (onPlaybackFailure != null) Button(
                            onClick = onPlaybackFailure,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) {
                            Text("다른 플레이어로 열기")
                        }
                    }
                }
            }
        }
        gestureMessage?.let { message ->
            Text(
                message,
                color = Color.White,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = .72f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (controlsVisible) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = .72f))
                    .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.systemBarsIgnoringVisibility).only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(16.dp),
            ) {
                Slider(
                    value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                    onValueChange = { value ->
                        onInteraction()
                        position = value.toInt()
                        player?.seekTo(position.toLong())
                    },
                    valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "${formatDuration(position)} / ${formatDuration(duration)}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                onInteraction()
                                if (playing) {
                                    player?.pause()
                                    audioManager.abandonAudioFocus(focusChangeListener)
                                    playing = false
                                } else {
                                    // ExoPlayer already owns audio focus (handleAudioFocus); a second app-level request here made
                                    // the player's own request deliver LOSS to this listener, which paused it again right away.
                                    player?.play()
                                    playing = true
                                }
                            },
                            enabled = duration > 0,
                        ) {
                            Icon(
                                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                if (playing) "일시정지" else "재생",
                                tint = Color.White,
                            )
                        }
                    }
                    IconButton(
                        onClick = { onPlaybackModeChange(playbackMode.next()) },
                    ) {
                        Icon(
                            imageVector = when (playbackMode) {
                                MediaPlaybackMode.ALL_REPEAT -> Icons.Default.Repeat
                                MediaPlaybackMode.SHUFFLE -> Icons.Default.Shuffle
                                MediaPlaybackMode.ONE_REPEAT -> Icons.Default.RepeatOne
                            },
                            contentDescription = "재생 모드: ${playbackMode.title}",
                            tint = Color.White,
                        )
                    }
                }
            }
        } else if (playing) {
            MediaPlaybackProgressPolicy.fraction(position.toLong(), duration.toLong())?.let { playbackFraction ->
                LinearProgressIndicator(
                    progress = { playbackFraction },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(1.dp)
                        .semantics {
                            contentDescription = "재생 위치 ${formatDuration(position)} / ${formatDuration(duration)}"
                        },
                    color = Color.White.copy(alpha = .35f),
                    trackColor = Color.Black.copy(alpha = .35f),
                )
            }
        }
    }
}

private fun formatDuration(milliseconds: Int): String {
    val seconds = (milliseconds.coerceAtLeast(0) / 1000)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

internal fun formatPlaybackDuration(durationMs: Long?): String? {
    if (durationMs == null || durationMs <= 0L) return null
    val totalSeconds = (durationMs + 500) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

/**
 * iOS TrailerDraftManagerView: full-screen NavigationStack with inline "보관한 편집" title and a 닫기 cancellation action,
 * plain list of rows (56pt thumbnail corner 8, subheadline medium title, "모드 · N개 · 길이" caption, "마지막 편집 …" caption2,
 * bordered "이어서 하기" and trash), per-entry "이 편집을 지울까요?" alert, PorcelainStateView when empty.
 * The drafts stay the existing in-memory store: no persistence, cap or eviction is added here.
 */
@Composable
private fun TrailerDraftManager(drafts: List<TrailerDraft>, model: NasFinderViewModel) {
    var draftToDelete by remember { mutableStateOf<TrailerDraft?>(null) }
    val porcelain = usesUnifiedLayout(LocalNasFinderTheme.current)
    BackHandler { model.dismissTrailerDraftPicker() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                IconButton(onClick = model::dismissTrailerDraftPicker, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "닫기")
                }
                Text("편집본", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            if (drafts.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                ) {
                    if (porcelain) PackIcon(PackGlyph.DRAFTS, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    else Icon(Icons.Default.MovieFilter, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("편집본이 없습니다", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    val metadataProblem = TrailerDraftStore.metadataProblem()
                    Text(
                        metadataProblem ?: "편집을 닫으면 앱 저장 공간에 보관되어 앱을 다시 열어도 이어서 편집할 수 있습니다.",
                        fontSize = 12.sp,
                        color = if (metadataProblem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(drafts, key = { it.id }) { draft ->
                        if (porcelain) {
                            // Approved 04 보관한 편집: large real cover, name, actual length, 이어서 편집 / 삭제.
                            TrailerDraftCoverCard(
                                draft = draft,
                                onResume = {
                                    model.dismissTrailerDraftPicker()
                                    model.resumeTrailerDraft(draft)
                                },
                                onDelete = { draftToDelete = draft },
                            )
                        } else {
                            TrailerDraftRow(
                                draft = draft,
                                rowColor = Color.Transparent,
                                onResume = {
                                    model.dismissTrailerDraftPicker()
                                    model.resumeTrailerDraft(draft)
                                },
                                onDelete = { draftToDelete = draft },
                            )
                            HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f))
                        }
                    }
                }
            }
        }
        draftToDelete?.let { draft ->
            AlertDialog(
                onDismissRequest = { draftToDelete = null },
                title = { Text("이 편집본을 지울까요?") },
                text = { Text("이 편집 정보와 앱이 만든 이 프로젝트의 작업 폴더만 삭제됩니다. 원본 미디어, 컬렉션에 보관된 영상은 지워지지 않습니다.") },
                dismissButton = { TextButton(onClick = { draftToDelete = null }) { Text("취소") } },
                confirmButton = {
                    TextButton(onClick = {
                        draftToDelete = null
                        model.discardTrailerDraft(draft)
                    }) { Text("지우기", color = MaterialTheme.colorScheme.error) }
                },
            )
        }
    }
}

@Composable
private fun TrailerDraftCoverCard(draft: TrailerDraft, onResume: () -> Unit, onDelete: () -> Unit) {
    val cover = rememberTrailerDraftCover(draft, maxPixelSize = 360)
    val seconds = rememberTrailerDraftDuration(draft)
    val modeTitle = if (draft.isMusicOverlayMode) "뮤직비디오" else "예고편"
    val lastEdited = remember(draft.timestamp) {
        android.text.format.DateUtils.getRelativeTimeSpanString(
            draft.timestamp, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
            android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "이어서 편집", role = Role.Button, onClick = onResume)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .width(112.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (cover != null) Image(bitmap = cover.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Default.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            formatPlaybackDuration((seconds * 1000).toLong())?.let { label ->
                Text(
                    label,
                    Modifier.align(Alignment.BottomEnd).padding(4.dp)
                        .background(Color.Black.copy(alpha = .62f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    style = com.armsone.nasfinder.ui.theme.PorcelainType.Badge,
                    color = Color.White,
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                draft.displayTitle,
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "$modeTitle · ${draft.sources.size}개 · ${trailerLengthTitle(seconds)}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "마지막 편집 $lastEdited",
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Default.Delete, "지우기", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        Icon(Icons.Default.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f))
}

@Composable
private fun TrailerDraftRow(draft: TrailerDraft, rowColor: Color, onResume: () -> Unit, onDelete: () -> Unit) {
    val thumbnailBitmap = rememberTrailerDraftCover(draft, maxPixelSize = 360)
    val actualDuration = rememberTrailerDraftDuration(draft)
    val modeTitle = if (draft.isMusicOverlayMode) "뮤직비디오" else "예고편"
    val lastEdited = remember(draft.timestamp) {
        android.text.format.DateUtils.getRelativeTimeSpanString(
            draft.timestamp, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
            android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(rowColor)
            .clickable(onClickLabel = "이어서 하기", onClick = onResume)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .width(112.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(compactPanelFill()),
            contentAlignment = Alignment.Center,
        ) {
            val thumb = thumbnailBitmap
            if (thumb != null) {
                Image(bitmap = thumb.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Default.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            }
            formatPlaybackDuration((actualDuration * 1000).toLong())?.let { label ->
                Text(
                    label,
                    Modifier.align(Alignment.BottomEnd).padding(4.dp)
                        .background(Color.Black.copy(alpha = .62f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    style = com.armsone.nasfinder.ui.theme.PorcelainType.Badge,
                    color = Color.White,
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                draft.displayTitle,
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "$modeTitle · ${draft.sources.size}개 · ${trailerLengthTitle(actualDuration)}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "마지막 편집 $lastEdited",
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Default.Delete, "지우기", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
        }
        Icon(Icons.Default.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** First real scene thumbnail of a memory draft (existing source bitmap, else the existing inbox decoder). */
@Composable
internal fun rememberTrailerDraftCover(draft: TrailerDraft, maxPixelSize: Int = 160): android.graphics.Bitmap? {
    var thumbnailBitmap by remember(draft.id) {
        mutableStateOf(draft.sources.firstOrNull { it.thumbnail != null }?.thumbnail)
    }
    val context = LocalContext.current
    LaunchedEffect(draft.id) {
        if (thumbnailBitmap == null) {
            val firstMedia = draft.sources.firstOrNull()
            if (firstMedia != null && firstMedia.file.exists()) {
                withContext(Dispatchers.IO) {
                    val thumb = decodeInboxThumbnail(
                        context = context,
                        uri = null,
                        file = firstMedia.file,
                        isImage = firstMedia.isImage,
                        isVideo = !firstMedia.isImage,
                        isPdf = false,
                        maxPixelSize = maxPixelSize,
                    )
                    withContext(Dispatchers.Main) {
                        thumbnailBitmap = thumb
                    }
                }
            }
        }
    }
    return thumbnailBitmap
}

/** Draft length exactly as the draft manager shows it: completed output metadata, else the planned scene sum. */
@Composable
internal fun rememberTrailerDraftDuration(draft: TrailerDraft): Double {
    // 편집본이 있으면 출력 리비전이 현재와 일치함이 확인될 때만 출력 길이를 쓴다(알 수 없는 레거시 출력은 낡은 것으로 취급).
    val outputStale = if (draft.editor != null) {
        draft.revision == null || draft.outputRevision == null || draft.outputRevision != draft.revision
    } else {
        draft.revision != null && draft.outputRevision != null && draft.outputRevision != draft.revision
    }
    val hasCompletedOutput = draft.output != null && draft.output.exists() && draft.output.length() > 0 && !outputStale
    return remember(draft.id, draft.timestamp, draft.output, draft.revision, draft.outputRevision, hasCompletedOutput) {
        if (hasCompletedOutput) {
            val duration = runCatching {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(draft.output!!.absolutePath)
                    retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000.0)
                } finally {
                    retriever.release()
                }
            }.getOrNull()
            if (duration != null && duration.isFinite() && duration > 0.0) duration else null
        } else null
    } ?: remember(draft.id, draft.sources, draft.total, draft.clipLength, draft.photoClipLength, draft.musicTrack, draft.musicPolicy, draft.musicFitCount, draft.editor) {
        val scenes = if (draft.editor != null) {
            // 저장된 편집 장면이 있으면 계획이 아니라 실제 편집 결과 길이를 보여준다.
            draft.editor.toTrailerScenes(draft.sources)
        } else if (draft.isMusicOverlayMode) {
            musicOverlayScenes(draft.sources, draft.photoClipLength, draft.musicTrack, draft.musicPolicy, draft.musicFitCount)
        } else {
            trailerScenes(draft.sources, draft.total, draft.clipLength, draft.photoClipLength, draft.musicTrack, draft.musicPolicy, draft.musicFitCount)
        }
        val plannedSum = scenes.sumOf { it.length }
        if (plannedSum.isFinite() && plannedSum > 0.0) plannedSum
        else if (draft.total > 0.0) draft.total
        else draft.clipLength
    }
}


/** Flat theme page colour; the six themes carry no background art. */
private fun skyBrush(theme: AppTheme): Brush = SolidColor(paletteFor(theme).background)

/**
 * Phone bottom bar: exactly THREE visible main tabs (감상 / 영상 만들기 / 도구).
 * Retains all internal routes/keys; folds home+finder under 감상, and exposes workbench via toolbar capsule/sheet.
 */
@Composable
private fun MainTabBar(
    selected: MainNavigationDestination,
    onTogglePrivacy: () -> Unit,
    onSelect: (MainNavigationDestination) -> Unit,
) {
    val tabs = listOf(
        Triple(MainNavigationDestination.HOME, "감상", PackGlyph.PLAY),
        Triple(MainNavigationDestination.VIDEO, "영상 만들기", PackGlyph.VIDEO),
        Triple(MainNavigationDestination.TOOLS, "도구", PackGlyph.TRANSFER),
    )
    val current = selected
    Column(Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)).navigationBarsPadding()) {
        HorizontalDivider(thickness = .5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).selectableGroup()) {
            tabs.forEach { (tab, label, glyph) ->
                val isSelected = when (tab) {
                    MainNavigationDestination.HOME -> current == MainNavigationDestination.HOME || current == MainNavigationDestination.FINDER
                    else -> current == tab
                }
                val color = if (isSelected) themePalette().accent else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    Modifier.weight(1f).heightIn(min = 49.dp)
                        .then(
                            if (tab == MainNavigationDestination.TOOLS) {
                                // 도구 탭을 길게 누르면 프라이버시 모드가 바뀐다. 짧게 누르는 동작(도구 열기)은 그대로다.
                                Modifier
                                    .semantics {
                                        this.selected = isSelected
                                        customActions = listOf(
                                            CustomAccessibilityAction(if (com.armsone.nasfinder.data.PrivacyMode.isActive) "프라이버시 모드 끄기" else "프라이버시 모드 켜기") { onTogglePrivacy(); true },
                                        )
                                    }
                                    .combinedClickable(
                                        role = Role.Tab,
                                        onClickLabel = "도구 열기",
                                        onLongClickLabel = if (com.armsone.nasfinder.data.PrivacyMode.isActive) "프라이버시 모드 끄기" else "프라이버시 모드 켜기",
                                        onClick = { onSelect(tab) },
                                        onLongClick = onTogglePrivacy,
                                    )
                            } else {
                                Modifier.selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(tab) })
                            },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                ) {
                    PackIcon(glyph, null, Modifier.size(26.dp), tint = color)
                    Text(label, style = com.armsone.nasfinder.ui.theme.HomeType.FavoriteCaption, color = color, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun DashboardScreen(state: AppState, model: NasFinderViewModel) {
    var connectionPendingDeletion by remember { mutableStateOf<RemoteConnection?>(null) }
    var addingNetwork by remember { mutableStateOf(false) }
    var editingGooglePhotos by remember { mutableStateOf(false) }
    var deletingGooglePhotos by remember { mutableStateOf(false) }
    var googlePhotosName by remember(state.googlePhotosName) { mutableStateOf(state.googlePhotosName) }
    var favoritePendingDeletion by remember { mutableStateOf<RemoteFavorite?>(null) }
    var themeMenuExpanded by remember { mutableStateOf(false) }
    val fileImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        model.importPickedFiles(uris)
    }
    val privacyActive by com.armsone.nasfinder.data.PrivacyMode.active.collectAsStateWithLifecycle()
    val toolsTab = state.activeMainTab == MainNavigationDestination.TOOLS
    val videoTab = state.activeMainTab == MainNavigationDestination.VIDEO
    val finderRoot = state.activeMainTab == MainNavigationDestination.FINDER
    val homeRoot = state.activeMainTab == MainNavigationDestination.HOME
    val workbenchTab = state.activeMainTab == MainNavigationDestination.WORKBENCH
    val finderTab = !toolsTab && !videoTab && !finderRoot && !homeRoot && !workbenchTab
    BackHandler(enabled = (finderRoot || workbenchTab) && !rememberWideShell()) {
        model.selectMainDestination(MainNavigationDestination.HOME)
    }
    Scaffold(containerColor = Color.Transparent, topBar = {
        TopAppBar(
            navigationIcon = {
                if ((finderRoot || workbenchTab) && !rememberWideShell()) {
                    IconButton(onClick = { model.selectMainDestination(MainNavigationDestination.HOME) }) {
                        PackIcon(PackGlyph.BACK, "홈으로 돌아가기", tint = MaterialTheme.colorScheme.onBackground)
                    }
                }
            },
            title = {
            if (homeRoot || finderTab) {
                Row(
                    modifier = Modifier
                        .semantics(mergeDescendants = true) { contentDescription = if (privacyActive) com.armsone.nasfinder.data.PrivacyMode.BRAND_NAME else "NasFinder" }
                        // iOS logo: a tap goes to 홈, a long press reopens the last network folder.
                        // 프라이버시 모드에서는 한 번 누르면 모드를 끄고 홈으로 가며, 길게 누르기(마지막 폴더 열기)는 없다.
                        .combinedClickable(
                            onClickLabel = if (privacyActive) "프라이버시 모드를 끄고 홈으로 이동합니다." else "홈으로 이동합니다.",
                            onLongClickLabel = if (privacyActive) null else "마지막으로 보던 네트워크 폴더를 엽니다.",
                            role = Role.Button,
                            onClick = {
                                if (privacyActive) model.exitPrivacyToHome()
                                else model.selectMainDestination(MainNavigationDestination.HOME)
                            },
                            onLongClick = if (privacyActive) null else model::resumeLastLocation,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Approved 01-home header: 24 NAS glyph, gap 8, two-tone "NasFinder" wordmark (brand 22/700/28), no capsule.
                    if (usesUnifiedLayout(state.theme)) PackIcon(PackGlyph.BRAND, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onBackground) else Image(
                        painterResource(launcherIconDrawable(state.launcherIcon)),
                        null,
                        Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).border(.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .08f), RoundedCornerShape(10.dp)),
                    )
                    if (usesUnifiedLayout(state.theme)) Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            if (privacyActive) {
                                pushStyle(androidx.compose.ui.text.SpanStyle(color = themePalette().accent))
                                append(com.armsone.nasfinder.data.PrivacyMode.BRAND_NAME)
                                pop()
                            } else {
                                append("Nas")
                                // "Finder" in the theme accent (BK Style keeps the approved ornament #E44435).
                                pushStyle(androidx.compose.ui.text.SpanStyle(color = themePalette().accent))
                                append("Finder")
                                pop()
                            }
                        },
                        style = com.armsone.nasfinder.ui.theme.HomeType.Brand,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                    ) else Text(
                        if (privacyActive) com.armsone.nasfinder.data.PrivacyMode.BRAND_NAME else "NasFinder",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .82f),
                    )
                }
            } else {
                Text(if (toolsTab) "도구" else if (videoTab) "영상" else if (workbenchTab) "작업대" else "파인더", style = com.armsone.nasfinder.ui.theme.HomeType.Section, color = MaterialTheme.colorScheme.onBackground)
            }
        }, actions = {
            // iOS porcelainTrailingItems: 네트워크 추가(+) leads the trailing toolbar group.
            val settingsButton: @Composable () -> Unit = {
                IconButton(onClick = { model.show(Screen.Settings) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Settings, "설정", Modifier.size(28.dp)) }
            }
            if (usesUnifiedLayout(state.theme) && (finderTab || homeRoot)) {
                val workbenchCount = state.materials.size
                Surface(
                    onClick = { model.selectMainDestination(MainNavigationDestination.WORKBENCH) },
                    shape = RoundedCornerShape(99.dp),
                    color = if (workbenchTab) themePalette().accent.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, if (workbenchTab) themePalette().accent else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.padding(end = 4.dp).height(32.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        PackIcon(PackGlyph.WORKBENCH, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
                        Text("작업대", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        if (workbenchCount > 0) {
                            Text(
                                "$workbenchCount",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = themePalette().accent,
                            )
                        }
                    }
                }
                // Compact, independent actions: glyph 24 in a 44 hit; order (추가 · 설정 · 테마) unchanged.
                IconButton(onClick = { addingNetwork = true }, modifier = Modifier.size(44.dp)) {
                    PackIcon(PackGlyph.ADD, "네트워크 추가", Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onBackground)
                }
                IconButton(onClick = { model.show(Screen.Settings) }, modifier = Modifier.size(44.dp)) {
                    PackIcon(PackGlyph.SETTINGS, "설정", Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onBackground)
                }
            }
            // The small theme capsule keeps its look but gets a full 44 hit target with tap cycle and 500ms longpress menu.
            val currentViewConfig = LocalViewConfiguration.current
            val custom500msConfig = remember(currentViewConfig) {
                object : ViewConfiguration by currentViewConfig {
                    override val longPressTimeoutMillis: Long get() = 500L
                }
            }
            CompositionLocalProvider(LocalViewConfiguration provides custom500msConfig) {
                Box(
                    Modifier
                        .padding(end = 4.dp)
                        .size(44.dp)
                        .semantics {
                            contentDescription = "병기스타일 테마: ${themeTitle(state.theme)} (누르면 ${themeTitle(state.theme.next)}, 길게 누르면 목록)"
                        }
                        .combinedClickable(
                            role = Role.Button,
                            onClickLabel = "누르면 ${themeTitle(state.theme.next)}",
                            onLongClickLabel = "목록 열기",
                            onClick = { model.setTheme(state.theme.next) },
                            onLongClick = { themeMenuExpanded = true },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.animation.Crossfade(
                        targetState = state.theme,
                        animationSpec = androidx.compose.animation.core.tween(
                            durationMillis = 200,
                            easing = androidx.compose.animation.core.CubicBezierEasing(.42f, 0f, .58f, 1f),
                        ),
                        modifier = Modifier.size(24.dp),
                        label = "themeBadge",
                    ) { theme ->
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            ThemePackIcon(
                                theme,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = paletteFor(theme).accent,
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = themeMenuExpanded,
                        onDismissRequest = { themeMenuExpanded = false },
                    ) {
                        AppTheme.entries.forEach { theme ->
                            val isCurrent = theme == state.theme
                            val badge = themeChooserBadge(theme)
                            DropdownMenuItem(
                                modifier = Modifier.height(44.dp),
                                contentPadding = PaddingValues(0.dp),
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(start = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(CircleShape)
                                                .background(badge.fill)
                                                .border(1.dp, badge.glyph, CircleShape),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            ThemePackIcon(
                                                theme,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp),
                                                tint = badge.glyph,
                                            )
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            themeTitle(theme),
                                            modifier = Modifier.weight(1f),
                                            fontSize = 14.sp,
                                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                        )
                                        Box(
                                            modifier = Modifier.padding(end = 12.dp).width(20.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (isCurrent) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = "선택됨",
                                                    modifier = Modifier.size(12.dp),
                                                    tint = badge.glyph,
                                                )
                                            }
                                        }
                                    }
                                },
                                onClick = {
                                    themeMenuExpanded = false
                                    model.setTheme(theme)
                                },
                            )
                        }
                    }
                }
            }
            if (!usesUnifiedLayout(state.theme) && (finderTab || homeRoot)) settingsButton()
        }, expandedHeight = 52.dp, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent))
    }, bottomBar = {
        // Wide windows navigate with the persistent sidebar only; the bottom tabs would repeat the same five destinations.
        if (!rememberWideShell()) MainTabBar(state.activeMainTab, onTogglePrivacy = model::togglePrivacyMode) { model.selectMainDestination(it) }
    }) { padding ->
        val favoritesItems: LazyListScope.() -> Unit = {
            item { SectionTitle("즐겨찾기", Icons.Default.Star) }
            if (state.remoteFavorites.isNotEmpty()) {
                item {
                    DashboardCard {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp)) {
                            FavoriteShelf(
                                favorites = state.remoteFavorites,
                                connections = state.connections,
                                thumbnails = state.favoriteThumbnails,
                                thumbnailGeneration = state.favoriteThumbnailGeneration,
                                theme = state.theme,
                                onOpen = model::openFavorite,
                                onLoadThumbnail = model::loadFavoriteThumbnail,
                                onRemoveRequest = { favoritePendingDeletion = it },
                                onMove = model::moveFavorite,
                                onMenuRequest = model::openFavoriteActions,
                            )
                        }
                    }
                }
            } else {
                item {
                    Text(
                        "파일이나 폴더를 길게 눌러 즐겨찾기에 추가하면 이곳에 대표 썸네일이 표시됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            // 위치: NAS · 내 사진/영상 · 폰하드 · Google 포토 · Browser · 추가
            item {
                Box(Modifier.padding(top = 4.dp)) { SectionTitle("파일 열기", Icons.Default.Folder) }
            }
            item {
                DashboardCard {
                    state.connections.forEach { connection ->
                        ConnectionRow(
                            connection,
                            state.preferredId == connection.id,
                            state.theme,
                            onOpen = { model.resumeConnection(connection) },
                            onEdit = { model.show(Screen.AddConnection(connection)) },
                            onDelete = { connectionPendingDeletion = connection },
                            onPreferred = { model.setPreferred(if (state.preferredId == connection.id) null else connection) },
                            onUp = { model.moveConnection(connection, -1) },
                            onDown = { model.moveConnection(connection, 1) },
                        )
                        DashboardRowDivider()
                    }
                    DashboardRow(
                        Icons.Default.PhotoLibrary,
                        "내 미디어",
                        null,
                        iconTint = MaterialTheme.colorScheme.primary,
                        titleColor = MaterialTheme.colorScheme.onSurface,
                    ) { model.show(Screen.LocalPhotoLibrary()) }
                    DashboardRowDivider()
                    PhoneHardDashboardRow(
                        detail = "${state.inboxFiles.size}개",
                        draftCount = state.trailerDraftCount,
                        onClick = { model.show(Screen.Inbox) },
                        onLongClick = model::openPhoneHardLongTouch,
                    )
                    DashboardRowDivider()
                    if (state.googlePhotosVisible) {
                        ConnectionRow(
                            RemoteConnection(id = "google-photos-location", name = state.googlePhotosName,
                                kind = ConnectionKind.GOOGLE_DRIVE, host = "", username = ""),
                            preferred = false, theme = state.theme,
                            onOpen = model::requestGooglePhotosImport,
                            onEdit = { googlePhotosName = state.googlePhotosName; editingGooglePhotos = true },
                            onDelete = { deletingGooglePhotos = true },
                            onPreferred = {}, onUp = {}, onDown = {},
                            iconOverride = Icons.Default.PhotoLibrary,
                            googleMark = true,
                            badgeLabel = "Google 포토",
                            detailOverride = "",
                            supportsOrdering = false,
                        )
                        DashboardRowDivider()
                    }
                    BrowserDashboardRow { model.show(Screen.WebBrowser) }
                    DashboardRowDivider()
                    DashboardRow(
                        Icons.Default.Add,
                        "네트워크 추가",
                        null,
                        iconTint = if (state.connections.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        titleColor = if (state.connections.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    ) { addingNetwork = true }
                }
            }
        }
        val workItems: LazyListScope.() -> Unit = {
            // 영상 작업: 만들기 · 이어서 편집
            item { SectionTitle("영상 작업", Icons.Default.MovieFilter) }
            item {
                Surface(
                    onClick = { model.show(Screen.LocalPhotoLibrary(LocalMediaEditIntent.TRAILER)) },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.VideoCall, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(12.dp))
                        Text("영상 만들기", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
            item {
                DashboardCard {
                    DashboardRow(
                        Icons.Default.MovieFilter,
                        "편집본",
                        if (state.trailerDraftCount > 0) "${state.trailerDraftCount}개 보관됨 · 이어서 편집" else "없음",
                        iconTint = MaterialTheme.colorScheme.primary,
                        titleColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        model.openPhoneHardLongTouch()
                    }
                }
            }
            // 유틸리티
            item {
                Box(Modifier.padding(top = 4.dp)) { SectionTitle("유틸리티", Icons.Default.Tune) }
            }
            item {
                DashboardCard {
                    DashboardRow(
                        Icons.AutoMirrored.Filled.CompareArrows,
                        "미디어 전송",
                        null,
                    ) {
                        model.show(Screen.PhotoTransfer)
                    }
                    DashboardRowDivider()
                    DashboardRow(Icons.Default.PhotoLibrary, "사진 선별", null) {
                        model.show(Screen.PhotoSelection)
                    }
                    DashboardRowDivider()
                    DashboardRow(Icons.Default.Settings, "설정", null) {
                        model.show(Screen.Settings)
                    }
                }
            }
            item {
                DeviceStorageCard { fileImporter.launch(arrayOf("*/*")) }
            }
            item {
                Text(
                    "버전 ${BuildConfig.VERSION_NAME} · 빌드 ${BuildConfig.BUILD_NUMBER}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
                )
            }
        }
        if (toolsTab) {
            Box(Modifier.padding(padding)) {
                PorcelainWideHost(state, model, activeRoot = MainNavigationDestination.TOOLS) { PorcelainTools(state, model) }
            }
        } else if (videoTab) {
            Box(Modifier.padding(padding)) {
                PorcelainWideHost(state, model, activeRoot = MainNavigationDestination.VIDEO) { PorcelainVideoHub(state, model) }
            }
        } else if (workbenchTab) {
            // Wide windows keep the persistent sidebar (작업대 selected); narrow windows render the content alone.
            Box(Modifier.padding(padding)) {
                PorcelainWideHost(state, model, activeRoot = MainNavigationDestination.WORKBENCH) {
                    WorkbenchScreen(state, model, embedded = true)
                }
            }
        } else if (homeRoot) {
            // Wide windows keep the existing persistent sidebar beside the 홈 root; narrow windows render the content alone.
            Box(Modifier.padding(padding)) {
                PorcelainWideHost(state, model, activeRoot = MainNavigationDestination.HOME) {
                    PorcelainPhoneHome(
                        state = state,
                        model = model,
                        onAddNetwork = { addingNetwork = true },
                        onEditGooglePhotos = { googlePhotosName = state.googlePhotosName; editingGooglePhotos = true },
                        onDeleteGooglePhotos = { deletingGooglePhotos = true },
                        onDeleteConnection = { connectionPendingDeletion = it },
                        onRemoveFavorite = { favoritePendingDeletion = it },
                        onImportFiles = { fileImporter.launch(arrayOf("*/*")) },
                    )
                }
            }
        } else if (finderRoot) {
            Box(Modifier.padding(padding)) {
                PorcelainWideHost(state, model, activeRoot = MainNavigationDestination.FINDER) {
                    PorcelainFinder(
                        state, model,
                        onAddNetwork = { addingNetwork = true },
                        onEditGooglePhotos = { googlePhotosName = state.googlePhotosName; editingGooglePhotos = true },
                        onDeleteGooglePhotos = { deletingGooglePhotos = true },
                        onDeleteConnection = { connectionPendingDeletion = it },
                        onRemoveFavorite = { favoritePendingDeletion = it },
                        onImportFiles = { fileImporter.launch(arrayOf("*/*")) },
                    )
                }
            }
        } else if (usesUnifiedLayout(state.theme)) {
            PorcelainHome(
                state, model,
                onAddNetwork = { addingNetwork = true },
                onEditGooglePhotos = { googlePhotosName = state.googlePhotosName; editingGooglePhotos = true },
                onDeleteGooglePhotos = { deletingGooglePhotos = true },
                onDeleteConnection = { connectionPendingDeletion = it },
                onRemoveFavorite = { favoritePendingDeletion = it },
                onImportFiles = { fileImporter.launch(arrayOf("*/*")) },
                modifier = Modifier.padding(padding),
            )
        } else BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp)
            if (maxWidth >= 720.dp) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LazyColumn(Modifier.weight(1.25f).fillMaxHeight(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) { favoritesItems() }
                    LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) { workItems() }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    favoritesItems()
                    workItems()
                }
            }
        }
    }
    NetworkAddDialogs(state, model, addingNetwork, { addingNetwork = it }, editingGooglePhotos, { editingGooglePhotos = it }, googlePhotosName, { googlePhotosName = it })
    if (deletingGooglePhotos) {
        AlertDialog(
            onDismissRequest = { deletingGooglePhotos = false }, title = { Text("Google 포토 연결을 삭제할까요?") },
            text = { Text("나스파인더의 네트워크 목록에서만 제거합니다. Google 계정, 원본 미디어, 선택한 항목과 가져온 파일은 그대로 유지됩니다. 네트워크 추가에서 다시 추가할 수 있습니다.") },
            confirmButton = { TextButton(onClick = {
                model.saveGooglePhotosLocation(state.googlePhotosName, visible = false)
                deletingGooglePhotos = false
            }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { deletingGooglePhotos = false }) { Text("취소") } },
        )
    }
    connectionPendingDeletion?.let { connection ->
        AlertDialog(
            onDismissRequest = { connectionPendingDeletion = null },
            title = { Text("연결을 삭제할까요?") },
            text = { Text("${connection.name}의 저장된 로그인 정보와 파일 앱 위치가 이 Android 기기에서 제거됩니다. 서버의 파일은 삭제되지 않습니다.") },
            dismissButton = {
                TextButton(onClick = { connectionPendingDeletion = null }) { Text("취소") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        connectionPendingDeletion = null
                        model.removeConnection(connection)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("삭제") }
            },
        )
    }
    favoritePendingDeletion?.let { favorite ->
        FavoriteRemovalDialog(favorite, onDismiss = { favoritePendingDeletion = null }) {
            favoritePendingDeletion = null
            model.removeFavorite(favorite)
        }
    }
    HomeFavoriteActionHost(state, model)
}

/** Unregisters exactly one favorite entry; the source file or folder is never touched (that is the separate 삭제 action). */
@Composable
private fun FavoriteRemovalDialog(favorite: RemoteFavorite, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("즐겨찾기에서 제거할까요?") },
        text = { Text("‘${favorite.name}’ 항목만 즐겨찾기에서 제거합니다. ${if (favorite.connectionId == "phonehard") "폰하드" else "서버"}의 파일이나 폴더는 삭제되지 않습니다.") },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("즐겨찾기에서 제거") }
        },
    )
}

/**
 * Home favorite hold-release task panel, drawn over the unchanged home screen (content, scroll position, screen and history
 * stay as they were). Only this flow's dialogs live here; the ViewModel's dedicated action owner keeps the favorite's own
 * service alive while the flow is active and while any deferred command it started still holds its lease.
 */
@Composable
private fun HomeFavoriteActionHost(state: AppState, model: NasFinderViewModel) {
    val action = state.homeFavoriteAction ?: return
    key(action.token) { HomeFavoriteActionFlow(action, state, model) }
}

private enum class HomeFavoriteFolderMedia { TRAILER, MUSIC }
private enum class HomeFavoritePhoneHardPicker { COPY, MOVE, DOWNLOAD }

@Composable
private fun HomeFavoriteActionFlow(action: HomeFavoriteAction, state: AppState, model: NasFinderViewModel) {
    val token = action.token
    val favorite = action.favorite
    val target = action.target
    val isPhoneHard = favorite.connectionId == "phonehard"
    val context = LocalContext.current
    var sheetVisible by remember { mutableStateOf(true) }
    var confirmRemoval by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var waitingForFolderCheck by remember { mutableStateOf(false) }
    var folderMediaChoice by remember { mutableStateOf<HomeFavoriteFolderMedia?>(null) }
    var downloadDestination by remember { mutableStateOf(false) }
    var phoneHardPicker by remember { mutableStateOf<HomeFavoritePhoneHardPicker?>(null) }
    var waitingForExternalTree by remember { mutableStateOf(false) }
    var operationStatus by remember { mutableStateOf<String?>(null) }
    var toastMessage by remember { mutableStateOf<String?>(null) }

    // The flow (and its UI lease) ends as soon as nothing of it is pending: cancel, a dispatched command, or a finished op.
    val flowActive = sheetVisible || confirmRemoval || renameText != null || confirmDelete || waitingForFolderCheck ||
        folderMediaChoice != null || downloadDestination || phoneHardPicker != null || waitingForExternalTree ||
        operationStatus != null || toastMessage != null
    LaunchedEffect(flowActive) { if (!flowActive) model.endFavoriteActions(token) }
    DisposableEffect(token) {
        onDispose {
            // A rotation rebuilds this host from the same state; any real departure from home ends the flow.
            if (context.findHostActivity()?.isChangingConfigurations != true) model.endFavoriteActions(token)
        }
    }

    val remote = target as? HomeFavoriteTarget.Remote
    val phoneHardTarget: PhoneHardTarget? = when (target) {
        is HomeFavoriteTarget.PhoneHardFolder -> PhoneHardTarget(id = null, relativePath = target.folder.relativePath, isDirectory = true)
        is HomeFavoriteTarget.PhoneHardFile -> PhoneHardTarget(id = target.file.id, relativePath = target.file.relativePath, isDirectory = false)
        else -> null
    }
    // File commands are closed until the fresh target is validated, and stay closed when it cannot be.
    val closedReason = when (target) {
        HomeFavoriteTarget.Preparing -> "항목을 확인하는 중입니다. 잠시 후 다시 시도해 주세요."
        is HomeFavoriteTarget.Unavailable -> target.reason
        else -> null
    }
    val externalTreeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) {
            if (isPhoneHard) {
                val targets = listOfNotNull(phoneHardTarget)
                if (targets.isNotEmpty()) {
                    operationStatus = "외부 폴더로 내보내는 중입니다…"
                    model.exportPhoneHardTargetsToExternalTree(targets, treeUri) { result ->
                        operationStatus = null
                        when (result) {
                            is NetworkDownloadResult.Success -> toastMessage = result.message
                            is NetworkDownloadResult.Failure -> model.showMessage(result.message)
                            is NetworkDownloadResult.Cancelled -> toastMessage = "내보내기가 취소되었습니다."
                            is NetworkDownloadResult.Skipped -> model.showMessage(result.reason)
                        }
                    }
                }
            } else {
                model.homeFavoriteDownload(token, NetworkDownloadDestination.ExternalTree(treeUri))
            }
        }
        waitingForExternalTree = false
    }

    fun phoneHardResultMessage(result: com.armsone.nasfinder.data.PhoneHardOperationResult, verb: String): String? {
        if (result.failed.isEmpty()) return null
        val errorDetail = result.failed.first().second
        return when {
            result.succeeded.isEmpty() && result.failed.size == 1 -> errorDetail
            result.succeeded.isEmpty() -> "${result.failed.size}개 항목을 ${verb}하지 못했습니다: $errorDetail"
            else -> "${result.succeeded.size}개 항목을 ${verb}했지만 ${result.failed.size}개는 ${verb}하지 못했습니다: $errorDetail"
        }
    }

    fun startFolderMediaCheck(kind: HomeFavoriteFolderMedia) {
        if (isPhoneHard) {
            val folderPath = phoneHardTarget?.relativePath ?: return
            if (model.hasInboxSubfolders(folderPath)) folderMediaChoice = kind
            else if (kind == HomeFavoriteFolderMedia.TRAILER) model.startInboxFolderTrailer(folderPath, includeSubfolders = false)
            else model.startInboxFolderMusicOverlay(folderPath, includeSubfolders = false)
            return
        }
        waitingForFolderCheck = true
        model.homeFavoriteCheckSubfolders(
            token,
            onResult = { hasSubfolders ->
                if (hasSubfolders) folderMediaChoice = kind
                else if (kind == HomeFavoriteFolderMedia.TRAILER) model.homeFavoriteFolderTrailer(token, includeSubfolders = false)
                else model.homeFavoriteFolderMusicOverlay(token, includeSubfolders = false)
                waitingForFolderCheck = false
            },
            onFailure = { waitingForFolderCheck = false },
        )
    }

    if (sheetVisible) {
        val title: String
        val isFolder: Boolean
        val isVideo: Boolean
        val isImage: Boolean
        val mime: String
        val dateText: String
        val sizeText: String
        val durationText: String?
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        when (target) {
            is HomeFavoriteTarget.Remote -> {
                val item = target.item
                title = item.name; isFolder = item.isDirectory; isVideo = item.isVideo; isImage = item.isImage
                mime = item.mimeType.orEmpty()
                dateText = item.modifiedAt?.let { dateFormat.format(Date.from(it)) } ?: "알 수 없음"
                sizeText = if (item.isDirectory) "폴더" else formatBytes(item.size)
                durationText = null
            }
            is HomeFavoriteTarget.PhoneHardFolder -> {
                title = target.folder.name; isFolder = true; isVideo = false; isImage = false; mime = ""
                dateText = "알 수 없음"; sizeText = "폴더"; durationText = null
            }
            is HomeFavoriteTarget.PhoneHardFile -> {
                val file = target.file
                title = file.originalFilename; isFolder = false; isVideo = file.isTrailerVideo
                mime = file.mimeType.orEmpty(); isImage = mime.startsWith("image/")
                dateText = dateFormat.format(Date.from(file.importedAt))
                sizeText = formatBytes(file.byteCount)
                durationText = formatPlaybackDuration(file.durationMillis)
            }
            else -> {
                val item = favorite.toRemoteFileItem()
                title = favorite.name; isFolder = favorite.isDirectory; isVideo = item.isVideo; isImage = item.isImage
                mime = favorite.mimeType.orEmpty()
                dateText = favorite.modifiedAt?.let { dateFormat.format(Date.from(it)) } ?: "알 수 없음"
                sizeText = if (favorite.isDirectory) "폴더" else favorite.size?.let(::formatBytes) ?: "알 수 없음"
                durationText = null
            }
        }
        val capabilities = remote?.let { browserCapabilities(it.connection.kind) }
        fun fileAction(
            id: String,
            title: String,
            icon: androidx.compose.ui.graphics.vector.ImageVector,
            supported: Boolean = true,
            unsupportedReason: String? = null,
            isDestructive: Boolean = false,
            onClick: () -> Unit,
        ) = BaselineAction(
            id = id,
            title = title,
            icon = icon,
            enabled = closedReason == null && supported,
            disabledReason = closedReason ?: if (!supported) unsupportedReason else null,
            isDestructive = isDestructive,
            onClick = { sheetVisible = false; onClick() },
        )
        val row1 = listOf(
            fileAction("select", "선택", Icons.Default.CheckCircle) { model.homeFavoriteSelect(token) },
            fileAction(
                "copy", "복사", Icons.Default.ContentCopy,
                supported = isPhoneHard || capabilities?.copy != false, unsupportedReason = "복사가 지원되지 않는 연결입니다.",
            ) {
                if (isPhoneHard) phoneHardPicker = HomeFavoritePhoneHardPicker.COPY
                else model.homeFavoriteBeginTransfer(token, RemoteTransferAction.COPY)
            },
            fileAction(
                "move", "이동", Icons.Default.DriveFileMove,
                supported = isPhoneHard || capabilities?.move != false, unsupportedReason = "이동이 지원되지 않는 연결입니다.",
            ) {
                if (isPhoneHard) phoneHardPicker = HomeFavoritePhoneHardPicker.MOVE
                else model.homeFavoriteBeginTransfer(token, RemoteTransferAction.MOVE)
            },
            fileAction(
                "rename", "수정", Icons.Default.Edit,
                supported = isPhoneHard || capabilities?.rename != false, unsupportedReason = "이름 수정이 지원되지 않는 연결입니다.",
            ) { renameText = title },
            fileAction(
                "delete", "삭제", Icons.Default.Delete,
                supported = isPhoneHard || capabilities?.delete != false, unsupportedReason = "삭제가 지원되지 않는 연결입니다.",
                isDestructive = true,
            ) { confirmDelete = true },
            // Reference only (file or folder): the favorite's own connection and path are saved, nothing is read or copied.
            fileAction("workbench", "작업대에 담기", Icons.Default.Inbox) { model.requestWorkbenchAddFavorites(listOf(favorite)) },
        )
        val mediaReason = if (isPhoneHard) "예고편 보기와 뮤직비디오는 영상과 폴더에서 쓸 수 있습니다."
        else "예고편 보기와 뮤직비디오는 사진, 영상과 폴더에서 쓸 수 있습니다."
        val canMedia = isFolder || isVideo || (!isPhoneHard && isImage)
        val isThumbnailActive = if (isPhoneHard) state.isLocalThumbnailRunning
        else state.superThumbnailWork?.status in setOf(SuperThumbnailWorkStatus.WAITING, SuperThumbnailWorkStatus.RUNNING)
        val canThumbnail = if (isPhoneHard) {
            isFolder || isVideo || title.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "pdf")
        } else isFolder
        val row2 = listOf(
            // The original favorite entry itself: only its own unregistration, reachable even when file commands are closed.
            BaselineAction(
                id = "favorite",
                title = "즐겨찾기 해제",
                icon = Icons.Default.Star,
                enabled = true,
                onClick = { sheetVisible = false; confirmRemoval = true },
            ),
            // 이 앱의 목록에서만 숨긴다. 일반 모드에서 비공개로 바꾸면 이 작업창은 곧바로 닫힌다.
            BaselineAction(
                id = "favorite-privacy",
                title = if (favorite.isPrivate) "공개로 전환" else "비공개로 전환",
                icon = if (favorite.isPrivate) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                enabled = true,
                onClick = { sheetVisible = false; model.setRemoteFavoritePrivate(favorite.id, !favorite.isPrivate) },
            ),
            fileAction("trailer", "예고편 보기", Icons.Default.Movie, supported = canMedia, unsupportedReason = mediaReason) {
                when (target) {
                    is HomeFavoriteTarget.PhoneHardFile -> model.startInboxTrailer(listOf(target.file.id))
                    is HomeFavoriteTarget.Remote -> if (target.item.isDirectory) startFolderMediaCheck(HomeFavoriteFolderMedia.TRAILER) else model.homeFavoriteTrailer(token)
                    else -> startFolderMediaCheck(HomeFavoriteFolderMedia.TRAILER)
                }
            },
            fileAction("music", "뮤직비디오", Icons.Default.MusicNote, supported = canMedia, unsupportedReason = mediaReason) {
                when (target) {
                    is HomeFavoriteTarget.PhoneHardFile -> model.startInboxMusicOverlay(listOf(target.file.id), null)
                    is HomeFavoriteTarget.Remote -> if (target.item.isDirectory) startFolderMediaCheck(HomeFavoriteFolderMedia.MUSIC) else model.homeFavoriteMusicOverlay(token)
                    else -> startFolderMediaCheck(HomeFavoriteFolderMedia.MUSIC)
                }
            },
            fileAction("download", "다운로드", Icons.Default.Download) { downloadDestination = true },
            fileAction("share", "공유", Icons.Default.Share) {
                when (target) {
                    is HomeFavoriteTarget.PhoneHardFolder -> model.shareInboxFolder(target.folder.relativePath)
                    is HomeFavoriteTarget.PhoneHardFile -> model.shareInboxFile(target.file)
                    else -> model.homeFavoriteShare(token)
                }
            },
            fileAction(
                "thumbnail", if (isThumbnailActive) "생성 중지" else "썸네일 만들기",
                if (isThumbnailActive) Icons.Default.Stop else Icons.Default.Image,
                supported = canThumbnail,
                unsupportedReason = if (isPhoneHard) "사진 또는 영상 파일만 썸네일을 만들 수 있습니다." else "폴더 항목만 썸네일 생성을 지원합니다.",
                isDestructive = isThumbnailActive,
            ) {
                if (isPhoneHard) {
                    if (isThumbnailActive) model.cancelLocalThumbnailGeneration()
                    else phoneHardTarget?.let { localTarget ->
                        model.startLocalThumbnailGeneration(localTarget) { count -> model.showMessage("${count}개 썸네일을 만들었습니다.") }
                    }
                } else if (isThumbnailActive) {
                    model.cancelSuperThumbnail()
                } else if (remote != null) {
                    model.selectSuperThumbnailFolder(remote.connection.id, remote.item.path, remote.item.name)
                    model.startSuperThumbnail()
                }
            },
        )
        AppleBaselineActionSheet(
            title = title,
            isFolder = isFolder,
            row1 = row1,
            row2 = row2,
            infoMedia = when {
                isFolder -> "폴더"; isImage -> "사진"; isVideo -> "비디오"; mime.startsWith("audio/") || (!isPhoneHard && remote?.item?.isAudio == true) -> "오디오"; else -> "파일"
            },
            infoFeature = if (isFolder) "하위 항목을 담는 폴더" else listOfNotNull(
                title.substringAfterLast('.', "").uppercase().ifEmpty { null },
                mime.ifBlank { "파일" },
                durationText?.takeIf { it.isNotBlank() },
            ).joinToString(" · "),
            infoDate = dateText,
            infoSize = sizeText,
            onDismiss = { sheetVisible = false },
            onShowDisabledReason = { reason ->
                sheetVisible = false
                toastMessage = reason
            },
        )
    }

    if (confirmRemoval) {
        FavoriteRemovalDialog(favorite, onDismiss = { confirmRemoval = false }) {
            confirmRemoval = false
            model.removeFavorite(favorite)
        }
    }

    renameText?.let { text ->
        NameInputDialog(
            title = "이름 변경",
            value = text,
            confirmLabel = "변경",
            onValueChange = { renameText = it },
            onDismiss = { renameText = null },
            onConfirm = {
                val newName = text.trim()
                if (isPhoneHard) {
                    val localTarget = phoneHardTarget
                    if (newName.isNotEmpty() && localTarget != null) {
                        operationStatus = "이름을 변경하는 중입니다…"
                        model.renamePhoneHardItem(localTarget.relativePath, newName) { success, errMsg ->
                            if (!success && !errMsg.isNullOrBlank()) model.showMessage(errMsg)
                            operationStatus = null
                        }
                    }
                } else if (newName.isNotEmpty()) {
                    model.homeFavoriteRename(token, newName)
                }
                renameText = null
            },
        )
    }

    if (confirmDelete) {
        val deleteTitle = remote?.item?.name ?: phoneHardTarget?.relativePath?.substringAfterLast('/') ?: favorite.name
        val deletesFolder = remote?.item?.isDirectory ?: phoneHardTarget?.isDirectory ?: favorite.isDirectory
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (deletesFolder) "폴더를 삭제할까요?" else "파일을 삭제할까요?") },
            text = {
                Text(
                    if (isPhoneHard) {
                        if (deletesFolder) "'$deleteTitle' 폴더와 내부의 모든 내용이 기기에서 삭제됩니다. 되돌릴 수 없습니다."
                        else "'$deleteTitle' 파일이 기기에서 삭제됩니다. 되돌릴 수 없습니다."
                    } else {
                        if (deletesFolder) "$deleteTitle 폴더와 내부의 모든 항목이 원격 서버에서 삭제됩니다. 되돌릴 수 없습니다."
                        else "$deleteTitle 파일이 원격 서버에서 삭제됩니다. 되돌릴 수 없습니다."
                    }
                )
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (isPhoneHard) {
                            phoneHardTarget?.let { localTarget ->
                                operationStatus = "삭제하는 중입니다…"
                                model.deletePhoneHardTargets(listOf(localTarget)) { result ->
                                    result.failed.firstOrNull()?.let { model.showMessage(it.second) }
                                    operationStatus = null
                                }
                            }
                        } else {
                            model.homeFavoriteDelete(token)
                        }
                        confirmDelete = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("삭제") }
            },
        )
    }

    folderMediaChoice?.let { kind ->
        fun start(includeSubfolders: Boolean) {
            folderMediaChoice = null
            val folderPath = phoneHardTarget?.relativePath
            when {
                isPhoneHard && folderPath != null && kind == HomeFavoriteFolderMedia.TRAILER -> model.startInboxFolderTrailer(folderPath, includeSubfolders)
                isPhoneHard && folderPath != null -> model.startInboxFolderMusicOverlay(folderPath, includeSubfolders)
                kind == HomeFavoriteFolderMedia.TRAILER -> model.homeFavoriteFolderTrailer(token, includeSubfolders)
                else -> model.homeFavoriteFolderMusicOverlay(token, includeSubfolders)
            }
        }
        AlertDialog(
            onDismissRequest = { folderMediaChoice = null },
            title = { Text(if (kind == HomeFavoriteFolderMedia.TRAILER) "영상 만들기" else "뮤직비디오") },
            text = { Text("하위 폴더에 있는 미디어도 함께 모을까요?") },
            confirmButton = { TextButton(onClick = { start(true) }) { Text("하위 폴더 포함") } },
            dismissButton = { TextButton(onClick = { start(false) }) { Text("현재 폴더만") } },
        )
    }

    if (downloadDestination) {
        DownloadDestinationDialog(
            itemCount = 1,
            isLocal = isPhoneHard,
            onDismiss = { downloadDestination = false },
            onSelectPhoneHard = {
                phoneHardPicker = HomeFavoritePhoneHardPicker.DOWNLOAD
                downloadDestination = false
            },
            onSelectExternalSaf = {
                waitingForExternalTree = true
                downloadDestination = false
                externalTreeLauncher.launch(null)
            },
        )
    }

    phoneHardPicker?.let { picker ->
        val localTargets = listOfNotNull(phoneHardTarget)
        SelectPhoneHardDestinationDialog(
            title = when (picker) {
                HomeFavoritePhoneHardPicker.COPY -> "폴더/파일 복사 대상 선택"
                HomeFavoritePhoneHardPicker.MOVE -> "폴더/파일 이동 대상 선택"
                HomeFavoritePhoneHardPicker.DOWNLOAD -> if (isPhoneHard) "폰하드 복사 대상 폴더 선택" else "폰하드 다운로드 대상 폴더 선택"
            },
            model = model,
            targets = localTargets,
            onDismiss = { phoneHardPicker = null },
            onSelectDestination = { dest ->
                when {
                    !isPhoneHard -> model.homeFavoriteDownload(token, NetworkDownloadDestination.PhoneHard(dest))
                    localTargets.isEmpty() -> Unit
                    picker == HomeFavoritePhoneHardPicker.MOVE -> {
                        operationStatus = "이동하는 중입니다…"
                        model.movePhoneHardTargets(localTargets, dest) { result ->
                            phoneHardResultMessage(result, "이동")?.let(model::showMessage)
                            operationStatus = null
                        }
                    }
                    else -> {
                        operationStatus = "복사하는 중입니다…"
                        model.copyPhoneHardTargets(localTargets, dest) { result ->
                            phoneHardResultMessage(result, "복사")?.let(model::showMessage)
                            operationStatus = null
                        }
                    }
                }
                phoneHardPicker = null
            },
        )
    }

    operationStatus?.let { status ->
        Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 72.dp), contentAlignment = Alignment.BottomCenter) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shadowElevation = 6.dp,
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.inverseOnSurface)
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    toastMessage?.let { message ->
        LaunchedEffect(message) {
            delay(2200)
            toastMessage = null
        }
        Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 72.dp), contentAlignment = Alignment.BottomCenter) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shadowElevation = 6.dp,
            ) {
                Text(message, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun launcherIconDrawable(icon: LauncherIconVariant) = when (icon) {
    LauncherIconVariant.DEFAULT -> R.drawable.app_icon_blue_nas
    LauncherIconVariant.CYBER_VAULT -> R.drawable.app_icon_cyber_vault
    LauncherIconVariant.VIBE_CODER -> R.drawable.app_icon_vibe_coder
    LauncherIconVariant.PURPLE_NAS -> R.drawable.app_icon_purple_nas
    LauncherIconVariant.NAS_RADAR -> R.drawable.app_icon_nas_radar
    LauncherIconVariant.ENAMEL -> R.drawable.app_icon_enamel
}

private const val FavoriteHoldDurationMillis = 550L
private val FavoriteHoldAllowableMovement = 10.dp
private val FavoriteReorderActivationDistance = 12.dp

@Composable
internal fun FavoriteShelf(
    favorites: List<RemoteFavorite>,
    connections: List<RemoteConnection>,
    thumbnails: Map<String, android.graphics.Bitmap>,
    thumbnailGeneration: Long,
    theme: AppTheme,
    onOpen: (RemoteFavorite) -> Unit,
    onLoadThumbnail: (RemoteFavorite) -> Unit,
    onRemoveRequest: (RemoteFavorite) -> Unit,
    onMove: (String, Int) -> Unit,
    coverWidth: Dp = 96.dp,
    onMenuRequest: ((RemoteFavorite) -> Unit)? = null,
    /** Porcelain phone parity (iOS FavoriteCell): square side×side cover, inner badge, caption2 label. */
    squareTiles: Boolean = false,
    /** Square tiles only: fixed caption column width (68) wider than the cover; cover and caption group stay centered. */
    tileWidth: Dp? = null,
) {
    if (favorites.isEmpty()) return

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // Square shelf: fixed 68 column + gap 8 (image edge gap 24); the same gap feeds the reorder step.
    val shelfGap = if (squareTiles) 8.dp else 9.dp
    val spacingPx = with(density) { shelfGap.toPx() }
    val fallbackStepPx = with(density) { coverWidth.toPx() } + spacingPx
    val haptics = LocalHapticFeedback.current
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnMenu by rememberUpdatedState(onMenuRequest ?: onRemoveRequest)
    val edgeZonePx = with(density) { 40.dp.toPx() }
    val edgeScrollPx = with(density) { 12.dp.toPx() }

    LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(shelfGap)) {
        items(favorites, key = { it.id }) { favorite ->
            LaunchedEffect(
                favorite.id,
                favorite.connectionId,
                favorite.path,
                favorite.size,
                favorite.modifiedAt,
                thumbnailGeneration,
            ) {
                onLoadThumbnail(favorite)
            }
            val connection = connections.firstOrNull { it.id == favorite.connectionId }
            val isPhoneHard = favorite.connectionId == "phonehard"
            val tint = connection?.let { serviceColor(it.kind.name, theme) }
                ?: if (isPhoneHard) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            val thumbnail = thumbnails[favorite.id]
            val coverAspect = thumbnail?.takeIf { !it.isRecycled && it.height > 0 }
                ?.let { (it.width.toFloat() / it.height).coerceIn(.75f, 1.4f) } ?: 1.2f
            var dragOffsetX by remember(favorite.id) { mutableFloatStateOf(0f) }
            var isHeld by remember(favorite.id) { mutableStateOf(false) }
            var isDragging by remember(favorite.id) { mutableStateOf(false) }
            // pointerInput은 favorite.id로만 재시작되므로, 메타데이터가 갱신된 최신 항목을 콜백에 넘긴다.
            val currentFavorite by rememberUpdatedState(favorite)
            Box(
                modifier = Modifier
                    .width(if (squareTiles) androidx.compose.ui.unit.max(coverWidth, tileWidth ?: 56.dp) else coverWidth)
                    .animateItem()
                    .zIndex(if (isHeld) 1f else 0f)
                    .graphicsLayer {
                        translationX = dragOffsetX
                        val lift = if (isHeld) 1.04f else 1f
                        scaleX = lift
                        scaleY = lift
                    },
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "${favorite.name}, 즐겨찾기"
                        customActions = buildList {
                            add(CustomAccessibilityAction("즐겨찾기에서 제거") {
                                onRemoveRequest(favorite)
                                true
                            })
                            if (favorites.firstOrNull()?.id != favorite.id) {
                                add(CustomAccessibilityAction("앞으로 이동") {
                                    onMove(favorite.id, -1)
                                    true
                                })
                            }
                            if (favorites.lastOrNull()?.id != favorite.id) {
                                add(CustomAccessibilityAction("뒤로 이동") {
                                    onMove(favorite.id, 1)
                                    true
                                })
                            }
                        }
                    }
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        onClick(label = "열기") { onOpen(favorite); true }
                        onLongClick(label = "작업 메뉴") { onMenuRequest?.invoke(favorite) ?: onRemoveRequest(favorite); true }
                    }
                    // 단일 포인터 인식기: 탭 / 일반 팬(LazyRow에 양보) / 길게 누른 뒤 이동(순서 변경) / 길게 누르고 이동 없이 떼기(작업 메뉴).
                    .pointerInput(favorite.id) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // Apple FavoriteShelfInteractionPolicy: 0.55초 홀드, 홀드 전 10pt 초과 이동은 실패, 홀드 후 12pt 이상이면 순서 변경.
                            val holdAllowablePx = FavoriteHoldAllowableMovement.toPx()
                            val reorderActivationPx = FavoriteReorderActivationDistance.toPx()
                            // 0 = 탭, 1 = 팬/취소(소비하지 않고 LazyRow에 맡김), 2 / null = 길게 누름
                            // Compose의 withTimeout은 만료 순간 핸들러가 이벤트를 기다리는 중일 때만 전달되어, 연속 MOVE가 들어오는
                            // 실제 손가락에서는 유실·지연될 수 있다. 그래서 각 이벤트의 입력 시각으로 실제 누른 시간을 함께 판정한다.
                            var heldRelease: PointerInputChange? = null
                            val early = withTimeoutOrNull(FavoriteHoldDurationMillis) {
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull 1
                                    if (change.uptimeMillis - down.uptimeMillis >= FavoriteHoldDurationMillis) {
                                        if (!change.pressed) heldRelease = change
                                        return@withTimeoutOrNull 2
                                    }
                                    if (change.isConsumed) return@withTimeoutOrNull 1
                                    if (!change.pressed) return@withTimeoutOrNull 0
                                    if ((change.position - down.position).getDistance() > holdAllowablePx) return@withTimeoutOrNull 1
                                }
                                1
                            }
                            if (early == 0) { currentOnOpen(currentFavorite); return@awaitEachGesture }
                            if (early == 1) return@awaitEachGesture
                            // 길게 누름 확정: 이후 이동만 소비한다.
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            isHeld = true
                            dragOffsetX = 0f
                            var moved = false
                            var total = Offset.Zero
                            try {
                                heldRelease?.let { release ->
                                    // 늦게 처리된 손 떼기도 0.55초 이상 눌렀다면 탭이 아니라 작업 메뉴다.
                                    release.consume()
                                    currentOnMenu(currentFavorite)
                                }
                                while (heldRelease == null) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        change.consume()
                                        if (!moved) currentOnMenu(currentFavorite)
                                        break
                                    }
                                    val delta = change.positionChange()
                                    // 길게 누른 뒤의 모든 이동은 카드가 소유한다: 부모 LazyRow/LazyColumn이 슬롭을 누적해 가로채지 못하게 한다.
                                    change.consume()
                                    total += delta
                                    if (!moved && total.getDistance() >= reorderActivationPx) moved = true
                                    if (moved) {
                                        isDragging = true
                                        dragOffsetX += delta.x
                                        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == favorite.id }
                                        val step = item?.let { it.size + spacingPx } ?: fallbackStepPx
                                        if (dragOffsetX >= step) {
                                            currentOnMove(favorite.id, 1)
                                            dragOffsetX -= step
                                        } else if (dragOffsetX <= -step) {
                                            currentOnMove(favorite.id, -1)
                                            dragOffsetX += step
                                        }
                                        val draggedCenter = item?.let { it.offset + it.size / 2f + dragOffsetX }
                                        val viewportStart = listState.layoutInfo.viewportStartOffset.toFloat()
                                        val viewportEnd = listState.layoutInfo.viewportEndOffset.toFloat()
                                        val edgeDelta = when {
                                            draggedCenter == null -> 0f
                                            draggedCenter < viewportStart + edgeZonePx -> -edgeScrollPx
                                            draggedCenter > viewportEnd - edgeZonePx -> edgeScrollPx
                                            else -> 0f
                                        }
                                        if (edgeDelta != 0f) scope.launch { listState.scrollBy(edgeDelta) }
                                    }
                                }
                            } finally {
                                isHeld = false
                                isDragging = false
                                dragOffsetX = 0f
                            }
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (squareTiles) {
                    // Approved 01-home cover: the real bitmap aspect-filled and clipped to a square, corner 6,
                    // straight on the page background. No service circle overlay; a missing bitmap keeps the type glyph.
                    Box(
                        Modifier.size(coverWidth).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (thumbnail != null && !thumbnail.isRecycled) {
                            SkinAwareThumbnailImage(
                                bitmap = thumbnail,
                                cacheIdentity = "favorite:${favorite.connectionId}:${favorite.remoteItemId ?: favorite.path}:${favorite.path}:${favorite.size}:${favorite.modifiedAt}:${favorite.thumbnailUrl}",
                                contentDescription = favorite.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                isFolderCover = favorite.isDirectory,
                            )
                        } else {
                            Icon(favoriteIcon(favorite), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(coverWidth * .32f))
                        }
                    }
                } else Box {
                    Surface(
                        modifier = Modifier.width(coverWidth).aspectRatio(coverAspect),
                        shape = RoundedCornerShape(11.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = .90f),
                        border = BorderStroke(1.dp, tint.copy(alpha = .28f)),
                    ) {
                        if (thumbnail != null && !thumbnail.isRecycled) {
                            SkinAwareThumbnailImage(
                                bitmap = thumbnail,
                                cacheIdentity = "favorite:${favorite.connectionId}:${favorite.remoteItemId ?: favorite.path}:${favorite.path}:${favorite.size}:${favorite.modifiedAt}:${favorite.thumbnailUrl}",
                                contentDescription = favorite.name,
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(11.dp)),
                                contentScale = ContentScale.Crop,
                                isFolderCover = favorite.isDirectory,
                            )
                        } else {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(favoriteIcon(favorite), null, tint = tint, modifier = Modifier.size(36.dp))
                            }
                        }
                    }
                    if (connection != null || isPhoneHard) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 4.dp, y = (-4).dp)
                                .size(17.dp)
                                .background(tint, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (isPhoneHard) "P" else connectionKindBadge(connection!!.kind),
                                color = if (isPhoneHard) MaterialTheme.colorScheme.onPrimary else serviceForegroundColor(connection!!.kind.name, theme),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(if (squareTiles) 5.dp else 6.dp))
                if (squareTiles) {
                    // Caption centered under the cover (11/400/14) inside the fixed 68 column;
                    // up to two lines with minLines 2 reserving two scaled lines so every caption shares a baseline.
                    Text(
                        favorite.name,
                        style = com.armsone.nasfinder.ui.theme.HomeType.FavoriteCaption,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        minLines = 2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else Text(
                    favorite.name,
                    style = if (coverWidth > 100.dp) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            }
        }
    }
}

private fun favoriteIcon(favorite: RemoteFavorite): androidx.compose.ui.graphics.vector.ImageVector {
    if (favorite.isDirectory) return Icons.Default.Folder
    val ext = favorite.name.substringAfterLast('.', "").lowercase()
    val mime = favorite.mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    return when {
        mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "jpe", "png", "gif", "heic", "heif", "webp", "bmp", "dib", "tif", "tiff", "avif", "svg") -> Icons.Default.Image
        mime.startsWith("video/") || ext in setOf("3g2", "3gp", "flv", "fmp4", "m2ts", "m4v", "mkv", "mov", "mpe", "mpeg", "mpg", "mp4", "mts", "ogv", "qt", "ts", "vob", "webm") -> Icons.Default.Movie
        mime == "application/pdf" || ext == "pdf" -> Icons.Default.PictureAsPdf
        mime.startsWith("audio/") || ext in setOf("mp3", "m4a", "aac", "adts", "wav", "ogg", "oga", "flac", "opus", "amr") -> Icons.Default.Audiotrack
        else -> Icons.Default.InsertDriveFile
    }
}

private fun connectionKindBadge(kind: ConnectionKind) = when (kind) {
    ConnectionKind.SYNOLOGY -> "N"
    ConnectionKind.SFTP -> "S"
    ConnectionKind.SMB -> "M"
    ConnectionKind.WEBDAV -> "W"
    ConnectionKind.FTP -> "F"
    ConnectionKind.DROPBOX -> "D"
    ConnectionKind.ONEDRIVE -> "O"
    ConnectionKind.GOOGLE_DRIVE -> "G"
}

@Composable
private fun SectionTitle(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    // iOS SettingsSectionHeader (every theme, Porcelain included): themed symbol + footnote medium, secondary colour.
    Row(
        Modifier.padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant, design = destinationDesign(text))
        }
        Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val DashboardIconWellSize = 32.dp
private val DashboardIconGlyphSize = 17.dp

@Composable
private fun DashboardCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) { Column(content = content) }
}

@Composable
private fun DashboardRowDivider() {
    HorizontalDivider(Modifier.padding(start = 58.dp, end = 16.dp))
}

@Composable
internal fun DeviceStorageCard(onImportFiles: () -> Unit) {
    val filesDir = LocalContext.current.filesDir
    val total = filesDir.totalSpace.coerceAtLeast(0L)
    val available = filesDir.usableSpace.coerceIn(0L, total.coerceAtLeast(1L))
    val usedFraction = if (total > 0L) ((total - available).toDouble() / total).coerceIn(0.0, 1.0).toFloat() else 0f
    DashboardCard {
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = "Android 파일 선택기에서 파일을 고릅니다.", onClick = onImportFiles)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PackIcon(PackGlyph.STORAGE, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Android 저장공간", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (total > 0L) "전체 ${formatBytes(total)} · 사용 가능 ${formatBytes(available)}" else "저장공간 정보를 확인할 수 없습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (total > 0L) {
            HorizontalDivider(Modifier.padding(horizontal = 18.dp))
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("저장공간", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { usedFraction }, modifier = Modifier.fillMaxWidth())
                Text("${(usedFraction * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun destinationDesign(title: String): PackGlyph? = when (title) {
    "사진 선별", "미디어 선별" -> PackGlyph.PHOTO_SELECTION
    "폰하드" -> PackGlyph.PHONEHARD
    "기기 파일" -> PackGlyph.DEVICE_FILES
    "저장공간" -> PackGlyph.STORAGE
    "썸네일 캐시" -> PackGlyph.THUMBNAIL_CACHE
    "썸네일 캐시 및 생성", "썸네일 저장공간" -> PackGlyph.THUMBNAIL_STORAGE
    "작업대", "작업대에 담기" -> PackGlyph.WORKBENCH
    "컬렉션" -> PackGlyph.COLLECTION
    "편집본" -> PackGlyph.DRAFTS
    "연결 목록", "파인더" -> PackGlyph.CONNECTIONS
    else -> null
}

@Composable
private fun DashboardRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String?, iconTint: Color = MaterialTheme.colorScheme.primary, titleColor: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp), design = destinationDesign(title))
        Spacer(Modifier.width(12.dp))
        // 짧은 값(용량·'관리')은 오른쪽에, 설명 문장은 다른 행처럼 제목 아래에 둔다.
        val stacked = (detail?.length ?: 0) > 12
        if (stacked) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(detail.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Text(title, Modifier.weight(1f), color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp)) }
        }
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SuperThumbnailMark(modifier: Modifier = Modifier) {
    PackIcon(PackGlyph.SUPER_THUMBNAIL, null, modifier)
}

@Composable
private fun SuperThumbnailDashboardRow(detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SuperThumbnailMark(Modifier.size(30.dp))
        Spacer(Modifier.width(11.dp))
        Text("Super Thumbnail", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BrowserDashboardRow(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            Icon(Icons.Default.Language, null, Modifier.size(26.dp), tint = BrowserOrange)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("브라우저", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "WWW",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = BrowserOrange,
                    modifier = Modifier.background(BrowserOrange.copy(alpha = .10f), CircleShape).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Box(Modifier.size(width = 44.dp, height = 44.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.ArrowForward, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun PhoneHardDashboardRow(
    detail: String? = null,
    draftCount: Int = 0,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val longClickLabel = "보관된 편집 관리"
    val rowModifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 54.dp)
        .combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
            onLongClickLabel = longClickLabel,
        )
        .semantics {
            contentDescription = if (draftCount > 0) {
                "폰하드, 저장된 편집 ${draftCount}개 있음. 길게 누르면 보관된 편집을 관리합니다."
            } else {
                "폰하드. 길게 누르면 보관된 편집을 관리합니다."
            }
        }
        .padding(horizontal = 14.dp, vertical = 10.dp)

    Row(
        rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            if (LocalInterfaceIconPack.current != InterfaceIconPack.SOLID) {
                PackIcon(PackGlyph.PHONEHARD, null, Modifier.size(DashboardIconWellSize))
            } else {
            Image(
                painter = painterResource(R.drawable.phone_hard_logo),
                contentDescription = null,
                modifier = Modifier.size(DashboardIconWellSize).clip(RoundedCornerShape(7.dp)),
                contentScale = ContentScale.Fit,
            )
            }
        }
        Spacer(Modifier.width(12.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text("폰하드", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        detail?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
        }
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConnectionRow(connection: RemoteConnection, preferred: Boolean, theme: AppTheme, onOpen: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onPreferred: () -> Unit, onUp: () -> Unit, onDown: () -> Unit,
    iconOverride: androidx.compose.ui.graphics.vector.ImageVector? = null,
    badgeLabel: String = connectionKindDashboardLabel(connection.kind),
    detailOverride: String? = null,
    supportsOrdering: Boolean = true,
    googleMark: Boolean = false,
) {
    val color = serviceColor(connection.kind.name, theme)
    val icon = iconOverride ?: connectionKindIcon(connection.kind)
    val detail = detailOverride ?: if (connection.kind == ConnectionKind.DROPBOX) {
        connection.username.trim().takeIf { it.isNotBlank() && !it.equals(connection.kind.title, ignoreCase = true) }
            ?: "계정 정보 확인 필요"
    } else connection.host
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable(onClick = onOpen).padding(start = 14.dp, top = 2.dp, bottom = 2.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            if (googleMark) {
                GooglePhotosMark(Modifier.size(32.dp))
            } else {
                Icon(icon, null, Modifier.size(32.dp), tint = color, design = if (iconOverride == null && connection.kind == ConnectionKind.SYNOLOGY) PackGlyph.NAS else null)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(connection.name, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                if (preferred) { Spacer(Modifier.width(5.dp)); Icon(Icons.Default.Star, "기본 연결", tint = color, modifier = Modifier.size(14.dp)) }
                if (!connection.name.equals(badgeLabel, ignoreCase = true)) Text(badgeLabel, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = color, modifier = Modifier.background(color.copy(alpha = .10f), CircleShape).padding(horizontal = 6.dp, vertical = 2.dp))
                }
                if (detail.isNotBlank()) Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        var menu by remember { mutableStateOf(false) }
        IconButton(onClick = { menu = true }, modifier = Modifier.size(width = 44.dp, height = 54.dp)) { Icon(Icons.Default.MoreHoriz, "더 보기") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (supportsOrdering) {
            DropdownMenuItem(text = { Text(if (preferred) "기본 연결 해제" else "기본 연결") }, leadingIcon = { Icon(Icons.Default.Star, null) }, onClick = { menu = false; onPreferred() })
            DropdownMenuItem(text = { Text("위로") }, leadingIcon = { Icon(Icons.Default.ArrowUpward, null) }, onClick = { menu = false; onUp() })
            DropdownMenuItem(text = { Text("아래로") }, leadingIcon = { Icon(Icons.Default.ArrowDownward, null) }, onClick = { menu = false; onDown() })
            }
            DropdownMenuItem(text = { Text("연결 수정") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; onEdit() })
            DropdownMenuItem(text = { Text("연결 삭제") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; onDelete() })
        }
    }
}

private fun connectionKindDashboardLabel(kind: ConnectionKind) = when (kind) {
    ConnectionKind.SYNOLOGY -> "NAS"
    ConnectionKind.SFTP -> "SFTP"
    ConnectionKind.SMB -> "SMB"
    ConnectionKind.WEBDAV -> "WebDAV"
    ConnectionKind.FTP -> "FTP"
    ConnectionKind.DROPBOX -> "Dropbox"
    ConnectionKind.ONEDRIVE -> "OneDrive"
    ConnectionKind.GOOGLE_DRIVE -> "Google Drive"
}

@Composable
private fun ConnectionEditor(editing: RemoteConnection?, state: AppState, model: NasFinderViewModel) {
    val localNetworkGate = rememberLocalNetworkGate()
    val draftId = remember { editing?.id ?: java.util.UUID.randomUUID().toString() }
    var kind by remember { mutableStateOf(editing?.kind ?: ConnectionKind.SYNOLOGY) }
    var name by remember { mutableStateOf(editing?.name.orEmpty()) }; var host by remember { mutableStateOf(editing?.host.orEmpty()) }
    var port by remember { mutableStateOf((editing?.port ?: kind.defaultPort).toString()) }; var username by remember { mutableStateOf(editing?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }; var rootPath by remember { mutableStateOf(editing?.rootPath ?: kind.defaultRootPath) }
    var synologyOtp by remember { mutableStateOf("") }
    var tls by remember { mutableStateOf(editing?.usesTls ?: kind.supportsTls) }
    var trustedHostKey by remember { mutableStateOf(editing?.trustedHostKey) }
    var useSftpPrivateKey by remember { mutableStateOf(false) }
    var pendingHostKey by remember { mutableStateOf<com.armsone.nasfinder.network.SftpHostKeyTrustRequired?>(null) }
    val oauthProvider = CloudOAuthProvider.from(kind)
    var oauthClientId by remember(kind) { mutableStateOf(oauthProvider?.let(state.oauthClientIds::get).orEmpty()) }
    val oauthConnected = draftId in state.oauthConnectedConnectionIds
    val oauthConfigured = oauthClientId.isNotBlank()
    val oauthPending = state.oauthPendingConnectionId == draftId
    val otpValid = synologyOtp.isEmpty() || synologyOtp.length in 6..8
    val valid = if (kind.oauth) name.isNotBlank() && (password.isNotBlank() || oauthConnected)
        else name.isNotBlank() && host.isNotBlank() && username.isNotBlank() && password.isNotBlank() && port.toIntOrNull() in 1..65535 && (kind != ConnectionKind.SYNOLOGY || otpValid)
    fun candidate() = RemoteConnection(draftId, name.trim(), kind, (host.ifBlank { cloudApiHost(kind) }).trim().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':'), port.toIntOrNull() ?: kind.defaultPort, username.trim(), rootPath, tls, trustedHostKey, editing?.createdAt ?: System.currentTimeMillis())
    Scaffold(containerColor = Color.Transparent, topBar = { PorcelainTopBar(title = if (editing == null) "연결 추가" else "연결 수정", onBack = { model.show(Screen.Dashboard) }, actions = { TextButton(onClick = { localNetworkGate { val oneTimeOtp = synologyOtp; synologyOtp = ""; model.testConnection(candidate(), password, oneTimeOtp, onTrustRequired = { pendingHostKey = it }) { model.saveConnection(candidate(), password) } } }, enabled = valid && !state.isBusy, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (editing == null) "연결" else "저장") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("연결 방식", style = MaterialTheme.typography.titleSmall) }
            item {
                ConnectionKindGrid(
                    selected = kind,
                    theme = state.theme,
                    onSelected = { value ->
                        kind = value
                        port = value.defaultPort.toString()
                        rootPath = value.defaultRootPath
                        tls = value.supportsTls
                        trustedHostKey = null
                        useSftpPrivateKey = false
                        password = ""
                        synologyOtp = ""
                        if (value.oauth) host = cloudApiHost(value)
                    },
                )
            }
            item { Text(kind.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (kind.oauth) {
                item { Text("계정 정보", style = MaterialTheme.typography.titleSmall) }
                item { OutlinedTextField(name, { name = it }, label = { Text("표시 이름") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                item { OutlinedTextField(username, { username = it }, label = { Text("계정 표시 (선택)") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                item {
                    OutlinedTextField(
                        oauthClientId,
                        { oauthClientId = it },
                        label = { Text("${kind.title} client ID") },
                        supportingText = { Text("공개 앱 client ID만 입력합니다. client secret은 사용하지 않습니다.") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                item {
                    OutlinedButton(
                        onClick = { model.saveOAuthClientId(kind, oauthClientId) },
                        enabled = oauthConfigured && !state.isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("client ID 저장") }
                }
                if (oauthConfigured) {
                    item {
                        Button(
                            onClick = { model.beginOAuthLogin(candidate(), oauthClientId) },
                            enabled = name.isNotBlank() && !oauthPending && !state.isBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (oauthPending) "로그인 응답 대기 중" else "브라우저로 로그인") }
                    }
                    if (oauthPending) {
                        item { TextButton(onClick = model::cancelOAuthLogin, modifier = Modifier.fillMaxWidth()) { Text("로그인 대기 취소") } }
                    }
                    if (oauthConnected) {
                        item {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("로그인 토큰이 저장되어 있습니다.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { model.deleteOAuthToken(candidate()) }) { Text("로그아웃") }
                            }
                        }
                    }
                } else {
                    item { OutlinedTextField(password, { password = it }, label = { Text("수동 OAuth 액세스 토큰") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true) }
                }
                item { OutlinedButton(onClick = { localNetworkGate { model.testConnection(candidate(), password) } }, enabled = valid && !state.isBusy, modifier = Modifier.fillMaxWidth()) { Text("연결만 확인") } }
                item { Text(if (oauthConfigured) "PKCE 브라우저 로그인으로 받은 토큰은 Android Keystore로 보호됩니다." else "client ID가 없으면 기존 수동 액세스 토큰 방식만 사용합니다. 토큰은 Android Keystore로 보호됩니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("서버 정보", style = MaterialTheme.typography.titleSmall)
                        Text("필수 항목을 입력하면 연결할 수 있습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item { OutlinedTextField(name, { name = it }, label = { Text("표시 이름 · 필수 (예: 우리집 NAS)") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                item { OutlinedTextField(host, { host = it }, label = { Text("서버 주소 · 필수 (예: nas.example.com)") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                item { OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("포트 · 필수") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true) }
                if (kind.supportsTls) item { Row(verticalAlignment = Alignment.CenterVertically) { Text("보안 연결(HTTPS)", Modifier.weight(1f)); Switch(tls, { tls = it; if (kind == ConnectionKind.SYNOLOGY && port in setOf("5000", "5001")) port = if (it) "5001" else "5000" }) } }
                item { OutlinedTextField(rootPath, { rootPath = it }, label = { Text("시작 폴더 · 선택") }, supportingText = { Text("변경하지 않으면 서비스의 기본 위치에서 시작합니다.") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                item { OutlinedTextField(username, { username = it }, label = { Text("사용자 이름 · 필수") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                if (kind == ConnectionKind.SFTP) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("인증 방식", style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = !useSftpPrivateKey,
                                    onClick = { useSftpPrivateKey = false; password = "" },
                                    label = { Text("비밀번호") },
                                    leadingIcon = { Icon(Icons.Default.Password, null, modifier = Modifier.size(18.dp)) },
                                )
                                FilterChip(
                                    selected = useSftpPrivateKey,
                                    onClick = { useSftpPrivateKey = true; password = "" },
                                    label = { Text("개인키") },
                                    leadingIcon = { Icon(Icons.Default.Key, null, modifier = Modifier.size(18.dp)) },
                                )
                            }
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(if (useSftpPrivateKey) "PEM / OpenSSH 개인키 · 필수" else "비밀번호 · 필수") },
                            visualTransformation = if (useSftpPrivateKey) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = !useSftpPrivateKey,
                            minLines = if (useSftpPrivateKey) 7 else 1,
                            maxLines = if (useSftpPrivateKey) 12 else 1,
                        )
                    }
                    if (useSftpPrivateKey) {
                        item {
                            Text(
                                "암호가 없는 PEM/OpenSSH 개인키만 지원합니다. 암호화된 개인키와 passphrase는 현재 연결 모델에서 지원하지 않습니다. 개인키는 비밀번호와 같은 Keystore 보호 필드에 저장됩니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    item { OutlinedTextField(password, { password = it }, label = { Text("비밀번호 · 필수") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true) }
                }
                if (kind == ConnectionKind.SYNOLOGY) {
                    item {
                        OutlinedTextField(
                            value = synologyOtp,
                            onValueChange = { synologyOtp = it.filter(Char::isDigit).take(8) },
                            label = { Text("OTP (선택)") },
                            supportingText = { Text("2단계 인증을 사용할 때 6~8자리 숫자를 입력하세요. OTP는 이번 연결 요청에만 사용하고 저장하지 않으므로, 나중에 다시 로그인할 때 새 OTP가 필요할 수 있습니다.") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            isError = synologyOtp.isNotEmpty() && !otpValid,
                        )
                    }
                }
                item { OutlinedButton(onClick = { val oneTimeOtp = synologyOtp; synologyOtp = ""; model.testConnection(candidate(), password, oneTimeOtp, onTrustRequired = { pendingHostKey = it }) }, enabled = valid && !state.isBusy, modifier = Modifier.fillMaxWidth()) { Text("연결만 확인") } }
                item { Text(securityGuidance(kind, tls), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    pendingHostKey?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingHostKey = null },
            title = { Text("SSH 서버 키 확인") },
            text = { Text("${pending.message}\n\n서버 관리자에게 지문을 확인한 뒤에만 신뢰하세요.") },
            dismissButton = { TextButton(onClick = { pendingHostKey = null }) { Text("취소") } },
            confirmButton = {
                TextButton(onClick = {
                    trustedHostKey = pending.serializedHostKey
                    pendingHostKey = null
                }) { Text(if (pending.isChangedKey) "새 키 신뢰" else "이 키 신뢰") }
            },
        )
    }
}

private fun cloudApiHost(kind: ConnectionKind): String = when (kind) {
    ConnectionKind.DROPBOX -> "api.dropboxapi.com"
    ConnectionKind.ONEDRIVE -> "graph.microsoft.com"
    ConnectionKind.GOOGLE_DRIVE -> "www.googleapis.com"
    else -> ""
}

@Composable
private fun ConnectionKindGrid(
    selected: ConnectionKind,
    theme: AppTheme,
    onSelected: (ConnectionKind) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ConnectionKind.entries.chunked(3).forEach { rowKinds ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowKinds.forEach { kind ->
                    val selectedKind = kind == selected
                    val color = serviceColor(kind.name, theme)
                    val shape = RoundedCornerShape(11.dp)
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 64.dp)
                            .border(
                                width = 1.dp,
                                color = color.copy(alpha = if (selectedKind) .55f else .18f),
                                shape = shape,
                            )
                            .clickable { onSelected(kind) }
                            .semantics(mergeDescendants = true) { this.selected = selectedKind },
                        shape = shape,
                        color = if (selectedKind) color.copy(alpha = .14f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .72f),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(connectionKindIcon(kind), null, design = if (kind == ConnectionKind.SYNOLOGY) PackGlyph.NAS else null, tint = color, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.height(5.dp))
                            Text(
                                kind.title,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selectedKind) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = color,
                            )
                        }
                    }
                }
                repeat(3 - rowKinds.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private fun connectionKindIcon(kind: ConnectionKind) = when (kind) {
    ConnectionKind.SYNOLOGY -> Icons.Default.Storage
    ConnectionKind.SFTP -> Icons.Default.Security
    ConnectionKind.SMB -> Icons.Default.FolderShared
    ConnectionKind.WEBDAV -> Icons.Default.Cloud
    ConnectionKind.FTP -> Icons.Default.SwapVert
    ConnectionKind.DROPBOX -> Icons.Default.CloudQueue
    ConnectionKind.ONEDRIVE -> Icons.Default.CloudCircle
    ConnectionKind.GOOGLE_DRIVE -> Icons.Default.AddToDrive
}

private fun securityGuidance(kind: ConnectionKind, tls: Boolean) = when (kind) {
    ConnectionKind.SYNOLOGY -> if (tls) "QuickConnect ID 대신 기기에서 접근 가능한 DDNS·도메인 또는 VPN 주소와 신뢰 가능한 인증서를 사용하세요." else "HTTP는 같은 로컬 네트워크에서만 사용하세요."
    ConnectionKind.SFTP -> "연결 시 SSH 호스트 키 지문을 확인하고 고정해야 합니다."
    ConnectionKind.SMB -> "SMB 2.0 이상을 사용합니다."
    ConnectionKind.WEBDAV -> "HTTPS와 서비스에서 발급한 앱 비밀번호를 권장합니다."
    ConnectionKind.FTP -> "FTP는 암호화되지 않습니다. 신뢰하는 로컬 네트워크에서만 사용하세요."
    else -> "로그인 토큰은 Android Keystore로 보호됩니다."
}

internal data class BaselineAction(
    val id: String,
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val enabled: Boolean,
    val disabledReason: String? = null,
    val isDestructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * iOS FileBrowserView.itemActionPanel: "파일 작업"/"폴더 작업" subheadline semibold, VStack(spacing 12), padding 16/4/16,
 * two equal-width CompactPanelButton rows (no scroll), caption2 notes for dimmed actions, then the 5-row information panel.
 * The Android bottom-sheet container is kept; tapping a dimmed tile still reports its reason as before.
 */
@Composable
internal fun AppleBaselineActionSheet(
    title: String,
    isFolder: Boolean,
    row1: List<BaselineAction>,
    row2: List<BaselineAction>,
    infoMedia: String,
    infoFeature: String,
    infoDate: String,
    infoSize: String,
    onDismiss: () -> Unit,
    onShowDisabledReason: (String) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 340.dp)
                .align(Alignment.CenterHorizontally)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (isFolder) "폴더 작업" else "파일 작업", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            listOf(row1, row2).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { action ->
                        BaselineActionButton(action = action, onShowDisabledReason = onShowDisabledReason, modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
            val notes = (row1 + row2).filter { !it.enabled }.mapNotNull { it.disabledReason }.distinct()
            if (notes.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    notes.forEach { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Column(
                Modifier.fillMaxWidth()
                    .background(compactPanelFill(), RoundedCornerShape(12.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ActionInfoRow("이름", title)
                ActionInfoRow("미디어", infoMedia)
                ActionInfoRow("특징", infoFeature)
                ActionInfoRow("생성/수정", infoDate)
                ActionInfoRow("크기", infoSize)
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

/** Grouped fill inside panels: the theme's elevated colour, or its page background where elevated equals surface (Light). */
@Composable
internal fun compactPanelFill(): Color = themePalette().let { if (it.elevated == it.surface) it.background else it.elevated }

/** iOS CompactInformationRow: caption2 tertiary 54pt title, caption secondary value (2 lines). */
@Composable
private fun ActionInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f),
            modifier = Modifier.width(54.dp).alignByBaseline(),
        )
        Text(
            text = value,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).alignByBaseline(),
        )
    }
}

/** iOS CompactPanelButton: height 52, corner 10, 19pt icon in a 24pt frame, 10pt semibold label, 0.75pt separator×.24 stroke. */
@Composable
internal fun CompactPanelTile(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    labelMaxLines: Int = 1,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val tint = (if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface).let { if (enabled) it else it.copy(alpha = .38f) }
    Column(
        modifier = modifier
            .then(if (labelMaxLines > 1) Modifier.heightIn(min = 52.dp) else Modifier.height(52.dp))
            .clip(shape)
            .background(compactPanelFill(), shape)
            .border(.75.dp, MaterialTheme.colorScheme.outline.copy(alpha = .24f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (labelMaxLines > 1) Modifier.padding(vertical = 4.dp) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
        }
        Text(
            text = title,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = tint,
            maxLines = labelMaxLines,
            lineHeight = if (labelMaxLines > 1) 12.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

@Composable
private fun BaselineActionButton(
    action: BaselineAction,
    onShowDisabledReason: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    CompactPanelTile(
        title = action.title,
        icon = action.icon,
        modifier = modifier.semantics { contentDescription = action.title },
        enabled = action.enabled,
        destructive = action.isDestructive,
        labelMaxLines = 2,
    ) {
        if (action.enabled) {
            action.onClick()
        } else {
            onShowDisabledReason(action.disabledReason ?: "지원되지 않는 동작입니다.")
        }
    }
}

@Composable
internal fun DownloadDestinationDialog(
    itemCount: Int,
    isLocal: Boolean,
    onDismiss: () -> Unit,
    onSelectPhoneHard: () -> Unit,
    onSelectExternalSaf: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isLocal) "내보내기 / 복사 위치 선택" else "다운로드 위치 선택") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "${itemCount}개 항목을 어디에 저장할까요?",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Surface(
                    onClick = onSelectPhoneHard,
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (isLocal) "폰하드 내 다른 폴더로 복사" else "폰하드 (내부 저장소)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "폰하드 최상위 또는 특정 폴더를 선택합니다.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Surface(
                    onClick = onSelectExternalSaf,
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Default.SdCard, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(
                                "기기 다른 폴더 (외부 저장소)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "다운로드 폴더, SD 카드 등 외부 폴더를 직접 선택합니다.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        },
    )
}

/** 선택한 PhoneHard 항목과 복사/이동 구분. 대상 위치를 고르는 동안만 기억한다. */
private class PhoneHardTransferRequest(val targets: List<PhoneHardTarget>, val move: Boolean)

/**
 * 폰하드 복사/이동의 위치 선택: NAS·네트워크 / 내 휴대폰 폴더(SAF) / 폰하드 안 다른 폴더(기존 동작).
 * NAS·휴대폰 폴더는 파일만 한 개씩 보내므로 폴더를 함께 골랐다면 제외된다는 점을 먼저 알려 준다.
 */
@Composable
private fun PhoneHardTransferDestinationDialog(
    move: Boolean,
    fileCount: Int,
    folderCount: Int,
    hasConnections: Boolean,
    onDismiss: () -> Unit,
    onSelectNas: () -> Unit,
    onSelectDeviceFolder: () -> Unit,
    onSelectPhoneHardFolder: () -> Unit,
) {
    val verb = if (move) "이동" else "복사"
    @Composable
    fun Option(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, enabled: Boolean, onClick: () -> Unit) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.6f else 0.3f),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
                    Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("어디로 ${verb}할까요?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    buildString {
                        append("파일 ${fileCount}개")
                        if (folderCount > 0) append(", 폴더 ${folderCount}개")
                        append("를 선택했습니다. NAS·휴대폰 폴더로는 ZIP 없이 원래 이름의 파일을 한 개씩 ${verb}합니다.")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (folderCount > 0) {
                    Text(
                        "폴더 ${folderCount}개는 NAS·휴대폰 폴더로 개별 전송하지 않고 제외됩니다(선택은 그대로 남습니다). 폴더는 ‘폰하드 안 다른 폴더’로만 ${verb}할 수 있고, 폴더째 내보내려면 ‘다운로드’를 쓰세요.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (move) {
                    Text(
                        "이동은 한 개씩 대상에 저장하고 크기를 확인한 파일만 폰하드에서 지웁니다. 실패하거나 확인하지 못한 파일은 폰하드에 남습니다.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Option(
                    Icons.Default.CloudUpload,
                    "NAS · 네트워크",
                    if (fileCount == 0) "파일을 선택했을 때만 쓸 수 있습니다." else if (hasConnections) "저장해 둔 연결을 고른 뒤 폴더를 찾아 보냅니다." else "저장된 연결이 없습니다. 다음 단계에서 연결을 추가할 수 있습니다.",
                    fileCount > 0,
                    onSelectNas,
                )
                Option(
                    Icons.Default.SdCard,
                    "내 휴대폰 폴더",
                    if (fileCount == 0) "파일을 선택했을 때만 쓸 수 있습니다." else "다운로드, SD 카드 등 폴더를 직접 고릅니다. 같은 이름은 덮어쓰지 않고 번호를 붙입니다.",
                    fileCount > 0,
                    onSelectDeviceFolder,
                )
                Option(Icons.Default.Folder, "폰하드 안 다른 폴더", "폰하드 최상위 또는 특정 폴더로 ${verb}합니다(폴더 포함).", true, onSelectPhoneHardFolder)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
internal fun NetworkDownloadProgressDialog(
    progress: com.armsone.nasfinder.ui.NetworkDownloadProgress,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("네트워크 다운로드 중…") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (progress.currentFileName.isNotBlank()) {
                    Text(
                        progress.currentFileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (progress.totalFiles > 0) {
                    Text(
                        "${progress.completedFiles} / ${progress.totalFiles} 파일 완료",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LinearProgressIndicator(
                    progress = { if (progress.totalBytesExpected > 0) progress.byteProgressFraction else progress.fileProgressFraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (progress.totalBytesExpected > 0) {
                    Text(
                        "${formatBytes(progress.totalBytesDownloaded)} / ${formatBytes(progress.totalBytesExpected)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("취소")
            }
        },
    )
}

@Composable
private fun BrowserScreen(browser: Screen.Browser, state: AppState, model: NasFinderViewModel, onCoverFlowFullscreen: (Boolean) -> Unit = {}) {
    var searchText by remember(browser.connection.id, browser.path) { mutableStateOf("") }
    var searchVisible by rememberSaveable(browser.connection.id, browser.path) { mutableStateOf(false) }
    var creatingFolder by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var renamingItem by remember { mutableStateOf<RemoteFileItem?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deletingItem by remember { mutableStateOf<RemoteFileItem?>(null) }
    var actionItem by remember { mutableStateOf<RemoteFileItem?>(null) }
    var selectionMode by remember(browser.connection.id, browser.path) { mutableStateOf(false) }
    val selectedIds = remember(browser.connection.id, browser.path) { mutableStateListOf<String>() }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current.density
    var coverFlowSelectedIndex by rememberSaveable(browser.connection.id, browser.path) { mutableIntStateOf(0) }
    var batchTransfer by remember { mutableStateOf<RemoteTransferAction?>(null) }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    val capabilities = remember(browser.connection.kind) { browserCapabilities(browser.connection.kind) }
    val accessibilityLayout = LocalConfiguration.current.fontScale >= 1.3f
    val pickMode = state.mediaPick != null
    // Media-pick keeps its own layout (default SMALL_GRID); the general browser preference is never touched.
    var pickLayout by rememberSaveable { mutableStateOf(BrowserLayout.SMALL_GRID) }
    val effectiveLayout = if (pickMode) pickLayout else browser.preferences.layout
    // Explicit 커버플로우 (menu button): bypasses the size/aspect policy only; media-pick, selection and empty folders stay excluded.
    var explicitCoverFlow by rememberSaveable(browser.connection.id, browser.path) { mutableStateOf(false) }
    // Set after leaving the explicit mode so the automatic entry does not immediately re-open it for this folder.
    var autoCoverFlowSuppressed by rememberSaveable(browser.connection.id, browser.path) { mutableStateOf(false) }
    val showsCoverFlow = !pickMode && (
        (explicitCoverFlow && !selectionMode && browser.items.isNotEmpty()) ||
            (!autoCoverFlowSuppressed && browser.preferences.layout == BrowserLayout.LARGE_GRID &&
                AdaptiveOverflowPolicy.shouldUseAutomaticOverflow(
                    contentWidthDp = viewportSize.width / density,
                    contentHeightDp = viewportSize.height / density,
                    itemCount = browser.items.size,
                    selectionActive = selectionMode || state.mediaPick != null,
                    smallestScreenWidthDp = LocalConfiguration.current.smallestScreenWidthDp,
                ))
        )
    val currentOnCoverFlowFullscreen by rememberUpdatedState(onCoverFlowFullscreen)
    SideEffect { currentOnCoverFlowFullscreen(showsCoverFlow) }
    DisposableEffect(Unit) { onDispose { currentOnCoverFlowFullscreen(false) } }
    LaunchedEffect(effectiveLayout, showsCoverFlow, selectionMode) {
        if (effectiveLayout != BrowserLayout.LIST || showsCoverFlow || selectionMode) {
            model.stopInlineAudio()
        }
    }
    var coverFlowDark by rememberSaveable(browser.connection.id) { mutableStateOf(true) }
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        model.setBrowserSystemPickerOpen(false)
        uri?.let(model::uploadDocument)
    }
    var pendingDownloadItems by remember { mutableStateOf<List<RemoteFileItem>?>(null) }
    var showDownloadDestDialog by remember { mutableStateOf(false) }
    var showPhoneHardPickerForDownload by remember { mutableStateOf(false) }
    var folderTrailerTarget by remember { mutableStateOf<RemoteFileItem?>(null) }
    var folderMusicOverlayTarget by remember { mutableStateOf<RemoteFileItem?>(null) }
    var actionToastMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.notice) {
        state.notice?.let { actionToastMessage = it; model.consumeNotice() }
    }
    val externalTreeDownloadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        model.setBrowserSystemPickerOpen(false)
        if (treeUri != null) {
            val items = pendingDownloadItems ?: return@rememberLauncherForActivityResult
            model.downloadRemoteItems(browser.connection, items, NetworkDownloadDestination.ExternalTree(treeUri))
            pendingDownloadItems = null
        } else {
            pendingDownloadItems = null
        }
    }
    val displayedItems = remember(browser.items, searchText) {
        val query = searchText.trim()
        if (query.isEmpty()) browser.items
        else browser.items.filter { it.name.containsLocalized(query) }
    }
    val favoritePaths = remember(state.remoteFavorites, browser.connection.id) {
        state.remoteFavorites
            .filter { it.connectionId == browser.connection.id }
            .mapTo(mutableSetOf()) { it.path }
    }
    val parentPath = remoteParentPath(browser.path, browser.connection.normalizedRootPath)
    val selectedItems = browser.items.filter { it.id in selectedIds }
    // 선택 from the home favorite task panel: once this folder's own listing has loaded, select only the fresh entry with
    // the requested ID and kind. Nothing else is selected and no other fallback applies.
    val selectRequest = state.browserSelectRequest
    LaunchedEffect(selectRequest, browser.connection.id, browser.path, browser.items, state.isBusy) {
        val request = selectRequest ?: return@LaunchedEffect
        if (request.connectionId != browser.connection.id || request.parentPath != browser.path || state.isBusy) return@LaunchedEffect
        val target = browser.items.firstOrNull { it.id == request.itemId && it.isDirectory == request.isDirectory }
        if (target == null) {
            model.consumeBrowserSelectRequest(request, if (state.message == null) "‘${request.name}’ 항목을 이 폴더에서 찾을 수 없습니다." else null)
        } else {
            model.consumeBrowserSelectRequest(request)
            selectedIds.clear()
            selectedIds.add(target.id)
            selectionMode = true
        }
    }
    LaunchedEffect(browser.items) { selectedIds.removeAll { id -> browser.items.none { it.id == id } } }
    if (showsCoverFlow) {
        // Manual and automatic CoverFlow both close back to this same folder/view; automatic re-entry stays off until the user opts in again.
        BackHandler {
            explicitCoverFlow = false
            autoCoverFlowSuppressed = true
        }
        val browserViewModes = remember(browser.preferences.layout, explicitCoverFlow) {
            listOf(
                CoverFlowViewModeOption(
                    title = "자세히",
                    selected = browser.preferences.layout == BrowserLayout.LIST,
                    icon = Icons.Default.List,
                    onSelect = { explicitCoverFlow = false; model.updateBrowserPreferences(browser.preferences.copy(layout = BrowserLayout.LIST)) },
                ),
                CoverFlowViewModeOption(
                    title = "썸네일",
                    selected = browser.preferences.layout == BrowserLayout.SMALL_GRID,
                    icon = Icons.Default.GridView,
                    onSelect = { explicitCoverFlow = false; model.updateBrowserPreferences(browser.preferences.copy(layout = BrowserLayout.SMALL_GRID)) },
                ),
                CoverFlowViewModeOption(
                    title = "포스터",
                    selected = browser.preferences.layout == BrowserLayout.LARGE_GRID && !explicitCoverFlow,
                    icon = Icons.Default.GridOn,
                    onSelect = {
                        autoCoverFlowSuppressed = true
                        explicitCoverFlow = false
                        model.updateBrowserPreferences(browser.preferences.copy(layout = BrowserLayout.LARGE_GRID))
                    },
                ),
                CoverFlowViewModeOption(
                    title = "플로잉",
                    selected = explicitCoverFlow,
                    icon = Icons.Default.ViewCarousel,
                    onSelect = { explicitCoverFlow = true },
                ),
            )
        }
        RemoteBrowserCoverFlow(
            items = displayedItems,
            thumbnails = state.remoteThumbnails,
            theme = state.theme,
            title = browser.connection.name,
            usesDarkBackground = coverFlowDark,
            onBack = {
                explicitCoverFlow = false
                autoCoverFlowSuppressed = true
            },
            onDashboard = { model.show(Screen.Dashboard) },
            viewModes = browserViewModes,
            onToggleBackground = { coverFlowDark = it },
            onActivate = model::openItem,
            onLoadThumbnail = model::loadRemoteThumbnail,
            initialSelectedIndex = coverFlowSelectedIndex,
            onSelectedIndexChange = { coverFlowSelectedIndex = it },
            modifier = Modifier.fillMaxSize().onSizeChanged { viewportSize = it },
        )
        return
    }
    BackHandler(selectionMode) { selectedIds.clear(); selectionMode = false }
    BackHandler(!selectionMode) {
        if (pickMode) model.cancelNetworkMediaPick()
        else if (parentPath == null && state.pendingLocalUpload != null) model.cancelLocalUploadDestination()
        else if (parentPath == null && state.pendingInboxUpload != null) model.cancelInboxUpload()
        else if (parentPath == null) model.show(Screen.Dashboard)
        else model.openConnection(browser.connection, parentPath)
    }


    Scaffold(modifier = Modifier.onSizeChanged { viewportSize = it }, containerColor = Color.Transparent, topBar = { CenterAlignedTopAppBar(expandedHeight = 52.dp, title = {
        // iOS principal title: headline, tap opens the current-folder search; the path lives in the path bar below.
        Text(
            browser.connection.name,
            Modifier.clickable(onClickLabel = "현재 폴더 검색", role = Role.Button) { searchVisible = true }
                .semantics { contentDescription = "${browser.connection.name}, 검색" },
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }, navigationIcon = {
        CompositionLocalProvider(LocalViewConfiguration provides longPress500Configuration()) {
        Box(
            modifier = Modifier.size(48.dp).semantics(mergeDescendants = true) {
                contentDescription = if (selectionMode) "선택 종료" else if (parentPath == null && (state.pendingInboxUpload != null || state.pendingLocalUpload != null)) "NAS로 보내기 취소" else "이전 폴더"
                if (!selectionMode) {
                    customActions = listOf(
                        CustomAccessibilityAction("NasFinder 첫 화면") {
                            model.show(Screen.Dashboard)
                            true
                        }
                    )
                }
            }.combinedClickable(
                onClick = {
            if (selectionMode) { selectedIds.clear(); selectionMode = false }
            else if (parentPath == null && pickMode) model.cancelNetworkMediaPick()
            else if (parentPath == null && state.pendingLocalUpload != null) model.cancelLocalUploadDestination()
            else if (parentPath == null && state.pendingInboxUpload != null) model.cancelInboxUpload()
            else if (parentPath == null) model.show(Screen.Dashboard)
            else model.openConnection(browser.connection, parentPath)
                },
                onLongClick = if (selectionMode) null else ({ model.show(Screen.Dashboard) }),
                onLongClickLabel = if (selectionMode) null else "NasFinder 첫 화면",
            ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (selectionMode) Icons.Default.Close else Icons.Default.ChevronLeft,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        }
    }, actions = {
        if (selectionMode && !pickMode) {
            TextButton(onClick = { selectedIds.clear(); selectionMode = false }) { Text("완료") }
        } else if (!selectionMode) {
            BrowserMenu(
                preferences = browser.preferences,
                canCreateFolder = capabilities.createFolder,
                canPaste = capabilities.upload || state.pendingTransfer?.connectionId == browser.connection.id,
                pasteUploads = state.pendingTransfer?.connectionId != browser.connection.id,
                onSelect = { selectionMode = true },
                onCreateFolder = { folderName = ""; creatingFolder = true },
                onPaste = {
                    if (state.pendingTransfer?.connectionId == browser.connection.id) model.applyTransferDestination()
                    else { model.setBrowserSystemPickerOpen(true); uploadLauncher.launch(arrayOf("*/*")) }
                },
                onRefresh = model::refreshBrowser,
                update = model::updateBrowserPreferences,
                pickLayout = if (pickMode) pickLayout else null,
                onPickLayout = { pickLayout = it },
                onCoverFlow = if (!pickMode && browser.items.isNotEmpty()) ({ explicitCoverFlow = true }) else null,
                onLayoutChosen = { explicitCoverFlow = false; autoCoverFlowSuppressed = false },
            )
        }
    }, colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = if (selectionMode && usesUnifiedLayout(state.theme)) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)) }, bottomBar = {
        if (pickMode) BottomAppBar {
            val pickable = selectedItems.filter { !it.isDirectory && (it.isVideo || it.isImage) }
            TextButton(onClick = model::cancelNetworkMediaPick, enabled = state.mediaPickStaging == null) { Text("돌아가기") }
            TextButton(
                onClick = { model.requestWorkbenchAddRemote(browser.connection, selectedItems.toList()); selectedIds.clear() },
                enabled = selectedItems.isNotEmpty() && state.mediaPickStaging == null,
            ) { Text("작업대에 담기") }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { model.stageRemoteMediaForBasket(pickable); selectedIds.clear() },
                enabled = pickable.isNotEmpty() && state.mediaPickStaging == null,
                modifier = Modifier.padding(end = 12.dp),
            ) { Text(if (pickable.isEmpty()) "미디어를 선택하세요" else "영상 만들기에 ${pickable.size}개 추가") }
        } else if (selectionMode) {
            val canTrailer = selectedItems.isNotEmpty() && selectedItems.all { !it.isDirectory && (it.isVideo || it.isImage) }
            val canMusic = selectedItems.isNotEmpty() && selectedItems.all { !it.isDirectory && (it.isVideo || it.isImage) }
            val canShare = selectedItems.isNotEmpty()
            val allDisplayedSelected = displayedItems.isNotEmpty() && displayedItems.all { it.id in selectedIds }
            // iOS selectionBar order: 복사 · 이동 · 삭제 · 예고편 보기 · 뮤직비디오 · 다운로드 · 공유; handlers and conditions unchanged.
            BrowserSelectionBar(
                count = selectedIds.size,
                allSelected = allDisplayedSelected,
                canToggleAll = displayedItems.isNotEmpty(),
                onToggleAll = {
                    if (allDisplayedSelected) selectedIds.removeAll(displayedItems.map { it.id }.toSet())
                    else displayedItems.forEach { if (it.id !in selectedIds) selectedIds.add(it.id) }
                },
                actions = listOf(
                    BrowserSelectionAction("복사", Icons.Default.ContentCopy, selectedItems.isNotEmpty() && capabilities.copy) { batchTransfer = RemoteTransferAction.COPY },
                    BrowserSelectionAction("이동", Icons.Default.DriveFileMove, selectedItems.isNotEmpty() && capabilities.move) { batchTransfer = RemoteTransferAction.MOVE },
                    BrowserSelectionAction("삭제", Icons.Default.Delete, selectedItems.isNotEmpty() && capabilities.delete, destructive = true) { confirmBatchDelete = true },
                    // Files AND folders of any kind become references only; nothing is downloaded or listed here.
                    BrowserSelectionAction("작업대에 담기", Icons.Default.Inbox, selectedItems.isNotEmpty()) { model.requestWorkbenchAddRemote(browser.connection, selectedItems.toList()); selectedIds.clear(); selectionMode = false },
                    // NAS/cloud paths are never durable references: this always stores an app-owned copy.
                    BrowserSelectionAction("컬렉션에 사본 추가", Icons.Default.VideoLibrary, selectedItems.any { it.isVideo }) { model.importRemoteFilesToCollection(browser.connection, selectedItems); selectedIds.clear(); selectionMode = false },
                    BrowserSelectionAction("예고편 보기", Icons.Default.Movie, canTrailer) { model.startRemoteTrailer(selectedItems); selectedIds.clear(); selectionMode = false },
                    BrowserSelectionAction("뮤직비디오", Icons.Default.MusicNote, canMusic) { model.startRemoteMusicOverlay(selectedItems.toList(), null); selectedIds.clear(); selectionMode = false },
                    BrowserSelectionAction("다운로드", Icons.Default.Download, selectedItems.isNotEmpty()) { pendingDownloadItems = selectedItems.toList(); showDownloadDestDialog = true },
                    BrowserSelectionAction("공유", Icons.Default.Share, canShare) { model.shareItems(selectedItems); selectedIds.clear(); selectionMode = false },
                ),
                note = if (selectedItems.isNotEmpty() && !canTrailer) "예고편 보기와 뮤직비디오는 미디어만 선택했을 때 쓸 수 있습니다." else null,
            )
        }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.pendingInboxUpload == null) ThumbnailTrafficStatus(state.thumbnailTraffic)
            state.mediaPickStaging?.let { staging ->
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text("선택한 파일 가져오는 중") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${staging.current}/${staging.total} · ${staging.name}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                            staging.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                                ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    },
                    confirmButton = { TextButton(onClick = model::cancelMediaPickStaging) { Text("취소") } },
                )
            }
            state.pendingInboxUpload?.let { pending ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .94f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudUpload, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (pending.deleteSourceAfterUpload) {
                                    if (pending.isUploading) "NAS로 이동하는 중… (확인된 파일만 폰하드에서 삭제)" else "NAS로 이동할 폴더를 선택하세요"
                                } else {
                                    if (pending.isUploading) "NAS로 보내는 중…" else "NAS로 보낼 폴더를 선택하세요"
                                },
                                style = MaterialTheme.typography.labelLarge,
                            )
                            val firstName = pending.items.first().originalFilename
                            val what = if (pending.items.size == 1) "‘$firstName’" else "‘$firstName’ 외 ${pending.items.size - 1}개"
                            Text("$what → ${browser.connection.name}:${browser.path}", style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = model::cancelInboxUpload) { Text(if (pending.isUploading) "중단" else "취소") }
                        Button(
                            onClick = model::applyInboxUploadDestination,
                            enabled = pending.connectionId == browser.connection.id && capabilities.upload && !pending.isUploading && !state.isBusy,
                        ) { Text(if (pending.deleteSourceAfterUpload) "여기로 이동" else "여기로 보내기") }
                    }
                }
            }
            state.pendingLocalUpload?.let { pending ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .94f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudUpload, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (pending.isUploading) "네트워크에 저장하는 중…" else "저장할 폴더를 선택하세요", style = MaterialTheme.typography.labelLarge)
                            Text("${pending.filename} → ${browser.path}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = model::cancelLocalUploadDestination, enabled = !pending.isUploading) { Text("취소") }
                        Button(
                            onClick = model::applyLocalUploadDestination,
                            enabled = pending.connectionId == browser.connection.id && capabilities.upload && !pending.isUploading && !state.isBusy,
                        ) { Text("여기에 저장") }
                    }
                }
            }
            state.pendingTransfer?.let { pending ->
                val verb = if (pending.action == RemoteTransferAction.COPY) "복사" else "이동"
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .92f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        val firstName = pending.items.first().name
                        val what = if (pending.items.size == 1) "‘$firstName’" else "‘$firstName’ 외 ${pending.items.size - 1}개"
                        Text("$what $verb 대상: ${browser.path}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = model::cancelTransfer) { Text("취소") }
                        Button(
                            onClick = model::applyTransferDestination,
                            enabled = pending.connectionId == browser.connection.id && !state.isBusy,
                        ) { Text("여기에 $verb") }
                    }
                }
            }
            if (searchVisible || searchText.isNotEmpty()) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    placeholder = { Text("현재 폴더 검색") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        IconButton(onClick = { searchText = ""; searchVisible = false }) {
                            Icon(Icons.Default.Cancel, "검색 닫기")
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
            }
            val rootPath = browser.connection.normalizedRootPath
            val relativeSegments = browser.path.removePrefix(rootPath).trim('/').split('/').filter { it.isNotEmpty() }
            BrowserPathBar(
                rootTitle = browser.connection.name,
                segments = relativeSegments.mapIndexed { index, segment ->
                    val segmentPath = (rootPath.trimEnd('/') + "/" + relativeSegments.take(index + 1).joinToString("/"))
                    segment to { model.openConnection(browser.connection, segmentPath) }
                },
                onParent = parentPath?.let { { model.openConnection(browser.connection, it) } },
                onRoot = if (relativeSegments.isNotEmpty()) ({ model.openConnection(browser.connection, rootPath) }) else null,
                countLabel = if (searchText.isBlank()) "${browser.items.size}개" else "${displayedItems.size}/${browser.items.size}개",
            )
            when {
                browser.items.isEmpty() && state.isBusy -> Spacer(Modifier.weight(1f))
                browser.items.isEmpty() -> EmptyState(
                    icon = Icons.Default.Folder,
                    title = "빈 폴더",
                    description = "이 폴더에는 표시할 파일이 없습니다.",
                    modifier = Modifier.weight(1f),
                )
                displayedItems.isEmpty() -> EmptyState(
                    icon = Icons.Default.Search,
                    title = "검색 결과가 없습니다",
                    description = "‘${searchText.trim()}’과 일치하는 항목을 찾지 못했습니다.",
                    modifier = Modifier.weight(1f),
                )
                effectiveLayout == BrowserLayout.LIST -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(displayedItems, key = { it.id }) { item ->
                        if (item.isImage || item.isVideo || item.isDirectory) LaunchedEffect(browser.connection.id, browser.path, state.thumbnailGeneration, item.id, item.size, item.modifiedAt) { model.loadRemoteThumbnail(item) }
                        if (item.isDirectory) LaunchedEffect(browser.connection.id, browser.path, item.id, item.path, state.folderCountGeneration, browser.preferences.showHiddenFiles) { model.loadFolderChildCount(item) }
                        val itemAudioKey = if (item.isAudio) model.inlineRemoteAudioKey(browser.connection.id, item) else null
                        val activeInlineAudio = state.inlineAudioPlayback?.takeIf { itemAudioKey != null && it.key == itemAudioKey }
                        FileRow(
                            item,
                            thumbnail = state.remoteThumbnails[item.id],
                            theme = state.theme,
                            selected = item.id in selectedIds,
                            selectionMode = selectionMode || pickMode,
                            accessibilityLayout = accessibilityLayout,
                            folderChildCount = if (item.isDirectory) state.folderCountCache[item.id] else null,
                            inlineAudioState = activeInlineAudio,
                            onToggleInlineAudio = if (item.isAudio && !selectionMode) {
                                { model.toggleInlineRemoteAudio(item) }
                            } else null,
                            onClick = { if (pickMode && !item.isDirectory) toggleRemoteSelection(selectedIds, item.id) else if (selectionMode) toggleRemoteSelection(selectedIds, item.id) else model.openItem(item) },
                            onLongClick = { actionItem = item },
                        )
                    }
                }
                else -> {
                    val poster = effectiveLayout == BrowserLayout.LARGE_GRID
                    val porcelainGrid = usesUnifiedLayout(state.theme)
                    val minimumCellWidth = when {
                        poster && accessibilityLayout -> 270.dp
                        poster -> 158.dp
                        accessibilityLayout -> 118.dp
                        else -> 78.dp
                    }
                    val porcelainCells = if (porcelainGrid) porcelainGridCells(poster, accessibilityLayout) else null
                    LazyVerticalGrid(
                        porcelainCells?.first ?: GridCells.Adaptive(minimumCellWidth),
                        Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(if (poster) 16.dp else 10.dp),
                        verticalArrangement = Arrangement.spacedBy(if (poster) 20.dp else 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(porcelainCells?.second ?: if (poster) 16.dp else 8.dp, Alignment.CenterHorizontally),
                    ) {
                    items(displayedItems, key = { it.id }) { item ->
                        if (item.isImage || item.isVideo || item.isDirectory) LaunchedEffect(browser.connection.id, browser.path, state.thumbnailGeneration, item.id, item.size, item.modifiedAt) { model.loadRemoteThumbnail(item) }
                        if (item.isDirectory) LaunchedEffect(browser.connection.id, browser.path, item.id, item.path, state.folderCountGeneration, browser.preferences.showHiddenFiles) { model.loadFolderChildCount(item) }
                        FileTile(
                            item,
                            thumbnail = state.remoteThumbnails[item.id],
                            theme = state.theme,
                            poster = poster,
                            selected = item.id in selectedIds,
                            selectionMode = selectionMode || pickMode,
                            folderChildCount = if (item.isDirectory) state.folderCountCache[item.id] else null,
                            onClick = { if (pickMode && !item.isDirectory) toggleRemoteSelection(selectedIds, item.id) else if (selectionMode) toggleRemoteSelection(selectedIds, item.id) else model.openItem(item) },
                            onLongClick = { actionItem = item },
                        )
                    }
                    }
                }
            }
        }
    }
    if (creatingFolder) {
        NameInputDialog(
            title = "새 폴더",
            value = folderName,
            confirmLabel = "만들기",
            onValueChange = { folderName = it },
            onDismiss = { creatingFolder = false },
            onConfirm = { creatingFolder = false; model.createFolder(folderName) },
        )
    }
    renamingItem?.let { item ->
        NameInputDialog(
            title = "이름 변경",
            value = renameText,
            confirmLabel = "변경",
            onValueChange = { renameText = it },
            onDismiss = { renamingItem = null },
            onConfirm = { renamingItem = null; model.renameItem(item, renameText) },
        )
    }
    deletingItem?.let { item ->
        AlertDialog(
            onDismissRequest = { deletingItem = null },
            title = { Text(if (item.isDirectory) "폴더를 삭제할까요?" else "파일을 삭제할까요?") },
            text = { Text(if (item.isDirectory) "${item.name} 폴더와 내부의 모든 항목이 원격 서버에서 삭제됩니다. 되돌릴 수 없습니다." else "${item.name} 파일이 원격 서버에서 삭제됩니다. 되돌릴 수 없습니다.") },
            dismissButton = { TextButton(onClick = { deletingItem = null }) { Text("취소") } },
            confirmButton = {
                TextButton(
                    onClick = { deletingItem = null; model.deleteItem(item) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("삭제") }
            },
        )
    }
    folderTrailerTarget?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderTrailerTarget = null },
            title = { Text("영상 만들기") },
            text = { Text("하위 폴더에 있는 미디어도 함께 모을까요?") },
            confirmButton = {
                TextButton(onClick = {
                    folderTrailerTarget = null
                    model.startRemoteFolderTrailer(folder, includeSubfolders = true)
                }) { Text("하위 폴더 포함") }
            },
            dismissButton = {
                TextButton(onClick = {
                    folderTrailerTarget = null
                    model.startRemoteFolderTrailer(folder, includeSubfolders = false)
                }) { Text("현재 폴더만") }
            },
        )
    }
    folderMusicOverlayTarget?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderMusicOverlayTarget = null },
            title = { Text("뮤직비디오") },
            text = { Text("하위 폴더에 있는 미디어도 함께 모을까요?") },
            confirmButton = {
                TextButton(onClick = {
                    folderMusicOverlayTarget = null
                    model.startRemoteFolderMusicOverlay(folder, includeSubfolders = true)
                }) { Text("하위 폴더 포함") }
            },
            dismissButton = {
                TextButton(onClick = {
                    folderMusicOverlayTarget = null
                    model.startRemoteFolderMusicOverlay(folder, includeSubfolders = false)
                }) { Text("현재 폴더만") }
            },
        )
    }
    if (showDownloadDestDialog && pendingDownloadItems != null) {
        val items = pendingDownloadItems!!
        DownloadDestinationDialog(
            itemCount = items.size,
            isLocal = false,
            onDismiss = {
                showDownloadDestDialog = false
                pendingDownloadItems = null
            },
            onSelectPhoneHard = {
                showDownloadDestDialog = false
                showPhoneHardPickerForDownload = true
            },
            onSelectExternalSaf = {
                showDownloadDestDialog = false
                model.setBrowserSystemPickerOpen(true)
                externalTreeDownloadLauncher.launch(null)
            },
        )
    }
    if (showPhoneHardPickerForDownload && pendingDownloadItems != null) {
        val items = pendingDownloadItems!!
        SelectPhoneHardDestinationDialog(
            title = "폰하드 다운로드 대상 폴더 선택",
            model = model,
            targets = emptyList(),
            onDismiss = {
                showPhoneHardPickerForDownload = false
                pendingDownloadItems = null
            },
            onSelectDestination = { destSubdir ->
                showPhoneHardPickerForDownload = false
                pendingDownloadItems = null
                model.downloadRemoteItems(browser.connection, items, NetworkDownloadDestination.PhoneHard(destSubdir))
            },
        )
    }
    actionItem?.let { item ->
        val isThumbnailActive = state.superThumbnailWork?.status in setOf(SuperThumbnailWorkStatus.WAITING, SuperThumbnailWorkStatus.RUNNING)
        val canTrailer = item.isDirectory || item.isVideo || item.isImage
        val canMusic = item.isDirectory || item.isVideo || item.isImage
        val canShare = !item.isDirectory
        val isFavorite = item.path in favoritePaths

        val row1 = listOf(
            BaselineAction(
                id = "select",
                title = if (item.id in selectedIds) "선택 해제" else "선택",
                icon = Icons.Default.CheckCircle,
                enabled = true,
                onClick = {
                    actionItem = null
                    selectionMode = true
                    toggleRemoteSelection(selectedIds, item.id)
                },
            ),
            BaselineAction(
                id = "copy",
                title = "복사",
                icon = Icons.Default.ContentCopy,
                enabled = capabilities.copy,
                disabledReason = if (!capabilities.copy) "복사가 지원되지 않는 연결입니다." else null,
                onClick = {
                    actionItem = null
                    model.beginTransfer(item, RemoteTransferAction.COPY)
                },
            ),
            BaselineAction(
                id = "move",
                title = "이동",
                icon = Icons.Default.DriveFileMove,
                enabled = capabilities.move,
                disabledReason = if (!capabilities.move) "이동이 지원되지 않는 연결입니다." else null,
                onClick = {
                    actionItem = null
                    model.beginTransfer(item, RemoteTransferAction.MOVE)
                },
            ),
            BaselineAction(
                id = "rename",
                title = "수정",
                icon = Icons.Default.Edit,
                enabled = capabilities.rename,
                disabledReason = if (!capabilities.rename) "이름 수정이 지원되지 않는 연결입니다." else null,
                onClick = {
                    actionItem = null
                    renameText = item.name
                    renamingItem = item
                },
            ),
            BaselineAction(
                id = "delete",
                title = "삭제",
                icon = Icons.Default.Delete,
                enabled = capabilities.delete,
                disabledReason = if (!capabilities.delete) "삭제가 지원되지 않는 연결입니다." else null,
                isDestructive = true,
                onClick = {
                    actionItem = null
                    deletingItem = item
                },
            ),
        )

        val row2 = listOf(
            BaselineAction(
                id = "favorite",
                title = if (isFavorite) "별 해제" else "즐겨찾기",
                icon = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                enabled = true,
                onClick = {
                    actionItem = null
                    model.toggleFavorite(item)
                },
            ),
            BaselineAction(
                id = "trailer",
                title = "예고편 보기",
                icon = Icons.Default.Movie,
                enabled = canTrailer,
                disabledReason = if (!canTrailer) "예고편 보기와 뮤직비디오는 사진, 영상과 폴더에서 쓸 수 있습니다." else null,
                onClick = {
                    actionItem = null
                    if (item.isDirectory) {
                        model.prepareRemoteFolderTrailer(item) { hasSubs ->
                            if (hasSubs) folderTrailerTarget = item
                            else model.startRemoteFolderTrailer(item, false)
                        }
                    } else {
                        model.startRemoteTrailer(listOf(item))
                    }
                },
            ),
            BaselineAction(
                id = "music",
                title = "뮤직비디오",
                icon = Icons.Default.MusicNote,
                enabled = canMusic,
                disabledReason = if (!canMusic) "예고편 보기와 뮤직비디오는 사진, 영상과 폴더에서 쓸 수 있습니다." else null,
                onClick = {
                    actionItem = null
                    if (item.isDirectory) {
                        model.prepareRemoteFolderMusicOverlay(item) { hasSubs ->
                            if (hasSubs) folderMusicOverlayTarget = item
                            else model.startRemoteFolderMusicOverlay(item, false)
                        }
                    } else {
                        model.startRemoteMusicOverlay(listOf(item), null)
                    }
                },
            ),
            BaselineAction(
                id = "download",
                title = "다운로드",
                icon = Icons.Default.Download,
                enabled = true,
                onClick = {
                    actionItem = null
                    pendingDownloadItems = listOf(item)
                    showDownloadDestDialog = true
                },
            ),
            BaselineAction(
                id = "share",
                title = "공유",
                icon = Icons.Default.Share,
                enabled = true,
                onClick = {
                    actionItem = null
                    model.shareItem(item)
                },
            ),
            BaselineAction(
                id = "thumbnail",
                title = if (isThumbnailActive) "생성 중지" else "썸네일 만들기",
                icon = if (isThumbnailActive) Icons.Default.Stop else Icons.Default.Image,
                enabled = item.isDirectory,
                disabledReason = if (!item.isDirectory) "폴더 항목만 썸네일 생성을 지원합니다." else null,
                isDestructive = isThumbnailActive,
                onClick = {
                    actionItem = null
                    if (isThumbnailActive) {
                        model.cancelSuperThumbnail()
                    } else {
                        model.selectSuperThumbnailFolder(browser.connection.id, item.path, item.name)
                        model.startSuperThumbnail()
                    }
                },
            ),
        )

        val sizeText = if (item.isDirectory) {
            when (val count = state.folderCountCache[item.id]) {
                is FolderChildCountState.Ready -> "폴더 ${count.folderCount}개 · 파일 ${count.fileCount}개"
                FolderChildCountState.Loading -> "개수 확인 중…"
                FolderChildCountState.Error -> "개수 확인 불가"
                null -> "폴더"
            }
        } else {
            formatBytes(item.size)
        }
        val dateText = item.modifiedAt?.let {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date.from(it))
        } ?: "알 수 없음"

        val nasExtension = item.name.substringAfterLast('.', "").uppercase()
        AppleBaselineActionSheet(
            title = item.name,
            isFolder = item.isDirectory,
            row1 = row1,
            row2 = row2,
            infoMedia = when {
                item.isDirectory -> "폴더"; item.isImage -> "사진"; item.isVideo -> "비디오"; item.isAudio -> "오디오"; else -> "파일"
            },
            infoFeature = if (item.isDirectory) "하위 항목을 담는 폴더" else listOf(nasExtension, item.mimeType ?: "파일").filter { it.isNotBlank() }.joinToString(" · "),
            infoDate = dateText,
            infoSize = sizeText,
            onDismiss = { actionItem = null },
            onShowDisabledReason = { reason ->
                actionItem = null
                actionToastMessage = reason
            },
        )
    }
    batchTransfer?.let { action ->
        val verb = if (action == RemoteTransferAction.COPY) "복사" else "이동"
        AlertDialog(
            onDismissRequest = { batchTransfer = null },
            title = { Text("${selectedItems.size}개 항목을 ${verb}할까요?") },
            text = { Text("다음 화면에서 대상 폴더를 선택합니다.") },
            dismissButton = { TextButton(onClick = { batchTransfer = null }) { Text("취소") } },
            confirmButton = { TextButton(onClick = { batchTransfer = null; model.beginTransfer(selectedItems, action); selectedIds.clear(); selectionMode = false }) { Text(verb) } },
        )
    }
    if (confirmBatchDelete) {
        AlertDialog(
            onDismissRequest = { confirmBatchDelete = false },
            title = { Text("${selectedItems.size}개 항목을 삭제할까요?") },
            text = { Text("선택한 폴더의 내부 항목도 원격 서버에서 삭제되며 되돌릴 수 없습니다.") },
            dismissButton = { TextButton(onClick = { confirmBatchDelete = false }) { Text("취소") } },
            confirmButton = { TextButton(onClick = { confirmBatchDelete = false; model.deleteItems(selectedItems); selectedIds.clear(); selectionMode = false }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("삭제") } },
        )
    }

    actionToastMessage?.let { msg ->
        LaunchedEffect(msg) {
            delay(2200)
            actionToastMessage = null
        }
        Box(
            modifier = Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 72.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shadowElevation = 6.dp,
            ) {
                Text(
                    msg,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

internal data class CoverFlowViewModeOption(
    val title: String,
    val selected: Boolean,
    val icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    val onSelect: () -> Unit,
)

@Composable
internal fun RemoteBrowserCoverFlow(
    items: List<RemoteFileItem>,
    thumbnails: Map<String, android.graphics.Bitmap>,
    theme: AppTheme,
    title: String,
    usesDarkBackground: Boolean,
    onBack: () -> Unit,
    onDashboard: (() -> Unit)? = null,
    viewModes: List<CoverFlowViewModeOption> = emptyList(),
    onToggleBackground: (Boolean) -> Unit,
    onActivate: (RemoteFileItem) -> Unit,
    onLoadThumbnail: (RemoteFileItem) -> Unit,
    initialSelectedIndex: Int = 0,
    onSelectedIndexChange: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hostView = LocalView.current
    val statusBarController = remember(context, hostView) {
        context.findHostActivity()?.window?.let { WindowInsetsControllerCompat(it, hostView) }
    }
    DisposableEffect(statusBarController, hostView) {
        val controller = statusBarController
        if (controller == null) {
            onDispose { }
        } else {
            val rootInsets = androidx.core.view.ViewCompat.getRootWindowInsets(hostView)
            val statusWasVisible = rootInsets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
            val navigationWasVisible = rootInsets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true
            val previousBehavior = controller.systemBarsBehavior
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            onDispose {
                if (statusWasVisible) controller.show(WindowInsetsCompat.Type.statusBars())
                else controller.hide(WindowInsetsCompat.Type.statusBars())
                if (navigationWasVisible) controller.show(WindowInsetsCompat.Type.navigationBars())
                else controller.hide(WindowInsetsCompat.Type.navigationBars())
                controller.systemBarsBehavior = previousBehavior
            }
        }
    }
    var backgroundMenu by remember { mutableStateOf(false) }
    val boundedInitialIndex = initialSelectedIndex.coerceIn(0, items.lastIndex.coerceAtLeast(0))
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = boundedInitialIndex)
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val scrollPosition by remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val viewportCenter = (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
            val anchor = layout.visibleItemsInfo.minByOrNull {
                kotlin.math.abs((it.offset + it.size / 2f) - viewportCenter)
            }
            if (anchor == null) boundedInitialIndex.toFloat() else {
                CoverFlowLayoutPolicy.continuousPosition(
                    itemIndex = anchor.index,
                    itemCenter = anchor.offset + anchor.size / 2f,
                    viewportCenter = viewportCenter,
                    itemSize = anchor.size.toFloat(),
                )
            }
        }
    }
    val selectedIndex = scrollPosition.roundToInt().coerceIn(0, items.lastIndex.coerceAtLeast(0))
    LaunchedEffect(listState, items) {
        snapshotFlow { scrollPosition.roundToInt().coerceIn(0, items.lastIndex.coerceAtLeast(0)) }
            .collect(onSelectedIndexChange)
    }
    LaunchedEffect(items, boundedInitialIndex) {
        if (items.isNotEmpty() && selectedIndex != boundedInitialIndex) {
            listState.scrollToItem(boundedInitialIndex)
        }
        runCatching { focusRequester.requestFocus() }
    }
    val background = if (usesDarkBackground) Color.Black else Color.White
    val chromeForeground = if (usesDarkBackground) Color.White.copy(alpha = .88f) else Color.Black.copy(alpha = .82f)
    val chromeBackground = if (usesDarkBackground) Color.White.copy(alpha = .10f) else Color.White.copy(alpha = .92f)
    val chromeBorder = if (usesDarkBackground) Color.White.copy(alpha = .16f) else Color.Black.copy(alpha = .10f)

    fun moveSelection(offset: Int): Boolean {
        if (items.isEmpty()) return false
        val target = (selectedIndex + offset).coerceIn(0, items.lastIndex)
        if (target == selectedIndex) return false
        val itemSize = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.toFloat() ?: 1f
        scope.launch { listState.animateScrollBy((target - scrollPosition) * itemSize) }
        return true
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(background)
            .semantics {
                contentDescription = "플로잉 보기"
                customActions = listOf(
                    CustomAccessibilityAction("이전 항목") { moveSelection(-1) },
                    CustomAccessibilityAction("다음 항목") { moveSelection(1) },
                )
            }
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false
                else when (event.key) {
                    Key.DirectionLeft -> moveSelection(-1)
                    Key.DirectionRight -> moveSelection(1)
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                        items.getOrNull(selectedIndex)?.let(onActivate) != null
                    }
                    Key.Escape, Key.Back -> { onBack(); true }
                    else -> false
                }
            },
    ) {
        val widthValue = maxWidth.value
        val heightValue = maxHeight.value
        val centralCardSide = CoverFlowLayoutPolicy.centralCardSide(
            containerWidth = widthValue,
            containerHeight = heightValue,
            topContentInset = 0f,
            bottomContentInset = 0f,
        )
        val baseCardSide = CoverFlowLayoutPolicy.baseCardSide(widthValue, centralCardSide)
        val step = CoverFlowLayoutPolicy.cardStep(widthValue).dp
        if (items.isEmpty()) {
            EmptyState(Icons.Default.Folder, "빈 폴더", "이 폴더에는 표시할 파일이 없습니다.", Modifier.fillMaxSize())
        } else {
            LazyRow(
                state = listState,
                flingBehavior = rememberSnapFlingBehavior(listState),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = (maxWidth - step) / 2),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(items, key = { it.id }) { item ->
                    val index = items.indexOf(item)
                    val selected = index == selectedIndex
                    val distance = index - scrollPosition
                    val absoluteDistance = kotlin.math.abs(distance)
                    val renderedSide = CoverFlowLayoutPolicy.renderedCardSide(
                        distance,
                        baseCardSide,
                        centralCardSide,
                    )
                    val horizontalPush = CoverFlowLayoutPolicy.horizontalPush(
                        distance,
                        baseCardSide,
                        centralCardSide,
                    )
                    val reflectionHeight = minOf(
                        renderedSide * if (usesDarkBackground) .15f else .10f,
                        if (usesDarkBackground) 44f else 20f,
                    ).dp
                    if (item.isImage || item.isVideo || item.isDirectory) LaunchedEffect(item.id, item.size, item.modifiedAt) { onLoadThumbnail(item) }
                    Box(
                        Modifier
                            .width(step)
                            .fillParentMaxHeight()
                            .zIndex(20f - absoluteDistance),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            Modifier.requiredWidth(centralCardSide.dp).graphicsLayer {
                                rotationY = when { kotlin.math.abs(distance) < .02f -> 0f; distance < 0f -> 42f; else -> -42f }
                                cameraDistance = 14f * density
                                translationX = horizontalPush * density
                                alpha = if (absoluteDistance > CoverFlowLayoutPolicy.VISIBLE_CARD_COUNT_PER_SIDE) {
                                    0f
                                } else {
                                    CoverFlowLayoutPolicy.opacity(distance)
                                }
                            }.semantics {
                                contentDescription = item.name
                                this.selected = selected
                                role = Role.Button
                            }.clickable(role = Role.Button) {
                                if (selected || index == selectedIndex) {
                                    onActivate(item)
                                } else {
                                    scope.launch { listState.animateScrollToItem(index) }
                                }
                            },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Spacer(Modifier.height(reflectionHeight))
                            Surface(
                                modifier = Modifier.requiredSize(renderedSide.dp),
                                shape = RoundedCornerShape(if (selected) 18.dp else 13.dp),
                                color = Color.Black,
                                border = BorderStroke(1.dp, if (usesDarkBackground) Color.White.copy(alpha = .16f) else Color.Black.copy(alpha = .15f)),
                                shadowElevation = if (selected) 10.dp else 2.dp,
                            ) {
                                RemoteFileArtwork(item, thumbnails[item.id], theme, Modifier.fillMaxSize(), renderedSide.dp * .42f, if (selected) 18.dp else 13.dp)
                            }
                            RemoteFileArtwork(
                                item,
                                thumbnails[item.id],
                                theme,
                                Modifier
                                    .width(renderedSide.dp)
                                    .height(reflectionHeight)
                                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                                    .drawWithContent {
                                        drawContent()
                                        drawRect(
                                            brush = Brush.verticalGradient(
                                                colors = listOf(Color.Black, Color.Transparent),
                                            ),
                                            blendMode = BlendMode.DstIn,
                                        )
                                    }
                                    .graphicsLayer {
                                        rotationX = 180f
                                        alpha = if (usesDarkBackground) .24f else .12f
                                    },
                                22.dp,
                                8.dp,
                            )
                        }
                    }
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().height(if (usesDarkBackground) 72.dp else 82.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, if (usesDarkBackground) Color.White.copy(alpha = .10f) else Color.Black.copy(alpha = .06f)))),
            contentAlignment = Alignment.BottomCenter,
        ) {
            items.getOrNull(selectedIndex)?.name?.let { centeredItemName ->
                Text(
                    centeredItemName,
                    color = chromeForeground,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 32.dp, vertical = 10.dp),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.systemBarsIgnoringVisibility).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompositionLocalProvider(LocalViewConfiguration provides longPress450Configuration()) {
                Surface(
                    modifier = Modifier
                        .size(44.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "플로잉 닫기"
                            customActions = listOf(
                                CustomAccessibilityAction("NasFinder 첫 화면") {
                                    (onDashboard ?: onBack)()
                                    true
                                },
                            )
                        }
                        .clip(CircleShape)
                        .combinedClickable(
                            onClick = onBack,
                            onLongClick = onDashboard ?: onBack,
                            onLongClickLabel = "NasFinder 첫 화면",
                        ),
                    shape = CircleShape,
                    color = chromeBackground,
                    border = BorderStroke(1.dp, chromeBorder),
                    shadowElevation = 2.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.ChevronLeft, null, tint = chromeForeground) }
                }
            }
            Text(title, color = chromeForeground, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box {
                Surface(onClick = { backgroundMenu = true }, modifier = Modifier.size(44.dp), shape = CircleShape, color = chromeBackground, border = BorderStroke(1.dp, chromeBorder), shadowElevation = 2.dp) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.MoreHoriz, "플로잉 배경", tint = MaterialTheme.colorScheme.primary) }
                }
                DropdownMenu(backgroundMenu, onDismissRequest = { backgroundMenu = false }) {
                    if (viewModes.isNotEmpty()) {
                        viewModes.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.title) },
                                leadingIcon = mode.icon?.let { icon -> { Icon(icon, null) } },
                                trailingIcon = {
                                    if (mode.selected) Icon(Icons.Default.Check, null)
                                },
                                onClick = {
                                    backgroundMenu = false
                                    mode.onSelect()
                                },
                            )
                        }
                        HorizontalDivider()
                    }
                    DropdownMenuItem(text = { Text("밝은 배경") }, trailingIcon = { if (!usesDarkBackground) Icon(Icons.Default.Check, null) }, onClick = { backgroundMenu = false; onToggleBackground(false) })
                    DropdownMenuItem(text = { Text("어두운 배경") }, trailingIcon = { if (usesDarkBackground) Icon(Icons.Default.Check, null) }, onClick = { backgroundMenu = false; onToggleBackground(true) })
                }
            }
        }
    }
}

private data class BrowserCapabilities(
    val createFolder: Boolean,
    val rename: Boolean,
    val delete: Boolean,
    val upload: Boolean,
    val copy: Boolean,
    val move: Boolean,
)

private fun browserCapabilities(kind: ConnectionKind): BrowserCapabilities = when (kind) {
    ConnectionKind.SYNOLOGY, ConnectionKind.SFTP, ConnectionKind.SMB, ConnectionKind.WEBDAV,
    ConnectionKind.FTP, ConnectionKind.DROPBOX, ConnectionKind.ONEDRIVE, ConnectionKind.GOOGLE_DRIVE ->
        BrowserCapabilities(true, true, true, true, true, true)
}

private fun toggleRemoteSelection(selectedIds: MutableList<String>, id: String) {
    if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
}

@Composable
private fun ThumbnailTrafficStatus(traffic: com.armsone.nasfinder.data.RemoteThumbnailTrafficSnapshot) {
    if (traffic.requestCount == 0 && !traffic.limitReached) return
    val maxBytes = 64L * 1024 * 1024
    val fraction = if (traffic.limitReached) 1f else
        (maxOf(traffic.expectedBytes, traffic.actualBytes).toDouble() / maxBytes)
            .coerceIn(0.0, 1.0).toFloat()
    Column(Modifier.fillMaxWidth()) {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(3.dp))
        Text(
            if (traffic.limitReached) "썸네일 사용 한도 도달 · 요청 ${traffic.requestCount}회 · 예상 ${formatBytes(traffic.expectedBytes)} · 실제 ${formatBytes(traffic.actualBytes)}"
            else "썸네일 요청 ${traffic.requestCount}회 · 예상 ${formatBytes(traffic.expectedBytes)} · 실제 ${formatBytes(traffic.actualBytes)}",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (traffic.limitReached) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun remoteParentPath(path: String, rootPath: String): String? {
    val root = rootPath.trimEnd('/').ifBlank { "/" }
    val current = path.trimEnd('/').ifBlank { "/" }
    if (current == root || current == "/") return null
    val parent = current.substringBeforeLast('/', "").ifBlank { "/" }
    return if (root != "/" && !parent.startsWith(root)) root else parent
}

@Composable
private fun FileRow(
    item: RemoteFileItem,
    thumbnail: android.graphics.Bitmap?,
    theme: AppTheme,
    selected: Boolean,
    selectionMode: Boolean,
    accessibilityLayout: Boolean,
    folderChildCount: FolderChildCountState? = null,
    inlineAudioState: InlineAudioPlaybackState? = null,
    onToggleInlineAudio: (() -> Unit)? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    CompositionLocalProvider(LocalViewConfiguration provides longPress450Configuration()) { Surface(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "작업 보기"), color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            // iOS RemoteFileListRow: 58pt (accessibility 50) thumbnail, corner 9, thumbnailSurface fill and 1pt thumbnailBorder.
            val porcelainRow = usesUnifiedLayout(theme)
            val artworkSide = if (accessibilityLayout) 50.dp else 58.dp
            val wideRow = !accessibilityLayout && LocalConfiguration.current.screenWidthDp >= 600
            Box(
                modifier = Modifier
                    .size(artworkSide)
                    .then(
                        if (porcelainRow) Modifier.clip(RoundedCornerShape(9.dp)).background(thumbnailSurfaceColor()).border(1.dp, thumbnailBorderColor(), RoundedCornerShape(9.dp))
                        else Modifier
                    )
                    .then(
                        if (onToggleInlineAudio != null) {
                            Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .clickable(
                                    onClickLabel = if (inlineAudioState?.isPlaying == true) "일시정지" else "재생",
                                    onClick = onToggleInlineAudio,
                                )
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                RemoteFileArtwork(item, thumbnail, theme, Modifier.fillMaxSize(), 30.dp, 9.dp)
                if (item.isAudio && !selectionMode && onToggleInlineAudio != null) {
                    InlineAudioThumbnailOverlay(
                        state = inlineAudioState,
                        cornerRadius = 9.dp,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = if (accessibilityLayout) 3 else 2, overflow = TextOverflow.Ellipsis)
                if (!item.isDirectory) {
                    val baseMeta = listOfNotNull(
                        item.extension.uppercase().takeIf(String::isNotBlank),
                        formatBytes(item.size),
                    )
                    val inlineTimeLabel = inlineAudioState?.let { audioState ->
                        when {
                            audioState.isPreparing && audioState.downloadFraction != null ->
                                "버퍼링 ${(audioState.downloadFraction * 100f).roundToInt().coerceIn(0, 100)}%"
                            audioState.isPreparing -> "준비 중…"
                            audioState.durationMillis > 0L -> {
                                val pos = formatPlaybackDuration(audioState.positionMillis) ?: "0:00"
                                val dur = formatPlaybackDuration(audioState.durationMillis) ?: "0:00"
                                "$pos / $dur"
                            }
                            audioState.isPlaying -> "재생 중"
                            else -> null
                        }
                    }
                    Text(
                        (baseMeta + listOfNotNull(inlineTimeLabel)).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (inlineTimeLabel != null) MaterialTheme.colorScheme.primary else fileMetaColor(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    val countLabel = when (folderChildCount) {
                        is FolderChildCountState.Ready -> "폴더 ${folderChildCount.folderCount}, 파일 ${folderChildCount.fileCount}"
                        FolderChildCountState.Loading -> "개수 확인 중…"
                        FolderChildCountState.Error -> "개수 확인 불가"
                        null -> null
                    }
                    if (countLabel != null) {
                        val baseStyle = MaterialTheme.typography.labelMedium
                        Text(
                            countLabel,
                            style = baseStyle.copy(fontSize = baseStyle.fontSize * 0.8f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (!wideRow) item.modifiedAt?.let { modified ->
                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date.from(modified)), style = MaterialTheme.typography.labelMedium, color = fileMetaColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // 태블릿 폭에서는 수정 시각을 오른쪽 열로 보내 이름 줄을 길게 쓰고 목록을 훑기 쉽게 한다.
            if (wideRow) item.modifiedAt?.let { modified ->
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date.from(modified)),
                    modifier = Modifier.padding(start = 12.dp).width(190.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = fileMetaColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selectionMode) SelectionIndicator(selected)
        }
    } }
}

@Composable
private fun InlineAudioThumbnailOverlay(
    state: InlineAudioPlaybackState?,
    cornerRadius: Dp,
) {
    val isActive = state != null
    val isPlaying = state?.isPlaying == true
    val isPreparing = state?.isPreparing == true
    val progress = (state?.progress ?: 0f).coerceIn(0f, 1f)
    val downloadFraction = state?.downloadFraction?.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Color.Black.copy(alpha = if (isActive) 0.42f else 0.24f)
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isPreparing) {
            if (downloadFraction != null && downloadFraction > 0f) {
                CircularProgressIndicator(
                    progress = { downloadFraction },
                    modifier = Modifier.size(34.dp),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.28f),
                    strokeWidth = 2.75.dp,
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.24f),
                    strokeWidth = 2.75.dp,
                )
            }
        } else if (isActive) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(36.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.White.copy(alpha = 0.30f),
                strokeWidth = 3.dp,
            )
            Surface(
                modifier = Modifier.size(28.dp),
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.55f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "일시정지" else "재생",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        } else {
            Surface(
                modifier = Modifier.size(30.dp),
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.52f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "재생",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FileTile(
    item: RemoteFileItem,
    thumbnail: android.graphics.Bitmap?,
    theme: AppTheme,
    poster: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    folderChildCount: FolderChildCountState? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val porcelain = usesUnifiedLayout(theme)
    // iOS RemoteFileGridCell: square cell, corner 11 (poster 15), thumbnailSurface fill and 1pt thumbnailBorder.
    // Porcelain follows the approved 02-files board: real cover aspect-filled, corner 8, no frame; selected = red outline.
    val corner = if (porcelain && !poster) 8.dp else if (poster) 15.dp else 11.dp
    CompositionLocalProvider(LocalViewConfiguration provides longPress450Configuration()) {
        Column(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "작업 보기"), verticalArrangement = Arrangement.spacedBy(if (poster) 9.dp else 6.dp)) {
            Box {
                Surface(
                    Modifier.fillMaxWidth().aspectRatio(1f),
                    shape = RoundedCornerShape(corner),
                    color = if (selected && !porcelain) MaterialTheme.colorScheme.primaryContainer else thumbnailSurfaceColor(),
                    border = if (porcelain) (if (selected) BorderStroke(2.dp, themePalette().accent) else null) else BorderStroke(1.dp, thumbnailBorderColor()),
                ) {
                    RemoteFileArtwork(item, thumbnail, theme, Modifier.fillMaxSize(), if (poster) 52.dp else 42.dp, corner, cropCover = porcelain)
                }
                // No per-item duration exists in the remote listing, so videos keep a small play mark instead of a time badge.
                if (porcelain && item.isVideo && thumbnail != null) PackIcon(
                    PackGlyph.PLAY, null,
                    Modifier.align(Alignment.BottomEnd).padding(5.dp).size(20.dp).background(Color.Black.copy(alpha = .55f), RoundedCornerShape(4.dp)).padding(3.dp),
                    tint = Color.White,
                )
                if (selectionMode) SelectionIndicator(selected, Modifier.align(Alignment.TopEnd).padding(if (poster) 8.dp else 4.dp))
            }
                Text(
                    item.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = if (poster) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth(),
                )
            if (!item.isDirectory) {
                Text(
                    listOfNotNull(item.extension.uppercase().takeIf(String::isNotBlank), formatBytes(item.size)).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = fileMetaColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                val countLabel = when (folderChildCount) {
                    is FolderChildCountState.Ready -> "폴더 ${folderChildCount.folderCount}, 파일 ${folderChildCount.fileCount}"
                    FolderChildCountState.Loading -> "개수 확인 중…"
                    FolderChildCountState.Error -> "개수 확인 불가"
                    null -> null
                }
                if (countLabel != null) {
                    val baseStyle = MaterialTheme.typography.labelSmall
                    Text(
                        countLabel,
                        style = baseStyle.copy(fontSize = baseStyle.fontSize * 0.8f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (poster) {
                item.modifiedAt?.let { modified ->
                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date.from(modified)), style = MaterialTheme.typography.labelMedium, color = fileMetaColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun longPress450Configuration(): ViewConfiguration {
    val base = LocalViewConfiguration.current
    return remember(base) {
        object : ViewConfiguration by base {
            override val longPressTimeoutMillis: Long = 450L
        }
    }
}

@Composable
private fun longPress500Configuration(): ViewConfiguration {
    val base = LocalViewConfiguration.current
    return remember(base) {
        object : ViewConfiguration by base {
            override val longPressTimeoutMillis: Long = 500L
        }
    }
}

@Composable
private fun RemoteFileArtwork(item: RemoteFileItem, thumbnail: android.graphics.Bitmap?, theme: AppTheme, modifier: Modifier, iconSize: androidx.compose.ui.unit.Dp, cornerRadius: androidx.compose.ui.unit.Dp, cropCover: Boolean = false) {
    if (thumbnail != null && !thumbnail.isRecycled) {
        SkinAwareThumbnailImage(
            bitmap = thumbnail,
            cacheIdentity = "remote:${item.id}:${item.path}:${item.size}:${item.modifiedAt}:${item.thumbnailUrl}",
            contentDescription = "${item.name} 썸네일",
            modifier = modifier.clip(RoundedCornerShape(cornerRadius)),
            // iOS RemoteThumbnailRenderedContent: porcelain shows non-folder photos whole (.fit) on the paper surface.
            // Grid covers (cropCover) are aspect-filled like the approved boards; the full preview elsewhere is untouched.
            contentScale = if (usesUnifiedLayout(theme) && !item.isDirectory && !cropCover) ContentScale.Fit else ContentScale.Crop,
            isFolderCover = item.isDirectory,
        )
    } else {
        BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
            val porcelain = usesUnifiedLayout(theme)
            Icon(
                if (item.isDirectory) Icons.Default.Folder
                else if (item.isImage) Icons.Default.Image
                else if (item.isVideo) Icons.Default.Movie
                else if (item.isAudio) Icons.Default.MusicNote
                else Icons.Default.InsertDriveFile,
                null,
                // Porcelain fallback glyph: 0.38 × the cell side, folderBlue for folders and secondaryText otherwise.
                modifier = Modifier.size(if (porcelain) minOf(maxWidth, maxHeight) * .38f else iconSize),
                tint = if (item.isDirectory) folderColor(theme) else if (porcelain) fileMetaColor() else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun FileActionsMenu(item: RemoteFileItem, capabilities: BrowserCapabilities, onRename: () -> Unit, onDelete: () -> Unit, onCopy: () -> Unit, onMove: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, "${item.name} 작업") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (capabilities.rename) DropdownMenuItem(text = { Text("이름 변경") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { expanded = false; onRename() })
            if (capabilities.copy) DropdownMenuItem(text = { Text("복사") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = { expanded = false; onCopy() })
            if (capabilities.move) DropdownMenuItem(text = { Text("이동") }, leadingIcon = { Icon(Icons.Default.DriveFileMove, null) }, onClick = { expanded = false; onMove() })
            if (capabilities.delete) DropdownMenuItem(text = { Text("삭제") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { expanded = false; onDelete() })
        }
    }
}

@Composable
internal fun NameInputDialog(title: String, value: String, confirmLabel: String, onValueChange: (String) -> Unit, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, onValueChange, singleLine = true, label = { Text("이름") }) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        confirmButton = { TextButton(onClick = onConfirm, enabled = value.trim().isNotEmpty()) { Text(confirmLabel) } },
    )
}

@Composable
private fun DownloadProgressBanner(download: RemoteDownloadState, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (download.action == RemoteFileAction.PREVIEW) Icons.Default.Visibility else Icons.Default.Share,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(download.action.progressTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(download.filename, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            download.fraction?.let { fraction ->
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                if (download.totalBytes > 0) "${formatBytes(download.completedBytes)} / ${formatBytes(download.totalBytes)}"
                else "${formatBytes(download.completedBytes)} 받음",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PreviewLoadProgressStrip(download: RemoteDownloadState, modifier: Modifier = Modifier) {
    val progressModifier = modifier
        .fillMaxWidth()
        .navigationBarsPadding()
        .height(2.dp)
    download.fraction?.let { fraction ->
        LinearProgressIndicator(
            progress = { fraction },
            modifier = progressModifier,
            color = Color.White,
            trackColor = Color.Black,
        )
    } ?: LinearProgressIndicator(
        modifier = progressModifier,
        color = Color.White,
        trackColor = Color.Black,
    )
}

@Composable
private fun BrowserMenu(
    preferences: BrowserPreferences,
    canCreateFolder: Boolean,
    canPaste: Boolean,
    onSelect: () -> Unit,
    onCreateFolder: () -> Unit,
    onPaste: () -> Unit,
    onRefresh: () -> Unit,
    update: (BrowserPreferences) -> Unit,
    pickLayout: BrowserLayout? = null,
    onPickLayout: (BrowserLayout) -> Unit = {},
    /** PhoneHard with nothing to paste: the paste tile is the existing file importer and says "가져오기" (iOS). */
    pasteImports: Boolean = false,
    /** NAS browser with no copy/move pending: the paste tile opens the device file picker and uploads into this folder. */
    pasteUploads: Boolean = false,
    /** PhoneHard only: the existing Google 포토 entry, shown while that location is not hidden. */
    onGooglePhotos: (() -> Unit)? = null,
    /** NAS browser only: explicit 커버플로우 button right of 포스터; null (PhoneHard/pickers) hides it. */
    onCoverFlow: (() -> Unit)? = null,
    onLayoutChosen: () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    BrowserMoreButton { expanded = true }
    if (expanded) ModalBottomSheet(
        onDismissRequest = { expanded = false },
        sheetState = sheetState,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
    ) {
        // iOS browserMorePanel: VStack(spacing 0) of MorePanelSectionTitle + 4pt-spaced CompactPanelButton rows, separated by dividers.
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 14.dp),
        ) {
            BrowserMenuSection("파일 작업") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BrowserActionTile("선택", Icons.Default.CheckCircle, Modifier.weight(1f)) { expanded = false; onSelect() }
                    if (pasteImports) {
                        BrowserActionTile("가져오기", Icons.AutoMirrored.Filled.NoteAdd, Modifier.weight(1f), enabled = canPaste) { expanded = false; onPaste() }
                    } else if (pasteUploads) {
                        BrowserActionTile("기기에서 올리기", Icons.Default.CloudUpload, Modifier.weight(1f), enabled = canPaste) { expanded = false; onPaste() }
                    } else {
                        BrowserActionTile("붙여넣기", Icons.Default.ContentPaste, Modifier.weight(1f), enabled = canPaste) { expanded = false; onPaste() }
                    }
                    if (onGooglePhotos != null) {
                        BrowserActionTile("Google 포토", Icons.Default.AddPhotoAlternate, Modifier.weight(1f)) { expanded = false; onGooglePhotos() }
                    }
                    BrowserActionTile("새 폴더", Icons.Default.CreateNewFolder, Modifier.weight(1f), enabled = canCreateFolder) { expanded = false; onCreateFolder() }
                    BrowserActionTile("새로고침", Icons.Default.Refresh, Modifier.weight(1f)) { expanded = false; onRefresh() }
                }
            }
            BrowserMenuDivider()
            BrowserMenuSection("보기") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BrowserLayout.entries.forEach { layout ->
                        val title = when (layout) { BrowserLayout.LIST -> "자세히"; BrowserLayout.SMALL_GRID -> if (pickLayout != null || onCoverFlow != null) "썸네일" else "작은 썸네일"; BrowserLayout.LARGE_GRID -> "포스터" }
                        val icon = when (layout) { BrowserLayout.LIST -> Icons.Default.List; BrowserLayout.SMALL_GRID -> Icons.Default.GridView; BrowserLayout.LARGE_GRID -> Icons.Default.GridOn }
                        BrowserChoice(title, (pickLayout ?: preferences.layout) == layout, Modifier.weight(1f), icon, labelMaxLines = if (onCoverFlow != null) 2 else 1) {
                            if (pickLayout != null) onPickLayout(layout) else { onLayoutChosen(); update(preferences.copy(layout = layout)) }
                            expanded = false
                        }
                    }
                    if (onCoverFlow != null && pickLayout == null) {
                        BrowserChoice("플로잉", false, Modifier.weight(1f), Icons.Default.ViewCarousel, labelMaxLines = 2) {
                            expanded = false
                            onCoverFlow()
                        }
                    }
                }
            }
            BrowserMenuDivider()
            BrowserMenuSection("정렬") {
                BrowserOptionLabel("기준")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SortField.entries.forEach { sort -> BrowserChoice(sortTitle(sort), preferences.sortField == sort, Modifier.weight(1f)) { update(preferences.copy(sortField = sort)) } }
                }
                BrowserOptionLabel("순서")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BrowserChoice("오름차순", preferences.sortDirection == SortDirection.ASCENDING, Modifier.weight(1f)) { update(preferences.copy(sortDirection = SortDirection.ASCENDING)) }
                    BrowserChoice("내림차순", preferences.sortDirection == SortDirection.DESCENDING, Modifier.weight(1f)) { update(preferences.copy(sortDirection = SortDirection.DESCENDING)) }
                }
                BrowserOptionLabel("이름 우선")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BrowserChoice("숫자 먼저", preferences.namePriority == NamePriority.NUMBERS_FIRST, Modifier.weight(1f)) { update(preferences.copy(namePriority = NamePriority.NUMBERS_FIRST)) }
                    BrowserChoice("한글 먼저", preferences.namePriority == NamePriority.KOREAN_FIRST, Modifier.weight(1f)) { update(preferences.copy(namePriority = NamePriority.KOREAN_FIRST)) }
                    BrowserChoice("외국어 먼저", preferences.namePriority == NamePriority.LATIN_FIRST, Modifier.weight(1f)) { update(preferences.copy(namePriority = NamePriority.LATIN_FIRST)) }
                }
                BrowserOptionLabel("폴더 먼저")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BrowserChoice("끔", !preferences.foldersFirst, Modifier.weight(1f)) { update(preferences.copy(foldersFirst = false)) }
                    BrowserChoice("켬", preferences.foldersFirst, Modifier.weight(1f)) { update(preferences.copy(foldersFirst = true)) }
                }
            }
        }
    }
}

/** iOS regularMoreButton: ellipsis 15 bold in a 32pt accent×.16 circle with an accent×.48 1pt ring (48dp touch target kept). */
@Composable
private fun BrowserMoreButton(onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier.size(48.dp).clickable(onClickLabel = "더 보기", role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "더 보기" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(32.dp).background(accent.copy(alpha = .16f), CircleShape).border(1.dp, accent.copy(alpha = .48f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.MoreHoriz, null, tint = accent, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun BrowserMenuDivider() {
    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = .32f))
}

/** iOS MorePanelSectionTitle: subheadline bold, padding 8×6. */
@Composable
private fun BrowserMenuSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, Modifier.padding(horizontal = 8.dp, vertical = 6.dp), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

/** iOS CompactPanelOptionRow title: caption2 semibold, horizontal padding 2, 5pt above the choices. */
@Composable
private fun BrowserOptionLabel(text: String) {
    Text(text, Modifier.padding(start = 2.dp, end = 2.dp, top = 5.dp, bottom = 5.dp), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun BrowserActionTile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    CompactPanelTile(label, icon, modifier.semantics { contentDescription = label }, enabled = enabled) { if (enabled) onClick() }
}

/** iOS CompactPanelButton(isSelected:) for icon choices and CompactPanelOptionButton (minHeight 44, corner 10, 12pt) for text choices. */
@Composable
private fun BrowserChoice(label: String, selected: Boolean, modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, labelMaxLines: Int = 1, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier
            .then(if (icon == null) Modifier.heightIn(min = 44.dp) else if (labelMaxLines > 1) Modifier.heightIn(min = 72.dp) else Modifier.height(52.dp))
            .clip(shape)
            .background(if (selected) accent else compactPanelFill(), shape)
            .border(.75.dp, if (selected) accent else MaterialTheme.colorScheme.outline.copy(alpha = .24f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        val content = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        icon?.let { Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { Icon(it, null, tint = content, modifier = Modifier.size(19.dp)) } }
        Text(
            label,
            fontSize = if (icon == null) 12.sp else 10.sp,
            fontWeight = if (icon != null || selected) FontWeight.SemiBold else FontWeight.Normal,
            color = content,
            textAlign = TextAlign.Center,
            maxLines = labelMaxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** iOS selectionIndicator: checkmark.circle.fill (accent) or circle (secondary), title2 semibold, on a material circle. */
@Composable
private fun SelectionIndicator(selected: Boolean, modifier: Modifier = Modifier) {
    Icon(
        if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
        null,
        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(26.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = .86f), CircleShape),
    )
}

internal class BrowserSelectionAction(
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val enabled: Boolean,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * iOS selectionBar: "N개 선택" + 전체 선택/전체 해제 header, divider, then horizontally scrolling icon+label actions
 * (spacing 20, min width 58, body semibold icon, caption2 label); bar background, top divider, padding 16 / 10 / 8.
 */
@Composable
private fun BrowserSelectionBar(
    count: Int,
    allSelected: Boolean,
    canToggleAll: Boolean,
    onToggleAll: () -> Unit,
    actions: List<BrowserSelectionAction>,
    note: String?,
    // 스크롤 줄 밖에 항상 보이는 주 동작(폭을 나눠 가진 버튼). 좁은 폰에서도 화면 밖으로 밀리지 않는다.
    primaryActions: List<BrowserSelectionAction> = emptyList(),
) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .94f)) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .32f))
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp), tint = if (count == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                        Text("${count}개 선택", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (count == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    }
                    Text(
                        if (allSelected) "전체 해제" else "전체 선택",
                        Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = canToggleAll, role = Role.Button, onClick = onToggleAll).padding(horizontal = 4.dp, vertical = 6.dp),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (canToggleAll) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f),
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .32f))
                if (primaryActions.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        primaryActions.forEach { action ->
                            FilledTonalButton(
                                onClick = action.onClick,
                                enabled = action.enabled && count > 0,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            ) {
                                Icon(action.icon, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(action.title, maxLines = 1)
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                ) {
                    actions.forEach { action ->
                        val enabled = action.enabled && count > 0
                        val tint = (if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            .let { if (enabled) it else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f) }
                        Column(
                            Modifier.widthIn(min = 58.dp).clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = enabled, role = Role.Button, onClickLabel = action.title, onClick = action.onClick)
                                .padding(vertical = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Icon(action.icon, null, tint = tint, modifier = Modifier.size(22.dp))
                            Text(action.title, fontSize = 11.sp, color = tint, maxLines = 1)
                        }
                    }
                }
                if (note != null) Text(note, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * iOS currentPathBar: secondarySystemBackground strip, caption font, padding 12×8. 36×28 up chip (corner 7, accent×.12),
 * drive glyph, semibold connection name, chevron, monospaced path segments, trailing "N개" / "a/b개" count.
 */
@Composable
private fun BrowserPathBar(
    rootTitle: String,
    segments: List<Pair<String, () -> Unit>>,
    onParent: (() -> Unit)?,
    onRoot: (() -> Unit)?,
    countLabel: String,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().background(compactPanelFill()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (onParent != null) {
                Box(
                    Modifier.size(36.dp, 28.dp).clip(RoundedCornerShape(7.dp)).background(accent.copy(alpha = .12f))
                        .clickable(onClickLabel = "상위 폴더", role = Role.Button, onClick = onParent)
                        .semantics { contentDescription = "상위 폴더" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.ArrowUpward, null, tint = accent, modifier = Modifier.size(14.dp)) }
            }
            Icon(Icons.Default.Storage, null, tint = accent, modifier = Modifier.size(14.dp))
            Text(
                rootTitle,
                Modifier.then(if (onRoot != null) Modifier.clickable(role = Role.Button, onClick = onRoot) else Modifier),
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f), modifier = Modifier.size(12.dp))
            segments.forEachIndexed { index, (title, open) ->
                if (index > 0) Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f), modifier = Modifier.size(10.dp))
                val current = index == segments.lastIndex
                Text(
                    title,
                    Modifier.then(if (!current) Modifier.clickable(role = Role.Button, onClick = open) else Modifier),
                    fontSize = 12.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Text(countLabel, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/**
 * SwiftUI GridItem(.adaptive(minimum:maximum:)): as many columns as fit at [min] + spacing, each capped at [max].
 * Used only for the porcelain browser grids (iOS porcelainColumns); other themes keep their existing grids.
 */
private class AdaptiveRangeCells(private val min: Dp, private val max: Dp) : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val minPx = min.roundToPx().coerceAtLeast(1)
        val count = ((availableSize + spacing) / (minPx + spacing)).coerceAtLeast(1)
        val cell = ((availableSize - spacing * (count - 1)) / count).coerceAtMost(max.roundToPx()).coerceAtLeast(0)
        return List(count) { cell }
    }
    override fun equals(other: Any?) = other is AdaptiveRangeCells && other.min == min && other.max == max
    override fun hashCode() = 31 * min.hashCode() + max.hashCode()
}

/** iOS porcelainColumns: small 96–132 (accessibility 120–170) spacing 10; large 170–300 (accessibility 250–300) spacing 14. */
private fun porcelainGridCells(poster: Boolean, accessibility: Boolean): Pair<GridCells, Dp> = when {
    poster -> AdaptiveRangeCells(if (accessibility) 250.dp else 170.dp, 300.dp) to 14.dp
    else -> AdaptiveRangeCells(if (accessibility) 120.dp else 96.dp, if (accessibility) 170.dp else 132.dp) to 10.dp
}

private fun isImmediateChildOf(relativePath: String, parentSubdirectory: String?): Boolean {
    val normalizedParent = parentSubdirectory?.trim()?.removePrefix("/")?.removeSuffix("/").orEmpty()
    val parentOfItem = relativePath.substringBeforeLast('/', "")
    return parentOfItem == normalizedParent
}

private fun sortTitle(sort: SortField) = when (sort) {
    SortField.NAME -> "이름"
    SortField.KIND -> "종류"
    SortField.SIZE -> "크기"
    SortField.MODIFIED -> "수정일"
}

private enum class InboxLayout(val title: String) {
    DETAILS("자세히"),
    THUMBNAILS("썸네일"),
    POSTERS("포스터"),
    OVERFLOW("오버플로우"),
}

private fun inboxLayoutIcon(layout: InboxLayout) = when (layout) {
    InboxLayout.DETAILS -> Icons.Default.List
    InboxLayout.THUMBNAILS -> Icons.Default.GridView
    InboxLayout.POSTERS -> Icons.Default.GridOn
    InboxLayout.OVERFLOW -> Icons.Default.ViewCarousel
}

private sealed interface PhoneHardActionItem {
    data class Folder(val folder: PhoneHardItem) : PhoneHardActionItem
    data class File(val file: InboxDisplayItem) : PhoneHardActionItem
}

@Composable
private fun InboxScreen(state: AppState, model: NasFinderViewModel) {
    val allFiles = state.inboxFiles
    var currentSubdirectory by rememberSaveable { mutableStateOf(state.initialInboxSubpath) }
    LaunchedEffect(state.initialInboxSubpath) {
        if (state.initialInboxSubpath != null) {
            currentSubdirectory = state.initialInboxSubpath
            model.clearInitialInboxSubpath()
        }
    }
    val files = remember(allFiles, currentSubdirectory) {
        allFiles.filter { isImmediateChildOf(it.relativePath, currentSubdirectory) }
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val webHardStore = remember(context) { WebHardFileStore(context.applicationContext) }
    val webHardConnection = rememberWebHardConnectionState(webHardStore)
    val inboxPreferences = remember(context) {
        context.getSharedPreferences("inbox_ui", Context.MODE_PRIVATE)
    }
    var layout by rememberSaveable {
        mutableStateOf(
            runCatching {
                InboxLayout.valueOf(inboxPreferences.getString("layout", null).orEmpty())
            }.getOrDefault(InboxLayout.DETAILS).let {
                if (it == InboxLayout.OVERFLOW) InboxLayout.POSTERS else it
            },
        )
    }
    val configuredLayout = if (layout == InboxLayout.OVERFLOW) InboxLayout.POSTERS else layout
    var overflowUsesDarkBackground by rememberSaveable {
        mutableStateOf(inboxPreferences.getBoolean("overflow_dark", true))
    }
    fun setLayout(value: InboxLayout) {
        layout = value
        inboxPreferences.edit().putString("layout", value.name).apply()
    }
    var browserPreferences by remember {
        mutableStateOf(
            BrowserPreferences(
                layout = when (configuredLayout) {
                    InboxLayout.DETAILS -> BrowserLayout.LIST
                    InboxLayout.THUMBNAILS -> BrowserLayout.SMALL_GRID
                    InboxLayout.POSTERS, InboxLayout.OVERFLOW -> BrowserLayout.LARGE_GRID
                }
            )
        )
    }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var searchText by rememberSaveable { mutableStateOf("") }

    var selectionMode by remember { mutableStateOf(false) }
    val selectedFileIds = remember { mutableStateListOf<java.util.UUID>() }
    val selectedFolderPaths = remember { mutableStateListOf<String>() }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current.density
    var overflowSelectedIndex by rememberSaveable { mutableIntStateOf(0) }
    val porcelainPhoneHard = usesUnifiedLayout(state.theme)
    val showsAutomaticOverflow = configuredLayout == InboxLayout.POSTERS &&
        AdaptiveOverflowPolicy.shouldUseAutomaticOverflow(
            contentWidthDp = viewportSize.width / density,
            contentHeightDp = viewportSize.height / density,
            itemCount = files.size,
            selectionActive = selectionMode,
            smallestScreenWidthDp = LocalConfiguration.current.smallestScreenWidthDp,
        )
    val displayedLayout = if (showsAutomaticOverflow) InboxLayout.OVERFLOW else configuredLayout
    LaunchedEffect(currentSubdirectory, displayedLayout, selectionMode) {
        if (displayedLayout != InboxLayout.DETAILS || selectionMode) {
            model.stopInlineAudio()
        }
    }
    LaunchedEffect(currentSubdirectory) {
        model.stopInlineAudio()
    }

    val fileImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        model.importPickedFiles(uris)
    }

    LaunchedEffect(layout) {
        if (layout == InboxLayout.OVERFLOW ||
            inboxPreferences.getString("layout", null) == InboxLayout.OVERFLOW.name
        ) {
            layout = InboxLayout.POSTERS
            inboxPreferences.edit().putString("layout", InboxLayout.POSTERS.name).apply()
        }
    }
    LaunchedEffect(Unit) { model.refreshInbox() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.refreshInbox()
            if (event == Lifecycle.Event.ON_STOP) {
                model.stopInlineAudio()
                webHardConnection.stop()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            model.stopInlineAudio()
            webHardConnection.stop()
        }
    }
    LaunchedEffect(webHardConnection.server) {
        while (webHardConnection.server != null) {
            delay(900)
            model.refreshInbox()
        }
    }
    var folderItems by remember { mutableStateOf<List<PhoneHardItem>>(emptyList()) }
    var folderItemsLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(currentSubdirectory, allFiles) {
        folderItemsLoaded = false
        folderItems = model.loadPhoneHardFolder(currentSubdirectory)
        folderItemsLoaded = true
    }

    val displayedFolders = remember(folderItems, searchText) {
        val subs = folderItems.filter { it.kind == PhoneHardEntryKind.DIRECTORY }
        if (searchText.isBlank()) subs else subs.filter { it.name.contains(searchText, ignoreCase = true) }
    }
    val displayedFiles = remember(files, searchText) {
        if (searchText.isBlank()) files else files.filter { it.originalFilename.contains(searchText, ignoreCase = true) }
    }

    val selectedTargets = remember(selectedFolderPaths.toList(), selectedFileIds.toList(), folderItems, files) {
        buildList {
            folderItems.filter { it.kind == PhoneHardEntryKind.DIRECTORY && it.relativePath in selectedFolderPaths }.forEach {
                add(PhoneHardTarget(id = null, relativePath = it.relativePath, isDirectory = true))
            }
            files.filter { it.id in selectedFileIds }.forEach {
                add(PhoneHardTarget(id = it.id, relativePath = it.relativePath, isDirectory = false))
            }
        }
    }

    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var moveTargetsDialog by remember { mutableStateOf<List<PhoneHardTarget>?>(null) }
    var copyTargetsDialog by remember { mutableStateOf<List<PhoneHardTarget>?>(null) }
    var deleteTargetsDialog by remember { mutableStateOf<List<PhoneHardTarget>?>(null) }
    var renameTarget by remember { mutableStateOf<PhoneHardTarget?>(null) }
    var renameInputText by remember { mutableStateOf("") }
    var actionTarget by remember { mutableStateOf<PhoneHardActionItem?>(null) }
    // 선택 from the home favorite task panel: select only the fresh entry (file UUID or folder path) of the requested kind.
    val selectRequest = state.browserSelectRequest
    LaunchedEffect(selectRequest, currentSubdirectory, folderItemsLoaded, folderItems, files) {
        val request = selectRequest ?: return@LaunchedEffect
        if (request.connectionId != "phonehard") return@LaunchedEffect
        // The user moved to another PhoneHard folder before the selection applied: drop the request instead of applying it later.
        if (request.parentPath != currentSubdirectory?.ifEmpty { null }) { model.consumeBrowserSelectRequest(request); return@LaunchedEffect }
        if (!folderItemsLoaded) return@LaunchedEffect
        val found = if (request.isDirectory) {
            folderItems.any { it.kind == PhoneHardEntryKind.DIRECTORY && it.relativePath == request.itemId }
        } else {
            files.any { it.id.toString() == request.itemId }
        }
        if (!found) {
            model.consumeBrowserSelectRequest(request, "‘${request.name}’ 항목을 폰하드 폴더에서 찾을 수 없습니다.")
        } else {
            model.consumeBrowserSelectRequest(request)
            selectedFileIds.clear()
            selectedFolderPaths.clear()
            if (request.isDirectory) selectedFolderPaths.add(request.itemId)
            else files.firstOrNull { it.id.toString() == request.itemId }?.let { selectedFileIds.add(it.id) }
            selectionMode = true
        }
    }
    var folderTrailerTarget by remember { mutableStateOf<String?>(null) }
    var folderMusicTarget by remember { mutableStateOf<String?>(null) }
    var showLocalDownloadDialog by remember { mutableStateOf(false) }
    var showPhoneHardPickerForLocalDownload by remember { mutableStateOf(false) }
    var localDownloadTargets by remember { mutableStateOf<List<PhoneHardTarget>?>(null) }
    var pendingSendIds by remember { mutableStateOf<List<java.util.UUID>?>(null) }
    // NAS 연결 선택 다이얼로그가 복사(기본)인지 이동인지.
    var pendingSendMove by remember { mutableStateOf(false) }
    // 복사/이동 → 위치 선택 다이얼로그의 요청, 그리고 휴대폰 폴더 선택기를 띄워 둔 동안 기억하는 요청.
    var transferChoice by remember { mutableStateOf<PhoneHardTransferRequest?>(null) }
    var phoneHardOpBusy by remember { mutableStateOf(false) }
    var phoneHardOpStatus by remember { mutableStateOf<String?>(null) }
    var actionToastMessage by remember { mutableStateOf<String?>(null) }

    val localExternalTreeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        val targets = localDownloadTargets
        localDownloadTargets = null
        if (treeUri != null && targets != null) {
            phoneHardOpBusy = true
            phoneHardOpStatus = "외부 폴더로 내보내는 중입니다…"
            model.exportPhoneHardTargetsToExternalTree(targets, treeUri) { result ->
                phoneHardOpBusy = false
                phoneHardOpStatus = null
                when (result) {
                    is NetworkDownloadResult.Success -> {
                        actionToastMessage = result.message
                    }
                    is NetworkDownloadResult.Failure -> {
                        model.showInboxErrorMessage(result.message)
                    }
                    is NetworkDownloadResult.Cancelled -> {
                        actionToastMessage = "내보내기가 취소되었습니다."
                    }
                    is NetworkDownloadResult.Skipped -> {
                        model.showInboxErrorMessage(result.reason)
                    }
                }
            }
        }
    }

    // 휴대폰 폴더로 파일을 ZIP 없이 한 개씩 복사/이동: 같은 SAF 폴더 선택기(OpenDocumentTree)를 쓴다.
    val deviceTransferLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        // 요청은 ViewModel이 들고 있다(선택기가 열린 동안 화면이 다시 만들어져도 잃지 않는다).
        val (ids, move) = model.takeStagedDeviceTransfer() ?: return@rememberLauncherForActivityResult
        val verb = if (move) "이동" else "복사"
        if (treeUri == null) {
            actionToastMessage = "폴더를 고르지 않아 ${verb}하지 않았습니다. 선택은 그대로 남겨 두었습니다."
            return@rememberLauncherForActivityResult
        }
        phoneHardOpBusy = true
        phoneHardOpStatus = "파일을 한 개씩 ${verb}하는 중입니다…"
        // 결과 안내(성공 문구·오류 대화상자)와 실패분 재선택은 ViewModel 상태가 맡는다. 여기서는 목록과 선택만 갱신한다.
        model.transferPhoneHardFilesToExternalTree(ids, treeUri, move) { result ->
            folderItems = model.loadPhoneHardFolder(currentSubdirectory)
            phoneHardOpBusy = false
            phoneHardOpStatus = null
            // 끝난 파일은 선택에서 빼고, 실패·취소로 남은 파일(과 선택한 폴더)은 선택해 둔다.
            selectedFileIds.retainAll(result.retryIds(ids).toSet())
            if (selectedFileIds.isEmpty() && selectedFolderPaths.isEmpty()) selectionMode = false
        }
    }

    // NAS 전송에서 돌아오면(취소·부분 실패) 남은 파일을 같은 폴더에서 다시 선택해 둔다.
    LaunchedEffect(state.inboxRetrySelectIds, files) {
        val retry = state.inboxRetrySelectIds
        if (retry.isEmpty()) return@LaunchedEffect
        val inFolder = files.filter { it.id in retry }
        if (inFolder.isNotEmpty()) {
            selectedFolderPaths.clear()
            selectedFileIds.clear()
            selectedFileIds.addAll(inFolder.map { it.id })
            selectionMode = true
            model.consumeInboxRetrySelection()
        } else if (state.initialInboxSubpath == null) {
            model.consumeInboxRetrySelection()
        }
    }

    LaunchedEffect(files, folderItems) {
        val availableFiles = files.mapTo(hashSetOf()) { it.id }
        selectedFileIds.removeAll { it !in availableFiles }
        val availableFolders = folderItems.filter { it.kind == PhoneHardEntryKind.DIRECTORY }.mapTo(hashSetOf()) { it.relativePath }
        selectedFolderPaths.removeAll { it !in availableFolders }
        if (selectedFileIds.isEmpty() && selectedFolderPaths.isEmpty() && files.isEmpty() && folderItems.isEmpty()) {
            selectionMode = false
        }
    }

    BackHandler(selectionMode && !phoneHardOpBusy) {
        selectedFileIds.clear()
        selectedFolderPaths.clear()
        selectionMode = false
    }
    BackHandler(enabled = !selectionMode && !currentSubdirectory.isNullOrBlank() && !phoneHardOpBusy) {
        val parent = currentSubdirectory?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }
        currentSubdirectory = parent
    }
    BackHandler(enabled = phoneHardOpBusy) {}

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.onSizeChanged { viewportSize = it },
            // The cover flow owns its own bottom inset (name caption), so the content slot must not add it again.
            contentWindowInsets = if (showsAutomaticOverflow) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
            containerColor = Color.Transparent,
            topBar = {
                if (!showsAutomaticOverflow) CenterAlignedTopAppBar(
                    expandedHeight = 52.dp,
                    title = {
                        // iOS principal title (headline); tapping it opens the current-folder search.
                        val phoneHardTitle = if (currentSubdirectory.isNullOrBlank()) "폰하드" else currentSubdirectory!!.substringAfterLast('/')
                        Text(
                            text = phoneHardTitle,
                            modifier = Modifier.clickable(onClickLabel = "현재 폴더 검색", role = Role.Button) { searchVisible = true }
                                .semantics { contentDescription = "$phoneHardTitle, 검색" },
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        CompositionLocalProvider(LocalViewConfiguration provides longPress500Configuration()) {
                            Box(
                                modifier = Modifier.size(48.dp).semantics(mergeDescendants = true) {
                                    contentDescription = if (selectionMode) "선택 종료" else if (currentSubdirectory.isNullOrBlank()) "NasFinder 첫 화면" else "상위 폴더"
                                    if (!selectionMode) {
                                        customActions = listOf(
                                            CustomAccessibilityAction("NasFinder 첫 화면") {
                                                model.show(Screen.Dashboard)
                                                true
                                            }
                                        )
                                    }
                                }.combinedClickable(
                                    onClick = {
                                        if (selectionMode) {
                                            selectedFileIds.clear()
                                            selectedFolderPaths.clear()
                                            selectionMode = false
                                        } else if (!currentSubdirectory.isNullOrBlank()) {
                                            val parent = currentSubdirectory?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }
                                            currentSubdirectory = parent
                                        } else {
                                            model.show(Screen.Dashboard)
                                        }
                                    },
                                    onLongClick = if (selectionMode) null else ({ model.show(Screen.Dashboard) }),
                                    onLongClickLabel = if (selectionMode) null else "NasFinder 첫 화면",
                                ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .12f), CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        if (selectionMode) Icons.Default.Close else Icons.Default.ChevronLeft,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        if (!selectionMode) {
                            BrowserMenu(
                                preferences = browserPreferences,
                                canCreateFolder = true,
                                canPaste = true,
                                onSelect = { selectionMode = true },
                                onCreateFolder = { showCreateFolderDialog = true },
                                onPaste = { fileImporter.launch(arrayOf("*/*")) },
                                pasteImports = true,
                                onGooglePhotos = if (state.googlePhotosVisible) model::requestGooglePhotosImport else null,
                                onRefresh = { model.refreshInbox() },
                                update = { newPrefs ->
                                    browserPreferences = newPrefs
                                    val targetLayout = when (newPrefs.layout) {
                                        BrowserLayout.LIST -> InboxLayout.DETAILS
                                        BrowserLayout.SMALL_GRID -> InboxLayout.THUMBNAILS
                                        BrowserLayout.LARGE_GRID -> InboxLayout.POSTERS
                                    }
                                    setLayout(targetLayout)
                                },
                            )
                        } else {
                            TextButton(
                                onClick = {
                                    selectedFileIds.clear()
                                    selectedFolderPaths.clear()
                                    selectionMode = false
                                },
                            ) {
                                Text("완료")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
                )
            },
            bottomBar = {
                if (selectionMode) {
                    val selectedVideos = files.filter { it.id in selectedFileIds }
                    val canTrailer = selectedVideos.isNotEmpty() && selectedVideos.all { it.isTrailerVideo } && selectedFolderPaths.isEmpty()
                    val canMusic = selectedVideos.isNotEmpty() && selectedVideos.all { it.isTrailerVideo } && selectedFolderPaths.isEmpty()
                    val allCount = displayedFolders.size + displayedFiles.size
                    val allAreSelected = allCount > 0 && selectedTargets.size == allCount
                    // iOS selectionBar order with PhoneHard's "NAS로 보내기" last; every handler and condition is the existing one.
                    BrowserSelectionBar(
                        count = selectedTargets.size,
                        allSelected = allAreSelected,
                        canToggleAll = allCount > 0,
                        onToggleAll = {
                            if (allAreSelected) {
                                selectedFileIds.clear()
                                selectedFolderPaths.clear()
                            } else {
                                selectedFolderPaths.clear()
                                selectedFolderPaths.addAll(displayedFolders.map { it.relativePath })
                                selectedFileIds.clear()
                                selectedFileIds.addAll(displayedFiles.map { it.id })
                            }
                        },
                        // 복사·이동은 스크롤 줄 밖의 고정 버튼으로 둔다. 누르면 NAS·네트워크 / 내 휴대폰 폴더 / 폰하드 안 폴더를 고른다.
                        primaryActions = listOf(
                            BrowserSelectionAction("복사", Icons.Default.ContentCopy, selectedTargets.isNotEmpty() && !phoneHardOpBusy) {
                                transferChoice = PhoneHardTransferRequest(selectedTargets, move = false)
                            },
                            BrowserSelectionAction("이동", Icons.Default.DriveFileMove, selectedTargets.isNotEmpty() && !phoneHardOpBusy) {
                                transferChoice = PhoneHardTransferRequest(selectedTargets, move = true)
                            },
                        ),
                        actions = listOf(
                            BrowserSelectionAction("삭제", Icons.Default.Delete, selectedTargets.isNotEmpty() && !phoneHardOpBusy, destructive = true) { deleteTargetsDialog = selectedTargets },
                            // 이 앱의 목록에서만 숨긴다(파일은 그대로, 잠금·암호화 아님). 일반 모드에서 비공개로 바꾸면 목록에서 바로 사라진다.
                            BrowserSelectionAction("비공개로 전환", Icons.Default.VisibilityOff, selectedTargets.isNotEmpty() && !phoneHardOpBusy) {
                                selectedTargets.forEach { model.setPhoneHardPrivate(it.relativePath, it.isDirectory, true) }
                                selectedFileIds.clear(); selectedFolderPaths.clear(); selectionMode = false
                            },
                            BrowserSelectionAction("공개로 전환", Icons.Default.Visibility, com.armsone.nasfinder.data.PrivacyMode.isActive && selectedTargets.isNotEmpty() && !phoneHardOpBusy) {
                                selectedTargets.forEach { model.setPhoneHardPrivate(it.relativePath, it.isDirectory, false) }
                                selectedFileIds.clear(); selectedFolderPaths.clear(); selectionMode = false
                            },
                            BrowserSelectionAction("작업대에 담기", Icons.Default.Inbox, selectedTargets.isNotEmpty()) {
                                val targets = displayedFiles.filter { it.id in selectedFileIds }
                                val folders = selectedFolderPaths.toList()
                                selectedFileIds.clear()
                                selectedFolderPaths.clear()
                                selectionMode = false
                                model.requestWorkbenchAddPhoneHard(targets, folders)
                            },
                            // PhoneHard files are app-private storage, not a durable external reference: always copied.
                            BrowserSelectionAction("컬렉션에 사본 추가", Icons.Default.VideoLibrary, displayedFiles.any { it.id in selectedFileIds && it.file.extension.lowercase() in setOf("mp4", "mov", "m4v", "mkv", "webm", "3gp") }) {
                                val targets = displayedFiles.filter { it.id in selectedFileIds && it.file.extension.lowercase() in setOf("mp4", "mov", "m4v", "mkv", "webm", "3gp") }
                                selectedFileIds.clear()
                                selectedFolderPaths.clear()
                                selectionMode = false
                                targets.forEach { target ->
                                    model.importVideoUriToCollection(android.net.Uri.fromFile(target.file), copyToStorage = true)
                                }
                            },
                            BrowserSelectionAction("예고편 보기", Icons.Default.Movie, canTrailer) {
                                val ids = selectedFileIds.toList()
                                selectedFileIds.clear()
                                selectedFolderPaths.clear()
                                selectionMode = false
                                model.startInboxTrailer(ids)
                            },
                            BrowserSelectionAction("뮤직비디오", Icons.Default.MusicNote, canMusic) {
                                val ids = selectedFileIds.toList()
                                selectedFileIds.clear()
                                selectedFolderPaths.clear()
                                selectionMode = false
                                model.startInboxMusicOverlay(ids, null)
                            },
                            BrowserSelectionAction("다운로드", Icons.Default.Download, selectedTargets.isNotEmpty()) {
                                localDownloadTargets = selectedTargets
                                showLocalDownloadDialog = true
                            },
                            BrowserSelectionAction("공유", Icons.Default.Share, selectedTargets.isNotEmpty()) {
                                val folders = selectedFolderPaths.toList()
                                val fileIds = selectedFileIds.toList()
                                selectedFolderPaths.clear()
                                selectedFileIds.clear()
                                selectionMode = false
                                model.sharePhoneHardTargets(folders, fileIds)
                            },
                            BrowserSelectionAction("NAS로 보내기", Icons.Default.CloudUpload, selectedFileIds.isNotEmpty() && selectedFolderPaths.isEmpty()) {
                                pendingSendMove = false
                                pendingSendIds = selectedFileIds.toList()
                            },
                        ),
                        note = when {
                            selectedTargets.isEmpty() -> "복사하거나 이동할 파일을 먼저 선택하세요."
                            !canTrailer -> "예고편 보기와 뮤직비디오는 영상만 선택했을 때 쓸 수 있습니다."
                            selectedFileIds.isEmpty() -> "NAS로 보내기는 파일만 보낼 수 있습니다."
                            else -> null
                        },
                    )
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (state.isLocalThumbnailRunning) {
                    val progress = state.localThumbnailProgress
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.94f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            val progressText = if (progress != null) "썸네일 생성 중… (${progress.first}/${progress.second})" else "썸네일 생성 준비 중…"
                            Text(progressText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { model.cancelLocalThumbnailGeneration() }) {
                                Text("중지", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                if (searchVisible || searchText.isNotEmpty()) {
                    OutlinedTextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        placeholder = { Text("현재 폴더 검색") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            IconButton(onClick = { searchText = ""; searchVisible = false }) {
                                Icon(Icons.Default.Cancel, "검색 닫기")
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                    )
                }

                // folderItems는 폴더와 파일을 모두 담고 있으므로 폴더만 센다(파일은 files로 센다). 안 그러면 파일이 두 번 세어진다.
                val totalCount = folderItems.count { it.kind == PhoneHardEntryKind.DIRECTORY } + files.size
                val dispCount = displayedFolders.size + displayedFiles.size
                val phoneHardSegments = currentSubdirectory.orEmpty().split('/').filter { it.isNotEmpty() }
                BrowserPathBar(
                    rootTitle = "폰하드",
                    segments = phoneHardSegments.mapIndexed { index, segment ->
                        segment to { if (!phoneHardOpBusy) currentSubdirectory = phoneHardSegments.take(index + 1).joinToString("/") }
                    },
                    onParent = if (currentSubdirectory.isNullOrBlank()) null else ({
                        val parent = currentSubdirectory?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }
                        currentSubdirectory = parent
                    }),
                    onRoot = if (phoneHardSegments.isEmpty()) null else ({ if (!phoneHardOpBusy) currentSubdirectory = null }),
                    countLabel = if (searchText.isBlank()) "${totalCount}개" else "${dispCount}/${totalCount}개",
                )

                when {
                    files.isEmpty() && folderItems.isEmpty() && !showsAutomaticOverflow -> {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                            item { PhoneHardServerExpandableHeader(webHardConnection) }
                            item {
                                EmptyState(
                                    icon = Icons.Default.Folder,
                                    title = if (currentSubdirectory.isNullOrBlank()) "폰하드가 비어 있습니다" else "빈 폴더",
                                    description = if (currentSubdirectory.isNullOrBlank()) "내가 저장하거나 다른 기기에서 보낸 파일이 이곳에 모입니다." else "이 폴더에는 표시할 파일이 없습니다.",
                                    modifier = Modifier.fillParentMaxWidth().fillParentMaxHeight(0.8f),
                                    action = {
                                        Button(onClick = { fileImporter.launch(arrayOf("*/*")) }) {
                                            Icon(Icons.AutoMirrored.Filled.NoteAdd, null)
                                            Spacer(Modifier.width(8.dp))
                                            Text("파일 가져오기")
                                        }
                                    },
                                )
                            }
                        }
                    }
                    displayedFolders.isEmpty() && displayedFiles.isEmpty() && searchText.isNotBlank() -> {
                        EmptyState(
                            icon = Icons.Default.Search,
                            title = "검색 결과가 없습니다",
                            description = "‘${searchText.trim()}’과 일치하는 항목을 찾지 못했습니다.",
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                    displayedLayout == InboxLayout.THUMBNAILS || displayedLayout == InboxLayout.POSTERS -> {
                        InboxGrid(
                            connection = webHardConnection,
                            directories = displayedFolders,
                            files = displayedFiles,
                            poster = displayedLayout == InboxLayout.POSTERS,
                            selectionMode = selectionMode,
                            selectedFileIds = selectedFileIds,
                            selectedFolderPaths = selectedFolderPaths,
                            onOpenFolder = { path -> if (!phoneHardOpBusy) currentSubdirectory = path },
                            onToggleFolderSelection = { path ->
                                if (path in selectedFolderPaths) selectedFolderPaths.remove(path)
                                else selectedFolderPaths.add(path)
                            },
                            onToggleFileSelection = { id ->
                                if (id in selectedFileIds) selectedFileIds.remove(id)
                                else selectedFileIds.add(id)
                            },
                            onActionItem = { item -> if (!phoneHardOpBusy) actionTarget = item },
                            onActivateFile = model::previewInboxFile,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                    displayedLayout == InboxLayout.OVERFLOW -> {
                        InboxOverflow(
                            directories = displayedFolders,
                            files = displayedFiles,
                            theme = state.theme,
                            usesDarkBackground = overflowUsesDarkBackground,
                            onBack = { model.show(Screen.Dashboard) },
                            onDashboard = { model.show(Screen.Dashboard) },
                            configuredLayout = configuredLayout,
                            onSelectLayout = ::setLayout,
                            onOpenFolder = { path -> if (!phoneHardOpBusy) currentSubdirectory = path },
                            onToggleBackground = { dark ->
                                overflowUsesDarkBackground = dark
                                inboxPreferences.edit().putBoolean("overflow_dark", dark).apply()
                            },
                            onActivate = model::previewInboxFile,
                            initialSelectedIndex = overflowSelectedIndex,
                            onSelectedIndexChange = { overflowSelectedIndex = it },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                    else -> {
                        LazyColumn(
                            Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        ) {
                            item { PhoneHardServerExpandableHeader(webHardConnection, horizontalPadding = 0.dp) }

                            // Directory items
                            items(displayedFolders, key = { "dir_${it.relativePath}" }) { dirItem ->
                                val selected = dirItem.relativePath in selectedFolderPaths
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Box(
                                        Modifier.weight(1f).combinedClickable(
                                            onClick = {
                                                if (selectionMode) {
                                                    if (selected) selectedFolderPaths.remove(dirItem.relativePath)
                                                    else selectedFolderPaths.add(dirItem.relativePath)
                                                } else if (!phoneHardOpBusy) {
                                                    currentSubdirectory = dirItem.relativePath
                                                }
                                            },
                                            onLongClick = {
                                                if (!selectionMode && !phoneHardOpBusy) {
                                                    actionTarget = PhoneHardActionItem.Folder(dirItem)
                                                }
                                            },
                                        ),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            if (porcelainPhoneHard) {
                                                // iOS RemoteFileListRow: 58pt card-surface thumbnail, corner 9, 1pt line, graphite folder glyph at 0.38×.
                                                Box(
                                                    Modifier.size(58.dp).clip(RoundedCornerShape(9.dp)).background(thumbnailSurfaceColor())
                                                        .border(1.dp, thumbnailBorderColor(), RoundedCornerShape(9.dp)),
                                                    contentAlignment = Alignment.Center,
                                                ) { Icon(Icons.Default.Folder, null, tint = folderColor(state.theme), modifier = Modifier.size(22.dp)) }
                                            } else Surface(
                                                modifier = Modifier.size(48.dp),
                                                shape = RoundedCornerShape(8.dp),
                                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                                                }
                                            }
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(dirItem.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (porcelainPhoneHard) FontWeight.Normal else FontWeight.SemiBold)
                                                Text("폴더", style = MaterialTheme.typography.labelSmall, color = fileMetaColor())
                                            }
                                            if (selectionMode) SelectionIndicator(selected)
                                        }
                                    }
                                }
                                HorizontalDivider()
                            }

                            // File items
                            items(displayedFiles, key = { it.id }) { file ->
                                val selected = file.id in selectedFileIds
                                val isAudio = file.isInboxAudio
                                val itemAudioKey = if (isAudio) model.inlineInboxAudioKey(file) else null
                                val activeInlineAudio = state.inlineAudioPlayback?.takeIf { itemAudioKey != null && it.key == itemAudioKey }
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Box(
                                        Modifier.weight(1f).combinedClickable(
                                            onClick = {
                                                if (selectionMode) {
                                                    if (selected) selectedFileIds.remove(file.id)
                                                    else selectedFileIds.add(file.id)
                                                } else {
                                                    model.previewInboxFile(file)
                                                }
                                            },
                                            onLongClick = {
                                                if (!selectionMode && !phoneHardOpBusy) {
                                                    actionTarget = PhoneHardActionItem.File(file)
                                                }
                                            },
                                        ),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            InboxLeadingPreview(
                                                file = file,
                                                inlineAudioState = activeInlineAudio,
                                                onToggleInlineAudio = if (isAudio && !selectionMode && !phoneHardOpBusy) {
                                                    { model.toggleInlineInboxAudio(file) }
                                                } else null,
                                            )
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(file.originalFilename, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                CompositionLocalProvider(LocalContentColor provides fileMetaColor()) {
                                                    ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                                                        val inlineTimeLabel = activeInlineAudio?.let { audioState ->
                                                            when {
                                                                audioState.isPreparing -> "준비 중…"
                                                                audioState.durationMillis > 0L -> {
                                                                    val pos = formatPlaybackDuration(audioState.positionMillis) ?: "0:00"
                                                                    val dur = formatPlaybackDuration(audioState.durationMillis) ?: "0:00"
                                                                    "$pos / $dur"
                                                                }
                                                                audioState.isPlaying -> "재생 중"
                                                                else -> null
                                                            }
                                                        }
                                                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                                            Text(formatBytes(file.byteCount))
                                                            if (inlineTimeLabel != null) {
                                                                Text(inlineTimeLabel, color = MaterialTheme.colorScheme.primary)
                                                            } else {
                                                                formatPlaybackDuration(file.durationMillis)?.let { Text(it) }
                                                            }
                                                            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date.from(file.importedAt)))
                                                        }
                                                    }
                                                }
                                            }
                                            if (selectionMode) SelectionIndicator(selected)
                                        }
                                    }
                                    if (!selectionMode) {
                                        IconButton(
                                            onClick = { model.shareInboxFile(file) },
                                            modifier = Modifier.size(38.dp),
                                        ) { Icon(Icons.Default.Share, "${file.originalFilename} 공유") }
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }

        actionTarget?.let { item ->
            val target: PhoneHardTarget
            val title: String
            val relativePath: String
            val isFolder: Boolean
            val isVideo: Boolean
            val sizeText: String
            val dateText: String
            val durationText: String?

            when (item) {
                is PhoneHardActionItem.Folder -> {
                    val f = item.folder
                    target = PhoneHardTarget(id = null, relativePath = f.relativePath, isDirectory = true)
                    title = f.name
                    relativePath = f.relativePath
                    isFolder = true
                    isVideo = false
                    sizeText = "폴더"
                    dateText = "알 수 없음"
                    durationText = null
                }
                is PhoneHardActionItem.File -> {
                    val f = item.file
                    target = PhoneHardTarget(id = f.id, relativePath = f.relativePath, isDirectory = false)
                    title = f.originalFilename
                    relativePath = f.relativePath
                    isFolder = false
                    isVideo = f.isTrailerVideo
                    sizeText = formatBytes(f.byteCount)
                    dateText = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date.from(f.importedAt))
                    durationText = formatPlaybackDuration(f.durationMillis)
                }
            }

            val isSelected = when (item) {
                is PhoneHardActionItem.Folder -> item.folder.relativePath in selectedFolderPaths
                is PhoneHardActionItem.File -> item.file.id in selectedFileIds
            }

            val row1 = listOf(
                BaselineAction(
                    id = "select",
                    title = if (isSelected) "선택 해제" else "선택",
                    icon = Icons.Default.CheckCircle,
                    enabled = true,
                    onClick = {
                        actionTarget = null
                        selectionMode = true
                        when (item) {
                            is PhoneHardActionItem.Folder -> {
                                if (isSelected) selectedFolderPaths.remove(item.folder.relativePath)
                                else selectedFolderPaths.add(item.folder.relativePath)
                            }
                            is PhoneHardActionItem.File -> {
                                if (isSelected) selectedFileIds.remove(item.file.id)
                                else selectedFileIds.add(item.file.id)
                            }
                        }
                    },
                ),
                BaselineAction(
                    id = "copy",
                    title = "복사",
                    icon = Icons.Default.ContentCopy,
                    enabled = true,
                    onClick = {
                        actionTarget = null
                        transferChoice = PhoneHardTransferRequest(listOf(target), move = false)
                    },
                ),
                BaselineAction(
                    id = "move",
                    title = "이동",
                    icon = Icons.Default.DriveFileMove,
                    enabled = true,
                    onClick = {
                        actionTarget = null
                        transferChoice = PhoneHardTransferRequest(listOf(target), move = true)
                    },
                ),
                BaselineAction(
                    id = "rename",
                    title = "수정",
                    icon = Icons.Default.Edit,
                    enabled = true,
                    onClick = {
                        actionTarget = null
                        renameTarget = target
                        renameInputText = title
                    },
                ),
                BaselineAction(
                    id = "delete",
                    title = "삭제",
                    icon = Icons.Default.Delete,
                    enabled = true,
                    isDestructive = true,
                    onClick = {
                        actionTarget = null
                        deleteTargetsDialog = listOf(target)
                    },
                ),
            )

            val isFavorite = model.isPhoneHardFavorite(relativePath)
            val isThumbnailActive = state.isLocalThumbnailRunning
            val isMedia = isVideo || title.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "pdf")

            val row2 = listOf(
                BaselineAction(
                    id = "favorite",
                    title = if (isFavorite) "별 해제" else "즐겨찾기",
                    icon = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    enabled = true,
                    onClick = {
                        actionTarget = null
                        model.togglePhoneHardFavorite(
                            relativePath = relativePath,
                            name = title,
                            isDirectory = isFolder,
                            id = if (!isFolder) (item as PhoneHardActionItem.File).file.id.toString() else null,
                            size = if (!isFolder) (item as PhoneHardActionItem.File).file.byteCount else null,
                            modifiedAt = if (!isFolder) (item as PhoneHardActionItem.File).file.importedAt else null,
                            mimeType = if (!isFolder) (item as PhoneHardActionItem.File).file.mimeType else null,
                        )
                    },
                ),
                BaselineAction(
                    id = "trailer",
                    title = "예고편 보기",
                    icon = Icons.Default.Movie,
                    enabled = isFolder || isVideo,
                    disabledReason = if (!isFolder && !isVideo) "예고편 보기와 뮤직비디오는 영상과 폴더에서 쓸 수 있습니다." else null,
                    onClick = {
                        actionTarget = null
                        if (isFolder) {
                            if (model.hasInboxSubfolders(relativePath)) {
                                folderTrailerTarget = relativePath
                            } else {
                                model.startInboxFolderTrailer(relativePath, includeSubfolders = false)
                            }
                        } else {
                            val f = (item as PhoneHardActionItem.File).file
                            model.startInboxTrailer(listOf(f.id))
                        }
                    },
                ),
                BaselineAction(
                    id = "music",
                    title = "뮤직비디오",
                    icon = Icons.Default.MusicNote,
                    enabled = isFolder || isVideo,
                    disabledReason = if (!isFolder && !isVideo) "예고편 보기와 뮤직비디오는 영상과 폴더에서 쓸 수 있습니다." else null,
                    onClick = {
                        actionTarget = null
                        if (isFolder) {
                            if (model.hasInboxSubfolders(relativePath)) {
                                folderMusicTarget = relativePath
                            } else {
                                model.startInboxFolderMusicOverlay(relativePath, includeSubfolders = false)
                            }
                        } else {
                            val f = (item as PhoneHardActionItem.File).file
                            model.startInboxMusicOverlay(listOf(f.id), null)
                        }
                    },
                ),
                BaselineAction(
                    id = "download",
                    title = "다운로드",
                    icon = Icons.Default.Download,
                    enabled = true,
                    onClick = {
                        actionTarget = null
                        localDownloadTargets = listOf(target)
                        showLocalDownloadDialog = true
                    },
                ),
                BaselineAction(
                    id = "share",
                    title = "공유",
                    icon = Icons.Default.Share,
                    enabled = true,
                    onClick = {
                        actionTarget = null
                        if (isFolder) {
                            model.shareInboxFolder(relativePath)
                        } else {
                            val f = (item as PhoneHardActionItem.File).file
                            model.shareInboxFile(f)
                        }
                    },
                ),
                BaselineAction(
                    id = "thumbnail",
                    title = if (isThumbnailActive) "생성 중지" else "썸네일 만들기",
                    icon = if (isThumbnailActive) Icons.Default.Stop else Icons.Default.Image,
                    enabled = isFolder || isMedia,
                    disabledReason = if (!isFolder && !isMedia) "사진 또는 영상 파일만 썸네일을 만들 수 있습니다." else null,
                    isDestructive = isThumbnailActive,
                    onClick = {
                        actionTarget = null
                        if (isThumbnailActive) {
                            model.cancelLocalThumbnailGeneration()
                        } else {
                            model.startLocalThumbnailGeneration(target) { count ->
                                actionToastMessage = "${count}개 썸네일을 만들었습니다."
                            }
                        }
                    },
                ),
            )

            val phoneHardMime = (item as? PhoneHardActionItem.File)?.file?.mimeType.orEmpty()
            AppleBaselineActionSheet(
                title = title,
                isFolder = isFolder,
                row1 = row1,
                row2 = row2,
                infoMedia = when {
                    isFolder -> "폴더"; phoneHardMime.startsWith("image/") -> "사진"; isVideo -> "비디오"; phoneHardMime.startsWith("audio/") -> "오디오"; else -> "파일"
                },
                infoFeature = if (isFolder) "하위 항목을 담는 폴더" else listOfNotNull(
                    title.substringAfterLast('.', "").uppercase().ifEmpty { null },
                    phoneHardMime.ifBlank { "파일" },
                    durationText?.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                infoDate = dateText,
                infoSize = sizeText,
                onDismiss = { actionTarget = null },
                onShowDisabledReason = { reason ->
                    actionTarget = null
                    actionToastMessage = reason
                },
            )
        }
        renameTarget?.let { target ->
            NameInputDialog(
                title = "이름 변경",
                value = renameInputText,
                confirmLabel = "변경",
                onValueChange = { renameInputText = it },
                onDismiss = { renameTarget = null },
                onConfirm = {
                    val t = target
                    val newName = renameInputText.trim()
                    renameTarget = null
                    if (newName.isNotEmpty()) {
                        phoneHardOpBusy = true
                        phoneHardOpStatus = "이름을 변경하는 중입니다…"
                        model.renamePhoneHardItem(t.relativePath, newName) { success, errMsg ->
                            folderItems = model.loadPhoneHardFolder(currentSubdirectory)
                            if (!success && !errMsg.isNullOrBlank()) {
                                model.showInboxErrorMessage(errMsg)
                            }
                            phoneHardOpBusy = false
                            phoneHardOpStatus = null
                        }
                    }
                },
            )
        }

        folderTrailerTarget?.let { folderRelPath ->
            AlertDialog(
                onDismissRequest = { folderTrailerTarget = null },
                title = { Text("영상 만들기") },
                text = { Text("하위 폴더에 있는 미디어도 함께 모을까요?") },
                confirmButton = {
                    TextButton(onClick = {
                        val p = folderRelPath
                        folderTrailerTarget = null
                        model.startInboxFolderTrailer(p, includeSubfolders = true)
                    }) { Text("하위 폴더 포함") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        val p = folderRelPath
                        folderTrailerTarget = null
                        model.startInboxFolderTrailer(p, includeSubfolders = false)
                    }) { Text("현재 폴더만") }
                },
            )
        }

        folderMusicTarget?.let { folderRelPath ->
            AlertDialog(
                onDismissRequest = { folderMusicTarget = null },
                title = { Text("뮤직비디오") },
                text = { Text("하위 폴더에 있는 미디어도 함께 모을까요?") },
                confirmButton = {
                    TextButton(onClick = {
                        val p = folderRelPath
                        folderMusicTarget = null
                        model.startInboxFolderMusicOverlay(p, includeSubfolders = true)
                    }) { Text("하위 폴더 포함") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        val p = folderRelPath
                        folderMusicTarget = null
                        model.startInboxFolderMusicOverlay(p, includeSubfolders = false)
                    }) { Text("현재 폴더만") }
                },
            )
        }

        if (showLocalDownloadDialog) {
            DownloadDestinationDialog(
                itemCount = localDownloadTargets?.size ?: 1,
                isLocal = true,
                onDismiss = {
                    showLocalDownloadDialog = false
                    localDownloadTargets = null
                },
                onSelectPhoneHard = {
                    showLocalDownloadDialog = false
                    showPhoneHardPickerForLocalDownload = true
                },
                onSelectExternalSaf = {
                    showLocalDownloadDialog = false
                    localExternalTreeLauncher.launch(null)
                },
            )
        }

        if (showPhoneHardPickerForLocalDownload && localDownloadTargets != null) {
            SelectPhoneHardDestinationDialog(
                title = "폰하드 복사 대상 폴더 선택",
                model = model,
                targets = localDownloadTargets!!,
                onDismiss = {
                    showPhoneHardPickerForLocalDownload = false
                    localDownloadTargets = null
                },
                onSelectDestination = { dest ->
                    val targets = localDownloadTargets!!
                    showPhoneHardPickerForLocalDownload = false
                    localDownloadTargets = null
                    phoneHardOpBusy = true
                    phoneHardOpStatus = "복사하는 중입니다…"
                    model.copyPhoneHardTargets(targets, dest) { result ->
                        folderItems = model.loadPhoneHardFolder(currentSubdirectory)
                        phoneHardOpBusy = false
                        phoneHardOpStatus = null
                        if (result.failed.isNotEmpty()) {
                            model.showInboxErrorMessage(result.failed.first().second)
                        } else {
                            actionToastMessage = "${targets.size}개 항목을 복사했습니다."
                        }
                    }
                },
            )
        }

        if (showCreateFolderDialog) {
            CreateFolderDialog(
                parentPath = currentSubdirectory,
                onDismiss = { showCreateFolderDialog = false },
                onConfirm = { folderName ->
                    showCreateFolderDialog = false
                    phoneHardOpBusy = true
                    phoneHardOpStatus = "폴더를 만드는 중입니다…"
                    model.createPhoneHardFolder(folderName, currentSubdirectory) { success, errorMsg ->
                        if (success) {
                            folderItems = model.loadPhoneHardFolder(currentSubdirectory)
                        } else if (!errorMsg.isNullOrBlank()) {
                            model.showInboxErrorMessage(errorMsg)
                        }
                        phoneHardOpBusy = false
                        phoneHardOpStatus = null
                    }
                },
            )
        }

        moveTargetsDialog?.let { targets ->
            SelectPhoneHardDestinationDialog(
                title = "폴더/파일 이동 대상 선택",
                model = model,
                targets = targets,
                onDismiss = { moveTargetsDialog = null },
                onSelectDestination = { dest ->
                    moveTargetsDialog = null
                    phoneHardOpBusy = true
                    phoneHardOpStatus = "이동하는 중입니다…"
                    model.movePhoneHardTargets(targets, dest) { result ->
                        folderItems = model.loadPhoneHardFolder(currentSubdirectory)
                        if (result.failed.isNotEmpty()) {
                            val failedPaths = result.failed.map { it.first.relativePath }.toSet()
                            selectedFolderPaths.retainAll(failedPaths)
                            val failedIds = result.failed.mapNotNull { it.first.id }.toSet()
                            selectedFileIds.retainAll(failedIds)
                            if (selectedFolderPaths.isEmpty() && selectedFileIds.isEmpty()) selectionMode = false

                            val errorDetail = result.failed.first().second
                            val message = when {
                                result.succeeded.isEmpty() && result.failed.size == 1 -> errorDetail
                                result.succeeded.isEmpty() -> "${result.failed.size}개 항목을 이동하지 못했습니다: $errorDetail"
                                else -> "${result.succeeded.size}개 항목을 이동했지만 ${result.failed.size}개는 이동하지 못했습니다: $errorDetail"
                            }
                            model.showInboxErrorMessage(message)
                        } else {
                            if (selectionMode) {
                                selectedFileIds.clear()
                                selectedFolderPaths.clear()
                                selectionMode = false
                            }
                        }
                        phoneHardOpBusy = false
                        phoneHardOpStatus = null
                    }
                },
            )
        }

        copyTargetsDialog?.let { targets ->
            SelectPhoneHardDestinationDialog(
                title = "폴더/파일 복사 대상 선택",
                model = model,
                targets = targets,
                onDismiss = { copyTargetsDialog = null },
                onSelectDestination = { dest ->
                    copyTargetsDialog = null
                    phoneHardOpBusy = true
                    phoneHardOpStatus = "복사하는 중입니다…"
                    model.copyPhoneHardTargets(targets, dest) { result ->
                        folderItems = model.loadPhoneHardFolder(currentSubdirectory)
                        if (result.failed.isNotEmpty()) {
                            val failedPaths = result.failed.map { it.first.relativePath }.toSet()
                            selectedFolderPaths.retainAll(failedPaths)
                            val failedIds = result.failed.mapNotNull { it.first.id }.toSet()
                            selectedFileIds.retainAll(failedIds)
                            if (selectedFolderPaths.isEmpty() && selectedFileIds.isEmpty()) selectionMode = false

                            val errorDetail = result.failed.first().second
                            val message = when {
                                result.succeeded.isEmpty() && result.failed.size == 1 -> errorDetail
                                result.succeeded.isEmpty() -> "${result.failed.size}개 항목을 복사하지 못했습니다: $errorDetail"
                                else -> "${result.succeeded.size}개 항목을 복사했지만 ${result.failed.size}개는 복사하지 못했습니다: $errorDetail"
                            }
                            model.showInboxErrorMessage(message)
                        } else {
                            if (selectionMode) {
                                selectedFileIds.clear()
                                selectedFolderPaths.clear()
                                selectionMode = false
                            }
                        }
                        phoneHardOpBusy = false
                        phoneHardOpStatus = null
                    }
                },
            )
        }

        deleteTargetsDialog?.let { targets ->
            AlertDialog(
                onDismissRequest = { deleteTargetsDialog = null },
                title = {
                    Text(
                        if (targets.size == 1) {
                            if (targets[0].isDirectory) "폴더를 삭제할까요?" else "파일을 삭제할까요?"
                        } else {
                            "선택한 ${targets.size}개 항목을 삭제할까요?"
                        }
                    )
                },
                text = {
                    Text(
                        if (targets.size == 1) {
                            if (targets[0].isDirectory) "'${targets[0].relativePath.substringAfterLast('/')}' 폴더와 내부의 모든 내용이 기기에서 삭제됩니다. 되돌릴 수 없습니다."
                            else "'${targets[0].relativePath.substringAfterLast('/')}' 파일이 기기에서 삭제됩니다. 되돌릴 수 없습니다."
                        } else {
                            "선택한 ${targets.size}개 항목이 기기에서 영구히 삭제됩니다. 되돌릴 수 없습니다."
                        }
                    )
                },
                dismissButton = { TextButton(onClick = { deleteTargetsDialog = null }) { Text("취소") } },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val toDelete = targets
                            deleteTargetsDialog = null
                            phoneHardOpBusy = true
                            phoneHardOpStatus = "삭제하는 중입니다…"
                            model.deletePhoneHardTargets(toDelete) { result ->
                                folderItems = model.loadPhoneHardFolder(currentSubdirectory)
                                if (selectionMode) {
                                    val failedPaths = result.failed.map { it.first.relativePath }.toSet()
                                    selectedFolderPaths.retainAll(failedPaths)
                                    val failedIds = result.failed.mapNotNull { it.first.id }.toSet()
                                    selectedFileIds.retainAll(failedIds)
                                    if (selectedFolderPaths.isEmpty() && selectedFileIds.isEmpty()) selectionMode = false
                                }
                                if (result.failed.isNotEmpty()) {
                                    val errorDetail = result.failed.first().second
                                    model.showInboxErrorMessage(errorDetail)
                                }
                                phoneHardOpBusy = false
                                phoneHardOpStatus = null
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("삭제") }
                },
            )
        }

        transferChoice?.let { request ->
            val fileTargets = request.targets.filter { !it.isDirectory }
            PhoneHardTransferDestinationDialog(
                move = request.move,
                fileCount = fileTargets.size,
                folderCount = request.targets.size - fileTargets.size,
                hasConnections = state.connections.isNotEmpty(),
                onDismiss = { transferChoice = null },
                onSelectNas = {
                    transferChoice = null
                    pendingSendMove = request.move
                    pendingSendIds = fileTargets.mapNotNull { it.id }
                },
                onSelectDeviceFolder = {
                    transferChoice = null
                    model.stageDeviceTransfer(fileTargets.mapNotNull { it.id }, request.move)
                    deviceTransferLauncher.launch(null)
                },
                onSelectPhoneHardFolder = {
                    transferChoice = null
                    if (request.move) moveTargetsDialog = request.targets else copyTargetsDialog = request.targets
                },
            )
        }

        state.phoneHardTransferProgress?.let { progress ->
            AlertDialog(
                onDismissRequest = {},
                title = { Text(if (progress.move) "휴대폰 폴더로 이동하는 중" else "휴대폰 폴더로 복사하는 중") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${progress.current}/${progress.total}번째 파일 · 원래 이름으로 한 개씩 저장합니다.", style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(progress = { ((progress.current - 1).toFloat() / progress.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        if (progress.move) Text("대상에 저장하고 크기를 확인한 파일만 폰하드에서 지웁니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = { TextButton(onClick = model::cancelPhoneHardTransfer) { Text("중단") } },
            )
        }

        pendingSendIds?.let { ids ->
            val selectedFiles = ids.mapNotNull { id -> files.firstOrNull { it.id == id } }
            val connections = state.connections.sortedBy { if (it.id == state.preferredId) 0 else 1 }
            AlertDialog(
                onDismissRequest = {
                    pendingSendIds = null
                    pendingSendMove = false
                },
                title = { if (pendingSendMove) Text("NAS로 이동") else Text("NAS로 보내기") },
                text = {
                    if (connections.isEmpty()) {
                        Text("먼저 NAS 또는 원격 서버 연결을 저장해 주세요.")
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${selectedFiles.size}개 파일을 보낼 연결을 선택하세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                if (pendingSendMove) "다음 화면에서 폴더를 고르면 파일을 한 개씩 올리고, NAS 폴더에서 이름·크기를 확인한 파일만 폰하드에서 지웁니다. ZIP으로 묶지 않습니다."
                                else "다음 화면에서 폴더를 고르면 파일을 원래 이름으로 한 개씩 올립니다. 폰하드 파일은 그대로 남습니다.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            connections.forEach { connection ->
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        val move = pendingSendMove
                                        pendingSendIds = null
                                        pendingSendMove = false
                                        selectedFileIds.clear()
                                        selectedFolderPaths.clear()
                                        selectionMode = false
                                        model.beginInboxUpload(ids, connection, move)
                                    }.padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(connectionKindIcon(connection.kind), null, design = if (connection.kind == ConnectionKind.SYNOLOGY) PackGlyph.NAS else null, tint = serviceColor(connection.kind.name, state.theme))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(connection.name)
                                        Text(connection.endpoint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (connection.id == state.preferredId) {
                                        Text("기본", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        pendingSendIds = null
                        pendingSendMove = false
                    }) { Text("취소") }
                },
                confirmButton = {
                    if (connections.isEmpty()) {
                        TextButton(onClick = { pendingSendIds = null; pendingSendMove = false; model.show(Screen.AddConnection()) }) { Text("연결 추가") }
                    }
                },
            )
        }

        state.inboxErrorMessage?.let { message ->
            AlertDialog(
                onDismissRequest = model::dismissInboxError,
                title = { Text("폰하드 오류") },
                text = { Text(message) },
                confirmButton = { TextButton(onClick = model::dismissInboxError) { Text("확인") } },
            )
        }

        actionToastMessage?.let { msg ->
            LaunchedEffect(msg) {
                delay(2200)
                actionToastMessage = null
            }
            Box(
                modifier = Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 72.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shadowElevation = 4.dp,
                ) {
                    Text(
                        text = msg,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    } // Scaffold Box

    if (phoneHardOpBusy) {
        Surface(
            modifier = Modifier.fillMaxSize().clickable(enabled = true, onClick = {}),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.padding(24.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        Text(phoneHardOpStatus ?: "처리하는 중입니다…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneHardServerExpandableHeader(
    connection: WebHardConnectionState,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val porcelain = usesUnifiedLayout(LocalNasFinderTheme.current)
    val running = connection.server != null
    if (porcelain) {
        // iOS WebHardConnectionPanel: padding 14, corner 16, thumbnailSurface fill, thumbnailBorder×.72 1pt, shadow .06 r8 y4;
        // header HStack(9) { 28pt themed symbol (15pt art), "서버 열기" headline semibold, green running dot, chevron caption }.
        val shape = RoundedCornerShape(16.dp)
        Column(
            modifier
                .fillMaxWidth()
                .padding(start = horizontalPadding, top = 6.dp, end = horizontalPadding, bottom = 8.dp)
                .shadow(8.dp, shape, ambientColor = Color.Black.copy(alpha = .06f), spotColor = Color.Black.copy(alpha = .06f))
                .background(thumbnailSurfaceColor(), shape)
                .border(1.dp, thumbnailBorderColor().copy(alpha = .72f), shape)
                .clip(shape)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = if (expanded) "서버 열기 설정 접기" else "서버 열기 설정 펼치기") { expanded = !expanded }
                    .semantics {
                        contentDescription = if (running) "서버 열기, 웹하드 서버 실행 중 · ${connection.port}" else "서버 열기, 다른 기기에서 웹 브라우저로 폰하드에 접속합니다."
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    PackIcon(PackGlyph.PHONEHARD, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text("서버 열기", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                if (!expanded && running) Box(Modifier.size(8.dp).background(themePalette().accent, CircleShape))
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (expanded) {
                PhoneHardConnectionPanel(connection, Modifier.fillMaxWidth())
            } else if (running) {
                Text(
                    "웹하드 서버 실행 중 · ${connection.port}",
                    fontSize = 13.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        return
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = modifier
            .fillMaxWidth()
            .padding(start = horizontalPadding, top = 6.dp, end = horizontalPadding, bottom = 8.dp)
            .clickable { expanded = !expanded },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("서버 열기", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (connection.server != null) "웹하드 서버 실행 중 · ${connection.port}"
                        else "다른 기기에서 웹 브라우저로 폰하드에 접속합니다.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "접기" else "펼치기",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                PhoneHardConnectionPanel(connection, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun InboxGrid(
    connection: WebHardConnectionState,
    directories: List<PhoneHardItem> = emptyList(),
    files: List<InboxDisplayItem>,
    poster: Boolean,
    selectionMode: Boolean,
    selectedFileIds: List<java.util.UUID>,
    selectedFolderPaths: List<String>,
    onOpenFolder: (String) -> Unit = {},
    onToggleFolderSelection: (String) -> Unit = {},
    onToggleFileSelection: (java.util.UUID) -> Unit = {},
    onActionItem: (PhoneHardActionItem) -> Unit = {},
    onActivateFile: (InboxDisplayItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val porcelain = usesUnifiedLayout(LocalNasFinderTheme.current)
    // Porcelain: the same iOS thumbnailGrid as the NAS browser (porcelainColumns, padding 10/16, row spacing 12/20).
    val porcelainCells = if (porcelain) porcelainGridCells(poster, accessibility = LocalConfiguration.current.fontScale >= 1.3f) else null
    LazyVerticalGrid(
        columns = porcelainCells?.first ?: GridCells.Adaptive(if (poster) 164.dp else 104.dp),
        modifier = modifier,
        contentPadding = PaddingValues(if (porcelain) (if (poster) 16.dp else 10.dp) else 16.dp),
        verticalArrangement = Arrangement.spacedBy(if (porcelain) (if (poster) 20.dp else 12.dp) else if (poster) 20.dp else 14.dp),
        horizontalArrangement = Arrangement.spacedBy(porcelainCells?.second ?: 14.dp, Alignment.CenterHorizontally),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            PhoneHardServerExpandableHeader(connection, horizontalPadding = 0.dp)
        }
        items(directories, key = { "dir_${it.relativePath}" }) { dirItem ->
            val isSelected = dirItem.relativePath in selectedFolderPaths
            InboxFolderGridTile(
                folder = dirItem,
                poster = poster,
                selected = isSelected,
                selectionMode = selectionMode,
                onActivate = {
                    if (selectionMode) onToggleFolderSelection(dirItem.relativePath)
                    else onOpenFolder(dirItem.relativePath)
                },
                onAction = { onActionItem(PhoneHardActionItem.Folder(dirItem)) },
            )
        }
        items(files, key = { it.id }) { file ->
            val isSelected = file.id in selectedFileIds
            InboxGridTile(
                file = file,
                poster = poster,
                selected = isSelected,
                selectionMode = selectionMode,
                onActivate = {
                    if (selectionMode) onToggleFileSelection(file.id)
                    else onActivateFile(file)
                },
                onAction = { onActionItem(PhoneHardActionItem.File(file)) },
            )
        }
    }
}

@Composable
private fun InboxFolderGridTile(
    folder: PhoneHardItem,
    poster: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    onActivate: () -> Unit,
    onAction: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().combinedClickable(
            onClick = onActivate,
            onLongClick = onAction,
            onLongClickLabel = "작업 보기",
        ),
        verticalArrangement = Arrangement.spacedBy(if (poster) 9.dp else 6.dp),
    ) {
        Box {
            val porcelain = usesUnifiedLayout(LocalNasFinderTheme.current)
            Surface(
                Modifier.fillMaxWidth().aspectRatio(1f),
                shape = RoundedCornerShape(if (porcelain && !poster) 8.dp else if (poster) 15.dp else 11.dp),
                color = if (porcelain) thumbnailSurfaceColor() else if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                border = if (porcelain) (if (selected) BorderStroke(2.dp, themePalette().accent) else BorderStroke(.5.dp, thumbnailBorderColor())) else if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
            ) {
                BoxWithConstraints(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Folder, null,
                        tint = if (porcelain) folderColor(LocalNasFinderTheme.current) else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(if (porcelain) maxWidth * .38f else if (poster) 56.dp else 36.dp),
                    )
                }
            }
            if (selectionMode) SelectionIndicator(selected, Modifier.align(Alignment.TopEnd).padding(if (poster) 8.dp else 4.dp))
        }
        Text(
            folder.name,
            style = if (poster) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text("폴더", style = MaterialTheme.typography.labelSmall, color = fileMetaColor())
    }
}

@Composable
private fun InboxGridTile(
    file: InboxDisplayItem,
    poster: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    onActivate: () -> Unit,
    onAction: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().combinedClickable(
            onClick = onActivate,
            onLongClick = onAction,
            onLongClickLabel = "작업 보기",
        ),
        verticalArrangement = Arrangement.spacedBy(if (poster) 9.dp else 6.dp),
    ) {
        Box {
            val porcelain = usesUnifiedLayout(LocalNasFinderTheme.current)
            val corner = if (porcelain && !poster) 8.dp else if (poster) 15.dp else 11.dp
            Surface(
                Modifier.fillMaxWidth().aspectRatio(1f),
                shape = RoundedCornerShape(corner),
                color = if (porcelain) thumbnailSurfaceColor() else if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = .88f),
                border = if (porcelain) (if (selected) BorderStroke(2.dp, themePalette().accent) else null) else BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            ) {
                InboxArtwork(
                    file = file,
                    requestedPixels = if (poster) 420 else 240,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = corner,
                    cropCover = porcelain,
                )
            }
            // Approved 02-files: real duration (existing metadata only) as a small dark bottom-trailing badge.
            if (porcelain) formatPlaybackDuration(file.durationMillis)?.let { label ->
                Text(
                    label,
                    Modifier.align(Alignment.BottomEnd).padding(5.dp)
                        .background(Color.Black.copy(alpha = .62f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    style = com.armsone.nasfinder.ui.theme.PorcelainType.Badge,
                    color = Color.White,
                )
            }
            if (selectionMode) SelectionIndicator(selected, Modifier.align(Alignment.TopEnd).padding(if (poster) 8.dp else 4.dp))
        }
        Text(
            file.originalFilename,
            style = if (poster) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val durationText = formatPlaybackDuration(file.durationMillis)
        val sizeText = if (durationText != null) "${formatBytes(file.byteCount)} · $durationText" else formatBytes(file.byteCount)
        Text(sizeText, style = MaterialTheme.typography.labelSmall, color = fileMetaColor())
        if (poster) {
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date.from(file.importedAt)),
                style = MaterialTheme.typography.labelMedium,
                color = fileMetaColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun InboxOverflow(
    directories: List<PhoneHardItem> = emptyList(),
    files: List<InboxDisplayItem>,
    theme: AppTheme,
    usesDarkBackground: Boolean,
    onBack: () -> Unit,
    onDashboard: (() -> Unit)? = null,
    configuredLayout: InboxLayout = InboxLayout.POSTERS,
    onSelectLayout: (InboxLayout) -> Unit = {},
    onToggleBackground: (Boolean) -> Unit,
    onActivate: (InboxDisplayItem) -> Unit,
    onOpenFolder: (String) -> Unit = {},
    initialSelectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val thumbnails = remember(files) { mutableStateMapOf<String, android.graphics.Bitmap>() }
    val requested = remember(files) { mutableSetOf<String>() }
    val items = remember(directories, files) {
        directories.map { dir ->
            RemoteFileItem(
                id = "dir:${dir.relativePath}",
                name = dir.name,
                path = dir.relativePath,
                isDirectory = true,
                size = 0L,
                modifiedAt = dir.modifiedAt,
                mimeType = null,
            )
        } + files.map { file ->
            RemoteFileItem(
                id = file.id.toString(),
                name = file.originalFilename,
                path = file.file.path,
                isDirectory = false,
                size = file.byteCount,
                modifiedAt = file.importedAt,
                mimeType = file.mimeType,
            )
        }
    }
    val filesByID = remember(files) { files.associateBy { it.id.toString() } }
    val inboxViewModes = remember(configuredLayout) {
        listOf(
            CoverFlowViewModeOption(
                title = InboxLayout.DETAILS.title,
                selected = configuredLayout == InboxLayout.DETAILS,
                icon = inboxLayoutIcon(InboxLayout.DETAILS),
                onSelect = { onSelectLayout(InboxLayout.DETAILS) },
            ),
            CoverFlowViewModeOption(
                title = InboxLayout.THUMBNAILS.title,
                selected = configuredLayout == InboxLayout.THUMBNAILS,
                icon = inboxLayoutIcon(InboxLayout.THUMBNAILS),
                onSelect = { onSelectLayout(InboxLayout.THUMBNAILS) },
            ),
            CoverFlowViewModeOption(
                title = InboxLayout.POSTERS.title,
                selected = configuredLayout == InboxLayout.POSTERS,
                icon = inboxLayoutIcon(InboxLayout.POSTERS),
                onSelect = { onSelectLayout(InboxLayout.POSTERS) },
            ),
        )
    }

    Box(modifier) {
        RemoteBrowserCoverFlow(
            items = items,
            thumbnails = thumbnails,
            theme = theme,
            title = "폰하드",
            usesDarkBackground = usesDarkBackground,
            onBack = onBack,
            onDashboard = onDashboard,
            viewModes = inboxViewModes,
            onToggleBackground = onToggleBackground,
            onActivate = { item -> if (item.isDirectory) onOpenFolder(item.path) else filesByID[item.id]?.let(onActivate) },
            initialSelectedIndex = initialSelectedIndex,
            onSelectedIndexChange = onSelectedIndexChange,
            onLoadThumbnail = { item ->
                val file = filesByID[item.id] ?: return@RemoteBrowserCoverFlow
                if (requested.add(item.id)) {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            loadInboxThumbnail(
                                context,
                                file.file,
                                file.isInboxImage,
                                file.isInboxVideo,
                                file.isInboxPdf,
                                640,
                            )
                        }
                        result.bitmap?.let { thumbnails[item.id] = it }
                    }
                }
            },
        )
    }
}

private val InboxDisplayItem.isInboxImage: Boolean
    get() = mimeType?.startsWith("image/") == true ||
        originalFilename.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "gif", "heic", "webp")

private val InboxDisplayItem.isInboxVideo: Boolean
    get() = mimeType?.startsWith("video/") == true ||
        originalFilename.substringAfterLast('.', "").lowercase() in setOf("mp4", "mov", "m4v", "mkv", "avi", "webm")

private val InboxDisplayItem.isInboxPdf: Boolean
    get() = mimeType == "application/pdf" || originalFilename.endsWith(".pdf", ignoreCase = true)

private val InboxDisplayItem.isInboxAudio: Boolean
    get() = BuiltInPreviewPolicy.kind(originalFilename, mimeType) == BuiltInPreviewKind.AUDIO

@Composable
private fun InboxLeadingPreview(
    file: InboxDisplayItem,
    inlineAudioState: InlineAudioPlaybackState? = null,
    onToggleInlineAudio: (() -> Unit)? = null,
) {
    val porcelain = usesUnifiedLayout(LocalNasFinderTheme.current)
    Box(
        modifier = Modifier
            .size(if (porcelain) 58.dp else 56.dp)
            .then(
                if (porcelain) Modifier.clip(RoundedCornerShape(9.dp)).background(thumbnailSurfaceColor()).border(1.dp, thumbnailBorderColor(), RoundedCornerShape(9.dp))
                else Modifier
            )
            .then(
                if (onToggleInlineAudio != null) {
                    Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .clickable(
                            onClickLabel = if (inlineAudioState?.isPlaying == true) "일시정지" else "재생",
                            onClick = onToggleInlineAudio,
                        )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        InboxArtwork(file, requestedPixels = 112, modifier = Modifier.fillMaxSize(), cornerRadius = 9.dp)
        if (file.isInboxAudio && onToggleInlineAudio != null) {
            InlineAudioThumbnailOverlay(
                state = inlineAudioState,
                cornerRadius = 9.dp,
            )
        }
    }
}

@Composable
private fun InboxArtwork(
    file: InboxDisplayItem,
    requestedPixels: Int,
    modifier: Modifier,
    cornerRadius: Dp,
    cropCover: Boolean = false,
) {
    val context = LocalContext.current
    val isImage = file.isInboxImage
    val isVideo = file.isInboxVideo
    val isPdf = file.isInboxPdf
    val isAudio = file.isInboxAudio
    val preview by produceState(
        initialValue = InboxThumbnailResult(),
        key1 = if (isImage || isVideo || isPdf) "${file.file.path}:${file.file.lastModified()}:${file.file.length()}:$requestedPixels" else null,
    ) {
        value = if (isImage || isVideo || isPdf) withContext(Dispatchers.IO) {
            loadInboxThumbnail(context, file.file, isImage, isVideo, isPdf, requestedPixels)
        } else InboxThumbnailResult()
    }
    val icon = when {
        isImage -> Icons.Default.Image
        isVideo -> Icons.Default.Movie
        isPdf -> Icons.Default.PictureAsPdf
        isAudio -> Icons.Default.MusicNote
        else -> Icons.Default.InsertDriveFile
    }
    val thumbnail = preview.bitmap
    val porcelain = usesUnifiedLayout(LocalNasFinderTheme.current)
    BoxWithConstraints(
        modifier.clip(RoundedCornerShape(cornerRadius))
            .background(if (porcelain) thumbnailSurfaceColor() else MaterialTheme.colorScheme.onSurface.copy(alpha = .08f)),
        contentAlignment = Alignment.Center,
    ) {
        if (thumbnail != null) {
            SkinAwareThumbnailImage(
                bitmap = thumbnail,
                cacheIdentity = "inbox:${file.id}:${file.file.path}:${file.file.lastModified()}:${file.file.length()}:$requestedPixels",
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                // Porcelain shows the whole photo (.fit) on the paper surface; other themes keep the cropped fill.
                // Grid covers (cropCover) are aspect-filled per the approved boards.
                contentScale = if (porcelain && !cropCover) ContentScale.Fit else ContentScale.Crop,
            )
        } else {
            Icon(icon, null, Modifier.size(if (porcelain) minOf(maxWidth, maxHeight) * .38f else 24.dp), tint = if (porcelain) fileMetaColor() else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (isVideo && thumbnail != null) {
            Icon(
                Icons.Default.PlayCircle,
                "영상",
                Modifier.size(25.dp),
                tint = Color.White.copy(alpha = .94f),
            )
        }
        if (preview.isMotionPhoto) {
            Text(
                "Motion Photo",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = .68f))
                    .padding(horizontal = 3.dp, vertical = 2.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

private data class InboxThumbnailResult(
    val bitmap: android.graphics.Bitmap? = null,
    val isMotionPhoto: Boolean = false,
)

internal object InboxThumbnailMemoryCache {
    private val cache = object : android.util.LruCache<String, android.graphics.Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.allocationByteCount
    }

    @Synchronized fun get(key: String): android.graphics.Bitmap? = cache.get(key)
    @Synchronized fun put(key: String, bitmap: android.graphics.Bitmap) { cache.put(key, bitmap) }
}

private fun loadInboxThumbnail(
    context: Context,
    file: File,
    isImage: Boolean,
    isVideo: Boolean,
    isPdf: Boolean,
    maxPixelSize: Int,
): InboxThumbnailResult {
    val cacheKey = "${file.canonicalPath}:${file.lastModified()}:${file.length()}:$maxPixelSize"
    val cached = InboxThumbnailMemoryCache.get(cacheKey)
    val motionPhoto = isImage && isInboxMotionPhoto(file)
    if (cached != null && !cached.isRecycled) return InboxThumbnailResult(cached, motionPhoto)

    val thumbDir = File(context.cacheDir, "inbox_thumbs")
    val diskThumbFile = File(thumbDir, "${file.canonicalPath.hashCode()}_${file.lastModified()}_${file.length()}_$maxPixelSize.png")
    if (diskThumbFile.exists() && diskThumbFile.length() > 0) {
        val diskBitmap = runCatching { android.graphics.BitmapFactory.decodeFile(diskThumbFile.absolutePath) }.getOrNull()
        if (diskBitmap != null) {
            InboxThumbnailMemoryCache.put(cacheKey, diskBitmap)
            return InboxThumbnailResult(diskBitmap, motionPhoto)
        }
    }

    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.sharefiles", file)
    }.getOrNull()
    val bitmap = decodeInboxThumbnail(context, uri, file, isImage, isVideo, isPdf, maxPixelSize)
    if (bitmap != null) {
        InboxThumbnailMemoryCache.put(cacheKey, bitmap)
        runCatching {
            thumbDir.mkdirs()
            val tmp = File(thumbDir, "${diskThumbFile.name}.tmp")
            tmp.outputStream().buffered().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out)
            }
            tmp.renameTo(diskThumbFile)
        }
    }
    return InboxThumbnailResult(bitmap, motionPhoto)
}

internal fun decodeInboxThumbnail(
    context: Context,
    uri: android.net.Uri?,
    file: File,
    isImage: Boolean,
    isVideo: Boolean,
    isPdf: Boolean,
    maxPixelSize: Int,
): android.graphics.Bitmap? = runCatching {
        val resolverThumbnail = if (
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && uri != null && (isImage || isVideo)
        ) {
            runCatching {
                context.contentResolver.loadThumbnail(uri, android.util.Size(maxPixelSize, maxPixelSize), null)
            }.getOrNull()
        } else null
        when {
            resolverThumbnail != null -> scaleInboxThumbnail(resolverThumbnail, maxPixelSize)
            isImage -> {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > maxPixelSize || bounds.outHeight / sample > maxPixelSize) sample *= 2
                android.graphics.BitmapFactory.decodeFile(
                    file.path,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }
            isVideo -> {
                val platformThumbnail = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    android.media.ThumbnailUtils.createVideoThumbnail(
                        file,
                        android.util.Size(maxPixelSize, maxPixelSize),
                        null,
                    )
                } else null
                platformThumbnail?.let { scaleInboxThumbnail(it, maxPixelSize) } ?: android.media.MediaMetadataRetriever().run {
                    try {
                        setDataSource(file.path)
                        getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?.let { scaleInboxThumbnail(it, maxPixelSize) }
                    } finally {
                        release()
                    }
                }
            }
            isPdf -> android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                android.graphics.pdf.PdfRenderer(descriptor).use rendererBlock@ { renderer ->
                    if (renderer.pageCount == 0) return@rendererBlock null
                    renderer.openPage(0).use { page ->
                        val ratio = minOf(maxPixelSize.toFloat() / page.width, maxPixelSize.toFloat() / page.height, 1f)
                        android.graphics.Bitmap.createBitmap(
                            (page.width * ratio).toInt().coerceAtLeast(1),
                            (page.height * ratio).toInt().coerceAtLeast(1),
                            android.graphics.Bitmap.Config.ARGB_8888,
                        ).also { bitmap ->
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
            else -> null
        }
    }.getOrNull()

internal fun isInboxMotionPhoto(file: File): Boolean = runCatching {
    if (file.name.endsWith("MP.jpg")) return@runCatching true
    val prefix = ByteArray(minOf(file.length(), 256L * 1024L).toInt())
    val count = file.inputStream().buffered().use { input ->
        var offset = 0
        while (offset < prefix.size) {
            val read = input.read(prefix, offset, prefix.size - offset)
            if (read <= 0) break
            offset += read
        }
        offset
    }
    if (count <= 0) return@runCatching false
    val xmp = prefix.copyOf(count).toString(Charsets.UTF_8)
    (xmp.contains("MotionPhoto=\"1\"") || xmp.contains("MotionPhoto='1'")) &&
        xmp.contains("MotionPhotoVersion") && xmp.contains("MotionPhotoPresentationTimestampUs")
}.getOrDefault(false)

private fun scaleInboxThumbnail(bitmap: android.graphics.Bitmap, maxPixelSize: Int): android.graphics.Bitmap {
    val maxSide = maxOf(bitmap.width, bitmap.height)
    if (maxSide <= maxPixelSize) return bitmap
    val ratio = maxPixelSize.toFloat() / maxSide
    return android.graphics.Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * ratio).toInt().coerceAtLeast(1),
        (bitmap.height * ratio).toInt().coerceAtLeast(1),
        true,
    ).also { scaled -> if (scaled !== bitmap) bitmap.recycle() }
}

@Composable
internal fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = if (compact) 18.dp else 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Porcelain 07-states proposal: icon 56 (48–64), title 20/600, body 13/400; other themes unchanged.
        val porcelain = usesUnifiedLayout(LocalNasFinderTheme.current)
        Icon(icon, null, modifier = Modifier.size(if (compact) 34.dp else if (porcelain) 56.dp else 46.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f))
        Spacer(Modifier.height(10.dp))
        Text(
            title,
            style = if (compact) MaterialTheme.typography.titleSmall else if (porcelain) com.armsone.nasfinder.ui.theme.PorcelainType.StateTitle else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(5.dp))
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

private data class SuperThumbnailRuntimeConditions(
    val hasUnmeteredWifi: Boolean,
    val hasExternalPower: Boolean,
) {
    val isReady: Boolean get() = hasUnmeteredWifi && hasExternalPower
}

@Composable
private fun rememberSuperThumbnailRuntimeConditions(): SuperThumbnailRuntimeConditions {
    val context = LocalContext.current
    fun readConditions(): SuperThumbnailRuntimeConditions {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return SuperThumbnailRuntimeConditions(
            // Wi-Fi transport counts even when metered; cellular stays outside the normal run.
            hasUnmeteredWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            hasExternalPower = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL || plugged != 0,
        )
    }
    var conditions by remember { mutableStateOf(readConditions()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                conditions = readConditions()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }
        context.registerReceiver(receiver, filter)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return conditions
}

@Composable
private fun SuperThumbnailScreen(state: AppState, model: NasFinderViewModel) {
    val selected = state.connections.firstOrNull { it.id == state.superThumbnailConnectionId }
    val selectedPath = state.superThumbnailPath ?: selected?.normalizedRootPath
    val selectedTitle = state.superThumbnailTitle ?: selected?.name
    val history = state.superThumbnailHistory.filter { location ->
        state.connections.any { it.id == location.connectionId }
    }
    val work = state.superThumbnailWork
    val selectedLocationId = state.superThumbnailConnectionId?.let { connectionId ->
        selectedPath?.let { path -> "$connectionId\u0000$path" }
    }
    val selectedWork = work.takeIf { state.superThumbnailWorkLocation?.id == selectedLocationId }
    val sessionReport = state.superThumbnailSessionReport.takeIf {
        state.superThumbnailReportLocationId == selectedLocationId
    }
    val hasPendingSession = sessionReport?.hasWorkToResume == true
    val active = work?.status in setOf(SuperThumbnailWorkStatus.WAITING, SuperThumbnailWorkStatus.RUNNING)
    val conditions = rememberSuperThumbnailRuntimeConditions()
    val haptics = LocalHapticFeedback.current
    var hiddenStartTapCount by rememberSaveable { mutableIntStateOf(0) }
    var confirmVaultRemoval by remember { mutableStateOf(false) }
    var confirmSuperCacheReset by remember { mutableStateOf(false) }
    var vaultTimingMenuExpanded by remember { mutableStateOf(false) }
    val hiddenStartAvailable = selected != null && !conditions.isReady && !active

    BackHandler { model.show(Screen.Dashboard) }

    LaunchedEffect(selected?.id, selectedPath) { hiddenStartTapCount = 0 }
    LaunchedEffect(conditions.isReady, active) {
        if (conditions.isReady || active) hiddenStartTapCount = 0
    }
    DisposableEffect(Unit) { onDispose { hiddenStartTapCount = 0 } }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            PorcelainTopBar(title = "Super Thumbnail", onBack = { model.show(Screen.Dashboard) })
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .88f), contentColor = MaterialTheme.colorScheme.onSurface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column {
                        Column(
                            Modifier.fillMaxWidth().clickable(enabled = !active, onClickLabel = "NAS에서 처리할 폴더 선택", onClick = model::openSuperThumbnailFolderPicker),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 40.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(selectedTitle ?: "처리할 폴더 선택", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                                    Text(selectedPath ?: "NAS 폴더를 선택하세요", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("하위 폴더 포함 · 완료된 항목은 다시 만들지 않음", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            SuperThumbnailMark(Modifier.size(40.dp))
                            Text("선택한 폴더의 영상과 사진 썸네일을 미리 만듭니다.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = MaterialTheme.typography.bodyMedium.lineHeight)
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                Text("NAS에도 보관", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Switch(
                                    checked = state.superThumbnailVaultEnabled,
                                    onCheckedChange = model::setSuperThumbnailVaultEnabled,
                                    enabled = !active,
                                )
                            }
                            if (state.superThumbnailVaultEnabled) {
                                HorizontalDivider()
                                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("보관 시점", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Box {
                                        TextButton(onClick = { vaultTimingMenuExpanded = true }, enabled = !active) {
                                            Text(if (state.superThumbnailVaultTiming == SuperThumbnailVaultTiming.NOW) "즉시" else "나중에")
                                            Icon(Icons.Default.ArrowDropDown, null)
                                        }
                                        DropdownMenu(expanded = vaultTimingMenuExpanded, onDismissRequest = { vaultTimingMenuExpanded = false }) {
                                            listOf(
                                                SuperThumbnailVaultTiming.NOW to "폴더별 완료 즉시",
                                                SuperThumbnailVaultTiming.LATER to "작업 완료 후 한 번에",
                                            ).forEach { (timing, label) ->
                                                DropdownMenuItem(
                                                    text = { Text(label) },
                                                    leadingIcon = if (state.superThumbnailVaultTiming == timing) ({ Icon(Icons.Default.Check, null) }) else null,
                                                    onClick = {
                                                        vaultTimingMenuExpanded = false
                                                        model.setSuperThumbnailVaultTiming(timing)
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            Text(
                                when {
                                    !state.superThumbnailVaultEnabled -> "이 Android 기기의 Super Cache에만 저장합니다."
                                    state.superThumbnailVaultTiming == SuperThumbnailVaultTiming.NOW -> "각 폴더가 완료될 때마다 NAS에 보관합니다."
                                    else -> "모든 작업이 완료된 뒤 NAS에 한 번에 보관합니다."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .88f), contentColor = MaterialTheme.colorScheme.onSurface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Icon(if (conditions.isReady && selected != null) Icons.Default.CheckCircle else Icons.Default.Schedule, null, tint = if (conditions.isReady && selected != null) themePalette().accent else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                when {
                                    selected == null -> "먼저 처리할 폴더를 선택하세요."
                                    hiddenStartTapCount in 5..9 -> "제한 없이 시작하려면 ${10 - hiddenStartTapCount}번 더 누르세요."
                                    conditions.isReady -> "시작할 준비가 됐습니다."
                                    else -> "Wi‑Fi 연결과 충전을 기다립니다."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (active) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(superThumbnailStatus(work!!.status), style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = model::cancelSuperThumbnail, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(14.dp)) { Text("작업 중단") }
                        } else {
                            Box(Modifier.fillMaxWidth()) {
                                Button(
                                    onClick = {
                                        hiddenStartTapCount = 0
                                        model.startSuperThumbnail(resumeExisting = hasPendingSession)
                                    },
                                    enabled = selected != null && conditions.isReady,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                                    shape = RoundedCornerShape(14.dp),
                                ) {
                                    Icon(Icons.Default.AutoAwesome, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (hasPendingSession) "미완료 작업 이어서 하기" else "시작")
                                }
                                if (hiddenStartAvailable) {
                                    Box(
                                        Modifier.matchParentSize().pointerInput(selected?.id) {
                                            detectTapGestures {
                                                hiddenStartTapCount += 1
                                                if (hiddenStartTapCount >= 10) {
                                                    hiddenStartTapCount = 0
                                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    model.startSuperThumbnail(
                                                        allowsConstrainedRun = true,
                                                        resumeExisting = hasPendingSession,
                                                    )
                                                } else if (hiddenStartTapCount >= 5) {
                                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (history.isNotEmpty()) {
                item {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .88f), contentColor = MaterialTheme.colorScheme.onSurface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("최근 작업", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            history.forEachIndexed { index, location ->
                                val dismissState = rememberSwipeToDismissBoxState(
                                    confirmValueChange = { value ->
                                        if (value == SwipeToDismissBoxValue.EndToStart) {
                                            model.removeSuperThumbnailHistory(location)
                                            true
                                        } else false
                                    },
                                )
                                SwipeToDismissBox(
                                    state = dismissState,
                                    enableDismissFromStartToEnd = false,
                                    backgroundContent = {
                                        val deleting = dismissState.targetValue == SwipeToDismissBoxValue.EndToStart
                                        Box(
                                            Modifier.fillMaxSize().background(
                                                if (deleting) MaterialTheme.colorScheme.error else Color.Transparent,
                                            ).padding(end = 20.dp),
                                            contentAlignment = Alignment.CenterEnd,
                                        ) {
                                            if (deleting) Icon(Icons.Default.Delete, "최근 작업에서 삭제", tint = MaterialTheme.colorScheme.onError)
                                        }
                                    },
                                ) {
                                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .035f), contentColor = MaterialTheme.colorScheme.onSurface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f))) {
                                        Row(
                                            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { hiddenStartTapCount = 0; model.showSuperThumbnailReport(location) }.padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(11.dp),
                                        ) {
                                            Surface(Modifier.size(24.dp), CircleShape, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f), contentColor = MaterialTheme.colorScheme.onSurface) {
                                                Icon(if (index == 0) Icons.Default.History else Icons.Default.Schedule, null, Modifier.padding(5.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(location.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(location.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .72f), contentColor = MaterialTheme.colorScheme.onSurface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f))) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("네트워크 ${formatBytes(selectedWork?.estimatedBytes ?: 0L)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("생성 ${selectedWork?.generated ?: 0}개", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                        Text(
                            selectedWork?.let { "확인 ${it.visitedItems}개 · 실패 ${it.failed}개${if (it.budgetReached) " · 예산 도달" else ""}" }
                                ?: "아직 실행하지 않았습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        if (sessionReport != null) {
                            Text(
                                "완료 ${sessionReport.successfulCount + sessionReport.cachedCount} · 미완료 ${sessionReport.pendingCount + sessionReport.failures.size + sessionReport.vaultPendingCount + sessionReport.vaultFailedCount} · NAS 보관 ${sessionReport.vaultUploadedCount}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                        OutlinedButton(
                            onClick = { confirmVaultRemoval = true },
                            enabled = selected != null && !active && !state.isRemovingSuperThumbnailVault,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            if (state.isRemovingSuperThumbnailVault) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Delete, null)
                            Spacer(Modifier.width(8.dp))
                            Text("선택 폴더 NAS Vault 삭제")
                        }
                        OutlinedButton(
                            onClick = { confirmSuperCacheReset = true },
                            enabled = !active && !state.isBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Refresh, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Super Cache 초기화")
                        }
                    }
                }
            }
        }
    }
    if (confirmVaultRemoval) {
        AlertDialog(
            onDismissRequest = { confirmVaultRemoval = false },
            title = { Text("선택한 폴더와 하위 폴더의 NAS 보관본을 삭제할까요?") },
            text = { Text("원본 영상과 이 Android 기기의 Super Cache는 삭제되지 않습니다.") },
            dismissButton = { TextButton(onClick = { confirmVaultRemoval = false }) { Text("취소") } },
            confirmButton = {
                TextButton(
                    onClick = { confirmVaultRemoval = false; model.removeSelectedSuperThumbnailVault() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("NAS 보관본 삭제") }
            },
        )
    }
    if (confirmSuperCacheReset) {
        AlertDialog(
            onDismissRequest = { confirmSuperCacheReset = false },
            title = { Text("Super Thumbnail을 초기화할까요?") },
            text = { Text("Super Cache와 작업 기록을 초기화합니다. 원본 영상과 NAS Vault는 삭제하지 않습니다.") },
            dismissButton = { TextButton(onClick = { confirmSuperCacheReset = false }) { Text("취소") } },
            confirmButton = {
                TextButton(
                    onClick = { confirmSuperCacheReset = false; model.resetSuperCache() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("초기화") }
            },
        )
    }
    state.superThumbnailVaultResultMessage?.let { message ->
        AlertDialog(
            onDismissRequest = model::dismissSuperThumbnailVaultResult,
            title = { Text("NAS Vault") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = model::dismissSuperThumbnailVaultResult) { Text("확인") } },
        )
    }
}

@Composable
private fun SuperThumbnailFolderPickerScreen(state: AppState, model: NasFinderViewModel) {
    val picker = state.superThumbnailPicker
    val connection = state.connections.firstOrNull { it.id == picker.connectionId }
    val currentTitle = picker.path?.trimEnd('/')?.substringAfterLast('/')
        ?.takeIf(String::isNotBlank) ?: connection?.name ?: "NAS 선택"
    BackHandler {
        if (connection == null) model.closeSuperThumbnailFolderPicker()
        else model.openSuperThumbnailPickerParent()
    }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            PorcelainTopBar(
                title = if (connection == null) "NAS 선택" else currentTitle,
                onBack = if (connection != null) model::openSuperThumbnailPickerParent else null,
                backLabel = "상위 폴더",
                actions = { TextButton(model::closeSuperThumbnailFolderPicker, modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") } },
            )
        },
        bottomBar = {
            if (connection != null && picker.path != null && !picker.isLoading) {
                Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .94f), contentColor = MaterialTheme.colorScheme.onSurface, tonalElevation = 3.dp) {
                    Button(
                        onClick = model::selectCurrentSuperThumbnailFolder,
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).heightIn(min = 54.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Default.CheckCircle, null)
                        Spacer(Modifier.width(8.dp))
                        Text("이 폴더 선택")
                    }
                }
            }
        },
    ) { padding ->
        when {
            connection == null && state.connections.isEmpty() -> EmptyState(
                Icons.Default.CreateNewFolder,
                "연결된 NAS가 없습니다",
                "먼저 첫 화면에서 NAS 연결을 추가해 주세요.",
                Modifier.padding(padding).fillMaxSize(),
            )
            connection == null -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(state.connections, key = { it.id }) { item ->
                    ListItem(
                        headlineContent = { Text(item.name) },
                        supportingContent = { Text(item.normalizedRootPath, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = {
                            Icon(connectionKindIcon(item.kind), null, design = if (item.kind == ConnectionKind.SYNOLOGY) PackGlyph.NAS else null, tint = serviceColor(item.kind.name, state.theme))
                        },
                        trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                        modifier = Modifier.clickable { model.openSuperThumbnailPickerConnection(item.id) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
            picker.isLoading -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator()
                    Text("폴더를 불러오는 중…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            picker.error != null -> EmptyState(
                Icons.Default.WifiOff,
                "폴더를 열 수 없습니다",
                picker.error,
                Modifier.padding(padding).fillMaxSize(),
            )
            else -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                if (picker.items.isEmpty()) {
                    item { EmptyState(Icons.Default.FolderOpen, "하위 폴더 없음", "현재 폴더를 선택할 수 있습니다.") }
                } else {
                    items(picker.items, key = { it.id + "\u0000" + it.path }) { folder ->
                        ListItem(
                            headlineContent = { Text(folder.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                            modifier = Modifier.clickable { model.openSuperThumbnailPickerFolder(folder) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SuperThumbnailProgressScreen(state: AppState, model: NasFinderViewModel) {
    val work = state.superThumbnailWork
    val location = state.superThumbnailWorkLocation
    val report = state.superThumbnailSessionReport.takeIf { state.superThumbnailReportLocationId == location?.id }
    val active = work?.status in setOf(SuperThumbnailWorkStatus.WAITING, SuperThumbnailWorkStatus.RUNNING)
    BackHandler(enabled = active) { }
    BackHandler(enabled = !active, onBack = model::closeSuperThumbnailProgressOrReport)
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .94f), contentColor = MaterialTheme.colorScheme.onSurface, tonalElevation = 3.dp) {
                if (active) {
                    OutlinedButton(
                        onClick = model::cancelSuperThumbnail,
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp).heightIn(min = 54.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("작업 중단") }
                } else {
                    Button(
                        onClick = model::closeSuperThumbnailProgressOrReport,
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp).heightIn(min = 54.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("완료") }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Surface(Modifier.size(32.dp), RoundedCornerShape(8.dp), MaterialTheme.colorScheme.primaryContainer) {
                        Icon(Icons.Default.AutoAwesome, null, Modifier.padding(6.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        when (work?.status) {
                            SuperThumbnailWorkStatus.WAITING, SuperThumbnailWorkStatus.RUNNING -> "썸네일 만드는 중"
                            SuperThumbnailWorkStatus.SUCCESS -> "처리 완료"
                            SuperThumbnailWorkStatus.PARTIAL -> "일부 항목을 제외하고 완료"
                            SuperThumbnailWorkStatus.FAILED -> "처리 실패"
                            SuperThumbnailWorkStatus.CANCELLED -> "작업 중단"
                            null -> "작업 준비 중"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Icon(Icons.Default.Folder, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(location?.title ?: "선택한 폴더", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    location?.path?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            item {
                Surface(Modifier.fillMaxWidth(), RoundedCornerShape(15.dp), MaterialTheme.colorScheme.surface.copy(alpha = .88f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("확인 ${work?.visitedItems ?: 0}개", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        if (active) LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp))
                        Text(work?.let { superThumbnailStatus(it.status) } ?: "작업 정보를 기다리는 중", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    SuperThumbnailMetric("완료", report?.successfulCount ?: work?.generated ?: 0, Modifier.weight(1f))
                    SuperThumbnailMetric("건너뜀", report?.cachedCount ?: 0, Modifier.weight(1f))
                    SuperThumbnailMetric("실패", report?.failures?.size ?: work?.failed ?: 0, Modifier.weight(1f))
                }
            }
            item {
                Surface(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), MaterialTheme.colorScheme.surface.copy(alpha = .82f)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("세부 정보", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("세션 네트워크 · ${formatBytes(work?.estimatedBytes ?: 0L)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        report?.takeIf { it.vaultFolders.isNotEmpty() }?.let {
                            Text(
                                "NAS 보관 ${it.vaultUploadedCount} · 대기 ${it.vaultPendingCount} · 실패 ${it.vaultFailedCount}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (work?.budgetReached == true) Text("안전 예산에 도달해 일부 항목을 남겼습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }
    }
}

@Composable
private fun SuperThumbnailMetric(title: String, value: Int, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("$value", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SuperThumbnailReportScreen(state: AppState, model: NasFinderViewModel) {
    val location = state.superThumbnailHistory.firstOrNull {
        it.connectionId == state.superThumbnailConnectionId && it.path == state.superThumbnailPath
    } ?: state.superThumbnailConnectionId?.let { id ->
        state.superThumbnailPath?.let { path -> SuperThumbnailLocation(id, path, state.superThumbnailTitle ?: path.substringAfterLast('/')) }
    }
    val workSnapshot = state.superThumbnailWork?.takeIf { state.superThumbnailWorkLocation?.id == location?.id }
    val report = state.superThumbnailSessionReport.takeIf { state.superThumbnailReportLocationId == location?.id }
    BackHandler(onBack = model::closeSuperThumbnailProgressOrReport)
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            PorcelainTopBar(title = "Super Thumbnail", onBack = model::closeSuperThumbnailProgressOrReport)
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(Modifier.size(32.dp), RoundedCornerShape(8.dp), MaterialTheme.colorScheme.primaryContainer) {
                        Icon(Icons.Default.AutoAwesome, null, Modifier.padding(6.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("작업 보고서", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        Text(location?.title ?: "선택한 폴더", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(location?.path.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (report == null) {
                item { EmptyState(Icons.Default.FindInPage, "저장된 보고서 없음", "이 폴더의 Android Super Thumbnail 작업 기록이 없습니다.") }
            } else {
                item {
                    Surface(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp), MaterialTheme.colorScheme.surface.copy(alpha = .88f)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(
                                if (report.hasWorkToResume) {
                                    "미완료 ${report.pendingCount + report.failures.size + report.vaultPendingCount + report.vaultFailedCount}개"
                                } else "모두 완료",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "실패 ${report.failures.size} · 업로드 대기 ${report.vaultPendingCount + report.vaultFailedCount}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            workSnapshot?.takeIf { it.budgetReached }?.let {
                                Text("안전 예산에 도달했습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                        }
                    }
                }
                if (report.hasWorkToResume) {
                    item {
                        Button(
                            onClick = { model.startSuperThumbnail(resumeExisting = true) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(Icons.Default.Refresh, null)
                            Spacer(Modifier.width(8.dp))
                            Text("미완료 작업 이어서 하기")
                        }
                    }
                }
                item {
                    Surface(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp), MaterialTheme.colorScheme.surface.copy(alpha = .88f)) {
                        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("총계", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${report.successfulCount + report.cachedCount + report.failures.size + report.pendingCount}개", style = MaterialTheme.typography.titleMedium)
                            }
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("사진 미리보기", color = MaterialTheme.colorScheme.onSurfaceVariant); Text("성공 ${report.photoSuccessCount}개") }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("영상 미리보기", color = MaterialTheme.colorScheme.onSurfaceVariant); Text("성공 ${report.successCounts.sum()}개") }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("이미 완료", color = MaterialTheme.colorScheme.onSurfaceVariant); Text("${report.cachedCount}개") }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("실패", color = MaterialTheme.colorScheme.onSurfaceVariant); Text("${report.failures.size}개") }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("미처리", color = MaterialTheme.colorScheme.onSurfaceVariant); Text("${report.pendingCount}개") }
                            report.successCounts.forEachIndexed { index, success ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(listOf("5초", "20초", "40초").getOrElse(index) { "${index + 1}차" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("성공 ${success}개")
                                }
                            }
                            workSnapshot?.let {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("예상 네트워크", color = MaterialTheme.colorScheme.onSurfaceVariant); Text(formatBytes(it.estimatedBytes)) }
                            }
                        }
                    }
                }
                if (report.vaultFolders.isNotEmpty()) {
                    item {
                        Surface(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp), MaterialTheme.colorScheme.surface.copy(alpha = .88f)) {
                            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("NAS 보관 상세", fontWeight = FontWeight.Medium)
                                    Text("보관 ${report.vaultUploadedCount} · 대기 ${report.vaultPendingCount} · 실패 ${report.vaultFailedCount}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                report.vaultFolders.forEach { folder ->
                                    HorizontalDivider()
                                    Text(folder.path.substringAfterLast('/').ifBlank { folder.path }, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "보관 ${folder.uploadedCount}/${folder.totalCount} · 생성 대기 ${folder.waitingThumbnailCount} · 업로드 대기 ${folder.pendingCount} · 실패 ${folder.failedCount}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    folder.errorDescription?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, maxLines = 2) }
                                }
                                report.vaultLastVerifiedAt?.let { verified ->
                                    Text(
                                        "마지막 전체 확인 · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date.from(verified))}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                if (report.failures.isNotEmpty()) {
                    item {
                        Surface(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp), MaterialTheme.colorScheme.surface.copy(alpha = .88f)) {
                            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("미완료 파일 ${report.failures.size}개", fontWeight = FontWeight.Medium)
                                report.failures.forEachIndexed { index, failure ->
                                    if (index > 0) HorizontalDivider()
                                    Text(failure.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${failure.extension.ifBlank { "기타" }} · ${failure.size?.let(::formatBytes) ?: "크기 미상"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(failure.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                }
                            }
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = model::closeSuperThumbnailProgressOrReport, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.Folder, null)
                    Spacer(Modifier.width(8.dp))
                    Text("이 폴더 다시 선택")
                }
            }
        }
    }
}

@Composable
private fun ThumbnailCacheScreen(state: AppState, model: NasFinderViewModel) {
    var limitMenuExpanded by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val statistics = state.thumbnailCacheStatistics
    LaunchedEffect(Unit) { model.refreshThumbnailCacheStatistics() }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            PorcelainTopBar(title = "썸네일 캐시", onBack = { model.show(Screen.Dashboard) })
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { SectionTitle("현재 캐시", Icons.Default.Storage) }
            item {
                DashboardCard {
                    Row(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text("사용량", Modifier.weight(1f))
                        Text(statistics?.totalBytes?.let(::formatBytes) ?: "계산 중…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text("파일", Modifier.weight(1f))
                        Text(statistics?.let { "${it.fileCount}개" } ?: "—", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { SectionTitle("자동 정리", Icons.Default.AutoDelete) }
            item {
                DashboardCard {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("자동 정리 기준", Modifier.weight(1f))
                        Box {
                            TextButton(onClick = { limitMenuExpanded = true }, enabled = statistics != null) {
                                Text(statistics?.automaticLimitBytes?.let(::formatBytes) ?: "—")
                                Icon(Icons.Default.ArrowDropDown, null)
                            }
                            DropdownMenu(expanded = limitMenuExpanded, onDismissRequest = { limitMenuExpanded = false }) {
                                RemoteThumbnailCachePolicy.automaticLimitOptions.forEach { bytes ->
                                    DropdownMenuItem(
                                        text = { Text(formatBytes(bytes)) },
                                        leadingIcon = if (statistics?.automaticLimitBytes == bytes) ({ Icon(Icons.Default.Check, null) }) else null,
                                        onClick = { limitMenuExpanded = false; model.setThumbnailCacheLimit(bytes) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "용량을 넘거나 30일이 지나면 오래된 캐시부터 정리합니다.\n최대 5,000개를 보관합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
            item {
                DashboardCard {
                    TextButton(
                        onClick = { confirmClear = true },
                        enabled = (statistics?.fileCount ?: 0) > 0 && !state.isBusy,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) {
                        Icon(Icons.Default.Delete, null)
                        Spacer(Modifier.width(8.dp))
                        Text("지금 캐시 비우기")
                    }
                }
            }
            item {
                Text(
                    "원본 영상과 폰하드 파일은 삭제하지 않습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("썸네일 캐시를 비울까요?") },
            text = { Text("현재 ${statistics?.totalBytes?.let(::formatBytes) ?: "0 B"} · 원본 파일은 삭제되지 않습니다.") },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("취소") } },
            confirmButton = {
                TextButton(
                    onClick = { confirmClear = false; model.clearThumbnailCache() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("캐시 비우기") }
            },
        )
    }
}

@Composable
private fun GooglePhotosScreen(state: AppState, model: NasFinderViewModel) {
    val working = state.googlePhotosPhase in setOf(GooglePhotosPhase.AUTHORIZING, GooglePhotosPhase.PREPARING, GooglePhotosPhase.WAITING_FOR_SELECTION, GooglePhotosPhase.IMPORTING)
    Scaffold(
        containerColor = Color.Transparent,
        topBar = { PorcelainTopBar(title = "Google 포토", onBack = { if (working) model.cancelGooglePhotosImport(); model.show(Screen.Dashboard) }) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DashboardCard {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.PhotoLibrary, null, tint = themePalette().accent, modifier = Modifier.size(30.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (state.googlePhotosConnected) "Google 포토 연결됨" else "Google 포토 연결 필요", fontWeight = FontWeight.SemiBold)
                            Text("선택한 미디어만 폰하드에 저장합니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (working) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(when (state.googlePhotosPhase) {
                            GooglePhotosPhase.AUTHORIZING -> "브라우저에서 Google 로그인을 완료해 주세요."
                            GooglePhotosPhase.PREPARING -> "사진 선택 화면을 준비하고 있습니다."
                            GooglePhotosPhase.WAITING_FOR_SELECTION -> "Google 포토에서 사진을 선택해 주세요."
                            GooglePhotosPhase.IMPORTING -> "선택한 파일을 폰하드로 가져오고 있습니다."
                            else -> "처리 중입니다."
                        }, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(model::cancelGooglePhotosImport, Modifier.fillMaxWidth()) { Text("취소") }
                    } else {
                        Button(
                            onClick = model::requestGooglePhotosImport,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (state.googlePhotosConnected) "사진 선택" else "Google 포토 연결") }
                        if (state.googlePhotosPhase == GooglePhotosPhase.ERROR) {
                            OutlinedButton(model::retryGooglePhotosImport, Modifier.fillMaxWidth(), enabled = state.googlePhotosConnected) { Text("다시 시도") }
                        }
                        if (state.googlePhotosConnected) TextButton(model::disconnectGooglePhotos, Modifier.fillMaxWidth()) { Text("연결 해제") }
                    }
                }
            }
            Text("NasFinder는 선택을 시작할 때 Google에 접속합니다. 선택한 원본은 Google에서 이 기기로 내려받아 폰하드에 보관하며, NasFinder 서버로 보내지 않습니다. 모바일 데이터 사용량이 발생할 수 있습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (state.googlePhotosDisclosureRequired) {
        AlertDialog(
            onDismissRequest = model::dismissGooglePhotosDisclosure,
            icon = { Icon(Icons.Default.PhotoLibrary, null) },
            title = { Text("Google 포토에서 가져오기") },
            text = { Text("Google에 접속해 선택 화면을 엽니다. 선택한 미디어 원본을 이 기기로 내려받아 폰하드에 저장합니다. 모바일 데이터가 사용될 수 있습니다.") },
            confirmButton = { TextButton(model::confirmGooglePhotosDisclosure) { Text("계속") } },
            dismissButton = { TextButton(model::dismissGooglePhotosDisclosure) { Text("취소") } },
        )
    }
}

@Composable
private fun SettingsScreen(state: AppState, model: NasFinderViewModel, automaticUpdateDownloadEnabled: Boolean, onAutomaticUpdateDownloadChanged: (Boolean) -> Unit, onCheckForUpdates: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    var confirmCacheClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { model.refreshDownloadCacheSize() }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            PorcelainTopBar(title = "설정", onBack = { model.show(Screen.Dashboard) })
        },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
        // Settings use the whole content width: one column on phones, two from 900dp, three from 1500dp (iOS macSettingsColumns).
        val settingsColumns = if (maxWidth >= 1500.dp) 3 else if (maxWidth >= 900.dp) 2 else 1
        androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid(
            columns = androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells.Fixed(settingsColumns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 16.dp),
            verticalItemSpacing = 14.dp,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionTitle("테마", Icons.Default.Palette)
                        val columns = 3
                        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .94f), contentColor = MaterialTheme.colorScheme.onSurface) {
                            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                AppTheme.entries.chunked(columns).forEach { row ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        row.forEach { theme ->
                                            val selected = state.theme == theme
                                            // Each swatch shows that theme's own page, text, accent and selected colours.
                                            val swatch = paletteFor(theme)
                                            Box(
                                                Modifier.weight(1f).height(104.dp)
                                                    .background(swatch.background, RoundedCornerShape(12.dp))
                                                    .border(
                                                        if (selected) 1.5.dp else .5.dp,
                                                        if (selected) swatch.accent else swatch.border,
                                                        RoundedCornerShape(12.dp),
                                                    )
                                                    .clickable { model.setTheme(theme) }
                                                    .semantics(mergeDescendants = true) { this.selected = selected }
                                            ) {
                                                Column(Modifier.fillMaxSize().padding(9.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                        ThemePackIcon(theme, null, Modifier.size(12.dp), tint = swatch.text)
                                                        Spacer(Modifier.weight(1f))
                                                        if (selected) Icon(Icons.Default.CheckCircle, "선택됨", Modifier.size(12.dp), tint = swatch.accent)
                                                    }
                                                    Spacer(Modifier.weight(1f))
                                                    AutoShrinkThemeTitle(themeTitle(theme), swatch.text)
                                                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                                        listOf(swatch.accent, swatch.text, swatch.surface, swatch.selected).forEach { color ->
                                                            Box(Modifier.size(5.dp).background(color, CircleShape).border(.5.dp, swatch.border, CircleShape))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                                HorizontalDivider()
                                Text(
                                    "선택한 테마는 앱을 다시 열어도 유지됩니다. 레이아웃·글꼴은 같고 색만 바뀝니다.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionTitle("아이콘 스타일", Icons.Default.GridView)
                    InterfaceIconPackChooser(state.iconPack, model::setIconPack)
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionTitle("프라이버시", Icons.Default.VisibilityOff)
                    DashboardCard {
                        val privacyOn by com.armsone.nasfinder.data.PrivacyMode.active.collectAsStateWithLifecycle()
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("프라이버시 모드", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                Switch(checked = privacyOn, onCheckedChange = { model.setPrivacyMode(it) })
                            }
                            Text(
                                "켜면 평소 기록에 더해 비공개로 지정한 기록도 함께 보입니다. 끄면 비공개 기록이 목록에서 숨겨집니다. 앱을 다시 열면 항상 꺼진 상태로 시작합니다. 도구 탭을 길게 눌러도 바꿀 수 있습니다. " +
                                    com.armsone.nasfinder.data.PrivacyMode.HONEST_NOTE,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionTitle("화면 켜짐", Icons.Default.PhoneAndroid)
                        DashboardCard {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                    ScreenAwakeMode.entries.forEachIndexed { index, mode ->
                                        SegmentedButton(
                                            selected = state.screenAwakeMode == mode,
                                            onClick = { model.setScreenAwakeMode(mode) },
                                            shape = SegmentedButtonDefaults.itemShape(index, ScreenAwakeMode.entries.size),
                                        ) { Text(when (mode) { ScreenAwakeMode.AUTOMATIC -> "오토"; ScreenAwakeMode.ALWAYS -> "항상 켜짐"; ScreenAwakeMode.OFF -> "끔" }) }
                                    }
                                }
                                Text(
                                    when (state.screenAwakeMode) {
                                        ScreenAwakeMode.AUTOMATIC -> "다운로드·썸네일 생성·파일 작업 중에만 화면을 켜 둡니다."
                                        ScreenAwakeMode.ALWAYS -> "NasFinder가 화면에 열려 있는 동안 화면을 켜 둡니다."
                                        ScreenAwakeMode.OFF -> "Android의 화면 자동 잠금 설정을 그대로 따릅니다."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionTitle("앱 아이콘", Icons.Default.Apps)
                        val choices = listOf(
                            Triple(LauncherIconVariant.DEFAULT, "블루 NAS", R.drawable.app_icon_blue_nas),
                            Triple(LauncherIconVariant.PURPLE_NAS, "퍼플 NAS", R.drawable.app_icon_purple_nas),
                            Triple(LauncherIconVariant.VIBE_CODER, "Vibe Coder", R.drawable.app_icon_vibe_coder),
                            Triple(LauncherIconVariant.CYBER_VAULT, "사이버 볼트", R.drawable.app_icon_cyber_vault),
                            Triple(LauncherIconVariant.NAS_RADAR, "네트워크 NAS", R.drawable.app_icon_nas_radar),
                            Triple(LauncherIconVariant.ENAMEL, "BK Style", R.drawable.app_icon_enamel),
                        )
                        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .94f), contentColor = MaterialTheme.colorScheme.onSurface) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                choices.chunked(3).forEach { row ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        row.forEach { (variant, label, drawable) ->
                                            val selected = state.launcherIcon == variant
                                            Column(
                                                Modifier.weight(1f).height(104.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .56f))
                                                    .border(
                                                        BorderStroke(
                                                            if (selected) 1.5.dp else 1.dp,
                                                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .75f)
                                                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f),
                                                        ),
                                                        RoundedCornerShape(12.dp),
                                                    )
                                                    .clickable(enabled = state.pendingLauncherIcon == null && !selected) { model.setLauncherIcon(variant) }
                                                    .padding(8.dp)
                                                    .semantics(mergeDescendants = true) { this.selected = selected },
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                                            ) {
                                                Image(
                                                    bitmap = ImageBitmap.imageResource(drawable),
                                                    contentDescription = "$label 앱 아이콘 미리보기",
                                                    modifier = Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)),
                                                    filterQuality = FilterQuality.High,
                                                )
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    if (state.pendingLauncherIcon == variant) {
                                                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                                    } else if (selected) {
                                                        Icon(Icons.Default.CheckCircle, "선택됨", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                                    }
                                                }
                                            }
                                        }
                                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                                HorizontalDivider()
                                Text(
                                    "선택한 아이콘은 홈 화면과 앱 보관함에 적용됩니다. 테마를 바꿔도 앱 아이콘은 그대로입니다.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionTitle("저장공간 관리", Icons.Default.Storage)
                        DashboardCard {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Cached, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("현재 사용량")
                                    Text(
                                        "${state.downloadCacheBytes?.let(::formatBytes) ?: "계산 중…"} · 7일 · 최대 512 MB",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(
                                    onClick = { confirmCacheClear = true },
                                    enabled = (state.downloadCacheBytes ?: 0L) > 0L && !state.isBusy,
                                ) { Text("전체 지우기") }
                            }
                        }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionTitle("썸네일 캐시 및 생성", Icons.Default.PhotoLibrary)
                        DashboardCard {
                            DashboardRow(
                                Icons.Default.PhotoLibrary,
                                "썸네일 캐시",
                                formatDashboardCacheBytes(state.thumbnailCacheStatistics?.totalBytes),
                                MaterialTheme.colorScheme.onSurfaceVariant,
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            ) { model.show(Screen.ThumbnailCache) }
                            DashboardRowDivider()
                            SuperThumbnailDashboardRow(if (state.superThumbnailSessionReport == null) "0 B" else "관리") {
                                model.show(Screen.SuperThumbnail)
                            }
                        }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionTitle("파일 선택기 연동", Icons.Default.FolderOpen)
                        DashboardCard {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(if (state.connections.isEmpty()) "먼저 NAS 또는 SFTP 연결을 추가해 주세요." else "현재 저장된 원격 위치 ${state.connections.size}개를 Android 파일 선택기에서도 열 수 있습니다.", style = MaterialTheme.typography.bodyMedium)
                                Text("1. 파일 앱 또는 파일 선택 화면의 저장 위치 메뉴를 엽니다.\n2. ‘NasFinder’를 선택합니다.\n3. 서버 이름을 선택해 원격 파일을 엽니다.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("지원되는 작업은 연결 방식과 서버 권한에 따라 달라집니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionTitle("앱 정보", Icons.Default.Info)
                        DashboardCard {
                            Row(Modifier.fillMaxWidth().padding(14.dp)) {
                                Text("버전", Modifier.weight(1f))
                                Text(state.appVersion, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth().padding(14.dp)) {
                                Text("빌드", Modifier.weight(1f))
                                Text(BuildConfig.BUILD_NUMBER, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth().padding(14.dp)) {
                                Text("저장된 연결", Modifier.weight(1f))
                                Text("${state.connections.size}개", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionTitle("지원 프로토콜", Icons.Default.Hub)
                        DashboardCard {
                            ConnectionKind.entries.forEachIndexed { index, kind ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(connectionKindIcon(kind), null, design = if (kind == ConnectionKind.SYNOLOGY) PackGlyph.NAS else null, tint = serviceColor(kind.name, state.theme), modifier = Modifier.size(22.dp))
                                    Spacer(Modifier.width(11.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(kind.title, style = MaterialTheme.typography.bodyMedium)
                                        Text(protocolSupport(kind), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (index != ConnectionKind.entries.lastIndex) HorizontalDivider()
                            }
                        }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionTitle("업데이트", Icons.Default.Info)
                        DashboardCard {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("업데이트 자동 다운로드")
                                    Text("데이터 요금이 없는 네트워크에서 받습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = automaticUpdateDownloadEnabled, onCheckedChange = onAutomaticUpdateDownloadChanged)
                            }
                            HorizontalDivider()
                            TextButton(onClick = onCheckForUpdates, modifier = Modifier.fillMaxWidth()) { Text("업데이트 확인") }
                        }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionTitle("오픈 소스", Icons.Default.Code)
                        DashboardCard {
                            DashboardRow(Icons.Default.Code, "오픈 소스 구성요소", "구성요소·버전·라이선스·공식 소스 보기") {
                                uriHandler.openUri("https://github.com/armsone/NasFinder-Android/blob/main/docs/OPEN_SOURCE.md")
                            }
                            HorizontalDivider()
                            DashboardRow(Icons.Default.Description, "Apache 2.0 라이선스", "구성요소별 고지와 소스는 각 프로젝트에서 확인") { uriHandler.openUri("https://www.apache.org/licenses/LICENSE-2.0") }
                        }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionTitle("만든 사람", Icons.Default.Person)
                        DashboardCard {
                            DashboardRow(Icons.Default.Link, "GitHub · armsone", "NasFinder를 만든 사람의 GitHub입니다.") {
                                uriHandler.openUri("https://github.com/armsone")
                            }
                            HorizontalDivider()
                            DashboardRow(Icons.Default.Public, "공식 사이트 · nasfinder.com", "NasFinder 공식 홈페이지입니다.") {
                                uriHandler.openUri("https://nasfinder.com")
                            }
                        }
                }
            }
            item {
                Text(
                    "자격 증명은 Android Keystore로 암호화됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        }
    }
    if (confirmCacheClear) {
        AlertDialog(
            onDismissRequest = { confirmCacheClear = false },
            title = { Text("다운로드 캐시를 지울까요?") },
            text = { Text("미리보기와 공유를 위해 내려받은 임시 파일을 모두 삭제합니다. 원격 서버의 원본은 삭제되지 않습니다.") },
            dismissButton = { TextButton(onClick = { confirmCacheClear = false }) { Text("취소") } },
            confirmButton = {
                TextButton(
                    onClick = { confirmCacheClear = false; model.clearDownloadCache() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("전체 지우기") }
            },
        )
    }
}

@Composable
private fun InterfaceIconPackChooser(selectedPack: InterfaceIconPack, onSelect: (InterfaceIconPack) -> Unit) {
    // Approved 06-settings: one row per style — selection mark, NAS · photos · settings samples, name + one-line description.
    // Selection stays the manual pack preference; nothing here changes the pack on its own.
    val choices = listOf(
        Triple(InterfaceIconPack.SIMPLE, "심플", "간결한 선으로 구성된 라인 스타일"),
        Triple(InterfaceIconPack.SOLID, "솔리드", "명확하고 단단한 면의 플랫 스타일"),
        Triple(InterfaceIconPack.DIMENSION, "입체", "부드러운 입체감의 모던 스타일"),
        Triple(InterfaceIconPack.REALISTIC, "리얼", "실제 질감을 표현한 현실적인 스타일"),
    )
    val samples = listOf(PackGlyph.NAS, PackGlyph.PHOTOS, PackGlyph.SETTINGS)
    val accent = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .94f), contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxWidth()) {
            choices.forEachIndexed { index, (pack, title, description) ->
                val selected = selectedPack == pack
                if (index > 0) HorizontalDivider(Modifier.padding(start = 12.dp), thickness = .5.dp, color = secondary.copy(alpha = .18f))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(pack) })
                        .semantics { contentDescription = "$title 아이콘, $description" }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        if (selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                        null,
                        Modifier.size(20.dp),
                        tint = if (selected) accent else secondary.copy(alpha = .6f),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            samples.forEach { PackIcon(it, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSurface, pack = pack) }
                        }
                        Text(title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text(description, fontSize = 13.sp, lineHeight = 18.sp, color = secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (selected) Icon(Icons.Default.Check, "선택됨", Modifier.size(18.dp), tint = accent)
                }
            }
            Text(
                "화면 테마·앱 아이콘과 따로 저장됩니다. 미디어·폴더 썸네일은 항상 실제 이미지를 보여 줍니다.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

private fun superThumbnailStatus(status: SuperThumbnailWorkStatus) = when (status) {
    SuperThumbnailWorkStatus.WAITING -> "네트워크와 충전 조건을 기다리는 중"
    SuperThumbnailWorkStatus.RUNNING -> "원격 사진을 확인하는 중"
    SuperThumbnailWorkStatus.SUCCESS -> "완료"
    SuperThumbnailWorkStatus.PARTIAL -> "일부 항목을 제외하고 완료"
    SuperThumbnailWorkStatus.FAILED -> "완료하지 못함"
    SuperThumbnailWorkStatus.CANCELLED -> "취소됨"
}

private fun protocolSupport(kind: ConnectionKind) = when (kind) {
    ConnectionKind.SYNOLOGY, ConnectionKind.SFTP, ConnectionKind.SMB ->
        "탐색 · 다운로드 · 업로드 · 관리 · 복사/이동"
    ConnectionKind.FTP -> "탐색 · 다운로드 · 업로드 · 폴더/이름/삭제"
    ConnectionKind.WEBDAV -> "탐색 · 다운로드"
    ConnectionKind.DROPBOX, ConnectionKind.ONEDRIVE, ConnectionKind.GOOGLE_DRIVE -> "계정 연결 준비 중"
}

private fun themeTitle(theme: AppTheme) = theme.title
@Composable
private fun ThemePackIcon(theme: AppTheme, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = Color.Unspecified) {
    val glyph = when (theme) {
        AppTheme.LIGHT -> PackGlyph.DISPLAY_LIGHT
        AppTheme.BKSTYLE -> PackGlyph.THEME_HALF
        AppTheme.NIGHT -> PackGlyph.DISPLAY_DARK
        AppTheme.FRIDAY -> PackGlyph.THEME_HEXAGON
        AppTheme.JARVIS, AppTheme.ULTRON -> null // Named colour themes retain their identity marks.
    }
    CompositionLocalProvider(LocalThemePalette provides paletteFor(theme)) {
        val ink = if (LocalInterfaceIconPack.current == InterfaceIconPack.SIMPLE) {
            if (paletteFor(theme).isDark) Color.White else Color.Black
        } else tint
        Icon(ThemeVectors.vectorFor(theme), contentDescription, modifier, ink, design = glyph)
    }
}

private class ThemeChooserBadge(val fill: Color, val glyph: Color)

private fun themeChooserBadge(theme: AppTheme) = when (theme) {
    AppTheme.LIGHT -> ThemeChooserBadge(Color(0xFFF5F4F0), Color(0xFF5C6B3C))
    AppTheme.BKSTYLE -> ThemeChooserBadge(Color(0xFFD9D3C7), Color(0xFF46532B))
    AppTheme.NIGHT -> ThemeChooserBadge(Color(0xFF1C1C1A), Color(0xFF93B163))
    AppTheme.JARVIS -> ThemeChooserBadge(Color(0xFF0C1520), Color(0xFF4DC2E6))
    AppTheme.FRIDAY -> ThemeChooserBadge(Color(0xFFF8EFE3), Color(0xFFB8651B))
    AppTheme.ULTRON -> ThemeChooserBadge(Color(0xFF121316), Color(0xFFF2566A))
}
@Composable
private fun AutoShrinkThemeTitle(text: String, color: Color) {
    var size by remember(text) { mutableFloatStateOf(12f) }
    Text(
        text,
        color = color,
        fontSize = size.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { result ->
            if (result.hasVisualOverflow && size > 8.16f) size = (size - .5f).coerceAtLeast(8.16f)
        },
    )
}
private fun formatDashboardCacheBytes(value: Long?): String = if (value == null || value <= 0L) "0 B" else formatBytes(value)
private fun formatBytes(value: Long): String { if (value < 1024) return "$value B"; val units = arrayOf("KB", "MB", "GB", "TB"); var size = value.toDouble(); var index = -1; do { size /= 1024; index++ } while (size >= 1024 && index < units.lastIndex); return "%.1f %s".format(size, units[index]) }

/** Home "네트워크 추가" chooser (Google 포토 / NAS·서버·클라우드) and Google 포토 dialog; shared by home and the wide sidebar. */
@Composable
internal fun NetworkAddDialogs(
    state: AppState,
    model: NasFinderViewModel,
    addingNetwork: Boolean,
    onAddingNetwork: (Boolean) -> Unit,
    editingGooglePhotos: Boolean,
    onEditingGooglePhotos: (Boolean) -> Unit,
    googlePhotosName: String,
    onGooglePhotosName: (String) -> Unit,
) {
    if (addingNetwork) {
        BackHandler { onAddingNetwork(false) }
        Surface(
            modifier = Modifier.fillMaxSize().zIndex(90f),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .appSafeArea()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { onAddingNetwork(false) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "네트워크 추가",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { onAddingNetwork(false) }) {
                        Text("닫기", style = MaterialTheme.typography.labelMedium)
                    }
                }
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        onClick = {
                            onAddingNetwork(false)
                            model.show(Screen.AddConnection())
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("NAS · 서버 · 클라우드 드라이브", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text("Synology, SFTP, WebDAV, SMB/CIFS 등", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                    }

                    if (!state.googlePhotosVisible) {
                        Surface(
                            onClick = {
                                onAddingNetwork(false)
                                onGooglePhotosName(state.googlePhotosName)
                                onEditingGooglePhotos(true)
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(Icons.Default.PhotoLibrary, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("Google 포토", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    Text("Google 포토 연결 복원 및 추가", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            }
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(Icons.Default.PhotoLibrary, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(24.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("Google 포토", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Google 포토는 이미 추가되어 있습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (editingGooglePhotos) {
        AlertDialog(
            onDismissRequest = { onEditingGooglePhotos(false) },
            title = { Text(if (state.googlePhotosVisible) "Google 포토 연결 수정" else "Google 포토 연결 추가") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(googlePhotosName, onGooglePhotosName, label = { Text("표시 이름") }, singleLine = true)
                    Text("Google 계정은 시스템 사진 선택창에서 선택합니다. 계정과 원본 사진은 변경하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        model.saveGooglePhotosLocation(googlePhotosName)
                        onEditingGooglePhotos(false)
                        model.requestGooglePhotosImport()
                    }) { Text("사진 선택·계정 확인") }
                }
            },
            confirmButton = { TextButton(onClick = { model.saveGooglePhotosLocation(googlePhotosName); onEditingGooglePhotos(false) }) { Text(if (state.googlePhotosVisible) "저장" else "추가") } },
            dismissButton = { TextButton(onClick = { onEditingGooglePhotos(false); onGooglePhotosName(state.googlePhotosName) }) { Text("취소") } },
        )
    }
}
