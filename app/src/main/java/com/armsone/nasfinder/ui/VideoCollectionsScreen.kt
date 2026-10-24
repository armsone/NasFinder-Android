package com.armsone.nasfinder.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoCollectionsScreen(
    state: AppState,
    model: NasFinderViewModel,
    onBack: () -> Unit,
    // 영상 hub embeds this screen under its own title / tab bar: no own title, back arrow or system insets.
    embedded: Boolean = false
) {
    LaunchedEffect(Unit) {
        model.refreshCollections()
    }

    val collections = state.collections
    val context = LocalContext.current
    var editingItem by remember { mutableStateOf<CollectionItem?>(null) }
    var renameTitle by remember { mutableStateOf("") }
    var itemToDelete by remember { mutableStateOf<CollectionItem?>(null) }

    // System document picker; the result's grant flags decide whether a durable reference can be offered.
    // A cancelled pick returns null and changes nothing.
    val videoPicker = rememberLauncherForActivityResult(OpenDocumentWithGrantFlags()) { picked ->
        if (picked != null) {
            model.stageCollectionImport(listOf(picked.uri to picked.flags))
        }
    }

    // 추천 썸네일: candidates are only shown; the poster changes after explicit confirmation.
    var thumbnailCandidateItem by remember { mutableStateOf<CollectionItem?>(null) }
    var chosenPoster by remember { mutableStateOf<Bitmap?>(null) }
    var compressItem by remember { mutableStateOf<CollectionItem?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            PorcelainTopBar(
                title = if (embedded) "컬렉션 ${collections.size}편" else "컬렉션",
                subtitle = if (embedded) null else "완성된 영상 및 가져온 영상 보관소 (${collections.size}개)",
                onBack = if (embedded) null else onBack,
                windowInsets = if (embedded) WindowInsets(0) else TopAppBarDefaults.windowInsets,
                actions = {
                    TextButton(onClick = { videoPicker.launch(arrayOf("video/*")) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("영상 가져오기")
                    }
                },
            )
        },
        contentWindowInsets = if (embedded) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets
    ) { padding ->
        if (collections.isEmpty()) {
            EmptyState(
                icon = Icons.Default.VideoLibrary,
                title = "컬렉션에 보관된 영상이 없습니다",
                description = "영상을 내보내면 완성본이 이곳에 안전하게 보관되며, 원본 소스와 분리되어 언제든 재생하거나 공유할 수 있습니다.",
                modifier = Modifier.fillMaxSize().padding(padding),
                action = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // An unreadable store file is not an empty collection: say so.
                        DurableCollectionStore.loadProblem()?.let { problem ->
                            Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                        }
                        Button(onClick = { videoPicker.launch(arrayOf("video/*")) }, shape = RoundedCornerShape(10.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(6.dp))
                            Text("기기 영상 가져오기")
                        }
                    }
                },
            )
        } else {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                val columns = if (maxWidth >= 600.dp) 3 else 2
                Column(Modifier.fillMaxSize()) {
                DurableCollectionStore.loadProblem()?.let { problem ->
                    Text(problem, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
                state.compression?.takeIf { com.armsone.nasfinder.data.PrivacyMode.isVisible(it.originPrivate) }?.let { op ->
                    CompressionStatusCard(op, onCancel = model::cancelCollectionCompression, onDismiss = model::dismissCollectionCompression)
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(collections, key = { it.id }) { item ->
                        CollectionPosterCard(
                            item = item,
                            compressing = state.compression?.let { it.itemId == item.id && it.running } == true,
                            onCompress = { compressItem = item },
                            onPlay = { model.previewCollectionVideo(item) },
                            onTogglePin = { model.toggleCollectionPin(item.id) },
                            onSetPrivate = { model.setCollectionPrivate(item.id, it) },
                            onMove = { offset -> model.moveCollectionItem(item.id, offset) },
                            onRename = {
                                editingItem = item
                                renameTitle = item.title
                            },
                            onShare = { model.shareCollectionVideo(item) },
                            onRelink = { model.relinkCollectionVideo(item.id) },
                            onDelete = { itemToDelete = item },
                            onPickThumbnail = { thumbnailCandidateItem = item; chosenPoster = null },
                            onOpenProject = {
                                val draft = item.sourceDraftId?.let { id -> TrailerDraftStore.getAllDrafts().firstOrNull { it.id == id } }
                                if (draft != null) {
                                    model.resumeTrailerDraft(draft)
                                } else {
                                    model.createProjectFromCollectionVideo(item)
                                }
                            }
                        )
                    }
                }
                }
            }
        }

        compressItem?.let { item ->
            CompressCollectionDialog(
                item = item,
                model = model,
                onDismiss = { compressItem = null },
                onStart = { preset ->
                    compressItem = null
                    model.startCollectionCompression(item.id, preset)
                },
            )
        }

        editingItem?.let { item ->
            AlertDialog(
                onDismissRequest = { editingItem = null },
                title = { Text("영상 이름 변경") },
                text = {
                    TextField(
                        value = renameTitle,
                        onValueChange = { renameTitle = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        model.renameCollectionVideo(item.id, renameTitle)
                        editingItem = null
                    }) {
                        Text("확인")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { editingItem = null }) {
                        Text("취소")
                    }
                }
            )
        }

        itemToDelete?.let { item ->
            AlertDialog(
                onDismissRequest = { itemToDelete = null },
                title = { Text("컬렉션에서 삭제할까요?") },
                text = {
                    Text(
                        if (item.isReference) "‘${item.title}’의 컬렉션 등록 정보만 제거됩니다. 외부 원본 영상은 절대 삭제되지 않습니다."
                        else "‘${item.title}’의 컬렉션 보관 파일만 삭제됩니다. 원본 사진 및 영상은 절대 삭제되지 않습니다."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        model.removeCollectionVideo(item.id)
                        itemToDelete = null
                    }) {
                        Text("삭제", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { itemToDelete = null }) {
                        Text("취소")
                    }
                }
            )
        }

        thumbnailCandidateItem?.let { item ->
            AlertDialog(
                onDismissRequest = { thumbnailCandidateItem = null },
                title = { Text("추천 썸네일") },
                text = {
                    PosterCandidatePicker(videoFile = File(item.localFilePath), selected = chosenPoster, onSelect = { chosenPoster = it })
                },
                confirmButton = {
                    TextButton(
                        enabled = chosenPoster != null,
                        onClick = {
                            chosenPoster?.let { model.confirmCollectionPoster(item.id, it) }
                            thumbnailCandidateItem = null
                        },
                    ) { Text("이 썸네일로 바꾸기") }
                },
                dismissButton = {
                    // Cancel keeps the prior poster untouched.
                    TextButton(onClick = { thumbnailCandidateItem = null }) { Text("취소") }
                },
            )
        }
    }
}

