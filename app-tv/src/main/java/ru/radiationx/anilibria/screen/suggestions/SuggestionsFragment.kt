package ru.radiationx.anilibria.screen.suggestions

import android.os.Bundle
import android.speech.SpeechRecognizer
import android.view.View
import android.view.ViewGroup
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.core.view.updatePadding
import androidx.lifecycle.ViewModel
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.BaseCardsViewModel
import ru.radiationx.anilibria.common.CardDiffCallback
import ru.radiationx.anilibria.common.GradientBackgroundManager
import ru.radiationx.anilibria.common.LibriaCard
import ru.radiationx.anilibria.common.LinkCard
import ru.radiationx.anilibria.common.LoadingCard
import ru.radiationx.anilibria.common.RowDiffCallback
import ru.radiationx.anilibria.extension.createCardsRowBy
import ru.radiationx.anilibria.screen.details.DetailsViewModel
import ru.radiationx.anilibria.ui.presenter.CardPresenterSelector
import ru.radiationx.anilibria.ui.presenter.cust.CustomListRowPresenter
import ru.radiationx.anilibria.ui.presenter.cust.CustomListRowViewHolder
import ru.radiationx.anilibria.ui.widget.manager.ExternalProgressManager
import ru.radiationx.anilibria.ui.widget.manager.ExternalTextManager
import ru.radiationx.quill.inject
import ru.radiationx.shared.ktx.android.subscribeTo
import ru.radiationx.shared_app.di.quillParentViewModel

/**
 * «Поиск» — страница главного экрана (вкладка). ViewModel живут в
 * [ru.radiationx.anilibria.screen.mainpages.MainPagesFragment], [SuggestionsController] — в
 * [ru.radiationx.anilibria.di.MainPagesModule]. Полный редизайн поиска — отдельный этап.
 */
class SuggestionsFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider,
    BrowseSupportFragment.MainFragmentAdapterProvider {

    private val rowsPresenter by lazy { CustomListRowPresenter() }
    private val rowsAdapter by lazy { ArrayObjectAdapter(rowsPresenter) }

    private val progressManager by lazy { ExternalProgressManager() }
    private val emptyTextManager by lazy { ExternalTextManager() }

    private val rowsViewModel by quillParentViewModel<SuggestionsRowsViewModel>()
    private val resultViewModel by quillParentViewModel<SuggestionsResultViewModel>()
    private val recommendsViewModel by quillParentViewModel<SuggestionsRecommendsViewModel>()

    private val backgroundManager by inject<GradientBackgroundManager>()

    private val selfMainFragmentAdapter by lazy { BrowseSupportFragment.MainFragmentAdapter(this) }

    override fun getMainFragmentAdapter(): BrowseSupportFragment.MainFragmentAdapter<*> {
        return selfMainFragmentAdapter
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        disableAutoStartRecognition()
        super.onCreate(savedInstanceState)
    }

    /**
     * Страница создаётся при каждом переходе на вкладку: без этого Leanback сам включал бы
     * голосовой ввод (и запрос доступа к микрофону). Голос — только по кнопке микрофона.
     */
    private fun disableAutoStartRecognition() {
        runCatching {
            SearchSupportFragment::class.java.getDeclaredField("mAutoStartRecognition").apply {
                isAccessible = true
            }.setBoolean(this, false)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Строка поиска и результаты — под вкладками главного экрана.
        view.updatePadding(top = resources.getDimensionPixelSize(R.dimen.main_page_content_top))

        viewLifecycleOwner.lifecycle.addObserver(rowsViewModel)
        viewLifecycleOwner.lifecycle.addObserver(resultViewModel)
        viewLifecycleOwner.lifecycle.addObserver(recommendsViewModel)

        backgroundManager.clearGradient()

        setSearchResultProvider(this)
        setOnItemViewSelectedListener(ItemViewSelectedListener())
        setOnItemViewClickedListener { _, item, _, row ->
            when (val viewModel = getViewModel((row as ListRow).id)) {
                is BaseCardsViewModel -> when (item) {
                    is LinkCard -> viewModel.onLinkCardClick()
                    is LoadingCard -> viewModel.onLoadingCardClick()
                    is LibriaCard -> viewModel.onLibriaCardClick(item)
                }

                is SuggestionsResultViewModel -> when (item) {
                    is LibriaCard -> resultViewModel.onCardClick(item)
                }
            }
        }

        progressManager.rootView = view as ViewGroup
        progressManager.initialDelay = 0L

        emptyTextManager.rootView = view
        emptyTextManager.initialDelay = 0L
        emptyTextManager.text = "Ничего не найдено"


        subscribeTo(rowsViewModel.emptyResultState) {
            if (it) {
                emptyTextManager.show()
                backgroundManager.clearGradient()
            } else {
                emptyTextManager.hide()
            }
        }

        val rowMap = mutableMapOf<Long, Row>()
        subscribeTo(rowsViewModel.rowListData) { rowList ->
            val rows = rowList.map { rowId ->
                val row = rowMap[rowId] ?: createRowBy(rowId, rowsAdapter, getViewModel(rowId)!!)
                rowMap[rowId] = row
                row
            }
            rowsAdapter.setItems(rows, RowDiffCallback)
        }

        selfMainFragmentAdapter.fragmentHost.notifyViewCreated(selfMainFragmentAdapter)
        selfMainFragmentAdapter.fragmentHost.notifyDataReady(selfMainFragmentAdapter)
    }

    override fun onPause() {
        avoidSpeechRecognitinCrash()
        super.onPause()
    }

    // "destroy" may throw java.lang.IllegalArgumentException: Service not registered: android.speech.SpeechRecognizer$Connection@dabd9b8
    // do all this mehod's work and wrap "destroy" to try catch
    //    private void releaseRecognizer() {
    //        if (null != mSpeechRecognizer) {
    //            mSearchBar.setSpeechRecognizer(null);
    //            mSpeechRecognizer.destroy();
    //            mSpeechRecognizer = null;
    //        }
    //    }
    private fun avoidSpeechRecognitinCrash() {
        val mSpeechRecognizerField =
            SearchSupportFragment::class.java.getDeclaredField("mSpeechRecognizer").apply {
                isAccessible = true
            }
        val mSearchBarField =
            SearchSupportFragment::class.java.getDeclaredField("mSearchBar").apply {
                isAccessible = true
            }
        val currentSpeechRecognizer = mSpeechRecognizerField.get(this)
        if (currentSpeechRecognizer != null) {
            val mSearchBar = mSearchBarField.get(this)
            val setSpeechRecognizerMethod = mSearchBar::class.java.getDeclaredMethod(
                "setSpeechRecognizer",
                SpeechRecognizer::class.java
            ).apply {
                isAccessible = true
            }
            setSpeechRecognizerMethod.invoke(mSearchBar, null)

            val destroyMethod = currentSpeechRecognizer::class.java.getDeclaredMethod(
                "destroy"
            ).apply {
                isAccessible = true
            }
            try {
                destroyMethod.invoke(currentSpeechRecognizer)
            } catch (ignore: IllegalArgumentException) {
                // ignore
            }

            mSpeechRecognizerField.set(this, null)
        }
    }

    private fun getViewModel(rowId: Long): ViewModel? = when (rowId) {
        SuggestionsRowsViewModel.RESULT_ROW_ID -> resultViewModel
        SuggestionsRowsViewModel.RECOMMENDS_ROW_ID -> recommendsViewModel
        else -> null
    }

    private fun createRowBy(
        rowId: Long,
        rowsAdapter: ArrayObjectAdapter,
        viewModel: ViewModel,
    ): Row = when (rowId) {
        DetailsViewModel.RELEASE_ROW_ID -> createHeaderRowBy(
            rowId,
            viewModel as SuggestionsResultViewModel
        )

        else -> createCardsRowBy(rowId, rowsAdapter, viewModel as BaseCardsViewModel)
    }

    private fun createHeaderRowBy(
        rowId: Long,
        viewModel: SuggestionsResultViewModel,
    ): Row {
        val cardsPresenter = CardPresenterSelector(null)
        val cardsAdapter = ArrayObjectAdapter(cardsPresenter)
        val row = ListRow(rowId, HeaderItem("Результат поиска"), cardsAdapter)
        subscribeTo(viewModel.resultData) {
            cardsAdapter.setItems(it, CardDiffCallback)
        }
        subscribeTo(viewModel.progressState) {
            if (it) {
                progressManager.show()
            } else {
                progressManager.hide()
            }
        }
        return row
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        resultViewModel.onQueryChange(query.orEmpty())
        return true
    }

    override fun onQueryTextChange(newQuery: String?): Boolean {
        resultViewModel.onQueryChange(newQuery.orEmpty())
        return true
    }

    override fun getResultsAdapter(): ObjectAdapter {
        return rowsAdapter
    }

    private inner class ItemViewSelectedListener : OnItemViewSelectedListener {
        override fun onItemSelected(
            itemViewHolder: Presenter.ViewHolder?, item: Any?,
            rowViewHolder: RowPresenter.ViewHolder, row: Row,
        ) {
            if (rowViewHolder is CustomListRowViewHolder) {
                when (item) {
                    is LibriaCard -> {
                        rowViewHolder.setDescription(item.title, item.description)
                    }

                    is LinkCard -> {
                        rowViewHolder.setDescription(item.title, "")
                    }

                    is LoadingCard -> {
                        rowViewHolder.setDescription(item.title, item.description)
                    }

                    else -> {
                        rowViewHolder.setDescription("", "")
                    }
                }
            }

        }
    }
}