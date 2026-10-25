# Kontrak Android

## Konfigurasi build

Repo yang diperiksa memakai AGP 8.9.1, Kotlin 2.1.20, Chaquopy 17.1.0, Python 3.12, JDK 17, minSdk 26, compileSdk/targetSdk 35, arm64-v8a dan x86_64. Ini adalah baseline repo yang diamati, **bukan klaim paket ini sudah dikompilasi pada kombinasi itu**. Gunakan snippet untuk menggabungkan perubahan, bukan mengganti seluruh build.gradle.

Kotlin membutuhkan API Chaquopy, `androidx.annotation.Keep`, dan coroutines/StateFlow yang sudah dimiliki app. `ytmusicapi` harus dapat mengakses resource gettext-nya; `extractPackages("ytmusicapi")` disertakan pada snippet. Lock memakai paket pure Python tanpa `curl-cffi`, browser, plugin, atau Termux.

Python 3.12 dipilih agar sama dengan repo dan pengujian host. ABI 32 bit tidak disediakan. Jangan menyatakan dukungan universal perangkat Android.

## Tujuan dan sumber SAF

Pemilih tujuan menggunakan `ACTION_OPEN_DOCUMENT_TREE`. Ambil grant baca/tulis dengan `takePersistableUriPermission()` hanya sesuai flag yang benar-benar diberikan. Simpan URI di DataStore. `SafDocumentStore` menerima URI tersebut; tidak memakai `/storage/emulated/0` atau `MANAGE_EXTERNAL_STORAGE`.

Untuk sumber, gunakan URI pilihan terpisah. Panggil `SelectedFolderScanner.scan()` di `Dispatchers.IO` atas tindakan impor/rescan pengguna. Ia mengembalikan snapshot lengkap atau melempar error jika traversal gagal; file audio yang tidak terbaca dicantumkan. Jangan masukkan snapshot dari traversal yang dibatalkan.

Scanner menghitung SHA-256 file dan membaca metadata native. Nama file menjadi judul fallback, bukan bukti identitas lagu. Kredit artis yang tidak dapat dibaca secara lengkap bisa menyebabkan lagu tidak terdeduplikasi melalui metadata; riwayat hash tetap membantu untuk hasil unduhan yang pernah diindeks.

## Penggunaan klien

```kotlin
val nativeDir = File(context.applicationInfo.nativeLibraryDir)
val storage = SafDocumentStore(context, selectedDestinationUri)
val client = YonoEngineClient(
    context, storage,
    YonoEngineClient.RuntimePaths(
        quickJs = File(nativeDir, "libqjs.so"),
        ffmpeg = File(nativeDir, "libyono_ffmpeg.so"),
        ffprobe = File(nativeDir, "libyono_ffprobe.so"),
    ),
    accessToken = { authorizedSpotifyAccessTokenOrEmpty() },
)

// Run scanner on IO only when the user explicitly imports/rescans this source.
val snapshot = SelectedFolderScanner(context).scan(selectedSourceUri)
val indexed = client.begin("snapshot", JSONObject()
    .put("scope", selectedSourceUri.toString()).put("rows", snapshot.rows))
check(indexed.result.await().getBoolean("ok"))

val task = client.begin("prepare", JSONObject()
    .put("kind", "auto")
    .put("content", pastedTextOrLink)
    .put("name", chosenPlaylistName), timeoutSeconds = 900)
// Collect task.events with the owner's lifecycle; repository owns the task itself.
val prepared = task.result.await()
// Show the real entries and let the user choose ready rows.
// If needed, call task.cancel(); await completion before allowing a new engine call.
```

Kode contoh membutuhkan coroutine caller serta `org.json.JSONObject`/`java.io.File`. Jangan menyalin token atau helper contoh sebagai nilai hardcoded. Untuk format asli, boleh memakai `RuntimePaths(quickJs=...)` tanpa FFmpeg/FFprobe; validator native akan digunakan, dengan batasan kontainer di bawah.

Setelah review:

```kotlin
val request = JSONObject().put("plan_id", planId)
    .put("entry_ids", JSONArray(selectedReadyEntryIds))
    .put("format", "mp3").put("bitrate", 192)
val download = client.begin("acquire", request, timeoutSeconds = 7200)
val result = download.result.await()
// Import completed result entries into Room directly using their content:// URI.
```

`Task.result.await()` tidak otomatis menggantikan pembatalan engine ketika lifecycle caller berakhir. Gunakan repository pemilik pekerjaan dan panggil `Task.cancel()` pada aksi pengguna/worker stopped. Jangan hanya membatalkan coroutine penunggu.

## Operasi JSON

Semua operasi menghasilkan `{"ok":true,"result":...}` atau `{"ok":false,"error":{"code","message","retryable"}}`. `plan_id` juga dikembalikan bila pembatalan terjadi setelah rencana tersimpan. `config` dibuat oleh Kotlin; jangan mencampurkannya dengan JSON dari impor pengguna.

| Operasi | Payload | Hasil |
| --- | --- | --- |
| `doctor` | `{}` | Versi paket; kemampuan MP3/JS nyata, bukan bukti dukungan situs langsung |
| `snapshot` | `scope`, `rows` | Indeks folder pilihan diperbarui |
| `prepare` | `kind`, `content` atau `url`, opsional `name` | Rencana lengkap untuk review |
| `get_plan` | `plan_id` | Rencana/laporan tersimpan |
| `get_last` | `{}` | Rencana terakhir yang sempat tersimpan |
| `choose` | `plan_id`, `entry_id`, `youtube_id` dari pilihan ambigu | Pilihan diperiksa ulang dan disimpan |
| `acquire` | `plan_id`, `entry_ids`, `format`, opsional `bitrate` | Unduhan hasil pilihan; file yang sudah ada dilewati |
| `retry` | `plan_id` | Rencana review baru untuk entri gagal/tertunda/belum cocok |
| `repair_artwork` | `plan_id`, `entry_id` dengan URI lokal | Salinan bersampul di folder `Sampul diperbaiki`, tanpa unduh audio ulang; file awal tetap utuh |
| `export_m3u` | `plan_id` | Teks M3U sesuai urutan playlist |

