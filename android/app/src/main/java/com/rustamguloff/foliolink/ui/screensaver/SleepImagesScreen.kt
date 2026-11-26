package com.rustamguloff.foliolink.ui.screensaver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.app.AppModel
import com.rustamguloff.foliolink.app.SleepChoice
import com.rustamguloff.foliolink.app.SleepRules
import com.rustamguloff.foliolink.core.model.RemoteFile
import com.rustamguloff.foliolink.core.model.SleepResponse
import com.rustamguloff.foliolink.core.store.SettingsProfile
import com.rustamguloff.foliolink.ui.common.Card
import com.rustamguloff.foliolink.ui.common.FolioTopBar
import com.rustamguloff.foliolink.ui.common.ImageCache
import com.rustamguloff.foliolink.ui.common.LocalAppModel
import com.rustamguloff.foliolink.ui.common.RemoteImage
import com.rustamguloff.foliolink.ui.common.RequiresConnection
import com.rustamguloff.foliolink.ui.common.SyncButton
import com.rustamguloff.foliolink.ui.theme.FolioGold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// «Экран сна» (порт SleepImagesView, SleepChoice.swift, SleepBatchUpload.swift): режим сна,
// «Заставка» (какая картинка показывается), пакетная загрузка, набор Folio, картинки на читалке.

private const val PREFS = "foliolink.ui"
private const val FIT_KEY = "sleepBatchFit"

private fun loadFit(context: Context): SleepBatch.Fit =
    if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(FIT_KEY, null) == "fit") SleepBatch.Fit.FIT
    else SleepBatch.Fit.FILL

private fun saveFit(context: Context, fit: SleepBatch.Fit) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
        putString(FIT_KEY, if (fit == SleepBatch.Fit.FIT) "fit" else "fill")
    }
}

/** Название режима сна: по номеру на языке приложения (читалка присылает на своём). */
private fun AppModel.localizedMode(name: String, index: Int): String =
    if (index in 0..10) sleepModeName(index) else name.ifEmpty { index.toString() }

@Composable
fun SleepImagesScreen() {
    Scaffold(
        topBar = { FolioTopBar(stringResource(R.string.screensaver_sleep_title)) { SyncButton() } },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            RequiresConnection { SleepImagesContent() }
        }
    }
}

