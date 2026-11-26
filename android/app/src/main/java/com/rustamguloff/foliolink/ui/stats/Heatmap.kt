package com.rustamguloff.foliolink.ui.stats

import android.content.Context
import android.content.res.Resources
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.core.model.ReadingSession
import com.rustamguloff.foliolink.core.stats.HeatPalette
import com.rustamguloff.foliolink.core.stats.StatsEngine
import com.rustamguloff.foliolink.ui.common.Fmt
import com.rustamguloff.foliolink.ui.common.SectionHeader
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

private val CELL = 12.dp
private val GAP = 3.dp

/** Строки озвучки тепловой карты из ресурсов. */
class ResHeatmapTexts(private val res: Resources) : StatsLogic.HeatmapTexts {
    override fun duration(seconds: Int) = StatsFmt.durationFull(res, seconds)
    override fun shortDate(date: LocalDate) = StatsFmt.shortDate(date)
    override fun weekday(date: LocalDate): String = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
    override fun summary(weeks: Int, activeDays: Int, duration: String) =
        res.getString(R.string.stats_heatmap_a11y_summary, weeks, activeDays, duration)
    override fun weekEmpty(range: String) = res.getString(R.string.stats_heatmap_a11y_week_empty, range)
    override fun week(range: String, activeDays: Int, duration: String) =
        res.getString(R.string.stats_heatmap_a11y_week, range, activeDays, duration)
    override fun mostOn(weekday: String, duration: String) = res.getString(R.string.stats_heatmap_a11y_most, weekday, duration)
}

/** Цвета уровней 0…4 для текущей темы (светлая/тёмная шкала HeatPalette). */
@Composable
fun heatColors(): List<Color> {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return remember(dark) { (0..4).map { Color(HeatPalette.argb(it, dark)) } }
}

/**
 * Календарь чтения за год, как на GitHub: столбцы — недели. Касание дня — подробности.
 * Для TalkBack сетка — один регулируемый элемент: сводка года, значение — выбранная неделя;
 * смахивание вверх/вниз (или действия «Предыдущая/Следующая неделя») листает недели,
 * двойное касание открывает лучший день недели.
 */
