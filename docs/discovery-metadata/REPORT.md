# Discovery and synchronized metadata — Preview 4

Implementation follows the [pre-implementation source audit, exact file plan and source availability assessment](../DISCOVERY_METADATA_AUDIT.md). The preceding [streaming performance report](../performance/REPORT.md) remains applicable to Preview 3's controlled measurements. This change keeps Kotlin/Compose, Media3 1.11.1, the existing session service, Chaquopy/yt-dlp, Room schema 5, DataStore and WorkManager. GPL-3.0 is unchanged. No backend, second player, login, cookies or database migration was added.

## Root cause and implementation

Full yt-dlp extraction already returned title, artists, album, duration and artwork. The old Kotlin resolver discarded most of it, while the session/queue retained search metadata in `yono.remote`. Home enrichment affected only a private Compose page. Separate UI image fetching never supplied reliable local artwork bytes to the MediaSession. Key changes in `produceState` could briefly retain a previous bitmap.

Further source review during regression diagnosis found two additional traps. `MediaMetadata.equals` explicitly ignores extras, so the coordinator compares the serialized normalized record as well. Media3's default bitmap loader uses a shared single-thread executor; publishing a remote image URI can start an independent request and queue later images behind it. The session now receives **only validated local artwork URIs and image bytes**, never the remote search thumbnail URL. UI thumbnails still load independently through the shared resolver. [Pinned loader source](https://github.com/androidx/media/blob/1.11.1/libraries/datasource/src/main/java/androidx/media3/datasource/DataSourceBitmapLoader.java).

`OnlineSong` is now the normalized metadata record, expanded with artist arrays, album artist, uploader/channel ID, release year, genre, official status, content kind, provenance, update time and local artwork URI. Stable `id`/`videoId` identify a track; resolved audio URLs are never metadata identity or persistence fields. Optional missing values remain absent.

The application `MetadataRepository` owns a bounded 500-record StateFlow. Initial metadata is registered before playback; full extraction publishes enrichment after successful audio resolution. Canonical entries only share updates with an exact selected video identity. Existing AppState holds sanitized ordinary metadata; local cache file URIs and image bytes are not included in portable persisted records. Optional enrichment/storage failures do not convert valid audio resolution into playback failure.

Registration reads and merges an existing AppState record inside a Room transaction before writing. All enrichment persistence uses that same path, preventing a later lightweight registration or delayed write from erasing complete saved credits after a process restart. Older canonical MusicBrainz references retain catalogue provenance even without the newly introduced JSON fields. Controller UI snapshots also read through the shared repository, including during optimistic MediaController admission. No schema change is needed.

Merge policy: exact catalogue credits/album data > full extraction > chart > search, with timestamps breaking equal-source ties. Nonempty supplied fields fill gaps; missing values do not erase data. A generic uploader cannot replace credited artists. Artwork has separate catalogue > thumbnail > exact chart provenance. A new artwork version invalidates the prior local image. `artistVerified` denotes supplied artist credits, not ownership authorization.

One `PlaybackMetadataCoordinator` observes the repository. It updates queue MediaItems using the identical local configuration and preserved queue extras. It does not prepare, seek, reset the player or alter repeat/shuffle/order. Main-thread generation, current media ID, occurrence queue key and artwork-version checks prevent late results from another track applying to the current item. Media3 metadata supplies title/displayTitle/artist/album/album artist/duration/year/genre and cached artwork URI/data. `PlaybackConnection` continues deriving UI Tracks from the session's remote metadata; search/Home/actions observe the shared metadata map.

The native [ProgressiveMediaSource 1.11.1 implementation](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/ProgressiveMediaSource.java) was inspected: an unchanged URI, image duration and custom cache key allow metadata updates without recreating the source. Tests verify the actual service/controller behavior rather than relying on this documentation alone.

## Artwork

