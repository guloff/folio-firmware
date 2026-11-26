package com.rustamguloff.foliolink.core.net

/**
 * Ошибки обмена с читалкой. Текст для человека — в `app/ErrorText.kt`; здесь только смысл.
 * `message` — техническое описание для логов.
 */
sealed class DeviceError(message: String) : Exception(message) {
    data object InvalidAddress : DeviceError("invalid address")
    data object Unreachable : DeviceError("reader unreachable")
    data object Timeout : DeviceError("timed out")

    /** Ответ не 2xx (или 200 с `"ok":false`). `message` — текст ошибки прошивки (до 200 символов). */
    data class Server(val status: Int, val text: String) : DeviceError("HTTP $status: $text")

    /** Upstream: «File already exists: <имя>». */
    data class AlreadyExists(val name: String) : DeviceError("already exists: $name")

    /** Ответ не разобран; `what` — маршрут. */
    data class Decoding(val what: String) : DeviceError("cannot decode $what")

    /** Маршрута нет на этой прошивке (пустой 404 или страница upstream «404 Not Found»). */
    data object NotSupported : DeviceError("not supported")

    /** 401 «pairing required» (контракт v2 §1): читалке нужен токен этого телефона. */
    data object PairingRequired : DeviceError("pairing required")

    data class Pairing(val failure: PairingFailure) : DeviceError("pairing: $failure")

    data class Other(val text: String) : DeviceError(text)

    /** Ответ (или файл для отправки) больше допустимого: `limit` — потолок в байтах. */
    data class TooLarge(val limit: Long) : DeviceError("larger than $limit bytes")

    /**
     * Временный сбой: очередь останавливается, элемент остаётся (как [Unreachable] и [Timeout]).
     * `cause` — что случилось на самом деле (для текста ошибки).
     */
    class RetryLater(override val cause: Throwable) : DeviceError("retry later: ${cause.message}")

    /** Сбой, после которого очередь ждёт следующего подключения, а элемент не помечается отклонённым. */
    val isTransient: Boolean
        get() = this === Unreachable || this === Timeout || this === PairingRequired || this is RetryLater

    sealed interface PairingFailure {
        /** 403: неверный PIN, осталось `attemptsLeft` попыток. */
        data class BadPin(val attemptsLeft: Int?) : PairingFailure

        /** 410: PIN сгорел (5 неверных попыток) или истёк. */
        data object Expired : PairingFailure

        /** 409: PIN не запрашивали (или он уже использован). */
        data object NoActivePin : PairingFailure

        /** 429 на pair/start: слишком часто или блокировка после сгоревшего PIN. */
        data class RateLimited(val retryIn: Int) : PairingFailure
    }
}
