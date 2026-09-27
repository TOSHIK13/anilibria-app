package ru.radiationx.anilibria.screen.search

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.VerticalGridPresenter
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.CardDiffCallback
import ru.radiationx.anilibria.common.GradientBackgroundManager
import ru.radiationx.anilibria.common.LibriaCard
import ru.radiationx.anilibria.common.LinkCard
import ru.radiationx.anilibria.common.LoadingCard
import ru.radiationx.anilibria.common.fragment.GridFragment
import ru.radiationx.anilibria.databinding.ViewSearchControlsBinding
import ru.radiationx.anilibria.ui.presenter.CardPresenterSelector
import ru.radiationx.anilibria.ui.widget.manager.ExternalProgressManager
import ru.radiationx.anilibria.ui.widget.manager.ExternalTextManager
import ru.radiationx.quill.inject
import ru.radiationx.shared.ktx.android.subscribeTo
import ru.radiationx.shared_app.di.quillParentViewModel

/**
 * «Каталог» — страница главного экрана (вкладка). Под вкладками — панель фильтров,
 * ниже — сетка релизов. ViewModel живут в [ru.radiationx.anilibria.screen.mainpages.MainPagesFragment]:
 * фрагмент страницы пересоздаётся при переключении вкладок, а фильтры и результаты должны сохраняться.
 */
class SearchFragment : GridFragment() {

    private val cardsPresenter = CardPresenterSelector {
        cardsViewModel.onLinkCardBind()
    }
    private val cardsAdapter = ArrayObjectAdapter(cardsPresenter)

    private val progressManager by lazy { ExternalProgressManager() }
    private val emptyTextManager by lazy { ExternalTextManager() }

    private val backgroundManager by inject<GradientBackgroundManager>()

    private val cardsViewModel by quillParentViewModel<SearchViewModel>()
    private val formViewModel by quillParentViewModel<SearchFormViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gridPresenter = VerticalGridPresenter().apply {
            numberOfColumns = 6
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycle.addObserver(cardsViewModel)
        viewLifecycleOwner.lifecycle.addObserver(formViewModel)

        setupControls(view)

        backgroundManager.clearGradient()
        onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, _ ->
            when (item) {
                is LibriaCard -> setDescription(item.title, item.description)
                is LinkCard -> setDescription(item.title, "")
                is LoadingCard -> setDescription(item.title, item.description)
                else -> setDescription("", "")
            }
        }

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is LinkCard -> cardsViewModel.onLinkCardClick()
                is LoadingCard -> cardsViewModel.onLoadingCardClick()
                is LibriaCard -> cardsViewModel.onLibriaCardClick(item)
            }
        }

        adapter = cardsAdapter

        val root = view as ViewGroup
        progressManager.rootView = root
        progressManager.initialDelay = 0L

        emptyTextManager.rootView = root
        emptyTextManager.initialDelay = 0L
        emptyTextManager.text = "По данным параметрам ничего не найдено"

        subscribeTo(cardsViewModel.progressState) {
            if (it) progressManager.show() else progressManager.hide()
        }

        subscribeTo(cardsViewModel.cardsData) {
            if (it.isEmpty()) {
                backgroundManager.clearGradient()
                setDescription("", "")
                emptyTextManager.show()
            } else {
                emptyTextManager.hide()
            }
            cardsAdapter.setItems(it, CardDiffCallback)
        }

        mainFragmentAdapter.fragmentHost.notifyDataReady(mainFragmentAdapter)
    }

    /** Панель фильтров под вкладками главного экрана; сетка начинается под ней. */
    private fun setupControls(view: View) {
        val frame = view.findViewById<ViewGroup>(R.id.grid_frame)
        val dock = view.findViewById<View>(R.id.browse_grid_dock)
        val contentTop = resources.getDimensionPixelSize(R.dimen.main_page_content_top)
        val controlsHeight = resources.getDimensionPixelSize(R.dimen.catalog_controls_height)
        val paddingStart = resources.getDimensionPixelSize(R.dimen.main_top_tabs_margin_start)

        val controls = ViewSearchControlsBinding.inflate(LayoutInflater.from(frame.context), frame, false)
        frame.addView(
            controls.root,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                controlsHeight,
                Gravity.TOP or Gravity.START
            ).apply {
                topMargin = contentTop
                marginStart = paddingStart
            }
        )
        dock.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = contentTop + controlsHeight
        }
        // Стиль сетки рассчитан на заголовок поверх неё (большой отступ сверху), здесь он не нужен:
        // остаётся запас только под увеличение карточки в фокусе.
        view.findViewById<View>(androidx.leanback.R.id.browse_grid)?.apply {
            updatePadding(top = resources.getDimensionPixelSize(R.dimen.catalog_grid_padding_top))
        }

        controls.searchTitleYear.setOnClickListener { formViewModel.onYearClick() }
        controls.searchTitleSeason.setOnClickListener { formViewModel.onSeasonClick() }
        controls.searchTitleGenre.setOnClickListener { formViewModel.onGenreClick() }
        controls.searchTitleSort.setOnClickListener { formViewModel.onSortClick() }
        controls.searchTitleComplete.setOnClickListener { formViewModel.onOnlyCompletedClick() }

        subscribeTo(formViewModel.yearData) { controls.searchTitleYear.setValue(it) }
        subscribeTo(formViewModel.seasonData) { controls.searchTitleSeason.setValue(it) }
        subscribeTo(formViewModel.genreData) { controls.searchTitleGenre.setValue(it) }
        subscribeTo(formViewModel.sortData) { controls.searchTitleSort.setValue(it) }
        subscribeTo(formViewModel.onlyCompletedData) { controls.searchTitleComplete.setValue(it) }
    }

    private fun android.widget.TextView.setValue(value: String?) {
        text = value
        isVisible = value != null
    }
}
