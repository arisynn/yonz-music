# 1.5.0-preview.4 — 2026-10-28

## 1.5.2 · 2026-10-29

- Antarmuka Indonesia untuk navigasi, pustaka, pencarian, pemutar, antrean, daftar putar, impor, metadata/sampul, pengaturan, unduhan dan pembaruan; label aksesibilitas ikut disesuaikan.
- Label tampilan dan255 pesan lama/runtime dipetakan saat ditampilkan. Kode tema/pengurutan/status, skema Room3, ID lagu, nama koleksi pengguna, metadata musik dan format audio asli tidak diterjemahkan atau ditulis ulang.
- Sumber daya Material/Media3, tanggal dan kanal notifikasi mengikuti bahasa Indonesia di dalam aplikasi. Konfigurasi kanal lama diperbarui namanya sambil mempertahankan suara/tingkat notifikasi pengguna. Penanganan pencatatan bahasa OS bersifat opsional dan tidak menghalangi startup.
- Label Daftar putar dapat memakai dua baris pada navigasi saat font besar. Dialog, tombol, dan mini-player diperiksa pada320dp/light100%/dark150%, termasuk keyboard aktif pada150%.
- Package dan signing official tetap sama; versionCode16. Tidak ada izin, layanan jaringan, ketergantungan atau fitur unduhan baru.


## 1.5.1 · 2026-10-28

- Default-on “Sembunyikan unduhan dari Galeri”, persisted in DataStore. Applies only to explicit download destination folders, including their descendants. Existing selected destination reconciles on foreground; no whole-storage/media-library scan.
- Journal ownership before creating `.nomedia`; disabling removes only exact markers carrying the app's random ownership token. Existing/modified user markers and `.cover/.nomedia` survive. Unavailable/readonly providers show an actionable notice; publication does not silently ignore a failed hiding policy.
- New portable metadata manifests use hidden `.yono/` directories and readable two-space UTF-8 JSON with a final newline. Original audio and `.cover` remain in place. Explicit scans find both new manifests (even with recursive music browsing off) and legacy adjacent JSON, matching audio/art by SHA-256. No automatic moving or rewriting of old companion files.
- Package, production signing identity, Room schema, collections, navigation, engine dependencies and original audio format retained; versionCode15.


## 1.5.0 — native online playback and direct downloads

- Promote the engine Search/streaming/direct-download workflow while preserving Home / Library / Search / Playlists, the owner logo and original audio formats.
- Compact horizontal actions, immediate enqueue to a saved SAF destination, optional playlist creation, and truthful per-request recovery/retry/cancel status.
- Prepare the next item's initial audio through Media3 (target5seconds); resolve at most two upcoming queue items in memory after playback starts. Concurrent playback/warm-up shares successful resolution; failures, cancellation and expiry remain retriable.
- Keep every active selected-download request visible ahead of a bounded terminal history; long histories cannot disable active dedup/recovery/cancellation. Activity indicator includes persisted waiting requests.
- Pin Guava33.7.2-android after exact-version OSV review found CVE-2026-102554 in33.3.1-android. Updated notices and source provenance.
- Online playlist snapshots from fixed public YouTube Music/YouTube links, review before save; custom online playlists from Search, persistent order/rename/remove and shared-session Play all. Collection backups include validated references.
- Remove Spotify OAuth UI/callback/token exchange and retire only obsolete account credentials. Metadata exports remain supported.
- Same production ID/key, versionCode14, Room3 and existing migrations. No uninstall, data reset, transcoding or broad storage access.


- Simplify only the new online-search/stream-save UI: one result row, tap to play/pause, More for three horizontal secondary actions; compact grouped download approval with destination/playlist side by side. Keep existing Home/Library/Playlists/player and list-import UI.
- Enqueue directly from Search/Now Playing when a saved SAF grant exists; use truthful result messages and keep folder/playlist configuration explicit. Ask for configuration only when destination/grant is missing.
- Pin format during expiring-URL refresh, validate byte ranges/content identity, bound ignored-range discard and automatic network retries. Surface sanitized source-specific errors and safe manual replay.
- Serialize selected-request retry/cancel/recovery, reject stale WorkManager generations, retain per-entry errors, and verify exact-source published receipts by SHA256 before indexing/completion.
- Keep selected source during metadata retry and verified duplicate reuse. Original audio, portable metadata/.cover, local collection IDs, Room3, permissions/dependencies and Dev certificate are unchanged.
- Code13, separate Dev Preview. Official/latest1.4.2 remains unchanged. Live-source availability and physical HyperOS are not established by fixture tests; see TEST_REPORT.md.

