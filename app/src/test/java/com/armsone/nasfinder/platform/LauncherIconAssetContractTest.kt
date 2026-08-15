package com.armsone.nasfinder.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class LauncherIconAssetContractTest {
    @Test
    fun `launcher assets preserve the exact user supplied PNG bytes`() {
        val expected = linkedMapOf(
            "app_icon_blue_nas.png" to "281c7030e7890b8f858dd2638c489a17a193c55b1772d7f16d3a0d939e40a5ba",
            "app_icon_cyber_vault.png" to "2cd8837024481f67131ae924fcfa562a6a0fc13d0ea7e1d173b6210e8384671b",
            "app_icon_vibe_coder.png" to "302ce914160732412bbeaebd607bfe960d8ab8fdd9b82a267df27474549b0b12",
            "app_icon_purple_nas.png" to "609f5bec2d685d23e992301721724ea7c2810067dab86c34ebf118a4b49e4253",
        )
        val resourceDirectory = locateResourceDirectory()

        expected.forEach { (name, digest) ->
            val asset = File(resourceDirectory, name)
            assertTrue("Missing exact launcher asset: $name", asset.isFile)
            assertEquals(digest, asset.sha256())
        }
    }

    private fun locateResourceDirectory(): File {
        val workingDirectory = File(System.getProperty("user.dir")).canonicalFile
        val candidates = generateSequence(workingDirectory) { it.parentFile }
            .flatMap { parent ->
                sequenceOf(
                    File(parent, "app/src/main/res/drawable-nodpi"),
                    File(parent, "src/main/res/drawable-nodpi"),
                )
            }
        return candidates.firstOrNull(File::isDirectory)
            ?: error("Cannot locate drawable-nodpi from $workingDirectory")
    }

    private fun File.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(readBytes())
        .joinToString("") { "%02x".format(it) }
}
