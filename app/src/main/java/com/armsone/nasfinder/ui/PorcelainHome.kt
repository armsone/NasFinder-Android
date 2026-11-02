@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.armsone.nasfinder.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.armsone.nasfinder.BuildConfig
import com.armsone.nasfinder.model.ConnectionKind
import com.armsone.nasfinder.model.RemoteConnection
import com.armsone.nasfinder.model.RemoteFavorite
import com.armsone.nasfinder.ui.theme.HomeType
import com.armsone.nasfinder.ui.theme.themePalette

/** Breakpoint at which the home keeps a persistent sidebar; below it the phone hub is a single column. */
private val SidebarMinWidth = 600.dp
private val HomeTwoColumnMinWidth = 560.dp

/**
 * Actual main inner width (sidebar, divider and main padding removed) needed to split 파일 열기 | 영상 작업:
 * source group 2 × 168 + gap 24 + video 300 (HOME-VISUAL-EXECUTION-2232 §3 recommendation, not a PNG value).
 */
private val TwoColumnMinWidth = 660.dp
private val WideMainPadding = 20.dp
private val WideColumnGap = 24.dp

/**
 * Home metrics (HOME-VISUAL-EXECUTION-2232 §2). Structure is from 01-home.png; the numbers are that document's
 * implementation values, not PNG measurements: outer 16, section gap 20, title gap 8, group corner 12 / hairline 0.5,
 * source cell min 68 / padding 10×10 / leading 32 / gap 10, compact row min 44 / leading 24 / gap 8,
 * utility row leading 28 / gap 10, red CTA min 52 / corner 10 / icon 32.
 */
private val PorcelainCorner = 12.dp
private val PhoneOuter = 16.dp
private val SectionGap = 20.dp
private val TitleGap = 8.dp
private val SourceIcon = 32.dp
/** Favorite cover: phone and pad 52×52 (Mac 78 is Apple-only) centered in a fixed 68 caption column; shelf gap 8. */
private val FavoriteCover = 52.dp
private val FavoriteCaptionColumn = 68.dp
/** Leading frame used by the compact utility rows. */
private val PorcelainRowIcon = 24.dp
private val PorcelainLeadingFrame = 28.dp

private fun connectionGlyph(kind: ConnectionKind) = when (kind) {
    ConnectionKind.DROPBOX, ConnectionKind.ONEDRIVE, ConnectionKind.GOOGLE_DRIVE -> PackGlyph.CLOUD
    else -> PackGlyph.NAS
}

private fun isNasKind(kind: ConnectionKind) = kind != ConnectionKind.DROPBOX && kind != ConnectionKind.ONEDRIVE && kind != ConnectionKind.GOOGLE_DRIVE

/** iOS primaryNASConnection: the preferred connection when it is a NAS, otherwise the first saved NAS. */
private fun primaryNas(state: AppState): RemoteConnection? =
    state.connections.firstOrNull { it.id == state.preferredId && isNasKind(it.kind) }
        ?: state.connections.firstOrNull { isNasKind(it.kind) }

/** iOS ByteCountFormatter-like short size for porcelain summaries. */
private fun porcelainBytes(value: Long): String {
    if (value < 1024) return "$value B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var size = value.toDouble()
    var index = -1
    do { size /= 1024; index++ } while (size >= 1024 && index < units.lastIndex)
    return "%.1f %s".format(size, units[index])
}

/**
 * Unified home (all six themes) following the approved 01-home board: 즐겨찾기 title + 52 cover shelf directly on the
 * page → 파일 열기 compact 2×2 source group + thin Browser/네트워크 추가 row → 영상 작업 red CTA + real memory drafts →
 * compact utility rows → 저장된 연결. Wide windows keep the persistent sidebar and the same 52 shelf; 파일 열기 | 영상 작업
 * split only when the actual main inner width reaches 660, otherwise they stack.
 * Every callback is the existing dashboard callback; nothing here owns business logic.
 */
@Composable
internal fun PorcelainHome(
    state: AppState,
    model: NasFinderViewModel,
    onAddNetwork: () -> Unit,
    onEditGooglePhotos: () -> Unit,
    onDeleteGooglePhotos: () -> Unit,
    onDeleteConnection: (RemoteConnection) -> Unit,
    onRemoveFavorite: (RemoteFavorite) -> Unit,
    onImportFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val configuration = LocalConfiguration.current
        val isTablet = configuration.smallestScreenWidthDp >= 600
        val totalWidth = maxWidth
        val wide = isTablet && totalWidth >= SidebarMinWidth
        val sidebarWidth = sidebarWidthFor(state, totalWidth)
        val mainInnerWidth = totalWidth - sidebarWidth - 1.dp - WideMainPadding * 2
        val split = wide && mainInnerWidth >= TwoColumnMinWidth
        Row(Modifier.fillMaxSize()) {
            if (wide) {
                PorcelainSidebar(
                    state, model, onAddNetwork, onImportFiles,
                    Modifier.width(sidebarWidth).fillMaxHeight(),
                )
                VerticalDividerLine()
            }
            if (!wide) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = PhoneOuter, top = 4.dp, end = PhoneOuter, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(SectionGap),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    item { Box(Modifier.widthIn(max = 640.dp)) { PorcelainFavorites(state, model, onRemoveFavorite) } }
                    item {
                        Box(Modifier.widthIn(max = 640.dp)) {
                            PorcelainFileOpen(state, model, onAddNetwork, onEditGooglePhotos, onDeleteGooglePhotos, onDeleteConnection, allConnections = false)
                        }
                    }
                    item { Box(Modifier.widthIn(max = 640.dp)) { PorcelainVideo(state, model) } }
                    item { Box(Modifier.widthIn(max = 640.dp)) { PorcelainUtilities(model, onImportFiles) } }
                    item { Box(Modifier.widthIn(max = 640.dp)) { PorcelainSavedConnections(state, model, onDeleteConnection) } }
                }
            } else {
                // Wide: the same 52 shelf as the phone, then 파일 열기 | 영상 작업 only when the main area can hold both.
                LazyColumn(
                    Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(start = WideMainPadding, top = 6.dp, end = WideMainPadding, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(SectionGap),
                ) {
                    item { PorcelainFavorites(state, model, onRemoveFavorite) }
                    if (split) {
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WideColumnGap)) {
                                // 336 : 300 keeps both columns at or above their minimums right at the 660 threshold.
                                Box(Modifier.weight(336f)) {
                                    PorcelainFileOpen(state, model, onAddNetwork, onEditGooglePhotos, onDeleteGooglePhotos, onDeleteConnection, allConnections = true)
                                }
                                Box(Modifier.weight(300f)) { PorcelainVideo(state, model) }
                            }
                        }
                    } else {
                        item { PorcelainFileOpen(state, model, onAddNetwork, onEditGooglePhotos, onDeleteGooglePhotos, onDeleteConnection, allConnections = true) }
                        item { PorcelainVideo(state, model) }
                    }
                    item { VersionCaption() }
                }
            }
        }
    }
}

/** Section title with an optional 전체 보기 link; the link opens the full list of the same (unlimited) store. */
@Composable
private fun HomeSection(title: String, onViewAll: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = HomeType.Section, color = MaterialTheme.colorScheme.onBackground)
            if (onViewAll != null) {
                TextButton(onClick = onViewAll, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("전체 보기", style = HomeType.Tertiary, color = themePalette().accent)
                }
            }
        }
        content()
    }
}

/**
 * 홈 tab root: follows the approved iPhone Finder layout —
 * 즐겨찾기 (with 전체 보기 button and horizontal thumbnail shelf) →
 * 파일 열기 (compact 2x2 source group: primary NAS, 내 사진·영상, 폰하드, Google 포토) →
 * Browser / 기기 파일 split row + 아래 네트워크 추가 →
 * 저장된 연결 (other saved connections).
 * Phone: single column. Tablet / wide window: PorcelainWideHost keeps the persistent sidebar beside it.
 */
