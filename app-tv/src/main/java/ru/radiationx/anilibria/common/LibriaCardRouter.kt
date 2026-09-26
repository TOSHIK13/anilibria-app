package ru.radiationx.anilibria.common

import com.github.terrakok.cicerone.Router
import ru.radiationx.anilibria.screen.DetailsScreen
import ru.radiationx.anilibria.screen.PlayerScreen
import ru.radiationx.shared_app.common.SystemUtils
import javax.inject.Inject

class LibriaCardRouter @Inject constructor(
    private val router: Router,
    private val systemUtils: SystemUtils
) {

    fun navigate(libriaCard: LibriaCard) {
        when (val type = libriaCard.type) {
            is LibriaCard.Type.Release -> {
                // «Продолжить просмотр»: сразу плеер на сохранённой серии, позицию он берёт
                // из таймкода серии (как «Продолжить» в деталях).
                val episodeId = libriaCard.continueInfo?.episodeId
                if (episodeId != null) {
                    router.navigateTo(PlayerScreen(type.releaseId, episodeId))
                } else {
                    router.navigateTo(DetailsScreen(type.releaseId))
                }
            }

            is LibriaCard.Type.Youtube -> {
                systemUtils.externalLink(type.link)
            }
        }
    }
}