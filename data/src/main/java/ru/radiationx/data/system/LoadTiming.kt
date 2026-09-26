package ru.radiationx.data.system

import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Лёгкое логирование скорости загрузки.
 *
 * Debug: включено всегда.
 * Release: только если `adb shell setprop log.tag.LoadTiming DEBUG` (проверяется при старте процесса).
 *
 * Формат строк:
 * `[startup] config_loaded +1234ms`
 * `[net] GET anilibria.top/api/v1/... 200 dns=.. conn=.. tls=.. ttfb=.. total=.. reused=..`
 */
object LoadTiming {

    const val TAG = "LoadTiming"

    @Volatile
    var enabled: Boolean = false
        private set

    private val onceKeys = ConcurrentHashMap.newKeySet<String>()

    private val processStart: Long by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Process.getStartElapsedRealtime()
        } else {
            SystemClock.elapsedRealtime()
        }
    }

    fun init(debug: Boolean) {
        enabled = debug || runCatching { Log.isLoggable(TAG, Log.DEBUG) }.getOrDefault(false)
    }

    fun now(): Long = SystemClock.elapsedRealtime()

    /** Миллисекунды с момента старта процесса. */
    fun sinceStart(): Long = now() - processStart

    /** `[section] name +XXXms extra` — время от старта процесса. */
    fun mark(section: String, name: String, extra: String? = null) {
        if (!enabled) return
        log("[$section] $name +${sinceStart()}ms${extra.suffix()}")
    }

    /** То же, что [mark], но только один раз за процесс для пары section/name. */
    fun markOnce(section: String, name: String, extra: String? = null) {
        if (!enabled) return
        if (onceKeys.add("$section/$name")) {
            mark(section, name, extra)
        }
    }

    /** `[section] name 123ms +XXXms extra` — длительность от [startedAt] ([now]) и время от старта. */
    fun span(section: String, name: String, startedAt: Long, extra: String? = null) {
        if (!enabled) return
        log("[$section] $name ${now() - startedAt}ms +${sinceStart()}ms${extra.suffix()}")
    }

    fun log(message: String) {
        if (!enabled) return
        Log.d(TAG, message)
    }

    private fun String?.suffix(): String = if (isNullOrEmpty()) "" else " $this"
}
