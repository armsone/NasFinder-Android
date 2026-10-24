package com.armsone.nasfinder.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class PhotoPairingPayload(
    val host: String,
    val port: Int,
    val token: String,
) {
    fun encode(): String =
        "$SCHEME://$AUTHORITY/$PATH?version=$VERSION&host=$host&port=$port&token=$token"

    companion object {
        const val HANDSHAKE_V1_PREFIX = "NASFINDER_PHOTO/1 "
        const val HANDSHAKE_V2_PREFIX = "NASFINDER_PHOTO/2 "
        const val HANDSHAKE_V3_PREFIX = "NASFINDER_PHOTO/3 "
        const val HANDSHAKE_V4_PREFIX = "NASFINDER_PHOTO/4 "
        const val GROUPED_CAPABILITY = "grouped-v1"
        const val RECEIPT_CAPABILITY = "receipt-v1"
        private const val SCHEME = "nasfinder"
        private const val AUTHORITY = "photo-transfer"
        private const val PATH = "pair"
        private const val VERSION = "1"
        private val TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{22,128}$")

        fun decode(rawValue: String): PhotoPairingPayload? = runCatching {
            val uri = URI(rawValue)
            if (uri.scheme != SCHEME || uri.authority != AUTHORITY || uri.path != "/$PATH") {
                return null
            }
            val parameters = uri.rawQuery?.split('&')?.mapNotNull { component ->
                val pieces = component.split('=', limit = 2)
                if (pieces.size != 2) null else {
                    URLDecoder.decode(pieces[0], Charsets.UTF_8.name()) to
                        URLDecoder.decode(pieces[1], Charsets.UTF_8.name())
                }
            } ?: return null
            if (parameters.size != 4 || parameters.map { it.first }.toSet().size != 4) return null
            val values = parameters.toMap()
            if (values.keys != setOf("version", "host", "port", "token")) return null
            if (values["version"] != VERSION) return null
            val host = values["host"] ?: return null
            val port = values["port"]?.toIntOrNull() ?: return null
            val token = values["token"] ?: return null
            if (!isValidIpv4(host) || port !in 1..65535 || !TOKEN_PATTERN.matches(token)) {
                return null
            }
            PhotoPairingPayload(host, port, token)
        }.getOrNull()

        fun newToken(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        private fun isValidIpv4(value: String): Boolean {
            if (value == "0.0.0.0" || value == "255.255.255.255") return false
            val parts = value.split('.')
            return parts.size == 4 && parts.all { part ->
                part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                    !(part.length > 1 && part.startsWith('0')) && part.toIntOrNull() in 0..255
            }
        }
    }
}

internal sealed interface PhotoPairingStartResult {
    data class Ready(val payload: PhotoPairingPayload) : PhotoPairingStartResult
    data object NoLocalNetwork : PhotoPairingStartResult
    data class Failed(val reason: String) : PhotoPairingStartResult
}

internal data class PhotoTransferOutgoingItem(
    val uri: Uri,
    val id: String,
    val name: String,
    val mimeType: String,
    val mediaKind: String,
    val byteLength: Long,
)

internal data class PhotoTransferProgress(
    val fileName: String,
    val completedFiles: Int,
    val totalFiles: Int?,
    val transferredBytes: Long,
    val totalBytes: Long,
)

internal enum class SavedPhotoTransferKind(val displayName: String) {
    PHOTO("사진"),
    VIDEO("영상"),
    LIVE_PHOTO("Live Photo"),
    MOTION_PHOTO("Motion Photo"),
}

internal data class SavedPhotoTransferResult(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val kind: SavedPhotoTransferKind,
)

internal class PhotoPairingReceiver : Closeable {
    private val lifecycleLock = Any()
    private val sessionGeneration = AtomicLong(0L)
    private var serverSocket: ServerSocket? = null
    private val activeSocket = AtomicReference<Socket?>(null)
    private val pendingSockets = ConcurrentHashMap.newKeySet<Socket>()
    private var handshakeExecutor: ThreadPoolExecutor? = null
    /** Set once a legacy line carried this QR's raw token; reset only by a new [start] (new token). */
    private val legacyTokenExposed = AtomicBoolean(false)

    suspend fun start(
        context: Context,
        onConnected: (PhotoPeerPlatform) -> Unit,
        onProgress: (PhotoTransferProgress) -> Unit,
        onComplete: (List<SavedPhotoTransferResult>) -> Unit,
        onFailure: (String) -> Unit,
        onPartial: (List<SavedPhotoTransferResult>, String) -> Unit = { _, message -> onFailure(message) },
        onLegacyTokenExposed: () -> Unit = {},
    ): PhotoPairingStartResult {
        val generation = synchronized(lifecycleLock) {
            closeLocked()
            legacyTokenExposed.set(false)
            sessionGeneration.incrementAndGet()
        }
        return withContext(Dispatchers.IO) {
            val host = localIpv4Address() ?: return@withContext PhotoPairingStartResult.NoLocalNetwork
            synchronized(lifecycleLock) {
                if (!isCurrentGeneration(generation)) {
                    return@synchronized PhotoPairingStartResult.Failed("새 연결 준비로 이전 요청을 중단했습니다.")
                }
                runCatching {
                    val token = PhotoPairingPayload.newToken()
                    val server = ServerSocket(0).also {
                        it.reuseAddress = true
                        serverSocket = it
                    }
                    val executor = newHandshakeExecutor(generation).also { handshakeExecutor = it }
                    val payload = PhotoPairingPayload(host, server.localPort, token)
                    Thread(
                        {
                            acceptConnections(
                                generation = generation,
                                server = server,
                                token = token,
                                executor = executor,
                                context = context.applicationContext,
                                onConnected = onConnected,
                                onProgress = onProgress,
                                onComplete = onComplete,
                                onFailure = onFailure,
                                onPartial = onPartial,
                                onLegacyTokenExposed = onLegacyTokenExposed,
                            )
                        },
                        "photo-pairing-receiver-$generation",
                    ).apply {
                        isDaemon = true
                        start()
                    }
                    PhotoPairingStartResult.Ready(payload)
                }.getOrElse {
                    closeLocked()
                    PhotoPairingStartResult.Failed(it.userMessage("연결을 시작하지 못했습니다. 같은 Wi-Fi에 있는지 확인한 뒤 다시 시도해 주세요."))
                }
            }
        }
    }

    private fun acceptConnections(
        generation: Long,
        server: ServerSocket,
        token: String,
        executor: ThreadPoolExecutor,
        context: Context,
        onConnected: (PhotoPeerPlatform) -> Unit,
        onProgress: (PhotoTransferProgress) -> Unit,
        onComplete: (List<SavedPhotoTransferResult>) -> Unit,
        onFailure: (String) -> Unit,
        onPartial: (List<SavedPhotoTransferResult>, String) -> Unit,
        onLegacyTokenExposed: () -> Unit,
    ) {
        while (isCurrentGeneration(generation)) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            if (!registerPendingSocket(generation, socket)) {
                runCatching { socket.close() }
                continue
            }
            try {
                executor.execute {
                    handleCandidate(
                        generation = generation,
                        server = server,
                        socket = socket,
                        token = token,
                        context = context,
                        onConnected = onConnected,
                        onProgress = onProgress,
                        onComplete = onComplete,
                        onFailure = onFailure,
                        onPartial = onPartial,
                        onLegacyTokenExposed = onLegacyTokenExposed,
                    )
                }
            } catch (_: RejectedExecutionException) {
                pendingSockets.remove(socket)
                runCatching { socket.close() }
            }
        }
    }

    private fun handleCandidate(
        generation: Long,
        server: ServerSocket,
        socket: Socket,
        token: String,
        context: Context,
        onConnected: (PhotoPeerPlatform) -> Unit,
        onProgress: (PhotoTransferProgress) -> Unit,
        onComplete: (List<SavedPhotoTransferResult>) -> Unit,
        onFailure: (String) -> Unit,
        onPartial: (List<SavedPhotoTransferResult>, String) -> Unit,
        onLegacyTokenExposed: () -> Unit,
    ) {
        var claimed = false
        try {
            socket.use { client ->
                client.soTimeout = 5_000
                val line = readAsciiLine(client, 512)
                val isV1 = line == PhotoPairingPayload.HANDSHAKE_V1_PREFIX + token
                val peerPlatform = parseV2Handshake(line, token)
                val groupedPeer = parseV3Handshake(line, token)
                // v4 never carries the token: the hello line is answered by a challenge, and only a valid proof
                // (HMAC keyed by the QR token) lets this socket claim the pairing. A bad proof leaves it unclaimed.
                val legacyLine = isV1 || peerPlatform != null || groupedPeer != null
                if (legacyLine) {
                    // The sender just put this QR's raw token on the wire: the secret is tainted from now on.
                    legacyTokenExposed.set(true)
                    onLegacyTokenExposed()
                }
                val hello = parseV4Hello(line)
                if (hello != null && legacyTokenExposed.get()) {
                    // Exposed secret: no authenticated (deletion-capable) session with it, and no silent downgrade.
                    client.getOutputStream().write("ERROR tainted\n".toByteArray(Charsets.US_ASCII))
                    client.getOutputStream().flush()
                    return
                }
                val v4Session = if (hello != null) authenticateV4Hello(client, token, hello) else null
                if (hello != null && v4Session == null) return
                val receiptPeer = v4Session?.peer
                val accepted = isV1 || peerPlatform != null || groupedPeer != null || receiptPeer != null
                if (!accepted) {
                    client.getOutputStream().write("ERROR\n".toByteArray())
                    client.getOutputStream().flush()
                    return
                }
                if (!claimSocket(generation, server, client)) return
                claimed = true
                val response = when {
                    isV1 -> "OK\n"
                    v4Session != null -> v4Session.acceptLine
                    groupedPeer != null -> "OK android ${PhotoPairingPayload.GROUPED_CAPABILITY}\n"
                    else -> "OK android\n"
                }
                client.getOutputStream().write(response.toByteArray())
                client.getOutputStream().flush()
                if (!isCurrentGeneration(generation)) return
                onConnected(receiptPeer ?: groupedPeer ?: peerPlatform ?: PhotoPeerPlatform.UNKNOWN)
                if (isV1) return
                if (v4Session != null) {
                    serveReceiptSession(
                        context = context,
                        client = client,
                        expectedSource = v4Session.peer,
                        sessionKey = v4Session.sessionKey,
                        isCurrent = { isCurrentGeneration(generation) },
                        onProgress = onProgress,
                        onComplete = onComplete,
                        onFailure = onFailure,
                        onPartial = onPartial,
                    )
                    return
                }
                runCatching {
                    client.soTimeout = 0
                    receivePhotoTransfer(
                        context = context,
                        input = BufferedInputStream(client.getInputStream()),
                        expectedSource = checkNotNull(groupedPeer ?: peerPlatform),
                        groupedProtocol = groupedPeer != null,
                        onProgress = { progress ->
                            if (isCurrentGeneration(generation)) onProgress(progress)
                        },
                    )
                }.onSuccess { savedResults ->
                    if (!isCurrentGeneration(generation)) return@onSuccess
                    if (groupedPeer != null) {
                        // The assets are already committed: a lost count-only ACK never deletes them. The legacy
                        // sender then reports a failure and may resend; durable receipts need the v4 handshake.
                        runCatching {
                            client.getOutputStream().write(
                                "RESULT OK ${savedResults.size}\n".toByteArray(Charsets.US_ASCII),
                            )
                            client.getOutputStream().flush()
                        }
                    }
                    onComplete(savedResults)
                }.onFailure {
                    if (!isCurrentGeneration(generation)) return@onFailure
                    if (groupedPeer != null) {
                        runCatching {
                            client.getOutputStream().write("RESULT ERROR\n".toByteArray(Charsets.US_ASCII))
                            client.getOutputStream().flush()
                        }
                    }
                    onFailure(it.userMessage("파일을 받지 못했습니다. 두 기기가 같은 Wi-Fi에 있는지 확인한 뒤 다시 시도해 주세요."))
                }
            }
        } catch (error: Throwable) {
            if (claimed && isCurrentGeneration(generation)) {
                onFailure(error.userMessage("연결 응답을 보내지 못했습니다. 두 기기가 같은 Wi-Fi에 있는지 확인한 뒤 다시 시도해 주세요."))
            }
        } finally {
            pendingSockets.remove(socket)
            if (claimed) {
                activeSocket.compareAndSet(socket, null)
                synchronized(lifecycleLock) {
                    if (isCurrentGeneration(generation)) handshakeExecutor?.shutdown()
                }
            }
        }
    }

    override fun close() {
        synchronized(lifecycleLock) {
            sessionGeneration.incrementAndGet()
            closeLocked()
        }
    }

    private fun closeLocked() {
        runCatching { serverSocket?.close() }
        pendingSockets.forEach { socket -> runCatching { socket.close() } }
        pendingSockets.clear()
        runCatching { activeSocket.getAndSet(null)?.close() }
        handshakeExecutor?.shutdownNow()
        serverSocket = null
        handshakeExecutor = null
    }

    private fun isCurrentGeneration(generation: Long): Boolean =
        sessionGeneration.get() == generation

    private fun registerPendingSocket(generation: Long, socket: Socket): Boolean =
        synchronized(lifecycleLock) {
            if (!isCurrentGeneration(generation) || pendingSockets.size >= MAX_PENDING_HANDSHAKES) false else {
                pendingSockets += socket
                true
            }
        }

    private fun claimSocket(generation: Long, server: ServerSocket, socket: Socket): Boolean =
        synchronized(lifecycleLock) {
            if (!isCurrentGeneration(generation) || !activeSocket.compareAndSet(null, socket)) {
                false
            } else {
                pendingSockets.remove(socket)
                runCatching { server.close() }
                if (serverSocket === server) serverSocket = null
                pendingSockets.forEach { pending -> runCatching { pending.close() } }
                pendingSockets.clear()
                true
            }
        }

    private fun newHandshakeExecutor(generation: Long): ThreadPoolExecutor =
        ThreadPoolExecutor(
            MAX_HANDSHAKE_WORKERS,
            MAX_HANDSHAKE_WORKERS,
            0L,
            TimeUnit.MILLISECONDS,
            SynchronousQueue(),
            { work -> Thread(work, "photo-pairing-handshake-$generation").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )

    private companion object {
        const val MAX_HANDSHAKE_WORKERS = 4
        const val MAX_PENDING_HANDSHAKES = 4
    }
}

/**
 * Items a `ready` receipt tells the sender not to send again on this transferId: committed with inputs equal to the plan,
 * or failed because the receiver's earlier output is missing/modified/unverifiable (outmissing/outmodified/outunverified).
 * The latter are never resent here: a new copy only goes out through the user's explicit "새로 보내기" (new transferId).
 */
internal fun itemsNotToResend(ready: List<PhotoTransferReceiptItem>, plannedInputs: (String) -> Map<Int, String>?): Set<String> =
    ready.filter {
        (it.committed && plannedInputs(it.itemId) == it.inputs) ||
            (!it.committed && it.reason in ReceivedOutputCheck.OUTPUT_REASONS)
    }.map { it.itemId }.toSet()

/** Per logical item: what this sender hashed before sending (wire contract §2.2 `inputs`) plus the origin hash. */
internal data class PhotoTransferOutgoingPlan(
    val inputs: Map<Int, String>,
    val originSha256: String,
)

internal data class PhotoTransferItemOutcome(
    val itemId: String,
    /** Receiver reported the item durably committed. */
    val committed: Boolean,
    /** Receiver's verified input hashes equal this sender's plan (count, index and hash). */
    val inputsVerified: Boolean,
    val reason: String? = null,
)

internal data class PhotoTransferSendReport(
    val transferId: String,
    /** True only when the v4 receipt handshake was negotiated; count-only ACK peers never allow source deletion. */
    val receiptProtocol: Boolean,
    val receiverId: String?,
    val sentCount: Int,
    val outcomes: Map<String, PhotoTransferItemOutcome>,
    val originHashes: Map<String, String>,
    val finalStage: String?,
    /** The QR secret was sent in plaintext (legacy line): this QR can never qualify for deletion. */
    val legacyTokenExposed: Boolean = false,
)

internal class PhotoTransferSenderSession(
    private val socket: Socket,
    val peerPlatform: PhotoPeerPlatform,
    private val groupedProtocol: Boolean,
    val receiptProtocol: Boolean = false,
    private val sessionKey: ByteArray? = null,
    /** True when this QR's raw token was written to the wire (legacy line): the QR is tainted, deletion is impossible. */
    val legacyTokenExposed: Boolean = false,
) : Closeable {
    private val sendInProgress = AtomicBoolean(false)
    private var expectedReceiptSeq = 1

    /** Reads one stage of authenticated receipt frames (continued by `more`) for [transferId]. */
    private fun readReceiptFrames(input: InputStream, transferId: String, stages: Set<String>): List<PhotoTransferReceipt> {
        val key = checkNotNull(sessionKey) { "인증된 수신 확인 세션이 아닙니다." }
        val frames = mutableListOf<PhotoTransferReceipt>()
        while (true) {
            val frame = PhotoTransferWireCodec.readReceipt(input, key, expectedReceiptSeq)
                ?: error("상대 기기가 저장 확인 없이 연결을 종료했습니다.")
            expectedReceiptSeq += 1
            check(frame.transferId == transferId) { "다른 전송의 수신 확인입니다." }
            check(frame.stage in stages && (frames.isEmpty() || frames.first().stage == frame.stage)) {
                "예상하지 못한 수신 확인 단계입니다."
            }
            frames += frame
            check(frames.size <= 100) { "수신 확인 프레임이 너무 많습니다." }
            if (!frame.more) return frames
        }
    }
    val transferRoute: PhotoTransferRoute =
        photoTransferRoute(PhotoPeerPlatform.ANDROID, peerPlatform)

    suspend fun send(
        context: Context,
        items: List<PhotoTransferOutgoingItem>,
        transferId: String,
        expectedReceiverId: String? = null,
        onProgress: (PhotoTransferProgress) -> Unit,
    ): Result<PhotoTransferSendReport> = withContext(Dispatchers.IO) {
        if (!sendInProgress.compareAndSet(false, true)) {
            return@withContext Result.failure(IllegalStateException("이미 파일을 보내고 있습니다."))
        }
        try {
            runCatching {
                require(items.isNotEmpty() && items.size <= MAX_TRANSFER_ITEMS) { "보낼 항목 수가 올바르지 않습니다." }
                require(items.map { it.id }.toSet().size == items.size && items.all { isValidItemId(it.id) }) {
                    "항목 식별자가 올바르지 않습니다."
                }
                require(!receiptProtocol || isValidTransferId(transferId)) { "전송 식별자가 올바르지 않습니다." }
                val output = BufferedOutputStream(socket.getOutputStream())
                val plans = items.associate { it.id to describeOutgoingItem(context, it, peerPlatform) }
                var receiverId: String? = null
                var alreadyCommitted = emptySet<String>()
                if (receiptProtocol) {
                    PhotoTransferWireCodec.writeBegin(output, transferId, items.size)
                    output.flush()
                    socket.soTimeout = 30_000
                    val ready = readReceiptFrames(socket.getInputStream(), transferId, setOf("ready"))
                    receiverId = ready.first().receiverId
                    check(ready.all { it.receiverId == receiverId }) { "수신 기기 식별자가 일치하지 않습니다." }
                    // A retry of the same transfer must reach the same receiving app installation.
                    check(expectedReceiverId == null || expectedReceiverId == receiverId) {
                        "처음 보낸 기기와 다른 기기입니다. 같은 기기에서 새 QR 코드로 다시 연결하세요."
                    }
                    checkReceiptItems(ready, plans.keys)
                    // Items the receiver reports as failed because its earlier output cannot be confirmed are not resent on
                    // the same transfer: a new copy is only sent by the user's explicit "새로 보내기" under a new transferId.
                    alreadyCommitted = itemsNotToResend(ready.flatMap { it.items }) { plans[it]?.inputs }
                    socket.soTimeout = 0
                }
                val sendItems = items.filter { it.id !in alreadyCommitted }
                val totalBytes = sendItems.sumOf { it.byteLength }
                var transferredBytes = 0L
                sendItems.forEachIndexed { index, item ->
                if (groupedProtocol && item.mediaKind == PhotoTransferItemKind.MOTION_PHOTO.wireValue) {
                    val container = requireNotNull(context.contentResolver.openInputStream(item.uri)) {
                        "${item.name} 파일을 열지 못했습니다."
                    }.use { readBounded(it, item.byteLength) }
                    val layout = MotionPhotoCodec.parse(container)
                        ?: error("${item.name}은 표준 Motion Photo가 아닙니다.")
                    if (peerPlatform == PhotoPeerPlatform.ANDROID) {
                        writeByteComponent(
                            output, item, container, item.name, "image/jpeg",
                            PhotoTransferComponentRole.MOTION_CONTAINER, 0, 1,
                            layout.presentationTimestampUs,
                        )
                    } else {
                        writeByteComponent(
                            output, item, MotionPhotoCodec.primaryImage(container, layout),
                            item.name.substringBeforeLast('.') + ".jpg", "image/jpeg",
                            PhotoTransferComponentRole.PRIMARY_IMAGE, 0, 2,
                            layout.presentationTimestampUs,
                        )
                        writeByteComponent(
                            output, item, MotionPhotoCodec.motionVideo(container, layout),
                            item.name.substringBeforeLast('.') + ".mp4", "video/mp4",
                            PhotoTransferComponentRole.MOTION_VIDEO, 1, 2,
                            layout.presentationTimestampUs,
                        )
                    }
                } else {
                    val sha256 = checkNotNull(plans[item.id]).inputs.getValue(0)
                    PhotoTransferWireCodec.writeFileHeader(
                        output,
                        PhotoTransferFileHeader(
                            id = item.id,
                            name = item.name,
                            mimeType = item.mimeType,
                            mediaKind = item.mediaKind,
                            sourcePlatform = PhotoPeerPlatform.ANDROID.wireValue,
                            byteLength = item.byteLength,
                            itemId = item.id,
                            groupId = item.id,
                            itemKind = normalizedItemKind(item.mediaKind, item.mimeType),
                            componentRole = PhotoTransferComponentRole.REGULAR_FILE.wireValue,
                            componentIndex = 0,
                            componentCount = 1,
                            sha256 = if (groupedProtocol) sha256 else null,
                        ),
                    )
                    val input = requireNotNull(context.contentResolver.openInputStream(item.uri)) {
                        "${item.name} 파일을 열지 못했습니다."
                    }
                    input.use { copyExactly(it, output, item.byteLength) {} }
                }
                transferredBytes += item.byteLength
                onProgress(
                    PhotoTransferProgress(
                        fileName = item.name,
                        completedFiles = index + 1,
                        totalFiles = sendItems.size,
                        transferredBytes = transferredBytes,
                        totalBytes = totalBytes,
                    ),
                )
                }
                PhotoTransferWireCodec.writeDone(output)
                output.flush()
                socket.soTimeout = 120_000
                val originHashes = plans.mapValues { it.value.originSha256 }
                if (receiptProtocol) {
                    val finalFrames = readReceiptFrames(socket.getInputStream(), transferId, setOf("final", "error"))
                    check(finalFrames.all { it.receiverId == receiverId && it.stage == finalFrames.first().stage }) {
                        "수신 확인 프레임이 일치하지 않습니다."
                    }
                    checkReceiptItems(finalFrames, plans.keys)
                    val byId = finalFrames.flatMap { it.items }.associateBy { it.itemId }
                    // "complete" must mean every item of this transfer is committed; anything else is a lie or a bug.
                    check(finalFrames.last().status != "complete" || items.all { byId[it.id]?.committed == true }) {
                        "수신 확인 상태가 항목 내용과 일치하지 않습니다."
                    }
                    socket.soTimeout = 0
                    PhotoTransferSendReport(
                        transferId = transferId,
                        receiptProtocol = true,
                        receiverId = receiverId,
                        sentCount = sendItems.size,
                        outcomes = items.associate { item ->
                            val receipt = byId[item.id]
                            val committed = receipt?.committed == true
                            item.id to PhotoTransferItemOutcome(
                                itemId = item.id,
                                committed = committed,
                                inputsVerified = committed && receipt?.inputs == plans[item.id]?.inputs,
                                reason = receipt?.reason ?: if (receipt == null) "unconfirmed" else null,
                            )
                        },
                        originHashes = originHashes,
                        finalStage = finalFrames.first().stage,
                    )
                } else {
                    val result = readAsciiLine(socket, 128)
                    check(parseTransferResult(result) == items.size) { "상대 기기가 저장 완료를 확인하지 못했습니다." }
                    socket.soTimeout = 0
                    PhotoTransferSendReport(
                        transferId = transferId,
                        receiptProtocol = false,
                        receiverId = null,
                        sentCount = items.size,
                        outcomes = items.associate { it.id to PhotoTransferItemOutcome(it.id, committed = false, inputsVerified = false, reason = "count-only-ack") },
                        originHashes = originHashes,
                        finalStage = null,
                        legacyTokenExposed = legacyTokenExposed,
                    )
                }
            }
        } finally {
            sendInProgress.set(false)
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    /** Receipt items must name only items of this send, each at most once (extra or duplicated ids are rejected). */
    private fun checkReceiptItems(frames: List<PhotoTransferReceipt>, plannedIds: Set<String>) {
        val seen = mutableSetOf<String>()
        frames.flatMap { it.items }.forEach { item ->
            check(item.itemId in plannedIds && seen.add(item.itemId)) { "수신 확인에 알 수 없거나 중복된 항목이 있습니다." }
        }
    }

    private fun writeByteComponent(
        output: java.io.OutputStream,
        item: PhotoTransferOutgoingItem,
        bytes: ByteArray,
        name: String,
        mimeType: String,
        role: PhotoTransferComponentRole,
        index: Int,
        count: Int,
        stillImageTimeUs: Long,
    ) {
        val digest = photoTransferSha256(bytes)
        PhotoTransferWireCodec.writeFileHeader(
            output,
            PhotoTransferFileHeader(
                id = "${item.id}-$index",
                name = name,
                mimeType = mimeType,
                mediaKind = item.mediaKind,
                sourcePlatform = PhotoPeerPlatform.ANDROID.wireValue,
                byteLength = bytes.size.toLong(),
                itemId = item.id,
                groupId = item.id,
                itemKind = PhotoTransferItemKind.MOTION_PHOTO.wireValue,
                componentRole = role.wireValue,
                componentIndex = index,
                componentCount = count,
                sha256 = digest,
                stillImageTimeUs = stillImageTimeUs,
            ),
        )
        output.write(bytes)
    }
}

/**
 * Sender handshake: the v4 line (grouped + receipt capabilities) is tried first. A peer that answers ERROR or
 * closes before answering does not know v4 (legacy), so the same host:port is retried once with the v3 line.
 * A timeout is not a legacy signal and is reported as a failure.
 */
internal suspend fun connectToPhotoPair(context: Context, payload: PhotoPairingPayload): Result<PhotoTransferSenderSession> =
    withContext(Dispatchers.IO) {
        runCatching {
            // A QR whose token ever crossed the wire in plaintext is dead for this sender: nothing is sent with it.
            if (PhotoTransferTokenTaint.isTainted(context, payload.token)) throw PhotoTransferTaintedTokenException()
            openPairedSession(context, payload, receipt = true)
                ?: openPairedSession(context, payload, receipt = false)
                ?: error("상대 기기가 연결을 거부했습니다.")
        }
    }

internal const val TAINTED_QR_MESSAGE =
    "이 QR 코드의 비밀 값은 이전 방식 연결로 이미 노출되었거나 사용된 적이 있어 다시 쓸 수 없습니다. " +
        "받는 기기에서 새 QR 코드를 만든 뒤 다시 스캔하세요."

/** The QR secret is (or must be treated as) exposed; only a freshly generated QR can be used. Never auto-downgrades. */
internal class PhotoTransferTaintedTokenException : IllegalStateException(TAINTED_QR_MESSAGE)

/** Challenge/proof or receipt authentication failed after the peer spoke v4: not "unsupported", never downgraded. */
internal class PhotoTransferPeerAuthException(message: String) : IllegalStateException(message)

/**
 * Null only when the peer rejected the v4 hello with a bare `ERROR` or closed before any CHALLENGE (= it does not know
 * v4). Every failure after the CHALLENGE, a timeout, or `ERROR tainted` throws and is never retried as legacy.
 * The legacy line puts the raw token on the wire, so the token is persisted as tainted BEFORE that line is written.
 */
private fun openPairedSession(context: Context, payload: PhotoPairingPayload, receipt: Boolean): PhotoTransferSenderSession? {
    val socket = Socket()
    try {
        socket.connect(InetSocketAddress(payload.host, payload.port), 5_000)
        socket.soTimeout = 5_000
        val output = socket.getOutputStream()
        if (receipt) {
            // v4: hello (no token) → CHALLENGE → PROOF → OK carrying the receiver's proof.
            val senderNonce = PhotoTransferAuth.newNonce()
            output.write(
                (
                    PhotoPairingPayload.HANDSHAKE_V4_PREFIX + "hello ${PhotoPeerPlatform.ANDROID.wireValue} " +
                        "${PhotoPairingPayload.GROUPED_CAPABILITY} ${PhotoPairingPayload.RECEIPT_CAPABILITY} $senderNonce\n"
                ).toByteArray(Charsets.US_ASCII),
            )
            output.flush()
            val challenge = try {
                readAsciiLine(socket, 128)
            } catch (_: java.net.SocketException) {
                null
            }
            // ERROR or a closed socket before any challenge means the peer does not know v4 → legacy fallback.
            if (challenge == "ERROR tainted") throw PhotoTransferTaintedTokenException()
            if (challenge == null || challenge == "ERROR") {
                runCatching { socket.close() }
                return null
            }
            val receiverNonce = challenge.takeIf { it.startsWith("CHALLENGE ") }?.removePrefix("CHALLENGE ")
                ?.takeIf(PhotoTransferAuth::isValidNonce)
                ?: throw PhotoTransferPeerAuthException("상대 기기의 인증 응답이 올바르지 않습니다. 새 QR 코드로 다시 시도하세요.")
            val key = PhotoTransferAuth.deriveKey(payload.token)
            output.write(
                "PROOF ${PhotoTransferAuth.proof(key, "S", PhotoPeerPlatform.ANDROID.wireValue, senderNonce, receiverNonce)}\n"
                    .toByteArray(Charsets.US_ASCII),
            )
            output.flush()
            val acceptLine = readAsciiLine(socket, 192)
            val accept = acceptLine?.split(' ')
            if (accept == null || accept.size != 5 || accept[0] != "OK" ||
                accept[2] != PhotoPairingPayload.GROUPED_CAPABILITY || accept[3] != PhotoPairingPayload.RECEIPT_CAPABILITY
            ) throw PhotoTransferPeerAuthException("상대 기기가 인증을 받아들이지 않았습니다. 새 QR 코드로 다시 시도하세요.")
            val peerPlatform = PhotoPeerPlatform.fromWireValue(accept[1]).takeUnless { it == PhotoPeerPlatform.UNKNOWN }
                ?: throw PhotoTransferPeerAuthException("상대 기기의 인증 응답이 올바르지 않습니다. 새 QR 코드로 다시 시도하세요.")
            val expectedProof = PhotoTransferAuth.proof(key, "R", peerPlatform.wireValue, senderNonce, receiverNonce)
            if (!PhotoTransferAuth.constantTimeEquals(accept[4], expectedProof)) {
                throw PhotoTransferPeerAuthException("상대 기기를 확인하지 못했습니다. 새 QR 코드로 다시 시도하세요.")
            }
            socket.soTimeout = 0
            return PhotoTransferSenderSession(
                socket, peerPlatform, groupedProtocol = true, receiptProtocol = true,
                sessionKey = PhotoTransferAuth.sessionKey(key, senderNonce, receiverNonce),
            )
        }
        // From here the raw token is exposed to whoever answers (peer or an active man-in-the-middle that forged ERROR).
        PhotoTransferTokenTaint.markTainted(context, payload.token)
        output.write(
            (
                PhotoPairingPayload.HANDSHAKE_V3_PREFIX + payload.token +
                    " ${PhotoPeerPlatform.ANDROID.wireValue} ${PhotoPairingPayload.GROUPED_CAPABILITY}\n"
            ).toByteArray(),
        )
        output.flush()
        val response = readAsciiLine(socket, 64)
        val peerPlatform = when (response) {
            "OK ios ${PhotoPairingPayload.GROUPED_CAPABILITY}" -> PhotoPeerPlatform.IOS
            "OK android ${PhotoPairingPayload.GROUPED_CAPABILITY}" -> PhotoPeerPlatform.ANDROID
            else -> error("상대 기기가 연결을 거부했습니다.")
        }
        socket.soTimeout = 0
        return PhotoTransferSenderSession(socket, peerPlatform, groupedProtocol = true, legacyTokenExposed = true)
    } catch (error: Throwable) {
        runCatching { socket.close() }
        throw error
    }
}

/** Hashes the item exactly as it will be framed: Motion Photo parts for iOS, otherwise the single original. */
private fun describeOutgoingItem(
    context: Context,
    item: PhotoTransferOutgoingItem,
    peer: PhotoPeerPlatform,
): PhotoTransferOutgoingPlan {
    if (item.mediaKind == PhotoTransferItemKind.MOTION_PHOTO.wireValue) {
        val container = requireNotNull(context.contentResolver.openInputStream(item.uri)) {
            "${item.name} 파일을 열지 못했습니다."
        }.use { readBounded(it, item.byteLength) }
        val layout = MotionPhotoCodec.parse(container) ?: error("${item.name}은 표준 Motion Photo가 아닙니다.")
        val origin = photoTransferSha256(container)
        // Android peers receive the untouched container as one motionContainer component; iOS gets both parts.
        val inputs = if (peer == PhotoPeerPlatform.ANDROID) {
            mapOf(0 to origin)
        } else {
            mapOf(
                0 to photoTransferSha256(MotionPhotoCodec.primaryImage(container, layout)),
                1 to photoTransferSha256(MotionPhotoCodec.motionVideo(container, layout)),
            )
        }
        return PhotoTransferOutgoingPlan(inputs = inputs, originSha256 = origin)
    }
    val sha = requireNotNull(context.contentResolver.openInputStream(item.uri)) {
        "${item.name} 파일을 열지 못했습니다."
    }.use { sha256Exactly(it, item.byteLength) }
    return PhotoTransferOutgoingPlan(inputs = mapOf(0 to sha), originSha256 = sha)
}

private fun receivePhotoTransfer(
    context: Context,
    input: InputStream,
    expectedSource: PhotoPeerPlatform,
    groupedProtocol: Boolean,
    onProgress: (PhotoTransferProgress) -> Unit,
): List<SavedPhotoTransferResult> {
    var completedFiles = 0
    val savedResults = mutableListOf<SavedPhotoTransferResult>()
    val pending = linkedMapOf<String, MutableList<ReceivedComponent>>()
    val completedGroupIds = mutableSetOf<String>()
    val temporaryRoot = File(context.cacheDir, "photo-transfer-${UUID.randomUUID()}").also { check(it.mkdirs()) }
    try {
        while (true) {
            when (val message = PhotoTransferWireCodec.readMessage(input)
                ?: error("파일 전송이 완료되기 전에 연결이 종료되었습니다.")) {
                PhotoTransferWireMessage.Done -> {
                    check(pending.isEmpty()) { "완료되지 않은 움직이는 사진 그룹이 있습니다." }
                    return savedResults.toList()
                }
                is PhotoTransferWireMessage.Begin -> error("시작 헤더가 중복되었습니다.")
                is PhotoTransferWireMessage.File -> {
                check(PhotoPeerPlatform.fromWireValue(message.header.sourcePlatform) == expectedSource) {
                    "연결한 기기와 파일 정보가 일치하지 않습니다."
                }
                check(groupedProtocol || message.header.sha256 == null) { "지원하지 않는 그룹 헤더입니다." }
                check(!groupedProtocol || message.header.sha256 != null) { "그룹 전송 헤더가 필요합니다." }
                    check(groupedProtocol || message.header.mediaKind !in setOf("livePhoto", "motionPhoto")) {
                        "움직이는 사진에는 그룹 전송 연결이 필요합니다."
                    }
                    if (message.header.componentRole == "motionContainer") {
                        check(expectedSource == PhotoPeerPlatform.ANDROID) { "지원하지 않는 Motion Photo 컨테이너입니다." }
                    }
                    if (message.header.sha256 == null) {
                        savedResults += saveReceivedFile(context, input, message.header, completedFiles, onProgress)
                        completedFiles += 1
                    } else {
                        check(message.header.groupId !in completedGroupIds) { "이미 완료된 그룹 식별자입니다." }
                        val component = receiveComponentToTemp(
                            input, message.header, temporaryRoot, completedFiles, onProgress,
                        )
                        val group = pending.getOrPut(message.header.groupId) { mutableListOf() }
                        try {
                            validateGroupAddition(group, component)
                            group += component
                        } catch (error: Throwable) {
                            component.file.delete()
                            throw error
                        }
                        if (group.size == message.header.componentCount) {
                            savedResults += persistCompleteGroup(
                                context, group.sortedBy { it.header.componentIndex }, completedFiles, onProgress,
                            )
                            group.forEach { it.file.delete() }
                            pending.remove(message.header.groupId)
                            completedGroupIds += message.header.groupId
                            completedFiles += 1
                        }
                    }
                    onProgress(
                        PhotoTransferProgress(
                            fileName = message.header.name,
                            completedFiles = completedFiles,
                            totalFiles = null,
                            transferredBytes = message.header.byteLength,
                            totalBytes = message.header.byteLength,
                        ),
                    )
                }
            }
        }
    } catch (error: Throwable) {
        rollbackSavedResults(context, savedResults)
        throw error
    } finally {
        pending.values.flatten().forEach { it.file.delete() }
        temporaryRoot.delete()
    }
}

/** Legacy (count-only ACK) path only: results the sender never got a receipt for because the batch failed mid-receive. */
private fun rollbackSavedResults(context: Context, results: List<SavedPhotoTransferResult>) {
    results.forEach { result -> runCatching { context.contentResolver.delete(result.uri, null, null) } }
}

/**
 * v4 receiver session (wire contract §2). Every item is journaled STAGING → COMMITTED around the MediaStore
 * commit; nothing committed is ever deleted because a later item, the connection or the final receipt failed.
 */
private fun serveReceiptSession(
    context: Context,
    client: Socket,
    expectedSource: PhotoPeerPlatform,
    sessionKey: ByteArray,
    isCurrent: () -> Boolean,
    onProgress: (PhotoTransferProgress) -> Unit,
    onComplete: (List<SavedPhotoTransferResult>) -> Unit,
    onFailure: (String) -> Unit,
    onPartial: (List<SavedPhotoTransferResult>, String) -> Unit,
) {
    val journal = PhotoTransferReceiptJournal(context)
    // Journal records expire 90 days after their last update; saved assets are never removed by this.
    runCatching { journal.pruneOlderThan(90L * 24 * 60 * 60 * 1000) }
    val receiverId = journal.receiverId()
    val input = BufferedInputStream(client.getInputStream())
    val writer = ReceiptFrameWriter(BufferedOutputStream(client.getOutputStream()), sessionKey, receiverId)
    val saved = mutableListOf<SavedPhotoTransferResult>()
    var transferId: String? = null
    try {
        client.soTimeout = 30_000
        val begin = PhotoTransferWireCodec.readMessage(input, allowBegin = true) as? PhotoTransferWireMessage.Begin
            ?: error("전송 시작 정보가 필요합니다.")
        client.soTimeout = 0
        transferId = begin.transferId
        journal.begin(begin.transferId, begin.itemCount)
        val recovered = checkNotNull(journal.recoverStaging(context, begin.transferId))
        // Verified items are named committed; an earlier output that is gone, changed or unconfirmable is named failed
        // (outmissing/outmodified/outunverified). It is never saved again or restored silently.
        writer.write("ready", begin.transferId, null, committedReceiptItems(journal.reviewCommitted(context, recovered)))
        receiveJournaledItems(
            context, input, expectedSource, journal, begin.transferId, saved,
            onProgress = { progress -> if (isCurrent()) onProgress(progress) },
        )
        val record = checkNotNull(journal.load(begin.transferId))
        val review = journal.reviewCommitted(context, record)
        val committed = committedReceiptItems(review)
        val status = if (review.verified.size == record.itemCount) "complete" else "partial"
        // The assets are committed and journaled; a failure to write the final frame must not undo anything.
        runCatching { writer.write("final", begin.transferId, status, committed) }
        if (!isCurrent()) return
        if (status == "complete") onComplete(saved.toList())
        else onPartial(saved.toList(), "${review.verified.size}/${record.itemCount}개만 저장이 확인되었습니다." + unverifiedNote(review) + " 보내는 기기에서 다시 연결해도 이 항목은 자동으로 다시 저장하지 않습니다. 필요하면 보내는 기기에서 '새로 보내기'를 선택하세요.")
    } catch (error: Throwable) {
        val message = error.userMessage("파일을 받지 못했습니다. 두 기기가 같은 Wi-Fi에 있는지 확인한 뒤 다시 시도해 주세요.")
        transferId?.let { id ->
            runCatching {
                val record = journal.recoverStaging(context, id)
                val committed = record?.let { committedReceiptItems(journal.reviewCommitted(context, it)) }.orEmpty()
                writer.write("error", id, if (committed.none { it.committed }) "failed" else "partial", committed)
            }
        }
        if (!isCurrent()) return
        if (saved.isEmpty()) onFailure(message) else onPartial(saved.toList(), message)
    }
}

private fun unverifiedNote(review: CommittedReview): String =
    if (review.unverified.isEmpty()) "" else " 이전에 받은 사본이 없어졌거나 바뀌었거나 확인하지 못한 ${review.unverified.size}개는 실패(저장 확인 불가)로 보고했고, 남아 있는 파일은 지우지 않았습니다."

/** Verified assets as `committed`; every other earlier output as `failed` with an out* reason (never dropped from the receipt). */
internal fun committedReceiptItems(review: CommittedReview): List<PhotoTransferReceiptItem> =
    review.verified.map { item ->
        PhotoTransferReceiptItem(
            itemId = item.itemId,
            committed = true,
            inputs = item.inputs,
        )
    } + review.unverified.map { (itemId, verdict) ->
        PhotoTransferReceiptItem(itemId = itemId, committed = false, reason = ReceivedOutputCheck.failureReason(verdict))
    }

/** Writes authenticated receipt frames with a session-wide increasing `seq`; the journal's asset URIs stay local. */
private class ReceiptFrameWriter(
    private val output: java.io.OutputStream,
    private val sessionKey: ByteArray,
    private val receiverId: String,
) {
    private var nextSeq = 1

    fun write(stage: String, transferId: String, status: String?, items: List<PhotoTransferReceiptItem>) {
        val chunks = items.chunked(RECEIPT_ITEMS_PER_FRAME).ifEmpty { listOf(emptyList()) }
        chunks.forEachIndexed { index, chunk ->
            PhotoTransferWireCodec.writeReceipt(
                output,
                PhotoTransferReceipt(stage, transferId, receiverId, status, more = index < chunks.lastIndex, seq = nextSeq, items = chunk),
                sessionKey,
            )
            nextSeq += 1
        }
        output.flush()
    }
}

private const val RECEIPT_ITEMS_PER_FRAME = 128

private fun receiveJournaledItems(
    context: Context,
    input: InputStream,
    expectedSource: PhotoPeerPlatform,
    journal: PhotoTransferReceiptJournal,
    transferId: String,
    saved: MutableList<SavedPhotoTransferResult>,
    onProgress: (PhotoTransferProgress) -> Unit,
) {
    var completedFiles = 0
    val pending = linkedMapOf<String, MutableList<ReceivedComponent>>()
    val handledItems = mutableSetOf<String>()
    val temporaryRoot = File(context.cacheDir, "photo-transfer-${UUID.randomUUID()}").also { check(it.mkdirs()) }
    try {
        while (true) {
            when (val message = PhotoTransferWireCodec.readMessage(input) ?: error("파일 전송이 완료되기 전에 연결이 종료되었습니다.")) {
                PhotoTransferWireMessage.Done -> {
                    check(pending.isEmpty()) { "완료되지 않은 움직이는 사진 그룹이 있습니다." }
                    return
                }
                is PhotoTransferWireMessage.Begin -> error("시작 헤더가 중복되었습니다.")
                is PhotoTransferWireMessage.File -> {
                    val header = message.header
                    check(PhotoPeerPlatform.fromWireValue(header.sourcePlatform) == expectedSource) {
                        "연결한 기기와 파일 정보가 일치하지 않습니다."
                    }
                    check(header.sha256 != null) { "그룹 전송 헤더가 필요합니다." }
                    check(isValidItemId(header.itemId) && header.itemId == header.groupId) { "항목 식별자가 올바르지 않습니다." }
                    if (header.componentRole == "motionContainer") {
                        check(expectedSource == PhotoPeerPlatform.ANDROID) { "지원하지 않는 Motion Photo 컨테이너입니다." }
                    }
                    check(header.itemId !in handledItems) { "이미 완료된 항목 식별자입니다." }
                    val component = receiveComponentToTemp(input, header, temporaryRoot, completedFiles, onProgress)
                    val group = pending.getOrPut(header.groupId) { mutableListOf() }
                    try {
                        validateGroupAddition(group, component)
                        group += component
                    } catch (error: Throwable) {
                        component.file.delete()
                        throw error
                    }
                    if (group.size == header.componentCount) {
                        val ordered = group.sortedBy { it.header.componentIndex }
                        commitJournaledItem(context, journal, transferId, ordered, completedFiles, onProgress)?.let { saved += it }
                        group.forEach { it.file.delete() }
                        pending.remove(header.groupId)
                        handledItems += header.itemId
                        completedFiles += 1
                    }
                }
            }
        }
    } finally {
        pending.values.flatten().forEach { it.file.delete() }
        temporaryRoot.delete()
    }
}

/**
 * Saves one logical item once. Returns null when the journal already holds the item as COMMITTED (idempotent
 * duplicate delivery: bytes were verified against their hashes but nothing is saved again).
 */
private fun commitJournaledItem(
    context: Context,
    journal: PhotoTransferReceiptJournal,
    transferId: String,
    ordered: List<ReceivedComponent>,
    completedFiles: Int,
    onProgress: (PhotoTransferProgress) -> Unit,
): SavedPhotoTransferResult? {
    val itemId = ordered.first().header.itemId
    val inputs = ordered.associate { it.header.componentIndex to checkNotNull(it.header.sha256) }
    val existing = journal.load(transferId)?.items?.get(itemId)
    if (existing?.state == JournalItemState.COMMITTED) {
        // Verified, or not: the earlier output is never saved again here. An unverifiable one stays reported as failed
        // (outmissing/outmodified/outunverified) by the receipt; only an explicit "새로 보내기" (new transferId) sends a copy.
        val verdict = journal.verdictOf(context, existing)
        if (verdict != OutputVerdict.VERIFIED) {
            runCatching { journal.put(transferId, existing.copy(reason = ReceivedOutputCheck.failureReason(verdict))) }
        }
        return null
    }
    var stagedBytes = -1L
    var stagedSha = ""

    try {
        val result = persistCompleteGroup(context, ordered, completedFiles, onProgress) { uri, bytes, outputSha ->
            stagedBytes = bytes
            stagedSha = outputSha
            journal.put(transferId, JournalItem(itemId, JournalItemState.STAGING, inputs, uri.toString(), bytes, outputSha256 = outputSha))
        }
        journal.put(
            transferId,
            JournalItem(itemId, JournalItemState.COMMITTED, inputs, result.uri.toString(), stagedBytes, outputSha256 = stagedSha),
        )
        return result
    } catch (error: Throwable) {
        // Before MediaStore commit nothing persists (saveReceivedFile removes its row); STAGING left by a failed
        // journal write is settled by recoverStaging on the next connection.
        val current = journal.load(transferId)?.items?.get(itemId)
        if (current?.state != JournalItemState.STAGING && existing?.state != JournalItemState.COMMITTED) {
            runCatching {
                journal.put(transferId, JournalItem(itemId, JournalItemState.FAILED, inputs, reason = "storage"))
            }
        }
        throw error
    }
}

private data class ReceivedComponent(val header: PhotoTransferFileHeader, val file: File)

private fun receiveComponentToTemp(
    input: InputStream,
    header: PhotoTransferFileHeader,
    root: File,
    completedFiles: Int,
    onProgress: (PhotoTransferProgress) -> Unit,
): ReceivedComponent {
    val file = File(root, "${UUID.randomUUID()}.part")
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.outputStream().buffered().use { output ->
            copyExactly(input, output, header.byteLength) { copied ->
                onProgress(PhotoTransferProgress(header.name, completedFiles, null, copied, header.byteLength))
            }
        }
        file.inputStream().buffered().use { source ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        check(digest.digest().toHex() == header.sha256) { "${header.name} 체크섬이 일치하지 않습니다." }
        return ReceivedComponent(header, file)
    } catch (error: Throwable) {
        file.delete()
        throw error
    }
}

private fun validateGroupAddition(existing: List<ReceivedComponent>, incoming: ReceivedComponent) {
    val header = incoming.header
    check(existing.none { it.header.componentIndex == header.componentIndex }) { "중복 구성요소입니다." }
    existing.forEach { current ->
        val other = current.header
        check(other.itemId == header.itemId && other.groupId == header.groupId &&
            other.itemKind == header.itemKind && other.sourcePlatform == header.sourcePlatform &&
            other.componentCount == header.componentCount && other.stillImageTimeUs == header.stillImageTimeUs
        ) { "움직이는 사진 그룹 정보가 일치하지 않습니다." }
    }
}

internal fun validatePhotoComponentHeaders(headers: List<PhotoTransferFileHeader>): Boolean = runCatching {
    require(headers.isNotEmpty())
    val sorted = headers.sortedBy { it.componentIndex }
    val first = sorted.first()
    require(headers.size == first.componentCount)
    require(sorted.map { it.componentIndex } == (0 until first.componentCount).toList())
    require(sorted.all {
        it.itemId == first.itemId && it.groupId == first.groupId && it.itemKind == first.itemKind &&
            it.sourcePlatform == first.sourcePlatform && it.componentCount == first.componentCount &&
            it.stillImageTimeUs == first.stillImageTimeUs
    })
    if (first.componentCount == 1) {
        require(first.componentRole in setOf("regularFile", "motionContainer"))
    } else {
        require(first.componentCount == 2)
        require(sorted[0].componentRole == "primaryImage" && sorted[1].componentRole == "motionVideo")
        require(first.itemKind in setOf("livePhoto", "motionPhoto"))
    }
}.isSuccess

private fun persistCompleteGroup(
    context: Context,
    components: List<ReceivedComponent>,
    completedFiles: Int,
    onProgress: (PhotoTransferProgress) -> Unit,
    onStaged: ((Uri, Long, String) -> Unit)? = null,
): SavedPhotoTransferResult {
    check(validatePhotoComponentHeaders(components.map { it.header })) { "움직이는 사진 그룹이 완전하지 않습니다." }
    val first = components.first().header
    if (components.size == 1) {
        check(first.componentRole in setOf("regularFile", "motionContainer"))
        components.first().file.inputStream().buffered().use {
            return saveReceivedFile(context, it, first, completedFiles, onProgress, onStaged)
        }
    }
    check(components.size == 2 && components[0].header.componentRole == "primaryImage" &&
        components[1].header.componentRole == "motionVideo") { "움직이는 사진 그룹이 완전하지 않습니다." }
    val primary = components[0].file.readBoundedBytes()
    val videoComponent = components[1]
    val video = if (videoComponent.header.mimeType == "video/quicktime") {
        remuxQuickTimeToMp4(context, videoComponent.file)
    } else {
        videoComponent.file.readBoundedBytes()
    }
    val jpeg = ensureJpeg(context, primary)
    val motionPhoto = MotionPhotoCodec.create(
        primaryJpeg = jpeg,
        motionVideo = video,
        presentationTimestampUs = first.stillImageTimeUs,
        videoMimeType = "video/mp4",
    )
    val base = first.name.substringBeforeLast('.').ifBlank { "MotionPhoto" }.take(160)
    val outputHeader = first.copy(
        name = "${base}MP.jpg",
        mimeType = "image/jpeg",
        mediaKind = "motionPhoto",
        itemKind = "motionPhoto",
        byteLength = motionPhoto.size.toLong(),
        componentRole = "motionContainer",
        componentIndex = 0,
        componentCount = 1,
        sha256 = MessageDigest.getInstance("SHA-256").digest(motionPhoto).toHex(),
    )
    return saveReceivedFile(context, ByteArrayInputStream(motionPhoto), outputHeader, completedFiles, onProgress, onStaged)
}

private fun remuxQuickTimeToMp4(context: Context, source: File): ByteArray {
    require(source.length() in 8..512L * 1024 * 1024) { "Live Photo 영상이 너무 큽니다." }
    val output = File.createTempFile("photo-transfer-remux-", ".mp4", context.cacheDir)
    val extractor = MediaExtractor()
    var muxer: MediaMuxer? = null
    try {
        extractor.setDataSource(source.absolutePath)
        require(extractor.trackCount > 0) { "Live Photo 영상 트랙이 없습니다." }
        val trackMap = IntArray(extractor.trackCount) { -1 }
        var maxSampleSize = 1 * 1024 * 1024
        val activeMuxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer = activeMuxer
        var videoTrackCount = 0
        for (trackIndex in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(trackIndex)
            val mimeType = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (!mimeType.startsWith("video/") && !mimeType.startsWith("audio/")) continue
            trackMap[trackIndex] = activeMuxer.addTrack(format)
            if (mimeType.startsWith("video/")) videoTrackCount += 1
            if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                maxSampleSize = maxOf(maxSampleSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
            }
            extractor.selectTrack(trackIndex)
        }
        require(videoTrackCount > 0) { "Live Photo 영상 트랙이 없습니다." }
        require(maxSampleSize in 1..64 * 1024 * 1024) { "Live Photo 영상 샘플이 너무 큽니다." }
        val rotation = runCatching {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(source.absolutePath)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
                } finally {
                    release()
                }
            }
        }.getOrNull()
        if (rotation == 90 || rotation == 180 || rotation == 270) {
            activeMuxer.setOrientationHint(checkNotNull(rotation))
        }
        activeMuxer.start()
        val buffer = ByteBuffer.allocateDirect(maxSampleSize)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val trackIndex = extractor.sampleTrackIndex
            if (trackIndex < 0) break
            buffer.clear()
            val sampleSize = extractor.readSampleData(buffer, 0)
            require(sampleSize >= 0) { "Live Photo 영상 샘플을 읽지 못했습니다." }
            val extractorFlags = extractor.sampleFlags
            require(extractorFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) {
                "암호화된 Live Photo 영상 샘플은 변환할 수 없습니다."
            }
            val codecFlags =
                (if (extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else 0) or
                (if (extractorFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
                    MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                } else 0)
            info.set(0, sampleSize, extractor.sampleTime, codecFlags)
            activeMuxer.writeSampleData(trackMap[trackIndex], buffer, info)
            require(extractor.advance() || extractor.sampleTrackIndex < 0) { "Live Photo 영상 진행에 실패했습니다." }
        }
        activeMuxer.stop()
        activeMuxer.release()
        muxer = null
        return output.readBoundedBytes()
    } catch (error: Throwable) {
        throw IllegalStateException("Live Photo 영상을 MP4로 변환하지 못했습니다.", error)
    } finally {
        runCatching { extractor.release() }
        runCatching { muxer?.release() }
        output.delete()
    }
}

private fun File.readBoundedBytes(): ByteArray {
    require(length() in 0..512L * 1024 * 1024) { "움직이는 사진 구성요소가 너무 큽니다." }
    return inputStream().buffered().use { readBounded(it, length()) }
}

private fun ensureJpeg(context: Context, bytes: ByteArray): ByteArray {
    if (bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
        bytes[bytes.lastIndex - 1] == 0xff.toByte() && bytes.last() == 0xd9.toByte()
    ) return bytes
    val sourceExif = runCatching { ExifInterface(ByteArrayInputStream(bytes)) }.getOrNull()
    val orientation = sourceExif?.getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL,
    ) ?: ExifInterface.ORIENTATION_NORMAL
    val decoded = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) {
        "대표 사진을 JPEG로 변환하지 못했습니다."
    }
    val transform = exifPixelTransform(orientation)
    val matrix = Matrix().apply {
        if (transform.rotationDegrees != 0f) postRotate(transform.rotationDegrees)
        if (transform.mirrorHorizontal) postScale(-1f, 1f)
    }
    val normalized = if (transform == ExifPixelTransform.NORMAL) decoded else {
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }
    val output = File.createTempFile("photo-transfer-jpeg-", ".jpg", context.cacheDir)
    try {
        output.outputStream().buffered().use { stream ->
            check(normalized.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                "대표 사진을 JPEG로 변환하지 못했습니다."
            }
        }
        val destinationExif = ExifInterface(output)
        if (sourceExif != null) copyPracticalExif(sourceExif, destinationExif)
        destinationExif.setAttribute(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL.toString(),
        )
        destinationExif.saveAttributes()
        return output.readBoundedBytes()
    } finally {
        if (normalized !== decoded) normalized.recycle()
        decoded.recycle()
        output.delete()
    }
}

