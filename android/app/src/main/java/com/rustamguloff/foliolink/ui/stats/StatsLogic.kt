package com.rustamguloff.foliolink.ui.stats

import com.rustamguloff.foliolink.core.model.Book
import com.rustamguloff.foliolink.core.model.DeviceStats
import com.rustamguloff.foliolink.core.model.ReadingSession
import com.rustamguloff.foliolink.core.stats.StatsEngine
import java.time.LocalDate

/** Чистая подготовка данных экрана статистики (без Compose) — покрыта JVM-тестами. */
object StatsLogic {

    data class Totals(val seconds: Int, val pages: Int, val sessions: Int, val books: Int)

    /**
     * Плитки сверху: большее из счёта читалки (`stats.totals`) и локального журнала сессий.
     * Прочитанные книги — счёт читалки или сессии, дошедшие до 100 %.
     */
    fun totals(stats: DeviceStats?, sessions: List<ReadingSession>, daily: Map<String, Int>): Totals {
        val t = stats?.totals
        return Totals(
            seconds = maxOf(t?.secs ?: 0, daily.values.sum()),
            pages = maxOf(t?.pages ?: 0, sessions.sumOf { it.pages }),
            sessions = maxOf(t?.sessions ?: 0, sessions.size),
            books = maxOf(t?.books ?: 0, sessions.filter { it.pct >= 100 }.mapTo(HashSet()) { it.book }.size),
        )
    }

    /** Среднее минут в неделю по столбцам графика. */
    fun weeklyAverage(bars: List<StatsEngine.WeekBar>): Int =
        if (bars.isEmpty()) 0 else bars.sumOf { it.minutes } / bars.size

    /** Верх шкалы графика по неделям: столбцы и линия цели помещаются целиком. */
    fun weeklyScaleMax(bars: List<StatsEngine.WeekBar>, weeklyGoal: Int): Int =
        maxOf(bars.maxOfOrNull { it.minutes } ?: 0, weeklyGoal, 1)

    /**
     * Столбцы, над которыми подписывается месяц: первый столбец и каждый, где месяц сменился.
     * Подпись не ставится ближе `minGap` столбцов к предыдущей (иначе надписи наезжают).
     */
    fun monthStarts(dates: List<LocalDate>, minGap: Int = 1): List<Int> {
        val result = mutableListOf<Int>()
        for (i in dates.indices) {
            val isStart = i == 0 || dates[i].monthValue != dates[i - 1].monthValue
            if (!isStart) continue
            if (result.isNotEmpty() && i - result.last() < minGap) {
                // Новый месяц важнее обрезка предыдущего в начале сетки.
                if (result.last() == 0) result[result.lastIndex] = i
                continue
            }
            result += i
        }
        return result
    }

    /** Час, в который читали больше всего; null — нет сессий с часами. */
    fun peakHour(hours: List<Int>): Int? {
        val max = hours.maxOrNull() ?: return null
        return if (max > 0) hours.indexOf(max) else null
    }

    /** Название книги: из библиотеки, иначе из сессий, иначе имя файла. */
    fun bookTitle(stat: StatsEngine.BookStat, books: List<Book>): String =
        books.firstOrNull { it.path == stat.path }?.displayTitle
            ?: stat.title.ifEmpty { stat.path.substringAfterLast('/') }

    /** Сессии дня из журнала (для окна дня). */
    fun sessionsOn(day: String, sessions: List<ReadingSession>): List<ReadingSession> = sessions.filter { it.day == day }

    // region Тепловая карта для TalkBack

    /** Строки озвучки тепловой карты (в приложении — из ресурсов, в тестах — фейк). */
    interface HeatmapTexts {
        fun duration(seconds: Int): String
        /** «3 мар.» */
        fun shortDate(date: LocalDate): String
        /** «понедельник» */
        fun weekday(date: LocalDate): String
        fun summary(weeks: Int, activeDays: Int, duration: String): String
        fun weekEmpty(range: String): String
        fun week(range: String, activeDays: Int, duration: String): String
        fun mostOn(weekday: String, duration: String): String
    }

    /** «Календарь чтения за 53 нед.: дней с чтением 180, всего 120 часов». */
    fun heatmapSummary(columns: List<List<StatsEngine.HeatCell>>, t: HeatmapTexts): String {
        val totals = StatsEngine.heatmapTotals(columns)
        return t.summary(totals.weeks, totals.activeDays, t.duration(totals.seconds))
    }

    /** Одна неделя одной фразой; "" — неделя целиком в будущем. */
    fun weekText(column: List<StatsEngine.HeatCell>, t: HeatmapTexts): String {
        val w = StatsEngine.weekSummary(column) ?: return ""
        val range = t.shortDate(w.start) + " – " + t.shortDate(w.end)
        if (w.activeDays == 0) return t.weekEmpty(range)
        var text = t.week(range, w.activeDays, t.duration(w.seconds))
        w.bestDay?.let { text += ". " + t.mostOn(t.weekday(it.date), t.duration(it.seconds)) }
        return text
    }

    /** Неделя после шага TalkBack: в пределах сетки. */
    fun stepWeek(current: Int, delta: Int, count: Int): Int = (current + delta).coerceIn(0, maxOf(count - 1, 0))

    /** День, который открывает двойное касание по неделе: лучший, иначе последний прошедший. */
    fun dayToOpen(column: List<StatsEngine.HeatCell>): StatsEngine.HeatCell? =
        StatsEngine.weekSummary(column)?.bestDay ?: column.lastOrNull { !it.isFuture }

    // endregion
}
