package com.armsone.nasfinder.googlephotos

import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay

enum class GooglePhotosSkipReason { MISSING_MEDIA_FILE, MISSING_MIME_TYPE, TYPE_MISMATCH, VIDEO_NOT_READY, UNSUPPORTED_TYPE }
sealed interface GooglePhotosImportOutcome {
    data class Imported(val itemId: String) : GooglePhotosImportOutcome
    data class Skipped(val itemId: String, val reason: GooglePhotosSkipReason) : GooglePhotosImportOutcome
    data class Failed(val itemId: String) : GooglePhotosImportOutcome
}
data class GooglePhotosImportSummary(val outcomes: List<GooglePhotosImportOutcome>) {
    val importedCount get() = outcomes.count { it is GooglePhotosImportOutcome.Imported }
}

object GooglePhotosImportPolicy {
    fun validate(item: GooglePhotosMediaItem): GooglePhotosSkipReason? {
        val file = item.mediaFile ?: return GooglePhotosSkipReason.MISSING_MEDIA_FILE
        if (file.baseUrl.isBlank() || file.filename.isNullOrBlank()) return GooglePhotosSkipReason.MISSING_MEDIA_FILE
        val mime = file.mimeType?.takeIf(String::isNotBlank) ?: return GooglePhotosSkipReason.MISSING_MIME_TYPE
        return when (item.type) {
            GooglePhotosMediaType.PHOTO -> if (!mime.startsWith("image/")) GooglePhotosSkipReason.TYPE_MISMATCH else null
            GooglePhotosMediaType.VIDEO -> when { !mime.startsWith("video/") -> GooglePhotosSkipReason.TYPE_MISMATCH; file.videoStatus != GooglePhotosVideoStatus.READY -> GooglePhotosSkipReason.VIDEO_NOT_READY; else -> null }
            GooglePhotosMediaType.UNSPECIFIED -> GooglePhotosSkipReason.UNSUPPORTED_TYPE
        }
    }
}

class GooglePhotosImportCoordinator(
    private val download: suspend (GooglePhotosMediaItem) -> File,
    private val importFile: suspend (File, GooglePhotosMediaItem) -> Unit,
) {
    suspend fun import(items: List<GooglePhotosMediaItem>): GooglePhotosImportSummary {
        val outcomes = mutableListOf<GooglePhotosImportOutcome>()
        for (item in items) {
            currentCoroutineContext().ensureActive()
            val reason = GooglePhotosImportPolicy.validate(item)
            if (reason != null) { outcomes += GooglePhotosImportOutcome.Skipped(item.id, reason); continue }
            try { val file = download(item); currentCoroutineContext().ensureActive(); importFile(file, item); outcomes += GooglePhotosImportOutcome.Imported(item.id) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Throwable) { outcomes += GooglePhotosImportOutcome.Failed(item.id) }
        }
        return GooglePhotosImportSummary(outcomes)
    }
}

data class GooglePhotosRetryPolicy(val defaultPollSeconds: Double = 5.0, val inactivePollSeconds: Double = 1.0, val pickerOpenRetrySeconds: Double = .5, val maxPickerOpenAttempts: Int = 3) {
    fun pollSeconds(serverValue: Double?): Double = serverValue?.takeIf { it > 0 } ?: defaultPollSeconds
}

/** Lifecycle-neutral session poller. The UI supplies whether polling is currently allowed. */
class GooglePhotosPickerFlow(
    private val picker: GooglePhotosPickerClient,
    private val retry: GooglePhotosRetryPolicy = GooglePhotosRetryPolicy(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun awaitSelection(initial: GooglePhotosPickingSession, isActive: () -> Boolean): GooglePhotosPickingSession {
        var session = initial
        val timeoutAt = session.timeoutSeconds?.takeIf { it > 0 }?.let { nowMillis() + (it * 1_000).toLong() }
        while (!session.mediaItemsSet) {
            currentCoroutineContext().ensureActive()
            if (timeoutAt != null && nowMillis() >= timeoutAt) throw GooglePhotosPickerException.Http(408)
            val seconds = if (isActive()) retry.pollSeconds(session.pollIntervalSeconds) else retry.inactivePollSeconds
            sleep((seconds * 1_000).toLong())
            if (isActive()) session = picker.fetchSession(session.id)
        }
        return session
    }
}
