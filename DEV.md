Read `AGENTS.md` first for the canonical workflow used in this workspace.

Build and copy mobile releases
```
./gradlew :app-mobile:copyAppRelease :app-mobile:copyRustoreRelease :app-mobile:copyStoreRelease
```

Build and copy tv releases
```
./gradlew :app-tv:copyAppRelease :app-tv:copyRustoreRelease
```

TV mod release build
```
powershell -ExecutionPolicy Bypass -File scripts/build-tv-app-release.ps1
```

TV debug build / install / on-device checks
```
powershell -File scripts/tv/tv.ps1 help
```

See `docs/tv-mod-build.md` for the fixed application id, signing key,
versionCode rules, local toolchain paths, and update compatibility checks.
See `docs/tv-code-map.md` for where TV screens and data classes live.
