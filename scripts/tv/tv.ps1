<#
Compact helper for building and driving the TV app. Output is deliberately short (token-cheap).

  powershell -File scripts/tv/tv.ps1 <command> [args] [-Device emu|tv|<serial>]

Commands:
  devices                      adb devices -l
  emu                          start AVD Ani_TV_API_36 (headless window) if no emulator is running
  build                        :app-tv:assembleAppDebug, prints only errors + result
  install [apk]                adb install -r (default: latest app-tv debug APK of this checkout)
  start | stop | restart       launch / force-stop the app
  keys "DOWN DOWN CENTER" [-Delay 0.5]   send D-pad keys (UP DOWN LEFT RIGHT CENTER BACK HOME MENU PLAY or any KEYCODE_*; "DOWN*3" repeats)
  text "query"                 type text into the focused field
  shot <name> [-Width 960] [-Crop x,y,w,h]   screenshot, downscaled, prints the PNG path
  focus                        focused view as text (class, id, text, bounds) via uiautomator - prefer over shot
  log [-Lines 60] [-Pattern re]  app logcat (errors/exceptions by default)
  scenario <file>              run a script: one command per line (keys ... | wait <sec> | shot <name> | focus | log | start | restart | text ...)

Device: -Device emu (emulator-5554, default), tv (192.168.1.151:5555) or any serial; or $env:ANI_TV_DEVICE.
Package: -Variant debug (default, ru....mod.debug, side-by-side with release) or release (ru....mod).
Screenshots go to -Out (default $env:TEMP\ani-tv-shots).
#>
param(
    [Parameter(Position = 0)][string]$Command = 'help',
    [Parameter(Position = 1)][string]$Arg,
    [string]$Device = $(if ($env:ANI_TV_DEVICE) { $env:ANI_TV_DEVICE } else { 'emu' }),
    [ValidateSet('debug', 'release')][string]$Variant = $(if ($env:ANI_TV_VARIANT) { $env:ANI_TV_VARIANT } else { 'debug' }),
    [double]$Delay = 0.5,
    [int]$Width = 960,
    [string]$Crop,
    [int]$Lines = 60,
    [string]$Pattern = '^[EF]/|FATAL|Exception',
    [string]$Out = (Join-Path $env:TEMP 'ani-tv-shots')
)
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
. (Join-Path $PSScriptRoot 'env.ps1')
$pkg = if ($Variant -eq 'release') { 'ru.radiationx.anilibria.app.tv.mod' } else { 'ru.radiationx.anilibria.app.tv.mod.debug' }
$serial = switch ($Device) { 'emu' { 'emulator-5554' } 'tv' { '192.168.1.151:5555' } default { $Device } }

$adbExe = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
function AdbQuiet { $o = & $adbExe -s $serial @args 2>&1; if ($LASTEXITCODE -ne 0) { throw ($o | Out-String) } }

function Send-Keys([string]$spec) {
    foreach ($tok in ($spec -split '\s+' | Where-Object { $_ })) {
        $n = 1
        if ($tok -match '^(.+)\*(\d+)$') { $tok = $Matches[1]; $n = [int]$Matches[2] }
        $code = switch ($tok.ToUpper()) {
            'UP' { 'KEYCODE_DPAD_UP' } 'DOWN' { 'KEYCODE_DPAD_DOWN' } 'LEFT' { 'KEYCODE_DPAD_LEFT' }
            'RIGHT' { 'KEYCODE_DPAD_RIGHT' } 'CENTER' { 'KEYCODE_DPAD_CENTER' } 'OK' { 'KEYCODE_DPAD_CENTER' }
            'BACK' { 'KEYCODE_BACK' } 'HOME' { 'KEYCODE_HOME' } 'MENU' { 'KEYCODE_MENU' }
            'PLAY' { 'KEYCODE_MEDIA_PLAY_PAUSE' } default { if ($tok -like 'KEYCODE_*') { $tok } else { "KEYCODE_$($tok.ToUpper())" } }
        }
        for ($i = 0; $i -lt $n; $i++) { AdbQuiet shell input keyevent $code; Start-Sleep -Milliseconds ([int]($Delay * 1000)) }
    }
}

