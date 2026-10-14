package com.armsone.nasfinder.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Local-only photo/video picker for "내 사진·영상". No NAS access, no upload on selection;
 * original media is never written to or deleted. Selected [Uri]s are handed back via
 * [onCreateTrailer] so the caller (view model) can copy them into an operation-specific
 * temp directory before further processing.
 *
 * Integration contract for the caller:
 * - [onBack]: invoked when the user leaves this screen (toolbar back arrow or system back).
 * - [onPreview]: invoked with a single picked [Uri] when the user taps a grid thumbnail;
 *   caller is expected to show its own existing media preview (e.g. BuiltInPreviewScreen) for
 *   that Uri. This screen does not render a preview itself.
 * - [onCreateTrailer]: invoked with the selected subset of picked [Uri]s, in selection order,
 *   when the user taps "예고편 만들기". Only read access to these content Uris is required;
 *   nothing is copied or deleted by this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalPhotoLibraryScreen(
    onBack: () -> Unit,
    onPreview: (Uri) -> Unit,
    onCreateTrailer: (List<Uri>) -> Unit,
    onMusicOverlay: ((List<Uri>) -> Unit)? = null,
    onCopyToPhoneHard: ((List<Uri>, onProgress: (Int, Int) -> Unit, onComplete: (Int, Int) -> Unit) -> Unit)? = null,
) {
    val context = LocalContext.current

    var pickedUris by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var items by remember { mutableStateOf<List<LocalMediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loadedCount by remember { mutableStateOf(0) }
    var totalLoadingCount by remember { mutableStateOf(0) }
    var selected by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }

    var copying by remember { mutableStateOf(false) }
    var copyCurrent by remember { mutableStateOf(0) }
    var copyTotal by remember { mutableStateOf(0) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 100),
    ) { uris ->
        // An empty result means the user cancelled; the previous selection must be preserved.
        if (uris.isNotEmpty()) {
            val newStrings = uris.map(Uri::toString)
            val existing = pickedUris.toSet()
            val appended = newStrings.filterNot { it in existing }
            pickedUris = pickedUris + appended
            selected = selected + appended
        }
    }

    var hasAutoOpenedPicker by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!hasAutoOpenedPicker && pickedUris.isEmpty()) {
            hasAutoOpenedPicker = true
            picker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
            )
        }
    }

    LaunchedEffect(pickedUris) {
        if (pickedUris.isEmpty()) {
            items = emptyList()
            loading = false
            loadedCount = 0
            totalLoadingCount = 0
            return@LaunchedEffect
        }
        loading = true
        loadedCount = 0
        totalLoadingCount = pickedUris.size
        val placeholders = pickedUris.map { LocalMediaItem(Uri.parse(it), null, false, null) }
        items = placeholders
        val semaphore = Semaphore(4)
        val completedCounter = java.util.concurrent.atomic.AtomicInteger(0)
        coroutineScope {
            placeholders.forEachIndexed { index, placeholder ->
                launch {
                    val loaded = semaphore.withPermit { loadLocalMediaItem(context, placeholder.uri) }
                    items = items.toMutableList().also { current ->
                        if (index in current.indices) current[index] = loaded
                    }
                    val currentDone = completedCounter.incrementAndGet()
                    loadedCount = currentDone
                }
            }
        }
        loading = false
    }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("내 사진·영상") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(),
            )
        },
        bottomBar = {
            if (items.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().systemBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (copying) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                "폰하드에 복사 중 · $copyCurrent/${copyTotal}개",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "${selected.size}개 선택됨",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (onCopyToPhoneHard != null) {
                            Button(
                                onClick = {
                                    if (copying || loading || selected.isEmpty()) return@Button
                                    val ordered = selected.toSet()
                                    val orderedUris = items.map(LocalMediaItem::uri)
                                        .filter { it.toString() in ordered }
                                    if (orderedUris.isEmpty()) return@Button
                                    copying = true
                                    copyCurrent = 0
                                    copyTotal = orderedUris.size
                                    onCopyToPhoneHard(
                                        orderedUris,
                                        { current, total ->
                                            copyCurrent = current
                                            copyTotal = total
                                        },
                                        { _, _ ->
                                            copying = false
                                        },
                                    )
                                },
                                enabled = !loading && !copying && selected.isNotEmpty(),
                            ) {
                                Text("폰하드에 복사")
                            }
                        }
                        Button(
                            onClick = {
                                val ordered = selected.toSet()
                                val orderedUris = items.map(LocalMediaItem::uri)
                                    .filter { it.toString() in ordered }
                                onCreateTrailer(orderedUris)
                            },
                            enabled = !loading && !copying && selected.isNotEmpty(),
                        ) {
                            Text("예고편 만들기")
                        }
                        if (onMusicOverlay != null) {
                            Button(
                                onClick = {
                                    val ordered = selected.toSet()
                                    val orderedUris = items.map(LocalMediaItem::uri)
                                        .filter { it.toString() in ordered }
                                    onMusicOverlay(orderedUris)
                                },
                                enabled = !loading && !copying && selected.isNotEmpty(),
                            ) {
                                Text("음악 입히기")
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (items.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp))
                        Spacer(Modifier.size(12.dp))
                        Text(
                            "사진·영상 불러오는 중 · $loadedCount/${totalLoadingCount}개",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Icon(
                            Icons.Default.AddPhotoAlternate,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(12.dp))
                        Text("사진이나 영상을 선택하세요", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.size(16.dp))
                        Button(onClick = {
                            picker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                            )
                        }) {
                            Text("사진/영상 선택")
                        }
                    }
                }
            } else {
                if (loading) {
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(
                            "사진·영상 불러오는 중 · $loadedCount/${totalLoadingCount}개",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("기기에서만 처리합니다. 원본은 변경하지 않습니다.", style = MaterialTheme.typography.labelSmall)
                    Row {
                        Text(
                            if (selected.size == items.size) "전체 해제" else "전체 선택",
                            modifier = Modifier.clickable(enabled = !loading && !copying) {
                                selected = if (selected.size == items.size) {
                                    emptyList()
                                } else {
                                    items.map { it.uri.toString() }
                                }
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = if (!loading && !copying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                        Spacer(Modifier.size(16.dp))
                        Text(
                            "더 추가",
                            modifier = Modifier.clickable(enabled = !loading && !copying) {
                                picker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                                )
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = if (!loading && !copying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(96.dp),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items, key = { it.uri.toString() }) { item ->
                        val uriString = item.uri.toString()
                        val isSelected = uriString in selected
                        LocalMediaThumbnail(
                            item = item,
                            isSelected = isSelected,
                            onTap = { onPreview(item.uri) },
                            onToggleSelected = {
                                selected = if (isSelected) selected - uriString else selected + uriString
                            },
                        )
                    }
                }
                if (loading) {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

private data class LocalMediaItem(
    val uri: Uri,
    val thumbnail: Bitmap?,
    val isVideo: Boolean,
    val durationMs: Long?,
)

@Composable
private fun LocalMediaThumbnail(
    item: LocalMediaItem,
    isSelected: Boolean,
    onTap: () -> Unit,
    onToggleSelected: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onTap),
    ) {
        item.thumbnail?.let { bitmap ->
            SkinAwareThumbnailImage(
                bitmap = bitmap,
                cacheIdentity = "local-media:${item.uri}",
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
        }
        if (item.isVideo) {
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(28.dp),
            )
            item.durationMs?.let { durationMs ->
                Text(
                    formatLocalMediaDuration(durationMs),
                    maxLines = 1,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = .6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
        IconButton(
            onClick = onToggleSelected,
            modifier = Modifier.align(Alignment.TopEnd).size(32.dp).padding(2.dp),
        ) {
            Icon(
                if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = if (isSelected) "선택 해제" else "선택",
                tint = Color.White,
                modifier = Modifier.size(22.dp).background(Color.Black.copy(alpha = .4f), CircleShape),
            )
        }
    }
}

private suspend fun loadLocalMediaItem(context: Context, uri: Uri): LocalMediaItem =
    withContext(Dispatchers.IO) {
        val mimeType = context.contentResolver.getType(uri)
        val isVideo = mimeType?.startsWith("video/") == true
        LocalMediaItem(
            uri = uri,
            thumbnail = loadLocalMediaThumbnail(context, uri, isVideo),
            isVideo = isVideo,
            durationMs = if (isVideo) loadLocalMediaDuration(context, uri) else null,
        )
    }

private fun loadLocalMediaThumbnail(context: Context, uri: Uri, isVideo: Boolean): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // Platform thumbnails never read the full-resolution media into memory.
        context.contentResolver.loadThumbnail(uri, Size(320, 320), null)
    } else if (isVideo) {
        MediaMetadataRetriever().run {
            try {
                setDataSource(context, uri)
                getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                release()
            }
        }
    } else {
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = 4 })
        }
    }
}.getOrNull()

private fun loadLocalMediaDuration(context: Context, uri: Uri): Long? = runCatching {
    MediaMetadataRetriever().run {
        try {
            setDataSource(context, uri)
            extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            release()
        }
    }
}.getOrNull()

private fun formatLocalMediaDuration(durationMs: Long): String {
    val seconds = (durationMs / 1_000.0).roundToInt().coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
