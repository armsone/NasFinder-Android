package com.armsone.nasfinder.ui

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import com.armsone.nasfinder.data.PhoneHardImportResult
import com.armsone.nasfinder.data.PhoneHardItem
import com.armsone.nasfinder.data.PhoneHardTarget
import com.armsone.nasfinder.data.PrivacyMode
import com.armsone.nasfinder.data.SharedInboxStore
import com.armsone.nasfinder.model.RemoteFileItem
import com.armsone.nasfinder.network.RemoteFileService
import com.armsone.nasfinder.network.RemoteServiceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.UUID

/**
 * Destination target for network downloads.
 */
sealed interface NetworkDownloadDestination {
    /**
     * Downloads and saves to PhoneHard (internal app storage managed by SharedInboxStore).
     * @param subdirectory Subdirectory path inside PhoneHard (null or blank = root, e.g. "Music", "Videos").
     */
    data class PhoneHard(val subdirectory: String? = null) : NetworkDownloadDestination

    /**
     * Downloads and saves to an external directory tree selected by the user via Storage Access Framework (SAF).
     * @param treeUri The directory tree URI obtained from ACTION_OPEN_DOCUMENT_TREE.
     */
    data class ExternalTree(val treeUri: Uri) : NetworkDownloadDestination
}

/**
 * Real-time progress update for network downloads.
 */
data class NetworkDownloadProgress(
    val currentFileName: String,
    val completedFiles: Int,
    val totalFiles: Int,
    val currentFileBytes: Long,
    val currentFileTotalBytes: Long,
    val totalBytesDownloaded: Long,
    val totalBytesExpected: Long,
    // 작업을 시작할 때 고정한 출처. true이면 일반 모드 화면에는 파일 이름이 든 진행 대화상자를 내놓지 않는다.
    val originPrivate: Boolean = false,
) {
    val fileProgressFraction: Float
        get() = if (totalFiles > 0) (completedFiles.toFloat() / totalFiles).coerceIn(0f, 1f) else 0f

    val byteProgressFraction: Float
        get() = if (totalBytesExpected > 0) (totalBytesDownloaded.toFloat() / totalBytesExpected).coerceIn(0f, 1f) else 0f
}

/**
 * Record of a directory branch that was skipped during remote traversal.
 * @param path Remote path of the skipped branch.
 * @param reason Specific human-readable reason why the branch was skipped.
 */
data class SkippedBranch(
    val path: String,
    val reason: String,
)

/**
 * Result of a network download operation.
 */
sealed class NetworkDownloadResult {
    /**
     * Download completed successfully.
     * @param downloadedFiles Total number of files successfully downloaded and saved.
     * @param totalBytes Total bytes transferred and saved.
     * @param savedPaths List of saved file or directory paths/URIs.
     * @param message Human-readable success message.
     * @param skippedBranches Branches skipped during traversal.
     */
    data class Success(
        val downloadedFiles: Int,
        val totalBytes: Long,
        val savedPaths: List<String>,
        val message: String,
        val skippedBranches: List<SkippedBranch> = emptyList(),
    ) : NetworkDownloadResult()

    /**
     * Download failed due to network, storage, or permission errors.
     * @param message Honest failure explanation.
     * @param cause Underlying exception if available.
     * @param completedFiles Number of files completed and committed before failure.
     * @param totalBytes Bytes transferred and committed before failure.
     * @param skippedBranches Branches skipped during traversal.
     */
    data class Failure(
        val message: String,
        val cause: Throwable? = null,
        val completedFiles: Int = 0,
        val totalBytes: Long = 0L,
        val skippedBranches: List<SkippedBranch> = emptyList(),
    ) : NetworkDownloadResult()

    /**
     * Download was cancelled by user or parent coroutine.
     * Completed files committed to destination are preserved; partial files are cleaned up.
     * @param completedFiles Number of files committed to destination before cancellation.
     * @param totalBytes Bytes committed to destination before cancellation.
     * @param message Cancellation explanation.
     * @param skippedBranches Branches skipped during traversal.
     */
    data class Cancelled(
        val completedFiles: Int,
        val totalBytes: Long,
        val message: String = "사용자에 의해 다운로드가 취소되었습니다.",
        val skippedBranches: List<SkippedBranch> = emptyList(),
    ) : NetworkDownloadResult()

    /**
     * Download was skipped (e.g. empty selection or invalid targets).
     * @param reason Reason why download was skipped.
     * @param skippedBranches Branches skipped during traversal.
     */
    data class Skipped(
        val reason: String,
        val skippedBranches: List<SkippedBranch> = emptyList(),
    ) : NetworkDownloadResult()
}

/**
 * Coordinator interface for downloading remote files and folders into PhoneHard or SAF external trees.
 */
interface NetworkDownloadCoordinator {
    /**
     * Downloads single or multiple items (files and directory trees) to [destination] with progress callbacks.
     */
    suspend fun download(
        service: RemoteFileService,
        items: List<RemoteFileItem>,
        destination: NetworkDownloadDestination,
        onProgress: (NetworkDownloadProgress) -> Unit = {},
        // 호출자가 시작 순간에 고정한 출처(모드 + 입력). null은 "지정 안 함"이라 그때의 모드를 쓰고, 명시한 false는 그대로 공개로 고정된다.
        originPrivate: Boolean? = null,
    ): NetworkDownloadResult

