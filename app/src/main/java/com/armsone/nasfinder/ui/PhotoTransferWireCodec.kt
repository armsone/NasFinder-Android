package com.armsone.nasfinder.ui

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import org.json.JSONObject

internal data class PhotoTransferFileHeader(
    val id: String,
    val name: String,
    val mimeType: String,
    val mediaKind: String,
    val sourcePlatform: String,
    val byteLength: Long,
    val itemId: String = id,
    val groupId: String = id,
    val itemKind: String = mediaKind,
    val componentRole: String = "regularFile",
    val componentIndex: Int = 0,
    val componentCount: Int = 1,
    val sha256: String? = null,
    val stillImageTimeUs: Long = -1,
)

internal enum class PhotoTransferItemKind(val wireValue: String) {
    PHOTO("photo"), VIDEO("video"), LIVE_PHOTO("livePhoto"), MOTION_PHOTO("motionPhoto");

    companion object {
        fun fromWireValue(value: String): PhotoTransferItemKind? = entries.firstOrNull { it.wireValue == value }
    }
}

internal enum class PhotoTransferComponentRole(val wireValue: String) {
    REGULAR_FILE("regularFile"), PRIMARY_IMAGE("primaryImage"), MOTION_VIDEO("motionVideo"),
    MOTION_CONTAINER("motionContainer");

    companion object {
        fun fromWireValue(value: String): PhotoTransferComponentRole? = entries.firstOrNull { it.wireValue == value }
    }
}

internal enum class PhotoPeerPlatform(val wireValue: String, val displayName: String) {
    IOS("ios", "iPhone/iPad"),
    ANDROID("android", "Android"),
    UNKNOWN("unknown", "상대 기기");

    companion object {
        fun fromWireValue(value: String?): PhotoPeerPlatform = entries.firstOrNull {
            it.wireValue == value
        } ?: UNKNOWN
    }
}

internal enum class PhotoTransferRoute {
    SAME_PLATFORM_ORIGINAL,
    CROSS_PLATFORM_CONVERSION,
    UNKNOWN_PEER_ORIGINAL,
}

internal fun photoTransferRoute(
    source: PhotoPeerPlatform,
    target: PhotoPeerPlatform,
): PhotoTransferRoute = when {
    source == PhotoPeerPlatform.UNKNOWN || target == PhotoPeerPlatform.UNKNOWN -> {
        PhotoTransferRoute.UNKNOWN_PEER_ORIGINAL
    }
    source == target -> PhotoTransferRoute.SAME_PLATFORM_ORIGINAL
    else -> PhotoTransferRoute.CROSS_PLATFORM_CONVERSION
}

internal sealed interface PhotoTransferWireMessage {
    data class File(val header: PhotoTransferFileHeader) : PhotoTransferWireMessage
    data object Done : PhotoTransferWireMessage
    /** v4 only: first frame of a transfer attempt; [transferId] stays stable across reconnects. */
    data class Begin(val transferId: String, val itemCount: Int) : PhotoTransferWireMessage
}

/**
 * v4 receipt frame (receiver → sender), authenticated per frame (wire contract §2.2/§1.2).
 * Receiver-local asset identifiers are never part of the wire: there is no output id.
 */
internal data class PhotoTransferReceiptItem(
    val itemId: String,
    val committed: Boolean,
    val inputs: Map<Int, String> = emptyMap(),
    val reason: String? = null,
)

internal data class PhotoTransferReceipt(
    val stage: String,
    val transferId: String,
    val receiverId: String,
    val status: String?,
    val more: Boolean,
    val seq: Int,
    val items: List<PhotoTransferReceiptItem>,
)

/**
 * v4 mutual authentication (wire contract §1): the QR token is only an HMAC key, never sent. Constant-time compare.
 * K = HMAC(token, "NASFINDER_PHOTO/4 key"); proofs bind both nonces and the claimed platform;
 * the per-session receipt key binds the same nonces so receipts cannot be replayed into another session.
 */
