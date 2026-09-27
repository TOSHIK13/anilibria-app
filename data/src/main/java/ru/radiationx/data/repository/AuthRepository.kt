package ru.radiationx.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import ru.radiationx.data.datasource.holders.AuthHolder
import ru.radiationx.data.datasource.holders.CookieHolder
import ru.radiationx.data.datasource.holders.SocialAuthHolder
import ru.radiationx.data.datasource.holders.UserHolder
import ru.radiationx.data.datasource.remote.ApiError
import ru.radiationx.data.datasource.remote.address.ApiConfig
import ru.radiationx.data.datasource.remote.api.AuthApi
import ru.radiationx.data.entity.common.AuthState
import ru.radiationx.data.entity.domain.auth.OtpInfo
import ru.radiationx.data.entity.domain.auth.SocialAuth
import ru.radiationx.data.entity.domain.auth.hasValidCode
import ru.radiationx.data.entity.domain.other.ProfileItem
import ru.radiationx.data.entity.mapper.toDomain
import ru.radiationx.shared.ktx.coRunCatching
import timber.log.Timber
import javax.inject.Inject

/**
 * Created by radiationx on 30.12.17.
 */
class AuthRepository @Inject constructor(
    private val authApi: AuthApi,
    private val userHolder: UserHolder,
    private val authHolder: AuthHolder,
    private val socialAuthHolder: SocialAuthHolder,
    private val apiConfig: ApiConfig,
    private val cookieHolder: CookieHolder,
) {

    fun observeUser(): Flow<ProfileItem?> =
        combine(observeAuthState(), userHolder.observeUser()) { authState, profileItem ->
            profileItem?.takeIf { authState == AuthState.AUTH }
        }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)


    suspend fun getUser(): ProfileItem? {
        return withContext(Dispatchers.IO) {
            userHolder.getUser()?.takeIf {
                getAuthState() == AuthState.AUTH
            }
        }
    }

    fun observeAuthState(): Flow<AuthState> = combine(
        cookieHolder.observeCookies(),
        authHolder.observeSessionToken(),
        authHolder.observeAuthSkipped()
    ) { cookies, sessionToken, skipped ->
        computeAuthState(cookies, sessionToken, skipped)
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    suspend fun getAuthState(): AuthState {
        return withContext(Dispatchers.IO) {
            computeAuthState(
                cookieHolder.getCookies(),
                authHolder.getSessionToken(),
                authHolder.getAuthSkipped()
            )
        }
    }

    fun observeSessionToken(): Flow<String?> = authHolder
        .observeSessionToken()
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    suspend fun hasSessionToken(): Boolean = withContext(Dispatchers.IO) {
        !authHolder.getSessionToken().isNullOrBlank()
    }

    suspend fun setAuthSkipped(value: Boolean) {
        withContext(Dispatchers.IO) {
            authHolder.setAuthSkipped(value)
        }
    }

    suspend fun loadUser(): ProfileItem = withContext(Dispatchers.IO) {
        val profile = when {
            !authHolder.getSessionToken().isNullOrBlank() -> loadV1UserWithLegacyAvatarFallback()
            hasLegacySessionCookie() -> authApi.loadUser().toDomain(apiConfig)
            // Ни V1 токена, ни legacy PHPSESSID: сетевой `query=user` не нужен.
            else -> throw ApiError(401, "Unauthorized", null)
        }
        profile
            .also { updateUser(it) }
    }

    suspend fun getOtpInfo(): OtpInfo = withContext(Dispatchers.IO) {
        loadValidOtpInfo(
            load = { authApi.loadOtpInfo(authHolder.getDeviceId()).toDomain() },
            onInvalid = { attempt, info ->
                // Сервер теряет ведущий ноль (5 цифр), сайт такой код не примет.
                // Тот же device_id получит тот же код до expired_at, поэтому меняем device_id.
                Timber.w("OTP code '${info.code}' is not 6 digits, attempt $attempt/$OTP_MAX_ATTEMPTS, resetting device_id")
                authHolder.resetDeviceId()
            }
        )
    }

    suspend fun acceptOtp(code: String) = withContext(Dispatchers.IO) {
        authApi.acceptOtp(code)
    }

    suspend fun signInOtp(code: String): ProfileItem = withContext(Dispatchers.IO) {
        val tokenResponse = authApi.signInOtp(code, authHolder.getDeviceId())
        authHolder.setSessionToken(tokenResponse.token)
        loadV1UserWithLegacyAvatarFallback()
            .also { updateUser(it) }
    }

    suspend fun signIn(login: String, password: String, code2fa: String): ProfileItem =
        withContext(Dispatchers.IO) {
            val tokenResponse = authApi.signInV1(login, password)
            authHolder.setSessionToken(tokenResponse.token)
            loadV1UserWithLegacyAvatarFallback()
                .also { updateUser(it) }
        }

    suspend fun signOut() {
        withContext(Dispatchers.IO) {
            coRunCatching {
                authApi.signOut(withLegacyFallback = hasLegacySessionCookie())
            }.onFailure {
                Timber.e(it)
            }
            cookieHolder.removeAuthCookie()
            authHolder.setSessionToken(null)
            userHolder.delete()
        }
    }

    fun observeSocialAuth(): Flow<List<SocialAuth>> = socialAuthHolder
        .observe()
        .flowOn(Dispatchers.IO)

    suspend fun loadSocialAuth(): List<SocialAuth> = withContext(Dispatchers.IO) {
        authApi
            .loadSocialAuth()
            .map { it.toDomain() }
            .also { socialAuthHolder.save(it) }
    }

    suspend fun getSocialAuth(key: String): SocialAuth =
        withContext(Dispatchers.IO) {
            socialAuthHolder.get().first { it.key == key }
        }

    suspend fun signInSocial(resultUrl: String, item: SocialAuth): ProfileItem =
        withContext(Dispatchers.IO) {
            val tokenResponse = authApi
                .signInSocial(resultUrl, item)
            tokenResponse.token.takeIf { token -> token.isNotBlank() }?.also { token ->
                authHolder.setSessionToken(token)
            }

            val profile = if (!tokenResponse.token.isNullOrBlank()) {
                loadV1UserWithLegacyAvatarFallback()
            } else {
                authApi.loadUser().toDomain(apiConfig)
            }

            profile
                .also { updateUser(it) }
        }

    suspend fun prepareSocialAuth(key: String): SocialAuth = withContext(Dispatchers.IO) {
        authApi
            .loadSocialAuthLogin(key)
            .toDomain(key)
    }

    private suspend fun updateUser(newUser: ProfileItem) {
        withContext(Dispatchers.IO) {
            userHolder.saveUser(newUser)
        }
    }

    private suspend fun loadV1UserWithLegacyAvatarFallback(): ProfileItem {
        val v1Profile = authApi.loadV1User().toDomain(apiConfig)
        if (!v1Profile.avatarUrl.isNullOrBlank() || !hasLegacySessionCookie()) {
            return v1Profile
        }
        val legacyAvatar = coRunCatching {
            authApi.loadUser().toDomain(apiConfig)
        }.onFailure {
            Timber.e(it)
        }.getOrNull()?.avatarUrl
        return v1Profile.copy(avatarUrl = legacyAvatar ?: v1Profile.avatarUrl)
    }

    private suspend fun hasLegacySessionCookie(): Boolean =
        cookieHolder.getCookies()[CookieHolder.PHPSESSID] != null

    private fun computeAuthState(
        cookies: Map<String, Cookie>,
        sessionToken: String?,
        skipped: Boolean,
    ): AuthState {
        val cookie = cookies[CookieHolder.PHPSESSID]
        return when {
            !sessionToken.isNullOrBlank() -> AuthState.AUTH
            cookie != null -> AuthState.AUTH
            skipped -> AuthState.AUTH_SKIPPED
            else -> AuthState.NO_AUTH
        }
    }

}

internal const val OTP_MAX_ATTEMPTS = 5

/**
 * Запрашивает OTP до [maxAttempts] раз, пока код не станет 6-значным.
 * Перед каждым повтором вызывается [onInvalid]. Если все попытки неудачны,
 * возвращается последний ответ (UI сам покажет "Код обновляется…").
 */
internal suspend fun loadValidOtpInfo(
    maxAttempts: Int = OTP_MAX_ATTEMPTS,
    load: suspend () -> OtpInfo,
    onInvalid: suspend (attempt: Int, info: OtpInfo) -> Unit,
): OtpInfo {
    var attempt = 1
    var info = load()
    while (!info.hasValidCode() && attempt < maxAttempts) {
        onInvalid(attempt, info)
        attempt++
        info = load()
    }
    return info
}
