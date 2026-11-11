# Streaming audit — before optimization

Audited baseline: `3618d6946acb096f16d78c96d8c17ed7c3e881e8` (Preview 2). This audit preceded application changes. Read: ARCHITECTURE, STREAMING_PREVIEW, STREAMING_2_0, DISCOVERY_PREVIEW_2, TEST_REPORT, PREVIEW_2_TEST_REPORT; resolver/cache/range/recovery, transport, native formats, session/background, preload and live-probe regressions.

## Actual call paths and findings

- UI → MusicViewModel.playOnline → catalogue matching, complete local-track table materialization and possible whole-file hash → registry → MediaController → MediaSession admission → CatalogDataSource → another full table read/hash → StreamAudioCache → StreamDataSource → StreamRepository → PythonRuntime → yono_stream → yono_core.network.youtube_dl.
- EmbeddedEngine/yono_engine.py are legacy inspection/download/tag bridges, not the progressive streaming resolver. Streaming does not download an entire file first. Rewriting that legacy bridge would not fix initial streaming latency.
- PythonRuntime already synchronizes and reuses Chaquopy. StreamResolutionCache already has a bounded eight-entry LRU, per-key in-flight joining, cancellation checks, and a maximum two-minute TTL reduced by signed expiry minus 30 seconds. Neither a second Python runtime nor persistent signed-URL storage is warranted.
- StreamAudioCache already wraps the custom source in SimpleCache/CacheDataSource. Historical documentation saying no disk cache is obsolete. Capacity is currently 64 MiB, configurable off/32/64. A cache open resolves the source to pin a revision, then the upstream resolves again; usually a memory hit, but near-expiry/non-cacheable results can extract twice.
- Cache keys already combine source ID, format, codec, container and immutable revision. StreamDataSource.getUri returns the opaque source URI, avoiding CacheDataSource persisting the signed CDN URI. A revision change fails closed. Keep those invariants.
- StreamDataSource validates HTTPS Googlevideo hosts, public addresses, redirects, ranges/206 and total length, with bounded discard, finite connect/read/open budgets, one 401/403 refresh, format pinning and bounded transient retries. It uses platform HttpURLConnection keep-alive, not a newly allocated custom pool. Every close currently calls disconnect, including successful fully consumed bodies; partial/cancel paths close the stream before disconnecting, potentially waiting for a drain.
- Resolver timing covers only cache misses; CDN-open timing includes resolution/DNS/headers and is not first-byte latency. `first_audio` is measured from isPlaying, not AudioPositionAdvancing. Initial buffering, seeks and actual rebuffering are conflated. No wire-throughput or cache-read ratio is collected.
- Next resolver preparation excludes all `mb:recording:` items. It is gated by buffered duration but not current loading; its Python callback does not know it is speculative. Media3 1.11.1's DefaultLoadControl already prevents byte preloading while a player is loading. Preserve that built-in gate rather than claiming it is absent.
- One LoadControl sets 5000/30000/1000/2500 ms for both local and remote. Media3 1.11.1 already exposes separate streaming/local setters, so no second player/allocator or hand-rolled buffer policy is needed.
- Queue restoration is paused, admission re-resolves registered references, stale warm jobs are cancelled, and MediaSessionService owns background playback. Retain these safeguards and the existing single-player lifecycle.

## Cache adapter design, before changes

Retain the existing adapter: resolve once → freeze source/format/revision key → seed the first upstream open with that exact result → refresh only through the validating resolver. CacheDataSource continues receiving an opaque URI; upstream never exposes a signed redirect URI. Add byte-based cache accounting. Keep explicit SAF downloads outside this cache. Evaluate 100,000,000 bytes as an experimental option while preserving stored off/32/64 preferences and the existing default pending evidence. LRU and clear remain centralized. Do not bypass fresh identity resolution just to make expired-cache seeks appear faster.

## Measurement plan and limits

Instrument first, then record baseline on the audited algorithms before optimizing. Use identical generated owned audio, controlled resolver/HTTP delays and real Media3/service sessions for comparison. Separately record real Chaquopy cold/warm import and any public YouTube attempt. Shaped fixture rates are not real Wi-Fi/mobile results. No physical Wi-Fi/mobile device is attached; those measurements remain unavailable unless a real device becomes accessible. Preserve GPL-3.0, Room schema 5, all user files and collections; no backend, cookies, login, TLS weakening or access bypass.
