# Yono Music 1.4.0 — build report

Creator Karisyono. Repository https://github.com/arisynn/yonz-music . Version 1.4.0, code 7, production com.yonzmusic.app, Dev com.yonzmusic.app.dev. Existing owner/Dev signing identities, Room schema 3 and additive migrations retained.

Implemented: compact review with selection/search/reasons, persistent choices and same-list recovery, in-place metadata retry, actual source names, optional playlist, direct destination for one song or no-playlist batches, original audio, hidden companion covers and portable references. Home visual, real playback-status animation, keyed queue drag/swipe/undo/accessibility, local artist portraits. Offline core remains native Kotlin/Media3/Room/DataStore; optional embedded Python engine retains yt-dlp/EJS/QuickJS. No new dependency versions.

Spotify metadata connection is optional browser PKCE, requires configured public Client ID and permitted accounts. No shared production Spotify credentials were supplied. Playlist contents remain subject to API/Development Mode limitations. No live login success is claimed. Photos are explicitly local; automatic online artist photography is deferred. No new broad storage permission or whole-storage scan.

SDK: min26, target/compile35, JDK17, Gradle8.11.1, AGP8.9.1, Kotlin2.1.20, Media3 1.6.1, arm64-v8a/x86_64. Permissions: INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS, WAKE_LOCK, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK, FOREGROUND_SERVICE_DATA_SYNC, REQUEST_INSTALL_PACKAGES. Existing MainActivity handles a package-specific OAuth redirect with state/PKCE validation; service/component restrictions retained.

Actual tests and limits: TEST_REPORT.md, docs/verification/v1.4.0 and screenshot evidence. Install replacement preserved the existing nonempty Dev collection. Source archives exclude private signing files, local configuration, tokens, build caches and APKs. Production signing uses the existing external owner key; no private key is distributed. Google Play Protect and physical HyperOS are external/unverified. Final measured APK size/hash/signature, commit and compilation results accompany distribution.

Debug/release compilation, JVM tests and both lint variants passed. See available verification logs for warnings and actual checks.
