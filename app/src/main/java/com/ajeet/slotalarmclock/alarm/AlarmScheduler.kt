package com.ajeet.slotalarmclock.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.ajeet.slotalarmclock.MainActivity
import com.ajeet.slotalarmclock.data.AlarmStore
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Arms and disarms the platform alarm.
 *
 * This deliberately uses [AlarmManager.setAlarmClock], which has two properties that matter
 * here: it is always exact (so the app never needs SCHEDULE_EXACT_ALARM, which users would
 * have to grant by hand), and it surfaces the alarm in the system status bar.
 */
object AlarmScheduler {

    private const val REQUEST_CODE = 0x5107

    /** Arms the alarm for the next occurrence of the stored time, or clears it when disabled. */
    fun apply(context: Context) {
        val state = AlarmStore.get(context).state.value
        if (state.enabled) {
            schedule(context, state.hour, state.minute)
        } else {
            cancel(context)
        }
    }

    fun schedule(context: Context, hour: Int, minute: Int) {
        val triggerAt = nextTriggerMillis(hour, minute, System.currentTimeMillis())
        val manager = context.getSystemService(AlarmManager::class.java) ?: return

        manager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerAt, showIntent(context)),
            fireIntent(context),
        )
    }

    fun scheduleAt(context: Context, triggerAtMillis: Long) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        manager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent(context)),
            fireIntent(context),
        )
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        manager.cancel(fireIntent(context))
    }

    /**
     * Next wall-clock instant matching [hour]:[minute]. Strictly in the future, so an alarm
     * set for "now" rolls to tomorrow rather than firing immediately.
     *
     * Note this is local-time arithmetic: it follows DST transitions correctly because the
     * offset is resolved by [ZonedDateTime] on the target day, not carried over from today.
     */
    fun nextTriggerMillis(hour: Int, minute: Int, nowMillis: Long): Long {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        var target = now
            .withHour(hour.coerceIn(0, 23))
            .withMinute(minute.coerceIn(0, 59))
            .withSecond(0)
            .withNano(0)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return target.toInstant().toEpochMilli()
    }

    private fun fireIntent(context: Context): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_FIRE)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Tapping the alarm icon in the status bar opens the clock. */
    private fun showIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
