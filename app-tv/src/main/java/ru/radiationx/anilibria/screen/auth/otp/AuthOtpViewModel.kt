package ru.radiationx.anilibria.screen.auth.otp

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import ru.radiationx.anilibria.common.fragment.GuidedRouter
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.data.entity.domain.auth.OtpInfo
import ru.radiationx.data.entity.domain.auth.OtpNotAcceptedException
import ru.radiationx.data.entity.domain.auth.OtpNotFoundException
import ru.radiationx.data.entity.domain.auth.hasValidCode
import ru.radiationx.data.repository.AuthRepository
import ru.radiationx.shared.ktx.coRunCatching
import timber.log.Timber
import javax.inject.Inject
import kotlin.math.ceil

class AuthOtpViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val guidedRouter: GuidedRouter,
) : LifecycleViewModel() {

    companion object {
        private const val INVALID_CODE_RELOAD_DELAY = 3000L
        private const val MAX_INVALID_CODE_RELOADS = 3
    }

    val otpInfoData = MutableStateFlow<OtpInfo?>(null)
    val state = MutableStateFlow(State())

    private var timerJob: Job? = null
    private var signInJob: Job? = null
    private var invalidCodeReloadJob: Job? = null
    private var invalidCodeReloads = 0

    init {
        loadOtpInfo()
    }

    fun onCompleteClick() {
        updateState(progress = true, error = "")
        signIn()
    }

    fun onExpiredClick() {
        updateState(progress = true, error = "")
        loadOtpInfo()
    }

    fun onRefreshClick() {
        updateState(progress = true, error = "")
        loadOtpInfo()
    }

    fun onRepeatClick() {
        updateState(progress = true, error = "")
        loadOtpInfo()
    }

    private fun signIn() {
        val code = otpInfoData.value?.code ?: return
        signInJob?.cancel()
        signInJob = viewModelScope.launch {
            coRunCatching {
                authRepository.signInOtp(code)
            }.onSuccess {
                guidedRouter.finishGuidedChain()
            }.onFailure {
                handleError(it)
            }
        }
    }

    private fun loadOtpInfo() {
        signInJob?.cancel()
        signInJob = viewModelScope.launch {
            coRunCatching {
                authRepository.getOtpInfo()
            }.onSuccess {
                otpInfoData.value = it
                if (it.hasValidCode()) {
                    invalidCodeReloads = 0
                    updateState(ButtonState.COMPLETE, false, remainingSeconds = it.remainingSeconds())
                    startTimer(it)
                } else {
                    handleInvalidCode(it)
                }
            }.onFailure {
                handleError(it)
            }
        }
    }

    /**
     * Репозиторий уже перевыпускал код, но он всё ещё не 6-значный (сайт его не примет).
     * Показываем "Код обновляется…" и повторяем запрос сами, без кнопки "Готово".
     */
    private fun handleInvalidCode(otpInfo: OtpInfo) {
        timerJob?.cancel()
        invalidCodeReloadJob?.cancel()
        if (invalidCodeReloads >= MAX_INVALID_CODE_RELOADS) {
            invalidCodeReloads = 0
            updateState(ButtonState.REPEAT, false, "Не удалось получить код, обновите его", 0L)
            return
        }
        invalidCodeReloads++
        updateState(ButtonState.REPEAT, true, "", otpInfo.remainingSeconds())
        invalidCodeReloadJob = viewModelScope.launch {
            delay(INVALID_CODE_RELOAD_DELAY)
            loadOtpInfo()
        }
    }

    private fun handleError(error: Throwable) {
        Timber.e(error)
        val buttonState = when (error) {
            is OtpNotFoundException -> ButtonState.EXPIRED
            is OtpNotAcceptedException -> ButtonState.COMPLETE
            else -> ButtonState.REPEAT
        }
        updateState(buttonState, false, error.message.orEmpty())
    }

    private fun startTimer(otpInfo: OtpInfo) {
        timerJob?.cancel()
        val time = otpInfo.expiresAt.time - System.currentTimeMillis()
        if (time < 0) {
            setExpired()
            return
        }
        timerJob = viewModelScope.launch {
            while (true) {
                val remainingSeconds = otpInfo.remainingSeconds()
                if (remainingSeconds <= 0) {
                    setExpired()
                    return@launch
                }
                updateState(remainingSeconds = remainingSeconds)
                delay(1000L)
            }
        }
    }

    private fun setExpired() {
        signInJob?.cancel()
        updateState(ButtonState.EXPIRED, false, "", 0L)
    }

    private fun updateState(
        buttonState: ButtonState = state.value.buttonState,
        progress: Boolean = state.value.progress,
        error: String = state.value.error,
        remainingSeconds: Long = state.value.remainingSeconds,
    ) {
        state.value = State(buttonState, progress, error, remainingSeconds)
    }

    private fun OtpInfo.remainingSeconds(): Long {
        val remaining = expiresAt.time - System.currentTimeMillis()
        return ceil(remaining / 1000.0).toLong().coerceAtLeast(0L)
    }

    data class State(
        val buttonState: ButtonState = ButtonState.COMPLETE,
        val progress: Boolean = false,
        val error: String = "",
        val remainingSeconds: Long = 0L,
    )

    enum class ButtonState {
        COMPLETE,
        EXPIRED,
        REPEAT
    }
}
