package com.rustamguloff.foliolink.ui.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.core.model.Highlight
import com.rustamguloff.foliolink.ui.common.LocalAppModel
import com.rustamguloff.foliolink.ui.theme.FolioDanger

/** Заметка к цитате (iOS `HighlightEditor`): текст цитаты, поле заметки, сохранить, удалить с читалки. */
@Composable
internal fun HighlightEditorSheet(highlight: Highlight, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val model = LocalAppModel.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var note by rememberSaveable(highlight.key) { mutableStateOf(highlight.note) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                highlight.title,
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            SelectionContainer {
                Text(highlight.text, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Serif)
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                label = { Text(stringResource(R.string.notes_note)) },
                placeholder = { Text(stringResource(R.string.notes_note_hint)) },
                enabled = highlight.canModify,
                minLines = 3,
                maxLines = 10,
            )
            if (!highlight.canModify) {
                Text(
                    stringResource(R.string.notes_cant_modify),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                Button(
                    onClick = {
                        model.launch { updateNote(highlight, note) }
                        onDismiss()
                    },
                    enabled = highlight.canModify && note != highlight.note,
                ) { Text(stringResource(R.string.common_save)) }
            }
            if (highlight.canModify) {
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = FolioDanger),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Text(stringResource(R.string.notes_delete_highlight), Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
