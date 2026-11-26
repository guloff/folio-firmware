package com.rustamguloff.foliolink.core.dictionaries

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPOutputStream

/** Набор StarDict: части, .ifo, gunzip .idx.gz, проверка набора, имя папки. */
class StarDictTest {
    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    /** Настоящий крошечный .idx: слово\0, смещение и длина (big-endian u32). */
    private fun idx(vararg words: String): ByteArray {
        val out = ByteArrayOutputStream()
        var offset = 0
        for (w in words.sorted()) {
            out.write(w.toByteArray()); out.write(0)
            out.write(ByteBuffer.allocate(8).putInt(offset).putInt(10).array())
            offset += 10
        }
        return out.toByteArray()
    }

    private fun ifo(title: String = "English-Russian", words: Int = 3, idxSize: Int? = null) =
        ("StarDict's dict ifo file\nversion=2.4.2\nbookname=$title\nwordcount=$words\n" +
            (idxSize?.let { "idxfilesize=$it\n" } ?: "") + "sametypesequence=h\nlang=en-ru\n").toByteArray()

    private inline fun <reified E : StarDictError> expect(block: () -> Unit): E {
        try {
            block()
        } catch (e: StarDictError) {
            if (e is E) return e
            fail("expected ${E::class.simpleName}, got $e")
        }
        fail("expected ${E::class.simpleName}")
        throw AssertionError()
    }

    @Test
    fun partsByExtension() {
        assertEquals(StarDictPart.IFO, StarDictPart.of("a.ifo"))
        assertEquals(StarDictPart.IDX, StarDictPart.of("a.IDX"))
        assertEquals(StarDictPart.IDX_GZ, StarDictPart.of("en-ru.idx.gz"))
        assertEquals(StarDictPart.DICT_DZ, StarDictPart.of("en-ru.dict.dz"))
        assertEquals(StarDictPart.DICT, StarDictPart.of("en-ru.dict"))
        assertEquals(StarDictPart.SYN, StarDictPart.of("en-ru.syn"))
        assertNull(StarDictPart.of("en-ru.zip"))
        assertNull(StarDictPart.of("en-ru.syn.dz"))
        assertNull(StarDictPart.of(".ifo"))
        assertEquals("en-ru", StarDict.stem("en-ru.idx.gz"))
        assertEquals("Muller 24", StarDict.stem("dir/Muller 24.dict.dz"))
    }

    @Test
    fun parsesIfo() {
        val i = StarDict.parseIfo("\uFEFFStarDict's dict ifo file\r\nversion=3.0.0\r\nbookname=Мюллер = 24\r\nwordcount=123456\r\nidxfilesize=99\r\n".toByteArray())!!
        assertEquals("Мюллер = 24", i.bookname)
        assertEquals(123456, i.wordcount)
        assertEquals(99L, i.idxFileSize)
        assertNull(StarDict.parseIfo("bookname=x\n".toByteArray()))
        val noName = StarDict.parseIfo("StarDict's dict ifo file\nwordcount=x\n".toByteArray())!!
        assertNull(noName.bookname)
        assertNull(noName.wordcount)
    }

    @Test
    fun gunzipsIdx() {
        val raw = idx("book", "sea", "light")
        assertArrayEquals(raw, StarDict.gunzip(gzip(raw)))
        // Склеенные члены gzip распаковываются целиком.
        val a = "hello ".toByteArray()
        val b = "world".toByteArray()
        assertArrayEquals(a + b, StarDict.gunzip(gzip(a) + gzip(b)))
        expect<StarDictError.BadArchive> { StarDict.gunzip(raw, "x.idx.gz") }
        val broken = gzip(ByteArray(10_000) { it.toByte() }).let { it.copyOf(it.size / 2) }
        expect<StarDictError.BadArchive> { StarDict.gunzip(broken, "x.idx.gz") }
        expect<StarDictError.TooLarge> { StarDict.gunzip(gzip(ByteArray(5000)), "big", limit = 4096) }
    }

    @Test
    fun preparesAValidSetAndUnpacksIdx() {
        val rawIdx = idx("book", "light", "sea")
        val dict = gzip("x".repeat(30).toByteArray())
        val set = StarDict.prepare(listOf(
            "Eng Rus.dict.dz" to dict,
            "Eng Rus.idx.gz" to gzip(rawIdx),
            "Eng Rus.ifo" to ifo(idxSize = rawIdx.size),
            "Eng Rus.syn" to byteArrayOf(1, 2, 3),
            "._Eng Rus.ifo" to byteArrayOf(0),
            ".DS_Store" to byteArrayOf(0),
        ))
        assertEquals("Eng Rus", set.stem)
        assertEquals("English-Russian", set.title)
        assertEquals(3, set.words)
        val files = set.files("eng-rus")
        assertEquals(listOf("eng-rus.ifo", "eng-rus.idx", "eng-rus.syn", "eng-rus.dict.dz"), files.map { it.name })
        assertArrayEquals(rawIdx, files.first { it.part == StarDictPart.IDX }.data)
        assertArrayEquals(dict, files.last().data)
        assertEquals(files.sumOf { it.size.toLong() }, set.totalBytes)
    }

