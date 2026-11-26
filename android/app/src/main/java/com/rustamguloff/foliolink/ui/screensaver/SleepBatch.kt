package com.rustamguloff.foliolink.ui.screensaver

import com.rustamguloff.foliolink.app.AppModel
import com.rustamguloff.foliolink.app.SleepRules
import com.rustamguloff.foliolink.core.imaging.BmpCodec
import com.rustamguloff.foliolink.core.imaging.Dither
import com.rustamguloff.foliolink.core.imaging.DitherMethod
import com.rustamguloff.foliolink.core.imaging.GrayImage
import com.rustamguloff.foliolink.core.imaging.ToneAdjustments
import com.rustamguloff.foliolink.core.model.OutboxOperation
import com.rustamguloff.foliolink.core.model.SleepResponse
import com.rustamguloff.foliolink.core.net.DeviceClient
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * «Добавить картинки»: много заставок за раз (порт SleepBatchUpload.swift), чистая часть.
 *
 * BMP ровно 480×800 уходит на читалку байт в байт; остальное вписывается в панель как шаблон
 * «Фото» (с обрезкой или с белыми полями), переводится в серое, дизерингуется в 4 уровня и
 * кодируется в BMP 24 бит.
 */
object SleepBatch {
    const val WIDTH = PanelGeometry.WIDTH
    const val HEIGHT = PanelGeometry.HEIGHT
    const val MAX_PHOTOS = 30
    const val MAX_NAME_LENGTH = 60

    /** «Заполнить» (с обрезкой) / «Вписать» (с белыми полями). */
    enum class Fit { FILL, FIT }

    /** Выбранная картинка: байты и, из файлов, исходное имя. */
    class Source(val data: ByteArray, val name: String?)

    // region Подготовка

    /** BMP, который читалка покажет как есть: ровно 480×800, без сжатия, целый. */
    fun isPanelBmp(data: ByteArray): Boolean {
        val h = BmpCodec.header(data) ?: return false
        if (h.width != WIDTH || h.height != HEIGHT) return false
        if (h.bitsPerPixel !in setOf(1, 2, 4, 8, 16, 24, 32) || (h.compression != 0 && h.compression != 3)) return false
        return h.dataOffset >= 54 && data.size >= h.dataOffset + h.pixelBytes
    }

    /**
     * Байты для загрузки или null, если это не картинка. `decode` — декодер платформы (PNG/JPEG/
     * HEIC/BMP → серое в исходном размере, с учётом EXIF-поворота).
     */
    fun prepare(data: ByteArray, fit: Fit, dither: DitherMethod, decode: (ByteArray) -> GrayImage?): ByteArray? {
        if (isPanelBmp(data)) return data
        val gray = decode(data) ?: return null
        return BmpCodec.encode24(Dither.prepareForDevice(panelGray(gray, fit), ToneAdjustments(), dither))
    }

