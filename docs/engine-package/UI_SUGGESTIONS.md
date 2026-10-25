# Saran tampilan — Codex bebas merancang posisi

Nama aplikasi **Yono Music**, pembuat **Karisyono**, open source. Gunakan logo final yang diunggah pengguna dan palet **mist blue**. Pengguna menyukai UI yang tenang, sedikit elemen, dan langsung ke tujuan.

Pertahankan navigasi **Home | Library | Search | Playlists** yang sudah disetujui. Jangan membuat tab mesin/plugin baru atau menggandakan layar unduhan. Sesuaikan permukaan impor yang sudah ada.

## Satu alur utama

1. Judul **Unduh musik**.
2. Satu kolom **Tempel tautan atau daftar lagu**. Contoh singkat: `Artis - Judul`, satu baris per lagu. Tidak perlu deretan logo/platform atau jargon mesin di sini.
3. Aksi sekunder **Pilih berkas** untuk TXT/CSV/JSON/M3U. Setelah memilih berkas, tampilkan nama berkas dan jumlah entri nyata.
4. Baris tujuan yang ringkas: `Simpan ke: Music / Nama playlist`, dengan tombol ubah folder SAF. Memilih tujuan tidak berarti menyetujui pemindaian seluruh folder tersebut.
5. Tombol utama **Periksa daftar**.

Label masukan boleh netral. Asal tautan tetap tersedia pada review/rincian agar pengguna memahami sumber dan kegagalan. Jangan menyembunyikan proses jaringan, membuat dukungan platform palsu, atau menganggap label netral mengubah izin sebuah sumber. Bawa persetujuan/ketentuan yang memang diperlukan aplikasi secara ringkas dan mudah dipahami; jangan memenuhi layar dengan istilah implementasi.

## Progres yang menjelaskan apa yang terjadi

| Tahap nyata | Contoh teks UI |
| --- | --- |
| Sumber belum memberi jumlah | `Membaca daftar…` + indikator tak tentu |
| Daftar lengkap | `110 lagu ditemukan` |
| Mencocokkan | `Memeriksa 18 dari 110` + judul lagu saat ini |
| Menunggu batas layanan | `Menunggu sebentar…` + waktu bila tersedia |
| Transfer audio | `Mengunduh 7 dari 96` + progres byte bila total diketahui |
| Konversi | `Menyiapkan audio…` |
| Menulis tag/sampul | `Menambahkan sampul…` |
| Penyimpanan | `Menyimpan…` |
| Batal ditekan | `Membatalkan…` sampai operasi benar-benar berhenti |

Hindari spinner tanpa penjelasan. Jangan menampilkan `0/0` atau `100%` untuk tahap yang totalnya belum diketahui. Satu pekerjaan aktif; tombol baru tidak aktif selama pembatalan belum selesai.

## Review singkat

Tampilkan nama playlist dan hitungan **Siap**, **Sudah ada**, **Perlu dipilih**, dan **Belum cocok** berdasarkan status nyata. Baris lagu berisi judul, artis, durasi, serta status; sampul kecil hanya jika tersedia. Rincian alasan dapat dibuka, bukan memenuhi setiap baris.

Pilihan utama otomatis mencakup entri `ready`, kemudian **Unduh N lagu**. Entri `exists`/`duplicate` dipakai sebagai referensi playlist tanpa mengunduh atau menyalin audio lagi. Untuk `ambiguous`, bottom sheet **Pilih rekaman** menampilkan pilihan dari API `choose`; jangan menambahkan tombol yang belum tersambung.

Di layar hasil: `Tersimpan 96 · Sudah ada 10 · Belum cocok 4` hanya jika angkanya sesuai hasil. Sediakan **Coba yang gagal lagi** dan **Lihat rincian**. Jangan menyebut semua lagu berhasil ketika masih ada yang tertunda/tidak tersedia.

## Pengaturan dan fitur sekunder

- Nama **Yono Engine** boleh berada di Pengaturan → Tentang/Diagnostik. Hapus label produk lama seperti “Spoti engine · inside Yono”.
- Format MP3 hanya ditawarkan aktif setelah runtime/encoder benar-benar tersedia. Jika format asli tidak dapat menyimpan sampul, jangan menampilkan janji sampul tertanam.
- Perbaikan sampul dalam paket ini membuat **salinan aman** di folder `Sampul diperbaiki`. Jelaskan dampak ini sebelum menjalankannya; jangan mengaku mengubah file asli di tempat.
- Koleksi hanya berasal dari folder/file pilihan. Tombol **Tambah folder** dan **Pindai ulang folder ini** harus jelas; tidak ada auto-scan WhatsApp, game, atau seluruh internal storage.
- Pertahankan mini-player di atas navigasi dengan ruang bawah daftar yang cukup. Uji keyboard, layar sempit, dan font besar setelah integrasi; paket mesin ini tidak mengklaim sudah mengaudit UI aplikasi.
