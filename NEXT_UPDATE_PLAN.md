# Streaming 2.0 — implementation and remaining release gates

Base: official v1.5.2. Working branch: update/stable-v1.5.2.

Implemented:

- Streaming-first minimal Indonesian Home and online-default Search; local library, smart mixes and original-format SAF downloads retained.
- Media3 single session, yt-dlp resolver, public ytmusicapi catalogue/radio without account/backend.
- Balanced/economy/best quality, foreground audio priority, conditional one-track preload, 64MiB maximum LRU cache with off/32/64 controls.
- Online favorites/qualified history, personal shelves, bounded radio/autoplay with pause/repeat/queue-change protection.
- Room3→4 additive migration and collection backup2 accepting backup1.
- Local session diagnostics, migration/cache/backup/radio tests, private production-upgrade verification tooling and owner signing restoration outside git.

Pending before stable publication:

- Successful real-source decoded playback on the target network/device. The current host public catalogue and known-video resolver probes fail; fixtures are not a substitute.
- Same-device, same-Wi-Fi comparison against1.5.2 using20 recordings, cold/warm starts, p50/p95 startup and buffering duration. Target median improvement25%, without p95/buffering regression, remains unmeasured.
- Physical-device screen-off/audio focus/Bluetooth and Android16/HyperOS checks if those are target devices. Emulator evidence must remain identified as emulator evidence.
- Only after the gates pass: create the stable release/tag and verified updater metadata. Recheck GitHub's latest public versionCode first. No publication/push is performed by this delivery.

The signed candidate build and actual official1.5.2→candidate in-place upgrade passed; collection identities were retained without uninstall.

Actual build, upgrade, and test results are recorded in TEST_REPORT.md and FINAL_BUILD_REPORT.md. Deferred product additions remain charts, full artist/album discovery, synchronized lyrics, accounts/cloud sync and a custom backend.