internal data class PickedDocument(val uri: Uri, val flags: Int)

/** Same ACTION_OPEN_DOCUMENT request as [ActivityResultContracts.OpenDocument], also reporting the returned grant flags. */
internal class OpenDocumentWithGrantFlags : ActivityResultContract<Array<String>, PickedDocument?>() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_MIME_TYPES, input)
            .setType("*/*")

    override fun parseResult(resultCode: Int, intent: Intent?): PickedDocument? {
        if (resultCode != Activity.RESULT_OK) return null
        val uri = intent?.data ?: return null
        return PickedDocument(uri, intent.flags)
    }
}

/**
 * 컬렉션 추가 확인: shows the picked videos (title + first frame) and the 사본/참조 choice. App copy is the default;
 * 원본 참조 is selectable only when every item supports a durable grant, otherwise the reason is shown. A failed
 * reference attempt keeps the selection here with 다시 시도 and 앱 안에 사본 보관.
 */
@Composable
internal fun PendingCollectionImportDialog(pending: PendingCollectionImport, model: NasFinderViewModel) {
    val context = LocalContext.current
    var referenceRequested by remember(pending.entries) { mutableStateOf(false) }
    val asReference = referenceRequested && pending.referenceSupported
    val previewEntries = pending.entries.take(3)
    var posters by remember(pending.entries) { mutableStateOf<Map<String, Bitmap?>>(emptyMap()) }
    LaunchedEffect(pending.entries) {
        posters = withContext(Dispatchers.IO) {
            previewEntries.associate { entry ->
                val retriever = MediaMetadataRetriever()
                val frame = try {
                    retriever.setDataSource(context, Uri.parse(entry.uri))
                    if (android.os.Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320)
                    else retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } catch (_: Exception) { null } finally { runCatching { retriever.release() } }
                entry.uri to frame
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!pending.working) model.cancelCollectionImport() },
        title = { Text("컬렉션에 추가") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    previewEntries.forEach { entry ->
                        Column(Modifier.weight(1f)) {
                            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = .85f))) {
                                posters[entry.uri]?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            }
                            Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    repeat(3 - previewEntries.size) { Spacer(Modifier.weight(1f)) }
                }
                if (pending.entries.size > previewEntries.size) {
                    Text("외 ${pending.entries.size - previewEntries.size}개 · 모두 ${pending.entries.size}개", style = MaterialTheme.typography.labelSmall)
                }
                pending.excludedNote?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                ImportModeOption(
                    title = "앱 안에 사본 보관 (기본)",
                    detail = "원본을 옮기거나 지워도 계속 재생됩니다. 기기 저장 공간을 사용합니다.",
                    selected = !asReference,
                    enabled = !pending.working,
                    onClick = { referenceRequested = false },
                )
                ImportModeOption(
                    title = "원본 참조로 연결",
                    detail = if (pending.referenceSupported) "복사하지 않아 저장 공간을 쓰지 않습니다. 원본을 옮기거나 지우거나 권한이 해제되면 다시 연결이 필요합니다. 원본은 바꾸지 않습니다."
                    else "이 선택에는 사용할 수 없습니다: " + pending.entries.mapNotNull { it.referenceBlocker }.distinct().joinToString(" ") + " 앱 안에 사본으로 보관해 주세요.",
                    selected = asReference,
                    enabled = pending.referenceSupported && !pending.working,
                    onClick = { referenceRequested = true },
                )
                pending.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (pending.working) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("읽기 권한을 확인하는 중…", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        },
        confirmButton = {
            if (pending.problem != null && pending.referenceSupported) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { model.confirmCollectionImport(asReference = false) }, enabled = !pending.working) { Text("앱 안에 사본 보관") }
                    Button(onClick = { model.confirmCollectionImport(asReference = true) }, enabled = !pending.working) { Text("다시 시도") }
                }
            } else {
                Button(onClick = { model.confirmCollectionImport(asReference) }, enabled = !pending.working) {
                    Text(if (asReference) "참조로 추가" else "사본으로 추가")
                }
            }
        },
        dismissButton = { TextButton(onClick = model::cancelCollectionImport, enabled = !pending.working) { Text("취소") } },
    )
}

