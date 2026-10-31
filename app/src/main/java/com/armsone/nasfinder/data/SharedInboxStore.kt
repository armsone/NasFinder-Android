package com.armsone.nasfinder.data

import android.content.Context
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.UUID

data class SharedInboxRecord(
    val id: UUID,
    val originalFilename: String,
    val storedFilename: String,
    val mimeType: String?,
    val byteCount: Long,
    val importedAt: Instant,
    // 비공개 파일 레코드(기본 false). 매니페스트에는 true일 때만 기록해 예전 레코드를 새로 쓰지 않는다.
    val isPrivate: Boolean = false,
)

data class PhoneHardImportResult(
    val targetFile: File,
    val relativePath: String,
    val records: List<SharedInboxRecord>,
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
    private val privateFoldersFile: File
    @Volatile private var snapshot: List<SharedInboxRecord> = emptyList()
    // 비공개 폴더의 루트 기준 상대 경로 집합(폴더는 매니페스트에 없으므로 별도의 작은 내부 파일에 둔다). 하위 전체가 숨는다.
    @Volatile private var privateFolders: Set<String> = emptySet()
    @Volatile private var privateFoldersUnreadable = false

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
        privateFoldersFile = File(rootDirectory, PRIVATE_FOLDERS_NAME)
        PrivacyMode.setPhoneHardRootPath(rootDirectory.canonicalPath)
        privateFolders = readPrivateFolders()
        val musicDir = File(rootDirectory, MUSIC_DIRECTORY_NAME)
        if (!musicDir.exists()) musicDir.mkdirs()
        if (!manifestFile.exists()) atomicWrite(manifestFile, encodeManifest(emptyList()))
        synchronized(PROCESS_LOCK) { snapshot = readManifest() }
        publishPrivacyScope(snapshot)
    }

    val musicDirectory: File
        get() = File(rootDirectory, MUSIC_DIRECTORY_NAME).also {
            if (!it.exists()) it.mkdirs()
        }

    /** 비공개를 포함한 전체 레코드(삭제·이동·정리용). 화면 목록에는 [records]를 쓴다. */
    fun allRecords(): List<SharedInboxRecord> = synchronized(PROCESS_LOCK) {
        readManifest().also { snapshot = it; publishPrivacyScope(it) }
    }

    /** 지금 모드에서 보이는 레코드: 일반 모드는 공개만(자기 플래그·상위 폴더 모두 공개), 프라이버시 모드는 전체. */
    fun records(): List<SharedInboxRecord> = synchronized(PROCESS_LOCK) {
        val all = readManifest().also { snapshot = it; publishPrivacyScope(it) }
        // 비공개 폴더 표시를 읽을 수 없으면 무엇이 비공개인지 알 수 없으므로 일반 모드에서는 아무것도 보여 주지 않는다(전체 기록은 allRecords).
        if (PrivacyMode.isActive) all else if (privateFoldersUnreadable) emptyList() else {
            val index = privateIndex(all)
            all.filterNot { index.isPrivate(it.storedFilename) }
        }
    }

    /** 비공개 폴더 표시 파일을 읽을 수 없는 상태인지(일반 모드 목록은 이 동안 비워지고, 손상 파일과 백업은 그대로 보존된다). */
    val privateMetadataUnreadable: Boolean get() = privateFoldersUnreadable

    /** 한 번 만들어 두고 O(1)로 조회하는 비공개 판정 도구(레코드 수에 대해 제곱으로 느려지지 않게 한다). */
    private class PrivateIndex(private val files: Set<String>, private val folders: Set<String>) {
        /** 레코드 자기 플래그, 자신이 비공개 폴더, 또는 상위 폴더가 비공개면 true. */
        fun isPrivate(storedFilename: String): Boolean {
            val path = storedFilename.trim('/')
            if (storedFilename in files || path in files) return true
            if (folders.isEmpty()) return false
            var end = path.length
            while (true) {
                if (path.substring(0, end) in folders) return true
                end = path.lastIndexOf('/', end - 1)
                if (end <= 0) return false
            }
        }
    }

    private fun privateIndex(records: List<SharedInboxRecord>): PrivateIndex =
        PrivateIndex(records.filter { it.isPrivate }.mapTo(hashSetOf()) { it.storedFilename }, privateFolders)

    private fun publishPrivacyScope(records: List<SharedInboxRecord>) {
        val files = records.filter { it.isPrivate }.mapTo(hashSetOf()) { it.storedFilename }
        val index = PrivateIndex(files, privateFolders)
        val ids = records.filter { index.isPrivate(it.storedFilename) }.mapTo(hashSetOf()) { it.id.toString() }
        PrivacyMode.setPhoneHardScope(files, privateFolders, ids)
    }

    /**
     * 비공개 폴더 표시 파일을 읽는다. 파일이 없으면 빈 집합이지만, 있는데 읽거나 해석하지 못하면 모든 폰하드를 비공개로
     * 취급(fail-closed)하고 손상된 파일은 덮어쓰지 않도록 백업만 남긴다.
     */
    private fun readPrivateFolders(): Set<String> {
        // 없는 경우만 "비공개 폴더 없음"이다. 폴더 등 파일이 아닌 항목이 있거나 읽지 못하면 읽을 수 없음으로 본다.
        if (!privateFoldersFile.exists() && !Files.isSymbolicLink(privateFoldersFile.toPath())) {
            privateFoldersUnreadable = false
            PrivacyMode.setPhoneHardScopeUnreadable(false)
            return emptySet()
        }
        val parsed = runCatching {
            require(privateFoldersFile.isFile) { "비공개 폴더 표시가 파일이 아닙니다." }
            val list = JsonReader(privateFoldersFile.readText(StandardCharsets.UTF_8)).readValue() as? List<*>
                ?: throw IllegalStateException("비공개 폴더 표시 형식이 올바르지 않습니다.")
            // 모든 원소가 비어 있지 않은 문자열이어야 한다. 다른 형식이 섞인 목록을 조용히 비어 있는(공개) 것으로 해석하지 않는다.
            list.map { element ->
                (element as? String)?.trim('/')?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalStateException("비공개 폴더 표시 항목이 올바르지 않습니다.")
            }.toSet()
        }.getOrNull()
        if (parsed == null) {
            privateFoldersUnreadable = true
            PrivacyMode.setPhoneHardScopeUnreadable(true)
            runCatching {
                val backup = File(rootDirectory, "$PRIVATE_FOLDERS_NAME.unreadable")
                if (!backup.exists() && privateFoldersFile.isFile) privateFoldersFile.copyTo(backup, overwrite = false)
            }
            return privateFolders
        }
        privateFoldersUnreadable = false
        PrivacyMode.setPhoneHardScopeUnreadable(false)
        return parsed
    }

    private fun writePrivateFolders(folders: Set<String>) {
        check(!privateFoldersUnreadable) { "비공개 폴더 표시 파일을 읽을 수 없어 변경하지 않았습니다." }
        if (folders == privateFolders && privateFoldersFile.isFile == folders.isNotEmpty()) return
        val bytes = buildString {
            append('[')
            folders.sorted().forEachIndexed { index, folder ->
                if (index > 0) append(',')
                appendJson(folder)
            }
            append(']')
        }.toByteArray(StandardCharsets.UTF_8)
        atomicWrite(privateFoldersFile, bytes)
        privateFolders = folders
    }

    /** 명시적인 공개/비공개 전환(파일 레코드 또는 폴더). id·위치·순서는 그대로 두고 플래그만 바꾼다. */
    fun setPrivate(relativePath: String, isDirectory: Boolean, isPrivate: Boolean): Boolean = synchronized(PROCESS_LOCK) {
        val path = relativePath.trim('/')
        if (path.isEmpty()) return@synchronized false
        runCatching {
            if (isDirectory) {
                writePrivateFolders(if (isPrivate) privateFolders + path else privateFolders - path)
            } else {
                val current = readManifest()
                val updated = current.map { if (it.storedFilename == path) it.copy(isPrivate = isPrivate) else it }
                if (updated != current) hooks.commitManifest(manifestFile, encodeManifest(updated))
                snapshot = updated
                // 실패한 마무리가 남긴 이 파일 자신의 임시 경로 표시만 걷는다(상위·형제·하위 표시는 그대로).
                if (!isPrivate && path in privateFolders) writePrivateFolders(privateFolders - path)
            }
            publishPrivacyScope(readManifest())
            true
        }.getOrDefault(false)
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
        destinationSubdirectory: String? = null,
        // null = 지금 모드. 오래 걸리는 작업은 시작할 때 잡아 둔 출처를 명시해 넘긴다(모드가 바뀌어도 바뀌지 않는다).
        isPrivate: Boolean? = null,
    ): SharedInboxRecord = synchronized(PROCESS_LOCK) {
        val current = readManifest()
        val original = safeBasename(originalFilename)
        val subDir = destinationSubdirectory?.trim()?.removePrefix("/")?.removeSuffix("/")?.takeIf { it.isNotEmpty() }
        val relativeOriginal = if (subDir != null) "$subDir/$original" else original
        val stored = uniqueStoredFilename(relativeOriginal, current)
        require(current.none { it.id == id }) { "중복된 폰하드 파일 UUID입니다." }
        require(current.none { it.storedFilename == stored }) { "중복된 폰하드 파일 저장 이름입니다." }
        val safeMime = validateMimeType(mimeType)
        val destination = payloadFile(stored)
        destination.parentFile?.let { if (!it.exists()) it.mkdirs() }
        require(!destination.exists()) { "폰하드 파일 payload가 이미 있습니다." }
        val temporary = File(rootDirectory, ".import-$id.tmp")
        var payloadCommitted = false
        val recordPrivate = isPrivate ?: PrivacyMode.isActive
        // 비공개 파일은 최종 이름이 생기기 전에 비공개로 등록해, 매니페스트 분류가 끝나기 전에 웹하드 등에 보이지 않게 한다.
        val pending = if (recordPrivate) listOf(stored) else emptyList()
        try {
            PrivacyMode.withPendingPrivate(pending) {
            val bytes = FileOutputStream(temporary).use { output ->
                val copied = input.copyTo(output)
                output.fd.sync()
                copied
            }
            moveWithoutReplacing(temporary, destination)
            payloadCommitted = true
            val record = SharedInboxRecord(id, original, stored, safeMime, bytes, importedAt, recordPrivate)
            val updated = current + record
            hooks.commitManifest(manifestFile, encodeManifest(updated))
            snapshot = updated
            publishPrivacyScope(updated)
            record
            }
        } catch (error: Throwable) {
            temporary.delete()
            if (payloadCommitted) destination.delete()
            snapshot = runCatching { readManifest() }.getOrElse { current }
            throw error
        }
    }

    /**
     * Imports a staged file or directory tree into [destinationSubdirectory] without overwriting existing entries.
     * Preserves top-level names, subdirectories, hidden files, and empty directories.
     * Atomically registers all imported files into the manifest.
     * Preserves the staged entry on disk.
     */
    fun importFromStaging(
        stagedEntry: File,
        desiredName: String,
        destinationSubdirectory: String? = null,
        mimeType: String? = null,
        isPrivate: Boolean? = null,
    ): PhoneHardImportResult = synchronized(PROCESS_LOCK) {
        val importPrivate = isPrivate ?: PrivacyMode.isActive
        require(stagedEntry.exists()) { "스테이징 소스가 존재하지 않습니다: ${stagedEntry.path}" }
        val destDir = resolveDirectory(destinationSubdirectory)
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw IllegalStateException("대상 저장 폴더를 생성할 수 없습니다: ${destDir.path}")
        }
        val safeName = safeBasename(desiredName)
        require(safeName != MANIFEST_NAME && safeName != LEGACY_MANIFEST_NAME) {
            "예약된 시스템 이름으로는 저장할 수 없습니다."
        }

        val currentManifest = readManifest()
        val uniqueName = findUniqueNameInDirectory(destDir, safeName, currentManifest)
        val targetEntry = File(destDir, uniqueName).canonicalFile
        validateContainment(targetEntry)
        require(!targetEntry.exists()) { "대상 항목이 이미 존재합니다: ${targetEntry.path}" }

        val rootCanonical = rootDirectory.canonicalFile
        val createdFiles = mutableListOf<File>()
        val newRecords = mutableListOf<SharedInboxRecord>()
        val previousPrivateFolders = privateFolders
        val targetRelEarly = targetEntry.relativeTo(rootCanonical).invariantSeparatorsPath

        try {
          // 비공개 가져오기는 첫 파일이 최종 위치에 생기기 전에 그 경로(폴더면 하위 전체)를 비공개로 등록한다.
          PrivacyMode.withPendingPrivate(if (importPrivate) listOf(targetRelEarly) else emptyList()) {
            if (stagedEntry.isDirectory) {
                if (!targetEntry.mkdirs()) throw IllegalStateException("대상 폴더를 생성하지 못했습니다: ${targetEntry.path}")
                createdFiles.add(targetEntry)

                stagedEntry.walkTopDown().forEach { sourceChild ->
                    if (sourceChild == stagedEntry) return@forEach
                    if (Files.isSymbolicLink(sourceChild.toPath())) return@forEach
                    val relSubPath = sourceChild.relativeTo(stagedEntry).invariantSeparatorsPath
                    val destChild = File(targetEntry, relSubPath).canonicalFile
                    validateContainment(destChild)

                    if (sourceChild.isDirectory) {
                        if (!destChild.exists() && !destChild.mkdirs()) {
                            throw IllegalStateException("하위 폴더를 생성하지 못했습니다: ${destChild.path}")
                        }
                        createdFiles.add(destChild)
                    } else if (sourceChild.isFile) {
                        destChild.parentFile?.let { if (!it.exists()) it.mkdirs() }
                        sourceChild.copyTo(destChild, overwrite = false)
                        createdFiles.add(destChild)

                        val childRel = destChild.relativeTo(rootCanonical).invariantSeparatorsPath
                        // [mimeType] describes the top-level entry only; children are detected per file.
                        val childSafeMime = validateMimeType(getMimeTypeFromExtension(sourceChild.extension))
                        val safeOriginal = safeBasename(sourceChild.name)
                        val record = SharedInboxRecord(
                            id = UUID.randomUUID(),
                            originalFilename = safeOriginal,
                            storedFilename = childRel,
                            mimeType = childSafeMime,
                            byteCount = destChild.length(),
                            importedAt = Instant.now(),
                            isPrivate = importPrivate,
                        )
                        validateRecord(record)
                        newRecords.add(record)
                    }
                }
            } else {
                targetEntry.parentFile?.let { if (!it.exists()) it.mkdirs() }
                stagedEntry.copyTo(targetEntry, overwrite = false)
                createdFiles.add(targetEntry)

                val storedRel = targetEntry.relativeTo(rootCanonical).invariantSeparatorsPath
                val safeMime = validateMimeType(
                    mimeType ?: getMimeTypeFromExtension(stagedEntry.extension)
                )
                val record = SharedInboxRecord(
                    id = UUID.randomUUID(),
                    originalFilename = safeName,
                    storedFilename = storedRel,
                    mimeType = safeMime,
                    byteCount = targetEntry.length(),
                    importedAt = Instant.now(),
                    isPrivate = importPrivate,
                )
                validateRecord(record)
                newRecords.add(record)
            }

            // Ensure atomic and guaranteed unique IDs and stored filenames across all records
            require(newRecords.map { it.id }.distinct().size == newRecords.size) { "신규 임포트 기록 간 UUID가 중복되었습니다." }
            require(newRecords.map { it.storedFilename }.distinct().size == newRecords.size) { "신규 임포트 기록 간 저장 경로가 중복되었습니다." }
            val existingIds = currentManifest.mapTo(hashSetOf()) { it.id }
            val existingStored = currentManifest.mapTo(hashSetOf()) { it.storedFilename }
            require(newRecords.none { it.id in existingIds }) { "매니페스트에 이미 존재하는 UUID입니다." }
            require(newRecords.none { it.storedFilename in existingStored }) { "매니페스트에 이미 존재하는 저장 경로입니다." }

            val updatedManifest = currentManifest + newRecords
            val targetRel = targetRelEarly
            // 폴더를 통째로 가져오면 하위가 비공개로 시작하도록 폴더 자체를 비공개 폴더로 기록한다(빈 하위 폴더 포함).
            // 비공개 표시를 먼저 확정한 뒤 매니페스트를 기록한다(표시 쓰기가 실패하면 아무것도 확정되지 않는다).
            if (importPrivate && stagedEntry.isDirectory) writePrivateFolders(privateFolders + targetRel)
            hooks.commitManifest(manifestFile, encodeManifest(updatedManifest))
            snapshot = updatedManifest
            publishPrivacyScope(updatedManifest)
            PhoneHardImportResult(targetEntry, targetRel, newRecords)
          }
        } catch (error: Throwable) {
            for (created in createdFiles.reversed()) {
                runCatching {
                    if (created.isDirectory) created.deleteRecursively() else created.delete()
                }
            }
            // 확정하지 못한 비공개 폴더 표시는 되돌린다(표시 쓰기 자체가 실패한 경우는 되돌릴 것이 없다).
            if (privateFolders != previousPrivateFolders) runCatching { writePrivateFolders(previousPrivateFolders) }
            snapshot = runCatching { readManifest() }.getOrElse { currentManifest }
            runCatching { publishPrivacyScope(snapshot) }
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

    /**
     * 이동(복사 후 원본 삭제)용: 전송을 시작할 때 본 파일과 지금 파일이 같을 때만 지운다.
     * 레코드 ID·저장 경로·실제 길이·수정 시각 중 하나라도 다르면 아무것도 지우지 않고 사유를 돌려준다.
     * 확인과 삭제는 같은 락 안에서 이뤄지므로 그 사이에 다른 작업이 끼어들지 않는다. 성공이면 null.
     */
    fun deleteFileIfUnchanged(
        id: UUID,
        storedFilename: String,
        expectedLength: Long,
        expectedLastModified: Long,
    ): String? = synchronized(PROCESS_LOCK) {
        val record = readManifest().firstOrNull { it.id == id }
            ?: return@synchronized "원본이 폰하드에서 이미 사라졌거나 이동되어 삭제하지 않았습니다."
        if (record.storedFilename != storedFilename) {
            return@synchronized "원본 위치가 바뀌어 삭제하지 않았습니다."
        }
        val file = runCatching { payloadFile(record.storedFilename) }.getOrNull()
            ?: return@synchronized "원본 경로를 확인할 수 없어 삭제하지 않았습니다."
        if (!file.isFile || Files.isSymbolicLink(file.toPath())) {
            return@synchronized "원본이 일반 파일이 아니어서 삭제하지 않았습니다."
        }
        if (file.length() != expectedLength || file.lastModified() != expectedLastModified) {
            return@synchronized "전송하는 동안 원본이 바뀌어 삭제하지 않았습니다."
        }
        val result = deleteTargets(listOf(PhoneHardTarget(id = id, relativePath = storedFilename, isDirectory = false)))
        result.failed.firstOrNull()?.second
    }

    /**
     * Lists immediate entries (files and directories) inside a given subfolder.
     * [relativeSubdirectory] null or empty means root directory.
     */
    fun listPhoneHardFolder(relativeSubdirectory: String? = null): List<PhoneHardItem> = synchronized(PROCESS_LOCK) {
        val targetDir = resolveDirectory(relativeSubdirectory)
        require(targetDir.exists() && targetDir.isDirectory) { "폴더가 존재하지 않습니다." }
        check(PrivacyMode.isActive || !privateFoldersUnreadable) { PRIVATE_METADATA_UNREADABLE_MESSAGE }

        val records = readManifest().also { snapshot = it; publishPrivacyScope(it) }
        val recordByStored = records.associateBy { it.storedFilename }

        val rootCanonical = rootDirectory.canonicalFile
        val items = mutableListOf<PhoneHardItem>()
        val index = privateIndex(records)

        val children = targetDir.listFiles() ?: emptyArray()
        for (child in children) {
            val name = child.name
            if (name.startsWith('.') || name == MANIFEST_NAME || name == LEGACY_MANIFEST_NAME) continue
            if (Files.isSymbolicLink(child.toPath())) continue

            val relPath = child.canonicalFile.relativeTo(rootCanonical).invariantSeparatorsPath
            val itemPrivate = index.isPrivate(relPath)
            // 일반 모드에서는 비공개 파일·폴더를 목록에서 뺀다(디스크의 파일은 그대로다).
            if (itemPrivate && !PrivacyMode.isActive) continue
            if (child.isDirectory) {
                items.add(
                    PhoneHardItem(
                        id = null,
                        name = name,
                        relativePath = relPath,
                        kind = PhoneHardEntryKind.DIRECTORY,
                        byteCount = 0L,
                        modifiedAt = Instant.ofEpochMilli(child.lastModified()),
                        mimeType = null,
                        file = child,
                        isPrivate = itemPrivate,
                    )
                )
            } else if (child.isFile) {
                val rec = recordByStored[relPath]
                items.add(
                    PhoneHardItem(
                        id = rec?.id,
                        name = rec?.originalFilename ?: name,
                        relativePath = relPath,
                        kind = PhoneHardEntryKind.FILE,
                        byteCount = rec?.byteCount ?: child.length(),
                        modifiedAt = rec?.importedAt ?: Instant.ofEpochMilli(child.lastModified()),
                        mimeType = rec?.mimeType,
                        file = child,
                        isPrivate = itemPrivate,
                    )
                )
            }
        }
        items.sortedWith(
            compareBy<PhoneHardItem> { if (it.kind == PhoneHardEntryKind.DIRECTORY) 0 else 1 }
                .thenBy { it.name }
        )
    }

    /**
     * Creates a new directory inside [parentSubdirectory].
     */
    fun createFolder(folderName: String, parentSubdirectory: String? = null, isPrivate: Boolean? = null): File = synchronized(PROCESS_LOCK) {
        val parent = resolveDirectory(parentSubdirectory)
        val safeName = safeBasename(folderName)
        require(safeName.isNotBlank() && safeName != "." && safeName != "..") { "올바르지 않은 폴더명입니다." }
        require(safeName != MANIFEST_NAME && safeName != LEGACY_MANIFEST_NAME && !safeName.startsWith('.')) {
            "예약된 시스템 이름으로는 폴더를 생성할 수 없습니다."
        }

        val newDir = File(parent, safeName).canonicalFile
        validateContainment(newDir)
        require(!newDir.exists()) { "이미 같은 이름의 파일 또는 폴더가 존재합니다." }
        val folderPrivate = isPrivate ?: PrivacyMode.isActive
        val rel = newDir.relativeTo(rootDirectory.canonicalFile).invariantSeparatorsPath
        PrivacyMode.withPendingPrivate(if (folderPrivate) listOf(rel) else emptyList()) {
            if (!newDir.mkdirs()) throw IllegalStateException("폴더를 생성하지 못했습니다.")
            if (folderPrivate) {
                try {
                    writePrivateFolders(privateFolders + rel)
                    publishPrivacyScope(readManifest())
                } catch (error: Throwable) {
                    // 비공개 표시를 저장하지 못했으면 공개 폴더로 남기지 않고 방금 만든 빈 폴더를 없앤 뒤 실패를 알린다.
                    runCatching { newDir.delete() }
                    throw error
                }
            }
        }
        newDir
    }

    /**
     * Batch move files and folders into [destinationSubdirectory].
     * Preserves IDs, updates descendant stored paths, rejects self/descendant nesting, serializes with PROCESS_LOCK.
     */
    fun moveTargets(
        targets: List<PhoneHardTarget>,
        destinationSubdirectory: String? = null,
    ): PhoneHardOperationResult = synchronized(PROCESS_LOCK) { moveTargetsImpl(targets, destinationSubdirectory) }

    private fun moveTargetsImpl(
        targets: List<PhoneHardTarget>,
        destinationSubdirectory: String? = null,
    ): PhoneHardOperationResult {
        val destDir = resolveDirectory(destinationSubdirectory)
        val rootCanonical = rootDirectory.canonicalFile
        val destRelative = if (destDir == rootCanonical) "" else destDir.relativeTo(rootCanonical).invariantSeparatorsPath

        val deduped = deduplicateTargets(targets)
        val succeeded = mutableListOf<PhoneHardTarget>()
        val failed = mutableListOf<Pair<PhoneHardTarget, String>>()
        val pathMappings = mutableListOf<PhoneHardPathMapping>()

        var currentManifest = readManifest()

        for (target in deduped) {
            var fileMoved = false
            var movedFile: File? = null
            var originalFile: File? = null
            var foldersBefore: Set<String>? = null
            try {
                val targetFile = resolveExistingEntry(target.relativePath)
                originalFile = targetFile
                val targetRel = targetFile.relativeTo(rootCanonical).invariantSeparatorsPath

                // Reject move into self or child
                if (destRelative == targetRel || destRelative.startsWith("$targetRel/")) {
                    failed.add(target to "자기 자신 또는 하위 폴더로는 이동할 수 없습니다.")
                    continue
                }

                // Check if already in the same parent folder
                val currentParentRel = targetFile.parentFile?.relativeTo(rootCanonical)?.invariantSeparatorsPath?.takeIf { it != "." } ?: ""
                if (currentParentRel == destRelative) {
                    succeeded.add(target)
                    continue
                }

                val targetName = targetFile.name
                val movingDirectory = targetFile.isDirectory
                val finalName = findUniqueNameInDirectory(destDir, targetName, currentManifest)
                val finalFile = File(destDir, finalName).canonicalFile
                validateContainment(finalFile)

                val newRel = finalFile.relativeTo(rootCanonical).invariantSeparatorsPath

                // 비공개 분류를 먼저 디스크에 확정한다. 확정하지 못하면 아무것도 옮기지 않고 이 항목만 실패 처리한다.
                val plan = planRelocation(currentManifest, targetRel, newRel, movingDirectory, copy = false)
                val previousFolders = privateFolders
                try {
                    commitInterimFolders(plan, currentManifest)
                } catch (e: Exception) {
                    failed.add(target to "비공개 표시를 먼저 저장하지 못해 이동하지 않았습니다: ${e.message}")
                    continue
                }
                foldersBefore = previousFolders

                // Perform filesystem move
                moveWithoutReplacing(targetFile, finalFile)
                fileMoved = true
                movedFile = finalFile

                // Build candidate manifest without mutating currentManifest yet
                val candidateManifest = (if (movingDirectory) {
                    currentManifest.map { record ->
                        if (record.storedFilename == targetRel) {
                            record.copy(storedFilename = newRel)
                        } else if (record.storedFilename.startsWith("$targetRel/")) {
                            val subPath = record.storedFilename.removePrefix("$targetRel/")
                            record.copy(storedFilename = "$newRel/$subPath")
                        } else {
                            record
                        }
                    }
                } else {
                    currentManifest.map { record ->
                        if (record.storedFilename == targetRel) {
                            record.copy(storedFilename = newRel, originalFilename = target.relativePath.substringAfterLast('/'))
                        } else {
                            record
                        }
                    }
                }).withDestinationMarked(plan, newRel)

                hooks.commitManifest(manifestFile, encodeManifest(candidateManifest))
                currentManifest = candidateManifest
                snapshot = currentManifest
                finalizeFolders(plan)
                succeeded.add(target)
                pathMappings.add(PhoneHardPathMapping(targetRel, newRel, movingDirectory))
            } catch (e: Exception) {
                if (fileMoved && movedFile != null && originalFile != null) {
                    val rollbackResult = runCatching {
                        moveWithoutReplacing(movedFile, originalFile)
                    }
                    if (rollbackResult.isFailure) {
                        // 항목이 도착 위치에 남아 있으므로 먼저 확정한 비공개 표시도 그대로 둔다(공개로 남지 않는다).
                        failed.add(target to "이동 후 매니페스트 저장에 실패했으며 원래 위치 복구도 실패했습니다: ${e.message}")
                    } else {
                        foldersBefore?.let(::restoreFolders)
                        failed.add(target to "이동 후 매니페스트 저장에 실패하여 원래 위치로 복구했습니다: ${e.message}")
                    }
                } else {
                    foldersBefore?.let(::restoreFolders)
                    failed.add(target to (e.message ?: "이동에 실패했습니다."))
                }
            }
        }
        return PhoneHardOperationResult(succeeded, failed, pathMappings = pathMappings)
    }

    /**
     * Renames one file or folder in place.
     * Never overwrites, preserves IDs, mimeType and importedAt of the entry and every descendant record,
     * restores the original name when the manifest cannot be committed, serializes with PROCESS_LOCK.
     */
    fun renamePhoneHardTarget(
        relativePath: String,
        newName: String,
    ): PhoneHardOperationResult = renamePhoneHardTargetImpl(relativePath, newName)

    private fun renamePhoneHardTargetImpl(
        relativePath: String,
        newName: String,
    ): PhoneHardOperationResult = synchronized(PROCESS_LOCK) {
        var target = PhoneHardTarget(relativePath = relativePath, isDirectory = false)
        try {
            val rootCanonical = rootDirectory.canonicalFile
            val currentManifest = readManifest()
            val source = resolveExistingEntry(relativePath)
            val sourceRel = source.relativeTo(rootCanonical).invariantSeparatorsPath
            val renamingDirectory = source.isDirectory
            target = PhoneHardTarget(
                id = currentManifest.firstOrNull { it.storedFilename == sourceRel }?.id,
                relativePath = relativePath,
                isDirectory = renamingDirectory,
            )
            require(sourceRel != LEGACY_MANIFEST_NAME && sourceRel.split('/').none { it.startsWith('.') }) {
                "시스템 항목의 이름은 변경할 수 없습니다."
            }

            val name = newName.trim()
            require(
                name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name &&
                    name.none(Char::isISOControl)
            ) { "올바르지 않은 이름입니다." }
            require(name != MANIFEST_NAME && name != LEGACY_MANIFEST_NAME && !name.startsWith('.')) {
                "예약된 시스템 이름으로는 변경할 수 없습니다."
            }
            if (name == source.name) {
                return@synchronized PhoneHardOperationResult(listOf(target), emptyList())
            }

            val parent = source.parentFile ?: rootCanonical
            val requested = File(parent, name)
            require(!Files.exists(requested.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "이미 같은 이름의 파일 또는 폴더가 존재합니다."
            }
            val destination = requested.canonicalFile
            validateContainment(destination)
            require(destination.parentFile == parent) { "안전하지 않은 대상 경로입니다." }
            val destRel = destination.relativeTo(rootCanonical).invariantSeparatorsPath
            require(currentManifest.none { it.storedFilename == destRel || it.storedFilename.startsWith("$destRel/") }) {
                "이미 같은 이름의 파일 또는 폴더가 존재합니다."
            }

            val plan = planRelocation(currentManifest, sourceRel, destRel, renamingDirectory, copy = false)
            val candidateManifest = currentManifest.map { record ->
                if (record.storedFilename == sourceRel) {
                    record.copy(storedFilename = destRel, originalFilename = name)
                } else if (record.storedFilename.startsWith("$sourceRel/")) {
                    val subPath = record.storedFilename.removePrefix("$sourceRel/")
                    record.copy(storedFilename = "$destRel/$subPath")
                } else {
                    record
                }
            }.withDestinationMarked(plan, destRel)
            candidateManifest.forEach(::validateRecord)

            // 비공개 분류를 먼저 디스크에 확정한다. 확정하지 못하면 이름을 바꾸지 않는다.
            val foldersBefore = privateFolders
            try {
                commitInterimFolders(plan, currentManifest)
            } catch (e: Exception) {
                return@synchronized PhoneHardOperationResult(
                    emptyList(),
                    listOf(target to "비공개 표시를 먼저 저장하지 못해 이름을 바꾸지 않았습니다: ${e.message}"),
                )
            }

            try {
                moveWithoutReplacing(source, destination)
            } catch (e: Exception) {
                restoreFolders(foldersBefore)
                throw e
            }
            try {
                hooks.commitManifest(manifestFile, encodeManifest(candidateManifest))
            } catch (e: Exception) {
                if (runCatching { moveWithoutReplacing(destination, source) }.isSuccess) {
                    restoreFolders(foldersBefore)
                    return@synchronized PhoneHardOperationResult(
                        emptyList(),
                        listOf(target to "이름 변경 후 매니페스트 저장에 실패하여 원래 이름으로 복구했습니다: ${e.message}"),
                    )
                }
                // The entry stays renamed on disk; retry once so reconciliation does not mint new IDs.
                if (runCatching { hooks.commitManifest(manifestFile, encodeManifest(candidateManifest)) }.isFailure) {
                    return@synchronized PhoneHardOperationResult(
                        emptyList(),
                        listOf(target to "이름 변경 후 매니페스트 저장에 실패했으며 원래 이름 복구도 실패했습니다: ${e.message}"),
                    )
                }
            }
            snapshot = candidateManifest
            finalizeFolders(plan)
            PhoneHardOperationResult(
                listOf(target),
                emptyList(),
                pathMappings = listOf(PhoneHardPathMapping(sourceRel, destRel, renamingDirectory)),
            )
        } catch (e: Exception) {
            PhoneHardOperationResult(emptyList(), listOf(target to (e.message ?: "이름 변경에 실패했습니다.")))
        }
    }

    /**
     * Batch copy files and folders into [destinationSubdirectory].
     * Creates new IDs, deep-copies directory contents, rejects self/descendant nesting, serializes with PROCESS_LOCK.
     */
    fun copyTargets(
        targets: List<PhoneHardTarget>,
        destinationSubdirectory: String? = null,
    ): PhoneHardOperationResult = synchronized(PROCESS_LOCK) { copyTargetsImpl(targets, destinationSubdirectory) }

    private fun copyTargetsImpl(
        targets: List<PhoneHardTarget>,
        destinationSubdirectory: String? = null,
    ): PhoneHardOperationResult {
        val destDir = resolveDirectory(destinationSubdirectory)
        val rootCanonical = rootDirectory.canonicalFile
        val destRelative = if (destDir == rootCanonical) "" else destDir.relativeTo(rootCanonical).invariantSeparatorsPath

        val deduped = deduplicateTargets(targets)
        val succeeded = mutableListOf<PhoneHardTarget>()
        val failed = mutableListOf<Pair<PhoneHardTarget, String>>()
        val copied = mutableListOf<PhoneHardPathMapping>()

        var currentManifest = readManifest()

        for (target in deduped) {
            val owned = mutableListOf<Pair<File, Any?>>()
            var foldersBefore: Set<String>? = null
            try {
                val targetFile = resolveExistingEntry(target.relativePath)
                val targetRel = targetFile.relativeTo(rootCanonical).invariantSeparatorsPath

                // Reject copy into self or child
                if (destRelative == targetRel || destRelative.startsWith("$targetRel/")) {
                    failed.add(target to "자기 자신 또는 하위 폴더로는 복사할 수 없습니다.")
                    continue
                }

                val targetName = targetFile.name
                val finalName = findUniqueNameInDirectory(destDir, targetName, currentManifest)
                val finalFile = File(destDir, finalName).canonicalFile
                validateContainment(finalFile)

                val newRecordsToAdd = mutableListOf<SharedInboxRecord>()
                val plan = planRelocation(
                    currentManifest, targetRel, finalFile.relativeTo(rootCanonical).invariantSeparatorsPath,
                    target.isDirectory, copy = true,
                )
                // 복사본의 비공개 분류를 첫 파일이 생기기 전에 디스크에 확정한다. 확정하지 못하면 아무것도 복사하지 않는다.
                val previousFolders = privateFolders
                try {
                    commitInterimFolders(plan, currentManifest)
                } catch (e: Exception) {
                    failed.add(target to "비공개 표시를 먼저 저장하지 못해 복사하지 않았습니다: ${e.message}")
                    continue
                }
                foldersBefore = previousFolders
                // 도착 경로는 원자적으로 새로 만든 것만 이 작업의 것으로 기록한다(이미 있던 경로는 합치거나 지우지 않는다).
                createMissingParents(destDir, owned)

                if (target.isDirectory) {
                    val finalRel = finalFile.relativeTo(rootCanonical).invariantSeparatorsPath
                    createOwnedDirectory(finalFile, owned)

                    // Deep copy all descendants
                    targetFile.walkTopDown().forEach { sourceChild ->
                        if (sourceChild == targetFile) return@forEach
                        if (sourceChild.name.startsWith('.') || Files.isSymbolicLink(sourceChild.toPath())) return@forEach
                        val subPath = sourceChild.relativeTo(targetFile).invariantSeparatorsPath
                        val destChild = File(finalFile, subPath).canonicalFile
                        validateContainment(destChild)
                        if (sourceChild.isDirectory) {
                            destChild.parentFile?.let { createMissingParents(it, owned) }
                            createOwnedDirectory(destChild, owned)
                        } else if (sourceChild.isFile) {
                            destChild.parentFile?.let { createMissingParents(it, owned) }
                            copyFileExclusive(sourceChild, destChild, owned)

                            val destChildRel = destChild.relativeTo(rootCanonical).invariantSeparatorsPath
                            val oldSourceRel = sourceChild.relativeTo(rootCanonical).invariantSeparatorsPath
                            val existingRec = currentManifest.firstOrNull { it.storedFilename == oldSourceRel }

                            newRecordsToAdd.add(
                                SharedInboxRecord(
                                    id = UUID.randomUUID(),
                                    originalFilename = existingRec?.originalFilename ?: sourceChild.name,
                                    storedFilename = destChildRel,
                                    mimeType = existingRec?.mimeType,
                                    byteCount = destChild.length(),
                                    importedAt = Instant.now(),
                                    isPrivate = existingRec?.isPrivate == true,
                                )
                            )
                        }
                    }
                } else {
                    copyFileExclusive(targetFile, finalFile, owned)

                    val finalRel = finalFile.relativeTo(rootCanonical).invariantSeparatorsPath
                    val existingRec = currentManifest.firstOrNull { it.storedFilename == targetRel }

                    newRecordsToAdd.add(
                        SharedInboxRecord(
                            id = UUID.randomUUID(),
                            originalFilename = existingRec?.originalFilename ?: targetFile.name,
                            storedFilename = finalRel,
                            mimeType = existingRec?.mimeType,
                            byteCount = finalFile.length(),
                            importedAt = Instant.now(),
                            isPrivate = existingRec?.isPrivate == true,
                        )
                    )
                }

                val candidateManifest = (currentManifest + newRecordsToAdd)
                    .withDestinationMarked(plan, finalFile.relativeTo(rootCanonical).invariantSeparatorsPath)
                hooks.commitManifest(manifestFile, encodeManifest(candidateManifest))
                currentManifest = candidateManifest
                snapshot = currentManifest
                finalizeFolders(plan)
                succeeded.add(target)
                copied.add(PhoneHardPathMapping(targetRel, finalFile.relativeTo(rootCanonical).invariantSeparatorsPath, target.isDirectory))
            } catch (e: Exception) {
                // 이 작업이 실제로 만든 경로만 역순으로 하나씩 지우고(재귀 삭제 없음, 남이 넣은 항목은 남는다),
                // 모두 사라진 것이 확인될 때만 이전 비공개 표시로 되돌린다. 남으면 먼저 확정한 보호 표시를 유지한다(원본은 건드리지 않는다).
                val cleanedUp = removeOwnedPaths(owned)
                var reason = if (e is java.nio.file.FileAlreadyExistsException) {
                    "같은 이름의 항목이 이미 있어 복사하지 않았습니다."
                } else {
                    e.message ?: "복사에 실패했습니다."
                }
                if (cleanedUp) {
                    foldersBefore?.let(::restoreFolders)
                } else {
                    reason += " (복사 중이던 항목을 완전히 지우지 못해 비공개 표시를 유지했습니다.)"
                }
                failed.add(target to reason)
            }
        }
        return PhoneHardOperationResult(succeeded, failed, copiedPaths = copied)
    }

    /**
     * Batch delete files and folders.
     * Deduplicates ancestors, removes from manifest and filesystem.
     */
    fun deleteTargets(targets: List<PhoneHardTarget>): PhoneHardOperationResult = synchronized(PROCESS_LOCK) {
        val result = deleteTargetsImpl(targets)
        // 지운 폴더의 비공개 표시도 함께 정리한다(남기면 같은 이름의 새 폴더가 조용히 숨겨진다).
        val remaining = privateFolders.filterNot { folder ->
            result.removedPaths.any { removed -> folder == removed || folder.startsWith("$removed/") }
        }.toSet()
        runCatching { writePrivateFolders(remaining); publishPrivacyScope(readManifest()) }
        result
    }

    private fun deleteTargetsImpl(targets: List<PhoneHardTarget>): PhoneHardOperationResult {
        val deduped = deduplicateTargets(targets)
        val rootCanonical = rootDirectory.canonicalFile

        val succeeded = mutableListOf<PhoneHardTarget>()
        val failed = mutableListOf<Pair<PhoneHardTarget, String>>()
        val removedPaths = mutableListOf<String>()

        var currentManifest = readManifest()

        for (target in deduped) {
            try {
                val targetFile = resolveExistingEntry(target.relativePath)
                val targetRel = targetFile.relativeTo(rootCanonical).invariantSeparatorsPath

                if (!PrivacyMode.isActive && privateFoldersUnreadable) {
                    failed.add(target to PRIVATE_METADATA_UNREADABLE_MESSAGE)
                    continue
                }
                if (target.isDirectory) {
                    // 일반 모드에서 폴더를 지우면 보이지 않는 비공개 하위 항목까지 함께 지워진다 → 그런 폴더는 지우지 않는다.
                    if (!PrivacyMode.isActive) {
                        val hiddenIndex = privateIndex(currentManifest)
                        val hasHidden = currentManifest.any {
                            it.storedFilename.startsWith("$targetRel/") && hiddenIndex.isPrivate(it.storedFilename)
                        } || privateFolders.any { it.startsWith("$targetRel/") }
                        if (hasHidden) {
                            failed.add(target to "폴더 안에 이 모드에서 보이지 않는 항목이 있어 삭제하지 않았습니다.")
                            continue
                        }
                    }
                    val deleted = targetFile.deleteRecursively()
                    if (!deleted && targetFile.exists()) {
                        throw IllegalStateException("폴더를 완전히 삭제하지 못했습니다.")
                    }
                    val candidateManifest = currentManifest.filterNot {
                        it.storedFilename == targetRel || it.storedFilename.startsWith("$targetRel/")
                    }
                    hooks.commitManifest(manifestFile, encodeManifest(candidateManifest))
                    currentManifest = candidateManifest
                } else {
                    if (targetFile.exists() && !hooks.deletePayload(targetFile)) {
                        throw IllegalStateException("파일을 삭제하지 못했습니다.")
                    }
                    val candidateManifest = currentManifest.filterNot { it.storedFilename == targetRel }
                    hooks.commitManifest(manifestFile, encodeManifest(candidateManifest))
                    currentManifest = candidateManifest
                }

                snapshot = currentManifest
                succeeded.add(target)
                removedPaths.add(targetRel)
            } catch (e: Exception) {
                failed.add(target to (e.message ?: "삭제에 실패했습니다."))
            }
        }
        return PhoneHardOperationResult(succeeded, failed, removedPaths = removedPaths)
    }

    /**
     * 이동·이름 변경·복사 한 건의 비공개 분류 계획. 분류는 공개 노출 전에 디스크에 확정한다.
     * [interim]은 파일 시스템을 바꾸기 전에 먼저 쓰는 상위 집합(기존 표시 + 도착 쪽 표시 + 비공개 파일 레코드가 새 경로에서
     * 레코드 커밋 전에도 가려지도록 한 임시 경로), [finalFolders]는 완료 뒤 남기는 표시, [markFile]은 도착 파일 자신에 플래그가 필요한지.
     * 비공개 하위가 든 공개 상위 폴더는 비공개 폴더 표시와 레코드 플래그가 새 경로로 따라갈 뿐 상위를 비공개로 만들지 않는다.
     */
    private class RelocationPlan(val interim: Set<String>, val finalFolders: Set<String>, val markFile: Boolean)

    private fun coveredBy(path: String, folders: Set<String>): Boolean {
        var end = path.length
        while (end > 0) {
            if (path.substring(0, end) in folders) return true
            end = path.lastIndexOf('/', end - 1)
        }
        return false
    }

    private fun planRelocation(
        before: List<SharedInboxRecord>,
        src: String,
        dst: String,
        isDirectory: Boolean,
        copy: Boolean,
    ): RelocationPlan {
        val folders = privateFolders
        val flagged = before.filter { it.isPrivate }.mapTo(hashSetOf()) { it.storedFilename }
        // 표시를 읽을 수 없으면 무엇이 비공개인지 모르므로 비공개로 보고(→ 아래 표시 쓰기가 거부되어 아무것도 바뀌지 않는다).
        val selfPrivate = privateFoldersUnreadable || src in flagged || coveredBy(src, folders)
        fun inSource(path: String) = path == src || path.startsWith("$src/")
        val carried = folders.filter(::inSource).map { dst + it.removePrefix(src) }
        var finalFolders = (if (copy) folders else folders.filterNot(::inSource).toSet()) + carried
        var markFile = false
        val parent = dst.substringBeforeLast('/', "")
        if (selfPrivate && !(parent.isNotEmpty() && coveredBy(parent, finalFolders))) {
            if (isDirectory) finalFolders = finalFolders + dst else markFile = true
        }
        val temporary = hashSetOf<String>()
        flagged.filter(::inSource).forEach { temporary += dst + it.removePrefix(src) }
        if (markFile) temporary += dst
        temporary.removeAll { coveredBy(it, finalFolders) }
        return RelocationPlan(folders + finalFolders + temporary, finalFolders, markFile)
    }

    private fun List<SharedInboxRecord>.withDestinationMarked(plan: RelocationPlan, destination: String): List<SharedInboxRecord> =
        if (!plan.markFile) this else map { if (it.storedFilename == destination) it.copy(isPrivate = true) else it }

    /** 파일 시스템을 바꾸기 전에 비공개 표시 상위 집합을 디스크에 확정하고 공개 범위에 반영한다. 실패하면 예외(아직 아무것도 바뀌지 않음). */
    private fun commitInterimFolders(plan: RelocationPlan, records: List<SharedInboxRecord>) {
        writePrivateFolders(plan.interim)
        publishPrivacyScope(records)
    }

    /** 완료 뒤 임시 표시를 걷어 낸다. 쓰기에 실패해도 상위 집합이 남을 뿐이라 비공개로 안전하다(공개로 풀리지 않는다). */
    private fun finalizeFolders(plan: RelocationPlan) {
        runCatching { writePrivateFolders(plan.finalFolders) }
        runCatching { publishPrivacyScope(readManifest()) }
    }

    /** 파일 시스템을 원래대로 되돌린 뒤에만 쓴다. 되돌리지 못하면 상위 집합을 그대로 둔다. */
    private fun restoreFolders(previous: Set<String>) {
        runCatching { writePrivateFolders(previous) }
        runCatching { publishPrivacyScope(readManifest()) }
    }

    /** 이 작업이 방금 만든 경로를 만든 순간의 파일 식별자와 함께 기록한다. 정리할 때 같은 항목인지 대조한다. */
    private fun recordOwned(path: File, owned: MutableList<Pair<File, Any?>>) {
        val key = runCatching {
            Files.readAttributes(path.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
        }.getOrNull()
        owned.add(path to key)
    }

    /** [dir]을 원자적으로 새로 만든다. 같은 경로가 이미 있으면 합치지 않고 실패한다. */
    private fun createOwnedDirectory(dir: File, owned: MutableList<Pair<File, Any?>>) {
        Files.createDirectory(dir.toPath())
        recordOwned(dir, owned)
    }

    /** 없는 상위 폴더만 새로 만들어 기록한다. 이미 있던 폴더는 남의 것으로 두고 정리 대상에 넣지 않는다. */
    private fun createMissingParents(dir: File, owned: MutableList<Pair<File, Any?>>) {
        if (Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        dir.parentFile?.let { createMissingParents(it, owned) }
        try {
            createOwnedDirectory(dir, owned)
        } catch (e: java.nio.file.FileAlreadyExistsException) {
            if (!Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) throw e
        }
    }

    /** [dest]를 배타적으로 새로 만들어 쓴다. 파일이 생긴 즉시 기록해 쓰다 실패한 부분 복사본도 정리 대상이 된다. */
    private fun copyFileExclusive(source: File, dest: File, owned: MutableList<Pair<File, Any?>>) {
        Files.newInputStream(source.toPath()).use { input ->
            Files.newOutputStream(dest.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                recordOwned(dest, owned)
                input.copyTo(output)
            }
        }
    }

    /** 만든 역순으로 하나씩만 지운다(재귀 삭제 없음). 비어 있지 않거나 다른 항목으로 바뀐 경로는 남기고 false를 돌려준다. */
    private fun removeOwnedPaths(owned: List<Pair<File, Any?>>): Boolean {
        var allRemoved = true
        for ((path, key) in owned.asReversed()) {
            val removed = runCatching {
                val p = path.toPath()
                if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                    val current = Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
                    if (key == null || current == key) Files.delete(p)
                }
                !Files.exists(p, LinkOption.NOFOLLOW_LINKS)
            }.getOrDefault(false)
            if (!removed) allRemoved = false
        }
        return allRemoved
    }

    private fun resolveDirectory(relativeSubdirectory: String?): File {
        val rootCanonical = rootDirectory.canonicalFile
        val subDir = relativeSubdirectory?.trim()?.removePrefix("/")?.removeSuffix("/")?.takeIf { it.isNotEmpty() }
            ?: return rootCanonical
        val components = subDir.split('/')
        require(components.all { it.isNotBlank() && it != "." && it != ".." && '\\' !in it }) {
            "안전하지 않은 폴더 경로입니다."
        }
        val target = File(rootDirectory, subDir).canonicalFile
        validateContainment(target)
        return target
    }

    private fun resolveExistingEntry(relativePath: String): File {
        val trimmed = relativePath.trim().removePrefix("/").removeSuffix("/")
        require(trimmed.isNotEmpty() && trimmed != "." && trimmed != "..") { "폰하드 루트 자체는 조작 대상이 될 수 없습니다." }
        val target = File(rootDirectory, trimmed).canonicalFile
        validateContainment(target)
        val rootCanonical = rootDirectory.canonicalFile
        require(target != rootCanonical) { "폰하드 루트 폴더는 조작할 수 없습니다." }
        require(target.exists()) { "대상 항목이 존재하지 않습니다: $relativePath" }
        require(!Files.isSymbolicLink(target.toPath())) { "심볼릭 링크는 조작할 수 없습니다." }
        return target
    }

    private fun validateContainment(file: File) {
        val rootPath = rootDirectory.canonicalPath.trimEnd(File.separatorChar)
        val canonical = file.canonicalPath
        require(canonical == rootPath || canonical.startsWith("$rootPath${File.separator}")) {
            "폰하드 저장소 외부의 파일이나 폴더는 접근할 수 없습니다."
        }
    }

    private fun deduplicateTargets(targets: List<PhoneHardTarget>): List<PhoneHardTarget> {
        val sorted = targets.distinctBy { it.relativePath.trim().removePrefix("/").removeSuffix("/") }
            .sortedBy { it.relativePath.length }
        val result = mutableListOf<PhoneHardTarget>()
        for (item in sorted) {
            val normalized = item.relativePath.trim().removePrefix("/").removeSuffix("/")
            // If an ancestor is already included in result, skip this descendant
            val hasAncestor = result.any { parent ->
                val parentNorm = parent.relativePath.trim().removePrefix("/").removeSuffix("/")
                normalized.startsWith("$parentNorm/")
            }
            if (!hasAncestor) {
                result.add(item)
            }
        }
        return result
    }

    private fun findUniqueNameInDirectory(
        parentDir: File,
        originalName: String,
        manifest: List<SharedInboxRecord>? = null,
    ): String {
        val rootCanonical = rootDirectory.canonicalFile
        val existingStoredNames = manifest?.mapTo(hashSetOf()) { it.storedFilename } ?: emptySet()
        val requested = File(parentDir, originalName)
        val requestedRel = runCatching {
            requested.canonicalFile.relativeTo(rootCanonical).invariantSeparatorsPath
        }.getOrNull()

        val isAvailable: (File, String?) -> Boolean = { file, rel ->
            !file.exists() && (rel == null || (rel !in existingStoredNames && existingStoredNames.none { it.startsWith("$rel/") }))
        }

        if (isAvailable(requested, requestedRel)) return originalName
        val extension = requested.extension.takeIf(String::isNotEmpty)
        val stem = if (extension == null) requested.name else requested.name.removeSuffix(".$extension")
        for (index in 1..9_999) {
            val candidateName = if (extension == null) "$stem ($index)" else "$stem ($index).$extension"
            val candidateFile = File(parentDir, candidateName)
            val candidateRel = runCatching {
                candidateFile.canonicalFile.relativeTo(rootCanonical).invariantSeparatorsPath
            }.getOrNull()
            if (isAvailable(candidateFile, candidateRel)) return candidateName
        }
        return "${UUID.randomUUID()}-$originalName"
    }

    private fun atomicMoveOrFallback(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            Files.move(source.toPath(), destination.toPath())
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
                isPrivate = (item["isPrivate"] as? Number)?.toLong() == 1L,
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

    private fun uniqueStoredFilename(
        original: String,
        manifest: List<SharedInboxRecord>? = null,
    ): String {
        val rootCanonical = rootDirectory.canonicalFile
        val existingStoredNames = manifest?.mapTo(hashSetOf()) { it.storedFilename } ?: emptySet()
        val requested = File(rootDirectory, original)
        val requestedRel = runCatching {
            requested.canonicalFile.relativeTo(rootCanonical).invariantSeparatorsPath
        }.getOrNull()

        val isAvailable: (File, String?) -> Boolean = { file, rel ->
            !file.exists() && (rel == null || rel !in existingStoredNames)
        }

        if (isAvailable(requested, requestedRel)) return original
        val parentRelative = original.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }
        val basename = requested.name
        val extension = requested.extension.takeIf(String::isNotEmpty)
        val stem = if (extension == null) basename else basename.removeSuffix(".$extension")
        for (index in 1..9_999) {
            val candidateName = if (extension == null) "$stem ($index)" else "$stem ($index).$extension"
            val candidatePath = if (parentRelative != null) "$parentRelative/$candidateName" else candidateName
            val candidateFile = File(rootDirectory, candidatePath)
            val candidateRel = runCatching {
                candidateFile.canonicalFile.relativeTo(rootCanonical).invariantSeparatorsPath
            }.getOrNull()
            if (isAvailable(candidateFile, candidateRel)) return candidatePath
        }
        val fallbackName = "${UUID.randomUUID()}-$basename"
        return if (parentRelative != null) "$parentRelative/$fallbackName" else fallbackName
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
        const val MUSIC_DIRECTORY_NAME = "Music"
        private const val MANIFEST_NAME = ".nasfinder-manifest.json"
        private const val PRIVATE_FOLDERS_NAME = ".nasfinder-private-folders.json"
        internal const val LEGACY_MANIFEST_NAME = "manifest.json"
        private const val MANIFEST_VERSION = 1
        private const val PRIVATE_METADATA_UNREADABLE_MESSAGE =
            "비공개 폴더 표시 파일을 읽을 수 없어 일반 모드에서는 폰하드 목록을 숨겼습니다. 프라이버시 모드에서 확인하거나 파일(.nasfinder-private-folders.json과 .unreadable 백업)을 복구해 주세요."
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

private fun getMimeTypeFromExtension(extension: String): String? {
    if (extension.isBlank()) return null
    return runCatching {
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
    }.getOrNull()
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
        // true일 때만 쓴다(예전 레코드를 새 키로 다시 쓰지 않는다). 이 JSON 읽기 도구는 true/false 리터럴이 없어 1을 쓴다.
        if (record.isPrivate) append(",\"isPrivate\":1")
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
