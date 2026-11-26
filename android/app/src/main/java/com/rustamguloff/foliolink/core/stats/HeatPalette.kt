package com.rustamguloff.foliolink.core.stats

import kotlin.math.pow

/**
 * Цвета тепловой карты чтения: отдельная шкала на светлую и тёмную тему (UX-016), построенная
 * по относительной яркости WCAG, а не прозрачностью, — ступени различимы на обеих карточках.
 *
 * Гарантии (проверяет HeatPaletteTest):
 * - пустой день против любого дня с чтением: контраст ≥ 3:1 (WCAG 1.4.11, нетекстовый);
 * - соседние ступени интенсивности: контраст ≥ 1.6:1.
 *
 * Почему не 3:1 между всеми соседями: контрасты перемножаются вдоль монотонной шкалы, пяти
 * уровням понадобилось бы 3⁴ = 81:1 от края до края, а чёрный на белом — только 21:1.
 *
 * Без зависимостей от Compose: цвет — ARGB `Int` (в UI — `Color(argb)`).
 */
object HeatPalette {

    data class Rgb(val r: Int, val g: Int, val b: Int) {
        constructor(hex: Int) : this((hex shr 16) and 0xFF, (hex shr 8) and 0xFF, hex and 0xFF)

        /** Относительная яркость WCAG 2.x. */
        val luminance: Double
            get() {
                fun lin(c: Int): Double {
                    val v = c / 255.0
                    return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
                }
                return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
            }

        /** Непрозрачный ARGB (0xFFrrggbb). */
        val argb: Int get() = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

        /** То же беззнаковым Long (для `Color(0xFF…)`-подобных API). */
        val argbLong: Long get() = argb.toLong() and 0xFFFF_FFFFL
    }

    /** Уровни 0 (нет чтения) … 4 (60+ мин): от светлого серо-голубого к глубокому синему. */
    val light: List<Rgb> = listOf(Rgb(0xEBEDF0), Rgb(0x5187DA), Rgb(0x2760B6), Rgb(0x193E77), Rgb(0x0A1633))

    /** Уровни 0 … 4 на тёмной карточке: от тёмно-серого к почти белому. */
    val dark: List<Rgb> = listOf(Rgb(0x26282E), Rgb(0x166BEC), Rgb(0x5B97F2), Rgb(0xA2C4F8), Rgb(0xF2F6FF))

    /** Контраст WCAG, 1…21. */
    fun contrast(a: Rgb, b: Rgb): Double {
        val la = a.luminance
        val lb = b.luminance
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    fun rgb(level: Int, dark: Boolean): Rgb {
        val scale = if (dark) this.dark else light
        return scale[level.coerceIn(0, scale.size - 1)]
    }

    /** ARGB уровня для темы. */
    fun argb(level: Int, dark: Boolean): Int = rgb(level, dark).argb
}
