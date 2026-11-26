package com.rustamguloff.foliolink.app

import android.app.Application
import android.content.Context
import com.rustamguloff.foliolink.core.ble.BleSyncManager
import com.rustamguloff.foliolink.core.services.ReminderScheduler
import com.rustamguloff.foliolink.core.services.VocabReviewStore
import com.rustamguloff.foliolink.ui.common.ImageCache
import com.rustamguloff.foliolink.ui.widget.FolioWidgets
import java.io.File

/**
 * Приложение: один [AppModel] на процесс (экраны, «Поделиться», виджеты и приёмники берут его
 * через [Context.appModel]). Здесь же подключаются Bluetooth и повторение слов.
 */
class FolioApp : Application() {
    val model: AppModel by lazy {
        AppModel.create(this).also { m ->
            Integration.vocabReview = vocabReview
            Integration.ble = BleSyncManager(this, m.scope).apply { host = m }
        }
    }

    val vocabReview: VocabReviewStore by lazy { VocabReviewStore(File(filesDir, "Store/vocab-review.json")) }

    val ble: BleSyncManager get() = model.let { Integration.ble!! }

    override fun onCreate() {
        super.onCreate()
        ReminderScheduler.ensureChannel(this)
        ImageCache.install(this)
        Integration.widgetReloader = FolioWidgets::reload
    }
}

/** `(context.applicationContext as FolioApp).model`. */
val Context.appModel: AppModel get() = (applicationContext as FolioApp).model
