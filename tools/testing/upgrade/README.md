# Private production upgrade probe

Use only on a disposable emulator, with the owner's production signing identity. Never distribute the resulting same-signed instrumentation APK. It is deliberately separate from the app/normal artifacts.

Compile UpgradeProbe.java against android.jar, convert it with build-tools d8, link the manifest with aapt2, add classes.dex, zipalign and sign outside the repository. Install official1.5.2 plus the probe. `adb shell am instrument -w -e mode seed com.yonzmusic.upgrade/com.yonzmusic.upgrade.UpgradeProbe` seeds original fixture metadata in the actual baseline Room3 database. Install the candidate using `adb install -r`; run the same probe with `-e mode verify` to check Room4 and retained track/favorite/history/playlist/state identities. A failed probe must remain reported as a failure. It does not test persisted SAF grants, physical-device behavior or public streaming.

For the code-17 → code-18 update, use `snapshot-preview1` before installing Preview 2, then `verify-preview2` after `adb install -r`. The private same-signed probe checks schema 5 and compares SHA-256 snapshots of every row in tracks, favorites, history, playlists, playlist_entries and online_activity. Do not distribute this QA instrumentation APK.
