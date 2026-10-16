package com.ajeet.slotalarmclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import com.ajeet.slotalarmclock.alarm.AlarmRingingService
import com.ajeet.slotalarmclock.data.AlarmStore
import com.ajeet.slotalarmclock.ui.RingingScreen
import com.ajeet.slotalarmclock.ui.SlotAlarmTheme

/**
 * The screen an alarm opens on, including over the lock screen.
 *
 * It is launched by the ringing notification's full-screen intent, and closes itself the moment
 * ringing stops for any reason — including the user dismissing from the notification shade.
 */
class RingActivity : ComponentActivity() {

    private val ringingStopped = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        // Let the user pull down the shade to find Snooze/Dismiss from here.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        ContextCompat.registerReceiver(
            this,
            ringingStopped,
            IntentFilter(AlarmRingingService.ACTION_RINGING_STOPPED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val alarm = AlarmStore.get(this).state.value

        setContent {
            SlotAlarmTheme {
                RingingScreen(
                    hour = alarm.hour,
                    minute = alarm.minute,
                    onSnooze = { command(AlarmRingingService.ACTION_SNOOZE) },
                    onDismiss = { command(AlarmRingingService.ACTION_DISMISS) },
                )
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(ringingStopped) }
        super.onDestroy()
    }

    private fun command(action: String) {
        val intent = Intent(this, AlarmRingingService::class.java).setAction(action)
        // The service is already foreground; this just delivers the command. If it has already
        // stopped, starting it again is harmless — it stops itself immediately.
        runCatching { startService(intent) }
        finish()
    }
}
