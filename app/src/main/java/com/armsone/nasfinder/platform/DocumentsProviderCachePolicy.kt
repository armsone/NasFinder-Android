package com.armsone.nasfinder.platform

import java.io.File
import java.nio.file.Files

/** Pure ownership and pruning policy for completed SAF downloads and their private partials. */
internal object DocumentsProviderCachePolicy {
    const val MAX_AGE_MILLIS = 7L * 24L * 60L * 60L * 1_000L
    const val MAX_BYTES = 512L * 1024L * 1024L
    private val COMPLETE_NAME = Regex("^[0-9a-f]{64}(?:\\.[a-z0-9]{1,12})?$")
    private val TEMPORARY_NAME = Regex("^download-[A-Za-z0-9_-]+\\.part$")

    fun prune(
        directory: File,
        nowMillis: Long = System.currentTimeMillis(),
        preserve: File? = null,
        maxAgeMillis: Long = MAX_AGE_MILLIS,
        maxBytes: Long = MAX_BYTES,
    ) {
        require(maxAgeMillis >= 0 && maxBytes >= 0)
        val root = safeRoot(directory) ?: return
        val preserved = preserve?.takeIf { ownedCompleted(root, it) }?.canonicalFile
        completedFiles(root)
            .filter { it.canonicalFile != preserved && nowMillis - it.lastModified() > maxAgeMillis }
            .forEach(File::delete)

        var retained = 0L
        completedFiles(root).sortedByDescending(File::lastModified).forEach { file ->
            val size = file.length().coerceAtLeast(0L)
            if (file.canonicalFile == preserved || size <= maxBytes - retained) {
                retained = (retained + size).coerceAtMost(Long.MAX_VALUE)
            } else {
                file.delete()
            }
        }
    }

    /** Deletes only the provider-created destination and its service-owned sibling partial. */
    fun cleanupFailedDownload(directory: File, destination: File) {
        val root = safeRoot(directory) ?: return
        if (!ownedTemporaryPath(root, destination)) return
        val sibling = File(root, ".${destination.name}.nasfinder.part")
        if (ownedServicePartial(root, destination, sibling)) sibling.delete()
        if (!Files.isSymbolicLink(destination.toPath()) && destination.isFile) destination.delete()
    }

    fun ownedCompleted(directory: File, candidate: File): Boolean {
        val root = safeRoot(directory) ?: return false
        return ownedRegularFile(root, candidate, COMPLETE_NAME)
    }

    internal fun ownedServicePartial(directory: File, destination: File, candidate: File): Boolean {
        val root = safeRoot(directory) ?: return false
        if (!ownedTemporaryPath(root, destination)) return false
        if (candidate.name != ".${destination.name}.nasfinder.part") return false
        return ownedRegularFile(root, candidate, null)
    }

    private fun completedFiles(root: File): List<File> = root.listFiles().orEmpty()
        .filter { ownedRegularFile(root, it, COMPLETE_NAME) }

    private fun ownedTemporaryPath(root: File, candidate: File): Boolean {
        val parent = runCatching { candidate.absoluteFile.parentFile?.canonicalFile }.getOrNull()
        if (parent != root) return false
        if (!candidate.name.matches(TEMPORARY_NAME) || Files.isSymbolicLink(candidate.toPath())) return false
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return false
        return canonical.parentFile == root && canonical.path.startsWith(root.path + File.separator)
    }

    private fun ownedRegularFile(root: File, candidate: File, namePattern: Regex?): Boolean {
        val parent = runCatching { candidate.absoluteFile.parentFile?.canonicalFile }.getOrNull()
        if (parent != root) return false
        if (namePattern != null && !candidate.name.matches(namePattern)) return false
        if (Files.isSymbolicLink(candidate.toPath()) || !candidate.isFile) return false
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return false
        return canonical.parentFile == root && canonical.path.startsWith(root.path + File.separator)
    }

    private fun safeRoot(directory: File): File? {
        if (Files.isSymbolicLink(directory.toPath())) return null
        val root = runCatching { directory.canonicalFile }.getOrNull() ?: return null
        return root.takeIf { it.isDirectory }
    }
}
