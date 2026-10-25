# Security and privacy

## Design

No network permission or network features, analytics, ads, login, credentials, downloaders, shell/runtime execution, microphone, contacts, SMS, location, Accessibility Service, overlays, root features, or self-updater. Audio and document streams are opened read-only. Export writes only to a document the user explicitly creates. The app never deletes audio. Android backups are disabled so the private database and saved URI references are not automatically uploaded by Android backup.

Media access is scoped: READ_MEDIA_AUDIO on Android 13+, READ_EXTERNAL_STORAGE capped at API 32 on older Android, or user-selected SAF documents/folders with persisted read grants. Media3 uses WAKE_LOCK during active playback and releases it when paused; there is no permanent custom lock or background polling. Playback uses FOREGROUND_SERVICE and FOREGROUND_SERVICE_MEDIA_PLAYBACK and a mediaPlayback service type. Media-session notifications are exempt from Android 13's general notification permission requirement; no POST_NOTIFICATIONS permission is requested. The merged dependency manifest may include AndroidX's app-scoped signature permission protecting non-exported dynamic receivers and a profile-installer receiver protected by the system DUMP permission.

The launcher activity and media service are exported for Android launch/media integration. Session connections are accepted only for this package or controllers Android identifies as trusted. Incoming media IDs are resolved against indexed local content URIs; supplied remote URIs are never trusted. Media commands cannot make the app download, delete, or execute content. No custom app data provider is exported.

The import parser limits bytes/rows and never opens M3U file paths, follows playlist URLs, loads JSON code, or grants URI access from an imported list. SAF provider access is granted only through Android pickers. Read grants last until Android or the user revokes them. File bytes are not a source of executable code.

## Dependencies and release

Dependencies are pinned, obtained from Google's Maven repository/Maven Central/Gradle distribution hosts, and kept separate from the legacy ZIP. No legacy packages or binaries are reused. Room's schema is exported and no destructive database migration fallback is enabled. Release optimization removes unused code/resources and release debugging is disabled. The debug APK is intentionally debuggable; use an independently signed release for production distribution.

Dependency checks and static-analysis results are recorded in TEST_REPORT.md and docs/. Automated vulnerability database checks can miss unpublished or unindexed issues and are not a full code/dependency audit. A clean local lint/build does not guarantee security or device compatibility.

No production key is bundled. The development signing private key is excluded from delivery. Independently verify artifact SHA-256 and Android APK signatures before installing. App-data removal/uninstall clears collections and preference history, but leaves original music files untouched.

## Play Protect

No attempt is made to disable, bypass, suppress, or mislead scanners. Play Protect classification and Play Store review are external processes. No approval is claimed. Actual scanner status is stated in the final report; an unsigned optimized release is not an installable production release.

## Reporting

No hosted reporting endpoint or telemetry is embedded. Report reproducible issues to the project owner with OS/device version and steps, excluding personal audio, exported lists, or private URI values unless you intend to share them.
