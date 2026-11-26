package com.rustamguloff.foliolink.ui.screensaver

import android.content.res.Resources
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.app.AppModel
import com.rustamguloff.foliolink.core.imaging.DitherMethod
import com.rustamguloff.foliolink.core.imaging.ToneAdjustments
import com.rustamguloff.foliolink.core.model.BookStatus
import com.rustamguloff.foliolink.core.model.Highlight
import com.rustamguloff.foliolink.core.stats.StatsEngine
import com.rustamguloff.foliolink.ui.common.Card
import com.rustamguloff.foliolink.ui.common.ChipRow
import com.rustamguloff.foliolink.ui.common.FolioFilterChip
import com.rustamguloff.foliolink.ui.common.FolioTopBar
import com.rustamguloff.foliolink.ui.common.Fmt
import com.rustamguloff.foliolink.ui.common.LocalAppModel
import com.rustamguloff.foliolink.ui.common.SyncButton
import com.rustamguloff.foliolink.ui.screenshots.ShareFiles
import com.rustamguloff.foliolink.ui.screenshots.ZoomableBitmap
import com.rustamguloff.foliolink.ui.shell.Dest
import com.rustamguloff.foliolink.ui.shell.LocalRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// Студия заставок (порт ScreensaverStudioView.swift): шаблон → 480×800 → серое → тон →
// дизеринг → BMP 24 бит; предпросмотр показывает ровно пиксели читалки.

/** Настройки по умолчанию на языке интерфейса. */
private fun defaultConfig(res: Resources, dither: DitherMethod) = ScreensaverConfig(
    dither = dither,
    cardHeadline = res.getString(R.string.screensaver_default_card_headline),
    cardNote = res.getString(R.string.screensaver_default_card_note),
    quoteText = res.getString(R.string.screensaver_default_quote),
    quoteAuthor = res.getString(R.string.screensaver_default_quote_author),
    posterText = res.getString(R.string.screensaver_default_poster),
    calendarTitle = res.getString(R.string.screensaver_default_calendar_title),
)

/** Данные календарного постера из AppModel (как ScreensaverRenderer.calendarData в iOS). */
private fun calendarData(model: AppModel): CalendarPosterData {
    val snap = model.snapshot.value
    val daily = StatsEngine.dailySeconds(model.sessions.value, snap.stats?.days.orEmpty())
    val (current, longest) = model.streaks
    return CalendarPosterData(
        columns = StatsEngine.heatmap(daily, LocalDate.now(), CalendarLayout.WEEKS),
        streak = current,
        longest = longest,
        hours = Fmt.hours(maxOf(snap.stats?.totals?.secs ?: 0, daily.values.sum())),
        books = snap.books.count { it.bookStatus == BookStatus.DONE },
        todayMinutes = model.todaySeconds / 60,
        goal = model.goalMinutes.value,
    )
}

private fun canvasTexts(res: Resources, cal: CalendarPosterData, locale: Locale) = CanvasTexts(
    choosePhoto = res.getString(R.string.screensaver_canvas_choose_photo),
    namePlaceholder = res.getString(R.string.screensaver_canvas_name),
    phonePlaceholder = res.getString(R.string.screensaver_canvas_phone),
    minPerDay = res.getString(R.string.screensaver_canvas_min_per_day),
    streak = res.getString(R.string.screensaver_canvas_streak),
    record = res.getString(R.string.screensaver_canvas_record),
    hours = res.getString(R.string.screensaver_canvas_hours),
    books = res.getString(R.string.screensaver_canvas_books),
    today = res.getString(R.string.screensaver_canvas_today, cal.todayMinutes, cal.goal),
    date = LocalDate.now().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)),
)

/** Имя файла с ".bmp" на конце. */
internal fun bmpName(fileName: String): String {
    val t = fileName.trim()
    return if (t.lowercase().endsWith(".bmp")) t else "$t.bmp"
}

