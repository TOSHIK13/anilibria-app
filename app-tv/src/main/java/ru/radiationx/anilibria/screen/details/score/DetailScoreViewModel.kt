package ru.radiationx.anilibria.screen.details.score

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import ru.radiationx.anilibria.common.fragment.GuidedRouter
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.anilibria.screen.details.DetailExtra
import ru.radiationx.data.external.AniListRatings
import ru.radiationx.shared.ktx.coRunCatching
import timber.log.Timber
import javax.inject.Inject

/** Выбор оценки AniList (1..10, 0 — снять) для одного релиза (= один тайтл AniList = один сезон). */
class DetailScoreViewModel @Inject constructor(
    private val argExtra: DetailExtra,
    private val ratings: AniListRatings,
    private val guidedRouter: GuidedRouter,
    private val context: Context,
) : LifecycleViewModel() {

    /** Текущая оценка (0 — нет); null — ещё не загружена. */
    val currentScore = MutableStateFlow<Int?>(null)
    val progressState = MutableStateFlow(false)

    private val malId: Int? get() = ratings.malIdOf(argExtra.id.id)

    init {
        viewModelScope.launch {
            currentScore.value = coRunCatching {
                malId?.let { ratings.getScore(it) }
            }.getOrNull() ?: 0
        }
    }

    fun setScore(score: Int) {
        if (progressState.value) return
        val id = malId
        if (id == null || currentScore.value == score) {
            guidedRouter.close()
            return
        }
        viewModelScope.launch {
            progressState.value = true
            coRunCatching { ratings.setScore(id, score) }
                .onSuccess {
                    currentScore.value = score
                    guidedRouter.close()
                }
                .onFailure {
                    Timber.e(it)
                    Toast.makeText(context, "Не удалось сохранить оценку AniList", Toast.LENGTH_LONG).show()
                }
            progressState.value = false
        }
    }
}
