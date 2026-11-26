package com.rustamguloff.foliolink.core.imaging

import java.io.ByteArrayOutputStream

/** Ошибки разбора BMP. */
sealed class BmpDecodeException(message: String) : Exception(message) {
    data object TooShort : BmpDecodeException("BMP: файл короче заголовка или данных")
    data object BadSignature : BmpDecodeException("BMP: нет сигнатуры BM")
    data class Unsupported(val what: String) : BmpDecodeException("BMP: не поддерживается ($what)")
}

/**
 * Минимальный кодек BMP (Windows DIB).
 *
 * Кодер: несжатый 24 бит BI_RGB, строки снизу вверх с выравниванием до 4 байт — формат,
 * который читалка ждёт для картинок сна.
 * Декодер: 1/2/4/8 бит с палитрой, 16 бит (5-5-5), 24 и 32 бит, BI_RGB или BI_BITFIELDS,
 * снизу вверх или сверху вниз, плюс заголовок OS/2. Хватает для скриншотов прошивки.
 */
object BmpCodec {

    /** RGBA8 после декодирования. */
    class RgbaImage(val width: Int, val height: Int, val rgba: ByteArray) {
        /** Яркость по Rec. 601: (299·R + 587·G + 114·B) / 1000. */
        fun toGray(): GrayImage {
            val px = ByteArray(width * height)
            for (i in 0 until width * height) {
                val r = rgba[i * 4].toInt() and 0xFF
                val g = rgba[i * 4 + 1].toInt() and 0xFF
                val b = rgba[i * 4 + 2].toInt() and 0xFF
                px[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
            }
            return GrayImage(width, height, px)
        }
    }

    // region Кодер

    /** Серое изображение → 24-битный BGR снизу вверх. */
    fun encode24(image: GrayImage): ByteArray {
        val w = image.width
        val h = image.height
        val rowSize = (w * 3 + 3) and 3.inv()
        val pixelBytes = rowSize * h
        val fileSize = 54 + pixelBytes
        val out = ByteArrayOutputStream(fileSize)
        // BITMAPFILEHEADER
        out.write(0x42); out.write(0x4D)
        out.le32(fileSize); out.le32(0); out.le32(54)
        // BITMAPINFOHEADER
        out.le32(40)
        out.le32(w)
        out.le32(h) // положительная высота = снизу вверх
        out.le16(1) // плоскости
        out.le16(24) // бит на пиксель
        out.le32(0) // BI_RGB
        out.le32(pixelBytes)
        out.le32(2835) // 72 DPI
        out.le32(2835)
        out.le32(0)
        out.le32(0)
        val row = ByteArray(rowSize)
        for (y in h - 1 downTo 0) {
            for (x in 0 until w) {
                val v = image.pixels[y * w + x]
                row[x * 3] = v; row[x * 3 + 1] = v; row[x * 3 + 2] = v
            }
            out.write(row)
        }
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.le16(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.le32(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v ushr 24) and 0xFF)
    }

    // endregion

    // region Заголовок

    /** Что заголовок BMP говорит о пикселях — без их декодирования. */
    data class Header(
        val width: Int,
        /** Всегда положительная (у файлов сверху вниз она записана со знаком минус). */
        val height: Int,
        val bitsPerPixel: Int,
        val compression: Int,
        val dataOffset: Long,
    ) {
        /** Сколько байт пикселей обещает заголовок (строки выровнены до 4 байт). */
        val pixelBytes: Long get() = ((width.toLong() * bitsPerPixel + 31) / 32) * 4 * height
    }

    /** Файловый и информационный заголовок (BITMAPINFOHEADER и новее; OS/2 — null). */
    fun header(data: ByteArray): Header? {
        if (data.size < 54 || data[0] != 0x42.toByte() || data[1] != 0x4D.toByte()) return null
        if (u32(data, 14) < 40) return null
        val width = s32(data, 18)
        val height = s32(data, 22)
        if (width <= 0 || height == 0 || height == Int.MIN_VALUE) return null
        return Header(width, kotlin.math.abs(height), u16(data, 28), u32(data, 30).toInt(), u32(data, 10))
    }

    // endregion

    // region Декодер

    private val supportedBpp = setOf(1, 2, 4, 8, 16, 24, 32)

