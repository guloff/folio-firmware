package com.rustamguloff.foliolink.core.net

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * Тело multipart/form-data. Файл с диска ([addFile] с `File`) в память не читается: [writeTo]
 * переливает его в соединение кусками, а [length] известна заранее по размеру файла.
 */
class MultipartBody(val boundary: String = "FolioLink-${UUID.randomUUID()}") : HttpBody {
    private sealed interface Part {
        val size: Long
        class Bytes(val bytes: ByteArray) : Part { override val size get() = bytes.size.toLong() }
        class Disk(val file: File, override val size: Long) : Part
    }

    /** Куски тела по порядку; содержимое файлов не копируется. */
    private val parts = mutableListOf<Part>()
    private val closing get() = "--$boundary--\r\n".toByteArray(Charsets.UTF_8)

    val contentType: String get() = "multipart/form-data; boundary=$boundary"

    override val length: Long get() = parts.sumOf { it.size } + closing.size

    fun addField(name: String, value: String): MultipartBody {
        write("--$boundary\r\n")
        write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        write(value)
        write("\r\n")
        return this
    }

    fun addFile(name: String, fileName: String, mimeType: String, content: ByteArray): MultipartBody {
        header(name, fileName, mimeType)
        parts += Part.Bytes(content)
        write("\r\n")
        return this
    }

    /** Файл с диска: размер фиксируется сейчас, содержимое читается при отправке. */
    fun addFile(name: String, fileName: String, mimeType: String, file: File): MultipartBody {
        header(name, fileName, mimeType)
        parts += Part.Disk(file, file.length())
        write("\r\n")
        return this
    }

    private fun header(name: String, fileName: String, mimeType: String) {
        val escaped = fileName.replace("\"", "_")
        write("--$boundary\r\n")
        write("Content-Disposition: form-data; name=\"$name\"; filename=\"$escaped\"\r\n")
        write("Content-Type: $mimeType\r\n\r\n")
    }

    override fun writeTo(out: OutputStream, onProgress: (Long) -> Unit) {
        var done = 0L
        val buf = ByteArray(HttpBody.CHUNK)
        fun chunk(bytes: ByteArray, off: Int, n: Int) {
            out.write(bytes, off, n)
            done += n
            onProgress(done)
        }
        for (part in parts + Part.Bytes(closing)) {
            when (part) {
                is Part.Bytes -> {
                    var off = 0
                    while (off < part.bytes.size) {
                        val n = minOf(HttpBody.CHUNK, part.bytes.size - off)
                        chunk(part.bytes, off, n)
                        off += n
                    }
                }
                is Part.Disk -> part.file.inputStream().use { input ->
                    var left = part.size
                    while (left > 0) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        // Файл стал короче заявленного: длина тела уже отправлена в заголовке.
                        if (n < 0) throw IOException("file changed while uploading: ${part.file.name}")
                        chunk(buf, 0, n)
                        left -= n
                    }
                }
            }
        }
    }

    /** Тело одним массивом (для небольших тел, виртуальной читалки и тестов). */
    fun finalized(): ByteArray =
        ByteArrayOutputStream(length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).also { writeTo(it) }.toByteArray()

    private fun write(s: String) { parts += Part.Bytes(s.toByteArray(Charsets.UTF_8)) }

    companion object {
        /** Имя файла из первой части (для демо-читалки и тестов). */
        fun fileName(body: ByteArray): String? {
            val head = String(body, 0, minOf(body.size, 2048), Charsets.UTF_8)
            val start = head.indexOf("filename=\"").takeIf { it >= 0 } ?: return null
            val from = start + "filename=\"".length
            val end = head.indexOf('"', from).takeIf { it >= 0 } ?: return null
            return head.substring(from, end)
        }

        /** Содержимое файла в теле с одной частью. */
        fun fileContent(body: ByteArray): ByteArray? {
            val headerEnd = indexOf(body, "\r\n\r\n".toByteArray(), 0).takeIf { it >= 0 } ?: return null
            val lineEnd = indexOf(body, "\r\n".toByteArray(), 0).takeIf { it >= 0 } ?: return null
            val closing = "\r\n".toByteArray() + body.copyOfRange(0, lineEnd) + "--".toByteArray()
            val start = headerEnd + 4
            val end = lastIndexOf(body, closing, start).takeIf { it >= 0 } ?: return null
            return body.copyOfRange(start, end)
        }

        private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
            outer@ for (i in from..hay.size - needle.size) {
                for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }

        private fun lastIndexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
            outer@ for (i in hay.size - needle.size downTo from) {
                for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }
    }
}
