package com.armsone.nasfinder.data

import com.armsone.nasfinder.model.RemoteFileItem
import com.armsone.nasfinder.network.RemoteFileService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID

enum class SuperThumbnailVaultTiming { NOW, LATER }

data class SuperThumbnailVaultOptions(
    val enabled: Boolean = true,
    val timing: SuperThumbnailVaultTiming = SuperThumbnailVaultTiming.NOW,
)

data class SuperThumbnailVaultStoreResult(
    val storedItemIds: Set<String>,
    val attemptedItemIds: Set<String>,
    val errorDescription: String?,
    val didAttempt: Boolean,
) {
    companion object {
        val EMPTY = SuperThumbnailVaultStoreResult(emptySet(), emptySet(), null, false)
    }
}

data class SuperThumbnailVaultRemovalResult(
    val removedFiles: Int,
    val removedFolders: Int,
    val failures: List<String>,
    val cancelled: Boolean,
)

enum class SuperThumbnailCooperativeClaimType { UNCOORDINATED, ACQUIRED, DEFERRED }

data class SuperThumbnailCooperativeLease(
    val itemId: String,
    val vaultPath: String,
    val directoryName: String,
    val token: String,
)

data class SuperThumbnailCooperativeClaim(
    val type: SuperThumbnailCooperativeClaimType,
    val lease: SuperThumbnailCooperativeLease? = null,
)

internal enum class SuperThumbnailVaultPublishMode { IMMEDIATE, FOLDER_BATCH }

internal object SuperThumbnailVaultPublishPolicy {
    fun mode(hasCooperativeLease: Boolean): SuperThumbnailVaultPublishMode =
        if (hasCooperativeLease) {
            SuperThumbnailVaultPublishMode.IMMEDIATE
        } else {
            SuperThumbnailVaultPublishMode.FOLDER_BATCH
        }
}

/** Hidden NAS-side JPEG cache using verified staging uploads and root-bounded traversal. */
class SuperThumbnailVault(private val workDirectory: File) {
    private val missingLeaseFirstSeen = mutableMapOf<String, Instant>()
    suspend fun restoredData(
        item: RemoteFileItem,
        rootPath: String,
        service: RemoteFileService,
    ): ByteArray? {
        val boundary = SuperThumbnailTraversal(rootPath, SuperThumbnailBudget())
        val parent = parentDirectory(item.path)
        boundary.requireInsideRoot(parent)
        val vaultPath = boundary.requireInsideRoot(append(DIRECTORY_NAME, parent))
        return runCatching {
            val stored = service.list(vaultPath).firstOrNull { !it.isDirectory && it.name == filename(item) }
                ?: return null
            val local = ownedTemporary("restore", ".jpg")
            try {
                service.download(stored, local)
                if (!local.isFile || local.length() !in 1..MAX_JPEG_BYTES) null else local.readBytes()
            } finally {
                local.delete()
            }
        }.getOrNull()
    }

    suspend fun registerWorker(
        workerId: String,
        rootPath: String,
        service: RemoteFileService,
        now: Instant = Instant.now(),
    ) {
        requireWorkerId(workerId)
        val boundary = SuperThumbnailTraversal(rootPath, SuperThumbnailBudget())
        val workersPath = ensureWorkersDirectory(boundary, service)
        val name = workerFilename(workerId)
        service.list(workersPath).firstOrNull { !it.isDirectory && it.name == name }
            ?.let { service.delete(listOf(it)) }
        uploadJson(
            workersPath,
            name,
            JSONObject()
                .put("workerID", workerId)
                .put("expiresAt", appleDate(now.plusSeconds(WORKER_LIFETIME_SECONDS))),
            service,
        )
    }

