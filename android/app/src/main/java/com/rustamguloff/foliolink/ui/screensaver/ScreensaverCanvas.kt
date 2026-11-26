package com.rustamguloff.foliolink.ui.screensaver

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withSave
import com.rustamguloff.foliolink.core.imaging.AndroidImaging
import com.rustamguloff.foliolink.core.imaging.BmpCodec
import com.rustamguloff.foliolink.core.imaging.Dither
import com.rustamguloff.foliolink.core.imaging.DitherMethod
import com.rustamguloff.foliolink.core.imaging.ToneAdjustments
import kotlin.math.max
import kotlin.math.roundToInt

/** Строки, которые рисуются на самой картинке (берутся из ресурсов на экране). */
data class CanvasTexts(
    val choosePhoto: String = "Choose a photo",
    val namePlaceholder: String = "First Last",
    val phonePlaceholder: String = "+1 555 010 0000",
    val minPerDay: String = "min/day",
    val streak: String = "day streak",
    val record: String = "record",
    val hours: String = "hours",
    val books: String = "books",
    /** «Сегодня: 12 / 30 мин» — уже подставленная строка. */
    val today: String = "",
    /** Сегодняшняя дата под заголовком календаря. */
    val date: String = "",
)

/**
 * Отрисовка заставки ровно в 480×800 (масштаб 1, без растяжения) через android.graphics, затем
 * конвейер читалки: серое → тон → дизеринг → BMP 24 бит. Порт ScreensaverCanvas + ScreensaverRenderer.
 * Рисует только чёрным, белым и серыми, чтобы результат на читалке был предсказуем.
 */
object ScreensaverRenderer {
    const val W = PanelGeometry.WIDTH
    const val H = PanelGeometry.HEIGHT

    /** Итог: предпросмотр (ровно пиксели читалки), исходник без дизеринга и сам BMP. */
    class Result(val preview: Bitmap, val original: Bitmap, val bmp: ByteArray)

    /** Полный конвейер; звать не на главном потоке. */
    fun render(config: ScreensaverConfig, calendar: CalendarPosterData, texts: CanvasTexts): Result {
        val art = draw(config, calendar, texts)
        val gray = AndroidImaging.grayFromBitmap(art, W, H)
        art.recycle()
        val dithered = Dither.prepareForDevice(gray, config.tone, config.dither)
        return Result(AndroidImaging.toBitmap(dithered), AndroidImaging.toBitmap(gray), BmpCodec.encode24(dithered))
    }

    /** Холст шаблона 480×800. */
    fun draw(config: ScreensaverConfig, calendar: CalendarPosterData, texts: CanvasTexts): Bitmap {
        val bmp = createBitmap(W, H)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        when (config.template) {
            ScreensaverTemplate.PHOTO -> photo(c, config, texts)
            ScreensaverTemplate.ID_CARD -> idCard(c, config, texts)
            ScreensaverTemplate.QUOTE -> quote(c, config)
            ScreensaverTemplate.POSTER -> poster(c, config)
            ScreensaverTemplate.CALENDAR -> calendarPoster(c, config, calendar, texts)
        }
        return bmp
    }

    // region Шрифты и текст

    private enum class Design { SANS, SERIF, MONO, ROUNDED }
    private enum class Weight { REGULAR, MEDIUM, SEMIBOLD, BOLD, HEAVY }

    private fun face(design: Design, weight: Weight, italic: Boolean = false): Typeface {
        val bold = weight >= Weight.SEMIBOLD
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return when (design) {
            Design.SERIF -> Typeface.create(Typeface.SERIF, style)
            Design.MONO -> Typeface.create(Typeface.MONOSPACE, style)
            Design.ROUNDED -> Typeface.create("casual", style)
            Design.SANS -> when (weight) {
                Weight.HEAVY -> Typeface.create("sans-serif-black", if (italic) Typeface.ITALIC else Typeface.NORMAL)
                Weight.MEDIUM, Weight.SEMIBOLD ->
                    Typeface.create("sans-serif-medium", if (italic) Typeface.ITALIC else Typeface.NORMAL)
                else -> Typeface.create(Typeface.SANS_SERIF, style)
            }
        }
    }

