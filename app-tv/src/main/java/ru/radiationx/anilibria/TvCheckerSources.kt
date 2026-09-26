package ru.radiationx.anilibria

import ru.radiationx.data.datasource.remote.common.CheckerReserveSources
import javax.inject.Inject

class TvCheckerSources @Inject constructor() : CheckerReserveSources {

    override val sources: List<String> = listOf(
        // Мод ставится с другим applicationId, поэтому обновления берём из своего форка.
        "https://raw.githubusercontent.com/TOSHIK13/anilibria-app/develop/check-tv.json",
        "https://github.com/TOSHIK13/anilibria-app/raw/develop/check-tv.json"
    )

    // Legacy `query=app_update` возвращает обновление мобильного приложения, не TV.
    override val useLegacyApi: Boolean = false
}