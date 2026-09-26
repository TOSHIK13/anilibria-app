package ru.radiationx.data.datasource.storage

import android.content.SharedPreferences
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import ru.radiationx.data.DataPreferences
import ru.radiationx.data.repository.watch.PendingTimecode
import ru.radiationx.data.repository.watch.WatchHistorySnapshot
import timber.log.Timber
import javax.inject.Inject

/** Дисковый кэш серверной истории просмотра и очередь неотправленных таймкодов. */
class WatchHistoryStorage @Inject constructor(
    private val moshi: Moshi,
    @DataPreferences private val sharedPreferences: SharedPreferences,
) {

    private companion object {
        const val KEY_SNAPSHOT = "data.watch_history_v1"
        const val KEY_PENDING = "data.watch_pending_timecodes_v1"
    }

    private val snapshotAdapter: JsonAdapter<WatchHistorySnapshot> by lazy {
        moshi.adapter(WatchHistorySnapshot::class.java)
    }

    private val pendingAdapter: JsonAdapter<List<PendingTimecode>> by lazy {
        moshi.adapter(Types.newParameterizedType(List::class.java, PendingTimecode::class.java))
    }

    fun getSnapshot(): WatchHistorySnapshot? = read(KEY_SNAPSHOT) { snapshotAdapter.fromJson(it) }

    fun saveSnapshot(snapshot: WatchHistorySnapshot) {
        sharedPreferences.edit().putString(KEY_SNAPSHOT, snapshotAdapter.toJson(snapshot)).apply()
    }

    fun getPending(): List<PendingTimecode> = read(KEY_PENDING) { pendingAdapter.fromJson(it) }.orEmpty()

    fun savePending(items: List<PendingTimecode>) {
        val editor = sharedPreferences.edit()
        if (items.isEmpty()) {
            editor.remove(KEY_PENDING)
        } else {
            editor.putString(KEY_PENDING, pendingAdapter.toJson(items))
        }
        editor.apply()
    }

    fun clear() {
        sharedPreferences.edit().remove(KEY_SNAPSHOT).remove(KEY_PENDING).apply()
    }

    private fun <T> read(key: String, parse: (String) -> T?): T? = try {
        sharedPreferences.getString(key, null)?.let(parse)
    } catch (ex: Exception) {
        Timber.e(ex)
        null
    }
}