    /**
     * Convenience function to download to PhoneHard subdirectory (null = root).
     */
    suspend fun downloadToPhoneHard(
        service: RemoteFileService,
        items: List<RemoteFileItem>,
        subdirectory: String? = null,
        onProgress: (NetworkDownloadProgress) -> Unit = {},
        originPrivate: Boolean? = null,
    ): NetworkDownloadResult = download(service, items, NetworkDownloadDestination.PhoneHard(subdirectory), onProgress, originPrivate)

    /**
     * Convenience function to download to an external SAF tree Uri.
     */
    suspend fun downloadToExternalTree(
        service: RemoteFileService,
        items: List<RemoteFileItem>,
        treeUri: Uri,
        onProgress: (NetworkDownloadProgress) -> Unit = {},
    ): NetworkDownloadResult = download(service, items, NetworkDownloadDestination.ExternalTree(treeUri), onProgress)

    /**
     * Safely exports PhoneHard targets (files and directory trees) to an external SAF tree Uri.
     * Uses the same safe SAF staging and commit logic to prevent overwriting existing files.
     * Preserves nested hidden files, empty folders, and top-level names.
     */
    suspend fun exportPhoneHardTargetsToExternalTree(
        targets: List<PhoneHardTarget>,
        treeUri: Uri,
        onProgress: (NetworkDownloadProgress) -> Unit = {},
        // 요청 순간의 보기 범위(프라이버시 모드 여부). 재귀 개수 산정과 복사 전체에 같은 값을 쓴다. null이면 호출 시점의 모드.
        privateVisible: Boolean? = null,
    ): NetworkDownloadResult = NetworkDownloadResult.Skipped("로컬 내보내기가 지원되지 않는 코디네이터입니다.")

    companion object {
        fun create(
            context: Context,
            sharedInboxStore: SharedInboxStore = SharedInboxStore(context),
        ): NetworkDownloadCoordinator = DefaultNetworkDownloadCoordinator(context.applicationContext, sharedInboxStore)
    }
}

/**
 * Default implementation of [NetworkDownloadCoordinator].
 * - Uses an isolated staging directory in cacheDir.
 * - Recursively preserves top-level folder names, nested files, hidden files, and empty folders.
 * - Protects against ancestor-child duplicate selection, recursive symlink cycles, and path traversal.
 * - Preserves completed files and removes only partial/incomplete staging artifacts on cancellation.
 * - Never mutates remote files (only list and download operations are used).
 * - Executes all IO work on [Dispatchers.IO].
 */
