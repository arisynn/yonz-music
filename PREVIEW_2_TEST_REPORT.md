# Yono Music 2.0.0 Preview 2 — focused validation

This prerelease changes discovery, catalogue storage, offline source selection, and download containers. Verification is limited to those paths; the historical full regression suites were not repeated.

## Results — 2026-11-11

- Python: **4/4 passed**, including rejection of incomplete playlists.
- Android API 26 Dev: **3/3 passed**, including native Opus remux/tag/seek/decode and network-free source reuse.
- Final official APK: **signature/package/code 18 verified**, production **17→18 update passed**, complete collection-row snapshots retained; Room schema **5** verified.
- Final UI: **4 screenshots inspected**, at 320dp / font 150%, light and dark. The search placeholder and redundant headings were compacted after the first visual check. These final changes affect layout only; the audio cases were not repeated.
- Final release build: **R8/resource shrinking and release vital lint passed**.

The focused audio cases ran on the Dev variant. The final owner-signed release APK was separately installed for upgrade and UI verification. Public streaming/download availability remains subject to the limits below.

## Focused cases

- Python: actual chart playlist selection without invented artist ranks; conservative metadata enrichment rejects live/studio mismatches and ambiguous recordings; a real owned Opus/WebM fixture becomes Ogg Opus, embeds tags/cover, retains identical compressed audio packets, and rejects an actual AAC fixture.
- Android API 26: real Media3 Opus remux, native container/duration validation, Mutagen tagging, seek and offline decode; additive Room 4→5 migration; canonical catalogue identity/source mapping reuses a hash-verified content URI with a network data source that throws on any attempted use.
- Production update: install code 18 over owner-signed code 17, retain the same package/certificate, and compare complete row snapshots of tracks, favorites, history, playlists, playlist_entries, and online_activity. Validate the added catalogue tables.
- UI: 320dp display, 150% font; Beranda/Cari/Koleksi in light mode and Koleksi in dark mode. Check the final APK for runtime crashes while navigating.

The final outcomes and APK identity are stored under [docs/verification/v2.0.0-preview.2](docs/verification/v2.0.0-preview.2). The Android harness required fixture corrections: ByteArrayDataSource input must be nonempty, and Python `exec` called from Java needs explicit globals with the file path passed as a value instead of a JSON-escaped Python literal. Its initial output is retained separately; final results use the corrected harness.

## Public providers and limits

A live MusicBrainz probe returned Last Child and Diary Depresiku recordings, including the Everything We Are Everything release. A subsequent album query returned HTTP 503. The unauthenticated YouTube Music Charts request failed with CATALOGUE_NETWORK from this environment. Neither a live chart listing nor a complete public browse→download→airplane playback journey is claimed as verified. The controlled offline/native cases are separate from public-provider availability.

Provider failures show retry/error states and use prior successful cached responses. Song search can fall back to source metadata. Missing or ambiguous catalogue matches are reported rather than selecting another performance. Physical-device battery/network behavior and every album/radio variant remain outside this focused pass. Radio and album downloads expose per-track queue results; some sources may not offer a standalone Opus stream.

## Build

Release uses code 18, `com.yonzmusic.app`, owner signing, R8/resource shrinking, and release vital lint. yt-dlp 2026.10.26; EJS 0.8.0; ytmusicapi 1.12.3; all Media3 modules 1.11.1; AGP 8.10.1; compile SDK 36; target SDK 35; min SDK 26; JDK 17. Build/R8 and emulator verification run sequentially.

Completed old WebM files remain unchanged. New requests publish real Ogg Opus `.opus` only; original compressed packets are remuxed without reencoding. No private signing files, credentials, or production-targeted QA instrumentation are included in release assets.

Final APK SHA-256: `9cffd2a967f9c13bd6029a04d24d89af18fd4266d98f05c11a1fcf42652e736a` (30130872 bytes). Implementation commit: `3ee2a08`.
