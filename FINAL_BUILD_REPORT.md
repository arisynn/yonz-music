# Yono Music 1.5.0-preview.3 — native streaming and durable downloads

Karisyono · Preview ID com.yonzmusic.app.dev · code12 · Room schema3. Existing standard Dev certificate retained. Installs beside official1.4.2 or over the same-key Dev preview without uninstalling. Official application ID, production signing and latest/stable release remain unchanged.

## Delivered behavior

Search online still uses automatic embedded yt-dlp search. Native Play/Pause, Download and More actions now feed one shared Media3 service rather than an external-only listening flow. Mini-player, Now Playing, buffering, seek, mixed local/online queue, shuffle and background controls share their authoritative player state. A remote source remains metadata, not a fake downloaded Library track. Playback context restores paused.

A compact reused save sheet asks for SAF destination, optional playlist and source/content authorization. A selected exact recording can be queued while another runs. Room AppState and existing WorkManager serialize durable requests, deduplicate active source/destination, retain history/retry/cancel, reconcile published receipts after process loss, and preserve successors when cancelling a node. Completion requires an actual readable published output. Original audio/container and existing tags/.cover/portable manifests remain; no conversion/bitrate upscaling. Exact source identity is retained rather than substituting a similarly named recording. Unreadable/byte-changed references do not establish duplicates and are not removed.

## Runtime, signing and permissions

SDK min26, target/compile35; JDK17, Gradle8.11.1, AGP8.9.1, Kotlin2.1.20, Media3 1.6.1; arm64-v8a/x86_64. Existing pinned Chaquopy/Python/yt-dlp/EJS/QuickJS dependencies unchanged. Streaming resolves standalone progressive HTTPS audio on demand; restricted CDN transport supports ranges and one401/403 refresh. Signed audio URLs remain in memory only. No FFmpeg, video/audio merge, live/DRM/login/cookie/anti-bot bypass or arbitrary remote-URI admission.

No new permissions, dependency versions, database migrations, API keys, server or tracking. App permissions: INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS, WAKE_LOCK, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK, FOREGROUND_SERVICE_DATA_SYNC, REQUEST_INSTALL_PACKAGES. The merged manifest additionally includes RECEIVE_BOOT_COMPLETED and com.yonzmusic.app.dev.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION (signature level); inspect ANDROID_PACKAGE.txt / APK_INSPECTION.json for the actual artifact. No broad storage/media-read permission; SAF user grants only. Existing Dev certificate SHA256:3e8117db4ac9b9e71429136a4d687bdaec8a75f1c052ee7c153b6c62c2fda740. This is a debug-signed Preview, not a production-signed official update. No private key/password is distributed.

## Actual validation and limits

Preview assembly and lint passed (0 errors,117 warnings),129 JVM and173 host Python tests passed. Native fixture/regression attempts, harness fixes, screenshots and exact Dev11→12 collection retention are described in TEST_REPORT.md and docs/verification/v1.5.0-preview.3. No uninstall/data reset. New source network streaming has not passed in this environment: the real Android attempt returned a source error and did not reach playing before its45s test deadline; prior source metadata requests were blocked by the managed outbound proxy. Test fixture audio is an original generated WAV, not a successful commercial/live source download. No alternate network route or TLS bypass was used.

Physical Android16/HyperOS, Bluetooth hardware, current live WebM/Opus/M4A streaming, long-lived URL-expiry behavior on real sources, new queued live audio acquisition and independent Play Protect classification remain unverified. Existing formats/metadata fixture tests do not prove all remote codecs/extractors work. Online-only favorites/playlist memberships/listening statistics are deferred; existing local data remains local. A selected format may be rejected by source access or device codec support; finite errors/retry are provided. Source terms and content permissions apply separately from tool capability. This remains an experimental preview, not a verified stable streaming release.

## Distribution

Publish only v1.5.0-preview.3 as GitHub prerelease with make_latest=false. No stable update.json or official APK is supplied. Existing releases/tags are not overwritten. Source archive is generated from the exact final commit and excludes keys/local config/build outputs. Distribution appends the actual commit, APK path, bytes/hash/certificate and public verification result. Historical preview2 reports are retained in docs.
