package com.rustamguloff.foliolink.app

import com.rustamguloff.foliolink.core.services.ReminderScheduler
import com.rustamguloff.foliolink.core.services.ReminderScheduler.Message
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Правила напоминания (чистая часть ReminderScheduler). */
class ReminderRulesTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private fun at(h: Int, m: Int, day: Int = 22) = LocalDateTime.of(2026, 10, day, h, m).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun nextFireTodayOrTomorrow() {
        val nine = 21 * 60
        assertEquals("before the time, goal not met: today", at(21, 0), ReminderScheduler.nextFireTime(at(10, 0), nine, false, zone))
        assertEquals("goal met today: tomorrow", at(21, 0, 23), ReminderScheduler.nextFireTime(at(10, 0), nine, true, zone))
        assertEquals("time passed: tomorrow", at(21, 0, 23), ReminderScheduler.nextFireTime(at(21, 30), nine, false, zone))
        assertEquals("exactly now counts as passed", at(21, 0, 23), ReminderScheduler.nextFireTime(at(21, 0), nine, false, zone))
        assertEquals(at(7, 15, 23), ReminderScheduler.nextFireTime(at(8, 0), 7 * 60 + 15, false, zone))
    }

    @Test
    fun messageText() {
        assertEquals(Message.Remaining(12), ReminderScheduler.message(sameDay = true, todayMinutes = 18, goalMinutes = 30))
        assertEquals("never zero", Message.Remaining(1), ReminderScheduler.message(true, 40, 30))
        assertEquals(Message.Goal(30), ReminderScheduler.message(true, 0, 30))
        assertEquals("yesterday's numbers don't count", Message.Goal(30), ReminderScheduler.message(false, 18, 30))
    }
}
