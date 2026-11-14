# PocketShare - Audit Kode 1.0.5

Dokumen ini merangkum kondisi kode setelah upgrade 1.0.5 supaya pengembangan berikutnya tidak
perlu menebak-nebak. Fokusnya: peta modul, alur runtime, alur request publik, fitur baru, dan
utang teknis yang masih terbuka.

Status singkat (13 November 2026):

- `versionCode 15`, `versionName 1.0.5`, package `dev.pocketshare`.
- `:server:test --rerun-tasks` lulus 55 test; `:app:compileDebugKotlin` lulus.
- Lint terakhir: 0 error, 81 warning. Belum ada error kompilasi yang diketahui.
- Build release 1.0.5 sempat berhenti di `:app:minifyReleaseWithR8` karena koneksi klien Gradle
  terputus (`Broken pipe`), bukan karena kegagalan kode. Build ulang harus dijalankan tanpa
  memutus proses Gradle.
- Repo ini bukan git repository; semua perubahan hanya ada di working tree. Cadangkan sebelum
  eksperimen besar.

## 1. Peta modul

Arah dependensi: `app -> data, server, tunnel, core`, `data -> core`, `server -> core`,
`tunnel -> core`. `core` tidak bergantung pada Android.

| Modul | Isi | Tanggung jawab |
|-------|-----|----------------|
| `core` | 12 file, ~891 baris | Model share, port `ShareContentSource`/`ShareStore`, token & password, session signer, rate limiter, Range parser, `FileNames`, hostname validator, logger/redactor, `ImageTransformer` |
| `data` | 9 file, ~1.354 baris | Room (`shares`), DataStore settings, SAF content source, enkripsi secret dengan Android Keystore, file logger, binding Hilt |
| `server` | 9 file, ~4.879 baris | Ktor CIO, routing publik, keamanan request, streaming/Range/ZIP, renderer HTML, pipeline pratinjau dokumen |
| `tunnel` | 6 file, ~719 baris | Kontrak tunnel, perintah cloudflared, parser log, proses cloudflared, provider + runtime binary |
| `app` | 29 file, ~8.523 baris | Compose Material 3, ViewModel, `ShareRuntime`, foreground service, splash, changelog/What's New |

File paling padat: `server/PublicPageRenderer.kt` (~1.360 baris), `server/PocketShareModule.kt`
(~1.400 baris), `app/ui/screens/SettingsScreen.kt`, dan `app/runtime/ShareRuntime.kt`.

## 2. Alur runtime aplikasi

1. UI memanggil ViewModel (`HomeViewModel`, `SharesViewModel`, `SettingsViewModel`, ...).
2. Aksi mulai/berhenti berbagi masuk ke `ShareRuntime` (singleton, scope `SupervisorJob`).
3. `ShareRuntime.start()`:
   - memvalidasi konfigurasi dan ketersediaan port,
   - memilih mode tunnel (`QUICK` atau `NAMED`),
   - menjalankan `PocketShareServer` (Ktor CIO) di `127.0.0.1`/LAN sesuai setting,
   - menjalankan `cloudflared` lewat `CloudflaredTunnelProvider`,
   - memantau jaringan dan auto-stop.
4. `SharingService` (foreground service `specialUse`) memegang wake lock, menampilkan notifikasi,
   dan menjadi pemilik siklus hidup sesi.
5. UI mengamati `ShareRuntime.state: StateFlow<SharingState>` dengan fase
   `STOPPED/STARTING/RUNNING/STOPPING/ERROR`, URL publik, port lokal, status tunnel, dan error.

Aturan yang perlu dijaga:

- Hanya satu sesi aktif; `lifecycleMutex` mencegah start/stop bersamaan.
- Auto-stop berjalan lewat `autoStopJob`; perubahan setting Wi-Fi-only dapat menghentikan sesi.
- Semua error startup dipetakan ke fase `ERROR` agar UI bisa menampilkan pesan, bukan crash.

## 3. Alur request publik

Route di `PocketShareModule`:

| Route | Fungsi |
|-------|--------|
| `GET /favicon.svg`, `/favicon.ico` | Favicon SVG inline, cache-friendly |
| `GET /s/{token}` | Halaman share (atau form password) |
| `POST /s/{token}/unlock` | Verifikasi password, set cookie sesi |
| `GET /s/{token}/download/{entryId}` | Unduh satu file, dukung Range |
| `GET /s/{token}/zip[?ids=...]` | ZIP semua file atau file terpilih |
| `GET /s/{token}/preview/{entryId}[?embed=1]` | Pratinjau media/dokumen |
| `GET /{...}` | 404 identik |

