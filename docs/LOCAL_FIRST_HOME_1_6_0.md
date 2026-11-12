# Yon'z Music 1.6.0 — local-first Home

## Audit and decisions

Base: 1.5.5, commit `4841b89`. The app already uses Kotlin/Compose, Room schema 3,
DataStore, Media3/MediaSessionService and embedded Chaquopy/yt-dlp. These remain.

- `LibraryScreens.HomeScreen` combined local SmartMix/album/history sections with
  `DiscoveryScreens.MusicForYou`, whose online history and metadata lived separately.
- `ForYouRepository` retained source metadata, a recent seed and 500 listening
  signals over 30 days. `RecommendationRepository` provided bounded related-song
  metadata. Neither required another player or developer backend.
- Room already had Favorites, History, ListenEvent and AppState. ListenEvent counts
  a meaningful listen at its qualification threshold; its `listenedMs` is **not a
  completed session duration**. Treating old values as completion would be false.
- `PlaybackConnection` owns one controller, and `PlaybackService` one player/session.
  Its stable source identities, URL resolver, HTTPS validation, audio retries,
  preloading and download engine can be reused without audio-engine changes.
- The existing artwork repository already supplies density-aware cached images.
- OnlineSong lacked genre/tag/date/type fields. Room Track has no genre field.
  No schema migration is needed: optional source metadata and disposable embedded
  genre hints fit existing AppState JSON.
- Search spent two rows on source/type filters; Collection used a large Favorite
  card plus separate device/history rows. Their actions can fit one compact row.

## Exact implementation files

New production files:

- `home/HomePolicy.kt`: normalized view-only HomeTrack, HomeStat, HomeMix, HomeState;
  deterministic ranking, sections, daily composition, identity and diversity.
- `home/HomeRepository.kt`: observable Room/source/profile composition off the main
  thread; source metadata cache, explicit refresh, radio candidate preparation.
- `home/HomeLocalTags.kt`: serial, cached reads of real embedded genre metadata.
- `ui/PremiumHomeScreen.kt`: header, grids, carousels, playlist sheet, real actions,
  local profile summary and shared streamed/local Favorites screen.

Updated production files:

- `MusicViewModel.kt`: Home state/actions, mixed queue registration, cancellable radio
  requests and favorite identity handling.
- `data/Database.kt`: two read-only Flow queries; entities, schema version and
  migrations are unchanged.
- `search/ForYouRepository.kt`, `RecommendationEngine.kt`: observable profile,
  bounded per-occurrence audible progress snapshots; existing scoring retained.
- `search/SearchModels.kt`, `playback/StreamRepository.kt`, `yono_discovery.py`:
  optional supplied genres/tags/release dates/music types; backward compatible JSON.
- `playback/PlaybackConnection.kt`, `RadioQueue.kt`, `AutoRadio.kt`: mixed local/online
  Home queues, explicit song/artist radio context, shuffle and bulk enqueue.
- `playback/PlaybackService.kt`: the existing listening observer also handles local
  tracks and flushes progress at service shutdown. Player/source/load-control
  configuration is unchanged.
- `ui/LibraryScreens.kt`, `SearchScreen.kt`, `YonzRoot.kt`: route the actual Home,
  compact the filter/collection rows, reuse Favorites and native navigation.
- `app/build.gradle.kts`: version 1.6.0 / code 20.

Tests: `HomePolicyTest.kt`, `HomeInteractionTest.kt`, the updated empty-Home text in
`EmptyHomeTest.kt`, a generated content-URI WAV in the test-only
`QaFixtureProvider.java`, and optional-metadata fixtures in `test_discovery.py`.

## Working behavior

Home is a lazy vertical feed with stable keys. Artwork carousels are lazy, and
six Quick Picks use a small two-column grid. The header shows the device's actual
local date/greeting, a search action and a local profile avatar. Its profile sheet
shows real counts and resets recommendation preferences; it is not an account.
The red accent is scoped to Home; the rest of the application keeps its theme.
Inter, light/dark support, reduced-motion behavior and native navigation remain.

The feed includes eligible Quick Picks, Made For You, Daily Mix, Discover Mix,
Artist Radio, Song Radio, On Repeat, Recently Played, Favorites, Local Top Tracks,
album selections and metadata-backed mood/genre/release sections. Empty sections
are omitted, and a new user gets working Search/Import actions. Empty Favorites
also has an explanation. Source failures keep cached selections visible.

Ranking uses meaningful counts, elapsed listening, completion where known, recent
activity, favorites, early skips, artist affinity, supplied genre affinity and
source-related candidates. A tap alone never increments play counts or favorites.
On Repeat and local top counts cover 30 days, not global popularity. Recent items
use actual playback/history timestamps, not the order of a cached list.

Progress reports use elapsed `isPlaying` time, never seek position. Paused and
buffering time do not count. Each occurrence has a private session identifier;
progress overwrites that occurrence's older sample, at most every 15 seconds plus
pause/transition/failure/shutdown boundaries. Events remain limited to 500 over
30 days. Old records have unknown completion; no historical completion is invented.
Local Room qualification records and online qualification signals are not counted
twice for the same locally playable source.