`prepare.kind`: `auto`/`url`, `text`/`txt`, `csv`, `json`/`spotdl`, `m3u`/`m3u8`. Android membaca berkas teks SAF maksimal 2 MiB, decoding UTF-8/BOM, lalu mengirim **isinya**, bukan raw path storage. M3U lokal dicocokkan ke `relative_path` persis dari root sumber pilihan; tidak membuka jalur `../` di filesystem atau URI file asing.

Untuk menerima metadata Spotify, aplikasi menyediakan token akses API pengguna yang berlaku. Engine tidak mengimplementasikan UI OAuth/refresh. Adapter Python `Sources(..., spotify_client=...)` juga tersedia untuk provider metadata yang disetujui, dengan protokol `playlist`, `playlist_items`, `next`; tidak diekspos sebagai pemuatan plugin dari jaringan.

## Status dan progres

Event: `job_id`, `sequence` meningkat, `stage`, serta bidang sesuai tahap. Tahap meliputi `reading_source`, `reading_playlist`, `playlist_ready`, `matching`, `verifying`, `item_result`, `review`, `track_started`, `downloading`, `validating_audio`, `converting`, `artwork`, `saving`, `finished`.

`downloading` memiliki `downloaded_bytes`, `total_bytes` bila diketahui, `speed`, dan `eta`; jangan menggambar persen palsu ketika total belum ada. `reading_source.request_number` adalah jumlah permintaan, **bukan jumlah lagu atau nomor halaman yang selalu tepat**. Setelah pembacaan playlist selesai, `playlist_ready.total` adalah jumlah entri yang akan diperiksa.

Status entri: `pending`, `ready`, `exists`, `duplicate`, `ambiguous`, `unmatched`, `invalid`, `failed`, `saving`, `downloaded`. Jumlah seluruh status harus sama dengan total. Status rencana mencakup `preparing`, `review`, `paused_network`, `cancelled`, `timeout`, `downloading`, `complete`, `complete_with_errors`. `complete` hanya berarti putaran pilihan selesai; gunakan counts untuk memberi hasil yang jujur.

## Penyimpanan, pemulihan dan playlist

- Hanya file final dalam workspace privat yang dapat dipublikasikan. Host memeriksa SHA-256 sesudah penyalinan. Nama bentrok mendapat `(2)`, `(3)`, dan seterusnya.
- SAF provider tidak menjanjikan transaksi rename atomik lintas semua provider. Dokumen baru bisa terlihat selama penyalinan; engine tidak memasukkannya ke Room sampai verifikasi selesai. Tidak ada jaminan atomic filesystem global.
- Tanda terima AtomicFile menyimpan URI yang dibuat engine. Retry memulihkan publikasi yang sudah cocok hash; sisa parsial milik pekerjaan tersebut dapat dihapus saat retry. `unfinishedReceipts()` tersedia untuk review pemulihan, bukan penghapusan massal file pengguna.
- Riwayat sidecar bertahan setelah rename jika snapshot sumber baru mengandung SHA-256 yang sama. Snapshot baru tidak menghapus binding lama. Hash file berubah jika ditag ulang aplikasi lain; jangan menjanjikan fingerprint akustik.
- Lagu yang sudah ada di folder lain dipakai kembali oleh URI. Semua keanggotaan playlist disimpan dalam Room; satu lagu tidak disalin untuk setiap playlist. Folder fisik playlist berisi audio baru yang benar-benar diunduh pada impor tersebut.
- M3U memakai URI `content://` di Android. Aplikasi lain mungkin tidak mempunyai grant; ekspor portabel untuk pemutar lain membutuhkan pemetaan/copy yang dipilih pengguna, belum otomatis disediakan.
- Mesin tidak memindahkan/merapikan seluruh koleksi lama secara otomatis. Gunakan log CLI dan preview migrasi eksplisit bila fitur itu nanti ditambahkan.

## Batas pembatalan dan runtime

Control memeriksa pembatalan/deadline sebelum/sesudah panggilan katalog, setiap permintaan/chunk jaringan, progres yt-dlp, dan proses konversi. Proses FFmpeg dihentikan saat batal; QuickJS repo memiliki batas runtime sendiri. Socket metadata memakai connect hingga 8 detik/read hingga 12 detik dan retry terbatas. Setelah tiga kegagalan jaringan beruntun, sisa daftar tetap tertunda.

Pembatalan tetap kooperatif. DNS sistem, panggilan provider SAF, dan beberapa operasi native pihak ketiga tidak bisa dijamin berhenti seketika oleh coroutine/Python. Jangan mengubah UI menjadi idle sebelum worker selesai. Jika produk mensyaratkan penghentian keras dengan waktu mutlak, integrator harus memindahkan pekerjaan ke service proses `:yono_engine` dan menguji IPC/pemulihannya; pemisahan proses itu belum disertakan dalam paket ini.

Native validator tanpa FFprobe memakai MediaMetadataRetriever. Sebagian perangkat melaporkan gambar sampul sebagai video; jika terjadi, jangan mematikan seluruh validasi, gunakan FFprobe/validator track-level yang diuji. WebM dapat diputar tetapi Mutagen tidak menulis metadata/sampulnya; hasil mengembalikan `tags_embedded=false` dan `artwork_embedded=false`. Mode MP3 memberi tag/sampul tertanam bila artwork valid tersedia.
