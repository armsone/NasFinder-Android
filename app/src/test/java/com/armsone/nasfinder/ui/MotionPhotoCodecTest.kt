package com.armsone.nasfinder.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPhotoCodecTest {
    private val jpeg = byteArrayOf(
        0xff.toByte(), 0xd8.toByte(),
        0xff.toByte(), 0xda.toByte(),
        0xff.toByte(), 0xd9.toByte(),
    )
    private val mp4 = byteArrayOf(0, 0, 0, 8, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())

    @Test
    fun writesAndStrictlyParsesMotionPhoto10() {
        val encoded = MotionPhotoCodec.create(jpeg, mp4, 123_456)
        val layout = checkNotNull(MotionPhotoCodec.parse(encoded))

        assertEquals(123_456L, layout.presentationTimestampUs)
        assertArrayEquals(mp4, MotionPhotoCodec.motionVideo(encoded, layout))
        assertTrue(MotionPhotoCodec.primaryImage(encoded, layout).takeLast(2).toByteArray().contentEquals(byteArrayOf(0xff.toByte(), 0xd9.toByte())))
    }

    @Test
    fun preservesQuickTimeMimeForIphonePairedVideo() {
        val quickTime = byteArrayOf(
            0, 0, 0, 12,
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'q'.code.toByte(), 't'.code.toByte(), ' '.code.toByte(), ' '.code.toByte(),
        )
        val encoded = MotionPhotoCodec.create(
            jpeg,
            quickTime,
            presentationTimestampUs = -1,
            videoMimeType = "video/quicktime",
        )
        val layout = checkNotNull(MotionPhotoCodec.parse(encoded))

        assertArrayEquals(quickTime, MotionPhotoCodec.motionVideo(encoded, layout))
        assertTrue(encoded.toString(Charsets.ISO_8859_1).contains("Item:Mime=\"video/quicktime\""))
    }

    @Test
    fun rejectsMalformedContainerLengthInsteadOfSearchingForFtyp() {
        val encoded = MotionPhotoCodec.create(jpeg, mp4)
        val marker = "Item:Length=\"8\"".toByteArray()
        val offset = encoded.indexOfSubsequence(marker)
        require(offset >= 0)
        encoded[offset + marker.size - 2] = '9'.code.toByte()

        assertNull(MotionPhotoCodec.parse(encoded))
        assertNull(MotionPhotoCodec.parse(jpeg + mp4))
    }

    @Test
    fun motionXmpInsertionPreservesExifApp1() {
        val exifPayload = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
        val jpegWithExif = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(),
            0xff.toByte(), 0xe1.toByte(), 0, 8,
        ) + exifPayload + byteArrayOf(0xff.toByte(), 0xda.toByte(), 0xff.toByte(), 0xd9.toByte())

        val encoded = MotionPhotoCodec.create(jpegWithExif, mp4)

        assertTrue(encoded.indexOfSubsequence(exifPayload) >= 0)
        assertTrue(encoded.toString(Charsets.ISO_8859_1).contains("Camera:MotionPhoto=\"1\""))
    }

    private fun ByteArray.indexOfSubsequence(needle: ByteArray): Int =
        indices.firstOrNull { start -> start + needle.size <= size && needle.indices.all { this[start + it] == needle[it] } } ?: -1
}