@Composable
fun ScreensaverStudioScreen() {
    val model = LocalAppModel.current
    val router = LocalRouter.current
    val context = LocalContext.current
    val res = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val scope = rememberCoroutineScope()
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val sessions by model.sessions.collectAsStateWithLifecycle()
    val goal by model.goalMinutes.collectAsStateWithLifecycle()
    val connection by model.connection.collectAsStateWithLifecycle()
    val connected = connection == AppModel.Connection.Connected

    var config by remember {
        mutableStateOf(defaultConfig(res, ScreensaverRenderer.ditherFrom(model.settings.ditherMethod)))
    }
    var fileName by remember { mutableStateOf(AppModel.defaultSleepName(config.template.raw)) }
    var result by remember { mutableStateOf<ScreensaverRenderer.Result?>(null) }
    var rendering by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    var pickHighlight by remember { mutableStateOf(false) }

    val calendar = remember(snapshot, sessions, goal) { calendarData(model) }
    val texts = remember(calendar, locale) { canvasTexts(res, calendar, locale) }

    // Как в iOS: цитата по умолчанию заменяется случайной своей, если они есть.
    LaunchedEffect(Unit) {
        val default = res.getString(R.string.screensaver_default_quote)
        val h = model.snapshot.value.highlights.randomOrNull()
        if (h != null && config.template == ScreensaverTemplate.QUOTE && config.quoteText == default) {
            config = config.copy(quoteText = h.text, quoteAuthor = h.title)
        }
    }

    // Перерисовка с задержкой 150 мс (ползунки); сама отрисовка — в фоне.
    LaunchedEffect(config, calendar, texts) {
        delay(150)
        rendering = true
        try {
            result = withContext(Dispatchers.Default) { ScreensaverRenderer.render(config, calendar, texts) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            model.show(e)
        } finally {
            rendering = false
        }
    }

    fun setTemplate(t: ScreensaverTemplate) {
        if (t == config.template) return
        config = config.copy(template = t)
        fileName = AppModel.defaultSleepName(t.raw)
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                SleepImageDecoder.read(context, uri)?.let { SleepImageDecoder.decodeBitmap(it) }
            }
            if (bmp == null) model.show(res.getString(R.string.screensaver_photo_unreadable), AppModel.Banner.Kind.ERROR)
            else config = config.copy(photo = bmp, zoom = 1.0, offsetX = 0.0, offsetY = 0.0)
        }
    }
    val pickCardPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                SleepImageDecoder.read(context, uri)?.let { SleepImageDecoder.decodeBitmap(it, maxSide = 600) }
            }
            if (bmp == null) model.show(res.getString(R.string.screensaver_photo_unreadable), AppModel.Banner.Kind.ERROR)
            else config = config.copy(cardPhoto = bmp)
        }
    }
    val imagesOnly = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    Scaffold(
        topBar = { FolioTopBar(stringResource(R.string.screensaver_title)) { SyncButton() } },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Предпросмотр
            Preview(result, showOriginal, rendering, onZoom = { zoomed = true })
            SwitchRow(stringResource(R.string.screensaver_show_original), showOriginal) { showOriginal = it }

            // Шаблон
            Card(title = stringResource(R.string.screensaver_section_template)) {
                ChipRow(contentPadding = PaddingValues(0.dp)) {
                    ScreensaverTemplate.entries.forEach { t ->
                        FolioFilterChip(stringResource(t.titleRes), config.template == t, { setTemplate(t) }, t.icon)
                    }
                }
                when (config.template) {
                    ScreensaverTemplate.PHOTO -> PhotoFields(config, { config = it }) { pickPhoto.launch(imagesOnly) }
                    ScreensaverTemplate.ID_CARD -> CardFields(config, { config = it }) { pickCardPhoto.launch(imagesOnly) }
                    ScreensaverTemplate.QUOTE -> QuoteFields(
                        config, { config = it }, hasHighlights = snapshot.highlights.isNotEmpty(),
                        onPick = { pickHighlight = true },
                        onRandom = {
                            snapshot.highlights.randomOrNull()?.let { config = config.copy(quoteText = it.text, quoteAuthor = it.title) }
                        },
                    )
                    ScreensaverTemplate.POSTER -> PosterFields(config) { config = it }
                    ScreensaverTemplate.CALENDAR -> CalendarFields(config) { config = it }
                }
            }

            // Экран читалки: дизеринг и тон
            Card(title = stringResource(R.string.screensaver_section_screen)) {
                Text(stringResource(R.string.screensaver_dither), style = MaterialTheme.typography.labelLarge)
                ChipRow(contentPadding = PaddingValues(0.dp)) {
                    DitherMethod.entries.forEach { m ->
                        FolioFilterChip(stringResource(m.titleRes), config.dither == m, { config = config.copy(dither = m) })
                    }
                }
                val tone = config.tone
                LabeledSlider(stringResource(R.string.screensaver_brightness), tone.brightness, -0.5..0.5) {
                    config = config.copy(tone = tone.copy(brightness = it))
                }
                LabeledSlider(stringResource(R.string.screensaver_contrast), tone.contrast, 0.5..2.0) {
                    config = config.copy(tone = tone.copy(contrast = it))
                }
                LabeledSlider(stringResource(R.string.screensaver_gamma), tone.gamma, 0.5..2.2) {
                    config = config.copy(tone = tone.copy(gamma = it))
                }
                SwitchRow(stringResource(R.string.screensaver_invert), tone.invert) {
                    config = config.copy(tone = tone.copy(invert = it))
                }
                TextButton(onClick = { config = config.copy(tone = ToneAdjustments()) }, enabled = !tone.isIdentity) {
                    Text(stringResource(R.string.screensaver_reset_tone))
                }
                Footnote(stringResource(R.string.screensaver_screen_footer))
            }

            // Загрузка
            Card {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text(stringResource(R.string.screensaver_file_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val r = result ?: return@Button
                        scope.launch {
                            uploading = true
                            try {
                                if (model.uploadSleepImage(r.bmp, bmpName(fileName))) {
                                    fileName = AppModel.defaultSleepName(config.template.raw)
                                }
                            } finally {
                                uploading = false
                            }
                        }
                    },
                    enabled = !uploading && result != null && fileName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (uploading) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(Icons.Outlined.Upload, contentDescription = null, Modifier.size(18.dp))
                    }
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(if (connected) R.string.screensaver_upload else R.string.screensaver_enqueue))
                }
                if (!connected) Footnote(stringResource(R.string.screensaver_offline_hint))
                val shareTitle = stringResource(R.string.screensaver_save_bmp)
                OutlinedButton(
                    onClick = {
                        val r = result ?: return@OutlinedButton
                        scope.launch {
                            val file = ShareFiles.write(context, bmpName(fileName), r.bmp)
                            ShareFiles.share(context, file, "image/bmp", shareTitle)
                        }
                    },
                    enabled = result != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Description, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(shareTitle)
                }
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.screensaver_open_sleep)) },
                    leadingContent = { Icon(Icons.Outlined.Wallpaper, contentDescription = null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(role = Role.Button) { router.open(Dest.SLEEP_IMAGES) },
                )
                Footnote(
                    stringResource(
                        if (model.sleepSelectionSupported) R.string.screensaver_upload_footer_select
                        else R.string.screensaver_upload_footer_custom,
                    ),
                )
            }
        }
    }

    val r = result
    if (zoomed && r != null) {
        Dialog(onDismissRequest = { zoomed = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                ZoomableBitmap(
                    (if (showOriginal) r.original else r.preview).asImageBitmap(),
                    contentDescription = stringResource(R.string.screensaver_preview),
                    modifier = Modifier.fillMaxSize(),
                )
                TextButton(onClick = { zoomed = false }, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                    Text(stringResource(R.string.common_done), color = Color.White)
                }
            }
        }
    }

    if (pickHighlight) {
        HighlightPicker(
            highlights = snapshot.highlights.take(40),
            onDismiss = { pickHighlight = false },
        ) { h ->
            pickHighlight = false
            val author = model.book(h.book)?.author.orEmpty()
            config = config.copy(
                quoteText = h.text,
                quoteAuthor = if (author.isEmpty()) h.title else res.getString(R.string.screensaver_quote_author_format, author, h.title),
            )
        }
    }
}

