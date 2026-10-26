# Yono Music 1.4.0 · Karisyono

Version code 7, Room schema 3, production ID com.yonzmusic.app, existing signing identities. No uninstall/reset is required.

Downloads: paste a supported link/list, explicitly review, select entries and candidate recordings, then acquire. The review sheet supports search, attention filter and persisted selection/name/playlist choices. Repeated unchanged input restores the existing plan. Metadata retry retains plan/entry IDs and destination. No automatic download on paste.

Names come from actual platform playlist metadata; list-only input uses known artist/title or a date. Create playlist is opt-in. One selected song is published directly in the chosen destination even when creating an app playlist. Multiple selected songs use a named subfolder only if creating a playlist. A committed layout stays fixed for retry. Audio remains original encoding; no conversion or bitrate inflation.

New companion covers live in `.cover` with a `.nomedia` marker inside it. Audio remains outside that directory. Manifest references `.cover/<actual filename>` plus artwork SHA-256; the importer still reads old sibling covers. Portable metadata and hidden cover traversal stay within the chosen tree. Gallery behavior depends on the gallery/provider respecting Android hidden-directory and .nomedia conventions; physical HyperOS is not verified.

Home has a moderate static visual with native curves and local artwork. Playback bars animate only when isPlaying is true, visible and system/app motion is enabled. Queue supports handle drag, swipe removal, undo, and accessible menu actions. Turn off shuffle to reorder. Artist portraits use initials or a local photo chosen explicitly; automatic online artist photography is deferred until identity/licensing can be verified.

Spotify: optional browser OAuth PKCE with a user-configured Spotify Developer public Client ID, exact registered redirect, and permitted account. No shared app credentials were supplied. Official 2026 Development Mode restrictions generally limit playlist contents to owned/collaborative playlists; public/editorial/Radio links are not guaranteed. Local TXT/CSV/JSON/M3U imports remain available. Spotify metadata is separate from permitted audio sources; no Spotify audio download or scraping fallback was added. API setup/login/refresh have fixture/security coverage but no successful live Spotify account test. See https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide and https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow .

YouTube fixed-playlist reading and original-audio acquisition retain the existing embedded engine. A live metadata-only attempt from this host returned CATALOGUE_NETWORK; no audio was downloaded. This is a connectivity/runtime test limitation, not a claim that every playlist is unsupported or that live acquisition passed. Tests use generated owned audio and fabricated metadata where specified.

Sinkronisasi menyembunyikan track dengan semua lokasi terkonfirmasi hilang; folder Library tanpa track aktif ikut menghilang. ID/favorit/playlist/riwayat tetap disimpan untuk pemulihan. Izin hilang dan penyimpanan sementara tidak tersedia tetap dapat dihubungkan ulang.
