package com.armsone.nasfinder.ui

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

internal enum class JournalItemState { STAGING, COMMITTED, FAILED }

internal data class JournalItem(
    val itemId: String,
    val state: JournalItemState,
    val inputs: Map<Int, String> = emptyMap(),
    val outputUri: String? = null,
    val expectedBytes: Long = -1L,
    val reason: String? = null,
    /** SHA-256 of the bytes this device stored (the conversion output), not of the wire components in [inputs]. Null for legacy records. */
    val outputSha256: String? = null,
)

internal enum class OutputVerdict { VERIFIED, PENDING, ABSENT, CHANGED, LEGACY_UNVERIFIABLE, UNKNOWN }

internal data class OutputObservation(
    val pending: Boolean,
    val trashed: Boolean,
    val size: Long,
    val inExpectedFolder: Boolean,
)

internal data class CommittedReview(val verified: List<JournalItem>, val unverified: Map<String, OutputVerdict>)

internal object ReceivedOutputCheck {
    /** Wire `reason` codes (failed items) for an earlier received output the receiver can no longer confirm. */
    const val OUT_MISSING = "outmissing"
    const val OUT_MODIFIED = "outmodified"
    const val OUT_UNVERIFIED = "outunverified"
    val OUTPUT_REASONS = setOf(OUT_MISSING, OUT_MODIFIED, OUT_UNVERIFIED)

    /** Failure reason for a verdict that is not VERIFIED; unknown/legacy/pending stay "unverified", never "missing". */
    fun failureReason(verdict: OutputVerdict): String = when (verdict) {
        OutputVerdict.ABSENT -> OUT_MISSING
        OutputVerdict.CHANGED -> OUT_MODIFIED
        else -> OUT_UNVERIFIED
    }

    /**
     * Decides whether the saved asset still is the one the journal recorded. [observation] null means the row
     * is absent (when [queryFailed] is false) or could not be read. Size alone never verifies: legacy records
     * without [JournalItem.outputSha256] are LEGACY_UNVERIFIABLE; [hashOf] null (unreadable) is UNKNOWN.
     */
    fun classify(
        item: JournalItem,
        observation: OutputObservation?,
        queryFailed: Boolean,
        hashOf: () -> String?,
    ): OutputVerdict {
        if (item.outputUri == null) return OutputVerdict.LEGACY_UNVERIFIABLE
        if (queryFailed) return OutputVerdict.UNKNOWN
        if (observation == null) return OutputVerdict.ABSENT
        if (observation.pending) return OutputVerdict.PENDING
        if (observation.trashed || !observation.inExpectedFolder) return OutputVerdict.CHANGED
        if (item.expectedBytes < 0L || observation.size != item.expectedBytes) return OutputVerdict.CHANGED
        val expected = item.outputSha256 ?: return OutputVerdict.LEGACY_UNVERIFIABLE
        val actual = hashOf() ?: return OutputVerdict.UNKNOWN
        return if (actual.equals(expected, ignoreCase = true)) OutputVerdict.VERIFIED else OutputVerdict.CHANGED
    }
}

internal data class JournalTransfer(
    val transferId: String,
    val itemCount: Int,
    val updatedAt: Long,
    val items: Map<String, JournalItem>,
)

/**
 * Receiver-side durable receipt journal (wire contract §2.3). One small JSON file per transfer under
 * noBackupFilesDir, replaced atomically. An item is COMMITTED only after its MediaStore row left IS_PENDING;
 * a crash between that update and the journal write leaves a STAGING entry that [recoverStaging] promotes
 * (the row is complete and verified) or removes (the row is still pending/absent).
 * Committed assets are never deleted by this class or because a receipt could not be sent; a receipt names a
 * committed item only after [reviewCommitted] re-verified the live asset against its recorded output hash.
 */
internal class PhotoTransferReceiptJournal(context: Context) {
    private val directory = File(context.applicationContext.noBackupFilesDir, "photo-transfer-journal").also { it.mkdirs() }
    private val lock = Any()

    fun receiverId(): String = synchronized(lock) {
        val file = File(directory, "receiver-id.txt")
        val existing = runCatching { file.readText(Charsets.US_ASCII).trim() }.getOrNull()
        if (existing != null && isValidTransferId(existing)) return existing
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        val created = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        writeAtomically(file, created.toByteArray(Charsets.US_ASCII))
        created
    }

