package com.armsone.nasfinder.util

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Pure, JVM-testable boundary rules shared by remote-transfer implementations. */
internal object TransferContracts {
    fun normalizeSftpPath(path: String, rootPath: String): String {
        require((path.split('/') + rootPath.split('/')).none { it == ".." }) {
            "SFTP path traversal is not allowed"
        }
        val root = parseSlashPath(rootPath, allowRelative = true, rejectBackslash = false)
        val candidate = parseSlashPath(path, allowRelative = true, rejectBackslash = false)
        require(root.absolute == candidate.absolute && candidate.components.startsWith(root.components)) {
            "SFTP path is outside its configured root"
        }
        return candidate.render(dotForEmptyRelative = !root.absolute && root.components.isEmpty())
    }

    fun normalizeSmbPath(path: String, rootPath: String): String {
        val root = parseSlashPath(rootPath, allowRelative = false, rejectBackslash = true)
        val candidate = parseSlashPath(path, allowRelative = false, rejectBackslash = true)
        require(candidate.components.startsWith(root.components)) { "SMB path is outside its configured root" }
        return candidate.render(dotForEmptyRelative = false)
    }

    fun requireSafeName(name: String, rejectColon: Boolean = false): String {
        require(name.none { it == '\u0000' || it == '\r' || it == '\n' }) { "Invalid remote name" }
        val value = name.trim()
        require(value.isNotEmpty() && value != "." && value != "..") { "Invalid remote name" }
        require(value.none { it == '/' || it == '\\' || it == '\u0000' || it == '\r' || it == '\n' }) {
            "Invalid remote name"
        }
        require(!rejectColon || ':' !in value) { "Invalid remote name" }
        return value
    }

    fun requireSafeFtpArgument(value: String): String {
        require(value.isNotBlank() && value.none { it == '\u0000' || it == '\r' || it == '\n' }) {
            "Invalid FTP argument"
        }
        return value
    }

    fun keepBothName(originalName: String, existingNames: Collection<String>): String {
        requireSafeName(originalName)
        if (originalName !in existingNames) return originalName
        val dot = originalName.lastIndexOf('.').takeIf { it > 0 } ?: -1
        val extension = if (dot >= 0) originalName.substring(dot) else ""
        val stem = (if (dot >= 0) originalName.substring(0, dot) else originalName)
            .replace(Regex(" \\(\\d+\\)$"), "")
        for (index in 1..9_999) {
            val candidate = "$stem ($index)$extension"
            if (candidate !in existingNames) return candidate
        }
        throw IllegalStateException("No keep-both name is available")
    }

    fun isVisibleName(name: String): Boolean = !name.startsWith('.')

    /** Validates the app-relative path accepted by the local WebHard file store. */
    fun normalizeWebHardPath(path: String, allowingRoot: Boolean = true): String {
        require(path.none { it == '\u0000' || it == '\r' || it == '\n' || it == '\\' }) {
            "Invalid WebHard path"
        }
        val components = path.split('/').filter(String::isNotEmpty)
        require(components.none { it == "." || it == ".." }) { "Invalid WebHard path" }
        require(allowingRoot || components.isNotEmpty()) { "WebHard root is not mutable" }
        return if (components.isEmpty()) "/" else "/${components.joinToString("/")}"
    }

    /** Parses an origin-form HTTP target and decodes query values exactly once. */
    fun parseWebHardTarget(target: String): WebHardTarget {
        require(target.startsWith('/') && target.none { it == '\u0000' || it == '\r' || it == '\n' }) {
            "Invalid HTTP request target"
        }
        val uri = URI(target)
        require(!uri.isAbsolute && uri.rawFragment == null) { "Invalid HTTP request target" }
        val query = linkedMapOf<String, String>()
        uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).forEach { pair ->
            val separator = pair.indexOf('=')
            val rawName = if (separator < 0) pair else pair.substring(0, separator)
            val rawValue = if (separator < 0) "" else pair.substring(separator + 1)
            query.putIfAbsent(decodeQuery(rawName), decodeQuery(rawValue))
        }
        return WebHardTarget(route = uri.rawPath, query = query)
    }

    private fun decodeQuery(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun parseSlashPath(
        path: String,
        allowRelative: Boolean,
        rejectBackslash: Boolean,
    ): ParsedPath {
        require(path.isNotBlank() && path.none { it == '\u0000' || it == '\r' || it == '\n' }) {
            "Invalid remote path"
        }
        require(!rejectBackslash || '\\' !in path) { "Invalid remote path" }
        val absolute = path.startsWith('/')
        require(allowRelative || absolute) { "An absolute remote path is required" }
        val components = mutableListOf<String>()
        path.split('/').filter(String::isNotEmpty).forEach { component ->
            when (component) {
                "." -> Unit
                ".." -> {
                    require(components.isNotEmpty()) { "Remote path traverses above root" }
                    components.removeAt(components.lastIndex)
                }
                else -> components += component
            }
        }
        return ParsedPath(absolute, components)
    }

    private fun <T> List<T>.startsWith(prefix: List<T>): Boolean =
        size >= prefix.size && take(prefix.size) == prefix

    private data class ParsedPath(val absolute: Boolean, val components: List<String>) {
        fun render(dotForEmptyRelative: Boolean): String = when {
            absolute -> if (components.isEmpty()) "/" else "/${components.joinToString("/")}"
            components.isEmpty() -> if (dotForEmptyRelative) "." else ""
            dotForEmptyRelative -> "./${components.joinToString("/")}"
            else -> components.joinToString("/")
        }
    }
}

internal data class WebHardTarget(val route: String, val query: Map<String, String>)

/** Pure filename and identity contract used by DownloadCache. */
internal object DownloadCacheContract {
    fun key(connectionId: String, path: String, size: Long, modifiedAtEpochMillis: Long): String {
        val value = "$connectionId\u0000$path\u0000$size\u0000$modifiedAtEpochMillis"
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun safeFilename(original: String): String {
        val cleaned = original.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001F\\u007F/:*?\"<>|]"), "_")
            .trim().trim('.').ifBlank { "remote-file" }
        if (cleaned.toByteArray(Charsets.UTF_8).size <= MAX_FILENAME_BYTES) return cleaned

        val extension = cleaned.substringAfterLast('.', "").takeUtf8(MAX_EXTENSION_BYTES)
        val suffix = if (extension.isEmpty()) "" else ".$extension"
        val stemBudget = (MAX_FILENAME_BYTES - suffix.toByteArray(Charsets.UTF_8).size).coerceAtLeast(1)
        return cleaned.substringBeforeLast('.', cleaned).takeUtf8(stemBudget)
            .trimEnd().ifBlank { "remote-file" } + suffix
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

    private const val MAX_FILENAME_BYTES = 180
    private const val MAX_EXTENSION_BYTES = 40
}
