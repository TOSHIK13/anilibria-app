package ru.radiationx.data.datasource.remote.api

import com.squareup.moshi.Moshi
import ru.radiationx.data.ApiClient
import ru.radiationx.data.MainClient
import ru.radiationx.data.datasource.remote.IClient
import ru.radiationx.data.datasource.remote.address.ApiConfig
import ru.radiationx.data.datasource.remote.common.CheckerReserveSources
import ru.radiationx.data.datasource.remote.fetchApiResponse
import ru.radiationx.data.datasource.remote.fetchResponse
import ru.radiationx.data.entity.response.updater.UpdateDataRootResponse
import ru.radiationx.shared.ktx.coRunCatching
import javax.inject.Inject

/**
 * Created by radiationx on 28.01.18.
 */
class CheckerApi @Inject constructor(
    @ApiClient private val client: IClient,
    @MainClient private val mainClient: IClient,
    private val apiConfig: ApiConfig,
    private val reserveSources: CheckerReserveSources,
    private val moshi: Moshi
) {

    suspend fun checkUpdate(versionCode: Int): UpdateDataRootResponse {
        if (!reserveSources.useLegacyApi) {
            return getFromReserve(null)
        }
        val args: MutableMap<String, String> = mutableMapOf(
            "query" to "app_update",
            "current" to versionCode.toString()
        )
        return try {
            client
                .post(apiConfig.apiUrl, args)
                .fetchApiResponse(moshi)
        } catch (ex: Throwable) {
            getFromReserve(ex)
        }
    }

    private suspend fun getFromReserve(legacyError: Throwable?): UpdateDataRootResponse {
        var lastError: Throwable? = legacyError
        reserveSources.sources.forEach { url ->
            coRunCatching {
                getReserve(url)
            }.onSuccess {
                return it
            }.onFailure {
                if (lastError == null) lastError = it
            }
        }
        throw lastError ?: IllegalStateException("No update sources")
    }

    private suspend fun getReserve(url: String): UpdateDataRootResponse = mainClient
        .get(url, emptyMap())
        .fetchResponse(moshi)
}