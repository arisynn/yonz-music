# Yono Music 1.3.0 — final build report

Creator: **Karisyono**. Repository: https://github.com/arisynn/yonz-music. GPL-3.0 open source. Official tag: `v1.3.0`; version code **6**, production version **1.3.0**, Dev **1.3.0-dev**. Existing branding, user-selected mist-blue logo, application IDs and signing identities are retained.

## Implemented

- Additive Room 1→2→3 migration; exact-byte selected-SAF relocation/rename reconciliation; explicit ambiguous/out-of-grant relink; typed missing/permission/temporary-storage/unreadable states. Existing IDs, favorites, playlist order, history and qualified listening references survive. Shared MediaSession queue URI/paused position is updated after a committed relocation. No user music is automatically deleted, moved or overwritten.
- Preserve original audio container/encoding. Source metadata outranks conflicting tags; actual tags outrank filename fallback; a correct title tag is never split as a filename. Persist full artist credits, known album, duration, source identity/URL and cover. Safe supported tagging plus portable SHA-bound JSON/cover companions, old-download metadata/cover repair without audio redownload, size-aware/revision-aware artwork cache.
- One persistent download list/history, genuine progress or indeterminate state, per-item/all cancel, retry, readable output opening, publication receipts and stable retry order without overwriting manual playlist edits. Creation and durable playlist mapping share a transaction. Repeated destination/playlist folder names do not create an extra nesting layer.
- Compact Home (two recent previews, recent additions, known albums), saved scroll, optional explainable offline Smart Mix from qualified elapsed listening, calm reduced-motion-aware transitions. Metadata editor's real IME obstruction found in audit was repaired.

The embedded Python/yt-dlp/EJS/QuickJS engine and native Kotlin/Media3 UI/playback continue the safe CLI migration; Commons remains an additional source. TXT/CSV/JSON/M3U/M3U8, pasted-list formatting, conservative matching/deduplication and playlist organization remain available. New source-support/live-download claims are not added: current tests use owned audio/source fixtures; historical permitted live Commons/Incompetech checks are separately reported. Spotify OAuth is not newly configured, Spotify metadata is not Spotify MP3 audio, and DRM/access-control bypass, credentials/cookies, FFmpeg conversion/merge and whole-storage automatic scans are excluded.

## Measured builds and verification

- **142 host Python**, **104 JVM**, **27 distinct Android instrumentation cases** passed across regression/rechecks. Earlier harness failures, a System UI emulator ANR, and the 768-MiB R8 heap failure are retained/described with successful corrected checks. The final transaction change was rechecked natively.
- Actual Dev code-5→6 upgrade retained all original 15 track IDs / 13 playlists / 18 memberships / 2 history keys. Owner-signed production code-3 QA fixture→6 retained its small collection and passed offline MP3 and screen-off playback. No uninstall, data clear or database reset was used.
- Dark/light, 320-dp and 150% font, artwork, Home scroll, IME, dialogs, download list and Smart Mix switch were exercised in actual emulator frames. Production uses the separate 360-dp AVD.
- Lint **0 errors / 107 warnings**. OSV lookup: **105 exact packages, 0 advisory matches**; not a security certification. 144 ELF payloads statically align for 16-KiB pages; no 16-KiB runtime device was tested.

## SDK, artifacts and signing

JDK 17, Gradle 8.11.1, AGP 8.9.1, Kotlin/Compose plugin 2.1.20; compile/target SDK **35**, min SDK **26**; build tools 35.0.0, NDK 28.2.13676358; **arm64-v8a / x86_64**. Room 2.7.1, Media3 1.6.1, Chaquopy 17.1.0/Python 3.12.

| Artifact | Application ID | Signing |
| --- | --- | --- |
| `Yono_Music_v1.3.0.apk` | `com.yonzmusic.app` | Existing owner certificate, v2/v3, R8/resources optimized |
| `Yono_Music_v1.3.0_debug.apk` | `com.yonzmusic.app.dev` | Existing standard Dev debug certificate, v2 |
| `Yono_Music_v1.3.0_Source.zip` | Complete source/resources/branding/migrations/tests/docs | No keystore/password/token |

Production certificate SHA-256: `ebefa44cc5fba1292fec9fc25bb4840e41e501bb0958a86931c046fd1be523e5`.
Dev certificate SHA-256: `3e8117db4ac9b9e71429136a4d687bdaec8a75f1c052ee7c153b6c62c2fda740`.

Build outputs: `app/build/outputs/apk/release/app-release.apk` and `app/build/outputs/apk/debug/app-debug.apk`. Final distribution directory: `/workspace/deliverables-v1.3.0`. Exact measured byte sizes, final source commit, hashes and updater-contract results accompany this report as release assets. Install the channel matching the existing package; production and Dev keep separate data. No signing key was replaced; keys remain private and outside source/release archives.

## Permissions

INTERNET, ACCESS_NETWORK_STATE (optional source/update operations); POST_NOTIFICATIONS; WAKE_LOCK; FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK, FOREGROUND_SERVICE_DATA_SYNC; REQUEST_INSTALL_PACKAGES (verified official user-confirmed updates); RECEIVE_BOOT_COMPLETED (WorkManager recovery); AndroidX's own signature-only dynamic-receiver permission. No READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE / MANAGE_EXTERNAL_STORAGE, microphone, contacts, location, overlay, Accessibility Service, root or tracker permissions. The library opens from saved Room data and scans only explicitly selected sources on user action.

## Limits

No physical HyperOS/Bluetooth/headset/removable-media/API-35/16-KiB runtime testing, very-large real-library benchmark, new direct public-service download, full OS-kill-during-active-transfer test, or independent Play Protect/Play Store classification. Provider-failure and recovery fixtures are identified as such. Google Play Protect approval is not guaranteed or independently verified.

Older moved files without historical byte evidence require explicit relink; externally re-tagged bytes are not automatically assumed identical. Single-file grants cannot silently read sibling metadata/images; select a folder or import the manifest/local cover explicitly. Unknown artists/albums are not invented. Qualified listening counts stay in Room and are not yet exported by collection JSON backup. History UI loads the newest 100 plans within 24 MiB; older journals remain private. Native/provider blocking I/O can delay cancellation until it returns. Notification artwork invalidation for externally overwritten same-URI images is not independently verified. See [the detailed update scope](docs/UPDATE_1_3_0.md) and [actual tests](TEST_REPORT.md).

Official release: https://github.com/arisynn/yonz-music/releases/tag/v1.3.0
Production APK: https://github.com/arisynn/yonz-music/releases/download/v1.3.0/Yono_Music_v1.3.0.apk
Dev APK: https://github.com/arisynn/yonz-music/releases/download/v1.3.0/Yono_Music_v1.3.0_debug.apk
Source: https://github.com/arisynn/yonz-music/releases/download/v1.3.0/Yono_Music_v1.3.0_Source.zip
