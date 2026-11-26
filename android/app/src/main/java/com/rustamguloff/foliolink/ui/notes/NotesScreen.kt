package com.rustamguloff.foliolink.ui.notes

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.app.AppModel
import com.rustamguloff.foliolink.app.FolioApp
import com.rustamguloff.foliolink.core.export.MarkdownExporter
import com.rustamguloff.foliolink.core.export.ReadwiseCsv
import com.rustamguloff.foliolink.core.model.Highlight
import com.rustamguloff.foliolink.core.model.VocabWord
import com.rustamguloff.foliolink.ui.common.EmptyState
import com.rustamguloff.foliolink.ui.common.FolioTopBar
import com.rustamguloff.foliolink.ui.common.LocalAppModel
import com.rustamguloff.foliolink.ui.common.OfflineNotice
import com.rustamguloff.foliolink.ui.common.SyncButton
import com.rustamguloff.foliolink.ui.library.SearchField
import com.rustamguloff.foliolink.ui.shell.Dest
import com.rustamguloff.foliolink.ui.shell.LocalRouter
import com.rustamguloff.foliolink.ui.theme.FolioDanger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Вкладка «Заметки» (iOS `NotesView`): «Цитаты» — по книгам, с поиском, заметками, удалением,
 * «Поделиться» и экспортом; «Словарь» — слова с контекстом и карточки для повторения (SM-2).
 */
@Composable
fun NotesScreen() {
    val app = LocalContext.current.applicationContext as FolioApp
    val review = app.vocabReview
    LaunchedEffect(review) { review.load() }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Идущее повторение: слова сеанса (снимок на момент начала).
    // Пустой список — повторения нет (начать его без слов нельзя).
    var cards by rememberSaveable(stateSaver = listSaver<List<String>, String>({ it }, { it })) { mutableStateOf(emptyList()) }

    if (cards.isNotEmpty()) {
        BackHandler { cards = emptyList() }
        FlashcardsScreen(cards, onClose = { cards = emptyList() })
        return
    }

    val model = LocalAppModel.current
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            FolioTopBar(stringResource(R.string.notes_title), actions = {
                SyncButton()
                if (tab == 0) ExportMenu(enabled = snapshot.highlights.isNotEmpty())
            })
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OfflineNotice()
            SecondaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.notes_tab_highlights), maxLines = 1) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.notes_tab_vocab), maxLines = 1) })
            }
            RefreshBox {
                if (tab == 0) HighlightsTab() else VocabTab(onReview = { due -> cards = due.map { it.word } })
            }
        }
    }
}

/** Потянуть вниз — синхронизировать (без связи — окно подключения). */
@Composable
private fun RefreshBox(content: @Composable () -> Unit) {
    val model = LocalAppModel.current
    val router = LocalRouter.current
    val syncing by model.syncing.collectAsStateWithLifecycle()
    val connection by model.connection.collectAsStateWithLifecycle()
    PullToRefreshBox(
        isRefreshing = syncing,
        onRefresh = { if (connection == AppModel.Connection.Connected) model.launch { sync() } else router.showConnect = true },
        modifier = Modifier.fillMaxSize(),
    ) { content() }
}

// region Экспорт

@Composable
private fun ExportMenu(enabled: Boolean) {
    val model = LocalAppModel.current
    val router = LocalRouter.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(Icons.Default.IosShare, contentDescription = stringResource(R.string.notes_export))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.notes_export_markdown)) },
                leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                onClick = {
                    open = false
                    scope.launch { exportMarkdown(context, model, model.snapshot.value.highlights) }
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.notes_export_csv)) },
                leadingIcon = { Icon(Icons.Default.TableChart, contentDescription = null) },
                onClick = {
                    open = false
                    scope.launch { exportCsv(context, model, model.snapshot.value.highlights) }
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.notes_send_readwise)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                onClick = { open = false; router.open(Dest.READWISE) },
            )
        }
    }
}

