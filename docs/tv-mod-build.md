# TV Mod APK build notes

This document fixes the local build contract for the TV mod APK. Keep these
values identical in every branch if the produced APK must update the already
installed app.

## Update compatibility

Android treats an APK as an update only when all of these match the installed
app:

- `applicationId`
- signing certificate
- variant/package type

For this mod the stable update identity is:

```text
applicationId=ru.radiationx.anilibria.app.tv.mod
variant=appRelease
Gradle task=:app-tv:copyAppReleaseApk
output folder=D:/Ani/release-apks
output name=AniLiberty_TV_Mod_v<version>_<yyyy-MM-dd_HH-mm>.apk
```

The application id is configured in `app-tv/build.gradle.kts`:

```kotlin
defaultConfig {
    applicationId = "ru.radiationx.anilibria.app.tv.mod"
}
```

Do not change it in feature branches unless you intentionally want a separate
install.

## Signing

Release builds are signed from `local.properties`. The current local dev
signing configuration is:

```properties
storeFile=D:/Ani/.gradle/codex-tools/local-release.jks
storePassword=android
keyAlias=anilibria-local
keyPassword=android
```

This is a local development key. It is enough for installing updates over other
builds made from this workspace, but it is not a public production secret. Keep
the same keystore file and alias across branches.

If the app is already installed with another key, Android will reject the update
with a signature conflict. In that case either uninstall the old app or rebuild
both old and new APKs with this key.

## Version code

For normal updates the new APK should have `versionCode` greater than or equal
to the installed APK. The TV version code is defined in
`gradle/libs.versions.toml`:

```toml
tv-version-code = "..."
```

Increase it before a test build when Android refuses to update because the
installed version is newer.

## Local toolchain

This workspace uses local tools under:

```text
D:/Ani/.gradle/codex-tools/android-sdk
D:/Ani/.gradle/codex-tools/jdk-extract
D:/Ani/.gradle/codex-tools/jdk-home.txt
D:/Ani/.gradle/codex-tools/local-release.jks
```

`local.properties` must point to that SDK and keystore:

```properties
sdk.dir=D:/Ani/.gradle/codex-tools/android-sdk
storeFile=D:/Ani/.gradle/codex-tools/local-release.jks
storePassword=android
keyAlias=anilibria-local
keyPassword=android
appmetrica_post_api_key=
```

`local.properties`, `.gradle/`, `.android/`, `.kotlin/`, and build outputs are
ignored by git. They normally stay in place when switching branches in the same
working directory. If you clone the repository elsewhere, copy these local files
or install an equivalent Android SDK/JDK and create a matching keystore.

## Build command

Preferred command on Windows:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build-tv-app-release.ps1
```

The script sets:

```text
JAVA_HOME
ANDROID_HOME
ANDROID_SDK_ROOT
ANDROID_USER_HOME
GRADLE_USER_HOME
PATH
```

and runs:

```powershell
.\gradlew.bat :app-tv:copyAppReleaseApk
```

Use `copyAppReleaseApk`, not `copyRustoreReleaseApk`, for the base version.

If dependencies are already in the local Gradle cache, the build does not need
to download them again. If Gradle still tries to download modules, keep using
the same `GRADLE_USER_HOME=D:/Ani/.gradle` and avoid deleting `.gradle`.

## Verification

Check the latest copied APK:

```powershell
Get-ChildItem D:/Ani/release-apks -Filter 'AniLiberty_TV_Mod_v*.apk' |
  Sort-Object LastWriteTime -Descending |
  Select-Object -First 1 Name,Length,LastWriteTime
```

Verify the signature:

```powershell
$tools = 'D:/Ani/.gradle/codex-tools'
$jdkHome = (Get-Content (Join-Path $tools 'jdk-home.txt') -Raw).Trim()
$sdk = Join-Path $tools 'android-sdk'
$env:JAVA_HOME = $jdkHome
$env:PATH = "$jdkHome/bin;$sdk/build-tools/36.0.0;$env:PATH"
$apk = Get-ChildItem D:/Ani/release-apks -Filter 'AniLiberty_TV_Mod_v*.apk' |
  Sort-Object LastWriteTime -Descending |
  Select-Object -First 1
apksigner.bat verify --verbose $apk.FullName
```

Expected important lines:

```text
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
Number of signers: 1
```

Install/update over ADB:

```powershell
adb install -r D:/Ani/release-apks/<apk-name>.apk
```

If Android reports a downgrade only for local testing:

```powershell
adb install -r -d D:/Ani/release-apks/<apk-name>.apk
```

Prefer increasing `versionCode` instead of relying on `-d`.
