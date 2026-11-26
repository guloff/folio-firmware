package com.rustamguloff.foliolink.app

import com.rustamguloff.foliolink.core.dictionaries.CatalogEntry
import com.rustamguloff.foliolink.core.dictionaries.CatalogManifest
import com.rustamguloff.foliolink.core.dictionaries.DictionaryCatalog
import com.rustamguloff.foliolink.core.dictionaries.StarDict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Каталог словарей FolioLink: загрузка манифеста и установка словаря (скачать → sha256 →
 * в очередь на читалку). Живёт в [AppModel.scope]: уход с экрана установку не обрывает.
 * Ошибки хранятся объектами, текст для человека — в UI.
 */
class DictionaryCatalogController(private val model: AppModel) {

    sealed interface State {
        data object Idle : State
        data class Loading(val url: String) : State
        data class Loaded(val url: String, val manifest: CatalogManifest) : State
        data class Failed(val url: String, val error: Throwable) : State
    }

    /** Ход установки одной записи каталога. */
    sealed interface Install {
        /** Скачивается файл `index` из `count`; `fraction` — доля файла (null — размер неизвестен). */
        data class Downloading(val index: Int, val count: Int, val fraction: Float?) : Install
        data object Verifying : Install
        /** Файлы уходят на читалку; байтовый прогресс — [AppModel.transfer]. */
        data object Uploading : Install
        /** Читалка не подключена: файлы ждут в очереди отправки. */
        data object Queued : Install
        data object Done : Install
        data class Failed(val error: Throwable?) : Install
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    private val _installs = MutableStateFlow<Map<String, Install>>(emptyMap())
    private var loadJob: Job? = null

    val state: StateFlow<State> = _state.asStateFlow()
    val installs: StateFlow<Map<String, Install>> = _installs.asStateFlow()

    /** Загружает манифест (повторно — только с `force` или если сменился URL). */
    fun load(force: Boolean = false) {
        val url = model.dictionaryCatalogUrl
        val cur = _state.value
        if (!force && ((cur is State.Loaded && cur.url == url) || (cur is State.Loading && cur.url == url))) return
        loadJob?.cancel()
        _state.value = State.Loading(url)
        loadJob = model.scope.launch {
            _state.value = try {
                State.Loaded(url, DictionaryCatalog.load(url))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                State.Failed(url, e)
            }
        }
    }

    val isBusy: Boolean
        get() = _installs.value.values.any { it is Install.Downloading || it is Install.Verifying || it is Install.Uploading }

    /** Скачивает файлы записи, сверяет размер и sha256, проверяет набор и ставит в очередь. */
    fun install(entry: CatalogEntry, title: String) {
        val loaded = _state.value as? State.Loaded ?: return
        val current = _installs.value[entry.id]
        if (current is Install.Downloading || current is Install.Verifying || current is Install.Uploading) return
        set(entry.id, Install.Downloading(1, entry.files.size, 0f))
        model.scope.launch {
            try {
                val files = entry.files.mapIndexed { i, f ->
                    set(entry.id, Install.Downloading(i + 1, entry.files.size, 0f))
                    val data = DictionaryCatalog.fetch(
                        DictionaryCatalog.fileUrl(loaded.url, f.name),
                        limit = minOf(DictionaryCatalog.MAX_DOWNLOAD_BYTES, f.size + 1),
                    ) { done, total ->
                        val t = if (total > 0) total else f.size
                        val frac = if (t > 0) (done.toFloat() / t).coerceIn(0f, 1f) else null
                        set(entry.id, Install.Downloading(i + 1, entry.files.size, frac))
                    }
                    set(entry.id, Install.Verifying)
                    DictionaryCatalog.verify(f, data)
                    f.name to data
                }
                val prepared = StarDict.prepare(files)
                set(entry.id, Install.Uploading)
                val results = model.uploadDictionary(entry.id, title, prepared.files(entry.id))
                set(entry.id, when {
                    AppModel.SubmitResult.FAILED in results -> Install.Failed(null)
                    results.all { it == AppModel.SubmitResult.SENT } -> Install.Done
                    else -> Install.Queued
                })
            } catch (e: CancellationException) {
                set(entry.id, Install.Failed(null))
                throw e
            } catch (e: Exception) {
                set(entry.id, Install.Failed(e))
            }
        }
    }

    /** Убирает отметку «готово/ошибка» (например, после удаления словаря). */
    fun clear(id: String) = _installs.update { it - id }

    private fun set(id: String, install: Install) = _installs.update { it + (id to install) }
}
