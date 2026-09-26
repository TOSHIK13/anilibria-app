package ru.radiationx.anilibria.screen.main

import ru.radiationx.data.system.LoadTiming
import android.os.Bundle
import android.view.View
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.radiationx.anilibria.common.BaseCardsViewModel
import ru.radiationx.anilibria.common.LibriaCard
import ru.radiationx.anilibria.common.LinkCard
import ru.radiationx.anilibria.common.LoadingCard
import ru.radiationx.anilibria.common.RowDiffCallback
import ru.radiationx.anilibria.extension.createCardsRowBy
import ru.radiationx.anilibria.screen.mainpages.MainHeroViewModel
import ru.radiationx.anilibria.screen.mainpages.MainPagesFragment
import ru.radiationx.anilibria.screen.mainpages.hideRowsAboveSelected
import ru.radiationx.anilibria.ui.presenter.cust.ContinueListRowPresenter
import ru.radiationx.anilibria.ui.presenter.cust.CustomListRowViewHolder
import ru.radiationx.shared.ktx.android.subscribeTo
import ru.radiationx.shared_app.di.quillParentViewModel


class MainFragment : RowsSupportFragment() {

    private companion object {
        /** Стартовый фокус на «Продолжить просмотр» ставится один раз за процесс. */
        var initialFocusDone = false
        const val INITIAL_FOCUS_TIMEOUT_MS = 1_500L
    }

    private val rowsPresenter by lazy { ContinueListRowPresenter.rowsPresenterSelector(mainPage = true) }
    private val rowsAdapter by lazy { ArrayObjectAdapter(rowsPresenter) }

    private val heroViewModel by quillParentViewModel<MainHeroViewModel>()

    private val mainViewModel by quillParentViewModel<MainViewModel>()

    private val continueViewModel by quillParentViewModel<MainContinueViewModel>()
    private val feedViewModel by quillParentViewModel<MainFeedViewModel>()
    private val scheduleViewModel by quillParentViewModel<MainScheduleViewModel>()
    private val favoritesViewModel by quillParentViewModel<MainFavoritesViewModel>()
    private val youtubeViewModel by quillParentViewModel<MainYouTubeViewModel>()

    private fun getViewModel(rowId: Long): BaseCardsViewModel? = when (rowId) {
        MainViewModel.CONTINUE_ROW_ID -> continueViewModel
        MainViewModel.FEED_ROW_ID -> feedViewModel
        MainViewModel.SCHEDULE_ROW_ID -> scheduleViewModel
        MainViewModel.FAVORITE_ROW_ID -> favoritesViewModel
        MainViewModel.YOUTUBE_ROW_ID -> youtubeViewModel
        else -> null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        LoadTiming.markOnce("startup", "main_shown")

        viewLifecycleOwner.lifecycle.addObserver(mainViewModel)
        viewLifecycleOwner.lifecycle.addObserver(continueViewModel)
        viewLifecycleOwner.lifecycle.addObserver(feedViewModel)
        viewLifecycleOwner.lifecycle.addObserver(scheduleViewModel)
        viewLifecycleOwner.lifecycle.addObserver(favoritesViewModel)
        viewLifecycleOwner.lifecycle.addObserver(youtubeViewModel)

        adapter = rowsAdapter
        onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, _ ->
            heroViewModel.onItemSelected(item)
        }
        hideRowsAboveSelected()

        setOnItemViewClickedListener { _, item, rowViewHolder, row ->
            if (rowViewHolder is CustomListRowViewHolder) {
                val viewMode: BaseCardsViewModel? = getViewModel((row as ListRow).id)
                when (item) {
                    is LinkCard -> {
                        viewMode?.onLinkCardClick()
                    }

                    is LoadingCard -> {
                        viewMode?.onLoadingCardClick()
                    }

                    is LibriaCard -> {
                        viewMode?.onLibriaCardClick(item)
                    }

                    else -> {
                        // do nothing
                    }
                }
            }
        }

        val rowMap = mutableMapOf<Long, ListRow>()
        subscribeTo(mainViewModel.rowListData) { rowList ->
            val rows = rowList.map { rowId ->
                val row =
                    rowMap[rowId] ?: createCardsRowBy(rowId, rowsAdapter, getViewModel(rowId)!!)
                rowMap[rowId] = row
                row
            }
            rowsAdapter.setItems(rows, RowDiffCallback)
        }

        focusContinueRowOnStart()
    }

    /**
     * При запуске курсор сразу на первом релизе «Продолжить просмотр», а не на вкладках.
     * Только если ряд есть и пользователь ещё не ушёл с «Главной».
     */
    private fun focusContinueRowOnStart() {
        if (initialFocusDone) return
        viewLifecycleOwner.lifecycleScope.launch {
            val ready = withTimeoutOrNull(INITIAL_FOCUS_TIMEOUT_MS) {
                mainViewModel.rowListData.first { it.firstOrNull() == MainViewModel.CONTINUE_ROW_ID }
                continueViewModel.cardsData.first { cards -> cards.any { it is LibriaCard } }
                // Ряд должен уже стоять первым в адаптере, иначе фокус уйдёт на соседний ряд.
                while ((rowsAdapter.takeIf { it.size() > 0 }?.get(0) as? ListRow)?.id != MainViewModel.CONTINUE_ROW_ID) {
                    delay(16)
                }
            }
            initialFocusDone = true
            val pages = parentFragment as? MainPagesFragment
            if (ready == null || pages == null) {
                pages?.revealInitialScreen()
                return@launch
            }
            pages.focusContentOnStart {
                setSelectedPosition(0, false, ListRowPresenter.SelectItemViewHolderTask(0))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        notifyReady()
    }

    private fun notifyReady() {
        mainFragmentAdapter.fragmentHost.notifyDataReady(mainFragmentAdapter)
    }
}
