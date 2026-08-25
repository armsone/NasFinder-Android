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
        assertEquals(4, FolderSuperThumbnailPolicy.GAUSSIAN_BLUR_RADIUS_PX)
    }

    private fun item(name: String, directory: Boolean) = RemoteFileItem(
        id = name,
        name = name,
        path = "/root/$name",
        isDirectory = directory,
    )
}
