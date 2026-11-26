package com.rustamguloff.foliolink.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rustamguloff.foliolink.BuildConfig
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.app.AppModel
import com.rustamguloff.foliolink.core.model.DeviceEndpoint
import com.rustamguloff.foliolink.core.net.DemoDevice
import com.rustamguloff.foliolink.ui.common.LocalAppModel
import com.rustamguloff.foliolink.ui.settings.ActionRow
import com.rustamguloff.foliolink.ui.settings.InfoRow
import com.rustamguloff.foliolink.ui.settings.NoteRow
import com.rustamguloff.foliolink.ui.settings.SettingsGroup
import kotlinx.coroutines.launch

private const val MARK_MANUAL = "manual"
private const val MARK_DEMO = "demo"

/**
 * Содержимое окна «Подключить читалку» (iOS DeviceConnectView): найденные в сети читалки,
 * ручной адрес, демо-режим. Поиск идёт, пока окно открыто. На Android 17+ сначала просит доступ
 * к устройствам локальной сети; демо работает без него.
 */
@Composable
fun ConnectSheetContent(onClose: () -> Unit) {
    val model = LocalAppModel.current
    val scope = rememberCoroutineScope()
    val access = rememberLocalNetworkAccess()
    val found by model.discovered.collectAsStateWithLifecycle()
    val searching by model.isSearching.collectAsStateWithLifecycle()
    val connection by model.connection.collectAsStateWithLifecycle()
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    var address by rememberSaveable { mutableStateOf(model.settings.manualAddress) }
    var connecting by remember { mutableStateOf<String?>(null) }

    // Поиск — только пока окно открыто и есть доступ к локальной сети.
    LaunchedEffect(access.granted) { if (access.ui == LocalNetworkUi.READY) model.startDiscovery() }
    DisposableEffect(Unit) { onDispose { model.stopDiscovery() } }

    fun connect(ep: DeviceEndpoint, marker: String) {
        if (connecting != null) return
        val go = {
            scope.launch {
                connecting = marker
                val ok = model.connect(ep)
                connecting = null
                if (ok) onClose()
            }
            Unit
        }
        if (ep == DemoDevice.ENDPOINT) go() else access.run(go)
    }

    fun connectManual() {
        val ep = DeviceEndpoint.parse(address) ?: return
        connect(ep, MARK_MANUAL)
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()
            .padding(bottom = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.device_connect_title),
                Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close))
            }
        }

        if (access.ui != LocalNetworkUi.READY) LocalNetworkCard(access)

        SettingsGroup(
            title = stringResource(R.string.device_found_header),
            footer = stringResource(R.string.device_found_footer),
        ) {
            when {
                access.ui != LocalNetworkUi.READY ->
                    NoteRow(stringResource(R.string.device_found_need_access), Icons.Outlined.Router)
                found.isEmpty() -> Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(if (searching) R.string.device_searching else R.string.device_nothing_found),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (access.ui == LocalNetworkUi.READY) {
                found.forEach { item ->
                    ListItem(
                        modifier = Modifier.clickable(enabled = connecting == null, role = Role.Button) {
                            connect(item.endpoint, item.endpoint.id)
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { Icon(Icons.Outlined.TabletAndroid, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary) },
                        headlineContent = { Text(item.endpoint.name) },
                        supportingContent = { Text("${item.endpoint.id} · ${item.source}") },
                        trailingContent = if (connecting == item.endpoint.id) {
                            { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                        } else null,
                    )
                }
                ActionRow(stringResource(R.string.device_search_again), onClick = { model.startDiscovery() },
                    icon = Icons.Outlined.Refresh)
            }
        }

        SettingsGroup(
            title = stringResource(R.string.device_manual_header),
            footer = stringResource(if (BuildConfig.DEBUG) R.string.device_manual_footer_debug else R.string.device_manual_footer),
        ) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                label = { Text(stringResource(R.string.device_manual_hint)) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { connectManual() }),
            )
            ActionRow(
                stringResource(R.string.device_connect_button),
                onClick = { connectManual() },
                enabled = DeviceEndpoint.parse(address) != null && connecting == null,
                busy = connecting == MARK_MANUAL,
            )
        }

        SettingsGroup(footer = stringResource(R.string.device_demo_footer)) {
            ActionRow(
                stringResource(R.string.device_demo_button),
                onClick = { connect(DemoDevice.ENDPOINT, MARK_DEMO) },
                icon = Icons.Outlined.AutoAwesome,
                enabled = connecting == null,
                busy = connecting == MARK_DEMO,
            )
        }

        snapshot.device?.let { last ->
            SettingsGroup(title = stringResource(R.string.device_last_device)) {
                InfoRow(last.name, last.id)
                if (connection == AppModel.Connection.Connected) {
                    ActionRow(stringResource(R.string.device_disconnect), onClick = { model.disconnect() },
                        icon = Icons.Outlined.LinkOff, destructive = true)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Объяснение, зачем доступ к локальной сети, и кнопка «Разрешить» / «Открыть настройки». */
@Composable
private fun LocalNetworkCard(access: LocalNetworkAccess) {
    val settings = access.ui == LocalNetworkUi.OPEN_SETTINGS
    SettingsGroup(
        title = stringResource(R.string.device_lan_header),
        footer = if (settings) stringResource(R.string.device_lan_denied) else null,
    ) {
        NoteRow(stringResource(R.string.device_lan_explain), Icons.Outlined.Router)
        ActionRow(
            stringResource(if (settings) R.string.device_open_app_settings else R.string.device_lan_allow),
            onClick = { access.request() },
        )
    }
}
