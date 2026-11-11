# Stable streaming MVP: music discovery and radio

Base: `main` at `72be330` (v1.5.2). Target: v1.5.3, version code 17.

## Audit and root cause

- Text search in `yono_search.py` used flat general `ytsearch`, which includes fan
  lyric videos, covers and long compilations. Credited artists are often absent,
  so the stable UI correctly fell back to a channel label.
- Home only observed the selected local library. Streaming could work, including
  artwork, while Home still showed local-file onboarding.
- The service already warmed two upcoming source URLs and used Media3's five-second
  memory preload. It lacked recommendation-based queue extension.
- Library and playlist management were separate primary destinations despite sharing
  existing collection/import actions. Room schema version is 3.

## Implementation

- `yono_discovery.py` uses the already bundled `ytmusicapi` for song/artist catalogue
  search, artist songs, chart discovery and watch-radio recommendations. Calls use
  the existing validated public HTTPS transport, cancellation and finite timeouts.
  This is an anonymous third-party client, not a documented official Google API.
  Audio resolution and explicit-link metadata retain the stable yt-dlp path.
- A title-only query preserves catalogue relevance and excludes named alternate
  versions that were not requested. An explicitly named performer is prioritized;
  their cover can be returned. No artist mapping is hard-coded. Catalogue inclusion
  cannot establish who first recorded every song, particularly unmarked covers.
  `artistVerified` is the existing credit-availability flag, not an OAC badge.
- Home adds independently loaded Indonesia/Global popular music and artist shelves.
  Music rows come from source-provided music-video chart playlists, with that
  classification in the source caption. Artist rows are unranked when the source
  gives no ranking. No chart positions, streaming totals or chart dates are invented.
  The displayed timestamp is when metadata was fetched.
- Cache keys are `discovery:v1:<region>:<shelf>` in existing AppState. Freshness is
  six hours; a previously fetched shelf can be shown for up to seven days with a
  refresh error. Cached data contains normalized public metadata only.
- Search supports blended song/artist results plus Song/Artist filters and artist
  song pages. Catalogue artwork is allowed from the exact additional host
  `lh3.googleusercontent.com`. Existing thumbnail/stream artwork paths remain.
  Thumbnail composition is keyed by URL to prevent carrying a previous bitmap
  into a reused card.
- One service-owned, cancellable radio job adds recommendations to the existing
  ExoPlayer. Direct Search/Home playback follows the global default (on).
  Playlists default to off; the queue switch enables radio for that session.
  Local playback is not extended automatically. Playing a verified offline copy
  from online search retains its online radio seed.
- Radio requests wait for stable audio buffering, add at most four upcoming items,
  refill below two, and deduplicate source IDs and normalized title/artist credits.
  User-enqueued items precede generated radio items, including shuffled playback.
  Queue/session/current-occurrence guards reject stale results. Pausing, replacing
  the queue or disabling radio cancels background work. Repeat suppresses extension.
  Errors do not replace the user's queue; the queue screen offers retry.
- Bottom navigation is Home / Search / Collection. Collection reuses favorites,
  local/online playlists, Create/Import/Link and history. Device music opens the
  existing Song/Album/Artist/Folder library. Legacy playlist routes are normalized.
- The new default-radio preference is separate from local Smart Mix and is included
  in collection backup. Old backups remain readable. No Room migration, playback
  engine replacement, audio disk cache, signed-URL persistence or licensing change.

## Validation

- Nine controlled Python discovery tests passed, including explicit performer covers,
  ordinary title words, music/video classification, real-source fallback and URL validation.
- Seventeen targeted JVM tests passed: OnlineSearch (6), SearchContract (8), and
  RadioQueuePolicy (3). No failures or errors.
- A host metadata smoke probe for search and popular artists failed at the runtime
  proxy connection (`ProxyError`). No live chart/search rows or audio performance
  results are claimed. This does not establish source availability on Android.
- Release build/signature verification is recorded with the delivered APK.
- Android/emulator, Wi-Fi/mobile data, live streaming and visual inspection have not
  been executed for this change; use the focused phone checklist below.

Phone checks for the owner:

1. Open Home: Indonesia and Global shelves, source captions, refresh/cache fallback.
2. Search `Bertaut`, then `Bertaut Tami Aulia`; inspect credits and requested versions.
3. Search an artist; open their song list and play/save/download a result.
4. Play a search result; check title/artwork and radio additions in Queue. Skip
   rapidly, pause/resume, add Play Next, enable shuffle, and toggle radio off/on.
5. Play a saved online playlist: radio starts off and can be enabled explicitly.
6. Open Collection, existing playlists, device albums/artists/folders and Import.
7. Check offline/local playback, an existing download, background audio, notification
   artwork, network loss and recovery. These use the existing playback transport.

## Limits

Anonymous catalogue and chart access can change or be unavailable by region.
Unavailable shelves show a truthful empty/error state; text search never silently
falls back to unrestricted general-video results. There is no guarantee of zero
buffering: the existing next-two URL warm-up and Media3 preload still depend on
network and source availability. Radio is not automatically restarted by a paused
queue restored after process death; it can be enabled from Queue when resumed.
