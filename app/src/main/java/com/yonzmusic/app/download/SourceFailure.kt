package com.yonzmusic.app.download

/** Actionable bounded wording without private URLs, cookies, Python traces or misleading legal claims. */
object SourceFailure {
    fun describe(error: Throwable): String {
        val message = error.message.orEmpty().lowercase()
        return when {
            "requested title" in message || "artist credits" in message || "recording/version" in message -> "Judul, kredit artis lengkap, versi, atau durasi berbeda. Kandidat ini dilewati."
            "individual source" in message -> "Pembacaan otomatis daftar putar melalui jalur ini belum diterapkan. Gunakan tautan sumber langsung atau ekspor TXT, CSV, atau JSON yang diizinkan."
            "spotify" in message -> "Tautan Spotify menyediakan metadata, bukan unduhan MP3 lengkap. Gunakan ekspor TXT, CSV, atau JSON yang diizinkan dan sumber audio terpisah yang boleh diunduh."
            "download authorization" in message -> "Selain izin pemilik konten, layanan sumber juga harus mengizinkan pengunduhan."
            "certificate" in message || "ssl" in message -> "Sertifikat TLS sumber tidak dapat diverifikasi. Koneksi yang tidak aman tidak digunakan."
            "403" in message -> "Sumber menolak akses (HTTP 403). Pembatasan akses tidak dilewati."
            "404" in message -> "Sumber tidak ditemukan (HTTP 404). Periksa tautannya."
            "drm" in message -> "Sumber dilindungi DRM dan tidak diunduh."
            "ffmpeg" in message || "requested format" in message -> "Tidak ditemukan format audio mandiri yang sesuai. Versi ini tidak mengonversi video menjadi audio."
            "private" in message || "premium" in message || "sign in" in message -> "Sumber memerlukan akses terbatas. Versi ini tidak mengimpor kuki masuk atau melewati pembatasan akses."
            "cancel" in message -> "Dibatalkan olehmu. Coba ulang untuk memulai unduhan kembali dengan aman."
            "timed out" in message || "resolve" in message || "network" in message -> "Sumber tidak dapat dihubungi. Periksa koneksi lalu coba lagi."
            "unsafe" in message || "public https" in message || "credential" in message -> "Gunakan tautan HTTPS publik tanpa kredensial atau alamat jaringan privat."
            else -> "Sumber belum dapat dibaca. Periksa tautan, izin sumber, dan format audio yang tersedia. Beberapa sumber mungkin memerlukan integrasi tambahan."
        }
    }
}
