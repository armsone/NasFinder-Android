package com.armsone.nasfinder.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

class LauncherIconAssetContractTest {
    private data class LauncherAsset(
        val launcherName: String,
        val rawName: String,
        val foregroundName: String,
        val backgroundName: String,
    )

    private val launcherAssets = listOf(
        LauncherAsset("default", "blue_nas", "blue_nas_adaptive_foreground", "blue_nas_background"),
        LauncherAsset("purple_nas", "purple_nas", "purple_nas_adaptive_foreground", "purple_nas_background"),
        LauncherAsset("vibe_coder", "vibe_coder", "vibe_coder_adaptive_foreground", "vibe_coder_background"),
        LauncherAsset("cyber_vault", "cyber_vault", "cyber_vault_adaptive_foreground", "cyber_vault_background"),
        LauncherAsset("nas_radar", "nas_radar", "nas_radar_adaptive_foreground", "nas_radar_background"),
    )

    @Test
    fun `launcher assets preserve the exact user supplied image bytes`() {
        val expected = linkedMapOf(
            "app_icon_blue_nas.png" to "281c7030e7890b8f858dd2638c489a17a193c55b1772d7f16d3a0d939e40a5ba",
            "app_icon_cyber_vault.png" to "2cd8837024481f67131ae924fcfa562a6a0fc13d0ea7e1d173b6210e8384671b",
            "app_icon_vibe_coder.png" to "302ce914160732412bbeaebd607bfe960d8ab8fdd9b82a267df27474549b0b12",
            "app_icon_purple_nas.png" to "609f5bec2d685d23e992301721724ea7c2810067dab86c34ebf118a4b49e4253",
            "app_icon_nas_radar.jpg" to "6d0f9e5965e94de5672bec42bafe399468753dd8143f2587d3c7c73b5c23805c",
        )
        val resourceDirectory = File(locateMainResourceDirectory(), "drawable-nodpi")

        expected.forEach { (name, digest) ->
            val asset = File(resourceDirectory, name)
            assertTrue("Missing exact launcher asset: $name", asset.isFile)
            assertEquals(digest, asset.sha256())
        }
    }

    @Test
    fun `launcher XML preserves full artwork before the OS owned adaptive mask`() {
        val resources = locateMainResourceDirectory()

        launcherAssets.forEach { asset ->
            val rawReference = "@drawable/app_icon_${asset.rawName}"
            val foregroundReference = "@drawable/app_icon_${asset.foregroundName}"
            val backgroundReference = "@color/launcher_${asset.backgroundName}"

            val legacy = xml(File(resources, "mipmap-anydpi/ic_launcher_${asset.launcherName}.xml"))
                .documentElement
            assertEquals("bitmap", legacy.tagName)
            assertEquals("fill", legacy.android("gravity"))
            assertEquals(rawReference, legacy.android("src"))

            listOf("mipmap-anydpi-v26", "mipmap-anydpi-v33").forEach { directory ->
                val adaptive = xml(File(resources, "$directory/ic_launcher_${asset.launcherName}.xml"))
                val foreground = adaptive.getElementsByTagName("foreground").item(0) as Element
                val background = adaptive.getElementsByTagName("background").item(0) as Element
                assertEquals(foregroundReference, foreground.android("drawable"))
                assertEquals(backgroundReference, background.android("drawable"))
                assertEquals(0, adaptive.getElementsByTagName("monochrome").length)
            }

            val inset = xml(File(resources, "drawable/app_icon_${asset.foregroundName}.xml"))
                .documentElement
            assertEquals("inset", inset.tagName)
            assertEquals(rawReference, inset.android("drawable"))
            listOf("insetLeft", "insetTop", "insetRight", "insetBottom").forEach { side ->
                assertEquals("16.6667%", inset.android(side))
            }
        }

        val legacyDigitalRain = xml(File(resources, "mipmap-anydpi/ic_launcher_digital_rain.xml"))
            .documentElement
        assertEquals("@drawable/app_icon_vibe_coder", legacyDigitalRain.android("src"))
        listOf("mipmap-anydpi-v26", "mipmap-anydpi-v33").forEach { directory ->
            val digitalRain = xml(File(resources, "$directory/ic_launcher_digital_rain.xml"))
            assertEquals(
                "@drawable/app_icon_vibe_coder_adaptive_foreground",
                (digitalRain.getElementsByTagName("foreground").item(0) as Element).android("drawable"),
            )
            assertFalse(digitalRain.getElementsByTagName("monochrome").length > 0)
        }
    }

    private fun locateMainResourceDirectory(): File {
        val workingDirectory = File(System.getProperty("user.dir")).canonicalFile
        val candidates = generateSequence(workingDirectory) { it.parentFile }
            .flatMap { parent ->
                sequenceOf(
                    File(parent, "app/src/main/res"),
                    File(parent, "src/main/res"),
                )
            }
        return candidates.firstOrNull(File::isDirectory)
            ?: error("Cannot locate main resources from $workingDirectory")
    }

    private fun xml(file: File) = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(file)

    private fun Element.android(name: String): String =
        getAttributeNS("http://schemas.android.com/apk/res/android", name)

    private fun File.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(readBytes())
        .joinToString("") { "%02x".format(it) }
}
