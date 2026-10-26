# Yono Music 1.4.2 — shuffle and Home artwork

Karisyono · com.yonzmusic.app · versionCode9 · Room schema3. Existing owner production and Dev signing identities are retained. Repository: https://github.com/arisynn/yonz-music .

Shuffle now creates a new cycle beginning with the current song, so every other queued occurrence remains reachable without restarting current audio. Queue rows follow the authoritative Media3 playback order; tapping/removing a row still addresses its correct native occurrence. Turning shuffle off retains physical queue order. A new selected queue also begins its shuffled cycle at the requested track; edits to the same queue do not continuously reshuffle it.

The Home card crossfades between existing local covers every six seconds, with a 600ms fade and no tap action. It does not select/play songs or fetch artist/artwork online. Missing covers, a single cover, reduced motion, background/hidden state and large-font layout are handled with restrained static fallbacks.

SDK min26, target/compile35; JDK17, Gradle8.11.1, AGP8.9.1, Kotlin2.1.20, Media3 1.6.1; arm64-v8a/x86_64. No permission, dependency, acquisition/audio-format, database schema or signing changes. Permissions: INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS, WAKE_LOCK, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK, FOREGROUND_SERVICE_DATA_SYNC, REQUEST_INSTALL_PACKAGES.

Actual tests and their limits are documented in TEST_REPORT.md and docs/verification/v1.4.2. No production key/password/token is included. Final artifact sizes, hashes, signing verification and source commit accompany distribution. Existing official users install over the same app; do not uninstall or clear data. Physical HyperOS and Play Protect remain independently unverified.

Verified before release assembly:113 JVM tests,5 distinct Android cases (with the documented animation-profile rerun), debug assembly/lint and actual screenshot audit. Final release build/signature results are measured in the distribution report.
