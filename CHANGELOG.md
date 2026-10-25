# Changelog

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