`ArtworkRepository` uses independently scheduled foreground and shelf workers (at most one each), existing HTTPS host/redirect restrictions, JPEG/PNG/WebP MIME validation, a 4 MiB response limit, dimension limits, decoding samples and a maximum 512 px output. No guessed HD URL is generated: thumbnails are selected from source-supplied candidates near 512 px. The downloaded result is resized and stored as JPEG, at most 512,000 bytes.

The separate **100,000,000-byte** disk cache uses SHA-256(identity + source artwork URL/version + output-size policy), AtomicFile writes and LRU eviction. Hits refresh access time. Explicit offline downloads and the audio cache remain separate. Settings includes a disk artwork-cache clear action. Current session images can remain in bounded memory until the session/UI releases them. UI image results are tagged with request identity before rendering, so a bitmap from a previous key is not displayed while a new request runs.

## Four upcoming tracks

Following the later request to prepare 2–4 songs, the service now resolves **up to four available successors**, one at a time. Navigation follows the actual shuffle/repeat timeline, avoids cycles/current-track duplication and deduplicates source IDs. It starts after foreground loading settles and observes urgent playback, queue changes, pause and source promotion. Canonical matching uses the same bounded background resolver. Successful extraction also enriches those queue items through the shared metadata state.

Valid URLs reuse the existing eight-entry memory cache and expiry-aware maximum 120-second TTL. Signed audio URLs remain outside persistent storage. Media3 continues preloading approximately two seconds of **the nearest successor's audio** under its native loading gate. Preparation of four sources and byte preloading are distinct operations; this does not promise zero buffering on an interrupted or insufficient network.

Owned-PCM service fixture: **4 sources prepared, maximum 1 concurrent resolver, 73,644 next-track bytes read before transition, current audio kept advancing**. Next-track advancement was observed within **3,150 ms** in the software emulator. This fixture has no paired baseline and is not live YouTube or a claim of instantaneous device transitions. [Evidence](validation/upcoming-window.json).

## Chart functionality and honest availability

- Daily videos, daily Shorts songs, trending, weekly videos/songs/artists/Shorts, Spotify Top 50/200/genre/city/Local Pulse/viral, Official SEA national/domestic, new releases and genre discovery are explicit selections.
- YouTube country options include Indonesia, Global and the published supported countries. Daily/weekly video playlists require an exact source category title match. The first unrelated returned playlist is never relabelled as another chart. Numeric ranks and source update dates are not inferred from list indexes or fetch time.
- Weekly songs remain aggregate song performance. Artist charts remain catalogue performance, including variants. YouTube Music Explore trending is labelled **Explore, source region**, not a measured regional daily chart. Unauthenticated artist results can be unranked.
- Spotify and Official SEA expose official external chart pages and an explicit unavailable-data state. No authorized anonymous data interface was configured or established. National SEA selections cover Indonesia, its domestic chart, Malaysia, Singapore, Philippines, Thailand and Vietnam. Missing label/distributor, peak, movement, previous position, date or stream totals are never invented.
- Home exposes nine discovery entry points and one selected shelf at a time. Source attribution, refresh, region/category/official filters, source date when supplied, and separately labelled fetch/cache time are shown. Requests use a separate low-priority worker and yield to urgent audio; categories are not all fetched at launch. Existing CatalogCache stores source-specific TTLs: trending 15 minutes, daily 6 hours, weekly 12 hours, releases 1 hour, genres 24 hours. UI reveals 20 entries per page from bounded source responses of at most 100 entries.
- Search and Home official filters require source evidence. Explicit platform OMV/ATV music types or an explicitly identified official music-video chart can establish platform evidence. Title keywords, artist credits and a generic verified badge cannot establish official status. Live/remix/cover/karaoke/altered variants are classified separately and excluded from the requested official studio categories. Unverified albums/playlists are not passed through an official-only filter. An OAC/record-label verification registry was not fabricated; unavailable identity proof remains unverified.

## Files changed

