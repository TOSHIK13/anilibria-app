package ru.radiationx.data.external

import org.json.JSONObject
import timber.log.Timber
import java.io.File

/** JSON-файл с атомарной записью (временный файл + rename): переживает падение процесса посреди записи. */
class ExternalJsonFile(private val file: File) {

    fun read(): JSONObject? = try {
        if (file.exists()) JSONObject(file.readText()) else null
    } catch (e: Exception) {
        Timber.w(e, "external json %s: unreadable", file.name)
        null
    }

    fun write(json: JSONObject) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            Timber.w(e, "external json %s: write failed", file.name)
        }
    }
}
