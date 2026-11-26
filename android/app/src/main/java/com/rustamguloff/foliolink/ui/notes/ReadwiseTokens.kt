package com.rustamguloff.foliolink.ui.notes

import android.content.Context
import com.rustamguloff.foliolink.core.export.ReadwiseTokenStore
import com.rustamguloff.foliolink.core.store.KeystoreCipher
import com.rustamguloff.foliolink.core.store.SharedStore
import com.rustamguloff.foliolink.core.store.TokenCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Токен Readwise, зашифрованный AES-GCM ([TokenCipher]: на устройстве — ключ Android Keystore).
 * Файл `[IV][шифротекст]`; нечитаемый (ключ потерян, файл с другого устройства) — как будто
 * токена нет: пользователь просто вставит его заново. Сам токен никуда не логируется.
 */
class EncryptedReadwiseTokenStore(
    private val file: File,
    private val cipher: TokenCipher,
) : ReadwiseTokenStore {
    private val mutex = Mutex()

    override suspend fun token(): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext null
            val raw = runCatching { file.readBytes() }.getOrNull() ?: return@withContext null
            cipher.decrypt(raw)?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    override suspend fun save(token: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val clean = token.trim()
            if (clean.isEmpty()) {
                file.delete()
            } else {
                file.parentFile?.mkdirs()
                SharedStore.writeAtomically(file, cipher.encrypt(clean.toByteArray(Charsets.UTF_8)))
            }
            Unit
        }
    }

    override suspend fun remove() = mutex.withLock {
        withContext(Dispatchers.IO) { file.delete(); Unit }
    }

    companion object {
        private const val KEY_ALIAS = "foliolink.readwise"

        /** Хранилище приложения: `noBackupFilesDir/readwise/token.bin` (вне резервной копии), ключ Keystore. */
        fun standard(context: Context): ReadwiseTokenStore = EncryptedReadwiseTokenStore(
            File(File(context.noBackupFilesDir, "readwise"), "token.bin"),
            KeystoreCipher(KEY_ALIAS),
        )
    }
}
