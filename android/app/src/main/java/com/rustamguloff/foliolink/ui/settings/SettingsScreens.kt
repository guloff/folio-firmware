package com.rustamguloff.foliolink.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.app.AppModel
import com.rustamguloff.foliolink.core.model.DeviceSetting
import com.rustamguloff.foliolink.core.model.SettingType
import com.rustamguloff.foliolink.core.store.SettingsProfile
import com.rustamguloff.foliolink.ui.common.FolioTopBar
import com.rustamguloff.foliolink.ui.common.Fmt
import com.rustamguloff.foliolink.ui.common.LocalAppModel
import com.rustamguloff.foliolink.ui.common.RequiresConnection
import com.rustamguloff.foliolink.ui.shell.LocalRouter
import com.rustamguloff.foliolink.ui.theme.FolioDanger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Окно имени профиля: новый или переименование существующего. */
private data class ProfileNaming(val renaming: SettingsProfile?, val name: String)

/**
 * «Настройки читалки» (iOS DeviceSettingsView): форма по схеме `GET /api/settings`, отправляются
 * только изменённые ключи (через очередь). Без связи — сохранённые настройки; правки встанут в
 * очередь. Профили: сохранить текущие, применить, переименовать, удалить.
 */
@Composable
fun DeviceSettingsScreen() {
    val model = LocalAppModel.current
    val router = LocalRouter.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val connection by model.connection.collectAsStateWithLifecycle()
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val profiles by model.profiles.collectAsStateWithLifecycle()
    val connected = connection == AppModel.Connection.Connected

    var settings by remember { mutableStateOf(model.snapshot.value.deviceSettings.orEmpty()) }
    var draft by remember { mutableStateOf(emptyMap<String, JsonElement>()) }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<ProfileNaming?>(null) }
    var managing by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val changed = remember(settings, draft) { DeviceSettingsLogic.changed(settings, draft) }

    suspend fun load() {
        val client = model.client
        if (client == null) {
            settings = model.snapshot.value.deviceSettings.orEmpty()
            return
        }
        loading = true
        try {
            val fresh = client.settings()
            model.cacheDeviceSettings(fresh)
            // Неотправленные правки из очереди остаются видны поверх ответа читалки.
            settings = model.snapshot.value.deviceSettings ?: fresh
            draft = emptyMap()
            error = null
            settings.firstOrNull { it.key == "sleepScreen" }?.let { s ->
                DeviceSettingsLogic.intValue(s.value)?.let { model.updateCachedSleepMode(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = model.errorText(e)
            if (settings.isEmpty()) settings = model.snapshot.value.deviceSettings.orEmpty()
        } finally {
            loading = false
        }
    }

    suspend fun save(): Boolean {
        saving = true
        try {
            val changes = changed
            val names = DeviceSettingsLogic.changedNames(settings, changes)
            return when (model.updateDeviceSettings(changes, res.getString(R.string.settings_device_title), names.joinToString(", "))) {
                AppModel.SubmitResult.SENT -> {
                    model.show(res.getString(R.string.settings_saved), AppModel.Banner.Kind.SUCCESS)
                    saving = false
                    load()
                    true
                }
                AppModel.SubmitResult.QUEUED -> {
                    settings = model.snapshot.value.deviceSettings ?: settings
                    draft = emptyMap()
                    true
                }
                AppModel.SubmitResult.FAILED -> false
            }
        } finally {
            saving = false
        }
    }

    suspend fun apply(profile: SettingsProfile) {
        saving = true
        try {
            if (model.applySettingsProfile(profile, settings) == AppModel.SubmitResult.SENT) {
                saving = false
                load()
            } else {
                settings = model.snapshot.value.deviceSettings ?: settings
            }
            draft = emptyMap()
        } finally {
            saving = false
        }
    }

    // При появлении и после подключения — свежие настройки с читалки.
    LaunchedEffect(connected) { if (draft.isEmpty()) load() }
    // Изменения со стороны (профиль, правки из очереди) видны, пока ничего не редактируется.
    LaunchedEffect(snapshot.deviceSettings) {
        val cached = snapshot.deviceSettings
        if (draft.isEmpty() && !saving && cached != null) settings = cached
    }
    BackHandler(enabled = changed.isNotEmpty()) { confirmLeave = true }

    Scaffold(
        topBar = {
            FolioTopBar(
                stringResource(R.string.settings_device_title),
                onBack = if (changed.isNotEmpty()) ({ confirmLeave = true }) else null,
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }, enabled = changed.isEmpty() && !saving) {
                            Icon(Icons.Outlined.Bookmarks, contentDescription = stringResource(R.string.settings_profiles))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (profiles.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.settings_apply_profile),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                                profiles.forEach { p ->
                                    DropdownMenuItem(
                                        text = { Text(p.name) },
                                        onClick = { menuOpen = false; scope.launch { apply(p) } },
                                        enabled = settings.isNotEmpty(),
                                    )
                                }
                                HorizontalDivider()
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings_save_as_profile)) },
                                leadingIcon = { Icon(Icons.Outlined.Save, contentDescription = null) },
                                enabled = settings.isNotEmpty(),
                                onClick = {
                                    menuOpen = false
                                    naming = ProfileNaming(null, res.getString(R.string.core_profile_default_name, profiles.size + 1))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings_manage_profiles)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ViewList, contentDescription = null) },
                                onClick = { menuOpen = false; managing = true },
                            )
                        }
                    }
                    if (saving) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = { scope.launch { save() } }, enabled = changed.isNotEmpty()) {
                            Text(stringResource(R.string.common_save))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (connected || settings.isNotEmpty()) {
                PullToRefreshBox(isRefreshing = loading && settings.isNotEmpty(), onRefresh = { scope.launch { load() } }) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (!connected) {
                            item {
                                SettingsGroup {
                                    NoteRow(stringResource(R.string.settings_offline_note), Icons.Outlined.WifiOff)
                                }
                            }
                        }
                        error?.let { e -> item { SettingsGroup { NoteRow(e, color = FolioDanger) } } }
                        if (loading && settings.isEmpty()) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                        items(DeviceSettingsLogic.categories(settings), key = { it.first }) { (category, rows) ->
                            SettingsGroup(title = category.ifEmpty { stringResource(R.string.settings_other) }) {
                                rows.forEach { s ->
                                    SettingRow(
                                        setting = s,
                                        value = draft[s.key] ?: s.value,
                                        changed = s.key in changed,
                                        onChange = { v -> draft = draft + (s.key to v) },
                                    )
                                }
                            }
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            } else {
                RequiresConnection {}
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.settings_save_changes)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    scope.launch { if (save()) router.back() }
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.common_cancel)) }
                    TextButton(onClick = {
                        confirmLeave = false
                        draft = emptyMap()
                        router.back()
                    }) { Text(stringResource(R.string.settings_discard), color = FolioDanger) }
                }
            },
        )
    }

    naming?.let { n ->
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text(stringResource(if (n.renaming == null) R.string.settings_new_profile else R.string.settings_rename_profile)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (n.renaming == null) Text(stringResource(R.string.settings_profile_hint))
                    OutlinedTextField(
                        value = n.name,
                        onValueChange = { naming = n.copy(name = it) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.settings_profile_name)) },
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = n.name.isNotBlank(), onClick = {
                    naming = null
                    val name = n.name.trim()
                    if (n.renaming != null) model.renameSettingsProfile(n.renaming, name)
                    else model.saveSettingsProfile(name, settings)
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = { TextButton(onClick = { naming = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    if (managing) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { managing = false }, sheetState = state) {
            ProfilesSheet(
                profiles = profiles,
                settings = settings,
                onApply = { p -> managing = false; scope.launch { apply(p) } },
                onRename = { p -> naming = ProfileNaming(p, p.name) },
                onDelete = { p -> model.deleteSettingsProfile(p) },
                onClose = { managing = false },
            )
        }
    }
}

/** Одна настройка по типу схемы; изменённая подсвечена. */
@Composable
private fun SettingRow(setting: DeviceSetting, value: JsonElement, changed: Boolean, onChange: (JsonElement) -> Unit) {
    val highlight = if (changed) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) else Modifier
    Box(highlight) {
        when (setting.type) {
            SettingType.TOGGLE -> SwitchRow(
                setting.name,
                checked = (DeviceSettingsLogic.intValue(value) ?: 0) != 0,
                onCheckedChange = { onChange(JsonPrimitive(if (it) 1 else 0)) },
            )
            SettingType.ENUM -> EnumRow(setting, DeviceSettingsLogic.intValue(value) ?: 0) { onChange(JsonPrimitive(it)) }
            SettingType.VALUE -> ValueRow(setting, value, onChange)
            SettingType.STRING -> StringRow(setting, DeviceSettingsLogic.stringValue(value)) { onChange(JsonPrimitive(it)) }
            SettingType.UNKNOWN -> {}
        }
    }
}

@Composable
private fun EnumRow(setting: DeviceSetting, selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = setting.options.getOrNull(selected) ?: selected.toString()
    Box {
        ListItem(
            modifier = Modifier.clickable(role = Role.DropdownList) { open = true }
                .semantics { stateDescription = current },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(setting.name) },
            trailingContent = { Text(current, color = MaterialTheme.colorScheme.primary) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.align(Alignment.TopEnd)) {
            setting.options.forEachIndexed { i, name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { open = false; onSelect(i) },
                    trailingIcon = if (i == selected) ({ Icon(Icons.Default.Check, contentDescription = null) }) else null,
                )
            }
        }
    }
}