class DefaultNetworkDownloadCoordinator(
    private val context: Context,
    private val sharedInboxStore: SharedInboxStore = SharedInboxStore(context),
) : NetworkDownloadCoordinator {

    override suspend fun download(
        service: RemoteFileService,
        items: List<RemoteFileItem>,
        destination: NetworkDownloadDestination,
        onProgress: (NetworkDownloadProgress) -> Unit,
        originPrivate: Boolean?,
    ): NetworkDownloadResult = withContext(Dispatchers.IO) {
        // 호출자가 고정해 넘긴 값을 그대로 쓴다(명시한 false도 유지). 지정하지 않은 경우에만 지금 모드를 쓴다.
        val jobPrivate = originPrivate ?: PrivacyMode.isActive
        if (items.isEmpty()) {
            return@withContext NetworkDownloadResult.Skipped("다운로드할 대상 항목이 선택되지 않았습니다.")
        }

        // 1. Ancestor-child deduplication & distinct path check
        val distinctItems = items.distinctBy { it.path }
        val normalizedDirPaths = distinctItems.filter { it.isDirectory }.map { normalizeRemotePath(it.path) }
        val filteredRoots = distinctItems.filter { candidate ->
            val candPath = normalizeRemotePath(candidate.path)
            normalizedDirPaths.none { dirPath ->
                dirPath != candPath && candPath.startsWith("$dirPath/")
            }
        }

        if (filteredRoots.isEmpty()) {
            return@withContext NetworkDownloadResult.Skipped("유효한 다운로드 대상 항목이 없습니다.")
        }

        // 2. Setup isolated staging area (only our own staging cleanup in finally; service cache untouched)
        val stagingRoot = File(context.cacheDir, "staging_download_${UUID.randomUUID()}").canonicalFile
        if (!stagingRoot.mkdirs()) {
            return@withContext NetworkDownloadResult.Failure("다운로드 임시 스테이징 폴더를 생성할 수 없습니다.")
        }

        var stagedFilesCount = 0
        var stagedBytesDownloaded = 0L
        var committedDestinationFiles = 0
        var committedDestinationBytes = 0L
        val savedTargetPaths = mutableListOf<String>()
        val skippedBranches = mutableListOf<SkippedBranch>()

        try {
            // 3. Scan & Plan: traverse trees with ancestor guard, max depth bound, and cycle detection
            val filesToDownload = mutableListOf<FileDownloadTask>()
            val topLevelPlans = mutableListOf<TopLevelPlan>()
            val visitedRemotePaths = mutableSetOf<String>()

            for (rootItem in filteredRoots) {
                coroutineContext.ensureActive()
                val safeName = sanitizeSegment(rootItem.name.ifBlank { rootItem.path.substringAfterLast('/') })
                val topStagingEntry = File(stagingRoot, safeName).canonicalFile
                validateStagingContainment(stagingRoot, topStagingEntry)

                if (rootItem.isDirectory) {
                    if (!topStagingEntry.exists() && !topStagingEntry.mkdirs()) {
                        throw IOException("스테이징 폴더를 생성할 수 없습니다: ${topStagingEntry.path}")
                    }
                    topLevelPlans.add(TopLevelPlan(rootItem, safeName, topStagingEntry, isDirectory = true))

                    val normalizedRoot = normalizeRemotePath(rootItem.path)
                    visitedRemotePaths.add(normalizedRoot)

                    val dirQueue = ArrayDeque<DirScanItem>()
                    dirQueue.add(
                        DirScanItem(
                            remotePath = rootItem.path,
                            localDir = topStagingEntry,
                            depth = 0,
                            ancestorPaths = setOf(normalizedRoot),
                        )
                    )

                    while (dirQueue.isNotEmpty()) {
                        coroutineContext.ensureActive()
                        val current = dirQueue.removeFirst()

                        val children = try {
                            service.list(current.remotePath)
                        } catch (e: Exception) {
                            skippedBranches.add(
                                SkippedBranch(
                                    path = current.remotePath,
                                    reason = "원격 폴더 목록 조회 실패: ${e.message ?: e.javaClass.simpleName}"
                                )
                            )
                            continue
                        }

                        for (child in children) {
                            coroutineContext.ensureActive()
                            val childName = child.name.ifBlank { child.path.substringAfterLast('/') }
                            if (childName == "." || childName == "..") continue

                            val childSafeName = sanitizeSegment(childName)
                            val childLocalEntry = File(current.localDir, childSafeName).canonicalFile
                            validateStagingContainment(stagingRoot, childLocalEntry)

                            if (child.isDirectory) {
                                val childNormPath = normalizeRemotePath(child.path)

                                // 1. Ancestor guard: detect recursive symlink cycles / ancestor loops
                                if (childNormPath in current.ancestorPaths) {
                                    skippedBranches.add(
                                        SkippedBranch(
                                            path = child.path,
                                            reason = "순환 경로 감지: 상위 디렉터리를 가리키는 심볼릭 링크 루프입니다."
                                        )
                                    )
                                    continue
                                }

                                // 2. Max depth guard: bound path-changing symlink aliases
                                if (current.depth + 1 > MAX_REMOTE_DIRECTORY_DEPTH) {
                                    skippedBranches.add(
                                        SkippedBranch(
                                            path = child.path,
                                            reason = "최대 디렉터리 탐색 깊이 초과 ($MAX_REMOTE_DIRECTORY_DEPTH 단계 제한)"
                                        )
                                    )
                                    continue
                                }

                                // 3. Global visited paths guard (diamond symlink alias)
                                if (childNormPath in visitedRemotePaths) {
                                    skippedBranches.add(
                                        SkippedBranch(
                                            path = child.path,
                                            reason = "중복 방문 경로: 이미 탐색된 원격 디렉터리 별칭입니다."
                                        )
                                    )
                                    continue
                                }

                                // Create staging directory (preserves empty & hidden directories)
                                if (!childLocalEntry.exists() && !childLocalEntry.mkdirs()) {
                                    throw IOException("스테이징 하위 폴더를 생성할 수 없습니다: ${childLocalEntry.path}")
                                }

                                visitedRemotePaths.add(childNormPath)
                                dirQueue.add(
                                    DirScanItem(
                                        remotePath = child.path,
                                        localDir = childLocalEntry,
                                        depth = current.depth + 1,
                                        ancestorPaths = current.ancestorPaths + childNormPath,
                                    )
                                )
                            } else {
                                filesToDownload.add(FileDownloadTask(child, childLocalEntry))
                            }
                        }
                    }
                } else {
                    topLevelPlans.add(TopLevelPlan(rootItem, safeName, topStagingEntry, isDirectory = false))
                    filesToDownload.add(FileDownloadTask(rootItem, topStagingEntry))
                }
            }

            if (filesToDownload.isEmpty() && topLevelPlans.isEmpty()) {
                val reason = if (skippedBranches.isNotEmpty()) {
                    "유효한 다운로드 대상 항목이 없습니다. (건너뛴 경로: ${skippedBranches.first().path} - ${skippedBranches.first().reason})"
                } else {
                    "유효한 다운로드 대상 항목이 없습니다."
                }
                return@withContext NetworkDownloadResult.Skipped(reason, skippedBranches)
            }

            val totalExpectedBytes = filesToDownload.sumOf { maxOf(0L, it.item.size) }

            // Initial progress announcement
            onProgress(
                NetworkDownloadProgress(
                    currentFileName = "",
                    completedFiles = 0,
                    totalFiles = filesToDownload.size,
                    currentFileBytes = 0L,
                    currentFileTotalBytes = 0L,
                    totalBytesDownloaded = 0L,
                    totalBytesExpected = totalExpectedBytes,
                )
            )

            // 4. Download each file into staging
            for (task in filesToDownload) {
                coroutineContext.ensureActive()
                val remoteItem = task.item
                val destStagingFile = task.stagingFile
                destStagingFile.parentFile?.let { if (!it.exists()) it.mkdirs() }

                var fileBytesSoFar = 0L
                try {
                    service.download(remoteItem, destStagingFile) { bytesCurrent, bytesTotal ->
                        coroutineContext.ensureActive()
                        val delta = bytesCurrent - fileBytesSoFar
                        if (delta > 0) {
                            stagedBytesDownloaded += delta
                            fileBytesSoFar = bytesCurrent
                        }
                        onProgress(
                            NetworkDownloadProgress(
                                currentFileName = remoteItem.name,
                                completedFiles = stagedFilesCount,
                                totalFiles = filesToDownload.size,
                                currentFileBytes = fileBytesSoFar,
                                currentFileTotalBytes = if (bytesTotal > 0) bytesTotal else remoteItem.size,
                                totalBytesDownloaded = stagedBytesDownloaded,
                                totalBytesExpected = totalExpectedBytes,
                            )
                        )
                    }
                    stagedFilesCount++
                } catch (e: CancellationException) {
                    destStagingFile.delete()
                    throw e
                } catch (e: Throwable) {
                    destStagingFile.delete()
                    throw e
                }
            }

            // 5. Transfer from staging to target destination
            when (destination) {
                is NetworkDownloadDestination.PhoneHard -> {
                    for (plan in topLevelPlans) {
                        coroutineContext.ensureActive()
                        val importResult = sharedInboxStore.importFromStaging(
                            stagedEntry = plan.stagingEntry,
                            desiredName = plan.sanitizedName,
                            destinationSubdirectory = destination.subdirectory,
                            mimeType = if (plan.isDirectory) null else plan.originalItem.mimeType,
                            isPrivate = jobPrivate,
                        )
                        savedTargetPaths.add(importResult.targetFile.absolutePath)
                        committedDestinationFiles += importResult.records.size
                        committedDestinationBytes += importResult.records.sumOf { it.byteCount }
                    }
                    // Re-query folder items from PhoneHard to verify visibility and manifest consistency
                    sharedInboxStore.listPhoneHardFolder(destination.subdirectory)
                }

                is NetworkDownloadDestination.ExternalTree -> {
                    val contentResolver = context.contentResolver
                    val treeUri = destination.treeUri
                    val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
                    val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)

                    for (plan in topLevelPlans) {
                        coroutineContext.ensureActive()
                        val savedUri = saveStagedEntryToTreeUri(
                            contentResolver = contentResolver,
                            treeUri = treeUri,
                            parentDocUri = rootDocUri,
                            stagedEntry = plan.stagingEntry,
                            displayName = plan.sanitizedName,
                            mimeType = if (plan.isDirectory) null else plan.originalItem.mimeType,
                            onFileCommitted = { bytes ->
                                committedDestinationFiles++
                                committedDestinationBytes += bytes
                            },
                        )
                        savedTargetPaths.add(savedUri.toString())
                    }
                }
            }

            val formattedSize = formatByteSize(committedDestinationBytes)
            val skippedSummary = if (skippedBranches.isNotEmpty()) " (건너뛴 항목 ${skippedBranches.size}개 제외됨)" else ""
            val successMessage = if (filesToDownload.isEmpty()) {
                "다운로드가 완료되었습니다. (폴더 구조 생성 완료)$skippedSummary"
            } else {
                "다운로드가 완료되었습니다. (${committedDestinationFiles}개 파일, $formattedSize)$skippedSummary"
            }

            NetworkDownloadResult.Success(
                downloadedFiles = committedDestinationFiles,
                totalBytes = committedDestinationBytes,
                savedPaths = savedTargetPaths,
                message = successMessage,
                skippedBranches = skippedBranches,
            )
        } catch (e: CancellationException) {
            val message = if (committedDestinationFiles > 0) {
                "사용자에 의해 다운로드가 취소되었습니다. (${committedDestinationFiles}개 파일 보존됨)"
            } else {
                "사용자에 의해 다운로드가 취소되었습니다. (저장된 대상 파일 없음)"
            }
            NetworkDownloadResult.Cancelled(
                completedFiles = committedDestinationFiles,
                totalBytes = committedDestinationBytes,
                message = message,
                skippedBranches = skippedBranches,
            )
        } catch (e: RemoteServiceException) {
            NetworkDownloadResult.Failure(
                message = "원격 서버 오류로 다운로드에 실패했습니다: ${e.message}",
                cause = e,
                completedFiles = committedDestinationFiles,
                totalBytes = committedDestinationBytes,
                skippedBranches = skippedBranches,
            )
        } catch (e: SecurityException) {
            NetworkDownloadResult.Failure(
                message = "외부 저장소 쓰기 권한이 없거나 해제되었습니다: ${e.message}",
                cause = e,
                completedFiles = committedDestinationFiles,
                totalBytes = committedDestinationBytes,
                skippedBranches = skippedBranches,
            )
        } catch (e: Throwable) {
            NetworkDownloadResult.Failure(
                message = "다운로드 처리 중 오류가 발생했습니다: ${e.message ?: e.javaClass.simpleName}",
                cause = e,
                completedFiles = committedDestinationFiles,
                totalBytes = committedDestinationBytes,
                skippedBranches = skippedBranches,
            )
        } finally {
            // Clean up our own staging temporary directory completely. Service cache remains untouched.
            stagingRoot.deleteRecursively()
        }
    }

    override suspend fun exportPhoneHardTargetsToExternalTree(
        targets: List<PhoneHardTarget>,
        treeUri: Uri,
        onProgress: (NetworkDownloadProgress) -> Unit,
        privateVisible: Boolean?,
    ): NetworkDownloadResult = withContext(Dispatchers.IO) {
        val scopeVisible = privateVisible ?: PrivacyMode.isActive
        if (targets.isEmpty()) {
            return@withContext NetworkDownloadResult.Skipped("내보낼 대상 항목이 선택되지 않았습니다.")
        }

        val skippedBranches = mutableListOf<SkippedBranch>()
        val rootCanonical = sharedInboxStore.rootDirectory.canonicalFile
        val rootPath = rootCanonical.canonicalPath.trimEnd(File.separatorChar)

        // 1. Ancestor-child deduplication & target normalization
        val distinctTargets = targets
            .map { target ->
                val norm = target.relativePath.trim().replace('\\', '/').removePrefix("/").removeSuffix("/")
                target to norm
            }
            .filter { (target, norm) ->
                if (norm.isEmpty() || norm == "." || norm == "..") {
                    skippedBranches.add(SkippedBranch(target.relativePath, "폰하드 루트 자체는 내보낼 수 없습니다."))
                    false
                } else if (norm == ".nasfinder-manifest.json" || norm == "manifest.json") {
                    skippedBranches.add(SkippedBranch(target.relativePath, "시스템 매니페스트 파일은 내보낼 수 없습니다."))
                    false
                } else {
                    true
                }
            }
            .distinctBy { it.second }
            .sortedBy { it.second.length }

        val filteredTargets = mutableListOf<Pair<PhoneHardTarget, String>>()
        for (item in distinctTargets) {
            val norm = item.second
            val hasAncestor = filteredTargets.any { (_, parentNorm) ->
                norm.startsWith("$parentNorm/")
            }
            if (!hasAncestor) {
                filteredTargets.add(item)
            }
        }

        if (filteredTargets.isEmpty()) {
            return@withContext NetworkDownloadResult.Skipped(
                reason = "유효한 내보내기 대상 항목이 없습니다.",
                skippedBranches = skippedBranches,
            )
        }

        // 2. Validate containment, existence, and symlinks for filtered targets
        data class LocalExportPlan(
            val target: PhoneHardTarget,
            val resolvedFile: File,
            val displayName: String,
            val isDirectory: Boolean,
            val mimeType: String?,
        )

        val records = runCatching { sharedInboxStore.records() }.getOrDefault(emptyList())
        val recordByStored = records.associateBy { it.storedFilename }
        val recordById = records.associateBy { it.id }

        val plans = mutableListOf<LocalExportPlan>()
        for ((target, norm) in filteredTargets) {
            val file = File(rootCanonical, norm).canonicalFile
            val fileCanonical = file.canonicalPath
            if (fileCanonical == rootPath || !fileCanonical.startsWith("$rootPath${File.separator}")) {
                skippedBranches.add(SkippedBranch(target.relativePath, "폰하드 저장소 외부의 파일이나 폴더는 내보낼 수 없습니다."))
                continue
            }
            if (!file.exists()) {
                skippedBranches.add(SkippedBranch(target.relativePath, "대상 항목이 존재하지 않습니다: ${target.relativePath}"))
                continue
            }
            if (Files.isSymbolicLink(file.toPath())) {
                skippedBranches.add(SkippedBranch(target.relativePath, "심볼릭 링크는 내보낼 수 없습니다: ${target.relativePath}"))
                continue
            }

            val isDirectory = file.isDirectory
            val rec = if (target.id != null) recordById[target.id] else recordByStored[norm]
            val displayName = if (isDirectory) {
                file.name
            } else {
                rec?.originalFilename?.takeIf { it.isNotBlank() } ?: file.name
            }
            val mime = if (isDirectory) null else (rec?.mimeType ?: getMimeTypeFromExtension(file.extension))

            plans.add(
                LocalExportPlan(
                    target = target,
                    resolvedFile = file,
                    displayName = displayName,
                    isDirectory = isDirectory,
                    mimeType = mime,
                )
            )
        }

        if (plans.isEmpty()) {
            return@withContext NetworkDownloadResult.Skipped(
                reason = "유효한 내보내기 대상 항목이 없습니다.",
                skippedBranches = skippedBranches,
            )
        }

        // 3. Calculate expected file count and byte totals for progress tracking
        var totalFilesExpected = 0
        var totalBytesExpected = 0L
        for (plan in plans) {
            val file = plan.resolvedFile
            if (plan.isDirectory) {
                file.walkTopDown()
                    .maxDepth(MAX_REMOTE_DIRECTORY_DEPTH)
                    .onEnter { dir -> !Files.isSymbolicLink(dir.toPath()) && !hiddenFromExport(dir, file, scopeVisible) }
                    .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && !hiddenFromExport(it, file, scopeVisible) }
                    .forEach { f ->
                        totalFilesExpected++
                        totalBytesExpected += f.length()
                    }
            } else {
                totalFilesExpected++
                totalBytesExpected += file.length()
            }
        }

        onProgress(
            NetworkDownloadProgress(
                currentFileName = "",
                completedFiles = 0,
                totalFiles = totalFilesExpected,
                currentFileBytes = 0L,
                currentFileTotalBytes = 0L,
                totalBytesDownloaded = 0L,
                totalBytesExpected = totalBytesExpected,
            )
        )

        var committedDestinationFiles = 0
        var committedDestinationBytes = 0L
        var streamBytesTransferred = 0L
        val savedTargetPaths = mutableListOf<String>()

        try {
            val contentResolver = context.contentResolver
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
                ?: throw IOException("유효하지 않은 외부 저장소 트리 URI입니다: $treeUri")
            val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)

            for (plan in plans) {
                coroutineContext.ensureActive()
                val savedUri = saveStagedEntryToTreeUri(
                    contentResolver = contentResolver,
                    treeUri = treeUri,
                    parentDocUri = rootDocUri,
                    stagedEntry = plan.resolvedFile,
                    displayName = plan.displayName,
                    mimeType = plan.mimeType,
                    skipChild = { child -> hiddenFromExport(child, plan.resolvedFile, scopeVisible) },
                    onBytesCopied = { fileName, fileBytesCopied, fileTotal, delta ->
                        streamBytesTransferred += delta
                        onProgress(
                            NetworkDownloadProgress(
                                currentFileName = fileName,
                                completedFiles = committedDestinationFiles,
                                totalFiles = totalFilesExpected,
                                currentFileBytes = fileBytesCopied,
                                currentFileTotalBytes = fileTotal,
                                totalBytesDownloaded = streamBytesTransferred,
                                totalBytesExpected = totalBytesExpected,
                            )
                        )
                    },
                    onFileCommitted = { bytes ->
                        committedDestinationFiles++
                        committedDestinationBytes += bytes
                        onProgress(
                            NetworkDownloadProgress(
                                currentFileName = plan.displayName,
                                completedFiles = committedDestinationFiles,
                                totalFiles = totalFilesExpected,
                                currentFileBytes = bytes,
                                currentFileTotalBytes = bytes,
                                totalBytesDownloaded = committedDestinationBytes,
                                totalBytesExpected = totalBytesExpected,
                            )
                        )
                    },
                )
                savedTargetPaths.add(savedUri.toString())
            }

            val formattedSize = formatByteSize(committedDestinationBytes)
            val skippedSummary = if (skippedBranches.isNotEmpty()) " (건너뛴 항목 ${skippedBranches.size}개 제외됨)" else ""
            val successMessage = if (totalFilesExpected == 0) {
                "내보내기가 완료되었습니다. (폴더 구조 생성 완료)$skippedSummary"
            } else {
                "내보내기가 완료되었습니다. (${committedDestinationFiles}개 파일, $formattedSize)$skippedSummary"
            }

            NetworkDownloadResult.Success(
                downloadedFiles = committedDestinationFiles,
                totalBytes = committedDestinationBytes,
                savedPaths = savedTargetPaths,
                message = successMessage,
                skippedBranches = skippedBranches,
            )
        } catch (e: CancellationException) {
            val message = if (committedDestinationFiles > 0) {
                "사용자에 의해 내보내기가 취소되었습니다. (${committedDestinationFiles}개 파일 보존됨)"
            } else {
                "사용자에 의해 내보내기가 취소되었습니다. (저장된 대상 파일 없음)"
            }
            NetworkDownloadResult.Cancelled(
                completedFiles = committedDestinationFiles,
                totalBytes = committedDestinationBytes,
                message = message,
                skippedBranches = skippedBranches,
            )
        } catch (e: SecurityException) {
            NetworkDownloadResult.Failure(
                message = "외부 저장소 쓰기 권한이 없거나 해제되었습니다: ${e.message}",
                cause = e,
                completedFiles = committedDestinationFiles,
                totalBytes = committedDestinationBytes,
                skippedBranches = skippedBranches,
            )
        } catch (e: Throwable) {
            NetworkDownloadResult.Failure(
                message = "내보내기 처리 중 오류가 발생했습니다: ${e.message ?: e.javaClass.simpleName}",
                cause = e,
                completedFiles = committedDestinationFiles,
                totalBytes = committedDestinationBytes,
                skippedBranches = skippedBranches,
            )
        }
    }

    /**
     * 일반 모드에서 폴더를 통째로 내보낼 때 보이지 않는 비공개 하위 항목은 함께 나가지 않는다.
     * 사용자가 직접 고른 항목([root] 자신)과 프라이버시 모드의 내보내기는 막지 않는다.
     */
    private fun hiddenFromExport(file: File, root: File, privateVisible: Boolean): Boolean =
        file != root && !privateVisible && PrivacyMode.isPhoneHardAbsolutePathPrivate(file.absolutePath)

    private suspend fun saveStagedEntryToTreeUri(
        contentResolver: ContentResolver,
        treeUri: Uri,
        parentDocUri: Uri,
        stagedEntry: File,
        displayName: String,
        mimeType: String?,
        depth: Int = 0,
        ancestorCanonicalPaths: Set<String> = emptySet(),
        skipChild: ((File) -> Boolean)? = null,
        onBytesCopied: ((fileName: String, fileBytesCopied: Long, fileTotalBytes: Long, bytesDelta: Long) -> Unit)? = null,
        onFileCommitted: (Long) -> Unit,
    ): Uri = withContext(Dispatchers.IO) {
        if (Files.isSymbolicLink(stagedEntry.toPath())) {
            throw IOException("심볼릭 링크는 외부 저장소로 복사할 수 없습니다: ${stagedEntry.path}")
        }
        if (depth > MAX_REMOTE_DIRECTORY_DEPTH) {
            throw IOException("디렉터리 탐색 깊이가 최대 허용치($MAX_REMOTE_DIRECTORY_DEPTH)를 초과했습니다: ${stagedEntry.path}")
        }
        val canonical = stagedEntry.canonicalPath
        if (canonical in ancestorCanonicalPaths) {
            throw IOException("순환 디렉터리 참조가 감지되었습니다: ${stagedEntry.path}")
        }

        val (existingNames, existingDocIds) = queryChildrenInTree(contentResolver, treeUri, parentDocUri)
        val isDirectory = stagedEntry.isDirectory
        val uniqueName = reserveUniqueSafName(existingNames, displayName, isDirectory)

        if (isDirectory) {
            val dirUri = DocumentsContract.createDocument(
                contentResolver,
                parentDocUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                uniqueName,
            ) ?: throw IOException("외부 저장소에 폴더를 생성할 수 없습니다: $uniqueName")

            val dirDocId = runCatching { DocumentsContract.getDocumentId(dirUri) }.getOrNull()
            if (dirDocId != null && dirDocId in existingDocIds) {
                throw IOException("외부 저장소 제공자가 기존 폴더의 URI를 반환했습니다. 원본 보존을 위해 작업을 중단합니다: $uniqueName")
            }
            if (dirDocId != null) existingDocIds.add(dirDocId)

            val childDocId = DocumentsContract.getDocumentId(dirUri)
            val dirScopedUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)

            var anyChildCommitted = false
            try {
                val children = stagedEntry.listFiles() ?: emptyArray()
                val nextAncestors = ancestorCanonicalPaths + canonical
                for (child in children) {
                    coroutineContext.ensureActive()
                    if (Files.isSymbolicLink(child.toPath())) continue
                    if (skipChild?.invoke(child) == true) continue
                    saveStagedEntryToTreeUri(
                        contentResolver = contentResolver,
                        treeUri = treeUri,
                        parentDocUri = dirScopedUri,
                        stagedEntry = child,
                        displayName = child.name,
                        mimeType = null,
                        depth = depth + 1,
                        ancestorCanonicalPaths = nextAncestors,
                        skipChild = skipChild,
                        onBytesCopied = onBytesCopied,
                        onFileCommitted = { bytes ->
                            anyChildCommitted = true
                            onFileCommitted(bytes)
                        },
                    )
                }
                dirUri
            } catch (e: Throwable) {
                // Newly created incomplete output cleanup only: clean up newly created empty directory only if no children were committed
                if (!anyChildCommitted) {
                    runCatching { DocumentsContract.deleteDocument(contentResolver, dirUri) }
                }
                throw e
            }
        } else {
            val safeMime = mimeType?.takeIf { '/' in it }
                ?: getMimeTypeFromExtension(stagedEntry.extension)
                ?: "application/octet-stream"

            val fileDocUri = DocumentsContract.createDocument(
                contentResolver,
                parentDocUri,
                safeMime,
                uniqueName,
            ) ?: throw IOException("외부 저장소에 파일을 생성할 수 없습니다: $uniqueName")

            val fileDocId = runCatching { DocumentsContract.getDocumentId(fileDocUri) }.getOrNull()
            if (fileDocId != null && fileDocId in existingDocIds) {
                throw IOException("외부 저장소 제공자가 기존 파일의 URI를 반환했습니다. 원본 덮어쓰기 방지를 위해 작업을 중단합니다: $uniqueName")
            }
            if (fileDocId != null) existingDocIds.add(fileDocId)

            var writeSucceeded = false
            try {
                stagedEntry.inputStream().use { input ->
                    contentResolver.openOutputStream(fileDocUri, "wt")?.use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var bytesRead: Int
                        var fileBytesCopied = 0L
                        val fileTotal = stagedEntry.length()
                        while (input.read(buffer).also { bytesRead = it } >= 0) {
                            coroutineContext.ensureActive()
                            output.write(buffer, 0, bytesRead)
                            fileBytesCopied += bytesRead
                            onBytesCopied?.invoke(uniqueName, fileBytesCopied, fileTotal, bytesRead.toLong())
                        }
                        output.flush()
                    } ?: throw IOException("외부 저장소 파일 출력 스트림을 열 수 없습니다: $uniqueName")
                }
                writeSucceeded = true
                onFileCommitted(stagedEntry.length())
                fileDocUri
            } catch (e: Throwable) {
                if (!writeSucceeded) {
                    // Newly created incomplete output cleanup only
                    runCatching { DocumentsContract.deleteDocument(contentResolver, fileDocUri) }
                }
                throw e
            }
        }
    }

    private fun queryChildrenInTree(
        contentResolver: ContentResolver,
        treeUri: Uri,
        parentDocUri: Uri,
    ): Pair<MutableSet<String>, MutableSet<String>> {
        val parentDocId = runCatching {
            DocumentsContract.getDocumentId(parentDocUri)
        }.getOrNull() ?: throw IOException("외부 저장소 디렉터리 ID를 확인할 수 없습니다: $parentDocUri")

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )

        val cursor = try {
            contentResolver.query(childrenUri, projection, null, null, null)
                ?: throw IOException("외부 저장소 하위 항목을 조회할 수 없습니다 (null 커서): $childrenUri")
        } catch (e: Exception) {
            if (e is IOException) throw e
            throw IOException("외부 저장소 하위 항목 조회 중 오류가 발생했습니다: ${e.message}", e)
        }

        val existingNames = mutableSetOf<String>()
        val existingDocIds = mutableSetOf<String>()

        cursor.use { c ->
            val idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (idCol < 0 || nameCol < 0) {
                throw IOException("외부 저장소 커서에 필수 열이 누락되었습니다 (idCol=$idCol, nameCol=$nameCol): $childrenUri")
            }
            while (c.moveToNext()) {
                val docId = c.getString(idCol)
                val name = c.getString(nameCol)
                if (!docId.isNullOrBlank()) existingDocIds.add(docId)
                if (!name.isNullOrBlank()) existingNames.add(name)
            }
        }
        return existingNames to existingDocIds
    }

    private fun reserveUniqueSafName(
        existingNames: MutableSet<String>,
        desiredName: String,
        isDirectory: Boolean,
    ): String {
        if (desiredName !in existingNames) {
            existingNames.add(desiredName)
            return desiredName
        }
        val extension = if (isDirectory) "" else desiredName.substringAfterLast('.', "")
        val stem = if (isDirectory || extension.isEmpty()) desiredName else desiredName.removeSuffix(".$extension")
        for (i in 1..9_999) {
            val candidate = if (isDirectory || extension.isEmpty()) "$stem ($i)" else "$stem ($i).$extension"
            if (candidate !in existingNames) {
                existingNames.add(candidate)
                return candidate
            }
        }
        val fallback = "${UUID.randomUUID()}-$desiredName"
        existingNames.add(fallback)
        return fallback
    }

    private fun normalizeRemotePath(path: String): String {
        return path.trim().replace('\\', '/').removeSuffix("/").ifBlank { "/" }
    }

    private fun getMimeTypeFromExtension(extension: String): String? {
        if (extension.isBlank()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
    }

    private fun validateStagingContainment(stagingRoot: File, target: File) {
        val rootCanonical = stagingRoot.canonicalPath.trimEnd(File.separatorChar)
        val targetCanonical = target.canonicalPath
        require(targetCanonical == rootCanonical || targetCanonical.startsWith("$rootCanonical${File.separator}")) {
            "안전하지 않은 스테이징 경로입니다."
        }
    }

    private fun sanitizeSegment(name: String): String {
        val candidate = name.substringAfterLast('/').substringAfterLast('\\').trim()
        val filtered = candidate.filter { it >= ' ' && it != 127.toChar() }
        return when {
            filtered.isBlank() || filtered == "." || filtered == ".." -> "다운로드_항목"
            else -> filtered
        }
    }

    private fun formatByteSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }

    companion object {
        const val MAX_REMOTE_DIRECTORY_DEPTH = 32
    }

    private data class DirScanItem(
        val remotePath: String,
        val localDir: File,
        val depth: Int,
        val ancestorPaths: Set<String>,
    )

    private data class FileDownloadTask(
        val item: RemoteFileItem,
        val stagingFile: File,
    )

    private data class TopLevelPlan(
        val originalItem: RemoteFileItem,
        val sanitizedName: String,
        val stagingEntry: File,
        val isDirectory: Boolean,
    )
}