internal data class ExifPixelTransform(
    val rotationDegrees: Float,
    val mirrorHorizontal: Boolean,
) {
    companion object {
        val NORMAL = ExifPixelTransform(0f, false)
    }
}

internal fun exifPixelTransform(orientation: Int): ExifPixelTransform = when (orientation) {
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ExifPixelTransform(0f, true)
    ExifInterface.ORIENTATION_ROTATE_180 -> ExifPixelTransform(180f, false)
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> ExifPixelTransform(180f, true)
    ExifInterface.ORIENTATION_TRANSPOSE -> ExifPixelTransform(90f, true)
    ExifInterface.ORIENTATION_ROTATE_90 -> ExifPixelTransform(90f, false)
    ExifInterface.ORIENTATION_TRANSVERSE -> ExifPixelTransform(-90f, true)
    ExifInterface.ORIENTATION_ROTATE_270 -> ExifPixelTransform(-90f, false)
    else -> ExifPixelTransform.NORMAL
}

private fun copyPracticalExif(source: ExifInterface, destination: ExifInterface) {
    PRACTICAL_EXIF_TAGS.forEach { tag ->
        source.getAttribute(tag)?.let { value ->
            runCatching { destination.setAttribute(tag, value) }
        }
    }
}

private val PRACTICAL_EXIF_TAGS = arrayOf(
    ExifInterface.TAG_DATETIME,
    ExifInterface.TAG_DATETIME_ORIGINAL,
    ExifInterface.TAG_DATETIME_DIGITIZED,
    ExifInterface.TAG_OFFSET_TIME,
    ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
    ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
    ExifInterface.TAG_SUBSEC_TIME,
    ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
    ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
    ExifInterface.TAG_GPS_LATITUDE,
    ExifInterface.TAG_GPS_LATITUDE_REF,
    ExifInterface.TAG_GPS_LONGITUDE,
    ExifInterface.TAG_GPS_LONGITUDE_REF,
    ExifInterface.TAG_GPS_ALTITUDE,
    ExifInterface.TAG_GPS_ALTITUDE_REF,
    ExifInterface.TAG_GPS_DATESTAMP,
    ExifInterface.TAG_GPS_TIMESTAMP,
    ExifInterface.TAG_GPS_PROCESSING_METHOD,
    ExifInterface.TAG_GPS_SPEED,
    ExifInterface.TAG_GPS_SPEED_REF,
    ExifInterface.TAG_GPS_IMG_DIRECTION,
    ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
    ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
    ExifInterface.TAG_MAKE,
    ExifInterface.TAG_MODEL,
    ExifInterface.TAG_BODY_SERIAL_NUMBER,
    ExifInterface.TAG_LENS_MAKE,
    ExifInterface.TAG_LENS_MODEL,
    ExifInterface.TAG_LENS_SERIAL_NUMBER,
    ExifInterface.TAG_LENS_SPECIFICATION,
    ExifInterface.TAG_EXPOSURE_TIME,
    ExifInterface.TAG_F_NUMBER,
    ExifInterface.TAG_APERTURE_VALUE,
    ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
    ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
    ExifInterface.TAG_EXPOSURE_MODE,
    ExifInterface.TAG_EXPOSURE_PROGRAM,
    ExifInterface.TAG_METERING_MODE,
    ExifInterface.TAG_SHUTTER_SPEED_VALUE,
    ExifInterface.TAG_BRIGHTNESS_VALUE,
    ExifInterface.TAG_MAX_APERTURE_VALUE,
    ExifInterface.TAG_FOCAL_LENGTH,
    ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
    ExifInterface.TAG_FLASH,
    ExifInterface.TAG_WHITE_BALANCE,
    ExifInterface.TAG_LIGHT_SOURCE,
    ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
    ExifInterface.TAG_SCENE_CAPTURE_TYPE,
    ExifInterface.TAG_SUBJECT_DISTANCE,
    ExifInterface.TAG_SUBJECT_DISTANCE_RANGE,
    ExifInterface.TAG_CONTRAST,
    ExifInterface.TAG_SATURATION,
    ExifInterface.TAG_SHARPNESS,
    ExifInterface.TAG_GAIN_CONTROL,
    ExifInterface.TAG_CUSTOM_RENDERED,
    ExifInterface.TAG_COLOR_SPACE,
    ExifInterface.TAG_IMAGE_DESCRIPTION,
    ExifInterface.TAG_USER_COMMENT,
    ExifInterface.TAG_ARTIST,
    ExifInterface.TAG_COPYRIGHT,
    ExifInterface.TAG_SOFTWARE,
    ExifInterface.TAG_IMAGE_UNIQUE_ID,
)

