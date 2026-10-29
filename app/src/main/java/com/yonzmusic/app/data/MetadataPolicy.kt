package com.yonzmusic.app.data

object Availability {
    const val AVAILABLE = "available"
    const val MISSING = "location_missing"
    const val PERMISSION = "permission_lost"
    const val STORAGE = "storage_unavailable"
    const val UNREADABLE = "audio_unreadable"
    const val AMBIGUOUS = "ambiguous_location"
    const val ALIAS = "retained_alias"
    fun label(status: String): String = when(status) {
        AVAILABLE -> "Berkas tersedia"; MISSING -> "Lokasi lama tidak ditemukan · pilih lokasi baru"
        PERMISSION -> "Izin folder hilang · hubungkan ulang folder"
        STORAGE -> "Penyimpanan sementara tidak tersedia"
        UNREADABLE -> "Audio tidak dapat dibaca atau format belum didukung"
        AMBIGUOUS -> "Beberapa lokasi cocok · pilih lokasi yang benar"
        ALIAS -> "Referensi lama dipertahankan"; else -> "Periksa lokasi dan akses berkas"
    }
}

/** Field-by-field precedence. A filename is never identity evidence. */
object MetadataPolicy {
    data class Fields(val title: String = "", val artist: String = "", val album: String = "", val origin: String = "tags")
    fun known(value: String?): String = value.orEmpty().trim().takeUnless {
        it.lowercase() in setOf("", "<unknown>", "unknown", "unknown artist", "unknown album", "unspecified credits", "downloaded audio")
    }.orEmpty().take(512)
    fun fallback(fileName: String): Fields {
        val base = fileName.substringBeforeLast('.').trim()
        val parts = base.split(Regex("\\s+[-–—]\\s+"), limit = 2)
        return if (parts.size == 2 && parts.all { it.isNotBlank() }) Fields(parts[1].trim(), parts[0].trim(), origin="filename")
        else Fields(base.ifBlank { "Untitled audio" }, origin="filename")
    }
    fun choose(fileName: String, tags: Fields, verified: Fields? = null): Fields {
        val fallback = fallback(fileName)
        val trusted = verified?.takeIf { it.origin in setOf("source", "verified", "manifest", "user") }
        val priorFallback = verified?.takeIf { it.origin == "filename" }
        val actualTags = tags.takeUnless { it.origin == "filename" } ?: Fields()
        return Fields(known(trusted?.title).ifBlank { known(actualTags.title).ifBlank { known(priorFallback?.title).ifBlank { fallback.title } } },
            known(trusted?.artist).ifBlank { known(actualTags.artist).ifBlank { known(priorFallback?.artist).ifBlank { fallback.artist } } },
            known(trusted?.album).ifBlank { known(actualTags.album) }, trusted?.origin ?: if (known(actualTags.title).isNotBlank() || known(actualTags.artist).isNotBlank()) "tags" else "filename")
    }
    fun relocationCandidates(hash: String, tracks: List<Track>): List<String> =
        if (!hash.matches(Regex("[a-f0-9]{64}"))) emptyList() else tracks.filter { it.sha256 == hash && it.availability != Availability.ALIAS }.map { it.id }.distinct()
}
