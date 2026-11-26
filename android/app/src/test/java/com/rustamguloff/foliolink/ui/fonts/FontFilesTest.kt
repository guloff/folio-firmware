package com.rustamguloff.foliolink.ui.fonts

import com.rustamguloff.foliolink.core.model.FileEntry
import com.rustamguloff.foliolink.core.model.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class FontFilesTest {
    // region Сборка синтетических шрифтов sfnt

    private class Name(val platform: Int, val encoding: Int, val language: Int, val id: Int, val text: String)

    private fun u16(out: ByteArrayOutputStream, v: Int) { out.write(v shr 8 and 0xFF); out.write(v and 0xFF) }
    private fun u32(out: ByteArrayOutputStream, v: Long) { u16(out, (v shr 16).toInt() and 0xFFFF); u16(out, v.toInt() and 0xFFFF) }

    private fun os2(weight: Int, fsSelection: Int): ByteArray = ByteArray(96).also {
        it[4] = (weight shr 8).toByte(); it[5] = weight.toByte()
        it[62] = (fsSelection shr 8).toByte(); it[63] = fsSelection.toByte()
    }

    private fun head(macStyle: Int): ByteArray = ByteArray(54).also {
        it[44] = (macStyle shr 8).toByte(); it[45] = macStyle.toByte()
    }

    private fun name(vararg names: Name): ByteArray {
        val strings = ByteArrayOutputStream()
        val records = ByteArrayOutputStream()
        for (n in names) {
            val bytes = n.text.toByteArray(if (n.platform == 1) Charsets.ISO_8859_1 else Charsets.UTF_16BE)
            u16(records, n.platform); u16(records, n.encoding); u16(records, n.language); u16(records, n.id)
            u16(records, bytes.size); u16(records, strings.size())
            strings.write(bytes)
        }
        val out = ByteArrayOutputStream()
        u16(out, 0); u16(out, names.size); u16(out, 6 + names.size * 12)
        out.write(records.toByteArray()); out.write(strings.toByteArray())
        return out.toByteArray()
    }

    private fun win(id: Int, text: String) = Name(3, 1, 0x0409, id, text)

    /** Шрифт sfnt с таблицами; `base` — смещение шрифта в файле (для TTC). */
    private fun font(tables: Map<String, ByteArray>, tag: Long = 0x00010000, base: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        u32(out, tag); u16(out, tables.size); u16(out, 0); u16(out, 0); u16(out, 0)
        var offset = base + 12 + tables.size * 16
        for ((t, data) in tables) {
            out.write(t.toByteArray(Charsets.ISO_8859_1)); u32(out, 0); u32(out, offset.toLong()); u32(out, data.size.toLong())
            offset += data.size
        }
        tables.values.forEach { out.write(it) }
        return out.toByteArray()
    }

    // endregion

    @Test
    fun os2DecidesStyle() {
        val bold = font(mapOf("OS/2" to os2(700, 0x20), "head" to head(0), "name" to name(win(1, "Literata"))))
        assertEquals(FontFileInfo("Literata", FontStyle.BOLD, StyleSource.OS2), FontInspector.inspect(bold, "x.ttf"))

        val italic = FontInspector.inspect(font(mapOf("OS/2" to os2(400, 0x01))), "Literata-Regular.ttf")!!
        assertEquals(FontStyle.ITALIC, italic.style)

        val boldItalic = FontInspector.inspect(font(mapOf("OS/2" to os2(400, 0x21))), "a.ttf")!!
        assertEquals(FontStyle.BOLD_ITALIC, boldItalic.style)

        // Регулярный по fsSelection, но вес 700 — жирный; oblique (бит 9) — курсив.
        assertEquals(FontStyle.BOLD, FontInspector.inspect(font(mapOf("OS/2" to os2(700, 0x40))), "a.ttf")!!.style)
        assertEquals(FontStyle.ITALIC, FontInspector.inspect(font(mapOf("OS/2" to os2(400, 0x200))), "a.ttf")!!.style)
        assertEquals(FontStyle.REGULAR, FontInspector.inspect(font(mapOf("OS/2" to os2(400, 0x40))), "Font-Bold.ttf")!!.style)
    }

    @Test
    fun headMacStyleWithoutOs2() {
        val info = FontInspector.inspect(font(mapOf("head" to head(0x3))), "Some.otf")!!
        assertEquals(FontStyle.BOLD_ITALIC, info.style)
        assertEquals(StyleSource.HEAD, info.styleSource)
        assertEquals(FontStyle.ITALIC, FontInspector.inspect(font(mapOf("head" to head(0x2)), tag = 0x4F54544F), "a.otf")!!.style)
    }

    @Test
    fun subfamilyNameAndFileNameFallbacks() {
        val byName = FontInspector.inspect(font(mapOf("name" to name(win(1, "PT Serif"), win(2, "Bold Italic")))), "x.ttf")!!
        assertEquals(FontFileInfo("PT Serif", FontStyle.BOLD_ITALIC, StyleSource.NAME_TABLE), byName)

        val byFile = FontInspector.inspect(font(mapOf("cmap" to ByteArray(4))), "Inter-SemiBoldItalic.ttf")!!
        assertEquals(FontFileInfo("Inter", FontStyle.BOLD_ITALIC, StyleSource.FILE_NAME), byFile)

        val nothing = FontInspector.inspect(font(mapOf("cmap" to ByteArray(4))), "Mystery.ttf")!!
        assertEquals(FontFileInfo("Mystery", FontStyle.REGULAR, StyleSource.DEFAULT), nothing)
    }

    @Test
    fun familyPrefersTypographicNameAndWindowsEnglish() {
        val names = name(
            Name(1, 0, 0, 1, "Mac Family"),
            Name(3, 1, 0x0419, 1, "Семейство"),
            win(1, "Source Serif 4 Semibold"),
            win(16, "Source Serif 4"),
        )
        assertEquals("Source Serif 4", FontInspector.inspect(font(mapOf("name" to names)), "a.ttf")!!.family)
        // Только Mac-запись.
        assertEquals("Mac Family", FontInspector.inspect(font(mapOf("name" to name(Name(1, 0, 0, 1, "Mac Family")))), "a.ttf")!!.family)
        // Кириллическое имя на Windows-платформе декодируется из UTF-16BE.
        assertEquals("Литера", FontInspector.inspect(font(mapOf("name" to name(Name(3, 1, 0x0419, 1, "Литера")))), "a.ttf")!!.family)
    }

    @Test
    fun collectionUsesFirstFont() {
        val header = ByteArrayOutputStream().apply {
            write("ttcf".toByteArray()); u32(this, 0x00010000); u32(this, 1); u32(this, 16)
        }.toByteArray()
        val ttc = header + font(mapOf("OS/2" to os2(400, 0x01), "name" to name(win(1, "Coll"))), base = 16)
        assertEquals(FontFileInfo("Coll", FontStyle.ITALIC, StyleSource.OS2), FontInspector.inspect(ttc, "c.ttc"))
    }

    @Test
    fun notAFont() {
        assertNull(FontInspector.inspect("<html>not a font</html>".toByteArray(), "a.ttf"))
        assertNull(FontInspector.inspect(ByteArray(4), "a.ttf"))
        // Каталог таблиц выходит за конец файла.
        val broken = font(mapOf("OS/2" to os2(400, 0))).copyOf(20)
        assertNull(FontInspector.inspect(broken, "a.ttf"))
    }

    @Test
    fun styleFromNames() {
        assertEquals(FontStyle.BOLD, FontInspector.styleFromName("Literata-Bold"))
        assertEquals(FontStyle.BOLD_ITALIC, FontInspector.styleFromName("Bold Oblique"))
        assertEquals(FontStyle.ITALIC, FontInspector.styleFromName("Italic"))
        assertEquals(FontStyle.REGULAR, FontInspector.styleFromName("Regular"))
        assertNull(FontInspector.styleFromName("Serif"))
    }

    @Test
    fun familyFromFileName() {
        assertEquals("Literata", FontInspector.familyFromFileName("Literata-BoldItalic.ttf"))
        assertEquals("PT_Serif", FontInspector.familyFromFileName("PT_Serif.ttf"))
        assertEquals("Inter", FontInspector.familyFromFileName("Inter_Regular.otf"))
        assertEquals("Bookerly", FontInspector.familyFromFileName("Bookerly.cpfont"))
    }

    @Test
    fun safeFolderName() {
        assertEquals("PT_Serif", FontInspector.safeFamily("PT Serif"))
        assertEquals("Source_Serif_4", FontInspector.safeFamily("Source  Serif-4"))
        assertEquals("Font", FontInspector.safeFamily("Литера"))
        assertEquals("Font", FontInspector.safeFamily(""))
        assertEquals("A_B", FontInspector.safeFamily("_A__B_"))
    }

    @Test
    fun targetNames() {
        assertEquals("Literata-BoldItalic.ttf", FontInspector.targetName("Literata", "whatever.TTF", FontStyle.BOLD_ITALIC))
        assertEquals("Inter-Regular.otf", FontInspector.targetName("Inter", "inter.otf", FontStyle.REGULAR))
        assertEquals("Inter-Regular.ttf", FontInspector.targetName("Inter", "noext", FontStyle.REGULAR))
        // .cpfont — имя как есть (очищенное).
        assertEquals("Bookerly_14.cpfont", FontInspector.targetName("X", "Bookerly 14.cpfont", FontStyle.BOLD))
        assertEquals(setOf("A-Regular.ttf"), FontInspector.duplicates(listOf("A-Regular.ttf", "a-regular.ttf", "A-Bold.ttf")))
        assertTrue(FontInspector.duplicates(listOf("A-Regular.ttf", "A-Bold.ttf")).isEmpty())
    }

    @Test
    fun familiesFromApiAndFolder() {
        val api = listOf(FontFamily("Bookerly"), FontFamily("literata"))
        val entries = listOf(
            FileEntry("Literata", isDirectory = true),
            FileEntry("Single.ttf"),
            FileEntry("Bookerly.cpfont"),
            FileEntry("readme.txt"),
            FileEntry("Alegreya", isDirectory = true),
        )
        assertEquals(listOf("Alegreya", "Bookerly", "literata", "Single"), FontInspector.allFamilies(api, entries))
        assertEquals(4, FontInspector.fontEntries(entries).size)
    }
}
