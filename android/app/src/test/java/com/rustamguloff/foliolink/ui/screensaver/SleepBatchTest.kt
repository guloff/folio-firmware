package com.rustamguloff.foliolink.ui.screensaver

import com.rustamguloff.foliolink.app.AppModel
import com.rustamguloff.foliolink.core.imaging.BmpCodec
import com.rustamguloff.foliolink.core.imaging.Dither
import com.rustamguloff.foliolink.core.imaging.DitherMethod
import com.rustamguloff.foliolink.core.imaging.GrayImage
import com.rustamguloff.foliolink.core.model.OutboxOperation
import com.rustamguloff.foliolink.core.model.SleepResponse
import com.rustamguloff.foliolink.core.store.Outbox
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import javax.imageio.ImageIO

// Порт SleepBatchTests.swift: имена, подготовка картинок, постановка в очередь, набор Folio.

/** Записывает, что ставит пакет. */
private class BatchHost(override var sleep: SleepResponse? = SleepResponse()) : SleepBatch.Host {
    var result = AppModel.SubmitResult.SENT
    val submitted = mutableListOf<OutboxOperation>()
    val fileNames = mutableListOf<String>()
    val payloads = mutableListOf<ByteArray>()
    val details = mutableListOf<String>()

    override suspend fun submit(
        operation: OutboxOperation,
        title: String,
        detail: String,
        payload: ByteArray?,
        fileName: String,
    ): AppModel.SubmitResult {
        submitted += operation
        fileNames += fileName
        payloads += payload ?: ByteArray(0)
        details += detail
        return result
    }
}

/** Декодер для тестов: BMP своим кодеком, PNG/JPEG — ImageIO (на телефоне — BitmapFactory). */
private fun decode(data: ByteArray): GrayImage? =
    runCatching { BmpCodec.decode(data).toGray() }.getOrNull()
        ?: runCatching { ImageIO.read(data.inputStream())?.let(::grayOf) }.getOrNull()

private fun grayOf(img: java.awt.image.BufferedImage): GrayImage {
    val px = ByteArray(img.width * img.height)
    if (img.raster.numBands == 1) {
        // Серый PNG: значения как есть (getRGB прогнал бы их через цветовое пространство).
        for (y in 0 until img.height) for (x in 0 until img.width) px[y * img.width + x] = img.raster.getSample(x, y, 0).toByte()
        return GrayImage(img.width, img.height, px)
    }
    for (y in 0 until img.height) for (x in 0 until img.width) {
        val c = img.getRGB(x, y)
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        px[y * img.width + x] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
    }
    return GrayImage(img.width, img.height, px)
}

/** Полосы: левая половина тёмная, правая белая. */
private fun stripes(w: Int, h: Int): ByteArray {
    val g = GrayImage(w, h, 255)
    for (y in 0 until h) for (x in 0 until w / 2) g[x, y] = 80
    return BmpCodec.encode24(g)
}

private fun gray(bmp: ByteArray) = BmpCodec.decode(bmp).toGray()

class SleepBatchNameTest {
    @Test
    fun stemSanitizing() {
        assertEquals("folio-01-night-reading", SleepBatch.stem("folio-01-night-reading.bmp"))
        assertEquals("My-Photo-1", SleepBatch.stem("My Photo (1).JPEG"))
        assertEquals("a-b-c", SleepBatch.stem("  a / b \\ c:*?.png"))
        assertEquals("hidden", SleepBatch.stem(".hidden.png"))
        assertEquals("Cafe-creme", SleepBatch.stem("Café crème.heic"))
        assertEquals("", SleepBatch.stem("🙂🙂.png"))
        assertEquals("Nochnoe-chtenie", SleepBatch.stem("Ночное чтение.png"))
        assertEquals("Shchuka-i-ezh", SleepBatch.stem("Щука и ёж.jpg"))
        val long = SleepBatch.stem(List(40) { "word" }.joinToString(" ") + ".png")
        assertTrue(long.length <= SleepBatch.MAX_NAME_LENGTH)
        assertFalse(long.endsWith("-"))
        // Каждый результат переживает очистку имени в очереди без изменений.
        val cyrillic = SleepBatch.stem("Ночное чтение.png")
        assertEquals("$cyrillic.bmp", Outbox.sanitize("$cyrillic.bmp"))
        assertTrue(cyrillic.all { it.code < 128 })
    }

    @Test
    fun collisionsWithReaderAndBatch() {
        val date = LocalDateTime.of(2026, 10, 15, 10, 15, 0)
        val names = SleepBatch.NameAllocator(listOf("Cat.bmp", "photo-20261015-101500-1.bmp"), date)
        assertEquals("FAT names are case-insensitive", "cat-2.bmp", names.next("cat.png"))
        assertEquals("and unique within the batch", "cat-3.bmp", names.next("cat.jpg"))
        assertEquals("dog.bmp", names.next("dog.bmp"))
        assertEquals("photo-20261015-101500-1-2.bmp", names.next(null))
        assertEquals("photo-20261015-101500-2.bmp", names.next(null))
        assertEquals("no usable name → photo name", "photo-20261015-101500-3.bmp", names.next("🙂.png"))
    }
}

