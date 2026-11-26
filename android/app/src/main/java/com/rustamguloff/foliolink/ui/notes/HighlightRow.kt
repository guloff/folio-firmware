package com.rustamguloff.foliolink.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.rustamguloff.foliolink.core.model.Highlight
import com.rustamguloff.foliolink.ui.common.Fmt

/** Цитата: текст с полосой слева, заметка, глава · позиция · дата (iOS `HighlightRow`). */
@Composable
fun HighlightRow(highlight: Highlight, modifier: Modifier = Modifier, showBook: Boolean = true) {
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
            )
            Text(
                highlight.text,
                Modifier.padding(start = 10.dp),
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Serif,
            )
        }
        if (highlight.note.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    Icons.AutoMirrored.Outlined.Notes,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(highlight.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val meta = buildList {
            if (showBook && highlight.title.isNotEmpty()) add(highlight.title)
            if (highlight.chapter.isNotEmpty()) add(highlight.chapter)
            if (highlight.pct >= 0) add("${highlight.pct}%")
            if (highlight.created > 0) add(Fmt.date(highlight.created))
        }
        if (meta.isNotEmpty()) {
            Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
