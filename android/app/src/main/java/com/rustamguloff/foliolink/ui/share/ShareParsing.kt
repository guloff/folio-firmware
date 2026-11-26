package com.rustamguloff.foliolink.ui.share

import com.rustamguloff.foliolink.core.model.BookFiles
import java.net.URI
import java.util.Locale

/**
 * Разбор входящего «Поделиться» без Android: что из пришедшего — книга (EPUB, FB2), что — ссылка на
 * статью. Сами потоки (content://) обобщены типом [S], чтобы логику можно было тестировать на JVM.
 */
object ShareParsing {

    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"
    const val EPUB_MIME = "application/epub+zip"

    /** Файл из EXTRA_STREAM: ссылка, имя и MIME-тип от поставщика. */
    data class Stream<S>(val source: S, val name: String, val mime: String?)

    /** Что поставить в очередь. */
    sealed interface Item<out S> {
        /** Файл книги как есть; `fileName` — имя для /Books (EPUB, FB2 или FB2.zip). */
        data class Book<S>(val source: S, val fileName: String) : Item<S>
        data class Article(val url: String) : Item<Nothing>
    }

    /** План: элементы по порядку и пропущенные файлы (не EPUB и не FB2). */
    data class Plan<S>(val items: List<Item<S>>, val skipped: List<String>)

    /**
     * Книга — по [bookFileName]: имя `.epub`/`.fb2`/`.fb2.zip` (поставщики часто отдают
     * octet-stream или zip) или MIME EPUB/FB2 при имени без расширения. Текст — первая
     * http(s)-ссылка в нём (браузеры шлют «Заголовок\nссылка»).
     */
    fun <S> plan(action: String?, streams: List<Stream<S>>, text: String?): Plan<S> {
        if (action != ACTION_SEND && action != ACTION_SEND_MULTIPLE) return Plan(emptyList(), emptyList())
        val items = mutableListOf<Item<S>>()
        val skipped = mutableListOf<String>()
        for (s in streams) {
            val name = bookFileName(s.name, s.mime)
            if (name != null) items += Item.Book(s.source, name) else skipped += s.name
        }
        // Ссылку берём, только если файлов-книг нет: подпись к файлу — не статья.
        if (items.isEmpty()) extractUrl(text)?.let { items += Item.Article(it) }
        return Plan(items, skipped)
    }

    /**
     * Можно ли читать присланный поток (SEC-035): только `content://` чужого поставщика.
     * `file://` (путь в чужом или нашем каталоге) и `content://` собственного пакета (наш
     * FileProvider — так другое приложение могло бы заставить нас отправить наш же файл) отклоняются.
     */
    fun acceptsStream(scheme: String?, authority: String?, ownPackage: String): Boolean {
        if (scheme?.lowercase(Locale.ROOT) != "content") return false
        val a = authority?.lowercase(Locale.ROOT) ?: return false
        val own = ownPackage.lowercase(Locale.ROOT)
        return a != own && !a.startsWith("$own.")
    }

    fun isEpub(name: String, mime: String?): Boolean =
        mime?.lowercase(Locale.ROOT)?.startsWith(EPUB_MIME) == true || name.lowercase(Locale.ROOT).endsWith(".epub")

    /**
     * Имя для /Books или null — не книга. Имя с расширением EPUB/FB2/FB2.zip остаётся как есть
     * (FB2 никогда не получает `.epub`); имя без расширения книги получает его по MIME
     * (EPUB → `.epub`, FB2 → `.fb2`). Прочее (`.zip`, `.pdf`, `.txt`…) пропускается.
     */
    fun bookFileName(name: String, mime: String?): String? {
        val trimmed = name.trim().ifEmpty { "book" }
        val format = BookFiles.format(trimmed)
        if (format != null) return if (format.isEpubLike) trimmed else null
        return when {
            mime?.lowercase(Locale.ROOT)?.startsWith(EPUB_MIME) == true -> "$trimmed.epub"
            BookFiles.isFb2Mime(mime) -> "$trimmed.fb2"
            else -> null
        }
    }

    /**
     * MIME потока: от поставщика, а если он ничего не говорит (нет или octet-stream) — тип самого
     * интента, когда файл один (Telegram/почта знают, что шлют FB2, а поставщик — нет).
     */
    fun streamMime(providerMime: String?, intentType: String?, streamCount: Int): String? {
        val vague = providerMime.isNullOrBlank() || providerMime.equals("application/octet-stream", ignoreCase = true)
        val specific = intentType?.takeIf { it.isNotBlank() && !it.endsWith("/*") }
        return if (vague && streamCount == 1 && specific != null) specific else providerMime
    }

    /** Имя для /Books: с расширением `.epub` (статьи и EPUB без расширения). */
    fun epubFileName(name: String): String {
        val trimmed = name.trim().ifEmpty { "book" }
        return if (trimmed.lowercase(Locale.ROOT).endsWith(".epub")) trimmed else "$trimmed.epub"
    }

    /** Название книги из имени файла (без расширения формата, `.fb2.zip` целиком). */
    fun titleOf(fileName: String): String = BookFiles.stem(fileName)

    private val URL_REGEX = Regex("""(?i)\bhttps?://[^\s<>"«»“”]+""")

    /** Первая http(s)-ссылка в тексте, без хвостовой пунктуации; null — ссылки нет. */
    fun extractUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        for (m in URL_REGEX.findAll(text)) {
            val url = trimTrailing(m.value)
            if (isHttpUrl(url)) return url
        }
        return null
    }

    /**
     * Ввод в поле «Статья по ссылке»: ссылка как есть, если это http(s); адрес без схемы с
     * точкой в имени хоста («example.com/a») — с `https://`. Иначе null.
     */
    fun normalizeUrl(input: String): String? {
        val s = input.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        if (isHttpUrl(s)) return s
        if ("://" in s) return null
        val candidate = "https://$s"
        val host = runCatching { URI(candidate).host }.getOrNull() ?: return null
        return if ('.' in host && !host.endsWith('.')) candidate else null
    }

    fun isHttpUrl(s: String): Boolean {
        val uri = runCatching { URI(s) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        return (scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()
    }

    /** Отрезает точку, запятую и закрывающую скобку без пары на конце ссылки из текста. */
    private fun trimTrailing(url: String): String {
        var u = url
        while (u.isNotEmpty()) {
            val last = u.last()
            u = when {
                last in ".,;:!?'" -> u.dropLast(1)
                last == ')' && u.count { it == '(' } < u.count { it == ')' } -> u.dropLast(1)
                last == ']' && u.count { it == '[' } < u.count { it == ']' } -> u.dropLast(1)
                else -> return u
            }
        }
        return u
    }
}
