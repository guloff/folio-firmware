package com.rustamguloff.foliolink.core.stats

import com.rustamguloff.foliolink.core.model.Book
import com.rustamguloff.foliolink.core.model.DeviceStats
import com.rustamguloff.foliolink.core.model.Highlight
import com.rustamguloff.foliolink.core.model.ReadingSession
import com.rustamguloff.foliolink.core.model.SyncSnapshot
import com.rustamguloff.foliolink.core.model.VocabWord
import com.rustamguloff.foliolink.core.stats.StatsEngine.ReviewPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class StatsEngineTest {
    private val zone = ZoneId.of("Asia/Tashkent")
    private val monday = DayOfWeek.MONDAY

    private fun day(key: String) = LocalDate.parse(key)

    private fun session(day: String, secs: Int, book: String = "/Books/a.epub", pct: Int = 10, hour: Int = 21) =
        ReadingSession(
            book = book, title = "A", start = day(day).atStartOfDay(zone).plusHours(hour.toLong()).toEpochSecond(),
            secs = secs, pages = secs / 60, pct = pct, day = day,
        )

    @Test
    fun dailyAggregationMergesStatsDaysWithoutDoubleCounting() {
        val sessions = listOf(session("2026-09-20", 600), session("2026-09-20", 300), session("2026-09-21", 60))
        val days = listOf(DeviceStats.Day("2026-09-20", 900, 15), DeviceStats.Day("2026-09-01", 1200, 20))
        val daily = StatsEngine.dailySeconds(sessions, days)
        assertEquals(900, daily["2026-09-20"])
        assertEquals(60, daily["2026-09-21"])
        assertEquals(1200, daily["2026-09-01"])
        val pages = StatsEngine.dailyPages(sessions, days)
        assertEquals(15, pages["2026-09-20"])
        assertEquals(20, pages["2026-09-01"])
    }

    @Test
    fun sessionsWithoutClockAreIgnoredForDays() {
        val s = ReadingSession(book = "/b", title = "B", start = 0, secs = 999, pages = 1, pct = -1, day = "")
        assertTrue(StatsEngine.dailySeconds(listOf(s)).isEmpty())
        assertFalse(s.hasClock)
    }

    @Test
    fun streakCurrentAndLongest() {
        val daily = mapOf(
            "2026-09-01" to 60, "2026-09-02" to 60, "2026-09-03" to 60, "2026-09-04" to 60, // 4 дня
            "2026-09-10" to 60,
            "2026-09-24" to 60, "2026-09-25" to 60, "2026-09-26" to 60,
        )
        val st = StatsEngine.streaks(daily, day("2026-09-26"))
        assertEquals(3, st.current)
        assertEquals(4, st.longest)
    }

    @Test
    fun streakSurvivesUntilEndOfTodayButBreaksAfterGap() {
        val daily = mapOf("2026-09-24" to 60, "2026-09-25" to 60)
        assertEquals(2, StatsEngine.streaks(daily, day("2026-09-26")).current)
        assertEquals(0, StatsEngine.streaks(daily, day("2026-09-27")).current)
        assertEquals(0, StatsEngine.streaks(emptyMap(), day("2026-09-27")).current)
    }

    @Test
    fun zeroSecondDaysDoNotCount() {
        val daily = mapOf("2026-09-25" to 0, "2026-09-26" to 120)
        assertEquals(1, StatsEngine.streaks(daily, day("2026-09-26")).current)
    }

    @Test
    fun levels() {
        assertEquals(0, StatsEngine.level(0))
        assertEquals(1, StatsEngine.level(30))
        assertEquals(1, StatsEngine.level(14 * 60))
        assertEquals(2, StatsEngine.level(15 * 60))
        assertEquals(3, StatsEngine.level(45 * 60))
        assertEquals(4, StatsEngine.level(90 * 60))
    }

    @Test
    fun heatmapShape() {
        val today = day("2026-09-26") // суббота
        val daily = mapOf("2026-09-26" to 3600, "2026-09-21" to 600)
        val grid = StatsEngine.heatmap(daily, today, 53, monday)
        assertEquals(53, grid.size)
        assertTrue(grid.all { it.size == 7 })
        // Столбцы начинаются с понедельника.
        assertEquals(DayOfWeek.MONDAY, grid[0][0].date.dayOfWeek)
        val last = grid.last()
        assertEquals("2026-09-21", last[0].key)
        assertEquals(1, last[0].level)
        assertEquals("2026-09-26", last[5].key)
        assertEquals(4, last[5].level)
        assertTrue(last[6].isFuture)
        assertFalse(last[5].isFuture)
        // Неделя с воскресенья (США).
        assertEquals(DayOfWeek.SUNDAY, StatsEngine.heatmap(daily, today, 2, DayOfWeek.SUNDAY)[0][0].date.dayOfWeek)
    }

    @Test
    fun recentDaysAndWeeklyBars() {
        val today = day("2026-09-26")
        val daily = mapOf("2026-09-26" to 600, "2026-09-25" to 1200)
        val recent = StatsEngine.recentDays(daily, today, 70)
        assertEquals(70, recent.size)
        assertEquals(10, recent.last())
        assertEquals(20, recent[68])
        val bars = StatsEngine.weeklyBars(daily, today, 12, monday)
        assertEquals(12, bars.size)
        assertEquals(30, bars.last().minutes)
        assertEquals(day("2026-09-21"), bars.last().weekStart)
    }

    @Test
    fun perBookAndHours() {
        val s = listOf(
            session("2026-09-20", 600, book = "/a", pct = 10, hour = 9),
            session("2026-09-21", 1200, book = "/a", pct = 25, hour = 21),
            session("2026-09-21", 300, book = "/b", pct = 5, hour = 21),
        )
        val per = StatsEngine.perBook(s)
        assertEquals("/a", per.first().path)
        assertEquals(1800, per.first().seconds)
        assertEquals(2, per.first().sessions)
        assertEquals(25, per.first().lastPct)
        val hours = StatsEngine.hourDistribution(s, zone)
        assertEquals(1500, hours[21])
        assertEquals(600, hours[9])
    }

    @Test
    fun widgetSummaryUsesComputationDateAndFreshStats() {
        val snap = SyncSnapshot(lastSync = 1_700_000_000_000)
        val now = 1_790_000_000_000
        val summary = StatsEngine.widgetSummary(snap, emptyList(), 30, now, zone)
        assertEquals(now, summary.updated)
        assertEquals(0, summary.streak)
        assertEquals(70, summary.recentDays.size)
    }

    @Test
    fun widgetSummaryTakesTodayAndStreakFromFreshStatsOnly() {
        val now = day("2026-09-26").atStartOfDay(zone).plusHours(15).toInstant().toEpochMilli()
        val stats = DeviceStats(
            today = DeviceStats.Today(secs = 1500), streak = DeviceStats.Streak(current = 9, longest = 12),
        )
        val books = listOf(
            Book(path = "/a.epub", title = "Старая", progress = 40, lastRead = 10),
            Book(path = "/b.epub", title = "Свежая", author = "Автор", progress = 60, lastRead = 20),
            Book(path = "/c.epub", title = "Готова", progress = 100, lastRead = 30),
            Book(path = "/d.epub", title = "Брошена", progress = 50, lastRead = 40, status = "dropped"),
        )
        val fresh = SyncSnapshot(stats = stats, statsFetchedAt = now - 3_600_000, books = books)
        val s1 = StatsEngine.widgetSummary(fresh, listOf(session("2026-09-26", 600)), 30, now, zone)
        assertEquals(25, s1.todayMinutes)
        assertEquals(9, s1.streak)
        assertEquals(12, s1.longestStreak)
        assertEquals("Свежая", s1.currentBookTitle)
        assertEquals("Автор", s1.currentBookAuthor)
        assertEquals(60, s1.currentBookProgress)
        val stale = fresh.copy(statsFetchedAt = now - 2 * 86_400_000L)
        val s2 = StatsEngine.widgetSummary(stale, listOf(session("2026-09-26", 600)), 30, now, zone)
        assertEquals(10, s2.todayMinutes)
        assertEquals(1, s2.streak)
        assertEquals(12, s2.longestStreak)
    }

    @Test
    fun currentlyReading() {
        val books = listOf(
            Book(path = "/1", progress = 0, lastRead = 5),
            Book(path = "/2", progress = 0, status = "reading", lastRead = 1),
            Book(path = "/3", progress = 30, lastRead = 3),
            Book(path = "/4", progress = 30, status = "want", lastRead = 9),
        )
        assertEquals(listOf("/3", "/2"), StatsEngine.currentlyReading(books).map { it.path })
    }

    @Test
    fun dayKeys() {
        assertEquals("2026-01-05", StatsEngine.dayKey(LocalDate.of(2026, 1, 5)))
        assertEquals(LocalDate.of(2026, 1, 5), StatsEngine.dateFromKey("2026-01-05"))
        assertNull(StatsEngine.dateFromKey(""))
        assertNull(StatsEngine.dateFromKey("2026-02-30"))
        // Полночь UTC — уже следующий день в Ташкенте (UTC+5).
        assertEquals("2026-09-27", StatsEngine.dayKey(day("2026-09-26").atStartOfDay(ZoneOffset.UTC).plusHours(20)
            .toInstant().toEpochMilli(), zone))
    }
}

