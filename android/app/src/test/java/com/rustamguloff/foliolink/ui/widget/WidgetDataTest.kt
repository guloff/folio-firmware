package com.rustamguloff.foliolink.ui.widget

import com.rustamguloff.foliolink.core.model.WidgetSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class WidgetDataTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private fun ms(y: Int, m: Int, d: Int, h: Int = 12) = LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()

    private fun summary(updated: Long, days: List<Int> = List(70) { it }) =
        WidgetSummary(updated = updated, todayMinutes = 25, goalMinutes = 30, streak = 5, recentDays = days)

    @Test fun sameDayIsUnchanged() {
        val s = summary(ms(2026, 10, 22, 8))
        assertSame(s, WidgetData.adjusted(s, ms(2026, 10, 22, 23), zone))
    }

    @Test fun nextDayResetsTodayAndShiftsMapButKeepsStreak() {
        val s = summary(ms(2026, 10, 21, 23))
        val r = WidgetData.adjusted(s, ms(2026, 10, 22, 0), zone)
        assertEquals(0, r.todayMinutes)
        assertEquals(5, r.streak)
        assertEquals(70, r.recentDays.size)
        assertEquals(0, r.recentDays.last())
        assertEquals(69, r.recentDays[68])
        assertEquals(1, r.recentDays.first())
    }

    @Test fun missedDayBreaksStreak() {
        val r = WidgetData.adjusted(summary(ms(2026, 10, 19)), ms(2026, 10, 22), zone)
        assertEquals(0, r.streak)
        assertEquals(listOf(0, 0, 0), r.recentDays.takeLast(3))
        assertEquals(69, r.recentDays[66])
    }

    @Test fun longGapClearsWholeMap() {
        val r = WidgetData.adjusted(summary(ms(2026, 1, 1)), ms(2026, 10, 22), zone)
        assertEquals(List(70) { 0 }, r.recentDays)
    }

    @Test fun futureSummaryOnlyResetsToday() {
        // Часы телефона ушли назад: сводка «из завтра» не сдвигается, но минут за сегодня нет.
        val s = summary(ms(2026, 10, 23))
        val r = WidgetData.adjusted(s, ms(2026, 10, 22), zone)
        assertEquals(0, r.todayMinutes)
        assertEquals(s.recentDays, r.recentDays)
        assertEquals(5, r.streak)
    }

    @Test fun shortHistoryIsPaddedAtStart() {
        val cols = WidgetData.miniHeatmap(listOf(5, 20, 70))
        assertEquals(10, cols.size)
        assertTrue(cols.all { it.size == 7 })
        assertEquals(listOf(0, 0, 0, 0, 1, 2, 4), cols.last())
        assertTrue(cols.dropLast(1).flatten().all { it == 0 })
    }

    @Test fun longHistoryKeepsLastSeventyDays() {
        val cols = WidgetData.miniHeatmap(List(80) { if (it < 10) 60 else 0 })
        assertTrue(cols.flatten().all { it == 0 })
    }

    @Test fun progressAndGoal() {
        assertEquals(0.5f, WidgetData.progress(WidgetSummary(todayMinutes = 15, goalMinutes = 30)), 1e-6f)
        assertEquals(0f, WidgetData.progress(WidgetSummary(todayMinutes = 15, goalMinutes = 0)), 1e-6f)
        assertTrue(WidgetData.goalMet(WidgetSummary(todayMinutes = 30, goalMinutes = 30)))
        assertFalse(WidgetData.goalMet(WidgetSummary(todayMinutes = 30, goalMinutes = 0)))
    }

    @Test fun bookFraction() {
        assertNull(WidgetData.bookFraction(null))
        assertEquals(1f, WidgetData.bookFraction(140)!!, 1e-6f)
        assertEquals(0.42f, WidgetData.bookFraction(42)!!, 1e-6f)
    }
}
