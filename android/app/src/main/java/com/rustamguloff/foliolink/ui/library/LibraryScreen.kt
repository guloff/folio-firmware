package com.rustamguloff.foliolink.ui.library

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.app.AppModel
import com.rustamguloff.foliolink.core.model.Book
import com.rustamguloff.foliolink.core.model.BookStatus
import com.rustamguloff.foliolink.core.model.Shelf
import com.rustamguloff.foliolink.ui.common.BookProgressRow
import com.rustamguloff.foliolink.ui.common.ChipRow
import com.rustamguloff.foliolink.ui.common.EmptyState
import com.rustamguloff.foliolink.ui.common.FolioFilterChip
import com.rustamguloff.foliolink.ui.common.FolioTopBar
import com.rustamguloff.foliolink.ui.common.LocalAppModel
import com.rustamguloff.foliolink.ui.common.OfflineNotice
import com.rustamguloff.foliolink.ui.common.SectionHeader
import com.rustamguloff.foliolink.ui.common.SyncButton
import com.rustamguloff.foliolink.ui.common.shortTitleRes
import com.rustamguloff.foliolink.ui.common.titleRes
import com.rustamguloff.foliolink.ui.shell.Dest
import com.rustamguloff.foliolink.ui.shell.LocalRouter
import com.rustamguloff.foliolink.ui.share.ShareParsing
import kotlinx.coroutines.launch

/**
 * Вкладка «Библиотека» (порт iOS `LibraryView`): книги читалки с поиском, фильтрами статуса и
 * полок, сортировкой; карточка книги, полки, загрузка файлов с телефона и статьи по ссылке.
 *
 * Карточка книги и полки — экраны внутри вкладки (свой маленький стек, «назад» закрывает их).
 */
@Composable
fun LibraryScreen() {
    // Стек экранов вкладки: "book:<путь>", "shelves", "shelf:<id>".
    var stack by rememberSaveable(stateSaver = listSaver<List<String>, String>({ it }, { it })) { mutableStateOf(emptyList()) }
    fun push(route: String) { stack = stack + route }
    fun pop() { stack = stack.dropLast(1) }
    BackHandler(enabled = stack.isNotEmpty()) { pop() }

    val top = stack.lastOrNull()
    when {
        top == null -> LibraryList(onOpenBook = { push("book:" + it.path) }, onShelves = { push(ROUTE_SHELVES) })
        top.startsWith("book:") -> BookDetailScreen(
            path = top.removePrefix("book:"),
            onBack = ::pop,
            onManageShelves = { push(ROUTE_SHELVES) },
        )
        top == ROUTE_SHELVES -> ShelvesScreen(onBack = ::pop, onOpenShelf = { push("shelf:" + it.id) })
        top.startsWith("shelf:") -> ShelfEditScreen(shelfId = top.removePrefix("shelf:"), onBack = ::pop)
    }
}

private const val ROUTE_SHELVES = "shelves"

