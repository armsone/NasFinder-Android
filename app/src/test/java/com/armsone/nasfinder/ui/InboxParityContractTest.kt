package com.armsone.nasfinder.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxParityContractTest {
    @Test
    fun `phonehard screen keeps the iOS visible contract`() {
        val app = sourceFile("app/src/main/java/com/armsone/nasfinder/ui/NasFinderApp.kt").readText()
        val viewModel = sourceFile("app/src/main/java/com/armsone/nasfinder/ui/NasFinderViewModel.kt").readText()
        val webHard = sourceFile("app/src/main/java/com/armsone/nasfinder/ui/WebHardScreen.kt").readText()
        val inboxOverflow = app.substring(
            app.indexOf("private fun InboxOverflow("),
            app.indexOf("private val InboxDisplayItem.isInboxImage"),
        )

        assertTrue(app.contains("if (selectionMode) \"${'$'}{selectedIds.size}개 선택\" else \"폰하드\""))
        assertTrue(app.contains("item { PhoneHardConnectionHeader(webHardConnection) }"))
        assertTrue(app.contains("item { PhoneHardConnectionHeader(webHardConnection, horizontalPadding = 0.dp) }"))
        assertTrue(app.contains("item(span = { GridItemSpan(maxLineSpan) })"))
        assertTrue(app.contains("PhoneHardConnectionHeader(connection, horizontalPadding = 0.dp)"))
        assertTrue(app.contains("private fun InboxOverflow(\n    files: List<InboxDisplayItem>"))
        assertFalse(inboxOverflow.contains("PhoneHardConnectionHeader"))
        assertTrue(inboxOverflow.contains("Box(modifier)"))
        assertTrue(app.contains("modifier = Modifier.fillParentMaxWidth().fillParentMaxHeight()"))
        assertTrue(app.contains("val inboxLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE"))
        assertTrue(app.contains("val showsAutomaticOverflow = configuredLayout == InboxLayout.POSTERS && inboxLandscape"))
        assertTrue(app.contains("val displayedLayout = if (showsAutomaticOverflow) InboxLayout.OVERFLOW else configuredLayout"))
        assertTrue(app.contains("listOf(\n                                    InboxLayout.DETAILS,\n                                    InboxLayout.THUMBNAILS,\n                                    InboxLayout.POSTERS,"))
        assertTrue(app.contains("if (it == InboxLayout.OVERFLOW) InboxLayout.POSTERS else it"))
        assertTrue(app.contains("inboxPreferences.getString(\"layout\", null) == InboxLayout.OVERFLOW.name"))
        assertTrue(app.contains("if (files.isEmpty() && !showsAutomaticOverflow)"))
        assertTrue(app.contains("Icon(Icons.Default.MoreHoriz, \"Cover Flow 배경\""))
        assertTrue(app.contains("Text(\"밝은 배경\")"))
        assertTrue(app.contains("Text(\"어두운 배경\")"))
        assertTrue(app.contains("Screen.Inbox, Screen.WebHard -> InboxScreen(state, model)"))
        assertFalse(app.contains("model.show(Screen.WebHard)"))
        assertTrue(webHard.contains("internal fun PhoneHardConnectionPanel"))
        assertTrue(webHard.contains("\"서버 열기\""))
        assertFalse(webHard.contains("\"다른 기기 연결\""))
        assertFalse(webHard.contains("같은 Wi-Fi·핫스팟·VPN에서 연결할 수 있습니다."))
        assertFalse(webHard.contains("폰하드가 열려 있습니다."))
        assertFalse(webHard.contains("ExposedDropdownMenuBox"))
        assertFalse(webHard.contains("ExposedDropdownMenuDefaults.TrailingIcon"))
        assertTrue(webHard.contains("connection.selectedAddress?.hostAddress ?: \"사용 가능한 접속 주소가 없습니다\""))
        assertTrue(webHard.contains("val addressHeight = if (largeFont) 48.dp else 40.dp"))
        assertTrue(webHard.contains("Modifier.fillMaxWidth().heightIn(min = addressHeight).padding(start = 12.dp, end = 2.dp)"))
        assertTrue(webHard.contains("label = { Text(\"비밀번호 (선택)\", style = MaterialTheme.typography.labelSmall) }"))
        assertTrue(webHard.contains("val label = if (connection.server == null) \"열기\" else \"닫기\""))
        assertTrue(webHard.contains("private val BkPanelCharcoal = Color(0xFF34383B)"))
        assertTrue(webHard.contains("private val BkPanelRecessed = Color(0xFFE7E6E1)"))
        assertFalse(webHard.contains("BkPanelStatusRed"))
        assertFalse(webHard.contains("BkConnectionStatus"))
        assertTrue(webHard.contains("Brush.linearGradient(listOf(Color.White, BkPanelChrome"))
        assertTrue(webHard.contains("if (enabled) listOf(Color(0xFF666B6E), BkPanelCharcoal"))
        assertTrue(webHard.contains("ElevatedCard(modifier) { content() }"))
        assertFalse(webHard.contains("maxWidth < 360.dp"))
        assertTrue(webHard.contains("val fieldHeight = if (largeFont) 56.dp else 48.dp"))
        assertTrue(webHard.contains("var passwordExpanded by rememberSaveable { mutableStateOf(false) }"))
        assertTrue(webHard.contains("if (passwordExpanded)"))
        assertTrue(webHard.contains("private fun PhoneHardPasswordButton("))
        assertTrue(webHard.indexOf("PhoneHardPasswordButton(") < webHard.indexOf("PhoneHardConnectionButton(connection, enamel, Modifier.weight(1f).height(fieldHeight))"))
        assertTrue(webHard.contains("PhoneHardConnectionButton(connection, enamel, Modifier.weight(1f).height(fieldHeight))"))
        assertTrue(app.contains("while (webHardConnection.server != null)"))
        val phoneHardRow = app.indexOf("PhoneHardDashboardRow(\"${'$'}{state.inboxFiles.size}개\")")
        val photoTransferRow = app.indexOf(
            "DashboardRow(Icons.AutoMirrored.Filled.CompareArrows, \"Live Photos & Motion Photos\"",
            phoneHardRow,
        )
        val thumbnailCacheRow = app.indexOf(
            "DashboardRow(Icons.Default.PhotoLibrary, \"썸네일 캐시\"",
            photoTransferRow,
        )
        assertTrue(phoneHardRow >= 0 && photoTransferRow > phoneHardRow && thumbnailCacheRow > photoTransferRow)
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
        assertTrue(app.split("Modifier.fillMaxWidth().aspectRatio(1f)").size - 1 >= 2)
        assertTrue(app.contains("modifier = Modifier.requiredSize(cardSide)"))
        assertTrue(app.contains("context.contentResolver.loadThumbnail"))
        assertTrue(app.contains("ThumbnailUtils.createVideoThumbnail"))
        assertTrue(app.contains("MediaMetadataRetriever.OPTION_CLOSEST_SYNC"))
        assertTrue(app.contains("\"Motion Photo\""))
        assertTrue(app.contains("InboxThumbnailMemoryCache"))
        assertTrue(app.contains("modifier = Modifier.size(38.dp)"))
        assertTrue(app.contains("value == SwipeToDismissBoxValue.EndToStart"))
        assertTrue(app.contains("title = { Text(\"폰하드 오류\") }"))
        assertTrue(viewModel.contains("fun previewInboxFile(item: InboxDisplayItem)"))
        assertTrue(viewModel.contains("fun shareInboxFile(item: InboxDisplayItem)"))
        assertTrue(viewModel.contains("폰하드 파일 목록을 읽지 못했습니다:"))
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