private fun saveReceivedFile(
    context: Context,
    input: InputStream,
    header: PhotoTransferFileHeader,
    completedFiles: Int,
    onProgress: (PhotoTransferProgress) -> Unit,
    onStaged: ((Uri, Long, String) -> Unit)? = null,
): SavedPhotoTransferResult {
    val sourcePlatform = PhotoPeerPlatform.fromWireValue(header.sourcePlatform)
    val route = photoTransferRoute(sourcePlatform, PhotoPeerPlatform.ANDROID)
    val isVideo = when (route) {
        PhotoTransferRoute.SAME_PLATFORM_ORIGINAL -> {
            header.mediaKind == "video" || header.mimeType.startsWith("video/")
        }
        PhotoTransferRoute.CROSS_PLATFORM_CONVERSION -> {
            // The converter plugs into this branch; until then the exact source bytes are preserved.
            header.mediaKind == "video" || header.mimeType.startsWith("video/")
        }
        PhotoTransferRoute.UNKNOWN_PEER_ORIGINAL -> {
            header.mediaKind == "video" || header.mimeType.startsWith("video/")
        }
    }
    val collection = if (isVideo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
    } else {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
    }
    val displayName = safeDisplayName(header.name, isVideo)
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, header.mimeType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val root = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$root/NasFinder")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val destination = checkNotNull(resolver.insert(collection, values)) { "받은 파일을 저장하지 못했습니다." }
    var published = false
    try {
        onStaged?.invoke(destination, header.byteLength, checkNotNull(header.sha256) { "출력 체크섬이 필요합니다." })
        val output = checkNotNull(resolver.openOutputStream(destination, "w")) {
            "받은 파일을 저장하지 못했습니다."
        }
        output.use {
            copyExactly(input, it, header.byteLength) { fileBytes ->
                onProgress(
                    PhotoTransferProgress(
                        fileName = displayName,
                        completedFiles = completedFiles,
                        totalFiles = null,
                        transferredBytes = fileBytes,
                        totalBytes = header.byteLength,
                    ),
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(
                destination,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
        }
        published = true
        return querySavedPhotoTransferResult(
            context = context,
            uri = destination,
            fallbackName = displayName,
            fallbackMimeType = header.mimeType,
            verifiedMotionPhoto = header.itemKind == "motionPhoto" &&
                header.componentRole == "motionContainer",
        )
    } catch (error: Throwable) {
        // A row already published and journaled (STAGING) is left for recoverStaging to verify; only an unpublished
        // pending row of ours is removed.
        if (!(published && onStaged != null)) resolver.delete(destination, null, null)
        throw error
    }
}

private fun querySavedPhotoTransferResult(
    context: Context,
    uri: Uri,
    fallbackName: String,
    fallbackMimeType: String,
    verifiedMotionPhoto: Boolean,
): SavedPhotoTransferResult {
    var displayName = fallbackName
    var mimeType = fallbackMimeType
    var specialFormat = 0
    val columns = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE)
    val queried = context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use false
        val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
        val mimeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
        check(nameIndex >= 0 && mimeIndex >= 0 && !cursor.isNull(nameIndex) && !cursor.isNull(mimeIndex)) {
            "저장된 미디어 형식을 확인하지 못했습니다."
        }
        displayName = cursor.getString(nameIndex)
        mimeType = cursor.getString(mimeIndex)
        true
    } ?: false
    check(queried) { "저장된 미디어 정보를 확인하지 못했습니다." }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mimeType.startsWith("image/")) {
        specialFormat = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Files.FileColumns.SPECIAL_FORMAT),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use 0
                val index = cursor.getColumnIndex(MediaStore.Files.FileColumns.SPECIAL_FORMAT)
                if (index >= 0 && !cursor.isNull(index)) cursor.getInt(index) else 0
            } ?: 0
        }.getOrDefault(0)
    }
    return SavedPhotoTransferResult(
        uri = uri,
        displayName = displayName,
        mimeType = mimeType,
        kind = savedPhotoTransferKind(
            mimeType,
            verifiedMotionPhoto ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    specialFormat == MediaStore.Files.FileColumns.SPECIAL_FORMAT_MOTION_PHOTO),
        ),
    )
}

