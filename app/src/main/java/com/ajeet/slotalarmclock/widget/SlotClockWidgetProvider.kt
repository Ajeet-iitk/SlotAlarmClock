package com.ajeet.slotalarmclock.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import com.ajeet.slotalarmclock.MainActivity
import com.ajeet.slotalarmclock.R
import com.ajeet.slotalarmclock.alarm.AlarmScheduler
import com.ajeet.slotalarmclock.data.AlarmStore
import java.time.LocalTime

/**
 * The home-screen widget.
 *
 * Two independent tap targets share one visual panel: the plate opens the app, the switch flips
 * the alarm without opening anything. A one-minute alarm re-arms itself on every tick, which is
 * the only way to keep a RemoteViews clock honest — `updatePeriodMillis` bottoms out at 30
 * minutes, which is useless for a minute hand.
 */
class SlotClockWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { updateWidget(context, appWidgetManager, it) }
        scheduleTick(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        // Fires on every resize, which is exactly when the bitmap needs re-rendering.
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE -> {
                AlarmStore.get(context).toggle()
                AlarmScheduler.apply(context)
                refreshAll(context)
                scheduleTick(context)
            }

            ACTION_TICK -> {
                refreshAll(context)
                scheduleTick(context)
            }

            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> {
                refreshAll(context)
                scheduleTick(context)
            }
        }
    }

    override fun onDisabled(context: Context) {
        // Last widget removed — stop waking up for a minute hand nobody can see.
        cancelTick(context)
    }

    // ------------------------------------------------------------------------------- rendering

    private fun refreshAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(
            ComponentName(context, SlotClockWidgetProvider::class.java),
        )
        ids.forEach { updateWidget(context, manager, it) }
    }

    private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val options = manager.getAppWidgetOptions(widgetId)
        val density = context.resources.displayMetrics.density

        // Render at the midpoint of the possible range so the bitmap is neither stretched on a
        // small widget nor wasted on a large one.
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, DEFAULT_WIDTH_DP)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, DEFAULT_HEIGHT_DP)
        val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
        val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        val widthDp = if (maxWidth > 0) (minWidth + maxWidth) / 2 else minWidth
        val heightDp = if (maxHeight > 0) (minHeight + maxHeight) / 2 else minHeight

        val alarm = AlarmStore.get(context).state.value
        val now = LocalTime.now()

        val plateWidth = (widthDp * density).toInt()
        val plateHeight = (heightDp * density).toInt()
        val switchWidth = (SWITCH_WIDTH_DP * density).toInt()
        val switchHeight = (SWITCH_HEIGHT_DP * density).toInt()

        val views = RemoteViews(context.packageName, R.layout.widget_slot_clock)

        // A render failure must never leave the user staring at an empty frame: the full
        // material degrades to a plain face that still reports the time.
        val plate = runCatching {
            WidgetRenderer.renderPlate(context, plateWidth, plateHeight, now.hour, now.minute, alarm)
        }.getOrElse { error ->
            Log.e(TAG, "Plate render failed; falling back to the plain face", error)
            WidgetRenderer.renderPlain(context, plateWidth, plateHeight, now.hour, now.minute)
        }
        views.setImageViewBitmap(R.id.widget_plate, plate)

        runCatching {
            WidgetRenderer.renderSwitch(context, switchWidth, switchHeight, alarm.enabled)
        }.onSuccess { switch ->
            views.setImageViewBitmap(R.id.widget_switch, switch)
        }.onFailure { error ->
            Log.e(TAG, "Switch render failed", error)
        }

        views.setOnClickPendingIntent(R.id.widget_plate, openApp(context))
        views.setOnClickPendingIntent(R.id.widget_switch, toggleAlarm(context))

        // Binder can still refuse an oversized payload. Logging and moving on beats crash-looping
        // the provider, which would leave every widget blank on every future update too.
        runCatching { manager.updateAppWidget(widgetId, views) }
            .onFailure { error -> Log.e(TAG, "updateAppWidget failed for widget $widgetId", error) }
    }

    // ----------------------------------------------------------------------------- scheduling

    private fun scheduleTick(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        var at = now + (MINUTE_MILLIS - now % MINUTE_MILLIS) + 1_200L

        // Defensive: if the device clock is somehow ahead of the animation, never schedule in
        // the past, which would make AlarmManager fire immediately in a tight loop.
        if (at <= now) at = now + MINUTE_MILLIS

        val pending = tickIntent(context)
        val canBeExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        try {
            if (canBeExact) {
                // RTC rather than RTC_WAKEUP: a widget is only visible when the device is awake,
                // so waking the screen every minute would be pure battery waste.
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC, at, pending)
            } else {
                manager.set(AlarmManager.RTC, at, pending)
            }
        } catch (_: SecurityException) {
            manager.set(AlarmManager.RTC, at, pending)
        }
    }

    private fun cancelTick(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(tickIntent(context))
    }

    // -------------------------------------------------------------------------- pending intents

    private fun tickIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        TICK_REQUEST,
        Intent(context, SlotClockWidgetProvider::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun toggleAlarm(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        TOGGLE_REQUEST,
        Intent(context, SlotClockWidgetProvider::class.java).setAction(ACTION_TOGGLE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        TOGGLE_REQUEST,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_TOGGLE = "com.ajeet.slotalarmclock.action.TOGGLE_ALARM"
        const val ACTION_TICK = "com.ajeet.slotalarmclock.action.REFRESH_WIDGET"

        private const val TAG = "SlotClockWidget"
        private const val TOGGLE_REQUEST = 0x5A11
        private const val TICK_REQUEST = 0x5A12
        private const val MINUTE_MILLIS = 60_000L
        private const val DEFAULT_WIDTH_DP = 250
        private const val DEFAULT_HEIGHT_DP = 110
        private const val SWITCH_WIDTH_DP = 128
        private const val SWITCH_HEIGHT_DP = 44

        /**
         * Redraws every placed widget.
         *
         * Alarm state is global, so any change to it invalidates every widget instance. Called
         * from [AlarmStore] so no caller can forget.
         */
        fun requestRefresh(context: Context) {
            context.sendBroadcast(
                Intent(context, SlotClockWidgetProvider::class.java).setAction(ACTION_TICK),
            )
        }
    }
}
