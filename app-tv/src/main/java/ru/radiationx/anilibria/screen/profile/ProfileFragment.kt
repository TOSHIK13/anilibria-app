package ru.radiationx.anilibria.screen.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.leanback.app.BrowseSupportFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.imageview.ShapeableImageView
import dev.androidbroadcast.vbpd.viewBinding
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.GradientBackgroundManager
import ru.radiationx.anilibria.databinding.FragmentProfileBinding
import ru.radiationx.quill.inject
import ru.radiationx.shared_app.di.quillParentViewModel
import ru.radiationx.shared_app.imageloader.showImageUrl

/**
 * Настройки: слева разделы ([SettingsSection]), справа строки выбранного раздела.
 * Раздел выбирается фокусом; RIGHT — в строки, LEFT — обратно к разделам ([SettingsFocusLayout]).
 */
class ProfileFragment : Fragment(R.layout.fragment_profile),
    BrowseSupportFragment.MainFragmentAdapterProvider {

    private val binding by viewBinding<FragmentProfileBinding>()

    private val backgroundManager by inject<GradientBackgroundManager>()

    private val viewModel by quillParentViewModel<ProfileViewModel>()

    private val selfMainFragmentAdapter by lazy { BrowseSupportFragment.MainFragmentAdapter(this) }

    private val navViews = mutableMapOf<SettingsSection, TextView>()

    private var selectedSection = SettingsSection.ACCOUNTS
    private var renderedSection: SettingsSection? = null
    private var renderedKeys: List<String> = emptyList()
    private val rowViews = mutableMapOf<String, View>()

    /** Последняя строка в фокусе для каждого раздела — туда ведёт RIGHT из колонки разделов. */
    private val lastFocusedKey = mutableMapOf<SettingsSection, String>()

    /**
     * Строка, по которой нажали OK: после закрытия боковой панели (или возврата с экрана) фокус
     * возвращается на неё, а не на раздел. Сбрасывается, когда фокус сам ушёл на другой элемент.
     */
    private var restoreKey: String? = null

    private val focusChangeListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        val key = restoreKey ?: return@OnGlobalFocusChangeListener
        val pages = parentFragment?.view as? ViewGroup ?: view as? ViewGroup ?: return@OnGlobalFocusChangeListener
        if (newFocus != null && pages.isAncestorOf(newFocus) && newFocus !== restoreTarget(key)) {
            restoreKey = null
        }
    }

    override fun getMainFragmentAdapter(): BrowseSupportFragment.MainFragmentAdapter<*> {
        return selfMainFragmentAdapter
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycle.addObserver(viewModel)

        renderedSection = null
        renderedKeys = emptyList()
        rowViews.clear()
        setupNav()

        binding.settingsRoot.navContainer = binding.settingsNav
        binding.settingsRoot.paneContainer = binding.settingsScroll
        binding.settingsRoot.navEntry = { navViews[selectedSection] }
        binding.settingsRoot.paneEntry = { paneEntryView() }
        binding.settingsRoot.restoreEntry = { restoreKey?.let(::restoreTarget) }
        view.viewTreeObserver.addOnGlobalFocusChangeListener(focusChangeListener)

        viewModel.sections.onEach {
            renderPane()
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        viewModel.messages.filterNotNull().onEach {
            Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
            viewModel.onMessageShown()
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        mainFragmentAdapter.fragmentHost.notifyViewCreated(selfMainFragmentAdapter)
        mainFragmentAdapter.fragmentHost.notifyDataReady(selfMainFragmentAdapter)
        backgroundManager.clearGradient()
    }

    override fun onDestroyView() {
        view?.viewTreeObserver?.removeOnGlobalFocusChangeListener(focusChangeListener)
        navViews.clear()
        rowViews.clear()
        super.onDestroyView()
    }

    private fun setupNav() {
        val inflater = LayoutInflater.from(requireContext())
        binding.settingsNav.removeAllViews()
        navViews.clear()
        SettingsSection.values().forEach { section ->
            val item = inflater.inflate(R.layout.item_settings_nav, binding.settingsNav, false) as TextView
            item.text = section.title
            item.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) selectSection(section)
            }
            item.setOnClickListener { paneEntryView()?.requestFocus() }
            binding.settingsNav.addView(item)
            navViews[section] = item
        }
        updateNavState()
    }

    private fun selectSection(section: SettingsSection) {
        if (selectedSection == section) return
        selectedSection = section
        updateNavState()
        renderPane()
        binding.settingsScroll.scrollTo(0, 0)
    }

    private fun updateNavState() {
        navViews.forEach { (section, view) ->
            val selected = section == selectedSection
            view.isActivated = selected
            view.typeface = if (selected) MEDIUM else REGULAR
        }
        binding.settingsPaneTitle.text = selectedSection.title
    }

    private fun paneEntryView(): View? {
        val key = lastFocusedKey[selectedSection]
        val remembered = key?.let { rowViews[it] }?.let { focusTarget(it) }
        if (remembered != null && remembered.isShown) return remembered
        return renderedKeys.asSequence()
            .mapNotNull { rowViews[it]?.let(::focusTarget) }
            .firstOrNull { it.isShown }
    }

    private fun restoreTarget(key: String): View? = rowViews[key]?.let(::focusTarget)?.takeIf { it.isShown }

    private fun onRowClick(key: String, action: SettingsAction) {
        restoreKey = key
        viewModel.onAction(action)
    }

    private fun ViewGroup.isAncestorOf(child: View): Boolean {
        var parent = child.parent
        while (parent != null) {
            if (parent === this) return true
            parent = parent.parent
        }
        return false
    }

    /** Что берёт фокус в элементе: сама строка или пилюля в карточке аккаунта. */
    private fun focusTarget(view: View): View? = when {
        view.isFocusable -> view
        else -> view.findViewById<View>(R.id.settingsAccountAction)?.takeIf { it.isFocusable && it.isVisible }
    }

    private fun renderPane() {
        val items = viewModel.sections.value[selectedSection].orEmpty()
        val keys = items.map { it.key }
        if (renderedSection == selectedSection && keys == renderedKeys) {
            items.forEach { item -> rowViews[item.key]?.also { bind(it, item) } }
            return
        }
        val container = binding.settingsRows
        val focusedKey = rowViews.entries
            .firstOrNull { (_, view) -> view.hasFocus() }
            ?.key
            ?.takeIf { renderedSection == selectedSection }

        container.removeAllViews()
        rowViews.clear()
        val inflater = LayoutInflater.from(requireContext())
        items.forEach { item ->
            val layout = when (item) {
                is SettingsItem.Account -> R.layout.item_settings_account
                is SettingsItem.Header -> R.layout.item_settings_header
                is SettingsItem.Row -> R.layout.item_settings_row
                is SettingsItem.Service -> R.layout.item_settings_service
            }
            val view = inflater.inflate(layout, container, false)
            bind(view, item)
            val key = item.key
            val section = selectedSection
            (focusTarget(view) ?: view).setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) lastFocusedKey[section] = key
            }
            container.addView(view)
            rowViews[key] = view
        }
        renderedSection = selectedSection
        renderedKeys = keys

        if (focusedKey != null) {
            // структура раздела поменялась (например, вход/выход) — фокус на ту же строку или первую
            val target = rowViews[focusedKey]?.let(::focusTarget) ?: paneEntryView()
            target?.requestFocus()
        }
    }

    private fun bind(view: View, item: SettingsItem) {
        when (item) {
            is SettingsItem.Header -> (view as TextView).text = item.title
            is SettingsItem.Row -> bindRow(view, item)
            is SettingsItem.Account -> bindAccount(view, item)
            is SettingsItem.Service -> bindService(view, item)
        }
    }

    private fun bindRow(view: View, item: SettingsItem.Row) {
        view.findViewById<TextView>(R.id.settingsRowTitle).text = item.title
        view.findViewById<TextView>(R.id.settingsRowSubtitle).apply {
            text = item.subtitle
            isVisible = !item.subtitle.isNullOrEmpty()
        }
        view.findViewById<TextView>(R.id.settingsRowValue).apply {
            text = item.value?.let { if (it.isEmpty()) "›" else "$it ›" }
            isVisible = item.value != null
        }
        view.findViewById<SettingsSwitchView>(R.id.settingsRowSwitch).apply {
            isVisible = item.switch != null
            isOn = item.switch == true
        }
        view.setOnClickListener { onRowClick(item.key, item.action) }
    }

    private fun bindService(view: View, item: SettingsItem.Service) {
        view.findViewById<TextView>(R.id.settingsServiceIcon).apply {
            text = item.iconText
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 10 * resources.displayMetrics.density
                setColor(item.iconColor)
            }
        }
        view.findViewById<TextView>(R.id.settingsServiceTitle).text = item.title
        view.findViewById<TextView>(R.id.settingsServiceSubtitle).apply {
            text = item.subtitle
            if (item.subtitleError) setTextColor(0xFFFE3635.toInt()) else setTextColor(resources.getColorStateList(R.color.settings_row_subtitle, null))
        }
        view.findViewById<TextView>(R.id.settingsServiceValue).text = "${item.value} ›"
        view.setOnClickListener { onRowClick(item.key, item.action) }
    }

    private fun bindAccount(view: View, item: SettingsItem.Account) {
        val nick = item.nick
        val letter = view.findViewById<TextView>(R.id.settingsAccountLetter)
        val avatar = view.findViewById<ShapeableImageView>(R.id.settingsAccountAvatar)
        letter.text = nick?.trim()?.firstOrNull()?.uppercase() ?: "?"
        val avatarUrl = item.avatarUrl
        if (!avatarUrl.isNullOrEmpty()) {
            if (avatar.tag != avatarUrl) {
                avatar.tag = avatarUrl
                avatar.showImageUrl(avatarUrl)
            }
            avatar.isVisible = true
        } else {
            avatar.tag = null
            avatar.isVisible = false
        }
        view.findViewById<TextView>(R.id.settingsAccountNick).text = nick ?: "Вы не вошли"
        view.findViewById<TextView>(R.id.settingsAccountSubtitle).text = item.subtitle
        view.findViewById<TextView>(R.id.settingsAccountAction).apply {
            text = item.actionTitle
            isVisible = item.action != null
            isFocusable = item.action != null
            setOnClickListener { item.action?.also { onRowClick(item.key, it) } }
        }
    }

    private companion object {
        val MEDIUM: android.graphics.Typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        val REGULAR: android.graphics.Typeface = android.graphics.Typeface.DEFAULT
    }
}
