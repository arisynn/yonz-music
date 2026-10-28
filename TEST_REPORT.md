# Yono Music 1.5.0-preview.2 — actual tests

Preview assembly/lint passed (0 errors,111 warnings).126 JVM tests passed with no failures/errors/skips.166 host Python tests passed with no failures/errors/skips, including10 new read-only search cases and the existing acquisition/network/import/metadata/audio fixture suite. Evidence: docs/verification/v1.5.0-preview.2.

## Android results — fixtures and real services are separate

Eight distinct Android API29 cases passed across the initial run and corrected-harness rerun. The initial eight-case run had seven passes and one fixture-setup failure: calling Python builtins.exec without an explicit globals dict from Java produced SystemError: frame does not exist before the search test executed. The test harness was corrected to supply explicit globals and the single failing case then passed on the same production APK (6.055s). The raw failure is retained. This is not described as one clean eight-case run.

The actual Android Python bridge then executed the read-only flat-search flow using a scoped extractor fixture, checked supplied artist/duration normalization, cancelled a deliberately slow fixture search and obtained the next result without acquisition lock blocking. The fixture was restored in finally. Room track count stayed unchanged. Another case checked optional private history, deduplication, URL exclusion and deletion of the owned obsolete API-key file. No fixture switch/API exists in production code. Source results in these cases are fabricated labelled QA metadata, not a live YouTube claim.

The seven initial passes cover history/key removal plus native shuffle traversal/repeat/media commands, queue move/remove/Undo, original local WAV playback/seek/audio focus/sleep timer, two updater NaN regressions, and the existing YonoEngineClient doctor/QuickJS/110-entry local prepare/snapshot flow. This also exercises the shared lazy Python startup change in the old acquisition bridge. Host audio fixtures are original generated tones; host FFmpeg is not installed in the APK.

**Live source search did not pass.** A real yt-dlp host query failed with a proxy CONNECT403. The separate opt-in Android live query failed with SOURCE_NETWORK (11.56s). The actual app UI showed bounded timeout/source-failure states and a retry action. No alternate network route, TLS bypass, login/cookies or anti-bot evasion was used. This environment cannot prove the extractor works on an ordinary user connection; this release remains an experimental preview. It must not be marketed as a verified stable global-search release. No live new audio download or in-app streaming playback is claimed.

## Collection and UI audit

Dev code10→11 was installed with adb install -r and the same existing Dev certificate. Exact rows remained equal across57 tracks,1 favorite,33 playlists,54 memberships,13 history records,61 sources and18 listens. Schema stays3. No uninstall, clear-data, broad-storage scan, user-file move/delete or migration reset was used. Private SQLite snapshots are not distributed.

Actual full-app screenshots were inspected on the320dp-wide API29 emulator: Search error/timeout states in Light/Dark, font1.0/1.5, keyboard and history-settings dialog. No unintended overlap was observed in these states; no user API-key field or provider dropdown remains. Live results/detail/download gestures could not be exercised against a reachable remote source; the bridge/parser fixture tests and source-code exact-link review do not pretend to be a live end-to-end download test. Original external listening and existing review remain the only controls; no fake replacement preview is shown.

Physical Android16/Xiaomi HyperOS, Bluetooth hardware and independent Play Protect classification remain unverified. The emulator uses the managed environment proxy and its CA in emulator-only system roots with normal TLS checks; the APK does not require root or a custom CA on users' devices. Historical preview1 verification is preserved separately in docs/HISTORICAL_1_5_0_PREVIEW_1_TEST_REPORT.md.