internal fun savedPhotoTransferKind(mimeType: String, isMotionPhoto: Boolean): SavedPhotoTransferKind = when {
    isMotionPhoto && mimeType.startsWith("image/") -> SavedPhotoTransferKind.MOTION_PHOTO
    mimeType.startsWith("video/") -> SavedPhotoTransferKind.VIDEO
    else -> SavedPhotoTransferKind.PHOTO
}

internal fun parseV2Handshake(line: String?, expectedToken: String): PhotoPeerPlatform? {
    val prefix = PhotoPairingPayload.HANDSHAKE_V2_PREFIX + expectedToken + " "
    if (line?.startsWith(prefix) != true) return null
    return PhotoPeerPlatform.fromWireValue(line.removePrefix(prefix))
        .takeUnless { it == PhotoPeerPlatform.UNKNOWN }
}

internal fun parseV3Handshake(line: String?, expectedToken: String): PhotoPeerPlatform? {
    val prefix = PhotoPairingPayload.HANDSHAKE_V3_PREFIX + expectedToken + " "
    if (line?.startsWith(prefix) != true) return null
    val parts = line.removePrefix(prefix).split(' ')
    if (parts.size != 2 || parts[1] != PhotoPairingPayload.GROUPED_CAPABILITY) return null
    return PhotoPeerPlatform.fromWireValue(parts[0]).takeUnless { it == PhotoPeerPlatform.UNKNOWN }
}

