# Yono Music 1.5.5 — sharper artwork, relevant radio and compact UI

Base: v1.5.4 (`b436d53`). Existing Kotlin/Compose, Room 3, Media3,
MediaSessionService, embedded Chaquopy/yt-dlp and GPL licensing are retained.

## Audit and implementation

### Artwork

The old source-thumbnail decoder capped images at 256 px, independent of display
density. A 136 dp card on a 3x screen needs 408 physical pixels. URL-only memory
cache entries could not distinguish a small row image from a large player image.

Artwork now carries optional, bounded source-provided variants (URL + dimensions)
and an optional album browse ID. Old serialized rows still decode with defaults.
Search/radio metadata preserves these variants, and the existing audio extractor
also returns the variants it already received; audio selection is unchanged.

The loader chooses by requested physical pixels, with 128/256/512/1024/1536 buckets,
checks actual decoded dimensions, and tries other supplied variants if the first
is undersized or invalid. It never invents resized CDN URLs or an HD badge. Album
art enrichment uses album thumbnails only when that album contains the exact video
ID. A source with only a small image remains best-available, without fake upscaling.

One repository now serves source rows, Home, mini-player, Now Playing and local
artwork. The legacy background ArtCache API delegates to the same cache. Decoded
memory is bounded to 32 MB; encoded remote images use a 100 MB LRU in the app cache
directory. Memory keys include source identity, artwork version and size bucket;
encoded files use hashed source/version keys and can serve multiple decode sizes.
Only a low-resolution image is requested for the blurred mini-player background.

The cache-clear setting deletes disposable image-cache entries only. Local audio,
SAF files, downloaded `.cover` images, playlists and the database are not deleted.
HTTPS/host/redirect validation, byte limits and bitmap pixel bounds remain enforced.

### Recommendations and radio

The provider request now returns at most 60 normalized song candidates. One shared
recommendation repository serves Home and radio, with a bounded 30-minute memory
cache. A request cycle uses at most three catalogue calls: the current seed, a
refresh when its cached pool is exhausted, and/or up to two previous seeds whose
primary artists occur in the current provider-related pool. No unrelated trending
playlist is used as filler.

The deterministic ranking starts at 60% source order, 30% primary-artist affinity,
and 10% new-to-listener variety. Source order is recommendation order, not a
fabricated chart position or streaming statistic. No genre, mood, BPM or audio
similarity is inferred from titles.

Private listening signals distinguish actual starts, user choices, meaningful
listening, replay and early manual skips. Elapsed playing time counts; pause,
buffering and position seeks do not. Meaningful listening uses the existing
30-second/half-duration threshold. Playback errors suppress skip penalties, and
automatic playlist transitions are not counted as explicit song choices. Profile
signals are limited to 500 events over 30 days in existing AppState JSON.

The engine excludes the seed, the last 20 listened IDs, recent known recordings,
queue duplicates and hidden tracks. It first permits three songs per primary
artist, relaxes to five when necessary, and avoids adjacent matching primary
artists when alternatives exist. Existing upcoming artists count toward diversity
when refilling. Insufficient valid candidates produce a smaller truthful queue.

Radio targets 30 upcoming items and refills below 15. Manual items take priority,
and longer manual playlists are never truncated. Only already-played generated
items beyond the last 20 are pruned; original user playlist entries remain intact.
A new manually selected seed cancels old requests and removes obsolete generated
future items. Session, occurrence and generation checks still reject stale results.

Only the two nearest stream URLs are resolved serially, with the existing Media3
initial audio preload. Thirty prepared metadata entries do not mean thirty open
network audio streams or thirty downloaded tracks. Repeat, shuffle, pause and the
existing opt-in behavior for playlist radio remain respected.

“Kurang cocok” is available in the existing search/player song menu and the Home
card long-press menu. It hides that source ID for 30 days and removes matching
future generated items without deleting a manual queue entry. Preferences can be
reset from Settings. Home and radio use the same ranking results.

### UI

- Three tabs remain Home / Search / Collection, with 16 dp page margins, 12 dp
  common gaps, smaller 10/16/20 dp corners and readable 48 dp touch targets.
- Static Inter Regular/Medium/Semibold is bundled under SIL OFL 1.1, including its
  copyright/license in assets and the in-app notices. It is not Apple's SF Pro.
- Header branding is smaller, ordinary rows have a 64 dp minimum, and Home cards
  are 136 dp. The duplicate Continue Listening Home panel is removed; the shared
  mini-player remains. Recent listening uses four compact rows.
- The navigation bar is 64 dp at standard font scale and expands for large text.
  The mini-player is compact, while Now Playing retains its large cover and clear
  controls without the repeated title, decorative footer or heavy transport panel.
- Light/dark neutral surfaces retain the teal identity. There is no iOS playback
  engine, navigation replacement or change to Android permission behavior.

## Verification and limits

Twenty-two targeted JVM tests passed: artwork selection/cache safety,
ranking/diversity/feedback, manual queue priority and metadata contracts. Thirteen
Python catalogue/artwork fixtures plus three selected stream-identity/format
regressions passed. No repeated full test suite is required.

The API 26 software-emulator fixture completed assertions for actual 512/1024 px
bitmap decoding, a native 31-item queue, seeking, metadata updates without a
position reset, rapid skipping and a late old-track artwork update. Its audio is
a local WAV and its images are generated QA bitmaps, not live YouTube results.
The whole Android test **failed** at `UiAutomation.takeScreenshot()` returning null.
The assertions after that capture (final local-library count and playback surviving
configuration changes) were not reached; they are not reported as passing.

Separate ADB screenshots show the actual Home dark/light and Collection layout
with controlled fixtures. Requested 360/430 dp display overrides did not produce
the intended viewport in this headless emulator: screenshots remained 480 x 800 px.
They are visual evidence for this emulator only, not proof of those target widths.
The 150% font-scale Home capture was also inspected: navigation labels and the
mini-player remained visible; longer content scrolls. Normal release build,
lint vital and APK v2/v3 signature verification passed. The APK uses the same
production signing certificate as 1.5.4.

Live catalogue ranking, Wi-Fi/mobile-data behavior, Bluetooth, background audio and
notification rendering on the owner's phone are not claimed as tested here. Source
availability still limits queue size and image quality. The owner should check a
real search/play session, artwork sharpness, the radio queue and a local download.

There is no Room migration, signed audio-URL persistence, developer-hosted backend,
account/cookie integration or interference with explicit offline downloads.
