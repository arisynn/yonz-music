# Yono Music 1.1.0 — preview verification checkpoint

The owner asked to publish a test build before all final checks. This file reports actual evidence as of the preview checkpoint, not a completed production certification.

## Passed

- Gradle debug/APK instrumentation builds, 72 JVM tests (40 parsers, 10 collection order, 9 recording identity, 13 updater contract/cleanup rules), lintRelease with zero errors.
- 12 baseline Android instrumentation tests: real SQLite consistency (5), MediaSession playback/focus/pause/seek/restoration/timer (1), real Room 1→2 migration and selected-source/alias behavior (4), actual embedded engine fixture transfer/tags/artwork/cancel/QuickJS/EJS (2).
- 4 additional Android gates: FLAC/M4A/Ogg/WAV tagging preserves native audio packet hashes; corrupt/truncated/video rejection; retry/order/cancel/dedup through real Room and SAF output; live permitted Commons audio acquisition and MediaSession playback.
- The live Commons case used HTTPS through the environment's test proxy with certificate verification enabled. It downloaded an 11,470,392-byte source, tagged/published 11,483,368 bytes, emitted 13 progress events, and played the saved URI. SHA-256 and runtime evidence are in `docs/verification/v1.1.0/`. This was a real remote download, not a local fixture.
- Embedded runtime on Android: Python 3.12.15, yt-dlp 2026.10.21, yt-dlp-ejs 0.8.0, Mutagen 1.48.1, QuickJS-NG 0.17.0. Bundled EJS library/core loaded in the restricted runner. Node/Deno/FFmpeg/ffprobe are absent and not assumed available.
- Static audit of 144 bundled ELF files found every PT_LOAD alignment at least 16 KiB. This is static compatibility evidence, not a 16-KiB Android device run.
- Production signing was verified by apksigner v2/v3, RSA-4096, fingerprint documented in SIGNING_AND_RECOVERY.md. Owner-encrypted backup secret names were verified remotely; recovery execution is not claimed.

Earlier failed trials were repaired: missing emulator output-grant completion, proxy CA handling, unknown codec fields for valid application/ogg, an empty traced vector path that crashed launch, and a lint permission-flag constant. Logs distinguish those failures from later successful checks.

## Still in progress at preview publication

- Independent creator-site live MP3 acquisition test, full final regression after the latest changes.
- Actual production PackageInstaller permission denial/cancel/success/process-death/cleanup upgrade exercise; unit validation alone does not prove this.
- Fresh production install, migration bridge UI/export, final dark/light/large-font/compact-screen UI audit.
- Final resolved dependency advisory audit and final distribution inventory.

Only API 29 x86_64 software-emulator execution has been performed for this checkpoint. Physical HyperOS, real Bluetooth/wired headset hardware, API 35 device behavior, 32-bit devices and external Google Play Protect/Play Store review have not been tested. No guaranteed platform/extractor coverage is asserted.
