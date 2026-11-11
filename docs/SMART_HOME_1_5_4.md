# Yono Music 1.5.4 — Smart Home and artwork recovery

Based on v1.5.3 (`2f7f58e`), retaining the existing streaming engine, Media3 player,
MediaSessionService, downloads and Room version 3.

## Audit findings

The owner's phone screenshots show successful streaming, a missing search/playback
cover, and working images from imported online playlists and some chart rows.
The exact failed search URL/HTTP response was not captured, so the report does not
claim that one particular CDN or device decoder caused every missing image.

The code did expose two gaps: search artwork depended entirely on lightweight
catalogue thumbnails, and full extraction's `song.artwork_url` was discarded by
StreamRepository. Missing/unusable search images consequently had no enrichment
path, even after the audio extractor had obtained a thumbnail. The small caption
under SEDANG DIPUTAR displayed the album, not a second current-song title; it now
shows the actual current title to avoid that ambiguity.

## Changes

- Removed the Home identity banner, regional selector, popular-song and
  popular-artist shelves and their Kotlin chart-loading repository. Home no longer
  starts chart requests. Existing cached chart AppState rows are left untouched.
- Added **Lanjutkan mendengarkan**, **Musik untukmu** and **Baru kamu putar**.
  The new repository records actual started online playback, not just typed
  queries or failed playback attempts. Generated radio songs do not continually
  replace the user's chosen recommendation seed.
- Recommendation candidates come from the same existing service-owned watch-radio
  request. They are also prepared when automatic queue extension is disabled,
  after current audio has a stable buffer. This does not turn autoplay back on.
  Radio queue rules and preparation of two upcoming URLs remain in place.
- Home excludes the seed and the five most recent recordings, deduplicates by ID
  and title/artist, preserves provider order and displays at most twelve choices.
  Recent choices and recommendations persist locally in `for-you:v1` AppState.
  The six-hour cache shows useful content on return without chart requests or a
  new server. If recommendations fail, recent listening remains available.
- Added metadata-only artwork lookup with exact `videoDetails.videoId` validation.
  Supplied nested and protocol-relative thumbnails are normalized, and known
  exact YouTube artwork hosts are allowed. No thumbnail URL is manufactured and
  no artwork is matched by approximate song title.
- Missing/failed visible images request bounded recovery: one active image-metadata
  worker, at most five pending IDs, and a five-minute attempt cooldown. Full audio
  extraction also publishes its already-obtained thumbnail without repeating
  extraction or delaying audio. Image content is validated through bounded bitmap
  decoding and existing guarded HTTPS requests.
- Normalized public track metadata is observed through a bounded ID-keyed flow.
  Search, artist songs, online playlist rows, Home and player use that artwork.
  Registering lightweight search metadata retains existing recovered artwork.
- The service replaces only metadata on an item with the same media ID, source URI
  and queue occurrence extras. Cover updates do not call prepare, seek or replace
  the queue. Repeat/shuffle settings and the audio DataSource contract are unchanged.
  Image composition is keyed by track/URL so a previous bitmap cannot be retained
  when skipping to another identity.
- No Room migration, playlist deletion, local-file rewrite, second player,
  developer backend, login/cookies, or signed audio-URL persistence was added.
  GPL-3.0 and existing source/download contracts are preserved.

## Verification

- 11 controlled Python tests passed, including exact-ID artwork recovery, rejection
  of mismatched identities, nested/protocol-relative thumbnails, malicious hosts,
  requested performer covers, ordinary song titles and radio normalization.
- 13 targeted JVM tests passed: ForYouPolicy (2), SearchContract (8), RadioQueuePolicy
  (3). These cover recent/seed exclusion, duplicate recordings, bounded source order,
  validated metadata and artwork hosts, and manual queue priority.
- Release build, release lint and signature results are included with the published
  APK's verification report. No emulator or repeated full regression suite was run.
- Phone screenshots describe v1.5.3 behavior. New artwork rendering, real network
  results, lock-screen art and live transition behavior in 1.5.4 are not claimed
  as device-tested. No fabricated live measurements or screenshots are included.

## Focused phone check

1. Install over the official version without uninstalling. Check existing playlists,
   downloads and device music remain present.
2. Search and play Duka / Last Child. Check search art, mini-player, Now Playing,
   notification and lock-screen cover; playback should continue when artwork arrives.
3. Skip rapidly between different songs and seek. The current title/cover must stay
   with the correct song, and an artwork update must not restart its position.
4. Return Home: no old banner/popular lists; new recommendations and recent listening
   should reflect the chosen song. With auto radio off, recommendations should still
   prepare without adding songs to the queue.
5. Restart the app or lose network: cached Home remains available and playback
   restoration stays paused. Local/offline playback keeps the existing behavior.

Artwork and recommendations still depend on the source's availability. Invalid or
unavailable artwork falls back safely; the new path cannot guarantee every remote
image or zero network buffering on every connection.