    /**
     * Картинка по центру белой панели 480×800, «Заполнить» или «Вписать». Уменьшение — средним
     * по площади (без муара), увеличение — билинейное; в масштабе 1 пиксели копируются точно.
     */
    fun panelGray(src: GrayImage, fit: Fit): GrayImage {
        val out = GrayImage(WIDTH, HEIGHT, 255)
        if (src.width == 0 || src.height == 0) return out
        val r = PanelGeometry.place(src.width, src.height, fill = fit == Fit.FILL)
        val scale = r.width.toDouble() / src.width
        val x0 = max(0, floor(r.left.toDouble() + 0.5).toInt())
        val x1 = min(WIDTH, floor(r.right.toDouble() + 0.5).toInt())
        val y0 = max(0, floor(r.top.toDouble() + 0.5).toInt())
        val y1 = min(HEIGHT, floor(r.bottom.toDouble() + 0.5).toInt())
        val sw = src.width
        val sh = src.height
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val v = if (scale <= 1.0) {
                    // Область источника под пикселем назначения.
                    val sx0 = ((x - r.left) / scale).toInt().coerceIn(0, sw - 1)
                    val sy0 = ((y - r.top) / scale).toInt().coerceIn(0, sh - 1)
                    val sx1 = ((x + 1 - r.left) / scale).toInt().coerceIn(sx0 + 1, sw)
                    val sy1 = ((y + 1 - r.top) / scale).toInt().coerceIn(sy0 + 1, sh)
                    var sum = 0L
                    for (sy in sy0 until sy1) {
                        val row = sy * sw
                        for (sx in sx0 until sx1) sum += src.pixels[row + sx].toInt() and 0xFF
                    }
                    (sum / ((sx1 - sx0) * (sy1 - sy0))).toInt()
                } else {
                    val fx = ((x + 0.5 - r.left) / scale - 0.5).coerceIn(0.0, sw - 1.0)
                    val fy = ((y + 0.5 - r.top) / scale - 0.5).coerceIn(0.0, sh - 1.0)
                    val ix = fx.toInt()
                    val iy = fy.toInt()
                    val ix1 = min(ix + 1, sw - 1)
                    val iy1 = min(iy + 1, sh - 1)
                    val tx = fx - ix
                    val ty = fy - iy
                    val top = src[ix, iy] * (1 - tx) + src[ix1, iy] * tx
                    val bottom = src[ix, iy1] * (1 - tx) + src[ix1, iy1] * tx
                    (top * (1 - ty) + bottom * ty + 0.5).toInt()
                }
                out[x, y] = v
            }
        }
        return out
    }

    // endregion

    // region Имена

    private val CYRILLIC = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh",
        'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o",
        'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts",
        'ч' to "ch", 'ш' to "sh", 'щ' to "shch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu",
        'я' to "ya", 'і' to "i", 'ї' to "yi", 'є' to "ye", 'ґ' to "g", 'ў' to "u",
    )

    /** Кириллица → латиница, затем без диакритики («Café» → «Cafe»). */
    internal fun toLatin(text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            val lower = ch.lowercaseChar()
            val t = CYRILLIC[lower]
            if (t == null) {
                sb.append(ch)
            } else if (ch != lower && t.isNotEmpty()) {
                sb.append(t.replaceFirstChar { it.uppercaseChar() })
            } else {
                sb.append(t)
            }
        }
        return Normalizer.normalize(sb, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    }

    /**
     * ASCII-основа имени из исходного: транслитерация, пробелы и знаки → '-', не длиннее
     * [MAX_NAME_LENGTH]; "" — если ничего годного не осталось.
     */
    fun stem(original: String): String {
        val dot = original.lastIndexOf('.')
        val base = if (dot > 0) original.substring(0, dot) else original
        val text = toLatin(base)
        val out = StringBuilder()
        for (ch in text) {
            val ok = ch.code < 128 && (ch.isLetterOrDigit() || ch == '_' || ch == '-')
            if (ok) out.append(ch) else if (out.isNotEmpty() && !out.endsWith("-")) out.append('-')
        }
        var s = out.toString()
        while ("--" in s) s = s.replace("--", "-")
        s = s.trim('-', '_')
        return s.take(MAX_NAME_LENGTH).trim('-', '_')
    }

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** "photo-yyyyMMdd-HHmmss-N" для картинок без годного имени. */
    fun photoStem(date: LocalDateTime, index: Int): String = "photo-${date.format(STAMP)}-$index"

    /**
     * Раздаёт имена ".bmp", не совпадающие ни с файлами читалки, ни между собой (без учёта
     * регистра, как FAT на карте).
     */
    class NameAllocator(existing: Collection<String>, private val date: LocalDateTime = LocalDateTime.now()) {
        private val taken = existing.mapTo(HashSet()) { it.lowercase() }
        private var unnamed = 0

        fun next(original: String?): String {
            var base = original?.let(::stem).orEmpty()
            if (base.isEmpty()) {
                unnamed++
                base = photoStem(date, unnamed)
            }
            var name = "$base.bmp"
            var n = 2
            while (name.lowercase() in taken) {
                name = "$base-$n.bmp"
                n++
            }
            taken += name.lowercase()
            return name
        }
    }

    // endregion

    // region Загрузка

    /** Что нужно загрузчику от AppModel (интерфейс — чтобы тесты записывали очередь). */
    interface Host {
        val sleep: SleepResponse?
        suspend fun submit(
            operation: OutboxOperation,
            title: String,
            detail: String,
            payload: ByteArray?,
            fileName: String,
        ): AppModel.SubmitResult
    }

    data class Outcome(
        val sent: Int = 0,
        val queued: Int = 0,
        val failed: Int = 0,
        /** Картинки, которые не удалось прочитать или декодировать. */
        val skipped: Int = 0,
        val cancelled: Boolean = false,
    ) {
        val uploaded: Int get() = sent + queued
    }

    /**
     * Ставит каждый файл в очередь как одиночная заставка (с заменой одноимённого; без связи —
     * ждёт в очереди), но ни один не выбирает. Останавливается, когда `isCancelled()`.
     */
    suspend fun upload(
        files: List<Pair<String, ByteArray>>,
        host: Host,
        title: (String) -> String,
        isCancelled: () -> Boolean = { false },
        progress: (Int) -> Unit = {},
    ): Outcome {
        val dir = host.sleep?.dir?.ifEmpty { null } ?: "/sleep"
        var o = Outcome()
        for ((i, file) in files.withIndex()) {
            if (isCancelled()) {
                o = o.copy(cancelled = true)
                break
            }
            progress(i + 1)
            val (name, bmp) = file
            val op = OutboxOperation.UploadFile(dir, "image/bmp", replace = true, purpose = OutboxOperation.UploadPurpose.SLEEP)
            o = when (host.submit(op, title(name), DeviceClient.join(dir, name), bmp, name)) {
                AppModel.SubmitResult.SENT -> o.copy(sent = o.sent + 1)
                AppModel.SubmitResult.QUEUED -> o.copy(queued = o.queued + 1)
                AppModel.SubmitResult.FAILED -> o.copy(failed = o.failed + 1)
            }
        }
        return o
    }

    /** Вид итогового баннера; null — сказать нечего. */
    fun summaryKind(o: Outcome): AppModel.Banner.Kind? = when {
        o.sent == 0 && o.queued == 0 && o.failed == 0 && o.skipped == 0 -> null
        o.failed + o.skipped > 0 -> AppModel.Banner.Kind.ERROR
        o.sent > 0 && o.queued == 0 -> AppModel.Banner.Kind.SUCCESS
        else -> AppModel.Banner.Kind.INFO
    }

    /** После пакета предложить «Свой», если читалка сейчас не показывает свои картинки. */
    fun shouldOfferCustomMode(o: Outcome, mode: Int?): Boolean = o.uploaded > 0 && !SleepRules.modeShowsImages(mode)

    // endregion

    /** Этап пакета для строки состояния и полосы прогресса. */
    sealed interface Phase {
        data object Idle : Phase
        data class Preparing(val index: Int, val count: Int) : Phase
        data class Sending(val index: Int, val count: Int) : Phase

        /** Подготовка — первая половина полосы, отправка — вторая. */
        val progress: Float
            get() = when (this) {
                Idle -> 0f
                is Preparing -> (index - 1).toFloat() / max(count, 1) * 0.5f
                is Sending -> 0.5f + (index - 1).toFloat() / max(count, 1) * 0.5f
            }
    }
}

/** Набор из 10 заставок Folio в `assets/sleeppack` (PNG 480×800 уже в 4 оттенках читалки). */
object SleepPack {
    const val DIR = "sleeppack"

    data class Item(val asset: String) {
        val id: String get() = asset
        /** Имя на читалке: folio-01-night-reading.bmp … */
        val fileName: String get() = SleepBatch.stem(asset) + ".bmp"
        val path: String get() = "$DIR/$asset"
    }

    /** Из списка файлов папки: только PNG, по имени. */
    fun items(names: Array<String>?): List<Item> =
        names.orEmpty().filter { it.lowercase().endsWith(".png") }.sorted().map(::Item)
}