- Normalization/ownership: `search/SearchModels.kt`, new `metadata/MusicMetadata.kt`, `MetadataRepository.kt`, `YonzApp.kt`.
- Extractor bridge and persistence: `playback/StreamRepository.kt`, `search/DiscoveryRepository.kt`, `yono_stream.py`, `yono_search.py`, `yono_catalog.py`, new shared `yono_metadata.py`.
- Session/UI synchronization: `playback/RemoteMedia.kt`, new `PlaybackMetadataCoordinator.kt`, `PlaybackService.kt`, `PlaybackConnection.kt`, `ui/YonzRoot.kt`, `StreamingScreens.kt`, `Components.kt`, `SourceThumbnail.kt`.
- Artwork network/cache: new `metadata/ArtworkRepository.kt`, `network/SafeHttp.kt` (optional image-only MIME validation; other request behavior retained), `MusicViewModel.kt` (clear action).
- Charts: new `search/MusicCharts.kt`, `ui/ChartDiscovery.kt`, `yono_charts.py`, `ui/DiscoveryScreens.kt`; the legacy chart entry point delegates to the typed provider.
- Upcoming window: new `playback/UpcomingTracks.kt`, `UpcomingTracksTest.kt`, `UpcomingPreparationTest.kt`; the existing service scheduler and sanitized `StreamPerformance.kt` now record completed preparation counts.
- Validation/release: `UnifiedMetadataTest.kt`, `MetadataSyncTest.kt`, `ChartDataTest.kt`, `test_charts_metadata.py`, `app/build.gradle.kts`, private `tools/testing/upgrade/UpgradeProbe.java`, this report/audit and attached evidence. `PlaybackConnection.kt` keeps session event mirroring and normalizes its snapshots through the repository without separate title/artist/image stores. The shared Python normalization helper and chart fixture test are additions to the original planned file list, avoiding duplicate normalization and validating source contracts.

## Validation status

- Targeted JVM: **25 passed** (8 metadata/chart/legacy contract, 5 collection contract, 3 existing stream policy, 3 upcoming-window, 2 foreground-priority, 4 resolution-cache cases). [Results](validation/jvm.json).
- Targeted Python: **27 passed** (8 chart/metadata, 10 stream, 5 streaming-v2, 4 Preview 2 discovery/real Opus tagging tests). [Output](validation/python.txt).
- Real host public chart probes: **failed/unavailable** for ID and Global; 5,242 ms and 52 ms respectively, zero rows, no update date. These are host diagnostics, not Android chart successes. [Evidence](validation/public-chart-probe.json).
- Android core regression: **9 passed**: metadata/session/artwork 3, chart/cache/fallback 1, local/background 1, existing discovery/Room/Opus 3, four-track preparation 1. Final persistence correction has a separate focused result below; these nine cases were not rerun after the user's request to minimize repeated testing.
- Native session measurement before that persistence-only correction: **900 ms** from publishing full metadata to controller/UI agreement; **0 current audio-source reopens**, paused position/source/order/repeat/shuffle preserved, late A artwork rejected after selecting B. The notification existed, its icon was populated and the platform session exposed the correct title and bitmap. This is one controlled observation, not a latency percentile or a physical Bluetooth/lock-screen test. [Evidence](validation/metadata-sync.json).
- Signed release uses the owner's certificate, package `com.yonzmusic.app`, version code **20**, version `2.0.0-preview.4`, non-debuggable. [APK identity](validation/apk-identity.json). Packaged Python bytecode/constants matched the corresponding source modules. [Evidence](validation/packaged-python.json).

## Limits

No successful live YouTube chart ranking or live streaming improvement is claimed for this environment. Spotify/SEA ingestion remains unavailable and links to official sources; this is an intentional source-availability fallback, not fake chart data. Unknown source chart dates remain visibly unknown. Catalogue release navigation does not prove channel authorization.

Artwork and chart cancellation protect publication and use finite existing HTTP timeouts. A blocking system socket/DNS call may finish before an interrupted worker is released; this is not a claim of instantaneous transport abort. Physical Bluetooth devices, carrier networks and hardware lock-screen rendering are unavailable here and require device testing. Session assertions are reported separately from those physical-device checks.

## Mandatory scenario coverage

