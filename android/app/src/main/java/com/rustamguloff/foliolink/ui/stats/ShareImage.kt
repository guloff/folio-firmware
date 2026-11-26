package com.rustamguloff.foliolink.ui.stats

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.rustamguloff.foliolink.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

// Общее для «Статистики» и «Итогов года»: форматирование, картинка для «Поделиться».

/** Масштаб картинки для «Поделиться»: 360 dp → 1080 px (как renderer.scale = 3 в iOS). */
const val POSTER_SCALE = 3f

/** Форматирование для статистики. */
object StatsFmt {
    /** «1 час 5 минут» — для TalkBack и подписей (как Fmt.duration(style: .full) в iOS). */
    fun durationFull(res: Resources, seconds: Int): String {
        val mins = maxOf(seconds, 0) / 60
        val h = mins / 60
        val m = mins % 60
        return when {
            h == 0 -> res.getQuantityString(R.plurals.plural_minute, m, m)
            m == 0 -> res.getQuantityString(R.plurals.stats_hours_full, h, h)
            else -> res.getQuantityString(R.plurals.stats_hours_full, h, h) + " " +
                res.getQuantityString(R.plurals.plural_minute, m, m)
        }
    }

    fun longDate(date: LocalDate): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).format(date)

    fun mediumDate(date: LocalDate): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(date)

    /** «3 мар.» по локали. */
    fun shortDate(date: LocalDate): String {
        val locale = Locale.getDefault()
        return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "dMMM"), locale).format(date)
    }

    /** «янв.» — подписи месяцев на сетке и графике. */
    fun monthShort(date: LocalDate): String = date.month.getDisplayName(TextStyle.SHORT_STANDALONE, Locale.getDefault())

    /** «январь» — самостоятельная форма (как standaloneMonthSymbols). */
    fun monthName(month: Int): String =
        java.time.Month.of(month).getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())

    /** Диапазон дат «3 мар. 2026 г. — 9 мар. 2026 г.»; один день — длинной датой. */
    fun range(from: LocalDate, to: LocalDate): String =
        if (from == to) longDate(from) else mediumDate(from) + " — " + mediumDate(to)
}

/** Картинка → PNG в `cacheDir/share/` и системное окно «Поделиться». */
object ShareImage {
    suspend fun share(context: Context, image: ImageBitmap, fileName: String) {
        val file = try {
            withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "share").apply { mkdirs() }
                val file = File(dir, fileName)
                var bitmap = image.asAndroidBitmap()
                // Слой может отдать аппаратную картинку; PNG пишется из обычной.
                if (bitmap.config == Bitmap.Config.HARDWARE) {
                    bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                }
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                file
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Превью картинки в системном окне.
        send.clipData = ClipData.newRawUri(null, uri)
        val chooser = Intent.createChooser(send, context.getString(R.string.common_share))
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}

/**
 * Рисует [content] вне экрана в масштабе [POSTER_SCALE] (ширина [width], высота [height] или по
 * содержимому) и один раз отдаёт картинку в [onCaptured]. На экране ничего не видно: слой
 * записывается, но не рисуется; TalkBack его не видит.
 */
@Composable
fun OffscreenCapture(
    width: Dp,
    height: Dp?,
    onCaptured: (ImageBitmap?) -> Unit,
    content: @Composable () -> Unit,
) {
    val layer = rememberGraphicsLayer()
    val callback = rememberUpdatedState(onCaptured)
    Box(Modifier.size(0.dp)) {
        CompositionLocalProvider(LocalDensity provides Density(POSTER_SCALE, 1f)) {
            val sized = if (height != null) Modifier.requiredSize(width, height) else Modifier.requiredWidth(width)
            Box(
                Modifier
                    .wrapContentSize(align = Alignment.TopStart, unbounded = true)
                    .then(sized)
                    .clearAndSetSemantics { }
                    .drawWithContent { layer.record { this@drawWithContent.drawContent() } },
            ) { content() }
        }
    }
    LaunchedEffect(Unit) {
        // Пара кадров: композиция, раскладка и запись слоя успевают пройти.
        repeat(3) { withFrameNanos { } }
        val image = try {
            layer.toImageBitmap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        callback.value(image)
    }
}
