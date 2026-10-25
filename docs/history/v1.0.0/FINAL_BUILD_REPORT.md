# Final build report — Yon'z Music 1.0.0

Built and verified on 25 October 2026. This delivery contains a real native Android project, an installable debug APK, an optimized unsigned release, original assets, and test/build evidence.

## Artifacts

| Artifact | Actual path | Size | Signing |
| --- | --- | ---: | --- |
| Installable debug APK | `/workspace/deliverables/Yonz_Music_v1.0.0_debug.apk` | 21,820,428 bytes (20.81 MiB) | Standard Android debug certificate; APK signature verification passed |
| Optimized release | `/workspace/deliverables/Yonz_Music_v1.0.0_release_unsigned.apk` | 2,466,473 bytes (2.35 MiB) | Unsigned; not installable until owner signs it |
| Complete source archive | `/workspace/deliverables/Yonz_Music_v1.0.0_Source.zip` | See delivered file | Source, Gradle wrapper, resources, schemas, tests, assets and documentation |
| Editable/raster brand assets | `/workspace/deliverables/Yonz_Music_v1.0.0_Assets.zip` | See delivered file | Original SVG/PNG identity plus Android launcher/splash vectors |
| Working source | `/workspace/yonz-music` | Native Kotlin project | No production/private signing credential included |

Debug APK SHA-256: `e1ea56aa5331ef833b61dc936f25d687a3e055ea2a4e5bfa93fb925e1b43b438`

Unsigned release SHA-256: `8cfcd2d63cfea6e0e3b138d40e80331fb6ba1e728ca9b48fbe93970940d450e6`

A temporary copy of the optimized release was signed with the existing development/debug key solely for an emulator smoke test. Launch, the Room music library and local MP3 playback passed. The delivered release artifact remains unsigned. No production key was supplied or invented, and no debug-signed artifact is described as production signed.

## Application and toolchain

- Exact application label: **Yon'z Music**; ID **com.yonzmusic.app**; version name **1.0.0**, code **1**.
- Minimum Android API **26** (Android 8), target and compile API **35** (Android 15).
- Full JDK **17.0.21**, Gradle **8.11.1**, Android Gradle Plugin **8.9.1**, Build Tools **35.0.0**.
- Kotlin and Compose compiler plugin **2.1.20**, KSP **2.1.20-1.0.32**, Compose BOM **2025.04.01**, Media3 **1.6.1**, Room **2.7.1**, DataStore **1.1.4**. Compatible versions were compiled together; these are pinned tested versions, not a claim to be the newest available releases.
- Release minification/resource shrinking enabled. Debug is intentionally debuggable. Release is not debuggable.
- Adaptive icon, Android 13 monochrome icon, native splash branding and original vector artwork included. No downloaded font, cover URL or remote configuration is needed at startup.

## Implemented

- Kotlin / Compose Material 3 native UI with four main destinations, shared filtered detail views, original mint/violet Y-and-sound identity, separate dark/ivory light palettes, system theme selection, opaque surfaces and reduced-motion preference.
- Read-only MediaStore and SAF indexing; cached Room library; background metadata work; embedded/MediaStore artwork with bounded caching; albums, artists, folders, sorting, local song/artist/album and playlist-name search; missing/unreadable handling.
- One ExoPlayer / MediaSessionService and shared controller: local MP3, AAC/M4A, FLAC, WAV and OGG where device decoders support them; pause/resume, seek, previous/next, shuffle and repeat modes, queue edits, background notification/media commands, audio focus and noisy/headphone handling, paused context restoration. Actual hardware limits are separated below.
- Mini-player above navigation and a dedicated responsive Now Playing dialog; lifecycle-limited foreground progress updates. Album/control sizing adapts to short windows; large text/landscape can scroll.
- Transactional Room playlists, add/remove/reorder/rename/delete confirmation, favorites, recent history, playback position, M3U8 reference export, queue play-next/add/remove/move, session sleep timer with remaining time/cancel, system equalizer handoff using the real audio-session ID with an unavailable-panel message.
- Consolidated local audio/folder, TXT/CSV/JSON/M3U/M3U8 and pasted-list import. Conservative matching uses real local files; previews report matched, duplicate, invalid and unmatched entries. No audio is invented for a missing title. Bounded per-row import history avoids a large SQLite cursor blob.
- No ads, login, subscription, analytics, tracking, required connection, streaming downloader or self-updater. Runtime app needs no network permission.

## Legacy migration

Inspected the ZIP structure, Python modules, readme/validation documents, requirements, installer/updater source and representative tests without executing them. Reused concepts: numbered/Markdown lists, CSV/JSON exports, full-credit artist/title normalization, recording-version preservation, conservative ambiguity handling, duplicate suppression, playlist order, M3U references, folder browsing, artwork display and import reports.

