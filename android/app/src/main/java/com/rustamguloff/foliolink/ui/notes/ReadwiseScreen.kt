package com.rustamguloff.foliolink.ui.notes

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.app.AppModel
import com.rustamguloff.foliolink.core.export.ReadwiseClient
import com.rustamguloff.foliolink.core.export.ReadwiseException
import com.rustamguloff.foliolink.core.export.ReadwiseExport
import com.rustamguloff.foliolink.core.export.ReadwiseHighlight
import com.rustamguloff.foliolink.ui.common.Card
import com.rustamguloff.foliolink.ui.common.FolioTopBar
import com.rustamguloff.foliolink.ui.common.LocalAppModel
import com.rustamguloff.foliolink.ui.library.FooterText
import com.rustamguloff.foliolink.ui.theme.FolioDanger
import com.rustamguloff.foliolink.ui.theme.FolioSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

/** Состояние экрана Readwise (как `ReadwiseView.Status` в iOS). */
sealed interface ReadwiseStatus {
    data object Idle : ReadwiseStatus
    data object Checking : ReadwiseStatus
    data object Valid : ReadwiseStatus
    data object Invalid : ReadwiseStatus
    data class Uploading(val sent: Int, val total: Int) : ReadwiseStatus
    data class Done(val count: Int) : ReadwiseStatus
    data class Failed(val message: String) : ReadwiseStatus
}

/** Текст ошибки Readwise: строка и аргументы (чистая функция — проверяется тестами). */
object ReadwiseErrors {
    data class Message(val res: Int, val args: List<Any> = emptyList())

    fun message(e: Throwable): Message? = when (e) {
        ReadwiseException.InvalidToken -> Message(R.string.notes_readwise_error_token)
        is ReadwiseException.RateLimited ->
            e.retryAfter?.let { Message(R.string.notes_readwise_error_rate, listOf(it)) }
                ?: Message(R.string.notes_readwise_error_rate_plain)
        is ReadwiseException.Server -> Message(R.string.notes_readwise_error_server, listOf(e.status, e.detail))
        is IOException -> Message(R.string.notes_readwise_error_network)
        else -> null
    }

    fun text(context: Context, e: Throwable): String =
        message(e)?.let { context.getString(it.res, *it.args.toTypedArray()) }
            ?: e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
}

/**
 * «Отправить в Readwise» (iOS `ReadwiseView`): токен пользователя (зашифрован Keystore), проверка
 * токена и загрузка всех цитат через API пакетами по 100 с прогрессом.
 */