internal object PhotoTransferAuth {
    private const val ALGORITHM = "HmacSHA256"
    private val NONCE_PATTERN = Regex("^[A-Za-z0-9_-]{22,43}$")

    fun newNonce(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun isValidNonce(value: String): Boolean = NONCE_PATTERN.matches(value)

    private fun hmac(key: ByteArray, message: String): ByteArray {
        val mac = javax.crypto.Mac.getInstance(ALGORITHM)
        mac.init(javax.crypto.spec.SecretKeySpec(key, ALGORITHM))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun deriveKey(token: String): ByteArray = hmac(token.toByteArray(Charsets.UTF_8), "NASFINDER_PHOTO/4 key")

    /** [role] is "S" (sender proof) or "R" (receiver proof). */
    fun proof(key: ByteArray, role: String, platform: String, senderNonce: String, receiverNonce: String): String =
        hex(hmac(key, "NF4-$role\n$platform\n$senderNonce\n$receiverNonce"))

    fun sessionKey(key: ByteArray, senderNonce: String, receiverNonce: String): ByteArray =
        hmac(key, "NF4-session\n$senderNonce\n$receiverNonce")

    fun constantTimeEquals(a: String, b: String): Boolean =
        java.security.MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    /** Canonical byte string a receipt frame's `mac` covers (wire contract §2.2). */
    fun receiptCanonical(receipt: PhotoTransferReceipt): String = buildString {
        append("receipt\n").append(receipt.stage).append('\n').append(receipt.transferId).append('\n')
        append(receipt.receiverId).append('\n').append(receipt.status ?: "-").append('\n')
        append(receipt.more).append('\n').append(receipt.seq)
        receipt.items.sortedBy { it.itemId }.forEach { item ->
            append('\n').append(item.itemId).append('|').append(if (item.committed) "committed" else "failed").append('|')
            append(item.inputs.toSortedMap().entries.joinToString(",") { "${it.key}:${it.value}" }).append('|')
            append(item.reason ?: "-")
        }
    }

    fun receiptMac(sessionKey: ByteArray, receipt: PhotoTransferReceipt): String =
        hex(hmac(sessionKey, receiptCanonical(receipt)))
}

private val STABLE_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
private val TRANSFER_ID_PATTERN = Regex("^[A-Za-z0-9_-]{22,64}$")
private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
private val REASON_PATTERN = Regex("^[A-Za-z0-9_]{1,16}$")
internal const val MAX_TRANSFER_ITEMS = 10_000
private const val MAX_RECEIPT_ITEMS_PER_FRAME = 128

internal fun isValidTransferId(value: String): Boolean = TRANSFER_ID_PATTERN.matches(value)
internal fun isValidItemId(value: String): Boolean = STABLE_ID_PATTERN.matches(value)

internal object PhotoTransferWireCodec {
    private const val MAX_HEADER_BYTES = 64 * 1024
    // iOS 코덱과 동일한 단일 파일 한도.
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024

    fun writeFileHeader(output: OutputStream, header: PhotoTransferFileHeader) {
        require(header.id.isNotBlank())
        require(header.name.isNotBlank())
        require(header.mimeType.isNotBlank())
        require(header.mediaKind.isNotBlank())
        require(header.sourcePlatform in setOf("ios", "android"))
        require(header.byteLength in 0..MAX_FILE_BYTES)
        val json = JSONObject()
                .put("id", header.id)
                .put("name", header.name)
                .put("mimeType", header.mimeType)
                .put("mediaKind", header.mediaKind)
                .put("sourcePlatform", header.sourcePlatform)
                .put("byteLength", header.byteLength)
        if (header.sha256 != null) {
            validateExtendedHeader(header)
            json.put("itemId", header.itemId)
                .put("groupId", header.groupId)
                .put("itemKind", header.itemKind)
                .put("componentRole", header.componentRole)
                .put("componentIndex", header.componentIndex)
                .put("componentCount", header.componentCount)
                .put("sha256", header.sha256)
                .put("stillImageTimeUs", header.stillImageTimeUs)
        }
        writeJson(output, json)
    }

    fun writeDone(output: OutputStream) {
        writeJson(output, JSONObject().put("done", true))
    }

    fun writeBegin(output: OutputStream, transferId: String, itemCount: Int) {
        require(isValidTransferId(transferId)) { "전송 식별자가 올바르지 않습니다." }
        require(itemCount in 1..MAX_TRANSFER_ITEMS)
        writeJson(output, JSONObject().put("begin", true).put("transferId", transferId).put("itemCount", itemCount))
    }

    /** Writes one receipt frame; callers split [items] so each frame stays under [MAX_RECEIPT_ITEMS_PER_FRAME]. */
    fun writeReceipt(output: OutputStream, receipt: PhotoTransferReceipt, sessionKey: ByteArray) {
        require(receipt.stage in setOf("ready", "final", "error"))
        require(isValidTransferId(receipt.transferId) && isValidTransferId(receipt.receiverId))
        require(receipt.items.size <= MAX_RECEIPT_ITEMS_PER_FRAME && receipt.seq >= 1)
        val json = JSONObject()
            .put("receipt", true)
            .put("stage", receipt.stage)
            .put("transferId", receipt.transferId)
            .put("receiverId", receipt.receiverId)
            .put("more", receipt.more)
            .put("seq", receipt.seq)
        receipt.status?.let { json.put("status", it) }
        val items = org.json.JSONArray()
        receipt.items.forEach { item ->
            require(isValidItemId(item.itemId))
            val entry = JSONObject().put("itemId", item.itemId).put("state", if (item.committed) "committed" else "failed")
            if (item.inputs.isNotEmpty()) {
                val inputs = org.json.JSONArray()
                item.inputs.toSortedMap().forEach { (index, sha) ->
                    inputs.put(JSONObject().put("index", index).put("sha256", sha))
                }
                entry.put("inputs", inputs)
            }
            item.reason?.let { entry.put("reason", it) }
            items.put(entry)
        }
        json.put("items", items)
        json.put("mac", PhotoTransferAuth.receiptMac(sessionKey, receipt))
        writeJson(output, json)
    }

    /**
     * Reads one receipt frame; strict key sets, null on clean EOF. The frame must carry the next expected [expectedSeq]
     * and a valid per-session MAC, otherwise it is rejected (forged, replayed, reordered or dropped frames).
     */
    fun readReceipt(input: InputStream, sessionKey: ByteArray, expectedSeq: Int): PhotoTransferReceipt? {
        val json = readJson(input) ?: return null
        val allowedKeys = setOf("receipt", "stage", "transferId", "receiverId", "status", "more", "seq", "items", "mac")
        val requiredKeys = setOf("receipt", "stage", "transferId", "receiverId", "more", "seq", "items", "mac")
        require(
            json.optBoolean("receipt", false) && json.keys().asSequence().all { it in allowedKeys } &&
                requiredKeys.all(json::has) && json.get("more") is Boolean,
        ) { "잘못된 수신 확인 프레임입니다." }
        val stage = json.getString("stage")
        require(stage in setOf("ready", "final", "error")) { "잘못된 수신 확인 단계입니다." }
        val transferId = json.getString("transferId")
        val receiverId = json.getString("receiverId")
        require(isValidTransferId(transferId) && isValidTransferId(receiverId)) { "잘못된 수신 확인 식별자입니다." }
        // `status` may be absent (ready); when present it must be one of the three values (canonical uses "-" only for absent).
        val status = if (json.has("status")) json.get("status") as? String else null
        require(!json.has("status") || status in setOf("complete", "partial", "failed")) { "잘못된 수신 확인 상태입니다." }
        val entries = json.getJSONArray("items")
        require(entries.length() <= MAX_RECEIPT_ITEMS_PER_FRAME) { "수신 확인 항목이 너무 많습니다." }
        val items = (0 until entries.length()).map { index ->
            val entry = entries.getJSONObject(index)
            val itemKeys = setOf("itemId", "state", "inputs", "reason")
            require(entry.keys().asSequence().all { it in itemKeys }) { "잘못된 수신 확인 항목입니다." }
            val itemId = entry.getString("itemId")
            require(isValidItemId(itemId)) { "잘못된 수신 확인 항목 식별자입니다." }
            val state = entry.getString("state")
            require(state == "committed" || state == "failed") { "잘못된 수신 확인 항목 상태입니다." }
            val inputs = linkedMapOf<Int, String>()
            entry.optJSONArray("inputs")?.let { array ->
                for (i in 0 until array.length()) {
                    val input = array.getJSONObject(i)
                    val componentIndex = input.getInt("index")
                    val sha = input.getString("sha256")
                    require(componentIndex in 0 until 8 && SHA256_PATTERN.matches(sha) && inputs.put(componentIndex, sha) == null) {
                        "잘못된 수신 확인 해시입니다."
                    }
                }
            }
            PhotoTransferReceiptItem(
                itemId = itemId,
                committed = state == "committed",
                inputs = inputs,
                reason = if (entry.has("reason")) {
                    // Never truncate or rewrite: the MAC covers the exact string, and "-" is reserved for "absent".
                    (entry.get("reason") as? String)?.takeIf(REASON_PATTERN::matches)
                        ?: throw IllegalArgumentException("잘못된 수신 확인 사유입니다.")
                } else null,
            )
        }
        val seq = json.getInt("seq")
        require(seq == expectedSeq) { "수신 확인 순서가 올바르지 않습니다." }
        val receipt = PhotoTransferReceipt(stage, transferId, receiverId, status, json.optBoolean("more", false), seq, items)
        val mac = json.getString("mac")
        require(SHA256_PATTERN.matches(mac) && PhotoTransferAuth.constantTimeEquals(mac, PhotoTransferAuth.receiptMac(sessionKey, receipt))) {
            "수신 확인 서명이 올바르지 않습니다."
        }
        return receipt
    }

    private fun readJson(input: InputStream): JSONObject? {
        val data = DataInputStream(input)
        val length = try {
            data.readInt()
        } catch (_: EOFException) {
            return null
        }
        require(length in 1..MAX_HEADER_BYTES) { "잘못된 전송 헤더입니다." }
        val bytes = ByteArray(length)
        data.readFully(bytes)
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }

    fun readMessage(input: InputStream, allowBegin: Boolean = false): PhotoTransferWireMessage? {
        val json = readJson(input) ?: return null
        if (json.optBoolean("begin", false)) {
            require(allowBegin) { "지원하지 않는 시작 헤더입니다." }
            require(json.length() == 3 && json.has("transferId") && json.has("itemCount")) { "잘못된 시작 헤더입니다." }
            val transferId = json.getString("transferId")
            val itemCount = json.getInt("itemCount")
            require(isValidTransferId(transferId) && itemCount in 1..MAX_TRANSFER_ITEMS) { "잘못된 시작 헤더입니다." }
            return PhotoTransferWireMessage.Begin(transferId, itemCount)
        }
        if (json.optBoolean("done", false)) {
            require(json.length() == 1) { "잘못된 완료 헤더입니다." }
            return PhotoTransferWireMessage.Done
        }
        val keys = buildSet {
            val iterator = json.keys()
            while (iterator.hasNext()) add(iterator.next())
        }
        val legacyKeys = setOf(
                "id",
                "name",
                "mimeType",
                "mediaKind",
                "sourcePlatform",
                "byteLength",
            )
        val extendedKeys = legacyKeys + setOf(
            "itemId", "groupId", "itemKind", "componentRole",
            "componentIndex", "componentCount", "sha256", "stillImageTimeUs",
        )
        require(keys == legacyKeys || keys == extendedKeys) {
            "잘못된 파일 헤더입니다."
        }
        val header = PhotoTransferFileHeader(
            id = json.getString("id"),
            name = json.getString("name"),
            mimeType = json.getString("mimeType"),
            mediaKind = json.getString("mediaKind"),
            sourcePlatform = json.getString("sourcePlatform"),
            byteLength = json.getLong("byteLength"),
            itemId = if (keys == extendedKeys) json.getString("itemId") else json.getString("id"),
            groupId = if (keys == extendedKeys) json.getString("groupId") else json.getString("id"),
            itemKind = if (keys == extendedKeys) json.getString("itemKind") else json.getString("mediaKind"),
            componentRole = if (keys == extendedKeys) json.getString("componentRole") else "regularFile",
            componentIndex = if (keys == extendedKeys) json.getInt("componentIndex") else 0,
            componentCount = if (keys == extendedKeys) json.getInt("componentCount") else 1,
            sha256 = if (keys == extendedKeys) json.getString("sha256") else null,
            stillImageTimeUs = if (keys == extendedKeys) json.getLong("stillImageTimeUs") else -1,
        )
        require(header.id.isNotBlank() && header.name.isNotBlank()) { "파일 정보가 비어 있습니다." }
        require(header.mimeType.isNotBlank() && header.mediaKind.isNotBlank()) { "파일 형식이 비어 있습니다." }
        require(header.sourcePlatform in setOf("ios", "android")) { "지원하지 않는 발신 기기입니다." }
        require(header.byteLength in 0..MAX_FILE_BYTES) { "지원하지 않는 파일 크기입니다." }
        if (keys == extendedKeys) validateExtendedHeader(header)
        return PhotoTransferWireMessage.File(header)
    }

    private fun validateExtendedHeader(header: PhotoTransferFileHeader) {
        require(header.itemId.isNotBlank() && header.groupId.isNotBlank()) { "그룹 정보가 비어 있습니다." }
        require(PhotoTransferItemKind.fromWireValue(header.itemKind) != null) { "지원하지 않는 항목 형식입니다." }
        require(PhotoTransferComponentRole.fromWireValue(header.componentRole) != null) { "지원하지 않는 구성요소입니다." }
        require(header.componentCount in 1..8 && header.componentIndex in 0 until header.componentCount) {
            "잘못된 구성요소 순서입니다."
        }
        require(header.sha256?.matches(Regex("^[0-9a-f]{64}$")) == true) { "잘못된 체크섬입니다." }
        require(header.stillImageTimeUs >= -1) { "대표 프레임 시간이 올바르지 않습니다." }
        if (header.componentRole in setOf("regularFile", "motionContainer")) {
            require(header.componentCount == 1 && header.componentIndex == 0) { "일반 파일 그룹이 올바르지 않습니다." }
            if (header.componentRole == "motionContainer") {
                require(header.itemKind == "motionPhoto") { "Motion Photo 컨테이너가 올바르지 않습니다." }
            } else {
                require(header.itemKind != "motionPhoto") { "Motion Photo는 컨테이너 역할이 필요합니다." }
            }
        } else {
            require(header.itemKind in setOf("livePhoto", "motionPhoto")) { "움직이는 사진 그룹이 올바르지 않습니다." }
            require(header.componentCount == 2) { "움직이는 사진은 두 구성요소가 필요합니다." }
            require(
                (header.componentIndex == 0 && header.componentRole == "primaryImage") ||
                    (header.componentIndex == 1 && header.componentRole == "motionVideo"),
            ) { "움직이는 사진 구성요소 순서가 올바르지 않습니다." }
        }
    }

    private fun writeJson(output: OutputStream, json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_HEADER_BYTES)
        DataOutputStream(output).apply {
            writeInt(bytes.size)
            write(bytes)
        }
    }
}