// region Части экрана

private val ScreensaverTemplate.titleRes: Int
    get() = when (this) {
        ScreensaverTemplate.PHOTO -> R.string.screensaver_template_photo
        ScreensaverTemplate.ID_CARD -> R.string.screensaver_template_card
        ScreensaverTemplate.QUOTE -> R.string.screensaver_template_quote
        ScreensaverTemplate.POSTER -> R.string.screensaver_template_poster
        ScreensaverTemplate.CALENDAR -> R.string.screensaver_template_calendar
    }

private val ScreensaverTemplate.icon: ImageVector
    get() = when (this) {
        ScreensaverTemplate.PHOTO -> Icons.Outlined.Image
        ScreensaverTemplate.ID_CARD -> Icons.Outlined.Badge
        ScreensaverTemplate.QUOTE -> Icons.Outlined.FormatQuote
        ScreensaverTemplate.POSTER -> Icons.Outlined.TextFields
        ScreensaverTemplate.CALENDAR -> Icons.Outlined.CalendarMonth
    }

private val PosterFont.titleRes: Int
    get() = when (this) {
        PosterFont.SERIF -> R.string.screensaver_font_serif
        PosterFont.SANS -> R.string.screensaver_font_sans
        PosterFont.MONO -> R.string.screensaver_font_mono
        PosterFont.ROUNDED -> R.string.screensaver_font_rounded
    }

