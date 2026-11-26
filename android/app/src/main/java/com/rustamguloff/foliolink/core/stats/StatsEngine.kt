package com.rustamguloff.foliolink.core.stats

import com.rustamguloff.foliolink.core.model.Book
import com.rustamguloff.foliolink.core.model.BookStatus
import com.rustamguloff.foliolink.core.model.DeviceStats
import com.rustamguloff.foliolink.core.model.Highlight
import com.rustamguloff.foliolink.core.model.ReadingSession
import com.rustamguloff.foliolink.core.model.SyncSnapshot
import com.rustamguloff.foliolink.core.model.VocabWord
import com.rustamguloff.foliolink.core.model.WidgetSummary
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Чистые функции: сессии чтения → цифры дашборда, экрана статистики и виджетов.
 * «Сегодня», часовой пояс и первый день недели передаются явно — так тесты детерминированы.
 * Дни — `LocalDate` в поясе пользователя, ключ дня — "yyyy-MM-dd" (как `day` у прошивки).
 */
object StatsEngine {

    /** Первый день недели по локали устройства (как `Calendar.current.firstWeekday` на iOS). */
    fun defaultFirstDayOfWeek(): DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek

    data class HeatCell(
        val key: String,
        val date: LocalDate,
        val seconds: Int,
        val level: Int,
        val isFuture: Boolean,
    ) {
        val id: String get() = key
    }

    data class BookStat(
        val path: String,
        val title: String,
        val seconds: Int,
        val sessions: Int,
        val pages: Int,
        val lastPct: Int,
        /** start последней сессии, epoch-секунды. */
        val lastRead: Long,
    ) {
        val id: String get() = path
    }

    data class WeekBar(val weekStart: LocalDate, val minutes: Int) {
        val id: LocalDate get() = weekStart
    }

    data class Streaks(val current: Int, val longest: Int)

    /** Сводка за диапазон дней (карточка «Итоги»). Поля — как у iOS `YearReview`. */
    data class YearReview(
        val from: LocalDate,
        val to: LocalDate,
        val totalSeconds: Int,
        val totalPages: Int,
        val sessions: Int,
        val activeDays: Int,
        val booksFinished: Int,
        val longestStreak: Int,
        val bestDayKey: String?,
        val bestDaySeconds: Int,
        val topBookTitle: String?,
        val topBookSeconds: Int,
        val favoriteHour: Int?,
    )

    /** Периоды карточки итогов. Календарные периоды идут от начала до сегодня. */
    enum class ReviewPeriod(val raw: String) {
        DAY("day"), WEEK("week"), MONTH("month"), YEAR("year"), CUSTOM("custom");

        companion object {
            fun from(raw: String?): ReviewPeriod? = entries.firstOrNull { it.raw == raw }
        }
    }

    /** Включительный диапазон дней. */
    data class DayRange(val from: LocalDate, val to: LocalDate)

    // region Ключи дней

