# AniLiberty API V1 notes

Файл фиксирует проверенные детали V1 API, чтобы не восстанавливать их заново при следующих правках.

## База

- Public/API база: `https://anilibria.top`
- OpenAPI: `https://anilibria.top/storage/api/docs/v1?aniliberty-api-v1-docs.json`
- Основные публичные пути находятся под `/api/v1/anime`.
- Account paths находятся под `/api/v1/accounts`.

## Авторизация

- OTP get: `POST /api/v1/accounts/otp/get`
- OTP login: `POST /api/v1/accounts/otp/login`
- Login/password: `POST /api/v1/accounts/users/auth/login`
- Текущий пользователь: `GET /api/v1/accounts/users/me`
- V1 токен используется как `Authorization: Bearer <token>`.
- Legacy `401` не должен чистить V1 session token/user. Чистить V1 авторизацию нужно только на `401/403` от V1 account request с Authorization.

## OTP

- `otp.code` приходит строкой.
- Баг сервера: примерно 15% кодов приходят 5-значными (случайное число без ведущего нуля). Сервер хранит именно 5 цифр (`27055` находится, `027055` → 404), а сайт (`/app/auth/otp/linkDevice`) принимает только ровно 6 цифр, поэтому такой код ввести невозможно.
- Тот же `device_id` получает тот же код до `expired_at`; новый `device_id` сразу получает новый код.
- Обход: код никогда не дополняем и не обрезаем. Если код не `^\d{6}$`, `AuthRepository.getOtpInfo()` меняет `device_id` (`AuthHolder.resetDeviceId()`) и перезапрашивает, до 5 попыток. Если всё равно не 6 цифр, TV показывает "Код обновляется…" и повторяет запрос сам.
- `POST /otp/login`, пока код не введён на сайте, отвечает HTTP 500 `Server Error` (не 401, как в документации); неизвестный код — 404. Приложение трактует 500/401/404 от `/otp/login` как "Код ещё не введён на сайте" (`OtpNotAcceptedException`), кнопка "Готово" остаётся доступной. Сетевые ошибки не маскируются.
- `remaining_time` приходит числом секунд и используется для таймера.
- При истечении таймера пользователь обновляет код кнопкой.

## Коллекции и избранное

- Коллекции:
  - `GET /api/v1/accounts/users/me/collections/releases`
  - query: `type_of_collection`, `page`, `limit`
  - ответ: объект `{ data: [...], meta: { pagination: ... } }`
- Избранное:
  - `GET /api/v1/accounts/users/me/favorites/releases`
  - `POST /api/v1/accounts/users/me/favorites`
  - `DELETE /api/v1/accounts/users/me/favorites`
  - тело add/delete: `[{"release_id": 123}]`
  - favorites не являются коллекцией `PLANNED`.

## Релизы

- Детали релиза: `GET /api/v1/anime/releases/{idOrAlias}`
- Список по id: `GET /api/v1/anime/releases/list?ids=9886,8437`
  - ответ: объект `{ data: [...], meta: { pagination: ... } }`, не голый массив.
- Каталог: `GET /api/v1/anime/catalog/releases`
  - query: `page`, `limit`, `f[sorting]`, `f[genres]`, `f[years][from_year]`, `f[years][to_year]`, `f[seasons]`, `f[publish_statuses]`
  - годы — только диапазон: список `f[years]=1996,2001` сервер молча игнорирует (проверено 2026-09);
    `f[publish_statuses]` понимает `IS_ONGOING` и `IS_NOT_ONGOING`
  - ответ: объект `{ data: [...], meta: { pagination: { total, count, per_page, current_page, total_pages } } }`
- Случайный релиз: `GET /api/v1/anime/releases/random?limit=1`
  - ответ: голый массив релизов.
- Рекомендации: `GET /api/v1/anime/releases/recommended`
  - query: `limit`, optional `release_id`
  - ответ: голый массив релизов.

## Поиск и справочники

- Быстрый поиск: `GET /api/v1/app/search/releases?query=...`
  - ответ: голый массив релизов.
- Жанры: `GET /api/v1/anime/catalog/references/genres`
  - ответ: голый массив `{ id, name }`.
  - Для `f[genres]` нужны числовые id жанров, не названия.
- Годы: `GET /api/v1/anime/catalog/references/years`
  - ответ: голый массив чисел.
- Сезоны для catalog: `winter`, `spring`, `summer`, `autumn`.

## Лента и видео

