package ru.radiationx.data.entity.domain.feed

import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.entity.domain.schedule.ReleaseScheduleInfo

data class ScheduleItem(
    val releaseItem: Release,
    val completed: Boolean = false,
    val scheduleInfo: ReleaseScheduleInfo? = null,
)