@Composable
fun ReadwiseScreen() {
    val model = LocalAppModel.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val store = remember { EncryptedReadwiseTokenStore.standard(context.applicationContext) }
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val highlights = snapshot.highlights

    var token by rememberSaveable { mutableStateOf("") }
    var showToken by rememberSaveable { mutableStateOf(false) }
    var hasStored by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<ReadwiseStatus>(ReadwiseStatus.Idle) }
    var loadedStored by rememberSaveable { mutableIntStateOf(0) }
    val trimmed = token.trim()
    val busy = status is ReadwiseStatus.Checking || status is ReadwiseStatus.Uploading

    LaunchedEffect(Unit) {
        val saved = store.token()
        hasStored = saved != null
        // Сохранённый токен подставляется один раз (поворот экрана не затирает ввод).
        if (saved != null && loadedStored == 0) token = saved
        loadedStored = 1
    }

    fun rememberToken(t: String) = scope.launch { store.save(t); hasStored = true }

    fun check() {
        status = ReadwiseStatus.Checking
        val t = trimmed
        scope.launch {
            status = try {
                val ok = ReadwiseClient(t).checkToken()
                if (ok) rememberToken(t)
                if (ok) ReadwiseStatus.Valid else ReadwiseStatus.Invalid
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ReadwiseStatus.Failed(ReadwiseErrors.text(context, e))
            }
        }
    }

    fun upload() {
        val t = trimmed
        val books = model.snapshot.value.books
        val items = ReadwiseExport.ordered(highlights).map { ReadwiseHighlight.make(it, books) }
        status = ReadwiseStatus.Uploading(0, items.size)
        scope.launch {
            var sent = 0
            val client = ReadwiseClient(t)
            status = try {
                for (batch in items.chunked(ReadwiseClient.BATCH_SIZE)) {
                    sent += client.upload(batch)
                    status = ReadwiseStatus.Uploading(sent, items.size)
                }
                rememberToken(t)
                model.show(resources.getString(R.string.notes_readwise_sent, sent), AppModel.Banner.Kind.SUCCESS)
                ReadwiseStatus.Done(sent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ReadwiseException.InvalidToken) {
                ReadwiseStatus.Invalid
            } catch (e: Exception) {
                ReadwiseStatus.Failed(ReadwiseErrors.text(context, e))
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { FolioTopBar(stringResource(R.string.notes_readwise_title)) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(title = stringResource(R.string.notes_readwise_token_section)) {
                OutlinedTextField(
                    value = token,
                    onValueChange = {
                        token = it
                        if (status == ReadwiseStatus.Valid || status == ReadwiseStatus.Invalid) status = ReadwiseStatus.Idle
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.notes_readwise_token_label)) },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                        autoCorrectEnabled = false,
                    ),
                    trailingIcon = {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(
                                if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(
                                    if (showToken) R.string.notes_readwise_hide_token else R.string.notes_readwise_show_token,
                                ),
                            )
                        }
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { check() }, enabled = trimmed.isNotEmpty() && !busy) {
                        Text(stringResource(R.string.notes_readwise_check))
                    }
                    TokenBadge(status)
                }
                if (hasStored) {
                    TextButton(
                        onClick = {
                            scope.launch { store.remove() }
                            token = ""
                            hasStored = false
                            status = ReadwiseStatus.Idle
                        },
                        enabled = !busy,
                        colors = ButtonDefaults.textButtonColors(contentColor = FolioDanger),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) { Text(stringResource(R.string.notes_readwise_forget)) }
                }
                Text(
                    stringResource(R.string.notes_readwise_token_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { Sharing.openUrl(context, "https://readwise.io/access_token") },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.notes_readwise_get_token), Modifier.padding(start = 6.dp))
                }
            }

            Card(title = stringResource(R.string.notes_readwise_send_section)) {
                Button(
                    onClick = { upload() },
                    enabled = trimmed.isNotEmpty() && highlights.isNotEmpty() && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                    Text(stringResource(R.string.notes_readwise_send, highlights.size), Modifier.padding(start = 8.dp))
                }
                when (val s = status) {
                    is ReadwiseStatus.Uploading -> {
                        LinearProgressIndicator(
                            progress = { if (s.total > 0) s.sent.toFloat() / s.total else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(R.string.notes_readwise_progress, s.sent, s.total),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is ReadwiseStatus.Done ->
                        StatusLine(Icons.Default.CheckCircle, stringResource(R.string.notes_readwise_sent, s.count), FolioSuccess)
                    is ReadwiseStatus.Failed -> StatusLine(Icons.Default.Warning, s.message, FolioDanger)
                    else -> {}
                }
                FooterText(stringResource(R.string.notes_readwise_footer), Modifier.padding(horizontal = 0.dp))
            }
        }
    }
}

@Composable
private fun TokenBadge(status: ReadwiseStatus) {
    when (status) {
        ReadwiseStatus.Checking -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        ReadwiseStatus.Valid -> StatusLine(Icons.Default.CheckCircle, stringResource(R.string.notes_readwise_valid), FolioSuccess)
        ReadwiseStatus.Invalid -> StatusLine(Icons.Default.Warning, stringResource(R.string.notes_readwise_invalid), FolioDanger)
        else -> {}
    }
}

@Composable
private fun StatusLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color) {
    Row(
        Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}
