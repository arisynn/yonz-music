# Online Search — 1.5.0-preview.1

This is a Dev preview (`com.yonzmusic.app.dev`), using the existing Dev certificate, code10. It installs beside official Yono Music1.4.2, or upgrades an existing Dev install. Official APK/package/signing and the stable update channel are not replaced. The launcher label is Yono Music Preview; the original Karisyono/YONO branding and four navigation destinations are retained. Room remains schema3.

Search starts automatically after typing: local matching is offline with120ms debounce and accent/punctuation/word-order matching/ranking. Online is explicit opt-in through Koleksi/Online, at least3 characters,600ms debounce, latest-query protection, cancellation,22s native metadata budget, at most12 search operations/minute/provider (YouTube may use a second details API request),20 iTunes or15 YouTube results per page,30 in-memory cached pages with5min expiry, and at most100 paginated rows. API quotas can impose stricter limits. Metadata errors are not disguised as zero results. Network work does not acquire the Python/acquisition global lock.

## Provider scope

- **iTunes store discovery:** official iTunes Search API, Indonesia storefront, metadata/real available preview URLs. Every result and audition sheet links directly to its store item using the original Apple badge. Preview attribution is displayed. Native Media3 streams only the provided clip, with its actual decoded duration (not a hardcoded30 seconds); no media disk cache, file export, collection insertion or offline tags/artwork copying. Missing previews stay unavailable. Apple's preview is not connected to a YouTube download matcher.
- **YouTube:** official Data API v3 search, optionally configured with the user's own restricted API key. The key is in private no-backup app storage, sent only as an API header to www.googleapis.com, not embedded in APK or URLs/logs. Search returns video titles/channel credits (never fabricates an artist), thumbnails and duration when available. Without a key, valid pasted video links still open a visible official enhanced-privacy iframe; native search displays configuration guidance and an explicit external YouTube search fallback. No automatic browser launch while typing, no audio extraction for preview, no hidden video/ads, login/cookie import, DRM bypass or background YouTube playback. Embed restrictions/errors have an external-source fallback. API-key search is not claimed live-tested without a real configured project key.
- **Wikimedia Commons:** existing license-filtered source search remains an additional catalogue. Source/license links are visible, native audition is clipped to30s, and Tinjau unduhan passes the explicit audio link into the existing consolidated download review. It never starts a transfer or creates a playlist on a search tap. Existing output choice, authorization, original-container handling, optional playlist and final file validation remain authoritative.

This is not a claim that every platform/extractor supports global search. Existing download/playlist import remains intact. Spotify account setup and Apple Music/MusicKit subscription APIs are not added or silently substituted. The Apple→YouTube acquisition proposal is excluded under the documented promotional-use restrictions; another provider's preview support does not grant permission to download its content.

## Playback and privacy

Native preview uses a separate foreground-only, lazy ExoPlayer, normal audio focus and noisy-route handling, bounded streaming transport and buffers. Starting preview pauses the local controller. Local Play/jump stops preview; closing the audition sheet, leaving Search or Activity.onPause stops native preview. Local queue/position/favorites/history are not replaced by a remote preview, and local music does not auto-resume on closing it. YouTube uses the official visible web player with no JavaScript/native bridge, no file/content access or SSL overrides, and lifecycle pause/destruction. No new manifest permissions, native libraries, Python dependencies, API secrets or background service are introduced.

Online queries/images go to the selected provider after explicit Online selection. Optional query history is bounded to10 successful text queries in private no-backup storage; it can be cleared/disabled. Video URLs and API keys are excluded from history. Response/thumbnail caches are bounded memory caches; preview audio has no file cache. Selecting local mode does not require network, API configuration or Python startup.

## Limits and setup

Enable YouTube Data API v3 in your own Google Cloud project and restrict the key to that API. Enter the key through Online → Pengaturan. Do not paste a client secret, Spotify credential or GitHub token. API access/quota is governed by Google. Some video embeds can be unavailable by region, age, uploader restrictions or client-identification requirements. Use Buka di YouTube where the official embedded player cannot play. A source's preview availability is independent of download permission and does not prove another source is the same recording.

Official provider references (checked27 October2026):
https://performance-partners.apple.com/search-api
https://www.apple.com/itunes/marketing-on-itunes/identity-guidelines.html
https://developers.google.com/youtube/v3/docs/search/list
https://developers.google.com/youtube/iframe_api_reference
https://developers.google.com/youtube/terms/required-minimum-functionality

Actual test outcomes and device limitations are recorded in TEST_REPORT.md and docs/verification/v1.5.0-preview.1. Fixture tests and live-service tests must be distinguished.

Commons currently reuses the existing bounded blocking transport; cancellation can wait for its socket timeout even though stale results are discarded. Apple/YouTube metadata transport disconnects on cancellation. YouTube readiness is bounded to18s with a visible external-source fallback, not a claim that embedding is verified on every WebView.

Visual refinement before first publication: the official Apple Small Badge (no Buy on wording) replaces the large graphic in results and the preview sheet. Its79.25×16dp graphic has an88×48dp touch area and clear space. The direct store destination and provided-courtesy attribution remain.