Daily mixes start with 70% familiar / 20% related / 10% exploration. Only eligible
pools are used; shortages are not filled with unrelated songs or fabricated genre
labels. The subtitle reports actual counts. Ordering rotates deterministically by
day and responds to activity. Artist/genre groups produce extra mixes only when
there is enough real material. The existing smart-mix setting controls Daily Mix.
Discover excludes known played/started/favorite songs within available local
records; it is not a claim to know a user's listening outside this app.

Artist radio prioritizes known tracks by the chosen primary artist and permits up
to eight per artist, alongside source-related music. Song radio begins with one
song and uses a tighter artist cap. Both create the existing Media3 queue and
explicit radio context, so its existing refill/autoplay/preload behavior continues.
The initial user-selected radio seed remains manual; subsequent prepared radio
items carry the existing generated-radio flag. Automatic radio transitions therefore
do not become explicit choices or silently replace the artist/song seed. A matching
local download retains its local audio URI plus the same source radio context.
Queue changes during preparation discard stale results. Explicit manual selections
cancel pending Home radio work. Offline radio uses eligible cached/local material;
continuous streaming still requires an accessible source and a network.

Mix sheets support Play, Shuffle, Add All to Queue, individual song selection and
song menus. Artist Radio's Play/Shuffle generates the artist radio. Song menus
support the existing Favorites table, queue, song/artist radio and hidden feedback.
Streamed favorites are visible from Collection as well. Local favorites whose
storage is unavailable remain visible with their original recovery/metadata menu;
they are not deleted or described as an empty Favorites library. Local downloads retain
local playback identity and can satisfy a matching online source without streaming.

## Source availability and honest limits

| Feature | Available evidence / behavior |
| --- | --- |
| Related music | Existing anonymous YouTube Music catalogue recommendations; metadata only. Cached locally. |
| Official preference | Available music-video type/catalogue credits are ranking evidence. They are not proof of OAC verification. Unknown sources get no invented official badge. Alternate-title evidence is a penalty in Home, not a blanket deletion. |
| Genre | Supplied source genres or real embedded local genre tags. Read up to 16 uncached local files per Home visit, serially off the main thread; failures are cached. |
| Mood | Explicit supplied tags such as chill/study/workout. Titles and genres do not imply moods. Many catalogues do not supply these tags, so that section may be absent. |
| New Releases | Only a valid supplied ISO release date within 90 days. No upload-date substitution, guessed year or hardcoded albums. The anonymous source does not reliably expose dates, so this section may be absent. No undocumented API was added. |
| Album interaction | Opens the actual known songs in that album; it does not pretend an incomplete local album is a complete remote discography. Album-open frequency was not previously recorded; selections use actual listening/favorite evidence. |
| Listening profile | Local records only, bounded to 500 signals/30 days; old sessions have no full-duration completion. |

At most 1,500 normalized source rows and 5,000 recent local listen rows are used in
the Home snapshot. Heavy merging/ranking runs on Dispatchers.Default. Recomposition
never runs searches or audio extraction. Source discovery caches for six hours,
with a fifteen-minute automatic failure/retry guard; explicit refresh is available.
A local-only profile with insufficient discovery can use one existing artist song
search, requiring an exact primary-artist match before requesting related metadata.
It never replaces that local audio with a remote guess. This fallback is not claimed
as live-network verified. Current playback buffering delays background Home requests. Existing recommendation
pools retain their own 30-minute cache and bounded request count. A new manual seed
still has priority. Artwork uses the existing 32 MB RAM / 100 MB disk LRU.

No backend, AI service, login/cookies, second player, Room migration, persistent
signed audio URL, global chart fabrication, audio-download change or GPL removal.

## Verification

Thirty targeted JVM tests and fourteen catalogue/Python fixtures passed. Final
release build, lint vital and production APK signature are checked before upload.
The pure tests include actual daily rotation, 70/20/10 selection, source/download
play-count merging, hidden recommendations and unknown metadata handling.

The focused native Home test passed (one case, 184.725 seconds): actual UI Mix Play,
Shuffle, queue append, Next, short background playback, navigation and retention of
nine local fixture tracks/three favorites. It uses a generated WAV through a
test-only content provider. Two earlier harness attempts failed to locate the
visible Compose header with text search; walking the accessibility tree fixed the
helper. No further full native-suite repetition is required.

Five actual 480 x 800 px screenshots were inspected: Home dark/light, the Home
header, Search and Collection. They use controlled QA artwork, not live releases.
The new Home was checked at a 320 dp viewport; wider and large-text views still
need phone checks. Native assertions cover local paths; final ranking/rotation
changes are checked with JVM fixtures. Online source-radio/download context was
not device exercised.

Exact final JVM/Python counts, build/signature status and screenshots are recorded
with the release in `verification.json`. Controlled fixtures must not be described as live
YouTube or mobile-data results. The owner's phone should check real search/play,
radio relevance, source artwork, network recovery, Bluetooth/notification behavior
and long background sessions. These are not established by local fixtures.
