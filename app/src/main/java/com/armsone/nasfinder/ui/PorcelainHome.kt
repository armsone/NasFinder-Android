@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.armsone.nasfinder.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.armsone.nasfinder.BuildConfig
import com.armsone.nasfinder.model.ConnectionKind
import com.armsone.nasfinder.model.RemoteConnection
import com.armsone.nasfinder.model.RemoteFavorite
import com.armsone.nasfinder.ui.theme.HomeType
import com.armsone.nasfinder.ui.theme.themePalette

/** Breakpoint at which the home keeps a persistent sidebar; below it the phone hub is a single column. */
private val SidebarMinWidth = 600.dp
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
        val totalWidth = maxWidth
        val wide = totalWidth >= SidebarMinWidth
        val sidebarWidth = sidebarWidthFor(totalWidth)
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

/** Keeps the compact location sidebar beside file browsers on wide windows; narrow windows render [content] untouched. */
@Composable
internal fun PorcelainWideHost(
    state: AppState,
    model: NasFinderViewModel,
    selectedConnectionId: String? = null,
    phoneHardSelected: Boolean = false,
    fullscreen: Boolean = false,
    content: @Composable () -> Unit,
) {
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { model.importPickedFiles(it) }
    var addingNetwork by remember { mutableStateOf(false) }
    var editingGooglePhotos by remember { mutableStateOf(false) }
    var googlePhotosName by remember(state.googlePhotosName) { mutableStateOf(state.googlePhotosName) }
    NetworkAddDialogs(state, model, addingNetwork, { addingNetwork = it }, editingGooglePhotos, { editingGooglePhotos = it }, googlePhotosName, { googlePhotosName = it })
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Every theme shares the unified layout; only the width decides whether the sidebar is shown.
        if (maxWidth < SidebarMinWidth) {
            content()
        } else {
            Row(Modifier.fillMaxSize()) {
                // Only the sidebar is skipped when fullscreen; the content slot keeps its position so its state survives.
                if (!fullscreen) {
                    PorcelainSidebar(
                        state, model, { addingNetwork = true }, { importer.launch(arrayOf("*/*")) },
                        Modifier.width(sidebarWidthFor(this@BoxWithConstraints.maxWidth)).fillMaxHeight().navigationBarsPadding(), selectedConnectionId, phoneHardSelected,
                    )
                    VerticalDividerLine()
                }
                Box(Modifier.weight(1f).fillMaxHeight()) { content() }
            }
        }
    }
}

