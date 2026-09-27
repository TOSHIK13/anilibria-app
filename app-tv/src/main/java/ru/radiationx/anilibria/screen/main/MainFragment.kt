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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
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
        verticalGridView?.setOnKeyInterceptListener { event ->
            (parentFragment as? MainPagesFragment)?.onUserKey(event)
            false
        }

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
            val continueWasFirst = (rowsAdapter.takeIf { it.size() > 0 }?.get(0) as? ListRow)?.id ==
                    MainViewModel.CONTINUE_ROW_ID
            rowsAdapter.setItems(rows, RowDiffCallback)
            if (!continueWasFirst && rowList.firstOrNull() == MainViewModel.CONTINUE_ROW_ID) {
                onContinueRowInserted()
            }
        }

        focusContinueRowOnStart()
    }

    private fun isUserNavigated(): Boolean =
        (parentFragment as? MainPagesFragment)?.userNavigated?.value ?: true

    /**
     * «Продолжить просмотр» вставился над выбранным рядом. Пока пользователь ничего не нажимал,
     * в том же кадре выбираем его, чтобы он встал на место под hero, а не выше него.
     * Иначе выбор остаётся на ряде пользователя, а вставленный ряд скрыт (он выше выбранного).
     */
    private fun onContinueRowInserted() {
        if (initialFocusDone || isUserNavigated()) return
        setSelectedPosition(0, false)
    }

    /**
     * При запуске курсор на первом релизе «Продолжить просмотр», а не на вкладках — даже если ряд
     * загрузился поздно. Ждём, пока пользователь сам не нажал кнопку пульта.
     */
    private fun focusContinueRowOnStart() {
        if (initialFocusDone) return
        val pages = parentFragment as? MainPagesFragment ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val continueReady = combine(
                mainViewModel.rowListData,
                continueViewModel.cardsData,
            ) { rows, cards ->
                rows.firstOrNull() == MainViewModel.CONTINUE_ROW_ID && cards.any { it is LibriaCard }
            }.filter { it }
            val ready = merge(
                continueReady,
                pages.userNavigated.filter { it }.map { false },
            ).first()
            if (ready) {
                // Ряд должен уже стоять первым в адаптере, иначе фокус уйдёт на соседний ряд.
                while ((rowsAdapter.takeIf { it.size() > 0 }?.get(0) as? ListRow)?.id != MainViewModel.CONTINUE_ROW_ID) {
                    delay(16)
                }
            }
            initialFocusDone = true
            if (!ready || isUserNavigated()) {
                pages.revealInitialScreen()
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