    fun load(transferId: String): JournalTransfer? = synchronized(lock) { read(transferId) }

    /** Creates the record, or returns the existing one; a different [itemCount] for the same id is a conflict. */
    fun begin(transferId: String, itemCount: Int): JournalTransfer = synchronized(lock) {
        val existing = read(transferId)
        if (existing != null) {
            require(existing.itemCount == itemCount) { "같은 전송 식별자에 다른 항목 수가 지정되었습니다." }
            return existing
        }
        val created = JournalTransfer(transferId, itemCount, System.currentTimeMillis(), emptyMap())
        write(created)
        created
    }

    fun put(transferId: String, item: JournalItem) = synchronized(lock) {
        val current = read(transferId) ?: error("전송 기록이 없습니다.")
        write(current.copy(updatedAt = System.currentTimeMillis(), items = current.items + (item.itemId to item)))
    }

    fun remove(transferId: String, itemId: String): Unit = synchronized(lock) {
        val current = read(transferId) ?: return
        write(current.copy(updatedAt = System.currentTimeMillis(), items = current.items - itemId))
    }

    /**
     * Settles STAGING entries left by an interrupted run. Returns the record after recovery.
     * A row is promoted only when its size AND recorded output hash match. Only a row that is still pending
     * (this journal inserted it and it was never published) is deleted; a published, changed, legacy or unreadable
     * row is preserved, and the item is simply received again as a new copy.
     */
    fun recoverStaging(context: Context, transferId: String): JournalTransfer? {
        val record = load(transferId) ?: return null
        record.items.values.filter { it.state == JournalItemState.STAGING }.forEach { item ->
            when (verdictOf(context, item)) {
                OutputVerdict.VERIFIED -> put(transferId, item.copy(state = JournalItemState.COMMITTED))
                OutputVerdict.PENDING -> {
                    item.outputUri?.let { runCatching { context.contentResolver.delete(Uri.parse(it), null, null) } }
                    remove(transferId, item.itemId)
                }
                OutputVerdict.ABSENT, OutputVerdict.CHANGED, OutputVerdict.LEGACY_UNVERIFIABLE -> remove(transferId, item.itemId)
                OutputVerdict.UNKNOWN -> Unit
            }
        }
        return load(transferId)
    }

    /** Re-checks every COMMITTED asset against the live MediaStore row and recorded output hash before a receipt names it. */
    fun reviewCommitted(context: Context, record: JournalTransfer): CommittedReview {
        val verified = mutableListOf<JournalItem>()
        val unverified = linkedMapOf<String, OutputVerdict>()
        record.items.values.filter { it.state == JournalItemState.COMMITTED }.forEach { item ->
            val verdict = verdictOf(context, item)
            if (verdict == OutputVerdict.VERIFIED) {
                verified += item
            } else {
                unverified[item.itemId] = verdict
                // Receiver-local record of the failure; the stored media and the row itself are never touched.
                val reason = ReceivedOutputCheck.failureReason(verdict)
                if (item.reason != reason) runCatching { put(record.transferId, item.copy(reason = reason)) }
            }
        }
        return CommittedReview(verified, unverified)
    }

    fun verdictOf(context: Context, item: JournalItem): OutputVerdict {
        val resolver = context.contentResolver
        val uri = item.outputUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (uri == null) return ReceivedOutputCheck.classify(item, null, queryFailed = false) { null }
        var failed = false
        val observation = runCatching { observe(resolver, uri) }.getOrElse { failed = true; null }
        return ReceivedOutputCheck.classify(item, observation, failed) { hashOutput(resolver, uri) }
    }

