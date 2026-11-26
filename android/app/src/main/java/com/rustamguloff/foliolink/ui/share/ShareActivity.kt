package com.rustamguloff.foliolink.ui.share

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.IntentCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.app.appModel
import com.rustamguloff.foliolink.core.epub.ArticleExtractor
import com.rustamguloff.foliolink.ui.library.ContentFiles
import com.rustamguloff.foliolink.ui.library.articleErrorText
import com.rustamguloff.foliolink.ui.theme.FolioSuccess
import com.rustamguloff.foliolink.ui.theme.FolioTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Отправить в FolioLink» из любого приложения (порт iOS ShareExtension): EPUB и FB2 ставятся в
 * очередь как есть, ссылка на страницу превращается в EPUB (режим чтения) и тоже ставится в очередь.
 * Очередь уходит на читалку при подключении; если читалка уже подключена — сразу.
 *
 * Небольшое диалоговое окно поверх приложения-источника: статус и «Готово».
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = ViewModelProvider(this)[ShareViewModel::class.java]
        // Поворот экрана не запускает обработку повторно (ViewModel переживает пересоздание).
        if (!vm.started) vm.start(parse(intent))
        setContent {
            FolioTheme {
                val phase by vm.phase.collectAsStateWithLifecycle()
                Dialog(onDismissRequest = { finish() }) {
                    ShareCard(phase, onDone = { finish() }, onConfirm = { vm.confirm() })
                }
            }
        }
    }

    /** Входящий интент → потоки (с именами и типами) и текст. */
    private fun parse(intent: Intent?): ShareInput {
        val action = intent?.action
        val uris = buildList<Uri> {
            if (intent == null) return@buildList
            when (action) {
                Intent.ACTION_SEND ->
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
                Intent.ACTION_SEND_MULTIPLE ->
                    IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::addAll)
            }
            // Часть приложений кладёт файлы только в ClipData.
            if (isEmpty()) intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(::add) }
        }
        // SEC-035: только content:// чужих поставщиков; file:// и наш собственный FileProvider — нет.
        val accepted = uris.distinct().filter { ShareParsing.acceptsStream(it.scheme, it.authority, packageName) }
        val rejected = uris.distinct().size - accepted.size
        val distinct = accepted
        val streams = distinct.map {
            val mime = ShareParsing.streamMime(ContentFiles.mimeType(this, it), intent?.type, distinct.size)
            ShareParsing.Stream(it, ContentFiles.displayName(this, it), mime)
        }
        val text = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        return ShareInput(action, streams, text, rejected)
    }
}

/** Разобранный входящий «Поделиться». */
data class ShareInput(
    val action: String?,
    val streams: List<ShareParsing.Stream<Uri>>,
    val text: String?,
    /** Сколько потоков отклонено проверкой источника (file://, наш пакет). */
    val rejected: Int = 0,
)

/** Ход обработки (как `ShareState.Phase` в iOS). */
sealed interface SharePhase {
    data class Working(val text: String) : SharePhase

    /** SEC-035: что пришло — имя и размер; в очередь только после «Добавить». */
    data class Confirm(val lines: List<String>, val note: String?) : SharePhase
    data class Done(val titles: List<String>, val error: String?, val sendingNow: Boolean) : SharePhase
    data class Failed(val message: String) : SharePhase
}

/**
 * Обработка в области [com.rustamguloff.foliolink.app.AppModel]: закрытие окна раньше времени не
 * прерывает постановку в очередь. Ошибки по элементам собираются, последняя показывается рядом
 * с добавленным.
 */
class ShareViewModel(app: Application) : AndroidViewModel(app) {
    private val _phase = MutableStateFlow<SharePhase>(SharePhase.Working(app.getString(R.string.share_preparing)))
    val phase: StateFlow<SharePhase> = _phase.asStateFlow()
    var started = false
        private set

    private var plan: ShareParsing.Plan<Uri>? = null
    private var input: ShareInput? = null

    /** Разбирает пришедшее и показывает подтверждение; в очередь ничего не ставится. */
    fun start(input: ShareInput) {
        if (started) return
        started = true
        this.input = input
        val app = getApplication<Application>()
        val plan = ShareParsing.plan(input.action, input.streams, input.text)
        this.plan = plan
        if (plan.items.isEmpty()) {
            val reason = if (input.rejected > 0) app.getString(R.string.share_rejected_source)
            else plan.skipped.takeIf { it.isNotEmpty() }?.let { app.getString(R.string.share_skipped, it.joinToString(", ")) }
            _phase.value = SharePhase.Failed(reason ?: app.getString(R.string.share_nothing))
            return
        }
        app.appModel.scope.launch {
            val lines = withContext(Dispatchers.IO) {
                plan.items.map { item ->
                    when (item) {
                        is ShareParsing.Item.Book -> {
                            val size = ContentFiles.size(app, item.source)
                            val sizeText = size?.let { android.text.format.Formatter.formatShortFileSize(app, it) }
                                ?: app.getString(R.string.share_unknown_size)
                            "${item.fileName} · $sizeText"
                        }
                        is ShareParsing.Item.Article -> app.getString(R.string.share_article_item, item.url)
                    }
                }
            }
            val notes = buildList {
                if (plan.skipped.isNotEmpty()) add(app.getString(R.string.share_skipped, plan.skipped.joinToString(", ")))
                if (input.rejected > 0) add(app.getString(R.string.share_rejected_source))
            }
            _phase.value = SharePhase.Confirm(lines, notes.joinToString("\n").ifEmpty { null })
        }
    }

