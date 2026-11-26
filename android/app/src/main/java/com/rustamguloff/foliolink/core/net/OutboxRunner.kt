package com.rustamguloff.foliolink.core.net

import com.rustamguloff.foliolink.core.model.OutboxItem
import com.rustamguloff.foliolink.core.model.OutboxOperation
import com.rustamguloff.foliolink.core.store.Outbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Отправляет очередь по порядку на подключённую читалку.
 *
 * - Успех удаляет элемент.
 * - Книга, которая уже есть на читалке (то же имя и тот же размер — это проверяет исполнитель,
 *   см. [DeviceClient.uploadBook]), удаляется из очереди как дубликат.
 * - Временный сбой (читалка пропала, таймаут, нужно сопряжение) останавливает прогон: элемент и всё
 *   после него ждут следующего подключения.
 * - Любой другой сбой — отказ: элемент остаётся в очереди с `failed` и текстом ошибки,
 *   автоматические прогоны пропускают его до «Повторить» или удаления, прогон идёт дальше.
 * - Неизвестная этой сборке операция (`Unsupported`) не исполняется никогда и остаётся как есть.
 */
object OutboxRunner {
    /** Что исполнитель может сообщить об элементе, кроме успеха. */
    class Step {
        /** Файл лёг на читалку под этим именем, а не под своим. */
        var renamedTo: String? = null
    }

    data class Report(
        val sent: List<OutboxItem> = emptyList(),
        val duplicates: List<OutboxItem> = emptyList(),
        val failed: List<OutboxItem> = emptyList(),
        /** Почему прогон остановился раньше (временный сбой), если остановился. */
        val stoppedBy: Throwable? = null,
        /** Книги, легшие на читалку под другим именем (имя занято другим файлом): элемент → имя. */
        val renamed: List<Pair<OutboxItem, String>> = emptyList(),
    ) {
        val isEmpty: Boolean get() = sent.isEmpty() && duplicates.isEmpty() && failed.isEmpty() && stoppedBy == null
    }

    fun isTransient(error: Throwable): Boolean =
        error is CancellationException || (error as? DeviceError)?.isTransient == true

    /**
     * @param includeFailed повторить и отклонённые раньше элементы («Отправить сейчас»).
     * @param only только эти элементы (id) — «Повторить» у одного элемента.
     * @param deviceKey подключённая читалка; изменения для другой читалки не трогаются.
     * @param errorText текст ошибки для `lastError` (локализует приложение).
     * @param io где идут операции с файлами очереди (индекс, удаление нагрузки): не главный поток.
     * @param execute исполняет один элемент на читалке (в контексте вызывающего).
     */
    suspend fun drain(
        outbox: Outbox,
        includeFailed: Boolean,
        only: Set<String>? = null,
        deviceKey: String? = null,
        errorText: (Throwable) -> String = { it.message ?: it.javaClass.simpleName },
        io: CoroutineContext = Dispatchers.IO,
        execute: suspend Step.(OutboxItem) -> Unit,
    ): Report {
        val renamed = mutableListOf<Pair<OutboxItem, String>>()
        val sent = mutableListOf<OutboxItem>()
        val duplicates = mutableListOf<OutboxItem>()
        val failed = mutableListOf<OutboxItem>()
        var stoppedBy: Throwable? = null
        val attempted = HashSet<String>()
        // Индекс перечитывается на каждом шаге: добавленное во время прогона подхватывается по порядку.
        while (true) {
            val item = withContext(io) {
                outbox.items().firstOrNull {
                    it.id !in attempted && it.operation.isSupported && (includeFailed || !it.failed) &&
                        (only == null || it.id in only) && it.belongsTo(deviceKey)
                }
            } ?: break
            attempted += item.id
            try {
                val step = Step()
                step.execute(item)
                withContext(io) { outbox.remove(item) }
                sent += item
                step.renamedTo?.let { renamed += item to it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DeviceError.AlreadyExists) {
                if (item.operation == OutboxOperation.UploadBook) {
                    withContext(io) { outbox.remove(item) }
                    duplicates += item
                } else {
                    val message = errorText(e)
                    withContext(io) { reject(outbox, item, message, failed) }
                }
            } catch (e: Exception) {
                // RetryLater — обёртка: и текст, и причина остановки берутся от настоящей ошибки.
                val cause = (e as? DeviceError.RetryLater)?.cause ?: e
                val message = errorText(cause)
                if (isTransient(e)) {
                    withContext(io) { outbox.update(item.id) { it.copy(lastError = message, attempts = it.attempts + 1) } }
                    stoppedBy = cause
                    break
                }
                withContext(io) { reject(outbox, item, message, failed) }
            }
        }
        return Report(sent, duplicates, failed, stoppedBy, renamed)
    }

    private fun reject(outbox: Outbox, item: OutboxItem, message: String, failed: MutableList<OutboxItem>) {
        outbox.update(item.id) { it.copy(lastError = message, failed = true, attempts = it.attempts + 1) }
        failed += item.copy(lastError = message, failed = true)
    }
}
