package com.armsone.nasfinder.data

import android.content.Context
import com.armsone.nasfinder.model.RemoteFileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Private cache for remote files prepared for FileProvider grants.
 * Entries expire after seven days and least-recently-used entries are removed
 * whenever the cache exceeds 512 MiB.
 */
class DownloadCache(context: Context) {
    private val root = File(context.cacheDir, "shares").apply { mkdirs() }.canonicalFile
    private val lock = Mutex()

    suspend fun resolve(
        connectionId: String,
        item: RemoteFileItem,
        progress: (completed: Long, total: Long) -> Unit,
        download: suspend (destination: File, progress: (Long, Long) -> Unit) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        require(!item.isDirectory) { "폴더는 미리보거나 공유할 수 없습니다." }
        lock.withLock {
            cleanUp()
            val entryDirectory = File(root, cacheKey(connectionId, item)).canonicalFile
            require(entryDirectory.parentFile == root) { "안전하지 않은 캐시 경로입니다." }
            val destination = File(entryDirectory, safeFilename(item.name)).canonicalFile
            require(destination.parentFile == entryDirectory) { "안전하지 않은 파일명입니다." }

            if (destination.isFile) {
                destination.setLastModified(System.currentTimeMillis())
                entryDirectory.setLastModified(System.currentTimeMillis())
                progress(destination.length(), destination.length())
                return@withLock destination
            }

            check(entryDirectory.mkdirs() || entryDirectory.isDirectory) {
                "다운로드 캐시를 만들 수 없습니다."
            }
            val partial = File(entryDirectory, ".download.part")
            partial.delete()
            try {
                download(partial, progress)
                check(partial.isFile) { "다운로드한 파일을 찾을 수 없습니다." }
                if (!partial.renameTo(destination)) {
                    partial.copyTo(destination, overwrite = true)
                    partial.delete()
                }
                destination.setLastModified(System.currentTimeMillis())
                entryDirectory.setLastModified(System.currentTimeMillis())
                cleanUp(excluding = destination)
                destination
            } catch (error: Throwable) {
                partial.delete()
                if (entryDirectory.listFiles().isNullOrEmpty()) entryDirectory.delete()
                throw error
            }
        }
    }

    private fun cleanUp(excluding: File? = null) {
        val now = System.currentTimeMillis()
        root.listFiles().orEmpty()
            .filter(File::isDirectory)
            .filter { directory ->
                directory.walkTopDown().filter(File::isFile).maxOfOrNull(File::lastModified)
                    ?.let { now - it > MAX_AGE_MILLIS } ?: true
            }
            .forEach { it.deleteRecursively() }

        val entries = root.listFiles().orEmpty()
            .filter(File::isDirectory)
            .map { directory ->
                val files = directory.walkTopDown().filter(File::isFile).toList()
                CacheEntry(
                    directory = directory,
                    bytes = files.sumOf(File::length),
                    lastAccess = files.maxOfOrNull(File::lastModified) ?: directory.lastModified(),
                )
            }
            .sortedBy(CacheEntry::lastAccess)
            .toMutableList()
        var bytes = entries.sumOf(CacheEntry::bytes)
        for (entry in entries) {
            if (bytes <= MAX_BYTES) break
            if (excluding != null && excluding.path.startsWith(entry.directory.path + File.separator)) {
                continue
            }
            if (entry.directory.deleteRecursively()) bytes -= entry.bytes
        }
    }

    private fun cacheKey(connectionId: String, item: RemoteFileItem): String {
        val value = buildString {
            append(connectionId)
            append('\u0000')
            append(item.path)
            append('\u0000')
            append(item.size)
            append('\u0000')
            append(item.modifiedAt?.toEpochMilli() ?: 0L)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun safeFilename(original: String): String {
        val cleaned = original
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001F\\u007F/:*?\"<>|]"), "_")
            .trim()
            .trim('.')
            .ifBlank { "remote-file" }
        if (cleaned.toByteArray(Charsets.UTF_8).size <= MAX_FILENAME_BYTES) return cleaned

        val extension = cleaned.substringAfterLast('.', "")
            .takeUtf8(MAX_EXTENSION_BYTES)
        val suffix = if (extension.isEmpty()) "" else ".$extension"
        val stemBudget = (MAX_FILENAME_BYTES - suffix.toByteArray(Charsets.UTF_8).size)
            .coerceAtLeast(1)
        return cleaned.substringBeforeLast('.', cleaned)
            .takeUtf8(stemBudget)
            .trimEnd()
            .ifBlank { "remote-file" } + suffix
    }

    private fun String.takeUtf8(maxBytes: Int): String {
        if (toByteArray(Charsets.UTF_8).size <= maxBytes) return this
        var end = 0
        while (end < length) {
            val next = offsetByCodePoints(end, 1)
            if (substring(0, next).toByteArray(Charsets.UTF_8).size > maxBytes) break
            end = next
        }
        return substring(0, end)
    }

    private data class CacheEntry(
        val directory: File,
        val bytes: Long,
        val lastAccess: Long,
    )

    private companion object {
        val MAX_AGE_MILLIS = TimeUnit.DAYS.toMillis(7)
        const val MAX_BYTES = 512L * 1024L * 1024L
        const val MAX_FILENAME_BYTES = 180
        const val MAX_EXTENSION_BYTES = 40
    }
}
