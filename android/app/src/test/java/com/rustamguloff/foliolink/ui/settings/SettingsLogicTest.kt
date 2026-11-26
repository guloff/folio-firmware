package com.rustamguloff.foliolink.ui.settings

import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.core.model.DeviceSetting
import com.rustamguloff.foliolink.core.model.SettingType
import com.rustamguloff.foliolink.core.store.SettingsProfile
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLogicTest {
    private val schema = listOf(
        DeviceSetting("fontSize", "Font size", "Reader", SettingType.ENUM, JsonPrimitive(1), listOf("S", "M", "L")),
        DeviceSetting("deviceName", "Device name", "System", SettingType.STRING, JsonPrimitive("Folio")),
        DeviceSetting("margin", "Margin", "Reader", SettingType.VALUE, JsonPrimitive(10), min = 0, max = 40, step = 2),
        DeviceSetting("mystery", "?", "Reader", SettingType.UNKNOWN, JsonPrimitive(0)),
        DeviceSetting("bold", "Bold", "", SettingType.TOGGLE, JsonPrimitive(0)),
        DeviceSetting("koPassword", "Password", "Sync", SettingType.STRING, JsonPrimitive("x")),
    )

    @Test
    fun categoriesKeepSchemaOrderAndSkipUnknown() {
        val cats = DeviceSettingsLogic.categories(schema)
        assertEquals(listOf("Reader", "System", "", "Sync"), cats.map { it.first })
        assertEquals(listOf("fontSize", "margin"), cats[0].second.map { it.key })
    }

    @Test
    fun changedComparesNumbersByValueAndStringsVerbatim() {
        val draft = mapOf(
            "fontSize" to JsonPrimitive(1.0),   // то же число
            "margin" to JsonPrimitive(12),       // другое
            "bold" to JsonPrimitive(true),       // 1 ≠ 0
            "deviceName" to JsonPrimitive("Folio"),
        )
        assertEquals(setOf("margin", "bold"), DeviceSettingsLogic.changed(schema, draft).keys)
        // Строка "01" — не то же, что "1", даже если обе — числа.
        val pin = listOf(DeviceSetting("pin", "PIN", "", SettingType.STRING, JsonPrimitive("1")))
        assertEquals(setOf("pin"), DeviceSettingsLogic.changed(pin, mapOf("pin" to JsonPrimitive("01"))).keys)
        // Ключа нет в схеме — не отправляется.
        assertTrue(DeviceSettingsLogic.changed(schema, mapOf("gone" to JsonPrimitive(1))).isEmpty())
    }

    @Test
    fun changedNamesAreSorted() {
        val names = DeviceSettingsLogic.changedNames(schema, mapOf("margin" to JsonPrimitive(1), "bold" to JsonPrimitive(1)))
        assertEquals(listOf("Bold", "Margin"), names)
    }

    @Test
    fun stringValue() {
        assertEquals("Folio", DeviceSettingsLogic.stringValue(JsonPrimitive("Folio")))
        assertEquals("5", DeviceSettingsLogic.stringValue(JsonPrimitive(5)))
        assertEquals("", DeviceSettingsLogic.stringValue(JsonNull))
    }

    @Test
    fun secretKeysAreMasked() {
        assertTrue(DeviceSettingsLogic.isSecret("koPassword"))
        assertTrue(DeviceSettingsLogic.isSecret("apiToken"))
        assertFalse(DeviceSettingsLogic.isSecret("deviceName"))
    }

    @Test
    fun rangeChoosesStepperOrSlider() {
        val margin = DeviceSettingsLogic.range(schema[2])
        assertEquals(DeviceSettingsLogic.Range(0, 40, 2), margin)
        assertTrue(margin.useStepper) // 20 шагов
        val wide = DeviceSettingsLogic.range(DeviceSetting("x", type = SettingType.VALUE, min = 0, max = 255))
        assertFalse(wide.useStepper)
        assertEquals(254, wide.sliderSteps)
        // Без границ: 0…100, шаг 1; max ≤ min → min + 1; шаг 0 → 1.
        assertEquals(DeviceSettingsLogic.Range(0, 100, 1), DeviceSettingsLogic.range(DeviceSetting("y", type = SettingType.VALUE)))
        assertEquals(DeviceSettingsLogic.Range(5, 6, 1),
            DeviceSettingsLogic.range(DeviceSetting("z", type = SettingType.VALUE, min = 5, max = 3, step = 0)))
    }

    @Test
    fun sliderSnapsToStepAndClamps() {
        val r = DeviceSettingsLogic.Range(0, 100, 5)
        assertEquals(10, r.snap(11.9f))
        assertEquals(15, r.snap(13.0f))
        assertEquals(100, r.snap(140f))
        assertEquals(0, r.clamp(-3))
    }

    @Test
    fun profileSummary() {
        val profile = SettingsProfile(name = "Night", values = mapOf(
            "fontSize" to JsonPrimitive(2), "bold" to JsonPrimitive(0), "gone" to JsonPrimitive(1),
        ))
        val parts = DeviceSettingsLogic.profileSummary(profile.plan(schema), profile.values.size, hasSettings = true)
        assertEquals(
            listOf(
                DeviceSettingsLogic.Part(R.string.settings_profile_will_change, 1),
                DeviceSettingsLogic.Part(R.string.settings_profile_missing, 1),
            ),
            parts,
        )
        val same = SettingsProfile(name = "Same", values = mapOf("bold" to JsonPrimitive(0)))
        assertEquals(listOf(DeviceSettingsLogic.Part(R.string.settings_profile_matches, null)),
            DeviceSettingsLogic.profileSummary(same.plan(schema), 1, hasSettings = true))
        // Настроек читалки нет (офлайн без кэша) — только число настроек в профиле.
        assertEquals(listOf(DeviceSettingsLogic.Part(R.string.settings_profile_count, 3)),
            DeviceSettingsLogic.profileSummary(profile.plan(emptyList()), 3, hasSettings = false))
    }

    @Test
    fun goalStepsByFiveWithinBounds() {
        assertEquals(35, AppSettingsLogic.stepGoal(30, up = true))
        assertEquals(25, AppSettingsLogic.stepGoal(30, up = false))
        assertEquals(5, AppSettingsLogic.stepGoal(5, up = false))
        assertEquals(240, AppSettingsLogic.stepGoal(240, up = true))
    }
}
