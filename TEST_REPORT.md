# Yono Music 2.0 streaming candidate — verification

Base: official v1.5.2, commit4aa2fb6. This is an unpublished candidate, not a claim that public-source streaming or Wi-Fi performance has passed. Previous release evidence remains in git/history and docs/releases; results below belong to this change.

## Completed checks

- 189 pinned Python tests passed, including real yt-dlp quality-selector behavior, source revision/expiry identity, bounded/deduplicated related lists, cancellation and sanitized failures.
- 148 JVM tests passed, including radio eligibility for local/paused/repeating/disabled queues and duplicate/failed-ID exclusions.
- Android API26 x86_64 software emulator: regression invocation reports19 cases, with18 passing and one opt-in live-search case skipped. Cases cover additive Room migration, actual disk cache reuse/change/clear/disable/LRU32MiB limit, backup2 and backup1 restoration, original MP3/WebM/Opus/M4A decode/seek, timeout recovery, local collection migrations, online collections, catalogue bridge/cancellation, qualified history/favorites and radio/pause.
- The final playback invocation overlapped heavy R8 and timed out on radio and preloading. Both cases passed when rerun separately after pausing the build. The preload fixture observed992044 bytes before transition and2153ms until playing after Next. These are generated-audio emulator results, not live-service benchmarks.
- Preview compilation,148 JVM tests and lint have passed. The current production-signed build/final artifact checks are still running at this checkpoint.
- The actual supplied keystore certificate matches official production SHA-256 `ebefa44cc5fba1292fec9fc25bb4840e41e501bb0958a86931c046fd1be523e5`.
- The actual official1.5.2 APK/code16 was installed and a private same-signed QA probe seeded Room3 track/favorite/history/playlist/state fixtures. Installation of the new APK and post-upgrade assertions are pending completion of the production build.

## Live source and performance gates

The host public music catalogue probe failed with CATALOGUE_NETWORK (5548ms). Independent resolution of a known public Blender recording failed with NETWORK (134ms). The underlying catalogue exception chain includes ProxyError; no raw URLs/headers/credentials are published. No source access restriction or TLS bypass was attempted.

Live decoded playback,20-recording same-device/same-Wi-Fi comparison, p50/p95 startup improvement and rebuffering reduction are **not verified**. The25% median improvement is a target, not a result. Stable publication/updater metadata remain gated on successful real-source playback and this comparison.

Physical Android16/HyperOS, Bluetooth hardware and screen-off battery behavior have not been independently tested. Acquisition/provider-specific SAF tests requiring a selected writable destination were not rerun; download behavior was retained and host engine regression tests passed. The production upgrade probe checks database identities, not persisted SAF grants on a physical device.

## Corrections and environment limits

Initial SDK image installation exhausted the small workspace disk and interrupted a JVM test output directory. Later builds/tests succeeded after tooling storage was corrected. A cold emulator System UI ANR obscured the first native-session run, which timed out; it was dismissed and the functional regression run passed. The private production probe initially depended on an obfuscated getter; it was corrected to initialize through the actual launcher without weakening application shrinking rules.

Final hardening uses Media3's DEFAULT configuration to disable preload fully (zero means a zero-duration preload, not disabled), avoids repeatedly reconfiguring preload/persisting each second, defers a maximum of two thumbnail requests to foreground audio, and captures the history-clear generation before queued persistence. The latest build plus focused final emulator checks are still pending at this checkpoint; do not treat earlier binary evidence as a new stable qualification.

Evidence: docs/verification/v2.0.0. Build commands: BUILD_INSTRUCTIONS.md. Remaining release gates: NEXT_UPDATE_PLAN.md.
