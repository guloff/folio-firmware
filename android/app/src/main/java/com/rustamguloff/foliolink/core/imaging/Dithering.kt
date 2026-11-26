package com.rustamguloff.foliolink.core.imaging

/**
 * Способ квантования в 4 уровня серого читалки (2-битные чернила).
 * Названия для экрана — в строковых ресурсах UI (`raw` — ключ для сохранения).
 */
enum class DitherMethod(val raw: String) {
    FLOYD_STEINBERG("floydSteinberg"),
    ATKINSON("atkinson"),
    BAYER("bayer"),
    NONE("none");

    val id: String get() = raw

    companion object {
        fun from(raw: String?): DitherMethod? = entries.firstOrNull { it.raw == raw }
    }
}

object Dither {
    /** 4 выходных уровня: чёрный, тёмно-серый, светло-серый, белый. */
    val levels: List<Int> = listOf(0, 85, 170, 255)
    private val levelArray = intArrayOf(0, 85, 170, 255)

    internal fun nearestLevel(v: Int): Int {
        val c = v.coerceIn(0, 255)
        return levelArray[minOf(3, (c * 3 + 127) / 255)]
    }

    fun apply(method: DitherMethod, image: GrayImage): GrayImage = when (method) {
        DitherMethod.NONE -> threshold(image)
        DitherMethod.FLOYD_STEINBERG -> errorDiffusion(image, FLOYD_KERNEL, 16)
        DitherMethod.ATKINSON -> errorDiffusion(image, ATKINSON_KERNEL, 8)
        DitherMethod.BAYER -> ordered(image)
    }

    /** Полный конвейер для читалки: тон → квантование. */
    fun prepareForDevice(image: GrayImage, tone: ToneAdjustments, method: DitherMethod): GrayImage =
        apply(method, tone.apply(image))

    private fun threshold(image: GrayImage): GrayImage {
        val src = image.pixels
        return GrayImage(image.width, image.height, ByteArray(src.size) { nearestLevel(src[it].toInt() and 0xFF).toByte() })
    }

    // Тройки (dx, dy, вес).
    private val FLOYD_KERNEL = arrayOf(intArrayOf(1, 0, 7), intArrayOf(-1, 1, 3), intArrayOf(0, 1, 5), intArrayOf(1, 1, 1))

    // Atkinson разносит только 6/8 ошибки — светлые места остаются чистыми.
    private val ATKINSON_KERNEL = arrayOf(
        intArrayOf(1, 0, 1), intArrayOf(2, 0, 1), intArrayOf(-1, 1, 1),
        intArrayOf(0, 1, 1), intArrayOf(1, 1, 1), intArrayOf(0, 2, 1),
    )

    private fun errorDiffusion(image: GrayImage, kernel: Array<IntArray>, divisor: Int): GrayImage {
        val w = image.width
        val h = image.height
        // Буфер ошибки в фиксированной точке (×16): без плавающей арифметики на 384 тыс. пикселей.
        val buf = IntArray(w * h) { (image.pixels[it].toInt() and 0xFF) shl 4 }
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val old = (buf[i] + 8) shr 4
                val new = nearestLevel(old)
                out[i] = new.toByte()
                val err = (old.coerceIn(-255, 510) - new) shl 4
                for (k in kernel) {
                    val nx = x + k[0]
                    val ny = y + k[1]
                    if (nx < 0 || nx >= w || ny >= h) continue
                    buf[ny * w + nx] += err * k[2] / divisor
                }
            }
        }
        return GrayImage(w, h, out)
    }

    private val BAYER8 = intArrayOf(
        0, 32, 8, 40, 2, 34, 10, 42,
        48, 16, 56, 24, 50, 18, 58, 26,
        12, 44, 4, 36, 14, 46, 6, 38,
        60, 28, 52, 20, 62, 30, 54, 22,
        3, 35, 11, 43, 1, 33, 9, 41,
        51, 19, 59, 27, 49, 17, 57, 25,
        15, 47, 7, 39, 13, 45, 5, 37,
        63, 31, 55, 23, 61, 29, 53, 21,
    )

    private fun ordered(image: GrayImage): GrayImage {
        val w = image.width
        val h = image.height
        val out = ByteArray(w * h)
        val step = 85 // расстояние между соседними уровнями
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val t = (BAYER8[(y and 7) * 8 + (x and 7)] * step) / 64 - step / 2
                out[i] = nearestLevel((image.pixels[i].toInt() and 0xFF) + t).toByte()
            }
        }
        return GrayImage(w, h, out)
    }
}