@Composable
private fun ValueRow(setting: DeviceSetting, value: JsonElement, onChange: (JsonElement) -> Unit) {
    val range = DeviceSettingsLogic.range(setting)
    val current = DeviceSettingsLogic.intValue(value) ?: range.lo
    if (range.useStepper) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(setting.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            IconButton(onClick = { onChange(JsonPrimitive(range.clamp(current - range.step))) }, enabled = current > range.lo) {
                Icon(Icons.Outlined.Remove, contentDescription = stringResource(R.string.settings_decrease, setting.name))
            }
            Text("$current", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { onChange(JsonPrimitive(range.clamp(current + range.step))) }, enabled = current < range.hi) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.settings_increase, setting.name))
            }
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row {
                Text(setting.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text("$current", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(
                value = current.toFloat(),
                onValueChange = { onChange(JsonPrimitive(range.snap(it))) },
                valueRange = range.lo.toFloat()..range.hi.toFloat(),
                steps = range.sliderSteps,
            )
        }
    }
}

@Composable
private fun StringRow(setting: DeviceSetting, value: String, onChange: (String) -> Unit) {
    val secret = DeviceSettingsLogic.isSecret(setting.key)
    var reveal by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        singleLine = true,
        label = { Text(setting.name) },
        visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        trailingIcon = if (secret) ({
            IconButton(onClick = { reveal = !reveal }) {
                Icon(
                    if (reveal) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = stringResource(if (reveal) R.string.settings_hide_value else R.string.settings_show_value),
                )
            }
        }) else null,
    )
}

