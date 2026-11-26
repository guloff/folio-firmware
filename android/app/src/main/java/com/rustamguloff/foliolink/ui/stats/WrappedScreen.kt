package com.rustamguloff.foliolink.ui.stats

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.core.model.Book
import com.rustamguloff.foliolink.core.stats.StatsEngine
import com.rustamguloff.foliolink.ui.common.Fmt
import com.rustamguloff.foliolink.ui.common.LocalAppModel
import com.rustamguloff.foliolink.ui.shell.LocalRouter
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale

// «Итоги года» — перенос iOS Features/Stats/WrappedView.swift.

private fun Long.color() = Color(this.toInt())

/**
 * Итоги года: полноэкранные карточки с перелистыванием (данные — [StatsEngine.wrapped]),
 * выбор года, «Поделиться» картинкой 1080×1920.
 */
@Composable
fun WrappedScreen() {
    val model = LocalAppModel.current
    val router = LocalRouter.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val sessions by model.sessions.collectAsStateWithLifecycle()
    val daily = rememberDaily(model)
    val today = LocalDate.now()
    var year by rememberSaveable { mutableIntStateOf(today.year) }
    val data = remember(year, sessions, daily, snapshot) {
        StatsEngine.wrapped(year, today, sessions, daily, snapshot.books, snapshot.highlights, snapshot.vocab)
    }
    val years = remember(daily) { StatsEngine.wrappedYears(daily, today) }
    val cards = remember(data) { WrappedCard.cards(data) }
    val pager = rememberPagerState { cards.size }
    val current = cards[pager.currentPage.coerceIn(0, cards.lastIndex)]
    val top by animateColorAsState(current.top.color(), tween(350), label = "top")
    val bottom by animateColorAsState(current.bottom.color(), tween(350), label = "bottom")
    var sharing by remember { mutableStateOf<WrappedCard?>(null) }

    LaunchedEffect(year) { pager.scrollToPage(0) }
    LightStatusBarIcons()

    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(top, bottom)))) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val minHeight = maxHeight
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = minHeight)
                                .statusBarsPadding()
                                .padding(start = 28.dp, end = 28.dp, top = 72.dp, bottom = 56.dp),
                            verticalArrangement = if (cards[i].centered) Arrangement.spacedBy(18.dp, Alignment.CenterVertically)
                            else Arrangement.spacedBy(18.dp),
                        ) {
                            WrappedCardBody(cards[i], data, snapshot.books)
                        }
                    }
                }
            }
            WrappedTopBar(
                year = year,
                years = years,
                sharing = sharing != null,
                onClose = { router.back() },
                onYear = { year = it },
                onShare = { sharing = current },
            )
            PageDots(pager.currentPage, cards.size, Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp))
        }

        sharing?.let { card ->
            // 360×640 dp в масштабе 3 → 1080×1920 px (формат историй 9:16).
            OffscreenCapture(width = 360.dp, height = 640.dp, onCaptured = { image ->
                sharing = null
                if (image != null) scope.launch { ShareImage.share(context, image, WrappedLogic.posterFileName(data.year, card)) }
            }) {
                WrappedPoster(card, data, snapshot.books)
            }
        }
    }
}

/** Карточка для «Поделиться»: год сверху, марка приложения снизу. */
@Composable
private fun WrappedPoster(card: WrappedCard, data: StatsEngine.Wrapped, books: List<Book>) {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(card.top.color(), card.bottom.color())))
                .padding(horizontal = 28.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (card.posterHeader) {
                Text(stringResource(R.string.stats_wrapped_year_title, data.year.toString()),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.7f))
            }
            Column(
                Modifier.weight(1f),
                verticalArrangement = if (card.centered) Arrangement.spacedBy(18.dp, Alignment.CenterVertically)
                else Arrangement.spacedBy(18.dp),
            ) { WrappedCardBody(card, data, books) }
            Text(stringResource(R.string.stats_poster_brand), Modifier.align(Alignment.End),
                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.6f))
        }
    }
}