/** Contract proposal: sidebar ≈ 20–22 % of the window, 200…280. */
private fun sidebarWidthFor(total: Dp): Dp = (total * .21f).coerceIn(200.dp, 280.dp)

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
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button)
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
) {
    // No favorites-manager route exists, so the board's "전체 보기" is not drawn (no new navigation).
    PorcelainSection("즐겨찾기") {
        if (state.remoteFavorites.isNotEmpty()) {
            FavoriteShelf(
                favorites = state.remoteFavorites,
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
    SourceCell("내 사진·영상", "이 기기의 사진과 영상", PackGlyph.PHOTOS, modifier, onClick = { model.show(Screen.LocalPhotoLibrary()) })
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
        cells.chunked(2).forEachIndexed { index, pair ->
            if (index > 0) PorcelainDivider(start = 0.dp)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                pair.forEachIndexed { cellIndex, cell ->
                    if (cellIndex > 0) PorcelainVerticalHairline()
                    cell(Modifier.weight(1f).fillMaxHeight())
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
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
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                CompactAction("Browser", PackGlyph.BROWSER, Modifier.weight(1f).fillMaxHeight()) { model.show(Screen.WebBrowser) }
                PorcelainVerticalHairline()
                CompactAction("네트워크 추가", PackGlyph.ADD, Modifier.weight(1f).fillMaxHeight(), onClick = onAddNetwork)
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
    PorcelainSection("영상") {
        Surface(
            onClick = { model.selectVideoDestination(VideoNavigationDestination.CREATE) },
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.onPrimary, CircleShape), contentAlignment = Alignment.Center) {
                    PackIcon(PackGlyph.VIDEO, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text("영상 제작", Modifier.weight(1f), style = HomeType.Cta, color = MaterialTheme.colorScheme.onPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
        PorcelainPanel(Modifier.fillMaxWidth()) {
            PorcelainRow(
                "재료함",
                PackGlyph.GRID,
                subtitle = "영상에 쓸 미디어 모음",
                trailing = { Text("${state.materials.size}개", style = HomeType.Tertiary, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                onClick = { model.selectVideoDestination(VideoNavigationDestination.MATERIALS) },
            )
            PorcelainDivider()
            PorcelainRow(
                "편집본",
                PackGlyph.DRAFTS,
                subtitle = "저장된 영상 프로젝트",
                trailing = { Text("${state.trailerDraftCount}개", style = HomeType.Tertiary, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                onClick = { model.selectVideoDestination(VideoNavigationDestination.DRAFTS) },
            )
            PorcelainDivider()
            PorcelainRow(
                "컬렉션",
                PackGlyph.VIDEO,
                subtitle = "완성된 영상 및 재생 보관소",
                trailing = { Text("${state.collections.size}개", style = HomeType.Tertiary, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                onClick = { model.selectVideoDestination(VideoNavigationDestination.COLLECTIONS) },
            )
        }
    }
}

/** Compact utility rows (전송 · 저장공간 · 설정) with the version caption; no oversized panels. */
@Composable
private fun PorcelainUtilities(model: NasFinderViewModel, onImportFiles: () -> Unit) {
    val filesDir = LocalContext.current.filesDir
    val total = filesDir.totalSpace.coerceAtLeast(0L)
    val available = filesDir.usableSpace.coerceIn(0L, total.coerceAtLeast(1L))
    val usedFraction = if (total > 0L) ((total - available).toDouble() / total).coerceIn(0.0, 1.0).toFloat() else 0f
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
        PorcelainPanel(Modifier.fillMaxWidth()) {
            PorcelainRow("기기 간 사진 전송", PackGlyph.TRANSFER, subtitle = "Live Photos·Motion Photos 함께 보내기") { model.show(Screen.PhotoTransfer) }
            PorcelainDivider()
            // Live total/available on the secondary line, used percent trailing, real used ratio as a thin bar below.
            PorcelainRow(
                "기기 저장공간",
                PackGlyph.STORAGE,
                subtitle = if (total > 0L) "전체 ${porcelainBytes(total)} · 사용 가능 ${porcelainBytes(available)}" else "확인 불가",
                trailing = if (total > 0L) ({ Text("${(usedFraction * 100).toInt()}%", style = HomeType.Tertiary, color = MaterialTheme.colorScheme.onSurfaceVariant) }) else null,
                onClick = onImportFiles,
            )
            if (total > 0L) LinearProgressIndicator(
                progress = { usedFraction },
                modifier = Modifier.fillMaxWidth().padding(start = 50.dp, end = 38.dp, bottom = 10.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
                drawStopIndicator = {},
                gapSize = 0.dp,
            )
            PorcelainDivider()
            PorcelainRow("설정", PackGlyph.SETTINGS) { model.show(Screen.Settings) }
        }
        VersionCaption()
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
                                Modifier.size(36.dp).clickable(onClickLabel = "${connection.name} 연결 메뉴", role = Role.Button) { menuOpen = true },
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

@Composable
private fun PorcelainSidebar(
    state: AppState,
    model: NasFinderViewModel,
    onAddNetwork: () -> Unit,
    onImportFiles: () -> Unit,
    modifier: Modifier,
    selectedConnectionId: String? = null,
    phoneHardSelected: Boolean = false,
) {
    val homeSelected = selectedConnectionId == null && !phoneHardSelected
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SidebarItem("홈", PackGlyph.HOME, selected = homeSelected) { if (!homeSelected) model.show(Screen.Dashboard) }
        if (state.connections.isEmpty()) SidebarItem("NAS·서버 추가", PackGlyph.NAS, onClick = onAddNetwork)
        state.connections.forEach { connection ->
            SidebarItem(connection.name, connectionGlyph(connection.kind), selected = connection.id.toString() == selectedConnectionId) { model.resumeConnection(connection) }
        }
        SidebarItem("내 사진·영상", PackGlyph.PHOTOS) { model.show(Screen.LocalPhotoLibrary()) }
        SidebarItem("폰하드", PackGlyph.PHONEHARD, selected = phoneHardSelected, onLongClick = model::openPhoneHardLongTouch) { model.show(Screen.Inbox) }
        if (state.googlePhotosVisible) SidebarItem(state.googlePhotosName, PackGlyph.PHOTOS, googleMark = true, onClick = model::requestGooglePhotosImport)
        SidebarDivider()
        SidebarItem("영상 제작", PackGlyph.VIDEO) { model.selectVideoDestination(VideoNavigationDestination.CREATE) }
        SidebarItem("재료함", PackGlyph.GRID) { model.selectVideoDestination(VideoNavigationDestination.MATERIALS) }
        SidebarItem("편집본", PackGlyph.DRAFTS) { model.selectVideoDestination(VideoNavigationDestination.DRAFTS) }
        SidebarItem("컬렉션", PackGlyph.VIDEO) { model.selectVideoDestination(VideoNavigationDestination.COLLECTIONS) }
        SidebarDivider()
        SidebarItem("기기 간 사진 전송", PackGlyph.TRANSFER) { model.show(Screen.PhotoTransfer) }
        SidebarItem("기기 저장공간", PackGlyph.STORAGE, onClick = onImportFiles)
        SidebarDivider()
        SidebarItem("브라우저", PackGlyph.BROWSER) { model.show(Screen.WebBrowser) }
        SidebarItem("네트워크 추가", PackGlyph.ADD, onClick = onAddNetwork)
        SidebarItem("설정", PackGlyph.SETTINGS) { model.show(Screen.Settings) }
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
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(shape)
            .background(if (selected) themePalette().selected else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (googleMark) GooglePhotosMark(Modifier.size(26.dp)) else PackIcon(glyph, null, Modifier.size(26.dp), tint = if (selected) themePalette().accent else MaterialTheme.colorScheme.onSurface)
        Text(
            title,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            // Selected rows keep the primary text colour on the selected fill; the accent marks only the glyph.
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
