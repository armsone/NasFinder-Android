package com.armsone.nasfinder.data

import com.armsone.nasfinder.model.RemoteFileItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

enum class SuperThumbnailMediaScope { VIDEOS_ONLY, VIDEOS_AND_PHOTOS }
enum class SuperThumbnailVaultStatus { WAITING_FOR_THUMBNAIL, PENDING_UPLOAD, UPLOADED, UPLOAD_FAILED }

data class SuperThumbnailFailureRecord(
    val itemId: String,
    val name: String,
    val extension: String,
    val size: Long?,
    val reason: String,
) {
    val id: String get() = itemId
}

data class SuperThumbnailVaultFolderReport(
    val path: String,
    val totalCount: Int,
    val uploadedCount: Int,
    val waitingThumbnailCount: Int,
    val pendingCount: Int,
    val failedCount: Int,
    val errorDescription: String?,
)

data class SuperThumbnailSessionReport(
    val successCounts: List<Int>,
    val photoSuccessCount: Int,
    val failures: List<SuperThumbnailFailureRecord>,
    val pendingCount: Int,
    val cachedCount: Int,
    val vaultFolders: List<SuperThumbnailVaultFolderReport>,
    val vaultLastVerifiedAt: Instant?,
    val mediaScope: SuperThumbnailMediaScope,
) {
    val successfulCount: Int get() = successCounts.sum() + photoSuccessCount
    val vaultUploadedCount: Int get() = vaultFolders.sumOf { it.uploadedCount }
    val vaultPendingCount: Int get() = vaultFolders.sumOf { it.pendingCount }
    val vaultFailedCount: Int get() = vaultFolders.sumOf { it.failedCount }
    val hasWorkToResume: Boolean get() = pendingCount > 0 || failures.isNotEmpty() || vaultPendingCount > 0 || vaultFailedCount > 0
}

/** App-private, credential-free resume state keyed by `connectionId|rootPath`. */
class SuperThumbnailSessionStore(private val storageFile: File) {
    fun prepare(
        sessionKey: String,
        rootPath: String,
        items: List<RemoteFileItem>,
        mediaScope: SuperThumbnailMediaScope = SuperThumbnailMediaScope.VIDEOS_AND_PHOTOS,
        vaultEnabled: Boolean,
    ) = update { sessions ->
        val session = sessions.getOrPut(sessionKey) { Session(rootPath = rootPath) }
        session.rootPath = rootPath
        session.mediaScope = mediaScope
        items.forEach { item ->
            val signature = signature(item)
            val previous = session.items[item.id]
            session.items[item.id] = if (previous?.signature == signature) {
                previous.copy(item = item)
            } else {
                ItemState(
                    item = item,
                    signature = signature,
                    vaultStatus = if (vaultEnabled) SuperThumbnailVaultStatus.WAITING_FOR_THUMBNAIL else null,
                )
            }
        }
    }

    fun markCached(sessionKey: String, item: RemoteFileItem) = mutateItem(sessionKey, item) {
        it.copy(outcome = Outcome.CACHED, failure = null)
    }

    fun recordSuccess(sessionKey: String, item: RemoteFileItem, attempt: Int = 0, photo: Boolean = item.isImage) =
        mutateItem(sessionKey, item) {
            it.copy(
                outcome = if (photo) Outcome.PHOTO_SUCCESS else Outcome.SUCCESS,
                successAttempt = attempt.coerceIn(0, 2),
                failure = null,
            )
        }

    fun recordFailure(sessionKey: String, item: RemoteFileItem, reason: String) = mutateItem(sessionKey, item) {
        it.copy(
            outcome = Outcome.FAILURE,
            successAttempt = 2,
            failure = SuperThumbnailFailureRecord(
                itemId = item.id,
                name = item.name,
                extension = item.extension.uppercase(),
                size = item.size.takeIf { size -> size >= 0 },
                reason = reason,
            ),
        )
    }

