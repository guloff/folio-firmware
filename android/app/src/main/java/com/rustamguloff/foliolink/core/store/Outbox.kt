package com.rustamguloff.foliolink.core.store

import android.util.Log
import com.rustamguloff.foliolink.core.model.BookFiles
import com.rustamguloff.foliolink.core.model.OutboxItem
import com.rustamguloff.foliolink.core.model.OutboxOperation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Постоянная упорядоченная очередь изменений для читалки: книги из «Поделиться» и любые правки
 * приложения (статусы, полки, заметки, удаления, заставки, шрифты, настройки). Отправляется по
 * порядку при подключении.
 *
 * Хранение: `dir/outbox.json` (массив элементов) и `dir/<id>/<fileName>` — нагрузка элемента.
 * Методы синхронные и потокобезопасные в пределах процесса.
 */
class Outbox(val dir: File) {
    private val indexFile = File(dir, INDEX)
    private val lock: Any = LOCKS.getOrPut(dir.absoluteFile.normalize().path) { Any() }

    init { dir.mkdirs() }

    /**
     * Элементы в порядке отправки. Элементы, чей файл нагрузки пропал, скрыты; `Unsupported`
     * не скрывается никогда (см. [OutboxOperation.hasPayload]).
     */
    fun items(): List<OutboxItem> = synchronized(lock) { visible(decodeIndex().items) }

    private fun visible(items: List<OutboxItem>) =
        items.filter { !it.operation.hasPayload || fileFor(it).exists() }

    /**
     * `lost` — сколько записей индекса не прочиталось (для целиком нечитаемого — оценка по числу
     * `"id"` в тексте, не меньше 1). `whole` — индекс не прочитался вовсе.
     */
    private class Decoded(val items: List<OutboxItem>, val lost: Int, val whole: Boolean) {
        val damaged: Boolean get() = lost > 0
    }

    /** Одна нечитаемая запись не должна стоить всей очереди: записи разбираются по одной. */
    private fun decodeIndex(): Decoded {
        if (!indexFile.exists()) return Decoded(emptyList(), 0, false)
        val text = try {
            indexFile.readText()
        } catch (e: Exception) {
            return Decoded(recoverFromFolders(emptySet()), 1, true)
        }
        val array = runCatching { Strict.parseToJsonElement(text) as? JsonArray }.getOrNull()
        if (array == null) {
            if (text.isBlank()) return Decoded(emptyList(), 0, false)
            return Decoded(recoverFromFolders(emptySet()), estimateEntries(text), true)
        }
        val items = array.mapNotNull { e -> (e as? JsonObject)?.let { runCatching { OutboxItem.fromJson(it) }.getOrNull() } }
        return Decoded(items, array.size - items.size, false)
    }

    /**
     * Индекс не читается целиком: книги, чьи файлы лежат в папках `<id>/`, восстанавливаются как
     * загрузки в /Books (по ним однозначно видно, что это). Прочие операции без индекса не
     * восстановить — они остаются в копии индекса и считаются «не прочитано».
     */
    private fun recoverFromFolders(known: Set<String>): List<OutboxItem> =
        dir.listFiles().orEmpty()
            .filter { it.isDirectory && it.name !in known }
            .sortedBy { it.lastModified() }
            .mapNotNull { folder ->
                val file = folder.listFiles()?.singleOrNull { it.isFile && !it.name.startsWith(".") } ?: return@mapNotNull null
                if (BookFiles.format(file.name) == null) return@mapNotNull null
                OutboxItem(
                    id = folder.name, fileName = file.name, title = BookFiles.stem(file.name), source = RECOVERED,
                    added = file.lastModified(), operation = OutboxOperation.UploadBook,
                )
            }

    private fun estimateEntries(text: String): Int = maxOf(1, Regex("\"id\"\\s*:").findAll(text).count())

    /**
     * Перед перезаписью нечитаемого (целиком или частично) индекса оригинал копируется рядом
     * (`outbox.unreadable-<время>.json`) и копия сверяется побайтно. Без проверенной копии
     * индекс не перезаписывается: [IOException].
     */
    private fun preserveDamagedIndex(decoded: Decoded) {
        if (!decoded.damaged) return
        val original = runCatching { indexFile.readBytes() }.getOrNull()
        val backup = File(dir, "$BACKUP_PREFIX${System.currentTimeMillis()}.json")
        val ok = original != null && runCatching {
            SharedStore.writeAtomically(backup, original)
            backup.readBytes().contentEquals(original)
        }.getOrDefault(false)
        if (!ok) {
            backup.delete()
            Log.e(TAG, "Outbox index is unreadable and no copy could be made; not overwriting it")
            throw IOException("outbox index unreadable; backup failed")
        }
        Log.e(TAG, "Outbox index was unreadable (${decoded.lost} entries); kept a copy as ${backup.name}")
    }

    /** Копии повреждённых индексов. */
    fun unreadableBackups(): List<File> =
        dir.listFiles()?.filter { it.name.startsWith(BACKUP_PREFIX) }.orEmpty()

    /**
     * Сколько изменений не прочиталось: в текущем индексе и в отложенных копиях (для экрана
     * очереди: «N изменений не прочитано»).
     */
    fun unreadableCount(): Int = synchronized(lock) {
        val current = decodeIndex().lost
        val saved = unreadableBackups().sumOf { f ->
            val text = runCatching { f.readText() }.getOrNull() ?: return@sumOf 1
            val array = runCatching { Strict.parseToJsonElement(text) as? JsonArray }.getOrNull()
                ?: return@sumOf estimateEntries(text)
            array.count { e -> (e as? JsonObject)?.let { runCatching { OutboxItem.fromJson(it) }.getOrNull() } == null }
        }
        current + saved
    }

