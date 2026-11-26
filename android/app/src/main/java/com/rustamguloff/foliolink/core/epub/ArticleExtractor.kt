package com.rustamguloff.foliolink.core.epub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import com.rustamguloff.foliolink.core.net.readLimited
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.regex.Matcher
import java.util.regex.Pattern

/** Ошибки загрузки статьи; текст для человека — в UI. */
sealed class ArticleException(message: String) : Exception(message) {
    /** На странице не нашлось текста статьи. */
    data object NoContent : ArticleException("no article text")

    /** Страница не загрузилась (не 2xx, не HTTP). */
    data object BadResponse : ArticleException("bad response")
}

/**
 * Эвристический «режим чтения»: HTML → заголовок + абзацы. Без сторонних парсеров, на регулярках.
 *
 * 1. Выбросить скрипты, стили, комментарии, nav/header/footer/aside/формы.
 * 2. Предпочесть содержимое самого длинного `<article>` (или `<main>`).
 * 3. Иначе взять контейнер `<div>/<section>/<td>` с наибольшим текстом в `<p>` (абзацы
 *    группируются по ближайшему открывающему блоку).
 * 4. Оставить h1–h4, абзацы, цитаты и пункты списков по порядку.
 */
object ArticleExtractor {

    data class Article(
        val title: String,
        val author: String,
        val language: String,
        val blocks: List<EpubDocument.Block>,
    )

    /** Готовый EPUB и имя файла для него. */
    class EpubFile(val data: ByteArray, val fileName: String)

    /** Потолок HTML-страницы статьи. */
    const val MAX_PAGE_BYTES = 8L * 1024 * 1024

    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0 Mobile Safari/537.36"

