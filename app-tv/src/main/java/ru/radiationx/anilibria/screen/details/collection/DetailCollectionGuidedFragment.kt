package ru.radiationx.anilibria.screen.details.collection

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.leanback.widget.GuidedAction
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.fragment.FakeGuidedStepFragment
import ru.radiationx.anilibria.screen.details.DetailExtra
import ru.radiationx.anilibria.ui.widget.manager.ExternalProgressManager
import ru.radiationx.data.entity.domain.collection.CollectionType
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.quill.viewModel
import ru.radiationx.shared.ktx.android.getExtraNotNull
import ru.radiationx.shared.ktx.android.putExtra
import ru.radiationx.shared.ktx.android.subscribeTo

class DetailCollectionGuidedFragment : FakeGuidedStepFragment() {

    companion object {
        private const val ARG_ID = "id"
        private const val CHECK_SET_ID = 1

        private val COLLECTION_ITEMS = listOf(
            null to "Не в коллекции",
            CollectionType.PLANNED to "Запланировано",
            CollectionType.WATCHING to "Смотрю",
            CollectionType.WATCHED to "Просмотрено",
            CollectionType.POSTPONED to "Отложено",
            CollectionType.ABANDONED to "Брошено",
        )

        fun newInstance(releaseId: ReleaseId) = DetailCollectionGuidedFragment().putExtra {
            putParcelable(ARG_ID, releaseId)
        }
    }

    private val viewModel by viewModel<DetailCollectionViewModel> {
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
            if (it) {
                progressManager.show()
            } else {
                progressManager.hide()
            }
        }

        subscribeTo(viewModel.currentTypeData) {
            updateCheckedType(it)
        }
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        super.onCreateActions(actions, savedInstanceState)
        actions.addAll(
            COLLECTION_ITEMS.mapIndexed { index, item ->
                GuidedAction.Builder(requireContext())
                    .id(index.toLong())
                    .title(item.second)
                    .checkSetId(CHECK_SET_ID)
                    .build()
            }
        )
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        super.onGuidedActionClicked(action)
        val item = COLLECTION_ITEMS.getOrNull(action.id.toInt()) ?: return
        val type = item.first
        viewModel.setCollection(type)
    }

    private fun updateCheckedType(type: CollectionType?) {
        COLLECTION_ITEMS.forEachIndexed { index, item ->
            val action = findActionById(index.toLong()) ?: return@forEachIndexed
            val checked = item.first == type
            if (action.isChecked != checked) {
                action.isChecked = checked
                notifyActionChanged(findActionPositionById(action.id))
            }
        }
    }
}