# 1.5.0-preview.3 — 2026-10-28

- Add native online playback through embedded yt-dlp with on-demand expiring audio references; retain one Media3 service, mini-player, Now Playing, seek and mixed queue.
- Add direct Play/Pause, Download and More actions in Search, truthful buffering state, compact shared download approval, and remote artwork in the existing bounded cache.
- Add durable exact-recording download requests: enqueue while busy, active-source/destination deduplication, per-item cancel, retry and WorkManager recovery. Do not cancel dependent work when one row is cancelled.
- Verify selected recording ID rather than silently substituting catalogue title/artist matches. Preserve original audio, SAF publication validation, optional playlists and portable metadata/.cover companions.
- Do not insert remote references into local Library or persist signed stream URLs. Restore playback context paused. Keep local qualified listening/favorites unchanged.
- Preview code12, same Dev package/certificate, Room3, SDK and dependencies/permissions unchanged. Official1.4.2 is not replaced. Live source availability remains unverified; fixture results are separate from actual network failures.

# 1.5.0-preview.2 — 2026-10-28

- Replaced online providers with embedded yt-dlp flat metadata search, automatic600ms debounce, cancellation/deadlines, bounded pagination and cache.
- Removed iTunes API/audio/art assets and Google YouTube Data API/key UI, plus official iframe/temporary native audition. Listening now opens the original source externally; no replacement preview is faked.
- Kept one Search tab, local offline matching, optional private history and exact-link handoff into existing authorized download review. Existing obsolete search key files are removed; music/collections remain untouched.
- Search uses separate bounded workers and per-instance guarded networking; all bridges share lazy Python startup synchronization. Channel names stay labelled as channels rather than guessed artists.
- Preview code11, same Dev signature/package, Room schema3. Source is still YouTube through yt-dlp; removing the API provider does not remove the underlying platform. Live test requests were blocked by the environment proxy403; do not claim general live support.

# 1.5.0-preview.1 — 2026-10-27

- Automatic Search with offline ranked/accent-aware matching and an explicit Online mode; cancellable debounce, bounded caching, timeouts and query history controls.
- Official iTunes discovery with real promotional clips, store badges and attribution; no preview export or Apple-to-YouTube download mapping.
- Optional own-key YouTube Data API search and visible official player; direct video links and external fallback. Commons catalogue and handoff to existing authorized download review.
- Foreground audition pauses local playback without replacing its queue or adding remote tracks to collections.
- Small official iTunes attribution badge replaces the oversized Buy on artwork; store link and accessible touch target retained.
- Compact source selector and one keyboard inset keep results usable while typing.
- Separate existing Dev identity, code10; official 1.4.2 and Room schema3 remain unchanged.

# Changelog

## 1.4.2 — 2026-10-26

- Start each newly enabled shuffle cycle at the current song so all other queued songs remain reachable; keep the original queue order when disabling shuffle.
- Display Media3’s actual playback order in the queue, including correct upcoming labels and native-index jump/remove behavior.
- Crossfade decorative Home artwork between existing local covers every six seconds. No tap action; pause when hidden/backgrounded and respect reduced motion. Missing covers are skipped; no artwork network lookup.
- Keep IDs, Room schema 3, signing, permissions, downloader, original audio and updater verification unchanged.

## 1.4.1 — 2026-10-26

- Fix updater crash `current must not be NaN` at download completion: capture immutable UI state and calculate a finite progress value before deferred draw/semantics callbacks.
- Unknown totals use indeterminate progress. All download/playback progress uses the same bounded snapshot calculation.
- Existing signature/checksum validation, signing identity, collection database and 1.4.0 features retained.

## 1.4.0 — 2026-10-26

