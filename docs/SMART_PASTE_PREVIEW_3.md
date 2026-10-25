# Yono Music 1.2.0 Preview 3 — input tempelan

Perubahan melanjutkan Preview 2, bukan memulai ulang proyek. Rencana produk berikutnya ada di [NEXT_UPDATE_PLAN.md](../NEXT_UPDATE_PLAN.md). Pembuat Karisyono; logo, native playback, sumber pilihan, format audio asli dan sampul dipertahankan.

## Perilaku yang diimplementasikan

`SmartSongListField` digunakan Downloads dan Files & playlists dengan satu `PasteListFormatter` Kotlin yang tidak menggunakan jaringan. Kolom utama 2–3 baris; daftar panjang dapat diedit dalam dialog. Pemulihan pemeriksaan lama tersedia saat input kosong agar tidak memenuhi halaman ketika sedang menyiapkan daftar baru. Pengetikan satu karakter mempertahankan kursor. Komposisi IME tidak diformat. Pemformatan otomatis berlaku pada bulk edit (termasuk paste dan penggantian teks); callback editor tidak dapat membedakan clipboard paste dari setiap saran keyboard yang melakukan bulk commit.

Contoh `Artis - judul lagu Artis - judul lagu` otomatis mendapat newline di antara dua entri. Artis lain yang ada di pustaka pilihan dapat digunakan sebagai batas. Pemisah semicolon/pipe/bullet dan penomoran berurutan dirapikan hanya jika setiap bagian berisi pasangan artis–judul. Dua URL dalam satu baris dipisah hanya jika seluruh baris terdiri dari URL dan whitespace.

Pemformatan adalah saran heuristik, bukan pembuktian rekaman. Tanda hubung tidak menunjukkan dengan pasti kapan sebuah judul selesai dan artis berikutnya dimulai. Artis berbeda yang belum dikenal tetap dipertahankan dengan petunjuk menambahkan enter atau `;`. Penyebutan artis di kolaborasi, dalam tanda kurung atau dalam kutip tidak dipakai sebagai batas otomatis. Hasil tetap terlihat/dapat diedit sebelum pencocokan dan unduhan; tidak ada unduhan otomatis saat menempel.

`Urungkan` mengembalikan teks tempelan sebelum format terakhir, selama teks belum diedit lagi. Riwayat undo bersifat sementara selama sesi editor aktif, bukan riwayat lintas rotasi/proses. Hasil format idempotent. JSON, CSV dengan header dikenali, dan M3U dipertahankan; deteksi JSON membedakan tautan Markdown dari array JSON. URL/path/query bertanda tangan tidak ditulis ulang. BOM/CRLF pada teks biasa dinormalisasi. Batas pemformatan otomatis: 64 Ki karakter per bulk edit, dan 4096 karakter per segmen tanpa pemisah jelas; input besar dipertahankan dengan petunjuk, bukan dipotong. Batas impor 2 MiB dan batas entri engine tetap berlaku seperti sebelumnya.

Nama artis dipakai hanya dari database pustaka aktif yang dipilih pengguna; tidak ada scan penyimpanan atau pencocokan online ketika menempel. File yang dipilih tidak ditulis ulang. Tidak ada dependency/permission baru. OAuth Spotify dan pembacaan Adele Radio belum ditambahkan; rencana menjelaskan prasyarat dan batas aksesnya tanpa mengklaim dukungan yang belum ada.

## Pengujian aktual

- Build debug, uji JVM dan lint release lulus. **94 uji JVM**, termasuk **22 kasus formatter baru**, tanpa kegagalan/error.
- Kasus mencakup contoh pengguna, artis berulang/berbeda, aksara non-Latin, kolaborasi, judul dengan hyphen, tanda kurung/kutip, penomoran, pemisah eksplisit, URL bertanda tangan, Markdown, JSON/CSV/M3U, idempotensi, batas ukuran, bulk edit dan tidak menghapus bagian ambigu.
- **Emulator API 29 x86_64, lebar 320 dp:** tempel clipboard sungguhan melalui KEYCODE_PASTE menghasilkan tepat dua baris contoh pengguna; `Urungkan` mengembalikan teks awal; nama artis berbeda yang ambigu dipertahankan; pemisah semicolon menghasilkan dua baris.
- Dialog editor diuji dengan keyboard terbuka: tombol Selesai terlihat dan bounds-nya berada di atas area IME. Screenshot yang benar-benar telah dirender diperiksa secara visual untuk editor/keyboard dan font sistem 1,3. Halaman utama tetap ringkas, field tidak bertambah lebih dari tiga baris, dan mini-player tetap terpisah dari kontrol. Capture awal yang diambil sebelum frame/transisi selesai diganti setelah memverifikasi piksel judul telah dirender.
- Pemeriksaan clipboard awal juga menemukan callback selection/composition dapat membuang undo. Callback tanpa perubahan teks kini mempertahankan undo; rerun clipboard/undo berhasil. Pemeriksaan tidak menganggap tombol yang terpotong viewport saat IME terbuka sebagai data editor yang hilang.
- Bukti: `docs/verification/engine-1.2-preview3/` dan `docs/screenshots/engine-1.2-preview3/`. Tidak ada audit landscape/seluruh tema/font 1,5 atau perangkat fisik dalam pembaruan ini. Hasil Preview 2 tentang WebM/Opus adalah pengujian historis; pipeline audio tidak diuji ulang dalam perubahan input ini.

## Distribusi

APK Dev `com.yonzmusic.app.dev`, versionName `1.2.0-dev`, versionCode 5; minSdk 26, target/compile 35, arm64-v8a/x86_64. Standar debug signing yang sama dengan Preview 1/2. Preview 3 tidak mengganti latest stable 1.1.0 dan tidak menimpa rilis/tag lama. Tidak ada klaim pengujian HyperOS fisik, koneksi Spotify, unduhan langsung baru, build R8 penuh, atau Play Protect.

APK: `Yono_Music_v1.2.0_preview3_debug.apk`, **44970745 byte**, SHA-256 `8e7de40e45cbfa8faf776fe08bac1d32c41918c3b161a10ffe3dedf834b74fe9`. Signature Scheme v2 terverifikasi; sertifikat debug SHA-256 `3e8117db4ac9b9e71429136a4d687bdaec8a75f1c052ee7c153b6c62c2fda740`.
