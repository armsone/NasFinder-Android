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

    @Test fun skinShareAtOrBelowTwentyPercentAppliesNoBlur() {
        assertEquals(0f, FolderSuperThumbnailPolicy.blurRadiusDp(0, 144), 0.0001f)
        assertEquals(0f, FolderSuperThumbnailPolicy.blurRadiusDp(0.20f), 0.0001f)
        assertEquals(0f, FolderSuperThumbnailPolicy.blurRadiusDp(Float.NaN), 0.0001f)
        // 28/144 ~= 19.4% -> still no blur; 29/144 ~= 20.1% -> already above the 20% cutoff
        assertEquals(0f, FolderSuperThumbnailPolicy.blurRadiusDp(28, 144), 0.0001f)
    }

    @Test fun skinShareAboveTwentyPercentRampsLinearlyAndCapsAtTwoPointFiveDp() {
        // clamp((share - 0.20) / 0.80, 0..1) * 2.5dp
        assertEquals(0f, FolderSuperThumbnailPolicy.blurRadiusDp(0.20f), 0.0001f)
        assertEquals(0.625f, FolderSuperThumbnailPolicy.blurRadiusDp(0.40f), 0.0001f)
        assertEquals(1.25f, FolderSuperThumbnailPolicy.blurRadiusDp(0.60f), 0.0001f)
        assertEquals(2.5f, FolderSuperThumbnailPolicy.blurRadiusDp(1.00f), 0.0001f)
        // Never exceeds the 2.5dp cap even for out-of-range shares.
        assertEquals(2.5f, FolderSuperThumbnailPolicy.blurRadiusDp(1.5f), 0.0001f)
        assertTrue(FolderSuperThumbnailPolicy.isSkinTone(200, 120, 90))
        assertFalse(FolderSuperThumbnailPolicy.isSkinTone(40, 140, 240))
    }

    @Test fun nonCumulativeFolderBlurPolicyNeverExceedsTwoPointFiveDpOnFinalSheet() {
        val pxPerDp = FolderSuperThumbnailPolicy.SHEET_PIXEL_SIZE / FolderSuperThumbnailPolicy.REFERENCE_DP_SIZE
        assertEquals(2f, pxPerDp, 0.0001f)

        val maxRadiusPx = FolderSuperThumbnailPolicy.MAX_BLUR_RADIUS_DP * pxPerDp
        assertEquals(maxRadiusPx, FolderSuperThumbnailPolicy.MAX_BLUR_RADIUS_PX)
        assertEquals(maxRadiusPx, FolderSuperThumbnailPolicy.blurRadiusPx(144, 144), 0.0001f)

        // Sourcing parent folder tiles from unblurred child sheets guarantees blur is applied
        // at most once to the final composite sheet rather than compounding recursively.
        val childSheetRadiusDp = FolderSuperThumbnailPolicy.blurRadiusDp(65, 144)
        val parentSheetRadiusDp = FolderSuperThumbnailPolicy.blurRadiusDp(65, 144)
        assertTrue(childSheetRadiusDp <= FolderSuperThumbnailPolicy.MAX_BLUR_RADIUS_DP)
        assertTrue(parentSheetRadiusDp <= FolderSuperThumbnailPolicy.MAX_BLUR_RADIUS_DP)
    }

    private fun item(name: String, directory: Boolean) = RemoteFileItem(
        id = name,
        name = name,
        path = "/root/$name",
        isDirectory = directory,
    )
}