| Scenario | Method and evidence |
| --- | --- |
| Indonesia/Global chart loading | Owned source fixtures verify both requested regions, supplied ranks/dates and cache. Public host attempts failed; live success is not claimed. |
| Ranking, attribution, update date | Fixture rank 3/peak 2/last week 5 retained exactly; missing ranks/dates remain absent. Source and fetch date are separate. |
| Official filter/no fake results/unavailable source | Kotlin/Python policy cases and Android chart fixture; external Spotify/SEA fallbacks contain zero fabricated entries. |
| Search/first-play artwork, immediate title/artist | Shared metadata binding and independently fetched owned images; native service/controller checks. Search result rendering uses the same resolver and identity-tagged state. |
| Asynchronous artwork and rapid skipping | Delayed A artwork followed by B; native B artwork color checked after obsolete A could finish. |
| Notification/lock screen/Bluetooth metadata | Platform-session/notification validation is recorded separately below. No physical Bluetooth or hardware lock-screen rendering is claimed. |
| Next-track metadata | Real Media3 transition to B checks current ID/title and image identity. |
| Missing/invalid artwork | Empty/invalid paths use default artwork; unsafe URL rejected before a request, HTML MIME rejected, image decode bounded. |
| Position/state/order/repeat/shuffle preservation | Paused seek position, source configuration, occurrence keys and playback controls asserted across metadata changes. Counts refer to actual opens of the current source, not construction of speculative next-track DataSources. |
| Local/background playback | Existing content-URI seek/background regression passed. |
| Offline downloads | Real Opus remux/tag/seek/decode regression passed; live internet download is not claimed. Explicit offline file storage remains separate. |
| Playlists/database/user data | Five collection-contract JVM cases, Room 4→5 regression; schema remains 5. Owner-signed final code-20 APK matches the retained original code-19 collection snapshots; schema remains 5. |

Intermediate failures are retained for review: the first session fixture timed out at the system-artwork check; a later fixture counted DataSource construction (including speculative work) as audio reopening. The final fixture counts actual current-track `open` calls and uses local-only session artwork. These failures are not counted as passing results.

## UI evidence

Production APK screenshots at 320 dp, with actual unavailable-source states rather than injected chart ranks:

| Screen | Evidence |
| --- | --- |
| Home/source state | [Light](screenshots/home-source-state-light.png) |
| Region selection | [Light](screenshots/region-selector-light.png) |
| Search and official filters | [Light](screenshots/search-official-filters-light.png) |
| Official Indonesia source fallback | [Light](screenshots/official-sea-unavailable-light.png), [dark](screenshots/official-sea-unavailable-dark.png) |

The emulator briefly displayed a **System UI** ANR dialog, recovered with Wait. It was not a target-app fatal exception. The failed capture is retained under validation; the five final screenshots were captured afterward. UI smoke was not repeated for the final persistence-only correction, which does not change these layouts. [UI result](validation/ui-smoke.json).

## Final focused check and release

The final storage regression **passed (1 Android case)**: a full metadata record was seeded in Room with no in-memory entry, then a lightweight search registration preserved its title, credited artist and album. [Result](validation/StoredMetadataRegression.txt). The previously passing nine Android cases were not repeated, following the user’s request to minimize redundant testing. The controlled total is 10 distinct Android cases across the implementation stages; performance figures above were not remeasured after this final storage-only correction.

The final non-debuggable owner APK was installed in place. Tracks, favorites, history, playlists, playlist entries and online activity matched the original version-19 complete-row SHA-256 snapshots; Room stayed at version 5. Verification retained the original snapshot through an intermediate code-20 candidate and the final same-version reinstall; no downgrade, uninstall or data clear was used. [Before](validation/upgrade-before.txt), [final after](validation/upgrade-after.txt), [final verification](validation/final-verification.json).

No additional broad runtime, timing or UI rerun was performed after the final persistence correction. Live YouTube playback, physical Wi-Fi/mobile data, lock-screen presentation and Bluetooth operation are reserved for the user’s Android device.
