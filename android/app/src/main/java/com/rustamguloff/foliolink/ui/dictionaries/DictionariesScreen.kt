package com.rustamguloff.foliolink.ui.dictionaries

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.app.AppModel
import com.rustamguloff.foliolink.app.DictionaryCatalogController.Install
import com.rustamguloff.foliolink.app.DictionaryCatalogController.State
import com.rustamguloff.foliolink.app.Texts
import com.rustamguloff.foliolink.core.dictionaries.CatalogEntry
import com.rustamguloff.foliolink.core.dictionaries.DictionaryFolder
import com.rustamguloff.foliolink.core.dictionaries.DictionaryRules
import com.rustamguloff.foliolink.core.dictionaries.StarDict
import com.rustamguloff.foliolink.core.dictionaries.StarDictPart
import com.rustamguloff.foliolink.core.dictionaries.StarDictSet
import com.rustamguloff.foliolink.core.model.DictionariesResponse
import com.rustamguloff.foliolink.core.model.DictionaryInfo
import com.rustamguloff.foliolink.ui.common.Fmt
import com.rustamguloff.foliolink.ui.common.FolioTopBar
import com.rustamguloff.foliolink.ui.common.LocalAppModel
import com.rustamguloff.foliolink.ui.settings.ActionRow
import com.rustamguloff.foliolink.ui.settings.NoteRow
import com.rustamguloff.foliolink.ui.settings.SettingsGroup
import com.rustamguloff.foliolink.ui.settings.readPickedFiles
import com.rustamguloff.foliolink.ui.shell.Dest
import com.rustamguloff.foliolink.ui.shell.LocalRouter
import com.rustamguloff.foliolink.ui.theme.FolioDanger
import com.rustamguloff.foliolink.ui.theme.FolioSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Свой словарь, выбранный для загрузки: проверенный набор и исходные имена файлов. */
private class PickedDictionary(val set: StarDictSet, val originalNames: List<String>)

/**
 * «Словари» (Folio 1.1): словари на карте читалки и режим поиска, каталог FolioLink
 * (скачать → sha256 → на читалку), свой словарь StarDict из файлов телефона.
 */
@Composable
fun DictionariesScreen() {
    val model = LocalAppModel.current
    val router = LocalRouter.current
    val context = LocalContext.current
    val res = LocalResources.current
    val texts = remember(res) { Texts { id, args -> res.getString(id, *args) } }
    val scope = rememberCoroutineScope()
    val connection by model.connection.collectAsStateWithLifecycle()
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val catalog by model.dictionaryCatalog.state.collectAsStateWithLifecycle()
    val connected = connection == AppModel.Connection.Connected
    val supported = DictionaryRules.supported(snapshot.info)
    val cached = snapshot.dictionaries

    var loading by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<DictionaryInfo?>(null) }
    var picked by remember { mutableStateOf<PickedDictionary?>(null) }
    var preparing by remember { mutableStateOf(false) }

    suspend fun load() {
        if (!connected || supported == false) return
        loading = true
        try { model.refreshDictionaries() } finally { loading = false }
    }

    LaunchedEffect(connected, supported) { load() }
    LaunchedEffect(supported) { if (supported != false) model.dictionaryCatalog.load() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            preparing = true
            try {
                val (files, unreadable) = readPickedFiles(context, uris, maxBytes = StarDict.MAX_FILE_BYTES)
                if (unreadable.isNotEmpty()) {
                    model.show(res.getString(R.string.dictionaries_unreadable, unreadable.size), AppModel.Banner.Kind.ERROR)
                    return@launch
                }
                val pairs = files.map { it.name to it.data }
                val set = try {
                    withContext(Dispatchers.Default) { StarDict.prepare(pairs) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    model.show(DictionaryTexts.errorText(e, texts), AppModel.Banner.Kind.ERROR)
                    return@launch
                }
                picked = PickedDictionary(set, files.map { it.name })
            } finally {
                preparing = false
            }
        }
    }
    val pick = { picker.launch(arrayOf("*/*")) }

    Scaffold(
        topBar = {
            FolioTopBar(stringResource(R.string.dictionaries_title), actions = {
                if (supported != false) {
                    IconButton(onClick = pick, enabled = !preparing) {
                        Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.dictionaries_add))
                    }
                }
            })
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = loading && cached != null,
            onRefresh = {
                scope.launch { load() }
                model.dictionaryCatalog.load(force = true)
            },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                if (supported == false) {
                    item {
                        SettingsGroup {
                            NoteRow(stringResource(R.string.dictionaries_update_firmware), Icons.Outlined.Warning)
                            ActionRow(stringResource(R.string.dictionaries_open_firmware), onClick = { router.open(Dest.FIRMWARE) },
                                icon = Icons.Outlined.Memory)
                        }
                    }
                    item { HintGroup() }
                    item { Spacer(Modifier.height(24.dp)) }
                    return@LazyColumn
                }
                if (!connected) {
                    item {
                        SettingsGroup {
                            NoteRow(
                                stringResource(if (cached != null) R.string.dictionaries_offline_note else R.string.dictionaries_connect_note),
                                Icons.Outlined.WifiOff,
                            )
                        }
                    }
                }
                if (cached != null || connected) {
                    item {
                        InstalledGroup(cached, loading, onDelete = { pendingDelete = it })
                    }
                }
                if (cached != null && cached.dictionaries.isNotEmpty()) {
                    item {
                        ModeGroup(cached) { value, label -> scope.launch { model.selectDictionary(value, label) } }
                    }
                }
                item { CatalogGroup(catalog, cached?.dictionaries.orEmpty(), texts) }
                item {
                    SettingsGroup(
                        title = stringResource(R.string.dictionaries_own),
                        footer = stringResource(R.string.dictionaries_own_footer),
                    ) {
                        ActionRow(stringResource(R.string.dictionaries_own_button), onClick = pick,
                            icon = Icons.Outlined.FileUpload, busy = preparing)
                    }
                }
                item { HintGroup() }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    pendingDelete?.let { d ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.dictionaries_delete_title, d.displayTitle)) },
            text = { Text(stringResource(R.string.dictionaries_delete_text, d.path.ifEmpty { DictionaryRules.folderPath(d.name) })) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        if (model.deleteDictionary(d.name, d.displayTitle) != AppModel.SubmitResult.FAILED) {
                            model.dictionaryCatalog.clear(d.name)
                        }
                    }
                }) { Text(stringResource(R.string.common_delete), color = FolioDanger) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    picked?.let { p ->
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { picked = null }, sheetState = state) {
            ImportSheet(
                p, connected,
                existing = cached?.dictionaries.orEmpty().map { it.name },
                onClose = { picked = null },
            )
        }
    }
}

