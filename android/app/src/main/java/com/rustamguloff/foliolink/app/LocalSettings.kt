package com.rustamguloff.foliolink.app

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/** Простое хранилище ключ-значение: SharedPreferences в приложении, память в тестах. */
interface KeyValueStore {
    fun getInt(key: String): Int?
    fun getBoolean(key: String): Boolean?
    fun getString(key: String): String?
    fun getStringSet(key: String): Set<String>?
    fun put(key: String, value: Any?)
}

class PrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getInt(key: String) = if (prefs.contains(key)) runCatching { prefs.getInt(key, 0) }.getOrNull() else null
    override fun getBoolean(key: String) = if (prefs.contains(key)) runCatching { prefs.getBoolean(key, false) }.getOrNull() else null
    override fun getString(key: String) = runCatching { prefs.getString(key, null) }.getOrNull()
    override fun getStringSet(key: String) = runCatching { prefs.getStringSet(key, null)?.toSet() }.getOrNull()
    override fun put(key: String, value: Any?) {
        val e = prefs.edit()
        @Suppress("UNCHECKED_CAST")
        when (value) {
            null -> e.remove(key)
            is Int -> e.putInt(key, value)
            is Boolean -> e.putBoolean(key, value)
            is String -> e.putString(key, value)
            is Set<*> -> e.putStringSet(key, value as Set<String>)
            else -> e.putString(key, value.toString())
        }
        e.apply()
    }
}

class MemoryKeyValueStore : KeyValueStore {
    private val map = ConcurrentHashMap<String, Any>()
    override fun getInt(key: String) = map[key] as? Int
    override fun getBoolean(key: String) = map[key] as? Boolean
    override fun getString(key: String) = map[key] as? String
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String) = map[key] as? Set<String>
    override fun put(key: String, value: Any?) { if (value == null) map.remove(key) else map[key] = value }
}

/** Снимок настроек телефона (для `collectAsStateWithLifecycle`). */
data class LocalPrefs(
    val dailyGoalMinutes: Int = 30,
    val reminderEnabled: Boolean = false,
    /** Минуты после полуночи. */
    val reminderMinutes: Int = 21 * 60,
    /** "owner/repo" или полный URL GitHub releases API. */
    val firmwareRepo: String = "",
    val manualAddress: String = "",
    /** Сырое имя метода дизеринга (`floydSteinberg`, `atkinson`, `bayer`, `none`) — тип живёт в core/imaging. */
    val ditherMethod: String = "floydSteinberg",
    /** Сохранять новые скриншоты в галерею (Pictures/FolioLink) после каждой синхронизации. */
    val autoSaveScreenshots: Boolean = false,
    /** Пути скриншотов, уже обработанных автосохранением (каждый сохраняется один раз). */
    val savedScreenshots: Set<String> = emptySet(),
    /**
     * Читалки (ключ → версия прошивки), ответившие 404 на выбор заставки; после обновления
     * прошивки приложение пробует снова.
     */
    val sleepSelectUnsupported: Map<String, String> = emptyMap(),
    /** Отладка: свой URL manifest.json каталога словарей ("" — официальный). В релизе не читается. */
    val dictionaryCatalogUrl: String = "",
    /** Читалки (ключи), которые хоть раз доказали себя по BLE (`sproof`): без него — отказ (SEC-034). */
    val bleMutualAuth: Set<String> = emptySet(),
) {
    val reminderHour: Int get() = reminderMinutes / 60
    val reminderMinute: Int get() = reminderMinutes % 60
}

/**
 * Настройки только этого телефона. Каждое свойство пишется сразу; [state] — для экранов.
 * В отладочной сборке адрес по умолчанию — мок на компьютере (`10.0.2.2:8080` из эмулятора).
 */
