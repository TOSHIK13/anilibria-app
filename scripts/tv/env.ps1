# Dot-source: . scripts/tv/env.ps1
# Sets JAVA_HOME / ANDROID_HOME / GRADLE_USER_HOME / PATH to the local toolchain of the main checkout
# (works from git worktrees too: the toolchain lives only in D:/Ani/.gradle/codex-tools).

$aniRepo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$aniMain = $aniRepo
$commonDir = git -C $aniRepo rev-parse --path-format=absolute --git-common-dir 2>$null
if ($LASTEXITCODE -eq 0 -and $commonDir) { $aniMain = Split-Path -Parent $commonDir }

$aniTools = Join-Path $aniMain '.gradle\codex-tools'
$env:JAVA_HOME = (Get-Content (Join-Path $aniTools 'jdk-home.txt') -Raw).Trim()
$env:GRADLE_USER_HOME = Join-Path $aniMain '.gradle'
$env:ANDROID_HOME = Join-Path $aniTools 'android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_USER_HOME = Join-Path $aniTools 'android-user-home'
$env:ANDROID_AVD_HOME = Join-Path $env:ANDROID_USER_HOME 'avd'
$aniPathAdd = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\emulator"
if ($env:PATH -notlike "$aniPathAdd*") { $env:PATH = "$aniPathAdd;$env:PATH" }
