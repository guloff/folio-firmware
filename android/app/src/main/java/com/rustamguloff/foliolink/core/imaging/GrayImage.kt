package com.rustamguloff.foliolink.core.imaging

import kotlinx.serialization.Serializable
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 8-битное серое изображение, построчно сверху вниз. 0 — чёрный, 255 — белый.
 * Пиксели хранятся в `ByteArray` (без знака: читать через `get`, он отдаёт 0…255).
 */
class GrayImage(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(width >= 0 && height >= 0 && pixels.size == width * height) { "pixels.size != width * height" }
    }

    /** Изображение, залитое одним тоном. */
    constructor(width: Int, height: Int, fill: Int = 255) :
        this(width, height, ByteArray(width * height) { fill.toByte() })

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF

    operator fun set(x: Int, y: Int, value: Int) {
        pixels[y * width + x] = value.toByte()
    }

    /** Значение пикселя по линейному индексу, 0…255. */
    fun at(index: Int): Int = pixels[index].toInt() and 0xFF

    override fun equals(other: Any?): Boolean =
        other is GrayImage && width == other.width && height == other.height && pixels.contentEquals(other.pixels)

    override fun hashCode(): Int = 31 * (31 * width + height) + pixels.contentHashCode()

    override fun toString(): String = "GrayImage(${width}x$height)"

    companion object {
        /** Из списка значений 0…255 (удобно в тестах). */
        fun of(width: Int, height: Int, values: List<Int>): GrayImage =
            GrayImage(width, height, ByteArray(values.size) { values[it].toByte() })
    }
}

/** Тон перед квантованием. */
@Serializable
data class ToneAdjustments(
    /** -1…1, прибавляется к нормированной яркости. */
    val brightness: Double = 0.0,
    /** 0.2…3, растягивает расстояние от среднего серого. */
    val contrast: Double = 1.0,
    /** 0.3…3; > 1 осветляет средние тона (v^(1/gamma)). */
    val gamma: Double = 1.0,
    val invert: Boolean = false,
) {
    val isIdentity: Boolean get() = brightness == 0.0 && contrast == 1.0 && gamma == 1.0 && !invert

    /** Таблица 256 значений 0…255. */
    fun lookupTable(): IntArray = IntArray(256) { i ->
        var v = i / 255.0
        v = (v - 0.5) * contrast + 0.5 + brightness
        v = v.coerceIn(0.0, 1.0)
        v = v.pow(1 / maxOf(gamma, 0.05))
        if (invert) v = 1 - v
        (v * 255).roundToInt()
    }

    fun apply(image: GrayImage): GrayImage {
        if (isIdentity) return image
        val lut = lookupTable()
        val src = image.pixels
        return GrayImage(image.width, image.height, ByteArray(src.size) { lut[src[it].toInt() and 0xFF].toByte() })
    }
}
