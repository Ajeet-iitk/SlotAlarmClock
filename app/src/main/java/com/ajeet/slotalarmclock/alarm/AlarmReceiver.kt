package com.ajeet.slotalarmclock.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * The alarm's entry point. An exact alarm is one of the few situations where Android lets a
 * background app start a foreground service, which is what makes the ringing reliable.
 *
 * Rebooting is handled separately by [BootReceiver].
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val service = Intent(context, AlarmRingingService::class.java)
            .setAction(AlarmRingingService.ACTION_START)
        // startForegroundService is required from O onwards; the service promotes itself
        // within the five-second window it is given.
        ContextCompat.startForegroundService(context, service)
    }

    companion object {
        const val ACTION_FIRE = "com.ajeet.slotalarmclock.action.FIRE_ALARM"
    }
}
