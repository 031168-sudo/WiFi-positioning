package ru.wifinet.app

import android.content.Context
import android.util.Log

/**
 * Minimal in-house crash reporter: records the last fatal exception's stack trace to disk
 * (SharedPreferences survives the process death that follows) so the next launch can show it —
 * there's no adb/device access available to pull logcat during development otherwise.
 */
object CrashLog {
    private const val PREFS = "crash_log"
    private const val KEY = "last_crash"
    private var installed = false

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
                    .edit().putString(KEY, trace).apply()
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
