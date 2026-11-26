package com.rustamguloff.foliolink.ui.fonts

import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.app.AppModel
import com.rustamguloff.foliolink.core.model.FileEntry
import com.rustamguloff.foliolink.core.model.FontFamily as RemoteFontFamily
import com.rustamguloff.foliolink.ui.common.ChipRow
import com.rustamguloff.foliolink.ui.common.FolioFilterChip
import com.rustamguloff.foliolink.ui.common.FolioTopBar
import com.rustamguloff.foliolink.ui.common.LocalAppModel
import com.rustamguloff.foliolink.ui.settings.ActionRow
import com.rustamguloff.foliolink.ui.settings.NoteRow
import com.rustamguloff.foliolink.ui.settings.SettingsGroup
import com.rustamguloff.foliolink.ui.settings.readPickedFiles
import com.rustamguloff.foliolink.ui.theme.FolioDanger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Файл шрифта, выбранный для импорта. */
private class ImportedFont(
    val id: String = UUID.randomUUID().toString(),
    val data: ByteArray,
    val originalName: String,
    val family: String,
    style: FontStyle,
) {
    var style by mutableStateOf(style)
    val isCpfont: Boolean get() = FontInspector.isCpfont(originalName)
}

/**
 * «Шрифты» (iOS FontsView): семейства на карте читалки (`/api/fonts` и папка `/fonts`), удаление,
 * импорт TTF/OTF (несколько файлов; начертание — по таблицам шрифта) и готовых .cpfont.
 */
@Composable
fun FontsScreen() {
    val model = LocalAppModel.current
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val connection by model.connection.collectAsStateWithLifecycle()
    val connected = connection == AppModel.Connection.Connected

    var families by remember { mutableStateOf(emptyList<RemoteFontFamily>()) }
    var folders by remember { mutableStateOf(emptyList<FileEntry>()) }
    var maxFamilies by remember { mutableStateOf<Int?>(null) }
    var loading by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var imported by remember { mutableStateOf<List<ImportedFont>?>(null) }
    val all = remember(families, folders) { FontInspector.allFamilies(families, folders) }

    suspend fun load() {
        val client = model.client ?: return
        loading = true
        try {
            try {
                val r = client.fonts()
                families = r.families
                maxFamilies = r.maxFamilies
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Пустой список здесь подтолкнул бы загрузить уже установленные шрифты.
                model.show(e)
            }
            folders = try {
                FontInspector.fontEntries(client.files("/fonts"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        } finally {
            loading = false
        }
    }

    LaunchedEffect(connected) { if (connected) load() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val (files, unreadable) = readPickedFiles(context, uris)
            val fonts = files.mapNotNull { f ->
                if (FontInspector.isCpfont(f.name)) {
                    ImportedFont(data = f.data, originalName = f.name,
                        family = FontInspector.familyFromFileName(f.name), style = FontStyle.REGULAR)
                } else {
                    FontInspector.inspect(f.data, f.name)?.let { info ->
                        ImportedFont(data = f.data, originalName = f.name, family = info.family, style = info.style)
                    }
                }
            }
            val bad = unreadable.size + files.size - fonts.size
            if (fonts.isEmpty()) {
                model.show(res.getString(R.string.fonts_unreadable), AppModel.Banner.Kind.ERROR)
            } else {
                if (bad > 0) model.show(res.getString(R.string.fonts_some_unreadable, bad), AppModel.Banner.Kind.ERROR)
                imported = fonts
            }
        }
    }

    Scaffold(
        topBar = {
            FolioTopBar(stringResource(R.string.fonts_title), actions = {
                IconButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.fonts_import))
                }
            })
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = loading && all.isNotEmpty(),
            onRefresh = { scope.launch { load() } },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                if (!connected) {
                    item { SettingsGroup { NoteRow(stringResource(R.string.fonts_offline_note), Icons.Outlined.WifiOff) } }
                }
                item {
                    val max = maxFamilies
                    SettingsGroup(
                        title = if (max != null) stringResource(R.string.fonts_installed_of, all.size, max)
                        else stringResource(R.string.fonts_installed),
                        footer = stringResource(R.string.fonts_footer),
                    ) {
                        if (loading && all.isEmpty()) {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        if (!loading && all.isEmpty() && connected) NoteRow(stringResource(R.string.fonts_none))
                        all.forEach { name -> FamilyRow(name, families.firstOrNull { it.name == name }) { pendingDelete = name } }
                        ActionRow(stringResource(R.string.fonts_import_button), onClick = { picker.launch(arrayOf("*/*")) },
                            icon = Icons.Outlined.FileUpload)
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    pendingDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.fonts_delete_title, name)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch { if (model.deleteFontFamily(name) == AppModel.SubmitResult.SENT) load() }
                }) { Text(stringResource(R.string.common_delete), color = FolioDanger) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    imported?.let { fonts ->
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { imported = null }, sheetState = state) {
            FontImportSheet(fonts, connected, onClose = { imported = null }, onDone = { imported = null; scope.launch { load() } })
        }
    }
}

@Composable
private fun FamilyRow(name: String, family: RemoteFontFamily?, onDelete: () -> Unit) {
    val files = family?.files?.joinToString(", ") { it.name }.orEmpty()
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(Icons.Outlined.TextFields, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(name, fontWeight = FontWeight.SemiBold) },
        supportingContent = {
            Column {
                when {
                    family == null -> Text(stringResource(R.string.fonts_folder))
                    files.isNotEmpty() -> Text(files, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (family != null && family.sizes.isNotEmpty()) {
                    Text(stringResource(R.string.fonts_sizes, family.sizes.joinToString(", ")),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.fonts_delete_named, name))
            }
        },
    )
}

/** Импорт: семейство (папка), начертание каждого файла, предпросмотр, загрузка. */
@Composable
private fun FontImportSheet(fonts: List<ImportedFont>, connected: Boolean, onClose: () -> Unit, onDone: () -> Unit) {
    val model = LocalAppModel.current
    val scope = rememberCoroutineScope()
    var family by remember { mutableStateOf(FontInspector.safeFamily(fonts.first().family)) }
    var size by remember { mutableFloatStateOf(22f) }
    var uploading by remember { mutableStateOf(false) }
    val list = remember { mutableStateListOf<ImportedFont>().apply { addAll(fonts) } }
    val folder = FontInspector.safeFamily(family)
    val targets = list.map { FontInspector.targetName(folder, it.originalName, it.style) }
    val clashes = FontInspector.duplicates(targets)

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()
            .padding(bottom = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.fonts_import_title), Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onClose) { Text(stringResource(R.string.common_cancel)) }
        }
        SettingsGroup(
            title = stringResource(R.string.fonts_family_header),
            footer = stringResource(R.string.fonts_folder_will_be, folder),
        ) {
            OutlinedTextField(
                value = family,
                onValueChange = { family = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                label = { Text(stringResource(R.string.fonts_family)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            )
        }
        SettingsGroup {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.fonts_preview_size, size.toInt()))
                Slider(value = size, onValueChange = { size = it }, valueRange = 14f..36f, steps = 21)
            }
        }
        list.forEachIndexed { i, font ->
            val target = targets[i]
            SettingsGroup(title = "${font.originalName} → $target") {
                if (!font.isCpfont) {
                    ChipRow(Modifier.padding(vertical = 8.dp)) {
                        FontStyle.entries.forEach { st ->
                            FolioFilterChip(stringResource(styleTitle(st)), selected = font.style == st, onClick = { font.style = st })
                        }
                    }
                    FontPreview(font, size)
                } else {
                    NoteRow(stringResource(R.string.fonts_cpfont_note))
                }
                if (target in clashes) NoteRow(stringResource(R.string.fonts_duplicate_style), Icons.Outlined.Warning, FolioDanger)
            }
        }
        SettingsGroup(footer = if (!connected) stringResource(R.string.fonts_offline_import) else null) {
            ActionRow(
                stringResource(R.string.fonts_upload),
                icon = Icons.Outlined.FileUpload,
                enabled = !uploading && clashes.isEmpty() && folder.isNotEmpty(),
                busy = uploading,
                onClick = {
                    scope.launch {
                        uploading = true
                        val files = list.mapIndexed { i, f -> targets[i] to f.data }
                        val results = try {
                            model.uploadFont(folder, files)
                        } finally {
                            uploading = false
                        }
                        if (AppModel.SubmitResult.FAILED !in results) onDone()
                    }
                },
            )
        }
    }
}

