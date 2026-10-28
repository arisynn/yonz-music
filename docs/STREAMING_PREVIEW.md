# Native streaming and offline queue — 1.5.0-preview.3

Yono Music by Karisyono. Preview package com.yonzmusic.app.dev, code12, existing Dev certificate and Room3. Official/latest stays1.4.2. No release updater metadata points stable users at this preview.

## Using it

1. Open Search → Online and type a title, artist or supported video link. Results appear automatically after a short debounce; no API key or extra Search tap.
2. Tap a result or its Play button to listen. The current result's button toggles pause. The existing mini-player opens the single Now Playing screen, with seek, queue and a Save offline action.
3. More offers Play here, Play next, Add to queue, Save offline and the original source page. Explicit Play here replaces the current queue; queue actions preserve it. Local and online items share one Media3 session. Adding an item does not silently start a paused queue.
4. Download opens one compact approval sheet: choose/change the writable SAF destination, optionally create a playlist (off initially), approve the content/source permission, then enqueue. One song without playlist goes directly in the destination. Metadata/artwork companions continue in .cover with .nomedia where applicable. Original encoding/container is retained; no MP3 conversion or invented bitrate/HD artwork.
5. The shared Download list shows pending/current/history rows, real progress or an indeterminate indicator, cancel, retry and open result. Another request can be added while one runs. Duplicate active source+destination requests are rejected. Cancelling one row does not cancel later WorkManager nodes. A published readable receipt is required before completion. Pause/resume download is not offered.

## Architecture and collection safety

Search still uses flat read-only yt-dlp metadata; playback resolves full metadata only for a selected11-character video ID. Resolution requests best standalone HTTPS audio with vcodec=none, skip_download, no merge/transcoding. The Python boundary returns URL/allowed headers in memory; Kotlin owns playback and SAF downloads. The included QuickJS/EJS runtime is reused for extractors that require it, not borrowed from Termux.

A custom Media3 DataSource revalidates HTTPS CDN redirects and public addresses, handles byte ranges/seeking and finite transport timeouts, and refreshes an expired401/403 reference once. There is no stream-file disk cache. MediaItems use opaque yono-stream references. Room AppState stores source ID/title/actual supplied credits/art reference and persistent download status, never an expiring signed audio URL. Local MediaStore/SAF content playback continues through the same DefaultDataSource.

The service admits registered source references rather than trusting external caller URIs. Remote references use a view-only Track adapter for existing artwork/player composables and never enter the tracks table. On reopen the context is restored paused; no unexpected network/audio play starts. Local library search, SAF scans, favorites, playlist manager and qualified listening remain centralized. Online-only favorites, local playlist memberships and qualified online listening statistics are not introduced in this preview.

Before playing, an exact downloaded recording with matching source ID and verified bytes can use its local file. Downloading the selected result also retains exact source ID; title/artist/duration alone cannot substitute a different recording. Existing verified source/hash reuse remains allowed. Files unavailable or changed since their identity was stored cannot establish a duplicate. Actual output publication still uses existing checksum/audio validation, safe tags and portable manifests; metadata alone never becomes a playable file.

Persistent jobs use the existing WorkManager unique queue and Room AppState transactions without schema changes. Source metadata, destination, playlist preference, work ID, plan ID and cancellation survive process loss. Missing work is rescheduled; superseded work IDs are ignored; cancelled nodes return success so later requests remain runnable. Recovery reconciles only that job's validated plan and matching source receipt, including a file committed just before process death/cancellation. No user file is removed for a failed or cancelled request.

## UI and limits

Home / Library / Search / Playlists, owner logo and themes remain. Direct result controls have48dp targets; status text yields space to controls. Shared Now Playing shows true buffering state and disables seek for unknown duration. Download approval remains one scrollable modal for compact displays/font scaling, rather than adding another navigation destination. Existing reduced-motion settings apply.

Only standalone progressive HTTPS audio exposed by this source is admitted for streaming. HLS-only sources, merged video/audio, live, DRM/private/premium/access-restricted sources, login/cookies and FFmpeg transcoding are not supported by this implementation. Original WebM/Opus/M4A decode depends on Android/Media3. A network or extractor failure is shown as an error; local playback remains independent. User reports about prior Search do not prove live streaming. The real streaming attempt in this build environment failed; controlled own-audio fixture results are documented separately in TEST_REPORT.md.

Source terms and recording rights are separate requirements. yt-dlp is an extraction tool, not a permission grant. YouTube terms restrict automated access; this preview is not endorsed by YouTube and does not promise platform authorization. No credential theft, access restriction bypass or anti-bot evasion is implemented. Sources and networks may reject requests. References: https://github.com/yt-dlp/yt-dlp and https://www.youtube.com/static?template=terms .