@Composable
private fun LibraryList(onOpenBook: (Book) -> Unit, onShelves: () -> Unit) {
    val model = LocalAppModel.current
    val router = LocalRouter.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val outbox by model.outbox.collectAsStateWithLifecycle()
    val syncing by model.syncing.collectAsStateWithLifecycle()
    val connection by model.connection.collectAsStateWithLifecycle()

    var statusName by rememberSaveable { mutableStateOf<String?>(null) }
    var shelfFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var sortName by rememberSaveable { mutableStateOf(LibrarySort.RECENT.name) }
    var uploading by remember { mutableStateOf(false) }

    val shelves = snapshot.library?.shelves.orEmpty()
    // Удалённая полка не должна оставлять пустой фильтр.
    val activeShelf = shelfFilter?.takeIf { id -> shelves.any { it.id == id } }
    val filter = LibraryFilter(
        status = statusName?.let { BookStatus.valueOf(it) },
        shelf = activeShelf,
        search = search,
        sort = LibrarySort.valueOf(sortName),
    )
    val hidden = remember(outbox) { LibraryLogic.pendingDeletes(outbox) }
    val books = remember(snapshot.books, filter, shelves, hidden) {
        LibraryLogic.filter(snapshot.books, filter, shelves, hidden)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            uploading = true
            try {
                uploadFiles(context, model, uris)
            } finally {
                uploading = false
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            FolioTopBar(
                stringResource(R.string.library_title),
                actions = {
                    SyncButton()
                    SortMenu(LibrarySort.valueOf(sortName)) { sortName = it.name }
                    AddMenu(
                        uploading = uploading,
                        onUpload = { picker.launch(LibraryLogic.IMPORT_MIME_TYPES) },
                        onArticle = { router.open(Dest.ADD_ARTICLE) },
                        onShelves = onShelves,
                    )
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OfflineNotice()
            PullToRefreshBox(
                isRefreshing = syncing,
                onRefresh = {
                    if (connection == AppModel.Connection.Connected) model.launch { sync() } else router.showConnect = true
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item(key = "search") {
                        SearchField(search, { search = it }, stringResource(R.string.library_search_hint))
                    }
                    item(key = "status") {
                        // Чипы вместо сегментов: длинные подписи и крупный шрифт не обрезаются.
                        ChipRow(Modifier.padding(vertical = 4.dp)) {
                            FolioFilterChip(stringResource(R.string.library_filter_all), filter.status == null, { statusName = null })
                            BookStatus.entries.forEach { s ->
                                FolioFilterChip(stringResource(s.shortTitleRes), filter.status == s, { statusName = s.name }, s.icon)
                            }
                        }
                    }
                    if (shelves.isNotEmpty()) {
                        item(key = "shelves") {
                            ChipRow(Modifier.padding(vertical = 4.dp)) {
                                FolioFilterChip(stringResource(R.string.library_filter_all_shelves), activeShelf == null, { shelfFilter = null })
                                shelves.forEach { shelf ->
                                    FolioFilterChip(shelf.name, activeShelf == shelf.id, { shelfFilter = shelf.id })
                                }
                            }
                        }
                    }
                    item(key = "header") { SectionHeader(stringResource(R.string.library_books_count, books.size)) }
                    if (books.isEmpty()) {
                        item(key = "empty") {
                            if (snapshot.books.isEmpty()) {
                                EmptyState(
                                    title = stringResource(R.string.library_empty_title),
                                    icon = Icons.AutoMirrored.Filled.LibraryBooks,
                                    message = stringResource(R.string.library_empty_sync),
                                    modifier = Modifier.padding(top = 24.dp),
                                )
                            } else {
                                Text(
                                    stringResource(R.string.library_nothing_found),
                                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    items(books, key = { it.path }) { book ->
                        BookListRow(book, shelves, onOpen = { onOpenBook(book) })
                    }
                }
            }
        }
    }
}

/** Загрузка выбранных файлов: каждый — в /Books (или в очередь), затем общий итог. */
private suspend fun uploadFiles(context: Context, model: AppModel, uris: List<android.net.Uri>) {
    val results = mutableListOf<Pair<String, AppModel.SubmitResult?>>()
    val unsupported = mutableListOf<String>()
    for (uri in uris) {
        val name = ContentFiles.displayName(context, uri)
        if (!LibraryLogic.isImportable(name)) {
            unsupported += name
            continue
        }
        // Книга копируется потоком в очередь и уходит потоком: большая книга не держится в памяти.
        val probe = ContentFiles.open(context, uri)
        if (probe == null) {
            results += name to null
            continue
        }
        probe.close()
        results += name to model.uploadBookStream({ ContentFiles.open(context, uri) }, name, ShareParsing.titleOf(name))
    }
    when (val s = LibraryLogic.uploadSummary(uris.size, results, unsupported)) {
        is LibraryLogic.UploadSummary.Unreadable ->
            model.show(context.getString(R.string.library_unreadable, s.names.joinToString(", ")), AppModel.Banner.Kind.ERROR)
        is LibraryLogic.UploadSummary.Unsupported ->
            model.show(context.getString(R.string.library_unsupported, s.names.joinToString(", ")), AppModel.Banner.Kind.ERROR)
        is LibraryLogic.UploadSummary.Sent ->
            model.show(context.getString(R.string.library_books_sent, s.count), AppModel.Banner.Kind.SUCCESS)
        is LibraryLogic.UploadSummary.Queued ->
            model.show(context.getString(R.string.library_books_queued, s.count), AppModel.Banner.Kind.INFO)
        null -> {}
    }
}

@Composable
private fun SortMenu(current: LibrarySort, onSelect: (LibrarySort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.library_sort))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LibrarySort.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(stringResource(s.titleRes)) },
                    onClick = { onSelect(s); open = false },
                    trailingIcon = if (s == current) ({ Icon(Icons.Default.Check, contentDescription = null) }) else null,
                    modifier = Modifier.semantics { selected = s == current },
                )
            }
        }
    }
}

