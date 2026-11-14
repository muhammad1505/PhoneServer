# PocketShare - Decision Log

Catatan keputusan arsitektur dan keamanan, berurutan dari yang terbaru.

## M10 (saat ini): Edge adaptif dan resolver segar (1.1.1)

- **Percobaan pertama tetap memakai `TUNNEL_EDGE`** hasil `InetAddress` di HP: jalur tercepat dan
  tidak bergantung pada SRV. Namun proses cloudflared tidak bisa mengganti daftar itu sendiri, dan
  log 14 November 2026 menunjukkan keempat alamat pinned gagal terus selama VPN memblokir 7844.
- **Fallback discovery.** Bila percobaan pinned tidak pernah tersambung (watchdog 60 detik untuk
  percobaan pinned, 150 detik untuk discovery), percobaan berikutnya mengosongkan `TUNNEL_EDGE`
  sehingga cloudflared memakai SRV lewat resolver yang dipatch. Strategi bergantian pada kegagalan
  berturut-turut, dan kembali ke pinned setelah ada koneksi yang berhasil, agar satu strategi yang
  rusak di jaringan tertentu tidak mengunci semuanya.
- **`TUNNEL_DNS_SERVERS` dibaca ulang setiap launch** (bukan sekali saat sesi mulai). Resolver
  ikut berpindah jaringan; daftar lama tidak berguna setelah handover Wi-Fi/seluler/VPN.
- **CI tanpa APK debug** pada job tes; verifikasi `libcloudflared.so` dipindah ke APK rilis. Job
  rilis menghapus artifact workflow lama agar kuota penyimpanan GitHub tidak menumpuk.

## M9: Pratinjau dokumen dan seleksi unduhan (1.0.5)

- **Pratinjau dokumen diproses di ponsel**: `DocumentText`/`LegacyDocumentText`/`RtfText` mengekstrak
  teks dengan batas ukuran, lalu `DocumentHtml` merender struktur aman. File pengguna tidak pernah
  dirender sebagai HTML mentah dan tidak diunggah.
- **Thumbnail daftar file memakai token bertanda tangan** (`?thumb=`) yang terikat ke share+entry;
  thumbnail tidak memakai kuota unduhan, sedangkan pratinjau penuh dan unduhan memakai kuota.
- **ZIP parsial** memakai `/s/{token}/zip?ids=...`; id yang tidak dikenal diabaikan, dan satu ZIP
  (semua atau terpilih) memakai satu slot unduhan.
- **Dialog pratinjau memakai iframe same-origin** (`?embed=1`) untuk media/dokumen, dengan tautan
  unduh tetap berfungsi tanpa JavaScript.
- **Gambar animasi** (GIF/WebP/APNG) tidak di-re-encode untuk tampilan penuh; thumbnail boleh
  dioptimasi sebagai gambar statis.
- **Ekspor/impor settings JSON** tidak pernah memuat token tunnel atau secret lain.

## M8: Release, CI, dan dokumentasi

- **Aplikasi signing** dari variabel environment (`POCKETSHARE_KEYSTORE` dll.), bukan keystore di
  repo. `versionCode`/`versionName` juga dari environment dengan default `17`/`1.1.1`.
- **Cloudflare Tunnel sebagai satu-satunya pihak ketiga**; analytics/telemetry ditolak di semua
  layer.
- **GitHub Actions CI**: lint, unit/integration test, detekt, ktlint (check), dan `assembleRelease`
  saat tag; `gradleWrapperValidation` untuk integritas wrapper.
- **`ktlint` tetap check-only**; formatting dilakukan manual.

## M7: Server

- **Server binds ke `127.0.0.1` default**, tidak ke `0.0.0.0`; pengaturan LAN akses terbuka via
  `Settings.allowLanAccess`. Default loopback mengikuti prinsip privasi.
- **404 identik** untuk token tidak valid, expired, revoked, exhausted; rate limiter terpisah dari
  konten agar tidak membocorkan keberadaan token.
- **Password cookie**: sesi pendek, HttpOnly, Secure, SameSite=Lax; validasi ulang tiap request.
- **Streaming unduhan dan ZIP dinamis** tanpa buffer file utuh ke memori; backpressure via Ktor
  `respondOutputStream` dengan buffer 256 KB.
- **Konfigurasi keamanan** (CSP, header, batas header/form, gate koneksi, rate limiter)
  dikumpulkan di `ServerDependencies` dengan default defensif.

## M6: UI

- **Bottom navigation** lima tab (Beranda, Berbagi, Pengaturan, Diagnostik, Tentang) di `AppRoot`.
- **Sintaksis SAF**: `OpenMultipleDocuments` untuk file, `OpenDocumentTree` untuk folder; izin
  dipersistensikan sebelum database.
- **CreateShareDialog** di AppRoot level: pemilihan file/folder disimpan sementara, dialog
  memproses metadata saja; hasil diumpankan ke activity-scoped `SharesViewModel`.
- **Default expiry** diambil dari `SettingsRepository` dan diinformasikan oleh dialog.
- **QR code** di-Generate lokal dengan ZXing, tidak ada URL diunggah.
- **Bilingual ID/EN** via `values` dan `values-in`; bahasa diterapkan per aplikasi.

## M5: Tunnel

