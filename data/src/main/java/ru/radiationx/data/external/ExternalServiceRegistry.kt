package ru.radiationx.data.external

import javax.inject.Inject

/** Все внешние сервисы приложения. */
class ExternalServiceRegistry @Inject constructor(
    aniList: AniListService,
    shikimori: ShikimoriService,
) {
    val services: List<ExternalService> = listOf(aniList, shikimori)

    fun byId(id: String): ExternalService? = services.firstOrNull { it.id == id }

    inline fun <reified T> withCapability(): List<T> = services.filterIsInstance<T>()

    inline fun <reified T> withCapability(id: String): T? = byId(id) as? T
}
