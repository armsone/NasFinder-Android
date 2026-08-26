package com.armsone.nasfinder.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundReturnContractTest {
    @Test fun `leaving the foreground returns the next visit to the dashboard but rotation keeps the screen`() {
        val app = sourceFile("app/src/main/java/com/armsone/nasfinder/ui/NasFinderApp.kt").readText()
        val observer = app.substring(app.indexOf("DisposableEffect(appLifecycleOwner, model) {"), app.indexOf("BackHandler(enabled = state.imagePreview != null)"))
        assertTrue(observer.contains("event == Lifecycle.Event.ON_STOP"))
        assertTrue(observer.contains("isChangingConfigurations != true"))
        assertTrue(observer.contains("model.returnToDashboardAfterBackground()"))
        assertTrue(observer.contains("appLifecycleOwner.lifecycle.removeObserver(observer)"))
    }

    @Test fun `dashboard return clears only transient navigation viewer and browser state`() {
        val viewModel = sourceFile("app/src/main/java/com/armsone/nasfinder/ui/NasFinderViewModel.kt").readText()
        val body = functionBody(viewModel, "fun returnToDashboardAfterBackground() {", "fun dismissMessage()")
        assertTrue(body.contains("screen = Screen.Dashboard"))
        assertTrue(body.contains("invalidateImagePreviewRequests()"))
        assertTrue(body.contains("connectionOpenJob?.cancel()"))
        assertTrue(body.contains("thumbnailGeneration++"))
        // Persistent stores, credentials, settings, downloads and caches are never touched here.
        listOf(
            "repository.", "favoriteRepository", "settingsRepository", "inboxStore", "downloadCache",
            "thumbnailRepository", "superThumbnail", "credentials", "connections =", "remoteFavorites =",
            "browserFavorites =", "pendingInboxUpload", "pendingLocalUpload", "download =", "clear(", "delete",
        ).forEach { forbidden -> assertFalse("must not touch $forbidden", body.contains(forbidden)) }
        // The preview invalidation itself only drops the viewer and its in-flight preview download.
        val invalidate = functionBody(viewModel, "private fun invalidateImagePreviewRequests() {", "fun selectImagePreviewIndex(")
        assertTrue(invalidate.contains("imagePreview = null"))
        assertFalse(invalidate.contains("repository."))
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
