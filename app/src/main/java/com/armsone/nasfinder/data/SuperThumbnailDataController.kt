package com.armsone.nasfinder.data

import android.content.Context
import androidx.work.WorkManager
import com.armsone.nasfinder.model.RemoteConnection
import com.armsone.nasfinder.network.RemoteFileServiceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

/** UI-facing facade for one location's persisted report, resume work, and NAS Vault lifecycle. */
class SuperThumbnailDataController(context: Context) {
    private val appContext = context.applicationContext
    private val connections = ConnectionRepository(appContext)
    private val sessions = SuperThumbnailSessionStore(
        appContext.filesDir.resolve("super-thumbnail/session-v2.json")
    )

    fun report(connectionId: String, rootPath: String): SuperThumbnailSessionReport? =
        sessions.report(validSessionKey(connectionId, rootPath))

    fun hasWorkToResume(connectionId: String, rootPath: String): Boolean =
        report(connectionId, rootPath)?.hasWorkToResume == true

    fun enqueue(
        connectionId: String,
        rootPath: String,
        allowsConstrainedRun: Boolean = false,
        vaultOptions: SuperThumbnailVaultOptions = SuperThumbnailVaultOptions(),
        resumeExisting: Boolean = false,
    ): UUID {
        val connection = requireConnection(connectionId)
        val safeRoot = validateRoot(connection, rootPath)
        return SuperThumbnailWorkController.enqueue(
            context = appContext,
            connectionId = connectionId,
            rootPath = safeRoot,
            allowsConstrainedRun = allowsConstrainedRun,
            vaultOptions = vaultOptions,
            resumeExisting = resumeExisting,
        )
    }

    suspend fun removeVaults(connectionId: String, rootPath: String): SuperThumbnailVaultRemovalResult {
        val connection = requireConnection(connectionId)
        val safeRoot = validateRoot(connection, rootPath)
        val sessionKey = superThumbnailSessionKey(connectionId, safeRoot)
        val vault = SuperThumbnailVault(appContext.cacheDir.resolve("super-thumbnail-vault-work"))
        val credential = connections.credentials.read(connectionId).orEmpty()
        RemoteFileServiceFactory.create(connection, credential).use { service ->
            val result = vault.removeVaults(safeRoot, service)
            if (!result.cancelled) {
                // Clear optimistic UPLOADED state first. A partial delete then upgrades only
                // files that a fresh NAS listing proves are still present.
                sessions.markVaultRemoved(sessionKey)
                if (result.failures.isNotEmpty()) {
                    val observed = sessions.observedItems(sessionKey)
                    if (observed.isNotEmpty()) {
                        runCatching { vault.verifyStoredItemIds(observed, safeRoot, service) }
                            .onSuccess { sessions.recordVaultVerification(sessionKey, it, Instant.now()) }
                    }
                }
            }
            return result
        }
    }

    suspend fun superCacheResetAvailability(): SuperThumbnailCacheResetAvailability {
        if (SuperThumbnailCacheResetCoordinator.isResetting()) {
            return SuperThumbnailCacheResetAvailability(
                SuperThumbnailCacheResetStatus.BLOCKED_RESET_IN_PROGRESS,
                activeWorkCount = 0,
            )
        }
        val active = runCatching { activeSuperThumbnailWorkCount() }.getOrElse {
            return SuperThumbnailCacheResetAvailability(
                SuperThumbnailCacheResetStatus.BLOCKED_WORK_STATE_UNKNOWN,
                activeWorkCount = 0,
            )
        }
        return SuperThumbnailCacheResetAvailability(
            status = if (active == 0) {
                SuperThumbnailCacheResetStatus.AVAILABLE
            } else {
                SuperThumbnailCacheResetStatus.BLOCKED_RUNNING_WORK
            },
            activeWorkCount = active,
        )
    }

