$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot

# In a git worktree the toolchain, keystore and local.properties live only in the
# main checkout, so resolve it through the shared git dir.
$mainRoot = $root
$commonDir = git -C $root rev-parse --path-format=absolute --git-common-dir 2>$null
if ($LASTEXITCODE -eq 0 -and $commonDir) {
    $mainRoot = Split-Path -Parent $commonDir
}

$tools = Join-Path $mainRoot ".gradle\codex-tools"
$jdkHomeFile = Join-Path $tools "jdk-home.txt"
$sdk = Join-Path $tools "android-sdk"
$keystore = Join-Path $tools "local-release.jks"

if (-not (Test-Path $jdkHomeFile)) {
    throw "Missing $jdkHomeFile. Restore the local JDK toolchain before building."
}

$jdkHome = (Get-Content $jdkHomeFile -Raw).Trim()

if (-not (Test-Path $jdkHome)) {
    throw "Missing JDK home $jdkHome."
}

if (-not (Test-Path $sdk)) {
    throw "Missing Android SDK $sdk."
}

if (-not (Test-Path $keystore)) {
    throw "Missing release keystore $keystore."
}

$localProperties = Join-Path $root "local.properties"
$mainLocalProperties = Join-Path $mainRoot "local.properties"
if (-not (Test-Path $localProperties)) {
    if ($mainRoot -eq $root -or -not (Test-Path $mainLocalProperties)) {
        throw "Missing $localProperties with release signing config."
    }
    Copy-Item $mainLocalProperties $localProperties
    Write-Host "Copied local.properties from $mainRoot"
}

$env:JAVA_HOME = $jdkHome
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:ANDROID_USER_HOME = Join-Path $mainRoot ".android"
$env:GRADLE_USER_HOME = Join-Path $mainRoot ".gradle"
$env:PATH = "$jdkHome\bin;$sdk\platform-tools;$sdk\cmdline-tools\latest\bin;$sdk\build-tools\36.0.0;$env:PATH"

Push-Location $root
try {
    & .\gradlew.bat :app-tv:copyAppReleaseApk
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE."
    }

    $latest = Get-ChildItem (Join-Path $root "release-apks") -Filter "AniLiberty_TV_Mod_v*.apk" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if ($null -eq $latest) {
        throw "Build finished, but no copied APK was found in release-apks."
    }

    if ($mainRoot -ne $root) {
        $mainApks = Join-Path $mainRoot "release-apks"
        New-Item -ItemType Directory -Force $mainApks | Out-Null
        $latest = Move-Item $latest.FullName $mainApks -Force -PassThru
    }

    Write-Host "APK: $($latest.FullName)"
    Get-FileHash $latest.FullName -Algorithm SHA256
}
finally {
    Pop-Location
}
