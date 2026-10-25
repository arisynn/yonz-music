# Test report — Yon'z Music 1.0.0

Verification date: 25 October 2026. Tests use original generated sine-wave audio, not commercial recordings. No sample songs are bundled in either APK.

## Environment and scope

Full JDK 17.0.21, Gradle 8.11.1, AGP 8.9.1, Kotlin/Compose compiler 2.1.20, Android SDK 35 / Build Tools 35.0.0. Runtime testing used the Android 10 / API 29 AOSP x86_64 emulator with software rendering, no KVM acceleration, and muted host audio. Playback assertions verify decoder/player state, seek position and audio-focus behavior; they do not assess audible output quality.

An Android 15 / API 35 emulator was also installed and launched. Its System UI repeatedly timed out under software emulation. Startup branding and an initial Home render were observed, but a complete API 35 runtime test was **not completed**. That environment failure is retained under docs/verification and is not presented as an app success. No physical Xiaomi/HyperOS device was available.

## Automated tests

- **44 JVM tests:** text/Markdown/numbered lists, normalized artist/title matching, featured credits, recording-version preservation, wrong-artist/ambiguous/missing/unavailable rejection, duplicate counting, bounded inputs, JSON export shapes/nesting, CSV quoting and headers, M3U path safety, same-library export round trip, 10,000-entry matching, collection ordering, and completed/mid-track playback-position restoration.
- **6 Android instrumentation tests:** real SQLite metadata updates preserve favorites/history/memberships; playlist append/move consistency; playlist delete cascades only memberships; missing tracks retain collection references; 10,000 import-detail rows read across cursor windows; actual generated PCM playback with seek, pause/resume, transient focus loss/recovery, active audio-session metadata, timer set/cancel, paused queue append persistence, and queue clearing.
- See the final instrumented-run log and Gradle/JUnit reports in docs/verification for results and exact test method names. The final run passed all 44 JVM tests and all 6 Android instrumentation tests, with no failures. Debug/release builds and lint completed; both lint variants report 0 errors and 22 nonblocking warnings.

The instrumentation playback test creates and removes only its own MediaStore tone, and changes the current playback queue. Run it on a development emulator, not during personal listening.

## Functional/UI checks actually exercised

| Workflow | Observation |
| --- | --- |
| Fresh install / empty library | Home and Library rendered without account, activation or network use |
| Music permission denied, then granted | Rationale remained usable; Android permission dialog exercised; scanning started after grant |
| MediaStore scan and rescan | Five generated files indexed; cached metadata and collection data retained |
| MP3, M4A/AAC, FLAC, WAV, OGG | Each selected independently and reached active MediaSession playback; session evidence saved separately per format |
| Playback controls | Play/pause, system next and pause/resume media-key commands, native seeking, shuffle and all repeat modes exercised |
| Background / display asleep | Session continued playing after Home and screen sleep; power dump recorded Asleep with Media3's playback CPU lock |
| Audio interruption | Real AudioManager transient focus request suppressed playback and abandoning focus recovered it |
| Favorites | Favorite and unfavorite controls updated the shared collection |
| Playlists | Create, select/add songs, reorder, rename, and remove/delete confirmation/cancel paths exercised; actual delete cascade verified in SQLite tests |
| Search | Local title search, no-results state and playlist-name search exercised, including an active IME screenshot |
| Pasted import | 2 matched songs, 1 repeated entry, 1 invalid remote URL and 1 unmatched title; only real local matches saved |
| M3U8 document import | Android DocumentsUI selection, two local filename/metadata matches, and playlist creation exercised |
| Dark/light themes | Separate palettes rendered; preference persisted through process recreation |
| Reopen context | Restored paused; completed track restarts from the beginning on Play; initial buffering was allowed to complete before checking playback |
| Missing/corrupted audio | An owned synthetic WAV was temporarily made missing/corrupt outside the app; player skipped it, then recovered after byte-identical restoration and rescan |
| Album/artist/folder browsing | Shared filtered detail views opened with the expected counts |
| Queue | Open, move, native append/persist and clear exercised; queue editing uses saved order and shuffle can randomize playback order |
| System equalizer | Handoff/fallback invoked with a real session ID; emulator did not expose a usable device equalizer panel |
| Configuration changes | 320 dp and 360 dp widths, font scale 1.8, portrait/landscape recreation; controls remained reachable, with vertical scrolling in compact/large-text layouts |
| No network dependence | Installed manifest contains neither INTERNET nor ACCESS_NETWORK_STATE; local indexing/import/playback were exercised with Wi-Fi/mobile data disabled |

## Dedicated visual audit

Actual emulator screenshots are in docs/screenshots. Home, Library and group views, Search, Playlists/details, import preview, Settings, Now Playing, mini-player, queue and collection dialogs were inspected. Main lists receive Scaffold bottom-bar padding; mini-player and navigation stack vertically. The IME screenshot shows the active search field above the keyboard. Large font and landscape states were checked for reachable controls and scrolling.

Issues found and repaired: compact-player controls initially required excessive scrolling; artwork/control spacing now uses available height. Light-theme logo contrast improved. Import results now return to the summary instead of retaining the pasted-list scroll offset. Completed-track restoration, empty-queue restoration and persistence of paused queue edits were corrected. Early row-size/count limits protect list parsing and import history uses individual rows rather than a large cursor blob.

No unintended collisions were observed in the final audited states. This is not a guarantee covering every device, font, provider, or possible window size. Earlier failed/partial captures are explicitly separated from final screenshots under docs/verification/earlier-attempts.

## Checks not completed / limits

- Physical Xiaomi HyperOS battery endurance, secure lock-screen hardware, Bluetooth devices and wired headset unplugging; system media-key handling was tested instead of claiming hardware validation.
- Full Android 15 runtime suite, API 26 runtime, and Android 12+ blur rendering/performance on hardware. API 26 compatibility was checked by compilation/lint; blur is guarded and has a tested pre-31 fallback.
- Full 15–60 minute sleep-timer expiry/endurance; scheduling and cancellation were tested, not reboot persistence or exact alarms.
- Every SAF provider/folder permission/revocation scenario, very large real audio libraries, and export UI round trips across devices. Native folder/file selection and export implementations are present; parser/export logic was unit tested and M3U document input was device tested.
- Airplane-mode Settings automation did not complete reliably under software emulation. Offline checks used disabled Wi-Fi/mobile data and the verified network-free manifest; no separate airplane-mode result is invented.
- Formal accessibility/contrast certification, audio quality measurement, fuzzing all media containers, and an independent penetration test.
- Google Play Protect, Play Store review, VirusTotal or another independent APK scanner. No classification/approval is claimed.

## Security/build evidence

Final debug/release lint reports are preserved, including nonblocking warnings about newer library versions and style suggestions. Dependency coordinates are pinned for the tested toolchain. OSV queries returned no advisories for the 131 queried resolved runtime coordinates and nine selected direct/build components on the verification date. This is an advisory-database check, not proof that dependencies or the APK have no vulnerabilities.

Debug APK Android signature verification, manifest/permission dumps, artifact SHA-256 values, and release unsigned status are recorded in the delivery build report and docs/verification. Original fixture hashes are retained for data-safety comparisons. The debug APK is intentionally debuggable; production distribution requires the owner's release signing key.

## Optimized release smoke

A temporary copy of the unsigned optimized release was signed with the existing development key for emulator testing. Native launch, Room library display and local MP3 playback passed. The delivered release remains unsigned; this was not production signing or independent scanner approval.
