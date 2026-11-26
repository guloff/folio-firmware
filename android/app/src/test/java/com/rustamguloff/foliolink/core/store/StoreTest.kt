package com.rustamguloff.foliolink.core.store

import com.rustamguloff.foliolink.core.model.Book
import com.rustamguloff.foliolink.core.model.DeviceEndpoint
import com.rustamguloff.foliolink.core.model.DeviceSetting
import com.rustamguloff.foliolink.core.model.ReadingSession
import com.rustamguloff.foliolink.core.model.SettingType
import com.rustamguloff.foliolink.core.model.SleepResponse
import com.rustamguloff.foliolink.core.model.SyncSnapshot
import com.rustamguloff.foliolink.core.model.WidgetSummary
import com.rustamguloff.foliolink.core.model.decodeApi
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

class StoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // region SharedStore

    @Test
    fun sharedStoreRoundTripsSnapshotSessionsAndWidget() {
        val store = SharedStore(File(tmp.root, "Store"))
        assertEquals(SyncSnapshot(), store.loadSnapshot())
        assertTrue(store.loadSessions().isEmpty())
        assertNull(store.loadWidgetSummary())

        val sleep = SleepResponse(mode = 2, selected = "card.bmp")
        val snap = SyncSnapshot(
            device = DeviceEndpoint("10.0.0.2", 8080, "Folio"), lastSync = 1_790_380_800_000, deviceKey = "SN1",
            books = listOf(Book(path = "/Books/Дюна.epub", title = "Дюна")), sleep = sleep,
            deviceSettings = listOf(DeviceSetting(key = "sleepScreen", type = SettingType.ENUM, value = JsonPrimitive(2))),
        )
        store.saveSnapshot(snap)
        val sessions = listOf(ReadingSession(book = "/a", start = 5, secs = 60, day = "2026-09-26"))
        store.saveSessions(sessions)
        store.saveWidgetSummary(WidgetSummary.placeholder)

        val reopened = SharedStore(File(tmp.root, "Store"))
        assertEquals(snap, reopened.loadSnapshot())
        assertEquals(sessions, reopened.loadSessions())
        assertEquals(WidgetSummary.placeholder, reopened.loadWidgetSummary())
        assertTrue(File(tmp.root, "Store/snapshot.json").exists())
        assertTrue(File(tmp.root, "Store/sessions.json").exists())
        assertTrue(File(tmp.root, "Store/widget.json").exists())
        assertFalse("no temp files left", File(tmp.root, "Store").list()!!.any { it.endsWith(".tmp") })

        // Повреждённый файл — пустое значение, а не падение.
        File(tmp.root, "Store/snapshot.json").writeText("{broken")
        assertEquals(SyncSnapshot(), reopened.loadSnapshot())
        reopened.remove(SharedStore.SESSIONS_FILE)
        assertTrue(reopened.loadSessions().isEmpty())
    }

    @Test
    fun snapshotFromOlderBuildWithoutSelectedStillLoads() {
        val old = decodeApi<SleepResponse>("""{"dir":"/sleep","images":[],"mode":8}""")
        assertEquals("", old.selected)
        assertEquals("", decodeApi<SleepResponse>("""{"selected":null,"mode":2}""").selected)
    }

    @Test
    fun mergeDeduplicatesAndSorts() {
        val a = ReadingSession(book = "/a", start = 10, secs = 60, pages = 1, day = "d")
        val b = ReadingSession(book = "/b", start = 5, secs = 30, pages = 1, day = "d")
        val merged = SharedStore.merge(listOf(a), listOf(b, a))
        assertEquals(listOf(b, a), merged)
    }

    // endregion

    // region SettingsProfile (правила из iOS Release11Tests)

    private val schema = listOf(
        DeviceSetting("fontSize", "Font Size", "Reader", SettingType.ENUM, JsonPrimitive(1), listOf("S", "M", "L", "XL")),
        DeviceSetting("lineSpacing", "Line Spacing", "Reader", SettingType.ENUM, JsonPrimitive(1), listOf("Tight", "Normal", "Wide")),
        DeviceSetting("screenMargin", "Margin", "Reader", SettingType.VALUE, JsonPrimitive(10), min = 5, max = 40, step = 5),
        DeviceSetting("hyphenation", "Hyphenation", "Reader", SettingType.TOGGLE, JsonPrimitive(1)),
        DeviceSetting("deviceName", "Device Name", "System", SettingType.STRING, JsonPrimitive("X4")),
        DeviceSetting("koPassword", "Password", "Sync", SettingType.ENUM, JsonPrimitive(0), listOf("a")),
        DeviceSetting("future", "Future", "", SettingType.UNKNOWN, JsonPrimitive(0)),
    )

    @Test
    fun captureKeepsChoicesAndNumbersOnly() {
        val values = SettingsProfile.capture(schema)
        assertEquals("no free text, secrets or unknown types", setOf("fontSize", "lineSpacing", "screenMargin", "hyphenation"), values.keys)
        assertEquals(JsonPrimitive(10), values["screenMargin"])
    }

    @Test
    fun planSendsOnlyDifferingKnownKeys() {
        val profile = SettingsProfile(name = "Ночь", values = mapOf(
            "fontSize" to JsonPrimitive(3),        // отличается → уходит
            "lineSpacing" to JsonPrimitive(1),     // то же → нет
            "hyphenation" to JsonPrimitive(true),  // то же значение другим типом → нет
            "screenMargin" to JsonPrimitive(20.0), // отличается → уходит как сохранено
            "frontLight" to JsonPrimitive(1),      // нет у читалки → пропуск
            "deviceName" to JsonPrimitive("Y"),    // свободный текст не применяется
            "koPassword" to JsonPrimitive(0),      // секреты не применяются
        ))
        val plan = profile.plan(schema)
        assertEquals(mapOf("fontSize" to JsonPrimitive(3), "screenMargin" to JsonPrimitive(20.0)), plan.changes)
        assertEquals(listOf("deviceName", "frontLight", "koPassword"), plan.unknown)
        assertTrue(plan.invalid.isEmpty())
        assertEquals(2, plan.unchanged)
        assertEquals(3, plan.skipped)
    }

    @Test
    fun valuesThatNoLongerFitAreSkipped() {
        val plan = SettingsProfile(name = "p", values = mapOf(
            "fontSize" to JsonPrimitive(7),       // варианта больше нет
            "screenMargin" to JsonPrimitive(80),  // выше max
            "hyphenation" to JsonPrimitive(2),    // не значение переключателя
            "lineSpacing" to JsonPrimitive("x"),  // не число
        )).plan(schema)
        assertTrue(plan.changes.isEmpty())
        assertEquals(listOf("fontSize", "hyphenation", "lineSpacing", "screenMargin"), plan.invalid)
        assertEquals(4, plan.skipped)
    }

    @Test
    fun identicalProfileHasNothingToSend() {
        val plan = SettingsProfile(name = "same", values = SettingsProfile.capture(schema)).plan(schema)
        assertTrue(plan.changes.isEmpty())
        assertEquals(4, plan.unchanged)
        assertEquals(0, plan.skipped)
    }

    @Test
    fun secretKeys() {
        assertTrue(isSecretSetting("koPassword"))
        assertTrue(isSecretSetting("apiToken"))
        assertTrue(isSecretSetting("SharedSecret"))
        assertTrue(isSecretSetting("wifiPass"))
        assertFalse(isSecretSetting("passage"))
        assertFalse(isSecretSetting("fontSize"))
    }

    @Test
    fun profileStoreAddRenameDeleteAndPersist() {
        val root = File(tmp.root, "profiles")
        val store = SettingsProfileStore(SharedStore(root))
        assertTrue(store.load().isEmpty())
        val a = SettingsProfile(name = "День", values = mapOf("fontSize" to JsonPrimitive(1)), deviceKey = "SERIAL")
        val b = SettingsProfile(name = "Ночь", values = mapOf("fontSize" to JsonPrimitive(3)))
        store.add(a)
        store.add(b)
        store.rename(a.id, "  Утро ")
        store.rename(b.id, "   ")
        val reopened = SettingsProfileStore(SharedStore(root)).load()
        assertEquals("blank names are ignored", listOf("Утро", "Ночь"), reopened.map { it.name })
        assertEquals(mapOf("fontSize" to JsonPrimitive(1)), reopened[0].values)
        assertEquals("SERIAL", reopened[0].deviceKey)
        store.delete(a.id)
        assertEquals(listOf(b.id), store.load().map { it.id })
    }

    // endregion

    // region Токены сопряжения

    @Test
    fun tokenFileStoreRoundTrip() {
        val file = File(tmp.root, "Pairing/tokens.json")
        val store = FilePairingTokenStore(file)
        assertNull(store.token("SN1"))
        store.save("tok1", "SN1")
        store.save("tok2", "10.0.0.2:80")
        assertEquals("tok1", FilePairingTokenStore(file).token("SN1"))
        store.remove("SN1")
        assertNull(store.token("SN1"))
        assertEquals("tok2", store.token("10.0.0.2:80"))
        store.remove("never-saved")
        assertEquals("tok2", store.token("10.0.0.2:80"))
    }

    @Test
    fun encryptedTokenFileIsNotPlainTextAndNeedsTheKey() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val file = File(tmp.root, "pairing/tokens.bin")
        val token = "ab".repeat(32)
        FilePairingTokenStore(file, AesGcmCipher { key }).save(token, "SN1")
        val raw = file.readBytes()
        assertFalse("token is not stored in clear", String(raw, Charsets.ISO_8859_1).contains(token))
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("SN1"))
        assertEquals(token, FilePairingTokenStore(file, AesGcmCipher { key }).token("SN1"))

        // Другой ключ (восстановление на другом телефоне) — пустое хранилище, без падения.
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(FilePairingTokenStore(file, AesGcmCipher { other }).token("SN1"))
        // Испорченный файл — тоже.
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(FilePairingTokenStore(file, AesGcmCipher { key }).token("SN1"))

        // Каждое шифрование — новый IV.
        val c = AesGcmCipher { key }
        assertNotEquals(c.encrypt(byteArrayOf(1)).toList(), c.encrypt(byteArrayOf(1)).toList())
    }

    // endregion
}
