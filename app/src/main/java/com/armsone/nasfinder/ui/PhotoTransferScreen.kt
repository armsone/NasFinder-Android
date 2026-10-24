package com.armsone.nasfinder.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.util.UUID
import kotlin.math.roundToInt

private enum class PhotoPairingRole {
    NONE,
    SEND,
    RECEIVE,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoTransferScreen(state: AppState, model: NasFinderViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The receive-ready listener/QR lives in a ViewModel so rotation or window resize does not drop it.
    val receiveController: PhotoTransferReceiveController = viewModel()
    val receiveUi by receiveController.ui.collectAsState()
    var senderSession by remember { mutableStateOf<PhotoTransferSenderSession?>(null) }
    val scanner = remember {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(context, options)
    }
    // 보낼 항목은 작업대 포켓의 참조입니다. 고르기만 하면 원본은 복사하지 않고, 보내기를 누른 실행에서만 읽습니다.
    val pocketItems = remember(state.materials, state.workbenchPocketId, state.pockets) {
        state.materials.filter { item -> (if (state.pockets.any { it.id == item.pocketId }) item.pocketId else DEFAULT_POCKET_ID) == state.workbenchPocketId }
    }
    val selectedItems = pocketItems.filter { it.id in state.workbenchSelected && it.state != MaterialPreparationState.EXCLUDED }
    var sendJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // Unsupported items found when a send starts: the user decides before anything is sent (never silently dropped).
    var issuePrompt by remember { mutableStateOf<Pair<WorkbenchRunPlan, kotlinx.coroutines.CompletableDeferred<Boolean>>?>(null) }
    // Fetch / permission failures found AFTER the run started: sending fewer items needs the user's explicit choice.
    var fetchPrompt by remember { mutableStateOf<Triple<List<WorkbenchRunIssue>, Int, kotlinx.coroutines.CompletableDeferred<Boolean>>?>(null) }
    var runIssues by remember { mutableStateOf(emptyList<WorkbenchRunIssue>()) }
    var sentEntries by remember { mutableStateOf(emptyMap<String, WorkbenchSendEntry>()) }
    // Stable per selection (wire contract §2.1): reconnects and retries of the same selection reuse it, so a
    // receiver that already committed an item recognises it instead of saving a duplicate.
    // rememberSaveable alone does not survive a full process restart, so the id (with receiver and prepared items)
    // is also journaled per selection: the same selection resumes the same transfer, never a duplicate.
    val senderJournal = remember { PhotoTransferSenderJournal(context) }
    var transferId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var sendReport by remember { mutableStateOf<PhotoTransferSendReport?>(null) }
    var sourceInfos by remember { mutableStateOf(emptyMap<String, PhotoTransferSourceInfo>()) }
    var sendNames by remember { mutableStateOf(emptyMap<String, String>()) }
    var deleteOriginals by remember { mutableStateOf(false) }
    var deleteStatus by remember { mutableStateOf<String?>(null) }
    var deleteWorking by remember { mutableStateOf(false) }
    var deleteConfirmation by remember { mutableStateOf(false) }
    var verifiedDeleteIds by remember { mutableStateOf(emptyList<String>()) }
    var pairingRole by rememberSaveable { mutableStateOf(PhotoPairingRole.NONE) }
    var pairingStatus by rememberSaveable { mutableStateOf("보내기 또는 받기를 선택하세요.") }
    var transferInProgress by remember { mutableStateOf(false) }
    var senderConnecting by remember { mutableStateOf(false) }
    var senderPeerTitle by remember { mutableStateOf<String?>(null) }
    var sentItemCount by remember { mutableStateOf<Int?>(null) }
    var senderError by remember { mutableStateOf<String?>(null) }
    var senderUiGeneration by remember { mutableStateOf(0L) }
    var showSenderModal by remember { mutableStateOf(false) }
    var showDismissConfirmation by remember { mutableStateOf(false) }
    val receiverStarting = receiveUi.starting
    val receiverConnected = receiveUi.connected
    val receiverFinished = receiveUi.finished
    // A new pick/removal means the previous send report no longer describes the selection.
    val onPendingChanged: () -> Unit = {
        sendReport = null
        sourceInfos = emptyMap()
        deleteOriginals = false
        deleteStatus = null
        runIssues = emptyList()
    }
    // 미디어 / Google 포토 / 파일 (+ 폴더 inside 파일): every pick becomes references in a pocket the user chooses.
    val pickers = rememberWorkbenchPickers(model, afterAdd = { onPendingChanged() })
    var filesOrFolder by remember { mutableStateOf(false) }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val ids = verifiedDeleteIds
        verifiedDeleteIds = emptyList()
        deleteWorking = false
        val infos = sourceInfos
        val gone = ids.count { id ->
            (infos[id] as? PhotoTransferSourceInfo.Deletable)?.let { PhotoTransferSourceDelete.isGone(context, it.snapshot.mediaUri) } == true
        }
        deleteStatus = when {
            gone == ids.size && ids.isNotEmpty() -> "원본 ${gone}개를 삭제했습니다."
            gone > 0 -> "원본 ${gone}/${ids.size}개만 삭제되었습니다. 나머지는 그대로 남아 있습니다."
            result.resultCode == android.app.Activity.RESULT_OK -> "삭제 결과를 확인하지 못했습니다. 원본이 남아 있을 수 있습니다."
            else -> "삭제하지 않았습니다. 원본은 그대로 남아 있습니다."
        }
    }