private val LibrarySort.titleRes: Int
    get() = when (this) {
        LibrarySort.RECENT -> R.string.library_sort_recent
        LibrarySort.TITLE -> R.string.library_sort_title
        LibrarySort.AUTHOR -> R.string.library_sort_author
        LibrarySort.PROGRESS -> R.string.library_sort_progress
    }

@Composable
private fun AddMenu(uploading: Boolean, onUpload: () -> Unit, onArticle: () -> Unit, onShelves: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        if (uploading) {
            CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = { open = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.library_add))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_upload_file)) },
                leadingIcon = { Icon(Icons.Default.UploadFile, contentDescription = null) },
                onClick = { open = false; onUpload() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_add_article)) },
                leadingIcon = { Icon(Icons.Default.AddLink, contentDescription = null) },
                onClick = { open = false; onArticle() },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_shelves)) },
                leadingIcon = { Icon(Icons.Default.CollectionsBookmark, contentDescription = null) },
                onClick = { open = false; onShelves() },
            )
        }
    }
}

/** Строка книги: касание — карточка, долгое касание или «⋮» — статус и полки. */
@Composable
private fun BookListRow(book: Book, shelves: List<Shelf>, onOpen: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val statusText = book.bookStatus?.let { stringResource(it.titleRes) }
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClickLabel = stringResource(R.string.library_open_book),
                onLongClickLabel = stringResource(R.string.common_more_options),
                onLongClick = { menu = true },
                onClick = onOpen,
            )
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)
            .semantics { if (statusText != null) stateDescription = statusText },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BookProgressRow(book, Modifier.weight(1f))
        book.bookStatus?.let {
            Icon(it.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.common_more_options))
            }
            BookMenu(book, shelves, expanded = menu, onDismiss = { menu = false })
        }
    }
}

/** Меню книги: «Прочитано», статус, полки (как iOS `BookMenu` и смахивание «Прочитано»). */
@Composable
private fun BookMenu(book: Book, shelves: List<Shelf>, expanded: Boolean, onDismiss: () -> Unit) {
    val model = LocalAppModel.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (book.bookStatus != BookStatus.DONE) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_mark_done)) },
                leadingIcon = { Icon(Icons.Default.Done, contentDescription = null) },
                onClick = { onDismiss(); model.launch { setStatus(BookStatus.DONE, book) } },
            )
            HorizontalDivider()
        }
        Text(
            stringResource(R.string.library_filter_status),
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BookStatus.entries.forEach { s ->
            val selected = book.bookStatus == s
            DropdownMenuItem(
                text = { Text(stringResource(s.titleRes)) },
                leadingIcon = { Icon(if (selected) Icons.Default.Check else s.icon, contentDescription = null) },
                onClick = { onDismiss(); if (!selected) model.launch { setStatus(s, book) } },
                modifier = Modifier.semantics { this.selected = selected },
            )
        }
        if (book.bookStatus != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_status_none)) },
                onClick = { onDismiss(); model.launch { setStatus(null, book) } },
            )
        }
        if (shelves.isNotEmpty()) {
            HorizontalDivider()
            Text(
                stringResource(R.string.library_filter_shelves),
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            shelves.forEach { shelf ->
                val member = book.path in shelf.books
                DropdownMenuItem(
                    text = { Text(shelf.name) },
                    leadingIcon = {
                        Icon(if (member) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, contentDescription = null)
                    },
                    onClick = { onDismiss(); model.launch { setShelf(shelf, !member, book) } },
                    modifier = Modifier.semantics {
                        toggleableState = ToggleableState(member)
                        role = Role.Checkbox
                    },
                )
            }
        }
    }
}
