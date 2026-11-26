package com.rustamguloff.foliolink.ui.firmware

import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.core.model.DeviceInfo
import com.rustamguloff.foliolink.core.net.TransferProgress
import com.rustamguloff.foliolink.core.security.FirmwareSignature
import com.rustamguloff.foliolink.core.security.FirmwareSignatureException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Последний релиз прошивки на GitHub: образ для этой платы и подпись рядом (контракт v2 §2). */
data class GitHubRelease(
    val tag: String,
    val assetName: String,
    val downloadUrl: String,
    /** `<asset>.bin.sig` рядом с образом. */
    val signatureUrl: String,
) {
    sealed class ReleaseError(message: String) : Exception(message) {
        data object BadRepo : ReleaseError("bad repo")
        data object NoAsset : ReleaseError("no asset")
        data object Ambiguous : ReleaseError("ambiguous asset")
        data class Http(val code: Int) : ReleaseError("HTTP $code")
        data object NotFirmware : ReleaseError("not firmware")
        data object NoSignature : ReleaseError("no signature")
    }

    @Serializable
    internal data class ApiRelease(
        @SerialName("tag_name") val tag: String = "",
        val assets: List<Asset> = emptyList(),
    ) {
        @Serializable
        data class Asset(val name: String = "", @SerialName("browser_download_url") val url: String = "")
    }

    companion object {
        const val DEFAULT_REPO = "guloff/folio-firmware"
        private val json = Json { ignoreUnknownKeys = true }

        /** Только буквы и цифры в нижнем регистре, без "xteink": "X4 Pro" / "xteink_x4_pro" → "x4pro". */
        fun boardSlug(board: String): String =
            board.lowercase().filter { it.isLetterOrDigit() }.replace("xteink", "")

        /** .bin для платы `board` (ассеты называются folio-<tag>-<плата>.bin). */
        fun pickAsset(names: List<String>, board: String): String {
            val bins = names.filter { it.lowercase().endsWith(".bin") }
            if (bins.isEmpty()) throw ReleaseError.NoAsset
            val slug = boardSlug(board)
            if (slug.isNotEmpty()) {
                val matches = bins.filter { name ->
                    val stem = name.lowercase().removeSuffix(".bin")
                    stem.split('-').last().filter { it.isLetterOrDigit() } == slug
                }
                return when {
                    matches.size == 1 -> matches[0]
                    matches.size > 1 -> throw ReleaseError.Ambiguous
                    else -> throw ReleaseError.NoAsset
                }
            }
            if (bins.size == 1) return bins[0]
            throw ReleaseError.Ambiguous
        }

        /** HTTP 200, правдоподобный размер и магический байт образа ESP32 (0xE9). */
        fun validateDownload(status: Int, data: ByteArray) {
            if (status != 200) throw ReleaseError.Http(status)
            if (data.size <= 64 * 1024 || data.size >= 16 * 1024 * 1024 || data[0] != 0xE9.toByte()) throw ReleaseError.NotFirmware
        }

        /** "owner/repo", "https://github.com/owner/repo" или готовый URL API → URL последнего релиза. */
        fun apiUrl(repo: String): String? {
            val r = repo.trim()
            if (r.startsWith("https://api.github.com/")) return r
            var path = r
            runCatching { URI(r) }.getOrNull()?.takeIf { it.host == "github.com" }?.let { path = it.path.orEmpty() }
            val parts = path.split('/').filter { it.isNotEmpty() }
            if (parts.size < 2) return null
            return "https://api.github.com/repos/${parts[0]}/${parts[1]}/releases/latest"
        }

        /** Разбор ответа `releases/latest`: образ для платы и подпись рядом. */
        fun parse(body: String, board: String): GitHubRelease {
            val rel = json.decodeFromString(ApiRelease.serializer(), body)
            val name = pickAsset(rel.assets.map { it.name }, board)
            val asset = rel.assets.firstOrNull { it.name == name } ?: throw ReleaseError.NoAsset
            val sigName = FirmwareSignature.signatureName(name)
            val sigUrl = rel.assets.firstOrNull { it.name == sigName }?.url?.takeIf { it.isNotEmpty() }
                ?: (asset.url + FirmwareSignature.SIGNATURE_SUFFIX)
            if (asset.url.isEmpty()) throw ReleaseError.NoAsset
            return GitHubRelease(rel.tag, asset.name, asset.url, sigUrl)
        }

        /** Последний релиз из `repo` (пусто — официальный). */
        suspend fun latest(repo: String, board: String): GitHubRelease {
            val url = apiUrl(repo.ifBlank { DEFAULT_REPO }) ?: throw ReleaseError.BadRepo
            val (status, data) = fetch(url, accept = "application/vnd.github+json")
            if (status != 200) throw ReleaseError.Http(status)
            return parse(data.toString(Charsets.UTF_8), board)
        }

        /** GET с байтовым прогрессом; (статус, тело). Редиректы GitHub на CDN — внутри https. */
        suspend fun fetch(url: String, accept: String? = null, progress: TransferProgress? = null): Pair<Int, ByteArray> =
            withContext(Dispatchers.IO) {
                val conn = URL(url).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 60_000
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent", "FolioLink-Android")
                    accept?.let { conn.setRequestProperty("Accept", it) }
                    val status = conn.responseCode
                    if (status != 200) return@withContext status to ByteArray(0)
                    val total = conn.contentLengthLong
                    val out = ByteArrayOutputStream(if (total in 1..Int.MAX_VALUE) total.toInt() else 64 * 1024)
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done > 32L * 1024 * 1024) throw ReleaseError.NotFirmware
                            progress?.invoke(done, total)
                        }
                    }
                    status to out.toByteArray()
                } finally {
                    conn.disconnect()
                }
            }
    }
}

