package ru.radiationx.data.tracker

import kotlinx.coroutines.flow.Flow
import ru.radiationx.data.entity.domain.collection.CollectionType
import ru.radiationx.data.entity.domain.types.ReleaseId

/**
 * Внешний сервис статистики просмотра (Shikimori, MyAnimeList и т. п.).
 *
 * Сейчас реализаций нет: интерфейс и [AnimeTrackerRegistry] — задел, чтобы подключение
 * сервиса не требовало правок в плеере, коллекциях и настройках. Подробности — `docs/trackers.md`.
 *
 * Заметки для реализаций:
 * - **Shikimori**: OAuth2, `redirect_uri=urn:ietf:wg:oauth:2.0:oob` — после входа сайт показывает
 *   код, который пользователь вводит на ТВ (ТВ показывает ссылку/QR, вход — с телефона).
 *   Лимит API — 5 rps / **90 rpm**; обязателен заголовок `User-Agent` с названием приложения
 *   (без него — 403). Отметки — `user_rates` через `/api/v2/user_rates`
 *   (`target_type=Anime`, `target_id` = id Shikimori, `status`, `episodes`).
 * - **MyAnimeList**: OAuth2 + PKCE без oob — нужен redirect на свой сервер-посредник, который
 *   примет код и отдаст токен ТВ (привязка вида «показать ссылку/код и ждать»).
 *   id MAL совпадает с id Shikimori.
 *
 * Методы событий вызываются из фонового потока [AnimeTrackerRegistry]; исключения ловит реестр,
 * но очередь/повтор и соблюдение лимитов сервиса — забота реализации.
 */
interface AnimeTracker {

    /** Стабильный id сервиса (ключ хранилища токенов, аналитики): `shikimori`, `mal`. */
    val id: String

    /** Название для UI: «Shikimori». */
    val title: String

    /** Фирменный цвет карточки сервиса (ARGB) или null — цвет по умолчанию. */
    val brandColor: Int?
        get() = null

    /** URL иконки сервиса или null. */
    val iconUrl: String?
        get() = null

    /** Как пользователь привязывает аккаунт этого сервиса на ТВ. */
    val linkMethod: TrackerLinkMethod

    fun observeState(): Flow<TrackerState>

    /**
     * Привязка. Для [TrackerLinkMethod.CodeEntry] — [code], введённый пользователем;
     * для [TrackerLinkMethod.ExternalConfirm] — ожидание подтверждения на другом устройстве
     * (приостанавливается до результата, отмена — отменой корутины). Итог — в [observeState].
     */
    suspend fun link(code: String? = null)

    suspend fun unlink()

    /** Серия отмечена просмотренной (переход «не просмотрена» → «просмотрена»). */
    suspend fun onEpisodeWatched(event: TrackerEpisodeWatchedEvent)

    /** Релиз перенесён в другую коллекцию AniLiberty (`collection == null` — убран из коллекций). */
    suspend fun onCollectionChanged(event: TrackerCollectionChangedEvent)
}

/** Способ привязки аккаунта сервиса. */
sealed class TrackerLinkMethod {

    /**
     * OAuth2 с oob-кодом (Shikimori): пользователь открывает [authorizeUrl] на телефоне/ПК
     * (ТВ показывает ссылку и QR), получает код и вводит его на ТВ → [AnimeTracker.link].
     */
    data class CodeEntry(val authorizeUrl: String) : TrackerLinkMethod()

    /**
     * Подтверждение на другом устройстве без ввода кода на ТВ (MAL через сервер-посредник,
     * device flow): ТВ показывает [verificationUrl] и [userCode] и ждёт в [AnimeTracker.link].
     */
    data class ExternalConfirm(
        val verificationUrl: String,
        val userCode: String?,
    ) : TrackerLinkMethod()
}

sealed class TrackerState {
    object NotLinked : TrackerState()

    data class Linked(val account: TrackerAccount) : TrackerState()

    /** Аккаунт был привязан, но сервис недоступен или токен отозван. */
    data class Error(val message: String, val account: TrackerAccount? = null) : TrackerState()
}

data class TrackerAccount(
    val nick: String,
    val avatarUrl: String?,
)

/**
 * Релиз AniLiberty и его id во внешних базах (V1 `shikimori.id`, `mal.id`).
 * id могут быть null: реестр дозагружает их перед отправкой в сервис.
 */
data class TrackerReleaseRef(
    val releaseId: ReleaseId,
    val shikimoriId: Long? = null,
    val malId: Long? = null,
) {
    val hasExternalIds: Boolean
        get() = shikimoriId != null || malId != null
}

data class TrackerEpisodeWatchedEvent(
    val release: TrackerReleaseRef,
    /** Номер отмеченной серии (ordinal); null — отмечены все серии сразу. */
    val episodeOrdinal: Float?,
    /** Сколько серий релиза досмотрено всего (для `user_rates.episodes`). */
    val episodesWatched: Int,
    /** Всего серий в релизе; null — неизвестно. */
    val episodesTotal: Int?,
)

data class TrackerCollectionChangedEvent(
    val release: TrackerReleaseRef,
    /** Новая коллекция; null — релиз убран из коллекций. */
    val collection: CollectionType?,
)
