@file:android.annotation.SuppressLint("ExifInterface")

package com.rustamguloff.foliolink.ui.screensaver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.app.AppModel
import com.rustamguloff.foliolink.core.imaging.AndroidImaging
import com.rustamguloff.foliolink.core.imaging.BmpCodec
import com.rustamguloff.foliolink.core.imaging.GrayImage
import com.rustamguloff.foliolink.core.model.OutboxOperation
import com.rustamguloff.foliolink.core.model.SleepResponse
import com.rustamguloff.foliolink.core.net.readLimited
import com.rustamguloff.foliolink.ui.library.ContentFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/** Декодирование картинок телефона: уменьшение при чтении, EXIF-поворот, серое. */
object SleepImageDecoder {
    /** Длинная сторона после чтения (выход всего 480×800). */
    const val MAX_SIDE = 1600
    /** Больше не читаем: это уже не картинка для экрана читалки. */
    private const val MAX_BYTES = 64L * 1024 * 1024

    /** Байты по URI (галерея, файлы); null — не читается. */
    fun read(context: Context, uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            readLimited(input, MAX_BYTES)
        }
    }.getOrNull()

    /** Имя файла по URI документа (для имени на читалке). */
    fun displayName(context: Context, uri: Uri): String? =
        ContentFiles.providerName(context, uri) ?: uri.lastPathSegment?.substringAfterLast('/')

    /** Bitmap с длинной стороной не больше ~[maxSide]·2 (степень двойки) и с учётом EXIF-поворота. */
    fun decodeBitmap(data: ByteArray, maxSide: Int = MAX_SIDE): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, opts) ?: return null
        return oriented(bitmap, data)
    }

    // android.media.ExifInterface с API 24 читает поток; androidx-зависимость ради одного тега не нужна.
    private fun oriented(bitmap: Bitmap, data: ByteArray): Bitmap {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(data)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /** Любая картинка → серое в исходном размере; BMP, которые не берёт система, — своим декодером. */
    fun decodeGray(data: ByteArray): GrayImage? {
        decodeBitmap(data)?.let { bmp ->
            val gray = AndroidImaging.grayFromBitmap(bmp)
            bmp.recycle()
            return gray
        }
        return runCatching { BmpCodec.decode(data).toGray() }.getOrNull()
    }
}

/** Откуда пришла картинка пакета. */
sealed interface SleepBatchInput {
    /** Галерея: имя не берём (как iOS — «photo-дата-N»). */
    data class Photo(val uri: Uri) : SleepBatchInput
    /** Файлы: имя файла становится именем на читалке. */
    data class Document(val uri: Uri) : SleepBatchInput
    /** Встроенный набор Folio. */
    data class Pack(val item: SleepPack.Item) : SleepBatchInput

    fun load(context: Context): SleepBatch.Source? = when (this) {
        is Photo -> SleepImageDecoder.read(context, uri)?.let { SleepBatch.Source(it, null) }
        is Document -> SleepImageDecoder.read(context, uri)?.let { SleepBatch.Source(it, SleepImageDecoder.displayName(context, uri)) }
        is Pack -> runCatching { context.assets.open(item.path).use { it.readBytes() } }.getOrNull()
            ?.let { SleepBatch.Source(it, item.asset) }
    }
}

/** AppModel как получатель пакета. */
private class ModelHost(private val model: AppModel) : SleepBatch.Host {
    override val sleep: SleepResponse? get() = model.snapshot.value.sleep
    override suspend fun submit(
        operation: OutboxOperation,
        title: String,
        detail: String,
        payload: ByteArray?,
        fileName: String,
    ): AppModel.SubmitResult = model.submit(operation, title, detail, payload, fileName)
}

/**
 * Один пакет за раз: подготовка каждой картинки в фоне, затем постановка загрузок в очередь.
 * Один на приложение, так что пакет продолжается (и его прогресс виден снова) после ухода со страницы.
 */
