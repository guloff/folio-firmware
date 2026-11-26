package com.rustamguloff.foliolink.core.security

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest

/** Почему образ прошивки отклонён; текст для человека — в UI. */
sealed class FirmwareSignatureException(message: String) : Exception(message) {
    /** Нет файла подписи (.bin.sig). */
    data object Missing : FirmwareSignatureException("signature missing")

    /** Подпись не 64 байта. */
    data class Malformed(val size: Int) : FirmwareSignatureException("signature is $size bytes, expected 64")

    /** Подпись не сходится: образ изменён или подписан чужим ключом. */
    data object Invalid : FirmwareSignatureException("signature invalid")
}

/**
 * Подписи прошивки Folio (контракт v2 §2): ed25519 над `"FOLIO-FW-v1\n"` (12 байт) +
 * SHA-256 образа (32 байта); 64 байта подписи лежат рядом с образом как `<image>.bin.sig`.
 * Приложение не отправляет образ, который не проверяется ключом Folio.
 * Ed25519 — через BouncyCastle (в платформе он есть только с API 33).
 */
object FirmwareSignature {
    /** Релизный ключ Folio (32 байта, hex). Закрытая половина не покидает Mac сопровождающего. */
    const val PUBLIC_KEY_HEX = "26c08350263f1909220148c4c7d5983bb0b2493413f64c29579360f08d6c233b"
    val DOMAIN: ByteArray = "FOLIO-FW-v1\n".toByteArray(Charsets.UTF_8)
    const val SIGNATURE_SUFFIX = ".sig"
    const val SIGNATURE_SIZE = 64

    /** 44 подписываемых байта для образа. */
    fun message(image: ByteArray): ByteArray = DOMAIN + MessageDigest.getInstance("SHA-256").digest(image)

    /** Открытый ключ из hex; [FirmwareSignatureException.Invalid], если это не 32 байта hex. */
    fun publicKey(hex: String = PUBLIC_KEY_HEX): Ed25519PublicKeyParameters {
        val raw = Hex.decode(hex)?.takeIf { it.size == Ed25519PublicKeyParameters.KEY_SIZE }
            ?: throw FirmwareSignatureException.Invalid
        return runCatching { Ed25519PublicKeyParameters(raw, 0) }.getOrElse { throw FirmwareSignatureException.Invalid }
    }

    /** Бросает [FirmwareSignatureException], если `signature` — не подпись Folio для `image`. */
    fun verify(image: ByteArray, signature: ByteArray?, publicKeyHex: String = PUBLIC_KEY_HEX) {
        if (signature == null || signature.isEmpty()) throw FirmwareSignatureException.Missing
        if (signature.size != SIGNATURE_SIZE) throw FirmwareSignatureException.Malformed(signature.size)
        val key = publicKey(publicKeyHex)
        val ok = runCatching {
            Ed25519Signer().run {
                init(false, key)
                val msg = message(image)
                update(msg, 0, msg.size)
                verifySignature(signature)
            }
        }.getOrDefault(false)
        if (!ok) throw FirmwareSignatureException.Invalid
    }

    /** true, если подпись верна (без причины отказа). */
    fun isValid(image: ByteArray, signature: ByteArray?, publicKeyHex: String = PUBLIC_KEY_HEX): Boolean =
        runCatching { verify(image, signature, publicKeyHex) }.isSuccess

    fun signatureName(imageName: String): String = imageName + SIGNATURE_SUFFIX
}

/** Hex ↔ байты. */
object Hex {
    /** "0a1B…" → байты; null при нечётной длине или не-hex символе. */
    fun decode(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[2 * i], 16)
            val lo = Character.digit(hex[2 * i + 1], 16)
            if (hi < 0 || lo < 0 || hex[2 * i].code > 0x7F || hex[2 * i + 1].code > 0x7F) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun encode(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/**
 * Проект и версия из `esp_app_desc_t` образа (контракт v2, уточнение 7): первый байт 0xE9,
 * magic 0xABCD5432 по смещению 0x20, `version[32]` — 0x30, `project_name[32]` — 0x50.
 */
data class FirmwareImageInfo(val project: String, val version: String) {
    companion object {
        fun parse(image: ByteArray): FirmwareImageInfo? {
            if (image.size < 0x70 || image[0] != 0xE9.toByte()) return null
            fun u8(o: Int) = image[o].toLong() and 0xFF
            val magic = u8(0x20) or (u8(0x21) shl 8) or (u8(0x22) shl 16) or (u8(0x23) shl 24)
            if (magic != 0xABCD5432L) return null
            fun cString(offset: Int): String {
                var end = offset
                while (end < offset + 32 && image[end] != 0.toByte()) end++
                return String(image, offset, end - offset, Charsets.UTF_8).trim()
            }
            val version = cString(0x30)
            val project = cString(0x50)
            if (version.isEmpty() && project.isEmpty()) return null
            return FirmwareImageInfo(project, version)
        }
    }
}