/** Содержимое карточки (в колонке с отступами 18 dp). */
@Composable
private fun ColumnScope.WrappedCardBody(card: WrappedCard, data: StatsEngine.Wrapped, books: List<Book>) {
    val context = LocalContext.current
    val review = data.review
    val dim = Color.White.copy(alpha = 0.85f)
    when (card) {
        WrappedCard.INTRO -> {
            Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null, Modifier.size(48.dp))
            BigText(data.year.toString(), 96.sp)
            Text(stringResource(R.string.stats_wrapped_intro_title), style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.stats_wrapped_intro_hint), style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.75f))
        }
        WrappedCard.TIME -> {
            Caption(stringResource(R.string.stats_wrapped_time_caption), Icons.Outlined.Schedule)
            val minutes = review.totalSeconds / 60
            if (review.totalSeconds < 3600) {
                Big("$minutes", pluralStringResource(R.plurals.stats_noun_minute, minutes))
            } else {
                Big(Fmt.hours(review.totalSeconds), stringResource(R.string.stats_hours))
            }
            Line(pluralStringResource(R.plurals.plural_session, review.sessions, review.sessions) + " · " +
                pluralStringResource(R.plurals.plural_page, review.totalPages, review.totalPages))
            if (review.activeDays > 0) {
                Line(stringResource(R.string.stats_wrapped_avg_per_day,
                    Fmt.duration(context, WrappedLogic.averagePerReadingDay(review))), alpha = 0.8f)
            }
        }
        WrappedCard.DAYS -> {
            Caption(stringResource(R.string.stats_wrapped_days_caption), Icons.Outlined.CalendarMonth)
            Big("${review.activeDays}", pluralStringResource(R.plurals.stats_noun_day, review.activeDays))
            Line(stringResource(R.string.stats_wrapped_days_share, data.daysInPeriod,
                WrappedLogic.percent(review.activeDays, data.daysInPeriod)))
            RatioBar(review.activeDays.toFloat() / maxOf(data.daysInPeriod, 1))
            review.bestDayKey?.let(StatsEngine::dateFromKey)?.let { best ->
                Text(stringResource(R.string.stats_best_day, StatsFmt.longDate(best), Fmt.duration(context, review.bestDaySeconds)),
                    style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.8f))
            }
        }
        WrappedCard.STREAK -> {
            Caption(stringResource(R.string.stats_wrapped_streak_caption), Icons.Outlined.LocalFireDepartment)
            Big("${review.longestStreak}", pluralStringResource(R.plurals.stats_noun_streak_day, review.longestStreak))
            Line(stringResource(R.string.stats_wrapped_streak_text))
        }
        WrappedCard.BOOKS -> {
            Caption(stringResource(R.string.stats_wrapped_books_caption), Icons.AutoMirrored.Outlined.LibraryBooks)
            data.topBooks.forEachIndexed { i, b ->
                Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("${i + 1}", Modifier.width(34.dp), fontSize = 40.sp, fontWeight = FontWeight.Black,
                        color = Color.White.copy(alpha = 0.6f))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(StatsLogic.bookTitle(b, books), style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        books.firstOrNull { it.path == b.path }?.author?.takeIf { it.isNotEmpty() }?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.75f))
                        }
                        Text(Fmt.duration(context, b.seconds), style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold, color = dim)
                    }
                }
            }
            if (review.booksFinished > 0) {
                Line(stringResource(R.string.stats_wrapped_finished,
                    pluralStringResource(R.plurals.plural_book, review.booksFinished, review.booksFinished)))
            }
        }
        WrappedCard.HOUR -> {
            Caption(stringResource(R.string.stats_wrapped_hour_caption), Icons.Outlined.Bedtime)
            review.favoriteHour?.let { Big(String.format(Locale.ROOT, "%02d:00", it), stringResource(R.string.stats_wrapped_fav_hour)) }
            HourBars(data.hours, review.favoriteHour)
            data.bestMonth?.let { Line(stringResource(R.string.stats_wrapped_best_month, StatsFmt.monthName(it))) }
        }
        WrappedCard.HIGHLIGHTS -> {
            Caption(stringResource(R.string.stats_wrapped_highlights_caption), Icons.Outlined.BorderColor)
            Big("${data.highlights}", pluralStringResource(R.plurals.stats_noun_highlight, data.highlights))
            if (data.notes > 0) Line(stringResource(R.string.stats_wrapped_with_notes, data.notes))
            data.quote?.let { q ->
                Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.6f)))
                    Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.stats_wrapped_quote, q.text), style = MaterialTheme.typography.titleLarge,
                            fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, maxLines = 6,
                            overflow = TextOverflow.Ellipsis)
                        if (q.title.isNotEmpty()) {
                            Text("— ${q.title}", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.75f))
                        }
                    }
                }
            }
        }
        WrappedCard.WORDS -> {
            Caption(stringResource(R.string.stats_wrapped_words_caption), Icons.Outlined.Translate)
            Big("${data.wordsLookedUp ?: 0}", stringResource(R.string.stats_wrapped_words_label))
            Line(stringResource(R.string.stats_wrapped_words_text))
        }
        WrappedCard.FINALE -> {
            Text(stringResource(R.string.stats_wrapped_year_title, data.year.toString()),
                Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
            SmallRow(
                { Small(Fmt.hours(review.totalSeconds), stringResource(R.string.stats_hours)) },
                { Small("${review.activeDays}", pluralStringResource(R.plurals.stats_noun_day, review.activeDays)) },
            )
            SmallRow(
                { Small("${review.longestStreak}", pluralStringResource(R.plurals.stats_noun_streak_day, review.longestStreak)) },
                { Small("${review.booksFinished}", stringResource(R.string.stats_books_finished)) },
            )
            SmallRow(
                { Small("${review.totalPages}", pluralStringResource(R.plurals.stats_noun_page, review.totalPages)) },
                { Small("${data.highlights}", pluralStringResource(R.plurals.stats_noun_highlight, data.highlights)) },
            )
            data.topBooks.firstOrNull()?.let { top ->
                HorizontalDivider(color = Color.White.copy(alpha = 0.4f))
                Text(
                    topBookText(stringResource(R.string.stats_top_book), StatsLogic.bookTitle(top, books),
                        Fmt.duration(context, top.seconds)),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(stringResource(R.string.stats_wrapped_share_hint), style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f))
        }
        WrappedCard.EMPTY -> {
            Icon(Icons.Outlined.Book, contentDescription = null, Modifier.size(48.dp))
            Text(stringResource(R.string.stats_wrapped_empty_title, data.year.toString()),
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.stats_wrapped_empty_text), style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun Caption(text: String, icon: ImageVector) {
    Row(
        Modifier.semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, Modifier.size(22.dp), tint = Color.White.copy(alpha = 0.85f))
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.85f))
    }
}

