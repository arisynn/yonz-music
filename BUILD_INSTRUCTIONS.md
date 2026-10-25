# Build Yono Music 1.1.0

Use JDK 17, Gradle 8.11.1 (included wrapper), Android SDK platform/build-tools 35/35.0.0, NDK 28.2.13676358 and CMake 3.22.1. A Linux x86_64 build host with Python 3.12 is the verified environment. Set ANDROID_HOME and JAVA_HOME normally, or use an ignored `local.properties` for sdk.dir.

`bash ./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:assembleMigration`

AGP 8.9.1, Kotlin/Compose plugin 2.1.20, KSP 2.1.20-1.0.32, Compose BOM 2025.04.01, Media3 1.6.1, Room 2.7.1 and Chaquopy 17.1.0 are pinned in Gradle. Chaquopy bundles Python 3.12.15 with yt-dlp 2026.10.21, EJS 0.8.0, Mutagen 1.48.1 and certifi 2026.10.19. The native build script compiles the included QuickJS-NG source for arm64-v8a and x86_64; no device-side compiler, pip, Node or Termux is required. First build needs dependency downloads; the installed app's local core does not.

Debug output: `app/build/outputs/apk/debug/app-debug.apk`, ID `com.yonzmusic.app.dev`, standard debug signed. Production output: `app/build/outputs/apk/release/app-release.apk`; it is unsigned unless an owner signing configuration is supplied. Never publish an unsigned or debug APK as a production update. Minimum SDK 26, target/compile SDK 35.

For owner release signing, see [secure configuration/recovery](docs/SIGNING_AND_RECOVERY.md). Create a private ignored `signing.properties` containing storeFile (external PKCS#12 path), storePassword, keyAlias and keyPassword. No real credential belongs in this document. The Migration variant uses the local standard debug identity; it matches an old installation only if that actual key is the original verified debug key. A new machine's debug keystore is not automatically compatible.

Run normal Android tests with the AndroidX runner on the Dev package. `LiveEngineAcquisitionTest` is opt-in via `-e liveEngine true`; it requires a real user-selected SAF test destination and the environment-specific test proxy CA. Ordinary builds contain no such test network configuration. DownloadQueueTest also needs that real output grant. Fixtures are original generated tones. `tools/testing/generate_audio_fixtures.py` uses host FFmpeg to reproduce the test media; FFmpeg is not embedded in the app.

An emulator-only higher code can be built with `-PtestVersionCode=3` for updater verification. Never upload that local fixture as a fake public version. The public release version remains the Gradle default and must be rebuilt without the override. Verify signature, package, code, SDK, ABI and hashes on every actual artifact before publication.
