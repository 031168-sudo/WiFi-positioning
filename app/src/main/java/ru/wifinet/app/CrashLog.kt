package ru.wifinet.app

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Minimal in-house crash reporter: records the last fatal exception's stack trace to disk
 * (SharedPreferences survives the process death that follows) so the next launch can show it —
 * there's no adb/device access available to pull logcat during development otherwise. Also keeps
 * a short rolling log of activity lifecycle events, for diagnosing a screen that closes on its
 * own with no exception at all (e.g. the OS killing the process outright).
 */
object CrashLog {
    private const val PREFS = "crash_log"
    private const val KEY = "last_crash"
    private const val EVENTS_KEY = "events"
    private var installed = false
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Appends a short timestamped line to the rolling lifecycle event log. */
    fun logEvent(context: Context, line: String) {
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val existing = prefs.getString(EVENTS_KEY, "") ?: ""
            val stamped = "${timeFormat.format(System.currentTimeMillis())}  $line"
            val updated = (existing + "\n" + stamped).takeLast(4000)
            // commit(), not apply(): when the OS kills the process outright the async write
            // never lands, which is exactly the case this log exists to diagnose.
            prefs.edit().putString(EVENTS_KEY, updated).commit()
        } catch (_: Exception) {
        }
    }

    /** Returns and clears the rolling lifecycle event log, or null if empty. */
    fun consumeEvents(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val events = prefs.getString(EVENTS_KEY, null)
        if (!events.isNullOrBlank()) prefs.edit().remove(EVENTS_KEY).apply()
        return events?.trim()?.ifBlank { null }
    }

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val trace = Log.getStackTraceString(throwable)
                Log.e("WifiNetCrash", trace)
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY, trace).commit()
            } catch (_: Exception) {
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /** Returns and clears the last recorded crash, or null if the last run was clean. */
    fun consumeLast(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val trace = prefs.getString(KEY, null)
        if (trace != null) prefs.edit().remove(KEY).apply()
        return trace
    }
}
