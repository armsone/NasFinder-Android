package com.armsone.nasfinder.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NasFinderAppWidgetProviderTest {
    @Test
    fun `widget is a stateless app opener like the iOS accessory widget`() {
        val source = sourceFile(
            "app/src/main/java/com/armsone/nasfinder/platform/NasFinderAppWidgetProvider.kt",
        ).readText()

        assertTrue("Missing widget open action", "setOnClickPendingIntent" in source)
        assertFalse("Widget must not expose connection state", "ConnectionRepository" in source)
        assertFalse("Widget must not expose inbox state", "SharedInboxStore" in source)
    }

    private fun sourceFile(relativePath: String): File {
        val working = File(System.getProperty("user.dir")).canonicalFile
        return generateSequence(working) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Cannot locate $relativePath from $working")
    }
}
