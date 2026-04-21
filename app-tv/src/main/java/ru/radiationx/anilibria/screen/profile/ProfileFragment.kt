package ru.radiationx.anilibria.screen.profile

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.leanback.app.BrowseSupportFragment
import androidx.lifecycle.lifecycleScope
import dev.androidbroadcast.vbpd.viewBinding
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.GradientBackgroundManager
import ru.radiationx.anilibria.databinding.FragmentProfileBinding
import ru.radiationx.quill.inject
import ru.radiationx.shared_app.di.quillParentViewModel
import ru.radiationx.shared_app.imageloader.showImageUrl

class ProfileFragment : Fragment(R.layout.fragment_profile),
    BrowseSupportFragment.MainFragmentAdapterProvider {

    private val binding by viewBinding<FragmentProfileBinding>()

    private val backgroundManager by inject<GradientBackgroundManager>()

    private val viewModel by quillParentViewModel<ProfileViewModel>()

    private val selfMainFragmentAdapter by lazy { BrowseSupportFragment.MainFragmentAdapter(this) }

    override fun getMainFragmentAdapter(): BrowseSupportFragment.MainFragmentAdapter<*> {
        return selfMainFragmentAdapter
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycle.addObserver(viewModel)

        viewModel.state.onEach { state ->
            val profile = state.profile
            val hasAuth = profile != null

            if (!profile?.avatarUrl.isNullOrEmpty()) {
                binding.settingsAvatar.showImageUrl(profile.avatarUrl)
            }

            binding.settingsAvatar.isVisible = hasAuth
            binding.settingsNick.isVisible = hasAuth
            binding.settingsNick.text = profile?.nick
            binding.settingsAuthAction.text = if (hasAuth) "Выйти" else "Авторизоваться"

            binding.settingsSkips.text = toggleTitle("Кнопки пропуска", state.skipsEnabled)
            binding.settingsAutoSkip.text = toggleTitle("Автопропуск", state.autoSkipEnabled)
            binding.settingsAutoplay.text = toggleTitle("Автовоспроизведение", state.autoplayEnabled)
            binding.settingsBackBuffer.text =
                "Буфер назад: ${formatSeconds(state.backBufferSeconds)}"
            binding.settingsForwardBuffer.text =
                "Буфер вперёд: ${formatSeconds(state.forwardBufferSeconds)}"
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        binding.settingsAuthAction.setOnClickListener { viewModel.onAuthClick() }
        binding.settingsSkips.setOnClickListener { viewModel.onSkipsClick() }
        binding.settingsAutoSkip.setOnClickListener { viewModel.onAutoSkipClick() }
        binding.settingsAutoplay.setOnClickListener { viewModel.onAutoplayClick() }
        binding.settingsBackBuffer.setOnClickListener { viewModel.onBackBufferClick() }
        binding.settingsForwardBuffer.setOnClickListener { viewModel.onForwardBufferClick() }

        mainFragmentAdapter.fragmentHost.notifyViewCreated(selfMainFragmentAdapter)
        mainFragmentAdapter.fragmentHost.notifyDataReady(selfMainFragmentAdapter)
        backgroundManager.clearGradient()
    }

    private fun toggleTitle(title: String, enabled: Boolean): String {
        return "$title: ${if (enabled) "Вкл" else "Выкл"}"
    }

    private fun formatSeconds(value: Int): String {
        return if (value >= 60) {
            val minutes = value / 60
            val seconds = value % 60
            if (seconds == 0) {
                "$minutes мин."
            } else {
                "$minutes мин. $seconds сек."
            }
        } else {
            "$value сек."
        }
    }
}
