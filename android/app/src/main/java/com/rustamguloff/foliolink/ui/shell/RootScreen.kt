package com.rustamguloff.foliolink.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.ui.common.BannerHost
import com.rustamguloff.foliolink.ui.common.LocalAppModel
import com.rustamguloff.foliolink.ui.common.TransferBar
import com.rustamguloff.foliolink.ui.dashboard.DashboardScreen
import com.rustamguloff.foliolink.ui.device.ConnectSheetContent
import com.rustamguloff.foliolink.ui.device.DeviceScreen
import com.rustamguloff.foliolink.ui.device.PairingSheetContent
import com.rustamguloff.foliolink.ui.firmware.FirmwareScreen
import com.rustamguloff.foliolink.ui.dictionaries.DictionariesScreen
import com.rustamguloff.foliolink.ui.fonts.FontsScreen
import com.rustamguloff.foliolink.ui.library.AddArticleScreen
import com.rustamguloff.foliolink.ui.library.LibraryScreen
import com.rustamguloff.foliolink.ui.more.MoreScreen
import com.rustamguloff.foliolink.ui.notes.NotesScreen
import com.rustamguloff.foliolink.ui.notes.ReadwiseScreen
import com.rustamguloff.foliolink.ui.outbox.OutboxScreen
import com.rustamguloff.foliolink.ui.screensaver.ScreensaverStudioScreen
import com.rustamguloff.foliolink.ui.screensaver.SleepImagesScreen
import com.rustamguloff.foliolink.ui.screenshots.ScreenshotsScreen
import com.rustamguloff.foliolink.ui.settings.AppSettingsScreen
import com.rustamguloff.foliolink.ui.settings.DeviceSettingsScreen
import com.rustamguloff.foliolink.ui.stats.StatsScreen
import com.rustamguloff.foliolink.ui.stats.WrappedScreen

private data class TabItem(val tab: Tab, val title: Int, val icon: ImageVector)

private val tabs = listOf(
    TabItem(Tab.HOME, R.string.shell_tab_home, Icons.Outlined.Home),
    TabItem(Tab.STATS, R.string.shell_tab_stats, Icons.Outlined.BarChart),
    TabItem(Tab.LIBRARY, R.string.shell_tab_library, Icons.AutoMirrored.Outlined.LibraryBooks),
    TabItem(Tab.NOTES, R.string.shell_tab_notes, Icons.Outlined.FormatQuote),
    TabItem(Tab.MORE, R.string.shell_tab_more, Icons.Outlined.MoreHoriz),
)

/**
 * Корень интерфейса: нижняя панель вкладок, экран вкладки или верхний экран стека, баннер,
 * полоса передачи, окна подключения и сопряжения (как RootView в iOS).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootScreen(router: Router) {
    val model = LocalAppModel.current
    val outbox by model.outbox.collectAsStateWithLifecycle()
    val pairingPrompt by model.pairingPrompt.collectAsStateWithLifecycle()
    val loaded by model.loaded.collectAsStateWithLifecycle()
    val loadingText = stringResource(R.string.core_loading)

    BackHandler(enabled = router.stack.isNotEmpty()) { router.back() }

    // «Итоги года» — во весь экран, без нижней панели (как в iOS).
    val fullScreen = router.stack.lastOrNull() == Dest.WRAPPED
    Scaffold(
        bottomBar = {
            if (!fullScreen) Column {
                // BUG-040: кэш читается с диска на IO; пока не прочитан — тонкая полоса с подписью.
                if (!loaded) {
                    androidx.compose.material3.LinearProgressIndicator(
                        Modifier.fillMaxWidth().semantics { contentDescription = loadingText },
                    )
                }
                TransferBar()
                NavigationBar {
                    tabs.forEach { item ->
                        NavigationBarItem(
                            selected = router.tab == item.tab,
                            onClick = { router.select(item.tab) },
                            icon = {
                                if (item.tab == Tab.MORE && outbox.isNotEmpty()) {
                                    BadgedBox(badge = { Badge { Text("${outbox.size}") } }) { Icon(item.icon, null) }
                                } else {
                                    Icon(item.icon, null)
                                }
                            },
                            label = { Text(stringResource(item.title), maxLines = 1) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            val top = router.stack.lastOrNull()
            AnimatedContent(
                targetState = router.tab to top,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "screen",
            ) { (tab, dest) ->
                if (dest != null) DestinationScreen(dest) else TabScreen(tab)
            }
            BannerHost(Modifier.align(Alignment.TopCenter).statusBarsPadding())
        }
    }

    if (router.showConnect) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { router.showConnect = false }, sheetState = state) {
            ConnectSheetContent(onClose = { router.showConnect = false })
        }
    } else if (pairingPrompt) {
        // Ждёт закрытия окна подключения: одно окно за раз.
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { model.dismissPairingPrompt() }, sheetState = state) {
            PairingSheetContent(onClose = { model.dismissPairingPrompt() })
        }
    }
}

@Composable
private fun TabScreen(tab: Tab) {
    when (tab) {
        Tab.HOME -> DashboardScreen()
        Tab.STATS -> StatsScreen()
        Tab.LIBRARY -> LibraryScreen()
        Tab.NOTES -> NotesScreen()
        Tab.MORE -> MoreScreen()
    }
}

@Composable
private fun DestinationScreen(dest: Dest) {
    when (dest) {
        Dest.SCREENSAVER -> ScreensaverStudioScreen()
        Dest.SLEEP_IMAGES -> SleepImagesScreen()
        Dest.SCREENSHOTS -> ScreenshotsScreen()
        Dest.FONTS -> FontsScreen()
        Dest.DICTIONARIES -> DictionariesScreen()
        Dest.DEVICE_SETTINGS -> DeviceSettingsScreen()
        Dest.APP_SETTINGS -> AppSettingsScreen()
        Dest.FIRMWARE -> FirmwareScreen()
        Dest.DEVICE -> DeviceScreen()
        Dest.OUTBOX -> OutboxScreen()
        Dest.WRAPPED -> WrappedScreen()
        Dest.READWISE -> ReadwiseScreen()
        Dest.ADD_ARTICLE -> AddArticleScreen()
    }
}
