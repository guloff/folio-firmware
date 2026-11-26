package com.rustamguloff.foliolink.ui.settings

import android.content.Context
import android.net.Uri
import com.rustamguloff.foliolink.core.net.readLimited
import com.rustamguloff.foliolink.ui.library.ContentFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Файл, выбранный в системном окне выбора (шрифты, прошивка). */
class PickedFile(val name: String, val data: ByteArray)

/** Читает выбранные файлы на IO; нечитаемые — во втором списке (по имени). */
suspend fun readPickedFiles(
    context: Context,
    uris: List<Uri>,
    maxBytes: Long = 32L * 1024 * 1024,
): Pair<List<PickedFile>, List<String>> = withContext(Dispatchers.IO) {
    val ok = mutableListOf<PickedFile>()
    val failed = mutableListOf<String>()
    for (uri in uris) {
        val name = displayName(context, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val data = runCatching {
            // Больше потолка не читается вовсе (а не читается целиком и отбрасывается).
            context.contentResolver.openInputStream(uri)?.use { input -> readLimited(input, maxBytes) }
        }.getOrNull()
        if (data == null) failed += name else ok += PickedFile(name, data)
    }
    ok to failed
}

private fun displayName(context: Context, uri: Uri): String? = ContentFiles.providerName(context, uri)