@Composable
private fun InstalledGroup(cached: DictionariesResponse?, loading: Boolean, onDelete: (DictionaryInfo) -> Unit) {
    val list = cached?.dictionaries.orEmpty()
    SettingsGroup(
        title = if (cached != null) stringResource(R.string.dictionaries_on_reader_count, list.size)
        else stringResource(R.string.dictionaries_on_reader),
    ) {
        when {
            cached == null && loading -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            cached != null && list.isEmpty() -> NoteRow(stringResource(R.string.dictionaries_none))
        }
        list.forEach { d -> InstalledRow(d, inUse = cached != null && DictionaryRules.mode(cached) == d.name, onDelete = { onDelete(d) }) }
    }
}

@Composable
private fun InstalledRow(d: DictionaryInfo, inUse: Boolean, onDelete: () -> Unit) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val words = if (d.words > 0) pluralStringResource(R.plurals.dictionaries_words, d.words, DictionaryTexts.count(d.words, locale)) else null
    val sizeScript = stringResource(R.string.dictionaries_size_script, Fmt.bytes(context, DictionaryRules.bytes(d)),
        stringResource(DictionaryTexts.scriptRes(d.script)))
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Icon(if (inUse) Icons.Outlined.CheckCircle else Icons.Outlined.Translate, contentDescription = null,
                tint = if (inUse) FolioSuccess else MaterialTheme.colorScheme.primary)
        },
        headlineContent = { Text(d.displayTitle, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(listOfNotNull(words, sizeScript).joinToString(" · "))
                val folder = stringResource(R.string.dictionaries_folder, d.name)
                val tail = when {
                    inUse -> stringResource(R.string.dictionaries_in_use)
                    !d.indexed -> stringResource(R.string.dictionaries_not_indexed)
                    else -> null
                }
                Text(listOfNotNull(folder, tail).joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                    color = if (inUse) FolioSuccess else MaterialTheme.colorScheme.outline)
            }
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.dictionaries_delete_named, d.displayTitle))
            }
        },
    )
}