Excluded: yt-dlp/YouTube acquisition, public-page scraping, Spotify OAuth/public readers, shell/FFmpeg execution, embedded Termux/Python, remote artwork scraping, binary reuse, destructive physical folder/name reorganization, audio retagging, byte-hash binding migration and custom DSP. Remote APIs were unnecessary for this offline release. Existing music is never moved, renamed, overwritten or deleted by product flows. See LEGACY_MIGRATION_REPORT.md.

## Final compilation and verification

Final command: `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintRelease`.

- **Compilation/build: PASS** for debug, Android test APK and optimized unsigned release.
- **JVM tests: 44 passed, 0 failed.**
- **Final Android API 29 instrumentation: 6 passed, 0 failed.** Includes actual PCM playback, seeking, focus interruption/recovery, valid audio-session extras, timer cancellation, paused queue persistence and clearing, and database consistency/10,000-row history.
- **Debug and release lint: 0 errors, 22 nonblocking warnings each.** Warnings are preserved, chiefly newer-version notices, KTX/style suggestions and deprecated platform status-bar APIs used compatibly. No blanket baseline suppresses errors. The exported media-service warning has a targeted documented exception because Android media integration requires export and connection callbacks authenticate controllers.
- **Debug Android APK signature verification: PASS.** Release unsigned status independently checked and expected signature verification does not pass until signing.
- **Optimized release smoke: PASS** (temporary development signing, launch/library/local MP3). This does not substitute for production-key or physical-device testing.
- Device/UI checks covered fresh install, permission denial/grant, empty/cached library, each of five formats, controls, search, favorites, playlists and confirmations, pasted and M3U import, dark/light, queue editing, background/screen sleep, media-key commands, missing/corrupted synthetic files, rescan, process recreation, widths 320/360 dp, font 1.8 and landscape. Actual screenshots/logs are included.
- Five test fixture hashes were compared after import/error-recovery workflows and remained byte-identical. Test-induced missing/corrupt files were owned synthetic fixtures and were restored.
- OSV returned no advisories for the **131 queried resolved runtime coordinates** and nine selected direct/build components. This is not a guarantee of vulnerability absence or an APK scanner verdict.

## Packaged permissions and component exposure

- `READ_EXTERNAL_STORAGE` capped at API 32; `READ_MEDIA_AUDIO` on modern Android. Only the appropriate read grant is requested at runtime.
- `WAKE_LOCK`: Media3's playback-only CPU lock, released when playback is paused/stopped; no permanent custom lock.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK`; service type is mediaPlayback.
- AndroidX's app-scoped signature permission `com.yonzmusic.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` protects dynamic receivers.

**No INTERNET, ACCESS_NETWORK_STATE, microphone, contacts, SMS, location, overlay, broad-storage, boot/autostart, battery-exemption or APK-install permission.** Media-session notifications use Android's media exemption; POST_NOTIFICATIONS is not requested. Launcher activity and media service are exported as required; media controllers must be own-package or Android-trusted, and media IDs resolve only to indexed local content URIs. AndroidX's profile installer receiver is system-DUMP-permission protected; startup provider is private. Backup/cloud/device extraction is disabled. No private signing key is in the archive.

## Limits and unexecuted checks

No physical Xiaomi HyperOS, Bluetooth/wired headset, secure lock-screen hardware, long battery endurance or audio-quality measurements. An API 35 software emulator had System UI timeouts, so its complete runtime suite and real API 31+ blur performance were not verified; the API 29 fallback was verified. API 26 compatibility is compilation/lint checked, not device certified. Full timer expiry across long durations/reboot, every SAF provider/folder case, very large real audio collections, and export portability are not claimed tested.

Collection import is metadata matching, not downloading. Limits are 2 MB / 10,000 list rows / 2,048 characters per entry; folder scanning is bounded at 10,000 tracks and 20,000 directories. Different providers can expose the same physical audio through different URIs; possible metadata duplicates are reported, not automatically merged/deleted. Album grouping uses album plus displayed artist. M3U export holds content references, not audio or a portable permission backup. Queue editing shows saved order; shuffle can change playback order. Playback checkpointing can lose the latest few seconds on forced process termination. Sleep timer is session scoped. OEM background restrictions remain under user/Android control.

**Play Protect / independent APK scanning: NOT performed. No approval, evasion or guaranteed classification is claimed.** The delivery follows conventional Android build/signing practices; independent scanner and physical-device review remain external tasks.

Full workflow detail: TEST_REPORT.md. Build/signing: BUILD_INSTRUCTIONS.md. Security boundaries: SECURITY.md. Original identity: brand/IDENTITY.md.
