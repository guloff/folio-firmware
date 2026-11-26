package com.rustamguloff.foliolink.ui.dictionaries

import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.app.Texts
import com.rustamguloff.foliolink.core.dictionaries.CatalogError
import com.rustamguloff.foliolink.core.dictionaries.StarDictError
import com.rustamguloff.foliolink.core.net.DeviceError
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** Тексты экрана «Словари»: какая строка выбирается для ошибки. */
class DictionaryTextsTest {
    private val t = Texts { id, args -> (listOf(id.toString()) + args.map { it.toString() }).joinToString("|") }
    private fun s(id: Int, vararg args: Any) = (listOf(id.toString()) + args.map { it.toString() }).joinToString("|")

    @Test
    fun catalogFailures() {
        assertEquals(s(R.string.dictionaries_catalog_unavailable), DictionaryTexts.catalogFailure(CatalogError.Http(404), t))
        assertEquals(s(R.string.dictionaries_catalog_offline), DictionaryTexts.catalogFailure(java.net.UnknownHostException("x"), t))
        assertEquals(s(R.string.dictionaries_err_bad_manifest), DictionaryTexts.catalogFailure(CatalogError.BadManifest, t))
        assertEquals(
            s(R.string.dictionaries_catalog_failed, s(R.string.dictionaries_err_http, 500)),
            DictionaryTexts.catalogFailure(CatalogError.Http(500), t),
        )
    }

    @Test
    fun setAndInstallErrors() {
        assertEquals(s(R.string.dictionaries_err_unsupported, "a.zip"), DictionaryTexts.errorText(StarDictError.Unsupported("a.zip"), t))
        assertEquals(s(R.string.dictionaries_err_mixed, "a, b"), DictionaryTexts.errorText(StarDictError.MixedStems(listOf("a", "b")), t))
        assertEquals(s(R.string.dictionaries_err_checksum, "x.idx"), DictionaryTexts.errorText(CatalogError.Checksum("x.idx"), t))
        assertEquals(s(R.string.core_error_unreachable), DictionaryTexts.errorText(DeviceError.Unreachable, t))
    }

    @Test
    fun languagesAndCounts() {
        assertEquals("English", DictionaryTexts.language("en", Locale.ENGLISH))
        assertEquals("Русский", DictionaryTexts.language("ru", Locale.forLanguageTag("ru")))
        assertEquals("?", DictionaryTexts.language("", Locale.ENGLISH))
        assertEquals("123,456", DictionaryTexts.count(123456, Locale.US))
        assertEquals(R.string.dictionaries_script_cyrillic, DictionaryTexts.scriptRes("Cyrillic"))
        assertEquals(R.string.dictionaries_script_other, DictionaryTexts.scriptRes(""))
    }
}
