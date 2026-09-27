package ru.radiationx.anilibria.screen.suggestions

import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.DiffCallback
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.ItemBridgeAdapter
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.BaseCardsViewModel
import ru.radiationx.anilibria.common.CardItem
import ru.radiationx.anilibria.common.GradientBackgroundManager
import ru.radiationx.anilibria.common.LibriaCard
import ru.radiationx.anilibria.databinding.FragmentSuggestionsBinding
import ru.radiationx.anilibria.screen.suggestions.SuggestionsResultViewModel.State
import ru.radiationx.anilibria.ui.util.clearBlur
import ru.radiationx.anilibria.ui.util.showBlurred
import ru.radiationx.data.entity.domain.release.GenreItem
import ru.radiationx.quill.inject
import ru.radiationx.shared.ktx.android.subscribeTo
import ru.radiationx.shared_app.di.quillParentViewModel

/**
 * «Поиск» — страница главного экрана (вкладка), макет f-s0/f-s1/f-s2: микрофон и своя строка поиска
 * (OK — системная клавиатура), ниже прокручиваемые блоки. До ввода — «Недавние запросы», «Жанры»,
 * «Недавно открытые», «Рекомендации», «Популярное»; с запросом — «Найдено: N» с подписанными
 * постерами и строка «Каталог»; ничего не нашлось — сообщение и «Популярное».
 * Фокус: вкладка → строка поиска → блоки сверху вниз; LEFT со строки — микрофон.
 * ViewModel живут в [ru.radiationx.anilibria.screen.mainpages.MainPagesFragment].
 */
