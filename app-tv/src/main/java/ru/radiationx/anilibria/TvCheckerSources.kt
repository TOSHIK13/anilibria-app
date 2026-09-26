package ru.radiationx.anilibria

import ru.radiationx.data.datasource.remote.common.CheckerReserveSources
import javax.inject.Inject

class TvCheckerSources @Inject constructor() : CheckerReserveSources {

    override val sources: List<String> = listOf(
        "https://github.com/anilibria/anilibria-app/raw/master/check-tv.json",
        "https://bitbucket.org/RadiationX/anilibria-app/raw/master/check-tv.json"
    )

    // Legacy `query=app_update` возвращает обновление мобильного приложения, не TV.
    override val useLegacyApi: Boolean = false
}