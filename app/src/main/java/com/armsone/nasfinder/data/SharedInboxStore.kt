package com.armsone.nasfinder.data

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

data class SharedInboxRecord(
    val id: UUID,
    val originalFilename: String,
    val storedFilename: String,
    val mimeType: String?,
    val byteCount: Long,
    val importedAt: Instant,
)

/**
 * Process-serialized, file-backed inbox. The caller owns the sharesheet limit
 * (currently 50 items); this store deliberately commits one record at a time.
 */
class SharedInboxStore private constructor(
    filesDirectory: File,
    private val hooks: Hooks,
) {
    val rootDirectory: File = File(filesDirectory, DIRECTORY_NAME)
    private val manifestFile: File
    @Volatile private var snapshot: List<SharedInboxRecord> = emptyList()

    constructor(context: Context) : this(context.filesDir, Hooks.DEFAULT)
    constructor(filesDirectory: File) : this(filesDirectory, Hooks.DEFAULT)
    internal constructor(filesDirectory: File, hooks: Hooks, @Suppress("UNUSED_PARAMETER") marker: Unit) :
        this(filesDirectory, hooks)

    init {
        if (!rootDirectory.exists() && !rootDirectory.mkdirs()) {
            throw IllegalStateException("받은 파일 저장소를 만들 수 없습니다.")
        }
        require(rootDirectory.isDirectory && !Files.isSymbolicLink(rootDirectory.toPath())) {
            "받은 파일 저장소가 안전한 폴더가 아닙니다."
        }
        manifestFile = File(rootDirectory, MANIFEST_NAME)
        synchronized(PROCESS_LOCK) { snapshot = readManifest() }
    }

    fun records(): List<SharedInboxRecord> = synchronized(PROCESS_LOCK) {
        readManifest().also { snapshot = it }
    }

    fun file(record: SharedInboxRecord): File = synchronized(PROCESS_LOCK) {
        validateRecord(record)
        val current = readManifest()
        require(current.any { it.id == record.id && it.storedFilename == record.storedFilename }) {
            "받은 파일 기록이 현재 manifest에 없습니다."
        }
        payloadFile(record.storedFilename).also {
            if (!it.isFile) throw IllegalStateException("받은 파일 payload가 없습니다.")
        }
    }

    fun import(
        originalFilename: String,
        mimeType: String?,
        input: InputStream,
        id: UUID = UUID.randomUUID(),
        importedAt: Instant = Instant.now(),
    ): SharedInboxRecord = synchronized(PROCESS_LOCK) {
        val current = readManifest()
        val original = safeBasename(originalFilename)
        val stored = storedFilename(id, original)
        require(current.none { it.id == id }) { "중복된 받은 파일 UUID입니다." }
        require(current.none { it.storedFilename == stored }) { "중복된 받은 파일 저장 이름입니다." }
        val safeMime = validateMimeType(mimeType)
        val destination = payloadFile(stored)
        require(!destination.exists()) { "받은 파일 payload가 이미 있습니다." }
        val temporary = File(rootDirectory, ".import-$id.tmp")
        var payloadCommitted = false
        try {
            val bytes = FileOutputStream(temporary).use { output ->
                val copied = input.copyTo(output)
                output.fd.sync()
                copied
            }
            atomicMove(temporary, destination)
            payloadCommitted = true
            val record = SharedInboxRecord(id, original, stored, safeMime, bytes, importedAt)
            val updated = current + record
            hooks.commitManifest(manifestFile, encodeManifest(updated))
            snapshot = updated
            record
        } catch (error: Throwable) {
            temporary.delete()
            if (payloadCommitted) destination.delete()
            snapshot = runCatching { readManifest() }.getOrElse { current }
            throw error
        }
    }

    fun delete(id: UUID): Boolean = synchronized(PROCESS_LOCK) {
        val current = readManifest()
        val record = current.firstOrNull { it.id == id } ?: return@synchronized false
        val payload = payloadFile(record.storedFilename)
        if (payload.exists() && !hooks.deletePayload(payload)) {
            snapshot = readManifest()
            throw IllegalStateException("받은 파일 payload를 삭제하지 못했습니다.")
        }
        val updated = current.filterNot { it.id == id }
        try {
            hooks.commitManifest(manifestFile, encodeManifest(updated))
            snapshot = updated
            true
        } catch (error: Throwable) {
            snapshot = readManifest()
            throw error
        }
    }

    private fun readManifest(): List<SharedInboxRecord> {
        if (!manifestFile.exists()) return emptyList()
        if (!manifestFile.isFile) throw IllegalStateException("받은 파일 manifest가 파일이 아닙니다.")
        val root = JsonReader(manifestFile.readText(StandardCharsets.UTF_8)).readValue() as? Map<*, *>
            ?: throw IllegalStateException("받은 파일 manifest 형식이 올바르지 않습니다.")
        require(root["version"].asLong() == MANIFEST_VERSION.toLong()) { "지원하지 않는 manifest 버전입니다." }
        val values = root["records"] as? List<*>
            ?: throw IllegalStateException("받은 파일 manifest records가 없습니다.")
        val records = values.map { value ->
            val item = value as? Map<*, *> ?: throw IllegalStateException("받은 파일 record가 올바르지 않습니다.")
            val id = item["id"] as? String ?: throw IllegalStateException("받은 파일 UUID가 없습니다.")
            val importedAt = item["importedAt"] as? String
                ?: throw IllegalStateException("받은 파일 시각이 없습니다.")
            SharedInboxRecord(
                id = UUID.fromString(id),
                originalFilename = item["originalFilename"] as? String
                    ?: throw IllegalStateException("원본 파일명이 없습니다."),
                storedFilename = item["storedFilename"] as? String
                    ?: throw IllegalStateException("저장 파일명이 없습니다."),
                mimeType = item["mimeType"] as? String,
                byteCount = item["byteCount"].asLong(),
                importedAt = Instant.parse(importedAt),
            ).also(::validateRecord)
        }
        require(records.map { it.id }.distinct().size == records.size) { "중복된 받은 파일 UUID입니다." }
        require(records.map { it.storedFilename }.distinct().size == records.size) {
            "중복된 받은 파일 저장 이름입니다."
        }
        return records
    }

    private fun validateRecord(record: SharedInboxRecord) {
        require(safeBasename(record.originalFilename) == record.originalFilename) { "원본 파일명이 안전하지 않습니다." }
        require(record.storedFilename == storedFilename(record.id, record.originalFilename)) {
            "저장 파일명이 UUID/확장자 계약과 맞지 않습니다."
        }
        payloadFile(record.storedFilename)
        validateMimeType(record.mimeType)
        require(record.byteCount >= 0) { "파일 크기가 올바르지 않습니다." }
    }

    private fun payloadFile(storedFilename: String): File {
        require(storedFilename == storedFilename.substringAfterLast('/') &&
            storedFilename == storedFilename.substringAfterLast('\\')) { "저장 경로가 안전하지 않습니다." }
        return File(rootDirectory, storedFilename).canonicalFile.also {
            require(it.parentFile == rootDirectory.canonicalFile) { "저장 경로가 받은 파일 폴더 밖입니다." }
        }
    }

    internal data class Hooks(
        val commitManifest: (File, ByteArray) -> Unit,
        val deletePayload: (File) -> Boolean,
    ) {
        companion object {
            val DEFAULT = Hooks(::atomicWrite, File::delete)
        }
    }

    private companion object {
        const val DIRECTORY_NAME = "SharedInbox"
        const val MANIFEST_NAME = "manifest.json"
        const val MANIFEST_VERSION = 1
        val PROCESS_LOCK = Any()
    }
}

