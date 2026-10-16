package com.ajeet.slotalarmclock

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.ajeet.slotalarmclock.data.AlarmStore
import com.ajeet.slotalarmclock.ui.SlotAlarmClockScreen
import com.ajeet.slotalarmclock.ui.SlotAlarmTheme

/**
 * Hosts the clock face.
 *
 * The activity itself is thin — all state lives in [AlarmStore] so the widget and the ringing
 * service see the same values.
 */
class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val store = AlarmStore.get(this)

        setContent {
            SlotAlarmTheme {
                SlotAlarmClockScreen(
                    store = store,
                    onAlarmArmed = ::onAlarmArmed,
                )
            }
        }
    }

    /** Everything that must be true before an alarm can be relied on. */
    private fun onAlarmArmed() {
        ensureNotificationPermission()
        ensureExactAlarmPermission()
    }

    /**
     * Alarms ring through a foreground service, which needs a visible notification from
     * Android 13. Asking at the moment the switch is flipped is the only time the request has
     * obvious context for the user.
     */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * From Android 12 the "Alarms & reminders" grant is no longer implied. The alarm itself
     * works without it (setAlarmClock is exempt), but the widget's per-minute redraw does not,
     * so it is worth one settings trip.
     */
    private fun ensureExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val manager = getSystemService(AlarmManager::class.java) ?: return
        if (manager.canScheduleExactAlarms()) return
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.fromParts("package", packageName, null),
                ),
            )
        }
    }
}
