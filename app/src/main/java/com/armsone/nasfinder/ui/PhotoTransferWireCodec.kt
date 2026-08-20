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
}

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

    fun readMessage(input: InputStream): PhotoTransferWireMessage? {
        val data = DataInputStream(input)
        val length = try {
            data.readInt()
        } catch (_: EOFException) {
            return null
        }
        require(length in 1..MAX_HEADER_BYTES) { "잘못된 전송 헤더입니다." }
        val bytes = ByteArray(length)
        data.readFully(bytes)
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
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