- Последние релизы: `GET /api/v1/anime/releases/latest?limit=N`
  - голый массив, `limit` 1..50 (>50 → 422), `page` молча игнорируется.
  - `fresh_at` (ISO) == legacy `release.last` (unix) → `Release.torrentUpdate`.
  - порядок совпадает с `catalog/releases?f[sorting]=FRESH_AT_DESC`, поэтому
    дальше лента догружается из каталога (`{ data, meta }`, пагинация).
- Видео: `GET /api/v1/media/videos?limit=N`
  - голый массив, `limit` ≤ 50, пагинации нет.
  - `video_id` вместо `vid`, `url` — полная ссылка, `image` — объект
    `{preview, thumbnail, optimized{...}}` с относительными путями.
  - `created_at` == legacy youtube `timestamp`; `updated_at` у всех одинаковый — не использовать.
- Лента (`FeedRepository`) = слияние latest + videos по времени, 10 на страницу;
  на фикстурах точно повторяет legacy `query=feed` (см. `FeedMergerTest`).
- `include=` (в т.ч. вложенные `genres.name`) и `exclude=` работают на latest/catalog/videos
  и уменьшают ответ в разы. `releases/list?ids=` тоже понимает `include` (3 релиза:
  61 КБ → 11 КБ с полями ленты); карточки «Продолжить просмотр» берут его через
  `ReleaseApi.getShortReleasesByIds`, полный релиз для деталей грузится отдельно.
- Счётчик избранного релиза: `added_in_users_favorites`.
- Постер: `poster.optimized.preview` → fallback; пути `/storage/...` отдают
  `www.anilibria.tv`, `anilibria.top`, `aniliberty.top`, `static.wwnd.space` (байт-в-байт одинаково).

## Проверка адреса

- `GET /api/v1/app/status` (~170 байт): `{request, is_alive, available_api_endpoints}` —
  используется как health-check адреса при старте и на экране конфигурации.

## Расписание

- `GET /api/v1/anime/schedule/week`
- Ответ: голый массив элементов `{release, next_release_episode_number, full_season_is_released,
  published_release_episode}`.
  - `next_release_episode_number` — номер следующей серии (int или null);
  - `full_season_is_released` — boolean;
  - `published_release_episode` — объект серии (как в `episodes[]`, нужен `ordinal`) или null,
    если на этой неделе серия ещё не вышла.
- `ScheduleRepository.getScheduleInfo()` — кэш `releaseId → ReleaseScheduleInfo` на процесс.

## Доп. поля релиза (детали, latest, catalog, franchise)

- `age_rating`: `{value: "R16_PLUS", label: "16+", is_adult, description}` → `Release.ageRating = label`.
- `average_duration_of_episode`: int минут → `Release.averageEpisodeDurationMin`.
- `shikimori`: `{id, url, votes, rating: 7.46}` → `Release.shikimoriRating`.
- `background_covers`: массив `{preview, thumbnail}` (без `optimized`), только в деталях релиза;
  часто пустой. `preview` — 1920x1080 jpg, `thumbnail` — 32x18 → `Release.backgroundCover`.
- Серия (`episodes[]`, `latest_episode`): `preview` `{src, preview, thumbnail, optimized{src, preview, thumbnail}}`,
  `optimized.preview` — webp 720x405 → `Episode.previewUrl`; `duration` — секунды → `Episode.durationSec`.

## Франшизы

- `GET /api/v1/anime/franchises/release/{releaseId}` — голый массив франшиз (обычно 0 или 1).
- Франшиза: `{id (uuid), name, name_english, image{preview, thumbnail, optimized}, rating, first_year,
  last_year, total_releases, total_episodes, total_duration, total_duration_in_seconds, franchise_releases[]}`.
- `franchise_releases[]`: `{id, sort_order, release_id, franchise_id, release}`; `release` краткий
  (poster, name, year, type, episodes_total, age_rating, shikimori…, без `genres`/`episodes`),
  текущий релиз входит в список.
- В legacy `franchises` был внутри релиза; в V1 его нет → `ReleaseInteractor.loadFranchises()`.
  `loadWithFranchises()` для плееров оставлен как был (только сам релиз).

## История просмотра и таймкоды

Прогресс просмотра живёт только на сервере (общий с сайтом и телефоном), локально — кэш.

- `GET /api/v1/accounts/users/me/views/history?page=&limit=&include=`
  - ответ: `{ data: [...], meta: { pagination: { total, count, per_page, current_page, total_pages } } }`;
  - элемент — одна серия с таймкодом: `{id, time, user_id, is_watched, updated_at (ISO),
    release_episode_id, release_episode: {id, name, ordinal, duration, release_id, ...,
    release: {id, episodes_total, ...}}}`;
  - `include` сужает поля, вложенные пути через точку работают. Приложение просит
    `time,is_watched,updated_at,release_episode_id,release_episode.ordinal,release_episode.release_id,release_episode.release.episodes_total`;
    если в ответе нет `release_episode.release_id` — повтор без `include`;
  - порядок элементов на сервере не проверен — сортировка по `updated_at` на клиенте.
