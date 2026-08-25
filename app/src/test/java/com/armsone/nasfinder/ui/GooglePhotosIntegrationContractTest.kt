package com.armsone.nasfinder.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GooglePhotosIntegrationContractTest {
    @Test fun `google photos is disclosed connected and imported through phonehard`() {
        val app = sourceFile("app/src/main/java/com/armsone/nasfinder/ui/NasFinderApp.kt").readText()
        val viewModel = sourceFile("app/src/main/java/com/armsone/nasfinder/ui/NasFinderViewModel.kt").readText()
        val callback = sourceFile("app/src/main/java/com/armsone/nasfinder/platform/OAuthCallbackActivity.kt").readText()
        assertTrue(app.contains("Google Photos에서 가져오기"))
        assertTrue(app.contains("모바일 데이터"))
        assertTrue(app.contains("NasFinder 서버로 보내지 않습니다"))
        assertTrue(viewModel.contains("GooglePhotosPickerFlow(picker).awaitSelection"))
        assertTrue(viewModel.contains("picker.allMediaItems(session.id)"))
        assertTrue(viewModel.contains("inboxStore.import(item.mediaFile?.filename"))
        assertTrue(viewModel.contains("googlePhotosJob?.cancel()"))
        assertTrue(callback.contains("completeCallbackIfPending"))
        assertFalse(viewModel.contains("client_secret"))
    }

    private fun sourceFile(path: String): File {
        var current = File(System.getProperty("user.dir")).canonicalFile
        repeat(6) {
            File(current, path).takeIf(File::isFile)?.let { return it }
            current = current.parentFile ?: return@repeat
        }
        error("source file not found: $path")
    }
}
