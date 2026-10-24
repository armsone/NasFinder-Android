package com.armsone.nasfinder.ui

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** One logical item the sender prepared: the stable wire id and the local source it was derived from. */
internal data class SenderPreparedItem(
    val itemId: String,
    val uri: String,
    val name: String,
    val byteLength: Long,
)

internal data class SenderTransferRecord(
    val transferId: String,
    /** SHA-256 of the sorted selected URIs: the same selection resolves to the same transfer until it completes. */
    val selectionKey: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Receiving app installation (from the first authenticated `ready`); retries must reach the same one. */
    val receiverId: String?,
    val items: List<SenderPreparedItem>,
    /** Items a verified receipt showed committed (informational; never grants deletion after a restart). */
    val confirmedItemIds: Set<String>,
    val complete: Boolean,
)

/**
 * Minimal sender-side transfer journal (wire contract §2.1/§2.3). It only makes a restarted or retried send of the
 * same selection reuse the same transfer/item ids and receiver, so the receiver recognises already committed items
 * instead of saving duplicates. It stores no QR token, no key, no receipt MAC, and deletion eligibility is never
 * restored from it: after a restart the receipt/report state is "unknown" until a fresh authenticated final receipt
 * is obtained for the re-verified sources. Records are small (one JSON file each) and expire 30 days after last use.
 */
internal class PhotoTransferSenderJournal(context: Context) {
    private val directory = File(context.applicationContext.noBackupFilesDir, "photo-transfer-sender").also { it.mkdirs() }
    private val lock = Any()

    /** The transfer id for [uriStrings]: the open (not completed) record of the same selection, else a new one. */
    fun resolve(uriStrings: List<String>): String = synchronized(lock) {
        val key = selectionKey(uriStrings)
        pruneLocked()
        val open = allLocked().filter { it.selectionKey == key && !it.complete }.maxByOrNull { it.updatedAt }
        if (open != null) return open.transferId
        val now = System.currentTimeMillis()
        val created = SenderTransferRecord(UUID.randomUUID().toString(), key, now, now, null, emptyList(), emptySet(), false)
        writeLocked(created)
        created.transferId
    }

    /**
     * Always a NEW transfer id for [uriStrings] (explicit "새로 보내기"): never reuses an open record, so the receiver
     * treats it as a separate transfer and the earlier transfer, its journal and its saved assets stay untouched.
     */
    fun startNew(uriStrings: List<String>): String = synchronized(lock) {
        val now = System.currentTimeMillis()
        val created = SenderTransferRecord(UUID.randomUUID().toString(), selectionKey(uriStrings), now, now, null, emptyList(), emptySet(), false)
        writeLocked(created)
        created.transferId
    }

    fun load(transferId: String): SenderTransferRecord? = synchronized(lock) { readLocked(transferId) }

    fun recordPrepared(transferId: String, items: List<SenderPreparedItem>) = update(transferId) { it.copy(items = items) }

    fun recordReceiver(transferId: String, receiverId: String) = update(transferId) { current ->
        require(current.receiverId == null || current.receiverId == receiverId) { "다른 수신 기기입니다." }
        current.copy(receiverId = receiverId)
    }

    fun recordOutcome(transferId: String, confirmedItemIds: Set<String>, complete: Boolean) =
        update(transferId) { it.copy(confirmedItemIds = it.confirmedItemIds + confirmedItemIds, complete = complete) }

    private fun update(transferId: String, change: (SenderTransferRecord) -> SenderTransferRecord) = synchronized(lock) {
        val current = readLocked(transferId) ?: return@synchronized
        writeLocked(change(current).copy(updatedAt = System.currentTimeMillis()))
    }

    private fun selectionKey(uriStrings: List<String>): String =
        MessageDigest.getInstance("SHA-256").digest(uriStrings.sorted().joinToString("\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun pruneLocked() {
        val limit = System.currentTimeMillis() - RETENTION_MILLIS
        allLocked().filter { it.updatedAt < limit }.forEach { File(directory, "${it.transferId}.json").delete() }
    }

    private fun allLocked(): List<SenderTransferRecord> =
        directory.listFiles { file -> file.name.endsWith(".json") }.orEmpty()
            .mapNotNull { readLocked(it.name.removeSuffix(".json")) }

    private fun readLocked(transferId: String): SenderTransferRecord? {
        if (!isValidTransferId(transferId)) return null
        val target = File(directory, "$transferId.json")
        if (!target.isFile) return null
        return runCatching {
            val json = JSONObject(String(AtomicFile(target).readFully(), Charsets.UTF_8))
            val items = json.getJSONArray("items").let { array ->
                (0 until array.length()).map { index ->
                    val entry = array.getJSONObject(index)
                    SenderPreparedItem(
                        entry.getString("itemId"), entry.getString("uri"), entry.optString("name", ""), entry.optLong("byteLength", -1L),
                    )
                }
            }
            val confirmed = json.getJSONArray("confirmed").let { array -> (0 until array.length()).map(array::getString).toSet() }
            SenderTransferRecord(
                transferId = json.getString("transferId"),
                selectionKey = json.getString("selectionKey"),
                createdAt = json.getLong("createdAt"),
                updatedAt = json.getLong("updatedAt"),
                receiverId = json.optString("receiverId", "").takeIf { it.isNotEmpty() },
                items = items,
                confirmedItemIds = confirmed,
                complete = json.optBoolean("complete", false),
            )
        }.getOrNull()
    }

    private fun writeLocked(record: SenderTransferRecord) {
        val items = JSONArray()
        record.items.forEach { item ->
            items.put(JSONObject().put("itemId", item.itemId).put("uri", item.uri).put("name", item.name).put("byteLength", item.byteLength))
        }
        val json = JSONObject()
            .put("transferId", record.transferId)
            .put("selectionKey", record.selectionKey)
            .put("createdAt", record.createdAt)
            .put("updatedAt", record.updatedAt)
            .put("items", items)
            .put("confirmed", JSONArray(record.confirmedItemIds.toList()))
            .put("complete", record.complete)
            .apply { record.receiverId?.let { put("receiverId", it) } }
        val atomic = AtomicFile(File(directory, "${record.transferId}.json"))
        val stream = atomic.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }

    private companion object {
        const val RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}