@Composable
internal fun PorcelainPhoneHome(
    state: AppState,
    model: NasFinderViewModel,
    onAddNetwork: () -> Unit,
    onEditGooglePhotos: () -> Unit,
    onDeleteGooglePhotos: () -> Unit,
    onDeleteConnection: (RemoteConnection) -> Unit,
    onRemoveFavorite: (RemoteFavorite) -> Unit,
    onImportFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(PhoneOuter),
        verticalArrangement = Arrangement.spacedBy(SectionGap),
    ) {
        val superThumbStatus = state.superThumbnailWork?.status?.name ?: ""
        val isSuperThumbActive = superThumbStatus in setOf("WAITING", "RUNNING", "FAILED", "PARTIAL")
        val isVideoExportActive = (state.trailer?.export?.running == true || state.trailer?.export?.error != null) &&
            (state.trailer?.originPrivate != true || com.armsone.nasfinder.data.PrivacyMode.isVisible(true))

        val isMediaTransferVisible = (state.mediaTransferActive || state.mediaTransferFailure) &&
            com.armsone.nasfinder.data.PrivacyMode.isVisible(state.mediaTransferOriginPrivate)

        item {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Box(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                    PorcelainFavorites(
                        state = state,
                        model = model,
                        onRemoveFavorite = onRemoveFavorite,
                        onOpenFinder = { model.selectMainDestination(MainNavigationDestination.FINDER) },
                        onViewAll = { model.show(Screen.Favorites) },
                    )
                }
            }
        }

        item {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Box(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                    PorcelainHomeWorkbench(state = state, model = model)
                }
            }
        }

        item {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Box(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                    PorcelainVideo(state = state, model = model)
                }
            }
        }

        if (isMediaTransferVisible || isSuperThumbActive || isVideoExportActive) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                        PorcelainSection(title = "진행 중인 작업") {
                            PorcelainPanel(Modifier.fillMaxWidth()) {
                                if (isMediaTransferVisible) {
                                    PorcelainRow(
                                        title = "미디어 전송",
                                        glyph = PackGlyph.TRANSFER,
                                        subtitle = if (state.mediaTransferFailure) "확인이 필요한 전송이 있습니다." else "전송 진행 화면으로 돌아가기",
                                        onClick = { model.show(Screen.PhotoTransfer) },
                                    )
                                    if (isSuperThumbActive || isVideoExportActive) {
                                        Spacer(Modifier.height(8.dp))
                                    }
                                }
                                if (isSuperThumbActive) {
                                    val work = state.superThumbnailWork
                                    PorcelainRow(
                                        title = "수퍼 썸네일",
                                        glyph = PackGlyph.SUPER_THUMBNAIL,
                                        subtitle = if (work?.status?.name == "FAILED") "작업 실패" else "처리 중...",
                                        onClick = {
                                            if (state.superThumbnailWorkLocation != null) {
                                                model.show(Screen.SuperThumbnailProgress)
                                            } else {
                                                model.show(Screen.SuperThumbnail)
                                            }
                                        },
                                    )
                                }
                                if (isSuperThumbActive && isVideoExportActive) {
                                    PorcelainDivider()
                                }
                                if (isVideoExportActive) {
                                    val export = state.trailer?.export
                                    PorcelainRow(
                                        title = "영상 저장·내보내기",
                                        glyph = PackGlyph.VIDEO,
                                        subtitle = if (export?.error != null) "내보내기 실패" else "내보내는 중...",
                                        onClick = { model.restoreEditor() },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Home media-transfer progress is supplied by the retained transfer host.

        item {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Box(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                    PorcelainSection(title = "대표 메뉴") {
                        PorcelainPanel(Modifier.fillMaxWidth()) {
                            PorcelainRow(
                                title = "파인더",
                                glyph = PackGlyph.FOLDER,
                                subtitle = "파일 및 저장소 탐색",
                                onClick = { model.selectMainDestination(MainNavigationDestination.FINDER) },
                            )
                            PorcelainDivider()
                            PorcelainRow(
                                title = "만들기",
                                glyph = PackGlyph.VIDEO,
                                subtitle = "영상 편집 및 제작",
                                onClick = { model.selectMainDestination(MainNavigationDestination.VIDEO) },
                            )
                            PorcelainDivider()
                            PorcelainRow(
                                title = "도구",
                                glyph = PackGlyph.TRANSFER,
                                subtitle = "유틸리티 및 도구함",
                                onClick = { model.selectMainDestination(MainNavigationDestination.TOOLS) },
                            )
                            PorcelainDivider()
                            PorcelainRow(
                                title = "설정",
                                glyph = PackGlyph.SETTINGS,
                                subtitle = "앱 환경 설정",
                                onClick = { model.selectMainDestination(MainNavigationDestination.SETTINGS) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 작업대 location on the 홈 aggregate: independent of the Finder / 영상 / 도구 groups, opens the same destination as the tab. */
@Composable
private fun PorcelainHomeWorkbench(state: AppState, model: NasFinderViewModel) {
    PorcelainSection(title = "작업대") {
        PorcelainPanel(Modifier.fillMaxWidth()) {
            if (state.pockets.isEmpty()) {
                PorcelainRow(
                    title = "기본 포켓",
                    glyph = PackGlyph.WORKBENCH,
                    subtitle = "작업대로 이동",
                    onClick = { model.selectMainDestination(MainNavigationDestination.WORKBENCH) },
                )
            } else {
                state.pockets.forEachIndexed { index, pocket ->
                    if (index > 0) PorcelainDivider()
                    PorcelainRow(
                        title = pocket.name,
                        glyph = PackGlyph.WORKBENCH,
                        onClick = {
                            model.selectWorkbenchPocket(pocket.id)
                            model.selectMainDestination(MainNavigationDestination.WORKBENCH)
                        },
                    )
                }
            }
        }
    }
}

/** 도구 group on the 홈 aggregate: 사진 선별 · 사진·영상 전송 · 설정 (same routes as the 도구 tab). */
@Composable
private fun PorcelainHomeTools(model: NasFinderViewModel) {
    PorcelainSection("도구") {
        PorcelainPanel(Modifier.fillMaxWidth()) {
            PorcelainRow("사진 선별", PackGlyph.PHOTO_SELECTION, subtitle = "비슷한 사진을 묶고 몇 장이 적당한지 추천") { model.show(Screen.PhotoSelection) }
            PorcelainDivider()
            PorcelainRow("미디어 전송", PackGlyph.TRANSFER, subtitle = "미디어·Live Photos·Motion Photos 기기 간 전송") { model.show(Screen.PhotoTransfer) }
            PorcelainDivider()
            PorcelainRow("설정", PackGlyph.SETTINGS) { model.show(Screen.Settings) }
        }
        VersionCaption()
    }
}

/**
 * 파인더 tab root: the formal 즐겨찾기 sub-item first (the full stored list, reorderable), then every saved connection plus
 * the device sources as a plain list. Opens nothing until a row is tapped.
 */
@Composable
internal fun PorcelainFinder(
    state: AppState,
    model: NasFinderViewModel,
    onAddNetwork: () -> Unit,
    onEditGooglePhotos: () -> Unit,
    onDeleteGooglePhotos: () -> Unit,
    onDeleteConnection: (RemoteConnection) -> Unit,
    onRemoveFavorite: (RemoteFavorite) -> Unit,
    modifier: Modifier = Modifier,
    onImportFiles: (() -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(PhoneOuter),
        verticalArrangement = Arrangement.spacedBy(SectionGap),
    ) {
        item {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Box(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                    PorcelainFavorites(
                        state = state,
                        model = model,
                        onRemoveFavorite = onRemoveFavorite,
                        onViewAll = { model.show(Screen.Favorites) },
                    )
                }
            }
        }

        item {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Box(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                    PorcelainSection(title = "내 기기") {
                        SourceGroup(
                            cells = buildList<@Composable (Modifier) -> Unit> {
                                add { m -> PhotoLibraryCell(model = model, modifier = m) }
                                add { m -> PhoneHardCell(state = state, model = model, modifier = m) }
                                if (onImportFiles != null) {
                                    add { m ->
                                        SourceCell("기기 파일", "기기·클라우드의 파일 선택", PackGlyph.DEVICE_FILES, m, onClick = onImportFiles)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }

        item {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Box(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                    PorcelainSection(title = "네트워크") {
                        val netCells = mutableListOf<@Composable (Modifier) -> Unit>()
                        state.connections.forEach { conn ->
                            netCells.add { m ->
                                ConnectionCell(
                                    connection = conn,
                                    state = state,
                                    model = model,
                                    onDeleteConnection = onDeleteConnection,
                                    modifier = m,
                                )
                            }
                        }
                        if (state.googlePhotosVisible) {
                            netCells.add { m ->
                                GooglePhotosCell(
                                    state = state,
                                    model = model,
                                    onEditGooglePhotos = onEditGooglePhotos,
                                    onDeleteGooglePhotos = onDeleteGooglePhotos,
                                    modifier = m,
                                )
                            }
                        }
                        if (netCells.isNotEmpty()) {
                            SourceGroup(cells = netCells)
                        } else {
                            PorcelainPanel(Modifier.fillMaxWidth()) {
                                PorcelainRow(
                                    title = "등록된 네트워크 연결 없음",
                                    glyph = PackGlyph.NETWORK,
                                    subtitle = "연결 추가 버튼을 눌러 추가하세요",
                                    onClick = onAddNetwork,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        PorcelainPanel {
                            CompactAction(
                                title = "연결 추가·관리",
                                glyph = PackGlyph.ADD,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onAddNetwork,
                            )
                        }
                    }
                }
            }
        }

        item {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Box(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                    PorcelainSection(title = "웹") {
                        PorcelainPanel(Modifier.fillMaxWidth()) {
                            PorcelainRow(
                                title = "브라우저",
                                glyph = PackGlyph.BROWSER,
                                subtitle = "웹 사이트 탐색 및 다운로드",
                                onClick = { model.show(Screen.WebBrowser) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Formal 즐겨찾기 destination (wide-window sidebar → Finder group): the full stored list with the existing
 * reordering (drag / arrow via moveFavorite) and long-press actions, not just the preview shelf of the Finder root.
 */
@Composable
internal fun PorcelainFavoritesDestination(
    state: AppState,
    model: NasFinderViewModel,
    onRemoveFavorite: (RemoteFavorite) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(IconHitTarget)) { PackIcon(PackGlyph.BACK, "뒤로", Modifier.size(ToolbarActionIcon)) }
            Text("즐겨찾기", style = HomeType.Section, color = MaterialTheme.colorScheme.onBackground)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = PhoneOuter, top = 4.dp, end = PhoneOuter, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(SectionGap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Box(Modifier.widthIn(max = 640.dp)) {
                    if (state.remoteFavorites.isNotEmpty()) {
                        PorcelainFavoritesShelf(state, model, onRemoveFavorite, state.remoteFavorites)
                    } else {
                        Text(
                            "아직 즐겨찾기가 없습니다. 파인더에서 파일이나 폴더를 길게 눌러 즐겨찾기에 추가하면 이곳에 바로가기가 표시됩니다.",
                            style = HomeType.Description,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                    }
                }
            }
            if (state.remoteFavorites.size > 1) {
                item {
                    Text(
                        "항목을 길게 눌러 끌면 순서를 바꿀 수 있습니다.",
                        style = HomeType.Tertiary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * Keeps the compact location sidebar beside file browsers on wide windows; narrow windows render [content] untouched.
 * [activeRoot] marks a 홈/파인더 root screen: only 홈 is selected for HOME, and neither for FINDER, so the sidebar and the bottom bar agree.
 */
@Composable
internal fun PorcelainWideHost(
    state: AppState,
    model: NasFinderViewModel,
    selectedConnectionId: String? = null,
    phoneHardSelected: Boolean = false,
    fullscreen: Boolean = false,
    activeRoot: MainNavigationDestination? = null,
    favoritesSelected: Boolean = false,
    content: @Composable () -> Unit,
) {
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { model.importPickedFiles(it) }
    var addingNetwork by remember { mutableStateOf(false) }
    var editingGooglePhotos by remember { mutableStateOf(false) }
    var googlePhotosName by remember(state.googlePhotosName) { mutableStateOf(state.googlePhotosName) }
    NetworkAddDialogs(state, model, addingNetwork, { addingNetwork = it }, editingGooglePhotos, { editingGooglePhotos = it }, googlePhotosName, { googlePhotosName = it })
    val configuration = LocalConfiguration.current
    val isTablet = configuration.smallestScreenWidthDp >= 600 || LocalContext.current.applicationContext.resources.configuration.smallestScreenWidthDp >= 600
    val navExpanded = state.sidebarExpanded
    var portraitWorkbenchOverlay by rememberSaveable { mutableStateOf(false) }
    var wideWorkbenchExpanded by rememberSaveable { mutableStateOf(false) }
    val currentContent by rememberUpdatedState(content)
    val movableContent = remember { movableContentOf { currentContent() } }
    Column(Modifier.fillMaxSize()) {
        if (state.screen != Screen.Dashboard && !fullscreen) MainAppHeader(state, model)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val totalWidth = maxWidth
        val isLandscapeWide = isTablet && totalWidth >= 960.dp
        // Phone in landscape (smallestScreenWidthDp < 600) suppresses persistent sidebar to avoid duplicate navigation and cramped content.
        val showSidebar = isTablet && !fullscreen
        val showWorkbenchPanel = isLandscapeWide && !fullscreen && state.screen != Screen.MaterialBasket && state.activeMainTab != MainNavigationDestination.WORKBENCH
        if (!showSidebar) {
            if (fullscreen || state.screen == Screen.Dashboard) movableContent()
            else androidx.compose.material3.Scaffold(containerColor = Color.Transparent, bottomBar = {
                MainTabBar(state.activeMainTab, onTogglePrivacy = model::togglePrivacyMode, onSelect = model::selectMainDestination)
            }) { padding -> Box(Modifier.padding(padding)) { movableContent() } }
        } else {
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize()) {
                    val widthSpec = rememberSidebarWidthSpec(state, totalWidth)
                    val expandedWidth = widthSpec.resolve(state.sidebarWidthDp)
                    Box(Modifier.width(if (navExpanded) expandedWidth else 72.dp).fillMaxHeight()) {
                        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                            if (navExpanded) {
                                PorcelainSidebar(
                                    state, model, { addingNetwork = true }, { importer.launch(arrayOf("*/*")) },
                                    Modifier.weight(1f).fillMaxWidth(), selectedConnectionId, phoneHardSelected, activeRoot,
                                    favoritesSelected, onCollapse = { model.setSidebarExpanded(false) },
                                )
                            } else {
                                PorcelainCompactRail(
                                    state, model, { model.setSidebarExpanded(true) },
                                    selectedConnectionId, phoneHardSelected, activeRoot, favoritesSelected,
                                )
                            }
                        }
                        if (navExpanded) {
                            // Separate 8dp strip over the sidebar's outer padding; menu rows never receive the drag.
                            SidebarResizeStrip(
                                widthDp = expandedWidth.value,
                                minDp = widthSpec.min.value,
                                maxDp = widthSpec.max.value,
                                onWidth = model::setSidebarWidthDp,
                                modifier = Modifier.align(Alignment.CenterEnd),
                            )
                        }
                    }
                    VerticalDividerLine()
                    Box(Modifier.weight(1f).fillMaxHeight()) { movableContent() }
                    if (showWorkbenchPanel) {
                        VerticalDividerLine()
                        if (wideWorkbenchExpanded) {
                            Box(Modifier.width(260.dp).fillMaxHeight().navigationBarsPadding()) {
                                Column(Modifier.fillMaxSize()) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("작업대", style = HomeType.Tertiary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        IconButton(onClick = { wideWorkbenchExpanded = false }, modifier = Modifier.size(IconHitTarget)) {
                                            PackIcon(PackGlyph.CLOSE, "작업대 접기", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    Box(Modifier.weight(1f)) {
                                        WorkbenchScreen(state, model, embedded = true)
                                    }
                                }
                            }
                        } else {
                            Box(
                                Modifier
                                    .width(36.dp)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                    .clickable(onClick = { wideWorkbenchExpanded = true })
                                    .navigationBarsPadding()
                                    .semantics { contentDescription = "작업대 패널 열기" },
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PackIcon(PackGlyph.WORKBENCH, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (state.materials.isNotEmpty()) {
                                        Text(
                                            "${state.materials.size}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = themePalette().accent,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (!isLandscapeWide && portraitWorkbenchOverlay && state.screen != Screen.MaterialBasket && state.activeMainTab != MainNavigationDestination.WORKBENCH) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f))
                            .clickable(onClick = { portraitWorkbenchOverlay = false }),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Surface(
                            modifier = Modifier
                                .widthIn(max = 380.dp)
                                .fillMaxHeight()
                                .clickable(enabled = false) {},
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 6.dp,
                            shadowElevation = 8.dp,
                        ) {
                            Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("작업대", style = HomeType.Section, color = MaterialTheme.colorScheme.onSurface)
                                    IconButton(onClick = { portraitWorkbenchOverlay = false }, modifier = Modifier.size(IconHitTarget)) {
                                        PackIcon(PackGlyph.CLOSE, "닫기", tint = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                                Box(Modifier.weight(1f)) {
                                    WorkbenchScreen(state, model, embedded = true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

/** True when the window keeps the persistent sidebar (tablet-class window ≥ 600dp wide); the bottom tab bar is then not shown. */
@Composable
internal fun rememberWideShell(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.smallestScreenWidthDp >= 600 || LocalContext.current.applicationContext.resources.configuration.smallestScreenWidthDp >= 600
}

private val SidebarMinNaturalWidth = 180.dp
private val SidebarMinMainArea = 120.dp
private val SidebarResizeStep = 16f
private val SidebarRootLabels = listOf("홈", "파인더", "만들기", "도구", "설정")
private val SidebarChildLabels = listOf(
    "즐겨찾기", "내 미디어", "폰하드", "기기 파일", "연결 추가·관리", "브라우저",
    "미디어 고르기", "작업대", "편집본 전체",
    "사진 선별", "미디어 전송", "수퍼 썸네일", "썸네일 캐시",
)

/** Expanded sidebar sizing: [natural] fits the longest real label; [min]..[max] bound a user-dragged width for the current window. */
private class SidebarWidthSpec(val natural: Dp, val min: Dp, val max: Dp) {
    fun resolve(requestedDp: Float?): Dp = (requestedDp?.dp ?: natural).coerceIn(min, max)
}

/**
 * Natural width = widest of the root rows (8 outer + 10 pad + 26 icon + 12 gap + text + 10 pad + 8 outer), child rows (same with a
 * 24 indent and 20 icon) and the header (8 + 10 + 24 icon + 8 gap + brand + 8 + 48 toggle + 8), using the real styled text
 * measured at the current font scale (selected = semibold, the widest case), plus 4dp. Bounds: min = natural (≥ 180dp);
 * max = min(50 % of the window, window − 240dp), never below min and always leaving at least 120dp for the main area.
 */
@Composable
private fun rememberSidebarWidthSpec(state: AppState, total: Dp): SidebarWidthSpec {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    val childLabels = buildList {
        addAll(SidebarChildLabels)
        state.connections.forEach { add(it.name) }
        if (state.googlePhotosVisible) add(state.googlePhotosName)
    }
    val natural = remember(childLabels, density.density, density.fontScale, typography) {
        fun widest(labels: List<String>, style: TextStyle): Float = labels.maxOf { label ->
            with(density) { measurer.measure(label, style, maxLines = 1, softWrap = false).size.width.toDp().value }
        }
        val rootText = widest(SidebarRootLabels, typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
        val childText = widest(childLabels, typography.bodySmall.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
        val brandText = widest(listOf("Privacy", "NasFinder"), HomeType.Brand)
        val needed = maxOf(74f + rootText, 92f + childText, 114f + brandText)
        kotlin.math.ceil(needed + 4f).dp.coerceAtLeast(SidebarMinNaturalWidth)
    }
    val max = minOf(total * .5f, total - 240.dp)
        .coerceAtLeast(natural)
        .coerceAtMost((total - SidebarMinMainArea).coerceAtLeast(0.dp))
    return SidebarWidthSpec(natural, minOf(natural, max), max)
}

/** One sizing rule for every sidebar caller: the dragged RAM width if any, else the measured natural width, clamped to this window. */
@Composable
private fun sidebarWidthFor(state: AppState, total: Dp): Dp =
    rememberSidebarWidthSpec(state, total).resolve(state.sidebarWidthDp)

/** Narrow right-edge drag strip (and 넓히기/줄이기 accessibility actions, 16dp each) that resizes the expanded sidebar. */
@Composable
private fun SidebarResizeStrip(widthDp: Float, minDp: Float, maxDp: Float, onWidth: (Float) -> Unit, modifier: Modifier = Modifier) {
    val latestWidth by rememberUpdatedState(widthDp)
    val latestOnWidth by rememberUpdatedState(onWidth)
    Box(
        modifier
            .width(8.dp)
            .fillMaxHeight()
            .pointerInput(minDp, maxDp) {
                var base = 0f
                detectHorizontalDragGestures(onDragStart = { base = latestWidth }) { change, dragAmount ->
                    change.consume()
                    base = (base + dragAmount / density).coerceIn(minDp, maxDp)
                    latestOnWidth(base)
                }
            }
            .semantics {
                contentDescription = "사이드바 너비 조절"
                customActions = listOf(
                    CustomAccessibilityAction("넓히기") { latestOnWidth((latestWidth + SidebarResizeStep).coerceIn(minDp, maxDp)); true },
                    CustomAccessibilityAction("줄이기") { latestOnWidth((latestWidth - SidebarResizeStep).coerceIn(minDp, maxDp)); true },
                )
            }
    )
}

@Composable
private fun VerticalDividerLine() {
    Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f)))
}

@Composable
private fun VersionCaption() {
    Text(
        "버전 ${BuildConfig.VERSION_NAME} · 빌드 ${BuildConfig.BUILD_NUMBER}",
        style = HomeType.Tertiary,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    )
}

/** Board section title: plain 20/600 text on the page, optional small trailing meta. */
@Composable
private fun PorcelainHeader(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = HomeType.Section, color = MaterialTheme.colorScheme.onBackground)
        if (trailing != null) Text(trailing, style = HomeType.Tertiary, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PorcelainPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(PorcelainCorner),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) { Column(content = content) }
}

/** Title (gap 8) then content, no extra enclosure around the section itself. */
@Composable
private fun PorcelainSection(title: String, trailing: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
        PorcelainHeader(title, trailing)
        content()
    }
}

@Composable
private fun PorcelainDivider(start: Dp = 50.dp) {
    Box(Modifier.fillMaxWidth().padding(start = start).height(.5.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun PorcelainVerticalHairline() {
    Box(Modifier.fillMaxHeight().width(.5.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

/** Compact grouped row: 28 leading, gap 10, title 14/600, optional 12/400 subtitle up to 2 lines, padding 12×8, minHeight 48. */
@Composable
private fun PorcelainRow(
    title: String,
    glyph: PackGlyph,
    subtitle: String? = null,
    showsChevron: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(if (onClick != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(PorcelainLeadingFrame), contentAlignment = Alignment.Center) {
            PackIcon(glyph, null, Modifier.size(PorcelainRowIcon), tint = MaterialTheme.colorScheme.onSurface)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = HomeType.Source, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = HomeType.Description, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
        if (showsChevron) Icon(Icons.Default.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PorcelainFavorites(
    state: AppState,
    model: NasFinderViewModel,
    onRemoveFavorite: (RemoteFavorite) -> Unit,
    onOpenFinder: (() -> Unit)? = null,
    onViewAll: (() -> Unit)? = null,
) {
    // 파인더 하위 항목: the full, reorderable favorites shelf (the same stored list the home preview shows a part of).
    HomeSection("즐겨찾기", onViewAll = onViewAll) {
        if (state.remoteFavorites.isNotEmpty()) {
            PorcelainFavoritesShelf(state, model, onRemoveFavorite, state.remoteFavorites)
        } else if (onOpenFinder != null) {
            // Phone 홈 empty state: explain where favorites come from and offer the existing path to the 파인더 tab.
            Text(
                "아직 즐겨찾기가 없습니다. 파인더에서 파일이나 폴더를 길게 눌러 즐겨찾기에 추가하면 이곳에 바로가기가 표시됩니다.",
                style = HomeType.Description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            PorcelainPanel(Modifier.fillMaxWidth()) {
                CompactAction("파인더 열기", PackGlyph.CONNECTIONS, Modifier.fillMaxWidth(), onClick = onOpenFinder)
            }
        } else {
            Text(
                "길게 눌러 즐겨찾기에 추가하세요.",
                style = HomeType.Description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
    }
}

/** The existing favorite shelf (long-press menu, drag/arrow reordering via moveFavorite) for [favorites]. */
@Composable
private fun PorcelainFavoritesShelf(
    state: AppState,
    model: NasFinderViewModel,
    onRemoveFavorite: (RemoteFavorite) -> Unit,
    favorites: List<RemoteFavorite>,
) {
    FavoriteShelf(
        favorites = favorites,
        connections = state.connections,
        thumbnails = state.favoriteThumbnails,
        thumbnailGeneration = state.favoriteThumbnailGeneration,
        theme = state.theme,
        onOpen = model::openFavorite,
        onLoadThumbnail = model::loadFavoriteThumbnail,
        onRemoveRequest = onRemoveFavorite,
        onMove = model::moveFavorite,
        coverWidth = FavoriteCover,
        onMenuRequest = model::openFavoriteActions,
        squareTiles = true,
        tileWidth = FavoriteCaptionColumn,
    )
}

/**
 * 파일 열기 cell: 32 leading art left, gap 10, flexible text (title 14/600/19 and description 12/400/17, each up to two
 * lines), min 68 growing naturally, padding 10×10. No trailing menu button reserves width; management stays on long-press.
 */
@Composable
private fun SourceCell(
    title: String,
    detail: String?,
    glyph: PackGlyph,
    modifier: Modifier = Modifier,
    googleMark: Boolean = false,
    description: String? = null,
    starred: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    menu: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // Connection/Google context menus stay on long-press (and the accessibility long-click action).
    val longPress = onLongClick ?: if (menu != null) ({ menuOpen = true }) else null
    Box(modifier) {
        Row(
            Modifier.fillMaxSize().heightIn(min = 68.dp)
                .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = longPress,
                    onLongClickLabel = longClickLabel ?: if (menu != null) "$title 연결 메뉴" else null,
                )
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(SourceIcon), contentAlignment = Alignment.Center) {
                if (googleMark) GooglePhotosMark(Modifier.size(SourceIcon)) else PackIcon(glyph, null, Modifier.size(SourceIcon), tint = MaterialTheme.colorScheme.onSurface)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, Modifier.weight(1f, fill = false), style = HomeType.Source, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (starred) PackIcon(PackGlyph.FAVORITES, "기본 위치", Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                }
                if (!detail.isNullOrBlank()) Text(detail, style = HomeType.Description, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (menu != null) {
            Box(Modifier.align(Alignment.BottomEnd)) {
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) { menu { menuOpen = false } }
            }
        }
    }
}

@Composable
private fun ConnectionMenuItems(
    connection: RemoteConnection,
    preferred: Boolean,
    model: NasFinderViewModel,
    onDeleteConnection: (RemoteConnection) -> Unit,
    dismiss: () -> Unit,
) {
    DropdownMenuItem(text = { Text(if (preferred) "기본 연결 해제" else "기본 연결") }, leadingIcon = { Icon(Icons.Default.Star, null) }, onClick = { dismiss(); model.setPreferred(if (preferred) null else connection) })
    DropdownMenuItem(text = { Text("위로") }, leadingIcon = { Icon(Icons.Default.ArrowUpward, null) }, onClick = { dismiss(); model.moveConnection(connection, -1) })
    DropdownMenuItem(text = { Text("아래로") }, leadingIcon = { Icon(Icons.Default.ArrowDownward, null) }, onClick = { dismiss(); model.moveConnection(connection, 1) })
    DropdownMenuItem(text = { Text("연결 수정") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { dismiss(); model.show(Screen.AddConnection(connection)) })
    // 이 앱의 목록에서만 숨긴다. 로그인 정보·NAS 권한·원본은 그대로다(잠금이나 암호화가 아니다).
    DropdownMenuItem(
        text = { Text(if (connection.isPrivate) "공개로 전환" else "비공개로 전환") },
        leadingIcon = { Icon(if (connection.isPrivate) Icons.Default.Visibility else Icons.Default.VisibilityOff, null) },
        onClick = { dismiss(); model.setConnectionPrivate(connection, !connection.isPrivate) },
    )
    DropdownMenuItem(text = { Text("연결 삭제") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { dismiss(); onDeleteConnection(connection) })
}

@Composable
private fun ConnectionCell(
    connection: RemoteConnection,
    state: AppState,
    model: NasFinderViewModel,
    onDeleteConnection: (RemoteConnection) -> Unit,
    modifier: Modifier,
) {
    val preferred = state.preferredId == connection.id
    // Board short description; the real address stays in the connection's management (long-press) menu and editor.
    SourceCell(
        title = connection.name,
        detail = "저장된 연결",
        glyph = connectionGlyph(connection.kind),
        modifier = modifier,
        description = "${connection.name}, 저장된 연결, ${connection.host.takeIf { it.isNotBlank() } ?: connection.kind.title}",
        starred = preferred,
        onClick = { model.resumeConnection(connection) },
        menu = { dismiss -> ConnectionMenuItems(connection, preferred, model, onDeleteConnection, dismiss) },
    )
}

@Composable
private fun PhotoLibraryCell(model: NasFinderViewModel, modifier: Modifier) {
    SourceCell("내 미디어", "이 기기의 미디어", PackGlyph.PHOTOS, modifier, onClick = { model.show(Screen.LocalPhotoLibrary()) })
}

@Composable
private fun PhoneHardCell(state: AppState, model: NasFinderViewModel, modifier: Modifier) {
    val drafts = state.trailerDraftCount
    // iOS: "이 기기 · {count}개 · {size}" (or "이 기기 · 0개" when empty).
    val summary = if (state.inboxFiles.isEmpty()) "0개"
    else "${state.inboxFiles.size}개 · ${porcelainBytes(state.inboxFiles.sumOf { it.byteCount })}"
    SourceCell(
        title = "폰하드",
        detail = "가져온 파일",
        glyph = PackGlyph.PHONEHARD,
        modifier = modifier,
        description = if (drafts > 0) "폰하드, 이 기기 · $summary, 저장된 편집 ${drafts}개 있음. 길게 누르면 보관된 편집을 관리합니다." else "폰하드, 이 기기 · $summary. 길게 누르면 보관된 편집을 관리합니다.",
        onClick = { model.show(Screen.Inbox) },
        onLongClick = model::openPhoneHardLongTouch,
        longClickLabel = "보관된 편집 관리",
    )
}

@Composable
private fun GooglePhotosCell(
    state: AppState,
    model: NasFinderViewModel,
    onEditGooglePhotos: () -> Unit,
    onDeleteGooglePhotos: () -> Unit,
    modifier: Modifier,
) {
    // Only composed while visible (iOS `if !googlePhotosHidden`); a hidden location is restored from the 연결 추가 chooser.
    SourceCell(
        title = state.googlePhotosName,
        detail = "클라우드 사진",
        glyph = PackGlyph.PHOTOS,
        modifier = modifier,
        googleMark = true,
        onClick = model::requestGooglePhotosImport,
        menu = { dismiss ->
            DropdownMenuItem(text = { Text("연결 수정") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { dismiss(); onEditGooglePhotos() })
            DropdownMenuItem(text = { Text("연결 삭제") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { dismiss(); onDeleteGooglePhotos() })
        },
    )
}

/** One grouped card, two cells per row split by hairlines; an odd count leaves the last row single (no blank cell). */
@Composable
private fun SourceGroup(cells: List<@Composable (Modifier) -> Unit>) {
    PorcelainPanel(Modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth >= 560.dp) 2 else 1
            Column(Modifier.fillMaxWidth()) {
                cells.chunked(columns).forEachIndexed { index, group ->
                    if (index > 0) PorcelainDivider(start = 0.dp)
                    if (columns == 1) {
                        group.single()(Modifier.fillMaxWidth())
                    } else {
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                            group.forEachIndexed { cellIndex, cell ->
                                if (cellIndex > 0) PorcelainVerticalHairline()
                                cell(Modifier.weight(1f).fillMaxHeight())
                            }
                            if (group.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 파일 열기: phone shows the primary NAS (other connections stay in 저장된 연결 below); wide lists every saved connection.
 * Hidden Google 포토 contributes no cell; it is restored from the existing 연결 추가 chooser.
 */
@Composable
private fun PorcelainFileOpen(
    state: AppState,
    model: NasFinderViewModel,
    onAddNetwork: () -> Unit,
    onEditGooglePhotos: () -> Unit,
    onDeleteGooglePhotos: () -> Unit,
    onDeleteConnection: (RemoteConnection) -> Unit,
    allConnections: Boolean,
    onImportFiles: (() -> Unit)? = null,
) {
    val nas = primaryNas(state)
    PorcelainSection("파일 열기") {
        SourceGroup(
            buildList<@Composable (Modifier) -> Unit> {
                if (allConnections) {
                    state.connections.forEach { connection -> add { m -> ConnectionCell(connection, state, model, onDeleteConnection, m) } }
                    if (state.connections.isEmpty()) add { m -> SourceCell("NAS 추가", "NAS·서버 연결", PackGlyph.NAS, m, onClick = onAddNetwork) }
                } else add { m ->
                    if (nas != null) ConnectionCell(nas, state, model, onDeleteConnection, m)
                    else SourceCell("NAS 추가", "NAS·서버 연결", PackGlyph.NAS, m, onClick = onAddNetwork)
                }
                add { m -> PhotoLibraryCell(model, m) }
                add { m -> PhoneHardCell(state, model, m) }
                if (state.googlePhotosVisible) add { m -> GooglePhotosCell(state, model, onEditGooglePhotos, onDeleteGooglePhotos, m) }
            }
        )
        PorcelainPanel(Modifier.fillMaxWidth()) {
            if (onImportFiles != null) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    CompactAction("브라우저", PackGlyph.BROWSER, Modifier.weight(1f).fillMaxHeight()) { model.show(Screen.WebBrowser) }
                    PorcelainVerticalHairline()
                    CompactAction("기기 파일", PackGlyph.DEVICE_FILES, Modifier.weight(1f).fillMaxHeight(), onClick = onImportFiles)
                }
                PorcelainDivider()
                CompactAction("네트워크 추가", PackGlyph.ADD, Modifier.fillMaxWidth(), onClick = onAddNetwork)
            } else {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    CompactAction("브라우저", PackGlyph.BROWSER, Modifier.weight(1f).fillMaxHeight()) { model.show(Screen.WebBrowser) }
                    PorcelainVerticalHairline()
                    CompactAction("네트워크 추가", PackGlyph.ADD, Modifier.weight(1f).fillMaxHeight(), onClick = onAddNetwork)
                }
            }
        }
    }
}

/** Thin action cell: 24 art + gap 8 + 14/500 title (two lines if needed) + chevron, minHeight 44. */
@Composable
private fun CompactAction(title: String, glyph: PackGlyph, modifier: Modifier, description: String? = null, onClick: () -> Unit) {
    Row(
        modifier.heightIn(min = 44.dp)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PackIcon(glyph, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
        Text(title, Modifier.weight(1f), style = HomeType.Compact, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Default.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 영상 작업: the board's red 영상 만들기 CTA, then real memory drafts (actual first-scene cover + actual length + 이어서 편집
 * + delete with the manager's confirmation). Zero drafts draw no cover; the 보관한 편집 row always opens the existing
 * unlimited manager.
 */
@Composable
private fun PorcelainVideo(state: AppState, model: NasFinderViewModel) {
    // 새 저장소 없이 기존 TrailerDraftStore.getAllDrafts()(비공개 모드 필터·최신순)를 IO에서 읽는다.
    // 읽기 실패(metadataProblem)는 빈 목록으로 숨기지 않고 그대로 보여 준다.
    val draftList by produceState<Pair<List<TrailerDraft>, String?>?>(null, state.trailerDraftCount, state.trailerDraftsVersion, state.trailer?.saveState) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            TrailerDraftStore.getAllDrafts() to TrailerDraftStore.metadataProblem()
        }
    }
    PorcelainSection("이어서 편집") {
        val loaded = draftList
        val metadataProblem = loaded?.second
        val recent = loaded?.first.orEmpty().take(3)
        if (metadataProblem != null) {
            Text(metadataProblem, style = HomeType.Tertiary, color = MaterialTheme.colorScheme.error)
        } else if (loaded != null && recent.isEmpty()) {
            Text("보관된 편집본이 없습니다", style = HomeType.Tertiary, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        recent.forEach { draft ->
            CreateDraftRow(draft, Modifier.fillMaxWidth()) { model.resumeTrailerDraft(draft) }
        }
        PorcelainPanel(Modifier.fillMaxWidth()) {
            PorcelainRow(
                "편집본 전체",
                PackGlyph.DRAFTS,
                subtitle = "이름 변경 · 삭제",
                trailing = { Text("${loaded?.first?.size ?: state.trailerDraftCount}개", style = HomeType.Tertiary, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                onClick = { model.show(Screen.VideoDrafts) },
            )
        }
    }
}

/** Compact utility rows (전송 · 저장공간 · 설정) with the version caption; no oversized panels. */
@Composable
private fun PorcelainUtilities(model: NasFinderViewModel, onImportFiles: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
        PorcelainPanel(Modifier.fillMaxWidth()) {
            PorcelainRow("사진 선별", PackGlyph.PHOTO_SELECTION, subtitle = "비슷한 사진을 묶고 몇 장이 적당한지 추천") { model.show(Screen.PhotoSelection) }
            PorcelainDivider()
            PorcelainRow("미디어 전송", PackGlyph.TRANSFER, subtitle = "미디어·Live Photos·Motion Photos 기기 간 전송") { model.show(Screen.PhotoTransfer) }
            PorcelainDivider()
            PorcelainDeviceStorageRows(onImportFiles)
            PorcelainDivider()
            PorcelainRow("설정", PackGlyph.SETTINGS) { model.show(Screen.Settings) }
        }
        VersionCaption()
    }
}

/**
 * Live total/available on the secondary line, used percent trailing, real used ratio as a thin bar below.
 * [onImportFiles] null = the static Tools-tab card (no chevron, no percent, no action), as on iOS ToolsView.storageCard.
 */
@Composable
internal fun PorcelainDeviceStorageRows(onImportFiles: (() -> Unit)?) {
    val filesDir = LocalContext.current.filesDir
    val total = filesDir.totalSpace.coerceAtLeast(0L)
    val available = filesDir.usableSpace.coerceIn(0L, total.coerceAtLeast(1L))
    val usedFraction = if (total > 0L) ((total - available).toDouble() / total).coerceIn(0.0, 1.0).toFloat() else 0f
    PorcelainRow(
        "기기 저장공간",
        PackGlyph.STORAGE,
        subtitle = if (total > 0L) "전체 ${porcelainBytes(total)} · 사용 가능 ${porcelainBytes(available)}" else "확인 불가",
        showsChevron = onImportFiles != null,
        trailing = if (total > 0L && onImportFiles != null) ({ Text("${(usedFraction * 100).toInt()}%", style = HomeType.Tertiary, color = MaterialTheme.colorScheme.onSurfaceVariant) }) else null,
        onClick = onImportFiles,
    )
    if (total > 0L) LinearProgressIndicator(
        progress = { usedFraction },
        modifier = Modifier.fillMaxWidth().padding(start = 50.dp, end = if (onImportFiles != null) 38.dp else 12.dp, bottom = 10.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.outlineVariant,
        drawStopIndicator = {},
        gapSize = 0.dp,
    )
}

/**
 * iOS ToolsView.toolsList (도구 tab): 전송 → 저장공간 (device storage, 작업대 참조 수) → 앱 (설정 row + version caption).
 * Every row is an existing destination or read-only count; 설정 stays a row here instead of being the tab itself.
 */
@Composable
internal fun PorcelainTools(state: AppState, model: NasFinderViewModel, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = PhoneOuter, top = 4.dp, end = PhoneOuter, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(SectionGap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Box(Modifier.widthIn(max = 640.dp)) {
                PorcelainSection("도구") {
                    PorcelainPanel(Modifier.fillMaxWidth()) {
                        PorcelainRow("사진 선별", PackGlyph.PHOTO_SELECTION) {
                            model.show(Screen.PhotoSelection)
                        }
                        PorcelainDivider()
                        PorcelainRow("미디어 전송", PackGlyph.TRANSFER) {
                            model.show(Screen.PhotoTransfer)
                        }
                        PorcelainDivider()
                        PorcelainRow("수퍼 썸네일", PackGlyph.SUPER_THUMBNAIL) {
                            model.show(Screen.SuperThumbnail)
                        }
                        PorcelainDivider()
                        PorcelainRow("썸네일 캐시", PackGlyph.THUMBNAIL_CACHE) {
                            model.show(Screen.ThumbnailCache)
                        }
                    }
                }
            }
        }
    }
}




/**
 * iOS VideoHubView (영상 tab): inline title is the shell's, then the two destinations as a picker bar (accent + 3 capsule when selected)
 * over the selected destination. 영상 제작 is the explicit entry into the existing media picker; 편집본 reuses the existing screen.
 * The former 영상 재료 tab is the 작업대 (own top-level destination); its old destination only redirects there.
 */
@Composable
internal fun PorcelainVideoHub(state: AppState, model: NasFinderViewModel, modifier: Modifier = Modifier) {
    var showCreatePocket by remember { mutableStateOf(false) }
    var newPocketName by remember { mutableStateOf("") }

    val workbenchCount = state.materials.size
    // 편집본 목록: 새 저장소 없이 기존 TrailerDraftStore.getAllDrafts()(비공개 모드 필터·최신순)를 IO에서 읽는다.
    // 읽기 실패(metadataProblem)는 빈 목록으로 숨기지 않고 그대로 보여 준다. 이름 변경·삭제는 '편집본 전체'가 맡는다.
    val draftList by produceState<Pair<List<TrailerDraft>, String?>?>(null, state.trailerDraftCount, state.trailerDraftsVersion, state.trailer?.saveState) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            TrailerDraftStore.getAllDrafts() to TrailerDraftStore.metadataProblem()
        }
    }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(PhoneOuter),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item(key = "create-make") {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                PorcelainSection(title = "영상 만들기") {
                    PorcelainPanel(Modifier.fillMaxWidth()) {
                        PorcelainRow("미디어 고르기", PackGlyph.GRID, subtitle = "미디어·폰하드·NAS에서 골라 바로 제작합니다") { model.chooseMediaForVideo() }
                        PorcelainDivider()
                        PorcelainRow(
                            "작업대에서 가져오기",
                            PackGlyph.VIDEO,
                            subtitle = if (workbenchCount == 0) "작업대가 비어 있습니다 · 항목을 담은 뒤 포켓에서 영상 제작" else "포켓에서 항목을 골라 영상 제작 · 참조 ${workbenchCount}개",
                        ) { model.selectMainDestination(MainNavigationDestination.WORKBENCH) }
                        PorcelainDivider()
                        PorcelainRow(
                            "뮤직비디오",
                            PackGlyph.VIDEO,
                            subtitle = "사진·영상에 음악을 입혀 뮤직비디오를 만듭니다",
                        ) { model.show(Screen.LocalPhotoLibrary(LocalMediaEditIntent.MUSIC)) }
                    }
                }
            }
        }

        item(key = "create-drafts-header") {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PorcelainHeader("이어서 편집", "${draftList?.first?.size ?: state.trailerDraftCount}개")
                val metadataProblem = draftList?.second
                if (metadataProblem != null) {
                    Text(metadataProblem, style = HomeType.Tertiary, color = MaterialTheme.colorScheme.error)
                } else if (draftList?.first?.isEmpty() == true) {
                    Text("보관된 편집본이 없습니다", style = HomeType.Tertiary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        draftList?.let { (drafts, _) ->
            items(drafts, key = { it.id }) { draft ->
                CreateDraftRow(draft, Modifier.widthIn(max = 640.dp).fillMaxWidth()) { model.resumeTrailerDraft(draft) }
            }
        }
        item(key = "create-drafts-all") {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                PorcelainPanel(Modifier.fillMaxWidth()) {
                    PorcelainRow(
                        "편집본 전체",
                        PackGlyph.DRAFTS,
                        subtitle = "이름 변경 · 삭제",
                        trailing = { Text("${draftList?.first?.size ?: state.trailerDraftCount}개", style = HomeType.Tertiary, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = { model.show(Screen.VideoDrafts) },
                    )
                }
            }
        }

        item(key = "create-workbench") {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                PorcelainSection(title = "작업대") {
                    PorcelainPanel(Modifier.fillMaxWidth()) {
                        PorcelainRow(
                            title = "포켓 목록",
                            glyph = PackGlyph.WORKBENCH,
                            subtitle = "작업대 화면으로 이동",
                            onClick = { model.selectMainDestination(MainNavigationDestination.WORKBENCH) },
                        )
                        PorcelainDivider()
                        PorcelainRow(
                            title = "포켓 만들기",
                            glyph = PackGlyph.ADD,
                            subtitle = "새 작업대 포켓 생성",
                            onClick = {
                                newPocketName = ""
                                showCreatePocket = true
                            },
                        )
                    }
                }
            }
        }
    }

    if (showCreatePocket) {
        AlertDialog(
            onDismissRequest = { showCreatePocket = false },
            title = { Text("포켓 만들기") },
            text = {
                OutlinedTextField(
                    value = newPocketName,
                    onValueChange = { newPocketName = it },
                    label = { Text("포켓 이름") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.createWorkbenchPocket(newPocketName)
                        model.selectMainDestination(MainNavigationDestination.WORKBENCH)
                        showCreatePocket = false
                    }
                ) {
                    Text("생성")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePocket = false }) {
                    Text("취소")
                }
            }
        )
    }
}

/** Compact draft row on the create screen: 64x48 cover, two-line title, mode · media count. Opens via the existing resume path. */
@Composable
private fun CreateDraftRow(draft: TrailerDraft, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cover = rememberTrailerDraftCover(draft, maxPixelSize = 160)
    PorcelainPanel(modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(width = 64.dp, height = 48.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (cover != null) {
                    Image(cover.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                } else {
                    PackIcon(PackGlyph.VIDEO, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(draft.displayTitle, style = HomeType.Source, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${if (draft.isMusicOverlayMode) "뮤직비디오" else "예고편"} · ${draft.sources.size}개 미디어",
                    style = HomeType.Description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Default.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** iOS porcelainSavedConnections: every connection other than the primary NAS, with its open/menu/order actions. */
@Composable
private fun PorcelainSavedConnections(state: AppState, model: NasFinderViewModel, onDeleteConnection: (RemoteConnection) -> Unit) {
    val primaryId = primaryNas(state)?.id
    val extras = state.connections.filter { it.id != primaryId }
    if (extras.isEmpty()) return
    val preferredName = state.connections.firstOrNull { it.id == state.preferredId }?.name
    PorcelainSection("저장된 연결") {
        PorcelainPanel(Modifier.fillMaxWidth()) {
            extras.forEachIndexed { index, connection ->
                if (index > 0) PorcelainDivider()
                val preferred = state.preferredId == connection.id
                var menuOpen by remember(connection.id) { mutableStateOf(false) }
                Box {
                    PorcelainRow(
                        connection.name,
                        connectionGlyph(connection.kind),
                        connection.host.takeIf { it.isNotBlank() } ?: connection.kind.title,
                        showsChevron = false,
                        onLongClick = { menuOpen = true },
                        trailing = {
                            if (preferred) PackIcon(PackGlyph.FAVORITES, "기본 위치", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Box(
                                Modifier.size(IconHitTarget).clickable(onClickLabel = "${connection.name} 연결 메뉴", role = Role.Button) { menuOpen = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Default.MoreHoriz, "${connection.name} 연결 메뉴", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    ConnectionMenuItems(connection, preferred, model, onDeleteConnection) { menuOpen = false }
                                }
                            }
                        },
                    ) { model.resumeConnection(connection) }
                }
            }
        }
        Text(
            if (preferredName != null) "기본 위치 ‘$preferredName’ · 앱 실행 시 자동으로 열림" else "기본 위치 없음 · 앱 실행 시 연결 목록에서 시작",
            style = HomeType.Tertiary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One sidebar row; [id] is stable across launches so the saved drag order survives (connection ids, fixed keys). */
private class SidebarEntry(
    val id: String,
    val title: String,
    val glyph: PackGlyph,
    val selected: Boolean = false,
    val googleMark: Boolean = false,
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

@Composable
private fun PorcelainSidebar(
    state: AppState,
    model: NasFinderViewModel,
    onAddNetwork: () -> Unit,
    onImportFiles: () -> Unit,
    modifier: Modifier,
    selectedConnectionId: String? = null,
    phoneHardSelected: Boolean = false,
    activeRoot: MainNavigationDestination? = null,
    favoritesSelected: Boolean = false,
    onCollapse: () -> Unit = {},
) {
    val screen = state.screen
    val effectiveSection = sidebarSectionFor(state, selectedConnectionId, phoneHardSelected, activeRoot, favoritesSelected)

    val homeSelected = effectiveSection == MainNavigationDestination.HOME
    val finderSelected = effectiveSection == MainNavigationDestination.FINDER
    val videoSelected = effectiveSection == MainNavigationDestination.VIDEO
    val toolsSelected = effectiveSection == MainNavigationDestination.TOOLS
    val settingsSelected = effectiveSection == MainNavigationDestination.SETTINGS

    Column(
        modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Fixed outside the scrolling menu: brand at left (flexible, one line), 48dp collapse target at right (never shrinks).
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MainAppBrand(state, Modifier.weight(1f).padding(start = 10.dp))
            IconButton(onClick = onCollapse, modifier = Modifier.size(IconHitTarget)) {
                PackIcon(PackGlyph.ARROW_LEFT, "사이드바 접기", Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        // One continuous list (roots with their active children); weight is only the viewport, the whole list scrolls.
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
        SidebarItem(
            "홈",
            PackGlyph.HOME,
            selected = homeSelected,
            onClick = { model.selectMainDestination(MainNavigationDestination.HOME) }
        )

        SidebarItem(
            "파인더",
            PackGlyph.FOLDER,
            selected = finderSelected,
            onClick = { model.selectMainDestination(MainNavigationDestination.FINDER) }
        )
        if (effectiveSection == MainNavigationDestination.FINDER) {
            SidebarChildren(MainNavigationDestination.FINDER, state, model, onAddNetwork, onImportFiles, selectedConnectionId, phoneHardSelected, activeRoot, favoritesSelected)
        }
        SidebarItem(
            "만들기",
            PackGlyph.VIDEO,
            selected = videoSelected,
            onClick = { model.selectMainDestination(MainNavigationDestination.VIDEO) }
        )
        if (effectiveSection == MainNavigationDestination.VIDEO) {
            SidebarChildren(MainNavigationDestination.VIDEO, state, model, onAddNetwork, onImportFiles, selectedConnectionId, phoneHardSelected, activeRoot, favoritesSelected)
        }
        SidebarItem(
            "도구",
            PackGlyph.TRANSFER,
            selected = toolsSelected,
            onClick = { model.selectMainDestination(MainNavigationDestination.TOOLS) }
        )
        if (effectiveSection == MainNavigationDestination.TOOLS) {
            SidebarChildren(MainNavigationDestination.TOOLS, state, model, onAddNetwork, onImportFiles, selectedConnectionId, phoneHardSelected, activeRoot, favoritesSelected)
        }
        SidebarItem(
            "설정",
            PackGlyph.SETTINGS,
            selected = settingsSelected,
            onLongClick = model::togglePrivacyMode,
            onClick = { model.selectMainDestination(MainNavigationDestination.SETTINGS) }
        )
        }
    }
}

/** Section (root) a screen belongs to; shared by the expanded sidebar and the collapsed rail so both agree. */
private fun sidebarSectionFor(
    state: AppState,
    selectedConnectionId: String?,
    phoneHardSelected: Boolean,
    activeRoot: MainNavigationDestination?,
    favoritesSelected: Boolean,
): MainNavigationDestination {
    val screen = state.screen
    return when {
        screen == Screen.Settings -> MainNavigationDestination.SETTINGS
        screen == Screen.PhotoSelection || screen == Screen.PhotoTransfer ||
            screen == Screen.SuperThumbnail || screen == Screen.SuperThumbnailFolderPicker ||
            screen == Screen.SuperThumbnailProgress || screen == Screen.SuperThumbnailReport ||
            screen == Screen.ThumbnailCache -> MainNavigationDestination.TOOLS
        screen == Screen.MaterialBasket || screen == Screen.VideoDrafts ||
            (screen is Screen.LocalPhotoLibrary && (screen.initialIntent == LocalMediaEditIntent.TRAILER || screen.initialIntent == LocalMediaEditIntent.MUSIC)) ||
            activeRoot == MainNavigationDestination.WORKBENCH -> MainNavigationDestination.VIDEO
        screen is Screen.Browser || screen == Screen.Favorites || screen == Screen.Inbox ||
            screen == Screen.WebHard || screen == Screen.WebBrowser ||
            screen == Screen.GooglePhotos || (screen is Screen.LocalPhotoLibrary && screen.initialIntent != LocalMediaEditIntent.TRAILER && screen.initialIntent != LocalMediaEditIntent.MUSIC) ||
            selectedConnectionId != null || phoneHardSelected || favoritesSelected -> MainNavigationDestination.FINDER
        activeRoot != null -> if (activeRoot == MainNavigationDestination.WORKBENCH) MainNavigationDestination.VIDEO else activeRoot
        state.activeMainTab == MainNavigationDestination.WORKBENCH -> MainNavigationDestination.VIDEO
        else -> state.activeMainTab
    }
}

/** Child rows of one accordion [group] (a root's payload, unchanged); the caller shows it only under the active root. */
@Composable
private fun SidebarChildren(
    group: MainNavigationDestination,
    state: AppState,
    model: NasFinderViewModel,
    onAddNetwork: () -> Unit,
    onImportFiles: () -> Unit,
    selectedConnectionId: String?,
    phoneHardSelected: Boolean,
    activeRoot: MainNavigationDestination?,
    favoritesSelected: Boolean,
) {
    val screen = state.screen
    run {
        if (group == MainNavigationDestination.FINDER) {
            ReorderableSidebarGroup(
                "finder",
                buildList {
                    add(SidebarEntry("favorites", "즐겨찾기", PackGlyph.FAVORITES, favoritesSelected || screen == Screen.Favorites) {
                        model.show(Screen.Favorites)
                    })
                    add(SidebarEntry(
                        "my-photos",
                        "내 미디어",
                        PackGlyph.PHOTOS,
                        screen is Screen.LocalPhotoLibrary && screen.initialIntent == LocalMediaEditIntent.NONE && !screen.googlePhotosSource
                    ) {
                        model.show(Screen.LocalPhotoLibrary())
                    })
                    add(SidebarEntry("phonehard", "폰하드", PackGlyph.PHONEHARD, phoneHardSelected || screen == Screen.Inbox, onLongClick = model::openPhoneHardLongTouch) {
                        model.show(Screen.Inbox)
                    })
                    add(SidebarEntry("device-storage", "기기 파일", PackGlyph.DEVICE_FILES, onClick = onImportFiles))

                    state.connections.forEach { connection ->
                        add(
                            SidebarEntry(
                                "connection:${connection.id}",
                                connection.name,
                                connectionGlyph(connection.kind),
                                connection.id.toString() == selectedConnectionId
                            ) {
                                model.resumeConnection(connection)
                            }
                        )
                    }

                    if (state.googlePhotosVisible) {
                        add(SidebarEntry(
                            "google-photos",
                            state.googlePhotosName,
                            PackGlyph.PHOTOS,
                            screen == Screen.GooglePhotos,
                            googleMark = true,
                            onClick = model::requestGooglePhotosImport
                        ))
                    }

                    add(SidebarEntry("add-network", "연결 추가·관리", PackGlyph.ADD, onClick = onAddNetwork))
                    add(SidebarEntry("browser", "브라우저", PackGlyph.BROWSER, screen == Screen.WebBrowser) {
                        model.show(Screen.WebBrowser)
                    })
                }
            )
        }

        if (group == MainNavigationDestination.VIDEO) {
            val isCreateSelected = screen is Screen.LocalPhotoLibrary &&
                (screen.initialIntent == LocalMediaEditIntent.TRAILER || screen.initialIntent == LocalMediaEditIntent.MUSIC)
            val isWorkbenchSelected = activeRoot == MainNavigationDestination.WORKBENCH || screen == Screen.MaterialBasket
            val isDraftsSelected = screen == Screen.VideoDrafts || (screen == Screen.Dashboard && state.activeVideoTab == VideoNavigationDestination.DRAFTS)

            ReorderableSidebarGroup(
                "video",
                listOf(
                    SidebarEntry("video-choose-media", "미디어 고르기", PackGlyph.PHOTOS, isCreateSelected) {
                        model.chooseMediaForVideo()
                    },
                    SidebarEntry("video-workbench", "작업대", PackGlyph.WORKBENCH, isWorkbenchSelected) {
                        model.selectMainDestination(MainNavigationDestination.WORKBENCH)
                    },
                    SidebarEntry("video-drafts", "편집본 전체", PackGlyph.DRAFTS, isDraftsSelected) {
                        model.selectVideoDestination(VideoNavigationDestination.DRAFTS)
                    }
                )
            )
        }

        if (group == MainNavigationDestination.TOOLS) {
            val isSuperThumbnailSelected = screen == Screen.SuperThumbnail ||
                screen == Screen.SuperThumbnailFolderPicker ||
                screen == Screen.SuperThumbnailProgress ||
                screen == Screen.SuperThumbnailReport

            ReorderableSidebarGroup(
                "tools",
                listOf(
                    SidebarEntry("photo-selection", "사진 선별", PackGlyph.PHOTO_SELECTION, screen == Screen.PhotoSelection) {
                        model.show(Screen.PhotoSelection)
                    },
                    SidebarEntry("photo-transfer", "미디어 전송", PackGlyph.TRANSFER, screen == Screen.PhotoTransfer) {
                        model.show(Screen.PhotoTransfer)
                    },
                    SidebarEntry("super-thumbnail", "수퍼 썸네일", PackGlyph.SUPER_THUMBNAIL, isSuperThumbnailSelected) {
                        model.show(Screen.SuperThumbnail)
                    },
                    SidebarEntry("thumbnail-cache", "썸네일 캐시", PackGlyph.THUMBNAIL_CACHE, screen == Screen.ThumbnailCache) {
                        model.show(Screen.ThumbnailCache)
                    }
                )
            )
        }
    }
}


private val SidebarRowHeight = 46.dp
private val SidebarRowGap = 2.dp

/**
 * A sidebar group whose rows can be reordered by pressing a row and dragging it (no visible handle). A brief tap still opens
 * the row. A stationary hold that is released without moving runs the row's own long-press action (설정 = 프라이버시 모드,
 * 폰하드 menu), so the privacy entry and the reorder gesture are told apart by motion. Rows only move inside their own
 * group; the order is saved per group and applied by id, so new/removed connections never disturb the others. Screen
 * readers get 위로/아래로 이동 actions.
 */
@Composable
private fun ReorderableSidebarGroup(group: String, entries: List<SidebarEntry>) {
    val settings = (LocalContext.current.applicationContext as com.armsone.nasfinder.NasFinderApplication).settings
    var saved by remember(group) { mutableStateOf(settings.menuOrder(group)) }
    val ordered = remember(entries.map { it.id }, saved) {
        entries.sortedBy { entry -> saved.indexOf(entry.id).let { if (it < 0) Int.MAX_VALUE else it } }
    }
    var working by remember { mutableStateOf<List<String>?>(null) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    // 끌기/길게 누르기가 끝난 직후 손을 뗄 때 생기는 탭이 행을 열지 않도록 잠깐 막는다.
    var suppressClickUntil by remember { mutableStateOf(0L) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val touchSlop = androidx.compose.ui.platform.LocalViewConfiguration.current.touchSlop
    val stepPx = with(androidx.compose.ui.platform.LocalDensity.current) { (SidebarRowHeight + SidebarRowGap).toPx() }
    val byId = entries.associateBy { it.id }
    val shownIds = working ?: ordered.map { it.id }
    val latestShownIds by rememberUpdatedState(shownIds)
    val commit: (List<String>) -> Unit = { ids ->
        saved = ids
        settings.setMenuOrder(group, ids)
    }
    fun moveBy(ids: List<String>, id: String, delta: Int): List<String> {
        val from = ids.indexOf(id)
        val to = (from + delta).coerceIn(0, ids.lastIndex)
        if (from < 0 || from == to) return ids
        return ids.toMutableList().also { it.add(to, it.removeAt(from)) }
    }
    // Row centres measured from the real layout (before any drag translation) so the tree guide follows font scaling.
    val rowCenters = remember(group) { mutableStateMapOf<String, Float>() }
    val guideColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f)
    Column(
        Modifier.drawBehind {
            val lastCenter = shownIds.mapNotNull { rowCenters[it] }.maxOrNull() ?: return@drawBehind
            val x = 23.dp.toPx()
            val stroke = 1.dp.toPx()
            drawLine(guideColor, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, lastCenter), stroke)
            shownIds.forEach { shown ->
                val y = rowCenters[shown] ?: return@forEach
                drawLine(guideColor, androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Offset(34.dp.toPx(), y), stroke)
            }
        },
        verticalArrangement = Arrangement.spacedBy(SidebarRowGap)
    ) {
        shownIds.forEach { id ->
            val entry = byId[id] ?: return@forEach
            key(id) {
                val dragging = draggingId == id
                val currentEntry by rememberUpdatedState(entry)
                SidebarItem(
                    entry.title, entry.glyph, entry.selected, entry.googleMark,
                    // 길게 누르기는 아래 제스처가 직접 처리한다(움직였으면 순서 바꾸기, 가만히 뗐으면 행 고유의 길게 누르기 동작).
                    onLongClick = null,
                    onClick = { if (android.os.SystemClock.uptimeMillis() >= suppressClickUntil) entry.onClick() },
                    child = true,
                    modifier = Modifier
                        .onGloballyPositioned { rowCenters[id] = it.positionInParent().y + it.size.height / 2f }
                        .padding(start = 24.dp)
                        .graphicsLayer { translationY = if (dragging) dragOffset else 0f; shadowElevation = if (dragging) 8f else 0f }
                        .zIndex(if (dragging) 1f else 0f)
                        .then(if (dragging) Modifier.border(1.5.dp, themePalette().accent, RoundedCornerShape(10.dp)) else Modifier)
                        .semantics {
                            customActions = listOf(
                                CustomAccessibilityAction("위로 이동") { commit(moveBy(shownIds, id, -1)); true },
                                CustomAccessibilityAction("아래로 이동") { commit(moveBy(shownIds, id, 1)); true },
                            )
                        }
                        .pointerInput(id) {
                            var accumulated = androidx.compose.ui.geometry.Offset.Zero
                            var reordering = false
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    suppressClickUntil = android.os.SystemClock.uptimeMillis() + 1_000L
                                    accumulated = androidx.compose.ui.geometry.Offset.Zero
                                    reordering = false
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    if (!reordering) {
                                        accumulated += amount
                                        // 손가락이 touchSlop 넘게 움직여야 순서 바꾸기가 시작된다(가만히 누른 채면 길게 누르기 동작).
                                        if (accumulated.getDistance() > touchSlop) {
                                            reordering = true
                                            working = latestShownIds; draggingId = id; dragOffset = 0f
                                        }
                                        return@detectDragGesturesAfterLongPress
                                    }
                                    dragOffset += amount.y
                                    val current = working ?: return@detectDragGesturesAfterLongPress
                                    // Swap with the neighbour once the row has travelled half a step; the offset is re-based so it keeps following the finger.
                                    if (dragOffset > stepPx / 2f && current.indexOf(id) < current.lastIndex) {
                                        working = moveBy(current, id, 1); dragOffset -= stepPx
                                    } else if (dragOffset < -stepPx / 2f && current.indexOf(id) > 0) {
                                        working = moveBy(current, id, -1); dragOffset += stepPx
                                    }
                                },
                                onDragEnd = {
                                    if (reordering) working?.let(commit) else currentEntry.onLongClick?.invoke()
                                    working = null; draggingId = null; dragOffset = 0f; reordering = false
                                    suppressClickUntil = android.os.SystemClock.uptimeMillis() + 400L
                                },
                                onDragCancel = {
                                    working = null; draggingId = null; dragOffset = 0f; reordering = false
                                    suppressClickUntil = android.os.SystemClock.uptimeMillis() + 400L
                                },
                            )
                        },
                )
            }
        }
    }
}

@Composable
private fun SidebarDivider() {
    HorizontalDivider(Modifier.padding(vertical = 6.dp, horizontal = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f))
}

@Composable
private fun SidebarItem(
    title: String,
    glyph: PackGlyph,
    selected: Boolean = false,
    googleMark: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    child: Boolean = false,
) {
    val shape = RoundedCornerShape(10.dp)
    val iconSize = if (child) 20.dp else 26.dp
    Row(
        modifier.fillMaxWidth().heightIn(min = SidebarRowHeight).clip(shape)
            .background(if (selected) themePalette().selected else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (googleMark) GooglePhotosMark(Modifier.size(iconSize)) else PackIcon(glyph, null, Modifier.size(iconSize), tint = if (selected) themePalette().accent else MaterialTheme.colorScheme.onSurface)
        Text(
            title,
            Modifier.weight(1f),
            style = if (child) MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp) else MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            // Selected rows keep the primary text colour on the selected fill; the accent marks only the glyph.
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke()
    }
}

@Composable
private fun PorcelainCompactRail(
    state: AppState,
    model: NasFinderViewModel,
    onExpand: () -> Unit,
    selectedConnectionId: String? = null,
    phoneHardSelected: Boolean = false,
    activeRoot: MainNavigationDestination? = null,
    favoritesSelected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val section = sidebarSectionFor(state, selectedConnectionId, phoneHardSelected, activeRoot, favoritesSelected)

    Column(
        modifier = modifier
            .width(72.dp)
            .fillMaxHeight()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Fixed outside the scrolling list: selected app icon, then the expand target, so neither overlaps or clips.
        MainAppBrand(state, showText = false)
        IconButton(onClick = onExpand, modifier = Modifier.size(IconHitTarget)) {
            PackIcon(PackGlyph.ARROW_RIGHT, "사이드바 펼치기", Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(4.dp))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
        CompactRailItem("홈", PackGlyph.HOME, selected = section == MainNavigationDestination.HOME) {
            model.selectMainDestination(MainNavigationDestination.HOME)
        }
        CompactRailItem("파인더", PackGlyph.FOLDER, selected = section == MainNavigationDestination.FINDER) {
            model.selectMainDestination(MainNavigationDestination.FINDER)
        }
        CompactRailItem("만들기", PackGlyph.VIDEO, selected = section == MainNavigationDestination.VIDEO) {
            model.selectMainDestination(MainNavigationDestination.VIDEO)
        }
        CompactRailItem("도구", PackGlyph.TRANSFER, selected = section == MainNavigationDestination.TOOLS) {
            model.selectMainDestination(MainNavigationDestination.TOOLS)
        }
        CompactRailItem(
            "설정", PackGlyph.SETTINGS, selected = section == MainNavigationDestination.SETTINGS,
            onLongClick = model::togglePrivacyMode,
        ) {
            model.selectMainDestination(MainNavigationDestination.SETTINGS)
        }
        }
    }
}

@Composable
private fun CompactRailItem(
    label: String,
    glyph: PackGlyph,
    selected: Boolean,
    badge: Int? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val accent = themePalette().accent
    val color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant
    val bg = if (selected) themePalette().selected else Color.Transparent
    Column(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .semantics { this.selected = selected }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Tab),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            PackIcon(glyph, null, Modifier.size(24.dp), tint = color)
            if (badge != null && badge > 0) {
                Box(
                    Modifier
                        .offset(x = 6.dp, y = (-4).dp)
                        .background(accent, RoundedCornerShape(99.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text("$badge", style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}