internal val DitherMethod.titleRes: Int
    get() = when (this) {
        DitherMethod.FLOYD_STEINBERG -> R.string.screensaver_dither_floyd
        DitherMethod.ATKINSON -> R.string.screensaver_dither_atkinson
        DitherMethod.BAYER -> R.string.screensaver_dither_bayer
        DitherMethod.NONE -> R.string.screensaver_dither_none
    }

/** Предпросмотр: ровно пиксели читалки, без сглаживания; касание — во весь экран. */
@Composable
private fun Preview(result: ScreensaverRenderer.Result?, showOriginal: Boolean, rendering: Boolean, onZoom: () -> Unit) {
    val description = stringResource(R.string.screensaver_preview)
    val zoomLabel = stringResource(R.string.screensaver_preview_zoom)
    Box(
        Modifier
            .fillMaxWidth()
            .height(420.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF333333))
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = result?.let { if (showOriginal) it.original else it.preview }
        Box(
            Modifier
                .fillMaxHeight()
                .aspectRatio(PanelGeometry.WIDTH.toFloat() / PanelGeometry.HEIGHT)
                .background(Color(0xFFBBBBBB))
                .clickable(enabled = bmp != null, onClickLabel = zoomLabel, role = Role.Image, onClick = onZoom)
                .semantics { contentDescription = description },
        ) {
            if (bmp != null) {
                Image(
                    bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (rendering) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface), Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun Footnote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun SwitchRow(title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun LabeledSlider(title: String, value: Double, range: ClosedFloatingPointRange<Double>, onChange: (Double) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(String.format(LocalConfiguration.current.locales[0], "%.2f", value), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}

@Composable
private fun Field(value: String, label: String, onChange: (String) -> Unit, minLines: Int = 1, maxLines: Int = 1,
                  keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        singleLine = maxLines == 1, minLines = minLines, maxLines = maxLines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FillFitRow(fill: Boolean, onChange: (Boolean) -> Unit) {
    Text(stringResource(R.string.screensaver_scale), style = MaterialTheme.typography.labelLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(fill, { onChange(true) }, SegmentedButtonDefaults.itemShape(0, 2)) {
            Text(stringResource(R.string.screensaver_fill))
        }
        SegmentedButton(!fill, { onChange(false) }, SegmentedButtonDefaults.itemShape(1, 2)) {
            Text(stringResource(R.string.screensaver_fit))
        }
    }
}

@Composable
private fun ColumnScope.PhotoFields(c: ScreensaverConfig, set: (ScreensaverConfig) -> Unit, pick: () -> Unit) {
    OutlinedButton(onClick = pick, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(stringResource(if (c.photo != null) R.string.screensaver_another_photo else R.string.screensaver_choose_photo))
    }
    FillFitRow(c.photoFill) { set(c.copy(photoFill = it)) }
    LabeledSlider(stringResource(R.string.screensaver_zoom), c.zoom, 1.0..3.0) { set(c.copy(zoom = it)) }
    LabeledSlider(stringResource(R.string.screensaver_offset_x), c.offsetX, -1.0..1.0) { set(c.copy(offsetX = it)) }
    LabeledSlider(stringResource(R.string.screensaver_offset_y), c.offsetY, -1.0..1.0) { set(c.copy(offsetY = it)) }
    Field(c.photoCaption, stringResource(R.string.screensaver_caption), { set(c.copy(photoCaption = it)) }, maxLines = 3)
}

@Composable
private fun ColumnScope.CardFields(c: ScreensaverConfig, set: (ScreensaverConfig) -> Unit, pick: () -> Unit) {
    Field(c.cardHeadline, stringResource(R.string.screensaver_field_title), { set(c.copy(cardHeadline = it)) }, maxLines = 3)
    Field(c.cardName, stringResource(R.string.screensaver_card_name), { set(c.copy(cardName = it)) })
    Field(c.cardPhone, stringResource(R.string.screensaver_card_phone), { set(c.copy(cardPhone = it)) }, keyboard = KeyboardType.Phone)
    Field(c.cardEmail, stringResource(R.string.screensaver_card_email), { set(c.copy(cardEmail = it)) }, keyboard = KeyboardType.Email)
    Field(c.cardNote, stringResource(R.string.screensaver_card_note), { set(c.copy(cardNote = it)) }, maxLines = 3)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = pick) {
            Icon(Icons.Outlined.AddAPhoto, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(if (c.cardPhoto != null) R.string.screensaver_card_replace_photo else R.string.screensaver_card_add_photo))
        }
        if (c.cardPhoto != null) {
            TextButton(onClick = { set(c.copy(cardPhoto = null)) }) {
                Text(stringResource(R.string.screensaver_card_remove_photo), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ColumnScope.QuoteFields(
    c: ScreensaverConfig,
    set: (ScreensaverConfig) -> Unit,
    hasHighlights: Boolean,
    onPick: () -> Unit,
    onRandom: () -> Unit,
) {
    Field(c.quoteText, stringResource(R.string.screensaver_template_quote), { set(c.copy(quoteText = it)) }, minLines = 3, maxLines = 8)
    Field(c.quoteAuthor, stringResource(R.string.screensaver_quote_author), { set(c.copy(quoteAuthor = it)) })
    if (hasHighlights) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPick) {
                Icon(Icons.Outlined.BorderColor, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.screensaver_quote_pick), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = onRandom) {
                Icon(Icons.Outlined.Shuffle, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.screensaver_quote_random), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ColumnScope.PosterFields(c: ScreensaverConfig, set: (ScreensaverConfig) -> Unit) {
    Field(c.posterText, stringResource(R.string.screensaver_poster_text), { set(c.copy(posterText = it)) }, minLines = 2, maxLines = 8)
    Field(c.posterSubtitle, stringResource(R.string.screensaver_poster_subtitle), { set(c.copy(posterSubtitle = it)) })
    Text(stringResource(R.string.screensaver_font), style = MaterialTheme.typography.labelLarge)
    ChipRow(contentPadding = PaddingValues(0.dp)) {
        PosterFont.entries.forEach { f ->
            FolioFilterChip(stringResource(f.titleRes), c.posterFont == f, { set(c.copy(posterFont = f)) })
        }
    }
    LabeledSlider(stringResource(R.string.screensaver_size), c.posterSize, 28.0..120.0) { set(c.copy(posterSize = it)) }
    SwitchRow(stringResource(R.string.screensaver_centered), c.posterCentered) { set(c.copy(posterCentered = it)) }
    SwitchRow(stringResource(R.string.screensaver_inverted), c.posterInverted) { set(c.copy(posterInverted = it)) }
}

@Composable
private fun ColumnScope.CalendarFields(c: ScreensaverConfig, set: (ScreensaverConfig) -> Unit) {
    Field(c.calendarTitle, stringResource(R.string.screensaver_field_title), { set(c.copy(calendarTitle = it)) })
    SwitchRow(stringResource(R.string.screensaver_inverted), c.calendarInverted) { set(c.copy(calendarInverted = it)) }
    Footnote(stringResource(R.string.screensaver_calendar_hint))
}

/** «Взять из цитат»: первые 40 выделений; касание подставляет текст и автора. */
@Composable
private fun HighlightPicker(highlights: List<Highlight>, onDismiss: () -> Unit, onPick: (Highlight) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.screensaver_quote_pick), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onDismiss) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.common_close))
            }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(highlights, key = { it.key }) { h ->
                ListItem(
                    headlineContent = { Text(h.text, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(h.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Icon(Icons.Outlined.FormatQuote, contentDescription = null) },
                    modifier = Modifier.clickable(role = Role.Button) { onPick(h) },
                )
            }
        }
    }
}

// endregion