function Take-Shot([string]$name) {
    if (-not $name) { $name = 'shot' }
    New-Item -ItemType Directory -Force $Out | Out-Null
    $raw = Join-Path $Out "$name.raw.png"; $dst = Join-Path $Out "$name.png"
    AdbQuiet shell screencap -p /sdcard/ani_shot.png
    AdbQuiet pull /sdcard/ani_shot.png $raw
    Add-Type -AssemblyName System.Drawing
    $img = [System.Drawing.Image]::FromFile($raw)
    try {
        $src = New-Object System.Drawing.Rectangle 0, 0, $img.Width, $img.Height
        if ($Crop) { $c = $Crop -split ',' | ForEach-Object { [int]$_ }; $src = New-Object System.Drawing.Rectangle $c[0], $c[1], $c[2], $c[3] }
        $w = [Math]::Min($Width, $src.Width); $h = [int]($src.Height * $w / $src.Width)
        $bmp = New-Object System.Drawing.Bitmap $w, $h
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.InterpolationMode = 'HighQualityBicubic'
        $g.DrawImage($img, (New-Object System.Drawing.Rectangle 0, 0, $w, $h), $src, 'Pixel')
        $g.Dispose(); $bmp.Save($dst, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
    } finally { $img.Dispose() }
    Remove-Item $raw
    "shot: $dst"
}

function Show-Focus {
    $xml = Join-Path $env:TEMP 'ani_ui.xml'
    $o = & $adbExe -s $serial shell uiautomator dump /sdcard/ani_ui.xml 2>&1
    if ($LASTEXITCODE -ne 0 -or ($o -match 'ERROR')) { "focus: uiautomator failed ($o) - use shot"; return }
    AdbQuiet pull /sdcard/ani_ui.xml $xml
    [xml]$doc = [System.IO.File]::ReadAllText($xml, [System.Text.Encoding]::UTF8)
    $nodes = $doc.SelectNodes("//node[@focused='true']")
    if (-not $nodes.Count) { 'focus: nothing focused'; return }
    foreach ($n in $nodes) {
        $texts = @($n.SelectNodes('.//node') | ForEach-Object { $_.text; $_.'content-desc' } | Where-Object { $_ } | Select-Object -First 6) -join ' | '
        $id = $n.'resource-id' -replace '^.*:id/', ''
        "focus: $($n.class -replace '^.*\.', '') id=$id text='$($n.text)' desc='$($n.'content-desc')' bounds=$($n.bounds) inner='$texts'"
    }
}

function Show-Log {
    $appPid = (& $adbExe -s $serial shell pidof $pkg 2>$null)
    $args2 = @('logcat', '-d', '-v', 'brief')
    if ($appPid) { $args2 += "--pid=$($appPid.Trim())" }
    & $adbExe -s $serial @args2 2>$null | Select-String -Pattern $Pattern | Select-Object -Last $Lines | ForEach-Object { $_.Line }
}

function Invoke-One([string]$cmd, [string]$arg) {
    switch ($cmd) {
        'devices' { & $adbExe devices -l }
        'emu' {
            if ((& $adbExe devices) -match 'emulator-\d+\s+device') { 'emulator already running' }
            else {
                # stale crash reports make the emulator wait on a hidden "send report?" dialog and never boot
                Remove-Item (Join-Path $env:TEMP 'AndroidEmulator\emu-crash-*') -Recurse -Force -ErrorAction SilentlyContinue
                Start-Process emulator -ArgumentList '-avd', 'Ani_TV_API_36', '-no-snapshot-save', '-no-boot-anim' -WindowStyle Minimized; & $adbExe wait-for-device; 'emulator booting' }
        }
        'build' {
            Push-Location $aniRepo
            try {
                $log = & .\gradlew.bat :app-tv:assembleAppDebug --console=plain -q 2>&1 | ForEach-Object { "$_" }
                $bad = $log | Select-String -Pattern '^e: |error:|FAILURE|What went wrong|BUILD FAILED|Could not' | Select-Object -First 40
                if ($LASTEXITCODE -ne 0) { $bad | ForEach-Object { $_.Line }; if (-not $bad) { $log | Select-Object -Last 30 }; throw 'BUILD FAILED' }
                'BUILD OK: ' + (Get-ChildItem app-tv\build\outputs\apk -Recurse -Filter *debug*.apk | Sort-Object LastWriteTime | Select-Object -Last 1).FullName
            } finally { Pop-Location }
        }
        'install' {
            $apk = $arg
            if (-not $apk) { $apk = (Get-ChildItem (Join-Path $aniRepo 'app-tv\build\outputs\apk') -Recurse -Filter *debug*.apk | Sort-Object LastWriteTime | Select-Object -Last 1).FullName }
            AdbQuiet install -r $apk; "installed: $apk -> $serial"
        }
        'start' { AdbQuiet shell monkey -p $pkg -c android.intent.category.LAUNCHER 1; 'started' }
        'stop' { AdbQuiet shell am force-stop $pkg; 'stopped' }
        'restart' { AdbQuiet shell am force-stop $pkg; AdbQuiet shell monkey -p $pkg -c android.intent.category.LAUNCHER 1; 'restarted' }
        'keys' { Send-Keys $arg; "keys: $arg" }
        'text' { AdbQuiet shell input text ($arg -replace ' ', '%s'); "text: $arg" }
        'wait' { Start-Sleep -Milliseconds ([int]([double]$arg * 1000)) }
        'shot' { Take-Shot $arg }
        'focus' { Show-Focus }
        'log' { Show-Log }
        default { Get-Content $PSCommandPath -TotalCount 21 | Select-Object -Skip 1 }
    }
}

if ($Command -eq 'scenario') {
    foreach ($line in Get-Content $Arg -Encoding UTF8) {
        $line = $line.Trim(); if (-not $line -or $line.StartsWith('#')) { continue }
        $parts = $line -split '\s+', 2
        $a = if ($parts.Count -gt 1) { $parts[1].Trim('"') } else { $null }
        Invoke-One $parts[0] $a
    }
} else {
    Invoke-One $Command $Arg
}
