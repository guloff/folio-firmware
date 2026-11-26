package com.rustamguloff.foliolink.core.store

import com.rustamguloff.foliolink.core.model.DeviceSetting
import com.rustamguloff.foliolink.core.model.SettingType
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.UUID

/**
 * Именованный набор настроек читалки (шрифт, интервалы, поля, обновление…), сохранённый на телефоне
 * и применяемый потом одним частичным `POST /api/settings`.
 */
@Serializable
data class SettingsProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Ключ настройки → значение, как его отдаёт `/api/settings`. */
    val values: Map<String, JsonElement>,
    /** epoch-мс. */
    val created: Long = System.currentTimeMillis(),
    /** С какой читалки сняты значения (справочно: профиль применим к любой). */
    val deviceKey: String? = null,
) {
    data class Plan(
        /** Ключи, чьё значение отличается от читалки, со значением профиля. */
        val changes: Map<String, JsonElement>,
        /** Ключи, которых у этой читалки нет (или они больше не годятся для профиля). */
        val unknown: List<String>,
        /** Ключи, чьё сохранённое значение не подходит текущей схеме (нет варианта, вне диапазона). */
        val invalid: List<String>,
        /** Ключи, у которых уже значение профиля. */
        val unchanged: Int,
    ) {
        val skipped: Int get() = unknown.size + invalid.size
    }

    /**
     * Что отправит применение профиля к читалке с настройками `current`: только ключи из её
     * схемы, чьё значение отличается.
     */
    fun plan(current: List<DeviceSetting>): Plan {
        val schema = LinkedHashMap<String, DeviceSetting>()
        current.forEach { schema.putIfAbsent(it.key, it) }
        val changes = sortedMapOf<String, JsonElement>()
        val unknown = mutableListOf<String>()
        val invalid = mutableListOf<String>()
        var unchanged = 0
        for ((key, value) in values.toSortedMap()) {
            val setting = schema[key]
            if (setting == null || setting.type !in PROFILE_TYPES || isSecretSetting(key)) {
                unknown += key
                continue
            }
            if (!fits(value, setting)) {
                invalid += key
                continue
            }
            if (same(value, setting.value)) unchanged++ else changes[key] = value
        }
        return Plan(changes, unknown, invalid, unchanged)
    }

    companion object {
        private val PROFILE_TYPES = setOf(SettingType.TOGGLE, SettingType.ENUM, SettingType.VALUE)

        /**
         * Что стоит хранить в профиле: переключатели, списки и числа. Свободный текст (имя
         * читалки, сервер синхронизации, учётные записи) и секреты не сохраняются никогда.
         */
        fun capture(settings: List<DeviceSetting>): Map<String, JsonElement> =
            settings.filter { it.key.isNotEmpty() && it.type in PROFILE_TYPES && !isSecretSetting(it.key) }
                .associate { it.key to it.value }

        /** Числа сравниваются по значению (`1` == `1.0` == `true` у переключателя). */
        fun same(a: JsonElement, b: JsonElement): Boolean {
            if (a == b) return true
            val x = intValue(a) ?: return false
            return x == intValue(b)
        }

        fun fits(value: JsonElement, setting: DeviceSetting): Boolean {
            val n = intValue(value) ?: return false
            return when (setting.type) {
                SettingType.TOGGLE -> n == 0 || n == 1
                SettingType.ENUM -> setting.options.isEmpty() || n in setting.options.indices
                SettingType.VALUE -> (setting.min?.let { n >= it } ?: true) && (setting.max?.let { n <= it } ?: true)
                SettingType.STRING, SettingType.UNKNOWN -> false
            }
        }

        /** Целое из JSON: число, дробь (отбрасывается), булево, строка с целым — как JSONValue.intValue в iOS. */
        fun intValue(e: JsonElement): Int? {
            val p = e as? JsonPrimitive ?: return null
            if (p.isString) return p.content.trim().toIntOrNull()
            return p.longOrNull?.toInt() ?: p.doubleOrNull?.toInt() ?: p.booleanOrNull?.let { if (it) 1 else 0 }
        }
    }
}

/** Ключи вроде `koPassword` маскируются в интерфейсе и никогда не попадают в профили. */
fun isSecretSetting(key: String): Boolean {
    val k = key.lowercase()
    return k.contains("password") || k.contains("secret") || k.contains("token") || k.endsWith("pass")
}

/**
 * Профили — один JSON-файл в [SharedStore]. Их нет нигде, кроме телефона: нечитаемый файл
 * откладывается в сторону ([unreadable]), а изменения сообщают, дошли ли они до диска.
 */
class SettingsProfileStore(val store: SharedStore) {
    /** Итог изменения: список после него и записан ли он. */
    data class Change(val profiles: List<SettingsProfile>, val saved: Boolean)

    /** Последнее чтение наткнулось на нечитаемый файл (он отложен в сторону, если удалось). */
    @Volatile
    var unreadable: Boolean = false
        private set

    /** Сообщить о нечитаемом файле один раз: true — был, и флаг снят. */
    fun consumeUnreadable(): Boolean = unreadable.also { unreadable = false }

    fun load(): List<SettingsProfile> = readState().first

    /** (профили, запись запрещена): нечитаемый файл, который не удалось отложить, не перезаписывается. */
    private fun readState(): Pair<List<SettingsProfile>, Boolean> =
        when (val r = store.read(SERIALIZER, FILE, quarantine = true)) {
            is SharedStore.Loaded.Value -> r.value to false
            SharedStore.Loaded.Missing -> emptyList<SettingsProfile>() to false
            is SharedStore.Loaded.Unreadable -> {
                unreadable = true
                emptyList<SettingsProfile>() to (r.setAside == null)
            }
        }

    fun save(profiles: List<SettingsProfile>): Boolean = store.save(profiles, SERIALIZER, FILE)

    private fun change(transform: (List<SettingsProfile>) -> List<SettingsProfile>): Change {
        val (before, blocked) = readState()
        if (blocked) return Change(before, saved = false)
        val after = transform(before)
        return if (save(after)) Change(after, true) else Change(before, false)
    }

    fun add(profile: SettingsProfile): Change = change { it + profile }

    /** Пустое (после обрезки пробелов) имя игнорируется. */
    fun rename(id: String, name: String): Change {
        val trimmed = name.trim()
        return change { all -> all.map { if (it.id == id && trimmed.isNotEmpty()) it.copy(name = trimmed) else it } }
    }

    fun delete(id: String): Change = change { all -> all.filter { it.id != id } }

    companion object {
        const val FILE = "settings-profiles.json"
        private val SERIALIZER = ListSerializer(SettingsProfile.serializer())
    }
}
