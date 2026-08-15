package com.armsone.nasfinder.data

import android.content.Context
import com.armsone.nasfinder.model.RemoteConnection
import com.armsone.nasfinder.network.RemoteFileServiceFactory
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
