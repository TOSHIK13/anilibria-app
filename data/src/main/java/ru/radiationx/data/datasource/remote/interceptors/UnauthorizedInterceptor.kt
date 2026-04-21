package ru.radiationx.data.datasource.remote.interceptors

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import ru.radiationx.data.datasource.holders.AuthHolder
import ru.radiationx.data.datasource.holders.CookieHolder
import ru.radiationx.data.datasource.holders.UserHolder
import javax.inject.Inject

class UnauthorizedInterceptor @Inject constructor(
    private val tokenHolder: UserHolder,
    private val cookieHolder: CookieHolder,
    private val authHolder: AuthHolder,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val isV1AccountRequest = request.url.encodedPath.startsWith("/api/v1/accounts/") &&
            request.header("Authorization") != null
        val isV1Unauthorized = isV1AccountRequest && (response.code == 401 || response.code == 403)
        val isLegacyUnauthorized = !isV1AccountRequest && response.code == 401
        if (isV1Unauthorized) {
            runBlocking {
                tokenHolder.delete()
                cookieHolder.removeAuthCookie()
                authHolder.setSessionToken(null)
            }
        } else if (isLegacyUnauthorized) {
            runBlocking {
                cookieHolder.removeAuthCookie()
            }
        }
        return response
    }
}
