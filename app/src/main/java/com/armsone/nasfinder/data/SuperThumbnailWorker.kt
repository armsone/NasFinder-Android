package com.armsone.nasfinder.data

import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.LiveData
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import android.graphics.Bitmap
import com.armsone.nasfinder.model.RemoteConnection
import com.armsone.nasfinder.model.RemoteFileItem
import com.armsone.nasfinder.network.RemoteFileServiceFactory
import com.armsone.nasfinder.platform.SuperThumbnailThermalDecision
import com.armsone.nasfinder.platform.SuperThumbnailThermalPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

enum class SuperThumbnailWorkStatus { WAITING, RUNNING, SUCCESS, PARTIAL, FAILED, CANCELLED }

data class SuperThumbnailWorkSnapshot(
    val status: SuperThumbnailWorkStatus,
    val visitedItems: Int,
    val generated: Int,
    val failed: Int,
    val estimatedBytes: Long,
    val budgetReached: Boolean,
)

object SuperThumbnailWorkController {
    fun enqueue(
        context: Context,
        connectionId: String,
        rootPath: String? = null,
        allowsConstrainedRun: Boolean = false,
        vaultOptions: SuperThumbnailVaultOptions = SuperThumbnailVaultOptions(),
        resumeExisting: Boolean = false,
    ): UUID = SuperThumbnailCacheResetCoordinator.withEnqueueAllowed {
        require(connectionId.isNotBlank()) { "연결 ID가 필요합니다." }
        val input = Data.Builder().putString(SuperThumbnailWorker.KEY_CONNECTION_ID, connectionId).apply {
            rootPath?.takeIf(String::isNotBlank)?.let { putString(SuperThumbnailWorker.KEY_ROOT_PATH, it) }
            putBoolean(SuperThumbnailWorker.KEY_ALLOWS_CONSTRAINED_RUN, allowsConstrainedRun)
            putBoolean(SuperThumbnailWorker.KEY_VAULT_ENABLED, vaultOptions.enabled)
            putString(SuperThumbnailWorker.KEY_VAULT_TIMING, vaultOptions.timing.name)
            putBoolean(SuperThumbnailWorker.KEY_RESUME_EXISTING, resumeExisting)
        }.build()
        val runtimeConstraints = SuperThumbnailRuntimeConstraints.forRun(allowsConstrainedRun)
        val constraints = Constraints.Builder().apply {
            if (runtimeConstraints.requiresUnmeteredNetwork && Build.VERSION.SDK_INT >= 28) {
                // Wi-Fi transport (metered included). Cellular keeps the worker ENQUEUED instead of failing files.
                setRequiredNetworkRequest(
                    NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    NetworkType.CONNECTED,
                )
            } else {
                // Below API 28 (or explicit constrained run) any network; the worker's runtime Wi-Fi gate applies.
                setRequiredNetworkType(NetworkType.CONNECTED)
            }
            setRequiresCharging(runtimeConstraints.requiresExternalPower)
            setRequiresBatteryNotLow(runtimeConstraints.requiresBatteryNotLow)
        }.build()
        val request = OneTimeWorkRequestBuilder<SuperThumbnailWorker>()
            .setConstraints(constraints)
            .setInputData(input)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            uniqueName(connectionId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
        request.id
    }

    fun cancel(context: Context, connectionId: String): Operation =
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(uniqueName(connectionId))

    fun observe(context: Context, connectionId: String): LiveData<List<WorkInfo>> =
        WorkManager.getInstance(context.applicationContext)
            .getWorkInfosForUniqueWorkLiveData(uniqueName(connectionId))

    fun uniqueName(connectionId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(connectionId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "$UNIQUE_PREFIX${digest.take(24)}"
    }

    fun snapshot(data: Data): SuperThumbnailWorkSnapshot = SuperThumbnailWorkSnapshot(
        status = runCatching {
            SuperThumbnailWorkStatus.valueOf(data.getString(SuperThumbnailWorker.KEY_STATUS).orEmpty())
        }.getOrDefault(SuperThumbnailWorkStatus.FAILED),
        visitedItems = data.getInt(SuperThumbnailWorker.KEY_VISITED, 0).coerceAtLeast(0),
        generated = data.getInt(SuperThumbnailWorker.KEY_GENERATED, 0).coerceAtLeast(0),
        failed = data.getInt(SuperThumbnailWorker.KEY_FAILED, 0).coerceAtLeast(0),
        estimatedBytes = data.getLong(SuperThumbnailWorker.KEY_ESTIMATED_BYTES, 0L).coerceAtLeast(0L),
        budgetReached = data.getBoolean(SuperThumbnailWorker.KEY_BUDGET_REACHED, false),
    )

    fun snapshot(info: WorkInfo): SuperThumbnailWorkSnapshot = when (info.state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> emptySnapshot(SuperThumbnailWorkStatus.WAITING)
        WorkInfo.State.RUNNING -> snapshot(info.progress).let { progress ->
            if (info.progress.keyValueMap.isEmpty()) emptySnapshot(SuperThumbnailWorkStatus.RUNNING) else progress
        }
        WorkInfo.State.SUCCEEDED -> snapshot(info.outputData)
        WorkInfo.State.FAILED -> snapshot(info.outputData).copy(status = SuperThumbnailWorkStatus.FAILED)
        WorkInfo.State.CANCELLED -> emptySnapshot(SuperThumbnailWorkStatus.CANCELLED)
    }

    private fun emptySnapshot(status: SuperThumbnailWorkStatus) =
        SuperThumbnailWorkSnapshot(status, 0, 0, 0, 0L, false)

    internal const val TAG = "super-thumbnail"
    private const val UNIQUE_PREFIX = "super-thumbnail-"
}

class SuperThumbnailWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val connectionId = inputData.getString(KEY_CONNECTION_ID)
            ?.takeIf(String::isNotBlank) ?: return Result.failure(failureData())
        val requiresWifi = !inputData.getBoolean(KEY_ALLOWS_CONSTRAINED_RUN, false)
        // Eligibility wait (not a file failure): retry later without touching the network or the retry-by-error budget.
        if (requiresWifi && !SuperThumbnailNetworkPolicy.isWifi(applicationContext)) return Result.retry()
        val repository = ConnectionRepository(applicationContext)
        val connection = repository.load().firstOrNull { it.id == connectionId }
            ?: return Result.failure(failureData())
        val requestedRoot = inputData.getString(KEY_ROOT_PATH) ?: connection.normalizedRootPath
        val budget = SuperThumbnailBudget()
        // Wi-Fi transport only: no item/byte quotas. Re-evaluated per item so a switch to cellular restores the caps.
        val wifiBudget = SuperThumbnailBudget.unlimitedForWifi(budget.maxDepth)
        val budgetProvider: () -> SuperThumbnailBudget = {
            if (SuperThumbnailNetworkPolicy.isWifi(applicationContext)) wifiBudget else budget
        }
        val traversal = runCatching { SuperThumbnailTraversal(connection.normalizedRootPath, budget) }
            .getOrElse { return Result.failure(failureData()) }
        val root = runCatching { traversal.requireInsideRoot(requestedRoot) }
            .getOrElse { return Result.failure(failureData()) }
        repository.setSuperThumbnailRootPath(connection.id, root)
        val scopedTraversal = SuperThumbnailTraversal(root, budget)
        val password = repository.credentials.read(connection.id).orEmpty()
        val vaultOptions = SuperThumbnailVaultOptions(
            enabled = inputData.getBoolean(KEY_VAULT_ENABLED, true),
            timing = runCatching {
                SuperThumbnailVaultTiming.valueOf(inputData.getString(KEY_VAULT_TIMING).orEmpty())
            }.getOrDefault(SuperThumbnailVaultTiming.NOW),
        )
        val sessionKey = superThumbnailSessionKey(connection.id, root)
        val sessionStore = SuperThumbnailSessionStore(
            applicationContext.filesDir.resolve("super-thumbnail/session-v2.json")
        )
        val vault = SuperThumbnailVault(applicationContext.cacheDir.resolve("super-thumbnail-vault-work"))
        val resumeItemIds = if (inputData.getBoolean(KEY_RESUME_EXISTING, false)) {
            sessionStore.resumeItems(sessionKey).mapTo(hashSetOf(), RemoteFileItem::id).takeIf { it.isNotEmpty() }
        } else null

        return try {
            publish(SuperThumbnailWorkStatus.RUNNING, 0, 0, 0, 0L, false)
            RemoteFileServiceFactory.create(connection, password).use { service ->
                val cacheKeyStore = SuperThumbnailCacheKeyStore(
                    applicationContext.filesDir.resolve("super-thumbnail/cache-keys-v1.json")
                )
                RemoteThumbnailRepository(
                    applicationContext,
                    onCacheCommitted = cacheKeyStore::record,
                    unlimitedTraffic = { SuperThumbnailNetworkPolicy.isWifi(applicationContext) },
                ).use { thumbnails ->
                    coroutineScope {
                        val cooperation = if (vaultOptions.enabled &&
                            vaultOptions.timing == SuperThumbnailVaultTiming.NOW
                        ) SuperThumbnailCooperationSession(
                            vault, UUID.randomUUID().toString(), root, service,
                        ) else null
                        cooperation?.start(this)
                        try {
                            runTraversal(
                                connection, service, thumbnails, scopedTraversal, budgetProvider,
                                root, sessionKey, sessionStore, vault, vaultOptions, resumeItemIds,
                                cooperation, requiresWifi,
                            )
                        } finally {
                            cooperation?.stop()
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure(failureData())
        }
    }

    private suspend fun runTraversal(
        connection: RemoteConnection,
        service: com.armsone.nasfinder.network.RemoteFileService,
        thumbnails: RemoteThumbnailRepository,
        traversal: SuperThumbnailTraversal,
        budgetProvider: () -> SuperThumbnailBudget,
        rootPath: String,
        sessionKey: String,
        sessionStore: SuperThumbnailSessionStore,
        vault: SuperThumbnailVault,
        vaultOptions: SuperThumbnailVaultOptions,
        resumeItemIds: Set<String>?,
        cooperation: SuperThumbnailCooperationSession?,
        requiresWifi: Boolean,
    ): Result {
        var visited = 0
        var generated = 0
        var failed = 0
        var estimatedBytes = 0L
        var budgetReached = false
        var listingFailed = false
        val observedMedia = mutableListOf<RemoteFileItem>()
        val delayedVaultItems = mutableListOf<RemoteFileItem>()
        val visitedDirectoryNodes = mutableListOf<String>()
        val dirChildrenMap = mutableMapOf<String, List<RemoteFileItem>>()
        // Session items re-seen in current listings (before resume/budget filters) so the final
        // NAS check uses current metadata for the whole session, not only this run's subset.
        val sessionItemIds = if (vaultOptions.enabled) {
            sessionStore.observedItems(sessionKey).mapTo(hashSetOf()) { it.id }
        } else emptySet()
        val currentListed = linkedMapOf<String, RemoteFileItem>()

        while (true) {
            currentCoroutineContext().ensureActive()
            val directory = traversal.nextDirectory() ?: break
            visitedDirectoryNodes += directory.path
            val items = runCatching { service.list(directory.path) }.getOrElse {
                if (directory.depth == 0 && visited == 0) throw it
                listingFailed = true
                failed++
                publish(SuperThumbnailWorkStatus.RUNNING, visited, generated, failed, estimatedBytes, false)
                continue
            }
            val visibleItems = items.filterNot { it.name.startsWith('.') }
            dirChildrenMap[directory.path] = visibleItems
            if (sessionItemIds.isNotEmpty()) {
                visibleItems.forEach { listed ->
                    if (listed.id !in sessionItemIds) return@forEach
                    val listedPath = runCatching { traversal.requireInsideRoot(listed.path) }.getOrNull() ?: return@forEach
                    currentListed[listed.id] = listed.copy(path = listedPath)
                }
            }
            val completedInFolder = mutableListOf<RemoteFileItem>()
            itemLoop@ for (item in visibleItems) {
                currentCoroutineContext().ensureActive()
                if (!allowsThermalWork()) return Result.retry()
                if (requiresWifi && !SuperThumbnailNetworkPolicy.isWifi(applicationContext)) return Result.retry()
                val budget = budgetProvider()
                // Resume scans still need directories, but unrelated files must not consume
                // the bounded item budget before a saved pending item is reached.
                if (!item.isDirectory && resumeItemIds != null && item.id !in resumeItemIds) continue
                if (!budget.acceptsItem(visited)) {
                    budgetReached = true
                    break
                }
                visited++
                val safePath = runCatching { traversal.requireInsideRoot(item.path) }.getOrElse {
                    failed++
                    continue
                }
                if (item.isDirectory) {
                    val safeItem = item.copy(path = safePath)
                    observedMedia += safeItem
                    if (!traversal.enqueueDirectory(safePath, directory.depth) && directory.depth >= budget.maxDepth) {
                        budgetReached = true
                    }
                } else {
                    val estimate = SuperThumbnailEligibility.estimatedBytes(
                        item,
                        service.supportsRangeStreaming,
                    ) ?: continue
                    val safeItem = item.copy(path = safePath)
                    observedMedia += safeItem
                    sessionStore.prepare(
                        sessionKey = sessionKey,
                        rootPath = rootPath,
                        items = listOf(safeItem),
                        vaultEnabled = vaultOptions.enabled,
                    )
                    if (!budget.acceptsBytes(estimatedBytes, estimate)) {
                        budgetReached = true
                        continue
                    }
                    estimatedBytes += estimate
                    var wasCached = thumbnails.hasVaultCached(connection, safeItem)
                    if (!wasCached && vaultOptions.enabled) {
                        val restored = vault.restoredData(safeItem, rootPath, service)
                        if (restored != null && thumbnails.storeJpegData(connection, safeItem, restored)) {
                            wasCached = true
                        }
                    }
                    var cooperativeLease: SuperThumbnailCooperativeLease? = null
                    if (!wasCached && cooperation != null) {
                        var deferrals = 0
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val claim = cooperation.claim(safeItem)
                            when (claim.type) {
                                SuperThumbnailCooperativeClaimType.UNCOORDINATED -> break
                                SuperThumbnailCooperativeClaimType.ACQUIRED -> {
                                    cooperativeLease = claim.lease
                                    break
                                }
                                SuperThumbnailCooperativeClaimType.DEFERRED -> {
                                    val restored = vault.restoredData(safeItem, rootPath, service)
                                    if (restored != null && thumbnails.storeJpegData(connection, safeItem, restored)) {
                                        wasCached = true
                                        break
                                    }
                                    deferrals++
                                    if (deferrals >= MAX_COOPERATION_DEFERRALS) continue@itemLoop
                                    delay(COOPERATION_RETRY_MILLIS)
                                }
                            }
                        }
                    }
                    // A bad file is isolated: it counts as failed (PARTIAL result) and the remaining files keep going.
                    val cached = try {
                        if (wasCached) {
                            thumbnails.load(connection, safeItem, service)
                        } else {
                            thumbnails.generateSuperThumbnail(connection, safeItem, service)
                        }
                    } catch (error: CancellationException) {
                        cooperativeLease?.let { cooperation?.release(it) }
                        throw error
                    } catch (_: Throwable) {
                        null
                    }
                    if (cached == null) {
                        cooperativeLease?.let { cooperation?.release(it) }
                        cooperativeLease = null
                        failed++
                        sessionStore.recordFailure(sessionKey, safeItem, "썸네일을 생성하지 못했습니다.")
                    } else {
                        if (wasCached) {
                            sessionStore.markCached(sessionKey, safeItem)
                        } else {
                            generated++
                            sessionStore.recordSuccess(sessionKey, safeItem)
                        }
                        if (vaultOptions.enabled) sessionStore.markVaultPending(sessionKey, safeItem)
                        when (SuperThumbnailVaultPublishPolicy.mode(cooperativeLease != null)) {
                            SuperThumbnailVaultPublishMode.IMMEDIATE -> {
                                try {
                                    val result = vault.storeFolderWithRetry(listOf(safeItem), rootPath, service) { candidate ->
                                        thumbnails.loadJpegData(connection, candidate, service)
                                    }
                                    sessionStore.recordVaultResult(sessionKey, result)
                                } finally {
                                    cooperativeLease?.let { cooperation?.release(it) }
                                    cooperativeLease = null
                                }
                            }
                            SuperThumbnailVaultPublishMode.FOLDER_BATCH -> completedInFolder += safeItem
                        }
                    }
                }
                if (visited % PROGRESS_INTERVAL == 0) {
                    publish(SuperThumbnailWorkStatus.RUNNING, visited, generated, failed, estimatedBytes, budgetReached)
                }
            }
            if (vaultOptions.enabled && completedInFolder.isNotEmpty()) {
                if (vaultOptions.timing == SuperThumbnailVaultTiming.NOW) {
                    val result = vault.storeFolderWithRetry(completedInFolder, rootPath, service) { item ->
                        thumbnails.loadJpegData(connection, item, service)
                    }
                    sessionStore.recordVaultResult(sessionKey, result)
                } else {
                    delayedVaultItems += completedInFolder
                }
            }
            if (visited >= budgetProvider().maxItems) {
                budgetReached = true
                break
            }
        }
        if (vaultOptions.enabled && delayedVaultItems.isNotEmpty()) {
            delayedVaultItems.groupBy { item ->
                item.path.substringBeforeLast('/', "").ifBlank { if (item.path.startsWith('/')) "/" else "." }
            }
                .values.forEach { folderItems ->
                    currentCoroutineContext().ensureActive()
                    val result = vault.storeFolderWithRetry(folderItems, rootPath, service) { item ->
                        thumbnails.loadJpegData(connection, item, service)
                    }
                    sessionStore.recordVaultResult(sessionKey, result)
                }
        }
        // Bottom-up folder Super Thumbnail production
        val unblurredFolderSheets = mutableMapOf<String, Bitmap>()
        // Folders whose published sheet is fully backed by real tiles (or that are genuinely empty).
        val completeFolderPaths = hashSetOf<String>()
        try {
            for (dirPath in visitedDirectoryNodes.asReversed()) {
                val childFolders = dirChildrenMap[dirPath].orEmpty().filter { it.isDirectory }
                for (folderItem in childFolders) {
                    currentCoroutineContext().ensureActive()
                    if (resumeItemIds != null && folderItem.id !in resumeItemIds) continue
                    if (!allowsThermalWork()) return Result.retry()
                    val folderChildren = dirChildrenMap[folderItem.path] ?: continue
                    sessionStore.prepare(
                        sessionKey = sessionKey,
                        rootPath = rootPath,
                        items = listOf(folderItem),
                        vaultEnabled = vaultOptions.enabled,
                    )
                    if (folderChildren.isEmpty()) {
                        if (vaultOptions.enabled) {
                            vault.storeFolderEmptyMarkerWithRetry(folderItem, rootPath, service)
                        }
                        sessionStore.recordSuccess(sessionKey, folderItem)
                        completeFolderPaths += folderItem.path
                    } else {
                        // Always rebuild from unblurred run-scoped sources. A normal
                        // generation pass therefore upgrades legacy fixed-blur sheets at
                        // the same compatible filename, while parents never consume a
                        // persisted blurred child.
                        val selected = FolderSuperThumbnailPolicy.selectChildren(folderChildren)
                        val tileBitmaps = selected.associateWith { child ->
                            if (child.isDirectory) {
                                unblurredFolderSheets[child.path]
                            } else if (thumbnails.hasCached(connection, child)) {
                                try {
                                    thumbnails.load(connection, child, service)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Throwable) {
                                    null
                                }
                            } else null
                        }
                        // Never publish a sheet whose selected media/folder tile is missing (failed, unsupported or over budget).
                        val missingTile = selected.any { child ->
                            when {
                                child.isDirectory -> child.path !in completeFolderPaths &&
                                    dirChildrenMap[child.path]?.isEmpty() != true
                                child.isImage || child.isVideo -> tileBitmaps[child] == null
                                else -> false
                            }
                        }
                        if (missingTile) {
                            failed++
                            sessionStore.recordFailure(sessionKey, folderItem, "일부 하위 항목 썸네일이 없어 폴더 썸네일을 보류했습니다.")
                            continue
                        }
                        val rawSheet = FolderSuperThumbnailComposer.composeRawSheet(selected, tileBitmaps::get)
                        unblurredFolderSheets.put(folderItem.path, rawSheet)?.let { previous ->
                            if (!previous.isRecycled) previous.recycle()
                        }
                        val finalSheet = rawSheet.copy(rawSheet.config ?: Bitmap.Config.ARGB_8888, true)
                        FolderSuperThumbnailComposer.applySkinToneBlurIfNeeded(finalSheet)
                        val out = ByteArrayOutputStream()
                        if (finalSheet.compress(Bitmap.CompressFormat.JPEG, 88, out)) {
                            val jpegBytes = out.toByteArray()
                            finalSheet.recycle()
                            if (jpegBytes.isNotEmpty() && jpegBytes.size <= 8 * 1024 * 1024) {
                                thumbnails.storeJpegData(connection, folderItem, jpegBytes)
                                if (vaultOptions.enabled) {
                                    vault.storeFolderThumbnailWithRetry(folderItem, rootPath, service, jpegBytes)
                                }
                                generated++
                                completeFolderPaths += folderItem.path
                                sessionStore.recordSuccess(sessionKey, folderItem)
                            } else {
                                failed++
                                sessionStore.recordFailure(sessionKey, folderItem, "폴더 썸네일 데이터가 유효하지 않습니다.")
                            }
                        } else {
                            finalSheet.recycle()
                            failed++
                            sessionStore.recordFailure(sessionKey, folderItem, "폴더 썸네일 생성에 실패했습니다.")
                        }
                    }
                }
            }
        } finally {
            unblurredFolderSheets.values.forEach { if (!it.isRecycled) it.recycle() }
            unblurredFolderSheets.clear()
        }
        if (vaultOptions.enabled && (observedMedia.isNotEmpty() || currentListed.isNotEmpty())) {
            // Changed sources get fresh state first so an old completion is never kept.
            val sessionNow = sessionStore.observedItems(sessionKey).associateBy { it.id }
            val changed = currentListed.values.filter { current ->
                val old = sessionNow[current.id]
                old != null && (old.size != current.size || old.modifiedAt != current.modifiedAt)
            }
            if (changed.isNotEmpty()) {
                sessionStore.prepare(sessionKey = sessionKey, rootPath = rootPath, items = changed, vaultEnabled = true)
            }
            val verifyItems = LinkedHashMap<String, RemoteFileItem>().apply {
                currentListed.forEach { (id, item) -> put(id, item) }
                observedMedia.forEach { put(it.id, it) }
            }.values.toList()
            runCatching { vault.verifyItems(verifyItems, rootPath, service) }
                .onSuccess {
                    sessionStore.recordVaultVerification(sessionKey, it.storedItemIds, Instant.now(), it.checkedItemIds)
                }
        }
        if (resumeItemIds == null && !budgetReached && !listingFailed) {
            sessionStore.reconcileObserved(sessionKey, observedMedia)
        }
        val status = if (failed == 0 && !budgetReached) {
            SuperThumbnailWorkStatus.SUCCESS
        } else SuperThumbnailWorkStatus.PARTIAL
        val output = resultData(status, visited, generated, failed, estimatedBytes, budgetReached)
        setProgress(output)
        return Result.success(output)
    }

    private suspend fun allowsThermalWork(): Boolean {
        val thermalStatus = if (Build.VERSION.SDK_INT >= 29) {
            (applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus
        } else null
        return when (val decision = SuperThumbnailThermalPolicy.decision(Build.VERSION.SDK_INT, thermalStatus)) {
            SuperThumbnailThermalDecision.Continue -> true
            is SuperThumbnailThermalDecision.Pace -> {
                delay(decision.delayMillis)
                true
            }
            SuperThumbnailThermalDecision.RetryWhenCooler -> false
        }
    }

    private suspend fun publish(
        status: SuperThumbnailWorkStatus,
        visited: Int,
        generated: Int,
        failed: Int,
        estimatedBytes: Long,
        budgetReached: Boolean,
    ) {
        setProgress(resultData(status, visited, generated, failed, estimatedBytes, budgetReached))
    }

    private fun failureData(): Data = resultData(SuperThumbnailWorkStatus.FAILED, 0, 0, 0, 0, false)

    private fun resultData(
        status: SuperThumbnailWorkStatus,
        visited: Int,
        generated: Int,
        failed: Int,
        estimatedBytes: Long,
        budgetReached: Boolean,
    ): Data = workDataOf(
        KEY_STATUS to status.name,
        KEY_VISITED to visited,
        KEY_GENERATED to generated,
        KEY_FAILED to failed,
        KEY_ESTIMATED_BYTES to estimatedBytes,
        KEY_BUDGET_REACHED to budgetReached,
    )

    companion object {
        const val KEY_CONNECTION_ID = "connection_id"
        const val KEY_ROOT_PATH = "root_path"
        const val KEY_ALLOWS_CONSTRAINED_RUN = "allows_constrained_run"
        const val KEY_VAULT_ENABLED = "vault_enabled"
        const val KEY_VAULT_TIMING = "vault_timing"
        const val KEY_RESUME_EXISTING = "resume_existing"
        const val KEY_STATUS = "status"
        const val KEY_VISITED = "visited"
        const val KEY_GENERATED = "generated"
        const val KEY_FAILED = "failed"
        const val KEY_ESTIMATED_BYTES = "estimated_bytes"
        const val KEY_BUDGET_REACHED = "budget_reached"
        private const val PROGRESS_INTERVAL = 10
        private const val MAX_RETRIES = 2
        private const val MAX_COOPERATION_DEFERRALS = 20
        private const val COOPERATION_RETRY_MILLIS = 500L
    }
}
