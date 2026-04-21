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
import ru.radiationx.data.datasource.remote.address.ApiConfig
import ru.radiationx.data.datasource.remote.api.AuthApi
import ru.radiationx.data.entity.common.AuthState
import ru.radiationx.data.entity.domain.auth.OtpInfo
import ru.radiationx.data.entity.domain.auth.SocialAuth
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
        val profile = if (!authHolder.getSessionToken().isNullOrBlank()) {
            loadV1UserWithLegacyAvatarFallback()
        } else {
            authApi.loadUser().toDomain(apiConfig)
        }
        profile
            .also { updateUser(it) }
    }

    suspend fun getOtpInfo(): OtpInfo = withContext(Dispatchers.IO) {
        authApi
            .loadOtpInfo(authHolder.getDeviceId())
            .toDomain()
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
            val profile = loadV1UserWithLegacyAvatarFallback()
            coRunCatching {
                authApi.signIn(login, password, code2fa)
            }.onFailure {
                Timber.e(it)
            }
            profile.also { updateUser(it) }
        }

    suspend fun signOut() {
        withContext(Dispatchers.IO) {
            coRunCatching {
                authApi.signOut()
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
        if (!v1Profile.avatarUrl.isNullOrBlank()) {
            return v1Profile
        }
        val legacyAvatar = coRunCatching {
            authApi.loadUser().toDomain(apiConfig)
        }.onFailure {
            Timber.e(it)
        }.getOrNull()?.avatarUrl
        return v1Profile.copy(avatarUrl = legacyAvatar ?: v1Profile.avatarUrl)
    }

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
