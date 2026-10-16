package com.ajeet.slotalarmclock.data

import android.content.Context
import com.ajeet.slotalarmclock.widget.SlotClockWidgetProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Everything the app knows about the alarm. Immutable so Compose can diff it cheaply. */
data class AlarmState(
    val enabled: Boolean = false,
    val hour: Int = 7,
    val minute: Int = 30,
) {
    val hourTens: Int get() = hour / 10
    val hourOnes: Int get() = hour % 10
    val minuteTens: Int get() = minute / 10
    val minuteOnes: Int get() = minute % 10

    /** Always 24-hour, zero-padded — this app has no AM/PM concept by design. */
    val label: String get() = String.format(Locale.US, "%02d:%02d", hour, minute)
}

/**
 * Single source of truth for the alarm, shared by the activity, the widget and the ringing
 * service. Backed by SharedPreferences so it survives process death, and exposed as a
 * [StateFlow] so the UI recomposes when the widget flips the switch behind its back.
 */
class AlarmStore private constructor(context: Context) {

    private val appContext = context.applicationContext

    private val prefs = appContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<AlarmState> = _state.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _state.value = _state.value.copy(enabled = enabled)
        publish()
    }

    fun setTime(hour: Int, minute: Int) {
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        prefs.edit().putInt(KEY_HOUR, h).putInt(KEY_MINUTE, m).apply()
        _state.value = _state.value.copy(hour = h, minute = m)
        publish()
    }

    /** Flips the alarm and returns the new state. Used by the widget's switch. */
    fun toggle(): Boolean {
        val next = !_state.value.enabled
        setEnabled(next)
        return next
    }

    private fun read(): AlarmState = AlarmState(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        hour = prefs.getInt(KEY_HOUR, 7).coerceIn(0, 23),
        minute = prefs.getInt(KEY_MINUTE, 30).coerceIn(0, 59),
    )

    /**
     * Tells the home-screen widget that global alarm state moved.
     *
     * This is a deliberate one-way dependency from data to widget. Widgets cannot observe a
     * StateFlow — they are RemoteViews rebuilt on demand — so the alternative would be asking
     * every call site to remember to refresh, and eventually one of them would not.
     */
    private fun publish() {
        SlotClockWidgetProvider.requestRefresh(appContext)
    }

    companion object {
        private const val PREFS = "slot_alarm"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_HOUR = "hour"
        private const val KEY_MINUTE = "minute"

        @Volatile
        private var instance: AlarmStore? = null

        fun get(context: Context): AlarmStore =
            instance ?: synchronized(this) {
                instance ?: AlarmStore(context).also { instance = it }
            }
    }
}
