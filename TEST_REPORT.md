# Yono Music 1.1.0 — actual verification

Environment: JDK 17, Gradle 8.11.1, Android SDK/build tools 35/35.0.0, NDK r28c; AOSP API 29 x86_64 software emulators (320 dp existing installation and 360 dp fresh production installation). Physical devices were unavailable. Tests used original generated tones or explicitly licensed remote recordings.

## Compilation and automated checks

Debug, AndroidTest, optimized owner-signed Release and old-key Migration APKs built successfully. `testDebugUnitTest` passed **72 tests**: parser/local import 40, collection order 10, conservative recording identity 9, release metadata/cleanup contract 13. `lintRelease` passed with **0 errors / 85 warnings** (inventory in `docs/verification/v1.1.0/lint-summary.json`). Warnings include dependency update suggestions, library opt-in/deprecation and UI/localization guidance; they are not a claim of a warning-free build.

**20 distinct Android instrumentation checks passed across the regression run and focused live-network recheck:** native formats 2; SQLite consistency 5; real SAF download queue/retry/order/cancel/reuse 1; embedded engine/JS/runtime/artwork/cancel 2; real MediaSession playback/focus/seek/paused restore/timer 1; Room migration/source membership/aliases 4; signed-APK validation/recovery/forged callback/cleanup 3; live-source checks 2.

The main 20-check run passed 18 and failed its two network checks because the environment test proxy had stopped (`Connection refused`). The proxy was restarted, and **both live checks passed on recheck** without an application-code change. The recheck reused verified managed history after requesting live source metadata; it is not reported as a second full transfer. Both original full live transfers had separately passed earlier. The failed regression log and successful focused log are retained with clear filenames.

APK tests used a real same-key **Dev code-3 fixture**: valid APK accepted; wrong hash/certificate/package/version/SDK/ABI rejected; a corrupted APK with its recomputed hash still rejected by cryptographic signature verification. Interrupted verified rename recovered; incomplete/tampered cache discarded; a forged callback caused no deletion; cleanup was idempotent and preserved unrelated files. These are fixture/state tests, distinct from the actual OS installer exercise below.

## Actual permitted-source downloads on Android

- **Commons CC BY-SA 2.0 Ogg:** embedded yt-dlp → progress (13 events) → native validation/tags → chosen SAF folder → exact Room URI → real MediaSession `isPlaying`. Raw source 11,470,392 bytes; final tagged file 11,483,368 bytes, 355,413 ms; final SHA-256 `3440cfb54a3834ca4b0ec9aa63ab95cfb2442b1c2fdfe9337a07d8e7e7890655`.
- **Incompetech / Kevin MacLeod CC BY 4.0 MP3:** same embedded pipeline, 8 progress events, final 5,005,207 bytes, 125,074 ms; final SHA-256 `af5f371b82763d68bba492d3856da53732a7a2356b206bae265f2aa96b254d41`; saved audio played through MediaSession. Official creator licensing page was checked.

HTTPS used the environment's explicit emulator test proxy and trusted test CA, with certificate and hostname verification enabled. These live tests ran in the Dev variant so instrumentation-only transport could be supplied; the production bridge rejects test configuration. No downloaded music is bundled in the APK/source. Runtime on Android: Python 3.12.15, yt-dlp 2026.10.21, EJS 0.8.0, Mutagen 1.48.1, QuickJS-NG 0.17.0. EJS lib/core boot and yt-dlp runtime recognition passed; **no authorized live YouTube extraction was tested**. Node/Deno/FFmpeg/ffprobe were absent.

Original MP3 fixture compressed payload was unchanged after tags/art. FLAC/M4A/Ogg/WAV fixture audio packet hashes were unchanged after supported tagging; WAV bytes were left alone. Truncated, HTML and video-containing input was rejected before publication. Cancellation occurred after real transferred bytes, without completed shared audio. The real SAF queue check failed the first entry, completed later entries, cancelled another batch without blocking a following batch, retried in requested order while preserving a manual edit, and reused a verified source with no new transfer.

## Actual installation/migration and offline operation

The original installed v1.0.0 debug app contained 5 tracks, 3 playlists, 6 entries, 1 favorite, 5 history rows, 2 import logs and 7 import-detail rows. Android rejected the new production certificate over it as expected. The old-key Migration APK installed in place. Real SQLite validation found **every playlist/order/favorite/history row preserved exactly**, schema 2, no active global tracks and no implicit source registrations. The migration notice and actual DocumentsUI JSON export worked; the 4,124-byte backup contained all five tracks and three playlists plus playback/preferences. The original AVD/data was retained, not uninstalled.

On a separate fresh AVD, the non-debug owner-signed production code-2 APK opened with Wi-Fi/mobile disabled and no broad storage permission. Library was empty until a real DocumentsUI folder selection. Only the selected original 180-second MP3 was indexed; an outside-folder WAV was excluded. Real MediaSession remained playing after Home and screen-off. A named playlist was created for upgrade retention.

## Actual production updater / Android installer

A same-production-key code-3 APK was built **only as an emulator fixture**, never published as a fake version. Root adb seeded its private Ready record/cache solely for test setup; the app itself does not request root or have a test endpoint. Production `ApkValidation` and the real `PackageInstaller` UI were used.

Unknown-source denial preserved the installed code-2 app and ready APK. The user-granted Android settings path worked. Cancelling the actual OS installer left code 2 intact. On retry the app was force-stopped before OS confirmation, then Android successfully replaced it with code 3. The specific temporary APK was already deleted before the next manual app launch; the pending record was cleared, and a repeat launch remained clean. Playlist and selected library/source survived. JSON evidence: `actual-installer-upgrade.json`. Next-launch interrupted-download/replacement cleanup paths were also exercised as state fixtures; no callback timing guarantee is made for other Android/OEM versions.

## UI / security audit

Actual emulator screenshots were inspected for Home, Library, Search/real IME, Playlists, Now Playing, Import/source controls, Downloads/real IME, migration and installer. Dark/light, 320/360 dp, portrait/landscape and 180% font were exercised. The initial large-font hero squeezed text; it was repaired into a vertical card. Launcher padding was removed from small in-app logo tiles without changing the supplied mark. Startup now waits for stored appearance to avoid a wrong-theme flash. A black transition-frame landscape capture was discarded and replaced by a rendered production landscape capture. No unintended list/mini-player/navigation/IME obstruction was found in inspected layouts; partially visible scroll content is reachable normally. This is not exhaustive screenshot coverage of every possible text/device size.

All **144 bundled ELF files** had PT_LOAD alignment at least 16 KiB; this is static evidence, not a 16-KiB device run. Exact resolved **96 Maven + 4 PyPI packages** were queried against OSV with no advisory matches. Native runtime versions/linkage are inventoried separately; OSV absence is not a security guarantee. `apksigner` verified production v2/v3 RSA-4096 signatures. Secret scans excluded private signing configuration/keys from git/public archives; owner-encrypted remote signing secret names were verified. Actions recovery execution was not tested.

Not executed: physical Xiaomi/HyperOS, real Bluetooth/wired headset commands/disconnect, physical interruption hardware, API 35 runtime/16-KiB page-size device, 32-bit builds, very large real-library performance, authorized live YouTube/Spotify OAuth integration, or independent Play Protect/Play Store/security-scanner review. No guaranteed platform support or approval is claimed.
