package com.rustamguloff.foliolink.ui.screensaver

import com.rustamguloff.foliolink.app.AppModel
import com.rustamguloff.foliolink.core.stats.StatsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/** Раскладка шаблонов студии: перенос, подбор кегля, размещение фото, календарь. */
class TemplateLayoutTest {
    /** Моноширинная «метрика»: каждый символ — половина кегля. */
    private fun mono(size: Float) = { s: String -> s.length * size * 0.5f }
    private val measure = { s: String, size: Float -> s.length * size * 0.5f }
    private val lineHeight = { size: Float -> size * 1.2f }

    @Test
    fun wrapKeepsExplicitBreaksAndFillsLinesGreedily() {
        // 10 символов по 5 px в строке шириной 50.
        val w = mono(10f)
        assertEquals(listOf("Читать —", "значит", "жить", "дважды"), TextFit.wrap("Читать —\nзначит жить\nдважды", 50f, w))
        assertEquals(listOf("aaa bbb", "ccc"), TextFit.wrap("aaa bbb ccc", 35f, w))
        assertEquals("empty paragraph stays", listOf("a", "", "b"), TextFit.wrap("a\n\nb", 50f, w))
        assertEquals("spaces collapse", listOf("a b"), TextFit.wrap("  a   b  ", 50f, w))
    }

    @Test
    fun wrapBreaksWordsWiderThanTheLine() {
        val lines = TextFit.wrap("x abcdefghijklmnop", 25f, mono(10f)) // 5 символов в строке
        assertEquals(listOf("x", "abcde", "fghij", "klmno", "p"), lines)
        assertTrue(lines.all { it.length * 5f <= 25f })
        // Ширина меньше одного символа: по символу в строке, без зацикливания.
        assertEquals(listOf("a", "b", "c"), TextFit.wrap("abc", 1f, mono(10f)))
    }

    @Test
    fun fitKeepsTheLargestSizeThatFits() {
        val f = TextFit.fit("short", maxWidth = 400f, maxHeight = 200f, maxSize = 42f, minScale = 0.35f,
            lineHeight = lineHeight, measure = measure)
        assertEquals(42f, f.size)
        assertEquals(listOf("short"), f.lines)
        assertEquals(42f * 1.2f, f.height, 0.001f)
    }

    @Test
    fun fitShrinksALongQuoteUntilItFits() {
        val quote = List(40) { "слово" }.joinToString(" ")
        val f = TextFit.fit(quote, maxWidth = 392f, maxHeight = 300f, maxSize = 42f, minScale = 0.35f,
            lineHeight = lineHeight, measure = measure)
        assertTrue("shrunk: ${f.size}", f.size < 42f && f.size >= 42f * 0.35f)
        assertTrue(f.height <= 300.5f)
        assertTrue(f.lines.all { measure(it, f.size) <= 392f })
        assertEquals("nothing lost", quote, f.lines.joinToString(" "))
        // На кегль крупнее уже не помещалось.
        val bigger = f.size + 1
        val lines = TextFit.wrap(quote, 392f) { measure(it, bigger) }
        assertTrue(lines.size * lineHeight(bigger) > 300f)
    }

    @Test
    fun fitRespectsMaxLinesAndEllipsizesAtTheMinimum() {
        val name = "Константин Константинопольский-Задунайский"
        val two = TextFit.fit(name, 416f, 112f, 40f, minScale = 0.5f, maxLines = 2, lineHeight = lineHeight, measure = measure)
        assertTrue(two.lines.size <= 2)
        val huge = List(200) { "word" }.joinToString(" ")
        val cut = TextFit.fit(huge, 200f, 100f, 30f, minScale = 0.5f, lineHeight = lineHeight, measure = measure)
        assertEquals(15f, cut.size)
        assertEquals((100f / 18f).toInt(), cut.lines.size)
        assertTrue(cut.lines.last().endsWith("…"))
        assertTrue(cut.lines.all { measure(it, 15f) <= 200f })
    }