    /** Декодирует BMP; на мусоре — [BmpDecodeException]. */
    fun decode(data: ByteArray): RgbaImage {
        val b = data
        if (b.size < 26) throw BmpDecodeException.TooShort
        if (b[0] != 0x42.toByte() || b[1] != 0x4D.toByte()) throw BmpDecodeException.BadSignature

        val dataOffset = u32(b, 10)
        val headerSize = u32(b, 14)
        val width: Int
        val rawHeight: Int
        val bpp: Int
        val compression: Long
        var colorsUsed = 0L
        if (headerSize == 12L) { // OS/2 BITMAPCOREHEADER
            width = u16(b, 18)
            rawHeight = u16(b, 20).toShort().toInt()
            bpp = u16(b, 24)
            compression = 0
        } else {
            if (b.size < 54) throw BmpDecodeException.TooShort
            width = s32(b, 18)
            rawHeight = s32(b, 22)
            bpp = u16(b, 28)
            compression = u32(b, 30)
            colorsUsed = u32(b, 46)
        }
        val topDown = rawHeight < 0
        val height = if (rawHeight == Int.MIN_VALUE) 0 else kotlin.math.abs(rawHeight)
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192) throw BmpDecodeException.Unsupported("size")
        if (compression != 0L && compression != 3L) throw BmpDecodeException.Unsupported("compression $compression")
        if (bpp !in supportedBpp) throw BmpDecodeException.Unsupported("bpp $bpp")

        // Палитра
        val palette = ArrayList<Int>() // 0xRRGGBB
        if (bpp <= 8) {
            val entrySize = if (headerSize == 12L) 3 else 4
            val count = if (colorsUsed > 0) minOf(colorsUsed, 256L).toInt() else (1 shl bpp)
            val start = 14L + headerSize
            for (i in 0 until count) {
                val o = start + i.toLong() * entrySize
                if (o + 2 >= b.size) break
                val oi = o.toInt()
                palette += ((b[oi + 2].toInt() and 0xFF) shl 16) or ((b[oi + 1].toInt() and 0xFF) shl 8) or
                    (b[oi].toInt() and 0xFF)
            }
            if (palette.isEmpty()) throw BmpDecodeException.Unsupported("palette")
        }

        // Маски для 16/32 бит
        var masks = if (bpp == 16) longArrayOf(0x7C00, 0x03E0, 0x001F) else longArrayOf(0x00FF0000, 0x0000FF00, 0x000000FF)
        if (compression == 3L) {
            if (headerSize >= 52 && b.size >= 66) {
                masks = longArrayOf(u32(b, 54), u32(b, 58), u32(b, 62))
            } else if (b.size >= 14 + headerSize + 12) {
                val o = (14 + headerSize).toInt()
                masks = longArrayOf(u32(b, o), u32(b, o + 4), u32(b, o + 8))
            }
        }

        val rowSize = ((width.toLong() * bpp + 31) / 32) * 4
        if (dataOffset + rowSize * height > b.size) throw BmpDecodeException.TooShort
        val out = ByteArray(width * height * 4)
        for (row in 0 until height) {
            val y = if (topDown) row else height - 1 - row
            val base = (dataOffset + row * rowSize).toInt()
            for (x in 0 until width) {
                var rgb: Int
                when (bpp) {
                    1, 2, 4, 8 -> {
                        val bitIndex = x * bpp
                        val byte = b[base + bitIndex / 8].toInt() and 0xFF
                        val shift = 8 - bpp - (bitIndex % 8)
                        val idx = (byte shr shift) and ((1 shl bpp) - 1)
                        rgb = if (idx < palette.size) palette[idx] else 0
                    }
                    16 -> {
                        val v = u16(b, base + x * 2).toLong()
                        rgb = (extract(v, masks[0]) shl 16) or (extract(v, masks[1]) shl 8) or extract(v, masks[2])
                    }
                    24 -> {
                        val o = base + x * 3
                        rgb = ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                            (b[o].toInt() and 0xFF)
                    }
                    else -> { // 32
                        val o = base + x * 4
                        rgb = if (compression == 3L) {
                            val v = u32(b, o)
                            (extract(v, masks[0]) shl 16) or (extract(v, masks[1]) shl 8) or extract(v, masks[2])
                        } else {
                            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                                (b[o].toInt() and 0xFF)
                        }
                    }
                }
                val o = (y * width + x) * 4
                out[o] = (rgb shr 16).toByte()
                out[o + 1] = (rgb shr 8).toByte()
                out[o + 2] = rgb.toByte()
                out[o + 3] = 0xFF.toByte()
            }
        }
        return RgbaImage(width, height, out)
    }

    /** Канал по маске, растянутый до 0…255. */
    private fun extract(v: Long, mask: Long): Int {
        if (mask == 0L) return 0
        val shift = java.lang.Long.numberOfTrailingZeros(mask)
        val bits = java.lang.Long.bitCount(mask ushr shift)
        val raw = (v and mask) ushr shift
        val maxV = (1L shl bits) - 1
        return ((raw * 255 + maxV / 2) / maxV).toInt() and 0xFF
    }

    // endregion

    private fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24))

    private fun s32(b: ByteArray, o: Int): Int = u32(b, o).toInt()
}
