package ru.radiationx.data.datasource.remote.api

import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEmpty
import kotlinx.coroutines.withTimeout
import ru.radiationx.data.ApiClient
import ru.radiationx.data.MainClient
import ru.radiationx.data.datasource.remote.Api
import ru.radiationx.data.datasource.remote.IClient
import ru.radiationx.data.datasource.remote.address.ApiAddress
import ru.radiationx.data.datasource.remote.fetchApiResponse
import ru.radiationx.data.datasource.remote.fetchResponse
import ru.radiationx.data.entity.response.app.V1AppStatusResponse
import ru.radiationx.data.entity.response.config.ApiConfigResponse
import ru.radiationx.data.system.LoadTiming
import javax.inject.Inject

class ConfigurationApi @Inject constructor(
    @MainClient private val mainClient: IClient,
    @ApiClient private val apiClient: IClient,
    private val moshi: Moshi,
) {

    /**
     * Одна лёгкая v1-проверка `/api/v1/app/status` вместо двух последовательных
     * (legacy `query=empty` + v1 years). Идёт через API-клиент, чтобы прогретое
     * соединение к v1-хосту переиспользовалось следующими запросами.
     */
    suspend fun checkAvailable(address: ApiAddress): Boolean {
        val v1Base = address.resolvePublicV1Base()
        val startedAt = LoadTiming.now()
        return try {
            val status = withTimeout(10_000) {
                apiClient
                    .get("$v1Base/api/v1/app/status", emptyMap())
                    .fetchResponse<V1AppStatusResponse>(moshi)
            }
            (status.isAlive != false).also {
                LoadTiming.span("config", "check ${address.tag}", startedAt, "alive=$it")
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Throwable) {
            LoadTiming.span("config", "check ${address.tag}", startedAt, "error=${ex.javaClass.simpleName}")
            throw ex
        }
    }

    suspend fun getConfiguration(): ApiConfigResponse {
        return getMergeConfig().also {
            if (it.addresses.isEmpty()) {
                throw IllegalStateException("Empty config adresses")
            }
        }
    }

    private suspend fun getMergeConfig(): ApiConfigResponse {
        val apiFlow = flow {
            emit(getConfigFromApi())
        }.catch {
            emit(ApiConfigResponse(emptyList()))
        }
        val reserveFlow = flow {
            emit(getConfigFromReserve())
        }.catch {
            emit(ApiConfigResponse(emptyList()))
        }
        return merge(apiFlow, reserveFlow)
            .filter { it.addresses.isNotEmpty() }
            .onEmpty { emit(ApiConfigResponse(emptyList())) }
            .first()
    }

    private suspend fun getConfigFromApi(): ApiConfigResponse {
        val args = mapOf(
            "query" to "config"
        )
        val response = withTimeout(10_000) {
            mainClient.post(Api.DEFAULT_ADDRESS.api, args)
        }
        return response
            .fetchApiResponse(moshi)
    }

    private suspend fun getConfigFromReserve(): ApiConfigResponse {
        return try {
            getReserve("https://raw.githubusercontent.com/anilibria/anilibria-app/master/config.json")
        } catch (ex: Throwable) {
            getReserve("https://bitbucket.org/RadiationX/anilibria-app/raw/master/config.json")
        }
    }

    private suspend fun getReserve(url: String): ApiConfigResponse = mainClient
        .get(url, emptyMap())
        .fetchResponse(moshi)

    /** Как в [ru.radiationx.data.datasource.remote.address.ApiConfig.animeBaseUrl]: без animeBase — дефолтный v1-хост. */
    private fun ApiAddress.resolvePublicV1Base(): String {
        return listOf(animeBase, Api.DEFAULT_ADDRESS.animeBase)
            .firstNotNullOfOrNull { base -> base?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } }
            .orEmpty()
    }

}
