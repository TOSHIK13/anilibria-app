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

See `docs/tv-mod-build.md` for the fixed application id, signing key,
versionCode rules, local toolchain paths, and update compatibility checks.
