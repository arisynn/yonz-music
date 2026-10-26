# Yono Music — by Karisyono

Your Music. Your Space.

**[1.3.0](https://github.com/arisynn/yonz-music/releases/tag/v1.3.0):** perbaikan relokasi file dengan ID tetap, metadata/sampul portabel, daftar unduhan persisten, beranda ringkas dan Smart Mix offline. [Rincian dan batasan](docs/UPDATE_1_3_0.md) · [Pengujian aktual](TEST_REPORT.md). APK produksi menggunakan identitas signing pemilik yang sama; APK Dev tetap kanal terpisah. Rilis official 1.3.0 tersedia di bawah.

![Yono Music owner-supplied identity](brand/launcher-preview-1024.png)

**[Download Yono Music 1.3.0](https://github.com/arisynn/yonz-music/releases/download/v1.3.0/Yono_Music_v1.3.0.apk)** · [Release, source, assets and checksums](https://github.com/arisynn/yonz-music/releases/tag/v1.3.0). Android 8.0+, 64-bit arm64-v8a/x86_64. Production-signed by Karisyono. The [early Dev preview](https://github.com/arisynn/yonz-music/releases/tag/v1.1.0-preview.1) remains available as a separate testing channel.

A native Kotlin/Jetpack Compose music player, with Media3 background playback, local Room collections and DataStore preferences. Free, open source, no ads, no tracking, no account. Local playback, library browsing and playlists work without internet. Optional source downloads and GitHub update checks use the network.

## Core features

- **Choose your library.** Import audio documents or SAF folders, control subfolders, enable/exclude/remove sources, and refresh only those sources. Opening the app reads the saved database. There is no whole-storage scan, permission-triggered scan, or broad music/storage permission.
- **Keep collection context.** The real Room 1→2 migration retains favorites, playlists/order, history and old track references. Former global entries stay unavailable until selected sources reconnect exact identities. Removing a source never deletes its music.
- **Spoti's useful engine inside Android.** Chaquopy embeds pinned Python/yt-dlp, EJS and a reviewed Android QuickJS runtime. Kotlin owns queue, SAF storage, progress/cancel/retry and native playback. Termux is unnecessary. Explicit authorized source URLs are reviewed before downloading. Wikimedia Commons is an additional catalog, not the replacement engine.
- **Preserve recordings.** Native validation checks the audio, original encoding is retained, Mutagen adds tags/artwork where the container permits, and per-playlist folders include source/license attribution. Exact verified source history or conservative selected-library identity can reuse an existing recording. Lists alone never create playable audio.
- **Original owner logo.** The supplied mist-blue YONO monogram/note is traced into adaptive, monochrome and splash vectors. Main navigation, themes and playback UI are retained.
- **Production updater.** The stable channel checks GitHub Releases asynchronously, offers Unduh/Batal, verifies APK contents/signing and uses Android's confirmation installer. Successful replacement and next-launch reconciliation clean only the owned temporary APK. Actual installer permission denial, cancellation, same-key upgrade, process-stop reconciliation and temporary APK cleanup were exercised on the API 29 emulator.

## Start listening and import

Open **Import → Files & playlists** and choose local audio or a folder. No global permission is required. Only chosen sources appear in Library. TXT, CSV, JSON, M3U/M3U8 and pasted numbered/Markdown lists match actual selected audio. HTTPS source references can be reviewed separately in **Import → Downloads**. Downloading requires a writable destination and explicit content/source authorization.

Downloaded audio retains its original encoding/container. MP3/FLAC/M4A/Ogg/Opus are tagged where safe; WebM/WAV retain metadata in Room and a hash-bound portable JSON companion with a separate cover. Metadata/cover repair never needs to fetch the audio again. Actual codec playback depends on Android/Media3 support. The embedded engine does not include FFmpeg stream merging, video extraction or transcoding. Remote catalog/extractor support varies: see [migration and source scope](LEGACY_MIGRATION_REPORT.md). No DRM bypass, login/cookie import, Spotify-to-MP3 claim or untested blanket platform support is offered.

Home, Library, Search and Playlists remain the four main destinations. One mini-player opens the shared Now Playing session. Favorites, custom playlist editing/reordering/export, queue, sleep timer, system equalizer intent, dark/light/system themes, reduced motion and glass settings remain available.

## Signing and installation

Minimum Android 8.0/API 26; **64-bit arm64-v8a and x86_64** only. Dev previews use ID `com.yonzmusic.app.dev` and standard debug signing. The initial public production identity is separately persisted in the owner's encrypted GitHub repository secrets. Old differently signed test APKs cannot be upgraded to that identity in place. The Migration build/export-and-restore path is documented; the app never uninstalls itself. See [signing and owner recovery](docs/SIGNING_AND_RECOVERY.md).

[Final build report](FINAL_BUILD_REPORT.md) · [Build](BUILD_INSTRUCTIONS.md) · [Actual tests and limitations](TEST_REPORT.md) · [Security](SECURITY.md) · [Architecture](docs/ARCHITECTURE.md) · [Licenses/source directions](docs/THIRD_PARTY_NOTICES.md)

Physical Xiaomi HyperOS, Bluetooth hardware and Google Play Protect classification are not verified. If HyperOS interrupts screen-off playback, allow background activity through its normal app battery settings; the app does not force an exemption.
