package com.rustamguloff.foliolink.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.ui.common.LocalAppModel
import com.rustamguloff.foliolink.ui.settings.ActionRow
import com.rustamguloff.foliolink.ui.settings.SettingsGroup
import com.rustamguloff.foliolink.ui.theme.FolioDanger
import kotlinx.coroutines.delay

/**
 * Окно ввода PIN (контракт v2 §1, iOS PairingView): при появлении просит читалку показать PIN
 * (если действующего ещё нет), шестая цифра отправляет его сама. Запросы идут в области модели. Ошибки 403/410/409/429 — над полем, чтобы клавиатура их не
 * закрыла; 429 — с обратным отсчётом до нового PIN.
 */
@Composable
fun PairingSheetContent(onClose: () -> Unit) {
    val model = LocalAppModel.current
    val focus = remember { FocusRequester() }
    // Состояние PIN и запросы — в модели (BUG-039): поворот экрана не выдаёт новый PIN.
    val ui by model.pairingUi.collectAsStateWithLifecycle()
    var pin by rememberSaveable { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val digits = ui.digits
    val starting = ui.starting
    val submitting = ui.submitting
    val expiresAt = ui.expiresAt
    val retryAt = ui.retryAt
    val error = ui.error?.let { model.errorText(it) }

    // Секундный такт для «PIN действует ещё N с» и «Новый PIN через N с».
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    // Окно открылось (или пересоздано): PIN запрашивается, только если действующего нет.
    LaunchedEffect(Unit) { model.ensurePin() }

    // После неудачи и нового PIN поле очищается; фокус — когда поле снова включено.
    // Пересоздание окна (поворот) набранное не стирает: сравнивается с тем, что уже видели.
    var seenFailures by rememberSaveable { mutableStateOf(ui.failures) }
    var seenExpiry by rememberSaveable { mutableStateOf(ui.expiresAt) }
    LaunchedEffect(ui.failures, ui.expiresAt) {
        if (ui.failures != seenFailures || ui.expiresAt != seenExpiry) {
            pin = ""
            seenFailures = ui.failures
            seenExpiry = ui.expiresAt
        }
    }
    LaunchedEffect(starting, submitting) {
        if (!starting && !submitting) {
            withFrameNanos {}
            runCatching { focus.requestFocus() }
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()
            .padding(bottom = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.device_pair_title),
                Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onClose) { Text(stringResource(R.string.device_pair_later)) }
        }

        // Поле — первым: при крупном шрифте одно пояснение занимает весь экран.
        val expiresLeft = PinRules.secondsLeft(expiresAt, now)
        SettingsGroup(
            title = stringResource(R.string.device_pin_header),
            footer = when {
                expiresAt == null -> null
                expiresLeft > 0 -> stringResource(R.string.device_pin_valid_for, expiresLeft)
                else -> stringResource(R.string.device_pin_expired_request)
            },
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (starting || submitting) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(if (starting) R.string.device_pin_requesting else R.string.device_pin_checking),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                error?.let { message ->
                    Row(
                        Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = FolioDanger)
                        Text(message, color = FolioDanger, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                val pinLabel = stringResource(R.string.device_pin_header)
                OutlinedTextField(
                    value = pin,
                    onValueChange = { raw ->
                        val clean = PinRules.sanitize(raw, digits)
                        pin = clean
                        if (PinRules.isComplete(clean, digits)) model.submitPin(clean)
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = pinLabel },
                    enabled = !starting && !submitting,
                    singleLine = true,
                    placeholder = {
                        Text("0".repeat(digits), Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                            style = pinStyle, color = MaterialTheme.colorScheme.outline)
                    },
                    textStyle = pinStyle,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                )
            }
        }

        SettingsGroup {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.device_pin_shown), style = MaterialTheme.typography.titleMedium)
                }
                Text(stringResource(R.string.device_pin_explain), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        val wait = PinRules.secondsLeft(retryAt, now)
        SettingsGroup(footer = stringResource(R.string.device_pin_footer)) {
            ActionRow(
                if (wait > 0) stringResource(R.string.device_pin_new_in, wait) else stringResource(R.string.device_pin_new),
                onClick = { model.requestPin() },
                icon = if (wait > 0) Icons.Outlined.HourglassTop else Icons.Outlined.Refresh,
                enabled = !starting && !submitting && wait == 0,
            )
        }
    }
}

private val pinStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.SemiBold,
    fontSize = 32.sp,
    letterSpacing = 6.sp,
    textAlign = TextAlign.Center,
)
