# PocketShare

Bagikan file langsung dari ponsel Anda — tanpa VPS, tanpa akun, tanpa analytics. PocketShare mengubah ponsel menjadi server berbagi pribadi yang diekspos lewat **Cloudflare Tunnel**, sehingga Anda mendapat tautan HTTPS publik hanya dengan satu kali tekan.

## Prinsip

- **Seluruh server berjalan di ponsel.** Tidak ada cloud milik PocketShare. Satu-satunya pihak ketiga adalah Cloudflare Tunnel (`cloudflared`) untuk menembus CGNAT operator seluler.
- **Tidak ada data yang dikumpulkan.** Tidak ada analytics, tidak ada telemetri, tidak ada akun.
- **Privat sepenuhnya.** File tetap di ponsel; yang dipublikasikan adalah tautan, bukan unggahan.

## Cara kerja

1. Buka **Berbagi** → pilih **Bagikan file** atau **Bagikan folder**. Tentukan nama, masa berlaku, kata sandi opsional, dan batas unduhan opsional.
2. Buka **Pengaturan** → pilih **Mode tunnel**: **Quick tunnel** (alamat berubah tiap kali dimulai) atau **Named tunnel** (alamat tetap, butuh token Cloudflare Zero Trust).
3. Tekan **Mulai berbagi**. PocketShare memulai server lokal, membuka tunnel ke Cloudflare, lalu menampilkan tautan publik.
4. Bagikan tautan siapa saja. Mereka bisa membuka/mengunduh file di browser tanpa aplikasi apa pun. Gambar, video, audio, PDF, teks, serta dokumen Word/Excel/PowerPoint/OpenDocument/RTF bisa dipratinjau langsung di halaman publik; nama file panjang otomatis berjalan agar tetap terbaca. Centang file tertentu untuk mengunduh ZIP parsial, atau unduh semuanya sekaligus.
5. Butuh pindah ponsel? Buka **Pengaturan → menu titik tiga → Ekspor/Impor pengaturan**. File JSON berisi mode tunnel, port, bahasa, dan default; token tunnel tetap tinggal di ponsel.

## Persiapan named tunnel (opsional)

Named tunnel memberi alamat tetap, misalnya `https://files.example.com`.

1. Daftar di [Cloudflare Zero Trust](https://one.dash.cloudflare.com/) dan buat tunnel.
2. Simpan **token tunnel** dari dashboard.
3. Tambahkan ingress origin `http://<alamat-HP-anda>:<port>` (lihat Diagnostics untuk port lokal).
4. Di PocketShare → **Pengaturan → Named tunnel**, tempel token dan hostname, lalu simpan. Port lokal harus tetap dan cocok dengan origin.

Quick tunnel (mode default) tidak butuh setup apa pun dan cocok untuk pengujian.

## Arsitektur

| Modul    | Isi                                                                                     |
|----------|-----------------------------------------------------------------------------------------|
| `app`    | UI Jetpack Compose Material 3, MVVM + Hilt, FGS `specialUse`, notifikasi, SAF          |
| `core`   | Model, pembangkit token, PBKDF2, rate limiter, parser Range, sanitasi nama/file/ZIP      |
| `data`   | Room + DataStore, repository SAF, enkripsi Keystore, rolling redacted log               |
| `server` | Ktor CIO, rute `/s/{token}`, unlock berpassword, streaming, Range 206, ZIP dinamis, pratinjau dokumen, thumbnail |
| `tunnel` | Supervisi `cloudflared`, edge adaptif (pinned ↔ discovery), backoff eksponensial, environment aman, parser log |

## Instalasi manual

### Mengambil binary cloudflared

```bash
./scripts/fetch-cloudflared.sh
```

Script mengunduh **source** `2026.10.7`, memverifikasi SHA-256, menerapkan
`scripts/cloudflared-dns.patch` (resolver DNS Android), lalu membangun binary dengan Go `1.27.3`
dan menginstalnya sebagai `libcloudflared.so` di `app/src/main/jniLibs/`. Build pertama
mengunduh Go ±68 MB dan modul Go; build berikutnya memakai cache `.build/cloudflared/`. File
binary diabaikan oleh `.gitignore` agar build CI konsisten. Detail patch dan checksum ada di
`docs/CLOUDFLARED.md`.

### Build

```bash
./gradlew assembleDebug                                  # APK debug
./gradlew :core:test :server:test :tunnel:test :app:testDebugUnitTest
./gradlew ktlintCheck detekt :app:lint                    # check-only
./gradlew assembleRelease bundleRelease                  # perlu keystore (lihat Bawah)
```

### Release produksi

```bash
export POCKETSHARE_KEYSTORE=/path/pocketshare.jks
export POCKETSHARE_KEYSTORE_PASSWORD=...
export POCKETSHARE_KEY_ALIAS=pocketshare
export POCKETSHARE_KEY_PASSWORD=...
./gradlew assembleRelease
```

`versionCode` dan `versionName` otomatis dari variabel `POCKETSHARE_VERSION_CODE` dan `POCKETSHARE_VERSION_NAME`; tanpa keduanya digunakan `17` dan `1.1.1`.

## Keamanan

- Hanya rute `/s/{token}` yang dilayani; semua rute lain merespons halaman 404 yang identik (tidak ada enumerasi token).
- Token 32-byte base64url, perbandingan konstan-waktu. Hash password PBKDF2-HMAC-SHA256 dengan salt per share dan iterasi tinggi.
- Cookie sesi pendek, HttpOnly, Secure, SameSite=Lax. Header CSP ketat, `X-Content-Type-Options`, `Referrer-Policy`, dan `X-Robots-Tag`.
- Rate limiting per klien, batas header, batas koneksi bersamaan, dan gate unduhan.
- Token, password, dan token Cloudflare tidak pernah masuk log; log hanya merahasiakan (redacted), lokal, dan bisa dihapus.
- Akses file via Storage Access Framework (SAF); hanya URI dokumen yang dipilih yang dipersistensikan, tidak pernah path mentah.

## Dokumentasi pendukung

- [`docs/CLOUDFLARED.md`](docs/CLOUDFLARED.md) — detail binary, DNS, dan perilaku tunnel.
- [`docs/DECISIONS.md`](docs/DECISIONS.md) — catatan keputusan arsitektur.
- [`docs/TEST_MATRIX.md`](docs/TEST_MATRIX.md) — matriks uji manual.
- [`SECURITY.md`](SECURITY.md) — pelaporan kerentanan.
- [`PRIVACY.md`](PRIVACY.md) — privasi, tidak ada data dikumpulkan, dan model ancaman (TLS berakhir di Cloudflare, tanpa E2EE di 1.1.1).
- [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) — notis lisensi pihak ketiga.

## Lisensi

PocketShare tersedia di bawah [Apache-2.0](LICENSE).

## Catatan hukum

Pengguna bertanggung jawab atas kepatuhan terhadap ketentuan layanan Cloudflare dan hukum yang berlaku untuk file yang mereka bagikan.
