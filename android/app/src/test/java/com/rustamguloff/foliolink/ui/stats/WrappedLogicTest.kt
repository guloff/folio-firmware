package com.rustamguloff.foliolink.ui.stats

import com.rustamguloff.foliolink.core.stats.StatsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WrappedLogicTest {
    private val day = LocalDate.of(2026, 1, 1)

    private fun review(seconds: Int = 0, streak: Int = 0, favoriteHour: Int? = null, activeDays: Int = 0) = StatsEngine.YearReview(
        from = day, to = day.plusDays(294), totalSeconds = seconds, totalPages = 0, sessions = 0, activeDays = activeDays,
        booksFinished = 0, longestStreak = streak, bestDayKey = null, bestDaySeconds = 0, topBookTitle = null,
        topBookSeconds = 0, favoriteHour = favoriteHour,
    )

    private fun wrapped(
        review: StatsEngine.YearReview,
        topBooks: List<StatsEngine.BookStat> = emptyList(),
        highlights: Int = 0,
        words: Int? = null,
    ) = StatsEngine.Wrapped(
        year = 2026, review = review, daysInPeriod = 295, topBooks = topBooks, hours = List(24) { 0 },
        months = List(12) { 0 }, bestMonth = null, highlights = highlights, notes = 0, wordsLookedUp = words, quote = null,
    )

    @Test fun emptyYearShowsOnlyEmptyCard() {
        assertEquals(listOf(WrappedCard.EMPTY), WrappedCard.cards(wrapped(review())))
    }

    @Test fun fullYearShowsEveryCardInOrder() {
        val book = StatsEngine.BookStat("/a", "A", 3600, 2, 10, 50, 0)
        val d = wrapped(review(seconds = 7200, streak = 3, favoriteHour = 22, activeDays = 2), listOf(book), highlights = 4, words = 7)
        assertEquals(WrappedCard.entries.filter { it != WrappedCard.EMPTY }, WrappedCard.cards(d))
    }

    @Test fun cardsWithoutDataAreSkipped() {
        // Только выделения: без времени, серии, книг, часа и слов.
        val d = wrapped(review(streak = 1), highlights = 2, words = 0)
        assertEquals(listOf(WrappedCard.INTRO, WrappedCard.HIGHLIGHTS, WrappedCard.FINALE), WrappedCard.cards(d))
    }

    @Test fun percentAndAverage() {
        assertEquals(34, WrappedLogic.percent(100, 295))
        assertEquals(0, WrappedLogic.percent(0, 0))
        assertEquals(1800, WrappedLogic.averagePerReadingDay(review(seconds = 3600, activeDays = 2)))
        assertEquals(0, WrappedLogic.averagePerReadingDay(review(seconds = 3600, activeDays = 0)))
    }

    @Test fun hourHeightsKeepMinimum() {
        val h = WrappedLogic.hourHeights(List(24) { if (it == 20) 600 else if (it == 8) 300 else 0 })
        assertEquals(1f, h[20], 1e-6f)
        assertEquals(0.5f, h[8], 1e-6f)
        assertTrue(h[0] > 0f)
    }

    @Test fun posterFileName() {
        assertEquals("FolioLink-2026-streak.png", WrappedLogic.posterFileName(2026, WrappedCard.STREAK))
    }

    @Test fun introAndFinaleHaveNoPosterHeader() {
        assertEquals(
            setOf(WrappedCard.INTRO, WrappedCard.FINALE, WrappedCard.EMPTY),
            WrappedCard.entries.filter { !it.posterHeader }.toSet(),
        )
    }
}
