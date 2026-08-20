package com.armsone.nasfinder.ui

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxThumbnailTest {
    @Test
    fun motionPhotoBadgeRequiresMotionPhotoXmpMetadata() {
        val directory = Files.createTempDirectory("inbox-thumbnail").toFile()
        try {
            val motion = directory.resolve("camera.jpg").apply {
                writeText(
                    """<rdf:Description Camera:MotionPhoto="1" Camera:MotionPhotoVersion="1" """ +
                        """Camera:MotionPhotoPresentationTimestampUs="42"/>""",
                )
            }
            val plain = directory.resolve("plain.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }

            assertTrue(isInboxMotionPhoto(motion))
            assertFalse(isInboxMotionPhoto(plain))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun generatedMotionPhotoFilenameKeepsBadgeFallback() {
        val directory = Files.createTempDirectory("inbox-thumbnail-name").toFile()
        try {
            assertTrue(isInboxMotionPhoto(directory.resolve("IMG_0001MP.jpg").apply { writeBytes(byteArrayOf(1)) }))
            assertFalse(isInboxMotionPhoto(directory.resolve("camp.jpg").apply { writeBytes(byteArrayOf(1)) }))
        } finally {
            directory.deleteRecursively()
        }
    }
}
