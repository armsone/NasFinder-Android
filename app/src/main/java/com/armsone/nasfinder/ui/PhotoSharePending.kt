package com.armsone.nasfinder.ui

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One shared photo/video whose bytes were copied into app-private storage the moment the share arrived. */
data class PendingShareItem(
    val file: File,
    val name: String,
    val mimeType: String,
    val isVideo: Boolean,
    /** The URI the sending app granted (identity only; its temporary permission may already be gone). */
    val originalUri: String,
    /** Position in the shared list; the original order is kept. */
    val order: Int,
    /** SHA-256 of this snapshot's bytes: a revision check for the same URI only, never a cross-source duplicate key. Null for legacy manifests. */
    val sha256: String? = null,
)

data class PendingShareFailure(val name: String, val originalUri: String, val reason: String)

/**
 * One incoming photo/video share waiting for the user's two-way choice (이미지 선별 / 영상 만들기).
 * Its files are this share's own durable copies; they survive app exit and permission expiry.
 */
data class PendingMediaShare(
    val id: String,
    val createdAt: Long,
    val images: List<PendingShareItem>,
    val videos: List<PendingShareItem>,
    /** Items that could not be copied (kept visible, never dropped silently). */
    val failures: List<PendingShareFailure>,
) {
    val itemCount: Int get() = images.size + videos.size
}

/**
 * True only when [incoming] is the same delivery as [existing]: every item was read (non-empty), has the same original
 * URI AND the same snapshot hash (a legacy item without a hash never matches), and the same URIs failed. The same URI alone is not
 * enough: a provider may reuse a URI for new content, and that content must be kept as its own batch.
 */
internal fun isSameShareRevision(existing: PendingMediaShare, incoming: PendingMediaShare): Boolean {
    val a = (existing.images + existing.videos).sortedBy { it.order }
    val b = (incoming.images + incoming.videos).sortedBy { it.order }
    if (a.size != b.size || a.isEmpty()) return false
    if (a.zip(b).any { (x, y) -> x.sha256 == null || x.sha256 != y.sha256 || x.originalUri != y.originalUri }) return false
    return existing.failures.map { it.originalUri }.toSet() == incoming.failures.map { it.originalUri }.toSet()
}

// A share whose items all failed to read is never folded into an existing batch by URI alone: a failed read says nothing
// about what the URI points to now, so that error batch stays visible and retryable.

internal data class ShareIntakeSource(val uri: Uri, val mimeType: String?, val isVideo: Boolean)

/**
 * Durable intake for ACTION_SEND / ACTION_SEND_MULTIPLE media. Every share gets its own folder
 * `share-pending-staging/<id>/` with a `manifest.json`; the folder only becomes visible (renamed from `<id>.tmp`)
 * after the manifest is complete, so a crash never leaves a half-readable batch. Only the share's own folder is
 * ever removed, and only on explicit cancel or after the chosen action has fully succeeded.
 */
internal object PendingShareStaging {
    private const val ROOT = "share-pending-staging"
    private const val MANIFEST = "manifest.json"
    private val ID_PATTERN = Regex("^[0-9a-f-]{36}$")
    /** Intakes in flight: their `.tmp` folders must not be swept as crash leftovers. */
    private val activeIntakes = AtomicInteger(0)

    private fun root(context: Context): File = File(context.filesDir, ROOT)

    private fun dir(context: Context, id: String): File {
        require(ID_PATTERN.matches(id)) { "잘못된 공유 묶음 식별자입니다." }
        return File(root(context), id)
    }

    fun delete(context: Context, id: String) {
        runCatching { dir(context, id).deleteRecursively() }
    }