- Tambah tinjauan per lagu, pencarian, pilihan tersimpan, pemulihan daftar sama, dan retry metadata dengan ID rencana tetap.
- Nama daftar mengikuti metadata sumber; pembuatan playlist opsional. Satu lagu langsung di folder tujuan; banyak lagu tanpa playlist juga langsung di tujuan.
- Sampul pendamping baru di `.cover/.nomedia`, manifest portabel mendukung referensi relatif terbatas dan hash. Sampul lama tetap dibaca.
- Home memakai visual berukuran sedang; bar status bergerak hanya saat audio diputar; antrean drag/swipe/undo dengan aksi aksesibel. Pengurutan memerlukan shuffle dimatikan.
- Avatar artis berupa inisial atau foto lokal pilihan pengguna.
- Koneksi metadata Spotify resmi melalui OAuth PKCE opsional dengan Client ID yang dikonfigurasi pengguna. Token dienkripsi Android Keystore. Belum diuji login Spotify langsung; akses playlist mengikuti kebijakan API.

## 1.3.0 — 2026-10-26 · 2026-10-26

- Add non-destructive Room 2→3 migration, exact-byte relocation/rename reconciliation, typed availability and explicit relink. Retain collection IDs and rebind the shared queue.
- Publish portable SHA-bound metadata/cover manifests, preserve all artist credits, and add old-collection metadata/cover repair without audio redownload.
- Size artwork requests for thumbnail/Now Playing, invalidate by revision and expire negative cache entries.
- Consolidate persistent download list/history, per-item cancellation, retry and verified publication receipts; retain playlist order and manual edits on retry.
- Compact Home, hide unknown albums, retain scroll state and add optional explainable offline Smart Mix based on qualified listening.
- Respect reduced motion in page/player/action/download-status transitions.
- Preserve application IDs and existing Dev/owner signing identities; increase versionCode to 6. See [implementation, scope and limitations](docs/UPDATE_1_3_0.md).

## 1.2.0 — Preview 3

- Share one compact smart-paste editor between Downloads and local song-list import.
- Split repeated/selected-library artists and explicit separated entries conservatively; preserve ambiguous text with a review hint.
- Add undo and a full-list editing dialog, without growing the main field beyond three lines.
- Recognize structured pasted JSON/CSV/M3U while keeping their content and signed URLs intact.
- Document the next update plan and Spotify authorization/access prerequisites. Spotify OAuth is still not configured.
- Dev version code 5 updates Preview 2 with the same debug signing identity.


## 1.2.0 — Preview 2

- Enforce original audio format through the engine/API; disable conversion requests.
- Recognize more shared link forms while preserving source, credentials and redirect checks.
- Save companion covers for WebM/Opus; embed where supported and retain Room artwork across rescans.
- Add a cover-only action in result details for earlier downloads without fetching/modifying audio.
- Version code 4 updates the existing Dev preview. Production 1.1.0 remains stable.

See `docs/ENGINE_ORIGINAL_ARTWORK_PREVIEW_2.md` for actual verification and source limitations.


## 1.1.0 — 2026-10-25

- Rename the public application to Yono Music; credit Karisyono and use the supplied YONO mist-blue logo.
- Replace global library scanning with an explicit SAF source registry and non-destructive Room migration.
- Embed Python/yt-dlp/EJS and Android QuickJS; add reviewed source downloads, native audio validation, tags/artwork, SAF publication and durable retryable queues.
- Retain offline playback, original navigation/themes, favorites, history and playlists; add portable collection export/restore.
- Add a signature-verified GitHub Releases/PackageInstaller update path, pending-install reconciliation and owned-cache cleanup.
- Establish an owner-persisted initial production signing identity; separate Dev and Migration channels.

A separate Preview 1 was published early at the owner's request. Stable delivery follows build, unit, migration, permitted-source, installer and UI verification. See TEST_REPORT.md for actual coverage and remaining device limitations.

## 1.0.0 — historical

Initial native offline music player. Its reports are preserved in `docs/history/v1.0.0/` and at tag v1.0.0.

Sinkronisasi menyembunyikan track dengan semua lokasi terkonfirmasi hilang; folder Library tanpa track aktif ikut menghilang. ID/favorit/playlist/riwayat tetap disimpan untuk pemulihan. Izin hilang dan penyimpanan sementara tidak tersedia tetap dapat dihubungkan ulang.
