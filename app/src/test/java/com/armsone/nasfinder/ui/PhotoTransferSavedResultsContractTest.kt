package com.armsone.nasfinder.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoTransferSavedResultsContractTest {
    @Test
    fun completionTypeComesFromPublishedMediaStoreRow() {
        val source = sourceFile(
            "app/src/main/java/com/armsone/nasfinder/ui/PhotoTransferPairing.kt",
        ).readText()

        assertTrue(source.contains("querySavedPhotoTransferResult("))
        assertTrue(source.contains("MediaStore.MediaColumns.MIME_TYPE"))
        assertTrue(source.contains("MediaStore.Files.FileColumns.SPECIAL_FORMAT"))
        assertTrue(source.contains("SPECIAL_FORMAT_MOTION_PHOTO"))
    }

    @Test
    fun completionUiKeepsBadgeWhenThumbnailIsUnavailable() {
        val source = sourceFile(
            "app/src/main/java/com/armsone/nasfinder/ui/PhotoTransferScreen.kt",
        ).readText()

        assertTrue(source.contains("receivedResults = results"))
        assertTrue(source.contains("loadSavedPhotoTransferThumbnail(context, result)"))
        assertTrue(source.contains("result.kind.displayName"))
        assertTrue(source.contains("if (loadedThumbnail != null)"))
        assertTrue(source.contains("context.contentResolver.loadThumbnail(result.uri"))
    }

    @Test
    fun selectionReviewMatchesPairingViewsThumbnailGridContract() {
        val source = sourceFile(
            "app/src/main/java/com/armsone/nasfinder/ui/PhotoTransferScreen.kt",
        ).readText()

        assertTrue(source.contains("AdaptiveSelectedMediaGrid"))
        assertTrue(source.contains(".height(112.dp)"))
        assertTrue(source.contains("RoundedCornerShape(12.dp)"))
        assertTrue(source.contains("MediaKindBadge("))
        assertTrue(source.contains("formatDuration(item.durationMs)"))
        assertTrue(source.contains("contentDescription = \"항목 \${index + 1} 삭제\""))
        assertTrue(!source.contains("Text(item.displayName"))
    }

    private fun sourceFile(relativePath: String): File {
        val working = File(System.getProperty("user.dir")).canonicalFile
        return generateSequence(working) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull { it.isFile }
            ?: error("Cannot locate $relativePath from $working")
    }
}
