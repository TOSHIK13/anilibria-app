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
                // «Продолжить просмотр»: плеер на сохранённой серии, позицию он берёт из таймкода
                // серии (как «Продолжить» в деталях). Карточка релиза кладётся под плеер, чтобы
                // после выхода из плеера пользователь оказался в ней, а не на главной.
                val episodeId = libriaCard.continueInfo?.episodeId
                router.navigateTo(DetailsScreen(type.releaseId))
                if (episodeId != null) {
                    router.navigateTo(PlayerScreen(type.releaseId, episodeId))
                }
            }

            is LibriaCard.Type.Youtube -> {
                systemUtils.externalLink(type.link)
            }
        }
    }
}