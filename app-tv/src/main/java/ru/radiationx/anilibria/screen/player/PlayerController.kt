package ru.radiationx.anilibria.screen.player

import kotlinx.coroutines.flow.MutableStateFlow
import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.entity.domain.types.EpisodeId
import ru.radiationx.shared.ktx.EventFlow
import javax.inject.Inject

class PlayerController @Inject constructor() {

    val data = MutableStateFlow<List<Release>?>(null)

    val settingsOverlayVisible = MutableStateFlow(false)

    val selectEpisodeRelay = EventFlow<EpisodeId>()

    fun reset() {
        data.value = null
        settingsOverlayVisible.value = false
    }
}
