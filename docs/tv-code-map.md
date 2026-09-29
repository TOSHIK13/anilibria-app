# TV code map

Короткая карта `app-tv` / `data`, чтобы агенты не искали структуру заново. Файлы >800 строк читать только через `Read` с `offset`/`limit` после `Grep` по нужному символу.

Пути ниже относительно:
- `A` = `app-tv/src/main/java/ru/radiationx/anilibria`
- `D` = `data/src/main/java/ru/radiationx/data`

## Модули

| Модуль | Назначение |
|---|---|
| `app-tv` | TV-приложение (Leanback + Compose), экраны, навигация, DI |
| `data` | общий слой данных: V1 API `anilibria.top`, репозитории, storage, player cache |
| `player-tv` | переиспользуемый TV-плеер (`ReusableTvPlayerView`, `TvPlayerUi`) |
| `quill-di` | DI (Quill) |
| `shared-app`, `shared-ktx`, `shared-android-ktx` | общие утилиты, image loader, расширения |
| `app-mobile` | мобильное приложение — вне фокуса, проверять только при правках `data` |

## app-tv: вход, DI, навигация

- `A/App.kt` — Application, поднимает Quill (`AppModule`, `DataModule`).
- `A/screen/launcher/MainActivity.kt` — единственная activity.
- `A/di/` — `AppModule`, `ActivityModule`, `NavigationModule`, `PlayerModule`, `SearchModule`, `MainPagesModule`, `UpdateModule`.
- `A/screen/Screens.kt` — объявления экранов; `A/common/fragment/GuidedRouter.kt`, `A/common/LibriaCardRouter.kt` — переходы.

## app-tv: экраны (`A/screen/…`)

| Экран | Fragment | ViewModel |
|---|---|---|
| Главная (вкладки) | `main/MainFragment.kt`, `mainpages/MainPagesFragment.kt` | `main/MainViewModel.kt`, `mainpages/MainPagesViewModel.kt` |
| Каталог / поиск | `search/SearchFragment.kt` | `search/SearchViewModel.kt` |
| Подсказки поиска | `suggestions/SuggestionsFragment.kt` (~690 строк) | `suggestions/SuggestionsChipsViewModel.kt` |
| Релиз | `details/DetailFragment.kt`; `details/collection/…`, `details/other/…` (guided) | `details/DetailsViewModel.kt` |
| Плеер | `player/ComposePlayerFragment.kt` (**~3240 строк**), `player/ComposePlayerSubmenu.kt` | `player/PlayerViewModel.kt` (**~1030 строк**) |
| Меню плеера | `player/episodes/`, `player/quality/`, `player/speed/`, `player/settings/PlayerBufferSettingsGuidedFragment.kt` | — |
| Коллекции | `collections/CollectionsFragment.kt` | `collections/CollectionsViewModel.kt` |
| Расписание | `schedule/ScheduleFragment.kt` | `schedule/ScheduleViewModel.kt` |
| Профиль | `profile/ProfileFragment.kt` | `profile/ProfileViewModel.kt` |
| Авторизация | `auth/main/`, `auth/credentials/`, `auth/otp/` (guided) | одноимённые `*ViewModel.kt` |
| Конфиг / сплэш | `config/ConfigFragment.kt` | `config/ConfiguringViewModel.kt` |
| Обновление | `update/UpdateFragment.kt` | `update/UpdateViewModel.kt` |
| YouTube | `youtube/YoutubeFragment.kt` | `youtube/YouTubeViewModel.kt` |

## app-tv: прочее

- `A/common/` — `BaseCardsViewModel`, `BaseRowsViewModel`, `CardItem`, `LibriaCard`, `LibriaDetails`, `FranchiseCard`, `ContinueWatchingLoader`, `DataConverters`.
- `A/ui/presenter/` — Leanback presenters (карточки, строки, детали).
- `A/ui/widget/` — кастомные view: `MainHeroView`, `ContinueCardView`, `CatalogChipView`, `FranchiseCardView`, `BrowseTitleView` и др.
- `A/extension/` — `RowsFragment`, `GradientBackgroundManager`.
- `A/similar/` — строки «Похожее»: `SimilarReleasesRepository`, `LiveSimilarReleasesSource`, `SimilarCacheStorage`; сервисы, HTTP, кеш и MAL↔release id — `data/.../external/` (`AniListService`, `ShikimoriService`, `ExternalServiceRegistry`, `ExternalHttpClient`, `ExternalDiskCache`, `IdResolver`).
- `A/watchnext/` — канал «Продолжить» на главном экране Android TV.
- `A/screen/player/HlsRollingPrefetcher.kt` — HLS-префетч.
- Ресурсы: `app-tv/src/main/res/` — `values/strings.xml`, `styles.xml`, `detail_styles.xml`, `guided_styles.xml`, `colors.xml`, `dimens.xml`.

## data

- `D/datasource/remote/api/` — V1 API: `AuthApi`, `ReleaseApi`, `FeedApi`, `SearchApi`, `ScheduleApi`, `CollectionApi`, `FavoriteApi`, `YoutubeApi`, `ConfigurationApi` и др.
- `D/datasource/remote/address/` — хосты и base URL (`ApiConfig`, `ApiAddress`, `ApiConfigChanger`).
- `D/repository/` — `ReleaseRepository`, `FeedRepository`, `SearchRepository`, `FavoriteRepository`, `CollectionRepository`, `HistoryRepository`, `WatchProgressRepository`, `EpisodeProgressRepository`, `AuthRepository`, …
- `D/datasource/storage/` — `AuthStorage`, `WatchHistoryStorage`, `HistoryStorage`, `ReleaseCardStorage`, `UserStorage`, `PreferencesStorage`.
- `D/player/` — `PlayerCacheDataSourceProvider`, `PlayerDataSourceProvider`, `PlayerBufferConfig` (кэш/буфер видео).
- `D/entity/` — доменные модели. Тесты: `data/src/test`.
- Формат ответов V1 — в `docs/anilibria-api-v1-notes.md`.

## Сборка

- `app-tv/build.gradle.kts`: flavor `app`, `applicationId=ru.radiationx.anilibria.app.tv.mod`, `-PtvBeta=true` → `.beta`.
- Debug: `scripts/tv/tv.ps1 build`; release: `scripts/build-tv-app-release.ps1` (см. `docs/tv-mod-build.md`).

Если структура поменялась — обнови этот файл в том же PR.
