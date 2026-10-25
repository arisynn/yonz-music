package com.yonzmusic.app.download

/** Actionable bounded wording without private URLs, cookies, Python traces or misleading legal claims. */
object SourceFailure {
    fun describe(error: Throwable): String {
        val message = error.message.orEmpty().lowercase()
        return when {
            "requested title" in message || "artist credits" in message || "recording/version" in message -> "The requested title, complete artist credits, version or duration differs. This candidate was skipped."
            "individual source" in message -> "Implicit platform-playlist expansion is not implemented. Use explicit source links or an authorized TXT/CSV/JSON export."
            "spotify" in message -> "Spotify music URLs provide metadata, not a full MP3 download. Use an authorized TXT/CSV/JSON export and separately permitted audio."
            "download authorization" in message -> "The service's download authorization is required as well as content-owner permission."
            "certificate" in message || "ssl" in message -> "The source's TLS certificate could not be verified. No insecure connection was used."
            "403" in message -> "The source denied access (HTTP 403). No access bypass was attempted."
            "404" in message -> "The source is missing (HTTP 404). Check the link."
            "drm" in message -> "The source is DRM protected. It was not downloaded."
            "ffmpeg" in message || "requested format" in message -> "No compatible standalone audio format was obtained. Video conversion/transcoding is not implemented in this build."
            "private" in message || "premium" in message || "sign in" in message -> "The source requires restricted access. This build does not import login cookies or bypass access controls."
            "cancel" in message -> "Cancelled by you. Retry restarts the transfer safely."
            "timed out" in message || "resolve" in message || "network" in message -> "The source could not be reached. Check your connection and retry."
            "unsafe" in message || "public https" in message || "credential" in message -> "Use a public HTTPS link without embedded credentials or private-network addresses."
            else -> "Extraction failed for this source. Review the link, source permission and available audio format; other extractors may need additional integration."
        }
    }
}
