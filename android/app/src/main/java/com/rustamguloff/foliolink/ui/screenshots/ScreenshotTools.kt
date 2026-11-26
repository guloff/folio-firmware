package com.rustamguloff.foliolink.ui.screenshots

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.rustamguloff.foliolink.app.GalleryAccessDenied
import com.rustamguloff.foliolink.ui.common.LocalAppModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Общие для скриншотов и студии заставок: «Поделиться» файлом, просмотр с увеличением,
// разрешение на запись в галерею на Android 8–9.

/** Файлы для «Поделиться»: `cacheDir/share/` (FileProvider `${packageName}.files`). */
object ShareFiles {
    /** Пишет байты в `cacheDir/share/<name>` и возвращает файл. */
    suspend fun write(context: Context, name: String, bytes: ByteArray): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        File(dir, name.replace('/', '_')).apply { writeBytes(bytes) }
    }

    /** Bitmap → PNG без потерь в `cacheDir/share/<name>`. */
    suspend fun writePng(context: Context, name: String, bitmap: Bitmap): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        File(dir, name.replace('/', '_')).apply {
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** Системное окно «Поделиться» для файла. */
    fun share(context: Context, file: File, mime: String, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, chooserTitle).apply {
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
        }
    }
}

/**
 * Возвращает «выполнить с доступом к галерее»: на API < 29 сначала спрашивает
 * WRITE_EXTERNAL_STORAGE (в манифесте с maxSdkVersion 28); отказ — баннер, действие не идёт.
 */
@Composable
fun rememberGalleryAccess(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    val model = LocalAppModel.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pending
        pending = null
        if (granted) action?.invoke() else model.show(GalleryAccessDenied())
    }
    return remember(launcher) {
        { action ->
            val needs = Build.VERSION.SDK_INT < 29 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            if (needs) {
                pending = action
                launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                action()
            }
        }
    }
}

/**
 * Картинка с увеличением щипком и двойным касанием. Пиксели без сглаживания: видно ровно то,
 * что на экране читалки.
 */
@Composable
fun ZoomableBitmap(bitmap: ImageBitmap, contentDescription: String?, modifier: Modifier = Modifier) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    fun clamp(o: Offset, s: Float): Offset {
        val maxX = size.width * (s - 1) / 2
        val maxY = size.height * (s - 1) / 2
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
    Image(
        bitmap = bitmap,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None,
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { size = it }
            .pointerInput(bitmap) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 3f
                    }
                })
            }
            .pointerInput(bitmap) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    offset = if (scale == 1f) Offset.Zero else clamp(offset + pan, scale)
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}
