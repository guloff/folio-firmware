package com.rustamguloff.foliolink.ui.screensaver

import android.graphics.Bitmap
import com.rustamguloff.foliolink.core.imaging.DitherMethod
import com.rustamguloff.foliolink.core.imaging.ToneAdjustments
import com.rustamguloff.foliolink.core.stats.StatsEngine
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Модель студии заставок (порт ScreensaverTemplates.swift): шаблоны, настройки и чистая
// геометрия раскладки, которую рисует ScreensaverCanvas и проверяют JVM-тесты.

/** Шаблон заставки; `raw` идёт в имя файла (`foliolink-<raw>-…bmp`), как в iOS. */
enum class ScreensaverTemplate(val raw: String) {
    PHOTO("photo"),
    ID_CARD("idCard"),
    QUOTE("quote"),
    POSTER("poster"),
    CALENDAR("calendar"),
}

/** Гарнитура текстового постера. */
enum class PosterFont { SERIF, SANS, MONO, ROUNDED }

/**
 * Всё, что нужно для отрисовки заставки. Фото — уже уменьшенные Bitmap (сравниваются по ссылке,
 * так что новый кадр перерисовывает предпросмотр).
 */
data class ScreensaverConfig(
    val template: ScreensaverTemplate = ScreensaverTemplate.QUOTE,
    val tone: ToneAdjustments = ToneAdjustments(),
    val dither: DitherMethod = DitherMethod.FLOYD_STEINBERG,
    // Фото
    val photo: Bitmap? = null,
    val photoFill: Boolean = true,
    val zoom: Double = 1.0,
    val offsetX: Double = 0.0,
    val offsetY: Double = 0.0,
    val photoCaption: String = "",
    // Визитка
    val cardHeadline: String = "",
    val cardName: String = "",
    val cardPhone: String = "",
    val cardEmail: String = "",
    val cardNote: String = "",
    val cardPhoto: Bitmap? = null,
    // Цитата
    val quoteText: String = "",
    val quoteAuthor: String = "",
    // Постер
    val posterText: String = "",
    val posterSubtitle: String = "",
    val posterFont: PosterFont = PosterFont.SERIF,
    val posterSize: Double = 64.0,
    val posterInverted: Boolean = false,
    val posterCentered: Boolean = true,
    // Календарь
    val calendarTitle: String = "",
    val calendarInverted: Boolean = false,
)

/** Статистика для календарного постера (считается на главном потоке из AppModel). */
data class CalendarPosterData(
    val columns: List<List<StatsEngine.HeatCell>> = emptyList(),
    val streak: Int = 0,
    val longest: Int = 0,
    val hours: String = "0",
    val books: Int = 0,
    val todayMinutes: Int = 0,
    val goal: Int = 30,
)

/** Прямоугольник с дробными координатами (без android.graphics — для тестов). */
data class FRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

/** Размер экрана читалки и размещение картинки на нём. */
object PanelGeometry {
    const val WIDTH = 480
    const val HEIGHT = 800

    /**
     * Где на панели лежит картинка `iw`×`ih`: «Заполнить» (с обрезкой) или «Вписать» (с полями),
     * затем увеличение вокруг центра и сдвиг (−1…1 — на полпанели), как в iOS.
     */
    fun place(
        iw: Int,
        ih: Int,
        fill: Boolean,
        zoom: Double = 1.0,
        offsetX: Double = 0.0,
        offsetY: Double = 0.0,
        pw: Int = WIDTH,
        ph: Int = HEIGHT,
    ): FRect {
        val w0 = max(iw, 1).toDouble()
        val h0 = max(ih, 1).toDouble()
        val base = if (fill) max(pw / w0, ph / h0) else min(pw / w0, ph / h0)
        val s = base * zoom
        val w = w0 * s
        val h = h0 * s
        val left = (pw - w) / 2 + offsetX * pw / 2
        val top = (ph - h) / 2 + offsetY * ph / 2
        return FRect(left.toFloat(), top.toFloat(), w.toFloat(), h.toFloat())
    }
}

/** Раскладка текста: перенос по словам и подбор кегля (аналог minimumScaleFactor в SwiftUI). */
object TextFit {
    /** Текст, уложенный в строки при данном кегле. */
    data class Fitted(val size: Float, val lines: List<String>, val lineHeight: Float) {
        val height: Float get() = lines.size * lineHeight
    }