class LocalSettings(private val store: KeyValueStore, defaultManualAddress: String = "") {
    private val _state = MutableStateFlow(
        LocalPrefs(
            dailyGoalMinutes = store.getInt(K_GOAL) ?: 30,
            reminderEnabled = store.getBoolean(K_REMINDER) ?: false,
            reminderMinutes = store.getInt(K_REMINDER_MINUTES) ?: 21 * 60,
            firmwareRepo = store.getString(K_REPO) ?: "",
            manualAddress = store.getString(K_ADDRESS) ?: defaultManualAddress,
            ditherMethod = store.getString(K_DITHER) ?: "floydSteinberg",
            autoSaveScreenshots = store.getBoolean(K_AUTOSAVE) ?: false,
            savedScreenshots = store.getStringSet(K_SAVED) ?: emptySet(),
            sleepSelectUnsupported = store.getString(K_SLEEP_SELECT)?.let {
                runCatching { Json.decodeFromString(MAP, it) }.getOrNull()
            } ?: emptyMap(),
            dictionaryCatalogUrl = store.getString(K_DICT_CATALOG) ?: "",
            bleMutualAuth = store.getStringSet(K_BLE_MUTUAL) ?: emptySet(),
        ),
    )
    val state: StateFlow<LocalPrefs> = _state.asStateFlow()
    val value: LocalPrefs get() = _state.value

    private fun put(key: String, stored: Any?, change: (LocalPrefs) -> LocalPrefs) {
        _state.update(change)
        store.put(key, stored)
    }

    var dailyGoalMinutes: Int
        get() = value.dailyGoalMinutes
        set(v) = put(K_GOAL, v) { it.copy(dailyGoalMinutes = v) }

    var reminderEnabled: Boolean
        get() = value.reminderEnabled
        set(v) = put(K_REMINDER, v) { it.copy(reminderEnabled = v) }

    var reminderMinutes: Int
        get() = value.reminderMinutes
        set(v) = put(K_REMINDER_MINUTES, v) { it.copy(reminderMinutes = v) }

    fun setReminderTime(hour: Int, minute: Int) { reminderMinutes = hour * 60 + minute }

    var firmwareRepo: String
        get() = value.firmwareRepo
        set(v) = put(K_REPO, v) { it.copy(firmwareRepo = v) }

    var manualAddress: String
        get() = value.manualAddress
        set(v) = put(K_ADDRESS, v) { it.copy(manualAddress = v) }

    var ditherMethod: String
        get() = value.ditherMethod
        set(v) = put(K_DITHER, v) { it.copy(ditherMethod = v) }

    var autoSaveScreenshots: Boolean
        get() = value.autoSaveScreenshots
        set(v) = put(K_AUTOSAVE, v) { it.copy(autoSaveScreenshots = v) }

    var savedScreenshots: Set<String>
        get() = value.savedScreenshots
        set(v) = put(K_SAVED, v.toSet()) { it.copy(savedScreenshots = v.toSet()) }

    var sleepSelectUnsupported: Map<String, String>
        get() = value.sleepSelectUnsupported
        set(v) = put(K_SLEEP_SELECT, Json.encodeToString(MAP, v)) { it.copy(sleepSelectUnsupported = v) }

    var dictionaryCatalogUrl: String
        get() = value.dictionaryCatalogUrl
        set(v) = put(K_DICT_CATALOG, v.ifEmpty { null }) { it.copy(dictionaryCatalogUrl = v) }

    var bleMutualAuth: Set<String>
        get() = value.bleMutualAuth
        set(v) = put(K_BLE_MUTUAL, v.toSet()) { it.copy(bleMutualAuth = v.toSet()) }

    companion object {
        private const val K_BLE_MUTUAL = "bleMutualAuth"
        private const val K_DICT_CATALOG = "dictionaryCatalogUrl"
        private const val K_GOAL = "dailyGoalMinutes"
        private const val K_REMINDER = "reminderEnabled"
        private const val K_REMINDER_MINUTES = "reminderMinutes"
        private const val K_REPO = "firmwareRepo"
        private const val K_ADDRESS = "manualAddress"
        private const val K_DITHER = "ditherMethod"
        private const val K_AUTOSAVE = "autoSaveScreenshots"
        private const val K_SAVED = "savedScreenshots"
        private const val K_SLEEP_SELECT = "sleepSelectUnsupported"
        private val MAP = MapSerializer(String.serializer(), String.serializer())
    }
}
