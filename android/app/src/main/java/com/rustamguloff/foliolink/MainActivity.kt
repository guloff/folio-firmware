package com.rustamguloff.foliolink

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.rustamguloff.foliolink.app.appModel
import com.rustamguloff.foliolink.ui.common.LocalAppModel
import com.rustamguloff.foliolink.ui.shell.Dest
import com.rustamguloff.foliolink.ui.shell.LocalRouter
import com.rustamguloff.foliolink.ui.shell.RootScreen
import com.rustamguloff.foliolink.ui.shell.Router
import com.rustamguloff.foliolink.ui.shell.Tab
import com.rustamguloff.foliolink.ui.theme.FolioTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize

/**
 * Единственная активность. Кроме запуска из лаунчера принимает ссылки `foliolink://home|stats|
 * library|notes` (виджет) и, в отладочной сборке, параметры автоматической проверки через
 * `adb shell am start … --es tab stats --es open screensaver --es autoConnect 10.0.2.2:8080 --ez demo true`.
 */
class MainActivity : ComponentActivity() {
    /** Навигация переживает пересоздание активности (поворот, смена темы). */
    class RouterHolder : ViewModel() { val router = Router() }

    private val router: Router get() = holder.router
    private val holder: RouterHolder by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val model = appModel
        val launch = savedInstanceState == null
        if (launch) route(intent)
        setContent {
            FolioTheme {
                CompositionLocalProvider(LocalAppModel provides model, LocalRouter provides router) {
                    Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.testTag("root")) {
                        RootScreen(router)
                    }
                }
                LaunchedEffect(Unit) {
                    if (!launch) return@LaunchedEffect
                    val auto = automation(intent)
                    when {
                        auto.demo -> model.connectDemo()
                        auto.address != null -> model.connect(auto.address)
                        else -> model.reconnectIfPossible()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        appModel.onAppForeground()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        route(intent)
        val auto = automation(intent)
        if (auto.demo) appModel.launch { connectDemo() } else auto.address?.let { a -> appModel.launch { connect(a) } }
    }

    private fun route(intent: Intent?) {
        intent ?: return
        intent.data?.takeIf { it.scheme == "foliolink" }?.host?.let { host ->
            router.select(
                when (host) {
                    "stats" -> Tab.STATS
                    "library" -> Tab.LIBRARY
                    "notes" -> Tab.NOTES
                    else -> Tab.HOME
                },
            )
        }
        if (!BuildConfig.DEBUG) return
        // Отладка: каталог словарей с локального сервера (`--es catalogUrl http://10.0.2.2:8000/manifest.json`).
        intent.getStringExtra("catalogUrl")?.let { appModel.settings.dictionaryCatalogUrl = it.trim() }
        intent.getStringExtra("tab")?.let { t -> Tab.entries.firstOrNull { it.name.equals(t, true) }?.let(router::select) }
        Dest.from(intent.getStringExtra("open"))?.let(router::open)
    }

    private data class Automation(val demo: Boolean, val address: String?)

    private fun automation(intent: Intent?): Automation {
        if (!BuildConfig.DEBUG || intent == null) return Automation(false, null)
        return Automation(intent.getBooleanExtra("demo", false), intent.getStringExtra("autoConnect"))
    }
}
