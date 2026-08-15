package com.armsone.nasfinder.platform

import android.content.Context
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

data class WebHardFileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long?,
    val modifiedAt: Instant?,
)

sealed class WebHardFileStoreException(message: String) : Exception(message) {
    class InvalidPath : WebHardFileStoreException("허용되지 않은 파일 경로입니다.")
    class NotFound : WebHardFileStoreException("파일 또는 폴더를 찾을 수 없습니다.")
    class AlreadyExists : WebHardFileStoreException("같은 이름의 파일 또는 폴더가 이미 있습니다.")
    class Unsupported : WebHardFileStoreException("지원하지 않는 파일 형식입니다.")
}

class WebHardPreparedUpload internal constructor(
    internal val temporaryFile: File,
    internal val destinationFile: File,
    internal val reservationKey: String,
) : Closeable {
    private var stream: FileOutputStream? = null

    internal fun outputStream(): FileOutputStream = stream ?: FileOutputStream(temporaryFile).also {
        stream = it
    }

    internal fun synchronizeAndClose() {
        stream?.fd?.sync()
        stream?.close()
        stream = null
    }

    override fun close() {
        runCatching { stream?.close() }
        stream = null
    }
}

/** App-private PhoneHard storage with lexical and canonical root confinement. */
class WebHardFileStore private constructor(root: File, @Suppress("UNUSED_PARAMETER") marker: Unit) {
    val rootDirectory: File
    private val reservations = mutableSetOf<String>()

    constructor(context: Context) : this(File(context.filesDir, "WebHard"), Unit)

    /** Visible for local tests and non-Android hosts; callers must supply an app-private root. */
    constructor(root: File) : this(root, Unit)

