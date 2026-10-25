# Spoti Termux 1.2.1 → Yono Music 1.1.0

The provided handoff prompt and nested ZIP were read before implementation. Nested archive SHA-256 was verified as `89dcf50a24829e3bdd07310afa8b838028f9a50e449afd87b16b782392b8a56b`. Paths, counts, sizes and archive integrity were checked before isolated extraction. `spoti.py`, `spoti_sources.py`, `spoti_folders.py`, README, VALIDATION, requirements and relevant tests were reviewed. No legacy installer, shell script or downloaded binary was executed.

The original is an interactive Python/Termux application using yt-dlp, YouTube Music metadata/search and FFmpeg/ffprobe, with folder/index state and terminal progress. Its useful logic includes bounded TXT/CSV/JSON parsing, full artist/title/version/duration verification, source identity and hashes, local reuse, safe names, playlist folders, metadata/art, error journals and retries. Termux paths, shell/bootstrap/update logic, environment-specific binaries and implicit external tool availability do not transfer to an APK.

## Actual migration

Kotlin retains the native UI/playback and implements list parsing, conservative recording matching, selected-source deduplication, transactional Room playlist/order/history, durable transfer jobs and SAF publication/recovery. Python is genuinely embedded through Chaquopy 17.1.0: pinned yt-dlp 2026.10.21 is called as a library from Kotlin, with typed progress/cancel callbacks and private output paths. It is not a WebView, terminal wrapper or instruction to install Termux. Commons remains an additional source/catalog.

Python 3.12.15 and both 64-bit Android ABIs are bundled. EJS 0.8.0 and QuickJS-NG 0.17.0 are packaged at build time; the fixed NDK-built runner is extracted read-only under nativeLibraryDir, avoiding execution of downloaded code from writable app storage. Real Android tests ran the runner, loaded bundled EJS lib/core and confirmed yt-dlp recognized its runtime. This proves runtime integration, not successful live extraction from every service.

FFmpeg/ffprobe are not bundled. Android MediaExtractor/MetadataRetriever perform stream/container/duration/completeness checks; Mutagen writes MP3/FLAC/Ogg/MP4 tags/cover without re-encoding. Audio packet hash comparisons passed for original fixtures. WAV metadata remains in Room and the attribution file. Video extraction, transcoding and stream merging are **not implemented**; sources requiring them fail before shared publication. This is a deliberate technical pipeline limit, not a claim that FFmpeg cannot run on Android.

Only an explicit authorized source link is sent to yt-dlp. Reviewed candidates can form ordered batches; each requested artist/title/version/duration constraint is checked conservatively, including embedded tags for generic sources. Exact completed source IDs plus verified file bytes can be reused regardless of the filename. Local metadata matches require full recording identity and duration. No deduplication scans outside the selected sources and application download history occur.

## Source-specific scope and evidence

| Source/integration | Delivered scope and actual evidence | Limits / specific reason |
|---|---|---|
| Owner-generated local HTTP fixture | Real embedded yt-dlp transfer, progress/cancel, native validation, tags/artwork, hashes; Room/SAF queue/retry/order/reuse tested | Loopback HTTP is possible only through explicit instrumentation test configuration in a debug build; production requires public HTTPS |
| Wikimedia Commons | Additional authorized catalog and direct-source embedded extraction. One CC BY-SA Ogg was downloaded live, saved through SAF and played by MediaSession | License and performer credits must be retained; composer public-domain status does not license every performance |
| Explicit creator-permitted direct HTTPS audio | Generic embedded extraction/standalone audio pipeline implemented | A Kevin MacLeod MP3 from the official Incompetech site was downloaded live under CC BY 4.0, saved through SAF and played by MediaSession. Other creator sites are not blanket-tested |
| YouTube / YouTube Music | yt-dlp extractor and bundled EJS/QuickJS are present; explicit URLs require separate confirmation of source/service download authorization | No authorized live YouTube download was exercised. Content rights alone do not override service terms, which restrict downloading except as authorized by the service or applicable permission. No blanket support claim, login/cookies, paid access/DRM bypass, browser impersonation or missing-token workaround. The old automatic ytmusicapi song-search/verification orchestration has **not been migrated** |
| Spotify | Local metadata exports/TXT/CSV/JSON can be imported and matched | No developer OAuth client/authorized API integration is configured in this build. Spotify metadata API does not provide full-track MP3 downloads. Spotify links receive an explicit explanation; no scraping or fabricated audio |
| Other bundled yt-dlp extractors | Can be attempted through explicit authorized source review where their requirements fit the pipeline | Not individually live-tested. A bundled extractor name is not tested support. Login, DRM, JS module/network facilities, external binaries, stream merging or server changes may prevent a particular source |

A tool such as yt-dlp is not labelled illegal as a whole. Technical compatibility, service terms and rights to a specific recording are separate questions. The app's confirmation is not a license grant. Published Commons/creator licenses and original fixtures provide the permitted material used for tests.

## Safety and licensing

No existing audio is migrated, renamed, retagged or deleted. New downloads use sanitized names, collision-safe new documents, owned staging markers, post-copy hashes and source/license sidecars. Removing a playlist removes database references only. Failed/partial output is never presented as successful playback.

Mutagen is GPL-2.0-or-later; this combined first-party application is released under GPL-3.0-or-later. MIT/Apache/Unlicense/PSF/MPL dependencies retain their licenses and source directions. No unknown Termux native binaries or credentials are copied. See THIRD_PARTY_NOTICES.md, SECURITY.md and TEST_REPORT.md. This is a substantial embedded-engine migration, **not a claim that the entire CLI or all its streaming-source automation has been reproduced**.