    /**
     * Перенос по словам в ширину `maxWidth`. Явные переводы строки сохраняются (пустая строка —
     * пустая строка), слово шире строки рвётся по символам.
     */
    fun wrap(text: String, maxWidth: Float, measure: (String) -> Float): List<String> {
        val out = mutableListOf<String>()
        for (paragraph in text.split('\n')) {
            val words = paragraph.split(' ', '\t').filter { it.isNotEmpty() }
            if (words.isEmpty()) {
                out += ""
                continue
            }
            var current = ""
            for (word in words) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (measure(candidate) <= maxWidth) {
                    current = candidate
                    continue
                }
                if (current.isNotEmpty()) out += current
                current = word
                // Слово само шире строки: режем по символам (минимум один символ в строке).
                while (current.length > 1 && measure(current) > maxWidth) {
                    var n = current.length - 1
                    while (n > 1 && measure(current.substring(0, n)) > maxWidth) n--
                    out += current.substring(0, n)
                    current = current.substring(n)
                }
            }
            out += current
        }
        return out
    }

    /**
     * Самый крупный кегль от `maxSize` до `maxSize·minScale` (шаг — 1 пункт), при котором текст
     * помещается в `maxWidth`×`maxHeight` и не длиннее `maxLines` строк. Не влезает и на
     * минимальном — минимальный кегль, лишние строки отбрасываются, последняя кончается «…».
     *
     * `measure(text, size)` — ширина строки, `lineHeight(size)` — шаг строк.
     */
    fun fit(
        text: String,
        maxWidth: Float,
        maxHeight: Float,
        maxSize: Float,
        minScale: Float = 1f,
        maxLines: Int = Int.MAX_VALUE,
        lineHeight: (Float) -> Float,
        measure: (String, Float) -> Float,
    ): Fitted {
        val minSize = maxSize * minScale.coerceIn(0.05f, 1f)
        var size = maxSize
        while (true) {
            val s = size
            val lines = wrap(text, maxWidth) { measure(it, s) }
            val lh = lineHeight(s)
            if (lines.size <= maxLines && lines.size * lh <= maxHeight + 0.5f) return Fitted(s, lines, lh)
            if (size <= minSize) break
            size = max(minSize, size - 1f)
        }
        val lh = lineHeight(minSize)
        val all = wrap(text, maxWidth) { measure(it, minSize) }
        val fitLines = max(1, min(maxLines, (maxHeight / lh).toInt()))
        if (all.size <= fitLines) return Fitted(minSize, all, lh)
        val kept = all.take(fitLines).toMutableList()
        var last = kept.last()
        while (last.isNotEmpty() && measure("$last…", minSize) > maxWidth) last = last.dropLast(1)
        kept[kept.lastIndex] = last.trimEnd() + "…"
        return Fitted(minSize, kept, lh)
    }
}

/** Раскладка календарного постера: 26 недель тепловой карты, как в iOS. */
object CalendarLayout {
    const val WEEKS = 26
    const val PADDING = 32
    const val GAP = 3f
    const val MAX_CELL = 14f

    /** Сторона клетки: столбцы недель с зазором 3 помещаются в ширину без полей. */
    fun cellSize(columns: Int, width: Int = PanelGeometry.WIDTH): Float {
        if (columns <= 0) return MAX_CELL
        return min(MAX_CELL, (width - 2 * PADDING - (columns - 1) * GAP) / columns)
    }

    /** Левый верхний угол клетки (неделя `column`, день `row`) от начала сетки. */
    fun cellOrigin(column: Int, row: Int, cell: Float): Pair<Float, Float> =
        column * (cell + GAP) to row * (cell + GAP)

    /** Высота сетки из 7 строк. */
    fun gridHeight(cell: Float): Float = 7 * cell + 6 * GAP

    /** Серый (0…255) для уровня 0…4; на чёрном фоне шкала перевёрнута. */
    fun gray(level: Int, inverted: Boolean): Int {
        val map = if (inverted) doubleArrayOf(0.0, 0.33, 0.5, 0.66, 1.0) else doubleArrayOf(1.0, 0.66, 0.5, 0.33, 0.0)
        return (map[level.coerceIn(0, 4)] * 255).roundToInt()
    }
}
