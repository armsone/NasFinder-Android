package com.armsone.nasfinder.ui

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoTransferWireCodecTest {
    @Test
    fun fileHeaderUsesBigEndianLengthAndRoundTrips() {
        val header = PhotoTransferFileHeader(
            id = "asset-1",
            name = "motion.jpg",
            mimeType = "image/jpeg",
            mediaKind = "motionPhoto",
            sourcePlatform = "android",
            byteLength = 12_345,
        )
        val output = ByteArrayOutputStream()

        PhotoTransferWireCodec.writeFileHeader(output, header)

        val encoded = output.toByteArray()
        val declaredLength = DataInputStream(ByteArrayInputStream(encoded)).readInt()
        assertEquals(encoded.size - 4, declaredLength)
        assertEquals(
            PhotoTransferWireMessage.File(header),
            PhotoTransferWireCodec.readMessage(ByteArrayInputStream(encoded)),
        )
    }

    @Test
    fun platformRouteSeparatesSameAndCrossPlatformTransfers() {
        assertEquals(
            PhotoTransferRoute.SAME_PLATFORM_ORIGINAL,
            photoTransferRoute(PhotoPeerPlatform.ANDROID, PhotoPeerPlatform.ANDROID),
        )
        assertEquals(
            PhotoTransferRoute.CROSS_PLATFORM_CONVERSION,
            photoTransferRoute(PhotoPeerPlatform.ANDROID, PhotoPeerPlatform.IOS),
        )
    }

    @Test
    fun doneHeaderRoundTrips() {
        val output = ByteArrayOutputStream()
        PhotoTransferWireCodec.writeDone(output)

        assertEquals(
            PhotoTransferWireMessage.Done,
            PhotoTransferWireCodec.readMessage(ByteArrayInputStream(output.toByteArray())),
        )
    }

    @Test
    fun rejectsOversizedHeaderBeforeAllocating() {
        val encoded = byteArrayOf(0, 1, 0, 1)
        val result = runCatching {
            PhotoTransferWireCodec.readMessage(ByteArrayInputStream(encoded))
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun extendedGroupedHeaderRoundTripsWithChecksumAndTimestamp() {
        val header = PhotoTransferFileHeader(
            id = "asset-1-video",
            name = "asset.mov",
            mimeType = "video/quicktime",
            mediaKind = "livePhoto",
            sourcePlatform = "ios",
            byteLength = 8,
            itemId = "asset-1",
            groupId = "group-1",
            itemKind = "livePhoto",
            componentRole = "motionVideo",
            componentIndex = 1,
            componentCount = 2,
            sha256 = "ab".repeat(32),
            stillImageTimeUs = 42,
        )
        val output = ByteArrayOutputStream()
        PhotoTransferWireCodec.writeFileHeader(output, header)

        assertEquals(header, (PhotoTransferWireCodec.readMessage(ByteArrayInputStream(output.toByteArray())) as PhotoTransferWireMessage.File).header)
    }

    @Test
    fun groupingRejectsMissingOrMismatchedComponents() {
        val primary = PhotoTransferFileHeader(
            "a", "a.jpg", "image/jpeg", "livePhoto", "ios", 4,
            "item", "group", "livePhoto", "primaryImage", 0, 2, "00".repeat(32), 10,
        )
        val video = primary.copy(
            id = "b", name = "b.mov", mimeType = "video/quicktime",
            componentRole = "motionVideo", componentIndex = 1,
        )
        assertTrue(validatePhotoComponentHeaders(listOf(primary, video)))
        assertTrue(!validatePhotoComponentHeaders(listOf(primary)))
        assertTrue(!validatePhotoComponentHeaders(listOf(primary, video.copy(groupId = "other"))))
    }

    @Test
    fun sha256IsStableAndHeaderRejectsUppercaseChecksum() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            photoTransferSha256("abc".toByteArray()),
        )
        val invalid = PhotoTransferFileHeader(
            "a", "a.jpg", "image/jpeg", "photo", "android", 3,
            sha256 = "AB".repeat(32),
        )
        assertTrue(runCatching { PhotoTransferWireCodec.writeFileHeader(ByteArrayOutputStream(), invalid) }.isFailure)
    }
}
