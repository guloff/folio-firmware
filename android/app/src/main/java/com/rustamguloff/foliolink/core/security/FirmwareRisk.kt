package com.rustamguloff.foliolink.core.security

/**
 * Проверка образа перед установкой против того, что сообщает читалка (SEC-026, сторона
 * приложения). Подпись Folio не привязана к версии и плате, поэтому подписанный старый образ или
 * образ другой платы ставится только после отдельного явного подтверждения.
 */
object FirmwareRisk {
    sealed interface Risk {
        /** Версия образа ниже текущей. */
        data class Older(val image: String, val current: String) : Risk

        /** Образ собран для другой платы (по имени ассета `folio-<tag>-<плата>.bin`). */
        data class OtherBoard(val image: String, val reader: String) : Risk

        /** В образе нет читаемой версии — сравнить нельзя. */
        data class UnknownVersion(val current: String) : Risk
    }

    /**
     * @param imageInfo дескриптор образа ([FirmwareImageInfo.parse]); null — не найден.
     * @param imageName имя файла образа (по нему видна плата релизного ассета).
     * @param currentFirmware `info.firmware` читалки, [readerBoard] — `info.board`.
     */
    fun assess(imageInfo: FirmwareImageInfo?, imageName: String?, currentFirmware: String?, readerBoard: String?): List<Risk> {
        val risks = mutableListOf<Risk>()
        val current = currentFirmware?.trim().orEmpty()
        val currentVersion = parseVersion(current)
        val imageVersion = imageInfo?.version?.let(::parseVersion)
        if (currentVersion != null) {
            when {
                imageVersion == null -> risks += Risk.UnknownVersion(current)
                compare(imageVersion, currentVersion) < 0 -> risks += Risk.Older(imageInfo.version, current)
            }
        }
        val imageBoard = imageName?.let(::boardFromName)
        val board = readerBoard?.let(::boardSlug)?.takeIf { it.isNotEmpty() }
        if (imageBoard != null && board != null && imageBoard != board) {
            risks += Risk.OtherBoard(imageBoard, readerBoard)
        }
        return risks
    }

    /** "v1.2.3", "1.2.3-x4pro", "Folio 1.2" → [1, 2, 3]; без цифр — null. */
    fun parseVersion(raw: String): List<Int>? {
        val m = Regex("""(\d+(?:\.\d+)*)""").find(raw) ?: return null
        return m.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
    }

    /** Покомпонентно; недостающие компоненты — 0 ("1.1" == "1.1.0"). */
    fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val c = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** Как `GitHubRelease.boardSlug`: "xteink_x4_pro" / "X4 Pro" → "x4pro". */
    fun boardSlug(board: String): String = board.lowercase().filter { it.isLetterOrDigit() }.replace("xteink", "")

    /** Плата из имени релизного ассета `folio-<tag>-<плата>.bin`; другое имя — null (плата не известна). */
    fun boardFromName(name: String): String? {
        val m = Regex("""^folio-.+-([a-z0-9]+)\.bin$""").matchEntire(name.trim().lowercase()) ?: return null
        return m.groupValues[1].takeIf { it.any(Char::isLetter) && it.any(Char::isDigit) }
    }
}
