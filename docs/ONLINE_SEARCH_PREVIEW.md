> Historical preview.2 search design below. Current streaming/download behavior: [STREAMING_PREVIEW.md](STREAMING_PREVIEW.md). The external-only listening limitation below was superseded in preview.3; live streaming remains independently unverified.

# Online Search — 1.5.0-preview.2

Yono Music Preview by Karisyono; com.yonzmusic.app.dev, code11, same existing Dev certificate and Room schema3. Installs beside official1.4.2 or upgrades the matching Dev package in place. Official/latest stays1.4.2 and preview updates do not enter its updater.

## Current flow

Search keeps Koleksi / Online, automatic typing with600ms debounce and minimum3 characters, offline accent/title/artist/album matching, cancel/latest-query protection,22s UI budget, at most12 operations/minute,30 cached pages with5-minute TTL and optional ten-entry private history. No Search/Enter tap, API key, account or provider menu is required. Requests only start after Online is selected. Search does not touch Room collections or music storage.

Online uses the already bundled yt-dlp library's ytsearch extractor (currently YouTube search). It requests flat metadata with download=False, skip_download, no playlists/video extraction/audio transfer/postprocessors/artwork writes, no cookies/login, remote plugins/runtime downloads or FFmpeg. Search pages contain at most12 raw entries, with up to3 pages. Later pages re-read a bounded prefix, so live search order can change; UI de-duplicates IDs. Pasted HTTPS video links are canonicalized and matched by exact video ID rather than substituting a similar recording. Playlist import remains in the existing consolidated import flow.

Metadata comes from the selected source: title, duration, actual supplied thumbnail, channel, artist list/album only if present. Missing values stay absent. Channel credit is labelled Kanal and is not silently rewritten as artist. Live/upcoming items are omitted; covers/remixes are not falsely claimed to be equivalent studio recordings. Results are discovery candidates, not automatic verified matches or proof of download rights.

## Removed integrations and listening

iTunes Search API, its clips, promotional assets and store badges, Google YouTube Data API/key configuration, and the embedded YouTube WebView player are removed from application code/resources. Old owned youtube-search-key / .tmp files are deleted; no collection/database reset. Historical source reports/screenshots remain as evidence of earlier releases.

There is no in-app replacement streaming preview in this revision. Buka sumber untuk mendengarkan opens the exact original page through Android and pauses local playback intentionally; local queue is preserved. Tinjau unduhan hands that exact page into the existing download manager, which still requires destination/source authorization and validates metadata, recording identity, audio readability and final publication. A search tap never downloads audio, auto-creates playlists or imports metadata as playable tracks. Existing review may reject sources that cannot pass its conservative checks; all search videos are not promised downloadable.

## Isolation, runtime and constraints

EngineSearchBackend uses a bounded two-worker executor, with a two-task queue and cancelled-task purging. Cancellation is immediate for UI and cooperative in Python, with checks around requests/response reads and finite socket timeouts. No thread termination is falsely treated as stopping native code. The acquisition serial lock is not used by search. GuardedYoutubeDL uses instance transports and redirect/HTTPS/public-address checks; no global network monkey patch is added. PythonRuntime synchronizes lazy Python startup across new and existing bridges. Native local playback/startup does not import the search engine.

Flat search does not require JavaScript/EJS or FFmpeg. Existing QuickJS/CPython/yt-dlp dependencies and ABIs remain unchanged for the separate acquisition engine. No new permission, server, key or SDK is added. Android system trust and proxy are respected; no TLS override or policy bypass is used.

Technical availability, source terms and content rights are distinct. yt-dlp's license does not grant a right to access or download a platform's content. YouTube restricts automated access under its terms; this extractor integration is not an endorsement or a claim of platform authorization. No DRM/access restriction bypass, login/cookie import or anti-bot evasion is added. Existing explicit download authorization remains required. References: https://github.com/yt-dlp/yt-dlp ; https://www.youtube.com/static?template=terms ; https://developers.google.com/youtube/terms/required-minimum-functionality (the removed official API integration had separate restrictions).

The actual live host request failed with a proxy CONNECT403; no alternate route or relay was used. Android/fixture/live outcomes are recorded separately in TEST_REPORT.md. This build is an experimental preview, not a proven stable remote-source release or a physical HyperOS validation.
