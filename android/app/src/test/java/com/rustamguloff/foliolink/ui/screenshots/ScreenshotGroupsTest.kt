package com.rustamguloff.foliolink.ui.screenshots

import com.rustamguloff.foliolink.core.model.RemoteFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Порядок и группировка скриншотов по книгам (подпапки /screenshots/<название>/). */
class ScreenshotGroupsTest {
    private fun shot(name: String, book: String? = null) =
        RemoteFile(name, if (book.isNullOrEmpty()) "/screenshots/$name" else "/screenshots/$book/$name", 100, book)

    @Test
    fun newestFirstThenUntimedByName() {
        val files = listOf(
            shot("screenshot-1760000000.bmp"),
            shot("b.bmp"),
            shot("Dune_p12_1760500000.bmp", "Dune"),
            shot("a.bmp"),
            shot("screenshot-1760200000.bmp"),
            shot("screenshot-12.bmp"), // время до синхронизации часов — как без времени
        )
        assertEquals(
            listOf(
                "Dune_p12_1760500000.bmp", "screenshot-1760200000.bmp", "screenshot-1760000000.bmp",
                "screenshot-12.bmp", "b.bmp", "a.bmp",
            ),
            ScreenshotGroups.sorted(files).map { it.name },
        )
        assertEquals(1760500000L, files[2].capturedAt)
        assertNull(files[5].capturedAt)
    }

    @Test
    fun groupsByBookOrderedByNewestShot() {
        val files = listOf(
            shot("Dune_1760000000.bmp", "Dune"),
            shot("screenshot-1760300000.bmp"),
            shot("Emma_1760100000.bmp", "Emma"),
            shot("Dune_1760400000.bmp", "Dune"),
            shot("Emma_1760050000.bmp", "Emma"),
            shot("odd.bmp", ""), // пустая книга = без книги
        )
        val groups = ScreenshotGroups.group(files)
        assertEquals(listOf("Dune", null, "Emma"), groups.map { it.book })
        assertEquals(listOf("Dune_1760400000.bmp", "Dune_1760000000.bmp"), groups[0].files.map { it.name })
        assertEquals(listOf("screenshot-1760300000.bmp", "odd.bmp"), groups[1].files.map { it.name })
        assertEquals(listOf("Emma_1760100000.bmp", "Emma_1760050000.bmp"), groups[2].files.map { it.name })
        assertEquals(files.size, groups.sumOf { it.files.size })
        assertEquals(listOf("Dune", "", "Emma"), groups.map { it.key })
        assertTrue(ScreenshotGroups.showsHeaders(groups))
    }

    @Test
    fun withoutBooksItIsAPlainGrid() {
        val groups = ScreenshotGroups.group(listOf(shot("a.bmp"), shot("b.bmp")))
        assertEquals(1, groups.size)
        assertFalse(ScreenshotGroups.showsHeaders(groups))
        assertTrue(ScreenshotGroups.group(emptyList()).isEmpty())
        assertFalse(ScreenshotGroups.showsHeaders(emptyList()))
    }
}
