package com.rustamguloff.foliolink.core.services

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.rustamguloff.foliolink.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Ежедневное напоминание почитать. Одно неточное окно AlarmManager (`setWindow`, без права на
 * точные будильники) на ближайшее время; сработав, приёмник показывает уведомление и ставит
 * следующее. «Не беспокоить, если цель выполнена» — по данным последней синхронизации: тогда
 * сегодняшнее напоминание пропускается. Перепланируется при каждом запуске и синхронизации.
 */
object ReminderScheduler {
    const val CHANNEL_ID = "reading-reminder"
    const val ACTION_REMIND = "com.rustamguloff.foliolink.action.READING_REMINDER"
    private const val NOTIFICATION_ID = 4201
    private const val PREFS = "foliolink.reminder"
    /** Ширина окна срабатывания: система может сдвинуть напоминание в его пределах. */
    private const val WINDOW_MS = 10 * 60 * 1000L

    /** Что сказать в уведомлении. */
    sealed interface Message {
        /** До цели осталось `minutes` (сегодня уже читали). */
        data class Remaining(val minutes: Int) : Message
        /** Сегодня ещё не читали: цель на день. */
        data class Goal(val goalMinutes: Int) : Message
    }

    /** Ближайшее время напоминания, epoch-мс: сегодня (если не прошло и цель не выполнена), иначе завтра. */
    fun nextFireTime(nowMs: Long, minutesOfDay: Int, goalMetToday: Boolean, zone: ZoneId = ZoneId.systemDefault()): Long {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val todayFire = fireAt(today, minutesOfDay, zone)
        return if (!goalMetToday && todayFire > nowMs) todayFire else fireAt(today.plusDays(1), minutesOfDay, zone)
    }

    private fun fireAt(day: LocalDate, minutesOfDay: Int, zone: ZoneId): Long =
        day.atStartOfDay(zone).plusMinutes(minutesOfDay.toLong()).toInstant().toEpochMilli()

    fun message(sameDay: Boolean, todayMinutes: Int, goalMinutes: Int): Message =
        if (sameDay && todayMinutes > 0) Message.Remaining(maxOf(goalMinutes - todayMinutes, 1)) else Message.Goal(goalMinutes)

    /** Можно ли показывать уведомления (на Android 13+ нужно разрешение POST_NOTIFICATIONS — просит экран). */
    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.core_reminder_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /**
     * Сохраняет настройки напоминания и ставит ближайшее. `goalMetToday`/`todayMinutes` — по
     * последней синхронизации.
     */
    fun reschedule(
        context: Context,
        enabled: Boolean,
        minutesOfDay: Int,
        goalMetToday: Boolean,
        todayMinutes: Int,
        goalMinutes: Int,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val day = dayKey(nowMs)
        prefs(context).edit()
            .putBoolean("enabled", enabled)
            .putInt("minutesOfDay", minutesOfDay)
            .putInt("goalMinutes", goalMinutes)
            .putInt("todayMinutes", todayMinutes)
            .putBoolean("goalMet", goalMetToday)
            .putString("day", day)
            .apply()
        schedule(context, nowMs)
    }

    /** Ставит будильник по сохранённым настройкам (после загрузки, смены часового пояса, срабатывания). */
    fun schedule(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context)
        am.cancel(pi)
        val p = prefs(context)
        if (!p.getBoolean("enabled", false)) return
        val goalMetToday = p.getBoolean("goalMet", false) && p.getString("day", null) == dayKey(nowMs)
        val fire = nextFireTime(nowMs, p.getInt("minutesOfDay", 21 * 60), goalMetToday)
        am.setWindow(AlarmManager.RTC_WAKEUP, fire, WINDOW_MS, pi)
    }

    /** Срабатывание: уведомление (если цель сегодня ещё не выполнена) и следующее напоминание. */
    internal fun onAlarm(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val p = prefs(context)
        if (p.getBoolean("enabled", false)) {
            val sameDay = p.getString("day", null) == dayKey(nowMs)
            val goalMet = sameDay && p.getBoolean("goalMet", false)
            if (!goalMet && canNotify(context)) {
                val goal = p.getInt("goalMinutes", 30)
                show(context, message(sameDay, p.getInt("todayMinutes", 0), goal))
            }
        }
        // Следующее — завтра (окно сегодняшнего уже прошло или почти прошло).
        schedule(context, nowMs + WINDOW_MS)
    }

    private fun show(context: Context, message: Message) {
        ensureChannel(context)
        val body = when (message) {
            is Message.Remaining -> context.getString(R.string.core_reminder_left, message.minutes)
            is Message.Goal -> context.getString(R.string.core_reminder_goal, message.goalMinutes)
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val content = launch?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_popup_reminder)
            .setContentTitle(context.getString(R.string.core_reminder_title))
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(content)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            // Разрешение отозвали между проверкой и показом.
        }
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_REMIND),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dayKey(nowMs: Long): String = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate().toString()
}

/**
 * Приёмник напоминаний. В манифесте — с действиями BOOT_COMPLETED, MY_PACKAGE_REPLACED,
 * TIMEZONE_CHANGED, TIME_SET (перепланировать) и [ReminderScheduler.ACTION_REMIND].
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ReminderScheduler.ACTION_REMIND -> ReminderScheduler.onAlarm(context)
            else -> ReminderScheduler.schedule(context)
        }
    }
}
