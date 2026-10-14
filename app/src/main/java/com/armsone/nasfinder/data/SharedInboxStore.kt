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
        val musicDir = File(rootDirectory, MUSIC_DIRECTORY_NAME)
        if (!musicDir.exists()) musicDir.mkdirs()
        if (!manifestFile.exists()) atomicWrite(manifestFile, encodeManifest(emptyList()))
        synchronized(PROCESS_LOCK) { snapshot = readManifest() }
    }

    val musicDirectory: File
        get() = File(rootDirectory, MUSIC_DIRECTORY_NAME).also {
            if (!it.exists()) it.mkdirs()
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
        destinationSubdirectory: String? = null,
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
    ): PhoneHardImportResult = synchronized(PROCESS_LOCK) {
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

        try {
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
            hooks.commitManifest(manifestFile, encodeManifest(updatedManifest))
            snapshot = updatedManifest
            val targetRel = targetEntry.relativeTo(rootCanonical).invariantSeparatorsPath
            PhoneHardImportResult(targetEntry, targetRel, newRecords)
        } catch (error: Throwable) {
            for (created in createdFiles.reversed()) {
                runCatching {
                    if (created.isDirectory) created.deleteRecursively() else created.delete()
                }
            }
            snapshot = runCatching { readManifest() }.getOrElse { currentManifest }
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
     * Lists immediate entries (files and directories) inside a given subfolder.
     * [relativeSubdirectory] null or empty means root directory.
     */
    fun listPhoneHardFolder(relativeSubdirectory: String? = null): List<PhoneHardItem> = synchronized(PROCESS_LOCK) {
        val targetDir = resolveDirectory(relativeSubdirectory)
        require(targetDir.exists() && targetDir.isDirectory) { "폴더가 존재하지 않습니다." }

        val records = readManifest().also { snapshot = it }
        val recordByStored = records.associateBy { it.storedFilename }

        val rootCanonical = rootDirectory.canonicalFile
        val items = mutableListOf<PhoneHardItem>()

        val children = targetDir.listFiles() ?: emptyArray()
        for (child in children) {
            val name = child.name
            if (name.startsWith('.') || name == MANIFEST_NAME || name == LEGACY_MANIFEST_NAME) continue
            if (Files.isSymbolicLink(child.toPath())) continue

            val relPath = child.canonicalFile.relativeTo(rootCanonical).invariantSeparatorsPath
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
    fun createFolder(folderName: String, parentSubdirectory: String? = null): File = synchronized(PROCESS_LOCK) {
        val parent = resolveDirectory(parentSubdirectory)
        val safeName = safeBasename(folderName)
        require(safeName.isNotBlank() && safeName != "." && safeName != "..") { "올바르지 않은 폴더명입니다." }
        require(safeName != MANIFEST_NAME && safeName != LEGACY_MANIFEST_NAME && !safeName.startsWith('.')) {
            "예약된 시스템 이름으로는 폴더를 생성할 수 없습니다."
        }

        val newDir = File(parent, safeName).canonicalFile
        validateContainment(newDir)
        require(!newDir.exists()) { "이미 같은 이름의 파일 또는 폴더가 존재합니다." }
        if (!newDir.mkdirs()) throw IllegalStateException("폴더를 생성하지 못했습니다.")
        newDir
    }

    /**
     * Batch move files and folders into [destinationSubdirectory].
     * Preserves IDs, updates descendant stored paths, rejects self/descendant nesting, serializes with PROCESS_LOCK.
     */
    fun moveTargets(
        targets: List<PhoneHardTarget>,
        destinationSubdirectory: String? = null,
    ): PhoneHardOperationResult = synchronized(PROCESS_LOCK) {
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

                // Perform filesystem move
                moveWithoutReplacing(targetFile, finalFile)
                fileMoved = true
                movedFile = finalFile

                val newRel = finalFile.relativeTo(rootCanonical).invariantSeparatorsPath

                // Build candidate manifest without mutating currentManifest yet
                val candidateManifest = if (movingDirectory) {
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
                }

                hooks.commitManifest(manifestFile, encodeManifest(candidateManifest))
                currentManifest = candidateManifest
                snapshot = currentManifest
                succeeded.add(target)
                pathMappings.add(PhoneHardPathMapping(targetRel, newRel, movingDirectory))
            } catch (e: Exception) {
                if (fileMoved && movedFile != null && originalFile != null) {
                    val rollbackResult = runCatching {
                        moveWithoutReplacing(movedFile, originalFile)
                    }
                    if (rollbackResult.isFailure) {
                        failed.add(target to "이동 후 매니페스트 저장에 실패했으며 원래 위치 복구도 실패했습니다: ${e.message}")
                    } else {
                        failed.add(target to "이동 후 매니페스트 저장에 실패하여 원래 위치로 복구했습니다: ${e.message}")
                    }
                } else {
                    failed.add(target to (e.message ?: "이동에 실패했습니다."))
                }
            }
        }
        PhoneHardOperationResult(succeeded, failed, pathMappings = pathMappings)
    }

    /**
     * Renames one file or folder in place.
     * Never overwrites, preserves IDs, mimeType and importedAt of the entry and every descendant record,
     * restores the original name when the manifest cannot be committed, serializes with PROCESS_LOCK.
     */
    fun renamePhoneHardTarget(
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

            val candidateManifest = currentManifest.map { record ->
                if (record.storedFilename == sourceRel) {
                    record.copy(storedFilename = destRel, originalFilename = name)
                } else if (record.storedFilename.startsWith("$sourceRel/")) {
                    val subPath = record.storedFilename.removePrefix("$sourceRel/")
                    record.copy(storedFilename = "$destRel/$subPath")
                } else {
                    record
                }
            }
            candidateManifest.forEach(::validateRecord)

            moveWithoutReplacing(source, destination)
            try {
                hooks.commitManifest(manifestFile, encodeManifest(candidateManifest))
            } catch (e: Exception) {
                if (runCatching { moveWithoutReplacing(destination, source) }.isSuccess) {
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
    ): PhoneHardOperationResult = synchronized(PROCESS_LOCK) {
        val destDir = resolveDirectory(destinationSubdirectory)
        val rootCanonical = rootDirectory.canonicalFile
        val destRelative = if (destDir == rootCanonical) "" else destDir.relativeTo(rootCanonical).invariantSeparatorsPath

        val deduped = deduplicateTargets(targets)
        val succeeded = mutableListOf<PhoneHardTarget>()
        val failed = mutableListOf<Pair<PhoneHardTarget, String>>()

        var currentManifest = readManifest()

        for (target in deduped) {
            val newlyCreatedFiles = mutableListOf<File>()
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

                if (target.isDirectory) {
                    val finalRel = finalFile.relativeTo(rootCanonical).invariantSeparatorsPath
                    finalFile.mkdirs()
                    newlyCreatedFiles.add(finalFile)

                    // Deep copy all descendants
                    targetFile.walkTopDown().forEach { sourceChild ->
                        if (sourceChild == targetFile) return@forEach
                        if (sourceChild.name.startsWith('.') || Files.isSymbolicLink(sourceChild.toPath())) return@forEach
                        val subPath = sourceChild.relativeTo(targetFile).invariantSeparatorsPath
                        val destChild = File(finalFile, subPath).canonicalFile
                        validateContainment(destChild)
                        if (sourceChild.isDirectory) {
                            destChild.mkdirs()
                            newlyCreatedFiles.add(destChild)
                        } else if (sourceChild.isFile) {
                            destChild.parentFile?.mkdirs()
                            sourceChild.copyTo(destChild, overwrite = false)
                            newlyCreatedFiles.add(destChild)

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
                                )
                            )
                        }
                    }
                } else {
                    targetFile.copyTo(finalFile, overwrite = false)
                    newlyCreatedFiles.add(finalFile)

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
                        )
                    )
                }

                val candidateManifest = currentManifest + newRecordsToAdd
                hooks.commitManifest(manifestFile, encodeManifest(candidateManifest))
                currentManifest = candidateManifest
                snapshot = currentManifest
                succeeded.add(target)
            } catch (e: Exception) {
                // Rollback newly created files; currentManifest remains untouched
                for (created in newlyCreatedFiles.reversed()) {
                    runCatching { created.delete() }
                }
                failed.add(target to (e.message ?: "복사에 실패했습니다."))
            }
        }
        PhoneHardOperationResult(succeeded, failed)
    }

    /**
     * Batch delete files and folders.
     * Deduplicates ancestors, removes from manifest and filesystem.
     */
    fun deleteTargets(targets: List<PhoneHardTarget>): PhoneHardOperationResult = synchronized(PROCESS_LOCK) {
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

                if (target.isDirectory) {
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
        PhoneHardOperationResult(succeeded, failed, removedPaths = removedPaths)
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