    /** Пользователь смирился с потерей: копии нечитаемых индексов удаляются. */
    fun discardUnreadableBackups() = synchronized(lock) { unreadableBackups().forEach { it.delete() } }

    private fun write(items: List<OutboxItem>) {
        val json = JsonArray(items.map { it.toJson() }).toString()
        SharedStore.writeAtomically(indexFile, json.toByteArray(Charsets.UTF_8))
    }

    private fun updateIndex(change: (List<OutboxItem>) -> List<OutboxItem>) = synchronized(lock) {
        val decoded = decodeIndex()
        preserveDamagedIndex(decoded)
        write(change(visible(decoded.items)))
    }

    fun fileFor(item: OutboxItem): File = File(File(dir, item.id), item.fileName)

    fun payload(item: OutboxItem): ByteArray? =
        if (item.operation.hasPayload) runCatching { fileFor(item).readBytes() }.getOrNull() else null

    /** Файл нагрузки, если он есть (для потоковой отправки без чтения в память). */
    fun payloadFile(item: OutboxItem): File? =
        if (item.operation.hasPayload) fileFor(item).takeIf { it.isFile } else null

    /** Книга для /Books («Поделиться», статья). */
    fun enqueueBook(data: ByteArray, fileName: String, title: String, source: String): OutboxItem =
        enqueue(OutboxOperation.UploadBook, title, source, data, fileName)

    /** Любая операция; нагрузка (если есть) кладётся рядом с индексом. */
    fun enqueue(
        operation: OutboxOperation,
        title: String,
        source: String = "",
        payload: ByteArray? = null,
        fileName: String = "",
        deviceKey: String? = null,
    ): OutboxItem {
        val item = OutboxItem(
            fileName = if (payload == null) "" else sanitize(fileName),
            title = title, source = source, operation = operation, deviceKey = deviceKey,
        )
        if (payload != null) SharedStore.writeAtomically(fileFor(item), payload)
        try {
            updateIndex { it + item }
        } catch (e: Exception) {
            // Индекс не записан — нагрузка без записи в нём только занимала бы место.
            File(dir, item.id).deleteRecursively()
            throw e
        }
        return item
    }

    /**
     * Книга из потока (`content://` из «Поделиться» и выбора файлов) прямо в файл очереди, без
     * копии в памяти. Больше [limit] байт — [TooLargeException], файл не остаётся.
     */
    fun enqueueBookStream(
        input: InputStream,
        fileName: String,
        title: String,
        source: String,
        limit: Long = MAX_FILE_BYTES,
    ): OutboxItem {
        val item = OutboxItem(fileName = sanitize(fileName), title = title, source = source, operation = OutboxOperation.UploadBook)
        val target = fileFor(item)
        try {
            SharedStore.writeAtomically(target) { out -> copyLimited(input, out, limit) }
            updateIndex { it + item }
        } catch (e: Exception) {
            File(dir, item.id).deleteRecursively()
            throw e
        }
        return item
    }

    /** Меняет элемент на месте (ошибка, попытки); если его уже нет — ничего. */
    fun update(id: String, change: (OutboxItem) -> OutboxItem) {
        updateIndex { items -> items.map { if (it.id == id) change(it) else it } }
    }

    /** Переносит изменения читалки со старого ключа на новый (та же читалка опознана иначе). */
    fun rekey(from: String, to: String) {
        updateIndex { items -> items.map { if (it.deviceKey == from) it.copy(deviceKey = to) else it } }
    }

    fun remove(item: OutboxItem) {
        File(dir, item.id).deleteRecursively()
        updateIndex { items -> items.filter { it.id != item.id } }
    }

    /** Файл больше допустимого для очереди. */
    class TooLargeException(val limit: Long) : IOException("larger than $limit bytes")

    companion object {
        private const val TAG = "Outbox"
        private const val BACKUP_PREFIX = "outbox.unreadable-"
        /** Источник элемента, восстановленного по папке без индекса. */
        const val RECOVERED = "recovered"

        /** Потолок одного файла в очереди (книга, словарь): больше — отказ с понятным текстом. */
        const val MAX_FILE_BYTES = 256L * 1024 * 1024

        /** Копирует не больше `limit` байт; больше — [TooLargeException]. Возвращает размер. */
        fun copyLimited(input: InputStream, out: OutputStream, limit: Long): Long {
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) return total
                total += n
                if (total > limit) throw TooLargeException(limit)
                out.write(buf, 0, n)
            }
        }
        const val INDEX = "outbox.json"
        private val Strict = Json
        private val LOCKS = ConcurrentHashMap<String, Any>()

        private val FORBIDDEN = "/\\:*?\"<>|".toSet()

        /** Делает имя файла безопасным для FAT читалки. */
        fun sanitize(name: String): String {
            var cleaned = buildString {
                for (ch in name) append(if (ch in FORBIDDEN || ch.isISOControl()) '_' else ch)
            }.trim()
            cleaned = cleaned.trimStart('.')
            if (cleaned.isEmpty()) cleaned = "book"
            if (cleaned.codePointCount(0, cleaned.length) > 120) {
                // Двойное расширение книги (`.fb2.zip`) сохраняется целиком.
                val compound = BookFiles.format(cleaned)?.extension?.takeIf { it.count { c -> c == '.' } > 1 }
                val dot = if (compound != null) cleaned.length - compound.length else cleaned.lastIndexOf('.')
                val ext = if (dot > 0) cleaned.substring(dot + 1) else ""
                val stem = if (dot > 0) cleaned.substring(0, dot) else cleaned
                val shortStem = stem.take(110)
                cleaned = if (ext.isEmpty()) shortStem else "$shortStem.$ext"
            }
            return cleaned
        }
    }
}