    /**
     * Clears only cache entries recorded by Super Thumbnail plus its queue/report state.
     * Connections, credentials, source files, NAS Vaults, browser history and selected roots are untouched.
     */
    suspend fun resetSuperCache(): SuperThumbnailCacheResetResult {
        if (!SuperThumbnailCacheResetCoordinator.tryBegin()) {
            return SuperThumbnailCacheResetResult(
                status = SuperThumbnailCacheResetStatus.BLOCKED_RESET_IN_PROGRESS,
            )
        }
        try {
            val active = runCatching { activeSuperThumbnailWorkCount() }.getOrElse {
                return SuperThumbnailCacheResetResult(
                    status = SuperThumbnailCacheResetStatus.BLOCKED_WORK_STATE_UNKNOWN,
                    errors = listOf("실행 중인 Super Thumbnail 작업을 확인하지 못했습니다."),
                )
            }
            if (active > 0) {
                return SuperThumbnailCacheResetResult(
                    status = SuperThumbnailCacheResetStatus.BLOCKED_RUNNING_WORK,
                    activeWorkCount = active,
                )
            }
            val errors = mutableListOf<String>()
            val keyStore = SuperThumbnailCacheKeyStore(
                appContext.filesDir.resolve("super-thumbnail/cache-keys-v1.json")
            )
            var trackingReadable = true
            val trackedKeys = runCatching { keyStore.keys() }.getOrElse {
                trackingReadable = false
                errors += "Super Thumbnail 캐시 추적 기록을 읽지 못해 캐시 파일 삭제를 건너뛰었습니다."
                emptySet()
            }
            var removal = SuperThumbnailTrackedRemoval(0, 0, 0, emptySet())
            var trafficReset = false
            RemoteThumbnailRepository(appContext).use { thumbnails ->
                removal = runCatching { thumbnails.removeTrackedCache(trackedKeys) }
                    .getOrElse {
                        errors += "Super Thumbnail 캐시 파일 삭제에 실패했습니다."
                        SuperThumbnailTrackedRemoval(0, 0, 0, trackedKeys)
                    }
                trafficReset = thumbnails.resetTrafficBudget()
                if (!trafficReset) errors += "썸네일 네트워크 사용량 초기화가 보류되었습니다."
            }

            var trackingUpdated = false
            val resolvedKeys = trackedKeys - removal.failedKeys
            if (trackingReadable) {
                runCatching { keyStore.remove(resolvedKeys) }
                    .onSuccess { trackingUpdated = true }
                    .onFailure { errors += "Super Thumbnail 캐시 추적 기록 갱신에 실패했습니다." }
            }

            var clearedSessions = 0
            var sessionStateCleared = false
            runCatching { sessions.clearAll() }
                .onSuccess {
                    clearedSessions = it
                    sessionStateCleared = true
                }
                .onFailure { errors += "Super Thumbnail 작업 기록 초기화에 실패했습니다." }

            val completed = removal.failedKeys.isEmpty() && trackingUpdated && sessionStateCleared && trafficReset
            return SuperThumbnailCacheResetResult(
                status = if (completed) {
                    SuperThumbnailCacheResetStatus.COMPLETED
                } else {
                    SuperThumbnailCacheResetStatus.PARTIAL
                },
                trackedKeyCount = trackedKeys.size,
                removedFileCount = removal.removedFileCount,
                removedBytes = removal.removedBytes,
                alreadyMissingCount = removal.alreadyMissingCount,
                failedKeys = removal.failedKeys,
                clearedSessionCount = clearedSessions,
                sessionStateCleared = sessionStateCleared,
                trackingStateUpdated = trackingUpdated,
                trafficBudgetReset = trafficReset,
                errors = errors,
            )
        } finally {
            SuperThumbnailCacheResetCoordinator.end()
        }
    }

    private suspend fun activeSuperThumbnailWorkCount(): Int = withContext(Dispatchers.IO) {
        val states = WorkManager.getInstance(appContext)
            .getWorkInfosByTag(SuperThumbnailWorkController.TAG)
            .get()
            .map { it.state }
        SuperThumbnailCacheResetPolicy.activeWorkCount(states)
    }

    private fun requireConnection(connectionId: String): RemoteConnection {
        require(connectionId.isNotBlank()) { "연결 ID가 필요합니다." }
        return connections.load().firstOrNull { it.id == connectionId }
            ?: throw IllegalArgumentException("저장된 연결을 찾을 수 없습니다.")
    }

    private fun validateRoot(connection: RemoteConnection, rootPath: String): String =
        SuperThumbnailTraversal(connection.normalizedRootPath, SuperThumbnailBudget())
            .requireInsideRoot(rootPath)

    private fun validSessionKey(connectionId: String, rootPath: String): String {
        val connection = requireConnection(connectionId)
        return superThumbnailSessionKey(connectionId, validateRoot(connection, rootPath))
    }
}
