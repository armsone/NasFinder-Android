package com.armsone.nasfinder.platform

import com.armsone.nasfinder.data.SharedInboxStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class NasFinderAppWidgetProviderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun countUsesManifestRecordsInsteadOfEveryInboxFile() {
        val filesDirectory = temporary.newFolder("files")
        val store = SharedInboxStore(filesDirectory)
        store.import("one.txt", "text/plain", ByteArrayInputStream(byteArrayOf(1)))
        store.import("two.txt", "text/plain", ByteArrayInputStream(byteArrayOf(2)))
        val inbox = File(filesDirectory, "SharedInbox")
        File(inbox, "orphan.bin").writeBytes(byteArrayOf(3))

        assertEquals(2, widgetInboxRecordCount(filesDirectory))
        assertEquals(4, inbox.listFiles().orEmpty().count(File::isFile))
    }

    @Test fun unreadableManifestFailsClosedToZero() {
        val filesDirectory = temporary.newFolder("files")
        val inbox = File(filesDirectory, "SharedInbox").apply { mkdirs() }
        File(inbox, "manifest.json").writeText("not-json")
        File(inbox, "orphan.bin").writeBytes(byteArrayOf(1))

        assertEquals(0, widgetInboxRecordCount(filesDirectory))
    }
}