    fun markVaultPending(sessionKey: String, item: RemoteFileItem) = mutateItem(sessionKey, item) {
        if (it.vaultStatus == SuperThumbnailVaultStatus.UPLOADED) it
        else it.copy(vaultStatus = SuperThumbnailVaultStatus.PENDING_UPLOAD, vaultError = null)
    }

    fun recordVaultResult(sessionKey: String, result: SuperThumbnailVaultStoreResult) = update { sessions ->
        val session = sessions[sessionKey] ?: return@update
        result.attemptedItemIds.forEach { itemId ->
            val state = session.items[itemId] ?: return@forEach
            session.items[itemId] = when {
                itemId in result.storedItemIds -> state.copy(
                    vaultStatus = SuperThumbnailVaultStatus.UPLOADED,
                    vaultError = null,
                )
                result.errorDescription != null -> state.copy(
                    vaultStatus = SuperThumbnailVaultStatus.UPLOAD_FAILED,
                    vaultError = result.errorDescription,
                )
                else -> state.copy(
                    vaultStatus = SuperThumbnailVaultStatus.PENDING_UPLOAD,
                    vaultError = null,
                )
            }
        }
    }

    fun recordVaultVerification(sessionKey: String, storedItemIds: Set<String>, verifiedAt: Instant) =
        update { sessions ->
            val session = sessions[sessionKey] ?: return@update
            session.items.replaceAll { itemId, state ->
                when {
                    state.vaultStatus == null -> state
                    itemId in storedItemIds -> state.copy(vaultStatus = SuperThumbnailVaultStatus.UPLOADED, vaultError = null)
                    state.vaultStatus == SuperThumbnailVaultStatus.UPLOADED -> state.copy(
                        vaultStatus = SuperThumbnailVaultStatus.PENDING_UPLOAD,
                        vaultError = null,
                    )
                    else -> state
                }
            }
            session.vaultLastVerifiedAt = verifiedAt
        }

    fun markVaultRemoved(sessionKey: String, verifiedAt: Instant = Instant.now()) = update { sessions ->
        val session = sessions[sessionKey] ?: return@update
        session.items.replaceAll { _, state ->
            if (state.vaultStatus == null || state.vaultStatus == SuperThumbnailVaultStatus.WAITING_FOR_THUMBNAIL) state
            else state.copy(vaultStatus = SuperThumbnailVaultStatus.PENDING_UPLOAD, vaultError = null)
        }
        session.vaultLastVerifiedAt = verifiedAt
    }

    /** Removes deleted or changed entries only after a complete, non-resume traversal. */
    fun reconcileObserved(sessionKey: String, observedItems: List<RemoteFileItem>) = update { sessions ->
        val session = sessions[sessionKey] ?: return@update
        val signatures = observedItems.associate { it.id to signature(it) }
        session.items.entries.removeAll { (itemId, state) -> signatures[itemId] != state.signature }
    }

    fun report(sessionKey: String): SuperThumbnailSessionReport? = synchronized(FILE_LOCK) {
        val session = load()[sessionKey] ?: return@synchronized null
        val states = session.items.values
        val successCounts = (0..2).map { attempt ->
            states.count { it.outcome == Outcome.SUCCESS && it.successAttempt == attempt }
        }
        val vaultFolders = states.filter { it.vaultStatus != null }
            .groupBy { parentDirectory(it.item.path) }
            .map { (path, folderStates) ->
                SuperThumbnailVaultFolderReport(
                    path = path,
                    totalCount = folderStates.size,
                    uploadedCount = folderStates.count { it.vaultStatus == SuperThumbnailVaultStatus.UPLOADED },
                    waitingThumbnailCount = folderStates.count { it.vaultStatus == SuperThumbnailVaultStatus.WAITING_FOR_THUMBNAIL },
                    pendingCount = folderStates.count { it.vaultStatus == SuperThumbnailVaultStatus.PENDING_UPLOAD },
                    failedCount = folderStates.count { it.vaultStatus == SuperThumbnailVaultStatus.UPLOAD_FAILED },
                    errorDescription = folderStates.firstNotNullOfOrNull { it.vaultError },
                )
            }.sortedBy { it.path }
        SuperThumbnailSessionReport(
            successCounts = successCounts,
            photoSuccessCount = states.count { it.outcome == Outcome.PHOTO_SUCCESS },
            failures = states.mapNotNull { it.failure }.sortedBy { it.name },
            pendingCount = states.count { it.outcome == Outcome.PENDING },
            cachedCount = states.count { it.outcome == Outcome.CACHED },
            vaultFolders = vaultFolders,
            vaultLastVerifiedAt = session.vaultLastVerifiedAt,
            mediaScope = session.mediaScope,
        )
    }

