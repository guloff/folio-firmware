package com.rustamguloff.foliolink.ui.notes

import com.rustamguloff.foliolink.core.model.Book
import com.rustamguloff.foliolink.core.model.Highlight
import com.rustamguloff.foliolink.core.model.VocabWord
import java.text.Collator
import java.util.Locale

/** Чистая логика экрана «Заметки»: группировка цитат, поиск, словарь, текст для «Поделиться». */
object NotesLogic {

    /** Цитаты одной книги: ключ — путь книги, заголовок — название для шапки группы. */
    data class Group(val key: String, val title: String, val items: List<Highlight>)

    /**
     * Как `HighlightsList.groups` в iOS: поиск по тексту, заметке и названию книги; группы по
     * книге, внутри — по позиции и дате; группы — по названию (с учётом языка).
     */
    fun groups(
        highlights: List<Highlight>,
        books: List<Book>,
        search: String,
        collator: Collator = defaultCollator(),
    ): List<Group> {
        val q = search.trim()
        val items = highlights.filter {
            q.isEmpty() || it.text.contains(q, true) || it.note.contains(q, true) || it.title.contains(q, true)
        }
        val byPath = books.associateBy { it.path }
        return items.groupBy { it.book }
            .map { (path, list) ->
                Group(
                    key = path,
                    title = bookTitle(path, list, byPath),
                    items = list.sortedWith(compareBy<Highlight>({ it.pct }, { it.created })),
                )
            }
            .sortedWith { a, b -> collator.compare(a.title, b.title) }
    }

    private fun bookTitle(path: String, items: List<Highlight>, books: Map<String, Book>): String =
        books[path]?.displayTitle
            ?: items.firstOrNull { it.title.isNotEmpty() }?.title
            ?: path.substringAfterLast('/').ifEmpty { path }

    /** Словарь: поиск по слову, новые сверху. */
    fun words(vocab: List<VocabWord>, search: String): List<VocabWord> {
        val q = search.trim()
        return vocab.filter { q.isEmpty() || it.word.contains(q, true) }.sortedByDescending { it.created }
    }

    /** Текст цитаты для «Поделиться»: «цитата» — книга, автор; заметка отдельной строкой. */
    fun shareText(h: Highlight, bookTitle: String, author: String = ""): String = buildString {
        append('«').append(h.text.trim()).append('»')
        val source = listOf(bookTitle, author).filter { it.isNotBlank() }.joinToString(", ")
        if (source.isNotEmpty()) append("\n— ").append(source)
        if (h.note.isNotBlank()) append("\n\n").append(h.note.trim())
    }

    /** Где в контексте стоит слово (без учёта регистра); null — не нашлось. */
    fun wordRange(context: String, word: String): IntRange? {
        if (word.isBlank()) return null
        val i = context.indexOf(word, ignoreCase = true)
        return if (i < 0) null else i until i + word.length
    }

    fun defaultCollator(locale: Locale = Locale.getDefault()): Collator =
        Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
}
