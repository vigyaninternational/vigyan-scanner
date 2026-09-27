package com.vigyan.scanner

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * If the app ever closes because of an error, the error is written to a file so the next start can
 * show it (and the user can send it to be fixed). Nothing is sent anywhere by itself.
 */
object CrashLog {

    private fun file(context: Context) = File(context.filesDir, "last_crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(app, previous))
    }

    private class Handler(val context: Context, val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                file(context).writeText(
                    "Vigyan Scanner ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                        SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.US).format(Date()) + "\n\n" + sw.toString().take(6000),
                )
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** The last crash report, or null. */
    fun read(context: Context): String? = file(context).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        file(context).delete()
    }
}
