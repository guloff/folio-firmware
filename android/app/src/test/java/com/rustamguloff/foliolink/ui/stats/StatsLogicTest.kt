package com.rustamguloff.foliolink.ui.stats

import com.rustamguloff.foliolink.core.model.Book
import com.rustamguloff.foliolink.core.model.DeviceStats
import com.rustamguloff.foliolink.core.model.ReadingSession
import com.rustamguloff.foliolink.core.stats.StatsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class StatsLogicTest {
    private fun session(book: String, day: String, secs: Int, pages: Int = 0, pct: Int = -1) =
        ReadingSession(book = book, title = book, start = 0, secs = secs, pages = pages, pct = pct, day = day)

    @Test fun totalsTakeLargerOfDeviceAndLog() {
        val sessions = listOf(
            session("/a.epub", "2026-10-01", 600, pages = 10, pct = 100),
            session("/a.epub", "2026-10-02", 600, pages = 5, pct = 100),
            session("/b.epub", "2026-10-02", 1200, pages = 20, pct = 40),
        )
        val daily = StatsEngine.dailySeconds(sessions)
        val local = StatsLogic.totals(null, sessions, daily)
        assertEquals(StatsLogic.Totals(2400, 35, 3, 1), local)

        val device = DeviceStats(totals = DeviceStats.Totals(secs = 100_000, pages = 10, sessions = 50, books = 4))
        assertEquals(StatsLogic.Totals(100_000, 35, 50, 4), StatsLogic.totals(device, sessions, daily))
    }

    @Test fun weeklyAverageAndScale() {
        val d = LocalDate.of(2026, 10, 5)
        val bars = listOf(StatsEngine.WeekBar(d, 100), StatsEngine.WeekBar(d.plusWeeks(1), 50))
        assertEquals(75, StatsLogic.weeklyAverage(bars))
        assertEquals(0, StatsLogic.weeklyAverage(emptyList()))
        assertEquals(210, StatsLogic.weeklyScaleMax(bars, 210))
        assertEquals(100, StatsLogic.weeklyScaleMax(bars, 0))
        assertEquals(1, StatsLogic.weeklyScaleMax(emptyList(), 0))
    }

    @Test fun monthStartsLabelEachMonthOnce() {
        val start = LocalDate.of(2026, 1, 26)
        val weeks = List(8) { start.plusWeeks(it.toLong()) } // 26.01, 02.02 … 16.03
        assertEquals(listOf(0, 1, 5), StatsLogic.monthStarts(weeks))
    }

    @Test fun monthStartsDropCrowdedFirstLabel() {
        val start = LocalDate.of(2026, 1, 26)
        val weeks = List(8) { start.plusWeeks(it.toLong()) }
        // Январь в первом столбце — обрезок: подпись уступает февралю.
        assertEquals(listOf(1, 5), StatsLogic.monthStarts(weeks, minGap = 3))
    }

    @Test fun peakHour() {
        assertNull(StatsLogic.peakHour(List(24) { 0 }))
        assertEquals(21, StatsLogic.peakHour(List(24) { if (it == 21) 900 else if (it == 7) 300 else 0 }))
    }

    @Test fun bookTitlePrefersLibrary() {
        val stat = StatsEngine.BookStat("/Books/war.epub", "", 10, 1, 0, -1, 0)
        assertEquals("war.epub", StatsLogic.bookTitle(stat, emptyList()))
        assertEquals("War and Peace", StatsLogic.bookTitle(stat, listOf(Book(path = "/Books/war.epub", title = "War and Peace"))))
        assertEquals("Session title", StatsLogic.bookTitle(stat.copy(title = "Session title"), emptyList()))
    }

    private object FakeTexts : StatsLogic.HeatmapTexts {
        override fun duration(seconds: Int) = "${seconds / 60}m"
        override fun shortDate(date: LocalDate) = "${date.dayOfMonth}.${date.monthValue}"
        override fun weekday(date: LocalDate) = date.dayOfWeek.name.lowercase()
        override fun summary(weeks: Int, activeDays: Int, duration: String) = "$weeks weeks, $activeDays days, $duration"
        override fun weekEmpty(range: String) = "Week $range: no reading"
        override fun week(range: String, activeDays: Int, duration: String) = "Week $range: $activeDays days, $duration"
        override fun mostOn(weekday: String, duration: String) = "most on $weekday: $duration"
    }

    private val today = LocalDate.of(2026, 10, 22) // четверг
    private val daily = mapOf("2026-10-19" to 600, "2026-10-21" to 1800, "2026-10-12" to 0)
    private val columns = StatsEngine.heatmap(daily, today, weeks = 2, firstDayOfWeek = DayOfWeek.MONDAY)

    @Test fun weekTextNamesBestDay() {
        assertEquals("Week 19.10 – 22.10: 2 days, 40m. most on wednesday: 30m", StatsLogic.weekText(columns[1], FakeTexts))
    }

    @Test fun weekTextWithoutReading() {
        assertEquals("Week 12.10 – 18.10: no reading", StatsLogic.weekText(columns[0], FakeTexts))
    }

    @Test fun heatmapSummary() {
        assertEquals("2 weeks, 2 days, 40m", StatsLogic.heatmapSummary(columns, FakeTexts))
    }

    @Test fun talkBackStepsAndDoubleTap() {
        assertEquals(0, StatsLogic.stepWeek(0, -1, 53))
        assertEquals(52, StatsLogic.stepWeek(52, 1, 53))
        assertEquals(11, StatsLogic.stepWeek(10, 1, 53))
        assertEquals(LocalDate.of(2026, 10, 21), StatsLogic.dayToOpen(columns[1])?.date)
        // Неделя без чтения: открывается её последний прошедший день.
        assertEquals(LocalDate.of(2026, 10, 18), StatsLogic.dayToOpen(columns[0])?.date)
    }

    @Test fun sessionsOnDay() {
        val s = listOf(session("/a", "2026-10-01", 1), session("/b", "2026-10-02", 2))
        assertEquals(listOf("/b"), StatsLogic.sessionsOn("2026-10-02", s).map { it.book })
    }
}
