package com.rustamguloff.foliolink.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rustamguloff.foliolink.ui.common.SectionHeader
import com.rustamguloff.foliolink.ui.theme.FolioDanger

// Строки «настроечных» экранов (Устройство, Настройки, Шрифты, Прошивка, Ещё, Очередь) —
// аналог iOS Form/Section/LabeledContent: группа на подложке, заголовок сверху, пояснение снизу.

/** Группа строк: заголовок, подложка со строками, пояснение под ней. */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) SectionHeader(title) else Spacer(Modifier.height(16.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.padding(vertical = 4.dp)) { content() }
        }
        if (footer != null) {
            Text(
                footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 6.dp),
            )
        }
    }
}

private val rowColors @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

/** «Название — значение» (LabeledContent). Длинное значение переносится, а не обрезается. */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(
            value,
            Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.bodyLarge,
            color = if (valueColor == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else valueColor,
            textAlign = TextAlign.End,
        )
    }
}

/** Переход на другой экран: значок, название, счётчик или значение, шеврон. */
@Composable
fun NavRow(
    title: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    badge: Int = 0,
    value: String? = null,
    subtitle: String? = null,
) {
    ListItem(
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        colors = rowColors,
        leadingContent = icon?.let { { Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary) } },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (value != null) Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (badge > 0) Badge(containerColor = MaterialTheme.colorScheme.primary) { Text("$badge") }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

/** Переключатель на всю строку (TalkBack читает как один переключатель). */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    ListItem(
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        colors = rowColors,
        leadingContent = icon?.let { { Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary) } },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

/** Кнопка-строка (как Button в iOS Form): цвет акцента, у разрушительной — красный. */
@Composable
fun ActionRow(
    title: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    busy: Boolean = false,
    subtitle: String? = null,
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        destructive -> FolioDanger
        else -> MaterialTheme.colorScheme.primary
    }
    ListItem(
        modifier = Modifier.clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick)
            .semantics { if (busy) stateDescription = "…" },
        colors = rowColors,
        leadingContent = icon?.let { { Icon(it, contentDescription = null, tint = color) } },
        headlineContent = { Text(title, color = color, fontWeight = FontWeight.Medium) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = if (busy) ({ CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }) else null,
    )
}

/** Строка-пояснение со значком (состояние, предупреждение). */
@Composable
fun NoteRow(text: String, icon: ImageVector? = null, color: Color = Color.Unspecified) {
    val tint = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = tint)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}

/** Системные настройки этого приложения (разрешения, отказанные «навсегда»). */
fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
