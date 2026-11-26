package com.rustamguloff.foliolink.core.epub

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId

/** CRC-32 (IEEE 802.3), как в ZIP. */
object Crc32 {
    fun checksum(data: ByteArray): Long = java.util.zip.CRC32().apply { update(data) }.value
}

/**
 * Пишет ZIP только с несжатыми (STORED) записями — этого хватает для EPUB, где `mimetype`
 * всё равно обязан быть первым и несжатым. Время записей — местное (DOS-формат).
 */
class ZipWriter(dateMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) {
    private class Entry(val name: ByteArray, val crc: Long, val size: Int, val offset: Int)

    private val body = ByteArrayOutputStream()
    private val entries = ArrayList<Entry>()
    private val dosTime: Int
    private val dosDate: Int

    init {
        val t = Instant.ofEpochMilli(dateMs).atZone(zone)
        val year = maxOf(t.year - 1980, 0)
        dosDate = ((year shl 9) or (t.monthValue shl 5) or t.dayOfMonth) and 0xFFFF
        dosTime = ((t.hour shl 11) or (t.minute shl 5) or (t.second / 2)) and 0xFFFF
    }

    fun add(path: String, data: ByteArray) {
        val name = path.toByteArray(Charsets.UTF_8)
        val crc = Crc32.checksum(data)
        val offset = body.size()
        body.le32(0x04034B50) // сигнатура локального заголовка
        body.le16(10) // нужна версия 1.0 (stored)
        body.le16(0x0800) // флаги: имена в UTF-8
        body.le16(0) // метод: stored
        body.le16(dosTime)
        body.le16(dosDate)
        body.le32(crc.toInt())
        body.le32(data.size) // сжатый размер
        body.le32(data.size) // исходный размер
        body.le16(name.size)
        body.le16(0) // длина extra
        body.write(name)
        body.write(data)
        entries += Entry(name, crc, data.size, offset)
    }

    fun add(path: String, string: String) = add(path, string.toByteArray(Charsets.UTF_8))

    /** Готовый архив: записи + центральный каталог + конец каталога. */
    fun finalize(): ByteArray {
        val out = ByteArrayOutputStream(body.size() + entries.size * 64 + 22)
        body.writeTo(out)
        val cdStart = out.size()
        for (e in entries) {
            out.le32(0x02014B50) // сигнатура центрального каталога
            out.le16(20) // сделано версией
            out.le16(10) // нужна версия
            out.le16(0x0800)
            out.le16(0)
            out.le16(dosTime)
            out.le16(dosDate)
            out.le32(e.crc.toInt())
            out.le32(e.size)
            out.le32(e.size)
            out.le16(e.name.size)
            out.le16(0) // extra
            out.le16(0) // комментарий
            out.le16(0) // номер диска
            out.le16(0) // внутренние атрибуты
            out.le32(0) // внешние атрибуты
            out.le32(e.offset)
            out.write(e.name)
        }
        val cdSize = out.size() - cdStart
        out.le32(0x06054B50) // конец центрального каталога
        out.le16(0)
        out.le16(0)
        out.le16(entries.size)
        out.le16(entries.size)
        out.le32(cdSize)
        out.le32(cdStart)
        out.le16(0)
        return out.toByteArray()
    }
}

/** Читает STORED-записи ZIP (для тестов и проверки собственного вывода). */
object ZipReader {
    class Entry(val name: String, val method: Int, val data: ByteArray)

    fun entries(zip: ByteArray): List<Entry> {
        val b = zip
        fun u16(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun u32(o: Int): Long = (u16(o).toLong()) or (u16(o + 2).toLong() shl 16)
        val result = ArrayList<Entry>()
        var i = 0
        while (i + 30 <= b.size && u32(i) == 0x04034B50L) {
            val method = u16(i + 8)
            val size = u32(i + 18)
            val nameLen = u16(i + 26)
            val extraLen = u16(i + 28)
            val nameStart = i + 30
            if (nameStart.toLong() + nameLen + extraLen + size > b.size) break
            val name = String(b, nameStart, nameLen, Charsets.UTF_8)
            val dataStart = nameStart + nameLen + extraLen
            result += Entry(name, method, b.copyOfRange(dataStart, dataStart + size.toInt()))
            i = dataStart + size.toInt()
        }
        return result
    }
}

internal fun ByteArrayOutputStream.le16(v: Int) {
    write(v and 0xFF); write((v shr 8) and 0xFF)
}

internal fun ByteArrayOutputStream.le32(v: Int) {
    write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v ushr 24) and 0xFF)
}
