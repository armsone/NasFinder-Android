package com.armsone.nasfinder.data

import android.content.Context
import com.armsone.nasfinder.model.RemoteFileItem
import com.armsone.nasfinder.util.DownloadCacheContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit

/**
 * Private cache for remote files prepared for FileProvider grants.
 * Entries expire after seven days and least-recently-used entries are removed
 * whenever the cache exceeds 512 MiB, but automatic cleanup waits while any
 * instance reports active use and spares entries used within the last hour.
 * The cache can therefore exceed 512 MiB until the next idle cleanup.
 */
class DownloadCache(context: Context) {
    private val root: File = run {
        val cacheDirectory = context.cacheDir
        val raw = File(cacheDirectory, "shares")
        check(!Files.isSymbolicLink(raw.toPath())) {
            "다운로드 캐시 폴더가 심볼릭 링크라 사용할 수 없습니다."
        }
        raw.mkdirs()
        check(!Files.isSymbolicLink(raw.toPath())) {
            "다운로드 캐시 폴더가 심볼릭 링크라 사용할 수 없습니다."
        }
        val canonical = raw.canonicalFile
        check(canonical == File(cacheDirectory.canonicalFile, "shares")) {
            "다운로드 캐시 폴더가 앱 캐시 폴더 안에 있지 않아 사용할 수 없습니다."
        }
        canonical
    }
    private var automaticTrimGuard: (() -> Boolean)? = null

    /**
     * Automatic cleanup is skipped for the whole process while [isInUse] answers true, because a
     * returned file may still be read long after [resolve] ends. [clearOwned] keeps its own check.
     */
    fun guardAutomaticTrim(isInUse: () -> Boolean) {
        automaticTrimGuard?.let { automaticTrimGuards.remove(it) }
        automaticTrimGuard = isInUse
        automaticTrimGuards.add(isInUse)
    }

    fun releaseAutomaticTrimGuard() {
        automaticTrimGuard?.let { automaticTrimGuards.remove(it) }
        automaticTrimGuard = null
    }

    /** Runs the cleanup that [resolve] skipped while the cache was in use. */
    suspend fun trimIfIdle(): Unit = withContext(Dispatchers.IO) {
        lock.withLock { cleanUp() }
    }

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

            if (destination.isFile && DownloadCacheContract.isComplete(sizeOf(destination), item.size)) {
                destination.setLastModified(System.currentTimeMillis())
                entryDirectory.setLastModified(System.currentTimeMillis())
                progress(sizeOf(destination), sizeOf(destination))
                return@withLock destination
            }
            if (destination.exists()) destination.delete()