class SleepBatchImageTest {
    @Test
    fun panelBmpPassesThroughUnchanged() {
        // Средний серый при конвертации задизерился бы, так что совпадение байтов — это проход как есть.
        val bmp = BmpCodec.encode24(GrayImage(480, 800, 128))
        assertTrue(SleepBatch.isPanelBmp(bmp))
        assertArrayEquals(bmp, SleepBatch.prepare(bmp, SleepBatch.Fit.FILL, DitherMethod.FLOYD_STEINBERG, ::decode))
        assertFalse("truncated file is converted, not trusted", SleepBatch.isPanelBmp(bmp.copyOf(bmp.size - 100)))
    }

    @Test
    fun otherSizesAreConverted() {
        for ((w, h) in listOf(800 to 480, 240 to 400, 481 to 800)) {
            val bmp = BmpCodec.encode24(GrayImage(w, h, 128))
            assertFalse("$w×$h", SleepBatch.isPanelBmp(bmp))
            val out = SleepBatch.prepare(bmp, SleepBatch.Fit.FILL, DitherMethod.FLOYD_STEINBERG, ::decode)
            assertNotNull(out)
            val header = BmpCodec.header(out!!)!!
            assertEquals("$w×$h", listOf(480, 800, 24), listOf(header.width, header.height, header.bitsPerPixel))
            assertFalse(out.contentEquals(bmp))
        }
        assertNull(SleepBatch.prepare("not a picture".toByteArray(), SleepBatch.Fit.FILL, DitherMethod.FLOYD_STEINBERG, ::decode))
    }

    @Test
    fun conversionIs480x800InFourGrays() {
        val src = stripes(1000, 600)
        for (method in DitherMethod.entries) {
            val out = SleepBatch.prepare(src, SleepBatch.Fit.FILL, method, ::decode)!!
            val rgba = BmpCodec.decode(out)
            assertEquals(listOf(480, 800), listOf(rgba.width, rgba.height))
            val values = HashSet<Int>()
            for (i in 0 until rgba.width * rgba.height) {
                val r = rgba.rgba[i * 4].toInt() and 0xFF
                assertEquals(r, rgba.rgba[i * 4 + 1].toInt() and 0xFF)
                assertEquals(r, rgba.rgba[i * 4 + 2].toInt() and 0xFF)
                values += r
            }
            assertTrue("$method: $values", Dither.levels.containsAll(values))
            assertTrue(255 in values && values.size > 1)
        }
    }

    @Test
    fun fillCropsAndFitLeavesWhiteMargins() {
        val src = stripes(1000, 600) // альбомная на портретной панели
        val fit = gray(SleepBatch.prepare(src, SleepBatch.Fit.FIT, DitherMethod.NONE, ::decode)!!)
        val fill = gray(SleepBatch.prepare(src, SleepBatch.Fit.FILL, DitherMethod.NONE, ::decode)!!)
        assertTrue("fit: white band on top", (0 until 480).all { fit[it, 0] == 255 })
        assertTrue("fit: the dark half shows in the middle", fit[10, 400] < 255)
        assertTrue("fill: the stripes reach the top edge", fill[10, 0] < 255 && fill[470, 0] == 255)
    }

    @Test
    fun smallPicturesAreScaledUpSmoothly() {
        val src = GrayImage(48, 80, 255)
        for (x in 0 until 24) for (y in 0 until 80) src[x, y] = 0
        val out = SleepBatch.panelGray(src, SleepBatch.Fit.FILL)
        assertEquals(0, out[5, 400])
        assertEquals(255, out[470, 400])
        // Край билинейный: переход проходит через серый.
        assertTrue((230 until 250).any { out[it, 400] in 1..254 })
    }

    @Test
    fun exactPanelPixelsAreCopied() {
        val src = GrayImage(480, 800, 255)
        for (i in src.pixels.indices) src.pixels[i] = Dither.levels[i % 4].toByte()
        assertEquals(src, SleepBatch.panelGray(src, SleepBatch.Fit.FILL))
        assertEquals(src, SleepBatch.panelGray(src, SleepBatch.Fit.FIT))
    }
}

class SleepBatchUploadTest {
    private val upload = OutboxOperation.UploadFile("/sleep", "image/bmp", replace = true, purpose = OutboxOperation.UploadPurpose.SLEEP)
    private val files = (1..3).map { "p$it.bmp" to byteArrayOf(it.toByte()) }
    private val title = { name: String -> "Sleep $name" }