Urutan keamanan setiap request: rate limit umum → resolve token yang masih valid → cek password/
sesi → validasi panjang `entryId` → ambil slot unduhan (`Semaphore`) → buka konten SAF.

Detail penting:

- **404 identik** dipakai untuk token tidak ada, revoked, expired, kuota habis, entry tidak dikenal,
  dan path traversal. Jangan menambah pesan pembeda.
- **Password**: hash disimpan di Room (`passwordHash/Salt/Iterations`), sesi memakai cookie
  `HttpOnly; Secure; SameSite=Lax` yang ditandatangani `SessionTokenSigner`. Percobaan password
  dibatasi `RateLimiter` terpisah dan mengembalikan 429 + `Retry-After`.
- **Range**: `RangeParser` menangani single range, suffix range, dan menolak multi-range/unsatisfiable
  dengan 416. `Accept-Ranges: bytes` hanya dikirim bila ukuran diketahui.
- **ZIP**: streaming `ZipOutputStream`, nama entry dibersihkan `FileNames.uniqueZipEntry`, flag UTF-8
  aktif. `ids` diparse sebagai daftar id (maksimal 128 karakter per id); id yang tidak dikenal
  diabaikan, dan bila tidak ada yang cocok hasilnya 404. Satu ZIP memakai satu slot unduhan.
- **Kuota**: unduhan penuh dan ZIP memakai `ShareStore.tryReserveDownload`. Thumbnail daftar file
  memakai token bertanda tangan dan **tidak** memakai kuota.
- **CSP**: `default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src 'self';
  media-src 'self'; frame-src 'self' data:; connect-src 'none'; form-action 'self'; base-uri 'none';
  frame-ancestors 'self'`. Halaman publik sekarang memakai JavaScript inline untuk dialog pratinjau
  dan seleksi; tautan unduh tetap berfungsi tanpa JavaScript.

## 4. Fitur 1.0.5

### 4.1 Pratinjau dokumen

Pipeline di modul `server`:

- `DocumentText` mendeteksi format dan mengekstrak teks dari OOXML/OpenDocument (ZIP+XML), dengan
  batas jumlah entry, ukuran entry, total byte, dan jumlah karakter.
- `CompoundFile` membaca OLE/Compound File untuk `.doc`, `.xls`, `.ppt`.
- `LegacyDocumentText` mengekstrak teks dari format Office 97-2003.
- `RtfText` mengurai RTF.
- `DocumentHtml` merender struktur: heading/tabel Word, tabel spreadsheet, slide berurutan, dan
  fallback teks biasa bila parsing gagal.
- `handlePreview` membungkus hasil dalam halaman preview; dokumen tidak pernah dirender sebagai
  HTML mentah dari file pengguna. Mode `embed=1` menghapus chrome agar bisa dipakai di iframe
  dialog halaman share.

### 4.2 Thumbnail dan pratinjau gambar

- `AndroidImageTransformer` (implementasi `ImageTransformer` di `core`) mengecilkan gambar dengan
  `BitmapFactory`, menghormati rotasi EXIF, mempertahankan transparansi sebagai PNG, dan
  fallback ke byte asli bila decode gagal.
- Thumbnail daftar memakai `?thumb=<token>` bertanda tangan; token diikat ke `share.id + entryId`.
- GIF/WebP/APNG animasi tidak di-re-encode untuk tampilan penuh; thumbnail boleh menjadi statis.
- Pratinjau penuh dan unduhan memakai kuota; thumbnail tidak.

### 4.3 Seleksi dan ZIP parsial

- Halaman share menampilkan checkbox per file (default terpilih semua) dan toolbar
  pilih semua/batal pilih/unduh terpilih.
- Tombol unduh terpilih memanggil `/s/{token}/zip?ids=...`; tanpa `ids` tetap ZIP semua.
- Toolbar ringkas di layar sempit: label tombol disembunyikan, ikon tetap tampil.

### 4.4 UI lain di 1.0.5

- Toggle show/hide password pada form unlock.
- Splash screen, ekspor/impor settings JSON (tanpa secret tunnel), marquee nama file panjang.
- Changelog di `app/src/main/assets/changelog.json` menggerakkan About dan popup What's New sekali
  per versi (`changelogSeenVersion` di DataStore).

## 5. Data dan konfigurasi

- Room `shares`: `token` unik, `kind`, `displayName`, `filesJson` (daftar URI SAF), masa berlaku,
  hash password, batas & hitungan unduhan, `revokedAtEpochMs`.
