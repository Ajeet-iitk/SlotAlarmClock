package com.ajeet.slotalarmclock

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ScrollView
import android.widget.TextView
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
        CrashLog.mark(this, "MainActivity.onCreate")

        // The previous run died. Report it rather than walking straight back into the code that
        // killed it — otherwise the app just crash-loops and the reason is never visible.
        CrashLog.readCrash(this)?.let { report ->
            showReport(report)
            return
        }

        try {
            enableEdgeToEdge()

            val store = AlarmStore.get(this)
            CrashLog.mark(this, "MainActivity.storeReady")

            setContent {
                SlotAlarmTheme {
                    SlotAlarmClockScreen(
                        store = store,
                        onAlarmArmed = ::onAlarmArmed,
                    )
                }
            }
            CrashLog.mark(this, "MainActivity.composed")
        } catch (error: Throwable) {
            // A fault while building the first frame. Record it and show it now, so the user
            // does not have to relaunch to find out what happened.
            CrashLog.record(this, error)
            showReport(CrashLog.readCrash(this) ?: CrashLog.summary(error))
        }
    }

    /**
     * Renders a crash report using nothing but platform widgets.
     *
     * Deliberately no Compose, no XML layout and no custom theme: whatever the fault is, it is
     * most likely *in* that stack, and the report has to survive it.
     */
    private fun showReport(report: String) {
        val body = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(32, 48, 32, 48)
            setTextColor(0xFFE6EBF2.toInt())
            text = buildString {
                appendLine("SLOT ALARM — DIAGNOSTICS")
                appendLine()
                append(report)
                appendLine()
                appendLine()
                appendLine("Tap anywhere to clear this and reload the clock.")
            }
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF0C0E12.toInt())
            addView(body)
            setOnClickListener {
                CrashLog.clear(this@MainActivity)
                recreate()
            }
        }

        setContentView(scroll)
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