    init {
        if (!root.exists() && !root.mkdirs()) {
            throw IllegalStateException("폰하드 저장공간을 만들 수 없습니다.")
        }
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) {
            throw IllegalStateException("폰하드 저장공간이 안전한 폴더가 아닙니다.")
        }
        rootDirectory = root.canonicalFile
    }

    fun list(path: String): List<WebHardFileItem> {
        val directory = existing(path, expectDirectory = true)
        return directory.listFiles().orEmpty().mapNotNull { child ->
            if (child.name.startsWith('.') || child.isHidden || Files.isSymbolicLink(child.toPath())) {
                return@mapNotNull null
            }
            val attributes = Files.readAttributes(
                child.toPath(),
                java.nio.file.attribute.BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            if (!attributes.isDirectory && !attributes.isRegularFile) return@mapNotNull null
            WebHardFileItem(
                name = child.name,
                path = relativePath(child),
                isDirectory = attributes.isDirectory,
                size = attributes.size().takeUnless { attributes.isDirectory },
                modifiedAt = attributes.lastModifiedTime()?.toInstant(),
            )
        }.sortedWith(compareByDescending<WebHardFileItem> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    fun file(path: String): File = existing(path, expectDirectory = false)

    fun createDirectory(path: String) {
        val target = safeTarget(path, allowRoot = false)
        synchronized(this) {
            if (target.exists()) throw WebHardFileStoreException.AlreadyExists()
            validateExistingAncestor(target)
            if (!target.mkdirs()) throw WebHardFileStoreException.Unsupported()
            validateInsideRoot(target.canonicalFile)
        }
    }

    fun delete(path: String) {
        val target = existing(path, expectDirectory = null)
        if (target == rootDirectory) throw WebHardFileStoreException.InvalidPath()
        deleteWithoutFollowingLinks(target)
    }

    @Synchronized
    fun prepareUpload(path: String): WebHardPreparedUpload {
        val requested = safeTarget(path, allowRoot = false)
        validateExistingAncestor(requested)
        val parent = requested.parentFile ?: throw WebHardFileStoreException.InvalidPath()
        if (!parent.exists() && !parent.mkdirs()) throw WebHardFileStoreException.InvalidPath()
        validateExistingAncestor(requested)

        val destination = uniqueDestination(requested)
        val reservation = destination.canonicalPath
        reservations += reservation
        val temporary = File(parent, ".nasfinder-upload-${UUID.randomUUID()}")
        return try {
            if (!temporary.createNewFile()) throw WebHardFileStoreException.AlreadyExists()
            WebHardPreparedUpload(temporary, destination, reservation)
        } catch (error: Throwable) {
            reservations -= reservation
            throw error
        }
    }

    @Synchronized
    fun commitUpload(upload: WebHardPreparedUpload): WebHardFileItem {
        try {
            upload.synchronizeAndClose()
            validateInsideRoot(upload.temporaryFile.canonicalFile)
            if (!upload.temporaryFile.isFile || upload.destinationFile.exists()) {
                throw WebHardFileStoreException.AlreadyExists()
            }
            try {
                Files.move(
                    upload.temporaryFile.toPath(),
                    upload.destinationFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(upload.temporaryFile.toPath(), upload.destinationFile.toPath())
            }
            val file = upload.destinationFile.canonicalFile
            validateInsideRoot(file)
            return WebHardFileItem(
                name = file.name,
                path = relativePath(file),
                isDirectory = false,
                size = file.length(),
                modifiedAt = Instant.ofEpochMilli(file.lastModified()),
            )
        } finally {
            upload.close()
            reservations -= upload.reservationKey
        }
    }

    @Synchronized
    fun discardUpload(upload: WebHardPreparedUpload) {
        upload.close()
        upload.temporaryFile.delete()
        reservations -= upload.reservationKey
    }

    private fun existing(path: String, expectDirectory: Boolean?): File {
        val target = safeTarget(path, allowRoot = true)
        if (!target.exists()) throw WebHardFileStoreException.NotFound()
        validateNoSymlinkComponents(target)
        val canonical = target.canonicalFile
        validateInsideRoot(canonical)
        if (expectDirectory == true && !canonical.isDirectory) throw WebHardFileStoreException.Unsupported()
        if (expectDirectory == false && !canonical.isFile) throw WebHardFileStoreException.Unsupported()
        return canonical
    }

    private fun safeTarget(path: String, allowRoot: Boolean): File {
        if ('\u0000' in path || '\r' in path || '\n' in path) throw WebHardFileStoreException.InvalidPath()
        val components = path.split('/').filter { it.isNotEmpty() }
        if (components.any { it == "." || it == ".." || it.startsWith('.') }) {
            throw WebHardFileStoreException.InvalidPath()
        }
        if (components.isEmpty()) {
            if (!allowRoot) throw WebHardFileStoreException.InvalidPath()
            return rootDirectory
        }
        var candidate = rootDirectory
        for (component in components) candidate = File(candidate, component)
        val absolute = candidate.absoluteFile.normalize()
        validateInsideRoot(absolute)
        return absolute
    }

    private fun validateExistingAncestor(target: File) {
        var ancestor = target.parentFile ?: throw WebHardFileStoreException.InvalidPath()
        while (!ancestor.exists() && ancestor != rootDirectory) {
            ancestor = ancestor.parentFile ?: throw WebHardFileStoreException.InvalidPath()
        }
        validateNoSymlinkComponents(ancestor)
        validateInsideRoot(ancestor.canonicalFile)
    }

    private fun validateNoSymlinkComponents(target: File) {
        val relative = target.absoluteFile.normalize().path.removePrefix(rootDirectory.path)
            .trimStart(File.separatorChar)
        var current = rootDirectory
        if (Files.isSymbolicLink(current.toPath())) throw WebHardFileStoreException.InvalidPath()
        for (component in relative.split(File.separatorChar).filter { it.isNotEmpty() }) {
            current = File(current, component)
            if (Files.isSymbolicLink(current.toPath())) throw WebHardFileStoreException.InvalidPath()
        }
    }

    private fun validateInsideRoot(file: File) {
        val rootPath = rootDirectory.path.trimEnd(File.separatorChar)
        val filePath = file.path
        if (filePath != rootPath && !filePath.startsWith("$rootPath${File.separator}")) {
            throw WebHardFileStoreException.InvalidPath()
        }
    }

    private fun uniqueDestination(requested: File): File {
        fun unavailable(file: File) = file.exists() || file.canonicalPath in reservations
        if (!unavailable(requested)) return requested
        val extension = requested.extension.takeIf { it.isNotEmpty() }
        val stem = if (extension == null) requested.name else requested.name.removeSuffix(".$extension")
            .replace(Regex(" \\(\\d+\\)$"), "")
        for (index in 1..9_999) {
            val name = if (extension == null) "$stem ($index)" else "$stem ($index).$extension"
            val candidate = File(requested.parentFile, name)
            if (!unavailable(candidate)) return candidate
        }
        return File(requested.parentFile, "${UUID.randomUUID()}-${requested.name}")
    }

    private fun relativePath(file: File): String {
        val canonical = file.canonicalFile
        validateInsideRoot(canonical)
        if (canonical == rootDirectory) return "/"
        return "/" + canonical.path.removePrefix(rootDirectory.path).trimStart(File.separatorChar)
            .replace(File.separatorChar, '/')
    }

    private fun deleteWithoutFollowingLinks(file: File) {
        if (Files.isSymbolicLink(file.toPath())) {
            Files.delete(file.toPath())
            return
        }
        if (file.isDirectory) {
            file.listFiles().orEmpty().forEach(::deleteWithoutFollowingLinks)
        }
        if (!file.delete()) throw WebHardFileStoreException.Unsupported()
    }
}
