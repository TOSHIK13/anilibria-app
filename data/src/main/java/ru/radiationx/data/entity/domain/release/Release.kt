package ru.radiationx.data.entity.domain.release

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import ru.radiationx.data.entity.domain.types.ReleaseCode
import ru.radiationx.data.entity.domain.types.ReleaseId

/* Created by radiationx on 31.10.17. */

@Parcelize
data class Release(
    // base
    val id: ReleaseId,
    val code: ReleaseCode,
    val names: List<String>,
    val series: String?,
    val poster: String?,
    val torrentUpdate: Int,
    val status: String?,
    val statusCode: String?,
    val types: List<String>,
    val genres: List<String>,
    val voices: List<String>,
    val members: Members?,
    val year: String?,
    val season: String?,
    val days: List<String>,
    val description: String?,
    val announce: String?,
    val favoriteInfo: FavoriteInfo,
    val link: String?,
    val franchises: List<Franchise>,

    // full
    val showDonateDialog: Boolean,
    val blockedInfo: BlockedInfo,
    val moonwalkLink: String?,
    val episodes: List<Episode>,
    val sourceEpisodes: List<SourceEpisode>,
    val externalPlaylists: List<ExternalPlaylist>,
    val rutubePlaylist: List<RutubeEpisode>,
    val torrents: List<TorrentItem>,
    /** Сколько серий уже вышло (null — неизвестно). */
    val episodesAvailable: Int? = null,
    /** Возрастной рейтинг, например «16+» (V1 `age_rating.label`). */
    val ageRating: String? = null,
    /** Средняя длительность серии в минутах (V1 `average_duration_of_episode`). */
    val averageEpisodeDurationMin: Int? = null,
    /** Рейтинг Shikimori (V1 `shikimori.rating`). */
    val shikimoriRating: Double? = null,
    /** Голосов на Shikimori (V1 `shikimori.votes`). */
    val shikimoriVotes: Int? = null,
    /** id релиза на Shikimori (V1 `shikimori.id`); совпадает с id MyAnimeList. */
    val shikimoriId: Int? = null,
    /** Рейтинг MyAnimeList (V1 `mal.rating`). */
    val malRating: Double? = null,
    /** Голосов на MyAnimeList (V1 `mal.votes`). */
    val malVotes: Int? = null,
    /** MyAnimeList id (V1 `mal.id`, fallback `shikimori.id` — у Shikimori те же id). */
    val malId: Int? = null,
    /** Собственный рейтинг AniLibria (V1 `rating.average`); null — нет оценок. */
    val ownRatingAverage: Double? = null,
    /** Голосов за собственный рейтинг (V1 `rating.votes`). */
    val ownRatingVotes: Int? = null,
    /** Сколько пользователей добавили релиз в избранное / коллекции (V1 `added_in_*`). */
    val collectionStats: ReleaseCollectionStats? = null,
    /** Абсолютный URL фона 1920x1080 (V1 `background_covers[0].preview`). */
    val backgroundCover: String? = null,
    /** Английское название (V1 `name.english`); [titleEng] — последнее из [names], часто альтернативные. */
    val nameEnglish: String? = null,
) : Parcelable {


    companion object {
        const val STATUS_CODE_NOTHING = "0"
        const val STATUS_CODE_PROGRESS = "1"
        const val STATUS_CODE_COMPLETE = "2"
        const val STATUS_CODE_HIDDEN = "3"
        const val STATUS_CODE_NOT_ONGOING = "4"
    }

    val title: String?
        get() = names.firstOrNull()

    val titleEng: String?
        get() = names.lastOrNull()

    fun getFranchisesIds(): List<ReleaseId> {
        val ids = mutableListOf<ReleaseId>()
        franchises.forEach { franchise ->
            franchise.releases.forEach {
                ids.add(it.id)
            }
        }
        return ids
    }
}