    private val keyFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.US).withResolverStyle(ResolverStyle.STRICT)

    fun dayKey(date: LocalDate): String = keyFormatter.format(date)

    /** Ключ локального дня для момента `epochMs`. */
    fun dayKey(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        dayKey(Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate())

    /** "yyyy-MM-dd" → дата; null для мусора ("", "2026-13-01"…). */
    fun dateFromKey(key: String): LocalDate? = runCatching { LocalDate.parse(key, keyFormatter) }.getOrNull()

    /** Локальный день момента `epochMs`. */
    fun localDate(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    // endregion

    // region Агрегация

    /**
     * Секунды чтения по локальным дням. Берёт `stats.days` читалки (авторитетны, покрывают
     * историю, даже если журнал сессий обрезан) и журнал сессий — по большему значению за день,
     * так что ни один источник не считается дважды.
     */
    fun dailySeconds(sessions: List<ReadingSession>, statsDays: List<DeviceStats.Day> = emptyList()): Map<String, Int> {
        val result = HashMap<String, Int>()
        for (s in sessions) if (s.day.isNotEmpty()) result[s.day] = (result[s.day] ?: 0) + s.secs
        for (d in statsDays) if (d.d.isNotEmpty()) result[d.d] = maxOf(result[d.d] ?: 0, d.s)
        return result
    }

    fun dailyPages(sessions: List<ReadingSession>, statsDays: List<DeviceStats.Day> = emptyList()): Map<String, Int> {
        val result = HashMap<String, Int>()
        for (s in sessions) if (s.day.isNotEmpty()) result[s.day] = (result[s.day] ?: 0) + s.pages
        for (d in statsDays) if (d.d.isNotEmpty()) result[d.d] = maxOf(result[d.d] ?: 0, d.p)
        return result
    }

    /**
     * Текущая и самая длинная серия дней подряд с чтением. Текущая серия живёт весь
     * сегодняшний день, если вчера было чтение.
     */
    fun streaks(daily: Map<String, Int>, today: LocalDate): Streaks {
        val active = daily.filterValues { it > 0 }.keys.mapNotNullTo(HashSet()) { dateFromKey(it) }
        if (active.isEmpty()) return Streaks(0, 0)

        var longest = 1
        var run = 1
        val sorted = active.sorted()
        for (i in 1 until sorted.size) {
            run = if (sorted[i - 1].plusDays(1) == sorted[i]) run + 1 else 1
            longest = maxOf(longest, run)
        }

        var cursor = today
        if (cursor !in active) {
            val yesterday = cursor.minusDays(1)
            if (yesterday !in active) return Streaks(0, longest)
            cursor = yesterday
        }
        var current = 0
        while (cursor in active) {
            current++
            cursor = cursor.minusDays(1)
        }
        return Streaks(current, maxOf(longest, current))
    }

    /** Интенсивность 0…4 по минутам: 0 · 1–14 · 15–29 · 30–59 · 60+ (меньше минуты, но не 0 — 1). */
    fun level(seconds: Int): Int {
        val m = seconds / 60
        return when {
            m < 1 -> if (seconds > 0) 1 else 0
            m < 15 -> 1
            m < 30 -> 2
            m < 60 -> 3
            else -> 4
        }
    }

    /**
     * Сетка как на GitHub: столбцы — недели (старые первыми), в каждом 7 клеток от первого дня
     * недели. Охватывает `weeks` недель, последняя — неделя с `today`.
     */
    fun heatmap(
        daily: Map<String, Int>,
        today: LocalDate,
        weeks: Int = 53,
        firstDayOfWeek: DayOfWeek = defaultFirstDayOfWeek(),
    ): List<List<HeatCell>> {
        if (weeks <= 0) return emptyList()
        val offset = (today.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
        val firstWeekStart = today.minusDays(offset.toLong()).minusWeeks((weeks - 1).toLong())
        return List(weeks) { w ->
            List(7) { d ->
                val date = firstWeekStart.plusDays((w * 7 + d).toLong())
                val key = dayKey(date)
                val secs = daily[key] ?: 0
                HeatCell(key, date, secs, level(secs), isFuture = date > today)
            }
        }
    }

    /** Минуты по дням за последние `count` дней, от старых к новым (сегодня — последний). */
    fun recentDays(daily: Map<String, Int>, today: LocalDate, count: Int): List<Int> =
        (count - 1 downTo 0).map { back -> (daily[dayKey(today.minusDays(back.toLong()))] ?: 0) / 60 }

    fun weeklyBars(
        daily: Map<String, Int>,
        today: LocalDate,
        weeks: Int = 12,
        firstDayOfWeek: DayOfWeek = defaultFirstDayOfWeek(),
    ): List<WeekBar> = heatmap(daily, today, weeks, firstDayOfWeek).map { col ->
        WeekBar(col.firstOrNull()?.date ?: today, col.sumOf { it.seconds } / 60)
    }

    /** Секунды чтения по часу начала сессии (0…23), местное время. */
    fun hourDistribution(sessions: List<ReadingSession>, zone: ZoneId = ZoneId.systemDefault()): List<Int> {
        val hours = IntArray(24)
        for (s in sessions) if (s.hasClock) {
            hours[Instant.ofEpochSecond(s.start).atZone(zone).hour] += s.secs
        }
        return hours.toList()
    }

    /** Итоги по книгам, больше всего времени — первыми. */
    fun perBook(sessions: List<ReadingSession>): List<BookStat> {
        val map = LinkedHashMap<String, BookStat>()
        for (s in sessions) {
            var st = map[s.book] ?: BookStat(s.book, s.title, 0, 0, 0, -1, 0)
            st = st.copy(seconds = st.seconds + s.secs, sessions = st.sessions + 1, pages = st.pages + s.pages)
            if (s.start >= st.lastRead) {
                st = st.copy(lastRead = s.start, lastPct = if (s.pct >= 0) s.pct else st.lastPct)
            }
            if (st.title.isEmpty()) st = st.copy(title = s.title)
            map[s.book] = st
        }
        return map.values.sortedByDescending { it.seconds }
    }

    /**
     * Первый и последний день (включительно) периода `period`, содержащего `today`.
     * `CUSTOM` — `customFrom`…`customTo` (переставляются, если перепутаны; не позже сегодня).
     */
    fun reviewRange(
        period: ReviewPeriod,
        today: LocalDate,
        customFrom: LocalDate? = null,
        customTo: LocalDate? = null,
        firstDayOfWeek: DayOfWeek = defaultFirstDayOfWeek(),
    ): DayRange = when (period) {
        ReviewPeriod.DAY -> DayRange(today, today)
        ReviewPeriod.WEEK -> DayRange(today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek)), today)
        ReviewPeriod.MONTH -> DayRange(today.withDayOfMonth(1), today)
        ReviewPeriod.YEAR -> DayRange(today.withDayOfYear(1), today)
        ReviewPeriod.CUSTOM -> {
            var a = customFrom ?: today
            var b = customTo ?: today
            if (a > b) a = b.also { b = a }
            DayRange(minOf(a, today), minOf(b, today))
        }
    }

    private fun epochSecondsAtStart(day: LocalDate, zone: ZoneId): Long = day.atStartOfDay(zone).toEpochSecond()

    /** Сводка за включительный диапазон локальных дней `from`…`to`. */
    fun review(
        from: LocalDate,
        to: LocalDate,
        sessions: List<ReadingSession>,
        daily: Map<String, Int>,
        books: List<Book>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): YearReview {
        val startKey = dayKey(from)
        val endKey = dayKey(to)
        val inRange = daily.filterKeys { it in startKey..endKey }
        val rangeSessions = sessions.filter { it.day in startKey..endKey }
        // При равенстве — самый ранний день (iOS брал первый попавшийся из словаря).
        val best = inRange.entries.filter { it.value > 0 }.sortedBy { it.key }.maxByOrNull { it.value }
        val top = perBook(rangeSessions).firstOrNull()
        val hours = hourDistribution(rangeSessions, zone)
        val maxHour = hours.maxOrNull() ?: 0
        val favHour = if (maxHour > 0) hours.indexOf(maxHour) else null
        val lo = epochSecondsAtStart(from, zone)
        val hi = epochSecondsAtStart(to.plusDays(1), zone)
        val finishedFromSessions = rangeSessions.filter { it.pct >= 100 }.mapTo(HashSet()) { it.book }
        val finishedFromStatus = books
            .filter { it.bookStatus == BookStatus.DONE && it.lastRead >= lo && it.lastRead < hi }
            .mapTo(HashSet()) { it.path }
        return YearReview(
            from = from,
            to = to,
            totalSeconds = inRange.values.sum(),
            totalPages = rangeSessions.sumOf { it.pages },
            sessions = rangeSessions.size,
            activeDays = inRange.values.count { it > 0 },
            booksFinished = (finishedFromSessions + finishedFromStatus).size,
            longestStreak = streaks(inRange, to).longest,
            bestDayKey = best?.key,
            bestDaySeconds = best?.value ?: 0,
            topBookTitle = top?.title,
            topBookSeconds = top?.seconds ?: 0,
            favoriteHour = favHour,
        )
    }

    /** Последние 365 дней (для прежней годовой карточки). */
    fun yearReview(
        sessions: List<ReadingSession>,
        daily: Map<String, Int>,
        books: List<Book>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): YearReview = review(today.minusDays(364), today, sessions, daily, books, zone)

    /** Сводка для виджетов из кэша. `updated` — момент расчёта (`recentDays` кончается этим днём). */
    fun widgetSummary(
        snapshot: SyncSnapshot,
        sessions: List<ReadingSession>,
        goalMinutes: Int,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): WidgetSummary {
        val today = localDate(nowMs, zone)
        val stats = snapshot.stats
        val daily = dailySeconds(sessions, stats?.days ?: emptyList())
        val st = streaks(daily, today)
        var todaySecs = daily[dayKey(today)] ?: 0
        val statsFresh = snapshot.statsFetchedAt?.let { localDate(it, zone) == today } ?: false
        if (stats != null && statsFresh) todaySecs = maxOf(todaySecs, stats.today.secs)
        val current = currentlyReading(snapshot.books).firstOrNull()
        return WidgetSummary(
            updated = nowMs,
            todayMinutes = todaySecs / 60,
            goalMinutes = goalMinutes,
            streak = maxOf(st.current, if (statsFresh) stats?.streak?.current ?: 0 else 0),
            longestStreak = maxOf(st.longest, stats?.streak?.longest ?: 0),
            currentBookTitle = current?.displayTitle,
            currentBookAuthor = current?.author,
            currentBookProgress = current?.progress,
            recentDays = recentDays(daily, today, 70),
            deviceName = snapshot.info?.board,
        )
    }

    /** Книги со статусом «читаю» либо начатые и недочитанные (без статуса), свежие первыми. */
    fun currentlyReading(books: List<Book>): List<Book> = books
        .filter { b -> b.bookStatus?.let { it == BookStatus.READING } ?: (b.progress in 1..99) }
        .sortedByDescending { it.lastRead }

    // endregion

    // region Сводки тепловой карты (доступность, TalkBack)

    data class WeekSummary(
        val start: LocalDate,
        val end: LocalDate,
        /** Дни с чтением (будущие не считаются). */
        val activeDays: Int,
        val seconds: Int,
        val bestDay: HeatCell?,
    )

    data class HeatmapTotals(val activeDays: Int, val seconds: Int, val weeks: Int)

    /** Один столбец (неделя) тепловой карты одной фразой для TalkBack; null — вся неделя в будущем. */
    fun weekSummary(column: List<HeatCell>): WeekSummary? {
        val past = column.filter { !it.isFuture }
        val first = past.firstOrNull() ?: return null
        val active = past.filter { it.seconds > 0 }
        return WeekSummary(first.date, past.last().date, active.size, past.sumOf { it.seconds },
            active.maxByOrNull { it.seconds })
    }

    /** Итоги всей сетки: дни с чтением, секунды, число недель. */
    fun heatmapTotals(columns: List<List<HeatCell>>): HeatmapTotals {
        val cells = columns.flatten().filter { !it.isFuture }
        return HeatmapTotals(cells.count { it.seconds > 0 }, cells.sumOf { it.seconds }, columns.size)
    }

    // endregion

    // region Итоги года (Wrapped)

    data class Wrapped(
        val year: Int,
        val review: YearReview,
        /** Сколько дней года уже прошло (весь год, если он закончился). */
        val daysInPeriod: Int,
        /** До трёх книг с наибольшим временем. */
        val topBooks: List<BookStat>,
        /** Секунды по часу дня (0…23). */
        val hours: List<Int>,
        /** Секунды по месяцам (0 — январь). */
        val months: List<Int>,
        /** 1…12, null — ничего не прочитано. */
        val bestMonth: Int?,
        val highlights: Int,
        val notes: Int,
        /** Слов посмотрено за год; null, если читалка не ведёт историю словаря. */
        val wordsLookedUp: Int?,
        /** Цитата года для карточки (короткая, самая свежая). */
        val quote: Highlight?,
    ) {
        val isEmpty: Boolean get() = review.totalSeconds == 0 && highlights == 0
    }

    /** Годы с чтением (новые первыми); текущий год есть всегда. */
    fun wrappedYears(daily: Map<String, Int>, today: LocalDate): List<Int> {
        val years = daily.filterValues { it > 0 }.keys.mapNotNullTo(HashSet()) { it.take(4).toIntOrNull() }
        years += today.year
        return years.sortedDescending()
    }

    /** Итоги года `year`: с 1 января по сегодня (или по 31 декабря для прошедшего года). */
    fun wrapped(
        year: Int,
        today: LocalDate,
        sessions: List<ReadingSession>,
        daily: Map<String, Int>,
        books: List<Book>,
        highlights: List<Highlight>,
        vocab: List<VocabWord>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Wrapped {
        val first = LocalDate.of(year, 1, 1)
        val last = LocalDate.of(year, 12, 31)
        val end = minOf(last, today)
        val start = minOf(first, end)
        val review = review(start, end, sessions, daily, books, zone)

        val startKey = dayKey(start)
        val endKey = dayKey(end)
        val rangeSessions = sessions.filter { it.day in startKey..endKey }
        val months = IntArray(12)
        for ((key, secs) in daily) {
            if (key !in startKey..endKey || key.length < 7) continue
            val m = key.substring(5, 7).toIntOrNull() ?: continue
            if (m in 1..12) months[m - 1] += secs
        }
        val maxMonth = months.maxOrNull() ?: 0

        val lo = epochSecondsAtStart(start, zone)
        val hi = epochSecondsAtStart(end.plusDays(1), zone)
        val yearHighlights = highlights.filter { it.created in lo until hi }
        val quote = yearHighlights
            .filter { it.text.isNotEmpty() && it.text.codePointCount(0, it.text.length) <= 220 }
            .maxByOrNull { it.created }
            ?: yearHighlights.maxByOrNull { it.created }
        val days = ChronoUnit.DAYS.between(start, end).toInt() + 1

        return Wrapped(
            year = year,
            review = review,
            daysInPeriod = maxOf(days, 1),
            topBooks = perBook(rangeSessions).filter { it.seconds > 0 }.take(3),
            hours = hourDistribution(rangeSessions, zone),
            months = months.toList(),
            bestMonth = if (maxMonth > 0) months.indexOf(maxMonth) + 1 else null,
            highlights = yearHighlights.size,
            notes = yearHighlights.count { it.note.isNotEmpty() },
            wordsLookedUp = if (vocab.isEmpty()) null else vocab.count { it.created in lo until hi },
            quote = quote,
        )
    }

    // endregion
}
