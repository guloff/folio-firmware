package com.rustamguloff.foliolink.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.rustamguloff.foliolink.app.Integration
import com.rustamguloff.foliolink.core.imaging.AndroidImaging
import com.rustamguloff.foliolink.core.net.DeviceClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Кэш картинок с читалки (скриншоты, заставки): память + файлы в `cacheDir/images`. Ключ —
 * путь на читалке; [evict] — когда файл на читалке заменён. Загрузки идут по одной: ESP32
 * плохо переносит параллельные запросы.
 */
object ImageCache {
    private val memory = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val gate = Mutex()
    private var dir: File? = null

    fun install(context: Context) {
        dir = File(context.cacheDir, "images").apply { mkdirs() }
        Integration.prefetcher = { client, items -> items.forEach { runCatching { load(client, it.fullPath) } } }
        Integration.evictor = ::evict
    }

    fun cached(path: String): Bitmap? = memory.get(path)

    fun evict(path: String) {
        memory.remove(path)
        fileFor(path)?.delete()
    }

    /** Картинка по пути на читалке; null — нет связи и нет в кэше. Кидает ошибку разбора. */
    suspend fun load(client: DeviceClient?, path: String): Bitmap? {
        memory.get(path)?.let { return it }
        val file = fileFor(path)
        withContext(Dispatchers.IO) { file?.takeIf { it.exists() }?.readBytes() }?.let { bytes ->
            decode(bytes)?.let { memory.put(path, it); return it }
        }
        client ?: return null
        val bytes = gate.withLock { client.download(path) }
        val bitmap = withContext(Dispatchers.Default) { decode(bytes) } ?: error("not an image")
        withContext(Dispatchers.IO) { file?.writeBytes(bytes) }
        memory.put(path, bitmap)
        return bitmap
    }

    /** BMP читалки: системный декодер, а для экзотики (8 бит с палитрой, top-down) — свой. */
    fun decode(bytes: ByteArray): Bitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: runCatching { AndroidImaging.decodeBmp(bytes) }.getOrNull()

    private fun fileFor(path: String): File? {
        val d = dir ?: return null
        val hash = MessageDigest.getInstance("SHA-1").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(d, hash)
    }
}

/** Картинка с читалки по пути; пока грузится — индикатор, при ошибке — значок. */
@Composable
fun RemoteImage(
    path: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val model = LocalAppModel.current
    var bitmap by remember(path) { mutableStateOf(ImageCache.cached(path)) }
    var failed by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path) {
        if (bitmap == null) {
            bitmap = runCatching { ImageCache.load(model.client, path) }.getOrNull()
            failed = bitmap == null
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        val b = bitmap
        when {
            b != null -> Image(
                b.asImageBitmap(), contentDescription, contentScale = contentScale,
                // Пиксели читалки — 4 уровня серого: без размытия при увеличении.
                filterQuality = FilterQuality.Medium,
            )
            failed -> Icon(Icons.Outlined.BrokenImage, contentDescription, tint = MaterialTheme.colorScheme.outline)
            else -> CircularProgressIndicator(strokeWidth = 2.dp)
        }
    }
}
