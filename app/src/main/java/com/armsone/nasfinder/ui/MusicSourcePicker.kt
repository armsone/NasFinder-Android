package com.armsone.nasfinder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.armsone.nasfinder.model.RemoteConnection
import com.armsone.nasfinder.model.RemoteFavorite
import com.armsone.nasfinder.model.RemoteFileItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

enum class MusicSourceTab(val title: String) {
    PHONE_HARD("폰하드 Music"),
    FAVORITES("폴더 즐겨찾기"),
    NETWORK("네트워크"),
}

private data class DownloadProgress(
    val receivedBytes: Long,
    val totalBytes: Long,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedMusicPickerDialog(
    model: NasFinderViewModel,
    currentTrack: TrailerMusicTrack?,
    onDismiss: () -> Unit,
    onSelectTrack: (TrailerMusicTrack?) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val auditionController = rememberMusicAuditionController()

    // Session cache directory dedicated to this picker dialog instance
    val sessionCacheDir = remember {
        File(context.cacheDir, "music-picker-cache/${UUID.randomUUID()}").also { it.mkdirs() }
    }

    DisposableEffect(Unit) {
        onDispose {
            auditionController.stop()
            // Clean up temporary audition downloads of this session only
            sessionCacheDir.deleteRecursively()
        }
    }

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = MusicSourceTab.entries

    // PhoneHard Music state
    var phoneHardTracks by remember { mutableStateOf<List<TrailerMusicTrack>>(emptyList()) }
    LaunchedEffect(Unit) {
        phoneHardTracks = model.phoneHardAudioFiles()
    }

    // Network & Favorites navigation state
    val state = model.state.value
    val connections = state.connections
    val folderFavorites = remember(state.remoteFavorites) {
        state.remoteFavorites.filter { it.isDirectory }
    }

    // Remote browsing state: connection + path stack + boundary
    var activeConnection by remember { mutableStateOf<RemoteConnection?>(null) }
    var currentRemotePath by remember { mutableStateOf("") }
    var boundaryRootPath by remember { mutableStateOf("") }
    var remoteItems by remember { mutableStateOf<List<RemoteFileItem>>(emptyList()) }
    var isRemoteLoading by remember { mutableStateOf(false) }
    var remoteErrorMessage by remember { mutableStateOf<String?>(null) }
    var browseJob by remember { mutableStateOf<Job?>(null) }
    var browseRequestId by remember { mutableIntStateOf(0) }

    // Download & import state: item.id -> DownloadProgress
    val downloadProgressMap = remember { mutableStateMapOf<String, DownloadProgress>() }
    val downloadingItemIds = remember { mutableStateMapOf<String, Boolean>() }
    val importingItemIds = remember { mutableStateMapOf<String, Boolean>() }
    val itemErrorMessages = remember { mutableStateMapOf<String, String>() }
    val downloadJobs = remember { mutableMapOf<String, Job>() }
    // Cached downloaded files for audition: item.id -> File
    val cachedAuditionFiles = remember { mutableMapOf<String, File>() }

    fun cancelItemDownload(itemId: String) {
        downloadJobs[itemId]?.cancel()
        downloadJobs.remove(itemId)
        downloadingItemIds.remove(itemId)
        importingItemIds.remove(itemId)
        downloadProgressMap.remove(itemId)
    }

    fun loadRemoteFolder(conn: RemoteConnection, path: String, rootBoundary: String? = null) {
        browseJob?.cancel()
        val currentRequestId = ++browseRequestId
        activeConnection = conn
        currentRemotePath = path
        if (rootBoundary != null) {
            boundaryRootPath = rootBoundary
        }
        isRemoteLoading = true
        remoteErrorMessage = null
        browseJob = coroutineScope.launch {
            val result = model.listRemoteAudioFiles(conn, path)
            if (browseRequestId == currentRequestId && activeConnection?.id == conn.id && currentRemotePath == path) {
                isRemoteLoading = false
                result.fold(
                    onSuccess = { items ->
                        remoteItems = items.sortedWith(
                            compareByDescending<RemoteFileItem> { it.isDirectory }.thenBy { it.name }
                        )
                    },
                    onFailure = { error ->
                        remoteErrorMessage = error.message ?: "원격 폴더를 불러오지 못했습니다."
                        remoteItems = emptyList()
                    }
                )
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (importingItemIds.isEmpty()) {
                auditionController.stop()
                onDismiss()
            }
        },
        title = {
            Text("배경 음악 선택", fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 340.dp, max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PrimaryTabRow(
                    selectedTabIndex = selectedTabIndex,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = {
                                if (selectedTabIndex != index) {
                                    selectedTabIndex = index
                                    auditionController.stop()
                                    browseJob?.cancel()
                                    browseJob = null
                                    activeConnection = null
                                    currentRemotePath = ""
                                    boundaryRootPath = ""
                                    remoteItems = emptyList()
                                    remoteErrorMessage = null
                                    isRemoteLoading = false
                                }
                            },
                            text = {
                                Text(
                                    tab.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            icon = {
                                when (tab) {
                                    MusicSourceTab.PHONE_HARD -> Icon(Icons.Default.Smartphone, null, Modifier.size(18.dp))
                                    MusicSourceTab.FAVORITES -> Icon(Icons.Default.FolderSpecial, null, Modifier.size(18.dp))
                                    MusicSourceTab.NETWORK -> Icon(Icons.Default.Cloud, null, Modifier.size(18.dp))
                                }
                            }
                        )
                    }
                }

                HorizontalDivider()

                when (tabs[selectedTabIndex]) {
                    MusicSourceTab.PHONE_HARD -> {
                        if (phoneHardTracks.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "폰하드 Music 폴더에 음악이 없습니다.\n네트워크나 즐겨찾기, 브라우저에서 음악을 받아보세요.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(phoneHardTracks, key = { it.file.absolutePath }) { track ->
                                    MusicPickerRow(
                                        track = track,
                                        isSelected = currentTrack?.file?.absolutePath == track.file.absolutePath,
                                        auditionController = auditionController,
                                        onSelect = {
                                            auditionController.stop()
                                            onSelectTrack(track)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    MusicSourceTab.FAVORITES -> {
                        if (activeConnection != null) {
                            // Showing contents of favorite folder
                            RemoteFolderView(
                                connection = activeConnection!!,
                                currentPath = currentRemotePath,
                                items = remoteItems,
                                isLoading = isRemoteLoading,
                                errorMessage = remoteErrorMessage,
                                auditionController = auditionController,
                                downloadingItemIds = downloadingItemIds,
                                importingItemIds = importingItemIds,
                                itemErrorMessages = itemErrorMessages,
                                downloadProgressMap = downloadProgressMap,
                                onBack = {
                                    val parent = safeRemoteParent(currentRemotePath, boundaryRootPath)
                                    if (parent == null) {
                                        browseJob?.cancel()
                                        browseJob = null
                                        activeConnection = null
                                        remoteItems = emptyList()
                                        remoteErrorMessage = null
                                        isRemoteLoading = false
                                    } else {
                                        loadRemoteFolder(activeConnection!!, parent)
                                    }
                                },
                                onRefresh = { loadRemoteFolder(activeConnection!!, currentRemotePath) },
                                onFolderClick = { subDir ->
                                    loadRemoteFolder(activeConnection!!, subDir.path)
                                },
                                onAuditionClick = { item ->
                                    handleRemoteAudition(
                                        connection = activeConnection!!,
                                        item = item,
                                        sessionCacheDir = sessionCacheDir,
                                        cachedAuditionFiles = cachedAuditionFiles,
                                        downloadJobs = downloadJobs,
                                        downloadingItemIds = downloadingItemIds,
                                        itemErrorMessages = itemErrorMessages,
                                        downloadProgressMap = downloadProgressMap,
                                        auditionController = auditionController,
                                        coroutineScope = coroutineScope,
                                        model = model
                                    )
                                },
                                onUseClick = { item ->
                                    handleRemoteUse(
                                        connection = activeConnection!!,
                                        item = item,
                                        sessionCacheDir = sessionCacheDir,
                                        cachedAuditionFiles = cachedAuditionFiles,
                                        downloadJobs = downloadJobs,
                                        downloadingItemIds = downloadingItemIds,
                                        importingItemIds = importingItemIds,
                                        itemErrorMessages = itemErrorMessages,
                                        downloadProgressMap = downloadProgressMap,
                                        auditionController = auditionController,
                                        coroutineScope = coroutineScope,
                                        model = model,
                                        onSuccess = onSelectTrack
                                    )
                                },
                                onCancelDownload = { itemId -> cancelItemDownload(itemId) }
                            )
                        } else {
                            if (folderFavorites.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "등록된 NAS 폴더 즐겨찾기가 없습니다.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    items(folderFavorites, key = { it.id }) { fav ->
                                        FavoriteFolderRow(fav, connections) { conn ->
                                            loadRemoteFolder(conn, fav.path, rootBoundary = fav.path)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    MusicSourceTab.NETWORK -> {
                        if (activeConnection == null) {
                            if (connections.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "등록된 네트워크 연결이 없습니다.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    items(connections, key = { it.id }) { conn ->
                                        ConnectionRow(conn) {
                                            loadRemoteFolder(conn, conn.normalizedRootPath, rootBoundary = conn.normalizedRootPath)
                                        }
                                    }
                                }
                            }
                        } else {
                            RemoteFolderView(
                                connection = activeConnection!!,
                                currentPath = currentRemotePath,
                                items = remoteItems,
                                isLoading = isRemoteLoading,
                                errorMessage = remoteErrorMessage,
                                auditionController = auditionController,
                                downloadingItemIds = downloadingItemIds,
                                importingItemIds = importingItemIds,
                                itemErrorMessages = itemErrorMessages,
                                downloadProgressMap = downloadProgressMap,
                                onBack = {
                                    val parent = safeRemoteParent(currentRemotePath, boundaryRootPath)
                                    if (parent == null) {
                                        browseJob?.cancel()
                                        browseJob = null
                                        activeConnection = null
                                        remoteItems = emptyList()
                                        remoteErrorMessage = null
                                        isRemoteLoading = false
                                    } else {
                                        loadRemoteFolder(activeConnection!!, parent)
                                    }
                                },
                                onRefresh = { loadRemoteFolder(activeConnection!!, currentRemotePath) },
                                onFolderClick = { subDir ->
                                    loadRemoteFolder(activeConnection!!, subDir.path)
                                },
                                onAuditionClick = { item ->
                                    handleRemoteAudition(
                                        connection = activeConnection!!,
                                        item = item,
                                        sessionCacheDir = sessionCacheDir,
                                        cachedAuditionFiles = cachedAuditionFiles,
                                        downloadJobs = downloadJobs,
                                        downloadingItemIds = downloadingItemIds,
                                        itemErrorMessages = itemErrorMessages,
                                        downloadProgressMap = downloadProgressMap,
                                        auditionController = auditionController,
                                        coroutineScope = coroutineScope,
                                        model = model
                                    )
                                },
                                onUseClick = { item ->
                                    handleRemoteUse(
                                        connection = activeConnection!!,
                                        item = item,
                                        sessionCacheDir = sessionCacheDir,
                                        cachedAuditionFiles = cachedAuditionFiles,
                                        downloadJobs = downloadJobs,
                                        downloadingItemIds = downloadingItemIds,
                                        importingItemIds = importingItemIds,
                                        itemErrorMessages = itemErrorMessages,
                                        downloadProgressMap = downloadProgressMap,
                                        auditionController = auditionController,
                                        coroutineScope = coroutineScope,
                                        model = model,
                                        onSuccess = onSelectTrack
                                    )
                                },
                                onCancelDownload = { itemId -> cancelItemDownload(itemId) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (importingItemIds.isEmpty()) {
                        auditionController.stop()
                        onDismiss()
                    }
                },
                enabled = importingItemIds.isEmpty()
            ) {
                Text("닫기")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (importingItemIds.isEmpty()) {
                        auditionController.stop()
                        onSelectTrack(null)
                    }
                },
                enabled = importingItemIds.isEmpty()
            ) {
                Text("음악 없음")
            }
        }
    )
}

private fun safeRemoteParent(path: String, boundaryRoot: String): String? {
    val normPath = path.trimEnd('/').ifBlank { "/" }
    val normBoundary = boundaryRoot.trimEnd('/').ifBlank { "/" }
    if (normPath == normBoundary || normPath == "/" || normPath == ".") return null
    val parent = normPath.substringBeforeLast('/', "").ifBlank {
        if (normBoundary.startsWith('/')) "/" else "."
    }
    if (normBoundary != "/" && normBoundary != "." && !parent.startsWith(normBoundary)) {
        return null
    }
    return parent
}

@Composable
private fun FavoriteFolderRow(
    favorite: RemoteFavorite,
    connections: List<RemoteConnection>,
    onClick: (RemoteConnection) -> Unit,
) {
    val conn = connections.firstOrNull { it.id == favorite.connectionId }
    Surface(
        onClick = { if (conn != null) onClick(conn) },
        enabled = conn != null,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.FolderSpecial, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(favorite.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    conn?.name ?: "연결 없음 (${favorite.path})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ConnectionRow(
    connection: RemoteConnection,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(connection.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    connection.host,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RemoteFolderView(
    connection: RemoteConnection,
    currentPath: String,
    items: List<RemoteFileItem>,
    isLoading: Boolean,
    errorMessage: String?,
    auditionController: MusicAuditionController,
    downloadingItemIds: Map<String, Boolean>,
    importingItemIds: Map<String, Boolean>,
    itemErrorMessages: Map<String, String>,
    downloadProgressMap: Map<String, DownloadProgress>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onFolderClick: (RemoteFileItem) -> Unit,
    onAuditionClick: (RemoteFileItem) -> Unit,
    onUseClick: (RemoteFileItem) -> Unit,
    onCancelDownload: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "상위 폴더")
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = if (currentPath.isBlank()) connection.name else currentPath.substringAfterLast('/'),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Refresh, "새로고침")
            }
        }

        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            }
            errorMessage != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(onClick = onRefresh) {
                        Text("다시 시도")
                    }
                }
            }
            items.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "폴더나 오디오 파일이 없습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(items, key = { it.id }) { item ->
                        if (item.isDirectory) {
                            Surface(
                                onClick = { onFolderClick(item) },
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        item.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        } else {
                            RemoteAudioRow(
                                item = item,
                                auditionController = auditionController,
                                isDownloading = downloadingItemIds[item.id] == true,
                                isImporting = importingItemIds[item.id] == true,
                                errorMessage = itemErrorMessages[item.id],
                                progress = downloadProgressMap[item.id],
                                onAuditionClick = { onAuditionClick(item) },
                                onUseClick = { onUseClick(item) },
                                onCancelDownload = { onCancelDownload(item.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteAudioRow(
    item: RemoteFileItem,
    auditionController: MusicAuditionController,
    isDownloading: Boolean,
    isImporting: Boolean,
    errorMessage: String?,
    progress: DownloadProgress?,
    onAuditionClick: () -> Unit,
    onUseClick: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    val isAuditioning = auditionController.playingPath?.endsWith(item.name) == true
    val isPreparingThis = isAuditioning && auditionController.isPreparing

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Play / Stop button for audition
                IconButton(
                    onClick = {
                        if (isDownloading) onCancelDownload() else onAuditionClick()
                    },
                    enabled = !isImporting,
                    modifier = Modifier.size(36.dp)
                ) {
                    when {
                        isDownloading -> CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        isPreparingThis -> CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        isAuditioning -> Icon(
                            Icons.Default.Stop,
                            contentDescription = "미리듣기 정지",
                            tint = MaterialTheme.colorScheme.error
                        )
                        else -> Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "미리듣기",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(Modifier.width(6.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (item.size > 0L) {
                            Text(
                                formatBytes(item.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isDownloading && progress != null && progress.totalBytes > 0L) {
                            Text(
                                "${(progress.receivedBytes * 100 / progress.totalBytes)}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else if (isImporting) {
                            Text(
                                "음악 보관 중…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else if (isAuditioning && !isPreparingThis) {
                            Text(
                                "재생 중",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else if (errorMessage != null) {
                            Text(
                                errorMessage,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(Modifier.width(6.dp))

                // '사용' (Use) Button
                Button(
                    onClick = onUseClick,
                    enabled = !isDownloading && !isImporting,
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    if (isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text("사용", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // Linear Progress Bar if actively downloading
            if (isDownloading) {
                Spacer(Modifier.height(4.dp))
                if (progress != null && progress.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = { (progress.receivedBytes.toFloat() / progress.totalBytes).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                    )
                }
            }
        }
    }
}

private fun handleRemoteAudition(
    connection: RemoteConnection,
    item: RemoteFileItem,
    sessionCacheDir: File,
    cachedAuditionFiles: MutableMap<String, File>,
    downloadJobs: MutableMap<String, Job>,
    downloadingItemIds: MutableMap<String, Boolean>,
    itemErrorMessages: MutableMap<String, String>,
    downloadProgressMap: MutableMap<String, DownloadProgress>,
    auditionController: MusicAuditionController,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    model: NasFinderViewModel,
) {
    itemErrorMessages.remove(item.id)
    val existingFile = cachedAuditionFiles[item.id]
    if (existingFile != null && existingFile.exists()) {
        val track = TrailerMusicTrack(
            file = existingFile,
            name = item.name,
            durationSeconds = 0.0
        )
        auditionController.toggleAudition(track)
        return
    }

    // Download to session cache
    val dest = File(sessionCacheDir, "${UUID.randomUUID()}-${safeFilename(item.name)}")
    downloadingItemIds[item.id] = true
    val job = coroutineScope.launch {
        try {
            val result = model.downloadRemoteAudioToCache(
                connection = connection,
                item = item,
                destinationFile = dest,
                progress = { received, total ->
                    downloadProgressMap[item.id] = DownloadProgress(received, total)
                }
            )
            downloadingItemIds.remove(item.id)
            downloadProgressMap.remove(item.id)
            result.fold(
                onSuccess = {
                    cachedAuditionFiles[item.id] = dest
                    val track = TrailerMusicTrack(
                        file = dest,
                        name = item.name,
                        durationSeconds = 0.0
                    )
                    auditionController.toggleAudition(track)
                },
                onFailure = { error ->
                    dest.delete()
                    itemErrorMessages[item.id] = error.message ?: "음악 다운로드 실패"
                }
            )
        } catch (c: CancellationException) {
            dest.delete()
            throw c
        }
    }
    downloadJobs[item.id] = job
}

private fun handleRemoteUse(
    connection: RemoteConnection,
    item: RemoteFileItem,
    sessionCacheDir: File,
    cachedAuditionFiles: MutableMap<String, File>,
    downloadJobs: MutableMap<String, Job>,
    downloadingItemIds: MutableMap<String, Boolean>,
    importingItemIds: MutableMap<String, Boolean>,
    itemErrorMessages: MutableMap<String, String>,
    downloadProgressMap: MutableMap<String, DownloadProgress>,
    auditionController: MusicAuditionController,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    model: NasFinderViewModel,
    onSuccess: (TrailerMusicTrack) -> Unit,
) {
    if (importingItemIds[item.id] == true || downloadingItemIds[item.id] == true) return
    auditionController.stop()
    itemErrorMessages.remove(item.id)

    val existingFile = cachedAuditionFiles[item.id]
    if (existingFile != null && existingFile.exists()) {
        importingItemIds[item.id] = true
        coroutineScope.launch {
            try {
                val importResult = model.importAudioFileToMusic(
                    sourceFile = existingFile,
                    originalFilename = item.name,
                    mimeType = item.mimeType
                )
                importingItemIds.remove(item.id)
                importResult.fold(
                    onSuccess = { track ->
                        onSuccess(track)
                    },
                    onFailure = { error ->
                        itemErrorMessages[item.id] = error.message ?: "음악 보관 실패"
                    }
                )
            } catch (c: CancellationException) {
                importingItemIds.remove(item.id)
                throw c
            }
        }
        return
    }

    // Download then import
    val dest = File(sessionCacheDir, "${UUID.randomUUID()}-${safeFilename(item.name)}")
    downloadingItemIds[item.id] = true
    val job = coroutineScope.launch {
        try {
            val result = model.downloadRemoteAudioToCache(
                connection = connection,
                item = item,
                destinationFile = dest,
                progress = { received, total ->
                    downloadProgressMap[item.id] = DownloadProgress(received, total)
                }
            )
            downloadingItemIds.remove(item.id)
            downloadProgressMap.remove(item.id)
            result.fold(
                onSuccess = {
                    importingItemIds[item.id] = true
                    val importResult = model.importAudioFileToMusic(
                        sourceFile = dest,
                        originalFilename = item.name,
                        mimeType = item.mimeType
                    )
                    dest.delete()
                    importingItemIds.remove(item.id)
                    importResult.fold(
                        onSuccess = { track ->
                            onSuccess(track)
                        },
                        onFailure = { error ->
                            itemErrorMessages[item.id] = error.message ?: "음악 보관 실패"
                        }
                    )
                },
                onFailure = { error ->
                    dest.delete()
                    itemErrorMessages[item.id] = error.message ?: "음악 다운로드 실패"
                }
            )
        } catch (c: CancellationException) {
            dest.delete()
            downloadingItemIds.remove(item.id)
            importingItemIds.remove(item.id)
            throw c
        }
    }
    downloadJobs[item.id] = job
}

private fun safeFilename(name: String): String {
    return name.replace('/', '_').replace('\\', '_').trim()
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var digit = bytes.toDouble()
    var idx = 0
    while (digit >= 1024.0 && idx < units.lastIndex) {
        digit /= 1024.0
        idx++
    }
    return "%.1f %s".format(digit, units[idx])
}
