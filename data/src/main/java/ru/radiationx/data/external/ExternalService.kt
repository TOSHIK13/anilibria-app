package ru.radiationx.data.external

/** Внешний сервис (AniList, Shikimori…). Возможности — отдельные интерфейсы, которые он реализует. */
interface ExternalService {
    /** Стабильный id: `anilist`, `shikimori`. */
    val id: String
    val title: String
}

/** Тайтл во внешнем сервисе; ключ сопоставления с AniLiberty — [malId]. */
data class ExternalLink(
    val malId: Int,
    val title: String? = null,
    /** Вес сервиса (голоса AniList); 0 — важен только порядок. */
    val weight: Int = 0,
)

/**
 * Страница «похожих». [count] — сколько тайтлов вернул сервис (в т.ч. без MAL id),
 * [hasNext] — есть следующая страница (нумерация с 1).
 */
data class SimilarPage(
    val links: List<ExternalLink>,
    val count: Int,
    val hasNext: Boolean,
)

/** Возможность: похожие тайтлы по MAL id. */
interface SimilarProvider : ExternalService {
    /** Страница [page] (с 1). Тайтла нет в сервисе — пустая страница без продолжения. */
    suspend fun similar(malId: Int, page: Int): SimilarPage
}

/** Запись списка пользователя во внешнем сервисе. */
data class RemoteListEntry(
    val malId: Int,
    val externalId: Long,
    /** Статус сервиса в сыром виде (для AniList: PLANNING/CURRENT/COMPLETED/…). */
    val status: String,
    val progress: Int,
    val score: Double?,
    /** epoch ms. */
    val updatedAt: Long,
)

/** Возможность: получить список пользователя (реализация — этап 4). */
interface UserListSource : ExternalService {
    suspend fun fetchUserList(): List<RemoteListEntry>
}