    fun resumeItems(sessionKey: String): List<RemoteFileItem> = synchronized(FILE_LOCK) {
        load()[sessionKey]?.items?.values.orEmpty()
            .filter {
                it.outcome == Outcome.PENDING || it.outcome == Outcome.FAILURE ||
                    (it.vaultStatus != null && it.vaultStatus != SuperThumbnailVaultStatus.UPLOADED)
            }
            .map(ItemState::item)
            .sortedBy(RemoteFileItem::path)
    }

    fun observedItems(sessionKey: String): List<RemoteFileItem> = synchronized(FILE_LOCK) {
        load()[sessionKey]?.items?.values.orEmpty().map(ItemState::item).sortedBy(RemoteFileItem::path)
    }

    fun rootPath(sessionKey: String): String? = synchronized(FILE_LOCK) { load()[sessionKey]?.rootPath }

    fun clear(sessionKey: String) = update { it.remove(sessionKey) }

    fun clearConnection(connectionId: String) = update { sessions ->
        val prefix = "$connectionId|"
        sessions.keys.removeAll { it.startsWith(prefix) }
    }

    fun clearAll(): Int = synchronized(FILE_LOCK) {
        val count = load().size
        save(emptyMap())
        count
    }

    private fun mutateItem(sessionKey: String, item: RemoteFileItem, change: (ItemState) -> ItemState) = update { sessions ->
        val session = sessions[sessionKey] ?: return@update
        val state = session.items[item.id] ?: return@update
        if (state.signature == signature(item)) session.items[item.id] = change(state.copy(item = item))
    }

    private fun update(change: (MutableMap<String, Session>) -> Unit) = synchronized(FILE_LOCK) {
        val sessions = load()
        change(sessions)
        save(sessions)
    }

    private fun load(): MutableMap<String, Session> = runCatching {
        if (!storageFile.isFile) return@runCatching mutableMapOf()
        val root = JSONObject(storageFile.readText())
        val sessions = mutableMapOf<String, Session>()
        root.optJSONObject("sessions")?.let { values ->
            values.keys().forEach { key -> sessions[key] = decodeSession(values.getJSONObject(key)) }
        }
        sessions
    }.getOrElse { mutableMapOf() }

