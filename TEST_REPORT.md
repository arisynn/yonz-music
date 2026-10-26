# Yono Music 1.4.0 — actual verification

2026-10-26. Baseline 6f32eb7 (official 1.3.0, code 6). Current code 7, schema 3. Tests use AOSP API29 x86_64 software emulator, not physical HyperOS.

- 156 Python host tests passed with resolved pinned dependencies, including 14 new review/destination/hidden-cover/name/retry cases. An initial invocation without dependencies failed with missing modules; corrected invocation passed. Generated owned audio and fabricated metadata were used, not downloaded commercial music.
- 106 JVM tests passed, including RFC7636 S256/state checks.
- 15 distinct Android cases passed across regression and targeted recheck. First run: 13/15 passed. One SAF test incorrectly manufactured a new tree URI without a picker grant; corrected to the existing granted tree. A queue test exposed occurrence-key changes across the controller/service boundary; keys now survive authoritative resolution. Recheck: all four new cases passed. Raw first-run failures and successful recheck are retained in docs/verification/v1.4.0.
- Android checks cover original WebM bytes, .cover/.nomedia placement, hash-bound relative manifest, artwork reimport with recursive audio disabled, optional playlist/accepted subset, duplicate occurrence keys, move/remove/undo/new-queue protection, shuffle behavior, encrypted OAuth state/rejected/replayed callbacks, old relocation/cache/collection retry, migration and container checks. Reorder requires shuffle off.
- In-place Dev upgrade 6→7 retained all 39 tracks, 24 playlists, 39 memberships, 1 favorite, 10 history rows, 14 listens and 40 sources exactly at install. No uninstall or data clear. Evidence: upgrade-preservation.json.
- Live YouTube playlist metadata-only attempt returned CATALOGUE_NETWORK in this environment; no audio downloaded. No successful live Spotify login/API/refresh test: a configured Developer app/account was unavailable. Pagination/matching tests are fixtures; public/editorial/Radio access is not guaranteed by OAuth.

Visual evidence, release compilation/signature and final artifact measurements are recorded with the distribution report. Physical HyperOS, hardware Bluetooth/headsets, Android16, real gallery indexing, very large libraries and Play Protect classification were not verified for this version. Historical 1.3.0 verification remains in docs/HISTORICAL_1_3_0_TEST_REPORT.md.

Debug/release compilation and lint passed on the available toolchain. Visual audit used actual 320-dp emulator captures in dark/light and font150%; review action wrapping and partial sheet expansion were found and repaired. Final expanded-sheet/status-bar recheck is retained with final captures. No physical gallery claim is made.
