package com.rustamguloff.foliolink.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Battery0Bar
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Looks3
import androidx.compose.material.icons.outlined.LooksOne
import androidx.compose.material.icons.outlined.LooksTwo
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.app.AppModel
import com.rustamguloff.foliolink.app.Integration
import com.rustamguloff.foliolink.core.model.OutboxItem
import com.rustamguloff.foliolink.core.net.isDemo
import com.rustamguloff.foliolink.ui.common.BookProgressRow
import com.rustamguloff.foliolink.ui.common.Card
import com.rustamguloff.foliolink.ui.common.Fmt
import com.rustamguloff.foliolink.ui.common.FolioTopBar
import com.rustamguloff.foliolink.ui.common.LocalAppModel
import com.rustamguloff.foliolink.ui.common.OfflineNotice
import com.rustamguloff.foliolink.ui.common.ProgressRing
import com.rustamguloff.foliolink.ui.common.SyncButton
import com.rustamguloff.foliolink.ui.shell.Dest
import com.rustamguloff.foliolink.ui.shell.LocalRouter
import com.rustamguloff.foliolink.ui.shell.Tab
import com.rustamguloff.foliolink.ui.theme.FolioDanger
import com.rustamguloff.foliolink.ui.theme.FolioGold
import com.rustamguloff.foliolink.ui.theme.FolioSuccess

// «Главная» — перенос iOS Features/Dashboard/DashboardView.swift.

private val StreakOrange = Color(0xFFF08A24)

/** Главная: читалка, цель на сегодня, очередь отправки, «Сейчас читаю»; без читалки — приветствие. */
@Composable
fun DashboardScreen() {
    val model = LocalAppModel.current
    val router = LocalRouter.current
    val connection by model.connection.collectAsStateWithLifecycle()
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val outbox by model.outbox.collectAsStateWithLifecycle()
    val syncing by model.syncing.collectAsStateWithLifecycle()
    val connected = connection == AppModel.Connection.Connected

    Scaffold(
        topBar = { FolioTopBar(stringResource(R.string.dashboard_title), actions = { SyncButton() }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OfflineNotice()
            PullToRefreshBox(
                isRefreshing = syncing,
                onRefresh = { if (connected) model.launch { sync() } else router.showConnect = true },
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (DashboardLogic.showWelcome(snapshot.device != null, connected)) {
                        WelcomeCard()
                    } else {
                        DeviceStatusCard()
                        TodayCard()
                        if (outbox.isNotEmpty()) OutboxCard(outbox, connected)
                        CurrentlyReadingCard()
                    }
                }
            }
        }
    }
}

/** Первый запуск: что умеет FolioLink и как подключить читалку. */
@Composable
private fun WelcomeCard() {
    val model = LocalAppModel.current
    val router = LocalRouter.current
    Card {
        Text(stringResource(R.string.dashboard_welcome_title), style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.dashboard_welcome_text), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WelcomeStep(Icons.Outlined.LooksOne, stringResource(R.string.dashboard_welcome_step1))
            WelcomeStep(Icons.Outlined.LooksTwo, stringResource(R.string.dashboard_welcome_step2))
            WelcomeStep(Icons.Outlined.Looks3, stringResource(R.string.dashboard_welcome_step3))
        }
        Button(onClick = { router.showConnect = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dashboard_connect_reader))
        }
        OutlinedButton(onClick = { model.launch { connectDemo() } }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dashboard_try_demo))
        }
    }
}

