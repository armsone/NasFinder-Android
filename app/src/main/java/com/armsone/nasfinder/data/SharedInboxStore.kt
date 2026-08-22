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
    val rootDirectory: File = phoneHardRoot(filesDirectory)
    private val manifestFile: File
    @Volatile private var snapshot: List<SharedInboxRecord> = emptyList()

    constructor(context: Context) : this(context.filesDir, Hooks.DEFAULT)
    constructor(filesDirectory: File) : this(filesDirectory, Hooks.DEFAULT)
    internal constructor(filesDirectory: File, hooks: Hooks, @Suppress("UNUSED_PARAMETER") marker: Unit) :
        this(filesDirectory, hooks)

    init {
        if (!rootDirectory.exists() && !rootDirectory.mkdirs()) {
            throw IllegalStateException("폰하드 저장소를 만들 수 없습니다.")
        }
        require(rootDirectory.isDirectory && !Files.isSymbolicLink(rootDirectory.toPath())) {
            "폰하드 저장소가 안전한 폴더가 아닙니다."
        }
        manifestFile = File(rootDirectory, MANIFEST_NAME)
        if (!manifestFile.exists()) atomicWrite(manifestFile, encodeManifest(emptyList()))
        synchronized(PROCESS_LOCK) { snapshot = readManifest() }
    }

    fun records(): List<SharedInboxRecord> = synchronized(PROCESS_LOCK) {
        readManifest().also { snapshot = it }
    }

    fun file(record: SharedInboxRecord): File = synchronized(PROCESS_LOCK) {
        validateRecord(record)
        val current = readManifest()
        require(current.any { it.id == record.id && it.storedFilename == record.storedFilename }) {
            "폰하드 파일 기록이 현재 manifest에 없습니다."
        }
        payloadFile(record.storedFilename).also {
            if (!it.isFile) throw IllegalStateException("폰하드 파일 payload가 없습니다.")
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
        val stored = uniqueStoredFilename(original)
        require(current.none { it.id == id }) { "중복된 폰하드 파일 UUID입니다." }
        require(current.none { it.storedFilename == stored }) { "중복된 폰하드 파일 저장 이름입니다." }
        val safeMime = validateMimeType(mimeType)
        val destination = payloadFile(stored)
        require(!destination.exists()) { "폰하드 파일 payload가 이미 있습니다." }
        val temporary = File(rootDirectory, ".import-$id.tmp")
        var payloadCommitted = false
        try {
            val bytes = FileOutputStream(temporary).use { output ->
                val copied = input.copyTo(output)
                output.fd.sync()
                copied
            }
            moveWithoutReplacing(temporary, destination)
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
            throw IllegalStateException("폰하드 파일 payload를 삭제하지 못했습니다.")
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
        if (!manifestFile.exists()) return reconcileManifest(emptyList())
        if (!manifestFile.isFile) throw IllegalStateException("폰하드 manifest가 파일이 아닙니다.")
        val root = JsonReader(manifestFile.readText(StandardCharsets.UTF_8)).readValue() as? Map<*, *>
            ?: throw IllegalStateException("폰하드 manifest 형식이 올바르지 않습니다.")
        require(root["version"].asLong() == MANIFEST_VERSION.toLong()) { "지원하지 않는 manifest 버전입니다." }
        val values = root["records"] as? List<*>
            ?: throw IllegalStateException("폰하드 manifest records가 없습니다.")
        val records = values.map { value ->
            val item = value as? Map<*, *> ?: throw IllegalStateException("폰하드 record가 올바르지 않습니다.")
            val id = item["id"] as? String ?: throw IllegalStateException("폰하드 파일 UUID가 없습니다.")
            val importedAt = item["importedAt"] as? String
                ?: throw IllegalStateException("폰하드 파일 시각이 없습니다.")
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
        require(records.map { it.id }.distinct().size == records.size) { "중복된 폰하드 파일 UUID입니다." }
        require(records.map { it.storedFilename }.distinct().size == records.size) {
            "중복된 폰하드 파일 저장 이름입니다."
        }
        return reconcileManifest(records)
    }

    private fun reconcileManifest(records: List<SharedInboxRecord>): List<SharedInboxRecord> {
        val current = records.filter { record ->
            record.storedFilename != LEGACY_MANIFEST_NAME &&
                runCatching { payloadFile(record.storedFilename).exists() }.getOrDefault(false)
        }.toMutableList()
        val represented = current.mapTo(hashSetOf()) { it.storedFilename }

        rootDirectory.walkTopDown()
            .onEnter { directory ->
                directory == rootDirectory ||
                    (!directory.name.startsWith('.') && !Files.isSymbolicLink(directory.toPath()))
            }
            .filter { file ->
                file.isFile && !file.name.startsWith('.') &&
                    file.canonicalFile.relativeTo(rootDirectory.canonicalFile).invariantSeparatorsPath != LEGACY_MANIFEST_NAME &&
                    !Files.isSymbolicLink(file.toPath())
            }
            .forEach { file ->
                val relative = file.canonicalFile.relativeTo(rootDirectory.canonicalFile)
                    .invariantSeparatorsPath
                if (relative !in represented) {
                    current += SharedInboxRecord(
                        id = UUID.randomUUID(),
                        originalFilename = file.name,
                        storedFilename = relative,
                        mimeType = null,
                        byteCount = file.length(),
                        importedAt = Instant.ofEpochMilli(file.lastModified()),
                    )
                    represented += relative
                }
            }

        if (current != records) hooks.commitManifest(manifestFile, encodeManifest(current))
        return current
    }

    private fun validateRecord(record: SharedInboxRecord) {
        require(safeBasename(record.originalFilename) == record.originalFilename) { "원본 파일명이 안전하지 않습니다." }
        payloadFile(record.storedFilename)
        validateMimeType(record.mimeType)
        require(record.byteCount >= 0) { "파일 크기가 올바르지 않습니다." }
    }

    private fun payloadFile(storedFilename: String): File {
        val components = storedFilename.split('/')
        require(storedFilename.isNotBlank() && '\\' !in storedFilename &&
            components.all { it.isNotBlank() && it != "." && it != ".." }) {
            "저장 경로가 안전하지 않습니다."
        }
        return File(rootDirectory, storedFilename).canonicalFile.also {
            val rootPath = rootDirectory.canonicalPath.trimEnd(File.separatorChar)
            require(it.path.startsWith("$rootPath${File.separator}")) {
                "저장 경로가 폰하드 폴더 밖입니다."
            }
        }
    }

    private fun uniqueStoredFilename(original: String): String {
        val requested = File(rootDirectory, original)
        if (!requested.exists()) return original
        val extension = requested.extension.takeIf(String::isNotEmpty)
        val stem = if (extension == null) requested.name else requested.name.removeSuffix(".$extension")
        for (index in 1..9_999) {
            val candidate = if (extension == null) "$stem ($index)" else "$stem ($index).$extension"
            if (!File(rootDirectory, candidate).exists()) return candidate
        }
        return "${UUID.randomUUID()}-$original"
    }

    internal data class Hooks(
        val commitManifest: (File, ByteArray) -> Unit,
        val deletePayload: (File) -> Boolean,
    ) {
        companion object {
            val DEFAULT = Hooks(::atomicWrite, File::delete)
        }
    }

    companion object {
        const val DIRECTORY_NAME = "SharedInbox"
        private const val MANIFEST_NAME = ".nasfinder-manifest.json"
        internal const val LEGACY_MANIFEST_NAME = "manifest.json"
        private const val MANIFEST_VERSION = 1
        private val PROCESS_LOCK = Any()

        internal fun phoneHardRoot(filesDirectory: File): File = File(filesDirectory, DIRECTORY_NAME)
    }
}

private fun safeBasename(value: String): String {
    val candidate = value.substringAfterLast('/').substringAfterLast('\\').trim()
    return candidate.takeUnless { it.isBlank() || it == "." || it == ".." || it.any(Char::isISOControl) }
        ?: "폰하드 파일"
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

private fun moveWithoutReplacing(source: File, destination: File) {
    Files.move(source.toPath(), destination.toPath())
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
