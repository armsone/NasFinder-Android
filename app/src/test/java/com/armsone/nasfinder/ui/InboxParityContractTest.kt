package com.armsone.nasfinder.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxParityContractTest {
    @Test
    fun `received files screen keeps the iOS visible contract`() {
        val app = sourceFile("app/src/main/java/com/armsone/nasfinder/ui/NasFinderApp.kt").readText()
        val viewModel = sourceFile("app/src/main/java/com/armsone/nasfinder/ui/NasFinderViewModel.kt").readText()

        assertTrue(app.contains("if (selectionMode) \"${'$'}{selectedIds.size}개 선택\" else \"받은 파일\""))
        assertTrue(app.contains("ActivityResultContracts.OpenMultipleDocuments()"))
        assertTrue(app.contains("Icon(Icons.AutoMirrored.Filled.NoteAdd, \"파일에서 가져오기\")"))
        assertTrue(viewModel.contains("fun importPickedFiles(uris: List<Uri>)"))
        assertTrue(viewModel.contains("uris.distinct().take(InboxBatchContracts.MAX_SELECTED_ITEMS)"))
        assertTrue(app.contains("Text(if (selectionMode) \"완료\" else \"선택\")"))
        assertTrue(app.contains("Text(if (allSelected) \"전체 해제\" else \"전체 선택\")"))
        assertTrue(app.contains("Icon(Icons.Default.Delete, \"선택한 파일 삭제\""))
        assertTrue(app.contains("Text(\"선택한 파일을 지울까요?\")"))
        assertTrue(app.contains("model.deleteInboxFiles(ids)"))
        assertTrue(viewModel.contains("fun deleteInboxFiles(ids: Iterable<UUID>)"))
        assertTrue(app.contains("Text(\"NAS로 보내기\")"))
        assertTrue(app.contains("modifier = Modifier.size(56.dp), cornerRadius = 9.dp"))
        assertTrue(app.contains("DETAILS(\"자세히\")"))
        assertTrue(app.contains("THUMBNAILS(\"썸네일\")"))
        assertTrue(app.contains("POSTERS(\"포스터\")"))
        assertTrue(app.contains("OVERFLOW(\"오버플로우\")"))
        assertTrue(app.contains("context.contentResolver.loadThumbnail"))
        assertTrue(app.contains("ThumbnailUtils.createVideoThumbnail"))
        assertTrue(app.contains("MediaMetadataRetriever.OPTION_CLOSEST_SYNC"))
        assertTrue(app.contains("\"Motion Photo\""))
        assertTrue(app.contains("InboxThumbnailMemoryCache"))
        assertTrue(app.contains("modifier = Modifier.size(38.dp)"))
        assertTrue(app.contains("value == SwipeToDismissBoxValue.EndToStart"))
        assertTrue(app.contains("title = { Text(\"받은 파일 오류\") }"))
        assertTrue(viewModel.contains("fun previewInboxFile(item: InboxDisplayItem)"))
        assertTrue(viewModel.contains("fun shareInboxFile(item: InboxDisplayItem)"))
        assertTrue(viewModel.contains("받은 파일 목록을 읽지 못했습니다:"))
        assertTrue(viewModel.contains("${'$'}{item.originalFilename}을(를) 삭제하지 못했습니다:"))

        assertFalse(app.contains("Icons.Default.Checklist, \"다중 선택\""))
        assertFalse(app.contains("filePendingDeletion"))
        assertFalse(app.contains("이 iPhone에서 제거됩니다"))
        assertFalse(app.contains("iPhone의 라이트·다크 모드"))
        assertFalse(app.contains("AppTheme.SYSTEM -> \"iPhone 설정\""))
    }

    private fun sourceFile(relativePath: String): File {
        val working = File(System.getProperty("user.dir")).canonicalFile
        return generateSequence(working) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Cannot locate ${'$'}relativePath from ${'$'}working")
    }
}