/** Markdown по файлу на книгу → «Поделиться» (iOS `MarkdownExporter.exportFiles`). */
private suspend fun exportMarkdown(context: Context, model: AppModel, items: List<Highlight>) {
    if (items.isEmpty()) return
    try {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Sharing.clearOldExports(context) }
        val files = MarkdownExporter.exportFiles(items, model.snapshot.value.books, Sharing.shareDir(context))
        Sharing.shareFiles(context, files, "text/markdown", context.getString(R.string.notes_export_chooser))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        model.show(e)
    }
}

/** CSV в формате импорта Readwise → «Поделиться». */
private suspend fun exportCsv(context: Context, model: AppModel, items: List<Highlight>) {
    if (items.isEmpty()) return
    try {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Sharing.clearOldExports(context) }
        val file = ReadwiseCsv.exportFile(items, model.snapshot.value.books, Sharing.shareDir(context))
        Sharing.shareFiles(context, listOf(file), "text/csv", context.getString(R.string.notes_export_chooser))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        model.show(e)
    }
}

// endregion

// region Цитаты

@Composable
private fun HighlightsTab() {
    val model = LocalAppModel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    var search by rememberSaveable { mutableStateOf("") }
    var editingKey by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingKey by rememberSaveable { mutableStateOf<String?>(null) }
    val groups = remember(snapshot.highlights, snapshot.books, search) {
        NotesLogic.groups(snapshot.highlights, snapshot.books, search)
    }

    if (snapshot.highlights.isEmpty()) {
        // В списке, чтобы «потянуть вниз» работало и на пустом экране.
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                EmptyState(
                    title = stringResource(R.string.notes_no_highlights),
                    icon = Icons.Default.FormatQuote,
                    message = stringResource(R.string.notes_no_highlights_message),
                )
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "search") { SearchField(search, { search = it }, stringResource(R.string.notes_search_highlights)) }
        if (groups.isEmpty()) {
            item(key = "none") {
                Text(
                    stringResource(R.string.notes_nothing_found),
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        groups.forEach { group ->
            item(key = "g:" + group.key) {
                GroupHeader(group.title) { scope.launch { exportMarkdown(context, model, group.items) } }
            }
            // Индекс в ключе: локальные id старых цитат теоретически могут совпасть.
            itemsIndexed(group.items, key = { i, it -> "h:${group.key}#$i:${it.key}" }) { _, h ->
                HighlightListItem(
                    h,
                    bookTitle = group.title,
                    onEdit = { editingKey = h.key },
                    onDelete = { deletingKey = h.key },
                )
            }
        }
    }

    snapshot.highlights.firstOrNull { it.key == editingKey }?.let { h ->
        HighlightEditorSheet(h, onDismiss = { editingKey = null }, onDelete = { editingKey = null; deletingKey = h.key })
    }
    snapshot.highlights.firstOrNull { it.key == deletingKey }?.let { h ->
        AlertDialog(
            onDismissRequest = { deletingKey = null },
            title = { Text(stringResource(R.string.notes_delete_highlight_confirm)) },
            text = { Text(stringResource(R.string.notes_delete_highlight_message)) },
            confirmButton = {
                TextButton(
                    onClick = { deletingKey = null; model.launch { deleteHighlight(h) } },
                    colors = ButtonDefaults.textButtonColors(contentColor = FolioDanger),
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = { TextButton(onClick = { deletingKey = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/** Шапка группы: название книги и «Экспорт книги». */
@Composable
private fun GroupHeader(title: String, onExport: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onExport) {
            Icon(Icons.Default.IosShare, contentDescription = stringResource(R.string.notes_export_book, title))
        }
    }
}

@Composable
private fun HighlightListItem(h: Highlight, bookTitle: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    val model = LocalAppModel.current
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val chooserTitle = stringResource(R.string.notes_share_chooser)
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClickLabel = stringResource(R.string.notes_edit_note),
                onLongClickLabel = stringResource(R.string.common_more_options),
                onLongClick = { menu = true },
                onClick = onEdit,
            )
            .padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        HighlightRow(h, Modifier.weight(1f), showBook = false)
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.common_more_options))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.notes_note)) },
                    leadingIcon = { Icon(Icons.Default.EditNote, contentDescription = null) },
                    onClick = { menu = false; onEdit() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.common_share)) },
                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                    onClick = {
                        menu = false
                        val author = model.book(h.book)?.author.orEmpty()
                        Sharing.shareText(context, NotesLogic.shareText(h, bookTitle, author), chooserTitle)
                    },
                )
                if (h.canModify) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_delete), color = FolioDanger) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = FolioDanger) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

