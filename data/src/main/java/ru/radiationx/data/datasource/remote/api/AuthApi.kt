package ru.radiationx.data.datasource.remote.api

import android.net.Uri
import com.squareup.moshi.Moshi
import org.json.JSONObject
import ru.radiationx.data.ApiClient
import ru.radiationx.data.datasource.remote.ApiError
import ru.radiationx.data.datasource.remote.IClient
import ru.radiationx.data.datasource.remote.address.ApiConfig
import ru.radiationx.data.datasource.remote.fetchApiResponse
import ru.radiationx.data.datasource.remote.fetchEmptyApiResponse
import ru.radiationx.data.datasource.remote.fetchResponse
import ru.radiationx.data.datasource.remote.parsers.AuthParser
import ru.radiationx.data.entity.domain.auth.SocialAuth
import ru.radiationx.data.entity.domain.auth.SocialAuthException
import ru.radiationx.data.entity.response.auth.OtpInfoResponse
import ru.radiationx.data.entity.response.auth.SocialAuthResponse
import ru.radiationx.data.entity.response.auth.V1SocialAuthLoginResponse
import ru.radiationx.data.entity.response.auth.V1OtpInfoResponse
import ru.radiationx.data.entity.response.auth.V1TokenResponse
import ru.radiationx.data.entity.response.other.ProfileResponse
import ru.radiationx.data.entity.response.other.V1ProfileResponse
import ru.radiationx.data.system.HttpException
import ru.radiationx.shared.ktx.android.nullString
import java.util.regex.Pattern
import javax.inject.Inject

/**
 * Created by radiationx on 30.12.17.
 */
class AuthApi @Inject constructor(
    @ApiClient private val client: IClient,
    private val authParser: AuthParser,
    private val apiConfig: ApiConfig,
    private val moshi: Moshi,
) {

    suspend fun loadUser(): ProfileResponse {
        val args: MutableMap<String, String> = mutableMapOf(
            "query" to "user"
        )
        return client.post(apiConfig.apiUrl, args)
            .fetchApiResponse(moshi)
    }

    suspend fun loadV1User(): V1ProfileResponse {
        val args = mapOf<String, String>()
        return client
            .get("${apiConfig.accountsBaseUrl}/api/v1/accounts/users/me/profile", args)
            .fetchResponse(moshi)
    }

    suspend fun loadOtpInfo(deviceId: String): V1OtpInfoResponse {
        val args = mapOf(
            "device_id" to deviceId
        )
        return try {
            client
                .post("${apiConfig.accountsBaseUrl}/api/v1/accounts/otp/get", args)
                .fetchResponse(moshi)
        } catch (ex: Throwable) {
            throw authParser.checkOtpError(ex)
        }
    }

    suspend fun acceptOtp(code: String) {
        val args = mapOf(
            "code" to code
        )
        try {
            client
                .postRaw("${apiConfig.accountsBaseUrl}/api/v1/accounts/otp/accept", args)
                .use { }
        } catch (ex: Throwable) {
            throw authParser.checkOtpError(ex)
        }
    }

    suspend fun signInOtp(code: String, deviceId: String): V1TokenResponse {
        val args = mapOf(
            "device_id" to deviceId,
            "code" to code
        )
        return try {
            client
                .post("${apiConfig.accountsBaseUrl}/api/v1/accounts/otp/login", args)
                .fetchResponse(moshi)
        } catch (ex: Throwable) {
            throw authParser.checkOtpError(ex)
        }
    }

    suspend fun signIn(login: String, password: String, code2fa: String): ProfileResponse {
        val args: MutableMap<String, String> = mutableMapOf(
            "mail" to login,
            "passwd" to password,
            "fa2code" to code2fa
        )
        val url = "${apiConfig.baseUrl}/public/login.php"
        return client.post(url, args)
            .let { authParser.authResult(it) }
            .let { loadUser() }
    }

    suspend fun signInV1(login: String, password: String): V1TokenResponse {
        val args = mapOf(
            "login" to login,
            "password" to password
        )
        return client
            .post("${apiConfig.accountsBaseUrl}/api/v1/accounts/users/auth/login", args)
            .fetchResponse(moshi)
    }

    suspend fun loadSocialAuth(): List<SocialAuthResponse> {
        return listOf(
            SocialAuthResponse("vk", "VK", "", "", ""),
            SocialAuthResponse("google", "Google", "", "", ""),
            SocialAuthResponse("discord", "Discord", "", "", ""),
            SocialAuthResponse("patreon", "Patreon", "", "", ""),
        )
    }

    suspend fun loadSocialAuthLogin(provider: String): V1SocialAuthLoginResponse {
        return client
            .get("${apiConfig.accountsBaseUrl}/api/v1/accounts/users/auth/social/$provider/login", emptyMap())
            .fetchResponse(moshi)
    }

    suspend fun signInSocial(resultUrl: String, item: SocialAuth): V1TokenResponse {
        extractSocialAuthState(resultUrl, item)?.also { state ->
            return try {
                client
                    .get(
                        "${apiConfig.accountsBaseUrl}/api/v1/accounts/users/auth/social/authenticate",
                        mapOf("state" to state)
                    )
                    .fetchResponse(moshi)
            } catch (error: HttpException) {
                if (error.code == 404) {
                    throw SocialAuthException()
                }
                throw error
            }
        }
        return signInSocialLegacy(resultUrl, item)
    }

    suspend fun signOut() {
        try {
            client.post("${apiConfig.accountsBaseUrl}/api/v1/accounts/users/auth/logout", emptyMap())
            return
        } catch (_: Throwable) {
        }
        val args = mapOf<String, String>()
        client.post("${apiConfig.baseUrl}/public/logout.php", args)
    }

    private suspend fun signInSocialLegacy(resultUrl: String, item: SocialAuth): V1TokenResponse {
        val args: MutableMap<String, String> = mutableMapOf()

        val fixedUrl = Uri.parse(apiConfig.baseUrl).host?.let { redirectDomain ->
            resultUrl.replace("www.anilibria.tv", redirectDomain)
        } ?: resultUrl

        client
            .getFull(fixedUrl, args)
            .also { response ->
                if (item.errorUrlPattern.isNotBlank()) {
                    val matcher = Pattern.compile(item.errorUrlPattern).matcher(response.redirect)
                    if (matcher.find()) {
                        throw SocialAuthException()
                    }
                }
            }
            .also {
                val message = try {
                    JSONObject(it.body).nullString("mes")
                } catch (ignore: Exception) {
                    null
                }
                if (message != null) {
                    throw ApiError(400, message, null)
                }
            }
        return V1TokenResponse("")
    }

    private fun extractSocialAuthState(resultUrl: String, item: SocialAuth): String? {
        item.authState?.takeIf { authState ->
            resultUrl.contains(authState)
        }?.also { return it }
        return Uri.parse(resultUrl).getQueryParameter("state")
    }

}
