package com.rustamguloff.foliolink.core.store

import android.util.Log
import com.rustamguloff.foliolink.core.model.FolioJson
import com.rustamguloff.foliolink.core.model.ReadingSession
import com.rustamguloff.foliolink.core.model.SyncRules
import com.rustamguloff.foliolink.core.model.SyncSnapshot
import com.rustamguloff.foliolink.core.model.WidgetSummary
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * JSON-файлы кэша в каталоге `root` (в приложении — `context.filesDir`, в тестах — временная
 * папка). Их же читают виджеты. Запись атомарная (временный файл + rename).
 */
class SharedStore(val root: File) {
    init { root.mkdirs() }

    fun file(name: String): File = File(root, name)

    /** Итог чтения файла: «файла нет» и «файл не прочитался» — разные вещи. */
    sealed interface Loaded<out T> {
        data class Value<T>(val value: T) : Loaded<T>
        data object Missing : Loaded<Nothing>

        /** Файл есть, но не читается; `setAside` — куда он отложен (null — остался на месте). */
        data class Unreadable(val setAside: File?) : Loaded<Nothing>
    }

    /**
     * Читает файл. `quarantine` — для данных, которых нет на читалке (профили, повторение слов):
     * нечитаемый файл переименовывается в `<имя>.unreadable-<время>`, чтобы следующая запись не
     * затёрла его молча. В лог идёт только класс исключения: в тексте ошибки разбора бывает содержимое.
     */
    fun <T> read(serializer: KSerializer<T>, name: String, quarantine: Boolean = false): Loaded<T> {
        val f = file(name)
        if (!f.exists()) return Loaded.Missing
        return try {
            Loaded.Value(FolioJson.decodeFromString(serializer, f.readText()))
        } catch (e: Exception) {
            val aside = if (quarantine) setAside(f) else null
            Log.w(TAG, "$name unreadable: ${e.javaClass.simpleName}" + (aside?.let { "; kept as ${it.name}" } ?: ""))
            Loaded.Unreadable(aside)
        }
    }

    fun <T> load(serializer: KSerializer<T>, name: String): T? =
        (read(serializer, name) as? Loaded.Value)?.value

    /** True — значение записано на диск. */
    fun <T> save(value: T, serializer: KSerializer<T>, name: String): Boolean = try {
        writeAtomically(file(name), FolioJson.encodeToString(serializer, value).toByteArray())
        true
    } catch (e: Exception) {
        Log.w(TAG, "$name write failed: ${e.javaClass.simpleName}")
        false
    }

    fun remove(name: String) { file(name).delete() }

    fun loadSnapshot(): SyncSnapshot = load(SyncSnapshot.serializer(), SNAPSHOT_FILE) ?: SyncSnapshot()
    fun saveSnapshot(s: SyncSnapshot) = save(s, SyncSnapshot.serializer(), SNAPSHOT_FILE)

    fun loadSessions(): List<ReadingSession> = load(SESSIONS, SESSIONS_FILE) ?: emptyList()
    fun saveSessions(s: List<ReadingSession>) = save(s, SESSIONS, SESSIONS_FILE)

    fun loadWidgetSummary(): WidgetSummary? = load(WidgetSummary.serializer(), WIDGET_FILE)
    fun saveWidgetSummary(s: WidgetSummary) = save(s, WidgetSummary.serializer(), WIDGET_FILE)

    companion object {
        private const val TAG = "SharedStore"
        const val SNAPSHOT_FILE = "snapshot.json"
        const val SESSIONS_FILE = "sessions.json"
        const val WIDGET_FILE = "widget.json"
        const val VOCAB_REVIEW_FILE = "vocab-review.json"

        private val SESSIONS = ListSerializer(ReadingSession.serializer())

        /** Слияние сессий без дублей, по возрастанию начала (общее правило — [SyncRules]). */
        fun merge(existing: List<ReadingSession>, incoming: List<ReadingSession>): List<ReadingSession> =
            SyncRules.mergeSessions(existing, incoming)

        /**
         * Пишет во временный файл рядом, сбрасывает его на диск (fsync) и переименовывает поверх:
         * читатель видит старое или новое целиком, и после сбоя питания — тоже. Не получилось —
         * [IOException]; прежний файл при этом цел. Единственная реализация атомарной записи в приложении.
         */
        fun writeAtomically(target: File, bytes: ByteArray) =
            writeAtomically(target) { it.write(bytes) }

        /** То же для потоковой записи (большие файлы очереди). Временный файл при ошибке удаляется. */
        fun writeAtomically(target: File, write: (OutputStream) -> Unit) {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, ".${target.name}.tmp")
            try {
                FileOutputStream(tmp).use { out ->
                    write(out)
                    out.flush()
                    out.fd.sync()
                }
                if (!tmp.renameTo(target)) {
                    // Файловая система без замены переименованием: удалить цель и повторить.
                    target.delete()
                    if (!tmp.renameTo(target)) throw IOException("cannot replace ${target.name}")
                }
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
        }

        /** Откладывает нечитаемый файл в сторону; null — переименовать не удалось (файл на месте). */
        fun setAside(file: File): File? {
            val aside = File(file.parentFile, "${file.name}.unreadable-${System.currentTimeMillis() / 1000}")
            return if (file.renameTo(aside)) aside else null
        }
    }
}
