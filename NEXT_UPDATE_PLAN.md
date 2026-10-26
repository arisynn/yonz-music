# Rencana pembaruan Yono Music

Pemilik produk: Karisyono. Rencana awal berasal dari 1.2.0 Preview 2 dan kini dilanjutkan oleh 1.3.0; playback native, identitas YONO, pustaka dari sumber pilihan, format audio asli, koleksi lokal, dan signing tetap dipertahankan. Prioritas: fungsi, keamanan data, stabilitas, kejelasan UX, lalu tampilan.

## Status implementasi 1.3.0

- Input pintar Preview 3 tetap dipertahankan.
- Rekonsiliasi relokasi/hash dan status lokasi, migrasi database 3, metadata/sampul portabel dan editor tanpa audio ulang sudah diimplementasikan.
- Daftar unduhan persisten, batal per item, retry dengan urutan playlist tetap, Home ringkas dan Smart Mix lokal yang dapat dimatikan sudah diimplementasikan.
- Rincian serta hasil pengujian yang benar-benar tersedia ada di [laporan pembaruan](docs/UPDATE_1_3_0.md) dan [TEST_REPORT](TEST_REPORT.md).
- Masih menjadi prioritas pengujian lanjutan: perangkat HyperOS/Android modern, proses OS mematikan transfer aktif, penyimpanan fisik dilepas, pustaka sangat besar, dan unduhan HTTPS berizin langsung pada rilis yang dioptimalkan. OAuth Spotify tetap membutuhkan konfigurasi developer resmi; belum tersedia.

## 1. Input daftar pintar — Preview 3

Implementasi pada pembaruan ini:

- Satu komponen input dipakai Downloads dan Files & playlists. Kolom utama maksimal tiga baris; editor lengkap dibuka dalam dialog, bukan memperpanjang halaman.
- Saat tempel/bulk edit, pisahkan artis yang berulang dan nama artis yang sudah ada di pustaka pilihan. Contoh `Adele - Hello Adele - Skyfall` menjadi dua baris. Artis berbeda yang belum dikenal tidak ditebak dari beberapa kata terakhir judul.
- Pemisah `;`, `|`, bullet dan penomoran berurutan dapat dirapikan jika setiap potongan berisi pasangan artis–judul. Dua URL yang memenuhi seluruh baris dapat dipisah tanpa mengubah isinya.
- Pertahankan judul/versi, karakter non-ASCII, kolaborasi artis, tanda hubung dalam judul, URL bertanda tangan, JSON/CSV/M3U, dan urutan daftar. Jangan menghapus baris yang belum cocok.
- Beri ringkasan singkat, `Urungkan` untuk format otomatis terakhir, dan peringatan jika batas lagu masih ambigu. Pratinjau/edit tetap diperlukan sebelum pencocokan/pengunduhan; format bukan identifikasi rekaman.
- Pengetikan satu karakter dan komposisi IME tidak memicu pemformatan. Pemformatan otomatis dibatasi untuk menjaga responsivitas; daftar besar tetap dapat diimpor, tidak dipotong diam-diam.

Kriteria penerimaan: contoh pengguna terpisah menjadi dua baris, urutan/karakter tidak hilang, judul bertanda hubung tidak dipecah sembarang, undo mengembalikan teks tempelan, input dapat diedit dengan keyboard, dan halaman download tetap ringkas pada 320 dp.

## 2. Kejelasan sumber dan koneksi Spotify

Temuan pengujian langsung sebelumnya: tautan Adele Radio valid, halaman/oEmbed memberi HTTP 200, API tanpa token memberi 401, dan APK berhenti pada SPOTIFY_AUTH_REQUIRED. Koneksi OAuth memang belum diimplementasikan. Pengenalan URL tidak sama dengan dukungan membaca isi playlist.

Langkah berikutnya:

1. Bedakan di UI: koneksi belum dikonfigurasi, token kedaluwarsa, izin playlist ditolak, sumber tidak tersedia, dan gangguan jaringan. Tampilkan langkah yang memang tersedia; jangan menyuruh pengguna membuka pengaturan login yang belum ada.
2. Rancang koneksi resmi OAuth PKCE hanya setelah Client ID, redirect URI, dan akses aplikasi Spotify Developer tersedia. Jangan membundel client secret/token atau mengambil kredensial/cookie web. Persyaratan pemilik akun developer dan batas pengguna harus dijelaskan sebelum mengandalkan layanan ini.
3. Uji playlist milik/kolaborasi pengguna dengan akses resmi. Batas isi playlist mengikuti izin dan kebijakan API. Menambahkan login tidak menjamin radio/editorial atau setiap playlist publik dapat dibaca.
4. Pertahankan impor ekspor TXT/CSV/JSON/M3U dan pencocokan audio lokal sebagai jalur offline. Metadata Spotify tidak menyediakan unduhan audio Spotify. Sumber audio harus terpisah dan diizinkan.

Referensi resmi: [Get Playlist Items](https://developer.spotify.com/documentation/web-api/reference/get-playlists-items), [PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow), [perubahan Development Mode](https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide). Ini rencana, bukan klaim fitur sudah tersedia.

## 3. Antrean, sumber asli dan sampul

- Uji ulang satu unduhan HTTPS yang diizinkan pada perangkat, lengkap dengan progres, batal, retry, proses dihentikan, pemulihan antrean, dan playback hasil.
- Bedakan jelas hasil fixture dari unduhan langsung. Evaluasi perubahan extractor secara terpisah dari izin sumber/akses konten.
- Tingkatkan pemulihan sampul yang gagal tanpa mengunduh ulang audio. Pertahankan WebM dengan sampul pendamping dan embedding Opus yang sudah bekerja. Jangan menambahkan konversi MP3.
- Uji duplikasi berdasarkan identitas sumber/hash dalam sumber pilihan dan riwayat aplikasi; jangan memperluas scan ke seluruh penyimpanan.

## 4. Kandidat stabil setelah 1.3.0

- Audit ringkas 320 dp, font 1.3–1.5, tema terang/gelap, keyboard, dialog, rotasi dan landscape. Perbaiki temuan sebelum menyebutnya lulus.
- Jalankan pengujian pipeline engine pada build release dengan R8, izin notifikasi/foreground service di Android modern, serta instalasi pembaruan produksi yang mempertahankan koleksi 1.1.0.
- Minta hasil uji perangkat HyperOS untuk layar mati, headset/Bluetooth, interupsi audio dan pembatasan baterai. Emulator tidak membuktikan perilaku perangkat fisik.
- Gunakan identitas signing produksi yang sudah dicadangkan. Jangan mengganti kunci, menimpa tag/release lama, atau mengubah kanal stable sebelum pemeriksaan tersedia selesai. Publikasikan APK, source, checksum dan laporan aktual. Play Protect tetap proses eksternal; tidak ada janji persetujuan.

Tidak ada tanggal rilis yang dijanjikan sebelum integrasi, akses layanan, dan pengujian yang relevan tersedia.
