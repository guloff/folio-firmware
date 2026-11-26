package com.rustamguloff.foliolink.ui.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.createBitmap

/**
 * Маски для виджетов: белые фигуры на прозрачном фоне. Цвет даёт Glance (`ColorFilter.tint` с
 * дневным/ночным цветом), поэтому картинка не зависит от темы и не устаревает при её смене.
 */
object WidgetBitmaps {

    /** Дуга кольца: `fraction` 0…1 по часовой от 12 часов (1 — полный круг). */
    fun ring(sizePx: Int, strokePx: Float, fraction: Float): Bitmap {
        val bmp = createBitmap(sizePx, sizePx)
        val f = fraction.coerceIn(0f, 1f)
        if (f <= 0f) return bmp
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            strokeCap = if (f >= 1f) Paint.Cap.BUTT else Paint.Cap.ROUND
            color = 0xFFFFFFFF.toInt()
        }
        val inset = strokePx / 2
        Canvas(bmp).drawArc(RectF(inset, inset, sizePx - inset, sizePx - inset), -90f, 360f * f, false, paint)
        return bmp
    }

    /**
     * Клетки мини-карты одного уровня `level` (столбцы — недели, по 7 клеток сверху вниз).
     * Слои всех уровней складываются в одну карту.
     */
    fun heatLevel(columns: List<List<Int>>, level: Int, cellPx: Float, gapPx: Float): Bitmap {
        val w = (columns.size * cellPx + (columns.size - 1).coerceAtLeast(0) * gapPx).toInt().coerceAtLeast(1)
        val h = (7 * cellPx + 6 * gapPx).toInt().coerceAtLeast(1)
        val bmp = createBitmap(w, h)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        val r = cellPx * 0.2f
        columns.forEachIndexed { c, col ->
            col.forEachIndexed { row, l ->
                if (l != level) return@forEachIndexed
                val x = c * (cellPx + gapPx)
                val y = row * (cellPx + gapPx)
                canvas.drawRoundRect(RectF(x, y, x + cellPx, y + cellPx), r, r, paint)
            }
        }
        return bmp
    }
}
