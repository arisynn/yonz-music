# Yono Music 1.5.1 — official build report

Karisyono · com.yonzmusic.app · versionCode15 · versionName1.5.1 · Room3. Production signing identity unchanged. Install over matching-key official versions without uninstalling or clearing data.

Implemented: default-on persisted hide-downloads-from-Gallery setting; scoped .nomedia ownership journal and conservative off/recovery behavior; actionable unavailable-folder notices; readable new portable JSON grouped under .yono, compatible legacy JSON reads and nonrecursive companion discovery. Audio bytes/format, metadata/artwork association, collection identity, navigation and original owner branding remain intact. See docs/DOWNLOAD_FOLDER_LAYOUT.md for whole-folder effects and limitations. No automatic old-file migration or transcoding.

Release/Debug/AndroidTest assembly,140 JVM/184 Python tests and6 controlled Android cases passed after correcting a missing SAF test precondition. Actual UI verified at320dp/light100%/dark150%. In-place production and Dev14→15 installs kept exact existing collection/AppState rows. Details and honest scope: TEST_REPORT.md. Physical HyperOS Gallery reindex timing remains unverified; already indexed tiles may persist until the Gallery updates its index. Third-party MediaStore players can also hide that selected destination.

SDKmin26, compile/target35; arm64-v8a/x86_64; JDK17/Gradle8.11.1/AGP8.9.1/Kotlin2.1.20/Compose2025.04.01/Media3 1.6.1/Chaquopy17.1.0/Python3.12.15. Dependency pins/licenses unchanged from1.5.0, including Guava33.7.2-android. No new advisory/security-scanner approval is claimed.

Declared app permissions remain INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS, WAKE_LOCK, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK, FOREGROUND_SERVICE_DATA_SYNC, REQUEST_INSTALL_PACKAGES. Merged dependencies also declare RECEIVE_BOOT_COMPLETED and signature-only DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION. No broad media/storage, microphone, contacts, location, overlay or AccessibilityService permission.

Production certificate SHA256: `ebefa44cc5fba1292fec9fc25bb4840e41e501bb0958a86931c046fd1be523e5`. Standard Dev certificate: `3e8117db4ac9b9e71429136a4d687bdaec8a75f1c052ee7c153b6c62c2fda740`. No key rotation or credential publication.

Output directory: /workspace/deliverables-v1.5.1. Production/debug APKs, exact git source ZIP, original assets, unchanged matching dependency sources, actual reports/signature/package/native inspection, update.json and SHA256 checksums. New tag v1.5.1; publication never overwrites an old tag or release. Actual public verification is a separate packaging/publication proof.