class SuggestionsFragment : Fragment(R.layout.fragment_suggestions),
    BrowseSupportFragment.MainFragmentAdapterProvider {

    private companion object {
        const val HEADER_COLOR = 0xEBFFFFFF.toInt()
        const val CHIPS_HEADER_COLOR = 0x9EFFFFFF.toInt()
        const val SUBTITLE_COLOR = 0x99FFFFFF.toInt()
        const val LINK_COLOR = 0x9EFFFFFF.toInt()
        const val FOCUSED_TEXT_COLOR = 0xFF111111.toInt()
        const val BLUR_RADIUS_DP = 20f
        const val BLUR_BRIGHTNESS = 0.5f
        const val BLUR_SCALE = 1.15f
        const val FADE_MS = 250L
    }

    private enum class SectionId { RECENT, GENRES, HISTORY, HISTORY_RECOMMENDS, RESULTS, EMPTY, POPULAR, CATALOG }

    private enum class Mode { HOME, RESULTS, EMPTY }

    /** Блок страницы: [target] — куда ставить фокус (ряд постеров, чипы, ссылка), null — без фокуса. */
    private class Section(
        val id: SectionId,
        val view: LinearLayout,
        val target: View?,
        val grid: HorizontalGridView? = null,
        val adapter: ArrayObjectAdapter? = null,
        val chips: SuggestionsChipsLayout? = null,
        val title: TextView? = null,
    ) {
        var hasContent = false
    }

    private val selfMainFragmentAdapter by lazy { BrowseSupportFragment.MainFragmentAdapter(this) }

    override fun getMainFragmentAdapter(): BrowseSupportFragment.MainFragmentAdapter<*> {
        return selfMainFragmentAdapter
    }

    private val resultViewModel by quillParentViewModel<SuggestionsResultViewModel>()
    private val chipsViewModel by quillParentViewModel<SuggestionsChipsViewModel>()
    private val historyViewModel by quillParentViewModel<SuggestionsHistoryViewModel>()
    private val historyRecommendsViewModel by quillParentViewModel<SuggestionsHistoryRecommendsViewModel>()
    private val popularViewModel by quillParentViewModel<SuggestionsRecommendsViewModel>()

    private val backgroundManager by inject<GradientBackgroundManager>()
    private val queriesStorage by inject<SearchQueriesStorage>()

    private val voiceInput = SuggestionsVoiceInput(
        fragment = this,
        storage = { queriesStorage },
        onListening = { binding?.suggestionsMic?.isActivated = it },
        onResult = { text -> submitQuery(text) },
    )

    private var binding: FragmentSuggestionsBinding? = null
    private var sections: List<Section> = emptyList()
    private var mode = Mode.HOME

    /** После OK на недавнем запросе / «Найти» на клавиатуре / голоса — фокус на результаты. */
    private var focusResultsOnData = false

    /** Блок и карточка в фокусе при уходе со страницы (на релиз) — вернуть фокус туда. */
    private var restoreSection: SectionId? = null
    private var openedFromSection: SectionId? = null
    private val gridPositions = mutableMapOf<SectionId, Int>()
    private var blurShown = false

    private val focusChangeListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        onGlobalFocusChanged(newFocus)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentSuggestionsBinding.bind(view)
        this.binding = binding

        viewLifecycleOwner.lifecycle.addObserver(resultViewModel)
        viewLifecycleOwner.lifecycle.addObserver(chipsViewModel)
        viewLifecycleOwner.lifecycle.addObserver(historyViewModel)
        viewLifecycleOwner.lifecycle.addObserver(historyRecommendsViewModel)
        viewLifecycleOwner.lifecycle.addObserver(popularViewModel)

        backgroundManager.clearGradient()
        blurShown = false

        binding.root.onFocusSearch = ::onFocusSearch
        binding.root.onEntryFocus = ::entryFocusTarget
        view.viewTreeObserver.addOnGlobalFocusChangeListener(focusChangeListener)

        setupMic(binding)
        setupInput(binding)
        buildSections(binding)

        subscribeTo(chipsViewModel.recentQueriesData) { bindRecent(it) }
        subscribeTo(chipsViewModel.genresData) { bindGenres(it) }
        subscribeCards(SectionId.HISTORY, historyViewModel)
        subscribeCards(SectionId.HISTORY_RECOMMENDS, historyRecommendsViewModel)
        subscribeCards(SectionId.POPULAR, popularViewModel)
        subscribeTo(resultViewModel.state) { render(it) }

        selfMainFragmentAdapter.fragmentHost.notifyViewCreated(selfMainFragmentAdapter)
        selfMainFragmentAdapter.fragmentHost.notifyDataReady(selfMainFragmentAdapter)
    }

    override fun onResume() {
        super.onResume()
        openedFromSection = null
    }

    override fun onPause() {
        voiceInput.stop()
        super.onPause()
    }

    override fun onDestroyView() {
        val binding = binding
        if (binding != null) {
            hideKeyboard(binding)
            binding.root.viewTreeObserver.removeOnGlobalFocusChangeListener(focusChangeListener)
            // Ушли на релиз из блока — при возврате фокус туда же.
            restoreSection = openedFromSection
            openedFromSection = null
            sections.forEach { section ->
                section.grid?.also { gridPositions[section.id] = it.selectedPosition }
            }
        }
        voiceInput.stop()
        sections = emptyList()
        this.binding = null
        super.onDestroyView()
    }

    // region Строка поиска и микрофон

    private fun setupMic(binding: FragmentSuggestionsBinding) {
        val mic = binding.suggestionsMic
        mic.isVisible = voiceInput.isAvailable(mic.context)
        ImageViewCompat.setImageTintList(
            mic,
            ContextCompat.getColorStateList(mic.context, R.color.search_mic_icon)
        )
        mic.setOnClickListener { voiceInput.onMicClick() }
    }

    private fun setupInput(binding: FragmentSuggestionsBinding) {
        val input = binding.suggestionsInput
        input.setText(resultViewModel.queryData.value)
        input.setSelection(input.length())
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                resultViewModel.onQueryChange(s?.toString().orEmpty())
            }
        })
        input.setOnFocusChangeListener { _, hasFocus ->
            binding.suggestionsKeyboardHint.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
        }
        input.setOnKeyListener { _, keyCode, event -> onInputKey(binding, keyCode, event) }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard(binding)
                focusResultsOnData = true
                focusResultsIfReady()
                true
            } else {
                false
            }
        }
        input.onBackWithKeyboard = {
            hideKeyboard(binding)
            // Есть результаты — в них, иначе на вкладку (как BACK из контента).
            val results = visibleSection(SectionId.RESULTS)
            if (results?.grid != null) {
                results.grid.requestFocus()
            } else {
                input.focusSearch(View.FOCUS_UP)?.requestFocus(View.FOCUS_UP)
            }
        }
    }

    private fun onInputKey(binding: FragmentSuggestionsBinding, keyCode: Int, event: KeyEvent): Boolean {
        val input = binding.suggestionsInput
        val down = event.action == KeyEvent.ACTION_DOWN
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.action == KeyEvent.ACTION_UP) showKeyboard(binding)
                true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (down) binding.suggestionsMic.takeIf { it.isVisible }?.requestFocus()
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> true
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (down) {
                    hideKeyboard(binding)
                    sectionEntry(input, View.FOCUS_DOWN, visibleSections())?.requestFocus(View.FOCUS_DOWN)
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                if (down) input.focusSearch(View.FOCUS_UP)?.requestFocus(View.FOCUS_UP)
                true
            }

            else -> false
        }
    }

    private fun showKeyboard(binding: FragmentSuggestionsBinding) {
        val input = binding.suggestionsInput
        val imm = input.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        input.keyboardShown = true
        imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard(binding: FragmentSuggestionsBinding) {
        val input = binding.suggestionsInput
        if (!input.isKeyboardVisible()) return
        input.keyboardShown = false
        val imm = input.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
    }

    /** Недавний запрос или голос: текст в строку поиска, поиск, фокус — на результаты. */
    private fun submitQuery(text: String) {
        val binding = binding ?: return
        val input = binding.suggestionsInput
        focusResultsOnData = true
        input.setText(text)
        input.setSelection(input.length())
        focusResultsIfReady()
    }

    // endregion

    // region Блоки

    private fun buildSections(binding: FragmentSuggestionsBinding) {
        val container = binding.suggestionsSections
        container.removeAllViews()
        sections = SectionId.values().map { id -> createSection(container, id) }
        sections.forEachIndexed { index, section ->
            section.view.isVisible = false
            container.addView(section.view, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (index > 0) topMargin = resources.getDimensionPixelSize(R.dimen.search_section_gap)
            })
        }
    }

    private fun createSection(container: ViewGroup, id: SectionId): Section {
        val context = container.context
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        return when (id) {
            SectionId.RECENT, SectionId.GENRES -> {
                val title = createHeader(context, if (id == SectionId.RECENT) "Недавние запросы" else "Жанры", small = true)
                view.addView(title)
                val chips = SuggestionsChipsLayout(context).apply {
                    maxLines = 2
                    horizontalGap = resources.getDimensionPixelSize(R.dimen.search_chip_gap)
                    verticalGap = resources.getDimensionPixelSize(R.dimen.search_chip_gap)
                    clipChildren = false
                    setPadding(pageStart(), 0, pageStart(), 0)
                }
                view.addView(chips, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.search_chips_top) })
                Section(id, view, chips, chips = chips, title = title)
            }

            SectionId.HISTORY, SectionId.HISTORY_RECOMMENDS, SectionId.POPULAR, SectionId.RESULTS -> {
                val title = createHeader(context, "", small = false)
                view.addView(title)
                val presenter = SuggestionsPosterPresenter(
                    captions = id == SectionId.RESULTS,
                    onClick = { onPosterClick(id, it.card) },
                    onFocus = { onPosterFocus(id, it) },
                )
                val adapter = ArrayObjectAdapter(presenter)
                val grid = createGrid(context, adapter, presenter.itemHeight(container))
                view.addView(grid, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
                Section(id, view, grid, grid = grid, adapter = adapter, title = title)
            }

            SectionId.EMPTY -> {
                val title = createHeader(context, "", small = false)
                view.addView(title)
                view.addView(TextView(context).apply {
                    text = "Проверьте написание или поищите по английскому названию"
                    includeFontPadding = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(SUBTITLE_COLOR)
                    setPadding(pageStart(), dp(8f), pageStart(), 0)
                })
                Section(id, view, null, title = title)
            }

            SectionId.CATALOG -> {
                val link = TextView(context).apply {
                    isFocusable = true
                    gravity = Gravity.CENTER_VERTICAL
                    includeFontPadding = false
                    setBackgroundResource(R.drawable.bg_search_link)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding(dp(14f), 0, dp(14f), 0)
                    text = catalogLinkText(false)
                    setOnFocusChangeListener { v, hasFocus -> (v as TextView).text = catalogLinkText(hasFocus) }
                    setOnClickListener { chipsViewModel.onCatalogClick() }
                }
                view.addView(link, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(36f)
                ).apply { marginStart = pageStart() - dp(14f) })
                Section(id, view, link).apply { hasContent = true }
            }
        }
    }

    private fun createHeader(context: Context, text: String, small: Boolean) = TextView(context).apply {
        this.text = text
        includeFontPadding = false
        maxLines = 1
        if (small) {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(CHIPS_HEADER_COLOR)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        } else {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(HEADER_COLOR)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        setPadding(pageStart(), 0, pageStart(), 0)
    }

    private fun createGrid(context: Context, adapter: ArrayObjectAdapter, itemHeight: Int): HorizontalGridView {
        val zoomPadding = resources.getDimensionPixelSize(R.dimen.search_row_zoom_padding)
        return HorizontalGridView(context).apply {
            this.adapter = ItemBridgeAdapter(adapter)
            setRowHeight(itemHeight)
            horizontalSpacing = dp(8f)
            clipChildren = false
            clipToPadding = false
            setPadding(pageStart(), zoomPadding, pageStart(), zoomPadding)
        }
    }

    private fun createChip(context: Context, text: String, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        isFocusable = true
        gravity = Gravity.CENTER
        maxLines = 1
        includeFontPadding = false
        setBackgroundResource(R.drawable.bg_search_chip)
        setTextColor(ContextCompat.getColorStateList(context, R.color.search_chip_text))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        val padding = resources.getDimensionPixelSize(R.dimen.search_chip_padding_horizontal)
        setPadding(padding, 0, padding, 0)
        height = resources.getDimensionPixelSize(R.dimen.search_chip_height)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            resources.getDimensionPixelSize(R.dimen.search_chip_height)
        )
        setOnClickListener { onClick() }
    }

    private fun bindRecent(queries: List<String>) {
        bindChips(SectionId.RECENT, queries.map { query -> query to { submitQuery(query) } })
    }

    private fun bindGenres(genres: List<GenreItem>) {
        bindChips(SectionId.GENRES, genres.map { genre -> genre.title to { chipsViewModel.onGenreClick(genre) } })
    }

    private fun bindChips(id: SectionId, items: List<Pair<String, () -> Unit>>) {
        val section = section(id) ?: return
        val chips = section.chips ?: return
        val hadFocus = chips.hasFocus()
        chips.removeAllViews()
        items.forEach { (text, onClick) -> chips.addView(createChip(chips.context, text, onClick)) }
        section.hasContent = items.isNotEmpty()
        updateSections()
        if (hadFocus && !chips.hasFocus()) {
            (chips.getChildAt(0)?.takeIf { section.view.isVisible } ?: binding?.suggestionsInput)?.requestFocus()
        }
    }

    private fun subscribeCards(id: SectionId, viewModel: BaseCardsViewModel) {
        subscribeTo(viewModel.rowTitle) { title ->
            section(id)?.title?.text = title.ifEmpty { viewModel.defaultTitle }
        }
        subscribeTo(viewModel.cardsData) { cards -> bindCards(id, cards.toPosterItems()) }
    }

    private fun List<CardItem>.toPosterItems() = filterIsInstance<LibriaCard>().map { SuggestionsPosterItem(it) }

    private fun bindCards(id: SectionId, items: List<SuggestionsPosterItem>) {
        val section = section(id) ?: return
        val grid = section.grid ?: return
        section.adapter?.setItems(items, PosterDiff)
        section.hasContent = items.isNotEmpty()
        gridPositions.remove(id)?.also { position ->
            if (items.isNotEmpty()) grid.selectedPosition = position.coerceIn(0, items.lastIndex)
        }
        updateSections()
        restoreFocusIfNeeded()
    }

    private fun render(state: State) {
        val binding = binding ?: return
        binding.suggestionsProgress.isVisible = state is State.Loading
        when (state) {
            State.Idle -> mode = Mode.HOME
            is State.Loading -> Unit
            is State.Content -> {
                val results = section(SectionId.RESULTS)
                if (state.items.isEmpty()) {
                    mode = Mode.EMPTY
                    section(SectionId.EMPTY)?.title?.text = "Ничего не нашлось по «${state.query}»"
                } else {
                    mode = Mode.RESULTS
                    results?.title?.text = resultsTitle(state.items.size, state.query)
                }
                if (results != null) {
                    val sameItems = results.adapter?.let { adapter ->
                        adapter.size() == state.items.size &&
                                state.items.indices.all { (adapter.get(it) as SuggestionsPosterItem) == state.items[it] }
                    } ?: false
                    results.adapter?.setItems(state.items, PosterDiff)
                    results.hasContent = state.items.isNotEmpty()
                    // Новый запрос — с первой карточки.
                    if (!sameItems && state.items.isNotEmpty() && !gridPositions.containsKey(SectionId.RESULTS)) {
                        results.grid?.selectedPosition = 0
                    }
                    gridPositions.remove(SectionId.RESULTS)?.also { position ->
                        if (state.items.isNotEmpty()) {
                            results.grid?.selectedPosition = position.coerceIn(0, state.items.lastIndex)
                        }
                    }
                }
            }

            is State.Error -> {
                mode = Mode.EMPTY
                section(SectionId.EMPTY)?.title?.text = "Не удалось выполнить поиск по «${state.query}»"
            }
        }
        updateSections()
        if (state !is State.Loading) {
            focusResultsIfReady()
            restoreFocusIfNeeded()
        }
    }

    private fun isSectionShown(section: Section): Boolean = when (mode) {
        Mode.HOME -> section.hasContent && section.id in setOf(
            SectionId.RECENT,
            SectionId.GENRES,
            SectionId.HISTORY,
            SectionId.HISTORY_RECOMMENDS,
            SectionId.POPULAR,
        )

        Mode.RESULTS -> section.hasContent && (section.id == SectionId.RESULTS || section.id == SectionId.CATALOG)
        Mode.EMPTY -> section.id == SectionId.EMPTY || section.id == SectionId.CATALOG ||
                (section.id == SectionId.POPULAR && section.hasContent)
    }

    private fun updateSections() {
        val binding = binding ?: return
        val focusedSection = sections.firstOrNull { it.view.hasFocus() }
        var firstVisible = true
        sections.forEach { section ->
            val shown = isSectionShown(section)
            section.view.isVisible = shown
            (section.view.layoutParams as? LinearLayout.LayoutParams)?.also { params ->
                val margin = if (firstVisible) 0 else resources.getDimensionPixelSize(R.dimen.search_section_gap)
                if (params.topMargin != margin) {
                    params.topMargin = margin
                    section.view.layoutParams = params
                }
            }
            if (shown) firstVisible = false
        }
        // Блок в фокусе пропал (например, пришли результаты) — фокус на результаты или строку поиска.
        if (focusedSection != null && !focusedSection.view.isVisible) {
            val target = visibleSection(SectionId.RESULTS)?.grid?.takeIf { focusResultsOnData }
                ?: binding.suggestionsInput
            focusResultsOnData = false
            target.requestFocus()
        }
        if (mode != Mode.RESULTS) hideBlur()
    }

    private fun focusResultsIfReady() {
        if (!focusResultsOnData) return
        val binding = binding ?: return
        val state = resultViewModel.state.value
        when {
            state is State.Loading -> return
            state is State.Content && state.items.isNotEmpty() -> {
                val grid = visibleSection(SectionId.RESULTS)?.grid ?: return
                focusResultsOnData = false
                if (binding.root.hasFocus()) grid.requestFocus()
            }

            else -> focusResultsOnData = false
        }
    }

    private fun restoreFocusIfNeeded() {
        val id = restoreSection ?: return
        val binding = binding ?: return
        val section = visibleSection(id) ?: return
        val target = section.target ?: return
        if (section.grid != null && (section.adapter?.size() ?: 0) == 0) return
        // Фокус ещё не пришёл на страницу — его поставит entryFocusTarget.
        if (!binding.suggestionsInput.isFocused) return
        restoreSection = null
        target.requestFocus()
    }

    private fun onPosterClick(id: SectionId, card: LibriaCard) {
        openedFromSection = id
        when (id) {
            SectionId.RESULTS -> resultViewModel.onCardClick(card)
            SectionId.HISTORY -> historyViewModel.onLibriaCardClick(card)
            SectionId.HISTORY_RECOMMENDS -> historyRecommendsViewModel.onLibriaCardClick(card)
            else -> popularViewModel.onLibriaCardClick(card)
        }
    }

    private fun onPosterFocus(id: SectionId, item: SuggestionsPosterItem) {
        if (id == SectionId.RESULTS) showBlur(item.card.image) else hideBlur()
    }

    private fun resultsTitle(count: Int, query: String): CharSequence = SpannableStringBuilder().apply {
        append("Найдено: $count")
        val start = length
        append("  по запросу «$query»")
        setSpan(AbsoluteSizeSpan(13, true), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(ForegroundColorSpan(SUBTITLE_COLOR), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(TypefaceSpan("sans-serif"), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun catalogLinkText(focused: Boolean): CharSequence = SpannableStringBuilder().apply {
        append("Нет нужного? Откройте ")
        val start = length
        append("Каталог")
        val end = length
        append(" с фильтрами")
        setSpan(ForegroundColorSpan(if (focused) FOCUSED_TEXT_COLOR else LINK_COLOR), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(ForegroundColorSpan(if (focused) FOCUSED_TEXT_COLOR else 0xFFFFFFFF.toInt()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(TypefaceSpan("sans-serif-medium"), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    // endregion

    // region Фокус и прокрутка

    private fun section(id: SectionId): Section? = sections.firstOrNull { it.id == id }

    private fun visibleSection(id: SectionId): Section? = section(id)?.takeIf { it.view.isVisible }

    private fun visibleSections(): List<Section> = sections.filter { it.view.isVisible && it.target != null }

    /** Вход в страницу снаружи: после возврата с релиза — в прежний блок, иначе на строку поиска. */
    private fun entryFocusTarget(): View? {
        val binding = binding ?: return null
        val restore = restoreSection?.let { visibleSection(it) }
        if (restore?.target != null && (restore.grid == null || (restore.adapter?.size() ?: 0) > 0)) {
            restoreSection = null
            return restore.target
        }
        return binding.suggestionsInput
    }

    /** null — дальше решает главный экран (вкладки). */
    private fun onFocusSearch(focused: View, direction: Int): View? {
        val binding = binding ?: return null
        val input = binding.suggestionsInput
        val mic = binding.suggestionsMic
        if (focused === mic) {
            return when (direction) {
                View.FOCUS_RIGHT -> input
                View.FOCUS_LEFT -> mic
                View.FOCUS_DOWN -> sectionEntry(mic, direction, visibleSections()) ?: mic
                else -> null
            }
        }
        if (focused === input) {
            return when (direction) {
                View.FOCUS_LEFT -> mic.takeIf { it.isVisible } ?: input
                View.FOCUS_RIGHT -> input
                View.FOCUS_DOWN -> sectionEntry(input, direction, visibleSections()) ?: input
                else -> null
            }
        }
        val visible = visibleSections()
        val index = visible.indexOfFirst { it.view.isAncestorOf(focused) }
        if (index < 0) return null
        val section = visible[index]
        FocusFinder.getInstance().findNextFocus(section.view, focused, direction)?.also { return it }
        return when (direction) {
            View.FOCUS_UP -> {
                if (index == 0) input else sectionEntry(focused, direction, visible.subList(0, index).reversed())
            }

            View.FOCUS_DOWN -> sectionEntry(focused, direction, visible.drop(index + 1)) ?: focused
            else -> focused
        }
    }

    /** Первая цель фокуса в [candidates]: ряд постеров целиком (он помнит карточку), ближайший чип. */
    private fun sectionEntry(from: View, direction: Int, candidates: List<Section>): View? {
        val binding = binding ?: return null
        val section = candidates.firstOrNull() ?: return null
        val chips = section.chips ?: return section.target
        val rect = Rect()
        from.getDrawingRect(rect)
        binding.root.offsetDescendantRectToMyCoords(from, rect)
        binding.root.offsetRectIntoDescendantCoords(chips, rect)
        return FocusFinder.getInstance().findNextFocusFromRect(chips, rect, direction)
            ?: chips.getChildAt(0)
    }

    private fun onGlobalFocusChanged(newFocus: View?) {
        val binding = binding ?: return
        newFocus ?: return
        if (!binding.root.isAncestorOf(newFocus)) return
        val section = sections.firstOrNull { it.view.isVisible && it.view.isAncestorOf(newFocus) }
        if (section == null) {
            binding.suggestionsScroll.smoothScrollTo(0, 0)
            hideBlur()
            return
        }
        if (section.id != SectionId.RESULTS) hideBlur()
        scrollToSection(section)
    }

    /** Блок в фокусе — целиком на экране; первый блок — к началу. */
    private fun scrollToSection(section: Section) {
        val binding = binding ?: return
        val scroll = binding.suggestionsScroll
        val container = binding.suggestionsSections
        val pad = container.paddingTop
        var target = scroll.scrollY
        if (section.view.bottom + pad > target + scroll.height) {
            target = section.view.bottom + pad - scroll.height
        }
        if (section.view.top - pad < target) {
            target = section.view.top - pad
        }
        val max = (container.height - scroll.height).coerceAtLeast(0)
        scroll.smoothScrollTo(0, target.coerceIn(0, max))
    }

    private fun showBlur(url: String) {
        val binding = binding ?: return
        val image = binding.suggestionsBackground
        image.scaleX = BLUR_SCALE
        image.scaleY = BLUR_SCALE
        image.showBlurred(url, BLUR_RADIUS_DP, BLUR_BRIGHTNESS)
        if (!blurShown) {
            blurShown = true
            image.animate().alpha(1f).setDuration(FADE_MS).start()
            binding.suggestionsScrim.animate().alpha(1f).setDuration(FADE_MS).start()
        }
    }

    private fun hideBlur() {
        if (!blurShown) return
        val binding = binding ?: return
        blurShown = false
        val image = binding.suggestionsBackground
        image.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            if (!blurShown) image.clearBlur()
        }.start()
        binding.suggestionsScrim.animate().alpha(0f).setDuration(FADE_MS).start()
    }

    // endregion

    private fun pageStart(): Int = resources.getDimensionPixelSize(R.dimen.main_top_tabs_margin_start)

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    private fun View.isAncestorOf(child: View): Boolean {
        if (this === child) return true
        var parent = child.parent
        while (parent != null) {
            if (parent === this) return true
            parent = parent.parent
        }
        return false
    }

    private object PosterDiff : DiffCallback<SuggestionsPosterItem>() {
        override fun areItemsTheSame(oldItem: SuggestionsPosterItem, newItem: SuggestionsPosterItem): Boolean =
            oldItem.card.type == newItem.card.type

        override fun areContentsTheSame(oldItem: SuggestionsPosterItem, newItem: SuggestionsPosterItem): Boolean =
            oldItem == newItem
    }
}
