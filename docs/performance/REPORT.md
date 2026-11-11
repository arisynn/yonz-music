# Streaming performance — Preview 3

## Scope and audit

Audited Preview 2 (`3618d6946acb096f16d78c96d8c17ed7c3e881e8`) before modifying application code. Commit `7c62126` adds measurement to the original algorithms; the optimized build is version 2.0.0-preview.3 / code 19. See [source audit and cache adapter design](SOURCE_AUDIT.md). Kotlin, Compose, Media3 1.11.1, MediaSessionService, Chaquopy, yt-dlp and GPL-3.0 remain. No backend, account, cookies or access bypass was added.

## Root causes confirmed in source

1. Opening and seeking remote media materialized the complete local track table, including on the loader thread. With 5,000 fixture records this cost 1.5–7.6 seconds in the software emulator. A targeted query avoids decoding unrelated records. Genuine local candidates still require complete SHA-256 verification.
2. The cache adapter resolved a source to select an immutable cache identity, then resolved it again in the upstream source. Existing in-memory caching usually concealed this duplication, but non-cacheable/near-expiry results could extract twice.
3. Background resolution did not observe foreground loading and skipped all canonical catalogue references. Native Media3 byte preloading already had a loading gate; the explicit resolver needed its own priority/cancellation policy.
4. The transport explicitly disconnected even completed bodies, and closed partial bodies before aborting. This prevented relying on the platform's normal completed-body connection reuse and could drain unwanted data during cancellation.
5. Initial/rebuffer thresholds were shared with local playback. The original first-audio metric measured `isPlaying`, not audio position advancement. Controller binding also depended on a display-frame callback, including for background clients.

Python runtime reuse, an eight-entry resolution LRU/single-flight cache, expiry-aware maximum 120-second TTL, SimpleCache, stable format/revision keys and bounded transport retries **already existed**. They are retained, not presented as newly implemented features. EmbeddedEngine/yono_engine.py are download/inspection paths, not the progressive playback resolver.

## Optimizations and exact files

| Files | Change and reason |
| --- | --- |
| `data/Database.kt`, `MusicViewModel.kt`, `playback/CatalogDataSource.kt`, `search/DiscoveryRepository.kt` | Use `streamingCopy(identity)` instead of `allTracks()`, consult cached catalogue mapping without eager extraction, defer unknown canonical matching to the loader. Preserve verified local IDs, file hash checks and offline behavior. No schema migration. |
| `playback/StreamAudioCache.kt` | Seed the first upstream open with the exact resolution already used to pin the cache key. Refresh still validates format/revision; a new open still checks identity. Keep opaque URIs and stable source/format/container/codec/revision keys. |
| `playback/StreamDataSource.kt` | Close a completed known-length body without explicit disconnect; abort incomplete/error/cancelled bodies before closing. Preserve public-address/HTTPS/redirect checks, correct ranges/206, bounded discard, finite timeouts, retry budgets, one 401/403 refresh and format/length pinning. Volatile cancellation-visible transport handles. |
| `playback/StreamRepository.kt`, `playback/StreamWork.kt`, `search/DiscoveryRepository.kt`, `yono_stream.py`, `yono_discovery.py`, `yono_core/network.py` | Dynamically distinguish foreground and speculative work. Background extraction yields to urgent/current loading, uses an 8-second extraction budget and 2-second network operation timeout; foreground retains existing limits. Promote a now-current source without cancelling it. Existing Python runtime and memory TTL reuse remain. |
| `playback/PlaybackService.kt` | Prepare one actual successor, respecting shuffle/repeat. Debounce explicit resolver 400 ms; only run after current loading settles. Include already-mapped canonical tracks. Retain native loading gate; preload target reduced from 5 to 2 seconds. Cancel obsolete queue work. Isolated test transports cannot accidentally start a real resolver. |
| `playback/StreamingLoadControls.kt` | Separate native streaming/local profiles. Selected streaming 5,000/30,000/750/2,000 ms after the controlled experiment below. Local stays 5,000/30,000/1,000/2,500 ms; both retain the old `prioritizeTimeOverSizeThresholds=false` behavior. |
| `playback/PlaybackConnection.kt` | Main-looper posting replaces a display-frame callback for controller binding; pending connection work is removed on close. |
| `data/Preferences.kt`, `data/CollectionBackup.kt`, `ui/StreamingScreens.kt` | Add an experimental **100,000,000-byte** LRU option and backup validation. Existing off/32 MiB/64 MiB stored meanings and 64 MiB default remain. Existing clear control remains. Explicit offline downloads use separate storage. |
| `playback/StreamPerformance.kt`, `StreamPlayerMetrics.kt`, `engine/PythonRuntime.kt`, resolver/data-source/service/controller/ViewModel hooks | Bounded session diagnostics: tap→resolver, cold Python/import, extraction (also failed calls), catalogue/local lookup, DNS, response connection/headers, first byte, READY, actual advancing audio clock, seeks, rebuffer count/duration, bytes/throughput and cache hit/miss ratio. |
| `app/build.gradle.kts`, `tools/testing/upgrade/UpgradeProbe.java` | Preview 3 identity and private same-signed upgrade verification modes. The instrumentation APK is never distributed. |

