# API host migration plan

Этот файл фиксирует целевую схему адресов приложения и остатки legacy API,
которые ещё нужно перенести на V1.

## Цель

Убрать жёстко прошитые домены из V1-слоя и вернуть один источник истины для:

- legacy API;
- V1 anime/accounts API;
- site/widgets/web переходов;
- image CDN;
- fallback-конфига и автопереключения адресов.

## Что уже сделано

- В `ApiAddress` добавлены отдельные поля `animeBase` и `accountsBase`.
- `ApiConfig` теперь резолвит V1-хосты через активный адрес, а не через
  жёсткий `https://anilibria.top`.
- V1-клиенты переведены на `animeBaseUrl` / `accountsBaseUrl`.
- Проверка доступности адреса в `ConfigurationApi` теперь может проверять не
  только legacy `api`, но и public V1 endpoint.
- Локальный `config.json` расширен полями `animeBase` и `accountsBase`.

## Migration status

This stage additionally moved these areas to V1:

- auth login/password
- auth otp get/login
- auth social login/authenticate flow
- auth logout with legacy fallback
- teams data

These areas are still on legacy/query API:

- feed
- menu
- page comments (`vkcomments`)
- donation details
- app update check
- youtube list
- legacy user profile fallback
- config bootstrap via `query=config`

Detailed dependency and contract map for the remaining legacy zones:

- `docs/legacy-api-contract-map.md`

## Целевая модель адреса

Каждый `address` в конфиге должен описывать полный сетевой профиль, который
можно выбрать автоматически или вручную:

- `widgetsSite`
- `site`
- `baseImages`
- `base`
- `api`
- `animeBase`
- `accountsBase`
- `ips`
- `proxies`

Практически это означает, что разные домены и CDN должны задаваться не
хардкодом в коде, а разными `address` в конфиге.

## Следующий этап реализации

1. Перевести остатки web/navigation-логики на новые адресные роли и убрать
   implicit assumptions про `www.anilibria.tv`.
2. Решить стратегию для image CDN:
   либо разные CDN как разные `address`,
   либо отдельный список CDN-кандидатов внутри адреса.
3. Уточнить поведение auto-select:
   либо адрес считается валидным только когда живы и legacy, и V1,
   либо V1-only адреса допускаются как полноценные.
4. Обновить runtime `config.json` на стороне сервиса, чтобы он действительно
   отдавал несколько адресов/вариантов, а не только один legacy-профиль.
5. После переноса legacy API убрать старые fallback-зависимости от
   `public/api/index.php`.

## Остатки legacy API

Эти классы всё ещё используют `apiConfig.apiUrl` и `query=...` схему:

- `data/.../api/YoutubeApi.kt`
- `data/.../api/TeamsApi.kt`
- `data/.../api/PageApi.kt`
- `data/.../api/MenuApi.kt`
- `data/.../api/DonationApi.kt`
- `data/.../api/AuthApi.kt`
  Legacy-зоны: `loadUser`, `acceptOtp`, `loadSocialAuth`
- `data/.../api/FeedApi.kt`
- `data/.../api/CheckerApi.kt`
- `data/.../api/ConfigurationApi.kt`
  Legacy-зона: загрузка config через `query=config`

Это основной список кандидатов на перенос с query-based API.

## Остатки legacy web/site-связки

Эти места всё ещё завязаны на старую web-схему:

- `data/.../api/PageApi.kt`
  Статические страницы через `apiConfig.baseUrl`
- `data/.../api/AuthApi.kt`
  `public/login.php`, `public/logout.php`, social redirect replace
- `data/.../mapper/CollectionMapper.kt`
  release links через `apiConfig.siteUrl`
- `data/.../mapper/ReleaseMapper.kt`
  release links через `apiConfig.siteUrl`
- `app-mobile/.../WebPlayerActivity.kt`
  release page base через `apiConfig.widgetsSiteUrl`
- `app-mobile/.../auth/main/AuthViewModel.kt`
  переход на `pages/login.php`
- `app-mobile/.../auth/main/AuthFragment.kt`
  переход на `pages/cp.php`
- `app-mobile/.../page/PageFragment.kt`
  HTML base URL через `apiConfig.siteUrl`
- `data/.../storage/MenuStorage.kt`
  локальное меню содержит прямой `https://www.anilibria.tv/`

## Доменные замечания

- `anilibria.top` уже используется как V1 API base.
- `anilibria.tv` / `www.anilibria.tv` пока остаётся web/legacy-частью.
- `aniliberty.top` в runtime-коде почти не используется, кроме `README`.
- `status.anilibria.top` пока не участвует в адресации приложения и может быть
  подключён позже отдельно как status/health source.
