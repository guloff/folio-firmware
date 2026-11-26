package com.rustamguloff.foliolink.core.services

import com.rustamguloff.foliolink.core.model.VocabWord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class SpacedRepetitionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val zone = ZoneId.of("Asia/Tashkent")

    @Test
    fun sm2Progression() {
        val now = 1_790_380_800_000L
        var s = ReviewState()
        assertTrue("новое слово сразу к повторению", s.isDue(now))
        s = s.reviewed(ReviewState.Grade.GOOD, now, zone); assertEquals(1, s.interval)
        s = s.reviewed(ReviewState.Grade.GOOD, now, zone); assertEquals(3, s.interval)
        s = s.reviewed(ReviewState.Grade.GOOD, now, zone); assertTrue(s.interval >= 7)
        val ef = s.easiness
        s = s.reviewed(ReviewState.Grade.AGAIN, now, zone)
        assertEquals(0, s.repetitions)
        assertTrue(s.easiness < ef)
        assertTrue(s.easiness >= 1.3)
        assertTrue(s.due > now && s.due < now + 3_600_000)
        assertEquals(now, s.lastReviewed)
    }

    @Test
    fun dueIsMidnightAfterIntervalDays() {
        val now = LocalDate.of(2026, 9, 26).atStartOfDay(zone).plusHours(22).toInstant().toEpochMilli()
        val s = ReviewState().reviewed(ReviewState.Grade.EASY, now, zone).reviewed(ReviewState.Grade.EASY, now, zone)
        assertEquals(4, s.interval)
        assertEquals(LocalDate.of(2026, 9, 30).atStartOfDay(zone).toInstant().toEpochMilli(), s.due)
        val hard = ReviewState().reviewed(ReviewState.Grade.HARD, now, zone)
        assertEquals(1, hard.interval)
        assertTrue(hard.easiness < 2.5)
    }

    @Test
    fun storePersistsByLowercasedWord() = runTest {
        val file = File(tmp.root, "sub/vocab-review.json")
        val now = 1_790_380_800_000L
        val store = VocabReviewStore(file)
        store.review("Serendipity", ReviewState.Grade.GOOD, now, zone)
        store.review("ephemeral", ReviewState.Grade.AGAIN, now, zone)
        assertTrue(file.exists())

        val reopened = VocabReviewStore(file)
        reopened.load()
        assertEquals(1, reopened.state("serendipity").interval)
        assertEquals(2, reopened.states.value.size)
        val words = listOf(VocabWord(word = "SERENDIPITY"), VocabWord(word = "ephemeral"), VocabWord(word = "new"))
        // Через час: «ephemeral» (через 10 мин) и новое слово к повторению, «serendipity» — завтра.
        val due = reopened.dueWords(words, now + 3_600_000).map { it.word }
        assertEquals(listOf("new", "ephemeral"), due)

        reopened.remove("Ephemeral")
        assertEquals(setOf("serendipity"), VocabReviewStore(file).apply { load() }.states.value.keys)
    }

    @Test
    fun damagedFileStartsEmpty() = runTest {
        val file = File(tmp.root, "bad.json").apply { writeText("{not json") }
        val store = VocabReviewStore(file)
        store.load()
        assertTrue(store.states.value.isEmpty())
    }
}
