# Yono Engine untuk Android

Paket sumber mesin **Yono Engine 1.0.0**, untuk diintegrasikan ke **Yono Music** oleh Codex. Pembuat aplikasi: **Karisyono**. Ini bukan APK, AAR siap pakai, atau perubahan UI aplikasi.

Mesin membawa alur CLI: baca playlist/daftar → cari lagu lokal → cocokkan rilisan katalog → tinjau hasil → unduh pilihan → validasi audio → tulis tag/sampul → simpan dalam subfolder playlist. Memutar musik lokal tidak memerlukan Python atau jaringan.

**Mulai dari [INTEGRATE_CODEX.md](INTEGRATE_CODEX.md).** Saran tampilan ada di [UI_SUGGESTIONS.md](UI_SUGGESTIONS.md); Codex penerima menentukan tata letaknya.

## Isi paket

| Bagian | Isi |
| --- | --- |
| `src/main/python/yono_core/` | Parser, pembaca playlist, pencocokan rilisan, indeks duplikat, kontrol pekerjaan, pemrosesan audio, dan laporan |
| `src/main/python/yono_engine_api.py` | API JSON untuk Chaquopy |
| `src/main/kotlin/com/yonzmusic/engine/` | Klien asinkron, penyimpanan SAF, pemindaian folder pilihan |
| `requirements-android.lock` | Versi paket Python yang dipakai untuk pengujian host |
| `gradle-integration.snippet.kts` | Konfigurasi untuk digabung ke modul app yang sudah ada |
| `tools/build_android_audio.py` | Resep build FFmpeg + LAME dari sumber yang ditinjau; belum dijalankan dengan NDK di sini |
| `tests/`, `verification/` | Pengujian sebenarnya, log, dan hasil mesin yang dapat dibaca komputer |
| `reference/Spoti_Termux_1_2_1.zip` | CLI asli sebagai acuan, tidak dieksekusi/ditanam sebagai terminal |

## Batas yang harus diketahui sebelum build

- **Belum 100% setara CLI di Android.** Lihat [FEATURE_PARITY.md](FEATURE_PARITY.md). Tidak ada klaim semua platform/semua lagu berhasil.
- Playlist YouTube/YouTube Music dan pencarian daftar teks menggunakan `ytmusicapi`; transfer menggunakan `yt-dlp`. Implementasinya ada, tetapi unduhan YouTube langsung belum diuji dalam paket ini.
- Spotify dibaca sebagai metadata melalui API dengan token yang disediakan aplikasi. Tidak ada login/token tertanam. Pembaca publik eksperimental `SpotipyFree` dari CLI tidak dibundel; menempel tautan Spotify tanpa integrasi/token tidak dijanjikan langsung berhasil.
- MP3 memerlukan **FFmpeg Android dengan `libmp3lame`**; konversi MP3 nyata sudah diuji di Linux. Tidak ada biner Android siap pakai di ZIP. Format asli dapat dipakai dengan validator Android, tetapi dukungan tag/sampul mengikuti kontainernya.
- QuickJS/EJS harus dibundel saat build. Repo aplikasi yang diperiksa sudah memiliki sumber dan resep QuickJS; gunakan kembali setelah ditinjau. Instalasi runtime atau plugin dari internet di HP tidak diperlukan.
- Kotlin/SAF/Chaquopy, build native NDK, unduhan layanan langsung, dan Xiaomi HyperOS **belum diuji di lingkungan ini**. Tidak ada Android SDK, emulator, Gradle, atau compiler Kotlin di sini. [TEST_REPORT.md](TEST_REPORT.md) membedakan hasil host dari hal yang belum diuji.

## Coba pengujian host

Dengan Python 3.12, FFmpeg, FFprobe, dan Node yang sudah tersedia:

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements-android.lock
.venv/bin/python tools/verify_host.py
```

Runner menambahkan direktori sumber sendiri dan menulis `verification/host-results.json` serta `verification/host-tests.log`. Uji audio membuat sinus 4 detik sendiri; bukan mengunduh rekaman komersial. Pengujian daftar 110 lagu memakai metadata buatan, bukan klaim unduhan 110 lagu langsung.

Paket ini memakai GPL-3.0-or-later. Pertahankan sumber, LICENSE, dan pemberitahuan dependensi ketika mendistribusikan aplikasi. Lihat [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
