package com.rustamguloff.foliolink.ui.settings

import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.core.model.DeviceSetting
import com.rustamguloff.foliolink.core.model.SettingType
import com.rustamguloff.foliolink.core.store.SettingsProfile
import com.rustamguloff.foliolink.core.store.isSecretSetting
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

// Логика экранов настроек без Compose (проверяется JVM-тестами).

object DeviceSettingsLogic {
    /** Категории в порядке схемы; неизвестные типы не показываются. "" — «Прочее». */
    fun categories(settings: List<DeviceSetting>): List<Pair<String, List<DeviceSetting>>> {
        val map = LinkedHashMap<String, MutableList<DeviceSetting>>()
        for (s in settings) {
            if (s.type == SettingType.UNKNOWN) continue
            map.getOrPut(s.category) { mutableListOf() } += s
        }
        return map.map { (k, v) -> k to v.toList() }
    }

    /**
     * Правки, отличающиеся от значения читалки. Строки сравниваются дословно ("01" ≠ "1"),
     * остальное — по числу (`1` == `1.0` == `true`).
     */
    fun changed(settings: List<DeviceSetting>, draft: Map<String, JsonElement>): Map<String, JsonElement> =
        draft.filter { (key, value) ->
            val s = settings.firstOrNull { it.key == key } ?: return@filter false
            if (s.type == SettingType.STRING) stringValue(value) != stringValue(s.value)
            else !SettingsProfile.same(value, s.value)
        }

    /** Имена изменённых настроек по алфавиту — подробность для экрана очереди. */
    fun changedNames(settings: List<DeviceSetting>, changes: Map<String, JsonElement>): List<String> =
        changes.keys.mapNotNull { k -> settings.firstOrNull { it.key == k }?.name }.sorted()

    fun intValue(e: JsonElement): Int? = SettingsProfile.intValue(e)

    fun stringValue(e: JsonElement): String = if (e is JsonNull) "" else (e as? JsonPrimitive)?.content ?: ""

    fun isSecret(key: String): Boolean = isSecretSetting(key)

    /** Границы числовой настройки, как в iOS: min ?: 0, max ≥ min + 1, шаг ≥ 1. */
    data class Range(val lo: Int, val hi: Int, val step: Int) {
        /** До 20 шагов — кнопки «−/+», больше — ползунок. */
        val useStepper: Boolean get() = (hi - lo) / step <= 20
        val sliderSteps: Int get() = ((hi - lo) / step - 1).coerceAtLeast(0)

        fun clamp(v: Int): Int = v.coerceIn(lo, hi)

        /** Значение ползунка → ближайшее допустимое. */
        fun snap(v: Float): Int = clamp(lo + Math.round((v - lo) / step) * step)
    }

    fun range(s: DeviceSetting): Range {
        val lo = s.min ?: 0
        val hi = maxOf(s.max ?: 100, lo + 1)
        val step = maxOf(s.step ?: 1, 1)
        return Range(lo, hi, step)
    }

    /** Подписи профиля: (строка, число) по порядку; null-число — строка без параметра. */
    data class Part(val res: Int, val arg: Int?)

    fun profileSummary(plan: SettingsProfile.Plan, total: Int, hasSettings: Boolean): List<Part> {
        if (!hasSettings) return listOf(Part(R.string.settings_profile_count, total))
        val parts = mutableListOf(
            if (plan.changes.isEmpty()) Part(R.string.settings_profile_matches, null)
            else Part(R.string.settings_profile_will_change, plan.changes.size),
        )
        if (plan.skipped > 0) parts += Part(R.string.settings_profile_missing, plan.skipped)
        return parts
    }
}

object AppSettingsLogic {
    const val GOAL_MIN = 5
    const val GOAL_MAX = 240
    const val GOAL_STEP = 5

    fun stepGoal(current: Int, up: Boolean): Int =
        (current + if (up) GOAL_STEP else -GOAL_STEP).coerceIn(GOAL_MIN, GOAL_MAX)

    /** Официальные релизы прошивки, если в настройках пусто. */
    const val DEFAULT_FIRMWARE_REPO = "guloff/folio-firmware"
}
