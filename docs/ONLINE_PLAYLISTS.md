# Playlist online — Yono Music 1.5.0

Di tab **Playlists**, pilih **Create**, isi nama dan aktifkan **Playlist online** untuk daftar buatan sendiri. Cari lagu di Search → Online, buka opsi ⋮ lalu **Playlist** untuk menyimpan ke daftar tersebut atau membuat daftar baru. Lagu yang sama tidak ditambahkan dua kali. Daftar online ditandai “online”; daftar lokal dan favorit tetap seperti sebelumnya.

Untuk tautan publik YouTube Music / YouTube: **Playlists → Link**, tempel tautan playlist tetap lalu **Baca daftar**. Tinjau nama, isi dan jumlah entri tidak tersedia/duplikat sebelum **Simpan**. Ini menyimpan snapshot berurutan, bukan sinkronisasi otomatis dengan akun sumber. Playlist privat, Radio/mix dinamis dan daftar akun seperti Watch Later tidak diambil. Batas500 entri per impor,100 playlist tersimpan; hasil terlalu panjang/tidak lengkap ditolak tanpa diam-diam dipotong.

Ketuk **Putar** atau salah satu lagu untuk memainkan seluruh daftar dari posisi pilihan dalam MediaSession yang sama. Antrean, shuffle, notifikasi dan latar belakang memakai player yang sudah ada. Opsi daftar menyediakan tambah lagu, ubah nama dan hapus dengan konfirmasi; opsi lagu menyediakan urut naik/turun, hapus referensi dan Unduh. Penghapusan playlist/referensi tidak menghapus audio lokal. Unduhan tetap memakai folder SAF tersimpan atau meminta konfigurasi jika belum ada izin yang sesuai.

Metadata disimpan di Room/AppState secara transaksional, tanpa menaikkan skema3. Daftar tampil setelah aplikasi dibuka kembali tanpa perlu membaca ulang sumber; pemutaran daring memerlukan jaringan. Ekspor koleksi menyertakan daftar online; restore memvalidasi referensi dan menambah salinan dengan ID baru tanpa menimpa daftar yang ada. URL audio bertanda tangan dan buffer streaming tidak disertakan dalam backup.

Persiapan latar belakang dibatasi pada antrean pemutaran aktif: analisis dua item berikutnya, cache URL maksimal8entri/2menit, serta target preload awal audio lagu berikutnya5detik melalui Media3. Ini tidak mengunduh seluruh history/playlist ke perangkat, tidak mengubah Library, dan tidak menjamin bebas buffering pada jaringan lambat atau sumber yang menolak akses. Tidak ada autoplay saat pemulihan proses.

Fitur Hubungkan Spotify, OAuth Client ID, callback dan token refresh dihapus sesuai keputusan produk. Kredensial lokal lama dan alias kuncinya dibersihkan; musik, playlist lokal, favorit dan ekspor metadata tidak dihapus. Engine yt-dlp bukan bukti bahwa setiap playlist Spotify dapat dibaca: gunakan ekspor TXT/CSV/JSON bila sumber tersebut memerlukan akses API. Aplikasi tidak mengklaim unduhan audio Spotify dari API metadata.

Cakupan pengujian aktual dan percobaan sumber langsung terdapat di TEST_REPORT.md. Fixture metadata tidak membuktikan semua playlist/sumber live tersedia. Izin penggunaan konten dan ketentuan sumber tetap berlaku; tidak ada login/cookies, DRM atau bypass pembatasan akses.
