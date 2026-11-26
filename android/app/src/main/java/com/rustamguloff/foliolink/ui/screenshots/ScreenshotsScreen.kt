package com.rustamguloff.foliolink.ui.screenshots

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.app.AppModel
import com.rustamguloff.foliolink.core.model.RemoteFile
import com.rustamguloff.foliolink.core.net.DeviceError
import com.rustamguloff.foliolink.ui.common.EmptyState
import com.rustamguloff.foliolink.ui.common.FolioTopBar
import com.rustamguloff.foliolink.ui.common.Fmt
import com.rustamguloff.foliolink.ui.common.ImageCache
import com.rustamguloff.foliolink.ui.common.LocalAppModel
import com.rustamguloff.foliolink.ui.common.OfflineNotice
import com.rustamguloff.foliolink.ui.common.RemoteImage
import com.rustamguloff.foliolink.ui.common.SyncButton
import kotlinx.coroutines.launch

// Скриншоты с читалки (порт ScreenshotsView.swift): сетка по книгам, просмотр с увеличением,
// сохранение в галерею, «Поделиться» PNG, удаление.

@Composable
fun ScreenshotsScreen() {
    var opened by remember { mutableStateOf<RemoteFile?>(null) }
    val file = opened
    if (file != null) {
        BackHandler { opened = null }
        ScreenshotDetail(file, onClose = { opened = null })
    } else {
        ScreenshotsGrid(onOpen = { opened = it })
    }
}

@Composable
private fun ScreenshotsGrid(onOpen: (RemoteFile) -> Unit) {
    val model = LocalAppModel.current
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val connection by model.connection.collectAsStateWithLifecycle()
    val prefs by model.settings.state.collectAsStateWithLifecycle()
    val connected = connection == AppModel.Connection.Connected
    val scope = rememberCoroutineScope()
    val galleryAccess = rememberGalleryAccess()
    var saving by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val shots = remember(snapshot.screenshots) { ScreenshotGroups.sorted(snapshot.screenshots) }
    val groups = remember(shots) { ScreenshotGroups.group(shots) }
    val headers = ScreenshotGroups.showsHeaders(groups)

    LaunchedEffect(Unit) { if (model.isConnected) model.refreshScreenshots() }

    Scaffold(
        topBar = {
            FolioTopBar(stringResource(R.string.screenshots_title)) {
                if (saving) {
                    CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(
                        onClick = {
                            galleryAccess {
                                scope.launch {
                                    saving = true
                                    try { model.saveScreenshots(shots) } finally { saving = false }
                                }
                            }
                        },
                        enabled = shots.isNotEmpty() && connected,
                    ) {
                        Icon(Icons.Outlined.SaveAlt, contentDescription = stringResource(R.string.screenshots_save_all))
                    }
                }
                SyncButton()
            }
        },
        bottomBar = {
            if (prefs.autoSaveScreenshots) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.screenshots_autosave_notice), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OfflineNotice()
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    scope.launch {
                        refreshing = true
                        try { model.refreshScreenshots() } finally { refreshing = false }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                if (shots.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.screenshots_empty_title),
                        icon = Icons.Outlined.Screenshot,
                        message = stringResource(R.string.screenshots_empty_message),
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(104.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        for (group in groups) {
                            if (headers) {
                                item(key = "h:" + group.key, span = { GridItemSpan(maxLineSpan) }) {
                                    Text(
                                        group.book ?: stringResource(R.string.screenshots_no_book),
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                                    )
                                }
                            }
                            items(group.files, key = { it.id }) { shot ->
                                ScreenshotThumb(shot, showBook = !headers, onClick = { onOpen(shot) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenshotThumb(file: RemoteFile, showBook: Boolean, onClick: () -> Unit) {
    val title = if (showBook) file.bookTitle ?: file.name else null
    val date = file.capturedAt?.let { Fmt.dateTime(it * 1000) }
    val label = listOfNotNull(file.bookTitle ?: file.name, date).joinToString(", ")
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = stringResource(R.string.screenshots_open), role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RemoteImage(
            file.fullPath, contentDescription = label,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.6f)
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
        )
        if (title != null) {
            Text(title, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center)
        }
        if (date != null) {
            Text(date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ScreenshotDetail(file: RemoteFile, onClose: () -> Unit) {
    val model = LocalAppModel.current
    val context = LocalContext.current
    val connection by model.connection.collectAsStateWithLifecycle()
    val connected = connection == AppModel.Connection.Connected
    val scope = rememberCoroutineScope()
    val galleryAccess = rememberGalleryAccess()
    var bitmap by remember(file.id) { mutableStateOf<Bitmap?>(ImageCache.cached(file.fullPath)) }
    var error by remember(file.id) { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val shareTitle = stringResource(R.string.common_share)

    LaunchedEffect(file.id) {
        if (bitmap != null) return@LaunchedEffect
        try {
            bitmap = ImageCache.load(model.client, file.fullPath)
            if (bitmap == null) error = model.errorText(DeviceError.Unreachable)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = model.errorText(e)
        }
    }

    Scaffold(
        topBar = { FolioTopBar(file.name, onBack = onClose) },
        bottomBar = {
            BottomAppBar {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    DetailAction(
                        Icons.Outlined.SaveAlt, stringResource(R.string.screenshots_to_gallery),
                        enabled = connected && !saving,
                    ) {
                        galleryAccess {
                            scope.launch {
                                saving = true
                                try { model.saveScreenshots(listOf(file)) } finally { saving = false }
                            }
                        }
                    }
                    DetailAction(Icons.Outlined.Share, shareTitle, enabled = bitmap != null) {
                        val b = bitmap ?: return@DetailAction
                        scope.launch {
                            val png = ShareFiles.writePng(context, file.name.substringBeforeLast('.', file.name) + ".png", b)
                            ShareFiles.share(context, png, "image/png", shareTitle)
                        }
                    }
                    DetailAction(Icons.Outlined.Delete, stringResource(R.string.common_delete), enabled = true) {
                        confirmDelete = true
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val b = bitmap
                val e = error
                when {
                    b != null -> ZoomableBitmap(
                        b.asImageBitmap(),
                        contentDescription = file.bookTitle ?: file.name,
                        modifier = Modifier.fillMaxSize().border(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    )
                    e != null -> Text(e, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    else -> CircularProgressIndicator()
                }
            }
            Text(
                stringResource(R.string.screenshots_detail_info, file.name, Fmt.bytes(context, file.size)),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (!connected) {
                Text(
                    stringResource(R.string.screenshots_save_needs_connection),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.screenshots_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        if (model.deleteRemote(file)) {
                            ImageCache.evict(file.fullPath)
                            onClose()
                        }
                    }
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun DetailAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null)
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}
