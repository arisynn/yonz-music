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
