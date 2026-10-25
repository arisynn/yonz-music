# Audit dan migrasi logika

## Sumber yang diperiksa

- ZIP `Spoti_Termux_1_2_1.zip`, 63.472 byte, SHA-256 `89dcf50a24829e3bdd07310afa8b838028f9a50e449afd87b16b782392b8a56b`. Salinan byte-identik ada di `reference/`.
- Kode `spoti.py`, `spoti_sources.py`, `spoti_folders.py`, tes, dependensi, dan dokumentasi CLI. Tidak menjalankan install/update shell dari arsip.
- Repo `arisynn/yonz-music` pada commit `6ad0d2fdd6f95a68360e62492c67f94ec673d634`: modul Python bridge, EmbeddedEngine Kotlin, ViewModel impor, UI akuisisi, build Gradle, dan konfigurasi native QuickJS.

## Yang ditulis ulang/dipisahkan

- `norm`, `title_key`, `artist_keys`, versi rekaman, dan toleransi durasi CLI menjadi `model.py`. Pencocokan katalog dipisahkan dari input dan transport.
- `InputLoader` CLI menjadi parser konten dan `Sources`. Terminal prompt, print, akses path default Termux, login browser lokal, serta terminal menu tidak dimasukkan ke aplikasi.
- Pemilihan ATV katalog, seluruh artis dan judul/durasi mempertahankan pendekatan konservatif CLI. Teks dapat melengkapi kredit featured dari katalog bila nama artis utama sama; metadata yang sudah lengkap tidak boleh membuang kredit artis.
- Indeks file CLI menjadi sidecar SQLite berbasis URI/scope dan digest. Aplikasi mengirim snapshot folder pilihannya; tidak ada scan root storage.
- Loop unduhan/report menjadi rencana review persisten dan operasi acquire yang memilih ID entri. Angka total tidak disimpulkan dari 50 hasil pertama.
- FFmpeg hanya dipakai melalui jalur runtime yang diberikan build aplikasi. Mutagen menangani tag/sampul; tidak ada `-frames:v 1` yang berisiko memotong audio ketika menambahkan gambar.
- Host SAF menangani URI/grant, nama bentrok, checksum, serta receipt. Mesin tidak menduplikasi fungsi playlist/favorit/queue milik Room/MediaSession.

## Yang tidak disalin sebagai default

- SpotipyFree membawa dependensi web/cookie dan fallback internal yang tidak setara adapter API Android yang teruji. Pembaca itu tidak dibundel; API bertoken dan protokol adapter disediakan. Ini adalah perbedaan nyata dari CLI publik yang pernah dipakai pengguna.
- CLI Node/FFmpeg dari PATH, fcntl terminal, script paket Termux, dan token environment tidak ditanam ke APK. Runtime Android perlu build/pengujian sendiri.
- Pemindahan otomatis file lama, rename massal, dan penulisan tag di tempat ditunda. Mengubah URI/file lama di Android memerlukan preview serta strategi pemulihan provider; paket tidak mengambil risiko tersebut secara diam-diam.
- Plugin executable eksternal, cookie import, bypass akses/DRM, serta unduh kode engine dari internet tidak diimplementasikan. Mesin modular dibundel bersama APK.

Lihat FEATURE_PARITY.md untuk daftar perbedaan yang lengkap. Kode asli disertakan untuk peninjauan, bukan untuk dijalankan begitu saja pada Android.
