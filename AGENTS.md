# Agent Operating Guide

Этот файл предназначен для новых веток, новых чатов и новых агентов. Его задача:

- быстро ввести в рабочий контекст;
- не дать снова тратить время на уже известные ложные проблемы;
- задать каноничный способ сборки, установки и дебага;
- отделять проблемы окружения от проблем кода.

Если запрос связан с Android TV, сборкой APK, установкой на устройство, авторизацией или миграцией на новый API, сначала прочитай этот файл, затем:

1. `DEV.md`
2. `docs/tv-mod-build.md`
3. `docs/anilibria-api-v1-notes.md`

## 1. Главный принцип

Не изобретать новый процесс сборки, если в проекте уже есть рабочий.

Для TV release каноничная команда одна:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build-tv-app-release.ps1
```

Именно этот сценарий нужно считать эталонным. Если пользователь просит "собрать APK", по умолчанию сначала использовать его.

## 2. Что считать каноничным процессом

### TV release APK

Использовать только:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build-tv-app-release.ps1
```

Скрипт сам:

- поднимает корректный `JAVA_HOME`;
- поднимает корректный `ANDROID_HOME` / `ANDROID_SDK_ROOT`;
- использует локальный `GRADLE_USER_HOME`;
- использует локальный keystore;
- запускает правильную Gradle task;
- ищет итоговый APK в `D:/Ani/release-apks`;
- печатает SHA256.

Не нужно вручную пересобирать release через случайные Gradle task, если пользователь не просил иной вариант.

### TV debug APK

Для локальной проверки и установки на устройство использовать:

```powershell
$env:JAVA_HOME='D:\Ani\.gradle\codex-tools\jdk-extract\jdk-17.0.18+8'
$env:GRADLE_USER_HOME='D:\Ani\.gradle'
$env:ANDROID_HOME='D:\Ani\.gradle\codex-tools\android-sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:ANDROID_USER_HOME='D:\Ani\.gradle\codex-tools\android-user-home'
$env:PATH="$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"
.\gradlew.bat :app-tv:assembleAppDebug
.\gradlew.bat :app-tv:installAppDebug
```

Если нужен только APK без установки, достаточно `:app-tv:assembleAppDebug`.

### Mobile debug APK

Если менялся общий модуль `data` или другой shared-код, дополнительно проверять:

```powershell
.\gradlew.bat :app-mobile:assembleAppDebug
```

Это дешёвая страховка от поломки общего кода.

## 3. Что делать в первые 3 минуты

При новом запросе не нужно анализировать весь проект. Достаточно:

1. Понять, это задача про:
   - сборку;
   - установку на устройство;
   - дебаг через логи;
   - точечную правку кода;
   - миграцию API.
2. Прочитать только релевантные файлы.
3. Не делать `gradle sync`, полную индексацию и глубокий обход проекта без необходимости.

Если запрос про сборку TV APK, не нужно сначала копаться в коде. Сначала запускать существующий build script.

## 4. Частые ложные проблемы, на которые не нужно тратить время

### Ошибка вида:

```text
Could not resolve org.jetbrains.kotlin.plugin.parcelize:2.3.0
```

или:

```text
Plugin [id: 'com.android.application', version: '9.0.0', apply: false] was not found
```

Это в первую очередь признак проблем окружения / toolchain / Gradle setup, а не доказательство, что патч сломан.

Правильная реакция:

1. Не объявлять изменение "нерабочим" только по этому сообщению.
2. Проверить, используется ли каноничный сценарий сборки.
3. Проверить локальные пути:
   - `D:/Ani/.gradle/codex-tools/android-sdk`
   - `D:/Ani/.gradle/codex-tools/jdk-home.txt`
   - `D:/Ani/.gradle/codex-tools/local-release.jks`
4. Проверить, что используется локальный `GRADLE_USER_HOME=D:/Ani/.gradle`.
5. Только после этого делать выводы о самом коде.

Если Gradle падает на резолве плагинов до компиляции целевого модуля, нельзя писать пользователю, что проблема "в патче", пока не проверен стандартный build pipeline.

### "No connected devices!"

Это не ошибка приложения и не ошибка патча. Это просто отсутствие видимого ADB-устройства.

Сначала проверить:

```powershell
adb devices -l
```

Только если устройство видно, запускать `installAppDebug` или `adb install`.

## 5. Как отличать проблемы окружения от проблем кода

Проблема окружения:

