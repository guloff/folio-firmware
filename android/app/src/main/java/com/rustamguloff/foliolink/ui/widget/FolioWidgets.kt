package com.rustamguloff.foliolink.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.rustamguloff.foliolink.MainActivity
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.core.model.WidgetSummary
import com.rustamguloff.foliolink.core.stats.HeatPalette
import com.rustamguloff.foliolink.core.store.SharedStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// Виджеты на рабочем столе — перенос iOS Widgets/*.swift: маленький (серия и кольцо цели) и
// средний (текущая книга, серия, минуты, мини-карта за 10 недель). Данные — widget.json,
// который AppModel пишет после каждой синхронизации.

/** Обновление виджетов (подключается в `Integration.widgetReloader`). */
object FolioWidgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pending: Job? = null

    /**
     * Перерисовать все виджеты. Вызовы подряд (persist во время синхронизации) сливаются в один,
     * а пауза даёт записи widget.json дойти до диска.
     */
    fun reload(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            pending?.cancel()
            pending = scope.launch {
                delay(700)
                runCatching { ReadingSmallWidget().updateAll(app) }
                runCatching { ReadingMediumWidget().updateAll(app) }
            }
        }
    }

    /** Файл сводки — тот же, что пишет AppModel (`filesDir/Store/widget.json`). */
    fun file(context: Context): File = File(File(context.filesDir, "Store"), SharedStore.WIDGET_FILE)

    fun load(context: Context): WidgetSummary =
        SharedStore(File(context.filesDir, "Store")).loadWidgetSummary() ?: WidgetSummary()
}

/**
 * Состояние виджета — сводка из widget.json (только чтение). При `updateAll` Glance заново
 * читает состояние, так что открытая сессия виджета тоже получает свежие цифры.
 */
private object SummaryStateDefinition : GlanceStateDefinition<WidgetSummary> {
    override suspend fun getDataStore(context: Context, fileKey: String): DataStore<WidgetSummary> =
        SummaryStore(context.applicationContext)

    override fun getLocation(context: Context, fileKey: String): File = FolioWidgets.file(context)
}

private class SummaryStore(private val context: Context) : DataStore<WidgetSummary> {
    override val data: Flow<WidgetSummary>
        get() = flow { emit(withContext(Dispatchers.IO) { FolioWidgets.load(context) }) }

    /** Виджет сводку не меняет: её пишет только приложение. */
    override suspend fun updateData(transform: suspend (t: WidgetSummary) -> WidgetSummary): WidgetSummary =
        withContext(Dispatchers.IO) { FolioWidgets.load(context) }
}

// region Цвета (день/ночь)

private val Background = ColorProvider(day = Color.White, night = Color(0xFF1C1C1E))
private val Primary = ColorProvider(day = Color(0xFF1B1B1F), night = Color(0xFFF2F2F7))
private val Secondary = ColorProvider(day = Color(0xFF6B6B73), night = Color(0xFFA1A1AA))
private val Accent = ColorProvider(day = Color(0xFF1B2A4A), night = Color(0xFF8DA6D9))
private val AccentTrack = ColorProvider(day = Color(0x2E1B2A4A), night = Color(0x2E8DA6D9))
private val Success = ColorProvider(day = Color(0xFF2E9E5B), night = Color(0xFF4CC27A))
private val Flame = ColorProvider(day = Color(0xFFF08A24), night = Color(0xFFF5A04A))
private val HeatColors = (0..4).map { ColorProvider(day = Color(HeatPalette.argb(it, false)), night = Color(HeatPalette.argb(it, true))) }

// endregion

private fun openApp(context: Context, host: String) = actionStartActivity(
    Intent(Intent.ACTION_VIEW, "foliolink://$host".toUri(), context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
)

/** Маленький виджет: серия, кольцо минут против цели, «Цель выполнена». Касание — «Главная». */
class ReadingSmallWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single
    override val stateDefinition: GlanceStateDefinition<*> = SummaryStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val s = WidgetData.adjusted(currentState<WidgetSummary>(), System.currentTimeMillis())
            SmallContent(s)
        }
    }
}

/** Средний виджет: текущая книга, прогресс, серия, минуты и мини-карта. Касание — «Статистика». */
class ReadingMediumWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single
    override val stateDefinition: GlanceStateDefinition<*> = SummaryStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val s = WidgetData.adjusted(currentState<WidgetSummary>(), System.currentTimeMillis())
            MediumContent(s)
        }
    }
}

class ReadingSmallWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ReadingSmallWidget()
}

class ReadingMediumWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ReadingMediumWidget()
}

@Composable
private fun SmallContent(s: WidgetSummary) {
    val context = LocalContext.current
    val met = WidgetData.goalMet(s)
    val a11y = context.getString(R.string.widget_small_a11y, s.streak, s.todayMinutes, s.goalMinutes)
    Column(
        GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(Background)
            .cornerRadius(20.dp)
            .padding(12.dp)
            .clickable(openApp(context, "home"))
            .semantics { contentDescription = a11y },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(ImageProvider(R.drawable.widget_flame), contentDescription = null, GlanceModifier.size(16.dp),
                colorFilter = ColorFilter.tint(Flame))
            Spacer(GlanceModifier.width(4.dp))
            Text("${s.streak}", style = TextStyle(color = Primary, fontSize = 22.sp, fontWeight = FontWeight.Bold))
            Spacer(GlanceModifier.width(4.dp))
            Text(context.getString(R.string.widget_days_short), style = TextStyle(color = Secondary, fontSize = 12.sp))
        }
        Box(GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.Center) {
            GoalRing(s, met, 78.dp)
        }
        Text(
            context.getString(if (met) R.string.widget_goal_met else R.string.widget_min_today),
            GlanceModifier.fillMaxWidth(),
            style = TextStyle(color = if (met) Success else Secondary, fontSize = 11.sp,
                textAlign = androidx.glance.text.TextAlign.Center),
        )
    }
}