class ReviewTest {
    private val zone = ZoneId.of("Asia/Dushanbe")
    private fun day(key: String) = LocalDate.parse(key)

    private fun session(day: String, secs: Int, pages: Int, book: String = "/b.epub", pct: Int = 10) = ReadingSession(
        book = book, title = "B", start = day(day).atStartOfDay(zone).plusHours(10).toEpochSecond(),
        secs = secs, pages = pages, pct = pct, day = day,
    )

    @Test
    fun periodRanges() {
        val today = day("2026-09-26") // суббота
        val d = StatsEngine.reviewRange(ReviewPeriod.DAY, today, firstDayOfWeek = DayOfWeek.MONDAY)
        assertEquals(d.from, d.to)
        assertEquals(today, d.from)
        assertEquals(day("2026-09-21"), StatsEngine.reviewRange(ReviewPeriod.WEEK, today, firstDayOfWeek = DayOfWeek.MONDAY).from)
        assertEquals(day("2026-09-01"), StatsEngine.reviewRange(ReviewPeriod.MONTH, today).from)
        assertEquals(day("2026-01-01"), StatsEngine.reviewRange(ReviewPeriod.YEAR, today).from)
        // Свой период: перепутанные концы переставляются, будущее обрезается сегодняшним днём.
        val custom = StatsEngine.reviewRange(ReviewPeriod.CUSTOM, today, day("2026-10-05"), day("2026-09-10"))
        assertEquals(day("2026-09-10"), custom.from)
        assertEquals(today, custom.to)
    }

