package com.armsone.nasfinder.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.armsone.nasfinder.ui.theme.themePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MaterialBasketScreen(
    state: AppState,
    model: NasFinderViewModel,
    onBack: () -> Unit
) {
    LaunchedEffect(Unit) {
        model.refreshMaterials()
    }

    val materials = state.materials
    val context = LocalContext.current
    val palette = themePalette()

    // Explicit item selection maintained while navigating
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    // Initialize selection to all non-excluded items when list first loads or changes
    LaunchedEffect(materials.map { it.id }) {
        val nonExcluded = materials.filter { it.state != MaterialPreparationState.EXCLUDED }.map { it.id }.toSet()
        // Retain currently valid selected IDs or default to all non-excluded
        selectedIds = if (selectedIds.isEmpty()) nonExcluded else selectedIds.intersect(materials.map { it.id }.toSet())
    }

    // Confirmation dialog state before video creation
    var showCreateConfirmDialog by remember { mutableStateOf(false) }
    var previewItem by remember { mutableStateOf<MaterialItem?>(null) }

    val selectedMaterials = remember(materials, selectedIds) {
        materials.filter { selectedIds.contains(it.id) }
    }
    val allSelected = materials.isNotEmpty() && materials.all { selectedIds.contains(it.id) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("재료함", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text(
                            "영상 제작에 사용할 미디어를 모아둡니다 (${selectedMaterials.size}/${materials.size}개 선택됨)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        PackIcon(PackGlyph.BACK, "뒤로", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                actions = {
                    if (materials.isNotEmpty()) {
                        Button(
                            onClick = {
                                if (selectedMaterials.isNotEmpty()) {
                                    showCreateConfirmDialog = true
                                }
                            },
                            enabled = selectedMaterials.isNotEmpty(),
                            modifier = Modifier.padding(end = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = palette.solidAction,
                                contentColor = palette.onAccent
                            )
                        ) {
                            PackIcon(PackGlyph.VIDEO, null, Modifier.size(18.dp), tint = palette.onAccent)
                            Spacer(Modifier.width(6.dp))
                            Text("새 영상 제작 (${selectedMaterials.size})")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        if (materials.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        Modifier
                            .size(64.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        PackIcon(PackGlyph.STORAGE, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // An unreadable store file is not an empty basket: say so.
                    val loadProblem = DurableMaterialStore.loadProblem()
                    Text(
                        if (loadProblem != null) "재료함 목록을 불러오지 못했습니다" else "재료함이 비어 있습니다",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    loadProblem?.let { problem ->
                        Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Text(
                        "사진·영상 라이브러리, 폰하드, 원격 파일 브라우저에서 ‘재료함에 담기’를 눌러 미디어를 모아보세요. 소스를 이동하거나 앱을 다시 열어도 안전하게 유지됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { model.show(Screen.LocalPhotoLibrary()) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.solidAction,
                            contentColor = palette.onAccent
                        )
                    ) {
                        Text("사진·영상에서 재료 찾기")
                    }
                }
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                DurableMaterialStore.loadProblem()?.let { problem ->
                    item {
                        Text(problem, Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "모은 재료 목록 (정확한 출처 식별자 보존)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    selectedIds = if (allSelected) emptySet() else materials.map { it.id }.toSet()
                                }
                            ) {
                                Text(if (allSelected) "전체 해제" else "전체 선택", fontSize = 13.sp)
                            }
                        }
                    }
                }

                itemsIndexed(materials, key = { _, item -> item.id }) { index, item ->
                    val isSelected = selectedIds.contains(item.id)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedIds = if (isSelected) selectedIds - item.id else selectedIds + item.id
                            },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) palette.selected.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) palette.accent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { checked ->
                                    selectedIds = if (checked) selectedIds + item.id else selectedIds - item.id
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = palette.accent,
                                    checkmarkColor = palette.onAccent
                                )
                            )

                            // Thumbnail preview box
                            Box(
                                Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { previewItem = item },
                                contentAlignment = Alignment.Center
                            ) {
                                val thumbBitmap by produceState<Bitmap?>(initialValue = null, item.id, item.localFilePath, item.state) {
                                    value = withContext(Dispatchers.IO) {
                                        loadMaterialThumbnail(context, item)
                                    }
                                }
                                if (thumbBitmap != null) {
                                    Image(
                                        bitmap = thumbBitmap!!.asImageBitmap(),
                                        contentDescription = item.displayLabel,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    PackIcon(
                                        if (item.isVideo) PackGlyph.VIDEO else PackGlyph.PHOTOS,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    item.displayLabel,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val sourceTag = when (item.sourceType) {
                                    MaterialSourceType.LOCAL_LIBRARY -> "내 사진·영상"
                                    MaterialSourceType.PHONE_HARD -> "폰하드"
                                    MaterialSourceType.REMOTE_FILE -> "원격 저장소"
                                }
                                val stateTag = when (item.state) {
                                    MaterialPreparationState.READY -> "준비됨"
                                    MaterialPreparationState.DOWNLOADING -> {
                                        val p = item.downloadProgress?.let { " (${(it * 100).toInt()}%)" } ?: ""
                                        "다운로드 중$p"
                                    }
                                    MaterialPreparationState.MISSING -> {
                                        if (item.sourceType == MaterialSourceType.REMOTE_FILE && item.errorMessage == "가져오기 필요") "가져오기 필요"
                                        else "누락 (재시도 필요)"
                                    }
                                    MaterialPreparationState.EXCLUDED -> "제외됨"
                                }
                                Text(
                                    "$sourceTag · $stateTag",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when (item.state) {
                                        MaterialPreparationState.MISSING -> palette.danger
                                        MaterialPreparationState.READY -> palette.accent
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                                if (!item.errorMessage.isNullOrBlank() && item.errorMessage != "가져오기 필요") {
                                    Text(
                                        item.errorMessage,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.danger,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            // Item Actions: Retry / Exclude toggle / Remove
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (item.state == MaterialPreparationState.MISSING) {
                                    IconButton(onClick = { model.retryPrepareMaterial(item) }) {
                                        Icon(
                                            Icons.Default.Refresh,
                                            contentDescription = "다시 시도",
                                            modifier = Modifier.size(20.dp),
                                            tint = palette.accent
                                        )
                                    }
                                }
                                if (item.state == MaterialPreparationState.EXCLUDED) {
                                    TextButton(onClick = { model.setMaterialExcluded(item.id, false) }) {
                                        Text("포함", fontSize = 12.sp, color = palette.accent)
                                    }
                                } else if (item.state == MaterialPreparationState.MISSING) {
                                    TextButton(onClick = { model.setMaterialExcluded(item.id, true) }) {
                                        Text("제외", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                IconButton(onClick = { model.removeMaterialItem(item.id) }) {
                                    PackIcon(
                                        PackGlyph.CLOSE,
                                        contentDescription = "재료함에서 제거",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Preview confirmation dialog before creation
        if (showCreateConfirmDialog) {
            val missingCount = selectedMaterials.count { it.state == MaterialPreparationState.MISSING }
            val downloadingCount = selectedMaterials.count { it.state == MaterialPreparationState.DOWNLOADING }
            AlertDialog(
                onDismissRequest = { showCreateConfirmDialog = false },
                title = { Text("영상 제작 시작 확인", fontWeight = FontWeight.SemiBold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("선택한 ${selectedMaterials.size}개의 재료로 새 영상을 만듭니다.")
                        if (missingCount > 0 || downloadingCount > 0) {
                            Text(
                                "가져오기가 필요한 ${missingCount + downloadingCount}개 항목은 제작 전 자동으로 다운로드 및 준비 과정을 거칩니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.accent
                            )
                        }
                        Text(
                            "영상 제작 중에도 재료함의 원본 목록은 유지됩니다.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showCreateConfirmDialog = false
                            model.startTrailerFromMaterials(selectedMaterials)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.solidAction,
                            contentColor = palette.onAccent
                        )
                    ) {
                        Text("제작 시작")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateConfirmDialog = false }) {
                        Text("취소")
                    }
                }
            )
        }

        // Single item detail preview dialog
        previewItem?.let { item ->
            Dialog(onDismissRequest = { previewItem = null }) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(item.displayLabel, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            val detailBitmap by produceState<Bitmap?>(initialValue = null, item.id) {
                                value = withContext(Dispatchers.IO) {
                                    loadMaterialThumbnail(context, item, maxDimension = 720)
                                }
                            }
                            if (detailBitmap != null) {
                                Image(
                                    bitmap = detailBitmap!!.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                PackIcon(
                                    if (item.isVideo) PackGlyph.VIDEO else PackGlyph.PHOTOS,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            "출처: ${when(item.sourceType) { MaterialSourceType.LOCAL_LIBRARY -> "내 사진·영상"; MaterialSourceType.PHONE_HARD -> "폰하드"; MaterialSourceType.REMOTE_FILE -> "원격 저장소" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { previewItem = null },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = palette.solidAction,
                                contentColor = palette.onAccent
                            )
                        ) {
                            Text("닫기")
                        }
                    }
                }
            }
        }
    }
}

private suspend fun loadMaterialThumbnail(context: android.content.Context, item: MaterialItem, maxDimension: Int = 180): Bitmap? {
    return runCatching {
        // If local staged file exists, decode directly
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
            val uri = Uri.parse(item.uriString)
            loadCachedLocalMediaItem(context, uri).thumbnail
        } else null
    }.getOrNull()
}
