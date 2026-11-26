package com.rustamguloff.foliolink.ui.notes

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.rustamguloff.foliolink.R
import java.io.File

/** Отправка экспорта и цитат через системное «Поделиться» (файлы — из `cacheDir/share`). */
object Sharing {

    /** Папка для файлов, которыми делимся (FileProvider `${packageName}.files`, `cache-path share/`). */
    fun shareDir(context: Context): File = File(context.cacheDir, "share").apply { mkdirs() }

    /** Прошлые выгрузки заметок больше не нужны: удаляются перед новой (чужие файлы не трогаем). */
    fun clearOldExports(context: Context) {
        shareDir(context).listFiles()?.forEach { f ->
            if (f.isDirectory && (f.name.startsWith("FolioLinkExport-") || f.name.startsWith("FolioLinkReadwise-"))) {
                f.deleteRecursively()
            }
        }
    }

    /** Один файл — ACTION_SEND, несколько — ACTION_SEND_MULTIPLE; с правом чтения для получателя. */
    fun shareFiles(context: Context, files: List<File>, mime: String, chooserTitle: String) {
        if (files.isEmpty()) return
        val authority = "${context.packageName}.files"
        val uris = files.map { FileProvider.getUriForFile(context, authority, it) }
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
        }
        send.type = mime
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // ClipData переносит право чтения через chooser на все файлы.
        val clip = ClipData.newRawUri(files[0].name, uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        send.clipData = clip
        start(context, Intent.createChooser(send, chooserTitle))
    }

    fun shareText(context: Context, text: String, chooserTitle: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        start(context, Intent.createChooser(send, chooserTitle))
    }

    /**
     * «Посмотреть в словаре»: приложения-словари и переводчики принимают ACTION_PROCESS_TEXT;
     * если таких нет (или их не видно без `<queries>`), — веб-поиск определения.
     */
    fun lookUp(context: Context, word: String) {
        val process = Intent(Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, word)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        val handlers = runCatching { context.packageManager.queryIntentActivities(process, 0) }.getOrNull().orEmpty()
        if (handlers.isNotEmpty()) {
            start(context, Intent.createChooser(process, context.getString(R.string.notes_lookup)))
            return
        }
        val search = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, context.getString(R.string.notes_define_query, word))
        try {
            context.startActivity(search.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            val url = "https://www.google.com/search".toUri().buildUpon()
                .appendQueryParameter("q", context.getString(R.string.notes_define_query, word)).build()
            start(context, Intent(Intent.ACTION_VIEW, url))
        }
    }

    fun openUrl(context: Context, url: String) = start(context, Intent(Intent.ACTION_VIEW, url.toUri()))

    private fun start(context: Context, intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            // Некуда отправить — молча: системный выбор сам показывает «нет приложений».
        }
    }
}