private fun safeBasename(value: String): String {
    val candidate = value.substringAfterLast('/').substringAfterLast('\\').trim()
    return candidate.takeUnless { it.isBlank() || it == "." || it == ".." || it.any(Char::isISOControl) }
        ?: "받은 파일"
}

private fun storedFilename(id: UUID, original: String): String {
    val dot = original.lastIndexOf('.')
    val extension = if (dot > 0 && dot < original.lastIndex) original.substring(dot + 1) else ""
    val safeExtension = extension.takeIf { it.length <= 16 && it.all(Char::isLetterOrDigit) }
        ?.lowercase().orEmpty()
    return if (safeExtension.isEmpty()) id.toString() else "$id.$safeExtension"
}

private fun validateMimeType(value: String?): String? {
    val mime = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    require(mime.none(Char::isISOControl) && '/' in mime) { "MIME 형식이 올바르지 않습니다." }
    return mime
}

private fun encodeManifest(records: List<SharedInboxRecord>): ByteArray = buildString {
    append("{\"version\":1,\"records\":[")
    records.forEachIndexed { index, record ->
        if (index > 0) append(',')
        append('{')
        append("\"id\":").appendJson(record.id.toString()).append(',')
        append("\"originalFilename\":").appendJson(record.originalFilename).append(',')
        append("\"storedFilename\":").appendJson(record.storedFilename).append(',')
        append("\"mimeType\":")
        if (record.mimeType == null) append("null") else appendJson(record.mimeType)
        append(",\"byteCount\":").append(record.byteCount).append(',')
        append("\"importedAt\":").appendJson(record.importedAt.toString())
        append('}')
    }
    append("]}")
}.toByteArray(StandardCharsets.UTF_8)

