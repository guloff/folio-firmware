package com.rustamguloff.foliolink.ui.stats

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoAwesomeMotion
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Button
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.core.model.Book
import com.rustamguloff.foliolink.core.model.ReadingSession
import com.rustamguloff.foliolink.core.stats.StatsEngine
import com.rustamguloff.foliolink.core.stats.StatsEngine.ReviewPeriod
import com.rustamguloff.foliolink.ui.common.Card
import com.rustamguloff.foliolink.ui.common.ChipRow
import com.rustamguloff.foliolink.ui.common.Fmt
import com.rustamguloff.foliolink.ui.common.FolioFilterChip
import com.rustamguloff.foliolink.ui.shell.Dest
import com.rustamguloff.foliolink.ui.shell.LocalRouter
import kotlinx.coroutines.launch
import java.time.LocalDate

private const val DAY_MS = 86_400_000L

/** Выбор периода карточки «Итоги» переживает перезапуск (как @AppStorage в iOS). */
private class ReviewPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("foliolink.stats", Context.MODE_PRIVATE)

    var period: ReviewPeriod
        get() = ReviewPeriod.from(prefs.getString("reviewPeriod", null)) ?: ReviewPeriod.DAY
        set(v) = prefs.edit { putString("reviewPeriod", v.raw) }

    var customFrom: LocalDate
        get() = LocalDate.ofEpochDay(prefs.getLong("reviewCustomFrom", LocalDate.now().minusDays(6).toEpochDay()))
        set(v) = prefs.edit { putLong("reviewCustomFrom", v.toEpochDay()) }

    var customTo: LocalDate
        get() = LocalDate.ofEpochDay(prefs.getLong("reviewCustomTo", LocalDate.now().toEpochDay()))
        set(v) = prefs.edit { putLong("reviewCustomTo", v.toEpochDay()) }
}

private fun ReviewPeriod.chipRes(): Int = when (this) {
    ReviewPeriod.DAY -> R.string.stats_period_day
    ReviewPeriod.WEEK -> R.string.stats_period_week
    ReviewPeriod.MONTH -> R.string.stats_period_month
    ReviewPeriod.YEAR -> R.string.stats_period_year
    ReviewPeriod.CUSTOM -> R.string.stats_period_custom
}

private fun ReviewPeriod.titleRes(): Int = when (this) {
    ReviewPeriod.DAY -> R.string.stats_review_day
    ReviewPeriod.WEEK -> R.string.stats_review_week
    ReviewPeriod.MONTH -> R.string.stats_review_month
    ReviewPeriod.YEAR -> R.string.stats_review_year
    ReviewPeriod.CUSTOM -> R.string.stats_review_custom
}

/** «Итоги»: сводка за день (по умолчанию), неделю, месяц, год или свой период; «Поделиться» картинкой. */
@Composable
fun ReviewCard(sessions: List<ReadingSession>, daily: Map<String, Int>, books: List<Book>, goalMinutes: Int) {
    val context = LocalContext.current
    val router = LocalRouter.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { ReviewPrefs(context) }
    var period by remember { mutableStateOf(prefs.period) }
    var customFrom by remember { mutableStateOf(prefs.customFrom) }
    var customTo by remember { mutableStateOf(prefs.customTo) }
    var editingRange by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val range = StatsEngine.reviewRange(period, today, customFrom, customTo)
    val review = remember(range, sessions, daily, books) { StatsEngine.review(range.from, range.to, sessions, daily, books) }
    val title = stringResource(period.titleRes())

    // Box: временный узел снимка не сдвигает соседние карточки.
    Box {
        Card(title = stringResource(R.string.stats_review), icon = Icons.Outlined.AutoAwesome) {
            ChipRow(contentPadding = PaddingValues(0.dp)) {
                ReviewPeriod.entries.forEach { p ->
                    FolioFilterChip(stringResource(p.chipRes()), selected = p == period, onClick = {
                        period = p
                        prefs.period = p
                        if (p == ReviewPeriod.CUSTOM) editingRange = true
                    })
                }
            }
            if (period == ReviewPeriod.CUSTOM) {
                TextButton(onClick = { editingRange = true }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Icon(Icons.Outlined.CalendarMonth, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(StatsFmt.mediumDate(range.from) + " — " + StatsFmt.mediumDate(range.to))
                }
            }
            ReviewPoster(review, title, Modifier.clip(RoundedCornerShape(14.dp)))
            OutlinedButton(onClick = { capturing = true }, enabled = !capturing, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.IosShare, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.stats_share_image))
            }
            Button(onClick = { router.open(Dest.WRAPPED) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.AutoAwesomeMotion, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.stats_wrapped_cards))
            }
        }

        if (capturing) {
            // Как в iOS: ширина 360, масштаб 3 → 1080 px; высота по содержимому.
            OffscreenCapture(width = 360.dp, height = null, onCaptured = { image ->
                capturing = false
                if (image != null) scope.launch { ShareImage.share(context, image, "FolioLink-${period.raw}.png") }
            }) {
                ReviewPoster(review, title)
            }
        }
    }

    if (editingRange) {
        ReviewRangeDialog(
            from = customFrom,
            to = customTo,
            today = today,
            onDismiss = { editingRange = false },
            onConfirm = { a, b ->
                customFrom = a; customTo = b
                prefs.customFrom = a; prefs.customTo = b
                editingRange = false
            },
        )
    }
}

