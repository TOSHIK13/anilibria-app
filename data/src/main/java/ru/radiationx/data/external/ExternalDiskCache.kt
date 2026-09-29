package ru.radiationx.data.external

import android.content.Context
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * JSON-кеш на диске: файл на ключ в filesDir/[namespace], LRU по времени доступа
 * (не больше [maxItems]). Если задан [ttlMs] — записи старше него читаются как отсутствующие
 * (время записи хранится в поле `_saved_at`; у файлов без него срок не проверяется).
 */
class ExternalDiskCache(
    context: Context,
    private val namespace: String,
    private val maxItems: Int,
    private val ttlMs: Long? = null,
) {

    private val dir = File(context.filesDir, namespace)
    private val lock = Any()

    fun read(key: String): JSONObject? = synchronized(lock) {
        val file = File(dir, "$key.json")
        if (!file.exists()) return null
        try {
            val json = JSONObject(file.readText())
            val saved = json.optLong(SAVED_AT)
            if (ttlMs != null && saved > 0 && System.currentTimeMillis() - saved > ttlMs) return null
            file.setLastModified(System.currentTimeMillis())
            json
        } catch (e: Exception) {
            Timber.w(e, "external cache %s: bad file %s", namespace, key)
            file.delete()
            null
        }
    }

    fun write(key: String, json: JSONObject) = synchronized(lock) {
        try {
            dir.mkdirs()
            json.put(SAVED_AT, System.currentTimeMillis())
            val tmp = File(dir, "$key.json.tmp")
            tmp.writeText(json.toString())
            val file = File(dir, "$key.json")
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
            prune()
        } catch (e: Exception) {
            Timber.w(e, "external cache %s: write %s", namespace, key)
        }
    }

    fun delete(key: String) = synchronized(lock) { File(dir, "$key.json").delete() }

    /** Размер кеша на диске: файлов, байт. */
    fun stats(): Pair<Int, Long> = synchronized(lock) {
        val files = dir.listFiles().orEmpty()
        files.size to files.sumOf { it.length() }
    }

    private fun prune() {
        val files = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        if (files.size <= maxItems) return
        files.sortedBy { it.lastModified() }.take(files.size - maxItems).forEach { it.delete() }
    }

    private companion object {
        const val SAVED_AT = "_saved_at"
    }
}
