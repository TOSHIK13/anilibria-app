package ru.radiationx.anilibria.screen.player

import ru.radiationx.data.entity.domain.release.PlayerSkips

data class Video(
    val url: String,
    val nextUrl: String?,
    val seek: Long,
    val title: String,
    val subtitle: String,
    val skips: PlayerSkips?,
    /**
     * true — серия уже играет в плеере как следующий MediaItem очереди (переход сделал ExoPlayer),
     * повторный setMediaItems/prepare не нужен.
     */
    val reuseLoadedItem: Boolean = false,
)
