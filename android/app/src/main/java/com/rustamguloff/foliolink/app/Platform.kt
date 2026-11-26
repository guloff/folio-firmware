package com.rustamguloff.foliolink.app

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.rustamguloff.foliolink.core.services.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** Строки из ресурсов (в тестах — фейк). */
fun interface Texts {
    fun get(@StringRes id: Int, vararg args: Any): String
}

/** Нет разрешения на запись в галерею (API < 29 — WRITE_EXTERNAL_STORAGE). */
class GalleryAccessDenied : Exception("gallery access denied")

/** Картинка не декодируется (не BMP/PNG/JPEG). */
class ImageDecodeFailed(val name: String) : Exception("cannot decode $name")

/**
 * Всё платформенное, что нужно [AppModel]: строки, имя телефона, галерея, напоминания, виджеты.
 * Так AppModel проверяется JVM-тестами с фейком.
 */
interface Platform : Texts {
    /** Имя, которое читалка покажет рядом с PIN и сохранит с токеном. */
    val phoneName: String

    /** Сохраняет картинку с читалки (BMP) в галерею как PNG: `Pictures/FolioLink`. */
    suspend fun saveToGallery(image: ByteArray, fileName: String)

    fun rescheduleReminders(enabled: Boolean, minutesOfDay: Int, goalMetToday: Boolean, todayMinutes: Int, goalMinutes: Int)

    fun reloadWidgets()
}

class AndroidPlatform(private val context: Context) : Platform {
    override fun get(id: Int, vararg args: Any): String = context.getString(id, *args)

    override val phoneName: String
        get() = runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL)
                .filter { it.isNotBlank() }.distinct().joinToString(" ").ifEmpty { "Android" }

    override suspend fun saveToGallery(image: ByteArray, fileName: String): Unit = withContext(Dispatchers.IO) {
        // BMP с читалки → PNG без потерь (ровно 4 оттенка серого).
        val bitmap = BitmapFactory.decodeByteArray(image, 0, image.size) ?: throw ImageDecodeFailed(fileName)
        val png = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        bitmap.recycle()
        val name = fileName.substringBeforeLast('.', fileName) + ".png"
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("MediaStore insert failed")
            try {
                resolver.openOutputStream(uri)?.use { it.write(png) } ?: throw java.io.IOException("no stream")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            // API 26–28: прямая запись в Pictures/FolioLink, нужно WRITE_EXTERNAL_STORAGE (maxSdkVersion 28).
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) throw GalleryAccessDenied()
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
            dir.mkdirs()
            var file = File(dir, name)
            var n = 1
            while (file.exists()) file = File(dir, name.substringBeforeLast('.') + "-${n++}.png")
            file.writeBytes(png)
            MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf("image/png"), null)
        }
    }

    override fun rescheduleReminders(enabled: Boolean, minutesOfDay: Int, goalMetToday: Boolean, todayMinutes: Int, goalMinutes: Int) {
        ReminderScheduler.reschedule(context, enabled, minutesOfDay, goalMetToday, todayMinutes, goalMinutes)
    }

    override fun reloadWidgets() = Integration.reloadWidgets(context)

    companion object {
        const val ALBUM = "FolioLink"
    }
}
