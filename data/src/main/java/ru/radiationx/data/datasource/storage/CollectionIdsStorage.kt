package ru.radiationx.data.datasource.storage

import android.content.SharedPreferences
import com.squareup.moshi.Json
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import ru.radiationx.data.DataPreferences
import timber.log.Timber
import javax.inject.Inject

/** Релиз в коллекции пользователя: [type] — [ru.radiationx.data.entity.domain.collection.CollectionType.value]. */
@JsonClass(generateAdapter = true)
data class CollectionIdEntry(
    @Json(name = "id") val id: Int,
    @Json(name = "type") val type: String,
)

@JsonClass(generateAdapter = true)
data class CollectionIdsSnapshot(
    @Json(name = "items") val items: List<CollectionIdEntry>,
    @Json(name = "loaded_at") val loadedAt: Long,
)

/** Дисковый кэш «релиз → коллекция» (для фильтра ряда «Продолжить просмотр» без сети). */
class CollectionIdsStorage @Inject constructor(
    private val moshi: Moshi,
    @DataPreferences private val sharedPreferences: SharedPreferences,
) {

    private companion object {
        const val KEY_SNAPSHOT = "data.collection_ids_v1"
    }

    private val adapter: JsonAdapter<CollectionIdsSnapshot> by lazy {
        moshi.adapter(CollectionIdsSnapshot::class.java)
    }

    fun get(): CollectionIdsSnapshot? = try {
        sharedPreferences.getString(KEY_SNAPSHOT, null)?.let { adapter.fromJson(it) }
    } catch (ex: Exception) {
        Timber.e(ex)
        null
    }

    fun save(snapshot: CollectionIdsSnapshot) {
        sharedPreferences.edit().putString(KEY_SNAPSHOT, adapter.toJson(snapshot)).apply()
    }

    fun clear() {
        sharedPreferences.edit().remove(KEY_SNAPSHOT).apply()
    }
}
