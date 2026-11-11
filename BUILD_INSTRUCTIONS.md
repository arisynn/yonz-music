# Build Yono Music 2.0.0 candidate

The base is official stable v1.5.2. Code 17 / name 2.0.0 is an unpublished candidate. Preview has package `com.yonzmusic.app.dev` and name `2.0.0-preview.1`; production retains `com.yonzmusic.app`. Do not promote it to stable until the live-source and performance gates in TEST_REPORT.md pass.

Use JDK 17, Python 3.12, the Gradle 8.11.1 wrapper, Android SDK 35 / build-tools 35.0.0, NDK 28.2.13676358 and CMake 3.22.1. Point ANDROID_HOME or ignored local.properties at the SDK. Preserve the host's proxy and CA settings; Java/SDK tools may require explicit proxy properties and a trusted CA store.

## Checks and Preview

```sh
./gradlew --no-daemon --max-workers=1 -PtestBuildType=preview \
  :app:testPreviewUnitTest :app:assemblePreview :app:assemblePreviewAndroidTest :app:lintPreview
```

Preview: `app/build/outputs/apk/preview/app-preview.apk`.
Matching instrumentation: `app/build/outputs/apk/androidTest/preview/app-preview-androidTest.apk`.

Install both on a disposable emulator with `adb install -r`. Do not uninstall or wipe a user's app. Run the new migration/cache and native session tests:

```sh
adb shell am instrument -w -e class \
com.yonzmusic.app.StreamingV2Test,com.yonzmusic.app.StreamingRuntimeV2Test \
com.yonzmusic.app.dev.test/androidx.test.runner.AndroidJUnitRunner
```

Additional regression suites include StreamTransportTest, StreamFormatsTest, StreamPreloadTest, SourceMigrationTest and OnlineCollectionsTest. Acquisition/provider tests require an explicitly selected disposable writable SAF destination. Fixtures generate original audio and are not evidence of public-source availability.

## Pinned Python checks

Install `app/requirements-android.lock` in a project-external Python 3.12 virtual environment, then run:

```sh
PYTHONPATH=app/src/main/python:tools/yono-engine/tests python -m unittest discover -s tools/yono-engine/tests -v
PYTHONPATH=app/src/main/python python tools/testing/streaming_v2_live_probe.py
```

The latter is an opt-in live metadata/resolution probe. It does not decode audio and never prints signed URLs. Actual source failures remain failures. Android live playback uses StreamingLiveProbeTest with `-e liveStream true` and the normal source/network trust configuration.

## Production-signed candidate

Restore owner credentials only into ignored signing.properties with the keystore outside the checkout. The attached backup's Java properties use Unicode escaping; use Java Properties or the corresponding private JSON when restoring, never a naive password parser. Keep all restored files private. See docs/SIGNING_AND_RECOVERY.md.

```sh
./gradlew --no-daemon --max-workers=1 :app:assembleRelease :app:lintRelease
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

Online search is enabled by default in this streaming release. A wrong-key/unsigned APK is not a production update. The certificate SHA-256 must equal `ebefa44cc5fba1292fec9fc25bb4840e41e501bb0958a86931c046fd1be523e5`; package/code/ABI/SDK must also be checked. Recheck the current public versionCode before publication. Never include signing material in artifacts or source archives.

Room schema 4 is exported alongside schemas 1–3; all migrations remain registered. Backup schema 2 accepts schema 1 imports. Native runtime/engine versions and dependency locks are retained; FFmpeg is only a host fixture-generation tool. See docs/STREAMING_2_0.md for quality, cache, radio, and data behavior and TEST_REPORT.md for actual results/limitations.

## Preview 2

Use Android platform 36, AGP 8.10.1 and JDK 17. `./gradlew :app:assembleRelease --max-workers=1` creates the owner-signed code-18 update when the private ignored signing.properties is configured. For focused Android verification, build `:app:assemblePreview :app:assemblePreviewAndroidTest -PtestBuildType=preview` and run `com.yonzmusic.app.DiscoveryPreview2Test`. Run R8 builds and emulator tests sequentially on memory-limited hosts. New dependencies and schema behavior are described in [DISCOVERY_PREVIEW_2.md](docs/DISCOVERY_PREVIEW_2.md).
