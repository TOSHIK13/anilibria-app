package ru.radiationx.anilibria.screen.suggestions

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.radiationx.data.DataPreferences
import javax.inject.Inject

/**
 * Недавние запросы страницы «Поиск»: последние [MAX_COUNT] на устройстве, без дублей
 * (без учёта регистра), последний — первым. Здесь же — отказ в доступе к микрофону.
 */
class SearchQueriesStorage @Inject constructor(
    @DataPreferences private val sharedPreferences: SharedPreferences,
) {

    private companion object {
        const val KEY_QUERIES = "tv_search_recent_queries"
        const val KEY_MIC_DENIED = "tv_search_mic_denied"
        const val SEPARATOR = '\n'
        const val MAX_COUNT = 8
    }

    private val queries = MutableStateFlow(read())

    fun observe(): StateFlow<List<String>> = queries.asStateFlow()

    fun add(query: String) {
        val value = query.replace(SEPARATOR, ' ').trim()
        if (value.isEmpty()) return
        val newQueries = (listOf(value) + queries.value.filterNot { it.equals(value, ignoreCase = true) })
            .take(MAX_COUNT)
        if (newQueries == queries.value) return
        queries.value = newQueries
        sharedPreferences.edit().putString(KEY_QUERIES, newQueries.joinToString(SEPARATOR.toString())).apply()
    }

    /** Пользователь отказал в доступе к микрофону: дальше не спрашиваем, а подсказываем про настройки. */
    var micDenied: Boolean
        get() = sharedPreferences.getBoolean(KEY_MIC_DENIED, false)
        set(value) {
            sharedPreferences.edit().putBoolean(KEY_MIC_DENIED, value).apply()
        }

    private fun read(): List<String> = sharedPreferences
        .getString(KEY_QUERIES, null)
        .orEmpty()
        .split(SEPARATOR)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .take(MAX_COUNT)
}