- DataStore: `tunnel_mode`, `port`, `wifi_only`, `auto_stop_minutes`, `default_expiry`, `language`,
  `allow_lan_access`, `changelog_seen_version`.
- `NameTunnelRepository`/`AndroidKeystoreSecretCipher` menyimpan token tunnel terenkripsi; jangan
  pernah menulis secret ke log atau ekspor JSON.
- `FileAppLogger` + `Redactor` menyamarkan token/password/cookie sebelum log ditulis.

## 6. Peta pengujian

| Modul | Test | Catatan |
|-------|------|---------|
| `core` | 9 file | Range, token, password, rate limit, nama file, hostname, redactor |
| `server` | 6 file, 55 test | Integrasi Ktor, Range, ZIP, 404, password, dokumen, RTF, legacy Office |
| `tunnel` | 4 file | Perintah, parser log, proses |
| `app` | 1 file | Uji unit UI/dialog |
| `data` | 1 file | `SafShareContentSourceTest` butuh unduhan Maven Robolectric; gagal offline |

Suite QA yang biasanya dipakai:

```bash
./gradlew :core:test :server:test :tunnel:test :app:testDebugUnitTest \
  ktlintCheck detekt :app:lint :app:assembleDebug :app:assembleDebugAndroidTest
```

`:data:testDebugUnitTest` dijalankan terpisah saat ada jaringan.

## 7. Utang teknis dan risiko

| Prioritas | Item | Dampak |
|-----------|------|--------|
| Sedang | `android.media.ExifInterface` masih dipakai di `AndroidImageTransformer`; migrasi ke `androidx.exifinterface.media.ExifInterface` | Lint warning keamanan pada Android lama |
| Sedang | `DocumentText.parse` memakai `DocumentBuilderFactory`; parser Android tidak mendukung XInclude dan melempar `UnsupportedOperationException` | Setter `isXIncludeAware`/`isExpandEntityReferences` harus tetap dibungkus `runCatching`; unit test JVM tidak menangkap regresi ini (terjadi di 1.0.5) |
| Rendah | `HEAD` pada route download mengembalikan 404 (hanya `GET` terdaftar) | Klien yang memakai HEAD bisa bingung |
| Rendah | 81 warning lint, sebagian internasionalisasi/targetSdk | Tidak menghalangi rilis |
| Uji | T15 mode terang terblokir `mNightModeLocked=true` di ColorOS | Tidak bisa diverifikasi di perangkat ini |
| Uji | T20 offline tidak diuji karena airplane mode tidak memutus koneksi | Jalur offline hanya ditutup unit test |
| Uji | T12 emoji/newline dan soak T9 belum lengkap | Perlu sesi perangkat berikutnya |
| Proses | Build release bisa terputus bila klien Gradle ditutup saat R8 | Jalankan build di terminal/latar yang stabil |

## 8. Titik ekstensi yang aman

- **Format pratinjau baru**: tambahkan deteksi MIME di `DocumentText`, ekstraktor terbatas, lalu
  renderer di `DocumentHtml`; jangan pernah menyisipkan HTML dari file tanpa escape.
- **Route baru**: daftarkan di `PocketShareModule`, ikuti urutan rate limit → resolve token →
  otorisasi → validasi input → slot unduhan.
- **Setting baru**: tambahkan key DataStore di `SettingsRepository`, field `AppSettings`, UI di
  `SettingsScreen`, dan (bila portable) field di ekspor/impor JSON; jangan sertakan secret.
- **Perubahan halaman publik**: jaga tautan non-JS tetap berfungsi, escape semua nama file, dan
  perbarui CSP bila menambah sumber daya.
- **Perubahan gambar**: batas ukuran/kuota ada di `PocketShareModule`; transformer hanya boleh
  mengembalikan `null` untuk fallback, bukan melempar exception.

## 9. File kunci

- `core/share/SharePorts.kt` - kontrak konten dan store.
- `server/PocketShareModule.kt` - routing, keamanan, streaming, kuota, thumbnail, preview.
- `server/PublicPageRenderer.kt` - HTML/CSS/JS halaman publik.
- `server/DocumentText.kt`, `DocumentHtml.kt`, `LegacyDocumentText.kt`, `CompoundFile.kt`,
  `RtfText.kt` - pipeline dokumen.
- `data/content/SafShareContentSource.kt` - resolusi URI SAF dan metadata.
- `app/runtime/ShareRuntime.kt` - orkestrasi server + tunnel + jaringan.
- `app/service/SharingService.kt` - foreground service, wake lock, notifikasi.
- `app/src/main/assets/changelog.json` - sumber About/What's New.
- `app/build.gradle.kts` - versi rilis dan signing dari environment.
