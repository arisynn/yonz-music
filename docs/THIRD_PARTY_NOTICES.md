# Dependencies and source directions — 1.1.0

Yono Music is by Karisyono. First-party source is GPL-3.0-or-later; the owner-supplied YONO artwork is credited to Karisyono. Upstream components retain their own copyright/license notices. The About dialog includes the complete license texts in `docs/licenses/`, also bundled as `res/raw/third_party_notices.txt`. No software license grants rights to third-party music or trademarks.

| Component | Pinned/runtime version | License and source |
|---|---|---|
| AndroidX/Compose/Material/Media3/Room/DataStore/WorkManager | Gradle configuration and resolved report | Apache-2.0; https://android.googlesource.com/platform/frameworks/support/ and https://github.com/androidx/media |
| Kotlin/coroutines | 2.1.20 / 1.10.2 | Apache-2.0; https://github.com/JetBrains/kotlin and https://github.com/Kotlin/kotlinx.coroutines |
| apksig | 8.9.1 | Apache-2.0; https://android.googlesource.com/platform/tools/apksig/ |
| Chaquopy | 17.1.0 | MIT; https://github.com/chaquo/chaquopy/tree/17.1.0 (includes Android runtime build recipes/patches) |
| CPython | 3.12.15 | PSF-2.0 and bundled notices; https://github.com/python/cpython/tree/v3.12.15 |
| yt-dlp | 2026.10.21 | Unlicense; https://github.com/yt-dlp/yt-dlp/tree/2026.10.21 and https://pypi.org/project/yt-dlp/2026.10.21/#files |
| yt-dlp-ejs | 0.8.0 | MIT; https://github.com/yt-dlp/ejs/tree/0.8.0 and https://pypi.org/project/yt-dlp-ejs/0.8.0/#files |
| Mutagen | 1.48.1 | GPL-2.0-or-later, selected GPL v3; https://github.com/quodlibet/mutagen and https://pypi.org/project/mutagen/1.48.1/#files |
| certifi CA data | 2026.10.19 | MPL-2.0; https://github.com/certifi/python-certifi and https://pypi.org/project/certifi/2026.10.19/#files |
| QuickJS-NG | 0.17.0 | MIT; unmodified engine source and license included in `native/quickjs`; exact upstream/archive hash in `native/UPSTREAM.md` |

CPython/Chaquopy include native OpenSSL, SQLite, libffi, zlib, bzip2, liblzma, libexpat and decimal runtime code under their upstream licenses; CPython's LICENSE retains the included terms and Chaquopy's target recipes identify the native sources. Final runtime version/advisory details will accompany the completed verification report. The fixed custom QuickJS runner/build glue is first-party GPL-3.0-or-later; the upstream engine remains MIT.

Material rounded vector sources retain the original Apache headers. Test-only JUnit is EPL-1.0 and the unit-test org.json dependency uses the JSON license; neither test runner is part of the production APK. Gradle/AGP/KSP/NDK/CMake/host FFmpeg are build/test tools, not installed device-side engines.

Sources are available through the repository and exact version directions above, independently of APK use. The source archive includes build recipes, native source, first-party Python/Kotlin, tests/resources and license texts. Gradle/Chaquopy retrieve the pinned upstream dependencies during a clean build. No private key or proprietary runtime from the old ZIP is distributed.

Runtime probe on API 29: CPython 3.12.15, OpenSSL 3.5.9, SQLite 3.53.4, Expat 2.8.5. zlib 1.2.11 is reported by the emulator platform libz, not a vendored replacement; device system libraries/security patches remain Android responsibilities. The OSV query covered the exact Maven/PyPI inventory, not a comprehensive native-library CVE scan. Native source/license build directions are retained in Chaquopy 17.1.0. Service player JavaScript may be processed inside the restricted QuickJS sandbox; it has no JS filesystem/network/process modules. No downloaded native/runtime executable is used.


ONLINE SEARCH PREVIEW — SERVICE ASSETS
The unmodified Buy on iTunes Store badge is an Apple service mark used under the Apple marketing guidelines, not relicensed under the application GPL. Preview audio is provided courtesy of iTunes; clips link directly to their store items and are not exported or matched to other-service downloads.
https://www.apple.com/itunes/marketing-on-itunes/identity-guidelines.html
https://performance-partners.apple.com/search-api
YouTube content plays through the official visible iframe and remains subject to YouTube service/embedding terms; no YouTube media/runtime is bundled.
https://developers.google.com/youtube/terms/required-minimum-functionality
Commons result content retains its own displayed source license; app licensing does not replace that license.
