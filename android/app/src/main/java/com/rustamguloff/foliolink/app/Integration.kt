package com.rustamguloff.foliolink.app

import android.annotation.SuppressLint
import android.content.Context
import com.rustamguloff.foliolink.core.ble.BleSync
import com.rustamguloff.foliolink.core.ble.BleSyncManager
import com.rustamguloff.foliolink.core.ble.BleSyncResult
import com.rustamguloff.foliolink.core.model.Book
import com.rustamguloff.foliolink.core.model.Highlight
import com.rustamguloff.foliolink.core.model.ReadingSession
import com.rustamguloff.foliolink.core.model.RemoteFile
import com.rustamguloff.foliolink.core.model.SyncSnapshot
import com.rustamguloff.foliolink.core.model.WidgetSummary
import com.rustamguloff.foliolink.core.net.DeviceClient
import com.rustamguloff.foliolink.core.services.VocabReviewStore
import com.rustamguloff.foliolink.core.stats.StatsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Точки стыковки AppModel с модулями, которые живут отдельно: статистика, Bluetooth, повторение
 * слов, кэш картинок и виджеты. Платформенные части подключает [FolioApp] при старте; в
 * JVM-тестах они не заданы, и вызовы становятся пустыми.
 */
object Integration {
    /** Bluetooth-синхронизация (создаётся в [FolioApp], держит только applicationContext). */
    @SuppressLint("StaticFieldLeak")
    @Volatile var ble: BleSyncManager? = null

    /** Состояние повторения слов из словаря. */
    @Volatile var vocabReview: VocabReviewStore? = null

    /** Заранее загрузить миниатюры скриншотов; задаёт кэш картинок в UI. */
    @Volatile var prefetcher: (suspend (DeviceClient, List<RemoteFile>) -> Unit)? = null

    /** Убрать картинку из кэша (файл на читалке заменён). */
    @Volatile var evictor: ((String) -> Unit)? = null

    /** Обновить виджеты на рабочем столе. */
    @Volatile var widgetReloader: ((Context) -> Unit)? = null

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Секунды чтения за сегодня: сессии, а если stats свежие — не меньше, чем считает читалка. */
    fun todaySeconds(snapshot: SyncSnapshot, sessions: List<ReadingSession>, nowMs: Long): Int {
        val zone = ZoneId.systemDefault()
        val daily = StatsEngine.dailySeconds(sessions, snapshot.stats?.days ?: emptyList())
        var secs = daily[StatsEngine.dayKey(nowMs, zone)] ?: 0
        val stats = snapshot.stats
        if (stats != null && isFresh(snapshot, nowMs, zone)) secs = maxOf(secs, stats.today.secs)
        return secs
    }

    /** (текущая, лучшая) серия: по сессиям и, если stats свежие, не меньше серии читалки. */
    fun streaks(snapshot: SyncSnapshot, sessions: List<ReadingSession>, nowMs: Long): Pair<Int, Int> {
        val zone = ZoneId.systemDefault()
        val daily = StatsEngine.dailySeconds(sessions, snapshot.stats?.days ?: emptyList())
        val local = StatsEngine.streaks(daily, StatsEngine.localDate(nowMs, zone))
        val device = snapshot.stats?.streak
        val fresh = isFresh(snapshot, nowMs, zone)
        val current = maxOf(local.current, if (fresh) device?.current ?: 0 else 0)
        val longest = maxOf(local.longest, device?.longest ?: 0, current)
        return current to longest
    }

    fun currentlyReading(books: List<Book>): List<Book> = StatsEngine.currentlyReading(books)

    fun widgetSummary(snapshot: SyncSnapshot, sessions: List<ReadingSession>, goalMinutes: Int, nowMs: Long): WidgetSummary =
        StatsEngine.widgetSummary(snapshot, sessions, goalMinutes, nowMs)

    fun bleMerge(
        result: BleSyncResult,
        snapshot: SyncSnapshot,
        sessions: List<ReadingSession>,
    ): Pair<SyncSnapshot, List<ReadingSession>> = BleSync.merge(result, snapshot, sessions)

    fun highlightsCursor(highlights: List<Highlight>, lastFull: Long?, nowMs: Long): Long =
        BleSync.highlightsCursor(highlights, lastFull, nowMs)

    fun vocabReviewRemove(word: String) {
        val store = vocabReview ?: return
        background.launch { store.remove(word) }
    }

    fun bleForgetReader() { ble?.forgetReader() }

    fun bleAppBecameActive() { ble?.appBecameActive() }

    suspend fun prefetchScreenshots(client: DeviceClient, items: List<RemoteFile>) {
        prefetcher?.invoke(client, items)
    }

    fun evictImage(path: String) { evictor?.invoke(path) }

    fun reloadWidgets(context: Context) { widgetReloader?.invoke(context) }

    private fun isFresh(snapshot: SyncSnapshot, nowMs: Long, zone: ZoneId): Boolean =
        snapshot.statsFetchedAt?.let { StatsEngine.localDate(it, zone) == StatsEngine.localDate(nowMs, zone) } ?: false
}
