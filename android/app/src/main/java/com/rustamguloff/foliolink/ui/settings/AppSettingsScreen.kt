package com.rustamguloff.foliolink.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Outbox
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rustamguloff.foliolink.BuildConfig
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.core.imaging.DitherMethod
import com.rustamguloff.foliolink.core.services.ReminderScheduler
import com.rustamguloff.foliolink.ui.common.FolioTopBar
import com.rustamguloff.foliolink.ui.common.LocalAppModel
import com.rustamguloff.foliolink.ui.shell.Dest
import com.rustamguloff.foliolink.ui.shell.LocalRouter
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Название метода дизеринга (как DitherMethod.title в iOS). */
fun ditherTitleRes(method: DitherMethod): Int = when (method) {
    DitherMethod.FLOYD_STEINBERG -> R.string.settings_dither_floyd
    DitherMethod.ATKINSON -> R.string.settings_dither_atkinson
    DitherMethod.BAYER -> R.string.settings_dither_bayer
    DitherMethod.NONE -> R.string.settings_dither_none
}

/**
 * «Приложение» (iOS AppSettingsView): цель на день, напоминание, дизеринг по умолчанию,
 * автосохранение скриншотов, очередь, источник обновлений прошивки, данные, о приложении.
 */
@Composable
fun AppSettingsScreen() {
    val model = LocalAppModel.current
    val router = LocalRouter.current
    val context = LocalContext.current
    val res = LocalResources.current
    val uri = LocalUriHandler.current
    val prefs by model.settings.state.collectAsStateWithLifecycle()
    val modelGoal by model.goalMinutes.collectAsStateWithLifecycle()
    val outbox by model.outbox.collectAsStateWithLifecycle()
    val sessions by model.sessions.collectAsStateWithLifecycle()
    val snapshot by model.snapshot.collectAsStateWithLifecycle()

    var goal by remember { mutableIntStateOf(model.goalMinutes.value) }
    var pickTime by remember { mutableStateOf(false) }
    var ditherMenu by remember { mutableStateOf(false) }
    var notificationsDenied by remember { mutableStateOf(false) }
    var repo by remember { mutableStateOf(prefs.firmwareRepo) }
    var catalogUrl by remember { mutableStateOf(prefs.dictionaryCatalogUrl) }

    // Шаги цели копятся 600 мс и уходят одним изменением (как в iOS).
    LaunchedEffect(goal) {
        if (goal == model.goalMinutes.value) return@LaunchedEffect
        delay(600)
        model.setGoal(goal)
    }

    // Цель, пришедшая с читалки при синхронизации, видна сразу.
    LaunchedEffect(modelGoal) { goal = modelGoal }

    fun setReminder(on: Boolean) {
        model.settings.reminderEnabled = on
        model.rescheduleReminders()
    }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsDenied = !granted
        setReminder(granted)
    }
    // До Android 10 галерея — через WRITE_EXTERNAL_STORAGE; без него автосохранение не включается.
    val storage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.setAutoSaveScreenshots(granted)
    }

    Scaffold(topBar = { FolioTopBar(stringResource(R.string.settings_app_title)) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SettingsGroup(
                title = stringResource(R.string.settings_reading_header),
                footer = stringResource(R.string.settings_goal_footer),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_goal), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    IconButton(onClick = { goal = AppSettingsLogic.stepGoal(goal, up = false) }, enabled = goal > AppSettingsLogic.GOAL_MIN) {
                        Icon(Icons.Outlined.Remove, contentDescription = stringResource(R.string.settings_goal_less))
                    }
                    Text(stringResource(R.string.common_duration_m, goal), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { goal = AppSettingsLogic.stepGoal(goal, up = true) }, enabled = goal < AppSettingsLogic.GOAL_MAX) {
                        Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.settings_goal_more))
                    }
                }
            }
            SettingsGroup(footer = stringResource(R.string.settings_reminder_footer)) {
                SwitchRow(
                    stringResource(R.string.settings_reminder),
                    checked = prefs.reminderEnabled,
                    icon = Icons.Outlined.Notifications,
                    onCheckedChange = { on ->
                        val needsPermission = on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            !ReminderScheduler.canNotify(context)
                        if (needsPermission) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else setReminder(on)
                    },
                )
                if (prefs.reminderEnabled) {
                    val time = LocalTime.of(prefs.reminderHour, prefs.reminderMinute)
                    ListItem(
                        modifier = Modifier.clickable(role = Role.Button) { pickTime = true },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(stringResource(R.string.settings_reminder_time)) },
                        trailingContent = {
                            Text(time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)),
                                color = MaterialTheme.colorScheme.primary)
                        },
                    )
                }
                if (notificationsDenied && !prefs.reminderEnabled) {
                    NoteRow(stringResource(R.string.settings_notifications_denied), Icons.Outlined.Info)
                    ActionRow(stringResource(R.string.settings_open_app_settings), onClick = { openAppSettings(context) })
                }
            }

            SettingsGroup(title = stringResource(R.string.settings_sleep_header)) {
                val method = DitherMethod.from(prefs.ditherMethod) ?: DitherMethod.FLOYD_STEINBERG
                Box {
                    ListItem(
                        modifier = Modifier.clickable(role = Role.DropdownList) { ditherMenu = true }
                            .semantics { stateDescription = res.getString(ditherTitleRes(method)) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(stringResource(R.string.settings_dither_default)) },
                        trailingContent = { Text(stringResource(ditherTitleRes(method)), color = MaterialTheme.colorScheme.primary) },
                    )
                    DropdownMenu(expanded = ditherMenu, onDismissRequest = { ditherMenu = false },
                        modifier = Modifier.align(Alignment.TopEnd)) {
                        DitherMethod.entries.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(stringResource(ditherTitleRes(m))) },
                                onClick = { ditherMenu = false; model.settings.ditherMethod = m.raw },
                                trailingIcon = if (m == method) ({ Icon(Icons.Default.Check, contentDescription = null) }) else null,
                            )
                        }
                    }
                }
            }

            SettingsGroup(
                title = stringResource(R.string.settings_screenshots_header),
                footer = stringResource(R.string.settings_screenshots_footer),
            ) {
                SwitchRow(
                    stringResource(R.string.settings_autosave),
                    checked = prefs.autoSaveScreenshots,
                    icon = Icons.Outlined.Screenshot,
                    onCheckedChange = { on ->
                        val legacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(
                            context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        ) != PackageManager.PERMISSION_GRANTED
                        if (on && legacy) storage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        else model.setAutoSaveScreenshots(on)
                    },
                )
            }

            SettingsGroup {
                NavRow(
                    stringResource(R.string.settings_outbox),
                    Icons.Outlined.Outbox,
                    onClick = { router.open(Dest.OUTBOX) },
                    value = if (outbox.isEmpty()) stringResource(R.string.settings_outbox_empty) else "${outbox.size}",
                )
            }

            SettingsGroup(
                title = stringResource(R.string.settings_repo_header),
                footer = stringResource(R.string.settings_repo_footer),
            ) {
                OutlinedTextField(
                    value = repo,
                    onValueChange = { repo = it; model.settings.firmwareRepo = it.trim() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    placeholder = { Text(AppSettingsLogic.DEFAULT_FIRMWARE_REPO) },
                    label = { Text(stringResource(R.string.settings_repo_label)) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                    ),
                )
            }

            if (BuildConfig.DEBUG) {
                SettingsGroup(
                    title = stringResource(R.string.settings_dict_catalog_header),
                    footer = stringResource(R.string.settings_dict_catalog_footer),
                ) {
                    OutlinedTextField(
                        value = catalogUrl,
                        onValueChange = { catalogUrl = it; model.settings.dictionaryCatalogUrl = it.trim() },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        singleLine = true,
                        placeholder = { Text(BuildConfig.DICTIONARY_CATALOG_URL, maxLines = 1) },
                        label = { Text(stringResource(R.string.settings_dict_catalog_label)) },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Uri,
                        ),
                    )
                }
            }

            SettingsGroup(title = stringResource(R.string.settings_data_header)) {
                InfoRow(stringResource(R.string.settings_data_sessions), "${sessions.size}")
                InfoRow(stringResource(R.string.settings_data_highlights), "${snapshot.highlights.size}")
                InfoRow(stringResource(R.string.settings_data_words), "${snapshot.vocab.size}")
            }

            SettingsGroup(
                title = stringResource(R.string.settings_about_header),
                footer = stringResource(R.string.settings_about_footer),
            ) {
                InfoRow(stringResource(R.string.settings_version), BuildConfig.VERSION_NAME)
                val pages = listOf(
                    R.string.settings_link_site to R.string.settings_url_site,
                    R.string.settings_link_support to R.string.settings_url_support,
                    R.string.settings_link_privacy to R.string.settings_url_privacy,
                )
                pages.forEach { (title, url) ->
                    val link = stringResource(url)
                    ListItem(
                        modifier = Modifier.clickable(role = Role.Button) { runCatching { uri.openUri(link) } },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(stringResource(title), color = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary)
                        },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (pickTime) {
        val state = rememberTimePickerState(
            initialHour = prefs.reminderHour,
            initialMinute = prefs.reminderMinute,
            is24Hour = DateFormat.is24HourFormat(context),
        )
        AlertDialog(
            onDismissRequest = { pickTime = false },
            title = { Text(stringResource(R.string.settings_reminder_time)) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    pickTime = false
                    model.settings.setReminderTime(state.hour, state.minute)
                    model.rescheduleReminders()
                }) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