    // One transfer id per selection. Not recomputed while sending or while a send report is still relevant,
    // so a partly confirmed send is resumed under the same id.
    val selectionKey = selectedItems.joinToString("|") { it.sourceIdentity }
    LaunchedEffect(selectionKey, transferInProgress, sendReport != null) {
        if (transferInProgress || sendReport != null) return@LaunchedEffect
        transferId = if (selectedItems.isEmpty()) UUID.randomUUID().toString() else {
            runCatching { senderJournal.resolve(selectedItems.map { it.sourceIdentity }) }
                .getOrElse { UUID.randomUUID().toString() }
        }
    }
    // 미디어 전송 pressed on the 작업대: open straight in 보내기 (the QR / explicit send are still the user's).
    LaunchedEffect(state.workbenchSendRequested) {
        if (state.workbenchSendRequested) {
            pairingRole = PhotoPairingRole.SEND
            pairingStatus = "항목을 확인하고 QR을 스캔하세요."
            model.consumeWorkbenchSendRequest()
        }
    }

    // Leaving the screen for real (not a rotation/resize) releases the receive listener; configuration changes keep it.
    val hostActivity = remember(context) { context.findTransferHostActivity() }
    DisposableEffect(receiveController) {
        onDispose {
            if (hostActivity?.isChangingConfigurations != true) receiveController.release()
        }
    }

    DisposableEffect(senderSession) {
        val session = senderSession
        onDispose { session?.close() }
    }

    val startReceiving: () -> Unit = {
        showSenderModal = false
        senderUiGeneration += 1L
        senderSession?.close()
        senderSession = null
        pairingRole = PhotoPairingRole.RECEIVE
        receiveController.start()
    }

