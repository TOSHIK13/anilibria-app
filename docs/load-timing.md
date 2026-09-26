# LoadTiming: замеры скорости загрузки

`ru.radiationx.data.system.LoadTiming` пишет в logcat с тегом `LoadTiming`
короткие строки о времени старта, сети и экранов.

- debug-сборка: включено всегда;
- release: выключено (почти нулевая стоимость), включается свойством и
  перезапуском процесса:

```powershell
adb shell setprop log.tag.LoadTiming DEBUG
adb shell am force-stop ru.radiationx.anilibria.app.tv.mod
```

Выключить: `adb shell setprop log.tag.LoadTiming INFO` (или перезагрузка ТВ).

## Холодный старт с замером

```powershell
adb shell setprop log.tag.LoadTiming DEBUG
adb shell pm trim-caches 999G        # чистит кэш картинок, вход сохраняется
adb shell am force-stop ru.radiationx.anilibria.app.tv.mod
adb logcat -c
adb shell am start -W -n ru.radiationx.anilibria.app.tv.mod/ru.radiationx.anilibria.screen.launcher.MainActivity
adb logcat -d -s LoadTiming ActivityTaskManager
```

## Формат

Все `+Nms` — время от старта процесса (`Process.getStartElapsedRealtime`).

```text
[startup] app_create +310ms
[startup] activity_create +520ms
[startup] config_skipped +540ms tag=api0        # быстрый старт по сохранённому конфигу
[startup] window_drawn +640ms
[startup] main_shown +700ms
[main] feed page=1 data 850ms +1560ms items=10  # длительность запроса ряда, затем время от старта
[main] feed page=1 rendered 870ms +1580ms
[startup] first_row_data +1560ms feed
[startup] first_image +1900ms source=NETWORK
[startup] address_ok 600ms +1150ms tag=api0 background
[net] GET anilibria.top/api/v1/anime/releases/latest 200 dns=3 conn=210 tls=150 ttfb=420 total=455 reused=no proto=h2 size=10240 client=api +1500ms
[details] release_cached 1ms +20100ms poster=abc.webp
[details] poster_loaded +20150ms file=abc.webp
```

- `[startup]` — вехи старта: `app_create`, `activity_create`, `window_drawn`,
  `config_skipped` | `config_start` → `config_loaded` → `address_ok` → `config_done`,
  `main_shown`, `first_row_data`, `first_image`; фоновая проверка:
  `config_refreshed`, `address_ok … background`, `address_failed`,
  `address_switched`, `address_none`.
- `[config] check <tag>` — проверка адреса через `/api/v1/app/status`.
- `[main] <row> page=N data|rendered|error` — ряды главной
  (`continue`, `feed`, `youtube`, `schedule`, `favorites`).
- `[watch] history restored|loaded|error <ms> items=N` — история просмотра:
  снимок с диска и загрузка `views/history` (все страницы).
- `[details]` — `open`, `release_cached` (из кэша ленты), `release_full`,
  `poster_start` / `poster_loaded` с именем файла: два разных файла подряд
  означают перезагрузку постера.
- `[net]` — одна строка на HTTP-вызов API/Main клиента (картинки Coil идут через
  API-клиент и тоже попадают сюда). Query не пишется, кроме legacy `?query=`.
  `dns/conn/tls` = `-`, если соединение переиспользовано (`reused=yes`).
- `[net] api_client_recreated`, `[img] loader_created|loader_recreated` —
  пересоздание OkHttpClient / Coil ImageLoader.

В debug-сборке `HttpLoggingInterceptor(BODY)` и Chucker заметно искажают
сетевые тайминги — сравнивать цифры лучше на release.

Выборка для сравнения:

```powershell
adb logcat -d -s LoadTiming | Select-String "\[startup\]|\[main\]"
```