private fun styleTitle(style: FontStyle): Int = when (style) {
    FontStyle.REGULAR -> R.string.fonts_style_regular
    FontStyle.BOLD -> R.string.fonts_style_bold
    FontStyle.ITALIC -> R.string.fonts_style_italic
    FontStyle.BOLD_ITALIC -> R.string.fonts_style_bold_italic
}

/**
 * Образец текста этим шрифтом на «листе» шириной 480 px читалки: кегль в px читалки пересчитан
 * в ширину экрана телефона, чтобы строка вмещала столько же слов.
 */
@Composable
private fun FontPreview(font: ImportedFont, sizePx: Float) {
    val context = LocalContext.current
    val typeface by produceState<Typeface?>(null, font.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "font-preview").apply { mkdirs() }
                val ext = FontInspector.extension(font.originalName).ifEmpty { "ttf" }
                val file = File(dir, "${font.id}.$ext")
                file.writeBytes(font.data)
                try {
                    Typeface.Builder(file).build()
                } finally {
                    file.delete()
                }
            }.getOrNull()
        }
    }
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(10.dp),
        color = Color.White,
    ) {
        BoxWithConstraints(Modifier.padding(12.dp)) {
            val density = LocalDensity.current
            val fontSize = with(density) { (sizePx * maxWidth.value / 480f).dp.toSp() }
            val tf = typeface
            if (tf == null) {
                Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            } else {
                Text(
                    stringResource(R.string.fonts_sample),
                    color = Color.Black,
                    fontFamily = FontFamily(androidx.compose.ui.text.font.Typeface(tf)),
                    fontSize = fontSize,
                    lineHeight = fontSize * 1.25f,
                )
            }
        }
    }
}