/** «Профили настроек»: применить, переименовать, удалить. */
@Composable
private fun ProfilesSheet(
    profiles: List<SettingsProfile>,
    settings: List<DeviceSetting>,
    onApply: (SettingsProfile) -> Unit,
    onRename: (SettingsProfile) -> Unit,
    onDelete: (SettingsProfile) -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_profiles_title), Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onClose) { Text(stringResource(R.string.common_done)) }
        }
        if (profiles.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_no_profiles), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_no_profiles_message), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SettingsGroup {
            profiles.forEach { p ->
                val summary = DeviceSettingsLogic.profileSummary(p.plan(settings), p.values.size, settings.isNotEmpty())
                    .map { part -> if (part.arg != null) stringResource(part.res, part.arg) else stringResource(part.res) }
                    .joinToString(" · ")
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    modifier = Modifier.clickable(enabled = settings.isNotEmpty(), role = Role.Button) { onApply(p) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(p.name, fontWeight = FontWeight.Medium) },
                    supportingContent = { Text(summary + "\n" + Fmt.dateTime(p.created)) },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.common_more_options))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.settings_apply)) },
                                    leadingIcon = { Icon(Icons.Outlined.CheckCircle, contentDescription = null) },
                                    enabled = settings.isNotEmpty(),
                                    onClick = { menu = false; onApply(p) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.settings_rename)) },
                                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                    onClick = { menu = false; onRename(p) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.common_delete), color = FolioDanger) },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = FolioDanger) },
                                    onClick = { menu = false; onDelete(p) },
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}
