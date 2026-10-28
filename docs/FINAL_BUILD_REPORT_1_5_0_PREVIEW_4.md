# Yono Music 1.5.0-preview.4 — recovery and minimal online UI

Karisyono · com.yonzmusic.app.dev · versionCode13 · Room3 · existing standard Dev certificate. Separate Preview, installable beside official1.4.2 or over a same-key Dev installation without uninstall. Official application ID/signing, stable metadata and latest release remain unchanged.

## Implemented

- Simplified only the new online result and stream-save UI: one row, tap play/pause, More for queue/save/source; grouped compact configuration with destination and playlist side by side; repeat Download taps enqueue directly using a saved SAF grant and report the actual result. Existing Home/Library/Playlists/player and list import preserved.
- Pinned-format refresh401/403, strict range/total validation, finite cooperative resolver/transport timeouts, at most two transient retries per DataSource across progress/reopens, sanitized current-source failures and safe manual replay. Same native Media3 session, memory-only signed URLs.
- Request-scoped cancellation, serialized selected retry/recovery, transactional work-generation checks, source+SHA256 receipt verification before completion/indexing, exact-source metadata retry and verified existing-file reuse. Original audio, metadata/.cover, local collection IDs and no automatic file deletion retained.

## Build and verification

Preview assembly/lint passed (0 errors, 116 warnings);133 JVM,177 Python and19 distinct Android fixture/regression cases passed. Final two-case native/UI repetition passed at font100% and150%. Real MP3/WebM/Opus/M4A decode/seek used controlled owned transport; this is not a live platform stream. Actual host source resolution failed with proxy CONNECT403; actual Android live streaming failed with a network error. Details, initial test/implementation failures and fixes are in TEST_REPORT.md. No live streaming/queued-download/HyperOS/Play Protect success claimed. Preview only; stable1.4.2 unchanged.

Exact collection rows retained on Dev12→13 and final13→13 replacement; no uninstall/clear-data. No new schema, permissions or dependencies. No crossfade/ReplayGain/lrc/customDSP or source-code copying from references.

## Artifact, signing, SDK and permissions

Built APK: `app/build/outputs/apk/preview/app-preview.apk`. Delivered copy: `/workspace/deliverables-v1.5.0-preview.4/Yono_Music_v1.5.0-preview.4.apk`. Size 45,685,268bytes; SHA256 `2540dd103777e993471ad808ba26b8b423d82e1fa4455d2f4a08a11cf8c5fe07`.

Debug/Dev signing certificate SHA256 `3e8117db4ac9b9e71429136a4d687bdaec8a75f1c052ee7c153b6c62c2fda740`; no production key/password included. Debug Preview is not an official production-signed APK. SDK min26/compile+target35, arm64-v8a/x86_64; JDK17, Gradle8.11.1, AGP8.9.1, Kotlin2.1.20, Media3 1.6.1 and pinned embedded runtime dependencies unchanged.

Declared app permissions: INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS, WAKE_LOCK, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK, FOREGROUND_SERVICE_DATA_SYNC, REQUEST_INSTALL_PACKAGES. The merged manifest also includes dependency RECEIVE_BOOT_COMPLETED and the package signature-level DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION. No broad storage/media-read permission; selected SAF grants only. Actual manifest/signature/ELF inspection is distributed as ANDROID_PACKAGE.txt, SIGNATURE.txt and APK_INSPECTION.json.

## Distribution

New tag/release v1.5.0-preview.4 is a prerelease with make_latest=false. No stable update.json or official APK supplied; no old tag/release overwritten and no force push. Source ZIP is git archive of the release commit with resources/assets/tests, excluding keys/local configs/build outputs; dependency sources/asset ZIPs, actual reports and checksums accompany it. Packaging adds exact source commit and public artifact verification. Historical preview3 report retained in docs.
