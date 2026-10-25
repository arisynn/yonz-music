# Lisensi dan provenans

Sumber Yono Engine dalam paket ini: **GPL-3.0-or-later**. Lihat LICENSE. Dibuat untuk proyek Yono Music milik Karisyono, dengan adaptasi dari CLI yang disertakan dan pola transport repo aplikasi pada commit audit.

| Dependensi | Penggunaan / lisensi upstream |
| --- | --- |
| yt-dlp | Python extraction/transfer, Unlicense pada sumber; periksa lisensi distribusi spesifik sebelum membundel binary/extra |
| yt-dlp-ejs | Solver yang dibundel saat build; pertahankan lisensi paket yang benar-benar dipakai |
| ytmusicapi | Metadata katalog dan playlist, MIT |
| Mutagen | Metadata dan artwork, GPL-2.0-or-later; distribusi aplikasi gabungan harus kompatibel |
| requests, urllib3, idna, charset-normalizer, certifi | HTTP/TLS dan dependensi; pertahankan notices/lisensi masing-masing dari paket terkunci |
| Chaquopy | Runtime/Gradle plugin; ikuti lisensi dan ketentuan versi yang dipakai penerima |
| QuickJS-NG | Sumber native repo menggunakan MIT; wrapper Yono repo GPL-3.0-or-later |
| FFmpeg + LAME | Belum dibundel binernya; FFmpeg umumnya LGPL/GPL sesuai konfigurasi dan LAME LGPL. Simpan source/config/notices runtime yang benar-benar dibangun |

ZIP ini tidak menjual atau memberi lisensi atas musik/artwork dari layanan. Filter rilisan katalog memeriksa kecocokan metadata, bukan kepemilikan hak. Gunakan sumber yang pengunduhannya diizinkan. Tidak ada DRM bypass, cookie import, remote plugin execution, atau klaim persetujuan layanan/Play Protect.

Referensi primer dan audit:

- Repo aplikasi: https://github.com/arisynn/yonz-music/tree/6ad0d2fdd6f95a68360e62492c67f94ec673d634
- Chaquopy Android: https://chaquo.com/chaquopy/doc/current/android.html
- ytmusicapi playlist reference: https://ytmusicapi.readthedocs.io/en/stable/reference/playlists.html
- yt-dlp: https://github.com/yt-dlp/yt-dlp
- EJS/runtime: https://github.com/yt-dlp/yt-dlp/wiki/EJS
- Android SAF: https://developer.android.com/training/data-storage/shared/documents-files
- FFmpeg: https://ffmpeg.org/
- LAME: https://lame.sourceforge.io/

Sebelum rilis APK, periksa advisory dan lisensi versi dependensi yang benar-benar dikompilasi. Paket ini tidak menyertakan hasil vulnerability scanner atau penilaian Play Protect independen.
