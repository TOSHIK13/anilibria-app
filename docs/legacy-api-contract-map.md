# Legacy API Contract Map

This document links each remaining legacy/query-based API call to:

- the current data source class;
- the repository and UI flows that depend on it;
- the contract that must be preserved during migration;
- the expected V1 replacement or the current backend gap.

Use it as the working map for the next migration stages so the remaining
legacy cleanup can be done without rediscovering app dependencies.

## Status legend

- `has V1`: an official V1 endpoint already exists and can replace legacy logic.
- `no V1 in current notes`: no replacement was found in
  `docs/anilibria-api-v1-notes.md` and the previous OpenAPI audit.
- `internal bootstrap`: legacy call is still used to configure address profiles.

Already moved from legacy to V1 in auth:

- `otp/get`
- `otp/login`
- `otp/accept`
- `users/auth/login`
- `users/auth/social/{provider}/login`
- `users/auth/social/authenticate`
- `users/auth/logout`

## Remaining legacy areas

| Legacy query / flow | Source | Repository / feature entry points | Contract that UI/business logic expects | Replacement status | Migration notes |
| --- | --- | --- | --- | --- | --- |
| `query=feed` | `data/.../api/FeedApi.kt` | `FeedRepository`, mobile `FeedViewModel`, TV `MainFeedViewModel` | Returns a plain list of `FeedResponse` items where each item may contain `release`, `youtube`, or both. Existing mapper builds a mixed `FeedItem` list and feeds ads, release cache updates, share/copy actions, and the feed screen pagination. | `no V1 in current notes` | This is not a simple release list replacement. A V1 migration needs either a dedicated feed endpoint or a composition layer that merges V1 releases + V1 youtube/news blocks into the current mixed feed contract. |
| `query=link_menu` | `data/.../api/MenuApi.kt` | `MenuRepository`, mobile `OtherViewModel` | Returns a plain list of link items with `title`, optional `absoluteLink`, optional `sitePagePath`, optional `icon`. UI converts it into "Other" menu groups and uses it to open either external links or static in-app pages. | `no V1 in current notes` | Safe migration options: keep it as config/content JSON, move it into config payload, or add a small V1 content endpoint. There is no heavy business logic here, but the current contract must preserve `absoluteLink vs sitePagePath` routing. |
| `query=vkcomments` | `data/.../api/PageApi.kt` | `PageRepository`, mobile `VkCommentsViewModel` | Returns `baseUrl` and `script`. UI combines `baseUrl` with `release/{code}.html` and injects the returned JS snippet into the comments web page. `PageRepository.checkVkBlocked()` is a separate VK availability check and stays outside API migration. | `no V1 in current notes` | This is a web embed contract, not a normal JSON domain model. Migration must preserve both values exactly or replace the entire comment integration flow. |
| `query=donation_details` | `data/.../api/DonationApi.kt` | `DonationRepository`, mobile `MainViewModel`, `FeedViewModel`, `DonationDetailViewModel`, `DonationYooMoneyViewModel`, `DonationDialogViewModel`, `ReleaseInfoViewModel` | Returns a nested content model: cards for reminders, detail content blocks, named dialogs, and YooMoney form metadata. UI expects `button/caption/divider/header/section` content item types and dialog lookup by `tag`. | `no V1 in current notes` | This is a content-management payload, not a simple scalar API. Migration should keep the current typed content model or add a compatibility mapper. `createYooMoneyPayLink()` is not part of AniLibria API migration and can stay as-is. |
| `query=app_update` | `data/.../api/CheckerApi.kt` | `CheckerRepository`, mobile `CheckerViewModel`, mobile feed update warning, TV `UpdateViewModel`, TV `MainPagesViewModel` | Returns `update` object with `version_code`, `version_build`, `version_name`, `build_date`, `links`, `important`, `added`, `fixed`, `changed`. UI expects multiple download links with `file/site` type and a changelog split into sections. | `no V1 in current notes` | There is already a reserve JSON fallback via `CheckerReserveSources`. A future migration can move this to a static JSON source or a dedicated V1 app-update endpoint. Preserve the multi-link contract because both mobile and TV update flows depend on it. |
| `query=youtube` | `data/.../api/YoutubeApi.kt` | `YoutubeRepository`, mobile `YoutubeViewModel`, TV `MainYouTubeViewModel`, TV `YouTubeViewModel` | Returns paginated data mapped into `Paginated<YoutubeItem>`. UI expects classic page loading and item fields `id`, `title`, `image`, `vid`, `views`, `comments`, `timestamp`. Domain item derives external YouTube URL from `vid`. | `no V1 in current notes` | If migrated, preserve pagination semantics first. Feed also embeds youtube items, so a future unified solution should keep the same item shape for both `YoutubeApi` and `FeedApi`. |
| `query=user` | `data/.../api/AuthApi.kt::loadUser` | `AuthRepository.loadUser()`, mobile `MainViewModel`, mobile `OtherViewModel`, TV `AppLauncherViewModel`, social auth fallback path | Returns legacy profile data used when there is no V1 bearer token or when social auth fell back to the legacy flow. | `has V1` for primary profile, legacy kept as fallback | V1 profile already exists via `/api/v1/accounts/users/me/profile`. The remaining work is to remove the legacy fallback once all auth entry points always produce a valid V1 session token. |
| `query=config` | `data/.../api/ConfigurationApi.kt::getConfigFromApi` | `ConfigurationRepository`, `ConfiguringInteractor`, app startup/config selection flow | Returns the address profile list that seeds `ApiConfig`. It is also merged with reserve `config.json` from GitHub/Bitbucket. Startup logic depends on this to choose active address, then optional proxy fallback. | `internal bootstrap` | This is still the main bootstrap dependency on legacy API. A clean migration should move address profiles to a neutral JSON/V1 config source so the app can bootstrap without `public/api/index.php`. |

