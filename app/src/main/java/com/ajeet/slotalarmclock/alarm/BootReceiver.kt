package com.ajeet.slotalarmclock.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Alarms do not survive a reboot, so re-arm on boot.
 *
 * This deliberately does *not* listen for `LOCKED_BOOT_COMPLETED`. That broadcast only reaches
 * a `directBootAware` receiver, and the alarm time lives in credential-encrypted storage, which
 * is unreadable until the device has been unlocked. Firing at locked boot would read defaults
 * and silently cancel the alarm instead of restoring it. `BOOT_COMPLETED` is delivered only
 * after the first unlock, so it is both the correct and the earliest point we can re-arm.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> AlarmScheduler.apply(context)
        }
    }
}
