package com.armsone.nasfinder.platform

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

object NasFinderShareIntentFactory {
    /**
     * Creates a grant-scoped Sharesheet intent. Callers must first place remote
     * downloads below cache/shares or use an existing SharedInbox payload.
     */
    fun create(context: Context, files: List<File>): Intent {
        return createInternal(context, files, forceMultiple = false)
    }

    /** Batch Inbox shares always use ACTION_SEND_MULTIPLE, including a one-item selection. */
    fun createMultiple(context: Context, files: List<File>): Intent {
        return createInternal(context, files, forceMultiple = true)
    }

    private fun createInternal(context: Context, files: List<File>, forceMultiple: Boolean): Intent {
        require(files.isNotEmpty()) { "공유할 파일이 없습니다." }

        val allowedRoots = listOf(
            File(context.cacheDir, "shares").canonicalFile,
            File(context.filesDir, "SharedInbox").canonicalFile,
        )
        val safeFiles = files.map { source ->
            val file = source.canonicalFile
            require(file.isFile) { "일반 파일만 공유할 수 있습니다." }
            require(allowedRoots.any { file.isWithin(it) }) {
                "공유 전용 저장소 밖의 파일은 노출할 수 없습니다."
            }
            file
        }.distinctBy { it.path }
        require(safeFiles.size <= InboxBatchContracts.MAX_SELECTED_ITEMS) {
            "한 번에 최대 ${InboxBatchContracts.MAX_SELECTED_ITEMS}개까지 공유할 수 있습니다."
        }

        val authority = "${context.packageName}.sharefiles"
        val uris = safeFiles.map { file ->
            FileProvider.getUriForFile(context, authority, file)
        }
        val mimeTypes = safeFiles.map(::mimeType)
        val mode = shareIntentMode(uris.size, forceMultiple)
        val intent = Intent(if (mode == ShareIntentMode.SINGLE) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = commonMimeType(mimeTypes)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, safeFiles.first().name, uris.first()).also {
                for (index in 1 until uris.size) {
                    it.addItem(ClipData.Item(uris[index]))
                }
            }
            if (mode == ShareIntentMode.SINGLE) {
                putExtra(Intent.EXTRA_STREAM, uris.first())
            } else {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
            }
        }
        return intent
    }

    private fun File.isWithin(root: File): Boolean {
        val rootPath = root.path.trimEnd(File.separatorChar) + File.separator
        return path == root.path || path.startsWith(rootPath)
    }

    private fun mimeType(file: File): String {
        val extension = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    private fun commonMimeType(types: List<String>): String {
        val distinct = types.distinct()
        if (distinct.size == 1) return distinct.single()
        val topLevels = distinct.map { it.substringBefore('/') }.distinct()
        return if (topLevels.size == 1) "${topLevels.single()}/*" else "*/*"
    }
}
