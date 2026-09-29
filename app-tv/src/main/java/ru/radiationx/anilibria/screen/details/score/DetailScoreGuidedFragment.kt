package ru.radiationx.anilibria.screen.details.score

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.leanback.widget.GuidedAction
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.fragment.FakeGuidedStepFragment
import ru.radiationx.anilibria.screen.details.DetailExtra
import ru.radiationx.anilibria.ui.widget.manager.ExternalProgressManager
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.quill.viewModel
import ru.radiationx.shared.ktx.android.getExtraNotNull
import ru.radiationx.shared.ktx.android.putExtra
import ru.radiationx.shared.ktx.android.subscribeTo

/** Выбор оценки AniList: 10..1 и «Убрать оценку»; текущая отмечена. */
class DetailScoreGuidedFragment : FakeGuidedStepFragment() {

    companion object {
        private const val ARG_ID = "id"
        private const val CHECK_SET_ID = 1
        private const val CLEAR_ID = 0

        fun newInstance(releaseId: ReleaseId) = DetailScoreGuidedFragment().putExtra {
            putParcelable(ARG_ID, releaseId)
        }
    }

    private val viewModel by viewModel<DetailScoreViewModel> {
        DetailExtra(getExtraNotNull(ARG_ID))
    }

    private val progressManager by lazy { ExternalProgressManager() }

    override fun onProvideTheme(): Int = R.style.AppTheme_Player_LeanbackWizard

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycle.addObserver(viewModel)

        progressManager.rootView =
            view.findViewById<View>(androidx.leanback.R.id.action_fragment_root) as? ViewGroup

        subscribeTo(viewModel.progressState) {
            if (it) progressManager.show() else progressManager.hide()
        }
        subscribeTo(viewModel.currentScore) { score ->
            score ?: return@subscribeTo
            for (id in (0..10)) {
                val action = findActionById(id.toLong()) ?: continue
                // «Убрать оценку» отмечаем, только если оценки нет, и выбираем её лишь как «нет значения».
                val checked = id == score && id != CLEAR_ID
                if (action.isChecked != checked) {
                    action.isChecked = checked
                    notifyActionChanged(findActionPositionById(action.id))
                }
            }
            val position = findActionPositionById(score.toLong().takeIf { it > 0 } ?: 10L)
            if (position >= 0) setSelectedActionPosition(position)
        }
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        super.onCreateActions(actions, savedInstanceState)
        for (score in 10 downTo 1) {
            actions.add(
                GuidedAction.Builder(requireContext())
                    .id(score.toLong())
                    .title("★ $score")
                    .checkSetId(CHECK_SET_ID)
                    .build()
            )
        }
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(CLEAR_ID.toLong())
                .title("Убрать оценку")
                .build()
        )
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        super.onGuidedActionClicked(action)
        viewModel.setScore(action.id.toInt())
    }
}