internal data class PhotoTransferV4Hello(val peer: PhotoPeerPlatform, val senderNonce: String)

internal class PhotoTransferV4Session(val peer: PhotoPeerPlatform, val sessionKey: ByteArray, val acceptLine: String)

/** `NASFINDER_PHOTO/4 hello <ios|android> grouped-v1 receipt-v1 <senderNonce>`: no token on the wire. */
internal fun parseV4Hello(line: String?): PhotoTransferV4Hello? {
    val prefix = PhotoPairingPayload.HANDSHAKE_V4_PREFIX + "hello "
    if (line?.startsWith(prefix) != true) return null
    val parts = line.removePrefix(prefix).split(' ')
    if (parts.size != 4 || parts[1] != PhotoPairingPayload.GROUPED_CAPABILITY ||
        parts[2] != PhotoPairingPayload.RECEIPT_CAPABILITY || !PhotoTransferAuth.isValidNonce(parts[3])
    ) return null
    val platform = PhotoPeerPlatform.fromWireValue(parts[0]).takeUnless { it == PhotoPeerPlatform.UNKNOWN } ?: return null
    return PhotoTransferV4Hello(platform, parts[3])
}

/**
 * Receiver half of the v4 challenge/proof (wire contract §1). Returns the authenticated session, or writes `ERROR`
 * and returns null so the socket stays unclaimed.
 */
