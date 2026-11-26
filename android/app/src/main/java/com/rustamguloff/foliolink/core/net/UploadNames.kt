package com.rustamguloff.foliolink.core.net

import com.rustamguloff.foliolink.core.model.BookFiles
import com.rustamguloff.foliolink.core.model.FileEntry

/** Что делать с книгой, имя которой на читалке уже занято. */
object UploadNames {
    /**
     * null — на читалке лежит файл с тем же именем и тем же размером: это дубликат. Иначе —
     * свободное имя для загрузки рядом. Имена сравниваются без учёта регистра (FAT).
     */
    fun resolve(existing: List<FileEntry>, fileName: String, size: Long): String? {
        val same = existing.firstOrNull { !it.isDirectory && it.name.equals(fileName, ignoreCase = true) }
        if (same != null && same.size == size) return null
        return freeName(fileName, existing.map { it.name })
    }

    /**
     * Первое свободное «имя (N).расширение», N = 2, 3… Суффикс ставится перед расширением
     * формата целиком: «Книга.fb2.zip» → «Книга (2).fb2.zip».
     */
    fun freeName(fileName: String, taken: Collection<String>): String {
        val used = taken.mapTo(HashSet()) { it.lowercase() }
        val extension = BookFiles.format(fileName)?.let { fileName.takeLast(it.extension.length) }
            ?: fileName.lastIndexOf('.').takeIf { it > 0 }?.let { fileName.substring(it) }.orEmpty()
        val stem = fileName.dropLast(extension.length)
        var n = 2
        while (true) {
            val candidate = "$stem ($n)$extension"
            if (candidate.lowercase() !in used) return candidate
            n++
        }
    }
}
