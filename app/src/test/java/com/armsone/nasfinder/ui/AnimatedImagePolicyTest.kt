package com.armsone.nasfinder.ui

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedImagePolicyTest {
    @Test fun `multi-frame gif is detected as animated from a stream and from a file`() {
        val bytes = gif(frames = 2)
        assertTrue(AnimatedImagePolicy.isAnimatedGif(ByteArrayInputStream(bytes)))
        val file = File.createTempFile("animated", ".gif").apply { deleteOnExit(); writeBytes(bytes) }
        assertTrue(AnimatedImagePolicy.isAnimatedGif(file))
        assertTrue(AnimatedImagePolicy.isAnimatedGif(ByteArrayInputStream(gif(frames = 3, localColorTable = true, graphicControl = true))))
        assertTrue(AnimatedImagePolicy.isAnimatedGif(ByteArrayInputStream(gif(frames = 2, globalColorTable = false, version = "GIF87a"))))
    }

    @Test fun `single-frame gif and non-gif inputs fall back to the static image path`() {
        assertFalse(AnimatedImagePolicy.isAnimatedGif(ByteArrayInputStream(gif(frames = 1))))
        assertFalse(AnimatedImagePolicy.isAnimatedGif(ByteArrayInputStream(gif(frames = 1, graphicControl = true))))
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(32)
        assertFalse(AnimatedImagePolicy.isAnimatedGif(ByteArrayInputStream(png)))
        assertFalse(AnimatedImagePolicy.isAnimatedGif(ByteArrayInputStream(ByteArray(0))))
        assertFalse(AnimatedImagePolicy.isAnimatedGif(ByteArrayInputStream("GIF89a".toByteArray())))
        val truncated = gif(frames = 2).let { it.copyOf(it.size - 24) }
        assertFalse(AnimatedImagePolicy.isAnimatedGif(ByteArrayInputStream(truncated)))
        assertFalse(AnimatedImagePolicy.isAnimatedGif(File("/nonexistent/animated.gif")))
    }

    @Test fun `animated decode keeps the same pixel bound as the still decoder`() {
        assertEquals(1, AnimatedImagePolicy.sampleSize(4096, 4096))
        assertEquals(2, AnimatedImagePolicy.sampleSize(8000, 100))
        assertEquals(4, AnimatedImagePolicy.sampleSize(100, 16_384))
        assertEquals(1, AnimatedImagePolicy.sampleSize(0, 0))
    }

    @Test fun `every viewer route feeds the shared preview with the animated flag and the viewer plays it`() {
        val viewModel = sourceFile("app/src/main/java/com/armsone/nasfinder/ui/NasFinderViewModel.kt").readText()
        val app = sourceFile("app/src/main/java/com/armsone/nasfinder/ui/NasFinderApp.kt").readText()

        // Producer: the single image decode used by remote/NAS, favorites, PhoneHard, inbox and external imports.
        assertTrue(viewModel.contains("BuiltInPreviewKind.IMAGE -> decodePreviewBitmap(file)?.let {"))
        assertTrue(viewModel.contains("PreviewDecode(it, animatedImage = AnimatedImagePolicy.isAnimatedGif(file))"))
        // Both ImagePreviewState producers that carry a decoded bitmap forward the flag.
        assertEquals(2, viewModel.split("animatedImage = decoded.animatedImage").size - 1)
        // Remote images always go through the download+decode producer, never the range-stream route.
        assertTrue(viewModel.contains("val mediaRoute = if (previewKind in setOf(BuiltInPreviewKind.AUDIO, BuiltInPreviewKind.VIDEO))"))
        // Local routes (PhoneHard inbox, shared imports, external opens) all converge on prepareLocalPreview.
        assertTrue(viewModel.contains("prepareLocalPreview(supported.map { it.first }, supported.map { it.second }, index)"))

        // Consumer: the shared viewer animates the file and keeps the still frame as fallback.
        assertTrue(app.contains("animatedFile = preview.cachedFile.takeIf { preview.kind == BuiltInPreviewKind.IMAGE && preview.animatedImage }"))
        assertTrue(app.contains("AnimatedGifPreview(file = animatedFile, fallback = bitmap, description = description)"))
        assertTrue(app.contains("ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))"))
        assertTrue(app.contains("decoder.setTargetSampleSize(AnimatedImagePolicy.sampleSize(info.size.width, info.size.height))"))
        assertTrue(app.contains("file.inputStream().buffered().use(Movie::decodeStream)"))
        assertTrue(app.contains("LegacyGifImageView"))
        assertTrue(app.contains("drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE"))
        assertTrue(app.contains("view.drawable?.let(::stopAnimatedDrawable)"))
        // Zoom, pan and orientation handling stay on the shared container above the image content.
        val zoomable = app.substring(app.indexOf("private fun ZoomablePreviewBitmap("), app.indexOf("private fun AnimatedGifPreview("))
        assertTrue(zoomable.contains("MediaViewerGesturePolicy.DOUBLE_TAP_ZOOM_SCALE"))
        assertTrue(zoomable.contains("MediaViewerGesturePolicy.clampImageTranslation("))
        assertTrue(zoomable.contains("if (animatedFile != null) {"))
        assertTrue(viewModel.contains("val transform = exifPixelTransform(orientation)"))
    }

    private fun sourceFile(relativePath: String): File {
        val working = File(System.getProperty("user.dir")).canonicalFile
        return generateSequence(working) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Cannot locate $relativePath from $working")
    }

    /** Builds a minimal but structurally valid GIF with the requested number of image frames. */
    private fun gif(
        frames: Int,
        globalColorTable: Boolean = true,
        localColorTable: Boolean = false,
        graphicControl: Boolean = false,
        version: String = "GIF89a",
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(version.toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(2, 0, 2, 0)) // logical screen 2x2
        out.write(if (globalColorTable) 0x80 else 0x00) // packed: global table with 2 entries
        out.write(byteArrayOf(0, 0)) // background index, aspect ratio
        if (globalColorTable) out.write(ByteArray(6))
        repeat(frames) {
            if (graphicControl) out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0, 10, 0, 0, 0))
            out.write(0x2C)
            out.write(byteArrayOf(0, 0, 0, 0, 2, 0, 2, 0)) // left, top, width, height
            out.write(if (localColorTable) 0x80 else 0x00)
            if (localColorTable) out.write(ByteArray(6))
            out.write(2) // LZW minimum code size
            out.write(byteArrayOf(2, 0x44, 0x01, 0)) // one data sub-block, then terminator
        }
        out.write(0x3B)
        return out.toByteArray()
    }
}
