package com.rustamguloff.foliolink.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.core.model.Book
import com.rustamguloff.foliolink.core.model.BookStatus
import com.rustamguloff.foliolink.core.model.RemoteFile
import com.rustamguloff.foliolink.ui.common.BookCoverGlyph
import com.rustamguloff.foliolink.ui.common.Card
import com.rustamguloff.foliolink.ui.common.ChipRow
import com.rustamguloff.foliolink.ui.common.Fmt
import com.rustamguloff.foliolink.ui.common.FolioFilterChip
import com.rustamguloff.foliolink.ui.common.FolioTopBar
import com.rustamguloff.foliolink.ui.common.LocalAppModel
import com.rustamguloff.foliolink.ui.common.titleRes
import com.rustamguloff.foliolink.ui.notes.HighlightRow
import com.rustamguloff.foliolink.ui.theme.FolioDanger
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Карточка книги (iOS `BookDetailView`): прогресс, статистика, статус и полки, цитаты, сессии, удаление. */
@Composable
internal fun BookDetailScreen(path: String, onBack: () -> Unit, onManageShelves: () -> Unit) {
    val model = LocalAppModel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val sessions by model.sessions.collectAsStateWithLifecycle()
    val outbox by model.outbox.collectAsStateWithLifecycle()
    // Книга могла уйти из кэша (удалена): держим последнюю известную версию.
    var last by remember { mutableStateOf<Book?>(null) }
    val book = snapshot.books.firstOrNull { it.path == path }?.also { last = it } ?: last ?: Book(path = path)
    val own = remember(sessions, path) { sessions.filter { it.book == path }.sortedByDescending { it.start } }
    val stats = remember(book, sessions) { LibraryLogic.bookStats(book, sessions) }
    val highlights = remember(snapshot.highlights, path) { snapshot.highlights.filter { it.book == path } }
    val shelves = snapshot.library?.shelves.orEmpty()
    val deletePending = path in LibraryLogic.pendingDeletes(outbox)
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { FolioTopBar(book.displayTitle, onBack = onBack) },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "head") { BookHeader(book) }
            item(key = "stats") {
                Card(title = stringResource(R.string.library_stats), icon = Icons.Outlined.BarChart) {
                    LabeledRow(stringResource(R.string.library_reading_time), Fmt.duration(context, stats.seconds))
                    LabeledRow(stringResource(R.string.library_sessions_count), stats.sessions.toString())
                    LabeledRow(stringResource(R.string.library_pages_count), stats.pages.toString())
                    if (book.lastRead > 0) LabeledRow(stringResource(R.string.library_last_read), Fmt.date(book.lastRead))
                    stats.remainingSeconds?.let {
                        LabeledRow(stringResource(R.string.library_time_left), Fmt.duration(context, it))
                    }
                    LabeledRow(stringResource(R.string.library_file), book.path)
                }
            }
            item(key = "status") {
                Card(title = stringResource(R.string.library_status_and_shelves), icon = Icons.AutoMirrored.Outlined.Label) {
                    ChipRow(contentPadding = PaddingValues(0.dp)) {
                        FolioFilterChip(stringResource(R.string.library_status_none), book.bookStatus == null, {
                            if (book.bookStatus != null) model.launch { setStatus(null, book) }
                        })
                        BookStatus.entries.forEach { s ->
                            FolioFilterChip(stringResource(s.titleRes), book.bookStatus == s, {
                                if (book.bookStatus != s) model.launch { setStatus(s, book) }
                            }, s.icon)
                        }
                    }
                    if (shelves.isEmpty()) {
                        Text(
                            stringResource(R.string.library_no_shelves_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    shelves.forEach { shelf ->
                        val member = book.path in shelf.books
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .toggleable(value = member, role = Role.Checkbox) { on ->
                                    model.launch { setShelf(shelf, on, book) }
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = member, onCheckedChange = null)
                            Text(shelf.name, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    TextButton(onClick = onManageShelves, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Icon(Icons.Default.CollectionsBookmark, contentDescription = null)
                        Text(stringResource(R.string.library_manage_shelves), Modifier.padding(start = 8.dp))
                    }
                }
            }
            if (highlights.isNotEmpty()) {
                item(key = "hl-header") {
                    GroupTitle(stringResource(R.string.library_highlights_count, highlights.size))
                }
                itemsIndexed(highlights, key = { i, it -> "hl:$i:${it.key}" }) { _, h -> HighlightRow(h, showBook = false) }
            }
            if (own.isNotEmpty()) {
                item(key = "s-header") { GroupTitle(stringResource(R.string.library_sessions)) }
                items(own.take(50), key = { "s:" + it.id }) { s ->
                    val time = if (s.hasClock) {
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.start * 1000))
                    } else {
                        stringResource(R.string.library_no_date)
                    }
                    LabeledRow(time, stringResource(R.string.library_session_line, Fmt.duration(context, s.secs), s.pages))
                }
            }
            item(key = "delete") {
                Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        enabled = !deletePending,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = FolioDanger),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Text(stringResource(R.string.library_delete_file), Modifier.padding(start = 8.dp))
                    }
                    if (deletePending) FooterText(stringResource(R.string.library_delete_pending))
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.library_delete_file_confirm, book.displayTitle)) },
            text = { Text(stringResource(R.string.library_delete_file_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            val ok = model.deleteRemote(RemoteFile(name = book.fileName, path = book.path))
                            if (!ok) return@launch
                            // Очередь не обновляет список книг после удаления файла: перечитать с читалки
                            // (в области модели — экран сейчас закроется).
                            if (model.isConnected) model.launch { sync() }
                            onBack()
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = FolioDanger),
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun BookHeader(book: Book) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        BookCoverGlyph(book.displayTitle, size = 64.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(book.displayTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (book.author.isNotEmpty()) {
                Text(book.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (book.progress >= 0) {
                LinearProgressIndicator(progress = { book.progress.coerceAtMost(100) / 100f }, Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.library_read_pct, book.progress),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    stringResource(R.string.common_not_started),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** Заголовок группы внутри карточки книги (отступы задаёт список). */
@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        Modifier.padding(top = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
    )
}