    suspend fun activeWorkerIds(
        rootPath: String,
        service: RemoteFileService,
        now: Instant = Instant.now(),
    ): Set<String> {
        val boundary = SuperThumbnailTraversal(rootPath, SuperThumbnailBudget())
        val workersPath = ensureWorkersDirectory(boundary, service)
        val active = linkedSetOf<String>()
        service.list(workersPath)
            .filter { !it.isDirectory && it.name.startsWith("worker-") && it.name.endsWith(".json") }
            .forEach { item ->
                currentCoroutineContext().ensureActive()
                val record = downloadJson(item, service)
                val workerId = record?.optString("workerID")?.takeIf { isValidWorkerId(it) }
                val expiresAt = record?.optDouble("expiresAt", Double.NaN)
                    ?.takeIf { it.isFinite() }?.let(::instantFromAppleDate)
                if (workerId != null && expiresAt != null && expiresAt.isAfter(now)) {
                    active += workerId
                } else {
                    runCatching { service.delete(listOf(item)) }
                }
            }
        return active
    }

    suspend fun unregisterWorker(
        workerId: String,
        rootPath: String,
        service: RemoteFileService,
    ) {
        if (!isValidWorkerId(workerId)) return
        val boundary = runCatching { SuperThumbnailTraversal(rootPath, SuperThumbnailBudget()) }.getOrNull()
            ?: return
        val workersPath = runCatching { ensureWorkersDirectory(boundary, service) }.getOrNull() ?: return
        service.list(workersPath).firstOrNull { !it.isDirectory && it.name == workerFilename(workerId) }
            ?.let { runCatching { service.delete(listOf(it)) } }
    }

