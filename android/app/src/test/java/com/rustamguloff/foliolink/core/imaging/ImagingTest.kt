package com.rustamguloff.foliolink.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.cos
import kotlin.math.sin

class BmpCodecTest {
    private fun gradient(w: Int, h: Int): GrayImage {
        val img = GrayImage(w, h)
        for (y in 0 until h) for (x in 0 until w) img[x, y] = (x * 255 / maxOf(w - 1, 1) + y) % 256
        return img
    }

    private fun u32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    @Test
    fun encodeHeaderIs24BitBottomUpBiRgb() {
        val b = BmpCodec.encode24(gradient(480, 800))
        assertEquals(0x42.toByte(), b[0]); assertEquals(0x4D.toByte(), b[1])
        assertEquals(b.size.toLong(), u32(b, 2))
        assertEquals(54L, u32(b, 10))
        assertEquals(480L, u32(b, 18))
        assertEquals("положительная высота = снизу вверх", 800, u32(b, 22).toInt())
        assertEquals(24, (b[28].toInt() and 0xFF) or ((b[29].toInt() and 0xFF) shl 8))
        assertEquals("BI_RGB", 0L, u32(b, 30))
        assertEquals(54 + 480 * 3 * 800, b.size)
        assertEquals(1_152_054, b.size)
        val header = BmpCodec.header(b)!!
        assertEquals(BmpCodec.Header(480, 800, 24, 0, 54), header)
        assertEquals(480L * 3 * 800, header.pixelBytes)
    }

    @Test
    fun roundTripWithRowPadding() {
        // 7 px → 21 байт в строке, выравнивание до 24.
        val img = gradient(7, 5)
        val decoded = BmpCodec.decode(BmpCodec.encode24(img))
        assertEquals(7, decoded.width)
        assertEquals(5, decoded.height)
        assertEquals(img, decoded.toGray())
    }

    @Test
    fun roundTripDeviceSize() {
        val img = Dither.apply(DitherMethod.FLOYD_STEINBERG, gradient(480, 800))
        assertEquals(img, BmpCodec.decode(BmpCodec.encode24(img)).toGray())
    }

    @Test
    fun decodes8BitPalettizedTopDown() {
        // 2×2, 8 бит, сверху вниз (отрицательная высота), палитра из 4 серых.
        val d = ByteArrayOutputStream()
        fun le16(v: Int) { d.write(v and 0xFF); d.write((v shr 8) and 0xFF) }
        fun le32(v: Int) { le16(v and 0xFFFF); le16((v ushr 16) and 0xFFFF) }
        d.write(0x42); d.write(0x4D)
        val palette = 4 * 4
        val rowSize = 4
        val pixelBytes = rowSize * 2
        le32(54 + palette + pixelBytes); le32(0); le32(54 + palette)
        le32(40); le32(2); le32(-2)
        le16(1); le16(8); le32(0); le32(pixelBytes)
        le32(0); le32(0); le32(4); le32(0)
        for (g in listOf(0, 85, 170, 255)) d.write(byteArrayOf(g.toByte(), g.toByte(), g.toByte(), 0))
        d.write(byteArrayOf(0, 1, 0, 0)) // строка 0 (верх): чёрный, тёмно-серый
        d.write(byteArrayOf(2, 3, 0, 0)) // строка 1: светло-серый, белый
        val img = BmpCodec.decode(d.toByteArray()).toGray()
        assertEquals(listOf(0, 85, 170, 255), (0 until 4).map { img.at(it) })
    }

    @Test
    fun rejectsGarbage() {
        for (garbage in listOf(byteArrayOf(1, 2, 3), ByteArray(100))) {
            try {
                BmpCodec.decode(garbage)
                fail("должно бросить")
            } catch (_: BmpDecodeException) {
            }
        }
        // Заголовок обещает больше пикселей, чем есть.
        val truncated = BmpCodec.encode24(gradient(10, 10)).copyOf(80)
        try {
            BmpCodec.decode(truncated)
            fail("должно бросить")
        } catch (e: BmpDecodeException) {
            assertEquals(BmpDecodeException.TooShort, e)
        }
    }
}

class DitherTest {
    private fun photoLike(): GrayImage {
        val img = GrayImage(480, 800)
        for (y in 0 until 800) for (x in 0 until 480) {
            val v = 128 + 100 * sin(x / 37.0) * cos(y / 53.0)
            img[x, y] = v.coerceIn(0.0, 255.0).toInt()
        }
        return img
    }

    private fun GrayImage.values(): Set<Int> = (0 until width * height).mapTo(HashSet()) { at(it) }

    @Test
    fun allMethodsProduceOnlyFourLevels() {
        val img = photoLike()
        for (method in DitherMethod.entries) {
            val out = Dither.apply(method, img)
            assertEquals(480, out.width); assertEquals(800, out.height)
            val levels = out.values()
            assertTrue("$method дал ${levels.sorted()}", Dither.levels.containsAll(levels))
            if (method != DitherMethod.NONE) assertEquals("$method должен использовать все 4 уровня", 4, levels.size)
        }
    }

    @Test
    fun toneThenDitherStaysInLevels() {
        val tone = ToneAdjustments(brightness = 0.2, contrast = 1.6, gamma = 1.4, invert = true)
        val out = Dither.prepareForDevice(photoLike(), tone, DitherMethod.ATKINSON)
        assertTrue(Dither.levels.containsAll(out.values()))
    }

    @Test
    fun flatGrayPreservesAverage() {
        val out = Dither.apply(DitherMethod.FLOYD_STEINBERG, GrayImage(64, 64, 128))
        val mean = (0 until 64 * 64).sumOf { out.at(it) }.toDouble() / (64 * 64)
        assertEquals(128.0, mean, 6.0)
    }

    @Test
    fun exactLevelsAreUntouched() {
        val img = GrayImage.of(4, 1, listOf(0, 85, 170, 255))
        for (m in DitherMethod.entries) if (m != DitherMethod.BAYER) {
            assertEquals(img, Dither.apply(m, img))
        }
    }

    @Test
    fun toneLookupTable() {
        assertTrue(ToneAdjustments().isIdentity)
        val identity = ToneAdjustments().lookupTable()
        assertEquals((0..255).toList(), identity.toList())
        val inverted = ToneAdjustments(invert = true).lookupTable()
        assertEquals(255, inverted[0]); assertEquals(0, inverted[255])
        val img = GrayImage(2, 2, 10)
        assertTrue("тождественный тон не копирует картинку", ToneAdjustments().apply(img) === img)
        assertEquals(DitherMethod.ATKINSON, DitherMethod.from("atkinson"))
    }
}
