package ru.radiationx.anilibria.screen.mainpages

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.commitNow
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.BrowseFrameLayout
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.PageRow
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.ui.widget.MainHeroView
import ru.radiationx.anilibria.ui.widget.TopTabsView
import ru.radiationx.quill.viewModel
import ru.radiationx.shared.ktx.android.getCompatColor
import ru.radiationx.shared.ktx.android.subscribeTo

class MainPagesFragment : BrowseSupportFragment() {

    private companion object {
        var initialRevealHandled = false
        const val INITIAL_REVEAL_TIMEOUT_MS = 1_500L
        const val INITIAL_REVEAL_FADE_MS = 150L

        const val CONTENT_FOCUS_ATTEMPTS = 20
        const val CONTENT_FOCUS_ATTEMPT_DELAY_MS = 25L
    }

    private val menuPresenter by lazy { ListRowPresenter() }
    private val menuAdapter by lazy { ArrayObjectAdapter(menuPresenter) }
    private var lastSelectedPosition = -1
    private val fragmentFactory by lazy { MainPagesFragmentFactory() }

    private val viewModel by viewModel<MainPagesViewModel>()

    /** Общий hero страниц: карточки выбирают сами страницы (quillParentViewModel). */
    private val heroViewModel by viewModel<MainHeroViewModel>()
    private var heroView: MainHeroView? = null

    /** Холодный старт: «Главная» скрыта, пока курсор не встанет на «Продолжить просмотр». */
    private var revealPending = false
    private var revealFallbackJob: Job? = null

    private var topTabs: TopTabsView? = null
    private var contentFocusJob: Job? = null

    /** Подменённая тема на время [BrowseSupportFragment.onCreate], см. [onCreate]. */
    private var createContext: Context? = null

    /**
     * Пользователь уже нажимал кнопки пульта: стартовый фокус на «Продолжить просмотр»
     * (он может прийти поздно) больше не перехватываем.
     */
    val userNavigated = MutableStateFlow(false)

    fun onUserKey(event: KeyEvent) {
        if (event.action == KeyEvent.ACTION_DOWN) userNavigated.value = true
    }

    private val currentPage: Int
        get() = lastSelectedPosition.coerceAtLeast(0)

    override fun getContext(): Context? = createContext ?: super.getContext()

    override fun onCreate(savedInstanceState: Bundle?) {
        // BrowseSupportFragment читает browseRowsMarginTop из темы getContext() в onCreate.
        // Отступ рядов под вкладки задаём только здесь, не трогая другие Browse-экраны.
        createContext = ContextThemeWrapper(requireActivity(), R.style.ThemeOverlay_MainPages)
        try {
            super.onCreate(savedInstanceState)
        } finally {
            createContext = null
        }
        mainFragmentRegistry.registerFragment(PageRow::class.java, fragmentFactory)
        setupUi()
        showMenu()
    }

    private fun setupUi() {
        // HEADERS_DISABLED отключает PageRow в Leanback, поэтому меню только скрыто,
        // а выход в него перекрыт в onFocusSearch.
        headersState = HEADERS_HIDDEN
        isHeadersTransitionOnBackEnabled = false
        prepareEntranceTransition()
        startEntranceTransition()
    }