- Gradle не находит JDK / SDK / plugin repository;
- не видно Android-устройства в `adb devices`;
- нет keystore;
- не настроены локальные пути;
- ошибка возникает до компиляции изменённых модулей.

Проблема кода:

- проект собирается, но приложение падает;
- есть runtime exception;
- API возвращает формат, который текущий parser не ожидает;
- ломается только конкретный экран / сценарий;
- логи указывают на JSON parsing, 401/403, null mapping, business logic.

Не путать одно с другим.

## 6. Как работать с устройством

Перед установкой APK:

```powershell
adb devices -l
```

После успешной установки приложение можно запустить так:

```powershell
adb shell monkey -p ru.radiationx.anilibria.app.tv.mod -c android.intent.category.LAUNCHER 1
```

Если пользователь просит "поставить на смартфон", алгоритм такой:

1. Проверить `adb devices -l`
2. Если устройство есть:
   - собрать при необходимости;
   - установить `:app-tv:installAppDebug` или `adb install`;
   - запустить приложение
3. Если устройства нет:
   - не выдумывать проблему в коде;
   - честно сообщить, что ADB не видит устройство.

## 7. Как работать с дебагом

Если пользователь просит дебаг, но просит не менять код:

- не вносить правки;
- сосредоточиться на logcat, ADB, сетевых запросах и воспроизведении;
- сначала собрать факты, потом гипотезы.

Если пользователь открыл Logcat и просит найти проблему:

1. Уточнить сценарий воспроизведения.
2. Получить логи именно вокруг этого сценария.
3. Искать:
   - `JsonDataException`
   - `HttpException`
   - `401/403`
   - `NullPointerException`
   - ошибки маппинга / parsing.

Не нужно заранее переписывать код без подтверждения по логам.

## 8. Что уже известно по API

Для работы с новым API не нужно заново исследовать всё с нуля. Основные проверенные факты уже собраны в:

[`docs/anilibria-api-v1-notes.md`](D:/Ani/docs/anilibria-api-v1-notes.md)

Ключевые выводы:

- account API и anime API живут на `https://anilibria.top`;
- часть V1 endpoints отдаёт голый массив;
- часть отдаёт объект `{ data, meta }`;
- `favorites` и `collections` это разные endpoints;
- `releases/list` возвращает объект, а не массив;
- рекомендации есть отдельным endpoint `/api/v1/anime/releases/recommended`;
- OTP code не нужно визуально дополнять или изменять.

Перед новой миграцией API сначала проверить эту заметку.

## 9. Где именно не нужно тратить время

Не нужно без необходимости:

- запускать полный анализ всего репозитория;
- синкать IDE ради одной правки;
- переписывать архитектуру;
- массово менять legacy-код без привязки к задаче;
- делать вывод о "сломавшемся патче" по ошибке окружения;
- заново искать известные V1 endpoints, если они уже задокументированы.

## 10. Ожидаемый стиль работы агента

Агент должен:

- вносить минимальные правки;
- сохранять существующую архитектуру;
- использовать уже существующие repository/api/mapper;
- сначала предлагать решение, затем править код;
- показывать только изменённые файлы или diff;
- проверять результат сборкой только на релевантных модулях;
- не делать лишних коммитов и не пушить без запроса пользователя.

## 11. Короткий практический чеклист

### Если задача "собрать TV APK"

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build-tv-app-release.ps1
```

### Если задача "поставить TV debug на телефон"

```powershell
adb devices -l
.\gradlew.bat :app-tv:installAppDebug
adb shell monkey -p ru.radiationx.anilibria.app.tv.mod -c android.intent.category.LAUNCHER 1
```

### Если менялся общий код

```powershell
.\gradlew.bat :app-tv:assembleAppDebug
.\gradlew.bat :app-mobile:assembleAppDebug
```

### Если ошибка похожа на Gradle/toolchain/plugin issue

- сначала проверять окружение;
- не списывать это на текущий патч;
- не тратить часы на код, пока не подтверждена воспроизводимость в правильной build-схеме.

## 12. Источник истины

Если есть расхождение между "как привык делать агент" и "как уже настроен этот workspace", выбирать workspace.

Для этого репозитория источник истины:

1. `AGENTS.md`
2. `DEV.md`
3. `docs/tv-mod-build.md`
4. `docs/anilibria-api-v1-notes.md`
5. реальные рабочие скрипты в `scripts/`
