package ru.radiationx.anilibria.screen.profile

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.fragment.FakeGuidedStepFragment
import ru.radiationx.anilibria.common.fragment.GuidedRouter
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.anilibria.ui.widget.manager.ExternalProgressManager
import ru.radiationx.data.repository.AuthRepository
import ru.radiationx.quill.viewModel
import ru.radiationx.shared.ktx.android.subscribeTo
import ru.radiationx.shared.ktx.coRunCatching
import timber.log.Timber
import javax.inject.Inject

/** Подтверждение выхода из аккаунта AniLiberty. */
class SettingsSignOutGuidedFragment : FakeGuidedStepFragment() {

    companion object {
        private const val ACTION_SIGN_OUT = 1L
        private const val ACTION_CANCEL = 2L
    }

    private val viewModel by viewModel<SettingsSignOutViewModel>()

    private val progressManager by lazy { ExternalProgressManager() }

    override fun onProvideTheme(): Int = R.style.AppTheme_Player_LeanbackWizard

    override fun onCreateGuidance(savedInstanceState: Bundle?): Guidance = Guidance(
        "Выйти из аккаунта?",
        "История просмотров, коллекции и избранное останутся на сервере. " +
                "Чтобы снова увидеть их на этом устройстве, войдите по коду.",
        null,
        null
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += GuidedAction.Builder(requireContext())
            .id(ACTION_SIGN_OUT)
            .title("Выйти")
            .build()
        actions += GuidedAction.Builder(requireContext())
            .id(ACTION_CANCEL)
            .title("Отмена")
            .build()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycle.addObserver(viewModel)
        // Фокус по умолчанию на «Отмена»: случайное OK не выкинет из аккаунта.
        selectedActionPosition = 1
        progressManager.rootView =
            view.findViewById<View>(androidx.leanback.R.id.action_fragment_root) as? ViewGroup
        subscribeTo(viewModel.progressState) {
            if (it) progressManager.show() else progressManager.hide()
        }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ACTION_SIGN_OUT -> viewModel.signOut()
            ACTION_CANCEL -> viewModel.cancel()
        }
    }
}

class SettingsSignOutViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val guidedRouter: GuidedRouter,
) : LifecycleViewModel() {

    val progressState = MutableStateFlow(false)

    fun signOut() {
        if (progressState.value) return
        viewModelScope.launch {
            progressState.value = true
            coRunCatching { authRepository.signOut() }
                .onFailure { Timber.e(it) }
            progressState.value = false
            guidedRouter.close()
        }
    }

    fun cancel() {
        if (progressState.value) return
        guidedRouter.close()
    }
}
