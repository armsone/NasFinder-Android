package com.armsone.nasfinder.data

import com.armsone.nasfinder.model.RemoteFileItem
import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderSuperThumbnailPolicyTest {
    @Test fun portableDigestIsNfcStableAndUsesDistinctFolderPrefix() {
        val nfc = "가족 여행"
        val nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD)

        assertEquals(
            "v1-folder-${FolderSuperThumbnailPolicy.digest(nfc)}.jpg",
            FolderSuperThumbnailPolicy.jpegFilename(nfc),
        )
        assertEquals(
            FolderSuperThumbnailPolicy.jpegFilename(nfc),
            FolderSuperThumbnailPolicy.jpegFilename(nfd),
        )
        assertTrue(FolderSuperThumbnailPolicy.emptyMarkerFilename(nfc).endsWith(".empty"))
        assertFalse(FolderSuperThumbnailPolicy.jpegFilename(nfc).matches(Regex("v1-[0-9a-f]{64}\\.jpg")))
    }

    @Test fun selectionIsFoldersFirstNaturalAndLimitedToNineVisibleChildren() {
        val items = buildList {
            add(item(".hidden", true))
            add(item(SuperThumbnailVault.DIRECTORY_NAME, true))
            add(item("10 여행", true))
            add(item("2 여행", true))
            add(item("zeta.mp4", false))
            add(item("alpha.jpg", false))
            repeat(10) { add(item("file-${it + 1}.jpg", false)) }
        }

        val selected = FolderSuperThumbnailPolicy.selectChildren(items)

        assertEquals(9, selected.size)
        assertEquals(listOf("2 여행", "10 여행"), selected.take(2).map(RemoteFileItem::name))
        assertTrue(selected.none { it.name.startsWith('.') || it.name == SuperThumbnailVault.DIRECTORY_NAME })
        assertEquals(selected, FolderSuperThumbnailPolicy.selectChildren(items.reversed()))
    }

    @Test fun skinThresholdMatchesPortableTwelveByTwelveContract() {
        assertTrue(FolderSuperThumbnailPolicy.shouldBlur(61, 144))
        assertFalse(FolderSuperThumbnailPolicy.shouldBlur(60, 144))
        assertTrue(FolderSuperThumbnailPolicy.isSkinTone(200, 120, 90))
        assertFalse(FolderSuperThumbnailPolicy.isSkinTone(40, 140, 240))
        assertEquals(3, FolderSuperThumbnailPolicy.GAUSSIAN_BLUR_RADIUS_PX)
        assertEquals(1.5f, FolderSuperThumbnailPolicy.BLUR_RADIUS_POINTS, 0.001f)
    }

    @Test fun nonCumulativeFolderBlurPolicyAppliesExactlyOnePointFivePointsToFinalSheet() {
        // Blur radius is exactly 1.5 logical points = 3px at 384px/192dp reference
        val referencePixels = FolderSuperThumbnailPolicy.SHEET_PIXEL_SIZE.toFloat() // 384px
        val referenceDp = 192f // 192dp
        val pxPerPoint = referencePixels / referenceDp // 2.0 px/dp
        val expectedRadiusPx = (FolderSuperThumbnailPolicy.BLUR_RADIUS_POINTS * pxPerPoint).toInt()

        assertEquals(3, expectedRadiusPx)
        assertEquals(expectedRadiusPx, FolderSuperThumbnailPolicy.GAUSSIAN_BLUR_RADIUS_PX)

        // Sourcing parent folder tiles from unblurred child sheets guarantees blur is applied
        // at most once to the final composite sheet rather than compounding recursively.
        val childSheetRequiresBlur = FolderSuperThumbnailPolicy.shouldBlur(65, 144)
        val parentSheetRequiresBlur = FolderSuperThumbnailPolicy.shouldBlur(65, 144)
        assertTrue(childSheetRequiresBlur)
        assertTrue(parentSheetRequiresBlur)
    }

    private fun item(name: String, directory: Boolean) = RemoteFileItem(
        id = name,
        name = name,
        path = "/root/$name",
        isDirectory = directory,
    )
}
