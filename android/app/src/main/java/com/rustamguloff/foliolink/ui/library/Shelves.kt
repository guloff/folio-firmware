package com.rustamguloff.foliolink.ui.library

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.core.model.Shelf
import com.rustamguloff.foliolink.ui.common.EmptyState
import com.rustamguloff.foliolink.ui.common.FolioTopBar
import com.rustamguloff.foliolink.ui.common.LocalAppModel
import com.rustamguloff.foliolink.ui.common.plural
import com.rustamguloff.foliolink.ui.theme.FolioDanger

/** Полки (iOS `ShelvesView`): список, создание, переименование, удаление. */
@Composable
internal fun ShelvesScreen(onBack: () -> Unit, onOpenShelf: (Shelf) -> Unit) {
    val model = LocalAppModel.current
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val shelves = snapshot.library?.shelves.orEmpty()

    var creating by rememberSaveable { mutableStateOf(false) }
    var renamingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    val renaming = shelves.firstOrNull { it.id == renamingId }
    val deleting = shelves.firstOrNull { it.id == deletingId }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            FolioTopBar(stringResource(R.string.library_shelves), onBack = onBack, actions = {
                IconButton(onClick = { creating = true }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.library_new_shelf))
                }
            })
        },
    ) { padding ->
        if (shelves.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.library_no_shelves),
                icon = Icons.Default.CollectionsBookmark,
                modifier = Modifier.padding(padding),
                message = stringResource(R.string.library_no_shelves_hint),
                action = {
                    TextButton(onClick = { creating = true }) { Text(stringResource(R.string.library_new_shelf)) }
                },
            )
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(shelves, key = { it.id }) { shelf ->
                    ShelfRow(
                        shelf,
                        onOpen = { onOpenShelf(shelf) },
                        onRename = { renamingId = shelf.id },
                        onDelete = { deletingId = shelf.id },
                    )
                    HorizontalDivider(Modifier.padding(start = 16.dp))
                }
            }
        }
    }

    if (creating) {
        NameDialog(
            title = stringResource(R.string.library_new_shelf),
            initial = "",
            confirm = stringResource(R.string.library_create),
            onDismiss = { creating = false },
            onConfirm = { name -> creating = false; model.launch { createShelf(name) } },
        )
    }
    renaming?.let { shelf ->
        NameDialog(
            title = stringResource(R.string.library_rename_shelf),
            initial = shelf.name,
            confirm = stringResource(R.string.common_save),
            onDismiss = { renamingId = null },
            onConfirm = { name ->
                renamingId = null
                if (name != shelf.name) model.launch { renameShelf(shelf, name) }
            },
        )
    }
    deleting?.let { shelf ->
        AlertDialog(
            onDismissRequest = { deletingId = null },
            title = { Text(stringResource(R.string.library_delete_shelf_confirm, shelf.name)) },
            text = { Text(stringResource(R.string.library_delete_shelf_message)) },
            confirmButton = {
                TextButton(
                    onClick = { deletingId = null; model.launch { deleteShelf(shelf) } },
                    colors = ButtonDefaults.textButtonColors(contentColor = FolioDanger),
                ) { Text(stringResource(R.string.library_delete_shelf)) }
            },
            dismissButton = { TextButton(onClick = { deletingId = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun ShelfRow(shelf: Shelf, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(shelf.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                plural(R.plurals.plural_book, shelf.books.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.common_more_options))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_rename)) },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    onClick = { menu = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.common_delete), color = FolioDanger) },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = FolioDanger) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

/** Окно ввода названия полки. Пустое название не принимается. */
@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val name = text.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.library_shelf_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotEmpty()) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** Книги полки с переключателями (iOS `ShelfEditView`). */
@Composable
internal fun ShelfEditScreen(shelfId: String, onBack: () -> Unit) {
    val model = LocalAppModel.current
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val shelf = snapshot.library?.shelves?.firstOrNull { it.id == shelfId }
    // Полку удалили (здесь или на читалке) — экран больше не нужен.
    LaunchedEffect(shelf == null) { if (shelf == null) onBack() }
    if (shelf == null) return
    val books = remember(snapshot.books) { snapshot.books.sortedWith(LibraryLogic.comparator(LibrarySort.TITLE)) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { FolioTopBar(shelf.name, onBack = onBack) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "hint") { FooterText(stringResource(R.string.library_shelf_books_hint), Modifier.padding(vertical = 8.dp)) }
            items(books, key = { it.path }) { book ->
                val member = book.path in shelf.books
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = member, role = Role.Checkbox) { on -> model.launch { setShelf(shelf, on, book) } }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Checkbox(checked = member, onCheckedChange = null)
                    Column(Modifier.weight(1f)) {
                        Text(book.displayTitle, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (book.author.isNotEmpty()) {
                            Text(
                                book.author,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
