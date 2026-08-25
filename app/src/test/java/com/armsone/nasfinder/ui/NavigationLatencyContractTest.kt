package com.armsone.nasfinder.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationLatencyContractTest {
    @Test
    fun `openConnection navigates immediately to browser screen and executes network IO on Dispatchers IO`() {
        val viewModel = sourceFile("app/src/main/java/com/armsone/nasfinder/ui/NasFinderViewModel.kt").readText()
        val app = sourceFile("app/src/main/java/com/armsone/nasfinder/ui/NasFinderApp.kt").readText()

        val openConnection = functionBody(viewModel, "fun openConnection(", "fun resumeConnection(")

        // 1. The target screen is published before the asynchronous job starts.
        val screenUpdate = openConnection.indexOf("screen = Screen.Browser(connection, path, emptyList(), preferences)")
        val jobLaunch = openConnection.indexOf("connectionOpenJob = viewModelScope.launch")
        assertTrue(screenUpdate >= 0)
        assertTrue(jobLaunch > screenUpdate)
        assertTrue(openConnection.contains("isBusy = true"))

        // 2. IO operations in openConnection run within withContext(Dispatchers.IO)
        assertTrue(openConnection.contains("withContext(Dispatchers.IO)"))
        assertTrue(openConnection.contains("repository.credentials.read(connection.id)"))
        assertTrue(openConnection.contains("newService.list(path)"))

        // 3. Generation check protects against stale async completion
        assertTrue(openConnection.contains("if (it.thumbnailGeneration != generation) it"))

        // 4. BrowserScreen in UI suppresses empty state while isBusy is true
        assertTrue(app.contains("browser.items.isEmpty() && state.isBusy -> Spacer(Modifier.weight(1f))"))
    }

    @Test
    fun `remote operations offload credential reading and network calls to Dispatchers IO`() {
        val viewModel = sourceFile("app/src/main/java/com/armsone/nasfinder/ui/NasFinderViewModel.kt").readText()

        // Related remote entry points also cross an IO dispatcher boundary.
        assertTrue(functionBody(viewModel, "fun openFavorite(", "fun shareItem(").contains("withContext(Dispatchers.IO)"))

        // testConnection uses Dispatchers.IO
        assertTrue(functionBody(viewModel, "fun testConnection(", "fun saveOAuthClientId(").contains("withContext(Dispatchers.IO)"))

        // SuperThumbnail picker uses Dispatchers.IO
        assertTrue(functionBody(viewModel, "fun openSuperThumbnailPickerConnection(", "fun openSuperThumbnailPickerFolder(").contains("withContext(Dispatchers.IO)"))

        // SuperThumbnail picker path loading uses Dispatchers.IO
        assertTrue(functionBody(viewModel, "fun loadSuperThumbnailPickerPath(", "fun visibleSuperThumbnailFolders(").contains("withContext(Dispatchers.IO)"))
    }

    private fun sourceFile(relativePath: String): File {
        val working = File(System.getProperty("user.dir")).canonicalFile
        return generateSequence(working) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Cannot locate $relativePath from $working")
    }

    private fun functionBody(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue("Missing $startMarker", start >= 0)
        val end = source.indexOf(endMarker, start + startMarker.length)
        assertTrue("Missing $endMarker after $startMarker", end > start)
        return source.substring(start, end)
    }
}