/** Режим поиска на читалке: все словари / только один / выкл. */
@Composable
private fun ModeGroup(cached: DictionariesResponse, onSelect: (value: String, label: String) -> Unit) {
    val mode = DictionaryRules.mode(cached)
    SettingsGroup(
        title = stringResource(R.string.dictionaries_mode_header),
        footer = stringResource(R.string.dictionaries_mode_footer),
    ) {
        val all = stringResource(R.string.dictionaries_mode_all)
        ModeRow(all, stringResource(R.string.dictionaries_mode_all_note), mode == DictionariesResponse.ALL) {
            onSelect(DictionariesResponse.ALL, all)
        }
        cached.dictionaries.forEach { d ->
            val label = stringResource(R.string.dictionaries_mode_only, d.displayTitle)
            ModeRow(label, null, mode == d.name) { onSelect(d.name, d.displayTitle) }
        }
        val off = stringResource(R.string.dictionaries_mode_off)
        ModeRow(off, stringResource(R.string.dictionaries_mode_off_note), mode == DictionariesResponse.OFF) {
            onSelect(DictionariesResponse.OFF, off)
        }
    }
}

@Composable
private fun ModeRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = { if (!selected) onClick() }),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        headlineContent = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = subtitle?.let { { Text(it) } },
    )
}

@Composable
private fun CatalogGroup(catalog: State, installed: List<DictionaryInfo>, texts: Texts) {
    val model = LocalAppModel.current
    SettingsGroup(
        title = stringResource(R.string.dictionaries_catalog),
        footer = stringResource(R.string.dictionaries_catalog_footer),
    ) {
        when (catalog) {
            State.Idle, is State.Loading -> Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.dictionaries_catalog_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is State.Failed -> {
                NoteRow(DictionaryTexts.catalogFailure(catalog.error, texts), Icons.Outlined.CloudOff)
                ActionRow(stringResource(R.string.dictionaries_retry), onClick = { model.dictionaryCatalog.load(force = true) },
                    icon = Icons.Outlined.Refresh)
            }
            is State.Loaded -> {
                if (catalog.manifest.dictionaries.isEmpty()) NoteRow(stringResource(R.string.dictionaries_catalog_empty))
                catalog.manifest.dictionaries.forEach { entry ->
                    CatalogRow(entry, DictionaryRules.isInstalled(entry, installed), texts)
                }
            }
        }
    }
}

@Composable
private fun CatalogRow(entry: CatalogEntry, installed: Boolean, texts: Texts) {
    val model = LocalAppModel.current
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val locale: Locale = LocalConfiguration.current.locales[0]
    val installs by model.dictionaryCatalog.installs.collectAsStateWithLifecycle()
    val transfer by model.transfer.collectAsStateWithLifecycle()
    val install = installs[entry.id]
    val title = entry.title(locale.language)
    val busy = install is Install.Downloading || install is Install.Verifying || install is Install.Uploading
    val details = listOfNotNull(
        if (entry.from.isNotEmpty() || entry.to.isNotEmpty()) stringResource(
            R.string.dictionaries_languages, DictionaryTexts.language(entry.from, locale), DictionaryTexts.language(entry.to, locale),
        ) else null,
        if (entry.headwords > 0) pluralStringResource(R.plurals.dictionaries_words, entry.headwords,
            DictionaryTexts.count(entry.headwords, locale)) else null,
        Fmt.bytes(context, entry.totalSize),
    ).joinToString(" · ")

    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(details)
                if (entry.license.isNotEmpty()) {
                    Text(stringResource(R.string.dictionaries_license, entry.license), style = MaterialTheme.typography.labelSmall)
                }
                if (entry.attribution.isNotEmpty()) {
                    // Атрибуция бывает в абзац (Викисловарь): две строки, по нажатию — целиком.
                    var expanded by remember(entry.id) { mutableStateOf(false) }
                    Text(
                        entry.attribution,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.dictionaries_attribution_more)) {
                            expanded = !expanded
                        },
                    )
                }
                if (entry.source.isNotEmpty()) {
                    val host = runCatching { java.net.URI(entry.source).host }.getOrNull() ?: entry.source
                    Text(
                        stringResource(R.string.dictionaries_source, host),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = if (entry.source.startsWith("http")) {
                            Modifier.clickable(role = Role.Button) { runCatching { uri.openUri(entry.source) } }
                        } else Modifier,
                    )
                }
                InstallStatus(install, transfer, texts)
            }
        },
        trailingContent = {
            when {
                busy -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                installed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = FolioSuccess)
                    Text(stringResource(R.string.dictionaries_installed), color = FolioSuccess, style = MaterialTheme.typography.labelMedium)
                }
                install is Install.Queued -> Icon(Icons.Outlined.CheckCircle, contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline)
                else -> {
                    val label = stringResource(R.string.dictionaries_install_named, title)
                    FilledTonalButton(
                        onClick = { model.dictionaryCatalog.install(entry, title) },
                        modifier = Modifier.semantics { contentDescription = label },
                    ) { Text(stringResource(R.string.dictionaries_install)) }
                }
            }
        },
    )
}

