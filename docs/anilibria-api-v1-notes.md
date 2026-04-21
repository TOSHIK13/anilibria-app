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

## Расписание

- `GET /api/v1/anime/schedule/week`
- Ответ: голый массив элементов, внутри каждого есть `release`.

## Известные оставшиеся legacy-зоны

- `CheckerApi`, `DonationApi`, `FeedApi`, `MenuApi`, `PageApi` comments, `YoutubeApi`, `TeamsApi`.
- Legacy social auth/fallback login/logout/user/acceptOtp оставлены как совместимый fallback.
- Torrents/franchises в V1 mapper пока не восстановлены полностью.