@Composable
fun HeatmapView(
    daily: Map<String, Int>,
    selectedKey: String?,
    onSelect: (StatsEngine.HeatCell) -> Unit,
    weeks: Int = 53,
) {
    val resources = LocalResources.current
    val density = LocalDensity.current
    val today = LocalDate.now()
    val columns = remember(daily, today) { StatsEngine.heatmap(daily, today, weeks) }
    val colors = heatColors()
    val outline = MaterialTheme.colorScheme.onSurface
    val scroll = rememberScrollState()
    var a11yWeek by remember { mutableStateOf<Int?>(null) }
    val week = (a11yWeek ?: (columns.size - 1)).coerceIn(0, maxOf(columns.size - 1, 0))
    val texts = remember(resources) { ResHeatmapTexts(resources) }
    val summary = remember(columns, texts) { StatsLogic.heatmapSummary(columns, texts) }
    val weekText = remember(columns, week, texts) { columns.getOrNull(week)?.let { StatsLogic.weekText(it, texts) }.orEmpty() }
    val stepPx = with(density) { (CELL + GAP).toPx() }
    val cellPx = with(density) { CELL.toPx() }

    // Сначала видна текущая неделя (справа).
    LaunchedEffect(Unit) {
        snapshotFlow { scroll.maxValue }.first { it != Int.MAX_VALUE }
        scroll.scrollTo(scroll.maxValue)
    }
    // Неделя TalkBack — в середине видимой части.
    LaunchedEffect(a11yWeek) {
        val w = a11yWeek ?: return@LaunchedEffect
        scroll.animateScrollTo((w * stepPx - scroll.viewportSize / 2f + cellPx / 2).roundToInt().coerceAtLeast(0))
    }

    val prevLabel = stringResource(R.string.stats_heatmap_prev_week)
    val nextLabel = stringResource(R.string.stats_heatmap_next_week)
    val openLabel = stringResource(R.string.stats_heatmap_open_day)
    val gridWidth = CELL * columns.size + GAP * (columns.size - 1).coerceAtLeast(0)
    val gridHeight = CELL * 7 + GAP * 6

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {
                    contentDescription = summary
                    stateDescription = weekText
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        week.toFloat(), 0f..maxOf(columns.size - 1, 1).toFloat(), steps = maxOf(columns.size - 2, 0),
                    )
                    setProgress { v -> a11yWeek = v.roundToInt().coerceIn(0, maxOf(columns.size - 1, 0)); true }
                    customActions = listOf(
                        CustomAccessibilityAction(prevLabel) { a11yWeek = StatsLogic.stepWeek(week, -1, columns.size); true },
                        CustomAccessibilityAction(nextLabel) { a11yWeek = StatsLogic.stepWeek(week, 1, columns.size); true },
                    )
                    onClick(openLabel) {
                        columns.getOrNull(week)?.let(StatsLogic::dayToOpen)?.let(onSelect)
                        true
                    }
                }
                .horizontalScroll(scroll),
        ) {
            Column(Modifier.padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.width(gridWidth).height(16.dp)) {
                    val starts = remember(columns) { StatsLogic.monthStarts(columns.map { it.first().date }, minGap = 3) }
                    for (i in starts) {
                        Text(
                            StatsFmt.monthShort(columns[i].first().date),
                            Modifier.offset(x = (CELL + GAP) * i),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                Canvas(
                    Modifier
                        .size(gridWidth, gridHeight)
                        .pointerInput(columns) {
                            detectTapGestures { o ->
                                val c = (o.x / stepPx).toInt()
                                val r = (o.y / stepPx).toInt()
                                columns.getOrNull(c)?.getOrNull(r)?.takeIf { !it.isFuture }?.let(onSelect)
                            }
                        },
                ) {
                    val radius = CornerRadius(2.5.dp.toPx())
                    val size = Size(cellPx, cellPx)
                    columns.forEachIndexed { c, col ->
                        col.forEachIndexed { r, cell ->
                            if (cell.isFuture) return@forEachIndexed
                            val topLeft = Offset(c * stepPx, r * stepPx)
                            drawRoundRect(colors[cell.level.coerceIn(0, 4)], topLeft, size, radius)
                            if (cell.key == selectedKey) {
                                drawRoundRect(outline, topLeft, size, radius, style = Stroke(1.5.dp.toPx()))
                            }
                        }
                    }
                }
            }
        }
        HeatLegend(colors)
        val legendA11y = stringResource(R.string.stats_heatmap_scale_a11y)
        Text(
            stringResource(R.string.stats_heatmap_scale),
            Modifier.semantics { contentDescription = legendA11y },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HeatLegend(colors: List<Color>) {
    Row(
        Modifier.clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.stats_heatmap_less), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        colors.forEach { c -> Box(Modifier.size(11.dp).background(c, RoundedCornerShape(2.5.dp))) }
        Text(stringResource(R.string.stats_heatmap_more), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Окно дня: время, страницы, цель и сессии из журнала. */
@Composable
fun DayDetailSheet(
    cell: StatsEngine.HeatCell,
    sessions: List<ReadingSession>,
    goalMinutes: Int,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val state = rememberModalBottomSheetState()
    val day = remember(cell, sessions) { StatsLogic.sessionsOn(cell.key, sessions) }
    val timeFormat = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                StatsFmt.longDate(cell.date),
                Modifier.padding(horizontal = 16.dp).semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DetailRow(stringResource(R.string.stats_day_time), Fmt.duration(context, cell.seconds))
                DetailRow(stringResource(R.string.stats_day_pages), day.sumOf { it.pages }.toString())
                DetailRow(
                    stringResource(R.string.stats_day_goal),
                    stringResource(if (cell.seconds / 60 >= goalMinutes) R.string.stats_day_goal_met else R.string.stats_day_goal_not_met),
                )
            }
            SectionHeader(stringResource(R.string.stats_day_sessions))
            if (day.isEmpty()) {
                Text(stringResource(R.string.stats_day_no_sessions), Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            day.forEachIndexed { i, s ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics(mergeDescendants = true) {}) {
                    Text(s.title.ifEmpty { s.book.substringAfterLast('/') }, style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium)
                    val parts = buildList {
                        if (s.hasClock) add(timeFormat.format(Instant.ofEpochSecond(s.start).atZone(ZoneId.systemDefault())))
                        add(Fmt.duration(context, s.secs))
                        add(pagesText(context, s.pages))
                        if (s.pct >= 0) add("${s.pct}%")
                    }
                    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (i != day.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

private fun pagesText(context: Context, pages: Int): String =
    context.resources.getQuantityString(R.plurals.plural_page, pages, pages)

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