private fun authenticateV4Hello(client: Socket, token: String, hello: PhotoTransferV4Hello): PhotoTransferV4Session? {
    val output = client.getOutputStream()
    val receiverNonce = PhotoTransferAuth.newNonce()
    output.write("CHALLENGE $receiverNonce\n".toByteArray(Charsets.US_ASCII))
    output.flush()
    val line = readAsciiLine(client, 128)
    val key = PhotoTransferAuth.deriveKey(token)
    val expected = PhotoTransferAuth.proof(key, "S", hello.peer.wireValue, hello.senderNonce, receiverNonce)
    val presented = line?.takeIf { it.startsWith("PROOF ") }?.removePrefix("PROOF ")
    if (presented == null || !PhotoTransferAuth.constantTimeEquals(presented, expected)) {
        output.write("ERROR\n".toByteArray())
        output.flush()
        return null
    }
    val receiverProof = PhotoTransferAuth.proof(key, "R", PhotoPeerPlatform.ANDROID.wireValue, hello.senderNonce, receiverNonce)
    return PhotoTransferV4Session(
        peer = hello.peer,
        sessionKey = PhotoTransferAuth.sessionKey(key, hello.senderNonce, receiverNonce),
        acceptLine = "OK android ${PhotoPairingPayload.GROUPED_CAPABILITY} ${PhotoPairingPayload.RECEIPT_CAPABILITY} $receiverProof\n",
    )
}

