package com.rustamguloff.foliolink.core.ble

import com.rustamguloff.foliolink.core.model.DeviceInfo
import com.rustamguloff.foliolink.core.model.DeviceStats
import com.rustamguloff.foliolink.core.model.Highlight
import com.rustamguloff.foliolink.core.model.ReadingSession
import com.rustamguloff.foliolink.core.model.SyncSnapshot
import com.rustamguloff.foliolink.core.model.decodeApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.SimpleTimeZone

/** Эталон: настоящие кадры loopback симулятора (MTU 185). */
class BleGoldenSyncTest {
    private val fx = BleFixture.load()
    private val tz = SimpleTimeZone(fx.tzOffsetMin * 60_000, "fixture")

    @Test
    fun fullSyncReplaysRealFrames() = runBlocking<Unit> {
        assertEquals(185, fx.mtu)
        assertTrue(fx.full.sumOf { it.frames.size } > 100)
        val transport = BleFixture.transport(fx.full)
        val plan = BleSyncPlan(token = fx.token, nowMs = fx.now * 1000, timeZone = tz)
        val steps = mutableListOf<String>()
        val result = BleSync.run(BleCommandClient(transport), plan) { steps += it }

        // Ровно документированный порядок, страница за страницей.
        assertEquals(fx.full.map { it.request }, transport.writtenJson.map(BleFixture::withoutClientNonce))
        assertEquals(
            listOf("hello", "auth", "info", "time", "stats", "sessions", "highlights", "vocab", "books", "screenshots"),
            steps,
        )
        assertEquals(BleSync.STEPS, steps)

        assertTrue(result.failures.toString(), result.failures.isEmpty())
        assertTrue(result.clockSet)
        assertEquals("xteink_x4_pro", result.info?.board)
        assertEquals(true, result.info?.pairing?.authorized)
        assertNotNull(result.stats)
        assertEquals("6 страниц loopback с пределом 4 КБ", 132, result.sessions?.size)
        assertEquals(132, result.sessions?.map { it.id }?.toSet()?.size)
        assertEquals("17 + 9 через nextOffset", 26, result.books?.size)
        assertEquals(26, result.books?.map { it.path }?.toSet()?.size)
        assertEquals(1, result.highlights?.size)
        assertEquals(1, result.vocab?.size)
        assertEquals(0, result.screenshots?.size)
        // Настоящие кадры режут кириллицу посреди символа; склеенный JSON цел.
        val splitMidChar = fx.full.flatMap { it.frames }.filter { !isValidUtf8(it.copyOfRange(3, it.size)) }
        assertFalse(splitMidChar.isEmpty())
        assertTrue(result.sessions!!.any { s -> s.title.any { it.code in 0x400..0x4FF } })
    }

    /** Payload BLE — это тела HTTP: модели Wi-Fi читают их как есть. */
    @Test
    fun payloadsDecodeWithTheWiFiModels() {
        val info = fx.full.first { it.request == """{"op":"info"}""" }
        val decoded = decodeApi<DeviceInfo>(BleFixture.payload(info.frames).toString(Charsets.UTF_8))
        assertTrue(decoded.features.contains("stats"))
        val stats = fx.full.first { it.request == """{"op":"stats"}""" }
        decodeApi<DeviceStats>(BleFixture.payload(stats.frames).toString(Charsets.UTF_8))
    }

