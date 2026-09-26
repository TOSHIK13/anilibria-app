package ru.radiationx.data.di.providers

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import ru.radiationx.data.datasource.remote.address.ApiConfigChanger
import ru.radiationx.data.system.ClientWrapper
import ru.radiationx.data.system.LoadTiming
import javax.inject.Inject

@OptIn(DelicateCoroutinesApi::class)
class ApiClientWrapper @Inject constructor(
    private val provider: ApiOkHttpProvider,
    configChanger: ApiConfigChanger,
) : ClientWrapper(provider) {

    init {
        configChanger
            .observeConfigChanges()
            .onEach {
                LoadTiming.mark("net", "api_client_recreated")
                set(provider.get())
            }
            .launchIn(GlobalScope)
    }

}