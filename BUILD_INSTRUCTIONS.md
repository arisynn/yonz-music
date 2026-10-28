# Build Yono Music 1.5.0-preview.2

Use JDK 17, Gradle 8.11.1 (included wrapper), Android SDK platform/build-tools 35/35.0.0, NDK 28.2.13676358 and CMake 3.22.1. A Linux x86_64 build host with Python 3.12 is the verified environment. Set ANDROID_HOME and JAVA_HOME normally, or use an ignored `local.properties` for sdk.dir.

`bash ./gradlew --max-workers=1 :app:testDebugUnitTest :app:lintPreview :app:assemblePreview :app:assembleDebugAndroidTest`

Publish only `app/build/outputs/apk/preview/app-preview.apk` for this preview. ID `com.yonzmusic.app.dev`, code11, versionName `1.5.0-preview.2`, standard existing Dev debug certificate, launcher Yono Music Preview. It installs beside official, or upgrades a matching-key Dev install without uninstall. Preview and official collections are separate. `ONLINE_SEARCH_PREVIEW` is false in release builds; do not publish an official 1.5.0 from this preview branch. GitHub release must be a prerelease with `make_latest=false`; no stable update.json is distributed.

AGP 8.9.1, Kotlin/Compose plugin 2.1.20, KSP 2.1.20-1.0.32, Compose BOM 2025.04.01, Media3 1.6.1, Room 2.7.1 and Chaquopy 17.1.0 are pinned in Gradle. Chaquopy bundles Python 3.12.15 with yt-dlp 2026.10.21, EJS 0.8.0, Mutagen 1.48.1 and certifi 2026.10.23. The native build script compiles the included QuickJS-NG source for arm64-v8a and x86_64; no device-side compiler, pip, Node or Termux is required. First build needs dependency downloads; the installed app's local core does not.

Debug output: `app/build/outputs/apk/debug/app-debug.apk`, ID `com.yonzmusic.app.dev`, standard debug signed. Production output: `app/build/outputs/apk/release/app-release.apk`; it is unsigned unless an owner signing configuration is supplied. Never publish an unsigned or debug APK as a production update. Minimum SDK 26, target/compile SDK 35.

For owner release signing, see [secure configuration/recovery](docs/SIGNING_AND_RECOVERY.md). Create a private ignored `signing.properties` containing storeFile (external PKCS#12 path), storePassword, keyAlias and keyPassword. No real credential belongs in this document. The Migration variant uses the local standard debug identity; it matches an old installation only if that actual key is the original verified debug key. A new machine's debug keystore is not automatically compatible.

Run normal Android tests with the AndroidX runner on the Dev package. `LiveEngineAcquisitionTest` is opt-in via `-e liveEngine true`; it requires a real user-selected SAF test destination and the environment-specific test proxy CA. Ordinary builds contain no such test network configuration. DownloadQueueTest also needs that real output grant. Fixtures are original generated tones. `tools/testing/generate_audio_fixtures.py` uses host FFmpeg to reproduce the test media; FFmpeg is not embedded in the app.

An emulator-only higher code can be built with `-PtestVersionCode=12` for updater verification. Never upload that local fixture as a fake public version. The public release version remains the Gradle default and must be rebuilt without the override. Verify signature, package, code, SDK, ABI and hashes on every actual artifact before publication.

## Collection repair verification

Preview default: code11, name1.5.0-preview.2 (preview build type), Room schema3. Stable distribution remains1.4.2/code9. Keep all schema JSON files and migration 1→2→3; never delete the database to make an upgrade pass.

Host engine checks: install `app/requirements-android.lock` into a separate Python 3.12 virtual environment, then run `python tools/yono-engine/verify_host.py`. Reports default to `docs/verification/v1.3.0`; `--output` selects a different evidence directory. Host FFmpeg creates/inspects owned fixture audio only; it is not an app dependency. After building, `PYTHONPATH=app/build/python/pip/debug/common python3 tools/yono-engine/verify_host.py` can reuse the resolved pure-Python dependencies.

New Android checks include CollectionRecoveryTest, LocationStatesTest, OutsideAndCorruptTest, QueueRelocationTest, AcquisitionRecoveryTest and EngineControlTest. They require a deliberately selected writable disposable SAF fixture destination. Some create original-tone files there; the outside-tree move test restricts its shell operation to its own numeric fixture paths. The error provider is confined to androidTest, not the distributed application. Do not run destructive device setup or uninstall on a user's installation. Use `adb install -r` with the matching certificate and compare original collection IDs before/after. LocationStatesTest uses a controllable fixture provider rather than claiming a physical SD-card unmount.

For R8 on a constrained build host, use the default 1792 MiB Gradle heap and stop the software emulator during minification. A 768 MiB override exhausted the compiler heap during this release; the normal-heap rebuild passed. This is build-host memory, not an app runtime requirement.

Optional Spotify: configure a public Client ID in Downloads → Koneksi playlist Spotify. Register `com.yonzmusic.app://spotify/callback` (Dev: `com.yonzmusic.app.dev://spotify/callback`). No client secret is used. Developer account/allowlist/quota requirements and playlist owner/collaborator restrictions apply. See docs/UPDATE_1_4_0.md.


## Engine Search verification

`PYTHONPATH=app/src/main/python:app/build/python/pip/preview/common python3 -m unittest discover -s tools/yono-engine/tests -p test_search.py -v` verifies bounded flat search/parser/cancellation using fixtures. `OnlineSearchPreviewTest#actualPythonBridgeUsesFlatReadOnlySearchAndCancellation` exercises the actual Android bridge with a scoped test-only extractor fixture, restoring it afterward. `#optionalHistoryAndObsoleteKeyRemovalKeepCollections` checks history/key cleanup. Live search is a separate opt-in `-e liveSearch true -e class com.yonzmusic.app.OnlineSearchPreviewTest#liveEngineSearchReportsActualSourceAvailability`; failure must remain reported as failure, not converted into a pass. No fixture switch exists in production API/config.