/** Тексты и проверки экрана прошивки (без Compose). */
object FirmwareTexts {
    /** Строка для причины отказа; null — ошибка не из прошивки/релиза (текст — errorText). */
    fun errorRes(e: Throwable): Pair<Int, Any?>? = when (e) {
        FirmwareSignatureException.Missing -> R.string.firmware_sig_missing to null
        is FirmwareSignatureException.Malformed -> R.string.firmware_sig_malformed to e.size
        FirmwareSignatureException.Invalid -> R.string.firmware_sig_invalid to null
        GitHubRelease.ReleaseError.BadRepo -> R.string.firmware_err_bad_repo to null
        GitHubRelease.ReleaseError.NoAsset -> R.string.firmware_err_no_asset to null
        GitHubRelease.ReleaseError.Ambiguous -> R.string.firmware_err_ambiguous to null
        is GitHubRelease.ReleaseError.Http -> R.string.firmware_err_http to e.code
        GitHubRelease.ReleaseError.NotFirmware -> R.string.firmware_err_not_firmware to null
        GitHubRelease.ReleaseError.NoSignature -> R.string.firmware_err_no_signature to null
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException ->
            R.string.firmware_err_network to null
        else -> null
    }

    /** Предупреждать о заряде: меньше 30 % и не на зарядке (−1 — неизвестно, не предупреждать). */
    fun lowBattery(info: DeviceInfo?): Boolean = info != null && info.battery in 0 until 30 && !info.charging

    /** Образ и подпись после выбора файлов (как в iOS). */
    class Selection(val imageName: String?, val image: ByteArray?, val signature: ByteArray?)

    /**
     * `.sig` — подпись, остальное — образ. Новый образ без `.sig` в той же выборке сбрасывает
     * старую подпись; одна `.sig` подписывает уже выбранный образ.
     */
    fun select(
        previous: Selection,
        files: List<Pair<String, ByteArray>>,
    ): Selection {
        val sig = files.lastOrNull { it.first.lowercase().endsWith(".sig") }
        val img = files.lastOrNull { !it.first.lowercase().endsWith(".sig") }
        return when {
            img != null -> Selection(img.first, img.second, sig?.second)
            sig != null -> Selection(previous.imageName, previous.image, sig.second)
            else -> previous
        }
    }
}
