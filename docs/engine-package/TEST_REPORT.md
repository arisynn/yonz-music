# Laporan pengujian nyata

Tanggal: **25 Oktober 2026, Asia/Jakarta**. Versi paket engine: 1.0.0. Versi aplikasi Android tidak diubah oleh paket ini.

## Hasil host

Runner: `tools/verify_host.py`. **114 tes dijalankan, 0 gagal, 0 error, 0 dilewati.** Hasil terakhir tercatat dalam `verification/host-results.json` dan log tiap tes di `verification/host-tests.log`. Angka ini adalah tes engine baru; tidak mengambil kredit dari 113 tes CLI lama.

Lingkungan yang benar-benar dipakai: Python 3.12.14, Linux x86_64, FFmpeg/FFprobe 6.1.1, Node v24.22.1, yt-dlp 2026.10.21, EJS 0.8.0, ytmusicapi 1.12.3, Mutagen 1.48.1, requests 2.34.2. Paket tersedia pada lingkungan host yang telah ada; tidak ada klaim fresh pip install atau build wheel Android telah diuji.

Yang diuji:

- Parsing TXT/paste/Markdown, CSV ekspor, JSON/spotdl, M3U, ID tautan, batas ukuran/jumlah dan metadata invalid.
- Pencocokan judul, seluruh artis, featuring, versi, durasi, explicit, penolakan cover/UGC/OMV, ambiguity dan pemilihan ulang terverifikasi.
- Playlist fixture kecil, 50 dan 110 entri; total lengkap sebelum validasi; Spotify fixture 110 entri melalui tiga halaman; halaman berulang dan jumlah berubah ditolak.
- Deduplikasi berdasarkan metadata, hash/riwayat setelah rename/hilang tag, berkas berubah/hilang, ISRC berbeda, snapshot scoped dan local-first tanpa akses katalog.
- Review/acquire, nama file/folder, duplikat daftar, konflik nama tanpa overwrite, tanda terima simpan, retry/tail interupsi, pemulihan status saving dan laporan atomik.
- Pembatalan kooperatif, deadline, progres berurutan dan throttled, pembatalan reader serta penghentian proses anak sungguhan.
- Penolakan HTTP/nonstandard port/credential/private IPv4/IPv6 menggunakan fixture resolver; redirect dan body bound Requests; konfigurasi flat/lazy yt-dlp tanpa batas 50/plugin/remote code; handler redirect tidak memodifikasi handler global.
- API JSON, busy, snapshot/offline prepare, pembatalan sebelum mulai, serta token tidak tersimpan pada laporan.
- **Audio sungguhan buatan sendiri:** sinus 4 detik dikonversi menjadi MP3 dengan FFmpeg, metadata dan sampul APIC diverifikasi dengan Mutagen, durasi diperiksa, dan audio didecode penuh dengan FFmpeg. M4A dengan cover juga diuji. Perbaikan sampul mempertahankan SHA-256 packet audio MP3, tanpa memanggil transfer audio.
- Doctor host benar-benar menjalankan encoder check dan JavaScript. Hasilnya tidak diklaim sebagai bukti ekstraksi situs.

## Belum dijalankan

| Pemeriksaan | Status dan sebab |
| --- | --- |
| Kotlin compile / Gradle lint / APK | Tidak ada Gradle, kotlinc, javac atau Android SDK di lingkungan ini |
| Chaquopy Android/pip wheel dan resource gettext | Source integrasi ada; perlu build serta runtime test pada penerima |
| FFmpeg/LAME NDK kedua ABI | Resep ada, source/biner tidak dibangun di sini |
| QuickJS/EJS Android | Jalur integrasi mengacu sumber/resep repo, tidak dibangun/diuji ulang di sini |
| Unduhan YouTube/YT Music langsung | Tidak dijalankan; fixture dan keberhasilan host bukan bukti koneksi/challenge/format situs |
| Spotify API langsung/OAuth | Tidak ada token pengguna; hanya fixture pagination/adaptor yang diuji |
| Situs lain | Tidak ada klaim kompatibilitas setiap extractor |
| SAF provider, grant dicabut, disk penuh, proses mati pada Android | Harus diuji dengan emulator/perangkat, termasuk recovery receipts |
| Room dan WorkManager/foreground integration | Pekerjaan integrasi aplikasi penerima, belum diubah oleh ZIP |
| Layout UI, keyboard, font besar | Tidak ada UI/APK baru dalam paket ini; saran desain saja |
| Perangkat Xiaomi HyperOS, Bluetooth, background | Tidak ada hardware/emulator di lingkungan ini |
| Play Protect / Play Store / audit kerentanan independen | Tidak dilakukan; tidak ada klaim lolos/aman mutlak |

## Temuan yang sudah diperbaiki pada mesin ini

Daftar tidak lagi dikirim ke inspector single-source yang menolaknya setelah ekstraksi berat. Pembacaan YT Music memakai mode daftar lengkap; metadata generik memakai flat/lazy. Tidak ada `playlistend=50` pada transport baru. Kasus page berulang/total berubah gagal jelas sebelum audio.

Pembatalan memiliki entry point sendiri dan token yang diperiksa provider/reader/konverter; bukan hanya menghilangkan indikator UI. Namun DNS/SAF/native tetap mempunyai batas kooperatif yang dijelaskan pada ANDROID_INTEGRATION.md, bukan jaminan hard-stop.

Sampul ditulis melalui Mutagen dan diuji tidak memotong audio. Pengujian tidak mengunduh rekaman komersial; metadata buatan diberi label fixture pada source tes. `FakeMedia` hanya untuk tes orkestrasi, tidak menjadi fallback produksi.

Kesimpulan teknis: core host teruji dan sumber integrasi tersedia; **paket bukan APK siap pakai dan belum diverifikasi berjalan di Android atau 100% setara CLI**. Selesaikan pemeriksaan yang belum dijalankan sebelum rilis aplikasi.