Instrumentation includes the baseline commit, so not every file above appears in the final optimization-only diff. New/extended tests: `StreamPerformanceBenchmarkTest`, `StreamPerformanceContractsTest`, `StreamBufferExperimentTest`, `StreamCacheCapacityTest`, `StreamLocalRegressionTest`, `StreamPriorityTest`; existing regression classes are listed below.

## Cache contract

The pre-implementation design is in the audit. SimpleCache/CacheDataSource was already compatible with the custom DataSource: resolve → pin immutable identity → feed that exact result to first upstream → validate any refresh. `getUri()` stays opaque, so signed audio URLs are neither cache keys nor persisted redirect metadata. Cache identity changes fail closed instead of mixing bytes. Cache clearing/disabled mode and revision changes have regression coverage. LRU applies to committed spans; a temporary in-progress fragment can transiently exceed capacity.

## Measurement definitions and privacy

`YonoStreamPerf` emits schema, opaque session trace number, enum event and numeric measurements only. It never logs source IDs, song titles, signed URLs, request headers, tokens or cookies. Memory is bounded to 512 events and 32 trace identities. Persistent source references retain only ordinary catalogue/search metadata, never resolved audio URLs.

`http_connect` measures requesting the HTTP response code (including connection/TLS/server headers), not isolated TCP or TLS time. `http_headers` overlaps it and must not be added to it. DNS is measured separately where called. `first_byte` starts at header availability; `open_first_byte` starts at source open. `transfer` reports bytes from first to last body-read time: effective observed throughput, including scheduling/read pacing, not a physical link speed. `cache.bytes / cache.count` is hit bytes / total delivered bytes; logs also include explicit ratio and miss bytes. Counters are emitted when an open closes, so a snapshot can omit an active final source.

Audio advancement uses Media3's audio-position callback with a positive-clock fallback, never `isPlaying`. READY is required before accepting advancement. Baseline mobile backward-seek emitted an old advancement callback at 362 ms before READY at 3,504 ms; that raw record is retained but excluded. Seek comparisons use READY.

## Controlled baseline versus optimized

Environment: API 26 x86_64 emulator, software CPU/GPU, no KVM. Same owned 20-second 16 kHz mono PCM, 5,000 synthetic local-index rows, real service/ExoPlayer/cache/decoder, injected resolver and HTTP connection fixtures. Fast-shaped: 256,000 B/s and 30 ms response delay. Mobile-shaped: 48,000 B/s and 100 ms delay. These names describe fixtures, **not real Wi-Fi/mobile networks**.

One sequence per profile/build; these are observations, not percentiles or statistical claims. Cold Python startup is measured separately before media taps. The benchmark enters through PlaybackConnection after service connection, not a complete cold UI launch→YouTube journey. The optimized run precedes the final screen-off/DI cleanup, which was separately regression-tested.

| Measurement (ms) | Fast baseline | Fast optimized | Mobile-shaped baseline | Mobile-shaped optimized |
| --- | ---: | ---: | ---: | ---: |
| First READY | 10,487 | 5,225 | 6,170 | 3,300 |
| First audio advancement | 10,777 | 5,366 | 6,265 | 3,785 |
| Seek forward READY | 3,362 | 1,721 | 3,569 | 1,909 |
| Seek backward READY | 3,090 | 1,569 | 3,504 | 816 |
| Next track READY | 1,247 | 955 | 2,338 | 879 |
| Next track audio advancement | 1,417 | 1,047 | 2,576 | 994 |
| Subsequent READY | 3,492 | 2,066 | 3,326 | 1,838 |
| Subsequent audio advancement | 4,152 | 2,138 | 3,908 | 2,156 |

Fast first audio improved 50.2%; mobile-shaped 39.6% in these particular runs. They do not establish real-device network gains. Local lookup fell from 1,568–7,609 ms to 26–279 ms across fixture operations. Both profiles still made five HTTP opens and two fresh fixture extractions: do not infer fewer TCP reconnects or universally halved extraction from the cache adapter optimization. Next-track bytes before transition: fast 18,092→22,444, mobile-shaped 0→12,844.

The isolated cache adapter contract with a deliberately uncacheable 40 ms resolver changed first-open calls **2→1**, first-open 334→226 ms, completed-body explicit disconnect calls **1→0**; second-open identity checks remain **1→1**. These are method-call counts, not observed live socket reuse. Exact fixture bytes matched.

Cold Python first-module total: 4,438→4,327 ms; warm module: 6→2 ms. Runtime reuse was already implemented. This small difference is run variance, not an optimization claim.

Raw evidence: [baseline](baseline/performance-baseline.json), [optimized](optimized/performance-optimized.json), [adapter baseline](baseline/performance-contract-baseline.json), [adapter optimized](optimized/performance-contract-optimized.json), [Python startup](baseline/python-startup.json).

