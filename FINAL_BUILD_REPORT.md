# Yono Music 1.4.1 — hotfix build report

Karisyono · com.yonzmusic.app · versionCode 8 · Room schema 3 · existing production/Dev signing identities retained. Repository https://github.com/arisynn/yonz-music .

Fixes a completion-state race that supplied NaN to Compose progress semantics. Immutable snapshots and a shared finite progress calculation cover updater, download list, acquisition UI and mini-player. Unknown totals remain indeterminate. APK hash/certificate/manifest checks and official Android installer confirmation are unchanged. All 1.4.0 collection/download/UI features remain.

SDK min26, target/compile35; Gradle8.11.1, JDK17, Kotlin2.1.20, AGP8.9.1, Media3 1.6.1; arm64-v8a/x86_64. No permission/dependency/database/signing changes. Permissions remain INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS, WAKE_LOCK, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK, FOREGROUND_SERVICE_DATA_SYNC, REQUEST_INSTALL_PACKAGES.

Actual current tests are in TEST_REPORT.md and docs/verification/v1.4.1. State fixtures are distinct from live APK downloading/installation. No physical HyperOS or Play Protect result is claimed. Final measured size/hash/signature/commit accompany the distribution. Keystore/passwords/tokens are excluded.
