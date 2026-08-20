package com.armsone.nasfinder.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoTransferPairingTest {
    @Test
    fun payloadRoundTrips() {
        val payload = PhotoPairingPayload("192.168.0.10", 49152, "abcdefghijklmnopqrstuv")
        assertEquals(payload, PhotoPairingPayload.decode(payload.encode()))
    }

    @Test
    fun rejectsUnsupportedOrUnsafePayloads() {
        assertNull(PhotoPairingPayload.decode("https://192.168.0.10/pair"))
        assertNull(PhotoPairingPayload.decode("nasfinder://photo-transfer/pair?version=2&host=192.168.0.10&port=9&token=abcdefghijklmnopqrstuv"))
        assertNull(PhotoPairingPayload.decode("nasfinder://photo-transfer/pair?version=1&host=example.com&port=9&token=abcdefghijklmnopqrstuv"))
        assertNull(PhotoPairingPayload.decode("nasfinder://photo-transfer/pair?version=1&host=192.168.0.10&port=0&token=abcdefghijklmnopqrstuv"))
        assertNull(PhotoPairingPayload.decode("nasfinder://photo-transfer/pair?version=1&host=192.168.0.10&port=9&token=short"))
    }

    @Test
    fun generatedTokenIsUrlSafeAndLongEnough() {
        val token = PhotoPairingPayload.newToken()
        assertTrue(token.length >= 22)
        assertTrue(token.matches(Regex("^[A-Za-z0-9_-]+$")))
    }

    @Test
    fun v2HandshakeIdentifiesPeerPlatformAndRejectsWrongToken() {
        assertEquals(
            PhotoPeerPlatform.IOS,
            parseV2Handshake("NASFINDER_PHOTO/2 expected-token ios", "expected-token"),
        )
        assertEquals(
            PhotoPeerPlatform.ANDROID,
            parseV2Handshake("NASFINDER_PHOTO/2 expected-token android", "expected-token"),
        )
        assertNull(parseV2Handshake("NASFINDER_PHOTO/2 wrong-token ios", "expected-token"))
        assertNull(parseV2Handshake("NASFINDER_PHOTO/2 expected-token windows", "expected-token"))
    }

    @Test
    fun v3HandshakeRequiresGroupedCapability() {
        assertEquals(
            PhotoPeerPlatform.IOS,
            parseV3Handshake("NASFINDER_PHOTO/3 expected-token ios grouped-v1", "expected-token"),
        )
        assertNull(parseV3Handshake("NASFINDER_PHOTO/3 expected-token ios", "expected-token"))
        assertNull(parseV3Handshake("NASFINDER_PHOTO/3 expected-token ios other", "expected-token"))
        assertNull(parseV3Handshake("NASFINDER_PHOTO/3 wrong-token ios grouped-v1", "expected-token"))
    }

    @Test
    fun receiverResultIsExactAndBounded() {
        assertEquals(3, parseTransferResult("RESULT OK 3"))
        assertNull(parseTransferResult("RESULT ERROR"))
        assertNull(parseTransferResult("RESULT OK -1"))
        assertNull(parseTransferResult("RESULT OK 3 extra"))
    }

    @Test
    fun savedResultKindUsesMediaStoreResultInsteadOfSenderKind() {
        assertEquals(SavedPhotoTransferKind.PHOTO, savedPhotoTransferKind("image/jpeg", false))
        assertEquals(SavedPhotoTransferKind.VIDEO, savedPhotoTransferKind("video/mp4", false))
        assertEquals(SavedPhotoTransferKind.MOTION_PHOTO, savedPhotoTransferKind("image/jpeg", true))
        assertEquals(SavedPhotoTransferKind.VIDEO, savedPhotoTransferKind("video/mp4", true))
    }

    @Test
    fun allExifOrientationsNormalizeToPixels() {
        val expected = listOf(
            ExifPixelTransform(0f, false),
            ExifPixelTransform(0f, true),
            ExifPixelTransform(180f, false),
            ExifPixelTransform(180f, true),
            ExifPixelTransform(90f, true),
            ExifPixelTransform(90f, false),
            ExifPixelTransform(-90f, true),
            ExifPixelTransform(-90f, false),
        )

        assertEquals(expected, (1..8).map(::exifPixelTransform))
    }
}
