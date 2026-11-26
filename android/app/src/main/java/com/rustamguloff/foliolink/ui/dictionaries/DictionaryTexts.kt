package com.rustamguloff.foliolink.ui.dictionaries

import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.app.Texts
import com.rustamguloff.foliolink.core.dictionaries.CatalogError
import com.rustamguloff.foliolink.core.dictionaries.StarDictError
import java.util.Locale

// Тексты экрана «Словари» без Compose (проверяются JVM-тестами через Texts).

object DictionaryTexts {
    /** Сетевой сбой при загрузке каталога или файла (а не ответ сервера). */
    fun isNetwork(e: Throwable?): Boolean =
        e is java.net.UnknownHostException || e is java.net.ConnectException ||
            e is java.net.SocketTimeoutException || e is java.net.NoRouteToHostException

    /** Причина для человека: набор файлов, каталог, сеть, иначе — общий текст ошибки. */
    fun errorText(e: Throwable, t: Texts): String = when (e) {
        StarDictError.NoFiles -> t.get(R.string.dictionaries_err_no_files)
        is StarDictError.Unsupported -> t.get(R.string.dictionaries_err_unsupported, e.name)
        is StarDictError.MixedStems -> t.get(R.string.dictionaries_err_mixed, e.stems.joinToString(", "))
        is StarDictError.Duplicate -> t.get(R.string.dictionaries_err_duplicate, e.part)
        StarDictError.MissingIfo -> t.get(R.string.dictionaries_err_missing_ifo)
        StarDictError.MissingIdx -> t.get(R.string.dictionaries_err_missing_idx)
        StarDictError.MissingDict -> t.get(R.string.dictionaries_err_missing_dict)
        StarDictError.BadIfo -> t.get(R.string.dictionaries_err_bad_ifo)
        is StarDictError.BadArchive -> t.get(R.string.dictionaries_err_bad_archive, e.name)
        is StarDictError.IndexSizeMismatch -> t.get(R.string.dictionaries_err_index_size, e.actual.toString(), e.expected.toString())
        is StarDictError.Empty -> t.get(R.string.dictionaries_err_empty, e.name)
        is StarDictError.TooLarge -> t.get(R.string.dictionaries_err_too_large, e.name)
        is CatalogError.Checksum -> t.get(R.string.dictionaries_err_checksum, e.name)
        is CatalogError.SizeMismatch -> t.get(R.string.dictionaries_err_size, e.name)
        is CatalogError.TooLarge -> t.get(R.string.dictionaries_err_too_large, e.name)
        is CatalogError.Http -> t.get(R.string.dictionaries_err_http, e.code)
        CatalogError.BadManifest -> t.get(R.string.dictionaries_err_bad_manifest)
        else -> if (isNetwork(e)) t.get(R.string.dictionaries_err_network) else com.rustamguloff.foliolink.app.errorText(e, t)
    }

    /** Строка о каталоге, который не загрузился: 404 — ещё не опубликован, сеть — нет интернета. */
    fun catalogFailure(e: Throwable, t: Texts): String = when {
        e is CatalogError.Http && e.code == 404 -> t.get(R.string.dictionaries_catalog_unavailable)
        isNetwork(e) -> t.get(R.string.dictionaries_catalog_offline)
        e == CatalogError.BadManifest -> t.get(R.string.dictionaries_err_bad_manifest)
        else -> t.get(R.string.dictionaries_catalog_failed, errorText(e, t))
    }

    fun scriptRes(script: String): Int = when (script.lowercase()) {
        "latin" -> R.string.dictionaries_script_latin
        "cyrillic" -> R.string.dictionaries_script_cyrillic
        else -> R.string.dictionaries_script_other
    }

    /** "en" → "English" / «Английский» на языке интерфейса; непонятный код — как есть. */
    fun language(code: String, locale: Locale): String {
        if (code.isBlank()) return "?"
        val name = Locale.forLanguageTag(code).getDisplayLanguage(locale)
        if (name.isBlank() || name.equals(code, ignoreCase = true)) return code
        return name.replaceFirstChar { it.titlecase(locale) }
    }

    /** 123456 → «123 456» / "123,456" по локали. */
    fun count(n: Int, locale: Locale): String = java.text.NumberFormat.getIntegerInstance(locale).format(n)
}
