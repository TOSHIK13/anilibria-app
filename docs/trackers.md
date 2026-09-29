# Внешние сервисы статистики (трекеры) — задел

Статус: **только инфраструктура, реализаций нет.** Код — `data/src/main/java/ru/radiationx/data/tracker/`.

## Что есть

- `AnimeTracker` — интерфейс сервиса: `id` (стабильная строка: `shikimori`, `mal`), `title`,
  опционально `brandColor`/`iconUrl`, `linkMethod`, `observeState(): Flow<TrackerState>`,
  `link(code?)` / `unlink()`, события `onEpisodeWatched(...)` и `onCollectionChanged(...)`.
- `TrackerState`: `NotLinked` / `Linked(TrackerAccount(nick, avatarUrl))` / `Error(message, account?)`.
- `TrackerLinkMethod`:
  - `CodeEntry(authorizeUrl)` — OAuth2 с oob-кодом (Shikimori): ТВ показывает ссылку/QR,
    пользователь входит на телефоне, видит код и вводит его на ТВ;
  - `ExternalConfirm(verificationUrl, userCode?)` — «показать ссылку/код и ждать»
    (MAL: OAuth2 + PKCE без oob, нужен сервер-посредник, который примет redirect и отдаст токен ТВ).
- `AnimeTrackerRegistry` (singleton, `DataModule`): `trackers`, `availableToLink`, `observeEntries()`,
  `observeLinked()`, `dispatchEpisodeWatched(...)`, `dispatchCollectionChanged(...)`.
  При пустом `trackers` всё — no-op. Рассылка асинхронная (свой scope), ошибки сервисов
  ловятся и логируются (Timber), основной поток не ломается. Если в событии нет id
  Shikimori/MAL — реестр дозагружает релиз (`ReleaseRepository.getRelease`) только когда есть
  привязанный сервис.

## Где вызываются события

- `EpisodeProgressRepository.setAccessSeek(..., forceViewed)` / `importAccess` — при переходе серии
  «не просмотрена → просмотрена» (то же место, где на сервер уходит `is_watched=true`);
  `markAllViewed(release)` — одно событие на весь релиз (`episodeOrdinal = null`).
  `episodesWatched` — число досмотренных серий релиза из `WatchProgressRepository.currentProgress`.
- `CollectionRepository.setReleaseCollection` — после успешного запроса (`collection = null` — удалён).

## id релиза во внешних базах

V1 отдаёт у релиза `shikimori: {id, url, votes, rating}` и `mal: {id, url, votes, rating}`;
id совпадают (Shikimori использует id MAL). Маппятся в `Release.shikimoriId` / `Release.malId`.

## Заметки для реализации Shikimori

- Лимит API: 5 rps и **90 rpm** — нужна очередь с ограничением частоты.
- Обязателен заголовок `User-Agent` с названием OAuth-приложения (без него 403).
- Отметки: `user_rates` через `/api/v2/user_rates` (`user_id`, `target_id` = shikimoriId,
  `target_type=Anime`, `status`, `episodes`). Соответствие коллекций:
  PLANNED→planned, WATCHING→watching, WATCHED→completed, POSTPONED→on_hold, ABANDONED→dropped.
- Токены хранить по `AnimeTracker.id`; refresh token — до выхода/отвязки.

## UI (TV)

Настройки → «Аккаунты и сервисы»: после карточки AniLiberty — привязанные сервисы из
`observeLinked()`; строка «Подключить сервис» — только если `availableToLink` не пуст.
Пока сервисов нет, блок «Сервисы статистики» не показывается.

## Модуль внешних сервисов (`data/.../external/`)

Общая инфраструктура под AniList/Shikimori (трекеры — отдельно, см. выше).

- SPI: `ExternalService(id, title)`; возможности — `SimilarProvider.similar(malId, page): SimilarPage`,
  `UserListSource.fetchUserList(): List<RemoteListEntry>` (интерфейс, реализация позже).
  `ExternalServiceRegistry` — `services`, `byId`, `withCapability<T>()`.
- `ExternalHttpClient` — по экземпляру на сервис: интервал между запросами (AniList 2100 мс,
  Shikimori 700 мс), 429 → ждать `Retry-After` (до 30 с, 2 повтора; дольше — `ExternalRateLimitException`),
  User-Agent, опциональный Bearer (`tokenProvider`), дедупликация одинаковых запросов в полёте.
  Каждый реальный запрос — Timber `external[<сервис>]: METHOD url` (для проверки «нет дублей»).
  `AniListGraphQl.query(query, variables)` — обёртка для `https://graphql.anilist.co`.
- `ExternalDiskCache(namespace, maxItems, ttlMs?)` — JSON-файлы filesDir/<namespace>/<key>.json, LRU.
  «Похожие» (`SimilarCacheStorage`) лежат в прежнем `similar/items`.
- `IdResolver` — MAL id ↔ release id по каталогу (`releaseIdsByMalId`, `malIdByReleaseId`, TTL 24 ч).