    private fun hashOutput(resolver: ContentResolver, uri: Uri): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        } ?: return@runCatching null
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** Drops records not updated for [maxAgeMillis]; saved assets are never deleted. */
    fun pruneOlderThan(maxAgeMillis: Long) = synchronized(lock) {
        val limit = System.currentTimeMillis() - maxAgeMillis
        directory.listFiles { file -> file.name.endsWith(".json") }?.forEach { file ->
            val transferId = file.name.removeSuffix(".json")
            val record = read(transferId)
            if (record != null && record.updatedAt < limit) file.delete()
        }
    }

    /** Null when the row is gone; throws when it cannot be read. */
    private fun observe(resolver: ContentResolver, uri: Uri): OutputObservation? {
        val q = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val r = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val columns = buildList {
            add(MediaStore.MediaColumns.SIZE)
            if (q) { add(MediaStore.MediaColumns.IS_PENDING); add(MediaStore.MediaColumns.RELATIVE_PATH) }
            if (r) add(MediaStore.MediaColumns.IS_TRASHED)
        }.toTypedArray()
        val cursor = when {
            r -> resolver.query(
                uri, columns,
                Bundle().apply {
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                },
                null,
            )
            q -> {
                @Suppress("DEPRECATION")
                resolver.query(MediaStore.setIncludePending(uri), columns, null, null, null)
            }
            else -> resolver.query(uri, columns, null, null, null)
        } ?: error("query unavailable")
        return cursor.use {
            if (!it.moveToFirst()) return@use null
            fun intOf(name: String): Int = it.getColumnIndex(name).takeIf { index -> index >= 0 && !it.isNull(index) }?.let(it::getInt) ?: 0
            val relative = if (q) it.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH).takeIf { index -> index >= 0 && !it.isNull(index) }?.let(it::getString) else null
            OutputObservation(
                pending = q && intOf(MediaStore.MediaColumns.IS_PENDING) != 0,
                trashed = r && intOf(MediaStore.MediaColumns.IS_TRASHED) != 0,
                size = it.getLong(it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)),
                inExpectedFolder = relative == null || relative.startsWith("Pictures/NasFinder") || relative.startsWith("Movies/NasFinder"),
            )
        }
    }

    private fun file(transferId: String): File {
        require(isValidTransferId(transferId))
        return File(directory, "$transferId.json")
    }

    private fun read(transferId: String): JournalTransfer? {
        val target = file(transferId)
        if (!target.isFile) return null
        return runCatching {
            val json = JSONObject(String(AtomicFile(target).readFully(), Charsets.UTF_8))
            val items = linkedMapOf<String, JournalItem>()
            val array = json.getJSONArray("items")
            for (i in 0 until array.length()) {
                val entry = array.getJSONObject(i)
                val inputs = linkedMapOf<Int, String>()
                entry.optJSONArray("inputs")?.let { values ->
                    for (j in 0 until values.length()) {
                        val value = values.getJSONObject(j)
                        inputs[value.getInt("index")] = value.getString("sha256")
                    }
                }
                val item = JournalItem(
                    itemId = entry.getString("itemId"),
                    state = JournalItemState.valueOf(entry.getString("state")),
                    inputs = inputs,
                    outputUri = entry.optString("outputUri", "").takeIf { it.isNotEmpty() },
                    expectedBytes = entry.optLong("expectedBytes", -1L),
                    reason = entry.optString("reason", "").takeIf { it.isNotEmpty() },
                    outputSha256 = entry.optString("outputSha256", "").takeIf { it.isNotEmpty() },
                )
                items[item.itemId] = item
            }
            JournalTransfer(json.getString("transferId"), json.getInt("itemCount"), json.getLong("updatedAt"), items)
        }.getOrNull()
    }

    private fun write(record: JournalTransfer) {
        val array = JSONArray()
        record.items.values.forEach { item ->
            val inputs = JSONArray()
            item.inputs.toSortedMap().forEach { (index, sha) -> inputs.put(JSONObject().put("index", index).put("sha256", sha)) }
            array.put(
                JSONObject()
                    .put("itemId", item.itemId)
                    .put("state", item.state.name)
                    .put("inputs", inputs)
                    .put("expectedBytes", item.expectedBytes)
                    .apply {
                        item.outputUri?.let { put("outputUri", it) }
                        item.reason?.let { put("reason", it) }
                        item.outputSha256?.let { put("outputSha256", it) }
                    },
            )
        }
        val json = JSONObject()
            .put("transferId", record.transferId)
            .put("itemCount", record.itemCount)
            .put("updatedAt", record.updatedAt)
            .put("items", array)
        writeAtomically(file(record.transferId), json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }
}