    override fun onInflateTitleView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.view_top_tabs, parent, false)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {

        if (savedInstanceState == null) {
            childFragmentManager.findFragmentById(androidx.leanback.R.id.scale_frame)?.also {
                childFragmentManager.commitNow {
                    remove(it)
                }
            }
        }
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycle.addObserver(viewModel)
        viewLifecycleOwner.lifecycle.addObserver(heroViewModel)
        setupHero(view)

        if (!initialRevealHandled && savedInstanceState == null) {
            initialRevealHandled = true
            revealPending = true
            view.alpha = 0f
            revealFallbackJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(INITIAL_REVEAL_TIMEOUT_MS)
                revealInitialScreen()
            }
        }

        topTabs = (titleView as? TopTabsView)?.also { tabs ->
            tabs.setTabs(MainPagesFragmentFactory.tabIds.map { MainPagesFragmentFactory.variant1.getValue(it) })
            tabs.setSelectedTab(tabIndexOfPage(currentPage))
            tabs.onTabClickListener = ::onTabClicked
            tabs.onTabKeyListener = ::onUserKey
            ImageViewCompat.setImageTintList(
                tabs.logoView,
                ColorStateList.valueOf(tabs.context.getCompatColor(R.color.dark_contrast_icon))
            )
        }

        // Страницы просят открыть вкладку (например, кнопка поиска в «Каталоге» → «Поиск»).
        subscribeTo(viewModel.openTabEvent) { tabId ->
            onTabClicked(MainPagesFragmentFactory.tabIds.indexOf(tabId))
        }

        subscribeTo(viewModel.hasUpdatesData) {
            val alert = if (it) "Обновление" else null
            topTabs?.setAlert(alert) { viewModel.onAppUpdateClick() }
        }

        // Меню Leanback скрыто навсегда: убираем его панель, иначе слева остаётся тёмная полоса.
        view.findViewById<View>(androidx.leanback.R.id.browse_headers_dock)?.visibility = View.GONE

        setupFocusSearch(view)
        setupBackHandling()
    }

    override fun onDestroyView() {
        contentFocusJob?.cancel()
        topTabs = null
        heroView = null
        super.onDestroyView()
    }

    /** Hero под рядами и вкладками: первым ребёнком корня Browse, фон экрана — его цвет. */
    private fun setupHero(view: View) {
        val root = view as? ViewGroup ?: return
        val hero = MainHeroView(root.context)
        root.addView(
            hero,
            0,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        heroView = hero
        updateHeroVisibility()
        subscribeTo(heroViewModel.heroData) { hero.bind(it) }
    }

    private fun updateHeroVisibility() {
        heroView?.setContentVisible(!isFullPage(currentPage))
    }

    /** Страница со своей вёрсткой (без hero): вкладки над ней не прячутся. */
    private fun isFullPage(page: Int): Boolean {
        val pageId = MainPagesFragmentFactory.ids.getOrNull(page)
        return pageId in MainPagesFragmentFactory.fullPageIds
    }

    /**
     * Пока фокус на вкладках, панель не прячем: иначе фокус теряется и Leanback уводит его в контент.
     * Leanback прячет её, когда выбор страницы уходит с первого ряда (например, ряд вставился сверху
     * при загрузке) — в этом случае возвращаем страницу к первому ряду.
     */
    override fun showTitle(show: Boolean) {
        if (!show && isFullPage(currentPage)) {
            super.showTitle(true)
            return
        }
        if (!show && topTabs?.hasFocus() == true) {
            super.showTitle(true)
            view?.post {
                if (topTabs?.hasFocus() == true) {
                    (mainFragment as? RowsSupportFragment)?.setSelectedPosition(0, false)
                }
            }
            return
        }
        super.showTitle(show)
    }

    /** Стартовый фокус на контенте «Главной» ([select] выбирает карточку), затем показ экрана. */
    fun focusContentOnStart(select: () -> Unit) {
        if (currentPage == 0) {
            select()
            val content = mainFragment?.view
            if (content != null && !content.hasFocus()) {
                content.requestFocus()
            }
        }
        // Показываем кадром позже, когда фокус уже на месте.
        view?.post { revealInitialScreen() }
    }

    /** Показывает «Главную» после стартовой расстановки фокуса (или по таймауту). */
    fun revealInitialScreen() {
        if (!revealPending) return
        revealPending = false
        revealFallbackJob?.cancel()
        view?.animate()?.alpha(1f)?.setDuration(INITIAL_REVEAL_FADE_MS)?.start()
    }

    override fun onStart() {
        super.onStart()

        selectedPosition = lastSelectedPosition
    }

    private fun showMenu() {
        adapter = menuAdapter

        menuAdapter.clear()
        MainPagesFragmentFactory.ids.forEach {
            menuAdapter.add(PageRow(HeaderItem(it, MainPagesFragmentFactory.variant1[it])))
        }
    }

    private fun pageIndexOfTab(tabIndex: Int): Int {
        val id = MainPagesFragmentFactory.tabIds.getOrNull(tabIndex) ?: return -1
        return MainPagesFragmentFactory.ids.indexOf(id)
    }

    private fun tabIndexOfPage(pageIndex: Int): Int {
        val id = MainPagesFragmentFactory.ids.getOrNull(pageIndex) ?: return -1
        return MainPagesFragmentFactory.tabIds.indexOf(id)
    }

    /** Все вкладки срабатывают только по OK: фокус на вкладке лишь подсвечивает её. */
    private fun onTabClicked(tabIndex: Int) {
        val page = pageIndexOfTab(tabIndex)
        if (page < 0) return
        selectPage(page)
        focusContent(page)
    }

    private fun selectPage(page: Int) {
        topTabs?.setSelectedTab(tabIndexOfPage(page))
        if (page == currentPage) return
        lastSelectedPosition = page
        setSelectedPosition(page)
        updateHeroVisibility()
    }

    /** Фокус в контент страницы [page]; ждёт, пока Leanback подменит фрагмент и создаст view. */
    private fun focusContent(page: Int) {
        contentFocusJob?.cancel()
        contentFocusJob = viewLifecycleOwner.lifecycleScope.launch {
            repeat(CONTENT_FOCUS_ATTEMPTS) {
                val content = mainFragment?.view
                // До первого переключения страниц Leanback держит selectedPosition = -1 (это «Главная»).
                if (selectedPosition.coerceAtLeast(0) == page && content != null && content.isAttachedToWindow) {
                    if (content.hasFocus() || content.requestFocus()) return@launch
                }
                delay(CONTENT_FOCUS_ATTEMPT_DELAY_MS)
            }
        }
    }

    /** Фокус на вкладку [tabIndex] (по умолчанию — текущую страницу), панель при этом показывается. */
    private fun focusTabs(tabIndex: Int = tabIndexOfPage(currentPage)): Boolean {
        val tabs = topTabs ?: return false
        showTitle(true)
        return tabs.focusTab(tabIndex)
    }

    private fun setupFocusSearch(view: View) {
        val frame = view.findViewById<BrowseFrameLayout>(androidx.leanback.R.id.browse_frame)
            ?: return
        val leanbackListener = frame.onFocusSearchListener
        frame.onFocusSearchListener = BrowseFrameLayout.OnFocusSearchListener { focused, direction ->
            val tabs = topTabs
            if (tabs == null || focused == null) {
                return@OnFocusSearchListener leanbackListener?.onFocusSearch(focused, direction)
            }
            if (tabs.hasFocus()) {
                return@OnFocusSearchListener when (direction) {
                    View.FOCUS_LEFT, View.FOCUS_RIGHT -> tabs.findNextHorizontal(focused, direction)
                        ?: focused

                    // Вниз — всегда в контент текущей страницы, даже с другой вкладки.
                    View.FOCUS_DOWN -> mainFragment?.view?.takeIf { it.isShown } ?: focused

                    else -> focused
                }
            }
            val content = mainFragment?.view as? ViewGroup
            if (content == null || !content.isAncestorOf(focused)) {
                return@OnFocusSearchListener leanbackListener?.onFocusSearch(focused, direction)
            }
            // Фокус в контенте: сначала ищем соседа внутри страницы, наружу выходим только вверх.
            val next = FocusFinder.getInstance().findNextFocus(content, focused, direction)
            when {
                next != null -> next
                direction == View.FOCUS_UP -> {
                    showTitle(true)
                    tabs.getTabView(tabIndexOfPage(currentPage))?.takeIf { it.isShown } ?: focused
                }

                // LEFT к скрытому меню Leanback и прочие выходы из страницы не пускаем.
                else -> focused
            }
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

    private fun setupBackHandling() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!handleBack()) {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
    }

    /** BACK: контент → вкладки; вкладки не на «Главной» → «Главная»; иначе штатное поведение. */
    private fun handleBack(): Boolean {
        val root = view ?: return false
        val tabs = topTabs ?: return false
        // Поверх открыт другой экран (например, guided step) — не наш BACK.
        if (parentFragmentManager.backStackEntryCount > 0 || !root.hasFocus()) return false
        if (!tabs.hasFocus()) {
            (mainFragment as? RowsSupportFragment)?.setSelectedPosition(0, true)
            return focusTabs()
        }
        if (currentPage != 0) {
            selectPage(0)
            focusTabs(tabIndexOfPage(0))
            return true
        }
        return false
    }
}