## By feature

### Feed

- Entry points:
  - `data/.../repository/FeedRepository.kt`
  - `app-mobile/.../feed/FeedViewModel.kt`
  - `app-tv/.../screen/main/MainFeedViewModel.kt`
- Current dependency:
  - mixed legacy feed payload
- Hidden coupling:
  - release cache update middleware;
  - feed ads insertion;
  - feed-level youtube cards;
  - share/copy/shortcut actions that rely on release/youtube domain objects.
- Migration impact:
  - do not replace this with plain catalog data unless the app feed UX is also redesigned.

### Other / menu / static links

- Entry points:
  - `data/.../repository/MenuRepository.kt`
  - `app-mobile/.../other/OtherViewModel.kt`
- Current dependency:
  - remote content menu
- Hidden coupling:
  - some items open browser URLs;
  - some items open internal static pages by `sitePagePath`.
- Migration impact:
  - a config-driven solution is likely simpler than inventing a heavy V1 API.

### Comments

- Entry points:
  - `data/.../repository/PageRepository.kt`
  - `app-mobile/.../comments/VkCommentsViewModel.kt`
- Current dependency:
  - web comments bootstrap
- Hidden coupling:
  - release code is interpolated into `baseUrl + "release/{code}.html"`;
  - returned `script` is injected into the page.
- Migration impact:
  - this should be treated as a web integration migration, not only as an API migration.

### Donations

- Entry points:
  - `data/.../repository/DonationRepository.kt`
  - `app-mobile/.../activities/main/MainViewModel.kt`
  - `app-mobile/.../feed/FeedViewModel.kt`
  - `app-mobile/.../donation/detail/DonationDetailViewModel.kt`
  - `app-mobile/.../donation/yoomoney/DonationYooMoneyViewModel.kt`
  - `app-mobile/.../donation/jointeam/DonationDialogViewModel.kt`
  - `app-mobile/.../release/details/ReleaseInfoViewModel.kt`
- Current dependency:
  - structured CMS-like payload
- Hidden coupling:
  - card reminder on feed/main screen;
  - dialog open by `tag`;
  - YooMoney special-case dialog identified by `DonationInfo.YOOMONEY_TAG`.
- Migration impact:
  - keep the content block taxonomy stable or rewrite all donation screens together.

### Updates

- Entry points:
  - `data/.../repository/CheckerRepository.kt`
  - `app-mobile/.../activities/updatechecker/CheckerViewModel.kt`
  - `app-mobile/.../feed/FeedViewModel.kt`
  - `app-tv/.../screen/update/UpdateViewModel.kt`
  - `app-tv/.../screen/mainpages/MainPagesViewModel.kt`
- Current dependency:
  - app update metadata
- Hidden coupling:
  - warning cards;
  - separate source selection;
  - file-vs-site link behavior;
  - changelog sections rendered independently.
- Migration impact:
  - this can be separated from AniLibria API and hosted as versioned static JSON if needed.

### YouTube

- Entry points:
  - `data/.../repository/YoutubeRepository.kt`
  - `app-mobile/.../youtube/YoutubeViewModel.kt`
  - `app-tv/.../screen/main/MainYouTubeViewModel.kt`
  - `app-tv/.../screen/youtube/YouTubeViewModel.kt`
- Current dependency:
  - paginated youtube list
- Hidden coupling:
  - same item shape is also embedded in feed items.
- Migration impact:
  - if backend adds a V1 endpoint, keep page semantics and item fields aligned with current domain model.

### Auth leftovers

- Entry points:
  - `data/.../repository/AuthRepository.kt`
  - `app-mobile/.../activities/main/MainViewModel.kt`
  - `app-mobile/.../other/OtherViewModel.kt`
  - `app-tv/.../screen/launcher/AppLauncherViewModel.kt`
- Current dependency:
  - legacy `user` fallback.
- Hidden coupling:
  - auth state may still be valid via PHP session cookie even without V1 bearer token;
  - social auth fallback path may end without a token and still rely on legacy user load.
- Migration impact:
  - remove these only after all auth flows are guaranteed to end with a V1 token or a deliberate no-auth state.

### Config bootstrap

- Entry points:
  - `data/.../api/ConfigurationApi.kt`
  - `data/.../repository/ConfigurationRepository.kt`
  - `data/.../interactors/ConfiguringInteractor.kt`
- Current dependency:
  - legacy config bootstrap plus reserve `config.json`
- Hidden coupling:
  - address auto-selection;
  - proxy fallback selection;
  - startup gating through `needConfig`.
- Migration impact:
  - treat this as infrastructure migration first, not as normal feature API work.

## Recommended migration order

1. Finish auth leftovers:
   - `loadUser` fallback
   - `acceptOtp`
2. Move config bootstrap away from legacy `query=config`.
3. Decide whether `menu`, `updates`, and `donation` should become:
   - V1 endpoints, or
   - config/static JSON content.
4. Design a dedicated replacement for `feed` and `youtube`:
   - either backend V1 endpoints,
   - or an app-side aggregator over V1 resources.
5. Re-evaluate `vkcomments` as a web integration problem.

## Practical rule for the next migration pass

When replacing any remaining legacy endpoint, preserve the existing repository
output type first. Change transport first, domain contract second.

That keeps mobile and TV UI stable while the backend source changes underneath.