    @Test
    fun batchQueuesOneUploadEachAndSelectsNothing() = runTest {
        val host = BatchHost(SleepResponse(mode = 8))
        val seen = mutableListOf<Int>()
        val outcome = SleepBatch.upload(files, host, title) { seen += it }
        assertEquals("no select, no mode switch", List(3) { upload }, host.submitted)
        assertEquals(listOf("p1.bmp", "p2.bmp", "p3.bmp"), host.fileNames)
        assertEquals(listOf("/sleep/p1.bmp", "/sleep/p2.bmp", "/sleep/p3.bmp"), host.details)
        assertTrue(host.payloads.zip(files).all { (a, b) -> a.contentEquals(b.second) })
        assertEquals(listOf(1, 2, 3), seen)
        assertEquals(SleepBatch.Outcome(sent = 3), outcome)
        assertTrue(SleepBatch.shouldOfferCustomMode(outcome, 8))
        assertFalse(SleepBatch.shouldOfferCustomMode(outcome, 2))
        assertFalse(SleepBatch.shouldOfferCustomMode(outcome, 4))
        assertFalse(SleepBatch.shouldOfferCustomMode(SleepBatch.Outcome(failed = 3), 8))
        assertEquals(AppModel.Banner.Kind.SUCCESS, SleepBatch.summaryKind(outcome))
    }

    @Test
    fun usesTheReadersSleepFolder() = runTest {
        val host = BatchHost(SleepResponse(dir = "/.sleep"))
        SleepBatch.upload(files.take(1), host, title)
        assertEquals("/.sleep", (host.submitted.single() as OutboxOperation.UploadFile).dir)
        val noFolder = BatchHost(null)
        SleepBatch.upload(files.take(1), noFolder, title)
        assertEquals("/sleep", (noFolder.submitted.single() as OutboxOperation.UploadFile).dir)
    }

    @Test
    fun offlineBatchIsQueued() = runTest {
        val host = BatchHost().apply { result = AppModel.SubmitResult.QUEUED }
        val outcome = SleepBatch.upload(files, host, title)
        assertEquals(3, host.submitted.size)
        assertEquals(SleepBatch.Outcome(queued = 3), outcome)
        assertEquals(AppModel.Banner.Kind.INFO, SleepBatch.summaryKind(outcome))
        assertNull(SleepBatch.summaryKind(SleepBatch.Outcome()))
        assertEquals(AppModel.Banner.Kind.ERROR, SleepBatch.summaryKind(SleepBatch.Outcome(sent = 2, skipped = 1)))
        assertEquals(AppModel.Banner.Kind.ERROR, SleepBatch.summaryKind(SleepBatch.Outcome(failed = 1)))
        assertEquals(AppModel.Banner.Kind.INFO, SleepBatch.summaryKind(SleepBatch.Outcome(sent = 1, queued = 1)))
    }

    @Test
    fun cancelledBatchSendsNothingMore() = runTest {
        val host = BatchHost()
        val outcome = SleepBatch.upload(files, host, title, isCancelled = { true })
        assertTrue(outcome.cancelled)
        assertTrue(host.submitted.isEmpty())
        // Отмена посреди пакета: отправленное остаётся, остальное — нет.
        val mid = BatchHost()
        var n = 0
        val partial = SleepBatch.upload(files, mid, title, isCancelled = { n++ >= 2 })
        assertEquals(SleepBatch.Outcome(sent = 2, cancelled = true), partial)
    }

    @Test
    fun progressHalvesBetweenPreparingAndSending() {
        assertEquals(0f, SleepBatch.Phase.Idle.progress)
        assertEquals(0f, SleepBatch.Phase.Preparing(1, 4).progress)
        assertEquals(0.375f, SleepBatch.Phase.Preparing(4, 4).progress)
        assertEquals(0.5f, SleepBatch.Phase.Sending(1, 4).progress)
        assertEquals(0.875f, SleepBatch.Phase.Sending(4, 4).progress)
    }
}

class SleepPackTest {
    private val dir = listOf(File("src/main/assets/sleeppack"), File("app/src/main/assets/sleeppack")).first { it.isDirectory }

    @Test
    fun packHasTenPanelImages() {
        val items = SleepPack.items(dir.list())
        assertEquals(10, items.size)
        assertEquals("folio-01-night-reading.bmp", items.first().fileName)
        assertEquals("sleeppack/folio-01-night-reading.png", items.first().path)
        assertEquals(10, items.map { it.fileName }.toSet().size)
        for (item in items) {
            val image = decode(File(dir, item.asset).readBytes())!!
            assertEquals(item.asset, listOf(480, 800), listOf(image.width, image.height))
        }
        assertTrue(SleepPack.items(arrayOf("a.txt", "b.PNG")).map { it.asset } == listOf("b.PNG"))
        assertTrue(SleepPack.items(null).isEmpty())
    }

    /** Набор уже в 4 оттенках читалки: конвертация не меняет ни пикселя. */
    @Test
    fun packConversionIsIdentity() {
        val item = SleepPack.items(dir.list()).first()
        val data = File(dir, item.asset).readBytes()
        val source = SleepBatch.panelGray(decode(data)!!, SleepBatch.Fit.FILL)
        assertTrue(source.pixels.map { it.toInt() and 0xFF }.toSet().all { it in Dither.levels })
        val out = SleepBatch.prepare(data, SleepBatch.Fit.FILL, DitherMethod.FLOYD_STEINBERG, ::decode)!!
        assertEquals(source, gray(out))
        // Имена набора сохраняются как есть: повторная отправка заменяет файлы.
        val names = SleepBatch.NameAllocator(emptyList())
        val items = SleepPack.items(dir.list())
        assertEquals(items.map { it.fileName }, items.map { names.next(it.asset) })
    }
}