    suspend fun claim(
        item: RemoteFileItem,
        workerId: String,
        rootPath: String,
        service: RemoteFileService,
        now: Instant = Instant.now(),
    ): SuperThumbnailCooperativeClaim {
        if (!isValidWorkerId(workerId)) {
            return SuperThumbnailCooperativeClaim(SuperThumbnailCooperativeClaimType.UNCOORDINATED)
        }
        val boundary = runCatching { SuperThumbnailTraversal(rootPath, SuperThumbnailBudget()) }.getOrNull()
            ?: return SuperThumbnailCooperativeClaim(SuperThumbnailCooperativeClaimType.UNCOORDINATED)
        val mediaDirectory = parentDirectory(item.path)
        return try {
            boundary.requireInsideRoot(mediaDirectory)
            val vaultPath = ensureVaultDirectory(mediaDirectory, boundary, service)
            var entries = service.list(vaultPath)
            if (entries.any { !it.isDirectory && it.name == filename(item) }) {
                return SuperThumbnailCooperativeClaim(SuperThumbnailCooperativeClaimType.DEFERRED)
            }
            val directoryName = leaseDirectoryName(item)
            entries.firstOrNull { it.isDirectory && it.name == directoryName }?.let { existing ->
                if (leaseIsActive(existing, service, now)) {
                    return SuperThumbnailCooperativeClaim(SuperThumbnailCooperativeClaimType.DEFERRED)
                }
                runCatching { service.delete(listOf(existing)) }
                entries = service.list(vaultPath)
                if (entries.any { !it.isDirectory && it.name == filename(item) }) {
                    return SuperThumbnailCooperativeClaim(SuperThumbnailCooperativeClaimType.DEFERRED)
                }
            }
            try {
                service.createFolder(vaultPath, directoryName)
            } catch (_: Throwable) {
                return SuperThumbnailCooperativeClaim(SuperThumbnailCooperativeClaimType.DEFERRED)
            }
            val leasePath = boundary.requireInsideRoot(append(directoryName, vaultPath))
            val token = UUID.randomUUID().toString()
            try {
                uploadJson(
                    leasePath,
                    LEASE_RECORD_NAME,
                    JSONObject()
                        .put("workerID", workerId)
                        .put("token", token)
                        .put("expiresAt", appleDate(now.plusSeconds(LEASE_LIFETIME_SECONDS))),
                    service,
                )
            } catch (error: Throwable) {
                service.list(vaultPath).firstOrNull { it.isDirectory && it.name == directoryName }
                    ?.let { runCatching { service.delete(listOf(it)) } }
                throw error
            }
            SuperThumbnailCooperativeClaim(
                SuperThumbnailCooperativeClaimType.ACQUIRED,
                SuperThumbnailCooperativeLease(item.id, vaultPath, directoryName, token),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            SuperThumbnailCooperativeClaim(SuperThumbnailCooperativeClaimType.UNCOORDINATED)
        }
    }

    suspend fun release(lease: SuperThumbnailCooperativeLease, service: RemoteFileService) {
        val directory = runCatching { service.list(lease.vaultPath) }.getOrNull()
            ?.firstOrNull { it.isDirectory && it.name == lease.directoryName } ?: return
        val recordItem = runCatching { service.list(directory.path) }.getOrNull()
            ?.firstOrNull { !it.isDirectory && it.name == LEASE_RECORD_NAME } ?: return
        val record = try {
            downloadJson(recordItem, service)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return
        }
        if (record?.optString("token") != lease.token) return
        runCatching { service.delete(listOf(directory)) }
        missingLeaseFirstSeen.remove(directory.path)
    }

    suspend fun storeFolder(
        items: List<RemoteFileItem>,
        rootPath: String,
        service: RemoteFileService,
        localData: suspend (RemoteFileItem) -> ByteArray?,
    ): SuperThumbnailVaultStoreResult {
        if (items.isEmpty()) return SuperThumbnailVaultStoreResult.EMPTY
        val attempted = items.mapTo(linkedSetOf(), RemoteFileItem::id)
        val stored = linkedSetOf<String>()
        val boundary = SuperThumbnailTraversal(rootPath, SuperThumbnailBudget())
        val mediaDirectory = parentDirectory(items.first().path)
        return try {
            boundary.requireInsideRoot(mediaDirectory)
            require(items.all { !it.isDirectory && parentDirectory(it.path) == mediaDirectory }) {
                "NAS Vault에는 같은 폴더의 파일만 함께 저장할 수 있습니다."
            }
            val vaultPath = ensureVaultDirectory(mediaDirectory, boundary, service)
            for (item in items) {
                currentCoroutineContext().ensureActive()
                val jpeg = localData(item) ?: continue
                if (jpeg.isEmpty() || jpeg.size > MAX_JPEG_BYTES) continue
                val finalName = filename(item)
                if (service.list(vaultPath).any { !it.isDirectory && it.name == finalName }) {
                    stored += item.id
                    continue
                }
                atomicallyUpload(jpeg, finalName, vaultPath, boundary, service)
                stored += item.id
            }
            SuperThumbnailVaultStoreResult(stored, attempted, null, true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            SuperThumbnailVaultStoreResult(
                stored,
                attempted,
                error.message?.take(240) ?: "NAS Vault 저장에 실패했습니다.",
                true,
            )
        }
    }

    suspend fun storeFolderWithRetry(
        items: List<RemoteFileItem>,
        rootPath: String,
        service: RemoteFileService,
        localData: suspend (RemoteFileItem) -> ByteArray?,
    ): SuperThumbnailVaultStoreResult {
        var latest = SuperThumbnailVaultStoreResult.EMPTY
        repeat(MAX_UPLOAD_ATTEMPTS) { attempt ->
            currentCoroutineContext().ensureActive()
            latest = storeFolder(items, rootPath, service, localData)
            if (latest.errorDescription == null) return latest
            if (attempt + 1 < MAX_UPLOAD_ATTEMPTS) delay(RETRY_DELAY_MILLIS)
        }
        return latest
    }

    suspend fun verifyStoredItemIds(
        items: List<RemoteFileItem>,
        rootPath: String,
        service: RemoteFileService,
    ): Set<String> {
        val boundary = SuperThumbnailTraversal(rootPath, SuperThumbnailBudget())
        val stored = linkedSetOf<String>()
        items.groupBy { parentDirectory(it.path) }.forEach { (parent, folderItems) ->
            currentCoroutineContext().ensureActive()
            boundary.requireInsideRoot(parent)
            val siblings = service.list(parent)
            if (siblings.none { it.isDirectory && it.name == DIRECTORY_NAME }) return@forEach
            val vaultPath = boundary.requireInsideRoot(append(DIRECTORY_NAME, parent))
            val names = service.list(vaultPath).asSequence().filterNot(RemoteFileItem::isDirectory).mapTo(hashSetOf()) { it.name }
            folderItems.filterTo(mutableListOf()) { filename(it) in names }.forEach { stored += it.id }
        }
        return stored
    }

    suspend fun removeVaults(
        rootPath: String,
        service: RemoteFileService,
    ): SuperThumbnailVaultRemovalResult {
        val boundary = SuperThumbnailTraversal(rootPath, SuperThumbnailBudget(maxItems = MAX_REMOVAL_ITEMS))
        val pending = ArrayDeque<String>().apply { add(boundary.root) }
        var removedFiles = 0
        var removedFolders = 0
        var visited = 0
        val failures = mutableListOf<String>()
        return try {
            while (pending.isNotEmpty() && visited < MAX_REMOVAL_ITEMS) {
                currentCoroutineContext().ensureActive()
                val directory = pending.removeFirst()
                val children = service.list(directory)
                visited += children.size
                for (child in children.filter(RemoteFileItem::isDirectory)) {
                    val safePath = runCatching { boundary.requireInsideRoot(child.path) }.getOrNull()
                    if (safePath == null) {
                        failures += child.path
                        continue
                    }
                    if (child.name == DIRECTORY_NAME) {
                        val vaultItems = service.list(safePath)
                        for (vaultItem in vaultItems.filterNot(RemoteFileItem::isDirectory)) {
                            runCatching { service.delete(listOf(vaultItem)) }
                                .onSuccess { removedFiles++ }
                                .onFailure { failures += vaultItem.path }
                        }
                        runCatching { service.delete(listOf(child)) }
                            .onSuccess { removedFolders++ }
                            .onFailure { failures += child.path }
                    } else if (!child.name.startsWith('.')) {
                        pending.addLast(safePath)
                    }
                }
            }
            if (pending.isNotEmpty()) failures += "탐색 한도에 도달했습니다."
            SuperThumbnailVaultRemovalResult(removedFiles, removedFolders, failures.distinct(), false)
        } catch (_: CancellationException) {
            SuperThumbnailVaultRemovalResult(removedFiles, removedFolders, failures.distinct(), true)
        }
    }

    private suspend fun ensureVaultDirectory(
        mediaDirectory: String,
        boundary: SuperThumbnailTraversal,
        service: RemoteFileService,
    ): String {
        val vaultPath = boundary.requireInsideRoot(append(DIRECTORY_NAME, mediaDirectory))
        if (service.list(mediaDirectory).any { it.isDirectory && it.name == DIRECTORY_NAME }) return vaultPath
        runCatching { service.createFolder(mediaDirectory, DIRECTORY_NAME) }.getOrElse { error ->
            if (service.list(mediaDirectory).none { it.isDirectory && it.name == DIRECTORY_NAME }) throw error
        }
        return vaultPath
    }

    private suspend fun ensureWorkersDirectory(
        boundary: SuperThumbnailTraversal,
        service: RemoteFileService,
    ): String {
        val rootVault = ensureVaultDirectory(boundary.root, boundary, service)
        val workersPath = boundary.requireInsideRoot(append(WORKERS_DIRECTORY_NAME, rootVault))
        if (service.list(rootVault).any { it.isDirectory && it.name == WORKERS_DIRECTORY_NAME }) return workersPath
        runCatching { service.createFolder(rootVault, WORKERS_DIRECTORY_NAME) }.getOrElse { error ->
            if (service.list(rootVault).none { it.isDirectory && it.name == WORKERS_DIRECTORY_NAME }) throw error
        }
        return workersPath
    }

    private suspend fun leaseIsActive(
        leaseDirectory: RemoteFileItem,
        service: RemoteFileService,
        now: Instant,
    ): Boolean {
        val recordItem = runCatching { service.list(leaseDirectory.path) }.getOrNull()
            ?.firstOrNull { !it.isDirectory && it.name == LEASE_RECORD_NAME }
        val expiresAt = recordItem?.let { downloadJson(it, service) }
            ?.optDouble("expiresAt", Double.NaN)?.takeIf { it.isFinite() }?.let(::instantFromAppleDate)
        if (expiresAt != null) {
            missingLeaseFirstSeen.remove(leaseDirectory.path)
            return expiresAt.isAfter(now)
        }
        leaseDirectory.modifiedAt?.let { return it.plusSeconds(LEASE_LIFETIME_SECONDS).isAfter(now) }
        val firstSeen = missingLeaseFirstSeen.getOrPut(leaseDirectory.path) { now }
        return firstSeen.plusSeconds(LEASE_LIFETIME_SECONDS).isAfter(now)
    }

    private suspend fun uploadJson(
        parent: String,
        name: String,
        json: JSONObject,
        service: RemoteFileService,
    ) {
        require(name.matches(Regex("[A-Za-z0-9._-]{1,160}")))
        val directory = ownedTemporaryDirectory("json")
        val local = File(directory, name).canonicalFile
        require(local.parentFile == directory.canonicalFile)
        try {
            FileOutputStream(local).use { output ->
                output.write(json.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            service.upload(parent, local)
            if (service.list(parent).none { !it.isDirectory && it.name == name }) {
                throw IllegalStateException("NAS Vault 협업 레코드를 확인하지 못했습니다.")
            }
        } finally {
            local.delete()
            directory.delete()
        }
    }

    private suspend fun downloadJson(item: RemoteFileItem, service: RemoteFileService): JSONObject? {
        val local = ownedTemporary("record", ".json")
        return try {
            service.download(item, local)
            if (!local.isFile || local.length() !in 1..MAX_RECORD_BYTES) null
            else runCatching { JSONObject(local.readText(Charsets.UTF_8)) }.getOrNull()
        } finally {
            local.delete()
        }
    }

    private suspend fun atomicallyUpload(
        jpeg: ByteArray,
        finalName: String,
        vaultPath: String,
        boundary: SuperThumbnailTraversal,
        service: RemoteFileService,
    ) {
        boundary.requireInsideRoot(vaultPath)
        val local = ownedTemporary(".upload-${UUID.randomUUID()}", ".tmp")
        var remoteTemporary: RemoteFileItem? = null
        try {
            FileOutputStream(local).use { output ->
                output.write(jpeg)
                output.fd.sync()
            }
            service.upload(vaultPath, local)
            val staging = service.list(vaultPath).firstOrNull { !it.isDirectory && it.name == local.name }
                ?: throw IllegalStateException("NAS Vault staging 업로드를 확인하지 못했습니다.")
            remoteTemporary = staging
            val current = service.list(vaultPath)
            if (current.any { !it.isDirectory && it.name == finalName }) {
                service.delete(listOf(staging))
                remoteTemporary = null
                return
            }
            service.rename(staging, finalName)
            repeat(VERIFY_ATTEMPTS) { attempt ->
                if (service.list(vaultPath).any { !it.isDirectory && it.name == finalName }) {
                    remoteTemporary = null
                    return
                }
                if (attempt + 1 < VERIFY_ATTEMPTS) delay(RETRY_DELAY_MILLIS)
            }
            throw IllegalStateException("NAS Vault rename 결과를 확인하지 못했습니다.")
        } finally {
            remoteTemporary?.let { runCatching { service.delete(listOf(it)) } }
            local.delete()
        }
    }

    private fun ownedTemporary(prefix: String, suffix: String): File {
        workDirectory.mkdirs()
        val canonicalRoot = workDirectory.canonicalFile
        val file = File.createTempFile(prefix.take(32).padEnd(3, '_'), suffix, canonicalRoot).canonicalFile
        require(file.parentFile == canonicalRoot && !Files.isSymbolicLink(file.toPath()))
        return file
    }

    private fun ownedTemporaryDirectory(prefix: String): File {
        workDirectory.mkdirs()
        val canonicalRoot = workDirectory.canonicalFile
        val directory = Files.createTempDirectory(canonicalRoot.toPath(), prefix.take(24)).toFile().canonicalFile
        require(directory.parentFile == canonicalRoot && !Files.isSymbolicLink(directory.toPath()))
        return directory
    }

    companion object {
        const val DIRECTORY_NAME = ".NasFinder-Vault"
        private const val ENGINE_VERSION = 1
        private const val MAX_JPEG_BYTES = 8L * 1024L * 1024L
        private const val MAX_UPLOAD_ATTEMPTS = 3
        private const val VERIFY_ATTEMPTS = 3
        private const val RETRY_DELAY_MILLIS = 250L
        private const val MAX_REMOVAL_ITEMS = 100_000
        const val WORKERS_DIRECTORY_NAME = ".workers-v1"
        const val LEASE_RECORD_NAME = ".owner.json"
        const val WORKER_LIFETIME_SECONDS = 90L
        const val LEASE_LIFETIME_SECONDS = 180L
        private const val APPLE_REFERENCE_EPOCH_SECONDS = 978_307_200.0
        private const val MAX_RECORD_BYTES = 64L * 1024L

        fun filename(item: RemoteFileItem): String {
            val normalizedName = Normalizer.normalize(item.name, Normalizer.Form.NFC)
            val identity = "engine=$ENGINE_VERSION|name=$normalizedName|size=${item.size}|modified=${item.modifiedAt?.toEpochMilli() ?: 0}"
            val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return "v$ENGINE_VERSION-$digest.jpg"
        }

        fun leaseDirectoryName(item: RemoteFileItem): String =
            ".claim-${filename(item).removeSuffix(".jpg")}"

        private fun workerFilename(workerId: String) = "worker-$workerId.json"

        private fun requireWorkerId(workerId: String) {
            require(isValidWorkerId(workerId)) { "올바르지 않은 Vault worker ID입니다." }
        }

        private fun isValidWorkerId(workerId: String): Boolean =
            workerId.matches(Regex("[A-Za-z0-9-]{1,64}"))

        private fun appleDate(instant: Instant): Double =
            instant.epochSecond.toDouble() + instant.nano / 1_000_000_000.0 - APPLE_REFERENCE_EPOCH_SECONDS

        private fun instantFromAppleDate(value: Double): Instant =
            Instant.ofEpochMilli(((value + APPLE_REFERENCE_EPOCH_SECONDS) * 1_000.0).toLong())

        private fun parentDirectory(path: String): String =
            path.substringBeforeLast('/', "").ifBlank { if (path.startsWith('/')) "/" else "." }

        private fun append(name: String, path: String): String = when (path) {
            "/" -> "/$name"
            "." -> "./$name"
            else -> "${path.trimEnd('/')}/$name"
        }
    }
}

class SuperThumbnailCooperationSession(
    private val vault: SuperThumbnailVault,
    private val workerId: String,
    private val rootPath: String,
    private val service: RemoteFileService,
) {
    private var heartbeat: Job? = null
    private var lastPeerRefresh = Instant.MIN
    private var cachedHasPeers = false

    suspend fun start(scope: CoroutineScope) {
        if (heartbeat != null) return
        runCatching { vault.registerWorker(workerId, rootPath, service) }.getOrElse { return }
        heartbeat = scope.launch {
            while (true) {
                delay(HEARTBEAT_INTERVAL_MILLIS)
                runCatching { vault.registerWorker(workerId, rootPath, service) }
            }
        }
        delay(PEER_DISCOVERY_DELAY_MILLIS)
        hasPeers(forceRefresh = true)
    }

    suspend fun claim(item: RemoteFileItem): SuperThumbnailCooperativeClaim {
        if (!hasPeers()) return SuperThumbnailCooperativeClaim(SuperThumbnailCooperativeClaimType.UNCOORDINATED)
        return vault.claim(item, workerId, rootPath, service)
    }

    suspend fun release(lease: SuperThumbnailCooperativeLease) = vault.release(lease, service)

    suspend fun stop() {
        heartbeat?.cancelAndJoin()
        heartbeat = null
        try {
            vault.unregisterWorker(workerId, rootPath, service)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // A stale worker record expires after 90 seconds and must not fail completed work.
        }
    }

    private suspend fun hasPeers(forceRefresh: Boolean = false): Boolean {
        val now = Instant.now()
        if (!forceRefresh && lastPeerRefresh != Instant.MIN &&
            now.isBefore(lastPeerRefresh.plusMillis(PEER_REFRESH_MILLIS))) return cachedHasPeers
        cachedHasPeers = runCatching { vault.activeWorkerIds(rootPath, service, now) }
            .getOrDefault(emptySet()).any { it != workerId }
        lastPeerRefresh = now
        return cachedHasPeers
    }

    private companion object {
        const val HEARTBEAT_INTERVAL_MILLIS = 30_000L
        const val PEER_REFRESH_MILLIS = 5_000L
        const val PEER_DISCOVERY_DELAY_MILLIS = 750L
    }
}