internal fun parseTransferResult(line: String?): Int? {
    val prefix = "RESULT OK "
    if (line?.startsWith(prefix) != true) return null
    val value = line.removePrefix(prefix)
    if (value.isEmpty() || value.any { !it.isDigit() }) return null
    return value.toIntOrNull()?.takeIf { it in 0..10_000 }
}

private fun safeDisplayName(rawName: String, isVideo: Boolean): String {
    val fallback = if (isVideo) "video" else "photo"
    val cleaned = rawName.substringAfterLast('/').substringAfterLast('\\')
        .filterNot { it.isISOControl() }
        .trim()
        .take(180)
    return cleaned.ifBlank { "$fallback-${UUID.randomUUID()}" }
}

private fun copyExactly(
    input: InputStream,
    output: java.io.OutputStream,
    byteLength: Long,
    onProgress: (Long) -> Unit,
) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var copied = 0L
    var lastReported = 0L
    while (copied < byteLength) {
        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), byteLength - copied).toInt())
        check(read > 0) { "파일 데이터가 예상보다 일찍 끝났습니다." }
        output.write(buffer, 0, read)
        copied += read
        if (copied == byteLength || copied - lastReported >= 256 * 1024L) {
            onProgress(copied)
            lastReported = copied
        }
    }
}

