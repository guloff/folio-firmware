package com.rustamguloff.foliolink.ui.device

import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.core.ble.BleUiState
import com.rustamguloff.foliolink.core.net.DeviceError

// Логика экранов подключения, сопряжения и Bluetooth без Compose (проверяется JVM-тестами).

/** Android 17 (API 37): доступ к устройствам локальной сети — опасное разрешение. */
const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
const val LOCAL_NETWORK_MIN_SDK = 37

/** Что показать про доступ к локальной сети. */
enum class LocalNetworkUi {
    /** Разрешение не нужно (Android до 17) или уже дано. */
    READY,
    /** Нужно попросить (с объяснением). */
    ASK,
    /** Отказано без повторного вопроса: только через настройки приложения. */
    OPEN_SETTINGS,
}

fun localNetworkUi(sdk: Int, granted: Boolean, permanentlyDenied: Boolean): LocalNetworkUi = when {
    sdk < LOCAL_NETWORK_MIN_SDK || granted -> LocalNetworkUi.READY
    permanentlyDenied -> LocalNetworkUi.OPEN_SETTINGS
    else -> LocalNetworkUi.ASK
}

/** Ввод PIN: только цифры, не длиннее `digits`; PIN бывает 4–8 цифр (по умолчанию 6). */
object PinRules {
    const val DEFAULT_DIGITS = 6

    fun clampDigits(digits: Int): Int = digits.coerceIn(4, 8)

    fun sanitize(input: String, digits: Int): String = input.filter { it in '0'..'9' }.take(digits)

    fun isComplete(pin: String, digits: Int): Boolean = pin.length == digits

    /** Сколько целых секунд осталось до `untilMs` (с округлением вверх, не меньше 0). */
    fun secondsLeft(untilMs: Long?, nowMs: Long): Int {
        if (untilMs == null) return 0
        val left = untilMs - nowMs
        return if (left <= 0) 0 else ((left + 999) / 1000).toInt()
    }

    /** Что сделать с окном после ошибки. */
    data class FailureEffect(
        /** Срок PIN больше не показывать (сгорел/не запрошен/блокировка). */
        val clearExpiry: Boolean,
        /** 429: «Новый PIN через N с». */
        val retryInSeconds: Int?,
    )

    fun afterFailure(error: Throwable): FailureEffect {
        val f = (error as? DeviceError.Pairing)?.failure
        return when (f) {
            is DeviceError.PairingFailure.RateLimited -> FailureEffect(true, f.retryIn)
            DeviceError.PairingFailure.Expired, DeviceError.PairingFailure.NoActivePin -> FailureEffect(true, null)
            else -> FailureEffect(false, null)
        }
    }
}

/** Тексты раздела «Синхронизация по Bluetooth». */
object BleTexts {
    /** Строка состояния; null — [BleUiState.Status.Failed], там текст ошибки. */
    fun statusRes(status: BleUiState.Status): Int? = when (status) {
        BleUiState.Status.Off -> R.string.device_ble_status_off
        BleUiState.Status.Unsupported -> R.string.device_ble_status_unsupported
        BleUiState.Status.Unauthorized -> R.string.device_ble_status_unauthorized
        BleUiState.Status.PoweredOff -> R.string.device_ble_status_powered_off
        BleUiState.Status.Searching -> R.string.device_ble_status_searching
        BleUiState.Status.Connecting -> R.string.device_ble_status_connecting
        BleUiState.Status.Syncing -> R.string.device_ble_status_syncing
        BleUiState.Status.Connected -> R.string.device_ble_status_connected
        is BleUiState.Status.Failed -> null
    }

    enum class Tone { NEUTRAL, GOOD, WARNING }

    fun tone(status: BleUiState.Status): Tone = when (status) {
        BleUiState.Status.Connected -> Tone.GOOD
        is BleUiState.Status.Failed, BleUiState.Status.Unauthorized, BleUiState.Status.PoweredOff,
        BleUiState.Status.Unsupported -> Tone.WARNING
        else -> Tone.NEUTRAL
    }

    fun isBusy(status: BleUiState.Status): Boolean =
        status == BleUiState.Status.Syncing || status == BleUiState.Status.Connecting

    /** Человеческое название шага синхронизации по id из BleSync.STEPS; null — неизвестный шаг. */
    fun stepRes(id: String?): Int? = when (id) {
        "hello" -> R.string.device_ble_step_hello
        "auth" -> R.string.device_ble_step_auth
        "info" -> R.string.device_ble_step_info
        "time" -> R.string.device_ble_step_time
        "stats" -> R.string.device_ble_step_stats
        "sessions" -> R.string.device_ble_step_sessions
        "highlights" -> R.string.device_ble_step_highlights
        "vocab" -> R.string.device_ble_step_vocab
        "books" -> R.string.device_ble_step_books
        "screenshots" -> R.string.device_ble_step_screenshots
        else -> null
    }

    /** «Читалка · MTU 247» или просто имя. */
    fun readerLine(name: String, mtu: Int?): String = if (mtu != null) "$name · MTU $mtu" else name
}