@Composable
private fun ImportModeOption(title: String, detail: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else MaterialTheme.colorScheme.surfaceVariant,
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Spacer(Modifier.width(6.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (enabled || selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Frames sampled at even intervals from the video; "다른 후보" shifts the sampling phase. The 추천 mark is the
 * sharpest/best-exposed non-black frame measured on this device (local measurement, not AI).
 */
@Composable
internal fun PosterCandidatePicker(videoFile: File, selected: Bitmap?, onSelect: (Bitmap) -> Unit) {
    var phase by remember(videoFile) { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var candidates by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var recommended by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(videoFile, phase) {
        loading = true
        val frames = withContext(Dispatchers.IO) { extractPosterCandidates(videoFile, count = 6, phase = phase) }
        candidates = frames
        recommended = withContext(Dispatchers.Default) { selectBestVideoFrameIndex(frames) }
        loading = false
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("영상에서 일정 간격으로 뽑은 장면입니다. ‘추천’은 기기에서 선명도와 밝기를 비교해 고른 후보입니다.", style = MaterialTheme.typography.bodySmall)
        when {
            loading -> Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            candidates.isEmpty() -> Text("추출할 수 있는 프레임이 없습니다. 첫 장면이 썸네일로 쓰입니다.")
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxWidth().height(200.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(candidates.indices.toList()) { index ->
                    val bmp = candidates[index]
                    val isSelected = selected === bmp
                    Box(
                        Modifier
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(6.dp))
                            .border(if (isSelected) 3.dp else 0.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(6.dp))
                            .clickable { onSelect(bmp) },
                    ) {
                        Image(bmp.asImageBitmap(), contentDescription = "후보 ${index + 1}${if (index == recommended) ", 추천" else ""}", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        if (index == recommended) {
                            Text("추천", Modifier.align(Alignment.TopStart).padding(3.dp).background(Color.Black.copy(alpha = .65f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                                color = Color.White, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
        TextButton(onClick = { phase++ }, enabled = !loading) { Text("다른 후보 보기") }
    }
}

/** Evenly spaced frames whose positions move with [phase] (golden-ratio offsets, so each phase differs). */
internal fun extractPosterCandidates(file: File, count: Int, phase: Int): List<Bitmap> {
    val results = mutableListOf<Bitmap>()
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(file.absolutePath)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        if (durationMs > 0L) {
            val offset = (phase * 0.618) % 1.0
            for (i in 0 until count) {
                val fraction = ((i + 0.5 + offset) / count).let { if (it >= 1.0) it - 1.0 else it }.coerceIn(0.02, 0.98)
                val timeUs = (durationMs * 1000L * fraction).toLong()
                val frame = if (android.os.Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 640)
                else retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) results.add(frame)
            }
        }
    } catch (_: Exception) {
    } finally {
        runCatching { retriever.release() }
    }
    return results
}

private fun collectionBytes(value: Long): String {
    if (value < 1024) return "$value B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var size = value.toDouble()
    var index = -1
    do { size /= 1024; index++ } while (size >= 1024 && index < units.lastIndex)
    return "%.1f %s".format(size, units[index])
}

@Composable
private fun CompressionStatusCard(op: CollectionCompressionState, onCancel: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("용량 줄이기 · ${op.itemTitle} · ${op.preset.title}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (op.running) TextButton(onClick = onCancel) { Text("취소") } else TextButton(onClick = onDismiss) { Text("닫기") }
            }
            val progress = op.progress
            Text(
                op.message ?: "${op.phase.title}${progress?.let { " ${(it * 100).toInt()}%" } ?: ""}",
                style = MaterialTheme.typography.labelMedium,
                color = if (op.phase == CompressionPhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (op.running) {
                if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("작업 중에도 다른 화면으로 이동할 수 있습니다. 끝나기 전까지 기존 영상은 그대로입니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CompressCollectionDialog(
    item: CollectionItem,
    model: NasFinderViewModel,
    onDismiss: () -> Unit,
    onStart: (CompressionPreset) -> Unit,
) {
    var facts by remember(item.id) { mutableStateOf<VideoFileFacts?>(null) }
    var loaded by remember(item.id) { mutableStateOf(false) }
    var preset by remember { mutableStateOf(CompressionPreset.P720) }
    LaunchedEffect(item.id, item.localFilePath) {
        facts = model.collectionVideoFacts(item)
        loaded = true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("용량 줄이기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val current = facts
                when {
                    !loaded -> CircularProgressIndicator(Modifier.size(24.dp))
                    current == null -> Text("영상 파일을 읽을 수 없어 줄일 수 없습니다. 파일이 이동되었거나 지원되지 않는 형식입니다.", color = MaterialTheme.colorScheme.error)
                    else -> {
                        Text("현재: ${current.width}×${current.height} · ${trailerLengthTitle(current.durationSeconds)} · ${collectionBytes(current.sizeBytes)}", style = MaterialTheme.typography.bodySmall)
                        CompressionPreset.entries.forEach { candidate ->
                            val (w, h) = compressionTargetSize(current, candidate)
                            val estimate = estimateCompressedBytes(current, candidate)
                            val noDownscale = w >= current.width && h >= current.height
                            Surface(
                                onClick = { preset = candidate },
                                shape = RoundedCornerShape(10.dp),
                                color = if (preset == candidate) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else MaterialTheme.colorScheme.surfaceVariant,
                                border = if (preset == candidate) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(candidate.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text(
                                        "${w}×${h}${if (noDownscale) " (원본보다 키우지 않음)" else ""} · 예상 약 ${collectionBytes(estimate)}" +
                                            if (estimate >= current.sizeBytes) " · 줄어들지 않을 수 있음" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                        Text("예상 용량은 추정치입니다. 결과가 원본보다 작지 않으면 원본을 그대로 둡니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (item.isReference) {
                            Text("이 영상은 외부 원본을 참조합니다. 외부 원본은 바꾸거나 지우지 않고, 줄인 사본을 앱 안에 새로 보관한 뒤 컬렉션이 그 사본을 사용합니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        } else {
                            Text("검증된 더 작은 결과가 저장된 뒤에만 앱이 보관한 기존 사본을 바꿉니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onStart(preset) }, enabled = facts != null) { Text("줄이기 시작") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun CollectionPosterCard(
    item: CollectionItem,
    compressing: Boolean,
    onCompress: () -> Unit,
    onPlay: () -> Unit,
    onTogglePin: () -> Unit,
    onSetPrivate: (Boolean) -> Unit = {},
    onMove: (Int) -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onRelink: () -> Unit,
    onDelete: () -> Unit,
    onPickThumbnail: () -> Unit,
    onOpenProject: () -> Unit
) {
    var posterBitmap by remember(item.posterFilePath, item.localFilePath) { mutableStateOf<Bitmap?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(item.posterFilePath, item.localFilePath) {
        posterBitmap = withContext(Dispatchers.IO) {
            val pPath = item.posterFilePath
            if (pPath != null && File(pPath).exists()) {
                BitmapFactory.decodeFile(pPath)
            } else {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(item.localFilePath)
                    retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } catch (_: Exception) { null }
                finally { runCatching { retriever.release() } }
            }
        }
    }

    val dateFormatter = remember { SimpleDateFormat("yyyy.MM.dd", Locale.getDefault()) }
    val dateText = remember(item.timestamp) { dateFormatter.format(Date(item.timestamp)) }
    val durationText = listOfNotNull(
        if (item.durationSeconds > 0) trailerLengthTitle(item.durationSeconds) else null,
        item.fileSizeBytes.takeIf { it > 0L }?.let(::collectionBytes),
    ).joinToString(" · ").ifBlank { "재생 가능" }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black.copy(alpha = 0.85f))
            ) {
                posterBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(24.dp))
                }

                if (item.isPinned) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Row(
                            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PushPin, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(2.dp))
                            Text("고정", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Black.copy(alpha = 0.7f)
                ) {
                    Text(
                        if (item.isValid) item.storageModeLabel else item.unavailableLabel,
                        fontSize = 10.sp,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                if (compressing) {
                    CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(8.dp).size(20.dp), color = Color.White)
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        item.title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Box {
                        IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "메뉴", modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            val draftExists = item.sourceDraftId?.let { id -> TrailerDraftStore.getAllDrafts().any { it.id == id } } == true
                            DropdownMenuItem(
                                text = { Text(if (draftExists) "편집본 열기" else "이 영상으로 제작") },
                                leadingIcon = { Icon(Icons.Default.MovieFilter, null) },
                                onClick = {
                                    menuExpanded = false
                                    onOpenProject()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("용량 줄이기") },
                                leadingIcon = { Icon(Icons.Default.Compress, null) },
                                enabled = item.isValid && !compressing,
                                onClick = {
                                    menuExpanded = false
                                    onCompress()
                                }
                            )
                            if (!item.isValid) {
                                DropdownMenuItem(
                                    text = { Text("다시 연결") },
                                    leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                    onClick = {
                                        menuExpanded = false
                                        onRelink()
                                    }
                                )
                            }
                            DropdownMenuItem(
                                // Candidates are sampled from a local file; a content:// reference has none.
                                text = { Text(if (item.isContentReference) "추천 썸네일 (외부 참조는 지원 안 함)" else "추천 썸네일") },
                                leadingIcon = { Icon(Icons.Default.Image, null) },
                                enabled = item.isValid && !item.isContentReference,
                                onClick = {
                                    menuExpanded = false
                                    onPickThumbnail()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("이름 변경") },
                                leadingIcon = { Icon(Icons.Default.Edit, null) },
                                onClick = {
                                    menuExpanded = false
                                    onRename()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (item.isPrivate) "공개로 전환" else "비공개로 전환") },
                                leadingIcon = { Icon(if (item.isPrivate) Icons.Default.Visibility else Icons.Default.VisibilityOff, null) },
                                onClick = {
                                    menuExpanded = false
                                    onSetPrivate(!item.isPrivate)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (item.isPinned) "고정 해제" else "고정") },
                                leadingIcon = { Icon(Icons.Default.PushPin, null) },
                                onClick = {
                                    menuExpanded = false
                                    onTogglePin()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("앞으로 이동") },
                                leadingIcon = { Icon(Icons.Default.ArrowBack, null) },
                                onClick = {
                                    menuExpanded = false
                                    onMove(-1)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("뒤로 이동") },
                                leadingIcon = { Icon(Icons.Default.ArrowForward, null) },
                                onClick = {
                                    menuExpanded = false
                                    onMove(1)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("공유") },
                                leadingIcon = { Icon(Icons.Default.Share, null) },
                                onClick = {
                                    menuExpanded = false
                                    onShare()
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("삭제", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menuExpanded = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }

                Text(
                    "$durationText · $dateText",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