    val selectSendRole: () -> Unit = {
        showSenderModal = false
        senderUiGeneration += 1L
        receiveController.stop()
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
        showSenderModal = true
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
                    senderError = "NasFinder 미디어 전송 QR 코드가 아니거나 형식이 올바르지 않습니다."
                    pairingStatus = checkNotNull(senderError)
                } else {
                    scope.launch connectionAttempt@{
                        val connection = connectToPhotoPair(context, payload)
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
                                pairingStatus = "${session.peerPlatform.displayName} 기기와 연결되었습니다." +
                                    if (session.legacyTokenExposed) {
                                        " 이전 방식으로 연결되어 이 QR은 노출되었으므로 원본 삭제를 사용할 수 없습니다. " +
                                            "삭제하려면 받는 기기에서 새 QR을 만들어 다시 스캔하세요."
                                    } else ""
                            },
                            onFailure = {
                                senderConnecting = false
                                senderError = it.userMessage("연결하지 못했습니다. 두 기기가 같은 Wi-Fi에 있는지 확인한 뒤 다시 시도해 주세요.")
                                pairingStatus = checkNotNull(senderError)
                            },
                        )
                    }
                }
            }
            .addOnFailureListener {
                if (senderUiGeneration != scanGeneration) return@addOnFailureListener
                senderConnecting = false
                senderError = it.userMessage("QR 코드를 읽지 못했습니다. 받는 기기의 QR을 다시 비춰 주세요.")
                pairingStatus = checkNotNull(senderError)
            }
            .addOnCanceledListener {
                if (senderUiGeneration != scanGeneration) return@addOnCanceledListener
                senderConnecting = false
                showSenderModal = false
                pairingStatus = "QR 스캔이 취소되었습니다."
            }
    }

    val sendSelected: () -> Unit = sendSelected@{
        if (transferInProgress) return@sendSelected
        val session = senderSession ?: return@sendSelected
        // The explicit SEND acts on exactly the selection the user reviewed, as it is right now.
        val snapshot = selectedItems.toList()
        if (snapshot.isEmpty()) {
            pairingStatus = "보낼 항목이 없습니다."
            return@sendSelected
        }
        transferInProgress = true
        val currentTransferId = transferId
        senderError = null
        sendReport = null
        deleteOriginals = false
        deleteStatus = null
        runIssues = emptyList()
        sendJob = scope.launch {
            // Temporary workspace owned by THIS run (NAS downloads only). Removed in finally, whatever the outcome.
            var runDirectory: java.io.File? = null
            try {
                // 1. Folders are listed now (their CURRENT media), unsupported items are reported before anything is sent.
                pairingStatus = "항목을 확인하는 중…"
                val plan = model.planWorkbenchSend(snapshot) { name, found -> pairingStatus = "$name · 미디어 ${found}개 확인" }
                if (plan.issues.isNotEmpty()) {
                    runIssues = plan.issues
                    val decision = kotlinx.coroutines.CompletableDeferred<Boolean>()
                    issuePrompt = plan to decision
                    val proceed = try { decision.await() } finally { issuePrompt = null }
                    if (!proceed) {
                        pairingStatus = "보내기를 취소했습니다. 작업대 항목은 그대로 남아 있습니다."
                        return@launch
                    }
                }
                if (plan.leaves.isEmpty()) {
                    pairingStatus = "보낼 수 있는 미디어가 없습니다."
                    senderError = pairingStatus
                    return@launch
                }
                // 2. Read the inputs: local references stream from where they are, NAS items are downloaded into the run workspace.
                val materialized = model.materializeWorkbenchForSend(plan.leaves) { index, total, name ->
                    pairingStatus = "준비 중 ${index + 1}/$total · $name"
                }
                runDirectory = materialized.runDirectory
                runIssues = plan.issues + materialized.failures
                val entries = materialized.entries
                if (materialized.failures.isNotEmpty()) {
                    // Never turn a failed fetch into a quietly smaller send: ask, and clean this run's workspace on cancel.
                    val decision = kotlinx.coroutines.CompletableDeferred<Boolean>()
                    fetchPrompt = Triple(materialized.failures, entries.size, decision)
                    val proceed = try { decision.await() } finally { fetchPrompt = null }
                    if (!proceed) {
                        pairingStatus = "보내기를 취소했습니다. 읽지 못한 항목은 작업대에 그대로 남아 있고 ‘다시 연결’할 수 있습니다."
                        return@launch
                    }
                }
                if (entries.isEmpty()) {
                    senderError = "읽을 수 있는 항목이 없어 보내지 않았습니다. 작업대에서 ‘다시 연결’할 수 있습니다."
                    pairingStatus = checkNotNull(senderError)
                    return@launch
                }
                val outgoing = entries.map { entry ->
                    PhotoTransferOutgoingItem(
                        uri = entry.media.uri,
                        // Stable per transfer + reference identity (not the temporary file name), so retries reuse the same ids.
                        id = stableTransferItemId(currentTransferId, Uri.parse("workbench:${entry.leaf.item.sourceIdentity}")),
                        name = entry.media.name,
                        mimeType = entry.media.mimeType,
                        mediaKind = entry.media.mediaKind,
                        byteLength = entry.media.byteLength,
                    )
                }
                sentEntries = entries.indices.associate { outgoing[it].id to entries[it] }
                // Snapshot every original before the first byte leaves, so a later delete can prove "unchanged".
                val infos = withContext(Dispatchers.IO) {
                    outgoing.indices.associate { index ->
                        val item = entries[index].leaf.item
                        outgoing[index].id to runCatching {
                            if (item.sourceType != MaterialSourceType.LOCAL_LIBRARY || item.localFilePath != null || item.uriString.isBlank()) {
                                PhotoTransferSourceInfo.NotDeletable("작업대 참조나 NAS·폰하드 항목은 원본 삭제 대상에서 제외했습니다.")
                            } else {
                                PhotoTransferSourceDelete.inspect(context, Uri.parse(item.uriString), outgoing[index].byteLength)
                            }
                        }.getOrElse { PhotoTransferSourceInfo.NotDeletable("원본에 더 이상 접근할 수 없어 삭제 대상에서 제외했습니다.") }
                    }
                }
                sourceInfos = infos
                sendNames = outgoing.associate { it.id to it.name }
                val expectedReceiver = senderJournal.load(currentTransferId)?.receiverId
                runCatching {
                    senderJournal.recordPrepared(
                        currentTransferId,
                        outgoing.mapIndexed { index, it -> SenderPreparedItem(it.id, "workbench:${entries[index].leaf.item.sourceIdentity}", it.name, it.byteLength) },
                    )
                }
                session.send(context, outgoing, currentTransferId, expectedReceiverId = expectedReceiver) { progress ->
                    scope.launch { pairingStatus = sendProgressText(progress) }
                }.fold(
                    onSuccess = { report ->
                        sendReport = report
                        report.receiverId?.let { id -> runCatching { senderJournal.recordReceiver(currentTransferId, id) } }
                        val verifiedIds = report.outcomes.values.filter { it.committed && it.inputsVerified }.map { it.itemId }.toSet()
                        val allVerified = report.receiptProtocol && verifiedIds.size == outgoing.size
                        runCatching { senderJournal.recordOutcome(currentTransferId, verifiedIds, allVerified) }
                        val confirmed = verifiedIds.size
                        val mismatched = report.outcomes.values.count { it.committed && !it.inputsVerified }
                        sentItemCount = if (report.receiptProtocol) confirmed else report.sentCount
                        pairingStatus = sendCompletionText(report.receiptProtocol, report.sentCount, confirmed, mismatched, outgoing.size) +
                            outputFailureNote(failedOutputItemIds(report).size)
                        // The pocket keeps every reference after success: only the user removes references.
                    },
                    onFailure = {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        senderError = it.userMessage("파일을 보내지 못했습니다. 두 기기가 같은 Wi-Fi에 있는지 확인한 뒤 다시 시도해 주세요.") + if (session.receiptProtocol) {
                            " 작업대 항목은 그대로 남아 있습니다. 다시 연결해 보내면 이미 저장된 항목은 건너뛰고 이어서 확인합니다."
                        } else " 작업대 항목은 그대로 남아 있습니다."
                        pairingStatus = checkNotNull(senderError)
                    },
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                pairingStatus = "보내기를 취소했습니다. 작업대 항목은 그대로 남아 있습니다."
                throw cancelled
            } catch (error: Exception) {
                senderError = error.userMessage("보내기를 준비하지 못했습니다.") + " 작업대 항목은 그대로 남아 있습니다."
                pairingStatus = checkNotNull(senderError)
            } finally {
                model.finishWorkbenchSend(runDirectory)
                transferInProgress = false
                session.close()
                senderSession = null
            }
        }
    }

    val stopAndDismissPairing: () -> Unit = {
        showDismissConfirmation = false
        showSenderModal = false
        senderUiGeneration += 1L
        receiveController.stop()
        sendJob?.cancel()
        sendJob = null
        senderSession?.close()
        senderSession = null
        senderConnecting = false
        transferInProgress = false
        if (pairingRole == PhotoPairingRole.RECEIVE) pairingRole = PhotoPairingRole.NONE
    }
    val runOriginalDelete: () -> Unit = runDelete@{
        val report = sendReport ?: return@runDelete
        if (deleteWorking) return@runDelete
        deleteWorking = true
        deleteStatus = null
        scope.launch {
            val infos = sourceInfos
            val verified = mutableListOf<String>()
            var changed = 0
            withContext(Dispatchers.IO) {
                report.outcomes.values.filter { it.committed && it.inputsVerified }.forEach { outcome ->
                    val info = infos[outcome.itemId] as? PhotoTransferSourceInfo.Deletable ?: return@forEach
                    val sha = report.originHashes[outcome.itemId] ?: return@forEach
                    if (PhotoTransferSourceDelete.verifyUnchanged(context, info.snapshot, sha) == null) {
                        verified += outcome.itemId
                    } else {
                        changed += 1
                    }
                }
            }
            if (verified.isEmpty()) {
                deleteWorking = false
                deleteStatus = if (changed > 0) "보낸 뒤 바뀐 원본 ${changed}개는 삭제하지 않았습니다." else "삭제할 수 있는 원본이 없습니다."
                return@launch
            }
            val sender = PhotoTransferSourceDelete.createDeleteRequest(
                context,
                verified.map { (infos.getValue(it) as PhotoTransferSourceInfo.Deletable).snapshot.mediaUri },
            )
            if (sender == null) {
                deleteWorking = false
                deleteStatus = "삭제 요청을 만들 수 없습니다. 원본은 그대로 남아 있습니다."
                return@launch
            }
            verifiedDeleteIds = verified
            deleteStatus = if (changed > 0) "바뀐 원본 ${changed}개는 제외하고 삭제 동의를 요청합니다." else null
            deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
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
            PorcelainTopBar(title = "미디어 전송", onBack = onBack)
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            // 미디어 전송 uses the whole content pane on every window size (no centered column cap).
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
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
                            design = PackGlyph.RECEIVE,
                            onClick = startReceiving,
                            enabled = !transferInProgress && !receiverStarting && !receiverConnected,
                            accessibilityHint = "수신을 시작하고 QR 코드를 표시합니다.",
                        )
                    }
                }

                if (pairingRole == PhotoPairingRole.SEND) {
                    item {
                        PhotoTransferSection(
                            title = "1 미디어 고르기",
                            headerIcon = Icons.Default.CheckCircle,
                            footer = "고른 항목은 작업대 포켓에 위치·권한 정보만 담깁니다. 원본은 복사하거나 내려받지 않고 ‘보내기’를 누른 순간에만 읽습니다. " +
                                "출처를 바꾸거나 선택을 취소해도 포켓과 선택은 그대로이고, 여러 출처에서 계속 담을 수 있습니다.",
                        ) {
                            PhotoTransferActionRow(
                                title = "미디어",
                                subtitle = "Android 사진 선택기 · 기기에 클라우드 사진이 연결되어 있으면 함께 보일 수 있음",
                                icon = Icons.Default.AddPhotoAlternate,
                                onClick = pickers.pickMedia,
                                enabled = !transferInProgress,
                            )
                            PhotoTransferActionRow(
                                title = "Google 포토",
                                subtitle = if (pickers.googlePhotosAvailable) "Google 포토 앱의 선택 화면 · 로그인이나 업로드 없이 고른 항목만 담음"
                                else "Google 포토 앱이 설치되어 있지 않아 사용할 수 없음",
                                icon = Icons.Default.Photo,
                                onClick = pickers.pickGooglePhotos,
                                enabled = pickers.googlePhotosAvailable && !transferInProgress,
                                accessibilityHint = "Google 포토 앱에서 미디어를 고릅니다.",
                            )
                            PhotoTransferActionRow(
                                title = "파일",
                                subtitle = "파일 선택기 · 파일이나 폴더를 담음 · 이 방식으로 고른 기기 저장소 원본만 보낸 뒤 삭제를 고를 수 있음",
                                icon = Icons.Default.Collections,
                                onClick = { filesOrFolder = true },
                                enabled = !transferInProgress,
                                accessibilityHint = "파일 또는 폴더를 고릅니다. 폴더는 보내기를 누를 때 안의 미디어만 확인합니다.",
                            )
                        }
                    }

                    item {
                        PocketSendHeader(
                            state = state,
                            selectedCount = selectedItems.size,
                            enabled = !transferInProgress,
                            onSelectPocket = { onPendingChanged(); model.selectWorkbenchPocket(it) },
                            onSelectAll = {
                                onPendingChanged()
                                val ids = pocketItems.filter { it.state != MaterialPreparationState.EXCLUDED }.map { it.id }
                                val all = ids.isNotEmpty() && ids.all { it in state.workbenchSelected }
                                model.setWorkbenchSelected(if (all) state.workbenchSelected - ids.toSet() else state.workbenchSelected + ids)
                            },
                            onCreatePocket = { model.createWorkbenchPocket(it) },
                        )
                    }
                    if (pocketItems.isEmpty()) {
                        item {
                            Text(
                                "이 포켓은 비어 있습니다. 위에서 출처를 골라 담거나 다른 포켓을 선택하세요.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }
                    items(pocketItems, key = { "pocket-item-${it.id}" }) { item ->
                        WorkbenchItemRow(
                            item = item,
                            selected = item.id in state.workbenchSelected,
                            onToggle = { if (!transferInProgress) { onPendingChanged(); model.toggleWorkbenchSelected(item.id) } },
                            onRemove = if (transferInProgress) null else ({ onPendingChanged(); model.removeMaterialItem(item.id) }),
                        )
                    }
                    if (runIssues.isNotEmpty()) {
                        item {
                            Text(
                                "보내지 못한 항목 ${runIssues.size}개 (작업대에는 그대로 남아 있고 ‘다시 연결’할 수 있습니다)\n" +
                                    runIssues.joinToString("\n") { "• ${it.name}: ${it.reason}" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }

                    item {
                        PhotoTransferSection(
                            title = "2 다른 폰 연결 후 보내기",
                            headerIcon = Icons.Default.Wifi,
                            footer = "두 기기가 같은 Wi-Fi에 있어야 합니다. 연결한 뒤 ‘보내기’를 눌러야 전송이 시작됩니다.",
                        ) {
                            PhotoTransferActionRow(
                                title = "QR 스캔해서 연결",
                                icon = Icons.Default.QrCodeScanner,
                                onClick = scanReceiverQr,
                                enabled = selectedItems.isNotEmpty() && !senderConnecting && !transferInProgress,
                                accessibilityHint = "받는 기기에 표시된 QR 코드를 스캔해 연결합니다.",
                            )
                        }
                    }

                }
            }
        }
    }

    if (filesOrFolder) FilesOrFolderDialog(pickers) { filesOrFolder = false }

    fetchPrompt?.let { (failures, available, decision) ->
        WorkbenchPartialDialog(failures, available, "보내기", onContinue = { decision.complete(true) }, onCancel = { decision.complete(false) })
    }

    issuePrompt?.let { (plan, decision) ->
        AlertDialog(
            onDismissRequest = { decision.complete(false) },
            title = { Text("보낼 수 없는 항목이 있습니다", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(
                    Modifier.heightIn(max = 320.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        if (plan.leaves.isEmpty()) "보낼 수 있는 미디어가 없습니다." else "아래 항목은 제외하고 미디어 ${plan.leaves.size}개를 보낼 수 있습니다. 작업대 항목은 그대로 남습니다.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    plan.issues.forEach { Text("• ${it.name}: ${it.reason}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) }
                    plan.notes.forEach { Text("• $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            },
            confirmButton = { TextButton(onClick = { decision.complete(true) }) { Text(if (plan.leaves.isEmpty()) "확인" else "제외하고 보내기") } },
            dismissButton = { TextButton(onClick = { decision.complete(false) }) { Text("취소") } },
        )
    }

    val pairingModalVisible = if (pairingRole == PhotoPairingRole.RECEIVE) receiveUi.modalOpen else showSenderModal
    if (pairingModalVisible) {
        PhotoTransferPairingModal(
            role = pairingRole,
            onDismissRequest = requestPairingDismiss,
        ) {
            if (pairingRole == PhotoPairingRole.RECEIVE) {
                ReceiverTransferState(
                    starting = receiveUi.starting,
                    connected = receiveUi.connected,
                    finished = receiveUi.finished,
                    error = receiveUi.error,
                    peerTitle = receiveUi.peerTitle,
                    qr = receiveUi.qr,
                    address = receiveUi.address,
                    status = receiveUi.status,
                    results = receiveUi.results,
                    partialNote = receiveUi.partialNote,
                    canRegenerate = receiveUi.qr != null && !receiveUi.starting && !receiveUi.connected,
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
                    selectedCount = selectedItems.size,
                    canSend = senderSession != null && selectedItems.isNotEmpty(),
                    onSend = sendSelected,
                    onRetry = scanReceiverQr,
                    notice = if (senderSession?.legacyTokenExposed == true) {
                        "이전 방식으로 연결되어 이 QR은 노출되었으므로 원본 삭제를 사용할 수 없습니다. " +
                            "삭제하려면 받는 기기에서 새 QR을 만든 뒤 다시 스캔하세요."
                    } else null,
                    afterSend = {
                        sendReport?.let { report ->
                            val failedIds = failedOutputItemIds(report)
                            if (failedIds.isNotEmpty()) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(outputFailureNote(failedIds.size).trim(), style = MaterialTheme.typography.bodySmall)
                                    OutlinedButton(
                                        onClick = {
                                            // Explicit user choice after the explanation: the not-yet-confirmed items go under a NEW
                                            // transfer id. Nothing is removed from the pocket and originals are never touched; the
                                            // selection just narrows to the references that were not confirmed.
                                            val verifiedIds = report.outcomes.values.filter { it.committed && it.inputsVerified }.map { it.itemId }.toSet()
                                            val retainedEntries = sentEntries.filterKeys { it !in verifiedIds }.values
                                            val retainedRoots = retainedEntries.map { it.leaf.rootId }.toSet()
                                            if (retainedRoots.isNotEmpty()) {
                                                val retainedIdentities = selectedItems.filter { it.id in retainedRoots }.map { it.sourceIdentity }
                                                model.setWorkbenchSelected(retainedRoots)
                                                transferId = runCatching { senderJournal.startNew(retainedIdentities) }
                                                    .getOrElse { UUID.randomUUID().toString() }
                                                onPendingChanged()
                                                sentItemCount = null
                                                pairingStatus = "새로 보내기 준비: ${retainedRoots.size}개 항목을 새 전송으로 보냅니다. 받는 기기에서 새 QR을 만들어 다시 연결하세요."
                                            }
                                        },
                                        enabled = !transferInProgress,
                                    ) { Text("새로 보내기 (${failedIds.size}개 포함)") }
                                }
                            }
                            SenderOriginalDeleteSection(
                                report = report,
                                infos = sourceInfos,
                                names = sendNames,
                                enabled = deleteOriginals,
                                onEnabledChange = { deleteOriginals = it },
                                status = deleteStatus,
                                working = deleteWorking,
                                onRequestDelete = { deleteConfirmation = true },
                            )
                        }
                    },
                )
            }
        }
    }

    if (deleteConfirmation) {
        AlertDialog(
            onDismissRequest = { deleteConfirmation = false },
            title = { Text("보낸 원본을 삭제할까요?") },
            text = {
                Text(
                    "상대 기기가 저장을 확인했고, 보낸 뒤 바뀌지 않은 원본만 삭제 대상입니다. " +
                        "Android가 삭제 동의 화면을 한 번 더 보여 주며, 거절하면 아무것도 지워지지 않습니다.",
                )
            },
            confirmButton = {
                TextButton(onClick = { deleteConfirmation = false; runOriginalDelete() }) { Text("확인하고 계속") }
            },
            dismissButton = { TextButton(onClick = { deleteConfirmation = false }) { Text("취소") } },
        )
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
                        "현재 연결 또는 파일 전송이 완료되지 않습니다. 작업대 항목은 그대로 남습니다."
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
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).appSafeArea(),
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
    subtitle: String? = null,
    design: PackGlyph? = null,
) {
    Column {
        TextButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = if (subtitle == null) 44.dp else 60.dp).semantics {
                contentDescription = listOfNotNull(title, subtitle, accessibilityHint).joinToString(". ")
            },
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), design = design)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp)
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outlineVariant)
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
    partialNote: String?,
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
                        tint = com.armsone.nasfinder.ui.theme.themePalette().warn,
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
                        contentDescription = "미디어 전송 페어링 QR 코드",
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
                        tint = com.armsone.nasfinder.ui.theme.themePalette().accent,
                        modifier = Modifier.size(48.dp),
                    )
                    Text("${peerTitle ?: "상대"} 기기와 연결되었습니다.", fontWeight = FontWeight.Medium)
                    if (finished) {
                        Text(
                            if (partialNote == null) "${results.size}개 받기 완료" else "${results.size}개 저장됨 · 일부만 받음",
                            fontSize = 15.sp,
                            color = if (partialNote == null) MaterialTheme.colorScheme.onSurface else com.armsone.nasfinder.ui.theme.themePalette().warn,
                            fontWeight = FontWeight.SemiBold,
                        )
                        partialNote?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
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
    notice: String? = null,
    afterSend: @Composable () -> Unit = {},
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
                        tint = com.armsone.nasfinder.ui.theme.themePalette().warn,
                        modifier = Modifier.size(40.dp),
                    )
                    Text(error, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onRetry) { Text("다시 스캔") }
                }
                peerTitle != null -> {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = com.armsone.nasfinder.ui.theme.themePalette().accent,
                        modifier = Modifier.size(48.dp),
                    )
                    Text("$peerTitle 기기와 연결되었습니다.", fontWeight = FontWeight.Medium)
                    when {
                        sentItemCount != null -> {
                            Text(
                                "${sentItemCount}개 보내기 완료",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                            )
                            afterSend()
                        }
                        transferInProgress -> {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(status, style = MaterialTheme.typography.labelSmall)
                        }
                        else -> {
                            notice?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Button(onClick = onSend, enabled = canSend) {
                                Text("4 보내기 · ${selectedCount}개")
                            }
                        }
                    }
                }
                else -> Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Optional cleanup after a send: default OFF, only items the receiver durably confirmed with matching input hashes
 * and whose exact MediaStore original is unchanged are offered; every other item is listed with its reason.
 */