    /** «Добавить»: постановка в очередь в области модели (закрытие окна её не прерывает). */
    fun confirm() {
        val input = input ?: return
        if (_phase.value !is SharePhase.Confirm) return
        val app = getApplication<Application>()
        _phase.value = SharePhase.Working(app.getString(R.string.share_preparing))
        app.appModel.scope.launch { process(app, input) }
    }

    private suspend fun process(app: Application, input: ShareInput) {
        val model = app.appModel
        val plan = plan ?: ShareParsing.plan(input.action, input.streams, input.text)
        val queued = mutableListOf<String>()
        var lastError: String? = plan.skipped.takeIf { it.isNotEmpty() }?.let { app.getString(R.string.share_skipped, it.joinToString(", ")) }
        for (item in plan.items) {
            try {
                when (item) {
                    is ShareParsing.Item.Book -> {
                        _phase.value = SharePhase.Working(app.getString(R.string.share_copying))
                        val title = ShareParsing.titleOf(item.fileName)
                        if (ContentFiles.open(app, item.source)?.also { it.close() } == null) {
                            lastError = app.getString(R.string.share_unreadable, item.fileName)
                            continue
                        }
                        // Из потока прямо в файл очереди, с потолком размера (BUG-038).
                        model.enqueueBookStream({ ContentFiles.open(app, item.source) }, item.fileName, title, "share")
                        queued += title
                    }
                    is ShareParsing.Item.Article -> {
                        _phase.value = SharePhase.Working(app.getString(R.string.share_loading_article))
                        val article = ArticleExtractor.fetch(item.url)
                        _phase.value = SharePhase.Working(app.getString(R.string.share_building))
                        val epub = withContext(Dispatchers.Default) { ArticleExtractor.makeEpub(article, item.url) }
                        withContext(Dispatchers.IO) { model.outboxStore.enqueueBook(epub.data, epub.fileName, article.title, item.url) }
                        queued += article.title
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = articleErrorText(app, e)
            }
        }
        model.refreshOutbox()
        if (queued.isEmpty()) {
            _phase.value = SharePhase.Failed(lastError ?: app.getString(R.string.share_nothing))
            return
        }
        val sendNow = model.isConnected
        _phase.value = SharePhase.Done(queued, lastError, sendNow)
        // Читалка подключена (приложение открыто в фоне) — очередь уходит сразу.
        if (sendNow) model.drainOutbox()
    }
}

@Composable
private fun ShareCard(phase: SharePhase, onDone: () -> Unit, onConfirm: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.share_title),
                Modifier.fillMaxWidth().semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            Column(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (phase) {
                    is SharePhase.Working -> {
                        CircularProgressIndicator(Modifier.size(48.dp))
                        Text(phase.text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                    is SharePhase.Confirm -> {
                        Text(stringResource(R.string.share_confirm_title), style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center)
                        phase.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
                        phase.note?.let { WarningLine(it) }
                    }
                    is SharePhase.Done -> {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = FolioSuccess, modifier = Modifier.size(54.dp))
                        Text(stringResource(R.string.share_added), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        phase.titles.forEach {
                            Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                        }
                        Text(
                            stringResource(if (phase.sendingNow) R.string.share_sending_now else R.string.share_will_send),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        phase.error?.let {
                            WarningLine(stringResource(R.string.share_partial, it))
                        }
                    }
                    is SharePhase.Failed -> {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = ShareWarning, modifier = Modifier.size(54.dp))
                        Text(phase.message, textAlign = TextAlign.Center)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (phase is SharePhase.Confirm) {
                    TextButton(onClick = onDone) { Text(stringResource(R.string.common_cancel)) }
                    TextButton(onClick = onConfirm) { Text(stringResource(R.string.share_confirm_add)) }
                } else {
                    TextButton(onClick = onDone) { Text(stringResource(R.string.common_done)) }
                }
            }
        }
    }
}

private val ShareWarning = Color(0xFFE08A1E)

@Composable
private fun WarningLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = ShareWarning, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = ShareWarning)
    }
}
