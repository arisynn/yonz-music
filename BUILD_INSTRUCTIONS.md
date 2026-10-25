# Build and signing

## Reproducible toolchain

Use a full JDK 17 (including javac), Android SDK platform 35, Build Tools 35.0.0, and the Gradle 8.11.1 wrapper included in this project. Android Gradle Plugin 8.9.1 supports this Gradle/JDK/API combination. Kotlin and its Compose compiler plugin both use 2.1.20; KSP is 2.1.20-1.0.32. The Compose BOM 2025.04.01 aligns UI dependencies. Media3 modules both use 1.6.1; Room modules/compiler all use 2.7.1. Versions are pinned in Gradle files.

Create an untracked `local.properties` with your Android SDK path, or set ANDROID_HOME. Do not reuse the delivery machine's path. Android Studio can create this file automatically.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:assembleRelease :app:lintRelease
```

The connected test command needs a booted emulator/device. Outputs are `app/build/outputs/apk/debug/app-debug.apk` (standard Android debug signed) and `app/build/outputs/apk/release/app-release-unsigned.apk` (optimized and unsigned). Unit, instrumentation, and lint reports are under app/build/reports and app/build/outputs. Network access is required to download build dependencies; installed app operation is offline.

## Production signing

No production signing key was supplied or generated. Preserve your own long-lived release key securely outside the source/archive. Use Android Studio's Generate Signed Bundle/APK workflow or your secure CI signing setup. Never commit the keystore, passwords, or signing.properties. For CLI signing, first zipalign the unsigned APK, then run Android SDK apksigner with your own keystore, entering credentials through its secure prompt or CI secret mechanism. Verify with `apksigner verify --verbose --print-certs` and test the resulting artifact before distribution. Never distribute an unsigned release as installable.

The debug APK is installable but debuggable and uses a development certificate. It is not production signed. A later release signed with a different certificate cannot update it in place; uninstalling clears local collections, so export playlists first. Debug keystore/private key is excluded from the source archive.

## SDK and permissions

Application ID com.yonzmusic.app; minimum API 26 (Android 8); target/compile API 35 (Android 15); version code 1/name 1.0.0. Runtime audio read permission depends on OS version. Foreground-service permissions are restricted to mediaPlayback. Media3 manages a WAKE_LOCK only during active playback for reliable screen-off audio. No INTERNET, microphone, contacts, location, overlay, broad-storage, or battery-exemption permission is requested.

For locked-down build networks, configure Gradle's HTTPS proxy and trust store outside source control. The delivered Gradle wrapper distribution checksum prevents accepting an altered distribution. Consult final build reports for the actual verification performed in this environment.
