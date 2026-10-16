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
import com.ajeet.slotalarmclock.CrashLog
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

    /**
     * Renders and pushes one widget.
     *
     * Structured in two layers on purpose. [buildViews] is the real material, and it shares the
     * palette, the reel renderer and the alarm state with the app — which means it inherits
     * every fault the app can have. [plainViews] shares none of that: if the renderer cannot
     * even be loaded, the widget still shows the time and the reason. Between them the widget
     * has no way to end up as an empty frame.
     */
    private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val views: RemoteViews? = try {
            buildViews(context, manager, widgetId)
        } catch (error: Throwable) {
            Log.e(TAG, "Widget build failed; using the plain face", error)
            try {
                plainViews(context, manager, widgetId, CrashLog.summary(error))
            } catch (fallback: Throwable) {
                Log.e(TAG, "Plain face failed too", fallback)
                null
            }
        }

        if (views == null) return

        // Binder can still refuse an oversized payload. Logging and moving on beats crash-looping
        // the provider, which would leave every widget blank on every future update too.
        runCatching { manager.updateAppWidget(widgetId, views) }
            .onFailure { error -> Log.e(TAG, "updateAppWidget failed for widget $widgetId", error) }
    }

    /** The full metal face. Depends on the shared renderer, and so can fail with it. */
    private fun buildViews(context: Context, manager: AppWidgetManager, widgetId: Int): RemoteViews {
        val density = context.resources.displayMetrics.density
        val (plateWidth, plateHeight) = widgetSizePx(context, manager, widgetId)
        val now = LocalTime.now()
        val alarm = AlarmStore.get(context).state.value

        val views = RemoteViews(context.packageName, R.layout.widget_slot_clock)
        views.setImageViewBitmap(
            R.id.widget_plate,
            WidgetRenderer.renderPlate(context, plateWidth, plateHeight, now.hour, now.minute, alarm),
        )
        runCatching {
            WidgetRenderer.renderSwitch(
                context,
                (SWITCH_WIDTH_DP * density).toInt(),
                (SWITCH_HEIGHT_DP * density).toInt(),
                alarm.enabled,
            )
        }.onSuccess { switch ->
            views.setImageViewBitmap(R.id.widget_switch, switch)
        }.onFailure { error ->
            Log.e(TAG, "Switch render failed", error)
        }
        views.setOnClickPendingIntent(R.id.widget_plate, openApp(context))
        views.setOnClickPendingIntent(R.id.widget_switch, toggleAlarm(context))
        return views
    }

    /**
     * The last resort: a time, a reason, and working taps.
     *
     * It uses no shared material, no shared typeface and no shared state, because any of those
     * is a candidate for being the thing that just failed.
     */
    private fun plainViews(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        fault: String?,
    ): RemoteViews {
        val size = runCatching { widgetSizePx(context, manager, widgetId) }
            .getOrElse { defaultSizePx(context) }
        val now = LocalTime.now()

        val views = RemoteViews(context.packageName, R.layout.widget_slot_clock)
        views.setImageViewBitmap(
            R.id.widget_plate,
            WidgetRenderer.renderPlain(context, size.first, size.second, now.hour, now.minute, fault),
        )
        if (fault != null) {
            // Also park it in the app's diagnostic store, so opening the app explains the fault
            // even though the widget itself survived it.
            CrashLog.mark(context, "widget render fault: $fault")
        }
        views.setOnClickPendingIntent(R.id.widget_plate, openApp(context))
        views.setOnClickPendingIntent(R.id.widget_switch, toggleAlarm(context))
        return views
    }

    /** Renders at the midpoint of the cell's possible range: never stretched, never wasted. */
    private fun widgetSizePx(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
    ): Pair<Int, Int> {
        val options = manager.getAppWidgetOptions(widgetId)
        val density = context.resources.displayMetrics.density
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, DEFAULT_WIDTH_DP)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, DEFAULT_HEIGHT_DP)
        val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
        val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        val widthDp = if (maxWidth > 0) (minWidth + maxWidth) / 2 else minWidth
        val heightDp = if (maxHeight > 0) (minHeight + maxHeight) / 2 else minHeight
        return (widthDp * density).toInt() to (heightDp * density).toInt()
    }

    private fun defaultSizePx(context: Context): Pair<Int, Int> {
        val density = context.resources.displayMetrics.density
        return (DEFAULT_WIDTH_DP * density).toInt() to (DEFAULT_HEIGHT_DP * density).toInt()
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