/** Строка хода установки под карточкой каталога. */
@Composable
private fun InstallStatus(install: Install?, transfer: AppModel.Transfer?, texts: Texts) {
    when (install) {
        null, Install.Done -> {}
        is Install.Downloading -> ProgressLine(stringResource(R.string.dictionaries_downloading, install.index, install.count), install.fraction)
        Install.Verifying -> ProgressLine(stringResource(R.string.dictionaries_verifying), null)
        Install.Uploading -> {
            val suffix = transfer?.title?.substringAfterLast(": ")?.let { " $it" }.orEmpty()
            ProgressLine(stringResource(R.string.dictionaries_uploading) + suffix, transfer?.fraction)
        }
        Install.Queued -> Text(stringResource(R.string.dictionaries_queued), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        is Install.Failed -> Text(
            install.error?.let { stringResource(R.string.dictionaries_install_failed, DictionaryTexts.errorText(it, texts)) }
                ?: stringResource(R.string.dictionaries_install_failed_queue),
            style = MaterialTheme.typography.labelSmall, color = FolioDanger,
        )
    }
}

@Composable
private fun ProgressLine(text: String, fraction: Float?) {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun HintGroup() {
    SettingsGroup(title = stringResource(R.string.dictionaries_hint_header)) {
        NoteRow(stringResource(R.string.dictionaries_hint), Icons.Outlined.TouchApp)
    }
}

/** Свой словарь: файлы, папка на читалке, загрузка. */
@Composable
private fun ImportSheet(p: PickedDictionary, connected: Boolean, existing: List<String>, onClose: () -> Unit) {
    val model = LocalAppModel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locale = LocalConfiguration.current.locales[0]
    var folderInput by remember { mutableStateOf(DictionaryFolder.sanitize(p.set.stem)) }
    var uploading by remember { mutableStateOf(false) }
    val clean = DictionaryFolder.sanitize(folderInput)
    val folder = DictionaryFolder.unique(clean, existing)
    val files = remember(folder) { p.set.files(folder) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()
            .padding(bottom = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.dictionaries_import_title), Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onClose) { Text(stringResource(R.string.common_cancel)) }
        }
        SettingsGroup {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Icon(Icons.Outlined.Translate, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text(p.set.title, fontWeight = FontWeight.SemiBold) },
                supportingContent = {
                    val words = if (p.set.words > 0) pluralStringResource(R.plurals.dictionaries_words, p.set.words,
                        DictionaryTexts.count(p.set.words, locale)) else null
                    Text(listOfNotNull(words, Fmt.bytes(context, p.set.totalBytes)).joinToString(" · "))
                },
            )
        }
        SettingsGroup(title = stringResource(R.string.dictionaries_import_files)) {
            p.originalNames.forEach { name ->
                val gz = StarDictPart.of(name) == StarDictPart.IDX_GZ
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Icon(Icons.Outlined.Description, contentDescription = null) },
                    headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = if (gz) ({ Text(stringResource(R.string.dictionaries_import_gunzip, name)) }) else null,
                )
            }
        }
        SettingsGroup(
            title = stringResource(R.string.dictionaries_import_folder_header),
            footer = stringResource(R.string.dictionaries_import_folder_will_be, folder),
        ) {
            OutlinedTextField(
                value = folderInput,
                onValueChange = { folderInput = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                label = { Text(stringResource(R.string.dictionaries_import_folder)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            )
            if (folder != clean) {
                NoteRow(stringResource(R.string.dictionaries_import_folder_taken, folder), Icons.Outlined.Warning)
            }
        }
        SettingsGroup(footer = if (!connected) stringResource(R.string.dictionaries_import_offline) else null) {
            ActionRow(
                stringResource(R.string.dictionaries_import_upload),
                icon = Icons.Outlined.FileUpload,
                enabled = !uploading,
                busy = uploading,
                onClick = {
                    scope.launch {
                        uploading = true
                        val results = try {
                            model.uploadDictionary(folder, p.set.title, files)
                        } finally {
                            uploading = false
                        }
                        if (AppModel.SubmitResult.FAILED !in results) onClose()
                    }
                },
            )
        }
    }
}
