package ru.radiationx.data.external

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.radiationx.data.DataPreferences
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** Настройки синхронизации одного сервиса; по умолчанию всё включено. */
data class ExternalServiceOptions(
    val sendWatched: Boolean = true,
    val sendCollection: Boolean = true,
    val receiveChanges: Boolean = true,
    val notifyAfterEpisode: Boolean = true,
)

/** Настройки внешних сервисов по id сервиса (SharedPreferences). */
class ExternalServiceSettings @Inject constructor(
    @DataPreferences private val prefs: SharedPreferences,
) {

    private companion object {
        fun key(id: String, field: String) = "external_${id}_opt_$field"
    }

    private val states = ConcurrentHashMap<String, MutableStateFlow<ExternalServiceOptions>>()

    private fun state(id: String) = states.getOrPut(id) { MutableStateFlow(load(id)) }

    fun observe(id: String): Flow<ExternalServiceOptions> = state(id).asStateFlow()

    fun get(id: String): ExternalServiceOptions = state(id).value

    fun update(id: String, transform: (ExternalServiceOptions) -> ExternalServiceOptions) {
        val value = transform(get(id))
        prefs.edit {
            putBoolean(key(id, "send_watched"), value.sendWatched)
            putBoolean(key(id, "send_collection"), value.sendCollection)
            putBoolean(key(id, "receive"), value.receiveChanges)
            putBoolean(key(id, "notify"), value.notifyAfterEpisode)
        }
        state(id).value = value
    }

    /**
     * Первая синхронизация выполнена (мастер этапа 5 ставит флаг). Пока нет — чтение списка сервиса
     * только сравнивает списки и ничего не применяет и не отправляет.
     */
    fun isFirstSyncDone(id: String): Boolean = prefs.getBoolean(key(id, "first_sync_done"), false)

    fun setFirstSyncDone(id: String, done: Boolean) {
        prefs.edit { putBoolean(key(id, "first_sync_done"), done) }
    }

    private fun load(id: String) = ExternalServiceOptions(
        sendWatched = prefs.getBoolean(key(id, "send_watched"), true),
        sendCollection = prefs.getBoolean(key(id, "send_collection"), true),
        receiveChanges = prefs.getBoolean(key(id, "receive"), true),
        notifyAfterEpisode = prefs.getBoolean(key(id, "notify"), true),
    )
}
