package ru.radiationx.data.di.providers

import android.content.Context
import com.chuckerteam.chucker.api.ChuckerInterceptor
import okhttp3.OkHttpClient
import ru.radiationx.data.SharedBuildConfig
import ru.radiationx.data.analytics.features.SslCompatAnalytics
import ru.radiationx.data.sslcompat.SslCompat
import ru.radiationx.data.sslcompat.appendSslCompat
import ru.radiationx.data.system.appendSslCompatAnalytics
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider

/**
 * Провайдер зарегистрирован в DI как single, поэтому клиент (и его connection pool)
 * общий для плеера, prefetch-а и загрузки плейлистов.
 */
class PlayerOkHttpProvider @Inject constructor(
    private val context: Context,
    private val sharedBuildConfig: SharedBuildConfig,
    private val sslCompat: SslCompat,
    private val sslCompatAnalytics: SslCompatAnalytics
) : Provider<OkHttpClient> {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .appendSslCompatAnalytics(sslCompat, sslCompatAnalytics)
            .appendSslCompat(sslCompat)
            .apply {
                if (sharedBuildConfig.debug) {
                    addNetworkInterceptor(ChuckerInterceptor.Builder(context).build())
                }
            }
            .build()
    }

    override fun get(): OkHttpClient = client

    private companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val READ_TIMEOUT_SECONDS = 20L
        private const val WRITE_TIMEOUT_SECONDS = 10L
    }
}