    private fun save(sessions: Map<String, Session>) {
        storageFile.parentFile?.mkdirs()
        val values = JSONObject().apply { sessions.forEach { (key, value) -> put(key, encodeSession(value)) } }
        val temporary = File(storageFile.parentFile, ".${storageFile.name}.part")
        temporary.writeText(JSONObject().put("version", 2).put("sessions", values).toString())
        try {
            Files.move(temporary.toPath(), storageFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), storageFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    private fun encodeSession(session: Session) = JSONObject()
        .put("rootPath", session.rootPath)
        .put("mediaScope", session.mediaScope.name)
        .put("vaultLastVerifiedAt", session.vaultLastVerifiedAt?.toEpochMilli())
        .put("items", JSONArray().apply { session.items.values.forEach { put(encodeItemState(it)) } })

    private fun decodeSession(json: JSONObject): Session {
        val session = Session(
            rootPath = json.optString("rootPath", "/"),
            mediaScope = runCatching { SuperThumbnailMediaScope.valueOf(json.optString("mediaScope")) }
                .getOrDefault(SuperThumbnailMediaScope.VIDEOS_AND_PHOTOS),
            vaultLastVerifiedAt = json.optLong("vaultLastVerifiedAt", 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli),
        )
        val items = json.optJSONArray("items") ?: JSONArray()
        for (index in 0 until items.length()) decodeItemState(items.getJSONObject(index))?.let { session.items[it.item.id] = it }
        return session
    }

    private fun encodeItemState(state: ItemState) = JSONObject()
        .put("signature", state.signature)
        .put("outcome", state.outcome.name)
        .put("successAttempt", state.successAttempt)
        .put("vaultStatus", state.vaultStatus?.name)
        .put("vaultError", state.vaultError)
        .put("item", JSONObject()
            .put("id", state.item.id).put("name", state.item.name).put("path", state.item.path)
            .put("directory", state.item.isDirectory).put("size", state.item.size)
            .put("modifiedAt", state.item.modifiedAt?.toEpochMilli()).put("mimeType", state.item.mimeType)
            .put("thumbnailUrl", state.item.thumbnailUrl))
        .also { json ->
            state.failure?.let { failure ->
                json.put("failure", JSONObject().put("itemId", failure.itemId).put("name", failure.name)
                    .put("extension", failure.extension).put("size", failure.size).put("reason", failure.reason))
            }
        }

    private fun decodeItemState(json: JSONObject): ItemState? = runCatching {
        val value = json.getJSONObject("item")
        val item = RemoteFileItem(
            id = value.getString("id"), name = value.getString("name"), path = value.getString("path"),
            isDirectory = value.optBoolean("directory"), size = value.optLong("size"),
            modifiedAt = value.optLong("modifiedAt", 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli),
            mimeType = value.optString("mimeType").takeIf { it.isNotBlank() && it != "null" },
            thumbnailUrl = value.optString("thumbnailUrl").takeIf { it.isNotBlank() && it != "null" },
        )
        val failure = json.optJSONObject("failure")?.let {
            SuperThumbnailFailureRecord(
                it.getString("itemId"), it.getString("name"), it.optString("extension"),
                it.optLong("size", -1).takeIf { size -> size >= 0 }, it.optString("reason"),
            )
        }
        ItemState(
            item = item,
            signature = json.getString("signature"),
            outcome = runCatching { Outcome.valueOf(json.optString("outcome")) }.getOrDefault(Outcome.PENDING),
            successAttempt = json.optInt("successAttempt", 0).coerceIn(0, 2),
            failure = failure,
            vaultStatus = json.optString("vaultStatus").takeIf { it.isNotBlank() && it != "null" }
                ?.let { SuperThumbnailVaultStatus.valueOf(it) },
            vaultError = json.optString("vaultError").takeIf { it.isNotBlank() && it != "null" },
        )
    }.getOrNull()

    private fun signature(item: RemoteFileItem): String = "${item.id}|${item.size}|${item.modifiedAt?.toEpochMilli() ?: 0}"
    private fun parentDirectory(path: String): String = path.substringBeforeLast('/', "").ifBlank { if (path.startsWith('/')) "/" else "." }

    private enum class Outcome { PENDING, CACHED, SUCCESS, PHOTO_SUCCESS, FAILURE }
    private data class ItemState(
        val item: RemoteFileItem,
        val signature: String,
        val outcome: Outcome = Outcome.PENDING,
        val successAttempt: Int = 0,
        val failure: SuperThumbnailFailureRecord? = null,
        val vaultStatus: SuperThumbnailVaultStatus? = null,
        val vaultError: String? = null,
    )
    private data class Session(
        var rootPath: String,
        var mediaScope: SuperThumbnailMediaScope = SuperThumbnailMediaScope.VIDEOS_AND_PHOTOS,
        var vaultLastVerifiedAt: Instant? = null,
        val items: MutableMap<String, ItemState> = mutableMapOf(),
    )

    private companion object { val FILE_LOCK = Any() }
}

fun superThumbnailSessionKey(connectionId: String, rootPath: String): String = "$connectionId|$rootPath"
