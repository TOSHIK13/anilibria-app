package ru.radiationx.data.datasource.storage

import android.content.SharedPreferences
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import ru.radiationx.data.DataPreferences
import ru.radiationx.data.repository.watch.ReleaseCardInfo
import timber.log.Timber
import javax.inject.Inject

/** Дисковый кэш кратких данных релизов для карточек «Продолжить просмотр». */
class ReleaseCardStorage @Inject constructor(
    private val moshi: Moshi,
    @DataPreferences private val sharedPreferences: SharedPreferences,
) {

    private companion object {
        const val KEY_CARDS = "data.release_cards_v1"
    }

    private val adapter: JsonAdapter<List<ReleaseCardInfo>> by lazy {
        moshi.adapter(Types.newParameterizedType(List::class.java, ReleaseCardInfo::class.java))
    }

    fun get(): List<ReleaseCardInfo> = try {
        sharedPreferences.getString(KEY_CARDS, null)?.let { adapter.fromJson(it) }.orEmpty()
    } catch (ex: Exception) {
        Timber.e(ex)
        emptyList()
    }

    fun save(items: List<ReleaseCardInfo>) {
        sharedPreferences.edit().putString(KEY_CARDS, adapter.toJson(items)).apply()
    }
}
