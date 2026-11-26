package com.rustamguloff.foliolink.ui.device

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.app.FolioApp
import com.rustamguloff.foliolink.core.ble.BleSyncManager
import com.rustamguloff.foliolink.core.ble.BleUiState
import com.rustamguloff.foliolink.ui.common.Fmt
import com.rustamguloff.foliolink.ui.common.LocalAppModel
import com.rustamguloff.foliolink.ui.settings.ActionRow
import com.rustamguloff.foliolink.ui.settings.InfoRow
import com.rustamguloff.foliolink.ui.settings.NoteRow
import com.rustamguloff.foliolink.ui.settings.SettingsGroup
import com.rustamguloff.foliolink.ui.settings.SwitchRow
import com.rustamguloff.foliolink.ui.settings.openAppSettings
import com.rustamguloff.foliolink.ui.theme.FolioSuccess

/** Цвет предупреждений Bluetooth (как .orange в iOS). */
private val Warning = androidx.compose.ui.graphics.Color(0xFFE08A00)

/**
 * «Синхронизация по Bluetooth» (iOS BleSyncSection, FEAT-006): переключатель, разрешения
 * BLUETOOTH_SCAN/CONNECT (до Android 12 — геолокация), состояние и шаг, MTU, «Синхронизировать
 * сейчас», «Искать другую читалку».
 */
@Composable
fun BleSyncSection() {
    val context = LocalContext.current
    val model = LocalAppModel.current
    val ble = remember { (context.applicationContext as FolioApp).ble }
    val state by ble.state.collectAsStateWithLifecycle()
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val hasToken by model.hasToken.collectAsStateWithLifecycle()
    // Токен для этой читалки сохранён (по Wi-Fi уже сопрягли) — иначе BLE работать не будет.
    val tokenKnown = remember(snapshot.deviceKey, snapshot.device, hasToken) {
        runCatching { model.blePlan() }.isSuccess
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        ble.onPermissionsChanged()
    }
    fun askPermissions() = permissions.launch(BleSyncManager.requiredPermissions())

    SettingsGroup(
        title = stringResource(R.string.device_ble_header),
        footer = stringResource(R.string.device_ble_footer),
    ) {
        SwitchRow(
            stringResource(R.string.device_ble_toggle),
            checked = state.enabled,
            onCheckedChange = { on ->
                ble.setEnabled(on)
                if (on && !BleSyncManager.hasPermissions(context)) askPermissions()
            },
            icon = Icons.Outlined.Bluetooth,
        )
        if (state.enabled) {
            BleStatusRow(state)
            BleTexts.stepRes(state.step)?.takeIf { state.status == BleUiState.Status.Syncing }?.let {
                InfoRow(stringResource(R.string.device_ble_step), stringResource(it))
            }
            val name = state.peripheralName
            if (name != null && state.knowsReader) {
                InfoRow(stringResource(R.string.device_ble_reader), BleTexts.readerLine(name, state.mtu))
            }
            state.lastWarning?.let { NoteRow(it, Icons.Outlined.Warning, Warning) }
            if (!tokenKnown) NoteRow(stringResource(R.string.device_ble_needs_pairing), Icons.Outlined.Info, Warning)
            if (state.status == BleUiState.Status.Unauthorized) {
                ActionRow(stringResource(R.string.device_ble_grant), onClick = { askPermissions() },
                    icon = Icons.Outlined.Bluetooth)
                ActionRow(stringResource(R.string.device_open_app_settings), onClick = { openAppSettings(context) },
                    icon = Icons.Outlined.Settings)
            }
            if (state.canSyncNow) {
                ActionRow(stringResource(R.string.device_ble_sync_now), onClick = { ble.syncNow() }, icon = Icons.Outlined.Sync)
            }
            if (state.knowsReader) {
                ActionRow(stringResource(R.string.device_ble_find_other), onClick = { ble.forgetReader() },
                    icon = Icons.AutoMirrored.Outlined.BluetoothSearching)
            }
        }
        InfoRow(
            stringResource(R.string.device_ble_last_sync),
            state.lastSyncMs?.let { Fmt.dateTime(it) } ?: stringResource(R.string.device_ble_never),
        )
    }
}

@Composable
private fun BleStatusRow(state: BleUiState) {
    val model = LocalAppModel.current
    val status = state.status
    val text = BleTexts.statusRes(status)?.let { stringResource(it) }
        ?: state.failure?.let { model.errorText(it) }
        ?: (status as? BleUiState.Status.Failed)?.reason.orEmpty()
    val color = when (BleTexts.tone(status)) {
        BleTexts.Tone.GOOD -> FolioSuccess
        BleTexts.Tone.WARNING -> Warning
        BleTexts.Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.device_state), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (BleTexts.isBusy(status)) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(text, Modifier.weight(1f, fill = false), color = color, textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodyLarge)
    }
}
