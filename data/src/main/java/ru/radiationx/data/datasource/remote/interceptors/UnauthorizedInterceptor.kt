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
        val isV1AccountForbidden = response.code == 403 &&
            request.header("Authorization") != null &&
            request.url.encodedPath.startsWith("/api/v1/accounts/")
        if (response.code == 401 || isV1AccountForbidden) {
            runBlocking {
                tokenHolder.delete()
                cookieHolder.removeAuthCookie()
                authHolder.setSessionToken(null)
            }
        }
        return response
    }
}
