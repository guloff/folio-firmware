package com.rustamguloff.foliolink.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Outbox
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.app.AppModel
import com.rustamguloff.foliolink.core.dictionaries.DictionaryRules
import com.rustamguloff.foliolink.core.net.isDemo
import com.rustamguloff.foliolink.ui.common.FolioTopBar
import com.rustamguloff.foliolink.ui.common.LocalAppModel
import com.rustamguloff.foliolink.ui.common.SyncButton
import com.rustamguloff.foliolink.ui.settings.NavRow
import com.rustamguloff.foliolink.ui.settings.SettingsGroup
import com.rustamguloff.foliolink.ui.shell.Dest
import com.rustamguloff.foliolink.ui.shell.LocalRouter
import com.rustamguloff.foliolink.ui.theme.FolioDanger
import com.rustamguloff.foliolink.ui.theme.FolioSuccess

/** Вкладка «Ещё» (iOS MoreView): состояние связи и разделы экрана, читалки и приложения. */
@Composable
fun MoreScreen() {
    val model = LocalAppModel.current
    val router = LocalRouter.current
    val outbox by model.outbox.collectAsStateWithLifecycle()
    val snapshot by model.snapshot.collectAsStateWithLifecycle()

    Scaffold(topBar = { FolioTopBar(stringResource(R.string.more_title), actions = { SyncButton() }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SettingsGroup { ConnectionRow() }

            SettingsGroup(title = stringResource(R.string.more_screen_header)) {
                NavRow(stringResource(R.string.more_screensaver), Icons.Outlined.Brush, onClick = { router.open(Dest.SCREENSAVER) })
                NavRow(stringResource(R.string.more_sleep_images), Icons.Outlined.PhotoLibrary,
                    onClick = { router.open(Dest.SLEEP_IMAGES) })
                NavRow(stringResource(R.string.more_screenshots), Icons.Outlined.Screenshot,
                    onClick = { router.open(Dest.SCREENSHOTS) }, badge = snapshot.screenshots.size)
                NavRow(stringResource(R.string.more_fonts), Icons.Outlined.TextFields, onClick = { router.open(Dest.FONTS) })
                // Folio 1.1+: без признака `dictionaries` экран объясняет, что нужна новая прошивка.
                NavRow(
                    stringResource(R.string.more_dictionaries), Icons.Outlined.Translate,
                    onClick = { router.open(Dest.DICTIONARIES) },
                    subtitle = if (DictionaryRules.supported(snapshot.info) == false) stringResource(R.string.more_dictionaries_update) else null,
                )
            }

            SettingsGroup(title = stringResource(R.string.more_reader_header)) {
                NavRow(stringResource(R.string.more_device), Icons.Outlined.TabletAndroid, onClick = { router.open(Dest.DEVICE) })
                NavRow(stringResource(R.string.more_device_settings), Icons.Outlined.Tune,
                    onClick = { router.open(Dest.DEVICE_SETTINGS) })
                NavRow(stringResource(R.string.more_firmware), Icons.Outlined.Memory, onClick = { router.open(Dest.FIRMWARE) })
                val queueState = if (outbox.isEmpty()) stringResource(R.string.more_outbox_empty)
                else pluralStringResource(R.plurals.more_outbox_changes, outbox.size, outbox.size)
                Column(Modifier.semantics { stateDescription = queueState }) {
                    NavRow(stringResource(R.string.more_outbox), Icons.Outlined.Outbox,
                        onClick = { router.open(Dest.OUTBOX) }, badge = outbox.size)
                }
            }

            SettingsGroup {
                NavRow(stringResource(R.string.more_app), Icons.Outlined.Settings, onClick = { router.open(Dest.APP_SETTINGS) })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Строка состояния связи: подключено → «Устройство», нет связи → окно подключения. */
@Composable
private fun ConnectionRow() {
    val model = LocalAppModel.current
    val router = LocalRouter.current
    val connection by model.connection.collectAsStateWithLifecycle()
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val endpoint = snapshot.device
    val connected = connection == AppModel.Connection.Connected
    val title = when (connection) {
        AppModel.Connection.Connected ->
            if (endpoint?.isDemo == true) stringResource(R.string.more_demo)
            else stringResource(R.string.more_connected_to, endpoint?.name ?: "Folio")
        AppModel.Connection.Connecting -> stringResource(R.string.more_connecting)
        else -> stringResource(R.string.more_not_connected)
    }
    val subtitle = when (val c = connection) {
        is AppModel.Connection.Failed -> c.message
        AppModel.Connection.Connected -> snapshot.info?.firmware?.takeIf { it.isNotEmpty() }
            ?.let { stringResource(R.string.more_firmware_version, it) }
        else -> stringResource(R.string.more_connect_hint)
    }
    ListItem(
        modifier = Modifier.clickable(role = Role.Button) {
            if (connected) router.open(Dest.DEVICE) else router.showConnect = true
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            when (connection) {
                AppModel.Connection.Connecting -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                AppModel.Connection.Connected -> Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = FolioSuccess)
                else -> Icon(Icons.Outlined.WifiOff, contentDescription = null,
                    tint = if (connection is AppModel.Connection.Failed) FolioDanger else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = subtitle?.let { { Text(it, maxLines = 3) } },
        trailingContent = if (!connected && connection != AppModel.Connection.Connecting) {
            { TextButton(onClick = { router.showConnect = true }) { Text(stringResource(R.string.common_connect)) } }
        } else null,
    )
}
