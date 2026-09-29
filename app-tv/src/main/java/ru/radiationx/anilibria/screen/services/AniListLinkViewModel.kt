package ru.radiationx.anilibria.screen.services

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.github.terrakok.cicerone.Router
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.data.external.AniListAuth
import ru.radiationx.data.external.AniListValidation
import ru.radiationx.data.external.AniListService
import ru.radiationx.data.external.ExternalServiceSettings
import ru.radiationx.data.external.ExternalTokenStore
import javax.inject.Inject

/** Состояние проверки вставленного токена. */
sealed class TokenCheck {
    object Idle : TokenCheck()
    object Checking : TokenCheck()
    object FromPhone : TokenCheck()
    data class Ok(val name: String, val expiresAtSec: Long) : TokenCheck()
    data class Invalid(val reason: String) : TokenCheck()
    data class Network(val message: String) : TokenCheck()
}

class AniListLinkViewModel @Inject constructor(
    private val auth: AniListAuth,
    private val store: ExternalTokenStore,
    private val settings: ExternalServiceSettings,
    private val session: FirstSyncSession,
    private val router: Router,
    private val context: Context,
) : LifecycleViewModel() {

    private companion object {
        const val DEBOUNCE_MS = 600L
    }

    val authorizeUrl: String = auth.authorizeUrl

    val check = MutableStateFlow<TokenCheck>(TokenCheck.Idle)

    /** Ссылка на страницу в локальной сети; null — недоступна (нет сети/сервер не поднялся). */
    val lanUrl = MutableStateFlow<String?>(null)

    private val phoneLock = Mutex()
    private var server: LanTokenServer? = null

    override fun onCreate() {
        super.onCreate()
        startServer()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
    }

    override fun onCleared() {
        stopServer()
        super.onCleared()
    }

    private fun startServer() {
        if (server != null) return
        val ip = findLocalIpv4(context) ?: return
        val created = LanTokenServer(viewModelScope, authorizeUrl, ::onPhoneToken)
        if (created.start()) {
            server = created
            lanUrl.value = "http://$ip:${created.port}${created.path}"
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
        lanUrl.value = null
    }

    /** Токен с телефона: проверить и при успехе подключить аккаунт. */
    private suspend fun onPhoneToken(text: String): LanReply = phoneLock.withLock {
        withContext(Dispatchers.Main.immediate) {
            job?.cancel()
            valid = null
            check.value = TokenCheck.FromPhone
        }
        when (val result = auth.validate(text)) {
            is AniListValidation.Ok -> withContext(Dispatchers.Main.immediate) {
                valid = result
                check.value = TokenCheck.Ok(result.viewer.name, result.expiresAtSec)
                server?.stop()
                server = null
                connect("")
                LanReply(true, "Готово · AniList ${result.viewer.name} подключён на ТВ")
            }

            is AniListValidation.Invalid -> {
                check.value = TokenCheck.Invalid(result.reason)
                LanReply(false, "Токен не подошёл: ${result.reason}")
            }

            is AniListValidation.NetworkError -> {
                check.value = TokenCheck.Network(result.message)
                LanReply(false, "ТВ не смог проверить токен: ${result.message}. Попробуйте ещё раз.")
            }
        }
    }

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
            val hadAccount = store.get(AniListService.ID) != null
            auth.save(it)
            if (!hadAccount && !settings.isFirstSyncDone(AniListService.ID)) {
                // новый аккаунт: сразу мастер первой синхронизации (по «Позже» — экран сервиса)
                session.begin()
                router.replaceScreen(AniListServiceScreen())
                router.navigateTo(FirstSyncScreen())
            } else {
                router.exit()
            }
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
