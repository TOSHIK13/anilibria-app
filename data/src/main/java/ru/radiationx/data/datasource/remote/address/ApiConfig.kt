package ru.radiationx.data.datasource.remote.address

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import ru.radiationx.data.datasource.remote.Api
import ru.radiationx.data.datasource.storage.ApiConfigStorage
import ru.radiationx.data.entity.mapper.toDomain
import javax.inject.Inject

class ApiConfig @Inject constructor(
    private val configChanger: ApiConfigChanger,
    private val apiConfigStorage: ApiConfigStorage,
) {
    companion object {
        private val DEFAULT_V1_ANIME_BASE = Api.DEFAULT_ADDRESS.animeBase.orEmpty()
        private val DEFAULT_V1_ACCOUNTS_BASE = Api.DEFAULT_ADDRESS.accountsBase.orEmpty()
    }

    private val addresses = mutableListOf<ApiAddress>()
    private var activeAddressTag: String = ""
    private val possibleIps = mutableListOf<String>()
    private val proxyPings = mutableMapOf<String, Float>()

    private val needConfigRelay = MutableSharedFlow<Boolean>()
    var needConfig = true

    /**
     * Есть сохранённый конфиг и сохранённый активный адрес, который в нём есть:
     * можно стартовать сразу на нём, а проверку делать в фоне.
     */
    var hasSavedConfig: Boolean = false
        private set

    /** Под что сейчас собран API OkHttpClient (см. [updateActiveAddress]). */
    private var appliedClientKey: ClientKey? = null

    init {
        // todo TR-274 make api config async
        runBlocking {
            val savedActiveTag = apiConfigStorage.getActive()
            val savedConfig = apiConfigStorage.get()?.toDomain()
            activeAddressTag = savedActiveTag ?: Api.DEFAULT_ADDRESS.tag
            val initAddresses = savedConfig ?: ApiConfigData(listOf(Api.DEFAULT_ADDRESS))
            setConfig(initAddresses)
            hasSavedConfig = savedActiveTag != null &&
                    savedConfig?.addresses?.any { it.tag == savedActiveTag } == true
            appliedClientKey = clientKey()
        }
    }

    fun observeNeedConfig(): Flow<Boolean> = needConfigRelay

    suspend fun updateNeedConfig(state: Boolean) {
        needConfig = state
        needConfigRelay.emit(needConfig)
    }

    /**
     * Пересоздаёт API-клиенты (и Coil ImageLoader) только если изменилось то, из чего
     * собирается OkHttpClient ([ClientKey]: адрес и его прокси). URL-ы (base, animeBase,
     * картинки) читаются из конфига на каждый запрос и пересоздания не требуют.
     */
    suspend fun updateActiveAddress(address: ApiAddress) {
        activeAddressTag = address.tag
        apiConfigStorage.setActive(activeAddressTag)
        val newKey = clientKey()
        val changed = synchronized(this) {
            (appliedClientKey != newKey).also { appliedClientKey = newKey }
        }
        if (changed) {
            configChanger.onChange()
        }
    }

    /** Данные, от которых зависит ApiOkHttpProvider. */
    private data class ClientKey(
        val tag: String,
        val inAddresses: Boolean,
        val proxies: List<ApiProxy>,
    )

    private fun clientKey(): ClientKey {
        val active = active
        return ClientKey(
            tag = active.tag,
            inAddresses = getAddresses().any { it.tag == active.tag },
            proxies = active.proxies.map { it.copy() },
        )
    }

    fun setProxyPing(proxy: ApiProxy, ping: Float) {
        proxyPings[proxy.tag] = ping
        proxy.ping = ping
    }

    @Synchronized
    fun setConfig(configData: ApiConfigData) {
        val items = configData.addresses
        addresses.clear()
        /*if (items.find { it.tag == Api.DEFAULT_ADDRESS.tag } == null) {
            addresses.add(Api.DEFAULT_ADDRESS)
        }*/
        addresses.addAll(items)

        possibleIps.clear()
        val ips = addresses
            .map { address ->
                address.ips + address.proxies.map { it.ip }
            }
            .reduce { acc, list -> acc.plus(list) }
            .toSet()
            .toList()
        possibleIps.addAll(ips)

        addresses.forEach { address ->
            address.proxies.forEach { proxy ->
                proxyPings[address.tag]?.also {
                    proxy.ping = it
                }
            }
        }
    }

    @Synchronized
    fun getAddresses(): List<ApiAddress> = addresses.toList()

    @Synchronized
    fun getPossibleIps(): List<String> = possibleIps.toList()

    val active: ApiAddress
        get() = addresses.firstOrNull { it.tag == activeAddressTag } ?: Api.DEFAULT_ADDRESS

    val tag: String
        get() = active.tag

    val name: String?
        get() = active.name

    val desc: String?
        get() = active.desc

    val widgetsSiteUrl: String
        get() = active.widgetsSite

    val siteUrl: String
        get() = active.site

    val baseImagesUrl: String
        get() = active.baseImages

    val baseUrl: String
        get() = active.base

    val animeBaseUrl: String
        get() = active.animeBase.normalizeBaseUrl(DEFAULT_V1_ANIME_BASE)

    val accountsBaseUrl: String
        get() = active.accountsBase
            .normalizeBaseUrl(active.animeBase.normalizeBaseUrl(DEFAULT_V1_ACCOUNTS_BASE))

    val accountBaseUrl: String
        get() = accountsBaseUrl

    val apiUrl: String
        get() = active.api

    val ips: List<String>
        get() = active.ips

    val proxies: List<ApiProxy>
        get() = active.proxies

    private fun String?.normalizeBaseUrl(fallback: String): String {
        return this
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.isNotEmpty() }
            ?: fallback.trimEnd('/')
    }
}