    @Test
    fun reviewCountsOnlyTheRangeInclusive() {
        val sessions = listOf(
            session("2026-09-20", 600, 5), session("2026-09-21", 1200, 10),
            session("2026-09-26", 1800, 12, pct = 100),
        )
        val daily = mapOf("2026-09-20" to 600, "2026-09-21" to 1200, "2026-09-26" to 1800)
        val week = StatsEngine.review(day("2026-09-21"), day("2026-09-26"), sessions, daily, emptyList(), zone)
        assertEquals(3000, week.totalSeconds)
        assertEquals(22, week.totalPages)
        assertEquals(2, week.sessions)
        assertEquals(2, week.activeDays)
        assertEquals(1, week.booksFinished)
        assertEquals("2026-09-26", week.bestDayKey)
        assertEquals(10, week.favoriteHour)
        val dayOnly = StatsEngine.review(day("2026-09-26"), day("2026-09-26"), sessions, daily, emptyList(), zone)
        assertEquals(1800, dayOnly.totalSeconds)
        assertEquals(1, dayOnly.sessions)
        val empty = StatsEngine.review(day("2026-09-22"), day("2026-09-22"), sessions, daily, emptyList(), zone)
        assertEquals(0, empty.totalSeconds)
        assertNull(empty.topBookTitle)
        assertNull(empty.bestDayKey)
        assertNull(empty.favoriteHour)
    }

