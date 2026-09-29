package ru.radiationx.anilibria.screen.services

import androidx.lifecycle.viewModelScope
import com.github.terrakok.cicerone.Router
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.data.external.AniListAuth
import ru.radiationx.data.external.AniListValidation
import javax.inject.Inject

/** Состояние проверки вставленного токена. */
sealed class TokenCheck {
    object Idle : TokenCheck()
    object Checking : TokenCheck()
    data class Ok(val name: String, val expiresAtSec: Long) : TokenCheck()
    data class Invalid(val reason: String) : TokenCheck()
    data class Network(val message: String) : TokenCheck()
}

class AniListLinkViewModel @Inject constructor(
    private val auth: AniListAuth,
    private val router: Router,
) : LifecycleViewModel() {

    private companion object {
        const val DEBOUNCE_MS = 600L
    }

    val authorizeUrl: String = auth.authorizeUrl

    val check = MutableStateFlow<TokenCheck>(TokenCheck.Idle)

    private var valid: AniListValidation.Ok? = null
    private var job: Job? = null

    /** Текст поля изменился: проверка через паузу после последнего символа. */
    fun onTokenChanged(text: String) {
        job?.cancel()
        valid = null
        if (text.isBlank()) {
            check.value = TokenCheck.Idle
            return
        }
        check.value = TokenCheck.Checking
        job = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            validate(text)
        }
    }

    /** Кнопка «Подключить»: подключает проверенный токен, иначе проверяет сейчас. */
    fun connect(text: String) {
        valid?.also {
            auth.save(it)
            router.exit()
            return
        }
        job?.cancel()
        if (text.isBlank()) return
        check.value = TokenCheck.Checking
        job = viewModelScope.launch { validate(text) }
    }

    fun cancel() = router.exit()

    private suspend fun validate(text: String) {
        when (val result = auth.validate(text)) {
            is AniListValidation.Ok -> {
                valid = result
                check.value = TokenCheck.Ok(result.viewer.name, result.expiresAtSec)
            }

            is AniListValidation.Invalid -> check.value = TokenCheck.Invalid(result.reason)
            is AniListValidation.NetworkError -> check.value = TokenCheck.Network(result.message)
        }
    }
}
