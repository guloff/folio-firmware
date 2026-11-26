package com.rustamguloff.foliolink.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.AutoAwesomeMotion
import androidx.compose.material.icons.outlined.CalendarViewMonth
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.app.AppModel
import com.rustamguloff.foliolink.core.stats.StatsEngine
import com.rustamguloff.foliolink.ui.common.Card
import com.rustamguloff.foliolink.ui.common.Fmt
import com.rustamguloff.foliolink.ui.common.FolioTopBar
import com.rustamguloff.foliolink.ui.common.LocalAppModel
import com.rustamguloff.foliolink.ui.common.OfflineNotice
import com.rustamguloff.foliolink.ui.common.SyncButton
import com.rustamguloff.foliolink.ui.shell.Dest
import com.rustamguloff.foliolink.ui.shell.LocalRouter

// «Статистика» — перенос iOS Features/Stats/StatsView.swift.

/** Секунды чтения по дням: журнал сессий и `stats.days` читалки (как `model.daily` в iOS). */
@Composable
fun rememberDaily(model: AppModel): Map<String, Int> {
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val sessions by model.sessions.collectAsStateWithLifecycle()
    val days = snapshot.stats?.days
    return remember(sessions, days) { StatsEngine.dailySeconds(sessions, days ?: emptyList()) }
}

@Composable
fun StatsScreen() {
    val model = LocalAppModel.current
    val router = LocalRouter.current
    val connection by model.connection.collectAsStateWithLifecycle()
    val syncing by model.syncing.collectAsStateWithLifecycle()
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val sessions by model.sessions.collectAsStateWithLifecycle()
    val goalMinutes by model.goalMinutes.collectAsStateWithLifecycle()
    val daily = rememberDaily(model)
    var selected by remember { mutableStateOf<StatsEngine.HeatCell?>(null) }

    Scaffold(
        topBar = {
            FolioTopBar(stringResource(R.string.stats_title), actions = {
                IconButton(onClick = { router.open(Dest.WRAPPED) }) {
                    Icon(Icons.Outlined.AutoAwesomeMotion, contentDescription = stringResource(R.string.stats_wrapped))
                }
                SyncButton()
            })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OfflineNotice()
            PullToRefreshBox(
                isRefreshing = syncing,
                onRefresh = { if (connection == AppModel.Connection.Connected) model.launch { sync() } },
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    TotalsGrid(remember(snapshot.stats, sessions, daily) { StatsLogic.totals(snapshot.stats, sessions, daily) })
                    Card(title = stringResource(R.string.stats_calendar), icon = Icons.Outlined.CalendarViewMonth) {
                        HeatmapView(daily, selected?.key, onSelect = { selected = it })
                    }
                    WeeklyChartCard(daily, goalMinutes)
                    HourChartCard(sessions)
                    PerBookCard(sessions, snapshot.books)
                    ReviewCard(sessions, daily, snapshot.books, goalMinutes)
                }
            }
        }
    }

    selected?.let { cell ->
        DayDetailSheet(cell, sessions, goalMinutes, onDismiss = { selected = null })
    }
}

/** Четыре плитки: часы, страницы, сессии, прочитанные книги. */
@Composable
private fun TotalsGrid(t: StatsLogic.Totals) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(Fmt.hours(t.seconds), stringResource(R.string.stats_hours_total), Icons.Outlined.Schedule, Modifier.weight(1f))
            Tile("${t.pages}", pluralStringResource(R.plurals.stats_noun_page, t.pages), Icons.Outlined.Description,
                Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("${t.sessions}", pluralStringResource(R.plurals.stats_noun_session, t.sessions), Icons.Outlined.Timer,
                Modifier.weight(1f))
            Tile("${t.books}", pluralStringResource(R.plurals.stats_tile_books, t.books), Icons.AutoMirrored.Outlined.LibraryBooks,
                Modifier.weight(1f))
        }
    }
}

@Composable
private fun Tile(value: String, label: String, icon: ImageVector, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(14.dp).semantics(mergeDescendants = true) {}) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