- `GET /api/v1/anime/releases/{id}/episodes/timecodes` — голый массив
  `{id, time, user_id, is_watched, updated_at, release_episode_id}` по сериям релиза.
- `GET /api/v1/anime/releases/episodes/{episodeUuid}/timecode` — один объект, 404 если таймкода нет.
- `POST /api/v1/accounts/users/me/views/timecodes` — тело `[{time, is_watched, release_episode_id}]`;
  `DELETE` туда же — тело `[{release_episode_id}]`.
- `GET views/timecodes?since=ISO` — массив троек `[episode_uuid, time, is_watched]` без release id
  и даты: годится только для инкрементального обновления уже известных серий (пока не используется).

Как использует приложение (`WatchProgressRepository`, `EpisodeProgressRepository`):

- при старте (с авторизацией) сразу публикуется снимок истории с диска
  (`SharedPreferences` data-prefs, ключ `data.watch_history_v1`), затем в фоне грузится
  `views/history`: страница 1 (`limit=100`), остальные по `meta.pagination.total_pages`
  параллельно (до 4 одновременно); не чаще раза в 10 минут за процесс;
- из истории строятся индикаторы на постерах (досмотрено N из `episodes_total`) и ряд
  «Продолжить просмотр» (главная и «Я смотрю»): релизы по убыванию `updated_at` последней серии,
  полностью досмотренные пропускаются; карточки — один `releases/list?ids=` на недостающие релизы;
- кэш таймкодов серий заполняется из истории, поэтому списки не делают запросы таймкодов по
  каждому релизу; детали/плеер по-прежнему один раз за процесс грузят `episodes/timecodes` релиза;
- неудачные POST/DELETE таймкодов попадают в очередь (`data.watch_pending_timecodes_v1`,
  на серию — последнее изменение, до 200 шт., 30 дней) и дожимаются перед следующей отправкой и
  перед загрузкой истории; 4xx (кроме 401/408/429) из очереди выбрасываются;
- при выходе из аккаунта снимок и очередь стираются, ряд скрывается.

## Известные оставшиеся legacy-зоны

V1-эквивалентов в OpenAPI нет для: app update, config, menu, donations, comments.
Есть только `/api/v1/app/status` (используется как health-check адреса).

- `MenuApi` (`query=link_menu`), `DonationApi` (`query=donation_details`),
  `PageApi` comments (`query=vkcomments`) — остаются legacy (TV их не использует на основных экранах).
- `TeamsApi` уже на V1. Лента (`FeedApi`) и YouTube (`YoutubeApi`) переведены на V1.
- Список социальных провайдеров (`AuthApi.loadSocialAuth`) захардкожен, сети не трогает.
- `CheckerApi` (обновления): TV (`TvCheckerSources.useLegacyApi = false`) берёт обновление только
  из `check-tv.json` форка TOSHIK13/anilibria-app (ветка `develop`, JSON без `{status,data}` обёртки).
  При новом релизе мода поднимать там `version_code` и ссылку на APK. Legacy
  `query=app_update` отдаёт обновление мобильного приложения, поэтому для TV не вызывается.
  Mobile по-прежнему: legacy → fallback на `check.json`.
- `ConfigurationApi` bootstrap: `query=config` на `www.anilibria.tv` оставлен, т.к. reserve
  `config.json` отличается материально (legacy: `api0`/`api2`/`api1`, reserve: только `api1`,
  проверено 2026-09-26). Запросы идут параллельно, берётся первый непустой ответ.
- Legacy профиль `query=user`: вызывается только при наличии cookie `PHPSESSID`
  (mobile legacy social auth). Без V1 токена и без cookie `loadUser` локально бросает
  `ApiError(401)` без сети. Legacy аватар-fallback для V1 профиля — тоже только при `PHPSESSID`.
- `public/login.php` после успешного V1 логина больше не вызывается.
- `public/logout.php` — fallback только если V1 logout упал и есть `PHPSESSID`; иначе
  ошибка V1 logout логируется и локальная сессия всё равно очищается.
- Legacy social auth (redirect без `state`) оставлен как fallback для mobile.
- `SearchApi` содержит `"query" to name` — это параметр поиска, не legacy `query=`.
- Torrents/franchises в V1 mapper пока не восстановлены полностью.
