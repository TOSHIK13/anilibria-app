# Debug-only: подставляет ФЕЙКОВЫЙ вход AniList в prefs debug-приложения на эмуляторе, чтобы проверить UI
# состояний без настоящего токена. Использование: anilist-fake.ps1 linked|expiring|expired|off
param([Parameter(Mandatory)][ValidateSet('linked', 'expiring', 'expired', 'off')][string]$State)
. "$PSScriptRoot\env.ps1"
$dev = 'emulator-5554'
$pkg = 'ru.radiationx.anilibria.app.tv.mod.debug'
$file = "shared_prefs/${pkg}_datastorage.xml"
adb -s $dev shell am force-stop $pkg | Out-Null
Start-Sleep -Seconds 1
# незавершённая запись оставляет .bak, из которого Android восстановил бы старые prefs
adb -s $dev shell "run-as $pkg rm -f $file.bak" | Out-Null
# убрать прежние external_anilist_* записи
adb -s $dev shell "run-as $pkg sed -i '/external_anilist_/d' $file" | Out-Null
if ($State -ne 'off') {
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $exp = switch ($State) { 'linked' { $now + 200 * 86400 } 'expiring' { $now + 5 * 86400 - 3600 } 'expired' { $now - 86400 } }
    $nowMs = $now * 1000
    $lines = "<string name=\`"external_anilist_token\`">fake.test.token</string>" +
        "<long name=\`"external_anilist_exp\`" value=\`"$exp\`" />" +
        "<long name=\`"external_anilist_viewer_id\`" value=\`"1\`" />" +
        "<string name=\`"external_anilist_name\`">TestUser</string>" +
        "<long name=\`"external_anilist_linked_at\`" value=\`"$nowMs\`" />"
    adb -s $dev shell "run-as $pkg sed -i 's|</map>|$lines</map>|' $file" | Out-Null
}
"anilist fake state: $State"
