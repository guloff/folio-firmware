package com.rustamguloff.foliolink.ui.device

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.GppGood
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Outbox
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.WifiFind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.app.AppModel
import com.rustamguloff.foliolink.ui.common.FolioTopBar
import com.rustamguloff.foliolink.ui.common.Fmt
import com.rustamguloff.foliolink.ui.common.LocalAppModel
import com.rustamguloff.foliolink.ui.common.SyncButton
import com.rustamguloff.foliolink.ui.settings.ActionRow
import com.rustamguloff.foliolink.ui.settings.InfoRow
import com.rustamguloff.foliolink.ui.settings.NavRow
import com.rustamguloff.foliolink.ui.settings.NoteRow
import com.rustamguloff.foliolink.ui.settings.SettingsGroup
import com.rustamguloff.foliolink.ui.shell.Dest
import com.rustamguloff.foliolink.ui.shell.LocalRouter
import com.rustamguloff.foliolink.ui.theme.FolioDanger
import com.rustamguloff.foliolink.ui.theme.FolioSuccess
import kotlinx.coroutines.CancellationException

/**
 * «Ещё → Устройство» (iOS DeviceInfoView): сопряжение, подключение, Bluetooth, сведения о
 * читалке и сети.
 */
@Composable
fun DeviceScreen() {
    val model = LocalAppModel.current
    val router = LocalRouter.current
    val context = LocalContext.current
    val res = LocalResources.current
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val connection by model.connection.collectAsStateWithLifecycle()
    val pairing by model.pairingState.collectAsStateWithLifecycle()
    val outbox by model.outbox.collectAsStateWithLifecycle()
    val connected = connection == AppModel.Connection.Connected
    var confirmForget by remember { mutableStateOf(false) }

    Scaffold(topBar = { FolioTopBar(stringResource(R.string.device_title), actions = { SyncButton() }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            if (snapshot.device != null) {
                SettingsGroup(
                    title = stringResource(R.string.device_pairing_header),
                    footer = stringResource(R.string.device_pairing_footer),
                ) {
                    when (pairing) {
                        AppModel.PairingState.PAIRED ->
                            NoteRow(stringResource(R.string.device_paired), Icons.Outlined.GppGood, FolioSuccess)
                        AppModel.PairingState.UNPAIRED -> {
                            NoteRow(stringResource(R.string.device_unpaired), Icons.Outlined.Lock, FolioDanger)
                            ActionRow(stringResource(R.string.device_pair_button), onClick = { model.requestPairing() },
                                icon = Icons.Outlined.Key, enabled = connected)
                        }
                        AppModel.PairingState.NOT_REQUIRED ->
                            NoteRow(stringResource(R.string.device_pairing_not_required), Icons.Outlined.Shield)
                    }
                    ActionRow(stringResource(R.string.device_forget), onClick = { confirmForget = true },
                        icon = Icons.Outlined.DeleteForever, destructive = true)
                }
                SettingsGroup {
                    NavRow(stringResource(R.string.device_outbox), Icons.Outlined.Outbox,
                        onClick = { router.open(Dest.OUTBOX) }, badge = outbox.size)
                }
            }

            SettingsGroup(title = stringResource(R.string.device_connection_header)) {
                InfoRow(
                    stringResource(R.string.device_state),
                    stringResource(
                        when (connection) {
                            AppModel.Connection.Connected -> R.string.device_ble_status_connected
                            AppModel.Connection.Connecting -> R.string.device_ble_status_connecting
                            else -> R.string.device_not_connected
                        },
                    ),
                    valueColor = if (connected) FolioSuccess else androidx.compose.ui.graphics.Color.Unspecified,
                )
                (connection as? AppModel.Connection.Failed)?.let { NoteRow(it.message, color = FolioDanger) }
                model.endpoint?.let { InfoRow(stringResource(R.string.device_address), it.id) }
                ActionRow(
                    stringResource(if (connected) R.string.device_change_device else R.string.common_connect),
                    onClick = { router.showConnect = true },
                    icon = if (connected) Icons.Outlined.SwapHoriz else Icons.Outlined.WifiFind,
                )
                if (connected) {
                    ActionRow(stringResource(R.string.device_send_time), icon = Icons.Outlined.AccessTime, onClick = {
                        model.launch {
                            try {
                                val c = client ?: throw com.rustamguloff.foliolink.core.net.DeviceError.Unreachable
                                c.setTime()
                                show(res.getString(R.string.device_time_set), AppModel.Banner.Kind.SUCCESS)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                show(e)
                            }
                        }
                    })
                    ActionRow(stringResource(R.string.device_disconnect), onClick = { model.disconnect() },
                        icon = Icons.Outlined.LinkOff, destructive = true)
                }
            }

            BleSyncSection()

            snapshot.info?.let { info ->
                SettingsGroup(title = stringResource(R.string.device_reader_header)) {
                    InfoRow(stringResource(R.string.device_model), Fmt.boardName(info.board))
                    InfoRow(stringResource(R.string.device_firmware), info.firmware.ifEmpty { "—" })
                    InfoRow(stringResource(R.string.device_api), "v${info.api}")
                    if (info.battery >= 0) {
                        InfoRow(
                            stringResource(R.string.device_battery),
                            stringResource(if (info.charging) R.string.device_battery_charging else R.string.device_battery_value,
                                info.battery),
                        )
                    }
                    if (info.sdTotalMB > 0) {
                        InfoRow(
                            stringResource(R.string.device_sd),
                            stringResource(R.string.device_sd_value, Fmt.megabytes(context, info.sdFreeMB),
                                Fmt.megabytes(context, info.sdTotalMB)),
                        )
                    }
                    InfoRow(
                        stringResource(R.string.device_clock),
                        when {
                            info.clock.valid -> Fmt.dateTime(info.clock.epoch * 1000)
                            info.clock.available -> stringResource(R.string.device_clock_not_set)
                            else -> stringResource(R.string.device_clock_no_rtc)
                        },
                    )
                }
            }

            snapshot.status?.let { s ->
                SettingsGroup(title = stringResource(R.string.device_network_header)) {
                    s.ip?.let { InfoRow("IP", it) }
                    s.mode?.let { InfoRow(stringResource(R.string.device_mode), it) }
                    s.rssi?.let { InfoRow(stringResource(R.string.device_signal), stringResource(R.string.device_signal_value, it)) }
                    s.freeHeap?.let { InfoRow(stringResource(R.string.device_free_heap), Fmt.bytes(context, it)) }
                    s.uptime?.let {
                        InfoRow(stringResource(R.string.device_uptime), Fmt.duration(context, it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
                    }
                    s.serial?.let { InfoRow(stringResource(R.string.device_serial), it) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(stringResource(R.string.device_forget_title)) },
            text = { Text(stringResource(R.string.device_forget_message)) },
            confirmButton = {
                TextButton(onClick = { confirmForget = false; model.forgetReader() }) {
                    Text(stringResource(R.string.device_forget_confirm), color = FolioDanger)
                }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
