package com.rustamguloff.foliolink.ui.common

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.annotation.PluralsRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.core.model.Book
import com.rustamguloff.foliolink.core.model.BookStatus
import com.rustamguloff.foliolink.ui.theme.FolioSuccess
import java.text.DateFormat
import java.util.Date
import java.util.Locale

// Общие элементы интерфейса — аналог iOS Features/Common/CommonViews.swift.

/** Форматирование чисел, длительностей и дат для интерфейса. */
object Fmt {
    /** "xteink_x4_pro" → "Xteink X4 Pro" (прошивка сообщает id платы). */
    fun boardName(raw: String): String {
        if (!raw.contains('_')) return raw
        return raw.split('_').joinToString(" ") { part ->
            if (part.length <= 3 && part.any { it.isDigit() }) part.uppercase()
            else part.replaceFirstChar { it.titlecase(Locale.getDefault()) }
        }
    }

    /** «1 ч 5 мин» / «1h 5m»; меньше минуты — «0 мин». */
    fun duration(context: Context, seconds: Int): String {
        val mins = maxOf(seconds, 0) / 60
        val h = mins / 60
        val m = mins % 60
        return when {
            h == 0 -> context.getString(R.string.common_duration_m, m)
            m == 0 -> context.getString(R.string.common_duration_h, h)
            else -> context.getString(R.string.common_duration_hm, h, m)
        }
    }

    /** Часы с одним знаком до 10, дальше целые. */
    fun hours(seconds: Int): String {
        val h = seconds / 3600.0
        return if (h >= 10) String.format(Locale.getDefault(), "%.0f", h) else String.format(Locale.getDefault(), "%.1f", h)
    }

    fun bytes(context: Context, n: Long): String = Formatter.formatShortFileSize(context, n)

    fun megabytes(context: Context, mb: Int): String = Formatter.formatShortFileSize(context, mb.toLong() * 1_048_576)

    /** Дата по epoch-секундам; 0 — «—». */
    fun date(epochSeconds: Long): String {
        if (epochSeconds <= 0) return "—"
        return DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochSeconds * 1000))
    }

    /** Дата и время по epoch-миллисекундам. */
    fun dateTime(epochMs: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))

    /** «5 минут назад», «вчера». */
    fun relative(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String =
        DateUtils.getRelativeTimeSpanString(epochMs, nowMs, DateUtils.MINUTE_IN_MILLIS).toString()
}

/** Существительное, согласованное с числом (ресурсы plurals). */
@Composable
fun plural(@PluralsRes id: Int, n: Int): String = pluralStringResource(id, n, n)

val BookStatus.titleRes: Int
    get() = when (this) {
        BookStatus.WANT -> R.string.status_want
        BookStatus.READING -> R.string.status_reading
        BookStatus.DONE -> R.string.status_done
        BookStatus.DROPPED -> R.string.status_dropped
    }

val BookStatus.shortTitleRes: Int
    get() = when (this) {
        BookStatus.WANT -> R.string.status_want_short
        else -> titleRes
    }

/** Карточка-контейнер для главной и статистики. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (icon != null) Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                }
            }
            content()
        }
    }
}

/** Кольцо прогресса цели; больше 100 % — второй, зелёный круг. */
@Composable
fun ProgressRing(progress: Float, modifier: Modifier = Modifier, lineWidth: Dp = 12.dp) {
    val animated by animateFloatAsState(progress, label = "ring")
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val stroke = lineWidth.toPx()
        val inset = stroke / 2
        val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
        val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
        drawArc(accent.copy(alpha = 0.15f), 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
        drawArc(accent, -90f, 360f * animated.coerceIn(0f, 1f), false, topLeft, arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round))
        if (animated > 1f) {
            drawArc(FolioSuccess, -90f, 360f * (animated - 1f).coerceIn(0f, 1f), false, topLeft, arcSize,
                style = Stroke(stroke / 2, cap = StrokeCap.Round))
        }
    }
}

/** Сгенерированная «обложка»: первая буква на тонированной карточке. */
@Composable
fun BookCoverGlyph(title: String, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val letter = title.trim { it.isWhitespace() || !it.isLetterOrDigit() }.firstOrNull()?.toString() ?: "?"
    var h = 0
    for (c in title) h = (h * 31 + c.code).mod(3600)
    val hue = h / 10f
    val top = Color.hsv(hue, 0.25f, 0.45f)
    val bottom = Color.hsv(hue, 0.35f, 0.25f)
    Box(
        modifier
            .size(size, size * 1.4f)
            .clip(RoundedCornerShape(6.dp))
            .background(Brush.linearGradient(listOf(top, bottom))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letter,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = (size.value * 0.5f).sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Serif,
        )
    }
}

/** Строка книги с прогрессом. */
@Composable
fun BookProgressRow(book: Book, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        BookCoverGlyph(book.displayTitle)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(book.displayTitle, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (book.author.isNotEmpty()) {
                Text(book.author, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (book.progress >= 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        progress = { book.progress.coerceAtMost(100) / 100f },
                        modifier = Modifier.weight(1f),
                    )
                    Text("${book.progress}%", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text(stringResource(R.string.common_not_started), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/** Пустое состояние экрана: значок, заголовок, пояснение и действие. */
@Composable
fun EmptyState(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    message: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.size(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (message != null) {
            Spacer(Modifier.size(6.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.size(16.dp))
            action()
        }
    }
}

/** Горизонтальная лента чипов: подписи не обрезаются, лента прокручивается. */
@Composable
fun ChipRow(modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
            content: @Composable () -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** Чип фильтра — кнопка с состоянием «выбран» для TalkBack. */
@Composable
fun FolioFilterChip(title: String, selected: Boolean, onClick: () -> Unit, icon: ImageVector? = null) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(title, maxLines = 1) },
        leadingIcon = icon?.let { { Icon(it, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) } },
    )
}

/** Разделитель-заголовок группы в списках. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    Text(
        text.uppercase(locale),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp).semantics { heading() },
    )
}