/** «Свой период»: выбор диапазона дат не позже сегодня. */
@Composable
private fun ReviewRangeDialog(
    from: LocalDate,
    to: LocalDate,
    today: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalDate) -> Unit,
) {
    val todayMs = today.toEpochDay() * DAY_MS
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = from.toEpochDay() * DAY_MS,
        initialSelectedEndDateMillis = to.toEpochDay() * DAY_MS,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayMs
            override fun isSelectableYear(year: Int) = year <= today.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedStartDateMillis != null,
                onClick = {
                    val a = LocalDate.ofEpochDay(state.selectedStartDateMillis!! / DAY_MS)
                    val b = state.selectedEndDateMillis?.let { LocalDate.ofEpochDay(it / DAY_MS) } ?: a
                    onConfirm(a, b)
                },
            ) { Text(stringResource(R.string.common_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    ) {
        DateRangePicker(
            state,
            Modifier.weight(1f),
            title = {
                Text(stringResource(R.string.stats_custom_period), Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp))
            },
        )
    }
}

private val PosterTop = Color(0xFF121A2E)
private val PosterBottom = Color(0xFF29385C)

/** Плакат итогов: белый текст на тёмно-синем градиенте (одинаков в светлой и тёмной теме). */
@Composable
fun ReviewPoster(review: StatsEngine.YearReview, title: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val minutes = review.totalSeconds / 60
    val multiDay = review.from != review.to
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(listOf(PosterTop, PosterBottom)))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                Text(StatsFmt.range(review.from, review.to), style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f))
            }
            Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null, Modifier.size(36.dp), tint = Color.White)
        }
        PosterRow(
            {
                if (review.totalSeconds < 3600) {
                    BigStat("$minutes", pluralStringResource(R.plurals.stats_noun_minute, minutes))
                } else {
                    BigStat(Fmt.hours(review.totalSeconds), stringResource(R.string.stats_hours))
                }
            },
            { BigStat("${review.booksFinished}", stringResource(R.string.stats_books_finished)) },
        )
        if (multiDay) {
            PosterRow(
                { BigStat("${review.activeDays}", stringResource(R.string.stats_days_with_book)) },
                { BigStat("${review.longestStreak}", stringResource(R.string.stats_days_best_streak)) },
            )
        }
        PosterRow(
            { BigStat("${review.totalPages}", pluralStringResource(R.plurals.stats_noun_page, review.totalPages)) },
            { BigStat("${review.sessions}", pluralStringResource(R.plurals.stats_noun_session, review.sessions)) },
        )
        review.topBookTitle?.let { top ->
            HorizontalDivider(color = Color.White.copy(alpha = 0.4f))
            Text(
                topBookText(stringResource(R.string.stats_top_book), top, Fmt.duration(context, review.topBookSeconds)),
                style = MaterialTheme.typography.bodyMedium, color = Color.White,
            )
        }
        if (multiDay) {
            val best = review.bestDayKey?.let(StatsEngine::dateFromKey)
            if (best != null) {
                Text(
                    stringResource(R.string.stats_best_day, StatsFmt.longDate(best), Fmt.duration(context, review.bestDaySeconds)),
                    style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f),
                )
            }
        }
        Text(
            stringResource(R.string.stats_poster_brand),
            Modifier.align(Alignment.End).clearAndSetSemantics { },
            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.6f),
        )
    }
}

/** «Главная книга: **Название** — 3 ч» (`template` с %1$s — название, %2$s — время). */
fun topBookText(template: String, title: String, duration: String) = buildAnnotatedString {
    val marker = "\u0000"
    val parts = String.format(template, marker, duration).split(marker)
    append(parts.first())
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(title) }
    if (parts.size > 1) append(parts[1])
}

@Composable
private fun PosterRow(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.weight(1f)) { first() }
        Column(Modifier.weight(1f)) { second() }
    }
}

@Composable
private fun BigStat(value: String, label: String) {
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "$value $label" }) {
        Text(value, fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.White, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
    }
}