[Closed-open accounting](accounting.json): fast fixture hit ratio 50.55%→48.85%, observed body throughput 97,424→93,326 B/s; mobile-shaped 50.11%→10.52%, throughput 43,290→41,864 B/s. Read volumes/closure timing differ; the optimized mobile snapshot omits the final active cache read. These are instrumentation accounting checks, **not cache-hit or bandwidth improvements**. No throughput gain is claimed.

## Buffer threshold experiment

Owned 16-second PCM, 48,000 B/s, native ExoPlayer. Sequential single runs, not repeated randomized trials:

| Profile | READY ms | Audio ms | Rebuffers | Rebuffer ms |
| --- | ---: | ---: | ---: | ---: |
| 1,000/2,500, uninterrupted | 1,864 | 1,985 | 0 | 0 |
| 750/2,000, uninterrupted | 923 | 978 | 0 | 0 |
| 1,000/2,500, 3,500 ms injected stall | 1,071 | 1,181 | 1 | 3,514 |
| 750/2,000, same stall | 852 | 964 | 1 | 3,300 |

The first uninterrupted run has cold/JIT/order bias; its full improvement cannot be assigned to thresholds. In the stalled pair READY improved 219 ms and recovery 214 ms, with the same rebuffer count. This supports selecting 750/2,000 for the prerelease, not a universal radio-network claim. Min/max remain 5–30 seconds; local behavior is unchanged. [Raw experiment](validation/buffer-experiment.json).

## Validation and required scenario matrix

15 controlled Android regression cases passed, plus two benchmark/adapter cases in each baseline/optimized build. Targeted JVM and Python results are attached separately. No broad unrelated test expansion.

| Required scenario | Evidence/status |
| --- | --- |
| First playback after launch | Controlled first media tap and real cold Python timing measured separately; combined cold-launch public playback remains unverified. |
| Subsequent playback | Both profiles passed; cache and runtime reuse measured. |
| Next-track transition | Both profiles plus `StreamPreloadTest`; final isolated-preload rerun passed. |
| Forward/backward seeking | Both profiles; range/identity/cache/format regressions passed. |
| Wi-Fi | No physical Wi-Fi device attached. Fast-shaped fixture only. |
| Mobile data | No physical cellular device attached. Mobile-shaped fixture only. |
| Interruption/recovery | Injected transport timeout/truncation/stall and native decoder recovery passed. Physical handover unavailable. |
| Expired URL refresh | Controlled 401/403 one-refresh, format/length pinning and finite retries passed. No successful live expiry cycle. |
| Local regression | Actual fixture content URI decode/seek and final screen-off run passed. Hash verification remains. |
| Background playback | Service audio advancement passed; final screen-off local controller test passed. Real radio/Bluetooth/battery endurance unavailable. |

Controlled cases: `StreamTransportTest` 6, `StreamFormatsTest` 1 (MP3/WebM/Opus/M4A, seek/recovery/background), `StreamPreloadTest` 1, `StreamLocalRegressionTest` 1, `DiscoveryPreview2Test` 3 (Opus tagging/decode, schema 4→5, network-free canonical local reuse), `StreamingV2Test` cache case 1, buffer experiment 1, 100 MB LRU test 1. The 100 MB test read 104,000,000 bytes, verified eviction/clear and restored the original preference; full byte identity is covered separately by cache adapter/regression fixtures. Python streaming security/format policy suite: **15 passed**, host yt-dlp aligned to Android's 2026.10.26.

Final targeted JVM checks: **13 passed**, zero failures/errors/skips: resolution cache 4, recovery policy 4, stream policy 3, priority 2. [Machine-readable results](validation/jvm.json). Owner-signed release build succeeded with R8; certificate SHA-256 `ebefa44cc5fba1292fec9fc25bb4840e41e501bb0958a86931c046fd1be523e5`.

## Real network attempts and remaining limitations

- Android public licensed Blender-source decode probe: **failed**, 45,000 ms timeout. It reached resolver/Python initialization but did not produce decoded audio or a successful CDN first-byte measurement. [Output](validation/public-youtube-probe.txt).
- Host public extraction diagnostic: **failed**, `NETWORK`, 5,445 ms; not Android playback. [Output](validation/public-host-probe.json).
- Therefore no successful live YouTube startup, seek, throughput, DNS/TLS pooling, Wi-Fi/mobile or real expiry comparison is claimed. The performance improvements above are controlled-fixture evidence only.
- Cooperative cancellation checks prevent starting new speculative work when current playback is urgent. An already-blocked system DNS/socket operation cannot be forcibly preempted by a coroutine token; existing finite transport timeouts remain. No strict end-to-end hard-wall guarantee is claimed.
- Keep platform HttpURLConnection pooling, DNS behavior and existing read chunk policy: no successful live evidence justified a new HTTP stack, larger buffers or speculative connection tuning.
- 100 MB cache remains an opt-in experiment, preserving the existing default and all offline-download storage. No UI redesign, database schema change or data reset is part of this work.

Official owner-signed **18→19** in-place upgrade passed: Room remains schema 5; complete-row SHA-256 snapshots of tracks, favorites, history, playlists, playlist entries and online activity were unchanged. See [before](validation/upgrade-before.txt), [after](validation/upgrade-after.txt), [APK identity](validation/apk-identity.json).