private fun StringBuilder.appendJson(value: String): StringBuilder {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
        }
    }
    return append('"')
}

private fun atomicWrite(target: File, bytes: ByteArray) {
    val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
    try {
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        atomicMove(temporary, target)
    } finally {
        temporary.delete()
    }
}

private fun atomicMove(source: File, destination: File) {
    try {
        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun Any?.asLong(): Long = (this as? Number)?.toLong()
    ?: throw IllegalStateException("JSON 숫자 필드가 올바르지 않습니다.")

/** Strict subset JSON reader for the private manifest schema. */
private class JsonReader(private val source: String) {
    private var index = 0

    fun readValue(): Any? {
        skipWhitespace()
        val value = value()
        skipWhitespace()
        require(index == source.length) { "JSON 뒤에 알 수 없는 데이터가 있습니다." }
        return value
    }

    private fun value(): Any? {
        skipWhitespace()
        return when (source.getOrNull(index)) {
            '{' -> objectValue()
            '[' -> arrayValue()
            '"' -> stringValue()
            'n' -> literal("null", null)
            '-', in '0'..'9' -> numberValue()
            else -> throw IllegalStateException("JSON 값을 해석할 수 없습니다.")
        }
    }

    private fun objectValue(): Map<String, Any?> {
        expect('{')
        val result = linkedMapOf<String, Any?>()
        skipWhitespace()
        if (consume('}')) return result
        while (true) {
            val key = stringValue()
            skipWhitespace(); expect(':')
            require(!result.containsKey(key)) { "중복 JSON key입니다." }
            result[key] = value()
            skipWhitespace()
            if (consume('}')) return result
            expect(','); skipWhitespace()
        }
    }

    private fun arrayValue(): List<Any?> {
        expect('[')
        val result = mutableListOf<Any?>()
        skipWhitespace()
        if (consume(']')) return result
        while (true) {
            result += value()
            skipWhitespace()
            if (consume(']')) return result
            expect(','); skipWhitespace()
        }
    }

    private fun stringValue(): String {
        expect('"')
        val result = StringBuilder()
        while (true) {
            val character = source.getOrNull(index++) ?: throw IllegalStateException("끝나지 않은 JSON 문자열입니다.")
            when (character) {
                '"' -> return result.toString()
                '\\' -> {
                    val escaped = source.getOrNull(index++) ?: throw IllegalStateException("끝나지 않은 JSON escape입니다.")
                    result.append(when (escaped) {
                        '"', '\\', '/' -> escaped
                        'b' -> '\b'; 'f' -> '\u000C'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                        'u' -> {
                            val end = index + 4
                            require(end <= source.length) { "JSON unicode escape가 짧습니다." }
                            source.substring(index, end).toInt(16).toChar().also { index = end }
                        }
                        else -> throw IllegalStateException("지원하지 않는 JSON escape입니다.")
                    })
                }
                else -> {
                    require(character.code >= 0x20) { "JSON 문자열에 제어 문자가 있습니다." }
                    result.append(character)
                }
            }
        }
    }

    private fun numberValue(): Long {
        val start = index
        if (source.getOrNull(index) == '-') index++
        while (source.getOrNull(index)?.isDigit() == true) index++
        return source.substring(start, index).toLong()
    }

    private fun <T> literal(text: String, value: T): T {
        require(source.startsWith(text, index)) { "JSON literal이 올바르지 않습니다." }
        index += text.length
        return value
    }

    private fun expect(character: Char) {
        require(source.getOrNull(index) == character) { "JSON '$character' 문자가 필요합니다." }
        index++
    }

    private fun consume(character: Char): Boolean = if (source.getOrNull(index) == character) {
        index++; true
    } else false

    private fun skipWhitespace() {
        while (source.getOrNull(index)?.isWhitespace() == true) index++
    }
}