    @Test
    fun plainSetWithoutIfoTitleUsesStem() {
        val set = StarDict.prepare(listOf(
            "wiki.ifo" to "StarDict's dict ifo file\nversion=2.4.2\n".toByteArray(),
            "wiki.idx" to idx("a"),
            "wiki.dict" to "0123456789".toByteArray(),
        ))
        assertEquals("wiki", set.title)
        assertEquals(0, set.words)
        assertEquals(listOf("w.ifo", "w.idx", "w.dict"), set.files("w").map { it.name })
    }

    @Test
    fun rejectsBrokenSets() {
        val i = idx("a")
        val base = listOf("d.ifo" to ifo(), "d.idx" to i, "d.dict" to "0123456789".toByteArray())
        expect<StarDictError.NoFiles> { StarDict.prepare(emptyList()) }
        assertEquals("d.zip", expect<StarDictError.Unsupported> { StarDict.prepare(base + ("d.zip" to byteArrayOf(1))) }.name)
        assertEquals(listOf("d", "e"), expect<StarDictError.MixedStems> {
            StarDict.prepare(base.dropLast(1) + ("e.dict" to byteArrayOf(1)))
        }.stems)
        assertEquals(".idx", expect<StarDictError.Duplicate> { StarDict.prepare(base + ("d.idx.gz" to gzip(i))) }.part)
        expect<StarDictError.MissingIfo> { StarDict.prepare(base.drop(1)) }
        expect<StarDictError.MissingIdx> { StarDict.prepare(base.filter { !it.first.endsWith(".idx") }) }
        expect<StarDictError.MissingDict> { StarDict.prepare(base.dropLast(1)) }
        expect<StarDictError.BadIfo> { StarDict.prepare(listOf("d.ifo" to "hello".toByteArray()) + base.drop(1)) }
        expect<StarDictError.BadArchive> { StarDict.prepare(base.dropLast(1) + ("d.dict.dz" to "not gzip".toByteArray())) }
        expect<StarDictError.BadArchive> { StarDict.prepare(listOf(base[0], "d.idx.gz" to i, base[2])) }
        expect<StarDictError.Empty> { StarDict.prepare(listOf(base[0], "d.idx" to ByteArray(0), base[2])) }
        val mismatch = expect<StarDictError.IndexSizeMismatch> {
            StarDict.prepare(listOf("d.ifo" to ifo(idxSize = i.size + 5)) + base.drop(1))
        }
        assertEquals(i.size + 5L, mismatch.expected)
        assertEquals(i.size.toLong(), mismatch.actual)
    }

    @Test
    fun sanitizesFolderNames() {
        assertEquals("en-ru-wikdict", DictionaryFolder.sanitize("en-ru-wikdict"))
        assertEquals("Eng-Rus-24", DictionaryFolder.sanitize("  Eng Rus (24) "))
        assertEquals("Myuller-24", DictionaryFolder.sanitize("Мюллер 24"))
        assertEquals("Ozhegov", DictionaryFolder.sanitize("Ожегов"))
        assertEquals("hidden", DictionaryFolder.sanitize("...hidden"))
        assertEquals("dictionary", DictionaryFolder.sanitize("中文"))
        assertEquals("dictionary", DictionaryFolder.sanitize("-"))
        assertEquals("dictionary", DictionaryFolder.sanitize(""))
        val long = DictionaryFolder.sanitize("a".repeat(30) + " b c d")
        assertEquals(30, long.length)
        assertTrue(DictionaryFolder.isValid(long))
        for (raw in listOf("Ünïcode wörds", "a/b\\c:d*e?f", "x".repeat(80), ".-_.", "Тест-Test.v2")) {
            val s = DictionaryFolder.sanitize(raw)
            assertTrue("$raw → $s", DictionaryFolder.isValid(s))
            assertTrue(s.length <= DictionaryFolder.MAX_LENGTH)
            assertFalse(s.startsWith("."))
        }
    }

    @Test
    fun validatesFolderNamesLikeTheFirmware() {
        assertTrue(DictionaryFolder.isValid("en-ru_1.2"))
        assertFalse(DictionaryFolder.isValid(""))
        assertFalse(DictionaryFolder.isValid(".hidden"))
        assertFalse(DictionaryFolder.isValid("*"))
        assertFalse(DictionaryFolder.isValid("-"))
        assertFalse(DictionaryFolder.isValid("a b"))
        assertFalse(DictionaryFolder.isValid("a/b"))
        assertFalse(DictionaryFolder.isValid("x".repeat(32)))
        assertTrue(DictionaryFolder.isValid("x".repeat(31)))
    }

    @Test
    fun uniqueFolderAvoidsExistingCaseInsensitively() {
        assertEquals("en-ru", DictionaryFolder.unique("en-ru", listOf("ru-en")))
        assertEquals("en-ru-2", DictionaryFolder.unique("en-ru", listOf("EN-RU")))
        assertEquals("en-ru-3", DictionaryFolder.unique("en-ru", listOf("en-ru", "en-ru-2")))
        val long = "x".repeat(31)
        val u = DictionaryFolder.unique(long, listOf(long))
        assertEquals(31, u.length)
        assertTrue(u.endsWith("-2"))
        assertTrue(DictionaryFolder.isValid(u))
    }
}
