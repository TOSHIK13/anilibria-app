package ru.radiationx.data.interactors

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import ru.radiationx.data.datasource.remote.address.ApiConfig
import ru.radiationx.data.repository.ConfigurationRepository
import ru.radiationx.data.system.LoadTiming
import ru.radiationx.shared.ktx.coRunCatching
import timber.log.Timber
import javax.inject.Inject

/**
 * Быстрый старт: при сохранённом конфиге приложение сразу открывает главную
 * на последнем рабочем адресе, а проверка идёт здесь, в фоне.
 *
 * - параллельно: обновление конфига (как на экране проверки) и `/app/status` активного адреса;
 * - активный не отвечает -> параллельная проверка остальных адресов, переключение на первый живой;
 * - живых нет -> `needConfig = true`, лаунчер открывает экран конфигурации (повтор/прокси).
 */
class StartupConfigChecker @Inject constructor(
    private val apiConfig: ApiConfig,
    private val configurationRepository: ConfigurationRepository,
) {

    fun canStartWithoutConfig(): Boolean = apiConfig.hasSavedConfig

    suspend fun checkInBackground() = withContext(Dispatchers.IO) {
        supervisorScope {
            val startedAt = LoadTiming.now()
            val active = apiConfig.active
            val configRefresh = async {
                coRunCatching { configurationRepository.getConfiguration() }
                    .onSuccess { LoadTiming.span("startup", "config_refreshed", startedAt) }
                    .onFailure {
                        Timber.e(it)
                        LoadTiming.span("startup", "config_refresh_failed", startedAt, it.javaClass.simpleName)
                    }
            }

            val activeAlive = coRunCatching { configurationRepository.checkAvailable(active) }
                .onFailure { Timber.e(it) }
                .getOrDefault(false)

            configRefresh.await()

            if (activeAlive) {
                LoadTiming.span("startup", "address_ok", startedAt, "tag=${active.tag} background")
                // применит изменения адреса из обновлённого конфига; без изменений клиенты не пересоздаются
                apiConfig.updateActiveAddress(apiConfig.active)
                return@supervisorScope
            }

            LoadTiming.span("startup", "address_failed", startedAt, "tag=${active.tag}")
            val candidates = apiConfig.getAddresses().filter { it != active }
            val alive = configurationRepository.findFirstAvailable(candidates)
            if (alive != null) {
                LoadTiming.span("startup", "address_switched", startedAt, "tag=${alive.tag}")
                apiConfig.updateActiveAddress(alive)
            } else {
                LoadTiming.span("startup", "address_none", startedAt, "open config screen")
                apiConfig.updateNeedConfig(true)
            }
        }
    }
}
