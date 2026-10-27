# Yono Music 1.5.0-preview.1 — actual verification

126 JVM tests passed, zero failures/errors/skips; preview assembly and lint passed (0 errors, 115 warnings). Seven Android instrumentation cases passed in one API29 emulator run. Evidence and raw failure history: docs/verification/v1.5.0-preview.1. Stable 1.4.2 remains the official channel.

## Live service versus fixtures

The opt-in live Android case queried the real official iTunes API for Adele / All I Ask, selected its actual catalogue result, rendered the real audition sheet and streamed/decoded the provided AAC preview through native Media3. isPlaying, positive duration and advancing position were checked. No preview audio was exported or inserted into Room. The test first played an original generated local WAV at a nonzero seek position, then checked that preview paused it, retained its queue, local Play stopped the preview, and leaving the foreground stopped audition without automatically resuming local audio. The private configuration/history/provider-isolation case used fixtures; it does not claim live Google API access.

The other Android cases covered real MediaSession shuffle playback, queue movement/removal/Undo, original local WAV decoding/background playback and two updater NaN regressions. The initial run failed because the emulator lacked validated Wi-Fi and a queue test inherited shuffle-on state. Wi-Fi was connected normally and that test's manual-order precondition was made explicit. The subsequent standalone live case and complete seven-case run passed. These failures are retained, not presented as successes.

The 13 new JVM cases cover debounce without a submit action, stale-response cancellation, cache TTL, request budget/retry, pagination, source switching/clear, URL/host restrictions, provider metadata separation, optional preview, channel credit, duration and local accent/word-order matching. They supplement the existing 113 cases.

## Upgrade and collection safety

`adb install -r` upgraded the existing same-certificate Dev package from code9 to preview code10. Room remains schema3. Exact rows were retained across 57 tracks, 1 favorite, 33 playlists, 54 memberships, 13 history entries, 61 selected sources and 18 listen records. No uninstall/clear-data was used. Only the verification summary is published; private database snapshots are excluded. Official production and Dev collections are separate; official users can install the preview beside official without migrating their official collection.

## Environment and scope

API29 software emulator, x86_64. The managed test environment uses its ordinary outbound proxy; its CA was installed only in the test emulator's trusted roots, retaining TLS verification. No production trust override, permissive certificate handler, root requirement or debug network config was added to the APK. The emulator was restarted after a managed-session interruption; userdata was preserved. Earlier build attempts exhausted a constrained heap; the final single-worker 1024MiB build passed.

No real YouTube API key was available, so live native YouTube text search is unverified. No claim of physical HyperOS/Android16, Bluetooth hardware, Play Protect certification, general streaming-service download coverage or a newly exercised Commons transfer is made. Existing Python/native acquisition dependencies and implementation were unchanged; their historical suites were not rerun. Apple-to-YouTube matching/downloading is intentionally excluded under the cited preview promotional-use terms.

The seven-case suite ran before final UI-only polishing. The final UI audit repeated automatic real iTunes search and actual native clip playback on the polished APK, without a submit action. Dark Search with keyboard open, the clip sheet and the YouTube failure fallback were captured. The emulator uses WebView74: its official YouTube iframe did not become ready; the18s fallback removed the black view and exposed a usable external-source button. No successful embedded YouTube playback is claimed. The dedicated audit and remaining measured limitations are recorded in the evidence directory. Historical stable tests remain in docs/HISTORICAL_1_4_2_TEST_REPORT.md.

Final visual audit:320dp width, actual app Always dark/Always light themes (not merely system theme), font1.0/1.5, keyboard open/hidden. The initial double IME inset was removed, the source picker condensed into one row, and large-type action/badge layout corrected after screenshots exposed word splitting. Final screenshots show readable actions and no unintended overlap in the inspected Search states. The last resource/UI build also includes updated privacy/service notices. It passed preview lint and was installed in place and visually checked; the126-test JVM run predates these final UI-only changes. No every-screen or physical-device visual audit is claimed.