    @Test
    fun incrementalSyncAndMergeHaveNoDuplicates() = runBlocking<Unit> {
        // 1. Полная синхронизация в пустой кэш.
        val full = BleSync.run(
            BleCommandClient(BleFixture.transport(fx.full)),
            BleSyncPlan(token = fx.token, nowMs = fx.now * 1000, timeZone = tz),
        )
        val t1 = fx.now * 1000
        var snapshot = SyncSnapshot()
        var sessions = emptyList<ReadingSession>()
        BleSync.merge(full, snapshot, sessions, nowMs = t1).let { (s, l) -> snapshot = s; sessions = l }
        assertEquals(132, sessions.size)
        assertEquals(1, snapshot.highlights.size)
        assertEquals(26, snapshot.books.size)
        assertEquals(t1, snapshot.lastFullSessionSync)
        assertEquals(t1, snapshot.lastFullHighlightSync)
        assertEquals(t1, snapshot.lastSync)
        assertEquals(t1, snapshot.statsFetchedAt)
        assertNotNull(snapshot.info)
        assertTrue(snapshot.lastSessionStart in 1..fx.now)
        assertEquals(sessions.maxOf { it.start }.coerceAtMost(fx.now), snapshot.lastSessionStart)

        // Те же данные ещё раз (например, сразу следом Wi-Fi) ничего не меняют.
        BleSync.merge(full, snapshot, sessions, nowMs = t1).let { (s, l) -> snapshot = s; sessions = l }
        assertEquals(132, sessions.size)
        assertEquals(1, snapshot.highlights.size)

        // 2. Инкрементальная синхронизация при следующем подключении: часы верны, `time` нет.
        val infoPayload = BleFixture.payload(fx.incremental.first { it.request == """{"op":"info"}""" }.frames)
        val readerNow = decodeApi<DeviceInfo>(infoPayload.toString(Charsets.UTF_8)).clock.epoch
        val transport = BleFixture.transport(fx.incremental)
        val plan = BleSyncPlan(
            token = fx.token, sessionsSince = fx.incrementalSince, highlightsSince = fx.incrementalSince,
            nowMs = readerNow * 1000, timeZone = tz,
        )
        val inc = BleSync.run(BleCommandClient(transport), plan)
        assertEquals(fx.incremental.map { it.request }, transport.writtenJson.map(BleFixture::withoutClientNonce))
        assertTrue(inc.failures.toString(), inc.failures.isEmpty())
        assertFalse(inc.clockSet)
        val fresh = inc.sessions!!
        assertFalse(fresh.isEmpty())
        assertTrue("уже известные сессии", sessions.map { it.id }.toSet().containsAll(fresh.map { it.id }))

        BleSync.merge(inc, snapshot, sessions, nowMs = readerNow * 1000).let { (s, l) -> snapshot = s; sessions = l }
        assertEquals("без дублей сессий", 132, sessions.size)
        assertEquals(sessions.size, sessions.map { it.id }.toSet().size)
        assertEquals("пустой инкрементальный список сохраняет кэш цитат", 1, snapshot.highlights.size)
        assertEquals("инкрементальный список не двигает отметку полного", t1, snapshot.lastFullHighlightSync)
        assertEquals("инкрементальные сессии не двигают отметку полной выгрузки", t1, snapshot.lastFullSessionSync)
        assertEquals(26, snapshot.books.size)
        assertEquals(readerNow * 1000, snapshot.lastSync)
    }

    @Test
    fun highlightMergeReplacesByIdAndCursor() {
        val a = Highlight(id = "h1", book = "/b", title = "B", text = "old", created = 100)
        val b = Highlight(id = "h2", book = "/b", title = "B", text = "two", created = 200)
        val a2 = Highlight(id = "h1", book = "/b", title = "B", text = "old", note = "заметка", created = 100)
        val c = Highlight(id = "h3", book = "/b", title = "B", text = "three", created = 300)
        val merged = BleSync.mergeHighlights(listOf(a, b), listOf(a2, c))
        assertEquals(listOf("h1", "h2", "h3"), merged.map { it.id })
        assertEquals("заметка", merged[0].note)

        // Старые строки без id сливаются по локальному ключу.
        val legacy = Highlight(book = "/b", text = "legacy", created = 50)
        assertEquals(1, BleSync.mergeHighlights(listOf(legacy), listOf(legacy.copy(note = "n"))).size)

        val now = 1_000_000L
        assertEquals(0L, BleSync.highlightsCursor(emptyList(), lastFullMs = now, nowMs = now))
        assertEquals(0L, BleSync.highlightsCursor(merged, lastFullMs = null, nowMs = now))
        assertEquals(300L, BleSync.highlightsCursor(merged, lastFullMs = now - 3_600_000, nowMs = now))
        assertEquals(
            "раз в сутки полный список ловит удаления и правки",
            0L, BleSync.highlightsCursor(merged, lastFullMs = now - 25 * 3_600_000L, nowMs = now),
        )
        assertEquals("курсор не позже «сейчас»", 900L,
            BleSync.highlightsCursor(listOf(c.copy(created = 5000)), lastFullMs = 900_000L - 1000, nowMs = 900_000L))
    }
}
