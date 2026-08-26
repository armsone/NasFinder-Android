package com.armsone.nasfinder.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteDashboardContractTest {
    @Test
    fun `dashboard shelf requests and renders shared repository thumbnails with icon fallback`() {
        val app = sourceFile("app/src/main/java/com/armsone/nasfinder/ui/NasFinderApp.kt").readText()
        val viewModel = sourceFile("app/src/main/java/com/armsone/nasfinder/ui/NasFinderViewModel.kt").readText()

        val shelf = app.substring(app.indexOf("private fun FavoriteShelf("), app.indexOf("private fun connectionKindBadge("))
        assertTrue(shelf.contains("onLoadThumbnail(favorite)"))
        assertTrue(shelf.contains("val thumbnail = thumbnails[favorite.id]"))
        assertTrue(shelf.contains("SkinAwareThumbnailImage("))
        assertTrue(shelf.contains("bitmap = thumbnail"))
        assertTrue(shelf.contains("Icon(favoriteIcon(favorite)"))
        assertTrue(shelf.contains("onLongClick = { onRemoveRequest(favorite) }"))
        assertTrue(shelf.contains("onMove(favorite.id"))

        val loader = viewModel.substring(viewModel.indexOf("fun loadFavoriteThumbnail("), viewModel.indexOf("private fun updateFavoriteMetadata("))
        assertTrue(loader.contains("FavoriteItemResolver.resolve(favorite, connection, activeService)"))
        assertTrue(loader.contains("thumbnailRepository.load(connection, resolvedItem, activeService)"))
        assertTrue(loader.contains("favoriteThumbnailRequests.add(requestKey)"))
    }

    private fun sourceFile(relativePath: String): File {
        val working = File(System.getProperty("user.dir")).canonicalFile
        return generateSequence(working) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Cannot locate $relativePath from $working")
    }
}
