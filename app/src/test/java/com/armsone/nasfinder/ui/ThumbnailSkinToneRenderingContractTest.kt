package com.armsone.nasfinder.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailSkinToneRenderingContractTest {
    @Test
    fun `all app thumbnail artwork routes use the centralized skin aware renderer`() {
        val app = sourceFile("app/src/main/java/com/armsone/nasfinder/ui/NasFinderApp.kt").readText()
        val webHard = sourceFile("app/src/main/java/com/armsone/nasfinder/ui/WebHardScreen.kt").readText()
        val photoTransfer = sourceFile("app/src/main/java/com/armsone/nasfinder/ui/PhotoTransferScreen.kt").readText()

        val favoriteShelf = app.section("private fun FavoriteShelf(", "private fun favoriteIcon(")
        val remoteArtwork = app.section("private fun RemoteFileArtwork(", "private fun FileActionsMenu(")
        val inboxArtwork = app.section("private fun InboxArtwork(", "private data class InboxThumbnailResult(")
        val webHardArtwork = webHard.section("private fun WebHardArtwork(", "private fun WebHardItemMenu(")
        val savedTransfer = photoTransfer.section("private fun SavedPhotoTransferCard(", "private fun loadSavedPhotoTransferThumbnail(")
        val selectedTransfer = photoTransfer.section("private fun SelectedMediaReviewCard(", "private fun MediaKindBadge(")

        listOf(favoriteShelf, remoteArtwork, inboxArtwork, webHardArtwork, savedTransfer, selectedTransfer)
            .forEach { assertTrue(it.contains("SkinAwareThumbnailImage(")) }
    }

    @Test
    fun `full media viewer remains outside thumbnail blur path`() {
        val app = sourceFile("app/src/main/java/com/armsone/nasfinder/ui/NasFinderApp.kt").readText()
        val viewer = app.section("private fun ZoomablePreviewBitmap(", "private fun AnimatedGifPreview(")
        assertFalse(viewer.contains("SkinAwareThumbnailImage("))
    }

    private fun String.section(start: String, end: String): String =
        substring(indexOf(start), indexOf(end, startIndex = indexOf(start) + start.length))

    private fun sourceFile(relativePath: String): File {
        val working = File(System.getProperty("user.dir")).canonicalFile
        return generateSequence(working) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Cannot locate $relativePath from $working")
    }
}
