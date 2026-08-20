package com.armsone.nasfinder.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.util.UUID
import kotlin.math.roundToInt

private enum class SelectedMediaKind(
    val title: String,
    val icon: ImageVector,
    val wireName: String,
) {
    MOTION_PHOTO("Motion Photo", Icons.Default.LiveTv, "motionPhoto"),
    PHOTO("사진", Icons.Default.Photo, "photo"),
    VIDEO("영상", Icons.Default.VideoFile, "video"),
    UNKNOWN("알 수 없음", Icons.AutoMirrored.Filled.HelpOutline, "unknown"),
}

private data class SelectedMedia(
    val uri: Uri,
    val kind: SelectedMediaKind,
    val displayName: String,
    val mimeType: String,
    val byteLength: Long,
    val thumbnail: Bitmap?,
    val durationMs: Long?,
)

private enum class PhotoPairingRole {
    NONE,
    SEND,
    RECEIVE,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoTransferScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val receiver = remember { PhotoPairingReceiver() }
    var senderSession by remember { mutableStateOf<PhotoTransferSenderSession?>(null) }
    val scanner = remember {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(context, options)
    }
    var selectedUriStrings by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var selectionGeneration by rememberSaveable { mutableStateOf(0L) }
    var selectedMedia by remember { mutableStateOf(emptyList<SelectedMedia>()) }
    var selectionLoading by remember { mutableStateOf(false) }
    var pairingRole by rememberSaveable { mutableStateOf(PhotoPairingRole.NONE) }
    var pairingStatus by rememberSaveable { mutableStateOf("보내기 또는 받기를 선택하세요.") }
    var pairingQr by remember { mutableStateOf<Bitmap?>(null) }
    var transferInProgress by remember { mutableStateOf(false) }
    var receivedResults by remember { mutableStateOf(emptyList<SavedPhotoTransferResult>()) }
    var receiverStarting by remember { mutableStateOf(false) }
    var receiverConnected by remember { mutableStateOf(false) }
    var receiverUiGeneration by remember { mutableStateOf(0L) }
    var pairingAddress by remember { mutableStateOf<String?>(null) }
    var receiverPeerTitle by remember { mutableStateOf<String?>(null) }
    var receiverFinished by remember { mutableStateOf(false) }
    var receiverError by remember { mutableStateOf<String?>(null) }
    var senderConnecting by remember { mutableStateOf(false) }
    var senderPeerTitle by remember { mutableStateOf<String?>(null) }
    var sentItemCount by remember { mutableStateOf<Int?>(null) }
    var senderError by remember { mutableStateOf<String?>(null) }
    var senderUiGeneration by remember { mutableStateOf(0L) }
    var showPairingModal by remember { mutableStateOf(false) }
    var showDismissConfirmation by remember { mutableStateOf(false) }
    val receiverStartJob = remember { mutableStateOf<Job?>(null) }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 100),
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectionLoading = true
            selectionGeneration += 1L
            selectedUriStrings = uris.map(Uri::toString)
            selectedMedia = uris.map { uri ->
                SelectedMedia(uri, SelectedMediaKind.UNKNOWN, "", "application/octet-stream", -1L, null, null)
            }
        }
    }

    LaunchedEffect(selectedUriStrings, selectionGeneration) {
        selectionLoading = selectedUriStrings.isNotEmpty()
        if (selectedUriStrings.isNotEmpty()) {
            val uris = selectedUriStrings.map(Uri::parse)
            if (selectedMedia.size != uris.size) {
                selectedMedia = uris.map { uri ->
                    SelectedMedia(uri, SelectedMediaKind.UNKNOWN, "", "application/octet-stream", -1L, null, null)
                }
            }
            val semaphore = Semaphore(4)
            coroutineScope {
                uris.mapIndexed { index, uri ->
                    launch {
                        val loaded = semaphore.withPermit { loadSelectedMedia(context, uri) }
                        selectedMedia = selectedMedia.toMutableList().also { current ->
                            if (index in current.indices) current[index] = loaded
                        }
                    }
                }.joinAll()
            }
        } else {
            selectedMedia = emptyList()
        }
        selectionLoading = false
    }

    DisposableEffect(Unit) {
        onDispose {
            receiverStartJob.value?.cancel()
            receiver.close()
        }
    }

    DisposableEffect(senderSession) {
        val session = senderSession
        onDispose { session?.close() }
    }

    val startReceiving: () -> Unit = {
        showPairingModal = true
        senderUiGeneration += 1L
        receiverUiGeneration += 1L
        val uiGeneration = receiverUiGeneration
        receiverStartJob.value?.cancel()
        receiver.close()
        senderSession?.close()
        senderSession = null
        pairingRole = PhotoPairingRole.RECEIVE
        pairingQr = null
        pairingAddress = null
        receivedResults = emptyList()
        receiverFinished = false
        receiverError = null
        receiverPeerTitle = null
        receiverConnected = false
        receiverStarting = true
        pairingStatus = "새 연결 정보를 준비하고 있습니다."
        receiverStartJob.value = scope.launch {
            val result = receiver.start(
                context = context,
                onConnected = { peer ->
                    scope.launch connectionUpdate@{
                        if (receiverUiGeneration != uiGeneration) return@connectionUpdate
                        receiverStarting = false
                        receiverConnected = true
                        receiverPeerTitle = peer.displayName
                        pairingQr = null
                        pairingStatus = "${peer.displayName}와 연결되었습니다. 파일을 기다리고 있습니다."
                    }
                },
                onProgress = { progress ->
                    scope.launch {
                        if (receiverUiGeneration == uiGeneration) {
                            pairingStatus = receiveProgressText(progress)
                        }
                    }
                },
                onComplete = { results ->
                    scope.launch completionUpdate@{
                        if (receiverUiGeneration != uiGeneration) return@completionUpdate
                        receiverStarting = false
                        receiverConnected = false
                        receiverFinished = true
                        receivedResults = results
                        pairingStatus = "${results.size}개 파일을 받았습니다."
                    }
                },
                onFailure = { reason ->
                    scope.launch failureUpdate@{
                        if (receiverUiGeneration != uiGeneration) return@failureUpdate
                        receiverStarting = false
                        receiverConnected = false
                        pairingQr = null
                        receiverError = reason
                        pairingStatus = reason
                    }
                },
            )
            if (!isActive || receiverUiGeneration != uiGeneration) return@launch
            when (result) {
                is PhotoPairingStartResult.Ready -> {
                    val readyQr = pairingQrBitmap(result.payload.encode())
                    if (!isActive || receiverUiGeneration != uiGeneration || receiverConnected) {
                        return@launch
                    }
                    receiverStarting = false
                    pairingQr = readyQr
                    pairingAddress = "${result.payload.host}:${result.payload.port}"
                    pairingStatus = "보내는 기기에서 QR 코드를 스캔하세요."
                }
                PhotoPairingStartResult.NoLocalNetwork -> {
                    receiverStarting = false
                    receiverError = "같은 Wi-Fi에 연결한 뒤 다시 시도하세요."
                    pairingStatus = checkNotNull(receiverError)
                }
                is PhotoPairingStartResult.Failed -> {
                    receiverStarting = false
                    receiverError = result.reason
                    pairingStatus = result.reason
                }
            }
        }
    }

    val selectSendRole: () -> Unit = {
        showPairingModal = false
        senderUiGeneration += 1L
        receiverUiGeneration += 1L
        receiverStartJob.value?.cancel()
        receiver.close()
        receiverStarting = false
        receiverConnected = false
        pairingQr = null
        pairingAddress = null
        senderSession?.close()
        senderSession = null
        senderConnecting = false
        senderPeerTitle = null
        sentItemCount = null
        senderError = null
        pairingRole = PhotoPairingRole.SEND
        pairingStatus = "미디어를 선택하세요."
    }

    val scanReceiverQr: () -> Unit = {
        showPairingModal = true
        senderUiGeneration += 1L
        val scanGeneration = senderUiGeneration
        senderSession?.close()
        senderSession = null
        senderPeerTitle = null
        sentItemCount = null
        senderConnecting = true
        senderError = null
        pairingStatus = "연결 중…"
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                if (senderUiGeneration != scanGeneration) return@addOnSuccessListener
                val payload = barcode.rawValue?.let(PhotoPairingPayload::decode)
                if (payload == null) {
                    senderConnecting = false
                    senderError = "NasFinder 사진 전송 QR 코드가 아니거나 형식이 올바르지 않습니다."
                    pairingStatus = checkNotNull(senderError)
                } else {
                    scope.launch connectionAttempt@{
                        val connection = connectToPhotoPair(payload)
                        if (senderUiGeneration != scanGeneration) {
                            connection.getOrNull()?.close()
                            return@connectionAttempt
                        }
                        connection.fold(
                            onSuccess = { session ->
                                senderSession?.close()
                                senderSession = session
                                senderConnecting = false
                                senderError = null
                                senderPeerTitle = session.peerPlatform.displayName
                                sentItemCount = null
                                pairingStatus = "${session.peerPlatform.displayName} 기기와 연결되었습니다."
                            },
                            onFailure = {
                                senderConnecting = false
                                senderError = it.message ?: "연결하지 못했습니다."
                                pairingStatus = checkNotNull(senderError)
                            },
                        )
                    }
                }
            }
            .addOnFailureListener {
                if (senderUiGeneration != scanGeneration) return@addOnFailureListener
                senderConnecting = false
                senderError = it.message ?: "QR 코드를 읽지 못했습니다."
                pairingStatus = checkNotNull(senderError)
            }
            .addOnCanceledListener {
                if (senderUiGeneration != scanGeneration) return@addOnCanceledListener
                senderConnecting = false
                showPairingModal = false
                pairingStatus = "QR 스캔이 취소되었습니다."
            }
    }

    val sendSelected: () -> Unit = sendSelected@{
        if (transferInProgress || selectionLoading) return@sendSelected
        val session = senderSession
        if (session != null) {
            transferInProgress = true
            val outgoing = selectedMedia.mapNotNull { item ->
                if (item.byteLength < 0L) null else {
                    PhotoTransferOutgoingItem(
                        uri = item.uri,
                        id = UUID.randomUUID().toString(),
                        name = item.displayName,
                        mimeType = item.mimeType,
                        mediaKind = item.kind.wireName,
                        byteLength = item.byteLength,
                    )
                }
            }
            if (outgoing.size != selectedMedia.size) {
                pairingStatus = "크기를 확인할 수 없는 파일이 있어 전송할 수 없습니다."
                transferInProgress = false
            } else {
                senderError = null
                scope.launch {
                    session.send(context, outgoing) { progress ->
                        scope.launch { pairingStatus = sendProgressText(progress) }
                    }.fold(
                        onSuccess = { count ->
                            sentItemCount = count
                            pairingStatus = "${count}개 보내기 완료"
                        },
                        onFailure = {
                            senderError = it.message ?: "파일을 보내지 못했습니다."
                            pairingStatus = checkNotNull(senderError)
                        },
                    )
                    transferInProgress = false
                    session.close()
                    senderSession = null
                }
            }
        }
    }

    val stopAndDismissPairing: () -> Unit = {
        showDismissConfirmation = false
        showPairingModal = false
        receiverUiGeneration += 1L
        senderUiGeneration += 1L
        receiverStartJob.value?.cancel()
        receiver.close()
        senderSession?.close()
        senderSession = null
        receiverStarting = false
        receiverConnected = false
        senderConnecting = false
        transferInProgress = false
        if (pairingRole == PhotoPairingRole.RECEIVE) pairingRole = PhotoPairingRole.NONE
    }
    val pairingDismissNeedsConfirmation =
        (pairingRole == PhotoPairingRole.RECEIVE && receiverConnected && !receiverFinished) ||
            (pairingRole == PhotoPairingRole.SEND && (senderConnecting || transferInProgress) && sentItemCount == null)
    val requestPairingDismiss: () -> Unit = {
        if (pairingDismissNeedsConfirmation) showDismissConfirmation = true else stopAndDismissPairing()
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                modifier = Modifier.height(44.dp),
                title = {
                    Text(
                        "Live Photos & Motion Photos",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    PhotoTransferSection {
                        PhotoTransferActionRow(
                            title = "보내기",
                            icon = Icons.Default.Send,
                            onClick = selectSendRole,
                            enabled = !transferInProgress,
                        )
                        PhotoTransferActionRow(
                            title = "받기",
                            icon = Icons.Default.QrCode,
                            onClick = startReceiving,
                            enabled = !transferInProgress && !receiverStarting && !receiverConnected,
                            accessibilityHint = "수신을 시작하고 QR 코드를 표시합니다.",
                        )
                    }
                }

                if (pairingRole == PhotoPairingRole.SEND) {
                    item {
                        PhotoTransferSection(
                            title = "1 미디어 선택",
                            headerIcon = Icons.Default.CheckCircle,
                            footer = if (selectedMedia.isEmpty()) {
                                "사진, 영상, Motion Photo를 한 번에 여러 개 선택할 수 있습니다."
                            } else {
                                "선택한 항목은 QR 연결 후 한 번에 보낼 수 있습니다."
                            },
                        ) {
                            PhotoTransferActionRow(
                                title = if (selectedMedia.isEmpty()) "사진 보관함에서 선택" else "다시 선택",
                                icon = Icons.Default.AddPhotoAlternate,
                                onClick = {
                                    picker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                                    )
                                },
                            )
                            if (selectionLoading) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Text("미리보기 준비 중…", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }

                    if (selectedMedia.isNotEmpty()) {
                        item {
                            PhotoTransferSection(
                                title = "2 선택 검토 · ${selectedMedia.size}개",
                                headerIcon = Icons.Default.Collections,
                            ) {
                                AdaptiveSelectedMediaGrid(
                                    items = selectedMedia,
                                    onRemove = { index ->
                                        selectionLoading = true
                                        selectedUriStrings = selectedUriStrings.filterIndexed { itemIndex, _ ->
                                            itemIndex != index
                                        }
                                    },
                                )
                            }
                        }
                    }

                    item {
                        PhotoTransferSection(
                            title = "3 다른 폰 연결",
                            headerIcon = Icons.Default.Wifi,
                            footer = "두 기기가 같은 Wi-Fi에 있어야 합니다.",
                        ) {
                            PhotoTransferActionRow(
                                title = "QR 스캔해서 연결",
                                icon = Icons.Default.QrCodeScanner,
                                onClick = scanReceiverQr,
                                enabled = selectedMedia.isNotEmpty() && !selectionLoading &&
                                    !senderConnecting && !transferInProgress,
                                accessibilityHint = "받는 기기에 표시된 QR 코드를 스캔해 연결합니다.",
                            )
                        }
                    }

                }
            }
        }
    }

    if (showPairingModal) {
        PhotoTransferPairingModal(
            role = pairingRole,
            onDismissRequest = requestPairingDismiss,
        ) {
            if (pairingRole == PhotoPairingRole.RECEIVE) {
                ReceiverTransferState(
                    starting = receiverStarting,
                    connected = receiverConnected,
                    finished = receiverFinished,
                    error = receiverError,
                    peerTitle = receiverPeerTitle,
                    qr = pairingQr,
                    address = pairingAddress,
                    status = pairingStatus,
                    results = receivedResults,
                    canRegenerate = pairingQr != null && !receiverStarting && !receiverConnected,
                    onRegenerate = startReceiving,
                    onRetry = startReceiving,
                )
            } else {
                SenderTransferState(
                    connecting = senderConnecting,
                    peerTitle = senderPeerTitle,
                    sentItemCount = sentItemCount,
                    error = senderError,
                    transferInProgress = transferInProgress,
                    status = pairingStatus,
                    selectedCount = selectedMedia.size,
                    canSend = senderSession != null && selectedMedia.isNotEmpty() && !selectionLoading,
                    onSend = sendSelected,
                    onRetry = scanReceiverQr,
                )
            }
        }
    }

    if (showDismissConfirmation) {
        AlertDialog(
            onDismissRequest = { showDismissConfirmation = false },
            title = { Text(if (pairingRole == PhotoPairingRole.RECEIVE) "받기를 중단할까요?" else "보내기를 중단할까요?") },
            text = {
                Text(
                    if (pairingRole == PhotoPairingRole.RECEIVE) {
                        "연결된 기기에서 받고 있는 항목은 완료되지 않습니다."
                    } else {
                        "현재 연결 또는 파일 전송이 완료되지 않습니다."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = stopAndDismissPairing) { Text("중단하고 닫기") }
            },
            dismissButton = {
                TextButton(onClick = { showDismissConfirmation = false }) {
                    Text(if (pairingRole == PhotoPairingRole.RECEIVE) "계속 받기" else "계속 보내기")
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoTransferPairingModal(
    role: PhotoPairingRole,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                CenterAlignedTopAppBar(
                    modifier = Modifier.height(44.dp),
                    title = {
                        Text(
                            if (role == PhotoPairingRole.RECEIVE) "QR 표시해서 받기" else "QR 스캔해서 보내기",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    actions = {
                        TextButton(onClick = onDismissRequest) { Text("닫기", fontSize = 15.sp) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item {
                    Box(modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        content()
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoTransferSection(
    title: String? = null,
    headerIcon: ImageVector? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        title?.let {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                headerIcon?.let { icon ->
                    Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
                }
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .88f),
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = content,
            )
        }
        footer?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f),
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

@Composable
private fun PhotoTransferActionRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    accessibilityHint: String? = null,
) {
    Column {
        TextButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(44.dp).semantics {
                contentDescription = listOfNotNull(title, accessibilityHint).joinToString(". ")
            },
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(title, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
        }
        HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun AdaptiveSelectedMediaGrid(items: List<SelectedMedia>, onRemove: (Int) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        val columnCount = ((maxWidth.value + 10f) / 114f).toInt().coerceIn(1, 6)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items.withIndex().chunked(columnCount).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowItems.forEach { indexed ->
                        SelectedMediaReviewCard(
                            item = indexed.value,
                            index = indexed.index,
                            onRemove = { onRemove(indexed.index) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columnCount - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun AdaptiveReceivedResultsGrid(results: List<SavedPhotoTransferResult>) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        BoxWithConstraints(modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(top = 4.dp)) {
            val columnCount = ((maxWidth.value + 10f) / 106f).toInt().coerceIn(1, 6)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                results.chunked(columnCount).forEach { rowResults ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowResults.forEach { result ->
                            SavedPhotoTransferCard(result, Modifier.weight(1f))
                        }
                        repeat(columnCount - rowResults.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReceiverTransferState(
    starting: Boolean,
    connected: Boolean,
    finished: Boolean,
    error: String?,
    peerTitle: String?,
    qr: Bitmap?,
    address: String?,
    status: String,
    results: List<SavedPhotoTransferResult>,
    canRegenerate: Boolean,
    onRegenerate: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = if (connected || error != null) 40.dp else 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (qr != null) 16.dp else 12.dp),
    ) {
        when {
                error != null -> {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xffff9800),
                        modifier = Modifier.size(40.dp),
                    )
                    Text(error, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onRetry) { Text("다시 시도") }
                }
                starting -> {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    Text("수신 준비 중…")
                }
                qr != null -> {
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = "사진 전송 페어링 QR 코드",
                        modifier = Modifier.size(280.dp),
                    )
                    Text("보내는 기기에서 이 QR 코드를 스캔하세요.", style = MaterialTheme.typography.bodyMedium)
                    address?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("연결 대기 중…", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(onClick = onRegenerate, enabled = canRegenerate) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("QR 다시 만들기", style = MaterialTheme.typography.labelMedium)
                    }
                }
                connected || finished -> {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xff34a853),
                        modifier = Modifier.size(48.dp),
                    )
                    Text("${peerTitle ?: "상대"} 기기와 연결되었습니다.", fontWeight = FontWeight.SemiBold)
                    if (finished) {
                        Text(
                            "${results.size}개 받기 완료",
                            fontSize = 15.sp,
                            color = Color(0xff34a853),
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (results.isNotEmpty()) AdaptiveReceivedResultsGrid(results)
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(status, style = MaterialTheme.typography.labelSmall)
                    }
                }
                else -> Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SenderTransferState(
    connecting: Boolean,
    peerTitle: String?,
    sentItemCount: Int?,
    error: String?,
    transferInProgress: Boolean,
    status: String,
    selectedCount: Int,
    canSend: Boolean,
    onSend: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
                connecting -> {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    Text("연결 중…")
                }
                error != null -> {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xffff9800),
                        modifier = Modifier.size(40.dp),
                    )
                    Text(error, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onRetry) { Text("다시 스캔") }
                }
                peerTitle != null -> {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xff34a853),
                        modifier = Modifier.size(48.dp),
                    )
                    Text("$peerTitle 기기와 연결되었습니다.", fontWeight = FontWeight.SemiBold)
                    when {
                        sentItemCount != null -> Text(
                            "${sentItemCount}개 보내기 완료",
                            fontSize = 15.sp,
                            color = Color(0xff34a853),
                            fontWeight = FontWeight.SemiBold,
                        )
                        transferInProgress -> {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(status, style = MaterialTheme.typography.labelSmall)
                        }
                        else -> Button(onClick = onSend, enabled = canSend) {
                            Text("4 보내기 · ${selectedCount}개")
                        }
                    }
                }
                else -> Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SavedPhotoTransferCard(result: SavedPhotoTransferResult, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val thumbnail by produceState<Bitmap?>(initialValue = null, key1 = result.uri) {
        value = withContext(Dispatchers.IO) { loadSavedPhotoTransferThumbnail(context, result) }
    }
    val icon = when (result.kind) {
        SavedPhotoTransferKind.PHOTO -> Icons.Default.Photo
        SavedPhotoTransferKind.VIDEO -> Icons.Default.VideoFile
        SavedPhotoTransferKind.LIVE_PHOTO,
        SavedPhotoTransferKind.MOTION_PHOTO -> Icons.Default.LiveTv
    }
    Box(
        modifier = modifier
            .height(104.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { contentDescription = "저장 완료, ${result.kind.displayName}" },
        contentAlignment = Alignment.Center,
    ) {
        val loadedThumbnail = thumbnail
        if (loadedThumbnail != null) {
            Image(
                bitmap = loadedThumbnail.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(icon, contentDescription = null, modifier = Modifier.size(34.dp))
        }
        if (result.kind == SavedPhotoTransferKind.VIDEO) {
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(34.dp),
            )
        }
        MediaKindBadge(
            text = result.kind.displayName,
            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
        )
    }
}

private fun loadSavedPhotoTransferThumbnail(context: Context, result: SavedPhotoTransferResult): Bitmap? = runCatching {
    val platformThumbnail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching { context.contentResolver.loadThumbnail(result.uri, Size(320, 240), null) }.getOrNull()
    } else null
    platformThumbnail ?: if (result.kind == SavedPhotoTransferKind.VIDEO) {
        MediaMetadataRetriever().run {
            try {
                setDataSource(context, result.uri)
                getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                release()
            }
        }
    } else {
        context.contentResolver.openInputStream(result.uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = 4 })
        }
    }
}.getOrNull()

@Composable
private fun SelectedMediaReviewCard(
    item: SelectedMedia,
    index: Int,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(112.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { contentDescription = "항목 ${index + 1}, ${item.kind.title}" },
        contentAlignment = Alignment.Center,
    ) {
        item.thumbnail?.let { thumbnail ->
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } ?: Icon(
            item.kind.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(34.dp),
        )
        if (item.kind == SelectedMediaKind.VIDEO) {
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(34.dp),
            )
        }
        MediaKindBadge(
            text = listOfNotNull(item.kind.title, formatDuration(item.durationMs)).joinToString(" "),
            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
        )
        IconButton(
            onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(36.dp),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "항목 ${index + 1} 삭제",
                tint = Color.White,
                modifier = Modifier.size(24.dp).background(Color.Black.copy(alpha = .65f), CircleShape),
            )
        }
    }
}

@Composable
private fun MediaKindBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        modifier = modifier
            .background(Color.Black.copy(alpha = .62f), CircleShape)
            .padding(horizontal = 7.dp, vertical = 4.dp),
    )
}

private suspend fun loadSelectedMedia(context: Context, uri: Uri): SelectedMedia =
    withContext(Dispatchers.IO) {
        val mimeType = context.contentResolver.getType(uri)
        val kind = when {
            mimeType?.startsWith("video/") == true -> SelectedMediaKind.VIDEO
            mimeType?.startsWith("image/") == true -> {
                if (isMotionPhoto(context, uri)) {
                    SelectedMediaKind.MOTION_PHOTO
                } else {
                    SelectedMediaKind.PHOTO
                }
            }
            else -> SelectedMediaKind.UNKNOWN
        }
        var displayName: String? = null
        var byteLength = -1L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameColumn >= 0 && !cursor.isNull(nameColumn)) displayName = cursor.getString(nameColumn)
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) byteLength = cursor.getLong(sizeColumn)
                }
            }
        }
        if (byteLength < 0L) {
            byteLength = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull() ?: -1L
        }
        SelectedMedia(
            uri = uri,
            kind = kind,
            displayName = displayName?.takeIf(String::isNotBlank) ?: "media-${uri.toString().hashCode()}",
            mimeType = mimeType ?: if (kind == SelectedMediaKind.VIDEO) "video/mp4" else "image/jpeg",
            byteLength = byteLength,
            thumbnail = loadMediaThumbnail(context, uri, kind),
            durationMs = if (kind == SelectedMediaKind.VIDEO) loadVideoDuration(context, uri) else null,
        )
    }

private fun loadMediaThumbnail(context: Context, uri: Uri, kind: SelectedMediaKind): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.contentResolver.loadThumbnail(uri, Size(320, 240), null)
    } else if (kind == SelectedMediaKind.VIDEO) {
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

private fun loadVideoDuration(context: Context, uri: Uri): Long? = runCatching {
    MediaMetadataRetriever().run {
        try {
            setDataSource(context, uri)
            extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            release()
        }
    }
}.getOrNull()

private fun formatDuration(durationMs: Long?): String? = durationMs?.let {
    val seconds = (it / 1_000.0).roundToInt().coerceAtLeast(0)
    "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun sendProgressText(progress: PhotoTransferProgress): String {
    val percent = if (progress.totalBytes > 0L) {
        (progress.transferredBytes * 100L / progress.totalBytes).coerceIn(0L, 100L)
    } else {
        100L
    }
    return "${progress.fileName} · $percent% · ${progress.completedFiles}/${progress.totalFiles ?: 0}개"
}

private fun receiveProgressText(progress: PhotoTransferProgress): String {
    val percent = if (progress.totalBytes > 0L) {
        (progress.transferredBytes * 100L / progress.totalBytes).coerceIn(0L, 100L)
    } else {
        100L
    }
    return "${progress.fileName} · $percent% · ${progress.completedFiles}개 저장"
}

private fun isMotionPhoto(context: Context, uri: Uri): Boolean {
    val mediaStoreFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Files.FileColumns.SPECIAL_FORMAT),
            null,
            null,
            null,
        )?.use { cursor ->
            val column = cursor.getColumnIndex(MediaStore.Files.FileColumns.SPECIAL_FORMAT)
            cursor.moveToFirst() && column >= 0 &&
                cursor.getInt(column) == MediaStore.Files.FileColumns.SPECIAL_FORMAT_MOTION_PHOTO
        } ?: false
    }.getOrDefault(false) else false
    if (mediaStoreFlag) return true

    return runCatching {
        val length = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: return false
        if (length !in 1..512L * 1024 * 1024) return false
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream(length.toInt())
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var remaining = length
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count <= 0) return false
                output.write(buffer, 0, count)
                remaining -= count
            }
            output.toByteArray()
        } ?: return false
        MotionPhotoCodec.parse(bytes) != null
    }.getOrDefault(false)
}