    @Test
    fun finishedByStatusInsideTheRange() {
        val inside = day("2026-09-23").atStartOfDay(zone).plusHours(23).toEpochSecond()
        val after = day("2026-09-27").atStartOfDay(zone).toEpochSecond()
        val books = listOf(
            Book(path = "/x", status = "done", lastRead = inside),
            Book(path = "/y", status = "done", lastRead = after),
            Book(path = "/z", status = "reading", lastRead = inside),
        )
        val r = StatsEngine.review(day("2026-09-21"), day("2026-09-26"), emptyList(), emptyMap(), books, zone)
        assertEquals(1, r.booksFinished)
    }
}

class WrappedTest {
    private val utc: ZoneId = ZoneOffset.UTC

    private fun session(book: String, y: Int, m: Int, d: Int, hour: Int, secs: Int, pct: Int = 10): ReadingSession {
        val start = LocalDate.of(y, m, d).atStartOfDay(utc).plusHours(hour.toLong())
        return ReadingSession(
            book = book, title = book.uppercase(), start = start.toEpochSecond(), secs = secs,
            pages = secs / 60, pct = pct, day = StatsEngine.dayKey(start.toLocalDate()),
        )
    }

    private fun epoch(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(utc).plusHours(12).toEpochSecond()

    @Test
    fun yearOfReading() {
        val sessions = listOf(
            session("a", 2025, 12, 31, hour = 21, secs = 9_999), // прошлый год: не считается
            session("a", 2026, 1, 1, hour = 21, secs = 600),
            session("a", 2026, 1, 2, hour = 21, secs = 600),
            session("a", 2026, 1, 3, hour = 21, secs = 600),
            session("b", 2026, 3, 10, hour = 8, secs = 3_000),
            session("c", 2026, 3, 11, hour = 8, secs = 1_200, pct = 100),
            session("d", 2026, 3, 12, hour = 21, secs = 100),
        )
        val daily = StatsEngine.dailySeconds(sessions)
        val highlights = listOf(
            Highlight(id = "old", book = "a", title = "A", text = "old", created = epoch(2025, 12, 30)),
            Highlight(id = "1", book = "a", title = "A", text = "short one", note = "n", created = epoch(2026, 2, 1)),
            Highlight(id = "2", book = "b", title = "B", text = "long ".repeat(60), created = epoch(2026, 3, 1)),
            Highlight(id = "3", book = "b", title = "B", text = "no date"),
        )
        val vocab = listOf(VocabWord(word = "x", created = epoch(2026, 1, 5)), VocabWord(word = "y", created = epoch(2025, 6, 1)))
        val w = StatsEngine.wrapped(2026, LocalDate.of(2026, 10, 15), sessions, daily, emptyList(), highlights, vocab, utc)
        assertEquals(2026, w.year)
        assertEquals("1 янв … 15 окт", 288, w.daysInPeriod)
        assertEquals(6_100, w.review.totalSeconds)
        assertEquals(6, w.review.activeDays)
        assertEquals(3, w.review.longestStreak)
        assertEquals(1, w.review.booksFinished)
        assertEquals("три книги по времени", listOf("b", "a", "c"), w.topBooks.map { it.path })
        assertEquals(8, w.review.favoriteHour)
        assertEquals(1_900, w.hours[21])
        assertEquals(1_800, w.months[0])
        assertEquals(4_300, w.months[2])
        assertEquals(3, w.bestMonth)
        assertEquals("только выделения этого года", 2, w.highlights)
        assertEquals(1, w.notes)
        assertEquals("короткие цитаты в приоритете", "1", w.quote?.id)
        assertEquals(1, w.wordsLookedUp)
        assertFalse(w.isEmpty)
    }

    @Test
    fun pastYearCoversTheWholeYearAndEmptyData() {
        val w = StatsEngine.wrapped(2024, LocalDate.of(2026, 10, 15), emptyList(), emptyMap(), emptyList(),
            emptyList(), emptyList(), utc)
        assertEquals("2024 — високосный", 366, w.daysInPeriod)
        assertNull(w.bestMonth)
        assertNull("истории словаря нет вовсе", w.wordsLookedUp)
        assertNull(w.quote)
        assertTrue(w.topBooks.isEmpty())
        assertTrue(w.isEmpty)
    }

    @Test
    fun yearsList() {
        val daily = mapOf("2024-05-01" to 60, "2025-01-02" to 0, "2025-02-02" to 120)
        assertEquals(listOf(2026, 2025, 2024), StatsEngine.wrappedYears(daily, LocalDate.of(2026, 1, 3)))
        assertEquals(listOf(2026), StatsEngine.wrappedYears(emptyMap(), LocalDate.of(2026, 1, 3)))
    }
}

class HeatPaletteTest {
    @Test
    fun contrastMathMatchesWcag() {
        assertEquals(21.0, HeatPalette.contrast(HeatPalette.Rgb(0xFFFFFF), HeatPalette.Rgb(0x000000)), 0.01)
        assertEquals(4.48, HeatPalette.contrast(HeatPalette.Rgb(0x777777), HeatPalette.Rgb(0xFFFFFF)), 0.01)
    }

