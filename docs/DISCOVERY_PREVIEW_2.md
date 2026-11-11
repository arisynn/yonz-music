# Streaming discovery — Preview 2

Version `2.0.0-preview.2`, code 18, updates `com.yonzmusic.app` with the existing owner certificate. Preview 1 and stable 1.5.2 collection records and completed audio files remain intact.

The primary navigation is Beranda, Cari, Koleksi. Beranda reads actual Indonesia/Global YouTube Music chart playlists with yt-dlp and shows the chart's popular artists without inventing ranks. Recent listening is secondary. Device music and completed downloads live under Koleksi → Offline.

Cari browses recordings, albums, artists, and public playlists. MusicBrainz supplies canonical recording/artist/release-group identities, artist discography, and ordered release tracks. Cover Art Archive supplies release covers. Public playlist and chart audio references come from YouTube Music. Two-character artist names are accepted. Album/artist data is independently saved; saving does not download audio.

`MetadataProvider`, `ChartsProvider`, and `AudioResolver` separate catalogue browsing from audio selection. MusicBrainz requests use a meaningful User-Agent and a shared maximum of one request per second. Responses are cached in Room; cached discovery remains usable when a provider fails. Recording-to-YouTube mappings persist separately from the bounded response cache. Signed Googlevideo URLs remain memory-only. Metadata enrichment accepts only a unique match with full artist/title/version/duration agreement. Ambiguity preserves the original information.

A selected catalogue recording resolves lazily to a verified YouTube Music recording. ISRC queries precede title/artist queries when an ISRC exists; title, credits, version and duration verification still apply. Every remote queue can reuse a readable local source only after verifying its exact source identity and full SHA-256. This also applies to album/playlist queues, not only single-song playback.

Koleksi contains favorites, local/online playlists, saved albums, followed artists, saved radio stations, and Offline. Radio stores a seed and its last snapshot. Refresh changes that station's saved snapshot without replacing the playing queue. Downloading an album, playlist or radio enqueues the current list, with individual progress/retry/cancellation; later radio refreshes never download automatically.

New engine downloads select a standalone HTTPS Opus stream. Media3 `OggMuxer` remuxes encoded packets into Ogg Opus `.opus`; no decoder/encoder or quality conversion is involved. Android compares encoded packet hashes across remux, and checks the actual output MIME, duration, complete packet timeline, final tagged-file hash and publication receipt. Mutagen writes title/artist/album/ISRC and supported artwork. AAC-only sources fail with a clear message while remaining streamable. Existing completed WebM files stay playable. Pending unpublished jobs use the Opus profile; old WebM receipts do not qualify as new Opus output.

Room 4 → 5 only adds catalogue-cache and saved-catalogue tables. Backup schema 3 includes saved albums/artists/radio and accepts schemas 1 and 2. Existing IDs, foreign keys and local file paths are unchanged.

Toolchain: yt-dlp 2026.10.26, EJS 0.8.0, ytmusicapi 1.12.3, Media3 1.11.1 (all modules), AGP 8.10.1, compile SDK 36, target SDK 35, min SDK 26, Gradle 8.11.1, JDK 17. The host FFmpeg binary is used only for owned test fixtures; Android uses Media3 for remux.

Public catalogue coverage and provider availability vary. A missing/ambiguous recording is reported instead of selecting a different performance. Release validation and live-provider limitations are recorded in `PREVIEW_2_TEST_REPORT.md`.