            check(entryDirectory.mkdirs() || entryDirectory.isDirectory) {
                "다운로드 캐시를 만들 수 없습니다."
            }
            val partial = File(entryDirectory, ".download.part")
            partial.delete()
            try {
                download(partial, progress)
                check(partial.isFile) { "다운로드한 파일을 찾을 수 없습니다." }
                check(DownloadCacheContract.isComplete(sizeOf(partial), item.size)) {
                    "다운로드 크기가 원격 파일과 다릅니다. 다시 시도해 주세요."
                }
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

    /** Total bytes of owned (64-hex) download entries only. */
    suspend fun ownedSizeBytes(): Long = withContext(Dispatchers.IO) {
        lock.withLock { ownedDirectories().sumOf { ownedFiles(it).sumOf(this@DownloadCache::sizeOf) } }
    }

    /** Deletes only owned (64-hex) download entries and returns the bytes removed. */
    suspend fun clearOwned(canClear: () -> Boolean = { true }): Long = withContext(Dispatchers.IO) {
        lock.withLock {
            check(canClear()) {
                "작업이 진행 중이거나 편집·미리보기가 열려 있어 다운로드 캐시를 비우지 않았습니다. 작업을 마친 뒤 다시 시도해 주세요."
            }
            var removed = 0L
            var failed = 0
            ownedDirectories().forEach { directory ->
                val bytes = ownedFiles(directory).sumOf(this@DownloadCache::sizeOf)
                if (deleteTree(directory)) removed += bytes else failed++
            }
            check(failed == 0) { "다운로드 캐시 ${failed}개 항목을 지우지 못했습니다." }
            removed
        }
    }

    /** 루트가 확실히 없으면 false, 존재하지만 실제 디렉터리가 아니거나 읽을 수 없으면 예외. */
    private fun rootExists(): Boolean {
        try {
            val attrs = Files.readAttributes(
                root.toPath(),
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            check(attrs.isDirectory && !attrs.isSymbolicLink) {
                "다운로드 캐시 폴더가 올바른 폴더가 아니라 사용할 수 없습니다."
            }
            check(root.canonicalFile == root) {
                "다운로드 캐시 폴더 위치가 바뀌어 사용할 수 없습니다."
            }
            return true
        } catch (_: java.nio.file.NoSuchFileException) {
            return false
        }
    }

    private fun ownedDirectories(): List<File> {
        if (!rootExists()) return emptyList()
        val entries = root.listFiles()
            ?: throw java.io.IOException("다운로드 캐시 폴더를 읽을 수 없습니다.")
        return entries.filter { entry ->
            if (!OWNED_NAME.matches(entry.name)) return@filter false
            val attrs = Files.readAttributes(
                entry.toPath(),
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            !attrs.isSymbolicLink && attrs.isDirectory &&
                entry.canonicalFile == File(root, entry.name)
        }
    }

    private fun ownedFiles(directory: File): List<File> {
        val directoryPath = directory.toPath()
        val directoryAttrs = Files.readAttributes(
            directoryPath,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (!directoryAttrs.isDirectory || directoryAttrs.isSymbolicLink) {
            throw java.io.IOException("다운로드 캐시 항목을 읽을 수 없습니다.")
        }
        return Files.walk(directoryPath).use { stream ->
            stream.iterator().asSequence()
                .filter {
                    Files.readAttributes(
                        it,
                        BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    ).isRegularFile
                }
                .map { it.toFile() }
                .toList()
        }
    }

    /** 크기를 읽지 못하면 0 대신 예외를 던진다. */
    private fun sizeOf(file: File): Long = Files.size(file.toPath())

    private fun deleteTree(directory: File): Boolean = runCatching {
        val start = directory.toPath()
        check(Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) { "not a directory" }
        Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
        true
    }.getOrDefault(false)

    private fun cleanUp(excluding: File? = null) {
        if (automaticTrimGuards.any { isInUse -> isInUse() }) return
        val now = System.currentTimeMillis()
        val directories = try {
            ownedDirectories()
        } catch (_: Exception) {
            return
        }

        val entries = mutableListOf<CacheEntry>()
        for (directory in directories) {
            val files: List<File>
            val snapshot = try {
                files = ownedFiles(directory)
                val times = files.map(File::lastModified)
                if (times.any { it == 0L }) continue
                Pair(times.maxOrNull(), files.sumOf { sizeOf(it) })
            } catch (_: Exception) {
                continue
            }
            val newest = snapshot.first
            val totalBytes = snapshot.second
            val lastAccess = newest ?: directory.lastModified()
            // Files handed to another app report no end of use, so recent entries get a bounded grace period.
            val isExcluded = excluding != null &&
                excluding.path.startsWith(directory.path + File.separator)
            val isProtected = isExcluded || now - lastAccess < RECENT_USE_GRACE_MILLIS
            if (!isProtected && (newest == null || now - newest > MAX_AGE_MILLIS)) {
                deleteTree(directory)
                continue
            }
            entries += CacheEntry(
                directory = directory,
                bytes = totalBytes,
                lastAccess = lastAccess,
                isProtected = isProtected,
            )
        }

        entries.sortBy(CacheEntry::lastAccess)
        var bytes = entries.sumOf(CacheEntry::bytes)
        for (entry in entries) {
            if (bytes <= MAX_BYTES) break
            if (entry.isProtected) continue
            if (deleteTree(entry.directory)) bytes -= entry.bytes
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
        val isProtected: Boolean,
    )

    private companion object {
        // Every instance shares one cache root, so downloads and cleanup are serialized across instances.
        val lock = Mutex()
        val automaticTrimGuards = CopyOnWriteArraySet<() -> Boolean>()
        val RECENT_USE_GRACE_MILLIS = TimeUnit.HOURS.toMillis(1)
        val MAX_AGE_MILLIS = TimeUnit.DAYS.toMillis(7)
        val OWNED_NAME = Regex("[0-9a-f]{64}")
        const val MAX_BYTES =512L * 1024L * 1024L
        const val MAX_FILENAME_BYTES = 180
        const val MAX_EXTENSION_BYTES = 40
    }
}