@Composable
private fun WelcomeStep(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Читалка: состояние связи, модель, прошивка, батарея, карта памяти, последняя синхронизация. */
@Composable
private fun DeviceStatusCard() {
    val model = LocalAppModel.current
    val router = LocalRouter.current
    val context = LocalContext.current
    val connection by model.connection.collectAsStateWithLifecycle()
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val syncing by model.syncing.collectAsStateWithLifecycle()
    val syncStep by model.syncStep.collectAsStateWithLifecycle()
    val connected = connection == AppModel.Connection.Connected
    val endpoint = model.endpoint
    val status = DashboardLogic.status(connection, endpoint?.isDemo == true, snapshot.device != null)
    val info = snapshot.info

    Card {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(9.dp).background(statusColor(status), CircleShape))
                    Text(statusText(status, endpoint?.id.orEmpty()), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                val board = info?.board?.takeIf { it.isNotEmpty() }?.let(Fmt::boardName)
                    ?: stringResource(R.string.dashboard_reader)
                Text(board, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                info?.firmware?.takeIf { it.isNotEmpty() }?.let {
                    Text(stringResource(R.string.dashboard_firmware, it), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.Outlined.TabletAndroid, contentDescription = null, Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary)
        }

        if (info != null && (info.battery >= 0 || info.sdTotalMB > 0)) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                if (info.battery >= 0) {
                    val label = stringResource(R.string.dashboard_battery_a11y, info.battery)
                    Row(
                        Modifier.clearAndSetSemantics { contentDescription = label },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(batteryIcon(DashboardLogic.battery(info.battery, info.charging)), contentDescription = null,
                            Modifier.size(20.dp))
                        Text("${info.battery}%", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                val used = DashboardLogic.sdUsed(info.sdTotalMB, info.sdFreeMB)
                if (used != null) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            stringResource(R.string.dashboard_sd_free, Fmt.megabytes(context, info.sdFreeMB),
                                Fmt.megabytes(context, info.sdTotalMB)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LinearProgressIndicator(
                            progress = { used },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val last = snapshot.lastSync
            Text(
                if (last != null) stringResource(R.string.dashboard_synced_at, Fmt.relative(last))
                else stringResource(R.string.dashboard_not_synced),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val small = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            if (connected) {
                FilledTonalButton(onClick = { model.launch { sync() } }, enabled = !syncing, contentPadding = small) {
                    if (syncing) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(syncStep ?: stringResource(R.string.dashboard_syncing), maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.width(140.dp))
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.dashboard_refresh))
                    }
                }
            } else {
                Button(onClick = { router.showConnect = true }, contentPadding = small,
                    enabled = connection != AppModel.Connection.Connecting) {
                    if (connection == AppModel.Connection.Connecting) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Wifi, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.common_connect))
                    }
                }
            }
        }
    }
}

private fun statusColor(s: DashboardLogic.Status): Color = when (s) {
    DashboardLogic.Status.CONNECTED, DashboardLogic.Status.DEMO -> FolioSuccess
    DashboardLogic.Status.CONNECTING -> StreakOrange
    DashboardLogic.Status.FAILED -> FolioDanger
    DashboardLogic.Status.NOT_CONNECTED, DashboardLogic.Status.OFFLINE -> Color.Gray
}

@Composable
private fun statusText(s: DashboardLogic.Status, endpointId: String): String = when (s) {
    DashboardLogic.Status.CONNECTED -> stringResource(R.string.dashboard_status_connected, endpointId)
    DashboardLogic.Status.DEMO -> stringResource(R.string.dashboard_status_demo)
    DashboardLogic.Status.CONNECTING -> stringResource(R.string.dashboard_status_connecting)
    DashboardLogic.Status.FAILED -> stringResource(R.string.dashboard_status_failed)
    DashboardLogic.Status.NOT_CONNECTED -> stringResource(R.string.dashboard_status_not_connected)
    DashboardLogic.Status.OFFLINE -> stringResource(R.string.dashboard_status_offline)
}

private fun batteryIcon(b: DashboardLogic.Battery): ImageVector = when (b) {
    DashboardLogic.Battery.CHARGING -> Icons.Default.BatteryChargingFull
    DashboardLogic.Battery.EMPTY -> Icons.Default.Battery0Bar
    DashboardLogic.Battery.QUARTER -> Icons.Default.Battery2Bar
    DashboardLogic.Battery.HALF -> Icons.Default.Battery4Bar
    DashboardLogic.Battery.THREE_QUARTERS -> Icons.Default.Battery6Bar
    DashboardLogic.Battery.FULL -> Icons.Default.BatteryFull
}

/** «Сегодня»: кольцо минут против цели, серия и рекорд. На крупном шрифте — столбиком. */
@Composable
private fun TodayCard() {
    val model = LocalAppModel.current
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val sessions by model.sessions.collectAsStateWithLifecycle()
    val goalMinutes by model.goalMinutes.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    val minutes = remember(snapshot, sessions) { Integration.todaySeconds(snapshot, sessions, now) / 60 }
    val streak = remember(snapshot, sessions) { Integration.streaks(snapshot, sessions, now) }
    val goal = maxOf(goalMinutes, 1)
    val fontScale = LocalDensity.current.fontScale
    // Как @ScaledMetric в iOS: кольцо растёт вместе с текстом.
    val scale = fontScale.coerceIn(1f, 2f)
    val ringSize = 112.dp * scale
    val ringLine = 14.dp * scale

    Card(title = stringResource(R.string.dashboard_today), icon = Icons.Outlined.WbSunny) {
        val ringA11y = stringResource(R.string.dashboard_today_a11y, minutes, goal)
        val ring: @Composable () -> Unit = {
            Box(
                Modifier.size(ringSize).clearAndSetSemantics { contentDescription = ringA11y },
                contentAlignment = Alignment.Center,
            ) {
                ProgressRing(minutes.toFloat() / goal, Modifier.fillMaxSize(), lineWidth = ringLine)
                Column(Modifier.padding(ringLine), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$minutes", fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(stringResource(R.string.dashboard_of_goal, goal), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
        val lines: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StatLine("${streak.first}", pluralStringResource(R.plurals.dashboard_streak_label, streak.first),
                    Icons.Default.LocalFireDepartment, StreakOrange)
                StatLine("${streak.second}", stringResource(R.string.dashboard_best_streak), Icons.Default.EmojiEvents, FolioGold)
                if (minutes >= goal) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.Verified, contentDescription = null, Modifier.size(16.dp), tint = FolioSuccess)
                        Text(stringResource(R.string.dashboard_goal_met), style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold, color = FolioSuccess)
                    }
                } else {
                    Text(stringResource(R.string.dashboard_minutes_left, goal - minutes), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (fontScale >= 1.5f) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { ring(); lines() }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                ring(); lines()
            }
        }
    }
}

@Composable
private fun StatLine(value: String, label: String, icon: ImageVector, color: Color) {
    Row(
        Modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, Modifier.size(20.dp), tint = color)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Очередь отправки: первые три изменения; касание открывает экран очереди. */
@Composable
private fun OutboxCard(outbox: List<OutboxItem>, connected: Boolean) {
    val router = LocalRouter.current
    val (shown, rest) = DashboardLogic.outboxPreview(outbox.size)
    Card(
        Modifier.clickable(onClickLabel = stringResource(R.string.dashboard_open_outbox)) { router.openInMore(Dest.OUTBOX) },
        title = stringResource(R.string.dashboard_outbox),
        icon = Icons.Outlined.CloudUpload,
    ) {
        outbox.take(shown).forEach { item -> OutboxLine(item) }
        if (rest > 0) {
            Text(stringResource(R.string.dashboard_outbox_more, rest), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (connected) R.string.dashboard_outbox_sending else R.string.dashboard_outbox_waiting),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OutboxLine(item: OutboxItem) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (item.failed) {
            Icon(Icons.Default.ErrorOutline, contentDescription = stringResource(R.string.dashboard_outbox_failed),
                Modifier.size(18.dp), tint = FolioDanger)
        } else {
            Icon(Icons.Outlined.CloudUpload, contentDescription = null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = if (item.failed) item.lastError.orEmpty() else item.source
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (item.failed) FolioDanger else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** «Сейчас читаю»: до пяти книг; касание открывает библиотеку. */
@Composable
private fun CurrentlyReadingCard() {
    val model = LocalAppModel.current
    val router = LocalRouter.current
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val books = remember(snapshot.books) { Integration.currentlyReading(snapshot.books).take(5) }
    Card(title = stringResource(R.string.dashboard_currently_reading), icon = Icons.AutoMirrored.Outlined.MenuBook) {
        if (books.isEmpty()) {
            Text(stringResource(R.string.dashboard_currently_reading_empty), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val openLabel = stringResource(R.string.dashboard_open_library)
            books.forEachIndexed { i, book ->
                BookProgressRow(
                    book,
                    Modifier.fillMaxWidth().clickable(onClickLabel = openLabel) { router.select(Tab.LIBRARY) },
                )
                if (i != books.lastIndex) HorizontalDivider()
            }
        }
    }
}