- **Quick tunnel tanpa DNS resolver**; **named tunnel mengirim DNS terdeteksi** lewat
  `TUNNEL_DNS_RESOLVER_ADDRS`. Quick tunnel tidak bisa menerima `--dns-resolver-addrs` di versi
  target tanpa patch (tugas riset tersisa).
- **Named token via `TUNNEL_TOKEN`**, bukan argv; token dienkripsi Keystore di `data/`.
- **Backoff eksponensial** (1s-60s, jitter 20%) pada `TunnelProvider`; status mengalir via
  `StateFlow`.
- **Perubahan jaringan tidak memaksa restart tunnel.** `cloudflared` menyambung ulang koneksi
  sendiri; memaksa restart akan mengganti URL quick tunnel dan memutus unduhan aktif. Runtime hanya
  menghentikan sesi bila opsi "Hanya WiFi" dilanggar (setelah jeda 5 detik agar handover Wi-Fi →
  seluler tidak memicu penghentian palsu).
- **Quick tunnel juga memakai HTTP/2**, bukan QUIC default; NAT UDP operator membuat QUIC diam
  tanpa memutus proses.
- **`/ready` cloudflared adalah sumber status kesehatan.** Proses yang hidup tidak berarti link
  hidup: 503 selama 15 detik → `TunnelStatus.Degraded`, 200 → `Running`. Ini menutup kasus UI
  menampilkan "Aktif" saat Cloudflare membalas 1033.
- **Probe publik `GET /healthz`** (204, tanpa token) memverifikasi jalur penuh browser → Cloudflare
  → tunnel → server tiap 45 detik (120 detik saat metered). Dua 5xx berturut-turut (atau timeout
  setelah host resolve) menandai `Reachability.UNREACHABLE`; `UnknownHostException` diabaikan
  karena itu masalah resolver lokal, bukan bukti tunnel mati. Probe hanya berjalan selama sesi
  aktif.
- **Jaringan dianggap online hanya bila `VALIDATED`**, bukan sekadar `INTERNET`, agar captive
  portal tidak memulai atau mempertahankan sesi yang tidak bisa diakses.

## M4: Service dan Runtime

- **Foreground service `specialUse`** bukan `dataSync` (Android 15 membatasi durasinya); notifikasi
  aksi Stop + Salin Link; wake lock parsial 24 jam.
- **`ShareRuntime` mengunci lifecycle**: satu server + satu tunnel per proses; restart setelah
  service `START_STICKY` memulihkan **named tunnel** otomatis setelah proses dibunuh sistem (hostname
  tetap, link lama tetap berlaku); quick tunnel tidak dipulihkan otomatis karena URL-nya berubah dan
  pengguna tidak akan tahu alamat barunya.
- **Wifi-only enforcement** memaksa henti jika seluler terdeteksi saat tunnel aktif.
- **Auto-stop** timer (0–24 jam) dari pengaturan.

## M3: Data

- **SAF-only**: hanya URI dokumen yang dipersistensikan; `takePersistableUriPermission` di
  `DefaultShareRepository` sebelum insert.
- **Room** menyimpan referensi URI, bukan path; `ShareStore` menerjemahkan ke konten nyata saat
  unduhan.
- **Password hash** PBKDF2-HMAC-SHA256 dengan salt per share, iterasi tinggi.

## M2: Core

- **Token 32-byte SecureRandom base64url**; constant-time comparison.
- **`FileNames`** menangani RFC 5987, escape HTML, sanitasi ZIP entry.
- **`RangeParser`** mendukung satu rentang dan `Accept-Ranges: bytes`.

## M1: Struktur proyek

- **Modul**: `app`, `core`, `data`, `server`, `tunnel`. `core` dan `server` murni JVM; `app`/
  `data`/`tunnel` Android.
- **Version catalog** dengan versi dipin; AGP 8.13.2, Gradle 8.13, Kotlin 2.2.21, KSP 2.2.21-2.0.5,
  Hilt 2.57.2, Room 2.8.1, Compose BOM 2025.10.01, Ktor 3.3.3.
- **`useLegacyPackaging`** untuk `libcloudflared.so` agar AGP mengekstraknya.

## Verifikasi build lokal (10 November 2026)

- `:core:test`, `:server:test`, `:tunnel:test`, dan `:app:testDebugUnitTest` lulus.
- `ktlintCheck`, `detekt` (semua modul), dan `:app:lint` lulus tanpa temuan.
- `assembleDebug`, `assembleRelease`, dan `bundleRelease` menghasilkan artefak; APK rilis
  unsigned 41 MB dan AAB 44 MB, keduanya memuat `libcloudflared.so` untuk `arm64-v8a` dan
  `x86_64`.
- `aapt2` memverifikasi `minSdkVersion 26`, `targetSdkVersion 36`, `extractNativeLibs=true`,
  `usesCleartextTraffic=false`, dan `foregroundServiceType=specialUse`.
- CI memakai nama task Gradle yang benar (`detekt`, `:app:lint`), membangun `bundleRelease`,
  dan mendekode keystore rilis dari secret base64 sebelum `assembleRelease`.

## Risiko tersisa yang harus diverifikasi di perangkat nyata

1. **DNS cloudflared cepat di jaringan seluler** — dokumentasikan hasil field test.
2. **Eksekusi binary di `nativeLibraryDir` pada API 26–29.**
3. **Servis dibunuh vendor (Xiaomi/Oppo/Samsung)** saat layar mati.
4. **Batas quick tunnel Cloudflare** (durasi, kuota, batas jendela).