private fun sha256Exactly(input: InputStream, byteLength: Long): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var remaining = byteLength
    while (remaining > 0L) {
        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        check(read > 0) { "체크섬 계산 중 파일이 예상보다 일찍 끝났습니다." }
        digest.update(buffer, 0, read)
        remaining -= read
    }
    return digest.digest().toHex()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun photoTransferSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun readBounded(input: InputStream, declaredLength: Long): ByteArray {
    require(declaredLength in 0..512L * 1024 * 1024) { "Motion Photo가 너무 큽니다." }
    val output = ByteArrayOutputStream(declaredLength.toInt())
    copyExactly(input, output, declaredLength) {}
    return output.toByteArray()
}

private fun normalizedItemKind(mediaKind: String, mimeType: String): String = when (mediaKind) {
    "motionPhoto" -> "motionPhoto"
    "video" -> "video"
    "photo" -> "photo"
    else -> if (mimeType.startsWith("video/")) "video" else "photo"
}

internal suspend fun pairingQrBitmap(contents: String, size: Int = 720): Bitmap =
    withContext(Dispatchers.Default) {
        val matrix = MultiFormatWriter().encode(contents, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, size, 0, 0, size, size)
        }
    }

private fun localIpv4Address(): String? = Collections.list(NetworkInterface.getNetworkInterfaces())
    .filter { it.isUp && !it.isLoopback }
    .sortedBy { network ->
        if (network.name.startsWith("wlan") || network.name.startsWith("wifi")) 0 else 1
    }
    .asSequence()
    .flatMap { network -> Collections.list(network.inetAddresses).asSequence() }
    .filterIsInstance<Inet4Address>()
    .firstOrNull { it.isSiteLocalAddress }
    ?.hostAddress

private fun readAsciiLine(socket: Socket, maximumBytes: Int): String? {
    val input = socket.getInputStream()
    val bytes = ArrayList<Byte>(maximumBytes)
    while (bytes.size < maximumBytes) {
        val value = input.read()
        if (value == -1) return null
        if (value == '\n'.code) return bytes.toByteArray().toString(Charsets.US_ASCII).trimEnd('\r')
        bytes.add(value.toByte())
    }
    return null
}

/**
 * 화면에 보이는 오류 문구. 시스템·소켓이 영어로 돌려주는 메시지(`failed to connect to …` 등)는 내지 않고
 * 한글 설명이 있을 때만 쓴다. 없으면 한글 원인+복구 안내 문구를 쓴다.
 */
internal fun Throwable.userMessage(fallback: String): String {
    val text = message.orEmpty()
    return if (text.any { it in '\uAC00'..'\uD7A3' }) text else fallback
}
