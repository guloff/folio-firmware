package com.rustamguloff.foliolink.core.model

import java.util.Locale

/**
 * Форматы книг, которые FolioLink отправляет на читалку. FB2 (с Folio 1.1) читалка сама
 * перекодирует в EPUB при первом открытии; приложение кладёт файл как есть, в ту же папку.
 * Тип — по полному имени в нижнем регистре: `.fb2.zip` — двойное расширение, а не `.zip`.
 */
enum class BookFormat(val extension: String, val mimeType: String) {
    EPUB(".epub", "application/epub+zip"),
    FB2(".fb2", "application/x-fictionbook+xml"),
    FB2_ZIP(".fb2.zip", "application/zip"),
    TXT(".txt", "text/plain"),
    MD(".md", "text/markdown"),
    ;

    /** Читалка открывает книгу EPUB-ридером (EPUB и FB2), а не как простой текст. */
    val isEpubLike: Boolean get() = this == EPUB || this == FB2 || this == FB2_ZIP
    val isFb2: Boolean get() = this == FB2 || this == FB2_ZIP
}

object BookFiles {
    /** MIME-типы, под которыми FB2 приходит от файловых менеджеров, браузеров и мессенджеров. */
    val FB2_MIME_TYPES = setOf("application/x-fictionbook+xml", "application/x-fictionbook", "text/fb2+xml")

    /** Длинные расширения раньше коротких: "x.fb2.zip" — FB2_ZIP. */
    private val BY_LENGTH = BookFormat.entries.sortedByDescending { it.extension.length }

    /** Формат по имени файла (без учёта регистра); null — не книга (в том числе просто `.zip`). */
    fun format(fileName: String): BookFormat? {
        val n = fileName.trim().substringAfterLast('/').lowercase(Locale.ROOT)
        return BY_LENGTH.firstOrNull { n.endsWith(it.extension) && n.length > it.extension.length }
    }

    fun isFb2(fileName: String): Boolean = format(fileName)?.isFb2 == true

    /** Имя без расширения формата: "Book.FB2.ZIP" → "Book", "a.b.epub" → "a.b"; не книга — до последней точки. */
    fun stem(fileName: String): String {
        val base = fileName.substringAfterLast('/')
        val f = format(base) ?: return base.substringBeforeLast('.', base)
        return base.dropLast(f.extension.length).trimEnd().ifEmpty { base }
    }

    /** MIME при загрузке на читалку; неизвестное — как текст (как раньше для TXT/MD). */
    fun uploadMime(fileName: String): String = format(fileName)?.mimeType ?: "text/plain"

    /** MIME говорит, что это FB2 (параметры вроде `; charset=` не мешают). */
    fun isFb2Mime(mime: String?): Boolean =
        mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) in FB2_MIME_TYPES
}
