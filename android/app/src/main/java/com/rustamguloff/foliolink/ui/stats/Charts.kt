package com.rustamguloff.foliolink.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.core.model.Book
import com.rustamguloff.foliolink.core.model.ReadingSession
import com.rustamguloff.foliolink.core.stats.StatsEngine
import com.rustamguloff.foliolink.ui.common.Card
import com.rustamguloff.foliolink.ui.common.Fmt
import com.rustamguloff.foliolink.ui.theme.FolioSuccess
import java.time.LocalDate

/** «По неделям»: минуты за 12 недель и пунктир цели на неделю (цель дня × 7). */
@Composable
fun WeeklyChartCard(daily: Map<String, Int>, goalMinutes: Int) {
    val resources = LocalResources.current
    val today = LocalDate.now()
    val bars = remember(daily, today) { StatsEngine.weeklyBars(daily, today, 12) }
    val weeklyGoal = goalMinutes * 7
    val avg = StatsLogic.weeklyAverage(bars)
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = muted)
    val goalLabel = stringResource(R.string.stats_weekly_goal)
    val description = remember(bars, weeklyGoal, resources) {
        bars.joinToString("; ") { resources.getString(R.string.stats_weekly_bar_a11y, StatsFmt.shortDate(it.weekStart), it.minutes) } +
            ". " + resources.getString(R.string.stats_weekly_goal_a11y, weeklyGoal)
    }
    Card(title = stringResource(R.string.stats_by_week), icon = Icons.Outlined.BarChart) {
        Canvas(Modifier.fillMaxWidth().height(180.dp).semantics { contentDescription = description }) {
            val axisW = 36.dp.toPx()
            val bottomH = 18.dp.toPx()
            val chartW = size.width - axisW
            val chartH = size.height - bottomH
            val maxV = StatsLogic.weeklyScaleMax(bars, weeklyGoal).toFloat()
            // Сетка и подписи шкалы справа: 0, половина, максимум.
            for (v in listOf(0f, maxV / 2, maxV)) {
                val y = chartH - chartH * v / maxV
                drawLine(grid, Offset(0f, y), Offset(chartW, y), strokeWidth = 1f)
                drawLabel(measurer, "${v.toInt()}", labelStyle, Offset(chartW + 4.dp.toPx(), y), centerY = true)
            }
            if (bars.isNotEmpty()) {
                val slot = chartW / bars.size
                val barW = slot * 0.62f
                bars.forEachIndexed { i, bar ->
                    val h = chartH * bar.minutes / maxV
                    if (h <= 0f) return@forEachIndexed
                    val left = i * slot + (slot - barW) / 2
                    drawRoundRect(
                        Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.7f)), startY = chartH - h, endY = chartH),
                        Offset(left, chartH - h), Size(barW, h), CornerRadius(4.dp.toPx()),
                    )
                }
                for (i in StatsLogic.monthStarts(bars.map { it.weekStart }, minGap = 2)) {
                    drawLabel(measurer, StatsFmt.monthShort(bars[i].weekStart), labelStyle,
                        Offset(i * slot + (slot - barW) / 2, chartH + 3.dp.toPx()))
                }
            }
            if (weeklyGoal > 0) {
                val y = chartH - chartH * weeklyGoal / maxV
                drawLine(FolioSuccess.copy(alpha = 0.7f), Offset(0f, y), Offset(chartW, y), strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
                val layout = measurer.measure(goalLabel, labelStyle)
                drawText(layout, topLeft = Offset(2.dp.toPx(), (y - layout.size.height - 2.dp.toPx()).coerceAtLeast(0f)))
            }
        }
        Text(stringResource(R.string.stats_weekly_average, avg), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** «Время суток»: минуты по часу начала сессии, пик выделен. */
@Composable
fun HourChartCard(sessions: List<ReadingSession>) {
    val resources = LocalResources.current
    val hours = remember(sessions) { StatsEngine.hourDistribution(sessions) }
    val peak = StatsLogic.peakHour(hours)
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = muted)
    val description = remember(hours, resources) {
        hours.withIndex().filter { it.value > 0 }
            .joinToString("; ") { resources.getString(R.string.stats_hour_bar_a11y, it.index, it.value / 60) }
            .ifEmpty { resources.getString(R.string.stats_no_data) }
    }
    Card(title = stringResource(R.string.stats_time_of_day), icon = Icons.Outlined.Schedule) {
        Canvas(Modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = description }) {
            val bottomH = 18.dp.toPx()
            val chartH = size.height - bottomH
            val slot = size.width / 24
            val barW = slot * 0.7f
            val maxV = maxOf(hours.maxOrNull() ?: 0, 1).toFloat()
            hours.forEachIndexed { h, secs ->
                val barH = chartH * secs / maxV
                if (barH <= 0f) return@forEachIndexed
                drawRoundRect(
                    if (h == peak) accent else accent.copy(alpha = 0.45f),
                    Offset(h * slot + (slot - barW) / 2, chartH - barH), Size(barW, barH), CornerRadius(2.dp.toPx()),
                )
            }
            drawLine(muted.copy(alpha = 0.4f), Offset(0f, chartH), Offset(size.width, chartH), strokeWidth = 1f)
            for (h in listOf(0, 6, 12, 18, 23)) {
                val layout = measurer.measure("$h", labelStyle)
                val x = (h * slot + slot / 2 - layout.size.width / 2).coerceIn(0f, size.width - layout.size.width)
                drawText(layout, topLeft = Offset(x, chartH + 3.dp.toPx()))
            }
        }
        if (peak != null) {
            Text(stringResource(R.string.stats_peak_hour, peak), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** «По книгам»: до восьми книг с наибольшим временем. */
@Composable
fun PerBookCard(sessions: List<ReadingSession>, books: List<Book>) {
    val context = LocalContext.current
    val stats = remember(sessions) { StatsEngine.perBook(sessions).take(8) }
    val maxSecs = maxOf(stats.firstOrNull()?.seconds ?: 1, 1)
    val accent = MaterialTheme.colorScheme.primary
    Card(title = stringResource(R.string.stats_by_book), icon = Icons.AutoMirrored.Outlined.MenuBook) {
        if (stats.isEmpty()) {
            Text(stringResource(R.string.stats_no_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        stats.forEach { s ->
            Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row {
                    Text(StatsLogic.bookTitle(s, books), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Fmt.duration(context, s.seconds), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(Modifier.fillMaxWidth().height(6.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth((s.seconds.toFloat() / maxSecs).coerceIn(0.02f, 1f))
                            .height(6.dp)
                            .background(accent.copy(alpha = 0.8f), CircleShape)
                            .clearAndSetSemantics { },
                    )
                }
                val line = buildString {
                    append(pluralStringResource(R.plurals.plural_session, s.sessions, s.sessions))
                    append(" · ")
                    append(pluralStringResource(R.plurals.plural_page, s.pages, s.pages))
                    if (s.lastPct >= 0) append(" · ${s.lastPct}%")
                }
                Text(line, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    at: Offset,
    centerY: Boolean = false,
) {
    val layout = measurer.measure(text, style)
    val y = if (centerY) at.y - layout.size.height / 2f else at.y
    drawText(layout, topLeft = Offset(at.x.coerceIn(0f, maxOf(size.width - layout.size.width, 0f)),
        y.coerceIn(0f, maxOf(size.height - layout.size.height, 0f))))
}