object SleepBatchController {
    var phase by mutableStateOf<SleepBatch.Phase>(SleepBatch.Phase.Idle)
        private set
    /** Последний пакет ушёл на читалку, чей режим сна не показывает загруженные картинки. */
    var offerCustomMode by mutableStateOf(false)
    private val cancelled = AtomicBoolean(false)

    val isRunning: Boolean get() = phase != SleepBatch.Phase.Idle

    /**
     * `replaceSameNames`: оставить каждой картинке её имя, даже если такое уже есть на читалке
     * (набор Folio: повторная отправка заменяет, а не плодит копии).
     */
    fun start(
        inputs: List<SleepBatchInput>,
        fit: SleepBatch.Fit,
        model: AppModel,
        context: Context,
        replaceSameNames: Boolean = false,
    ) {
        if (inputs.isEmpty() || isRunning) return
        val app = context.applicationContext
        offerCustomMode = false
        cancelled.set(false)
        phase = SleepBatch.Phase.Preparing(1, inputs.size)
        model.launch {
            try {
                run(inputs.take(SleepBatch.MAX_PHOTOS), fit, this, app, replaceSameNames)
            } finally {
                phase = SleepBatch.Phase.Idle
            }
        }
    }

    fun cancel() { cancelled.set(true) }

    private suspend fun run(
        inputs: List<SleepBatchInput>,
        fit: SleepBatch.Fit,
        model: AppModel,
        context: Context,
        replaceSameNames: Boolean,
    ) {
        val dither = ScreensaverRenderer.ditherFrom(model.settings.ditherMethod)
        val onReader = if (replaceSameNames) emptyList() else model.snapshot.value.sleep?.images?.map { it.name }.orEmpty()
        val names = SleepBatch.NameAllocator(onReader)
        val files = mutableListOf<Pair<String, ByteArray>>()
        var skipped = 0
        for ((i, input) in inputs.withIndex()) {
            if (cancelled.get()) break
            phase = SleepBatch.Phase.Preparing(i + 1, inputs.size)
            val source = withContext(Dispatchers.IO) { input.load(context) }
            if (source == null) { skipped++; continue }
            val bmp = try {
                withContext(Dispatchers.Default) {
                    SleepBatch.prepare(source.data, fit, dither, SleepImageDecoder::decodeGray)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            if (bmp == null) { skipped++; continue }
            files += names.next(source.name) to bmp
        }
        // Отменено во время подготовки: ничего не отправлено.
        if (cancelled.get()) {
            model.show(context.getString(R.string.screensaver_batch_cancelled), AppModel.Banner.Kind.INFO)
            return
        }
        val outcome = SleepBatch.upload(
            files, ModelHost(model),
            title = { context.getString(R.string.core_title_sleep_image, it) },
            isCancelled = { cancelled.get() },
        ) { i -> phase = SleepBatch.Phase.Sending(i, files.size) }.copy(skipped = skipped)
        if (outcome.sent > 0 && model.isConnected) model.refreshSleep()
        SleepBatch.summaryKind(outcome)?.let { kind -> model.show(summaryText(context, outcome), kind) }
        offerCustomMode = SleepBatch.shouldOfferCustomMode(outcome, model.snapshot.value.sleep?.mode)
    }

    /** Один баннер после пакета: «Отправлено 3 картинки, 2 картинки в очереди, …». */
    fun summaryText(context: Context, o: SleepBatch.Outcome): String {
        val r = context.resources
        return buildList {
            if (o.sent > 0) add(r.getQuantityString(R.plurals.screensaver_batch_sent, o.sent, o.sent))
            if (o.queued > 0) add(r.getQuantityString(R.plurals.screensaver_batch_queued, o.queued, o.queued))
            if (o.failed > 0) add(r.getString(R.string.screensaver_batch_failed, o.failed))
            if (o.skipped > 0) add(r.getString(R.string.screensaver_batch_skipped, o.skipped))
        }.joinToString(", ")
    }
}