    /** Copies every source now, in order. Per-item failures are recorded; one bad item never aborts the others. */
    suspend fun intake(context: Context, sources: List<ShareIntakeSource>): PendingMediaShare = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val finalDir = dir(context, id)
        val temp = File(root(context), "$id.tmp")
        activeIntakes.incrementAndGet()
        try {
        check(temp.mkdirs()) { "공유받은 파일을 보관할 공간을 만들지 못했습니다." }
        val items = mutableListOf<PendingShareItem>()
        val failures = mutableListOf<PendingShareFailure>()
        try {
            sources.forEachIndexed { index, source ->
                ensureActive()
                val name = PhotoSelectionStaging.displayName(context, source.uri, index)
                val mime = source.mimeType ?: runCatching { context.contentResolver.getType(source.uri) }.getOrNull()
                    ?: if (source.isVideo) "video/*" else "image/*"
                val extension = name.substringAfterLast('.', "").lowercase()
                    .takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isLetterOrDigit) } ?: if (source.isVideo) "mp4" else "img"
                val target = File(temp, "$index.$extension")
                val partial = File(temp, "$index.$extension.part")
                try {
                    val opened = context.contentResolver.openInputStream(source.uri) ?: throw java.io.IOException("파일을 열 수 없습니다.")
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    opened.use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    check(partial.length() > 0L) { "빈 파일입니다." }
                    check(partial.renameTo(target)) { "임시 사본을 저장하지 못했습니다." }
                    items += PendingShareItem(File(finalDir, target.name), name, mime, source.isVideo, source.uri.toString(), index, digest.digest().joinToString("") { "%02x".format(it) })
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    partial.delete()
                    failures += PendingShareFailure(name, source.uri.toString(), error.message ?: "사본을 만들지 못했습니다.")
                }
            }
            val share = PendingMediaShare(id, System.currentTimeMillis(), items.filter { !it.isVideo }, items.filter { it.isVideo }, failures)
            writeManifest(temp, share)
            check(temp.renameTo(finalDir)) { "공유 묶음을 저장하지 못했습니다." }
            share
        } catch (error: Throwable) {
            // Only this intake's own unfinished folder.
            temp.deleteRecursively()
            throw error
        }
        } finally {
            activeIntakes.decrementAndGet()
        }
    }

    /** Pending shares left by earlier runs, oldest first. Unfinished `.tmp` intakes (crash leftovers) are removed. */
    suspend fun loadAll(context: Context): List<PendingMediaShare> = withContext(Dispatchers.IO) {
        val entries = root(context).listFiles().orEmpty()
        if (activeIntakes.get() == 0) {
            entries.filter { it.isDirectory && it.name.endsWith(".tmp") }.forEach { runCatching { it.deleteRecursively() } }
        }
        entries.filter { it.isDirectory && ID_PATTERN.matches(it.name) }
            // A folder whose record is missing or unreadable is shown as an error (never silently omitted); its files
            // are kept until the user explicitly excludes it.
            .map { directory -> runCatching { readManifest(directory) }.getOrNull() ?: unreadableShare(directory) }
            .sortedBy { it.createdAt }
    }

    private fun unreadableShare(directory: File): PendingMediaShare = PendingMediaShare(
        directory.name, directory.lastModified(), emptyList(), emptyList(),
        listOf(PendingShareFailure("공유받은 묶음", "", "공유 기록을 읽지 못했습니다. 다시 확인하거나 이 묶음을 제외해 주세요. 파일은 지우지 않았습니다.")),
    )

    private fun writeManifest(directory: File, share: PendingMediaShare) {
        fun item(value: PendingShareItem) = JSONObject()
            .put("file", value.file.name).put("name", value.name).put("mime", value.mimeType)
            .put("video", value.isVideo).put("uri", value.originalUri).put("order", value.order)
            .apply { value.sha256?.let { put("sha256", it) } }
        val json = JSONObject()
            .put("id", share.id)
            .put("createdAt", share.createdAt)
            .put("items", JSONArray().also { array -> (share.images + share.videos).sortedBy { it.order }.forEach { array.put(item(it)) } })
            .put(
                "failures",
                JSONArray().also { array ->
                    share.failures.forEach { array.put(JSONObject().put("name", it.name).put("uri", it.originalUri).put("reason", it.reason)) }
                },
            )
        val temp = File(directory, "$MANIFEST.tmp")
        temp.writeText(json.toString(), Charsets.UTF_8)
        check(temp.renameTo(File(directory, MANIFEST))) { "공유 기록을 저장하지 못했습니다." }
    }

    private fun readManifest(directory: File): PendingMediaShare? {
        val manifest = File(directory, MANIFEST)
        if (!manifest.isFile) return null
        val json = JSONObject(manifest.readText(Charsets.UTF_8))
        val failures = mutableListOf<PendingShareFailure>()
        json.getJSONArray("failures").let { array ->
            for (i in 0 until array.length()) {
                array.getJSONObject(i).let { failures += PendingShareFailure(it.getString("name"), it.getString("uri"), it.getString("reason")) }
            }
        }
        val items = mutableListOf<PendingShareItem>()
        json.getJSONArray("items").let { array ->
            for (i in 0 until array.length()) {
                val entry = array.getJSONObject(i)
                val file = File(directory, entry.getString("file"))
                if (!file.isFile) {
                    failures += PendingShareFailure(entry.getString("name"), entry.getString("uri"), "임시 사본이 사라졌습니다.")
                } else {
                    items += PendingShareItem(
                        file, entry.getString("name"), entry.getString("mime"), entry.getBoolean("video"),
                        entry.getString("uri"), entry.getInt("order"), entry.optString("sha256", "").takeIf { it.isNotEmpty() },
                    )
                }
            }
        }
        return PendingMediaShare(
            json.getString("id"), json.getLong("createdAt"),
            items.filter { !it.isVideo }.sortedBy { it.order }, items.filter { it.isVideo }.sortedBy { it.order }, failures,
        )
    }
}
