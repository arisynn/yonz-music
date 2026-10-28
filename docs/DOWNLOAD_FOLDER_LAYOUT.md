# Download folder layout · 1.5.1

Karisyono · Yono Music. Original audio stays in the selected destination for single tracks; reviewed multi-track imports retain the existing playlist-folder behavior.

```text
Music/                           ← only the user's selected destination
  .nomedia                       ← default-on Gallery hiding; tiny owned marker
  Artist - Title.webm            ← original audio container/codec, no conversion
  .cover/
    .nomedia                     ← cover hiding independent of audio setting
    Artist - Title.webm.cover.jpg
  .yono/
    Artist - Title.webm.yono.json  ← indented UTF-8 metadata, final newline
```

The setting **Sembunyikan unduhan dari Galeri** is on by default, including upgrade from an absent preference. It affects the whole selected destination and its descendants, not only WebM; MediaStore-based third-party music players may also hide that folder. Yono reads explicitly selected SAF sources directly, so its indexed audio remains playable. Choose a dedicated destination if that scope is too broad, or switch the setting off in Settings. No broad storage permission is added.

The app reconciles only its registered download directories and current selected destination on foreground and before publication. It does not walk all music or the whole device. An unavailable/revoked destination remains retryable; it does not become permission to delete files. The random marker token is journalled privately before creation. Disabling removes only a marker whose full contents still match its token; existing empty/external/modified markers and cover-directory markers remain. Uninstall removes the private journal, so an old marker may require manual removal; do not delete the music folder. A restored marker without ownership proof is never removed automatically.

`.nomedia` is a standard scanner instruction, not a promise that every OEM gallery updates immediately. Already indexed Gallery tiles may remain until its index refreshes. No media-database row deletion, mass renaming to .weba, audio conversion, cache/data reset or automatic file migration is performed. Actual HyperOS Gallery reindex timing is unverified here. The controlled API29 SAF test returned `video/webm` even though publication requested `audio/webm`, the saved bytes were unchanged, and native track inspection found audio only. No MIME label is treated as proof of a video track.

New engine manifests and manually repaired metadata are written in `.yono`. Per-track JSON retains schema1, title/all verified artists/known album/source identity, audio hash/size/duration and cover reference/hash; it contains no signed streaming URL, Android grant or credential. Cover paths remain relative to the **audio** directory (`.cover/...`), even though JSON is inside `.yono`. Library scans explicitly read the companion directory when recursive browsing is off. Old adjacent JSON and existing receipt URIs stay readable in place. Names alone do not prove track identity. Copy audio, `.yono`, and `.cover` together when moving the collection. Explicit user-chosen Export Metadata still writes to the location the user selects.
