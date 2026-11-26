package com.rustamguloff.foliolink.core.store

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Токены сопряжения (контракт v2 §1), по одному на читалку (ключ — серийный номер, иначе host:port).
 * Сам токен — единственный секрет приложения: в notes/логах не пишется.
 */
interface PairingTokenStore {
    fun token(device: String): String?

    /** True — токен записан и переживёт перезапуск. */
    fun save(token: String, device: String): Boolean

    /** True — записи больше нет (или её и не было). */
    fun remove(device: String): Boolean
}

/** Шифр для файла токенов: [KeystoreCipher] на устройстве, программный AES в тестах. */
interface TokenCipher {
    fun encrypt(plain: ByteArray): ByteArray
    /** null — расшифровать нельзя (ключ потерян, файл с другого устройства). */
    fun decrypt(data: ByteArray): ByteArray?
}

/**
 * Файл `{"<читалка>":"<токен>"}`. Без шифра — открытый JSON (для JVM-тестов и отладки), с шифром —
 * `[12 байт IV][AES-GCM шифротекст]`.
 *
 * «Файла нет» и «файл не расшифровался» — разные состояния. Нечитаемый файл токенов не выдаёт
 * (телефон сопрягается заново по PIN), но и не перезаписывается одной новой записью: перед
 * первой записью он откладывается в сторону (`tokens.bin.unreadable-<время>`), и если отложить не
 * удалось, запись не выполняется.
 */
class FilePairingTokenStore(
    private val file: File,
    private val cipher: TokenCipher? = null,
) : PairingTokenStore {
    private val lock = Any()

    private sealed interface State {
        data class Readable(val tokens: Map<String, String>) : State
        data object Missing : State
        data object Unreadable : State
    }

    override fun token(device: String): String? = synchronized(lock) {
        (read() as? State.Readable)?.tokens?.get(device)
    }

    override fun save(token: String, device: String): Boolean = synchronized(lock) {
        when (val state = read()) {
            is State.Readable -> write(state.tokens + (device to token))
            State.Missing -> write(mapOf(device to token))
            State.Unreadable -> setAsideUnreadable() && write(mapOf(device to token))
        }
    }

    override fun remove(device: String): Boolean = synchronized(lock) {
        when (val state = read()) {
            is State.Readable -> if (device in state.tokens) write(state.tokens - device) else true
            State.Missing -> true
            // Что в файле — неизвестно: не трогаем.
            State.Unreadable -> false
        }
    }

    private fun read(): State {
        if (!file.exists()) return State.Missing
        return try {
            val raw = file.readBytes()
            val plain = if (cipher != null) {
                cipher.decrypt(raw) ?: run {
                    Log.w(TAG, "token file cannot be decrypted")
                    return State.Unreadable
                }
            } else {
                raw
            }
            State.Readable(Json.decodeFromString(MAP, plain.toString(Charsets.UTF_8)))
        } catch (e: Exception) {
            Log.w(TAG, "token file unreadable: ${e.javaClass.simpleName}")
            State.Unreadable
        }
    }

    private fun setAsideUnreadable(): Boolean {
        val aside = SharedStore.setAside(file)
        if (aside == null) {
            Log.e(TAG, "unreadable token file could not be set aside; not overwriting it")
            return false
        }
        Log.w(TAG, "unreadable token file kept as ${aside.name}")
        return true
    }

    private fun write(tokens: Map<String, String>): Boolean = try {
        val plain = Json.encodeToString(MAP, tokens).toByteArray(Charsets.UTF_8)
        SharedStore.writeAtomically(file, cipher?.encrypt(plain) ?: plain)
        true
    } catch (e: Exception) {
        Log.e(TAG, "token file write failed: ${e.javaClass.simpleName}")
        false
    }

    companion object {
        private const val TAG = "Pairing"
        private val MAP = MapSerializer(String.serializer(), String.serializer())

        /** Хранилище приложения: файл в `noBackupFilesDir` (в резервную копию не попадает), шифр Keystore. */
        fun standard(noBackupDir: File): PairingTokenStore =
            FilePairingTokenStore(File(File(noBackupDir, "pairing"), "tokens.bin"), KeystoreCipher())
    }
}

/** AES-256-GCM: `[IV 12 байт][шифротекст+тег]`. Ключ даёт [key]. */
open class AesGcmCipher(private val key: () -> SecretKey) : TokenCipher {
    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        require(iv.size == IV_SIZE)
        return iv + c.doFinal(plain)
    }

    override fun decrypt(data: ByteArray): ByteArray? {
        if (data.size <= IV_SIZE) return null
        return try {
            val c = Cipher.getInstance(TRANSFORMATION)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV_SIZE))
            c.doFinal(data, IV_SIZE, data.size - IV_SIZE)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}

/**
 * Ключ AES в Android Keystore (не извлекаемый, только на этом устройстве). Доступен без
 * разблокировки экрана — нужен фоновой синхронизации по Bluetooth.
 */
class KeystoreCipher(private val alias: String = "foliolink.pairing") : AesGcmCipher({ keystoreKey(alias) }) {
    companion object {
        private const val PROVIDER = "AndroidKeyStore"

        @Synchronized
        fun keystoreKey(alias: String): SecretKey {
            val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
            (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .apply { if (Build.VERSION.SDK_INT >= 28) setUnlockedDeviceRequired(false) }
                .build()
            gen.init(spec)
            return gen.generateKey()
        }
    }
}
