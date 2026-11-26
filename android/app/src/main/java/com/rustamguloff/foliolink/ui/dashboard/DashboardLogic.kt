package com.rustamguloff.foliolink.ui.dashboard

import com.rustamguloff.foliolink.app.AppModel

/** Чистая логика главной (без Compose) — покрыта JVM-тестами. */
object DashboardLogic {

    /** Ступень значка батареи (как SF Symbols battery.N в iOS). */
    enum class Battery { CHARGING, EMPTY, QUARTER, HALF, THREE_QUARTERS, FULL }

    fun battery(percent: Int, charging: Boolean): Battery = when {
        charging -> Battery.CHARGING
        percent < 13 -> Battery.EMPTY
        percent < 38 -> Battery.QUARTER
        percent < 63 -> Battery.HALF
        percent < 88 -> Battery.THREE_QUARTERS
        else -> Battery.FULL
    }

    /** Строка состояния карточки читалки. */
    enum class Status { CONNECTED, DEMO, CONNECTING, FAILED, NOT_CONNECTED, OFFLINE }

    fun status(connection: AppModel.Connection, isDemo: Boolean, hasKnownDevice: Boolean): Status = when (connection) {
        AppModel.Connection.Connected -> if (isDemo) Status.DEMO else Status.CONNECTED
        AppModel.Connection.Connecting -> Status.CONNECTING
        is AppModel.Connection.Failed -> Status.FAILED
        AppModel.Connection.Disconnected -> if (hasKnownDevice) Status.OFFLINE else Status.NOT_CONNECTED
    }

    /** Первый запуск: читалки ещё не было и связи нет — вместо карточек приветствие. */
    fun showWelcome(hasKnownDevice: Boolean, connected: Boolean): Boolean = !hasKnownDevice && !connected

    /** Сколько элементов очереди показать на карточке и сколько осталось за «И ещё N». */
    fun outboxPreview(count: Int, limit: Int = 3): Pair<Int, Int> = minOf(count, limit) to maxOf(count - limit, 0)

    /** Доля занятого места на карте памяти (0…1); null — размер неизвестен. */
    fun sdUsed(totalMb: Int, freeMb: Int): Float? =
        if (totalMb <= 0) null else ((totalMb - freeMb).toFloat() / totalMb).coerceIn(0f, 1f)
}