    private fun PosterFont.design(): Design = when (this) {
        PosterFont.SERIF -> Design.SERIF
        PosterFont.SANS -> Design.SANS
        PosterFont.MONO -> Design.MONO
        PosterFont.ROUNDED -> Design.ROUNDED
    }

    private fun gray(v: Double): Int {
        val x = (v * 255).roundToInt().coerceIn(0, 255)
        return Color.rgb(x, x, x)
    }

    private fun textPaint(typeface: Typeface, color: Int, size: Float = 20f) =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.typeface = typeface
            this.color = color
            textSize = size
        }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

    private fun stroke(color: Int, width: Float) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = width }

    /** Блок текста: кегль подобран, строки разбиты. */
    private class Block(val paint: Paint, val fitted: TextFit.Fitted) {
        val height: Float get() = fitted.height
        val width: Float get() = fitted.lines.maxOfOrNull { paint.measureText(it) } ?: 0f
    }

    private fun block(
        text: String,
        paint: Paint,
        maxWidth: Float,
        maxHeight: Float = Float.MAX_VALUE,
        size: Float,
        minScale: Float = 1f,
        maxLines: Int = Int.MAX_VALUE,
        spacing: Float = 0f,
    ): Block {
        val fitted = TextFit.fit(
            text, maxWidth, maxHeight, size, minScale, maxLines,
            lineHeight = { s -> paint.textSize = s; paint.fontSpacing + spacing },
            measure = { line, s -> paint.textSize = s; paint.measureText(line) },
        )
        paint.textSize = fitted.size
        return Block(paint, fitted)
    }

    private enum class Align { LEFT, CENTER }

    /** Рисует строки блока сверху вниз от `top` в колонке `left`…`left+width`. */
    private fun Canvas.drawBlock(b: Block, left: Float, top: Float, width: Float, align: Align) {
        val ascent = b.paint.fontMetrics.ascent
        b.fitted.lines.forEachIndexed { i, line ->
            val w = b.paint.measureText(line)
            val x = if (align == Align.CENTER) left + (width - w) / 2 else left
            drawText(line, x, top + i * b.fitted.lineHeight - ascent, b.paint)
        }
    }

    // endregion

    // region Значки (вместо SF Symbols iOS — простые фигуры)

    /** Рамка фото с горой и солнцем. */
    private fun Canvas.photoGlyph(cx: Float, cy: Float, w: Float, color: Int) {
        val h = w * 0.78f
        val r = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        val s = stroke(color, w * 0.04f).apply { strokeJoin = Paint.Join.ROUND }
        drawRoundRect(r, w * 0.1f, w * 0.1f, s)
        drawCircle(r.left + w * 0.3f, r.top + h * 0.3f, w * 0.08f, s)
        val p = Path().apply {
            moveTo(r.left + w * 0.08f, r.bottom - h * 0.12f)
            lineTo(r.left + w * 0.38f, r.top + h * 0.48f)
            lineTo(r.left + w * 0.58f, r.top + h * 0.7f)
            lineTo(r.left + w * 0.72f, r.top + h * 0.56f)
            lineTo(r.right - w * 0.08f, r.bottom - h * 0.12f)
        }
        drawPath(p, s)
    }

    /** Закрытая книга (заливка). */
    private fun Canvas.bookGlyph(cx: Float, top: Float, h: Float, color: Int, background: Int) {
        val w = h * 0.78f
        val r = RectF(cx - w / 2, top, cx + w / 2, top + h)
        drawRoundRect(r, h * 0.08f, h * 0.08f, fill(color))
        drawLine(r.left + w * 0.2f, r.top + h * 0.06f, r.left + w * 0.2f, r.bottom - h * 0.06f, stroke(background, h * 0.05f))
        drawLine(r.left + w * 0.35f, r.top + h * 0.25f, r.right - w * 0.15f, r.top + h * 0.25f, stroke(background, h * 0.04f))
    }

    /** Силуэт человека в круге. */
    private fun Canvas.personGlyph(cx: Float, cy: Float, d: Float, color: Int) {
        val r = d / 2
        val s = stroke(color, d * 0.025f)
        drawCircle(cx, cy, r - s.strokeWidth, s)
        drawCircle(cx, cy - r * 0.22f, r * 0.3f, s)
        withClip(Path().apply { addCircle(cx, cy, r - s.strokeWidth, Path.Direction.CW) }) {
            drawOval(RectF(cx - r * 0.62f, cy + r * 0.28f, cx + r * 0.62f, cy + r * 1.3f), s)
        }
    }

    /** Телефон: трубка смартфона. */
    private fun Canvas.phoneGlyph(cx: Float, cy: Float, size: Float, color: Int) {
        val w = size * 0.6f
        val r = RectF(cx - w / 2, cy - size / 2, cx + w / 2, cy + size / 2)
        drawRoundRect(r, size * 0.12f, size * 0.12f, fill(color))
        drawRoundRect(
            RectF(r.left + w * 0.14f, r.top + size * 0.12f, r.right - w * 0.14f, r.bottom - size * 0.2f),
            size * 0.03f, size * 0.03f, fill(Color.WHITE),
        )
        drawCircle(cx, r.bottom - size * 0.1f, size * 0.05f, fill(Color.WHITE))
    }

    /** Конверт (заливка с клапаном). */
    private fun Canvas.envelopeGlyph(cx: Float, cy: Float, size: Float, color: Int) {
        val h = size * 0.72f
        val r = RectF(cx - size / 2, cy - h / 2, cx + size / 2, cy + h / 2)
        drawRoundRect(r, size * 0.08f, size * 0.08f, fill(color))
        val flap = Path().apply {
            moveTo(r.left + size * 0.06f, r.top + h * 0.12f)
            lineTo(cx, cy + h * 0.1f)
            lineTo(r.right - size * 0.06f, r.top + h * 0.12f)
        }
        drawPath(flap, stroke(Color.WHITE, size * 0.07f).apply { strokeJoin = Paint.Join.ROUND })
    }

    // endregion

    // region Фото

    private fun photo(c: Canvas, cfg: ScreensaverConfig, t: CanvasTexts) {
        val img = cfg.photo
        if (img != null) {
            val r = PanelGeometry.place(img.width, img.height, cfg.photoFill, cfg.zoom, cfg.offsetX, cfg.offsetY)
            c.drawBitmap(img, null, RectF(r.left, r.top, r.right, r.bottom), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        } else {
            val color = gray(0.5)
            val text = block(t.choosePhoto, textPaint(face(Design.SANS, Weight.MEDIUM), color), W - 48f, size = 28f)
            val iconH = 90f
            val total = iconH + 12 + text.height
            val top = (H - total) / 2
            c.photoGlyph(W / 2f, top + iconH / 2, 110f, color)
            c.drawBlock(text, 0f, top + iconH + 12, W.toFloat(), Align.CENTER)
        }
        if (cfg.photoCaption.isNotBlank()) {
            val text = block(
                cfg.photoCaption, textPaint(face(Design.SERIF, Weight.SEMIBOLD), Color.WHITE), W - 48f,
                maxHeight = H / 2f, size = 30f, minScale = 0.6f,
            )
            val barH = text.height + 28
            c.drawRect(0f, H - barH, W.toFloat(), H.toFloat(), fill(Color.argb(217, 0, 0, 0)))
            c.drawBlock(text, 24f, H - barH + 14, W - 48f, Align.CENTER)
        }
    }

    // endregion

    // region Визитка «Если нашли — верните»

    private fun idCard(c: Canvas, cfg: ScreensaverConfig, t: CanvasTexts) {
        val black = Color.BLACK
        // Шапка: значок книги и заголовок белым по чёрному.
        val headline = block(
            cfg.cardHeadline.uppercase(), textPaint(face(Design.SANS, Weight.HEAVY), Color.WHITE), W - 56f,
            maxHeight = 3 * 40f, size = 26f, minScale = 0.6f, maxLines = 3,
        )
        val headerH = 34 + 44 + 10 + headline.height + 34
        c.drawRect(0f, 0f, W.toFloat(), headerH, fill(black))
        c.bookGlyph(W / 2f, 34f, 44f, Color.WHITE, black)
        c.drawBlock(headline, 28f, 34 + 44 + 10f, W - 56f, Align.CENTER)

        // Нижняя полоса и подпись снизу вверх.
        val barTop = H - 14f
        c.drawRect(0f, barTop, W.toFloat(), H.toFloat(), fill(gray(0.66)))
        var bottomLimit = barTop - 30
        if (cfg.cardNote.isNotBlank()) {
            val note = block(
                cfg.cardNote, textPaint(face(Design.SERIF, Weight.MEDIUM, italic = true), gray(0.33)), W - 60f,
                maxHeight = 120f, size = 24f, minScale = 0.7f, maxLines = 3,
            )
            val top = bottomLimit - note.height
            c.drawBlock(note, 30f, top, W - 60f, Align.CENTER)
            bottomLimit = top - 16
        }

        // Тело: фото или силуэт, имя, контакты.
        var y = headerH + 40
        val cardPhoto = cfg.cardPhoto
        if (cardPhoto != null) {
            val d = 170f
            val cx = W / 2f
            val cy = y + d / 2
            val r = PanelGeometry.place(cardPhoto.width, cardPhoto.height, fill = true, pw = d.toInt(), ph = d.toInt())
            val top = y
            c.withClip(Path().apply { addCircle(cx, cy, d / 2, Path.Direction.CW) }) {
                drawBitmap(
                    cardPhoto, null, RectF(cx - d / 2 + r.left, top + r.top, cx - d / 2 + r.right, top + r.bottom),
                    Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
                )
            }
            c.drawCircle(cx, cy, d / 2 - 2.5f, stroke(black, 5f))
            y += d
        } else {
            c.personGlyph(W / 2f, y + 60, 120f, gray(0.35))
            y += 120f
        }
        y += 22
        val name = block(
            cfg.cardName.ifBlank { t.namePlaceholder }, textPaint(face(Design.SERIF, Weight.BOLD), black), W - 64f,
            maxHeight = 2 * 56f, size = 40f, minScale = 0.5f, maxLines = 2,
        )
        c.drawBlock(name, 32f, y, W - 64f, Align.CENTER)
        y += name.height + 22

        val rows = buildList {
            if (cfg.cardPhone.isNotBlank()) add(true to cfg.cardPhone)
            if (cfg.cardEmail.isNotBlank()) add(false to cfg.cardEmail)
            if (isEmpty()) add(true to t.phonePlaceholder)
        }
        val textMax = W - 64f - 20 - 36 - 16
        val rowBlocks = rows.map { (_, text) ->
            block(text, textPaint(face(Design.SANS, Weight.SEMIBOLD), black), textMax, size = 30f, minScale = 0.5f, maxLines = 1)
        }
        val groupW = rowBlocks.maxOf { 36 + 16 + it.width }
        val left = (W - groupW) / 2
        rows.forEachIndexed { i, (isPhone, _) ->
            val b = rowBlocks[i]
            val rowH = max(36f, b.height)
            if (y + rowH > bottomLimit) return@forEachIndexed
            val cy = y + rowH / 2
            if (isPhone) c.phoneGlyph(left + 18, cy, 30f, black) else c.envelopeGlyph(left + 18, cy, 30f, black)
            c.drawBlock(b, left + 52, cy - b.height / 2, b.width, Align.LEFT)
            y += rowH + 16
        }

        // Рамка поверх всего (12 пт по краю, как stroke в SwiftUI: видно 6 пт внутри).
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), stroke(black, 12f))
    }

    // endregion

    // region Цитата

    private fun quote(c: Canvas, cfg: ScreensaverConfig) {
        val contentW = W - 88f
        val contentH = H - 140f
        val markH = 110f
        val author = cfg.quoteAuthor.takeIf { it.isNotBlank() }?.let {
            block(
                it, textPaint(face(Design.SANS, Weight.SEMIBOLD), gray(0.33)), contentW - 44 - 14,
                maxHeight = 3 * 34f, size = 24f, minScale = 0.6f, maxLines = 3,
            )
        }
        val authorH = author?.let { max(3f, it.height) + 28 } ?: 0f
        val text = block(
            cfg.quoteText, textPaint(face(Design.SERIF, Weight.REGULAR), Color.BLACK), contentW,
            maxHeight = contentH - markH - 28 - authorH, size = 42f, minScale = 0.35f, spacing = 6f,
        )
        val total = markH + 28 + text.height + authorH
        var y = 70 + (contentH - total) / 2
        val mark = textPaint(face(Design.SERIF, Weight.HEAVY), gray(0.66), 180f)
        // Кавычка в рамке 110 пт, прижатой кверху (как frame(height: 110, alignment: .top)).
        c.drawText("“", 40f, y + mark.textSize * 0.82f, mark)
        y += markH + 28
        c.drawBlock(text, 44f, y, contentW, Align.LEFT)
        y += text.height
        if (author != null) {
            y += 28
            val h = max(3f, author.height)
            c.drawRect(44f, y + h / 2 - 1.5f, 88f, y + h / 2 + 1.5f, fill(Color.BLACK))
            c.drawBlock(author, 44f + 44 + 14, y + (h - author.height) / 2, author.width, Align.LEFT)
        }
        c.drawRect(0f, H - 10f, W.toFloat(), H.toFloat(), fill(Color.BLACK))
    }

    // endregion

    // region Текстовый постер

    private fun poster(c: Canvas, cfg: ScreensaverConfig) {
        val fg = if (cfg.posterInverted) Color.WHITE else Color.BLACK
        c.drawColor(if (cfg.posterInverted) Color.BLACK else Color.WHITE)
        val contentW = W - 80f
        val contentH = H - 80f
        val design = cfg.posterFont.design()
        val align = if (cfg.posterCentered) Align.CENTER else Align.LEFT
        val subtitle = cfg.posterSubtitle.takeIf { it.isNotBlank() }?.let {
            block(
                it, textPaint(face(design, Weight.REGULAR), gray(if (cfg.posterInverted) 0.66 else 0.33)), contentW,
                maxHeight = contentH / 3, size = 26f, minScale = 0.6f,
            )
        }
        val subH = subtitle?.let { it.height + 24 } ?: 0f
        val text = block(
            cfg.posterText, textPaint(face(design, Weight.BOLD), fg), contentW,
            maxHeight = contentH - subH, size = cfg.posterSize.toFloat(), minScale = 0.3f,
        )
        var y = 40 + (contentH - text.height - subH) / 2
        c.drawBlock(text, 40f, y, contentW, align)
        y += text.height + 24
        subtitle?.let { c.drawBlock(it, 40f, y, contentW, align) }
    }

    // endregion

    // region Календарь и серия

    private fun calendarPoster(c: Canvas, cfg: ScreensaverConfig, cal: CalendarPosterData, t: CanvasTexts) {
        val inv = cfg.calendarInverted
        val fg = if (inv) Color.WHITE else Color.BLACK
        val secondary = gray(0.5)
        c.drawColor(if (inv) Color.BLACK else Color.WHITE)
        val pad = CalendarLayout.PADDING.toFloat()
        val contentW = W - 2 * pad
        var y = pad

        val title = block(
            cfg.calendarTitle, textPaint(face(Design.SERIF, Weight.HEAVY), fg), contentW,
            maxHeight = 2 * 60f, size = 44f, minScale = 0.5f, maxLines = 2,
        )
        c.drawBlock(title, pad, y, contentW, Align.LEFT)
        y += title.height + 26
        val date = block(t.date, textPaint(face(Design.SANS, Weight.REGULAR), secondary), contentW, size = 22f, maxLines = 1)
        c.drawBlock(date, pad, y, contentW, Align.LEFT)
        y += date.height + 26

        // Тепловая карта: столбцы — недели, строки — дни.
        val cell = CalendarLayout.cellSize(cal.columns.size)
        val border = stroke(secondary, 1f)
        cal.columns.forEachIndexed { ci, col ->
            col.forEachIndexed { ri, cellData ->
                if (cellData.isFuture) return@forEachIndexed
                val (dx, dy) = CalendarLayout.cellOrigin(ci, ri, cell)
                val r = RectF(pad + dx, y + dy, pad + dx + cell, y + dy + cell)
                val g = CalendarLayout.gray(cellData.level, inv)
                c.drawRect(r, fill(Color.rgb(g, g, g)))
                if (cellData.level == 0) c.drawRect(r.left + 0.5f, r.top + 0.5f, r.right - 0.5f, r.bottom - 0.5f, border)
            }
        }
        y += CalendarLayout.gridHeight(cell) + 26

        // Легенда: 5 уровней и «мин/день».
        var x = pad
        for (l in 0..4) {
            val g = CalendarLayout.gray(l, inv)
            c.drawRect(x, y, x + 16, y + 16, fill(Color.rgb(g, g, g)))
            if (l == 0) c.drawRect(x + 0.5f, y + 0.5f, x + 15.5f, y + 15.5f, border)
            x += 16 + 6
        }
        val legend = block(t.minPerDay, textPaint(face(Design.SANS, Weight.REGULAR), secondary), W - pad - x, size = 16f, maxLines = 1)
        c.drawBlock(legend, x, y + 8 - legend.height / 2, legend.width, Align.LEFT)
        y += 16 + 26

        // Итоги 2×2.
        val stats = listOf(
            cal.streak.toString() to t.streak, cal.longest.toString() to t.record,
            cal.hours to t.hours, cal.books.toString() to t.books,
        )
        val colW = (contentW - 30) / 2
        val values = stats.map { (v, _) ->
            block(v, textPaint(face(Design.SANS, Weight.HEAVY), fg), colW, size = 52f, minScale = 0.5f, maxLines = 1)
        }
        val labels = stats.map { (_, l) ->
            block(l, textPaint(face(Design.SANS, Weight.REGULAR), secondary), colW, size = 20f, minScale = 0.7f, maxLines = 1)
        }
        val firstColW = max(max(values[0].width, labels[0].width), max(values[2].width, labels[2].width))
        val secondX = pad + firstColW + 30
        for (row in 0..1) {
            val rowH = (0..1).maxOf { col -> values[row * 2 + col].height + labels[row * 2 + col].height }
            for (col in 0..1) {
                val i = row * 2 + col
                val left = if (col == 0) pad else secondX
                c.drawBlock(values[i], left, y, colW, Align.LEFT)
                c.drawBlock(labels[i], left, y + values[i].height, colW, Align.LEFT)
            }
            y += rowH + 20
        }

        // «Сегодня: 12 / 30 мин» внизу.
        val today = block(t.today, textPaint(face(Design.SANS, Weight.SEMIBOLD), fg), contentW, size = 22f, minScale = 0.6f, maxLines = 1)
        c.drawBlock(today, pad, max(y, H - pad - today.height), contentW, Align.LEFT)
    }

    // endregion

    /** Метод дизеринга из настроек приложения (сырое имя), по умолчанию — Флойд — Стейнберг. */
    fun ditherFrom(raw: String?): DitherMethod = DitherMethod.from(raw) ?: DitherMethod.FLOYD_STEINBERG

    /** Сбросить тон. */
    val neutralTone = ToneAdjustments()
}
