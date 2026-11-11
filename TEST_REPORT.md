# Yono Music 2.0 streaming candidate — verification

Base: official v1.5.2 (`4aa2fb6`). Code17 /2.0.0 is an unpublished candidate. Public-source availability and the Wi-Fi improvement target have not passed. Previous-release evidence remains in git/history and docs/releases.

## Results

| Check | Actual result |
| --- | --- |
| Pinned Python engine suite | 189 passed,0 failures |
| Final JVM suite | 148 passed,0 failures |
| Preview and release | Both APKs built; both lint tasks passed |
| Initial Android regression | 19 cases:18 passed,1 opt-in live-search skip |
| Final Android checks after hardening | 6 passed:4 migration/cache/backup/history cases, native session/radio, and preload |
| Production in-place upgrade | Official1.5.2/code16/Room3 → signed2.0.0/code17/Room4 passed without uninstall |
| Production identity | com.yonzmusic.app, same official certificate, non-debuggable, SDK26–35, arm64-v8a/x86_64 |

Across the Android invocations,20 distinct controlled cases passed and one opt-in live-search case was skipped. The final focused invocation checks the latest binary; earlier regressions preceded the last preload/history/thumbnail hardening. No skipped or synthetic case is counted as a successful live-source test.

The Python suite includes actual yt-dlp quality-selector evaluation, expiry/revision cache identity, bounded/deduplicated related lists, cancellation and sanitized failures. JVM coverage includes local/paused/repeating/disabled radio eligibility and queued/failed-ID exclusions.

Android tests used an API26 x86_64 software emulator and original generated audio. They check real Media3 MP3/WebM/Opus/M4A decoding and seeking, controlled HTTP timeout recovery, additive collection migrations, online collections, catalogue bridge cancellation, qualified listening/favorites, radio/autoplay/pause, actual cache reuse/invalidation/clear/disable and32MiB LRU enforcement with a34MiB input. Backup2 round-trips online favorites/counts and accepts backup1. A stale listening-generation test confirms queued work cannot recreate history after the synchronous clear/disable boundary.

The final preload fixture observed128044 bytes for the next recording before transition and4738ms to playing after Next, in the same player. Media3 is configured with a5-second preload target; preparation/read granularity can overshoot a duration target. Cache size and the number of speculative items are separately bounded. This fixture is not a live-service latency benchmark.

## Production signing and upgrade

The actual supplied keystore and both official/candidate APK certificates match:

`ebefa44cc5fba1292fec9fc25bb4840e41e501bb0958a86931c046fd1be523e5`

The actual official1.5.2 release APK was installed, and private same-signed QA instrumentation seeded track/favorite/history/playlist/state fixtures in its Room3 database. The final production-signed candidate was installed using `adb install -r`. The probe then opened the real minified app through its launcher and verified Room4 plus unchanged track identity/title, favorite, history position, playlist entry and state marker. No uninstall, app-data wipe, signature replacement or destructive migration was used.

This is a forward-upgrade check. A Room4→3 downgrade is not implemented. The probe checks controlled database identities, not persisted SAF grants or physical-device storage providers.

Candidate APK:30015136 bytes, only39444 bytes larger than the baseline official APK. Its SHA-256 is recorded in candidate-apk.json. There are no keystores/signing properties in the APK or source package. The private same-signed instrumentation APK is excluded from delivery artifacts.

## UI

The actual candidate was inspected at320dp in dark mode with150% font; navigation labels remain visible, including two-line Daftar putar. Streaming settings and quality/cache controls were inspected in the same configuration. A light100% Preview Home screenshot uses controlled fixture metadata. Navigation/settings labels were also read through the Android accessibility tree. Actual TalkBack speech/gesture navigation was not exercised. System font/night settings were restored after capture.

## Live source and performance gates

The host public music catalogue probe failed with CATALOGUE_NETWORK (5548ms). Independent resolution of a known public Blender recording failed with NETWORK (134ms). The underlying catalogue exception chain includes ProxyError; raw URLs/headers/credentials are not published. No access restriction or TLS bypass was attempted.

Live decoded playback, a20-recording same-device/same-Wi-Fi comparison, p50/p95 startup improvement and rebuffering reduction remain **unverified**. The25% median improvement is a target, not a measured result. Stable publication and stable updater metadata remain gated on successful real-source playback and this comparison.

Physical Android16/HyperOS, Bluetooth hardware, battery/screen-off behavior and Play Protect have not been independently tested. Acquisition/provider-specific SAF tests needing a selected writable destination were not rerun; download behavior was retained and host engine regressions passed.

## Failed attempts and corrections

Initial SDK image installation exhausted the small workspace disk and interrupted JVM test output. Later builds passed after tooling storage was corrected. A cold emulator System UI ANR obscured the first native-session run, which timed out; the subsequent functional regression passed.

A playback invocation timed out on radio and preload while heavy R8 ran concurrently. Both cases passed separately after pausing the build, and the final checks passed after build completion. Failed and successful logs are retained separately. An earlier build was intentionally stopped to include hardening; the final detached build completed successfully in9m20s.

The production probe initially depended on an obfuscated getter. It was corrected to launch the actual application; shrinking and production signing were retained. Preload disabling now uses Media3 DEFAULT, rather than zero-duration preload. Reconfiguration occurs only when the gate changes, snapshots remain every5seconds, thumbnail work is limited to two workers and yields to foreground audio, and the history generation is captured before persistence is queued.

Evidence: docs/verification/v2.0.0. Commands: BUILD_INSTRUCTIONS.md. Remaining public-release gates: NEXT_UPDATE_PLAN.md.
