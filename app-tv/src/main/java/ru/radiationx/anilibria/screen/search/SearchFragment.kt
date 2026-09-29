package ru.radiationx.anilibria.screen.search

import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.BrowseFrameLayout
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.VerticalGridPresenter
import androidx.leanback.widget.VerticalGridView
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.CardDiffCallback
import ru.radiationx.anilibria.common.CardItem
import ru.radiationx.anilibria.common.GradientBackgroundManager
import ru.radiationx.anilibria.common.LibriaCard
import ru.radiationx.anilibria.common.LinkCard
import ru.radiationx.anilibria.common.LoadingCard
import ru.radiationx.anilibria.common.fragment.GridFragment
import ru.radiationx.anilibria.databinding.ViewCatalogHeaderBinding
import ru.radiationx.anilibria.ui.presenter.CardPresenterSelector
import ru.radiationx.anilibria.ui.widget.CatalogChipView
import ru.radiationx.anilibria.ui.widget.manager.ExternalProgressManager
import ru.radiationx.quill.inject
import ru.radiationx.shared.ktx.android.subscribeTo
import ru.radiationx.shared_app.di.quillParentViewModel
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

/**
 * «Каталог» — страница главного экрана (вкладка). Под вкладками — чипы фильтров и счётчик,
 * ниже — название и мета выбранной карточки и сетка релизов (6 колонок), прокручиваемая в своей области.
 * ViewModel живут в [ru.radiationx.anilibria.screen.mainpages.MainPagesFragment]:
 * фрагмент страницы пересоздаётся при переключении вкладок, а фильтры и результаты должны сохраняться.
 */
class SearchFragment : GridFragment() {

    private companion object {
        const val COLUMNS = 6

        /** Подгрузка следующей страницы, когда до конца осталось не больше двух рядов. */
        const val LOAD_MORE_THRESHOLD = COLUMNS * 2

        val countFormat = DecimalFormat(
            "#,###",
            DecimalFormatSymbols(Locale("ru")).apply { groupingSeparator = ' ' }
        )

        fun titlesCount(count: Int): String {
            val n = abs(count) % 100
            val m = n % 10
            val word = when {
                n in 11..19 -> "тайтлов"
                m == 1 -> "тайтл"
                m in 2..4 -> "тайтла"
                else -> "тайтлов"
            }
            return "${countFormat.format(count)} $word"
        }
    }

    private enum class State { CONTENT, EMPTY, ERROR }

    private val cardsPresenter = CardPresenterSelector {
        cardsViewModel.onLinkCardBind()
    }
    private val cardsAdapter = ArrayObjectAdapter(cardsPresenter)

    private val progressManager by lazy { ExternalProgressManager() }

    private val backgroundManager by inject<GradientBackgroundManager>()

    private val cardsViewModel by quillParentViewModel<SearchViewModel>()
    private val formViewModel by quillParentViewModel<SearchFormViewModel>()

    private var header: ViewCatalogHeaderBinding? = null
    private var gridView: VerticalGridView? = null
    private var chips: List<CatalogChipView> = emptyList()

    /** Последний чип в фокусе: на него UP из первого ряда сетки. */
    private var lastChip: CatalogChipView? = null

    /** Чип, открывший панель фильтра: фокус на него, когда панель закроется. */
    private var restoreChip: CatalogChipView? = null

    /** После «Сбросить фильтры» / «Повторить» — фокус в сетку, как только появятся карточки. */
    private var focusGridOnData = false

    private var state = State.CONTENT
    private var firstCardKey: Any? = null
    private var positionRestored = false

