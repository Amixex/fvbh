package com.amix.tutor

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Показывает причину сбоя прямо в приложении: при следующем запуске появляется окно с отчётом,
 * который можно скопировать. Ловит ошибки Java и (Android 11+) причину «тихого» закрытия приложения.
 * Также следит за анимацией «чёрная дыра»: если приложение закрылось во время неё,
 * анимация отключается для этой версии, чтобы не было повторных вылетов.
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"
    private const val PREFS = "amix_crash"
    private const val KEY_SEEN = "seen_exit_ts"
    private const val KEY_GUARD = "bh_guard"
    private const val KEY_DISABLED = "bh_disabled_version"

    private val _report = MutableStateFlow<String?>(null)
    val report: StateFlow<String?> = _report.asStateFlow()

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                File(app.filesDir, FILE).writeText(
                    header(app) + "\nПоток: ${thread.name}\n" + error.stackTraceToString().take(4000)
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }

        val prefs = prefs(app)
        var disabledNote = false
        if (prefs.getBoolean(KEY_GUARD, false)) {
            prefs.edit().putBoolean(KEY_GUARD, false).putInt(KEY_DISABLED, versionCode(app)).apply()
            disabledNote = true
        }

        var text: String? = null
        val file = File(app.filesDir, FILE)
        if (file.exists()) {
            text = try { file.readText() } catch (_: Throwable) { null }
            file.delete()
        }
        val exit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) lastBadExit(app, prefs) else null
        text = text ?: exit
        if (disabledNote) {
            val note = "\n[!] Анимация «чёрная дыра» отключена для этой версии после сбоя."
            text = (text ?: header(app)) + note
        }
        _report.value = text
    }

    fun dismiss() {
        _report.value = null
    }

    /** Ошибка, после которой приложение продолжает работать, но её нужно показать. */
    fun reportNonFatal(context: Context, error: Throwable, where: String) {
        val app = context.applicationContext
        _report.value = header(app) + "\nМесто: $where\n" + error.stackTraceToString().take(4000)
    }

    fun guardStart(context: Context) {
        prefs(context.applicationContext).edit().putBoolean(KEY_GUARD, true).commit()
    }

    fun guardOk(context: Context) {
        prefs(context.applicationContext).edit().putBoolean(KEY_GUARD, false).apply()
    }

    fun blackHoleDisabled(context: Context): Boolean {
        val app = context.applicationContext
        return prefs(app).getInt(KEY_DISABLED, -1) == versionCode(app)
    }

    private fun prefs(app: Context): SharedPreferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Suppress("DEPRECATION")
    private fun versionCode(app: Context): Int = try {
        val p = app.packageManager.getPackageInfo(app.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode.toInt() else p.versionCode
    } catch (_: Throwable) {
        0
    }

    private fun header(app: Context): String {
        val name = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName } catch (_: Throwable) { "?" }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return "Amix $name (${versionCode(app)}), Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
            "${Build.MANUFACTURER} ${Build.MODEL}, $time"
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun lastBadExit(app: Context, prefs: SharedPreferences): String? = try {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val list = am.getHistoricalProcessExitReasons(app.packageName, 0, 5)
        val seen = prefs.getLong(KEY_SEEN, 0L)
        if (list.isNotEmpty()) prefs.edit().putLong(KEY_SEEN, list.maxOf { it.timestamp }).apply()
        val bad = list.firstOrNull { it.timestamp > seen && it.reason in BAD_REASONS }
        if (bad == null) {
            null
        } else {
            val sb = StringBuilder(header(app))
            sb.append("\nЗавершение процесса: ").append(reasonName(bad.reason))
            sb.append("\nОписание: ").append(bad.description ?: "-")
            sb.append("\nПамять (RSS), КБ: ").append(bad.rss / 1024)
            if (bad.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                val trace = try {
                    bad.traceInputStream?.bufferedReader()?.use { it.readText() }
                } catch (_: Throwable) {
                    null
                }
                if (!trace.isNullOrBlank()) sb.append("\nТрассировка:\n").append(trace.take(3500))
            }
            sb.toString()
        }
    } catch (_: Throwable) {
        null
    }

    private val BAD_REASONS = setOf(
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
    )

    @RequiresApi(Build.VERSION_CODES.R)
    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH (ошибка Java)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE (сбой в системной библиотеке)"
        ApplicationExitInfo.REASON_ANR -> "ANR (приложение зависло)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (не хватило памяти)"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        else -> "код $reason"
    }
}
