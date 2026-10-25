# Kesetaraan dengan CLI 1.2.1

Status “diimplementasikan” berarti kode ada. Kolom pengujian menjelaskan bukti sebenarnya; bukan jaminan semua layanan/perangkat berhasil. Paket ini belum dinyatakan 100% setara CLI.

| Kemampuan | Implementasi dalam paket | Bukti / batas |
| --- | --- | --- |
| Tempel Artis - Judul, daftar AI, numbered/Markdown | Parser portable + resolver katalog | Unit test, kolaborator dan tanda hubung artis |
| TXT/CSV/JSON/spotdl | Content parser dengan hitungan valid/invalid | Unit test; Android harus membaca teks SAF lalu mengirim isi |
| Tautan YouTube/YT Music satu lagu | Metadata single; watch+list tetap single | Fixture; belum uji extraction langsung Android |
| Playlist tetap YouTube/YT Music lengkap | `get_playlist(limit=None)`, cocokkan jumlah sebelum audio | Fixture kecil/50/110; live belum diverifikasi |
| Radio/mix dinamis | Ditolak dengan alasan, tidak dipotong jadi daftar palsu | Unit test |
| Spotify playlist sebagai metadata | Adapter API bertoken + pagination lengkap | Fixture 110 entri/3 halaman, repeated page dan count berubah; tidak ada token/live API diuji |
| Spotify publik tanpa konfigurasi seperti CLI | **Belum setara** | SpotipyFree/spotapi eksperimental tidak dibundel; adapter alternatif harus ditinjau dan diuji sendiri |
| Metadata platform lain | yt-dlp flat/lazy, kemudian resolver katalog yang sama | Pilihan yt-dlp diuji; situs tertentu belum diuji langsung; total tak diketahui diberi `unverified` |
| Pencarian otomatis dari nama lagu | `ytmusicapi.search(filter="songs")` + verifikasi | Fixture cocok/tidak cocok/ambigu; bukan pencarian video bebas |
| Hindari cover/live/remix yang berbeda | Judul, seluruh kredit artis, versi, durasi, explicit; `get_song` ATV | Unit test; metadata bukan fingerprint akustik atau verifikasi lisensi |
| Duplikat walau nama file berbeda | ID/ISRC + durasi, metadata tepat, SHA-256 + binding riwayat | Rename, hilang tag, file hilang/berubah, ISRC berbeda diuji |
| Mengenali semua audio tanpa tag | **Tidak dijanjikan CLI maupun engine** | Tanpa tag, ID, hash yang dikenal, atau metadata yang dapat dibuktikan, tidak menebak dari nama semata |
| MP3 128/192/256/320 kbps | FFmpeg `libmp3lame`; validasi sebelum/sesudah tag | Konversi/seek/decode dan durasi host; runtime NDK belum dibangun |
| Format asli | Transfer standalone audio, validator native/FFprobe | M4A host teruji; kontainer/perangkat lain perlu uji Android |
| Album art tertanam | Mutagen MP3/M4A/FLAC/Ogg; JPEG/PNG terbatas | MP3 dan M4A nyata host teruji; WebM tidak didukung penulisan tag/sampul |
| Perbaiki sampul tanpa unduh audio | Baca URI lokal → salinan privat → tag → publikasi salinan aman | Audio packet hash MP3 tetap sama dalam uji host; **bukan** retag atomik file asli |
| Folder per playlist/TXT/tempel | Subfolder nama playlist, file Artis - Judul | Store host teruji; SAF belum diuji pada emulator/perangkat |
| Lagu bersama beberapa playlist | Satu file/URI; keanggotaan Room oleh aplikasi | Dedup fixture teruji; integrasi Room harus dikerjakan penerima |
| M3U/M3U8 | Input relative-path snapshot pilihan + URL; output urutan URI | Unit test; ekspor `content://` tidak universal di pemutar lain |
| Progres, cancel, timeout | Event bertahap, token kooperatif, socket timeout, penghentian FFmpeg | Unit test deadline/reader/child-process; DNS/SAF/native tidak dijamin hard-stop seketika |
| Retry + laporan tahan interupsi | JSON atomik per entri, status pending, receipt publikasi | Unit test cancel/tail/receipt; crash/provider SAF perlu uji Android |
| Resume byte `.part` lintas proses | **Belum setara** | Workspace dibersihkan setelah putaran; retry entri, bukan melanjutkan byte parsial |
| Organize/names koleksi lama | **Tidak dimigrasikan otomatis** | CLI asli disertakan sebagai referensi; butuh preview dan migrasi SAF eksplisit |
| OAuth UI/refresh token | **Milik integrasi app, belum disertakan** | Token diinjeksi saat operasi; tidak disimpan mesin |
| Tanpa Termux | Python internal melalui Chaquopy, Kotlin/SAF/Media3 native | Source bridge ada; kompilasi dan runtime Android belum diuji di sini |
| Pembaruan melalui GitHub Release | Tidak diubah paket mesin | Pertahankan sistem updater aplikasi yang sudah ada; tidak ada remote engine ZIP execution |

Limit keselamatan: masukan 2 MiB; 5000 entri; audio 256 MiB per file; dokumen metadata 4 MiB per respons; gambar 1 MiB; pekerjaan maksimum 2 jam. Batas harus menghasilkan error/pause yang terlihat. Tidak ada batas 50 entri yang diam-diam dianggap hasil lengkap.