/** Крупное число в одну строку: шрифт ужимается, чтобы число влезло. */
@Composable
private fun BigText(value: String, size: TextUnit) {
    BasicText(
        value,
        style = TextStyle(color = Color.White, fontSize = size, fontWeight = FontWeight.Black),
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 28.sp, maxFontSize = size, stepSize = 4.sp),
    )
}

@Composable
private fun Big(value: String, label: String) {
    Column(Modifier.clearAndSetSemantics { contentDescription = "$value $label" }) {
        BigText(value, 88.sp)
        Text(label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Small(value: String, label: String) {
    Column(Modifier.clearAndSetSemantics { contentDescription = "$value $label" }) {
        BasicText(
            value,
            style = TextStyle(color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 34.sp, stepSize = 2.sp),
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
    }
}

@Composable
private fun SmallRow(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Box(Modifier.weight(1f)) { first() }
        Box(Modifier.weight(1f)) { second() }
    }
}

@Composable
private fun Line(text: String, alpha: Float = 0.85f) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        color = Color.White.copy(alpha = alpha))
}

@Composable
private fun RatioBar(fraction: Float) {
    Box(
        Modifier.fillMaxWidth().height(10.dp).background(Color.White.copy(alpha = 0.2f), CircleShape)
            .clearAndSetSemantics { },
    ) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(10.dp).background(Color.White, CircleShape))
    }
}

@Composable
private fun HourBars(hours: List<Int>, favorite: Int?) {
    val heights = remember(hours) { WrappedLogic.hourHeights(hours) }
    Column(Modifier.clearAndSetSemantics { }) {
        Row(Modifier.fillMaxWidth().height(90.dp), horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom) {
            heights.forEachIndexed { h, f ->
                Box(
                    Modifier.weight(1f).fillMaxHeight(f)
                        .background(Color.White.copy(alpha = if (h == favorite) 1f else 0.45f), RoundedCornerShape(50)),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            listOf("0", "6", "12", "18", "23").forEachIndexed { i, label ->
                if (i > 0) Spacer(Modifier.weight(1f))
                Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun WrappedTopBar(
    year: Int,
    years: List<Int>,
    sharing: Boolean,
    onClose: () -> Unit,
    onYear: (Int) -> Unit,
    onShare: () -> Unit,
) {
    val circle = IconButtonDefaults.iconButtonColors(containerColor = Color.White.copy(alpha = 0.15f), contentColor = Color.White)
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconButton(onClick = onClose, colors = circle) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.common_close))
        }
        Spacer(Modifier.weight(1f))
        if (years.size > 1) {
            var open by remember { mutableStateOf(false) }
            Box {
                val label = stringResource(R.string.stats_wrapped_choose_year)
                Surface(
                    onClick = { open = true },
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.15f),
                    contentColor = Color.White,
                    modifier = Modifier.height(40.dp).semantics { contentDescription = "$label, $year" },
                ) {
                    Row(Modifier.padding(start = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(year.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
                    }
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    years.forEach { y ->
                        DropdownMenuItem(text = { Text(y.toString()) }, onClick = { onYear(y); open = false })
                    }
                }
            }
        }
        IconButton(onClick = onShare, enabled = !sharing, colors = circle) {
            if (sharing) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.IosShare, contentDescription = stringResource(R.string.stats_share_image))
            }
        }
    }
}

@Composable
private fun PageDots(current: Int, count: Int, modifier: Modifier = Modifier) {
    if (count < 2) return
    val label = stringResource(R.string.stats_wrapped_page_a11y, current + 1, count)
    Row(
        modifier
            .background(Color.Black.copy(alpha = 0.2f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clearAndSetSemantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(count) { i ->
            Box(Modifier.size(7.dp).background(Color.White.copy(alpha = if (i == current) 1f else 0.4f), CircleShape))
        }
    }
}

/** Тёмный фон карточек: светлые значки строки состояния, пока экран открыт. */
@Composable
private fun LightStatusBarIcons() {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        val previous = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = false
        onDispose { controller.isAppearanceLightStatusBars = previous }
    }
}
