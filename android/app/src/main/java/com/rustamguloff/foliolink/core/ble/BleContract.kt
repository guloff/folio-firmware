package com.rustamguloff.foliolink.core.ble

import com.rustamguloff.foliolink.core.model.Book
import com.rustamguloff.foliolink.core.model.DeviceInfo
import com.rustamguloff.foliolink.core.model.DeviceStats
import com.rustamguloff.foliolink.core.model.Highlight
import com.rustamguloff.foliolink.core.model.ReadingSession
import com.rustamguloff.foliolink.core.model.RemoteFile
import com.rustamguloff.foliolink.core.model.VocabWord
import java.util.TimeZone

// Контракт между синхронизацией по Bluetooth (core/ble) и центром состояния (app/AppModel).

/** Что и с какого места забирать по BLE. */
data class BleSyncPlan(
    /** Токен сопряжения (64 hex), полученный по Wi-Fi. По радио не уходит — только HMAC. */
    val token: String,
    /** `sessions{since}`; 0 — всё. */
    val sessionsSince: Long = 0,
    /** `highlights{since}`; 0 — всё (список тогда заменяет кэш). */
    val highlightsSince: Long = 0,
    val nowMs: Long = System.currentTimeMillis(),
    val timeZone: TimeZone = TimeZone.getDefault(),
    /** Nonce телефона для `hello` (SEC-034); в тестах задаётся. */
    val cnonce: String = BleAuth.newClientNonce(),
    /** Эта читалка уже доказывала себя `sproof`: ответ без него теперь — отказ. */
    val requireServerProof: Boolean = false,
    /**
     * Ключ читалки из снимка (серийник или "id:"+deviceId); если info по BLE называет серийник
     * (или deviceId), они должны совпасть.
     */
    val expectedSerial: String? = null,
)

/** Итог одной синхронизации по BLE; null — шаг не выполнялся или не удался. */
data class BleSyncResult(
    val info: DeviceInfo? = null,
    val clockSet: Boolean = false,
    val stats: DeviceStats? = null,
    val sessions: List<ReadingSession>? = null,
    val sessionsSince: Long = 0,
    val highlights: List<Highlight>? = null,
    val highlightsSince: Long = 0,
    val vocab: List<VocabWord>? = null,
    val books: List<Book>? = null,
    val screenshots: List<RemoteFile>? = null,
    /** Шаги, на которые читалка ответила ошибкой ("имя: сообщение"). */
    val failures: List<String> = emptyList(),
    /** Читалка доказала себя (`sproof` сошёлся). */
    val serverVerified: Boolean = false,
)

/** Реализует AppModel: план, применение результата, признак идущей Wi-Fi-синхронизации. */
interface BleSyncHost {
    /** Кидает [BleError.NoToken], если читалку ещё не сопрягли по Wi-Fi. */
    fun blePlan(): BleSyncPlan
    fun applyBleSync(result: BleSyncResult)
    /** Идёт синхронизация по Wi-Fi: BLE ждёт следующего случая. */
    val isSyncing: Boolean
}

/**
 * Собеседник по BLE не доказал, что он сопряжённая читалка (SEC-034): данные не применяются.
 */
class BleServerProofException(val kind: Kind) : Exception("BLE server proof: $kind") {
    enum class Kind {
        /** `sproof` не сошёлся. */
        MISMATCH,
        /** `sproof` нет, а эта читалка его уже присылала. */
        MISSING,
        /** Серийник в info не тот, что у читалки в кэше. */
        WRONG_READER,
    }
}

sealed class BleError(message: String) : Exception(message) {
    data object NoToken : BleError("no pairing token")
    data object Timeout : BleError("no frames for 10 s")
    data object Disconnected : BleError("disconnected")
    data class Busy(val attempts: Int) : BleError("reader busy (ATT 0x80)")
    data class CorruptResponse(val attempts: Int) : BleError("corrupt response after $attempts attempts")
    data class CommandTooLong(val size: Int) : BleError("command is $size bytes (max 512)")
    /** Ответ с флагом ERROR: {"ok":false,"error":…,"status":…}. */
    data class Remote(val status: Int, val error: String, val attemptsLeft: Int? = null) : BleError(error)
    data object PairingRequired : BleError("pairing required")
    data class Gatt(val code: Int) : BleError("GATT error $code")
}

/** Состояние для экрана «Устройство» (раздел «Синхронизация по Bluetooth»). */
data class BleUiState(
    val enabled: Boolean = false,
    val status: Status = Status.Off,
    /** Последняя успешная синхронизация, epoch-мс. */
    val lastSyncMs: Long? = null,
    /** Текущий шаг (id шага: "hello", "auth", "info", "time", "stats", "sessions", …). */
    val step: String? = null,
    /** Предупреждение последней синхронизации (часть шагов не удалась). */
    val lastWarning: String? = null,
    val mtu: Int? = null,
    val peripheralName: String? = null,
    /** Приложение помнит адрес читалки и ждёт её рекламы. */
    val knowsReader: Boolean = false,
    val canSyncNow: Boolean = false,
    /**
     * Исключение, приведшее к [Status.Failed] (обычно [BleError], иначе ошибка ядра):
     * по нему ErrorText даёт текст для человека; `Failed.reason` — только технический текст.
     */
    val failure: Exception? = null,
) {
    sealed interface Status {
        data object Off : Status
        data object Unsupported : Status
        /** Нет разрешений BLUETOOTH_SCAN/CONNECT (Android 12+) или геолокации (до 12). */
        data object Unauthorized : Status
        data object PoweredOff : Status
        data object Searching : Status
        data object Connecting : Status
        data object Syncing : Status
        data object Connected : Status
        data class Failed(val reason: String) : Status
    }
}
