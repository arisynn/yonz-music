package com.yonzmusic.app.playback

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

/** Codes cross the session boundary; exception bodies/URLs never do. */
enum class StreamIssue(val text:String,val retryable:Boolean=false) {
    NETWORK("Koneksi terputus. Periksa jaringan lalu coba Putar lagi.",true),
    TIMEOUT("Sumber terlalu lama merespons. Coba lagi.",true),
    SERVER("Sumber sedang bermasalah. Coba lagi sebentar.",true),
    ACCESS("Sumber menolak akses audio. Coba lagi nanti atau pilih sumber lain."),
    UNAVAILABLE("Rekaman sudah tidak tersedia dari sumber ini."),
    RATE_LIMIT("Sumber membatasi permintaan. Tunggu sebelum mencoba lagi."),
    FORMAT_CHANGED("Format audio berubah. Tekan Putar untuk memulai ulang dengan aman."),
    FORMAT("Format audio ini belum dapat diputar. Coba rekaman lain."),
    SEEK("Sumber tidak melayani posisi audio ini dengan benar. Putar ulang dari awal."),
    TLS("Koneksi aman ke sumber gagal. Periksa tanggal perangkat dan jaringan."),
    UNSAFE("Alamat atau pengalihan audio tidak diizinkan."),
    CANCELLED("Pemutaran dibatalkan."),
    SOURCE("Audio belum dapat dibaca dari sumber. Coba lagi atau pilih rekaman lain.");

    companion object {
        fun http(status:Int)=when(status) {
            401,403->ACCESS;404,410->UNAVAILABLE;429->RATE_LIMIT
            408,500,502,503,504->SERVER;416->SEEK;else->SOURCE
        }
        fun from(error:Throwable?,cancelled:Boolean=false):StreamIssue {
            if(cancelled)return CANCELLED
            var cause=error
            repeat(12) {
                when(cause) {
                    is StreamFailure->return (cause as StreamFailure).issue
                    is SocketTimeoutException->return TIMEOUT
                    is SSLException->return TLS
                    is InterruptedIOException->return CANCELLED
                }
                cause=cause?.cause
            }
            return if(error is IOException)NETWORK else SOURCE
        }
        fun retryDelay(issue:StreamIssue,attempt:Int):Long? =
            if(issue.retryable && attempt in 1..2)attempt*1000L else null
    }
}
class StreamFailure(val issue:StreamIssue,val retryAllowed:Boolean=issue.retryable):IOException(issue.text)
