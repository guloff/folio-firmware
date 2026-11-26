package com.rustamguloff.foliolink.core.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect

/**
 * Мост между чистым Kotlin-конвейером (GrayImage, BmpCodec) и `android.graphics.Bitmap`.
 * Единственный платформенный файл модуля; в JVM-тестах не используется.
 */
object AndroidImaging {

    /**
     * Любой Bitmap → серое изображение заданного размера (растягивается на весь кадр).
     * Прозрачность ложится на белый фон, как на iOS (контекст заливается белым перед рисованием).
     */
    fun grayFromBitmap(bitmap: Bitmap, width: Int = bitmap.width, height: Int = bitmap.height): GrayImage {
        require(width > 0 && height > 0) { "size must be positive" }
        val canvasBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        canvas.drawBitmap(bitmap, Rect(0, 0, bitmap.width, bitmap.height), Rect(0, 0, width, height), paint)
        val argb = IntArray(width * height)
        canvasBitmap.getPixels(argb, 0, width, 0, 0, width, height)
        canvasBitmap.recycle()
        val px = ByteArray(width * height) { i ->
            val c = argb[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            ((r * 299 + g * 587 + b * 114) / 1000).toByte()
        }
        return GrayImage(width, height, px)
    }

    /** Серое изображение → непрозрачный ARGB_8888 Bitmap (превью результата дизеринга). */
    fun toBitmap(image: GrayImage): Bitmap {
        val argb = IntArray(image.width * image.height) { i ->
            val v = image.pixels[i].toInt() and 0xFF
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(argb, image.width, image.height, Bitmap.Config.ARGB_8888)
    }

    /** Декодированный BMP → Bitmap. */
    fun toBitmap(image: BmpCodec.RgbaImage): Bitmap {
        val n = image.width * image.height
        val src = image.rgba
        val argb = IntArray(n) { i ->
            val r = src[i * 4].toInt() and 0xFF
            val g = src[i * 4 + 1].toInt() and 0xFF
            val b = src[i * 4 + 2].toInt() and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(argb, image.width, image.height, Bitmap.Config.ARGB_8888)
    }

    /** Байты BMP (скриншот читалки) → Bitmap; на мусоре — [BmpDecodeException]. */
    fun decodeBmp(data: ByteArray): Bitmap = toBitmap(BmpCodec.decode(data))

    /** Bitmap → готовый для читалки BMP 24 бит: размер, тон, 4 уровня серого. */
    fun prepareSleepImage(
        bitmap: Bitmap,
        width: Int,
        height: Int,
        tone: ToneAdjustments,
        method: DitherMethod,
    ): ByteArray = BmpCodec.encode24(Dither.prepareForDevice(grayFromBitmap(bitmap, width, height), tone, method))
}
