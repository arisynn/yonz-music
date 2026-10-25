# Third-party notices

AndroidX, Jetpack Compose, Material icons, Room, DataStore, and Media3 are Android Open Source Project libraries under Apache License 2.0. Kotlin and kotlinx.coroutines are JetBrains projects under Apache License 2.0. Guava/AndroidX transitive runtime dependencies include their own license notices. JUnit (test-only) is Eclipse Public License 1.0; org.json (unit-test-only) has the JSON license.

The UI uses Material rounded icons. To avoid packaging the entire extended icon catalogue, only the needed extended icons are included as Kotlin vector sources in ui/icons. Source: Google's Maven artifact androidx.compose.material:material-icons-extended-android:1.7.8, commonMain sources, retrieved 2026-10-25. Copyright headers are retained. Package declarations are changed; mirrored variants are exposed on the local rounded icon namespace. These Material icons are licensed under Apache License 2.0 (APACHE-2.0.txt). Core icons remain a Maven dependency. Original Yon'z Music brand assets are separate original artwork.

Build-time dependencies (AGP, Gradle, Kotlin compiler, KSP, Room compiler) are not embedded development tools in the APK. Dependency coordinate/version evidence is provided in the release runtime dependency report.
