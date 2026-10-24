package com.armsone.nasfinder.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.armsone.nasfinder.ui.theme.themePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 작업대: several named pockets of REFERENCES (location / identity / permission / metadata). Nothing here copies an
 * original. 영상 제작 and 미디어 전송 are explicit actions on the selected references of the pocket on screen; removing a
 * reference or deleting a pocket removes references only.
 */
@Composable
internal fun WorkbenchScreen(
    state: AppState,
    model: NasFinderViewModel,
    // Embedded under the dashboard's own title / tab bar: no own title, back arrow or system insets.
    embedded: Boolean = true,
    onBack: () -> Unit = {},
) {
    LaunchedEffect(Unit) { model.refreshMaterials() }
    val palette = themePalette()
    val pockets = state.pockets
    val pocket = pockets.firstOrNull { it.id == state.workbenchPocketId } ?: pockets.first()
    val items = remember(state.materials, pocket.id, state.pockets) {
        state.materials.filter { item -> (if (pockets.any { it.id == item.pocketId }) item.pocketId else DEFAULT_POCKET_ID) == pocket.id }
    }
    val selectedItems = items.filter { it.id in state.workbenchSelected && it.state != MaterialPreparationState.EXCLUDED }
    val allSelected = items.isNotEmpty() && items.all { it.id in state.workbenchSelected }
    var previewItem by remember { mutableStateOf<MaterialItem?>(null) }
    var creatingPocket by remember { mutableStateOf(false) }
    var renamingPocket by remember { mutableStateOf(false) }
    var deletingPocket by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var filesOrFolder by remember { mutableStateOf(false) }
    var finderSources by remember { mutableStateOf(false) }
    var pocketMenu by remember { mutableStateOf(false) }
    val pickers = rememberWorkbenchPickers(model)
    var reconnecting by remember { mutableStateOf<MaterialItem?>(null) }
    val reconnectFile = rememberLauncherForActivityResult(PersistableOpenDocuments(multiple = false)) { uris ->
        val target = reconnecting
        reconnecting = null
        val uri = uris.firstOrNull()
        if (uri != null && target != null) model.reconnectLocalReference(target, uri)
    }
    val reconnectTree = rememberLauncherForActivityResult(PersistableOpenTree()) { uri ->
        val target = reconnecting
        reconnecting = null
        if (uri != null && target != null) model.reconnectFolderReference(target, uri)
    }
    val reconnectGoogle = rememberLauncherForActivityResult(GooglePhotosAppPickContract()) { uris ->
        val target = reconnecting
        reconnecting = null
        uris.firstOrNull()?.let { uri -> if (target != null) model.reconnectLocalReference(target, uri) }
    }
    val startReconnect: (MaterialItem) -> Unit = { item ->
        when {
            item.sourceType == MaterialSourceType.REMOTE_FILE -> model.reconnectRemoteReference(item)
            item.sourceType == MaterialSourceType.PHONE_HARD -> model.recheckMaterial(item)
            item.isFolder -> { reconnecting = item; reconnectTree.launch(Unit) }
            item.origin == "GOOGLE_PHOTOS" && pickers.googlePhotosAvailable -> { reconnecting = item; reconnectGoogle.launch(Unit) }
            else -> { reconnecting = item; reconnectFile.launch(arrayOf("*/*")) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (!embedded) PorcelainTopBar(title = "작업대", onBack = onBack)
        // Pocket chips: unlimited, never evicted automatically.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pockets.forEach { p ->
                FilterChip(
                    selected = p.id == pocket.id,
                    onClick = { model.selectWorkbenchPocket(p.id) },
                    label = { Text("${p.name} ${state.materials.count { item -> (if (pockets.any { it.id == item.pocketId }) item.pocketId else DEFAULT_POCKET_ID) == p.id }}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
            AssistChip(onClick = { creatingPocket = true }, label = { Text("＋ 새 포켓") })
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                OutlinedButton(onClick = { addMenu = true }) {
                    PackIcon(PackGlyph.ADD, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(6.dp))
                    Text("담기")
                }
                // The same three top-level choices as 전송; folders are picked from inside 파일.
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(text = { Text("미디어") }, onClick = { addMenu = false; pickers.pickMedia() })
                    DropdownMenuItem(text = { Text("Google 포토") }, onClick = { addMenu = false; pickers.pickGooglePhotos() })
                    DropdownMenuItem(text = { Text("파일") }, onClick = { addMenu = false; filesOrFolder = true })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("파인더에서 담기 (NAS·폰하드 등)") }, onClick = { addMenu = false; finderSources = true })
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { model.setWorkbenchSelected(if (allSelected) state.workbenchSelected - items.map { it.id }.toSet() else state.workbenchSelected + items.filter { it.state != MaterialPreparationState.EXCLUDED }.map { it.id }) }, enabled = items.isNotEmpty()) {
                Text(if (allSelected) "전체 해제" else "전체 선택", fontSize = 13.sp)
            }
            Box {
                IconButton(onClick = { pocketMenu = true }) { Icon(Icons.Default.MoreVert, "포켓 메뉴") }
                DropdownMenu(expanded = pocketMenu, onDismissRequest = { pocketMenu = false }) {
                    DropdownMenuItem(text = { Text("포켓 이름 바꾸기") }, onClick = { pocketMenu = false; renamingPocket = true })
                    // 이 앱의 목록에서만 숨긴다(잠금·암호화 아님). 일반 모드에서 비공개로 바꾸면 이 포켓은 곧바로 목록에서 사라진다.
                    if (pocket.id != DEFAULT_POCKET_ID) DropdownMenuItem(
                        text = { Text(if (pocket.isPrivate) "공개로 전환" else "비공개로 전환") },
                        onClick = { pocketMenu = false; model.setPocketPrivate(pocket.id, !pocket.isPrivate) },
                    )
                    if (pocket.id != DEFAULT_POCKET_ID) {
                        DropdownMenuItem(text = { Text("포켓 삭제 (참조만)") }, onClick = { pocketMenu = false; deletingPocket = true })
                    }
                }
            }
        }
        Text(
            "${pocket.name} · 항목 ${items.size}개 · ${selectedItems.size}개 선택 · 원본은 복사하지 않고 위치만 담습니다",
            Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DurableMaterialStore.loadProblem()?.let { problem ->
            Text(problem, Modifier.padding(horizontal = 14.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (items.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.Inbox,
                    title = if (DurableMaterialStore.loadProblem() != null) "작업대 목록을 불러오지 못했습니다" else "‘${pocket.name}’ 포켓이 비어 있습니다",
                    description = "담기에서 파일이나 폴더를 골라 이 포켓에 담으세요. 원본은 그대로 보관됩니다.",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 12.dp, top = 4.dp),
                ) {
                    itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                        WorkbenchItemRow(
                            item = item,
                            selected = item.id in state.workbenchSelected,
                            onToggle = { model.toggleWorkbenchSelected(item.id) },
                            onPreview = { previewItem = item },
                            onRemove = { model.removeMaterialItem(item.id) },
                            onReconnect = { startReconnect(item) },
                            onRecheck = { model.recheckMaterial(item) },
                            onExclude = { model.setMaterialExcluded(item.id, item.state != MaterialPreparationState.EXCLUDED) },
                            onMoveUp = if (index > 0) ({ model.moveWorkbenchItem(pocket.id, index, index - 1) }) else null,
                            onMoveDown = if (index < items.lastIndex) ({ model.moveWorkbenchItem(pocket.id, index, index + 1) }) else null,
                            onTogglePrivate = if (pocket.isPrivate) null else ({ model.setMaterialPrivate(item.id, !item.isPrivate) }),
                        )
                    }
                }
            }
        }
        // Explicit run actions: nothing starts until one of these is pressed.
        Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // The actions only run on the selection; say why they are off instead of leaving two silent disabled buttons.
            if (selectedItems.isEmpty()) {
                Text(
                    if (items.isEmpty()) "항목을 담으면 영상 제작과 미디어 전송을 쓸 수 있습니다." else "항목을 선택하면 영상 제작과 미디어 전송을 쓸 수 있습니다. 선택한 항목만 사용합니다.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { model.openWorkbenchSend(pocket.id) },
                    enabled = selectedItems.isNotEmpty() && state.workbenchRun == null,
                    modifier = Modifier.weight(1f),
                ) {
                    PackIcon(PackGlyph.TRANSFER, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(6.dp))
                    Text("미디어 전송 (${selectedItems.size})", maxLines = 1)
                }
                Button(
                    onClick = { model.startWorkbenchVideo(selectedItems) },
                    enabled = selectedItems.isNotEmpty() && state.workbenchRun == null,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.solidAction, contentColor = palette.onAccent),
                ) {
                    PackIcon(PackGlyph.VIDEO, null, Modifier.size(18.dp), tint = palette.onAccent)
                    Spacer(Modifier.width(6.dp))
                    Text("영상 제작 (${selectedItems.size})", maxLines = 1)
                }
            }
            }
        }
    }

    if (creatingPocket) PocketNameDialog("새 포켓", "", "만들기", onDismiss = { creatingPocket = false }) { model.createWorkbenchPocket(it); creatingPocket = false }
    if (renamingPocket) PocketNameDialog("포켓 이름 바꾸기", pocket.name, "바꾸기", onDismiss = { renamingPocket = false }) { model.renameWorkbenchPocket(pocket.id, it); renamingPocket = false }
    if (deletingPocket) {
        AlertDialog(
            onDismissRequest = { deletingPocket = false },
            title = { Text("‘${pocket.name}’ 포켓을 삭제할까요?") },
            text = { Text("이 포켓에 담긴 참조 ${items.size}개만 목록에서 지워집니다. 미디어·파일 원본, 기존 재료 사본, 다른 포켓의 같은 항목은 그대로 남습니다.") },
            confirmButton = { TextButton(onClick = { deletingPocket = false; model.deleteWorkbenchPocket(pocket.id) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("참조만 삭제") } },
            dismissButton = { TextButton(onClick = { deletingPocket = false }) { Text("취소") } },
        )
    }
    previewItem?.let { item -> WorkbenchPreviewDialog(item) { previewItem = null } }
    if (filesOrFolder) FilesOrFolderDialog(pickers) { filesOrFolder = false }
    if (finderSources) WorkbenchFinderSourcesDialog(state, model, pickers, onDeviceFiles = { filesOrFolder = true }) { finderSources = false }
}