@Composable
private fun SleepImagesContent() {
    val model = LocalAppModel.current
    val scope = rememberCoroutineScope()
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val sleep = snapshot.sleep
    val canSelect = model.sleepSelectionSupported
    var options by remember { mutableStateOf<List<String>>(emptyList()) }
    var refreshing by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showPack by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<RemoteFile?>(null) }

    LaunchedEffect(Unit) {
        model.refreshSleep()
        // Названия режимов и текущий режим — из GET /api/settings.
        val client = model.client ?: return@LaunchedEffect
        try {
            val setting = client.settings().firstOrNull { it.key == "sleepScreen" }
            if (setting != null) {
                options = setting.options
                SettingsProfile.intValue(setting.value)?.let { model.updateCachedSleepMode(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    val images = sleep?.images.orEmpty()
    val selected = if (canSelect) sleep?.selected.orEmpty() else ""

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                try { model.refreshSleep() } finally { refreshing = false }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "mode", span = { GridItemSpan(maxLineSpan) }) {
                ModeCard(sleep, options, canSelect, onChoose = { showPicker = true })
            }
            item(key = "batch", span = { GridItemSpan(maxLineSpan) }) {
                BatchCard(sleep, onPack = { showPack = true })
            }
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    stringResource(R.string.screensaver_pictures_on_reader),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                )
            }
            if (images.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Text(stringResource(R.string.screensaver_no_pictures), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(images, key = { it.id }) { file ->
                SleepThumb(
                    file = file,
                    isSelected = selected.isNotEmpty() && file.name == selected,
                    anySelected = selected.isNotEmpty(),
                    canSelect = canSelect,
                    onOpen = { preview = file },
                )
            }
            if (images.isNotEmpty()) {
                item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(if (canSelect) R.string.screensaver_pictures_footer_select else R.string.screensaver_pictures_footer_random),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showPicker) SleepChoicePicker(sleep, onDismiss = { showPicker = false })
    if (showPack) SleepPackSheet(onDismiss = { showPack = false })
    preview?.let { file ->
        SleepImagePreview(images.ifEmpty { listOf(file) }, start = file, onDismiss = { preview = null })
    }
}

// region Режим сна и «Заставка»

@Composable
private fun ModeCard(sleep: SleepResponse?, options: List<String>, canSelect: Boolean, onChoose: () -> Unit) {
    val model = LocalAppModel.current
    val mode = sleep?.mode ?: 0
    val names = options.ifEmpty { List(11) { "" } }
    var expanded by remember { mutableStateOf(false) }
    Card {
        Box {
            ListItem(
                headlineContent = { Text(stringResource(R.string.screensaver_sleep_title)) },
                supportingContent = { Text(model.localizedMode(names.getOrElse(mode) { "" }, mode)) },
                leadingContent = { Icon(Icons.Outlined.Bedtime, contentDescription = null) },
                trailingContent = { Icon(Icons.Outlined.ExpandMore, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable(role = Role.DropdownList) { expanded = true },
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                names.forEachIndexed { i, name ->
                    val title = model.localizedMode(name, i)
                    DropdownMenuItem(
                        text = { Text(title) },
                        onClick = {
                            expanded = false
                            if (i != mode) model.launch { setSleepMode(i, title) }
                        },
                        trailingIcon = if (i == mode) ({ Icon(Icons.Outlined.CheckCircle, contentDescription = null) }) else null,
                        modifier = Modifier.semantics { selected = i == mode },
                    )
                }
            }
        }
        if (canSelect) {
            SleepChoiceRow(sleep, onChoose)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.core_sleep_update_firmware), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(stringResource(R.string.screensaver_modes_footer), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Строка «Заставка»: текущий выбор с миниатюрой; открывает выбор. */
@Composable
private fun SleepChoiceRow(sleep: SleepResponse?, onClick: () -> Unit) {
    val model = LocalAppModel.current
    val choice = SleepChoice.current(sleep)
    val title = (choice as? SleepChoice.Image)?.fileName ?: stringResource(R.string.core_sleep_random)
    val hint = stringResource(R.string.screensaver_choice_hint)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClickLabel = hint, role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.height(64.dp).aspectRatio(0.6f)) {
            if (choice is SleepChoice.Image) {
                SleepThumbImage(choice.file(sleep), isSelected = false, corner = 5.dp)
            } else {
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(5.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Shuffle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(R.string.screensaver_choice), style = MaterialTheme.typography.titleSmall)
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            val mode = sleep?.mode
            if (mode != null && !SleepRules.modeShowsImages(mode)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Warning, contentDescription = null, Modifier.size(14.dp), tint = FolioGold)
                    Text(stringResource(R.string.screensaver_choice_mode_warning, model.sleepModeName(mode)),
                        style = MaterialTheme.typography.bodySmall, color = FolioGold)
                }
            }
        }
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}

/** Картинка заставки в пропорциях экрана (0.6) с отметкой выбора; null — ещё в очереди. */
@Composable
private fun SleepThumbImage(file: RemoteFile?, isSelected: Boolean, modifier: Modifier = Modifier, corner: Dp = 6.dp) {
    val shape = RoundedCornerShape(corner)
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(0.6f)
            .clip(shape)
            .border(if (isSelected) 3.dp else 1.dp, if (isSelected) accent else MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        if (file != null) {
            RemoteImage(file.fullPath, contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest), Alignment.Center) {
                Icon(Icons.Outlined.Image, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
            }
        }
        if (isSelected) {
            Icon(
                Icons.Filled.CheckCircle, contentDescription = null, tint = accent,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(CircleShape).background(Color.White),
            )
        }
    }
}

/** Картинка в сетке: касание — просмотр, долгое нажатие — меню «Показывать эту / случайные / Удалить». */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SleepThumb(file: RemoteFile, isSelected: Boolean, anySelected: Boolean, canSelect: Boolean, onOpen: () -> Unit) {
    val model = LocalAppModel.current
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val showThis = stringResource(R.string.screensaver_show_this)
    val showRandom = stringResource(R.string.screensaver_show_random)
    val delete = stringResource(R.string.screensaver_delete_from_reader)
    val shownState = stringResource(R.string.screensaver_shown_on_sleep)
    val openLabel = stringResource(R.string.screensaver_open_picture)
    Box {
        Column(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(onClickLabel = openLabel, role = Role.Button, onLongClick = { menu = true }, onClick = onOpen)
                .semantics(mergeDescendants = true) {
                    selected = isSelected
                    if (isSelected) stateDescription = shownState
                    customActions = buildList {
                        if (canSelect && !isSelected) add(CustomAccessibilityAction(showThis) { model.launch { chooseSleepImage(SleepChoice.Image(file.name)) }; true })
                        if (canSelect && anySelected) add(CustomAccessibilityAction(showRandom) { model.launch { chooseSleepImage(SleepChoice.Random) }; true })
                        add(CustomAccessibilityAction(delete) { confirmDelete = true; true })
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SleepThumbImage(file, isSelected)
            Text(file.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (canSelect && !isSelected) {
                DropdownMenuItem(
                    text = { Text(showThis) }, leadingIcon = { Icon(Icons.Outlined.CheckCircle, contentDescription = null) },
                    onClick = { menu = false; model.launch { chooseSleepImage(SleepChoice.Image(file.name)) } },
                )
            }
            if (canSelect && anySelected) {
                DropdownMenuItem(
                    text = { Text(showRandom) }, leadingIcon = { Icon(Icons.Outlined.Shuffle, contentDescription = null) },
                    onClick = { menu = false; model.launch { chooseSleepImage(SleepChoice.Random) } },
                )
            }
            DropdownMenuItem(
                text = { Text(delete, color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = { menu = false; confirmDelete = true },
            )
        }
    }
    if (confirmDelete) DeleteDialog(file, onDone = { confirmDelete = false })
}

@Composable
private fun DeleteDialog(file: RemoteFile, onDone: () -> Unit, afterDelete: () -> Unit = {}) {
    val model = LocalAppModel.current
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.screensaver_delete_confirm, file.name)) },
        confirmButton = {
            TextButton(onClick = {
                onDone()
                afterDelete()
                model.launch { if (deleteRemote(file)) ImageCache.evict(file.fullPath) }
            }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** Выбор: «Случайная», затем все картинки на читалке; касание выбирает и закрывает. */
@Composable
private fun SleepChoicePicker(sleep: SleepResponse?, onDismiss: () -> Unit) {
    val model = LocalAppModel.current
    val current = SleepChoice.current(sleep)
    val images = sleep?.images.orEmpty()
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun choose(c: SleepChoice) {
        onDismiss()
        model.launch { chooseSleepImage(c) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        SheetHeader(stringResource(R.string.screensaver_choice), onDismiss)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(100.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                val isRandom = current == SleepChoice.Random
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = if (isRandom) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                    modifier = Modifier.fillMaxWidth().semantics { selected = isRandom },
                    onClick = { choose(SleepChoice.Random) },
                ) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Outlined.Shuffle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.core_sleep_random), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.screensaver_choice_random_subtitle), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (isRandom) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.screensaver_pictures_on_reader), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 6.dp).semantics { heading() })
            }
            if (images.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(stringResource(R.string.screensaver_picker_empty), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(images, key = { it.id }) { file ->
                val isCurrent = current == SleepChoice.Image(file.name)
                Column(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button) { choose(SleepChoice.Image(file.name)) }
                        .semantics(mergeDescendants = true) { selected = isCurrent },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SleepThumbImage(file, isCurrent)
                    Text(file.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(title: String, onClose: () -> Unit, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        action()
        IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.common_close)) }
    }
}

/** Просмотр картинок на читалке во весь экран (листаются): показывать эту или удалить. */
@Composable
private fun SleepImagePreview(images: List<RemoteFile>, start: RemoteFile, onDismiss: () -> Unit) {
    val model = LocalAppModel.current
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val pager = rememberPagerState(initialPage = images.indexOfFirst { it.id == start.id }.coerceAtLeast(0)) { images.size }
    val current = images.getOrNull(pager.currentPage)
    var confirmDelete by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                SheetHeader(current?.name.orEmpty(), onDismiss)
                HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 28.dp),
                    pageSpacing = 16.dp) { page ->
                    Box(Modifier.fillMaxSize().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        SleepThumbImage(images[page], isSelected = false, corner = 10.dp, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (images.size > 1) {
                    Text("${pager.currentPage + 1} / ${images.size}", Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val isSelected = current != null && SleepChoice.current(snapshot.sleep) == SleepChoice.Image(current.name)
                    if (model.sleepSelectionSupported) {
                        if (isSelected) {
                            FilledTonalButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.screensaver_currently_chosen))
                            }
                        } else {
                            Button(
                                onClick = {
                                    val file = current ?: return@Button
                                    onDismiss()
                                    model.launch { chooseSleepImage(SleepChoice.Image(file.name)) }
                                },
                                enabled = current != null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Outlined.Bedtime, contentDescription = null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.screensaver_show_this))
                            }
                        }
                    } else {
                        Text(stringResource(R.string.core_sleep_update_firmware), Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { confirmDelete = true }, enabled = current != null, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Delete, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.screensaver_delete_from_reader), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        val file = current
        if (confirmDelete && file != null) DeleteDialog(file, onDone = { confirmDelete = false }, afterDelete = onDismiss)
    }
}

// endregion

// region Пакетная загрузка и набор Folio

@Composable
private fun BatchCard(sleep: SleepResponse?, onPack: () -> Unit) {
    val model = LocalAppModel.current
    val context = LocalContext.current
    val batch = SleepBatchController
    var fit by remember { mutableStateOf(loadFit(context)) }
    var menu by remember { mutableStateOf(false) }
    val packCount = remember { SleepPack.items(runCatching { context.assets.list(SleepPack.DIR) }.getOrNull()).size }

    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(SleepBatch.MAX_PHOTOS)) { uris ->
        if (uris.isNotEmpty()) batch.start(uris.map { SleepBatchInput.Photo(it) }, fit, model, context)
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) batch.start(uris.take(SleepBatch.MAX_PHOTOS).map { SleepBatchInput.Document(it) }, fit, model, context)
    }

    Card {
        val phase = batch.phase
        if (batch.isRunning) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val status = when (phase) {
                        is SleepBatch.Phase.Preparing -> stringResource(R.string.screensaver_batch_preparing, phase.index, phase.count)
                        is SleepBatch.Phase.Sending -> stringResource(R.string.screensaver_batch_sending, phase.index, phase.count)
                        SleepBatch.Phase.Idle -> ""
                    }
                    Text(status, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { batch.cancel() }) { Text(stringResource(R.string.common_cancel)) }
                }
                LinearProgressIndicator(progress = { phase.progress }, Modifier.fillMaxWidth())
            }
        } else {
            Box {
                Button(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.screensaver_add_pictures))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.screensaver_from_photos)) },
                        leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) },
                        onClick = {
                            menu = false
                            pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.screensaver_from_files)) },
                        leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                        onClick = {
                            menu = false
                            pickFiles.launch(arrayOf("image/*"))
                        },
                    )
                }
            }
            if (packCount > 0) {
                OutlinedButton(onClick = onPack, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.GridView, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.screensaver_pack_button, packCount))
                }
            }
        }
        Text(stringResource(R.string.screensaver_scale), style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SleepBatch.Fit.entries.forEachIndexed { i, f ->
                SegmentedButton(
                    selected = fit == f,
                    onClick = { fit = f; saveFit(context, f) },
                    shape = SegmentedButtonDefaults.itemShape(i, SleepBatch.Fit.entries.size),
                    enabled = !batch.isRunning,
                ) { Text(stringResource(if (f == SleepBatch.Fit.FILL) R.string.screensaver_fill else R.string.screensaver_fit)) }
            }
        }
        if (batch.offerCustomMode && !SleepRules.modeShowsImages(sleep?.mode)) {
            OutlinedButton(
                onClick = {
                    batch.offerCustomMode = false
                    model.launch { setSleepMode(SleepRules.CUSTOM_MODE, sleepModeName(SleepRules.CUSTOM_MODE)) }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Bedtime, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.screensaver_show_these))
            }
        }
        Text(pluralStringResource(R.plurals.screensaver_batch_footer, SleepBatch.MAX_PHOTOS, SleepBatch.MAX_PHOTOS), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** «Набор заставок Folio»: какие из встроенных картинок отправить (по умолчанию все). */
@Composable
private fun SleepPackSheet(onDismiss: () -> Unit) {
    val model = LocalAppModel.current
    val context = LocalContext.current
    val connection by model.connection.collectAsStateWithLifecycle()
    val items = remember { SleepPack.items(runCatching { context.assets.list(SleepPack.DIR) }.getOrNull()) }
    val selected = remember { mutableStateListOf<String>().apply { addAll(items.map { it.id }) } }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        SheetHeader(stringResource(R.string.screensaver_pack_title), onDismiss) {
            val all = selected.size == items.size
            TextButton(onClick = { selected.clear(); if (!all) selected.addAll(items.map { it.id }) }) {
                Text(stringResource(if (all) R.string.screensaver_deselect_all else R.string.screensaver_select_all))
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(100.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            items(items, key = { it.id }) { item ->
                val on = item.id in selected
                PackThumb(item, on) { if (on) selected.remove(item.id) else selected.add(item.id) }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.screensaver_pack_footer), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Button(
            onClick = {
                val chosen = items.filter { it.id in selected }.map { SleepBatchInput.Pack(it) }
                SleepBatchController.start(chosen, SleepBatch.Fit.FILL, model, context, replaceSameNames = true)
                onDismiss()
            },
            enabled = selected.isNotEmpty() && !SleepBatchController.isRunning,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Text(
                stringResource(
                    if (connection == AppModel.Connection.Connected) R.string.screensaver_pack_send else R.string.screensaver_pack_enqueue,
                    selected.size,
                ),
            )
        }
    }
}

@Composable
private fun PackThumb(item: SleepPack.Item, isOn: Boolean, toggle: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.id) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(item.path).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 }) }
            }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(6.dp)
    val accent = MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .toggleable(value = isOn, role = Role.Checkbox, onValueChange = { toggle() }),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.6f)
                .alpha(if (isOn) 1f else 0.55f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(if (isOn) 3.dp else 1.dp, if (isOn) accent else MaterialTheme.colorScheme.outlineVariant, shape),
        ) {
            bitmap?.let {
                Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            Icon(
                if (isOn) Icons.Filled.CheckCircle else Icons.Outlined.Circle, contentDescription = null,
                tint = if (isOn) accent else Color.Black.copy(alpha = 0.35f),
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(CircleShape).background(Color.White),
            )
        }
        Text(item.fileName, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
    }
}

// endregion
