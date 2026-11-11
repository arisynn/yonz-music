> Historical Preview 1 behavior. For Preview 2, see [DISCOVERY_PREVIEW_2.md](DISCOVERY_PREVIEW_2.md).

# Yono Music 2.0 — streaming candidate

Based on the official v1.5.2 tag (4aa2fb6). This working version is not a published stable release. Production package remains `com.yonzmusic.app`; code 17, name 2.0.0. Preview uses `com.yonzmusic.app.dev` / `2.0.0-preview.1`. Promotion requires real-source playback verification and the comparison described in TEST_REPORT.md.

## Listening

Beranda offers search, resume, online listening history, favorites, personal recommendations and saved online playlists. Cari opens online search by default; Koleksi remains available. Pustaka keeps selected-device audio and permanent downloads. Navigation is Beranda / Cari / Pustaka / Daftar putar. Original logo/colors, Indonesian UI, system themes and reduced motion remain.

Tap a search result to play. More offers next, queue, download, favorite, radio, playlist, source, destination configuration and quality. The shared player now uses its heart for favorites on both online and local tracks. Its More menu includes streaming/downloading options.

Autoplay is on by default for online queues. Once two or fewer tracks remain, a bounded public related-song request appends up to 20 unique items. Current/queued/session-failed IDs are excluded. A local item anywhere in the remaining queue prevents expansion. Repeat takes precedence. Pause cancels pending work; queue identity and pause/repeat/preferences are checked again after suspending registration. Explicit Radio works even if ordinary autoplay is off. Turning autoplay off removes unplayed automatic additions but retains explicit Radio additions. No autoplay occurs after process restoration. The queue is capped at 500 entries; access/catalogue failures leave existing items intact and show a short status.

Online favorites and qualified history live in the indexed `online_activity` table, not local `tracks`. Qualified listening follows the existing accumulator: 30 seconds or half the duration, minimum 5 seconds and capped by song duration; seeking does not add listening time. At most 1,000 recent nonfavorite entries and 5,000 favorites are retained. History can be disabled or cleared without deleting favorites/playlists/audio. Clearing invalidates pending history writes. Personalization uses a recent online recording or a favorite as the public radio seed, without an account/backend. Empty profiles get a search invitation, not an invented recommendation feed.

## Streaming and storage

Media3 1.6.1 is still the only player. Bundled yt-dlp 2026.10.21 resolves exact selected source IDs. Existing ytmusicapi 1.12.3 supplies music search and related lists; yt-dlp read-only search is the fallback. Neither is a source authorization guarantee. No account/login, API key, DRM bypass, remote engine installation, custom backend or transcoding is introduced.

Quality defaults to Seimbang: prefer standalone original HTTPS audio up to about 144 kbps (target around 128). Hemat prefers up to about 80 kbps (target around 64). If a ceiling is unavailable, use the lowest available standalone audio; Terbaik requests the best. Actual source bitrate/codec varies. Downloads retain their existing original-format workflow. Quality changes apply on the next playback/open, with the current byte format pinned during seek/recovery.

LoadControl: 5–30 seconds normal buffering, initial 1 second, rebuffer 2.5 seconds. One upcoming recording is resolved and a 5-second preload target applied only once at least 5 seconds of current audio is buffered. Foreground source opens/buffering preempt the single metadata worker through cooperative cancellation. Speculation is cancelled on pause/buffering/queue changes.

Media3 preparation/read granularity can overshoot a duration target; the cache limit and one-item speculation limit remain separate bounds.

A process-wide Media3 SimpleCache uses private app cache storage. Default 64 MiB; options off/32/64 MiB. LRU eviction applies on write and capacity changes; active fragments can temporarily occupy staging space before commit. Cache is cleared separately from permanent SAF downloads. It is not a promise of offline playback: source validation may still need the network. Keys include source/format/codec/container and a revision derived from exact length plus source modification identity. Expiring signed URLs never enter persisted metadata. When revision evidence is unavailable, reuse is confined to that resolved reference; changed identities never share cached byte ranges. Local content handling remains in DefaultDataSource.

## Compatibility and diagnostics

Room migration 3→4 only adds the activity table and indexes; 1→2→3 remain registered. Backup schema 2 adds online activity and streaming preferences; schemas 1 and 2 are accepted. Restore preserves existing online favorites and avoids multiplying aggregate counts. No music files or SAF grants are fabricated by a backup.

Diagnostics are limited to the last 100 timings per stage in memory: engine startup, catalogue, resolution, CDN open, time to playing, and buffering. They contain no query/title/URL/identifier and are never uploaded. Wi-Fi performance must be compared against v1.5.2 using the same 20 recordings/device/network; a 25% median improvement is a target, not a measured claim.

Signing is restored from the owner's private attachment outside git. The actual keystore certificate was checked against the official SHA-256 identity. Build artifacts must independently pass apksigner verification. No signing files, passwords, raw source logs or tokens may be shipped in source archives or releases.