/**
 * 파인더에서 담기: the content sources the Finder shows (every saved connection, 폰하드, 즐겨찾기, 내 미디어, Google 포토, 브라우저).
 * Each row opens the existing Finder screen; files and folders are picked there and its 작업대에 담기 action records references only.
 * Connections come from the live list, so a newly added connection appears here without any change. Connection add/edit and
 * settings screens are not content sources and are not listed.
 */
@Composable
internal fun WorkbenchFinderSourcesDialog(state: AppState, model: NasFinderViewModel, pickers: WorkbenchPickers, onDeviceFiles: () -> Unit, onDismiss: () -> Unit) {
    @Composable
    fun SourceRow(title: String, detail: String, onClick: () -> Unit) {
        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onDismiss(); onClick() }.padding(vertical = 6.dp, horizontal = 4.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("파인더에서 담기", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "출처를 열어 파일이나 폴더를 고른 뒤 ‘작업대에 담기’를 누르세요. 위치만 기록하며 원본은 복사하거나 내려받지 않습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                if (state.connections.isEmpty()) {
                    Text("저장된 NAS·네트워크 연결이 없습니다. 파인더 홈에서 연결을 추가하면 여기에 나타납니다.", style = MaterialTheme.typography.labelMedium)
                }
                state.connections.forEach { connection ->
                    SourceRow(connection.name, connection.kind.title) { model.openConnection(connection) }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                SourceRow("폰하드", "이 기기에 저장된 받은 파일") { model.show(Screen.Inbox) }
                SourceRow("기기 파일", "파일 선택기에서 파일·폴더 위치만 담기", onDeviceFiles)
                SourceRow("즐겨찾기", "별표한 폴더·파일") { model.show(Screen.Favorites) }
                SourceRow("내 미디어·달력", "이 기기의 사진·영상") { model.show(Screen.LocalPhotoLibrary()) }
                SourceRow("Google 포토", "내려받지 않고 항목만 담기") { pickers.pickGooglePhotos() }
                SourceRow("브라우저", "받은 파일은 폰하드에 저장한 뒤 담기") { model.show(Screen.WebBrowser) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

/** 파일: pick files directly, or a folder (stored as a location; its media is looked at only when a run starts). */
@Composable
internal fun FilesOrFolderDialog(pickers: WorkbenchPickers, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("파일") },
        text = { Text("파일을 직접 고르거나 폴더를 고를 수 있습니다. 폴더는 위치만 담고, 영상 제작이나 전송을 시작할 때 그 안의 미디어를 확인합니다.") },
        confirmButton = { TextButton(onClick = { onDismiss(); pickers.pickFiles() }) { Text("파일 고르기") } },
        dismissButton = { TextButton(onClick = { onDismiss(); pickers.pickFolder() }) { Text("폴더 고르기") } },
    )
}

@Composable
internal fun PocketNameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, singleLine = true, label = { Text("포켓 이름") }) },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
internal fun WorkbenchItemRow(
    item: MaterialItem,
    selected: Boolean,
    onToggle: () -> Unit,
    onPreview: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    onReconnect: (() -> Unit)? = null,
    onRecheck: (() -> Unit)? = null,
    onExclude: (() -> Unit)? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    // 공개 포켓 안의 항목 자신의 공개 범위를 바꾼다(포켓이 비공개이면 포켓이 가리므로 null).
    onTogglePrivate: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val palette = themePalette()
    val needsReconnect = item.needsReconnect()
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) palette.selected.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) palette.accent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggle() },
                    enabled = item.state != MaterialPreparationState.EXCLUDED,
                    colors = CheckboxDefaults.colors(checkedColor = palette.accent, checkmarkColor = palette.onAccent),
                )
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .then(if (onPreview != null && !item.isFolder && item.entryKind == MaterialEntryKind.MEDIA) Modifier.clickable { onPreview() } else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    val thumb by produceState<Bitmap?>(initialValue = null, item.id, item.localFilePath, item.state, item.uriString) {
                        value = withContext(Dispatchers.IO) { loadMaterialThumbnail(context, item) }
                    }
                    if (thumb != null) {
                        Image(thumb!!.asImageBitmap(), item.displayLabel, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Icon(
                            when {
                                item.isFolder -> Icons.Default.Folder
                                item.entryKind == MaterialEntryKind.FILE -> Icons.Default.InsertDriveFile
                                item.isVideo -> Icons.Default.PlayCircle
                                else -> Icons.Default.Image
                            },
                            null,
                            Modifier.size(26.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.displayLabel, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val kindTag = when {
                        item.isFolder -> "폴더 (실행할 때 안의 미디어를 확인)"
                        item.entryKind == MaterialEntryKind.FILE -> "파일 (미디어 아님)"
                        item.isVideo -> "영상"
                        else -> "사진"
                    }
                    val stateTag = when (item.state) {
                        MaterialPreparationState.READY -> "참조됨"
                        MaterialPreparationState.DOWNLOADING -> "처리 중" + (item.downloadProgress?.let { " (${(it * 100).toInt()}%)" } ?: "")
                        MaterialPreparationState.MISSING -> if (needsReconnect) "연결 필요" else "실행할 때 가져옴"
                        MaterialPreparationState.EXCLUDED -> "제외됨"
                    }
                    val grantTag = if (item.grantPersisted == false) " · 임시 권한" else ""
                    Text(
                        "${workbenchOriginLabel(item)} · $kindTag · $stateTag$grantTag",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (needsReconnect) palette.danger else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!item.errorMessage.isNullOrBlank() && item.errorMessage != WORKBENCH_REMOTE_NOT_FETCHED) {
                        Text(item.errorMessage, style = MaterialTheme.typography.labelSmall, color = palette.danger, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            val hasActions = onRemove != null || onReconnect != null || onMoveUp != null || onMoveDown != null || onExclude != null || onTogglePrivate != null
            if (hasActions) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                    if (needsReconnect && onReconnect != null) {
                        TextButton(onClick = onReconnect) { Text("다시 연결", fontSize = 13.sp, color = palette.accent) }
                    }
                    if (item.state == MaterialPreparationState.MISSING && onRecheck != null) {
                        TextButton(onClick = onRecheck) { Text("다시 확인", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (onExclude != null && (item.state == MaterialPreparationState.EXCLUDED || item.state == MaterialPreparationState.MISSING)) {
                        TextButton(onClick = onExclude) { Text(if (item.state == MaterialPreparationState.EXCLUDED) "포함" else "제외", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Spacer(Modifier.weight(1f))
                    if (onTogglePrivate != null) IconButton(onClick = onTogglePrivate) {
                        Icon(
                            if (item.isPrivate) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            if (item.isPrivate) "${item.displayLabel} 공개로 전환" else "${item.displayLabel} 비공개로 전환",
                            Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (onMoveUp != null) IconButton(onClick = onMoveUp) { Icon(Icons.Default.KeyboardArrowUp, "${item.displayLabel} 위로", Modifier.size(22.dp)) }
                    if (onMoveDown != null) IconButton(onClick = onMoveDown) { Icon(Icons.Default.KeyboardArrowDown, "${item.displayLabel} 아래로", Modifier.size(22.dp)) }
                    if (onRemove != null) IconButton(onClick = onRemove, modifier = Modifier.semantics { contentDescription = "${item.displayLabel} 작업대에서 빼기 (원본은 그대로)" }) {
                        PackIcon(PackGlyph.CLOSE, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkbenchPreviewDialog(item: MaterialItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val palette = themePalette()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.displayLabel, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    val detail by produceState<Bitmap?>(initialValue = null, item.id) { value = withContext(Dispatchers.IO) { loadMaterialThumbnail(context, item, maxDimension = 720) } }
                    if (detail != null) Image(detail!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else PackIcon(if (item.isVideo) PackGlyph.VIDEO else PackGlyph.PHOTOS, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("출처: ${workbenchOriginLabel(item)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("원본 위치만 저장되어 있고 이 미리보기는 작은 썸네일입니다.", style = MaterialTheme.typography.labelSmall, color = palette.accent)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

/** Small thumbnail of a reference. Reads only a thumbnail-sized preview; folders, non-media files and NAS items show an icon. */
private suspend fun loadMaterialThumbnail(context: android.content.Context, item: MaterialItem, maxDimension: Int = 180): Bitmap? {
    if (item.isFolder || item.entryKind == MaterialEntryKind.FILE) return null
    return runCatching {
        val localPath = item.localFilePath
        if (localPath != null) {
            val file = File(localPath)
            if (file.exists() && file.length() > 0L) {
                if (item.isVideo) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ThumbnailUtils.createVideoThumbnail(file, Size(maxDimension, maxDimension), null)
                    } else {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(file.path)
                            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        } finally {
                            retriever.release()
                        }
                    }
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, bounds)
                    val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / maxDimension)
                    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            } else null
        } else if (item.sourceType == MaterialSourceType.LOCAL_LIBRARY) {
            // Provider-made thumbnail only: never open the original stream just to draw a list row (cloud items could download).
            val uri = Uri.parse(item.uriString)
            when {
                uri.scheme == "file" -> uri.path?.let { path ->
                    val file = File(path)
                    if (file.isFile && item.isVideo) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ThumbnailUtils.createVideoThumbnail(file, Size(maxDimension, maxDimension), null) else null
                    } else if (file.isFile) {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.path, bounds)
                        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / maxDimension) })
                    } else null
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> context.contentResolver.loadThumbnail(uri, Size(maxDimension, maxDimension), null)
                else -> null
            }
        } else null
    }.getOrNull()
}

// ---- pickers shared by the 작업대 and the 전송 screen ---------------------------------------------------------------

private const val PERSISTABLE_READ = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION

/** Document picker that asks for a persistable read grant, so the reference can survive an app restart where the provider allows it. */
internal class PersistableOpenDocuments(private val multiple: Boolean) : androidx.activity.result.contract.ActivityResultContract<Array<String>, List<Uri>>() {
    override fun createIntent(context: android.content.Context, input: Array<String>): android.content.Intent =
        android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(android.content.Intent.EXTRA_MIME_TYPES, input)
            .putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            .addFlags(PERSISTABLE_READ)

    override fun parseResult(resultCode: Int, intent: android.content.Intent?): List<Uri> {
        if (resultCode != android.app.Activity.RESULT_OK || intent == null) return emptyList()
        val clip = intent.clipData
        val fromClip = if (clip != null) (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } else emptyList()
        return fromClip.ifEmpty { listOfNotNull(intent.data) }
    }
}

/** Folder picker with a persistable read grant (the folder is only a location until a run starts). */
internal class PersistableOpenTree : androidx.activity.result.contract.ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: android.content.Context, input: Unit): android.content.Intent =
        android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(PERSISTABLE_READ)

    override fun parseResult(resultCode: Int, intent: android.content.Intent?): Uri? =
        if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
}

internal class WorkbenchPickers(
    val pickMedia: () -> Unit,
    val pickGooglePhotos: () -> Unit,
    val pickFiles: () -> Unit,
    val pickFolder: () -> Unit,
    val googlePhotosAvailable: Boolean,
)

/**
 * The three top-level add choices (미디어 / Google 포토 / 파일) plus 폴더. Each result only becomes references; the
 * destination pocket is chosen in the 작업대에 담기 dialog. [afterAdd] runs with the pocket the references went to.
 */
@Composable
internal fun rememberWorkbenchPickers(model: NasFinderViewModel, afterAdd: ((String) -> Unit)? = null): WorkbenchPickers {
    val context = LocalContext.current
    val media = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        // An empty result means the picker was cancelled: nothing changes.
        if (uris.isNotEmpty()) model.requestWorkbenchAddLocal(uris, origin = "MY_MEDIA", afterAdd = afterAdd)
    }
    val google = rememberLauncherForActivityResult(GooglePhotosAppPickContract()) { uris ->
        if (uris.isNotEmpty()) model.requestWorkbenchAddLocal(uris, origin = "GOOGLE_PHOTOS", afterAdd = afterAdd)
    }
    val files = rememberLauncherForActivityResult(PersistableOpenDocuments(multiple = true)) { uris ->
        if (uris.isNotEmpty()) model.requestWorkbenchAddLocal(uris, origin = "FILES", afterAdd = afterAdd)
    }
    val folder = rememberLauncherForActivityResult(PersistableOpenTree()) { uri ->
        if (uri != null) model.requestWorkbenchAddFolderTree(uri, afterAdd)
    }
    val installed = remember(context) { isGooglePhotosInstalled(context) }
    return WorkbenchPickers(
        pickMedia = { media.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
        pickGooglePhotos = {
            if (installed) runCatching { google.launch(Unit) }.onFailure { model.showMessage("Google 포토 앱을 열지 못했습니다. 앱이 설치되어 있고 사용 가능한지 확인해 주세요.") }
            else model.showMessage("Google 포토 앱이 설치되어 있지 않아 사용할 수 없습니다.")
        },
        pickFiles = { files.launch(arrayOf("*/*")) },
        pickFolder = { folder.launch(Unit) },
        googlePhotosAvailable = installed,
    )
}

// ---- global dialogs (rendered once at the app root) ----------------------------------------------------------------

/** 작업대에 담기: choose the destination pocket (or create one in place). Nothing is read from the originals here. */
@Composable
internal fun WorkbenchAddDialog(state: AppState, model: NasFinderViewModel) {
    val request = state.workbenchAdd ?: return
    WorkbenchPocketChooser(
        title = "작업대에 담기",
        message = "${request.candidates.size}개 항목의 위치·권한 정보만 담습니다. 원본은 복사하거나 내려받지 않습니다.",
        note = request.note,
        pockets = state.pockets,
        countOf = { id -> state.materials.count { item -> (if (state.pockets.any { it.id == item.pocketId }) item.pocketId else DEFAULT_POCKET_ID) == id } },
        initialPocketId = state.workbenchPocketId,
        confirmLabel = "담기",
        onDismiss = model::dismissWorkbenchAdd,
        onChooseExisting = model::confirmWorkbenchAdd,
        onChooseNew = model::confirmWorkbenchAddToNewPocket,
    )
}

/** Destination-pocket chooser shared by every 작업대에 담기 entry (the existing pockets plus 새 포켓 in place). */
@Composable
internal fun WorkbenchPocketChooser(
    title: String,
    message: String,
    note: String? = null,
    pockets: List<MaterialPocket>,
    countOf: (String) -> Int,
    initialPocketId: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onChooseExisting: (String) -> Unit,
    onChooseNew: (String) -> Unit,
) {
    var choice by remember { mutableStateOf(initialPocketId) }
    var newName by remember { mutableStateOf("") }
    val creating = choice == NEW_POCKET_CHOICE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = themePalette().accent) }
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    pockets.forEach { p -> PocketChoiceRow("${p.name} (${countOf(p.id)})", choice == p.id) { choice = p.id } }
                    PocketChoiceRow("＋ 새 포켓 만들기", creating) { choice = NEW_POCKET_CHOICE }
                }
                if (creating) OutlinedTextField(value = newName, onValueChange = { newName = it.take(60) }, singleLine = true, label = { Text("새 포켓 이름") })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (creating) onChooseNew(newName) else onChooseExisting(choice) },
                enabled = !creating || newName.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

private const val NEW_POCKET_CHOICE = "\u0000new-pocket"

@Composable
private fun PocketChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Scan progress and the "unsupported items" report of a 영상 제작 run. */
@Composable
internal fun WorkbenchRunDialog(state: AppState, model: NasFinderViewModel) {
    // 비공개 출처로 시작한 실행은 일반 모드 화면에 이름·보고를 내놓지 않는다(실행·결정 대기 상태는 그대로 유지).
    val run = state.workbenchRun?.takeIf { com.armsone.nasfinder.data.PrivacyMode.isVisible(it.originPrivate) } ?: return
    val partial = run.partial
    if (partial != null) {
        WorkbenchPartialDialog(partial.failures, partial.available, "영상 제작", onContinue = model::confirmWorkbenchRun, onCancel = model::cancelWorkbenchRun)
        return
    }
    val plan = run.awaiting
    if (plan == null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("확인하는 중") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(run.phase)
                    if (run.name.isNotBlank()) Text("${run.name} · 미디어 ${run.current}개 확인", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = model::cancelWorkbenchRun) { Text("취소") } },
        )
    } else {
        AlertDialog(
            onDismissRequest = model::cancelWorkbenchRun,
            title = { Text("사용할 수 없는 항목이 있습니다", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (plan.leaves.isEmpty()) "쓸 수 있는 미디어가 없습니다." else "아래 ${plan.issues.size}개는 제외하고 미디어 ${plan.leaves.size}개로 진행할 수 있습니다. 작업대 항목은 그대로 남습니다.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    plan.issues.forEach { Text("• ${it.name}: ${it.reason}", style = MaterialTheme.typography.labelMedium, color = themePalette().danger) }
                    plan.notes.forEach { Text("• $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            },
            confirmButton = { TextButton(onClick = model::confirmWorkbenchRun) { Text(if (plan.leaves.isEmpty()) "확인" else "제외하고 계속") } },
            dismissButton = { TextButton(onClick = model::cancelWorkbenchRun) { Text("취소") } },
        )
    }
}

/**
 * Some inputs could not be fetched / read after the run started. The run never continues with fewer inputs on its own:
 * the user either continues with what was read or cancels (only this run's temporary files are removed; references stay).
 */
@Composable
internal fun WorkbenchPartialDialog(
    failures: List<WorkbenchRunIssue>,
    available: Int,
    action: String,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("일부 항목을 읽지 못했습니다 ($action)", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (available > 0) "아래 ${failures.size}개를 읽지 못했습니다. 읽은 ${available}개로 계속할까요? 취소하면 이번 실행의 임시 파일만 지우고, 작업대 항목은 그대로 남아 ‘다시 연결’할 수 있습니다."
                    else "읽을 수 있는 항목이 없습니다. 작업대 항목은 그대로 남습니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
                failures.forEach { Text("• ${it.name}: ${it.reason}", style = MaterialTheme.typography.labelMedium, color = themePalette().danger) }
            }
        },
        confirmButton = { TextButton(onClick = onContinue, enabled = available > 0) { Text("읽은 ${available}개로 계속") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("취소") } },
    )
}

/** 다시 연결 confirmation: a reference is replaced only after the user agrees. */
@Composable
internal fun WorkbenchReconnectDialog(state: AppState, model: NasFinderViewModel) {
    val pending = state.workbenchReconnect ?: return
    AlertDialog(
        onDismissRequest = model::dismissWorkbenchReconnect,
        title = { Text("참조를 바꿀까요?") },
        text = {
            Text(
                "‘${pending.oldName}’ 참조를 방금 고른 ‘${pending.replacement.displayLabel}’(으)로 바꿉니다. 같은 항목인지 이름이나 내용으로 추측하지 않으므로 직접 확인해 주세요. 원본 파일은 건드리지 않고, 다른 포켓의 항목은 바뀌지 않습니다.",
            )
        },
        confirmButton = { TextButton(onClick = model::confirmWorkbenchReconnect) { Text("이 항목으로 바꾸기") } },
        dismissButton = { TextButton(onClick = model::dismissWorkbenchReconnect) { Text("취소") } },
    )
}

/** Pocket picker + 전체 선택 for the 전송 screen: which pocket the send reads from, and how many of its items are selected. */
@Composable
internal fun PocketSendHeader(
    state: AppState,
    selectedCount: Int,
    enabled: Boolean,
    onSelectPocket: (String) -> Unit,
    onSelectAll: () -> Unit,
    onCreatePocket: (String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("보낼 포켓 · ${selectedCount}개 선택", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 4.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.pockets.forEach { p ->
                FilterChip(
                    selected = p.id == state.workbenchPocketId,
                    onClick = { onSelectPocket(p.id) },
                    enabled = enabled,
                    label = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
            AssistChip(onClick = { creating = true }, enabled = enabled, label = { Text("＋ 새 포켓") })
            TextButton(onClick = onSelectAll, enabled = enabled) { Text("전체 선택/해제", fontSize = 13.sp) }
        }
    }
    if (creating) PocketNameDialog("새 포켓", "", "만들기", onDismiss = { creating = false }) { onCreatePocket(it); creating = false }
}

/** Kept so the former route / call sites keep compiling: the standalone 영상 재료 screen is the 작업대. */
@Composable
internal fun MaterialBasketScreen(state: AppState, model: NasFinderViewModel, onBack: () -> Unit, embedded: Boolean = false) =
    WorkbenchScreen(state, model, embedded = embedded, onBack = onBack)
