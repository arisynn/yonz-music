# Yono Music 2.0.0 candidate — delivery

Built from the official stable v1.5.2 base. Implementation is pushed on `update/stable-v1.5.2`; no stable release/tag or updater metadata is published.

- Streaming-first minimal Beranda/Cari, retaining local collections, smart mixes and original-format SAF downloads.
- Media3 single session, yt-dlp audio resolution, public music catalogue/radio without login/backend.
- Balanced/economy/best quality, conditional one-track preload, private off/32/64MiB LRU cache and local timing diagnostics.
- Online favorites/qualified history, personal shelves and bounded autoplay with pause/repeat/queue-change protection.
- Additive Room3→4 migration and backup2 accepting backup1.

## Artifact

`artifacts/Yono_Music_v2.0.0_candidate.apk`

Package com.yonzmusic.app · code17 ·2.0.0 · non-debuggable · minimum API26 / target35 · arm64-v8a/x86_64.

30015136 bytes (39444 bytes above official1.5.2).

APK SHA-256: `e80234a3d9d57912ac5a460b3f4efdcc6b59e09eb54b9264fcf506914bc7895b`

Official certificate SHA-256: `ebefa44cc5fba1292fec9fc25bb4840e41e501bb0958a86931c046fd1be523e5`

Preview uses the separate com.yonzmusic.app.dev package /2.0.0-preview.1. Source and checksums are packaged separately. Signing files, passwords, GitHub credentials and the private upgrade probe APK are excluded.

## Validation and limits

Final Preview/release builds and lint passed. 189 Python and148 JVM tests passed.20 distinct controlled Android cases passed across regression/final runs, with one opt-in live-source skip. The final6cases cover the latest hardening. Actual official1.5.2→candidate replacement preserved seeded collection data in the minified production package without uninstall or data reset.320dp dark150% UI was inspected. TEST_REPORT.md retains failure/correction history and practical limits.

Public catalogue and known-video resolution probes failed in this environment. Successful live decoded playback, physical-device qualification and the25% Wi-Fi improvement target are not claimed. The APK is a candidate for further testing; public stable publication remains gated on NEXT_UPDATE_PLAN.md.
