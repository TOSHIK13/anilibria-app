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
- Проверенные ответы возвращали 6 символов, но приложение не должно визуально дополнять или обрезать код.
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
  - query: `page`, `limit`, `f[sorting]`, `f[genres]`, `f[years]`, `f[seasons]`, `f[publish_statuses]`
  - ответ: объект `{ data: [...], meta: { pagination: ... } }`
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
  и уменьшают ответ в разы.
- Счётчик избранного релиза: `added_in_users_favorites`.
- Постер: `poster.optimized.preview` → fallback; пути `/storage/...` отдают
  `www.anilibria.tv`, `anilibria.top`, `aniliberty.top`, `static.wwnd.space` (байт-в-байт одинаково).

## Проверка адреса

- `GET /api/v1/app/status` (~170 байт): `{request, is_alive, available_api_endpoints}` —
  используется как health-check адреса при старте и на экране конфигурации.

## Расписание

- `GET /api/v1/anime/schedule/week`
- Ответ: голый массив элементов, внутри каждого есть `release`.

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
