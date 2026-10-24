@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.armsone.nasfinder.ui

import android.content.Intent
import androidx.compose.material.icons.filled.Warning
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.armsone.nasfinder.platform.NasFinderShareIntentFactory
import com.hanai.core.ImageSelectionRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 도구 → 사진 선별. One flow for the direct picker and for Share → 이미지 선별:
 * photos (videos excluded and counted) → on-device analysis → recommended count with its reason → the user's own
 * count → result preview → explicit confirm → 저장 / 공유 / 영상 재료에 추가. Originals are never moved or deleted.
 */
@Composable
internal fun PhotoSelectionScreen(
    session: PhotoSelectionSession,
    onBack: () -> Unit,
    onShowMessage: (String) -> Unit,
    pendingShares: List<PendingMediaShare> = emptyList(),
    onOpenPendingShares: () -> Unit = {},
    onDiscardPendingShare: (String) -> Unit = {},
    // 작업대에 담기 destination: the existing pockets and a way to create one in place.
    pockets: List<MaterialPocket> = emptyList(),
    pocketCount: (String) -> Int = { 0 },
    activePocketId: String = DEFAULT_POCKET_ID,
    onCreatePocket: (String) -> String? = { null },
) {
    val state by session.state.collectAsStateWithLifecycle()
    var choosingPocket by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Re-read the pending inbox whenever the flow returns to idle, so a finished/cancelled batch never hides the others.
    LaunchedEffect(state.phase) { if (state.phase == PhotoSelectionPhase.IDLE) session.refreshResumable() }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) session.start(uris)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) session.start(uris.distinct())
    }
    val running = state.phase == PhotoSelectionPhase.STAGING || state.phase == PhotoSelectionPhase.ANALYZING ||
        state.phase == PhotoSelectionPhase.WORKING
    var confirmCancel by remember { mutableStateOf(false) }
    var analysisDetailsExpanded by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            PorcelainTopBar(title = "사진 선별", onBack = onBack)
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state.phase) {
                PhotoSelectionPhase.IDLE -> {
                    item {
                        SelectionCard {
                            Text("사진을 고르면 이 기기 안에서 분석해 몇 장이 적당한지 추천합니다.", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "동영상은 선별에서 제외됩니다. 원본 사진은 옮기거나 지우지 않습니다. 사진 수에는 제한이 없습니다.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = {
                                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("사진 보관함에서 고르기") }
                            OutlinedButton(
                                onClick = { filePicker.launch(arrayOf("image/*", "video/*")) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("파일에서 고르기 (개수 제한 없음)") }
                            Text(
                                "사진 보관함 선택은 시스템이 허용하는 개수까지 고를 수 있고, 파일에서 고르기는 개수 제한이 없습니다.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (pendingShares.isNotEmpty()) {
                        item {
                            SelectionCard {
                                Text("공유받아 대기 중인 항목 ${pendingShares.size}건", fontWeight = FontWeight.Medium)
                                pendingShares.forEach { share ->
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            "사진 ${share.images.size}장 · 동영상 ${share.videos.size}개" +
                                                if (share.failures.isNotEmpty()) " · 복사 실패 ${share.failures.size}개" else "",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f),
                                        )
                                        TextButton(onClick = { onDiscardPendingShare(share.id) }) { Text("제외") }
                                    }
                                }
                                Button(onClick = onOpenPendingShares) { Text("선택하러 가기") }
                            }
                        }
                    }
                    if (state.pendingBatches.isNotEmpty()) {
                        item {
                            Text(
                                "이전에 준비한 선별 ${state.pendingBatches.size}건",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                            )
                        }
                        items(state.pendingBatches, key = { it.id }) { batch ->
                            SelectionCard {
                                Text("사진 ${batch.photos.size}장" + if (batch.skippedVideos > 0) " · 동영상 ${batch.skippedVideos}개 제외" else "", fontWeight = FontWeight.Medium)
                                if (batch.issues.isNotEmpty()) {
                                    Text("준비하지 못한 사진 ${batch.issues.size}장", style = MaterialTheme.typography.labelSmall)
                                }
                                batch.problem?.let { ErrorLine(it) }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (batch.photos.isNotEmpty()) {
                                        Button(onClick = { session.resume(batch.id) }) { Text("이어서 선별") }
                                    }
                                    if (batch.problem != null) {
                                        TextButton(onClick = session::recheck) { Text("다시 확인") }
                                    }
                                    OutlinedButton(onClick = { session.discard(batch.id) }) { Text("이 묶음 제외하고 정리") }
                                }
                            }
                        }
                    }
                    state.error?.let { item { ErrorLine(it) } }
                    state.message?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
                }

                PhotoSelectionPhase.STAGING, PhotoSelectionPhase.ANALYZING, PhotoSelectionPhase.WORKING -> item {
                    SelectionCard {
                        val progress = state.progress
                        if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(state.progressLabel ?: "처리 중…", style = MaterialTheme.typography.bodyMedium)
                        if (state.skippedVideosNotice > 0) {
                            Text("동영상 ${state.skippedVideosNotice}개는 제외됩니다.", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(onClick = { if (state.phase == PhotoSelectionPhase.WORKING) session.cancel() else confirmCancel = true }) {
                            Text("취소")
                        }
                    }
                }

                PhotoSelectionPhase.BLOCKED -> {
                    item {
                        SelectionCard {
                            ErrorLine(state.error ?: "사진을 분석하지 못했습니다.")
                            Text(
                                "원본 사진은 그대로입니다. 다시 분석하거나, 취소하고 다른 사진을 골라 주세요.",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (state.batch != null && state.batch!!.photos.isNotEmpty()) {
                                    Button(onClick = session::retryAnalysis) { Text("다시 분석") }
                                }
                                OutlinedButton(onClick = { confirmCancel = true }) { Text("취소") }
                            }
                        }
                    }
                    issueItems(state.analysis?.failures.orEmpty())
                }

                PhotoSelectionPhase.SETUP -> {
                    val recommendation = state.recommendation
                    val analysis = state.analysis
                    if (recommendation != null && analysis != null) {
                        val failedCount = analysis.failures.size + recommendation.rejected.size
                        item {
                            SelectionCard {
                                Text("추천 ${recommendation.recommended}장", fontWeight = FontWeight.SemiBold)
                                if (failedCount > 0) {
                                    Text(
                                        "분석하지 못한 사진 ${failedCount}장 · 아래 분석 상세에서 확인",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                OutlinedTextField(
                                    value = state.countText,
                                    onValueChange = session::setCountText,
                                    label = { Text("선별할 사진 수") },
                                    singleLine = true,
                                    isError = state.countError != null,
                                    supportingText = {
                                        Text(state.countError ?: "추천 ${recommendation.recommended}장 · 고를 수 있는 범위 1–${recommendation.eligible}장")
                                    },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (state.countText != recommendation.recommended.toString()) {
                                    TextButton(onClick = { session.setCountText(recommendation.recommended.toString()) }) {
                                        Text("추천 ${recommendation.recommended}장으로 바꾸기")
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = session::showPreview, enabled = state.countError == null && state.countText.isNotEmpty()) {
                                        Text("결과 미리보기")
                                    }
                                    OutlinedButton(onClick = { confirmCancel = true }) { Text("취소") }
                                }
                            }
                        }
                        item {
                            SelectionCard {
                                TextButton(onClick = { analysisDetailsExpanded = !analysisDetailsExpanded }) {
                                    Text(if (analysisDetailsExpanded) "분석 상세 접기" else "분석 상세 펼치기")
                                }
                                if (analysisDetailsExpanded) {
                                    PhotoSelectionPolicy.explanation(recommendation, analysis).forEach {
                                        Text(it, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        if (analysisDetailsExpanded) issueItems(analysis.failures + recommendation.rejected)
                    }
                }

                PhotoSelectionPhase.PREVIEW -> {
                    val outcome = state.outcome
                    val batch = state.batch
                    if (outcome != null && batch != null) {
                        item {
                            SelectionCard {
                                Text("선별 결과 ${outcome.selectedIndices.size}장", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "비슷한 장면 ${outcome.groups}묶음에서 골랐습니다.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (outcome.includesSimilarExtras) {
                                    Text(
                                        "요청한 장수가 비슷한 장면 묶음 수보다 많아 같은 장면의 다른 사진이 함께 들어갔습니다.",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                        val byId = batch.photos.associateBy { it.id }
                        val selected = outcome.selectedIndices.mapNotNull(byId::get)
                        items(selected.chunked(3), key = { row -> row.first().id }) { row ->
                            Row(Modifier.widthIn(max = 560.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { photo ->
                                    SelectedPhotoCard(
                                        photo = photo,
                                        role = outcome.roleByIndex[photo.id],
                                        lowQuality = photo.id in outcome.lowQualityIndices,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                        item {
                            SelectionCard {
                                if (!state.confirmed) {
                                    Text("결과를 확인한 뒤 확정하면 저장·공유·작업대 담기를 고를 수 있습니다.", style = MaterialTheme.typography.labelMedium)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = session::confirm) { Text("이 결과로 확정") }
                                        OutlinedButton(onClick = session::backToSetup) { Text("개수 다시 정하기") }
                                    }
                                } else {
                                    Text("확정했습니다. 무엇을 할까요?", fontWeight = FontWeight.Medium)
                                    Button(onClick = session::saveToGallery, modifier = Modifier.fillMaxWidth()) {
                                        Text("사진 보관함에 저장")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                val files = session.prepareShareFiles()
                                                if (files.isNullOrEmpty()) {
                                                    onShowMessage("공유할 사진을 준비하지 못했습니다.")
                                                } else {
                                                    runCatching {
                                                        val intent = NasFinderShareIntentFactory.createMultipleUncapped(context, files)
                                                        context.startActivity(Intent.createChooser(intent, "선별한 사진 공유"))
                                                    }.onFailure { onShowMessage(it.message ?: "공유 화면을 열지 못했습니다.") }
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text("공유") }
                                    OutlinedButton(onClick = { choosingPocket = true }, modifier = Modifier.fillMaxWidth()) {
                                        Text("작업대에 담기")
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = session::backToSetup) { Text("개수 다시 정하기") }
                                        TextButton(onClick = session::finish) { Text("완료 · 임시 사본 정리") }
                                    }
                                }
                                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (choosingPocket) {
        WorkbenchPocketChooser(
            title = "작업대에 담기",
            message = "선별한 사진은 앱 안의 임시 사본이라 안전하게 사본 그대로 담습니다. 담을 포켓을 고르세요.",
            pockets = pockets,
            countOf = pocketCount,
            initialPocketId = activePocketId,
            confirmLabel = "담기",
            onDismiss = { choosingPocket = false },
            onChooseExisting = { id -> choosingPocket = false; session.addToVideoMaterials(id) },
            onChooseNew = { name -> choosingPocket = false; onCreatePocket(name)?.let(session::addToVideoMaterials) },
        )
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("사진 선별을 취소할까요?") },
            text = { Text("이 선별을 위해 만든 임시 사본만 지웁니다. 원본 사진과 다른 대기 항목은 그대로입니다.") },
            confirmButton = { TextButton(onClick = { confirmCancel = false; session.cancel() }) { Text("취소하고 정리") } },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("계속") } },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.issueItems(issues: List<PhotoSelectionIssue>) {
    if (issues.isEmpty()) return
    item {
        Text(
            "분석하지 못한 사진 ${issues.size}장",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
        )
    }
    items(issues) { issue ->
        Text(
            "· ${issue.name} — ${issue.reason}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SelectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .88f)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun ErrorLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(androidx.compose.material.icons.Icons.Default.Warning, null, Modifier.size(18.dp), tint = com.armsone.nasfinder.ui.theme.themePalette().warn)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun SelectedPhotoCard(photo: StagedPhoto, role: ImageSelectionRole?, lowQuality: Boolean, modifier: Modifier = Modifier) {
    val thumbnail by produceState<Bitmap?>(initialValue = null, key1 = photo.file.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                BitmapFactory.decodeFile(photo.file.absolutePath, BitmapFactory.Options().apply { inSampleSize = 8 })
            }.getOrNull()
        }
    }
    val roleText = when (role) {
        ImageSelectionRole.GROUP_REPRESENTATIVE -> "대표"
        ImageSelectionRole.EXTRA_GROUP_MEMBER -> "비슷한 장면에서 추가"
        null -> ""
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(
            Modifier.fillMaxWidth().height(104.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            thumbnail?.let {
                Image(it.asImageBitmap(), photo.displayName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Text(photo.displayName, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(roleText.takeIf { it.isNotEmpty() }, "품질 낮음".takeIf { lowQuality }).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Share → NasFinder for photos/videos: exactly two actions (plus cancel). 영상 만들기 only appends to 영상 재료.
 * Cancelling leaves every other pending item and the inbox untouched.
 */
@Composable
internal fun MediaShareChoiceDialog(
    share: PendingMediaShare,
    waitingCount: Int,
    onImageSelection: () -> Unit,
    onMakeVideo: () -> Unit,
    onLater: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("공유받은 미디어") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("사진 ${share.images.size}장 · 동영상 ${share.videos.size}개를 받았습니다. 무엇을 할까요?")
                if (share.failures.isNotEmpty()) {
                    Text(
                        "${share.failures.size}개는 복사하지 못해 제외되었습니다.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (waitingCount > 1) {
                    Text("대기 중인 공유 ${waitingCount}건 중 가장 먼저 받은 항목입니다.", style = MaterialTheme.typography.labelMedium)
                }
                Text(
                    "이미지 선별: 사진만 분석해 몇 장이 적당한지 추천합니다." +
                        if (share.videos.isNotEmpty()) " 동영상 ${share.videos.size}개는 제외됩니다." else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "영상 만들기: 영상 재료에 추가만 하고 편집본은 만들지 않습니다.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.End) {
                Button(onClick = onImageSelection, enabled = share.images.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text("이미지 선별")
                }
                OutlinedButton(onClick = onMakeVideo, enabled = share.itemCount > 0, modifier = Modifier.fillMaxWidth()) { Text("영상 만들기") }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onLater) { Text("나중에") }
                    TextButton(onClick = onCancel) { Text("취소 · 사본 삭제") }
                }
            }
        },
    )
}
