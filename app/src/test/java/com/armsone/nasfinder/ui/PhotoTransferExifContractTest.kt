package com.armsone.nasfinder.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoTransferExifContractTest {
    @Test
    fun conversionNormalizesOrientationAndCopiesPracticalExif() {
        val source = sourceFile(
            "app/src/main/java/com/armsone/nasfinder/ui/PhotoTransferPairing.kt",
        ).readText()

        assertTrue(source.contains("ExifInterface.ORIENTATION_TRANSPOSE -> ExifPixelTransform(90f, true)"))
        assertTrue(source.contains("ExifInterface.ORIENTATION_TRANSVERSE -> ExifPixelTransform(-90f, true)"))
        assertTrue(source.contains("ExifInterface.TAG_ORIENTATION,"))
        assertTrue(source.contains("ExifInterface.ORIENTATION_NORMAL.toString()"))
        assertTrue(source.contains("ExifInterface.TAG_DATETIME_ORIGINAL"))
        assertTrue(source.contains("ExifInterface.TAG_GPS_LATITUDE"))
        assertTrue(source.contains("ExifInterface.TAG_MAKE"))
        assertTrue(source.contains("ExifInterface.TAG_LENS_MODEL"))
        assertTrue(source.contains("ExifInterface.TAG_EXPOSURE_TIME"))
        assertTrue(source.contains("ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY"))
        assertTrue(source.contains("ExifInterface.TAG_FOCAL_LENGTH"))
        assertTrue(source.contains("ExifInterface.TAG_IMAGE_DESCRIPTION"))
    }

    @Test
    fun completeJpegTakesOriginalBytePreservingPath() {
        val source = sourceFile(
            "app/src/main/java/com/armsone/nasfinder/ui/PhotoTransferPairing.kt",
        ).readText()
        val ensureJpeg = source.substringAfter("private fun ensureJpeg")

        assertTrue(ensureJpeg.indexOf(") return bytes") < ensureJpeg.indexOf("BitmapFactory.decodeByteArray"))
    }

    @Test
    fun quickTimePairedVideoIsRemuxedToMp4WithoutReencoding() {
        val source = sourceFile(
            "app/src/main/java/com/armsone/nasfinder/ui/PhotoTransferPairing.kt",
        ).readText()

        assertTrue(source.contains("header.mimeType == \"video/quicktime\""))
        assertTrue(source.contains("MediaExtractor()"))
        assertTrue(source.contains("MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4"))
        assertTrue(source.contains("mimeType.startsWith(\"video/\")"))
        assertTrue(source.contains("info.set(0, sampleSize, extractor.sampleTime, extractor.sampleFlags)"))
        assertTrue(source.contains("activeMuxer.setOrientationHint"))
        assertTrue(source.contains("videoMimeType = \"video/mp4\""))
    }

    private fun sourceFile(relativePath: String): File {
        val working = File(System.getProperty("user.dir")).canonicalFile
        return generateSequence(working) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Cannot locate $relativePath from $working")
    }
}
