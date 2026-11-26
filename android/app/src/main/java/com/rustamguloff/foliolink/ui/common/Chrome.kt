package com.rustamguloff.foliolink.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.app.AppModel
import com.rustamguloff.foliolink.ui.shell.LocalRouter
import com.rustamguloff.foliolink.ui.theme.FolioDanger
import com.rustamguloff.foliolink.ui.theme.FolioSuccess
import kotlinx.coroutines.delay

// Элементы, связанные с состоянием приложения: верхняя панель, кнопка синхронизации, баннер,
// полоса передачи, заглушка «нет связи», плашка «офлайн».

/** AppModel для экранов (задаётся в MainActivity). */
val LocalAppModel = staticCompositionLocalOf<AppModel> { error("AppModel не задан") }

/**
 * Верхняя панель экрана. Со стрелкой «назад», если экран открыт поверх вкладки ([LocalRouter]
 * не пуст) или передан [onBack].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolioTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val router = LocalRouter.current
    val back = onBack ?: if (router.stack.isNotEmpty()) ({ router.back(); Unit }) else null
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (back != null) {
                IconButton(onClick = back) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
            }
        },
        actions = actions,
        scrollBehavior = scrollBehavior,
    )
}

/** Кнопка панели: синхронизировать, а без связи — открыть подключение. */
@Composable
fun SyncButton() {
    val model = LocalAppModel.current
    val router = LocalRouter.current
    val connection by model.connection.collectAsStateWithLifecycle()
    val syncing by model.syncing.collectAsStateWithLifecycle()
    val connected = connection == AppModel.Connection.Connected
    if (syncing || connection == AppModel.Connection.Connecting) {
        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
    } else {
        IconButton(onClick = { if (connected) model.launch { sync() } else router.showConnect = true }) {
            Icon(
                if (connected) Icons.Default.Sync else Icons.Default.WifiFind,
                contentDescription = stringResource(if (connected) R.string.common_sync else R.string.shell_connect_reader),
            )
        }
    }
}

/** Баннер сверху: сообщения о синхронизации и ошибках. Ошибки висят до касания. */
@Composable
fun BannerHost(modifier: Modifier = Modifier) {
    val model = LocalAppModel.current
    val banner by model.banner.collectAsStateWithLifecycle()
    AnimatedVisibility(
        visible = banner != null,
        modifier = modifier,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        val b = banner ?: return@AnimatedVisibility
        val color = when (b.kind) {
            AppModel.Banner.Kind.INFO -> MaterialTheme.colorScheme.primary
            AppModel.Banner.Kind.SUCCESS -> FolioSuccess
            AppModel.Banner.Kind.ERROR -> FolioDanger
        }
        val icon = when (b.kind) {
            AppModel.Banner.Kind.INFO -> Icons.Default.Info
            AppModel.Banner.Kind.SUCCESS -> Icons.Default.CheckCircle
            AppModel.Banner.Kind.ERROR -> Icons.Default.Warning
        }
        Surface(
            color = color,
            contentColor = Color.White,
            shape = RoundedCornerShape(14.dp),
            shadowElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clickable { model.dismissBanner() }
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, contentDescription = null)
                Text(b.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
        LaunchedEffect(b.id) {
            if (b.kind == AppModel.Banner.Kind.ERROR) return@LaunchedEffect
            delay(2500)
            if (model.banner.value?.id == b.id) model.dismissBanner()
        }
    }
}

/** Байтовый прогресс идущей загрузки/скачивания. */
@Composable
fun TransferBar(modifier: Modifier = Modifier) {
    val model = LocalAppModel.current
    val context = LocalContext.current
    val t by model.transfer.collectAsStateWithLifecycle()
    val transfer = t ?: return
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 4.dp,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SwapVert, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(transfer.title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                    maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                if (transfer.count > 1) {
                    Text(stringResource(R.string.shell_transfer_index, transfer.index, transfer.count),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val f = transfer.fraction
            if (f != null) {
                LinearProgressIndicator(progress = { f }, Modifier.fillMaxWidth())
                Text(stringResource(R.string.shell_transfer_bytes, Fmt.bytes(context, transfer.done), Fmt.bytes(context, transfer.total)),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

/** Содержимое, которому нужна связь с читалкой; без неё — подсказка и кнопка «Подключить». */
@Composable
fun RequiresConnection(content: @Composable () -> Unit) {
    val model = LocalAppModel.current
    val router = LocalRouter.current
    val connection by model.connection.collectAsStateWithLifecycle()
    if (connection == AppModel.Connection.Connected) {
        content()
    } else {
        EmptyState(
            title = stringResource(R.string.shell_not_connected_title),
            icon = Icons.Default.WifiOff,
            message = stringResource(R.string.shell_not_connected_message),
            action = { Button(onClick = { router.showConnect = true }) { Text(stringResource(R.string.common_connect)) } },
        )
    }
}

/** Плашка «офлайн · данные на …», чтобы кэш не принимали за живые данные. */
@Composable
fun OfflineNotice(modifier: Modifier = Modifier) {
    val model = LocalAppModel.current
    val router = LocalRouter.current
    val connection by model.connection.collectAsStateWithLifecycle()
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val last = snapshot.lastSync
    if (connection == AppModel.Connection.Connected || last == null) return
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.CloudOff, contentDescription = null, Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.shell_offline, Fmt.dateTime(last)), Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { router.showConnect = true }) { Text(stringResource(R.string.common_connect)) }
        }
    }
}
