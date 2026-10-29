# Bahasa Indonesia · 1.5.2

Yono Music memakai bahasa Indonesia untuk teks antarmuka aplikasi, termasuk navigasi Beranda / Pustaka / Cari / Daftar putar, label tema dan pengurutan, dialog, pemutar, antrean, unduhan, impor, pengaturan dan pembaruan. Teks aksesibilitas tombol ikut disesuaikan. Label navigasi daftar putar dapat membungkus ke dua baris pada font besar.

`i18n/Bahasa.kt` memetakan label kode tersimpan dan255 pesan aplikasi/riwayat ke teks Indonesia **hanya saat ditampilkan**. Tema tetap menyimpan System/Dark/Light, pengurutan tetap Title/Artist/Album/Recently added/Duration, dan status impor/unduhan/updater tetap menggunakan kode lama. Referensi per sumber, status, JSON/CSV/header protokol, metadata dan migrasi database tidak diganti. Nama lagu, artis/album terisi, judul koleksi pengguna, nama berkas/folder dan isi sumber tidak diterjemahkan; hanya label bawaan untuk metadata yang belum tersedia yang dilokalkan.

`BahasaLocale` menyiapkan konteks sumber daya Indonesia untuk aplikasi, aktivitas dan layanan pemutaran, sehingga kontrol Material/Media3 dan format tanggal menggunakan bahasa aplikasi. Android13+ juga diberi konfigurasi bahasa per aplikasi melalui LocaleManager. Jika pencatatan bahasa/konfigurasi notifikasi OEM belum tersedia, fallback konteks tetap dipakai dan aplikasi tetap dibuka. Bahasa sistem HP tidak diubah. Nama kanal notifikasi milik Yono disesuaikan dengan ID yang sama tanpa mereset kepentingan, suara, getaran atau lencana pengguna.

Dialog pemilih berkas, pemasang Android, keyboard dan pengaturan sistem mengikuti Android/IME pengguna. Nama merek, format/identitas teknis, konten sumber dan teks lisensi asli dipertahankan. Diagnostik sumber yang tidak dikenali tetap ditampilkan apa adanya; aplikasi tidak menerjemahkan atau menebak isi sumber secara daring. Tidak ada layanan penerjemahan, izin tambahan, perubahan signing, konversi audio atau penulisan ulang koleksi.

Uji aktual dan batasannya: TEST_REPORT.md serta docs/verification/v1.5.2. Pengujian pada perangkat fisik HyperOS/Android16 belum dilakukan oleh agen build.
