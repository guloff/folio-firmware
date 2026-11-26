package com.rustamguloff.foliolink.ui.widget

import com.rustamguloff.foliolink.core.model.WidgetSummary
import com.rustamguloff.foliolink.core.stats.StatsEngine
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Чистая логика виджетов (без Glance) — покрыта JVM-тестами. */
object WidgetData {
    /** Дней в мини-карте (10 недель). */
    const val DAYS = 70

    /**
     * Сводка, посчитанная в прошлый день (приложение давно не открывали), сдвигается на
     * сегодня, как в iOS ReadingProvider: минуты за сегодня — 0, мини-карта сдвигается на
     * прошедшие дни, серия обнуляется, если пропущен целый день.
     */
    fun adjusted(s: WidgetSummary, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): WidgetSummary {
        val updatedDay = StatsEngine.localDate(s.updated, zone)
        val today = StatsEngine.localDate(nowMs, zone)
        if (updatedDay == today) return s
        val days = ChronoUnit.DAYS.between(updatedDay, today)
        var r = s
        if (days > 0) {
            val shifted = (s.recentDays + List(minOf(days, DAYS.toLong()).toInt()) { 0 }).takeLast(DAYS)
            r = r.copy(recentDays = shifted)
            if (days > 1) r = r.copy(streak = 0)
        }
        return r.copy(todayMinutes = 0)
    }

    /** Доля цели (может быть больше 1); цель 0 — 0. */
    fun progress(s: WidgetSummary): Float =
        if (s.goalMinutes > 0) s.todayMinutes.toFloat() / s.goalMinutes else 0f

    fun goalMet(s: WidgetSummary): Boolean = s.goalMinutes > 0 && s.todayMinutes >= s.goalMinutes

    /**
     * Мини-карта: 10 столбцов по 7 дней, уровни 0…4; последний столбец кончается сегодня.
     * Короткий список дополняется нулями в начале.
     */
    fun miniHeatmap(days: List<Int>): List<List<Int>> {
        val padded = (List(maxOf(0, DAYS - days.size)) { 0 } + days).takeLast(DAYS)
        return padded.chunked(7).map { col -> col.map { StatsEngine.level(it * 60) } }
    }

    /** Прогресс книги для полосы (0…1); null — неизвестен. */
    fun bookFraction(progress: Int?): Float? = progress?.coerceIn(0, 100)?.div(100f)
}
