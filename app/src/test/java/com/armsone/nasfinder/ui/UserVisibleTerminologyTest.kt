package com.armsone.nasfinder.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserVisibleTerminologyTest {
    @Test
    fun `iOS product names remain canonical in Android UI`() {
        val app = sourceFile("app/src/main/java/com/armsone/nasfinder/ui/NasFinderApp.kt").readText()
        val viewModel = sourceFile("app/src/main/java/com/armsone/nasfinder/ui/NasFinderViewModel.kt").readText()
        val browser = sourceFile("app/src/main/java/com/armsone/nasfinder/ui/WebBrowserScreen.kt").readText()
        val strings = sourceFile("app/src/main/res/values/strings.xml").readText()

        assertTrue(app.contains("private fun BrowserDashboardRow"))
        assertTrue(app.contains("Text(\"Browser\""))
        assertTrue(app.contains("private fun PhoneHardDashboardRow"))
        assertTrue(app.contains("Text(\"폰하드\""))
        assertTrue(app.contains("DashboardRow(Icons.Default.AutoAwesome, \"Super Thumbnail\""))
        assertTrue(app.contains("DashboardRow(Icons.Default.PhotoLibrary, \"썸네일 캐시\""))
        assertTrue(app.contains("BrowserActionTile(\"붙여넣기\""))
        assertTrue(app.contains("Text(\"받은 파일 오류\")"))
        assertTrue(app.contains("Text(\"Super Cache 초기화\")"))
        assertTrue(app.contains("Text(\"Super Thumbnail을 초기화할까요?\")"))
        assertTrue(browser.contains("\"즐겨찾기\","))
        assertTrue(browser.contains("Text(\"즐겨찾기 편집\")"))
        assertTrue(browser.contains("Text(\"브라우저 오류\")"))

        assertTrue(strings.contains(">폰하드</string>"))
        assertTrue(strings.contains(">폰하드 화면 열기</string>"))
        assertTrue(strings.contains(">폰하드 열기</string>"))
        assertTrue(strings.contains(">Browser</string>"))
        assertTrue(strings.contains(">NasFinder Browser 열기</string>"))
        assertTrue(strings.contains(">NasFinder로 저장</string>"))
        assertTrue(strings.contains(">NasFinder 바로 열기</string>"))
        assertTrue(strings.contains(">NasFinder 열기</string>"))

        assertFalse(app.contains("DashboardRow(Icons.Default.Language, \"웹 브라우저\""))
        assertFalse(app.contains("DashboardRow(Icons.Default.WifiTethering, \"WebHard\""))
        assertFalse(app.contains("BrowserActionTile(\"업로드\""))
        assertFalse(app.contains("\"슈퍼 썸네일\""))
        assertFalse(viewModel.contains("슈퍼 썸네일"))
        assertFalse(viewModel.contains("웹 즐겨찾기"))
        assertFalse(browser.contains("웹 즐겨찾기"))
        assertFalse(strings.contains("WebHard"))
        assertFalse(strings.contains("웹하드"))
        assertFalse(strings.contains("웹 브라우저"))
    }

    private fun sourceFile(relativePath: String): File {
        val working = File(System.getProperty("user.dir")).canonicalFile
        return generateSequence(working) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Cannot locate $relativePath from $working")
    }
}
