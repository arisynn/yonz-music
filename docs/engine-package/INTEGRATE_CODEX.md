# Instruksi untuk Codex penerima

Integrasikan **kode mesin yang benar-benar ada dalam ZIP ini** ke repo `arisynn/yonz-music`. Jangan mengganti tugas menjadi rencana, hanya label unduhan, atau katalog Commons saja. Rancang penempatan UI sendiri, tetap sederhana sesuai `UI_SUGGESTIONS.md`.

## 1. Periksa keadaan repo

Paket disiapkan terhadap commit `6ad0d2fdd6f95a68360e62492c67f94ec673d634`. Periksa HEAD aktual dan perubahan pengguna sebelum menggabungkan. Jangan menimpa pekerjaan baru. Baca `README_MULAI.md`, `FEATURE_PARITY.md`, `ANDROID_INTEGRATION.md`, `TEST_REPORT.md`, dan kode sumber terlebih dahulu.

File lama yang relevan pada commit tersebut:

- `app/src/main/python/yono_engine.py`
- `app/src/main/java/com/yonzmusic/app/download/EmbeddedEngine.kt`
- `app/src/main/java/com/yonzmusic/app/MusicViewModel.kt`
- `app/src/main/java/com/yonzmusic/app/ui/EmbeddedAcquisition.kt`
- Worker akuisisi, repository Room, registry folder pilihan, dan `app/build.gradle.kts` yang tersedia dalam repo.

Masalah kode lama: `inspect()` meminta yt-dlp mengekstrak tautan playlist tanpa mode flat, lalu `source_record()` menolak playlist setelahnya. Konfigurasi juga memuat batas 50. Pembatalan coroutine UI tidak otomatis menghentikan panggilan Python. Paket baru memisahkan pembacaan daftar dari validasi/transfer tiap lagu.

## 2. Integrasi kode, satu mesin

1. Salin isi `src/main/python/` ke `app/src/main/python/`. Modul baru bernama `yono_engine_api` dan `yono_core`; jangan menimpa nama modul lama secara buta.
2. Salin Kotlin ke source set aplikasi. Paket `com.yonzmusic.engine` sengaja terpisah dari kelas lama.
3. Gabungkan snippet Gradle ke **modul Chaquopy yang sudah ada**, salin lock dependensi, dan gabungkan `consumer-rules.pro` ke aturan release.
4. Alihkan satu alur impor/unduhan ke klien baru. Jangan menjalankan worker lama dan baru untuk pilihan yang sama. Setelah pemindahan, hapus/putuskan jalur UI mesin lama yang menjadi duplikat, tanpa menghapus riwayat atau file musik.
5. Playback tetap memakai satu MediaSession/Media3 yang sudah ada. UI tidak mengimpor Python saat startup atau saat pengguna hanya memutar musik lokal.

## 3. Selesaikan runtime Android

- Pertahankan sumber/resep QuickJS repo yang sudah ditinjau; pastikan `libqjs.so` tersedia untuk kedua ABI dan EJS dari lock dibundel. Jalankan doctor dan uji solver yang relevan pada perangkat/emulator.
- Untuk keluaran MP3 seperti CLI, bangun FFmpeg + LAME Android dari sumber resmi yang diverifikasi, menggunakan resep `tools/build_android_audio.py` sebagai awal. Ikuti `native/README.md`, periksa dependensi ELF dan 16 KB alignment. Resep ini belum diuji dengan NDK di sini.
- Jangan mengirim opsi MP3 aktif bila encoder/runtime belum lolos pengujian. API menolak lebih awal sebelum mengunduh jika FFmpeg belum disediakan. Tidak boleh mengganti ekstensi WebM/Opus menjadi MP3.
- Jangan mengunduh kode Python, plugin, EJS, atau biner executable baru dari jaringan pada runtime aplikasi. Pembaruan mesin masuk lewat build APK.

