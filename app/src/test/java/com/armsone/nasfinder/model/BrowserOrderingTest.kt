package com.armsone.nasfinder.model

import java.time.Instant
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserOrderingTest {
    @Test
    fun `name priority follows the browser menu selection`() {
        val values = listOf(
            RemoteFileItem("k", "가나다", "/가나다", false),
            RemoteFileItem("l", "Alpha", "/Alpha", false),
            RemoteFileItem("n", "10 files", "/10 files", false),
        )

        assertEquals(listOf("10 files", "가나다", "Alpha"), values.sortedWith(BrowserPreferences()).map { it.name })
        assertEquals(
            listOf("가나다", "10 files", "Alpha"),
            values.sortedWith(BrowserPreferences(namePriority = NamePriority.KOREAN_FIRST)).map { it.name },
        )
        assertEquals(
            listOf("Alpha", "10 files", "가나다"),
            values.sortedWith(BrowserPreferences(namePriority = NamePriority.LATIN_FIRST)).map { it.name },
        )
    }
    @Test
    fun `name sort is numeric and keeps digit korean foreign groups`() = withLocale(Locale.KOREAN) {
        val values = listOf(
            item("alpha2"), item("가나"), item("10 files"), item("alpha10"), item("2 files"), item("나라"),
        )

        assertEquals(
            listOf("2 files", "10 files", "가나", "나라", "alpha2", "alpha10"),
            values.sortedWith(BrowserPreferences()).map { it.name },
        )
    }

    @Test
    fun `missing modified dates stay last in both directions`() {
        val old = item("old", modifiedAt = Instant.ofEpochSecond(1))
        val recent = item("recent", modifiedAt = Instant.ofEpochSecond(2))
        val missing = item("missing")

        assertEquals(
            listOf("old", "recent", "missing"),
            listOf(missing, recent, old).sortedWith(
                BrowserPreferences(sortField = SortField.MODIFIED),
            ).map { it.name },
        )
        assertEquals(
            listOf("recent", "old", "missing"),
            listOf(missing, recent, old).sortedWith(
                BrowserPreferences(sortField = SortField.MODIFIED, sortDirection = SortDirection.DESCENDING),
            ).map { it.name },
        )
    }

    @Test
    fun `folders first is independent of descending direction`() {
        val folder = item("z folder", isDirectory = true)
        val file = item("a file")
        assertEquals(
            listOf(folder, file),
            listOf(file, folder).sortedWith(
                BrowserPreferences(sortDirection = SortDirection.DESCENDING, foldersFirst = true),
            ),
        )
    }

    @Test
    fun `search is case accent and width insensitive`() = withLocale(Locale.ENGLISH) {
        assertTrue("CaféＡＢＣ".containsLocalized("cafeabc"))
        assertTrue("사진 폴더".containsLocalized("  사진 "))
        assertFalse("report.pdf".containsLocalized("photo"))
    }

    @Test
    fun `media classification uses mime type and wider platform extensions`() {
        assertTrue(item("no-extension").copy(mimeType = "image/jpeg; charset=binary").isImage)
        assertTrue(item("clip.M2TS").isVideo)
        assertTrue(item("scan").copy(mimeType = "application/pdf").isPdf)
        assertFalse(item("notes.txt").isImage)
    }

    private fun item(
        name: String,
        isDirectory: Boolean = false,
        modifiedAt: Instant? = null,
    ) = RemoteFileItem(
        id = name,
        name = name,
        path = "/$name",
        isDirectory = isDirectory,
        modifiedAt = modifiedAt,
    )

    private inline fun withLocale(locale: Locale, block: () -> Unit) {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(locale)
            block()
        } finally {
            Locale.setDefault(previous)
        }
    }
}