    private val focusChangeListener = ViewTreeObserver.OnGlobalFocusChangeListener { oldFocus, newFocus ->
        onGlobalFocusChanged(oldFocus, newFocus)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Как ряды главной: увеличение ×1,14 без затемнения остальных карточек.
        gridPresenter = VerticalGridPresenter(FocusHighlight.ZOOM_FACTOR_LARGE, false).apply {
            numberOfColumns = COLUMNS
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycle.addObserver(cardsViewModel)
        viewLifecycleOwner.lifecycle.addObserver(formViewModel)

        // Название и мета карточки — над сеткой, старый блок Leanback внизу не нужен.
        view.findViewById<View>(R.id.shadowDescriptionView)?.isVisible = false
        setupGrid(view)
        setupHeader(view)
        setupFocus(view)

        backgroundManager.clearGradient()
        onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, _ ->
            val position = gridView?.selectedPosition ?: return@OnItemViewSelectedListener
            cardsViewModel.onItemSelected(position, item)
            if (position >= cardsAdapter.size() - LOAD_MORE_THRESHOLD) {
                cardsViewModel.loadMore()
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

        progressManager.rootView = view as ViewGroup
        progressManager.initialDelay = 0L

        subscribeTo(cardsViewModel.progressState) {
            if (it) progressManager.show() else progressManager.hide()
            renderState()
        }
        subscribeTo(cardsViewModel.totalData) {
            header?.catalogCount?.apply {
                text = it?.let { count -> titlesCount(count) }
                visibility = if (it != null) View.VISIBLE else View.INVISIBLE
            }
            renderState()
        }
        subscribeTo(cardsViewModel.selectedInfoData) {
            header?.apply {
                catalogTitle.text = it?.title
                catalogMeta.text = it?.meta
            }
        }
        subscribeTo(cardsViewModel.cardsData) { onCardsChanged(it) }

        mainFragmentAdapter.fragmentHost.notifyDataReady(mainFragmentAdapter)
    }

    override fun onDestroyView() {
        view?.viewTreeObserver?.removeOnGlobalFocusChangeListener(focusChangeListener)
        header = null
        gridView = null
        chips = emptyList()
        lastChip = null
        restoreChip = null
        // Фрагмент может пережить свою view (возврат с релиза): позицию восстановим заново.
        positionRestored = false
        firstCardKey = null
        state = State.CONTENT
        super.onDestroyView()
    }

    /** Сетка 6×(130 dp + 8 dp) от левого поля, первый ряд на 178 dp, прокрутка внутри своей области. */
    private fun setupGrid(view: View) {
        val res = resources
        view.findViewById<View>(R.id.browse_grid_dock).updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = res.getDimensionPixelSize(R.dimen.catalog_grid_top)
            bottomMargin = res.getDimensionPixelSize(R.dimen.catalog_grid_margin_bottom)
        }
        val grid = view.findViewById<VerticalGridView>(androidx.leanback.R.id.browse_grid) ?: return
        gridView = grid
        grid.updateLayoutParams<FrameLayout.LayoutParams> {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            gravity = Gravity.TOP or Gravity.START
        }
        grid.updatePadding(
            left = res.getDimensionPixelSize(R.dimen.main_top_tabs_margin_start),
            top = res.getDimensionPixelSize(R.dimen.catalog_grid_padding_top),
            right = res.getDimensionPixelSize(R.dimen.main_top_logo_margin_end),
            bottom = res.getDimensionPixelSize(R.dimen.catalog_grid_padding_bottom),
        )
        // Клип по краю самой сетки, а не по padding: padding — запас под фокус-зум карточек
        // (первый ряд и первая колонка иначе срезаются). Прокрученный ряд в запас сверху не
        // попадает: он меньше вертикального промежутка между рядами.
        grid.clipToPadding = false
        grid.horizontalSpacing = res.getDimensionPixelSize(R.dimen.catalog_grid_horizontal_spacing)
        grid.verticalSpacing = res.getDimensionPixelSize(R.dimen.catalog_grid_vertical_spacing)
        grid.setGravity(Gravity.START)
        // Выбранный ряд — у верха области (под метой); у конца списка сетка не прокручивается дальше.
        grid.windowAlignment = BaseGridView.WINDOW_ALIGN_BOTH_EDGE
        grid.windowAlignmentOffset = res.getDimensionPixelSize(R.dimen.catalog_grid_padding_top)
        grid.windowAlignmentOffsetPercent = 0f
        grid.itemAlignmentOffset = 0
        grid.itemAlignmentOffsetPercent = 0f
        // Сетка Leanback не выпускает фокус вверх из первого ряда: UP оттуда — на чипы.
        grid.setOnKeyInterceptListener { event ->
            if (event.keyCode != KeyEvent.KEYCODE_DPAD_UP || grid.selectedPosition >= COLUMNS) {
                return@setOnKeyInterceptListener false
            }
            val focused = grid.findFocus() ?: return@setOnKeyInterceptListener false
            if (event.action == KeyEvent.ACTION_DOWN) {
                (lastChip ?: nearestChip(focused)).requestFocus()
            }
            true
        }
    }

    private fun setupHeader(view: View) {
        val frame = view.findViewById<ViewGroup>(R.id.grid_frame)
        val binding = ViewCatalogHeaderBinding.inflate(LayoutInflater.from(frame.context), frame, false)
        frame.addView(binding.root)
        header = binding

        chips = listOf(
            binding.catalogChipGenre,
            binding.catalogChipYear,
            binding.catalogChipSeason,
            binding.catalogChipStatus,
            binding.catalogChipSort,
        )
        binding.catalogChipGenre.setOnClickListener { openFilter(it) { formViewModel.onGenreClick() } }
        binding.catalogChipYear.setOnClickListener { openFilter(it) { formViewModel.onYearClick() } }
        binding.catalogChipSeason.setOnClickListener { openFilter(it) { formViewModel.onSeasonClick() } }
        binding.catalogChipStatus.setOnClickListener { openFilter(it) { formViewModel.onOnlyCompletedClick() } }
        binding.catalogChipSort.setOnClickListener { openFilter(it) { formViewModel.onSortClick() } }

        subscribeTo(formViewModel.genreData) { binding.catalogChipGenre.bind("Жанры", it) }
        subscribeTo(formViewModel.yearData) { binding.catalogChipYear.bind("Годы", it) }
        subscribeTo(formViewModel.seasonData) { binding.catalogChipSeason.bind("Сезон", it) }
        subscribeTo(formViewModel.onlyCompletedData) { binding.catalogChipStatus.bind("Статус", it) }
        subscribeTo(formViewModel.sortData) { binding.catalogChipSort.bind("Сортировка", it) }

        binding.catalogEmptyButton.setOnClickListener {
            focusGridOnData = true
            // Кнопка сейчас пропадёт: фокус заранее на чипы, чтобы он не ушёл из страницы.
            (lastChip ?: chips.first()).requestFocus()
            when (state) {
                State.ERROR -> cardsViewModel.reload()
                else -> formViewModel.onResetClick()
            }
        }
    }

    private fun CatalogChipView.bind(label: String, chip: SearchFilterChip) {
        bind(label, chip.value, chip.active)
    }

    private fun openFilter(chip: View, open: () -> Unit) {
        restoreChip = chip as? CatalogChipView
        open()
    }

    private fun setupFocus(view: View) {
        view.viewTreeObserver.addOnGlobalFocusChangeListener(focusChangeListener)
        val frame = view.findViewById<BrowseFrameLayout>(R.id.grid_frame)
        frame.onFocusSearchListener = BrowseFrameLayout.OnFocusSearchListener { focused, direction ->
            onFocusSearch(focused, direction)
        }
    }

    /** null — дальше решает главный экран (соседний чип, вкладки). */
    private fun onFocusSearch(focused: View?, direction: Int): View? {
        focused ?: return null
        val header = header ?: return null
        val grid = gridView ?: return null
        val chip = chips.firstOrNull { it === focused }
        return when {
            chip != null && direction == View.FOCUS_DOWN -> when {
                header.catalogEmpty.isVisible -> header.catalogEmptyButton
                grid.isVisible && cardsAdapter.size() > 0 -> grid
                else -> focused
            }

            focused === header.catalogEmptyButton -> when (direction) {
                View.FOCUS_UP -> lastChip ?: chips.first()
                else -> focused
            }

            direction == View.FOCUS_UP && grid.isAncestorOf(focused) -> lastChip ?: nearestChip(focused)
            else -> null
        }
    }

    private fun nearestChip(focused: View): View {
        val location = IntArray(2)
        focused.getLocationOnScreen(location)
        val centerX = location[0] + focused.width / 2
        return chips.minByOrNull {
            it.getLocationOnScreen(location)
            abs(location[0] + it.width / 2 - centerX)
        } ?: chips.first()
    }

    private fun onGlobalFocusChanged(oldFocus: View?, newFocus: View?) {
        val chip = chips.firstOrNull { it === newFocus }
        if (chip != null) {
            lastChip = chip
        }
        val restore = restoreChip ?: return
        val pageRoot = parentFragment?.view as? ViewGroup ?: view as? ViewGroup ?: return
        // Панель фильтра закрылась: фокус вернулся на главный экран — ставим его на чип фильтра,
        // а если по фильтрам ничего нет — на «Сбросить фильтры».
        if (newFocus != null && pageRoot.isAncestorOf(newFocus) && (oldFocus == null || !pageRoot.isAncestorOf(oldFocus))) {
            restoreChip = null
            val target = header?.catalogEmptyButton?.takeIf { state != State.CONTENT } ?: restore
            if (newFocus !== target) {
                target.post { target.requestFocus() }
            }
        }
    }

    private fun onCardsChanged(cards: List<CardItem>) {
        val grid = gridView
        val newFirstKey = cards.firstOrNull()?.let { (it as? LibriaCard)?.type ?: it }
        val isError = cards.size == 1 && (cards.first() as? LoadingCard)?.isError == true
        val items = if (isError) emptyList() else cards
        cardsAdapter.setItems(items, CardDiffCallback)

        if (grid != null && items.isNotEmpty()) {
            when {
                // Страница пересоздана (смена вкладки, возврат с релиза): та же карточка.
                !positionRestored -> {
                    positionRestored = true
                    grid.selectedPosition = cardsViewModel.selectedPosition.coerceIn(0, items.lastIndex)
                }
                // Новые результаты (сменились фильтры): к началу.
                newFirstKey != firstCardKey -> grid.selectedPosition = 0
            }
            val position = grid.selectedPosition.coerceIn(0, items.lastIndex)
            cardsViewModel.onItemSelected(position, items[position])
        }
        firstCardKey = newFirstKey
        renderState()

        if (focusGridOnData && items.any { it is LibriaCard }) {
            focusGridOnData = false
            if (chips.any { it.hasFocus() }) {
                grid?.requestFocus()
            }
        }
    }

    private fun renderState() {
        val header = header ?: return
        val cards = cardsViewModel.cardsData.value
        val loading = cardsViewModel.progressState.value
        val newState = when {
            loading -> State.CONTENT
            cards.size == 1 && (cards.first() as? LoadingCard)?.isError == true -> State.ERROR
            cards.isEmpty() && cardsViewModel.totalData.value == 0 -> State.EMPTY
            else -> State.CONTENT
        }
        val hasCards = cards.any { it is LibriaCard }
        val root = view as? ViewGroup
        // Фокус в сетке (или страница в фокусе, но без фокуса у ребёнка) — отдадим его кнопке.
        val gridHadFocus = gridView?.hasFocus() == true || (root?.isFocused == true)
        header.catalogTitle.isVisible = newState == State.CONTENT && hasCards
        header.catalogMeta.isVisible = newState == State.CONTENT && hasCards
        gridView?.isVisible = newState == State.CONTENT

        if (newState == state) return
        val emptyHadFocus = header.catalogEmptyButton.hasFocus()
        state = newState
        when (newState) {
            State.EMPTY -> {
                header.catalogEmptyTitle.text = "По этим фильтрам ничего нет"
                header.catalogEmptyHint.text = "Уберите один из жанров или год"
                header.catalogEmptyButton.text = "Сбросить фильтры"
            }

            State.ERROR -> {
                header.catalogEmptyTitle.text = "Не удалось загрузить"
                header.catalogEmptyHint.text = "Проверьте подключение и попробуйте ещё раз"
                header.catalogEmptyButton.text = "Повторить"
            }

            State.CONTENT -> Unit
        }
        header.catalogEmpty.isVisible = newState != State.CONTENT
        if (newState != State.CONTENT) {
            // Фокус был в сетке — на кнопку; с чипов и из панели фильтра не забираем.
            if (restoreChip == null && gridHadFocus) {
                header.catalogEmptyButton.requestFocus()
            }
        } else if (emptyHadFocus) {
            (lastChip ?: chips.firstOrNull())?.requestFocus()
        }
    }

    private fun ViewGroup.isAncestorOf(child: View): Boolean {
        var parent = child.parent
        while (parent != null) {
            if (parent === this) return true
            parent = parent.parent
        }
        return false
    }
}
