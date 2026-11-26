package com.rustamguloff.foliolink.ui.outbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.Note
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.app.AppModel
import com.rustamguloff.foliolink.core.model.OutboxItem
import com.rustamguloff.foliolink.ui.common.EmptyState
import com.rustamguloff.foliolink.ui.common.FolioTopBar
import com.rustamguloff.foliolink.ui.common.Fmt
import com.rustamguloff.foliolink.ui.common.LocalAppModel
import com.rustamguloff.foliolink.ui.settings.NoteRow
import com.rustamguloff.foliolink.ui.settings.ActionRow
import com.rustamguloff.foliolink.ui.settings.SettingsGroup
import com.rustamguloff.foliolink.ui.shell.LocalRouter
import com.rustamguloff.foliolink.ui.theme.FolioDanger

private val Warning = Color(0xFFE08A00)

private fun OutboxKind.icon(): ImageVector = when (this) {
    OutboxKind.BOOK -> Icons.AutoMirrored.Outlined.MenuBook
    OutboxKind.SLEEP_IMAGE -> Icons.Outlined.Image
    OutboxKind.FONT -> Icons.Outlined.TextFields
    OutboxKind.DICTIONARY -> Icons.Outlined.Translate
    OutboxKind.FILE -> Icons.Outlined.Description
    OutboxKind.DELETE -> Icons.Outlined.Delete
    OutboxKind.STATUS -> Icons.Outlined.Bookmark
    OutboxKind.GOAL -> Icons.Outlined.Flag
    OutboxKind.SHELF -> Icons.Outlined.ViewAgenda
    OutboxKind.NOTE -> Icons.AutoMirrored.Outlined.Note
    OutboxKind.SETTINGS -> Icons.Outlined.Tune
    OutboxKind.SELECT -> Icons.Outlined.CheckCircle
    OutboxKind.UNKNOWN -> Icons.AutoMirrored.Outlined.HelpOutline
}

/**
 * «Очередь отправки» (iOS OutboxView): все изменения, ждущие читалку, в порядке отправки.
 * Отклонённые — с ошибкой; повторить, удалить, «Отправить сейчас».
 */
@Composable
fun OutboxScreen() {
    val model = LocalAppModel.current
    val router = LocalRouter.current
    val outbox by model.outbox.collectAsStateWithLifecycle()
    val connection by model.connection.collectAsStateWithLifecycle()
    val draining by model.draining.collectAsStateWithLifecycle()
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val unreadable by model.outboxUnreadable.collectAsStateWithLifecycle()
    val connected = connection == AppModel.Connection.Connected
    var pendingDelete by remember { mutableStateOf<OutboxItem?>(null) }

    LaunchedEffect(Unit) { model.refreshOutbox() }

    Scaffold(
        topBar = {
            FolioTopBar(stringResource(R.string.outbox_title), actions = {
                when {
                    draining -> CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                    connected -> TextButton(onClick = { model.launch { retryOutbox() } }, enabled = outbox.isNotEmpty()) {
                        Text(stringResource(R.string.outbox_send_now))
                    }
                    else -> TextButton(onClick = { router.showConnect = true }) { Text(stringResource(R.string.common_connect)) }
                }
            })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // FBK-034: повреждённый индекс очереди не превращается молча в «пусто».
            if (unreadable > 0) {
                SettingsGroup(footer = stringResource(R.string.outbox_unreadable_footer)) {
                    NoteRow(
                        pluralStringResource(R.plurals.outbox_unreadable, unreadable, unreadable),
                        Icons.Outlined.ErrorOutline, Warning,
                    )
                    ActionRow(stringResource(R.string.outbox_unreadable_discard), onClick = { model.discardUnreadableOutbox() })
                }
            }
        Box(Modifier.fillMaxSize()) {
            if (outbox.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.outbox_empty_title),
                    icon = Icons.Outlined.Inbox,
                    message = stringResource(R.string.outbox_empty_message),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        SettingsGroup(
                            title = pluralStringResource(R.plurals.outbox_waiting, outbox.size, outbox.size),
                            footer = stringResource(if (connected) R.string.outbox_footer_connected else R.string.outbox_footer_offline),
                        ) {
                            // Чтение deviceKey подписывает список на смену читалки: «для другой читалки» пересчитается.
                            @Suppress("UNUSED_VARIABLE") val reader = snapshot.deviceKey
                            outbox.forEach { item ->
                                val other = model.isForOtherReader(item)
                                OutboxRow(
                                    item = item,
                                    forOtherReader = other,
                                    canSend = OutboxLogic.canSend(item, connected, other),
                                    onSend = { model.launch { retryOutbox(item) } },
                                    onDelete = { pendingDelete = item },
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.outbox_delete_title, item.title)) },
            text = { Text(stringResource(R.string.outbox_delete_message)) },
            confirmButton = {
                TextButton(onClick = { pendingDelete = null; model.removeFromOutbox(item) }) {
                    Text(stringResource(R.string.common_delete), color = FolioDanger)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun OutboxRow(
    item: OutboxItem,
    forOtherReader: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val sendLabel = stringResource(if (item.failed) R.string.outbox_retry else R.string.outbox_send_item)
    val deleteLabel = stringResource(R.string.outbox_remove)
    ListItem(
        modifier = Modifier.semantics(mergeDescendants = true) {
            customActions = buildList {
                if (canSend) add(CustomAccessibilityAction(sendLabel) { onSend(); true })
                add(CustomAccessibilityAction(deleteLabel) { onDelete(); true })
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Icon(OutboxLogic.kind(item.operation).icon(), contentDescription = null,
                tint = if (item.failed) FolioDanger else MaterialTheme.colorScheme.primary)
        },
        headlineContent = { Text(item.title, fontWeight = FontWeight.Medium) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (item.source.isNotEmpty()) Text(item.source, maxLines = 2)
                Text(Fmt.dateTime(item.added), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline)
                when (OutboxLogic.notice(item, forOtherReader)) {
                    OutboxNotice.NEEDS_NEWER_APP ->
                        Notice(stringResource(R.string.outbox_needs_newer_app), Icons.Outlined.SystemUpdate, Warning)
                    OutboxNotice.OTHER_READER ->
                        Notice(stringResource(R.string.outbox_other_reader), Icons.Outlined.SwapHoriz,
                            MaterialTheme.colorScheme.onSurfaceVariant)
                    OutboxNotice.NONE -> {}
                }
                item.lastError?.let { e ->
                    Notice(e, if (item.failed) Icons.Outlined.Error else Icons.Outlined.Schedule,
                        if (item.failed) FolioDanger else Warning)
                }
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.common_more_options))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (item.operation.isSupported) {
                        DropdownMenuItem(
                            text = { Text(sendLabel) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null) },
                            enabled = canSend,
                            onClick = { menu = false; onSend() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(deleteLabel, color = FolioDanger) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = FolioDanger) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        },
    )
}

@Composable
private fun Notice(text: String, icon: ImageVector, color: Color) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}
