package com.ajeet.slotalarmclock

import android.content.Context
import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Breadcrumbs and a last-crash report, kept in `SharedPreferences`.
 *
 * This build reaches the phone as a sideloaded APK with no `adb` on the machine that produces
 * it, so "the app will not open" has to be diagnosable from the phone alone. Two things are
 * recorded:
 *
 *  * a **stage** marker, rewritten at every startup milestone, so a fault that happens too
 *    early for the handler to catch still leaves a trail of how far the process got;
 *  * the **uncaught exception** itself, written synchronously before the process is torn down.
 *
 * [readCrash] is what the activity shows instead of the clock, so a crash loop can never hide
 * its own cause.
 */
object CrashLog {

    private const val PREFS = "slot_alarm_diagnostics"
    private const val KEY_STAGE = "stage"
    private const val KEY_CRASH = "crash"
    private const val KEY_CRASH_BUILD = "crash_build"
    private const val MAX_CHARS = 8000

    @Volatile
    private var installed = false

    /** Records [stage] as the furthest point reached. Synchronous so it survives a hard death. */
    fun mark(context: Context, stage: String) {
        prefs(context).edit()
            .putString(KEY_STAGE, "$stage  @ ${timestamp()}")
            .commit()
    }

    /** Installs the last-chance handler. Safe to call more than once. */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(app, error, "uncaught on ${thread.name}") }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Stores a report.
     *
     * The first fault wins: a crash loop would otherwise overwrite the original cause with its
     * own echo on every restart.
     */
    fun record(context: Context, error: Throwable, origin: String = "caught") {
        val app = context.applicationContext
        if (readCrash(app) != null) return

        val report = buildString {
            appendLine(summary(error))
            appendLine()
            appendLine("origin: $origin")
            appendLine("stage:  ${readStage(app) ?: "(never marked)"}")
            appendLine("seen:   ${timestamp()}")
            appendLine("build:  ${version(app)}")
            appendLine(
                "device: ${Build.MANUFACTURER} ${Build.MODEL}, " +
                    "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            )
            appendLine()
            append(Log.getStackTraceString(error))
        }
        prefs(app).edit()
            .putString(KEY_CRASH, report.take(MAX_CHARS))
            .putString(KEY_CRASH_BUILD, version(app))
            .commit()
    }

    /** A one-line description short enough to retype from a phone screen. */
    fun summary(error: Throwable): String {
        val message = error.message
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            ?.take(180)
        return buildString {
            append(error.javaClass.simpleName)
            if (!message.isNullOrEmpty()) {
                append(": ")
                append(message)
            }
        }
    }

    /**
     * The stored crash report, or null when there is nothing to report for *this* build.
     *
     * A report is only meaningful to the build that produced it. Installing a new APK therefore
     * starts from a clean slate — otherwise the screen for an old, already-fixed crash would sit
     * in front of the clock and hide the fix.
     */
    fun readCrash(context: Context): String? {
        val app = context.applicationContext
        val stored = prefs(app).getString(KEY_CRASH, null) ?: return null
        if (prefs(app).getString(KEY_CRASH_BUILD, null) != version(app)) {
            clear(app)
            return null
        }
        return stored
    }

    /** Where the most recent run got to. */
    fun readStage(context: Context): String? = prefs(context).getString(KEY_STAGE, null)

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_CRASH).remove(KEY_CRASH_BUILD).commit()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Which build produced the report — sideloaded APKs are otherwise anonymous. */
    private fun version(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
