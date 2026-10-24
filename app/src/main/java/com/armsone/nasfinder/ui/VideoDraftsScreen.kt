package com.armsone.nasfinder.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun VideoDraftsScreen(
    state: AppState,
    model: NasFinderViewModel,
    onBack: () -> Unit,
    // 영상 hub embeds this screen under its own title / tab bar: no own title, back arrow or system insets.
    embedded: Boolean = false
) {
    val drafts = remember(state.trailerDraftCount, state.trailerDraftsVersion) { TrailerDraftStore.getAllDrafts() }
    val metadataProblem = remember(state.trailerDraftsVersion) { TrailerDraftStore.metadataProblem() }
    var draftToDelete by remember { mutableStateOf<TrailerDraft?>(null) }
    var draftToRename by remember { mutableStateOf<TrailerDraft?>(null) }
    var renameTitle by remember { mutableStateOf("") }

    BackHandler(enabled = !embedded) { onBack() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            PorcelainTopBar(
                title = if (embedded) "편집본 ${drafts.size}개" else "편집본",
                subtitle = if (embedded) null else "진행 중인 프로젝트 (${drafts.size}개)",
                onBack = if (embedded) null else onBack,
                windowInsets = if (embedded) WindowInsets(0) else TopAppBarDefaults.windowInsets,
                actions = {
                    TextButton(onClick = { model.show(Screen.LocalPhotoLibrary(LocalMediaEditIntent.TRAILER)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("새 프로젝트")
                    }
                },
            )
        },
        contentWindowInsets = if (embedded) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets
    ) { padding ->
        if (drafts.isEmpty()) {
            EmptyState(
                icon = Icons.Default.MovieFilter,
                title = if (metadataProblem != null) "편집본 목록을 읽지 못했습니다" else "보관된 편집본이 없습니다",
                description = "새 영상을 제작하거나 편집을 일시 정지하면 여기에 안전하게 영구 저장됩니다.",
                modifier = Modifier.fillMaxSize().padding(padding),
                action = metadataProblem?.let { problem ->
                    // An unreadable list is reported as such, never as an intentionally empty library.
                    { Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
                },
            )
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                metadataProblem?.let { problem ->
                    item(key = "metadata-problem") {
                        Text(problem, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
                items(drafts, key = { it.id }) { draft ->
                    val unavailable = remember(draft, state.trailerDraftsVersion) { draft.unavailableDetails() }
                    val cover = rememberTrailerDraftCover(draft, maxPixelSize = 360)
                    val seconds = rememberTrailerDraftDuration(draft)
                    val modeTitle = if (draft.isMusicOverlayMode) "뮤직비디오" else "예고편"
                    val lastEdited = remember(draft.timestamp) {
                        android.text.format.DateUtils.getRelativeTimeSpanString(
                            draft.timestamp, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
                            android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE,
                        ).toString()
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(
                                Modifier.fillMaxWidth().clickable(role = Role.Button) {
                                    model.resumeTrailerDraft(draft)
                                },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    Modifier
                                        .width(112.dp)
                                        .aspectRatio(16f / 9f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (cover != null) {
                                        androidx.compose.foundation.Image(
                                            bitmap = cover.asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Icon(Icons.Default.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                                    }
                                }

                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        draft.displayTitle,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "$modeTitle · ${draft.sources.size}개 미디어 · ${trailerLengthTitle(seconds)}",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "수정 $lastEdited",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            // Unavailable projects stay listed with what is missing; nothing is removed automatically.
                            if (unavailable.isNotEmpty() || draft.migrationIssue != null) {
                                Spacer(Modifier.height(6.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    if (unavailable.isNotEmpty()) {
                                        Text("지금 열 수 없음 · 편집 정보와 남은 파일은 보존됨", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                                        unavailable.take(3).forEach { Text("· $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                        if (unavailable.size > 3) Text("· 외 ${unavailable.size - 3}개", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                                    }
                                    draft.migrationIssue?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error) }
                                    TextButton(onClick = { model.recheckTrailerDrafts() }) {
                                        Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("다시 확인")
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            Spacer(Modifier.height(4.dp))

                            FlowRow(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalArrangement = Arrangement.spacedBy(0.dp)
                            ) {
                                TextButton(onClick = { model.resumeTrailerDraft(draft) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                                    Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("이어서 편집", maxLines = 1, softWrap = false)
                                }
                                TextButton(onClick = { model.duplicateTrailerDraft(draft) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                                    Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("복제", maxLines = 1, softWrap = false)
                                }
                                TextButton(onClick = {
                                    draftToRename = draft
                                    renameTitle = draft.displayTitle
                                }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                                    Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("이름 변경", maxLines = 1, softWrap = false)
                                }
                                TextButton(onClick = { model.setDraftPrivate(draft.id, !draft.isPrivate) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                                    Icon(if (draft.isPrivate) Icons.Default.Visibility else Icons.Default.VisibilityOff, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (draft.isPrivate) "공개로" else "비공개로", maxLines = 1, softWrap = false)
                                }
                                TextButton(onClick = { draftToDelete = draft }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                                    Icon(Icons.Default.Delete, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                    Spacer(Modifier.width(4.dp))
                                    Text("삭제", color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
            }
        }

        draftToRename?.let { draft ->
            AlertDialog(
                onDismissRequest = { draftToRename = null },
                title = { Text("편집본 이름 변경") },
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
                        model.renameTrailerDraft(draft, renameTitle)
                        draftToRename = null
                    }) {
                        Text("확인")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { draftToRename = null }) {
                        Text("취소")
                    }
                }
            )
        }

        draftToDelete?.let { draft ->
            AlertDialog(
                onDismissRequest = { draftToDelete = null },
                title = { Text("이 편집본을 지울까요?") },
                text = { Text("이 편집 정보와 앱이 만든 이 프로젝트의 작업 폴더만 삭제됩니다. 원본 미디어, 컬렉션에 보관된 영상은 지워지지 않습니다.") },
                confirmButton = {
                    TextButton(onClick = {
                        model.discardTrailerDraft(draft)
                        draftToDelete = null
                    }) {
                        Text("지우기", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { draftToDelete = null }) {
                        Text("취소")
                    }
                }
            )
        }
    }
}
