package com.rustamguloff.foliolink.core.epub

/** Имена файлов для читалки и экспорта (как `Outbox.sanitize` на iOS). */
object FileNames {
    private const val FORBIDDEN = "/\\:*?\"<>|"

    /**
     * Безопасное имя: запрещённые и управляющие символы → "_", без пробелов по краям и
     * ведущих точек, пустое → "book", длиннее 120 символов — основа режется до 110 с сохранением
     * расширения.
     */
    fun sanitize(name: String): String {
        val sb = StringBuilder(name.length)
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            val type = Character.getType(cp)
            if (cp < 0x80 && FORBIDDEN.indexOf(cp.toChar()) >= 0 ||
                type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()
            ) {
                sb.append('_')
            } else {
                sb.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        var cleaned = sb.toString().trim().trimStart('.')
        if (cleaned.isEmpty()) cleaned = "book"
        if (cleaned.codePointCount(0, cleaned.length) > 120) {
            val dot = cleaned.lastIndexOf('.')
            val ext = if (dot > 0) cleaned.substring(dot + 1) else ""
            val stemFull = if (dot > 0) cleaned.substring(0, dot) else cleaned
            val stem = stemFull.prefixCodePoints(110)
            cleaned = if (ext.isEmpty()) stem else "$stem.$ext"
        }
        return cleaned
    }
}

/** Первые `n` кодовых точек (суррогатная пара не разрезается). */
fun String.prefixCodePoints(n: Int): String {
    if (n <= 0) return ""
    if (codePointCount(0, length) <= n) return this
    return substring(0, offsetByCodePoints(0, n))
}
