package com.armsone.nasfinder.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailSkinTonePolicyTest {
    private val lightSkin = argb(255, 224, 172, 138)
    private val darkSkin = argb(255, 92, 55, 38)
    private val blue = argb(255, 35, 92, 210)
    private val transparentSkin = argb(0, 224, 172, 138)

    @Test
    fun `exactly fifty percent visible skin is blurred`() {
        assertTrue(ThumbnailSkinTonePolicy.shouldBlurArgb(intArrayOf(lightSkin, darkSkin, blue, blue)))
    }

    @Test
    fun `just below fifty percent is not blurred`() {
        assertFalse(ThumbnailSkinTonePolicy.shouldBlurArgb(intArrayOf(lightSkin, blue, blue)))
    }

    @Test
    fun `more than fifty percent mixed light and dark skin is blurred`() {
        assertTrue(ThumbnailSkinTonePolicy.shouldBlurArgb(intArrayOf(lightSkin, darkSkin, lightSkin, blue)))
    }

    @Test
    fun `non skin colors are not blurred`() {
        val green = argb(255, 40, 180, 70)
        val gray = argb(255, 128, 128, 128)
        assertFalse(ThumbnailSkinTonePolicy.shouldBlurArgb(intArrayOf(blue, green, gray)))
    }

    @Test
    fun `transparent skin pixels do not affect visible ratio`() {
        assertFalse(ThumbnailSkinTonePolicy.shouldBlurArgb(intArrayOf(transparentSkin, transparentSkin, blue)))
        assertTrue(ThumbnailSkinTonePolicy.shouldBlurArgb(intArrayOf(transparentSkin, lightSkin)))
    }

    @Test
    fun `classification cache reuses identity and rescans a new revision`() {
        val cache = ThumbnailSkinToneClassificationCache(maxEntries = 4)
        var scans = 0
        val first = ThumbnailSkinToneCacheIdentity.effective("remote:path:size:modified-1", 32, 32, 8)
        val revised = ThumbnailSkinToneCacheIdentity.effective("remote:path:size:modified-2", 32, 32, 9)

        assertTrue(cache.getOrClassify(first) { scans++; true })
        assertTrue(cache.getOrClassify(first) { scans++; false })
        assertFalse(cache.getOrClassify(revised) { scans++; false })
        assertEquals(2, scans)
    }

    @Test
    fun `rendering policy applies exactly two dp only to classified thumbnails`() {
        assertEquals(2f, ThumbnailBlurRenderingPolicy.radiusDp(true), 0f)
        assertEquals(0f, ThumbnailBlurRenderingPolicy.radiusDp(false), 0f)
    }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}