@Composable
private fun SenderOriginalDeleteSection(
    report: PhotoTransferSendReport,
    infos: Map<String, PhotoTransferSourceInfo>,
    names: Map<String, String>,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    status: String?,
    working: Boolean,
    onRequestDelete: () -> Unit,
) {
    val excluded = mutableListOf<Pair<String, String>>()
    var eligible = 0
    report.outcomes.values.forEach { outcome ->
        val name = names[outcome.itemId] ?: "항목"
        val info = infos[outcome.itemId]
        val reason = when {
            report.legacyTokenExposed ->
                "이전 방식으로 연결되어 이 QR의 비밀 값이 노출되었습니다. 받는 기기에서 새 QR을 만들어 다시 연결해야 삭제할 수 있습니다."
            !report.receiptProtocol -> "상대 앱이 항목별 저장 확인을 지원하지 않습니다."
            !outcome.committed -> "상대 기기의 저장 확인을 받지 못했습니다."
            !outcome.inputsVerified -> "저장 확인 해시가 보낸 내용과 일치하지 않습니다."
            info is PhotoTransferSourceInfo.NotDeletable -> info.reason
            info == null -> "원본 정보를 확인하지 못했습니다."
            else -> null
        }
        if (reason == null) eligible += 1 else excluded += name to reason
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("보낸 원본 삭제", fontWeight = FontWeight.Medium)
                Text(
                    "기본은 삭제하지 않음 · 삭제 가능 ${eligible}개 · 제외 ${excluded.size}개",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled && eligible > 0, onCheckedChange = onEnabledChange, enabled = eligible > 0 && !working)
        }
        if (enabled && eligible > 0) {
            Button(onClick = onRequestDelete, enabled = !working) {
                Text(if (working) "확인 중…" else "원본 ${eligible}개 삭제…")
            }
        }
        Text(
            PhotoTransferSourceDelete.SUPPORTED_SOURCE_NOTE + " 받는 기기의 저장 확인을 받은 항목만 삭제할 수 있습니다. " +
                "소중한 원본은 받는 기기에서 직접 확인한 뒤 삭제하세요.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (excluded.isNotEmpty()) {
            Text("삭제에서 제외된 항목", style = MaterialTheme.typography.labelMedium)
            excluded.take(6).forEach { (name, reason) ->
                Text(
                    "· $name — $reason",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (excluded.size > 6) {
                Text(
                    "외 ${excluded.size - 6}개",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal const val LEGACY_RECEIVE_NOTE =
    "보내는 기기가 이전 방식으로 연결해 이 QR의 비밀 값이 노출되었습니다. 원본 삭제를 쓰려면 새 QR 코드를 만들어 다시 연결하세요."

/** Same selection + same transfer id → same item id on every retry (never a fresh random id per attempt). */
internal fun stableTransferItemId(transferId: String, uri: Uri): String =
    UUID.nameUUIDFromBytes("$transferId|$uri".toByteArray(Charsets.UTF_8)).toString()

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
            SkinAwareThumbnailImage(
                bitmap = loadedThumbnail,
                cacheIdentity = "photo-transfer-saved:${result.uri}:${result.displayName}:${result.mimeType}",
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

internal fun receiveProgressText(progress: PhotoTransferProgress): String {
    val percent = if (progress.totalBytes > 0L) {
        (progress.transferredBytes * 100L / progress.totalBytes).coerceIn(0L, 100L)
    } else {
        100L
    }
    return "${progress.fileName} · $percent% · ${progress.completedFiles}개 저장"
}

// Tablets use the available content width; only extremely wide windows keep a centered column.

private tailrec fun Context.findTransferHostActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findTransferHostActivity()
    else -> null
}

private const val GOOGLE_PHOTOS_PACKAGE = "com.google.android.apps.photos"

internal fun isGooglePhotosInstalled(context: Context): Boolean = runCatching {
    @Suppress("DEPRECATION")
    context.packageManager.getPackageInfo(GOOGLE_PHOTOS_PACKAGE, 0)
    true
}.getOrDefault(false)

/**
 * Opens the Google Photos app's own picker (ACTION_GET_CONTENT pinned to its package). It returns content URIs with a
 * temporary read grant for just the chosen items; no Google sign-in, API scope or upload is involved on our side.
 * The result is read as any other picked URI. This is not the system Files chooser.
 */
internal class GooglePhotosAppPickContract : ActivityResultContract<Unit, List<Uri>>() {
    override fun createIntent(context: Context, input: Unit): android.content.Intent =
        android.content.Intent(android.content.Intent.ACTION_GET_CONTENT)
            .setType("*/*")
            .putExtra(android.content.Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            .putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .setPackage(GOOGLE_PHOTOS_PACKAGE)

    override fun parseResult(resultCode: Int, intent: android.content.Intent?): List<Uri> {
        if (resultCode != android.app.Activity.RESULT_OK || intent == null) return emptyList()
        val clip = intent.clipData
        val fromClip = if (clip != null) (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } else emptyList()
        return fromClip.ifEmpty { listOfNotNull(intent.data) }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1fGB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1fMB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "${bytes shr 10}KB"
    else -> "${bytes}B"
}

/** Items the receiver reported failed because its earlier received output is missing/modified/unverifiable. */
internal fun failedOutputItemIds(report: PhotoTransferSendReport): Set<String> =
    report.outcomes.values.filter { !it.committed && it.reason in ReceivedOutputCheck.OUTPUT_REASONS }.map { it.itemId }.toSet()

internal fun outputFailureNote(count: Int): String =
    if (count == 0) "" else " 받는 기기에 이전에 저장된 ${count}개의 사본이 없어졌거나 바뀌었거나 확인되지 않아 실패로 보고되었습니다. 자동으로 다시 저장하지 않았고 받은 기기의 파일은 그대로입니다. 원본은 삭제 대상에서 제외됩니다. 다시 받으려면 '새로 보내기'를 선택하세요."

/** Completion counts only items whose stored content matches what was sent; a differing stored copy is reported separately. */
internal fun sendCompletionText(receiptProtocol: Boolean, sentCount: Int, confirmed: Int, mismatched: Int, total: Int): String = when {
    !receiptProtocol -> "${sentCount}개 보내기 완료"
    confirmed == total -> "${confirmed}개 보내기 완료"
    mismatched > 0 -> "${confirmed}/${total}개만 저장이 확인되었습니다. ${mismatched}개는 받은 기기에 저장된 내용이 보낸 내용과 다릅니다. 원본을 지우지 마세요."
    else -> "${confirmed}/${total}개만 저장이 확인되었습니다."
}