## 4. Sambungkan data aplikasi

- Gunakan hanya folder/file yang dipilih pengguna lewat SAF. Tidak ada pemindaian seluruh MediaStore saat izin diberikan, saat startup, atau saat unduhan selesai.
- Snapshot folder pilihan masuk lewat operasi `snapshot`. Pisahkan `scope` per sumber. Pemindaian yang dibatalkan/gagal tidak boleh mengganti snapshot sebelumnya.
- Setelah hasil `downloaded`, masukkan URI dan metadata lengkapnya langsung ke Room; jangan memindai seluruh storage. Playlist Room menunjuk lagu yang sudah ada, termasuk yang berada di folder lain.
- Indeks SQLite di mesin hanya sidecar akuisisi/deduplikasi. Room tetap sumber utama library, favorit, queue, riwayat, dan playlist aplikasi.
- UI membaca event `job_id`, `stage`, `completed`, `total`, `entry`, dan hasil akhir. Abaikan event pekerjaan lama. `Cancel` harus memanggil `Task.cancel()` dan menampilkan “Membatalkan…” sampai worker benar-benar selesai.
- Pekerjaan panjang harus dipegang repository/WorkManager foreground yang sesuai, bukan lifecycle sebuah Composable. Simpan pilihan, ID rencana, dan tujuan SAF. Ikuti kebijakan foreground service Android untuk versi target yang sebenarnya.
- Spotify: hubungkan adapter API yang diizinkan/token pengguna jika hendak ditawarkan; tangani kedaluwarsa dan scope. Jangan menaruh client secret/token di APK atau repo. Tanpa integrasi ini, berikan pesan jelas/ekspor teks; jangan mengaku tautan Spotify sudah didukung sepenuhnya.

## 5. Verifikasi sampai hasil nyata

Jalankan host tests lalu build/lint/test aplikasi. Uji di emulator dan perangkat jika tersedia:

1. Tempel teks dan tautan satu lagu, playlist tetap kecil, 50, serta 110 entri. Jumlah dibaca harus cocok; jumlah dilewati/gagal tetap dihitung.
2. Lakukan uji layanan langsung dengan rekaman yang pengunduhannya diizinkan. Keberhasilan fixture tidak membuktikan extraction YouTube langsung.
3. Pilih sumber/destinasi SAF, cabut grant, disk penuh/provider gagal, nama bentrok, unduhan dibatalkan, app ditutup, dan retry. Jangan menimpa file lama.
4. Ganti nama/pindah file dalam sumber pilihan lalu rescan sumber tersebut. Hash/riwayat harus mencegah unduhan ulang bila identitas masih dapat dibuktikan.
5. Validasi durasi penuh, seek mendekati akhir, tag/sampul, dan playback nyata format yang disimpan. Pastikan sampul tidak memotong audio.
6. Uji background/screen-off, proses mati, pemulihan tanda terima SAF, Kotlin bridge release dengan R8, kedua ABI, serta font besar/tampilan kecil.
7. Pastikan tidak ada batas 50 tersisa pada entry point, ViewModel, worker, repository, dan UI lama. Batas keselamatan baru 5000 entri harus memberi error jelas, bukan hasil sebagian.

Perbaiki kegagalan integrasi sebelum menyebut fitur siap. Jika tooling tidak tersedia, nyatakan tepat apa yang belum diuji. Jangan menciptakan APK, screenshot, hash, signing status, hasil Play Protect, atau hasil unduhan yang tidak benar-benar ada.

Selesaikan UI/build APK dalam repo, dengan branding **Yono Music**, pembuat **Karisyono**, logo final pengguna, serta navigasi yang sudah disetujui. Pertahankan updater GitHub dan penandatanganan yang sudah ada. Paket ini tidak mengubah versionCode, kunci signing, atau merilis apa pun dengan sendirinya; ikuti instruksi rilis pengguna pada sesi aplikasi tersebut.