    @Test
    fun photoPlacementFillFitZoomAndOffset() {
        // Альбомное 1000×600 на портретной панели.
        val fill = PanelGeometry.place(1000, 600, fill = true)
        assertEquals(800f, fill.height, 0.01f)
        assertEquals(1333.33f, fill.width, 0.01f)
        assertEquals((480 - 1333.333f) / 2, fill.left, 0.01f)
        val fit = PanelGeometry.place(1000, 600, fill = false)
        assertEquals(480f, fit.width, 0.01f)
        assertEquals(288f, fit.height, 0.01f)
        assertEquals(256f, fit.top, 0.01f)
        // Увеличение вокруг центра, сдвиг на полпанели.
        val z = PanelGeometry.place(480, 800, fill = true, zoom = 2.0, offsetX = 1.0, offsetY = -1.0)
        assertEquals(960f, z.width, 0.01f)
        assertEquals(-240f + 240f, z.left, 0.01f)
        assertEquals(-400f - 400f, z.top, 0.01f)
        // Ровно 480×800 ложится пиксель в пиксель.
        assertEquals(FRect(0f, 0f, 480f, 800f), PanelGeometry.place(480, 800, fill = true))
        // Круглое фото визитки 170×170.
        val card = PanelGeometry.place(300, 600, fill = true, pw = 170, ph = 170)
        assertEquals(170f, card.width, 0.01f)
        assertEquals(-85f, card.top, 0.01f)
    }

    @Test
    fun calendarGridFitsThePanel() {
        val cell = CalendarLayout.cellSize(CalendarLayout.WEEKS)
        assertEquals((480 - 64 - 25 * 3f) / 26, cell, 0.001f)
        val (lastX, lastY) = CalendarLayout.cellOrigin(25, 6, cell)
        assertTrue("last column inside", 32 + lastX + cell <= 480 - 32 + 0.01f)
        assertEquals(CalendarLayout.gridHeight(cell), lastY + cell, 0.001f)
        // Мало недель — клетка не больше 14.
        assertEquals(14f, CalendarLayout.cellSize(4))
        assertEquals(14f, CalendarLayout.cellSize(0))
    }

    @Test
    fun calendarHeatmapIs26WeeksEndingToday() {
        val today = LocalDate.of(2026, 10, 22) // четверг
        val daily = mapOf(StatsEngine.dayKey(today) to 45 * 60, StatsEngine.dayKey(today.minusDays(1)) to 5 * 60)
        val cols = StatsEngine.heatmap(daily, today, CalendarLayout.WEEKS, DayOfWeek.MONDAY)
        assertEquals(26, cols.size)
        assertTrue(cols.all { it.size == 7 })
        val last = cols.last()
        assertEquals(LocalDate.of(2026, 10, 19), last.first().date)
        assertEquals(3, last[3].level)
        assertEquals(1, last[2].level)
        assertTrue("rest of the week is in the future", last.drop(4).all { it.isFuture })
    }

    @Test
    fun calendarGraysMatchIos() {
        assertEquals(listOf(255, 168, 128, 84, 0), (0..4).map { CalendarLayout.gray(it, inverted = false) })
        assertEquals(listOf(0, 84, 128, 168, 255), (0..4).map { CalendarLayout.gray(it, inverted = true) })
        assertEquals(0, CalendarLayout.gray(9, inverted = false))
    }

    @Test
    fun fileNames() {
        assertEquals("card.bmp", bmpName(" card "))
        assertEquals("CARD.BMP", bmpName("CARD.BMP"))
        assertEquals("foliolink-idCard-20261022-093005.bmp",
            AppModel.defaultSleepName(ScreensaverTemplate.ID_CARD.raw, LocalDateTime.of(2026, 10, 22, 9, 30, 5)))
        assertEquals(listOf("photo", "idCard", "quote", "poster", "calendar"), ScreensaverTemplate.entries.map { it.raw })
    }
}
