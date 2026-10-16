package com.ajeet.slotalarmclock

import android.app.Application

/**
 * Installs the crash reporter before anything else can fail, and leaves the first breadcrumb.
 *
 * This exists purely so that a fault during startup is recorded rather than lost: the phone has
 * no debugger attached, and "it will not open" is otherwise unactionable.
 */
class SlotAlarmApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        CrashLog.mark(this, "application.onCreate")
    }
}
