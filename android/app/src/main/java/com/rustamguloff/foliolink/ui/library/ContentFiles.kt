package com.rustamguloff.foliolink.ui.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.app.errorText
import com.rustamguloff.foliolink.core.epub.ArticleException
import com.rustamguloff.foliolink.core.net.readLimited
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Файлы из системного выбора и «Поделиться»: имя и содержимое по content://-ссылке. */
object ContentFiles {
    /** Потолок файла, который читается в память (картинки, шрифты, прошивка — меньше). */
    const val MAX_IN_MEMORY_BYTES = 64L * 1024 * 1024

    /** Имя от поставщика (`OpenableColumns.DISPLAY_NAME`); null — не сообщил. Общая реализация. */
    fun providerName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Отображаемое имя файла; без него — последний сегмент пути. */
    fun displayName(context: Context, uri: Uri): String =
        providerName(context, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "book"

    /** Размер от поставщика (`OpenableColumns.SIZE`); null — не сообщил. */
    fun size(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()?.takeIf { it >= 0 }

    /** MIME-тип, который сообщает поставщик (может быть null или octet-stream). */
    fun mimeType(context: Context, uri: Uri): String? = runCatching { context.contentResolver.getType(uri) }.getOrNull()

    /** Поток файла (для копирования в очередь без чтения в память); null — не открывается. */
    fun open(context: Context, uri: Uri): java.io.InputStream? = try {
        context.contentResolver.openInputStream(uri)
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    /** Содержимое файла (на IO), не больше `limit` байт; null — не прочитать или больше потолка. */
    suspend fun read(context: Context, uri: Uri, limit: Long = MAX_IN_MEMORY_BYTES): ByteArray? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { readLimited(it, limit) }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }
}

/** Текст ошибки загрузки статьи (как `ArticleExtractor.ArticleError` в iOS) и сети. */
fun articleErrorText(context: Context, e: Throwable): String = when (e) {
    ArticleException.NoContent -> context.getString(R.string.library_article_no_content)
    ArticleException.BadResponse -> context.getString(R.string.library_article_bad_response)
    is UnknownHostException, is SSLException, is java.net.ConnectException, is java.net.SocketTimeoutException ->
        context.getString(R.string.library_article_offline)
    is IllegalArgumentException, is java.net.URISyntaxException, is java.net.MalformedURLException ->
        context.getString(R.string.library_article_bad_url)
    else -> context.errorText(e)
}
