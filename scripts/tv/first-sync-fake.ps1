# Debug-only (эмулятор emulator-5554, пакет debug): фейковый отчёт мастера первой синхронизации AniList.
# on  — включает фейковый вход (anilist-fake.ps1 linked) и кладёт filesDir/external/fake_first_sync.json;
#       в debug-сборке мастер берёт отчёт из файла и работает в dry-run (ничего не отправляет и не меняет).
# off — удаляет файл и фейковый вход (anilist-fake.ps1 off).
# Настоящий токен (не fake.test.token) в prefs — ничего не делаем.
param([Parameter(Mandatory)][ValidateSet('on', 'off')][string]$State)
. "$PSScriptRoot\env.ps1"
$dev = 'emulator-5554'
$pkg = 'ru.radiationx.anilibria.app.tv.mod.debug'
$prefs = "shared_prefs/${pkg}_datastorage.xml"
$token = (adb -s $dev shell "run-as $pkg grep -o 'external_anilist_token\`">[^<]*' $prefs" 2>$null) -join ''
if ($token -and $token -notmatch 'fake\.test\.token') { Write-Output 'Real AniList token found - not touching the account.'; return }

if ($State -eq 'off') {
    adb -s $dev shell "run-as $pkg rm -f files/external/fake_first_sync.json" | Out-Null
    & "$PSScriptRoot\anilist-fake.ps1" off
    return
}

$items = @(
    # matching
    @{ g = 'matching'; mal = 1; rel = 101; title = 'Клинок, рассекающий демонов'; l = 'CURRENT:5'; r = 'CURRENT:5'; eps = 12 },
    @{ g = 'matching'; mal = 2; rel = 102; title = 'Одна из многих'; l = 'COMPLETED:12'; r = 'COMPLETED:12'; eps = 12 },
    # differing
    @{ g = 'differing'; mal = 10; rel = 110; title = 'Sakamoto Days'; l = 'CURRENT:5'; r = 'CURRENT:3'; eps = 11 },
    @{ g = 'differing'; mal = 11; rel = 111; title = 'Фрирен, провожающая в последний путь'; l = 'COMPLETED:28'; r = 'CURRENT:24'; eps = 28 },
    @{ g = 'differing'; mal = 12; rel = 112; title = 'Магическая битва'; l = 'PAUSED:12'; r = 'DROPPED:12'; eps = 24 },
    @{ g = 'differing'; mal = 13; rel = 113; title = 'Ледяная стена'; l = 'CURRENT:4'; r = 'CURRENT:6'; eps = 12 },
    @{ g = 'differing'; mal = 14; rel = 114; title = '[Полнометражный фильм]'; l = 'COMPLETED:1'; r = 'PLANNING:0'; eps = 1; movie = $true },
    # only in AniList
    @{ g = 'remote'; mal = 20; rel = 120; title = 'Монолог фармацевта'; r = 'PLANNING:0'; eps = 24 },
    @{ g = 'remote'; mal = 21; rel = 121; title = 'Поднятие уровня в одиночку'; r = 'CURRENT:7'; eps = 12 },
    @{ g = 'remote'; mal = 22; rel = 122; title = 'Ван-Пис'; r = 'COMPLETED:12'; eps = 12 },
    # only in AniLiberty
    @{ g = 'local'; mal = 30; rel = 130; title = 'Тетрадь смерти'; l = 'COMPLETED:37'; eps = 37 },
    @{ g = 'local'; mal = 31; rel = 131; title = 'Ванпанчмен'; l = 'CURRENT:3'; eps = 12 },
    # not in catalog
    @{ g = 'missing'; mal = 40; title = 'Obscure Short OVA'; r = 'COMPLETED:1'; eps = 1; movie = $true },
    @{ g = 'missing'; mal = 41; title = 'Unlicensed Series'; r = 'CURRENT:2'; eps = 12 }
)
$json = @{ remoteTotal = 20; noMalId = 0; items = $items } | ConvertTo-Json -Depth 5
$tmp = Join-Path $env:TEMP 'fake_first_sync.json'
[IO.File]::WriteAllText($tmp, $json, (New-Object Text.UTF8Encoding($false)))
& "$PSScriptRoot\anilist-fake.ps1" linked
adb -s $dev push $tmp /data/local/tmp/fake_first_sync.json | Out-Null
adb -s $dev shell "run-as $pkg mkdir -p files/external; run-as $pkg cp /data/local/tmp/fake_first_sync.json files/external/fake_first_sync.json" | Out-Null
adb -s $dev shell "rm /data/local/tmp/fake_first_sync.json" | Out-Null
"fake first sync report: on"
