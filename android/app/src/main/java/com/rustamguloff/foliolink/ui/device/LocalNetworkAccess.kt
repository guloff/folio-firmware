package com.rustamguloff.foliolink.ui.device

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Доступ к устройствам локальной сети (Android 17+). До 17 — всегда есть. [run] выполняет действие
 * сразу или после выданного разрешения; отказ «навсегда» переводит в [LocalNetworkUi.OPEN_SETTINGS].
 */
@Stable
class LocalNetworkAccess internal constructor(private val context: Context) {
    var granted by mutableStateOf(isGranted(context))
        internal set
    var permanentlyDenied by mutableStateOf(false)
        internal set
    internal var pending: (() -> Unit)? = null
    internal var launch: () -> Unit = {}

    val ui: LocalNetworkUi get() = localNetworkUi(Build.VERSION.SDK_INT, granted, permanentlyDenied)

    /** Выполнить `action`, когда доступ есть (при необходимости — спросить). */
    fun run(action: () -> Unit) {
        when (ui) {
            LocalNetworkUi.READY -> action()
            LocalNetworkUi.ASK -> { pending = action; launch() }
            LocalNetworkUi.OPEN_SETTINGS -> com.rustamguloff.foliolink.ui.settings.openAppSettings(context)
        }
    }

    fun request() = run {}

    internal fun refresh() { granted = isGranted(context) }

    companion object {
        fun isGranted(context: Context): Boolean =
            Build.VERSION.SDK_INT < LOCAL_NETWORK_MIN_SDK ||
                ContextCompat.checkSelfPermission(context, LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
fun rememberLocalNetworkAccess(): LocalNetworkAccess {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val access = remember { LocalNetworkAccess(context.applicationContext) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        access.granted = ok
        if (ok) {
            access.permanentlyDenied = false
            access.pending?.invoke()
        } else {
            // Нет повода объяснять ещё раз — система больше не покажет запрос.
            access.permanentlyDenied = activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, LOCAL_NETWORK_PERMISSION)
        }
        access.pending = null
    }
    access.launch = { launcher.launch(LOCAL_NETWORK_PERMISSION) }
    // Вернулись из настроек приложения — разрешение могли выдать там.
    LifecycleResumeEffect(access) {
        access.refresh()
        if (access.granted) access.permanentlyDenied = false
        onPauseOrDispose {}
    }
    return access
}
