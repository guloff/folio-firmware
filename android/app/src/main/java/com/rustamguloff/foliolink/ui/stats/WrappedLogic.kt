package com.rustamguloff.foliolink.ui.stats

import com.rustamguloff.foliolink.core.stats.StatsEngine
import kotlin.math.roundToInt

/**
 * Карточки «Итогов года» по порядку; карточки без данных пропускаются (как iOS WrappedCard).
 * `top`/`bottom` — ARGB градиента фона (сверху слева → снизу справа).
 */
enum class WrappedCard(val raw: String, val top: Long, val bottom: Long) {
    INTRO("intro", 0xFF121A2E, 0xFF29385C),
    TIME("time", 0xFF173354, 0xFF0D6B80),
    DAYS("days", 0xFF14402E, 0xFF21734D),
    STREAK("streak", 0xFF5C1F14, 0xFFB3541A),
    BOOKS("books", 0xFF331A4D, 0xFF6B3380),
    HOUR("hour", 0xFF0F0F33, 0xFF38337A),
    HIGHLIGHTS("highlights", 0xFF523D0D, 0xFF8C6B1A),
    WORDS("words", 0xFF0D404D, 0xFF1A6666),
    FINALE("finale", 0xFF121A2E, 0xFF29385C),
    EMPTY("empty", 0xFF121A2E, 0xFF29385C),
    ;

    /** На плакате над карточкой подписывается год (кроме вступления и финала). */
    val posterHeader: Boolean get() = this != INTRO && this != FINALE && this != EMPTY

    /** Содержимое по центру по вертикали (вступление и пустой год). */
    val centered: Boolean get() = this == INTRO || this == EMPTY

    companion object {
        fun cards(d: StatsEngine.Wrapped): List<WrappedCard> {
            if (d.isEmpty) return listOf(EMPTY)
            val cards = mutableListOf(INTRO)
            if (d.review.totalSeconds > 0) cards += listOf(TIME, DAYS)
            if (d.review.longestStreak > 1) cards += STREAK
            if (d.topBooks.isNotEmpty()) cards += BOOKS
            if (d.review.favoriteHour != null) cards += HOUR
            if (d.highlights > 0) cards += HIGHLIGHTS
            if ((d.wordsLookedUp ?: 0) > 0) cards += WORDS
            cards += FINALE
            return cards
        }
    }
}

object WrappedLogic {
    /** Доля дней с чтением, %, с округлением. */
    fun percent(activeDays: Int, daysInPeriod: Int): Int =
        (activeDays.toDouble() / maxOf(daysInPeriod, 1) * 100).roundToInt()

    /** Средняя длительность дня с чтением, секунды; 0 — дней нет. */
    fun averagePerReadingDay(review: StatsEngine.YearReview): Int =
        if (review.activeDays > 0) review.totalSeconds / review.activeDays else 0

    /** Высоты столбиков часов 0…1 от пика (минимум виден всегда). */
    fun hourHeights(hours: List<Int>, min: Float = 0.033f): List<Float> {
        val peak = maxOf(hours.maxOrNull() ?: 0, 1).toFloat()
        return hours.map { maxOf(min, it / peak) }
    }

    /** Имя файла картинки для «Поделиться». */
    fun posterFileName(year: Int, card: WrappedCard): String = "FolioLink-$year-${card.raw}.png"
}
