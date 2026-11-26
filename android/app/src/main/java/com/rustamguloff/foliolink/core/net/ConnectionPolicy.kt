package com.rustamguloff.foliolink.core.net

import com.rustamguloff.foliolink.core.model.DeviceEndpoint
import com.rustamguloff.foliolink.core.model.DeviceInfo
import com.rustamguloff.foliolink.core.model.DeviceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Решение о подключении: под каким ключом живёт читалка, какой токен ей можно показать и нужно ли
 * сбрасывать кэш. Вынесено из AppModel, чтобы правила проверялись тестами без него.
 *
 * Правило одно: личность читалки подтверждает только серийный номер из `/api/status`. Адрес
 * (ip:port) её не подтверждает — в чужой сети тот же адрес может занимать другой хост.
 */
object ConnectionPolicy {
    /** Сколько раз повторить `/api/status` после временного сбоя. */
    const val STATUS_RETRIES = 2

    /** Что ответил `/api/status`. */
    sealed interface Probe {
        val status: DeviceStatus?

        /** Серийный номер подтверждён. */
        data class Confirmed(override val status: DeviceStatus, val serial: String) : Probe

        /**
         * Хост ответил, но серийника не назвал (прошивка апстрима, плата без номера) или маршрута
         * нет вовсе: подключаться можно, показывать токен — нет.
         */
        data class NoSerial(override val status: DeviceStatus?) : Probe
    }

    data class Decision(
        /** Ключ читалки для кэша, очереди и токена. */
        val key: String,
        /** Личность читалки подтверждена этим подключением (серийник или deviceId). */
        val confirmed: Boolean,
        /** Токен, который можно отправлять этому хосту (null — запросы идут без токена). */
        val token: String?,
        /** Подключена другая читалка: её история не должна смешаться с кэшем. */
        val resetCache: Boolean,
        /**
         * Можно ли отправлять этому хосту изменения очереди, привязанные к [key]. False — хост не
         * подтвердил, что он та самая читалка: привязанные изменения ждут (книги «для любой» идут).
         */
        val ownsQueue: Boolean,
        /**
         * Прежний адресный ключ (host:port) той же читалки, подтверждённой теперь по deviceId:
         * токен, ключ снимка и элементы очереди переносятся на [key] без сброса кэша.
         */
        val migrateFrom: String? = null,
    )

    /** Префикс ключа читалки без серийника, опознанной по `deviceId` из info. */
    const val DEVICE_ID_PREFIX = "id:"

    /** `deviceId` из info: ровно 32 hex-символа (строчными); иное — нет идентификатора. */
    fun normalizedDeviceId(raw: String?): String? {
        val id = raw?.trim()?.lowercase() ?: return null
        return id.takeIf { it.length == 32 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    /**
     * Подтверждённый ключ: серийник из /api/status, если он известен, иначе "id:" + deviceId из
     * /api/inklink/info, запрошенного без токена. null — читалку не опознать (апстрим, 1.1.1 без серийника).
     */
    fun confirmedKey(probe: Probe, deviceId: String?): String? =
        (probe as? Probe.Confirmed)?.serial ?: normalizedDeviceId(deviceId)?.let { DEVICE_ID_PREFIX + it }

    /**
     * `/api/status` с повторами. Временный сбой (нет связи, таймаут, 5xx) повторяется до
     * [STATUS_RETRIES] раз и, если не прошёл, бросается: подключаться «под другим ключом» нельзя.
     * Ответ, из которого серийник не извлечь (404, не JSON), — [Probe.NoSerial].
     */
    suspend fun probe(
        retries: Int = STATUS_RETRIES,
        retryDelayMs: Long = 400,
        status: suspend () -> DeviceStatus,
    ): Probe {
        var attempt = 0
        while (true) {
            try {
                val s = status()
                val serial = s.knownSerial
                return if (serial != null) Probe.Confirmed(s, serial) else Probe.NoSerial(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DeviceError) {
                if (!isRetriable(e)) return Probe.NoSerial(null)
                if (attempt >= retries) throw e
            }
            attempt++
            delay(retryDelayMs)
        }
    }

    /** Сбой, после которого тот же запрос может пройти. */
    fun isRetriable(e: DeviceError): Boolean = when (e) {
        DeviceError.Unreachable, DeviceError.Timeout -> true
        is DeviceError.Server -> e.status >= 500
        is DeviceError.Other, is DeviceError.RetryLater -> true
        else -> false
    }

    /**
     * @param deviceId `deviceId` из info без токена (нужен, только если серийника нет).
     * @param knownDevice адрес читалки из кэша, [knownKey] — её ключ (null — кэша нет).
     * @param tokenFor токен, сохранённый под ключом.
     */
    fun decide(
        probe: Probe,
        deviceId: String?,
        endpoint: DeviceEndpoint,
        knownDevice: DeviceEndpoint?,
        knownKey: String?,
        tokenFor: (String) -> String?,
    ): Decision {
        val confirmed = confirmedKey(probe, deviceId)
        if (confirmed != null) {
            // Читалка без серийника, запомненная по адресу, теперь опознана по deviceId — та же самая.
            val migrate = knownKey?.takeIf {
                confirmed.startsWith(DEVICE_ID_PREFIX) && it == endpoint.id && knownDevice?.id == endpoint.id
            }
            return Decision(
                key = confirmed,
                confirmed = true,
                // Только токен этой читалки: чужой читалке и неопознанному хосту он не уходит.
                token = tokenFor(confirmed) ?: migrate?.let(tokenFor),
                resetCache = knownKey != null && knownKey != confirmed && migrate == null,
                ownsQueue = true,
                migrateFrom = migrate,
            )
        }
        // Без подтверждённого другого идентификатора ключ и кэш не меняются.
        val key = knownKey ?: endpoint.id
        return Decision(
            key = key,
            confirmed = false,
            token = null,
            resetCache = false,
            // Ключ «host:port» этого же адреса — читалка без идентификатора, которую так и запомнили.
            ownsQueue = key == endpoint.id && (knownDevice == null || knownDevice.id == endpoint.id),
        )
    }

    /** Ключ подтверждает info: серийник для обычного ключа, deviceId для "id:…"; неизвестное — не спорит. */
    fun sameReader(expectedKey: String?, serial: String?, deviceId: String?): Boolean {
        val key = expectedKey?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        if (key.startsWith(DEVICE_ID_PREFIX)) {
            val id = normalizedDeviceId(deviceId) ?: return true
            return key == DEVICE_ID_PREFIX + id
        }
        val sn = serial?.trim()?.takeIf { it.isNotEmpty() && it != "Not found" } ?: return true
        return key == sn
    }

    /**
     * Сохранённый токен удаляется только когда подтверждённая читалка сама сказала, что он
     * недействителен: сопряжение обязательно, а присланный токен не принят.
     */
    fun shouldForgetToken(decision: Decision, info: DeviceInfo): Boolean =
        decision.confirmed && decision.token != null &&
            info.pairing?.required == true && !info.pairing.authorized
}
