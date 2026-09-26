package ru.radiationx.data.entity.domain.release

/**
 * Франшиза из V1 `GET /api/v1/anime/franchises/release/{id}`.
 * [parts] отсортированы по `sort_order` и включают текущий релиз.
 * Релизы частей краткие (без серий и жанров) — годятся для карточек.
 */
data class ReleaseFranchise(
    val id: String,
    val name: String,
    val nameEnglish: String?,
    /** Абсолютный URL картинки франшизы. */
    val image: String?,
    val firstYear: Int?,
    val lastYear: Int?,
    val totalReleases: Int?,
    val totalEpisodes: Int?,
    val parts: List<ReleaseFranchisePart>,
) {
    val releases: List<Release>
        get() = parts.map { it.release }
}

data class ReleaseFranchisePart(
    val sortOrder: Int,
    val release: Release,
)