    @Test
    fun separateScalesAndContrastSteps() {
        assertNotEquals(HeatPalette.light, HeatPalette.dark)
        for ((name, scale) in listOf("light" to HeatPalette.light, "dark" to HeatPalette.dark)) {
            assertEquals(5, scale.size)
            // Чтение против его отсутствия: нетекстовый контраст WCAG 1.4.11.
            for (level in 1..4) {
                assertTrue("$name: пусто vs уровень $level", HeatPalette.contrast(scale[0], scale[level]) >= 3.0)
            }
            // Соседние уровни различимы и упорядочены по яркости.
            for (level in 1 until 4) {
                val ratio = HeatPalette.contrast(scale[level], scale[level + 1])
                assertTrue("$name: уровень $level→${level + 1} = $ratio", ratio >= 1.6)
            }
            val lum = scale.map { it.luminance }
            if (name == "light") {
                assertEquals("светлая: темнее = больше чтения", lum.sortedDescending(), lum)
            } else {
                assertEquals("тёмная: ярче = больше чтения", lum.sorted(), lum)
            }
        }
        assertEquals(0xFFEBEDF0.toInt(), HeatPalette.argb(0, dark = false))
        assertEquals(0xFFF2F6FFL, HeatPalette.rgb(9, dark = true).argbLong)
        assertEquals(HeatPalette.dark[0], HeatPalette.rgb(-1, dark = true))
    }

    @Test
    fun weekSummaryForAccessibility() {
        val today = LocalDate.of(2026, 9, 24) // четверг
        val daily = mapOf("2026-09-21" to 1800, "2026-09-23" to 3600, "2026-09-14" to 600)
        val columns = StatsEngine.heatmap(daily, today, 3, DayOfWeek.MONDAY)
        val week = StatsEngine.weekSummary(columns[2])!!
        assertEquals(2, week.activeDays)
        assertEquals(5400, week.seconds)
        assertEquals("2026-09-23", week.bestDay?.key)
        assertEquals("будущие дни не входят", 24, week.end.dayOfMonth)
        val totals = StatsEngine.heatmapTotals(columns)
        assertEquals(3, totals.activeDays)
        assertEquals(6000, totals.seconds)
        assertEquals(3, totals.weeks)
        assertNull(StatsEngine.weekSummary(columns[2].map { it.copy(isFuture = true) }))
    }
}