// endregion

// region Словарь

@Composable
private fun VocabTab(onReview: (List<VocabWord>) -> Unit) {
    val model = LocalAppModel.current
    val review = (LocalContext.current.applicationContext as FolioApp).vocabReview
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val states by review.states.collectAsStateWithLifecycle()
    var search by rememberSaveable { mutableStateOf("") }
    var deletingWord by rememberSaveable { mutableStateOf<String?>(null) }
    val words = remember(snapshot.vocab, search) { NotesLogic.words(snapshot.vocab, search) }
    val due = remember(snapshot.vocab, states) { review.dueWords(snapshot.vocab) }

    if (snapshot.vocab.isEmpty()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                EmptyState(
                    title = stringResource(R.string.notes_vocab_empty),
                    icon = Icons.Outlined.Translate,
                    message = stringResource(R.string.notes_vocab_empty_message),
                )
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "cards") { FlashcardsEntry(due.size) { onReview(due) } }
        item(key = "search") { SearchField(search, { search = it }, stringResource(R.string.notes_search_words)) }
        if (words.isEmpty()) {
            item(key = "none") {
                Text(
                    stringResource(R.string.notes_nothing_found),
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(words, key = { "w:" + it.word }) { w ->
            val st = states[w.word.lowercase()]
            WordRow(w, model.book(w.book)?.displayTitle, nextInDays = st?.takeIf { it.repetitions > 0 }?.interval) {
                deletingWord = w.word
            }
            HorizontalDivider(Modifier.padding(start = 16.dp))
        }
    }

    snapshot.vocab.firstOrNull { it.word == deletingWord }?.let { w ->
        AlertDialog(
            onDismissRequest = { deletingWord = null },
            title = { Text(stringResource(R.string.notes_delete_word_confirm, w.word)) },
            confirmButton = {
                TextButton(
                    onClick = { deletingWord = null; model.launch { deleteWord(w) } },
                    colors = ButtonDefaults.textButtonColors(contentColor = FolioDanger),
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = { TextButton(onClick = { deletingWord = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/** Плитка «Карточки · К повторению: N»; без слов к повторению недоступна. */
@Composable
private fun FlashcardsEntry(dueCount: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = dueCount > 0,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Default.Style, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.notes_flashcards), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (dueCount > 0) stringResource(R.string.notes_due, dueCount) else stringResource(R.string.notes_all_caught_up),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (dueCount > 0) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun WordRow(w: VocabWord, bookTitle: String?, nextInDays: Int?, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onLongClickLabel = stringResource(R.string.common_more_options),
                onLongClick = { menu = true },
                onClick = { menu = true },
            )
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(w.word, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                if (w.count > 1) {
                    Text(stringResource(R.string.notes_word_count, w.count), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (nextInDays != null) {
                Text(pluralStringResource(R.plurals.notes_next_review, nextInDays, nextInDays),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            if (w.context.isNotEmpty()) {
                Text(w.context, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (bookTitle != null) {
                Text(bookTitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.common_more_options))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.notes_delete_word), color = FolioDanger) },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = FolioDanger) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

// endregion
