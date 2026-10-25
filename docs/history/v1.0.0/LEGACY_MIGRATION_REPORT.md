# Legacy audit and migration

Inspected the user-supplied Spoti_Termux_1_2_1.zip before writing application code. All archive entries were size/path checked before extraction to a separate audit directory. No legacy installer, updater, Python module, executable, or test was executed.

## Findings

The archive is a Python CLI, not an Android project. `spoti.py` (63 KB) owns normalized metadata, CSV/JSON parsing, SQLite file/hash bindings, Spotify OAuth, YouTube catalogue verification, yt-dlp acquisition, FFmpeg conversion, artwork tagging, and command dispatch. `spoti_sources.py` (20 KB) adds Markdown/numbered text lists, ambiguous-title handling, public/external metadata sources, retry reports, and M3U export. `spoti_folders.py` (24 KB) adds playlist identity, journaled file moves, collision handling, M3U references, and filename cleanup. README, START_HERE, VALIDATION, installers, dependency lists, sample inputs, and representative parser/source tests were read. Existing test claims in VALIDATION are legacy claims and are not Yon'z Music test results.

## Native migration plan and implementation

- Rewrite numbered/Markdown artist–title list parsing in Kotlin. Preserve hyphens in artist names and preserve live/remix/recording distinctions.
- Parse TXT, CSV, JSON (tracks/songs/items exports), M3U/M3U8, and pasted lists with bounded input sizes. Match only actual indexed local audio. Reject remote URLs; report ambiguous/unmatched/invalid/duplicate rows explicitly.
- Use MediaStore and persisted read-only SAF grants instead of unrestricted filesystem traversal. Index metadata in Room off the main thread. Display embedded/MediaStore artwork without modifying tags.
- Store one transactional playlist membership/order model. Import creates references, never moves, renames, deletes, or overwrites audio. Export M3U8 references with explicit document creation.
- Keep import result history in Room, including row errors. Keep favorites/history and playback restoration independently from metadata rescans.
- Identify possible metadata duplicates without deleting files; actual list duplicates are suppressed by stable track IDs.

## Deliberate exclusions

No yt-dlp, ytmusicapi, spotipyFree, Redis, Spotify OAuth, public-page scraping, remote audio acquisition, FFmpeg shell execution, album-cover scraping, or embedded Python/Termux runtime. The legacy requirements include a downloader and optional brittle public-playlist reader; none is appropriate for the offline native player. No legacy binaries or dependency code is bundled. Remote playlist APIs are omitted to avoid credentials and network dependence. Byte-level audio hash matching and destructive folder/name reorganization are not migrated. Yon'z Music reports possible metadata duplicates rather than claiming two differently encoded files are identical.

## Data safety

Existing audio is opened read-only. Playlist removal affects Room references only. Missing audio stays indexed as unavailable so collections retain context. Library rescans preserve favorites, history, and playlist order. Legacy SQLite/OAuth state is never opened or copied. To migrate, choose the existing music folder, then import its M3U8 files or metadata lists. Original files and original Termux project remain untouched.