/** Кольцо цели: дорожка и дуга — маски, окрашенные дневным/ночным цветом; число в центре. */
@Composable
private fun GoalRing(s: WidgetSummary, met: Boolean, size: Dp) {
    val context = LocalContext.current
    val px = (size.value * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    val stroke = 9f * context.resources.displayMetrics.density
    Box(GlanceModifier.size(size), contentAlignment = Alignment.Center) {
        Image(ImageProvider(WidgetBitmaps.ring(px, stroke, 1f)), contentDescription = null, GlanceModifier.fillMaxSize(),
            colorFilter = ColorFilter.tint(AccentTrack))
        Image(ImageProvider(WidgetBitmaps.ring(px, stroke, WidgetData.progress(s))), contentDescription = null,
            GlanceModifier.fillMaxSize(), colorFilter = ColorFilter.tint(if (met) Success else Accent))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${s.todayMinutes}", style = TextStyle(color = Primary, fontSize = 20.sp, fontWeight = FontWeight.Bold))
            Text(context.getString(R.string.widget_of_goal, s.goalMinutes), style = TextStyle(color = Secondary, fontSize = 11.sp))
        }
    }
}

@Composable
private fun MediumContent(s: WidgetSummary) {
    val context = LocalContext.current
    val title = s.currentBookTitle ?: context.getString(R.string.widget_no_book)
    val a11y = context.getString(R.string.widget_medium_a11y, title, s.streak, s.todayMinutes, s.goalMinutes)
    Row(
        GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(Background)
            .cornerRadius(20.dp)
            .padding(14.dp)
            .clickable(openApp(context, "stats"))
            .semantics { contentDescription = a11y },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(GlanceModifier.defaultWeight().fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(ImageProvider(R.drawable.widget_book), contentDescription = null, GlanceModifier.size(12.dp),
                    colorFilter = ColorFilter.tint(Secondary))
                Spacer(GlanceModifier.width(4.dp))
                Text(context.getString(R.string.widget_currently_reading), style = TextStyle(color = Secondary, fontSize = 11.sp))
            }
            Spacer(GlanceModifier.height(4.dp))
            Text(title, maxLines = 2, style = TextStyle(color = Primary, fontSize = 15.sp, fontWeight = FontWeight.Bold))
            s.currentBookAuthor?.takeIf { it.isNotEmpty() }?.let {
                Text(it, maxLines = 1, style = TextStyle(color = Secondary, fontSize = 12.sp))
            }
            Spacer(GlanceModifier.defaultWeight())
            WidgetData.bookFraction(s.currentBookProgress)?.let { f ->
                BookBar(f)
                Text("${s.currentBookProgress}%", style = TextStyle(color = Secondary, fontSize = 11.sp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(ImageProvider(R.drawable.widget_flame), contentDescription = null, GlanceModifier.size(13.dp),
                    colorFilter = ColorFilter.tint(Flame))
                Spacer(GlanceModifier.width(2.dp))
                Text("${s.streak}", style = TextStyle(color = Flame, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                Spacer(GlanceModifier.width(10.dp))
                Text(context.getString(R.string.widget_minutes_of_goal, s.todayMinutes, s.goalMinutes),
                    style = TextStyle(color = Secondary, fontSize = 12.sp, fontWeight = FontWeight.Medium))
            }
        }
        Spacer(GlanceModifier.width(14.dp))
        MiniHeatmap(s.recentDays)
    }
}

/** Полоса прогресса книги: дорожка и заполненная часть — маски, окрашенные акцентом. */
@Composable
private fun BookBar(fraction: Float) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val w = (120 * density).toInt()
    val h = (5 * density).toInt().coerceAtLeast(1)
    Box(GlanceModifier.fillMaxWidth().height(5.dp)) {
        Image(ImageProvider(bar(w, h, 1f)), contentDescription = null, GlanceModifier.fillMaxWidth().height(5.dp),
            colorFilter = ColorFilter.tint(AccentTrack), contentScale = androidx.glance.layout.ContentScale.FillBounds)
        Image(ImageProvider(bar(w, h, fraction)), contentDescription = null, GlanceModifier.fillMaxWidth().height(5.dp),
            colorFilter = ColorFilter.tint(Accent), contentScale = androidx.glance.layout.ContentScale.FillBounds)
    }
}

private fun bar(w: Int, h: Int, fraction: Float): android.graphics.Bitmap {
    val bmp = createBitmap(w, h)
    val f = fraction.coerceIn(0f, 1f)
    if (f > 0f) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        android.graphics.Canvas(bmp).drawRoundRect(android.graphics.RectF(0f, 0f, maxOf(w * f, h.toFloat()), h.toFloat()),
            h / 2f, h / 2f, paint)
    }
    return bmp
}

/** Последние 10 недель: 7 строк, столбцы — недели, последний кончается сегодня. */
@Composable
private fun MiniHeatmap(days: List<Int>) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val columns = WidgetData.miniHeatmap(days)
    val cell = 10f
    val gap = 3f
    val width = (columns.size * cell + (columns.size - 1) * gap).dp
    val height = (7 * cell + 6 * gap).dp
    Box(GlanceModifier.size(width, height)) {
        for (level in 0..4) {
            Image(
                ImageProvider(WidgetBitmaps.heatLevel(columns, level, cell * density, gap * density)),
                contentDescription = null,
                modifier = GlanceModifier.size(width, height),
                colorFilter = ColorFilter.tint(HeatColors[level]),
            )
        }
    }
}