    /** Загружает страницу и извлекает статью (сеть — на Dispatchers.IO). */
    suspend fun fetch(url: String): Article = withContext(Dispatchers.IO) {
        val conn = URI(url).toURL().openConnection() as? HttpURLConnection ?: throw ArticleException.BadResponse
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            val status = conn.responseCode
            if (status !in 200..299) throw ArticleException.BadResponse
            // Страница — не больше MAX_PAGE_BYTES: Content-Length не задаёт буфер, тело режется по потолку.
            if (conn.contentLengthLong > MAX_PAGE_BYTES) throw ArticleException.BadResponse
            val data = conn.inputStream.use { readLimited(it, MAX_PAGE_BYTES) } ?: throw ArticleException.BadResponse
            val html = decodeHtml(data, conn.getHeaderField("Content-Type"))
            val host = runCatching { URI(url).host }.getOrNull()
            val article = extract(html, host ?: "Article")
            if (article.blocks.isEmpty()) throw ArticleException.NoContent
            article
        } finally {
            conn.disconnect()
        }
    }

    private val CP1251: Charset = Charset.forName("windows-1251")

    /** Кодировка: windows-1251 по заголовку, иначе строгий UTF-8, иначе 1251 по `<meta>`, иначе UTF-8 с заменой. */
    fun decodeHtml(data: ByteArray, contentType: String?): String {
        if ((contentType ?: "").lowercase().contains("windows-1251")) return String(data, CP1251)
        strictUtf8(data)?.let { return it }
        val head = String(data, 0, minOf(data.size, 2048), Charsets.UTF_8).lowercase()
        if (head.contains("windows-1251")) return String(data, CP1251)
        return String(data, Charsets.UTF_8)
    }

    private fun strictUtf8(data: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data)).toString()
    }.getOrNull()

    private const val CI = Pattern.CASE_INSENSITIVE
    private val STRIPPED_TAGS = listOf(
        "script", "style", "noscript", "svg", "template", "iframe", "form", "nav", "header", "footer", "aside",
        "figure", "button", "select",
    ).map { Pattern.compile("<$it\\b[^>]*>[\\s\\S]*?</$it>", CI) }
    private val COMMENT = Pattern.compile("<!--[\\s\\S]*?-->")
    private val H1 = Pattern.compile("<h1\\b[^>]*>([\\s\\S]*?)</h1>", CI)
    private val TITLE = Pattern.compile("<title\\b[^>]*>([\\s\\S]*?)</title>", CI)
    private val LANG = Pattern.compile("<html[^>]+lang=[\"']([a-zA-Z-]+)[\"']", CI)
    private val ARTICLE = Pattern.compile("<article\\b[^>]*>([\\s\\S]*?)</article>", CI)
    private val MAIN = Pattern.compile("<main\\b[^>]*>([\\s\\S]*?)</main>", CI)
    private val BLOCK = Pattern.compile("<(h[1-4]|p|blockquote|li|pre)\\b[^>]*>([\\s\\S]*?)</\\1>", CI)
    private val CLUSTER = Pattern.compile("<(div|section|td)\\b[^>]*>|<p\\b[^>]*>[\\s\\S]*?</p>", CI)
    private val META = Pattern.compile("<meta\\b[^>]*>", CI)
    private val ANY_TAG = Pattern.compile("<[^>]+>")
    private val BR = Pattern.compile("<br\\s*/?>", CI)
    // Явный класс вместо UNICODE_CHARACTER_CLASS: этот флаг есть в JVM, но не в Android (ICU).
    private val SPACES = Pattern.compile("[\\s\\p{Z}\\u200B\\uFEFF]+")

    fun extract(html: String, fallbackTitle: String = "Article"): Article {
        val raw = html
        var body = html
        // Выбросить элементы без содержания.
        for (p in STRIPPED_TAGS) body = p.matcher(body).replaceAll(" ")
        body = COMMENT.matcher(body).replaceAll(" ")

        val title = metaContent("og:title", raw)
            ?: firstMatch(H1, body)?.let(::cleanText)
            ?: firstMatch(TITLE, raw)?.let(::cleanText)
            ?: fallbackTitle
        val cleanTitle = cleanText(title)
        val author = metaContent("author", raw) ?: ""
        val lang = firstMatch(LANG, raw)?.take(2)?.lowercase() ?: "ru"

        val candidates = allMatches(ARTICLE, body) + allMatches(MAIN, body)
        var region = candidates.maxByOrNull { textLength(it) } ?: ""
        // Короткий или отсутствующий <article> проигрывает гораздо большему скоплению абзацев.
        val cluster = bestParagraphCluster(body)
        if (cluster != null && (textLength(cluster) > maxOf(2 * textLength(region), 500) || region.isEmpty())) {
            region = cluster
        }
        if (region.isEmpty()) region = body

        val blocks = ArrayList<EpubDocument.Block>()
        val m = BLOCK.matcher(region)
        while (m.find()) {
            val tag = m.group(1)!!.lowercase()
            val text = cleanText(m.group(2) ?: "")
            if (text.isEmpty()) continue
            when (tag) {
                "h1", "h2", "h3", "h4" ->
                    if (text != cleanTitle && text.length < 200) blocks += EpubDocument.Block.Heading(text)
                "blockquote" -> blocks += EpubDocument.Block.Quote(text)
                "li" -> if (text.length > 20) blocks += EpubDocument.Block.Paragraph("• $text")
                else -> if (text.length > 1) blocks += EpubDocument.Block.Paragraph(text)
            }
        }
        // Убрать повторы вложенных совпадений (например, <p> внутри <blockquote>).
        val seen = HashSet<String>()
        val unique = blocks.filter { seen.add(it.text) }
        return Article(cleanTitle, cleanText(author), lang, unique)
    }

    /**
     * Группирует `<p>` по ближайшему предшествующему открывающему div/section/td и возвращает
     * самую «текстовую» группу вместе с соседями, у которых текста больше четверти от неё
     * (длинные статьи часто разбиты на соседние div).
     */
    internal fun bestParagraphCluster(html: String): String? {
        val clusters = LinkedHashMap<Int, MutableList<String>>()
        var currentContainer = 0
        val m = CLUSTER.matcher(html)
        while (m.find()) {
            val s = m.group()
            if (s.lowercase().startsWith("<p")) {
                clusters.getOrPut(currentContainer) { ArrayList() }.add(s)
            } else {
                currentContainer = m.start()
            }
        }
        if (clusters.isEmpty()) return null
        val lengths = clusters.mapValues { (_, v) -> v.sumOf { textLength(it) } }
        val bestLen = lengths.values.max()
        return clusters.keys.filter { lengths.getValue(it) > bestLen / 4 }.sorted()
            .flatMap { clusters.getValue(it) }
            .joinToString("\n")
    }

    internal fun textLength(html: String): Int = ANY_TAG.matcher(html).replaceAll("").length

    /**
     * `content` первого `<meta>`, у которого property/name равно `key` — атрибуты в любом
     * порядке, любые кавычки (апостроф внутри значения сохраняется).
     */
    fun metaContent(key: String, html: String): String? {
        val m = META.matcher(html)
        while (m.find()) {
            val tag = m.group()
            val which = attribute("property", tag) ?: attribute("name", tag) ?: continue
            if (!which.equals(key, ignoreCase = true)) continue
            val content = attribute("content", tag) ?: continue
            val v = content.trim()
            if (v.isNotEmpty()) return decodeEntities(v)
        }
        return null
    }

    private val attributePatterns = HashMap<String, Pattern>()

    internal fun attribute(name: String, tag: String): String? {
        val p = synchronized(attributePatterns) {
            attributePatterns.getOrPut(name) {
                Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=\\s*([\"'])(.*?)\\1", CI or Pattern.DOTALL)
            }
        }
        val m = p.matcher(tag)
        return if (m.find()) m.group(2) else null
    }

    private fun firstMatch(p: Pattern, s: String): String? {
        val m = p.matcher(s)
        if (!m.find()) return null
        val v = (m.group(1) ?: return null).trim()
        return if (v.isEmpty()) null else decodeEntities(v)
    }

    private fun allMatches(p: Pattern, s: String): List<String> {
        val out = ArrayList<String>()
        val m: Matcher = p.matcher(s)
        while (m.find()) out += m.group(1) ?: ""
        return out
    }

    /** Текст из фрагмента HTML: без тегов, с раскрытыми сущностями, пробелы схлопнуты. */
    fun cleanText(html: String): String {
        var s = BR.matcher(html).replaceAll(" ")
        s = ANY_TAG.matcher(s).replaceAll("")
        s = decodeEntities(s)
        s = SPACES.matcher(s).replaceAll(" ")
        return s.trim()
    }

    private val NAMED: Map<String, String> = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0",
        "mdash" to "—", "ndash" to "–", "laquo" to "«", "raquo" to "»", "hellip" to "…", "rsquo" to "’",
        "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "bdquo" to "„", "copy" to "©", "shy" to "",
        "thinsp" to "\u2009", "middot" to "·", "times" to "×", "deg" to "°", "euro" to "€",
    )

    /** Именованные (частые) и числовые сущности HTML; неизвестные остаются как есть. */
    fun decodeEntities(s: String): String {
        if (!s.contains('&')) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val windowEnd = minOf(s.length, i + 12)
                var semi = -1
                for (j in i + 1 until windowEnd) if (s[j] == ';') { semi = j; break }
                if (semi >= 0) {
                    val entity = s.substring(i + 1, semi)
                    val replacement: String? = when {
                        entity.startsWith("#x") || entity.startsWith("#X") ->
                            codePointString(entity.substring(2).toLongOrNull(16))
                        entity.startsWith("#") -> codePointString(entity.substring(1).toLongOrNull())
                        else -> NAMED[entity]
                    }
                    if (replacement != null) {
                        out.append(replacement)
                        i = semi + 1
                        continue
                    }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun codePointString(v: Long?): String? {
        if (v == null || v < 0 || v > Character.MAX_CODE_POINT || v in 0xD800L..0xDFFFL) return null
        return String(Character.toChars(v.toInt()))
    }

    /** Статья → EPUB и имя файла. */
    fun makeEpub(article: Article, url: String?): EpubFile {
        val doc = EpubDocument(
            title = article.title, author = article.author, language = article.language,
            sourceUrl = url, blocks = article.blocks,
        )
        return EpubFile(EpubBuilder.build(doc), EpubBuilder.fileName(article.title))
    }
}
