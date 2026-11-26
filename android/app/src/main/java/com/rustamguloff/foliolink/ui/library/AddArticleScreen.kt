package com.rustamguloff.foliolink.ui.library

import android.content.ClipboardManager
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.app.AppModel
import com.rustamguloff.foliolink.core.epub.ArticleExtractor
import com.rustamguloff.foliolink.ui.common.Card
import com.rustamguloff.foliolink.ui.common.FolioTopBar
import com.rustamguloff.foliolink.ui.common.LocalAppModel
import com.rustamguloff.foliolink.ui.shell.LocalRouter
import com.rustamguloff.foliolink.ui.share.ShareParsing
import com.rustamguloff.foliolink.ui.theme.FolioDanger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Статья по ссылке» (iOS `AddArticleView`): ссылка → загрузка и извлечение текста → предпросмотр →
 * EPUB → очередь; если читалка подключена, очередь сразу отправляется.
 */
@Composable
fun AddArticleScreen() {
    val model = LocalAppModel.current
    val router = LocalRouter.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connection by model.connection.collectAsStateWithLifecycle()
    val connected = connection == AppModel.Connection.Connected

    var urlText by rememberSaveable { mutableStateOf("") }
    var loadedUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<ArticleExtractor.Article?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    val url = ShareParsing.normalizeUrl(urlText)
    val badUrlText = stringResource(R.string.library_article_bad_url)

    fun load() {
        val target = url ?: run { error = badUrlText; return }
        scope.launch {
            loading = true
            error = null
            preview = null
            try {
                preview = ArticleExtractor.fetch(target)
                loadedUrl = target
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = articleErrorText(context, e)
            } finally {
                loading = false
            }
        }
    }

    fun send(article: ArticleExtractor.Article) {
        val source = loadedUrl ?: url ?: return
        sending = true
        val app = context.applicationContext
        // В области модели: экран закрывается сразу, отправка продолжается.
        model.launch {
            try {
                val epub = withContext(Dispatchers.Default) { ArticleExtractor.makeEpub(article, source) }
                enqueueBook(epub.data, epub.fileName, article.title, source)
                if (isConnected) drainOutbox() else show(app.getString(R.string.library_article_queued), AppModel.Banner.Kind.INFO)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                show(e)
            }
        }
        router.back()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { FolioTopBar(stringResource(R.string.library_add_article)) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = urlText,
                onValueChange = { urlText = it; error = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.library_article_url_label)) },
                placeholder = { Text(stringResource(R.string.library_article_url_hint)) },
                singleLine = true,
                isError = error != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { if (url != null && !loading) load() }),
                trailingIcon = {
                    IconButton(onClick = { pasteUrl(context)?.let { urlText = it; error = null } }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = stringResource(R.string.library_article_paste))
                    }
                },
            )
            Button(
                onClick = { load() },
                enabled = url != null && !loading && !sending,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Text(stringResource(R.string.library_article_load), Modifier.padding(start = 8.dp))
                } else {
                    Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null)
                    Text(stringResource(R.string.library_article_load), Modifier.padding(start = 8.dp))
                }
            }
            FooterText(stringResource(R.string.library_article_footer), Modifier.padding(horizontal = 0.dp))

            error?.let { message ->
                Row(
                    Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = FolioDanger)
                    Text(message, color = FolioDanger, style = MaterialTheme.typography.bodyMedium)
                }
            }

            preview?.let { a ->
                Card(title = stringResource(R.string.library_article_preview)) {
                    Text(a.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (a.author.isNotEmpty()) {
                        Text(a.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        stringResource(R.string.library_article_paragraphs, a.blocks.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    a.blocks.take(3).forEach { b ->
                        Text(b.text, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                FilledTonalButton(
                    onClick = { send(a) },
                    enabled = !sending && !loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                    Text(
                        stringResource(if (connected) R.string.library_article_send else R.string.library_article_queue),
                        Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/** Первая ссылка из буфера обмена (Android сам покажет уведомление о чтении буфера). */
private fun pasteUrl(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = cm.primaryClip ?: return null
    for (i in 0 until clip.itemCount) {
        val item = clip.getItemAt(i)
        val text = item.uri?.toString() ?: item.coerceToText(context)?.toString()
        ShareParsing.extractUrl(text)?.let { return it }
        text?.trim()?.let { ShareParsing.normalizeUrl(it) }?.let { return it }
    }
    return null
}
