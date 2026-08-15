package com.armsone.nasfinder.platform

import com.armsone.nasfinder.model.ConnectionKind
import com.armsone.nasfinder.model.RemoteFileItem
import java.io.FileNotFoundException

internal class MutationMayHaveSucceededException(operation: String) : FileNotFoundException(
    "$operation 결과를 확인하지 못했습니다. 원격 작업이 일부 또는 전부 반영됐을 수 있으니 새로고침해 확인하세요.",
) {
    val remoteStateMayHaveChanged: Boolean = true
    val safeToRetryAutomatically: Boolean = false
}

internal object SafSftpMutationPolicy {
    data class Capabilities(
        val createFolder: Boolean = false,
        val rename: Boolean = false,
        val delete: Boolean = false,
        val move: Boolean = false,
    )

    fun supportsMutations(kind: ConnectionKind): Boolean = kind == ConnectionKind.SFTP

    fun capabilities(kind: ConnectionKind, isRoot: Boolean, isDirectory: Boolean): Capabilities {
        if (!supportsMutations(kind)) return Capabilities()
        if (isRoot) return Capabilities(createFolder = true)
        return Capabilities(createFolder = isDirectory, rename = true, delete = true, move = true)
    }

    fun validatedName(value: String): String = value.trim().also { name ->
        require(
            name.isNotEmpty() && !name.startsWith('.') && name !in setOf(".", "..") &&
                name.none { it == '/' || it == '\\' || it == '\u0000' || it == '\r' || it == '\n' },
        ) { "안전하지 않은 문서 이름입니다." }
    }

    fun append(parent: String, name: String): String {
        val safeName = validatedName(name)
        return when (parent) {
            "/" -> "/$safeName"
            "." -> "./$safeName"
            else -> "${parent.trimEnd('/')}/$safeName"
        }
    }

    fun isImmediateChild(path: String, parent: String): Boolean = parent(path) == parent

    fun isSameOrDescendant(path: String, root: String): Boolean = when (root) {
        "/" -> path.startsWith('/')
        "." -> path == "." || path.startsWith("./")
        else -> path == root || path.startsWith("$root/")
    }

    fun verifiedItem(items: List<RemoteFileItem>, expectedPath: String, directory: Boolean?): RemoteFileItem? =
        items.singleOrNull { it.path == expectedPath && (directory == null || it.isDirectory == directory) }

    fun parent(path: String): String {
        if (path == "/" || path == ".") return path
        val separator = path.lastIndexOf('/')
        return when {
            separator < 0 -> "."
            separator == 0 -> "/"
            path.startsWith("./") && separator == 1 -> "."
            else -> path.substring(0, separator)
        }
    }
}
