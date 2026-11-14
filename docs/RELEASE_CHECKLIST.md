# PocketShare Deployment Checklist

Gunakan daftar ini saat mempersiapakan rilis pertama dan update berikutnya.

## 1. Persiapan keystore

```bash
keytool -genkeypair \
  -keystore /path/pocketshare.jks \
  -storetype JKS \
  -alias pocketshare \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass <keystore-password> -keypass <key-password> \
  -dname "CN=Your Name, OU=PocketShare, O=Your Organization, L=City, ST=State, C=ID"
```

Jangan pernah commit keystore. Untuk CI, simpan isi biner keystore sebagai base64 di secret
`POCKETSHARE_KEYSTORE_BASE64`:

```bash
base64 -w0 /path/pocketshare.jks   # tempel hasilnya ke secret, satu baris
```

Secret lain yang dibutuhkan workflow rilis: `POCKETSHARE_KEYSTORE_PASSWORD`,
`POCKETSHARE_KEY_ALIAS`, `POCKETSHARE_KEY_PASSWORD`.

## 2. Persetujuan foreground service `specialUse`

**Teks justifikasi untuk formulir Play Console:**

> PocketShare menjalankan foreground service dengan tipe `specialUse` untuk mempertahankan
> layanan berbagi file di latar belakang setelah layar mati. Tanpa layanan ini, ponsel dapat
> menghentikan tunnel dan memutuskan akses ke link publik. Aplikasi tidak menjalankan proses
> latar belakang selain servis ini, dan servis langsung dihentikan setelah berbagi selesai.

**Data Safety:** bukan bagian dari layanan foreground; aplikasi tidak mengumpulkan data pribadi.

## 3. Formulir Data Safety (Play Console)

Isi sebagai berikut:

- **Apakah aplikasi mengumpulkan data?**: Tidak.
- **Data apa yang dikumpulkan**: Tidak ada data pribadi yang dikumpulkan atau dibagikan.
- **Pengumpulan data**: Tidak ada.
- **Berbagi data ke pihak ketiga**: Tidak ada.
- **Keamanan data**:
  - Data disimpan di perangkat pengguna; aplikasi menyimpan data lokal di perangkat.
  - Semua komunikasi menggunakan HTTPS (Cloudflare Tunnel).
  - Token dan kredensial tunnel dienkripsi dengan Android Keystore.
  - Aplikasi tidak mengumpulkan, menyimpan, atau mengirimkan data pribadi ke pihak ketiga.
  - Data dihapus saat pengguna menghapus aplikasi.
- **Kebijakan privasi**: sediakan URI dokumen PRIVACY.md (upload ke website/hosting).
- **Tujuan pengumpulan**: Tidak ada tujuan karena tidak ada pengumpulan data.

## 4. Aset listing

- Screenshot beranda, daftar berbagi, pengaturan, dan diagnostik (gelap + terang).
- deskripsi pendek: "Bagikan file dari ponsel Anda lewat Cloudflare Tunnel — tanpa VPS, tanpa akun."
- deskripsi panjang: jelaskan fitur dan alur; sertakan contoh named tunnel.
- kategori: **Alat Produktivitas**.
- icon: 512x512, jelas, tanpa background rumit.
- konten: **Semua usia** atau **Remaja**; jelaskan berbagi file user-generated.

## 5. Build dan verifikasi

```bash
./scripts/fetch-cloudflared.sh

export POCKETSHARE_KEYSTORE=/path/pocketshare.jks
export POCKETSHARE_KEYSTORE_PASSWORD=...
export POCKETSHARE_KEY_ALIAS=pocketshare
export POCKETSHARE_KEY_PASSWORD=...
export POCKETSHARE_VERSION_CODE=15
export POCKETSHARE_VERSION_NAME="1.0.5"

./gradlew assembleRelease bundleRelease
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
aapt2 dump badging app/build/outputs/apk/release/app-release.apk | grep -E '^sdkVersion|^targetSdkVersion|^package:'
unzip -l app/build/outputs/apk/release/app-release.apk | grep libcloudflared
```

Jalankan uji manual di matriks perangkat (lihat TEST_MATRIX.md), termasuk transfer data ke WiFi dan
file lebih besar dari 4 GB.

### Verifikasi rilis 1.0.0 (12 November 2026)

Build yang diverifikasi:

- APK: `app/build/outputs/apk/release/app-release.apk` (41 MB).
- AAB: `app/build/outputs/bundle/release/app-release.aab` (45 MB).
- `package=dev.pocketshare`, versionCode `1`, versionName `1.0.0`, minSdk `26`, targetSdk `36`,
  label `Pocket Share`.
- `apksigner verify --print-certs` lulus. Sertifikat:
  `CN=PocketShare, OU=Mobile, O=PocketShare, L=Jakarta, ST=DKI Jakarta, C=ID`;
  SHA-256 `9259c9cc0014e7d9e3379c904640a50f1d66ac2db220ae105f726ae40e236fad`.
- `jarsigner -verify` pada AAB exit 0 (`jar verified`; peringatan self-signed dan tanpa timestamp
  adalah normal untuk rilis lokal).
- `lib/arm64-v8a/libcloudflared.so` dan `lib/x86_64/libcloudflared.so` ada di dalam APK.
- SHA-256 artifact: APK `f8ec455c0d35ca15ae95485694dc849d4b2683361ead9dbc2e4faa5f667a8763`,
  AAB `022ab28a4c8999090d29fd1f4aca4b9600ae98d821182d4cc5dcfba4a447c694`.
- Suite QA lengkap lulus: `:core:test :server:test :tunnel:test :app:testDebugUnitTest ktlintCheck
  detekt :app:lint :app:assembleDebug :app:assembleDebugAndroidTest`.

### Rebuild 14 November 2026 (patch DNS loopback Android)

Perubahan utama: perbaikan tahan-lama untuk DNS cloudflared di Android.
- `TUNNEL_EDGE` kini berisi alamat IP edge yang diselesaikan lewat resolver Android,
  sehingga penemuan SRV ke `region1.v2.argotunnel.com`/`region2.v2.argotunnel.com` dilewati
  sepenuhnya.
- `TUNNEL_DNS_SERVERS` diambil dari `ConnectivityManager.getLinkProperties()` dan diteruskan ke
  cloudflared, sehingga cloudflared tidak lagi bergantung pada `/etc/resolv.conf` yang tidak ada di
  Android.
- Binary cloudflared `2026.10.7-pocketshare.1` berisi patch `scripts/cloudflared-dns.patch`
  (loopback DNS forwarder khusus).

Build yang diverifikasi:

- APK: `app/build/outputs/apk/release/app-release.apk` (23 MB = 23253762 byte).
- AAB: `app/build/outputs/bundle/release/app-release.aab` (27 MB = 27541679 byte).
- `package=dev.pocketshare`, versionCode `16`, versionName `1.1.0`, minSdk `26`, targetSdk `36`,
  label `Pocket Share`.
- `apksigner verify --print-certs` lulus. Sertifikat:
  `CN=PocketShare, OU=Mobile, O=PocketShare, L=Jakarta, ST=DKI Jakarta, C=ID`;
  SHA-256 `9259c9cc0014e7d9e3379c904640a50f1d66ac2db220ae105f726ae40e236fad`.
- APK `base.libcloudflared.so` ada di dalam APK; `arm64-v8a` versi 2026.10.7-pocketshare.1
  SHA-256 `cfbd697e4fe08acf20d7fcbe10f1e38b2c2b0edfd7fbc6e8d271ae07d77f60bc`,
  `x86_64` SHA-256
  `516a51cd5b9638c8086ee4cdf4f40dbfd2a11385361e48a66b940baebde28987`.
- SHA-256 artifact: APK `f8a4c0066f380d6f6b51b491382f57d346f09b72fc56a169e82fda999d9df7b8`,
  AAB `f530a9012118b5cdf4e717fdf72933c4b16983299685162dd8a5b73c052bc8aa`.
- Suite QA lengkap lulus: `:core:test :server:test :tunnel:test :app:testDebugUnitTest ktlintCheck
  detekt :app:lint :app:assembleDebug :app:assembleDebugAndroidTest`.
- Instalasi di Realme 3 (RMX1821, Android 10): `adb -s GEQKOJAYRC8HXOWG install -r` sukses.

### Verifikasi rilis 1.0.1 (12 November 2026)

Perubahan utama: named tunnel memakai `--protocol http2` agar tidak flapping di jaringan seluler.

Build yang diverifikasi:

- APK: `app/build/outputs/apk/release/app-release.apk` (42.226.631 byte).
- AAB: `app/build/outputs/bundle/release/app-release.aab` (46.427.331 byte).
- `package=dev.pocketshare`, versionCode `2`, versionName `1.0.1`, minSdk `26`, targetSdk `36`,
  label `Pocket Share`.
- Sertifikat sama dengan 1.0.0:
  `CN=PocketShare, OU=Mobile, O=PocketShare, L=Jakarta, ST=DKI Jakarta, C=ID`;
  SHA-256 `9259c9cc0014e7d9e3379c904640a50f1d66ac2db220ae105f726ae40e236fad`.
- `apksigner verify -v`: v2 `true`; v1, v3, v3.1, dan v4 `false` (v2-only).
- SHA-256 artifact: APK `a8d93f4f74b018ac655e72c101248bf3c84054a74345cd6007b4cc4db165072b`,
  AAB `80cc9d20bb80747abf58946daf018682a085e143a653b64277bfc58e8aec3b05`.
- `lib/arm64-v8a/libcloudflared.so` dan `lib/x86_64/libcloudflared.so` ada di dalam APK.
- Suite QA lengkap lulus: `:core:test :server:test :tunnel:test :app:testDebugUnitTest ktlintCheck
  detekt :app:lint :app:assembleDebug :app:assembleDebugAndroidTest`.
- Catatan instalasi ColorOS/Realme 3: `adb install -r` dapat gagal
  `INSTALL_FAILED_VERIFICATION_FAILURE` meskipun APK valid; gunakan
  `adb install --no-streaming -r app/build/outputs/apk/release/app-release.apk`.
- Verifikasi produksi: named tunnel HTTP/2 ke `pocketshare.my.id`; halaman share 200, unduhan 200,
  Range 206, root 404, dan halaman tampil di Firefox ponsel.
- Uji restart: `force-stop` lalu buka ulang dan mulai berbagi mempertahankan share serta link yang
  sama (`https://pocketshare.my.id/s/Kphn2owmVUhNUquloC2n38DTzrP1muJJF_46U8yQGqU`); halaman,
  unduhan, dan Range kembali 200/200/206.

Untuk build lokal, sumber kredensial tanpa mencetaknya:

```bash
set -a; . "$HOME/.pocketshare/keystore.properties"; set +a
export POCKETSHARE_KEYSTORE="$storeFile"
export POCKETSHARE_KEYSTORE_PASSWORD="$storePassword"
export POCKETSHARE_KEY_ALIAS="$keyAlias"
export POCKETSHARE_KEY_PASSWORD="$keyPassword"
export POCKETSHARE_VERSION_CODE=15
export POCKETSHARE_VERSION_NAME="1.0.5"
./gradlew assembleRelease bundleRelease
```

Setiap perubahan kode mengharuskan build ulang; fingerprint artifact akan berubah.

### Verifikasi rilis 1.0.4 (13 November 2026)

Perubahan utama: pratinjau media di halaman publik, kompresi thumbnail dan pratinjau gambar,
splash screen baru, ekspor/impor pengaturan JSON, marquee nama file panjang, dan toolbar halaman
berbagi berbasis ikon.

Build yang diverifikasi:

- APK: `app/build/outputs/apk/release/app-release.apk` (42.331.754 byte).
- AAB: `app/build/outputs/bundle/release/app-release.aab` (46.588.149 byte).
- `package=dev.pocketshare`, versionCode `14`, versionName `1.0.4`, minSdk `26`, targetSdk `36`,
  label `Pocket Share`.
- Sertifikat sama dengan rilis sebelumnya:
  `CN=PocketShare, OU=Mobile, O=PocketShare, L=Jakarta, ST=DKI Jakarta, C=ID`;
  SHA-256 `9259c9cc0014e7d9e3379c904640a50f1d66ac2db220ae105f726ae40e236fad`.
- `apksigner verify -v`: v2 `true`; v1, v3, v3.1, dan v4 `false` (v2-only).
- `jarsigner -verify` pada AAB exit 0.
- `lib/arm64-v8a/libcloudflared.so` dan `lib/x86_64/libcloudflared.so` ada di dalam APK, dan
  `assets/changelog.json` memuat entri 1.0.4.
- SHA-256 artifact: APK `0d18692ec5715f2fe77afebef9e824d466b58e7892f4ec4a73e336028cae9364`,
  AAB `7a584629ea1538197b62b7ee4296dfb62f8030ad5f47e0a83cbaba28789ca01a`.
- Suite QA lengkap lulus: `:core:test :server:test :tunnel:test :app:testDebugUnitTest ktlintCheck
  detekt :app:lint :app:assembleDebug :app:assembleDebugAndroidTest`.
- Perbaikan sebelum rilis: pelanggaran ktlint di `:server` dan `:app` dibereskan (rantai method,
  KDoc ganda, pembungkusan kondisi, format parameter), temuan detekt `TooGenericExceptionCaught`
  diganti dengan `IllegalArgumentException`/`IllegalStateException`, dan `CreateShareDialogTest`
  disesuaikan dengan API `selectedUris`/`onAddFiles`/`onRemoveFile`.

### Verifikasi rilis 1.0.5 (13 November 2026)

Perubahan utama: pratinjau dokumen kantor terstruktur (DOCX/XLSX/PPTX/ODT/ODS/RTF dan format
Office lama), thumbnail di daftar file publik, dialog pratinjau media, pemilihan file + ZIP
parsial, favicon web, dan penyempurnaan UI/UX.

Build yang diverifikasi:

- APK: `app/build/outputs/apk/release/app-release.apk` (42.349.506 byte).
- AAB: `app/build/outputs/bundle/release/app-release.aab` (46.629.423 byte).
- `package=dev.pocketshare`, versionCode `15`, versionName `1.0.5`, minSdk `26`, targetSdk `36`,
  label `Pocket Share`.
- Sertifikat sama dengan rilis sebelumnya:
  `CN=PocketShare, OU=Mobile, O=PocketShare, L=Jakarta, ST=DKI Jakarta, C=ID`;
  SHA-256 `9259c9cc0014e7d9e3379c904640a50f1d66ac2db220ae105f726ae40e236fad`.
- `apksigner verify -v`: v2 `true`; v1, v3, v3.1, dan v4 `false` (v2-only).
- `jarsigner -verify` pada AAB exit 0.
- SHA-256 artifact: APK `5e8acac348ddd08d011c15ce3c68bad220e2562f654bb7eadfc08523f667028a`,
  AAB `0aa1370a11366aa6eec0d6bf5ca25a346a276492a4718cfe1fee6c8ff8b388b8`.
- Terpasang di Realme 3 (RMX1821, Android 10/API 29) dengan
  `adb install --no-streaming -r`; `dumpsys package` menunjukkan versionCode 15/versionName 1.0.5.
- Verifikasi quick tunnel: halaman share 200 (14 file), thumbnail 320×240 6.531 byte vs pratinjau
  penuh 1280×960 54.808 byte, ZIP semua file 14 entri, ZIP parsial hanya memuat ID terpilih,
  ID tidak valid 404, Range video `bytes=1000-1999` → 206 `bytes 1000-1999/317854`,
  root `/` 404, dan `/favicon.svg` 200 `image/svg+xml` 643 byte.
- Verifikasi named tunnel produksi setelah token dimasukkan ulang: `https://pocketshare.my.id`
  halaman 200 (14 entri), unduhan PDF 200 674 byte, Range 206, ZIP parsial 200 (2 file),
  root 404, favicon 200, dan preview DOCX ter-render sebagai tabel (bukan "Preview unavailable").
  Restart sesi (hentikan → mulai) mempertahankan URL dan token share; halaman kembali 200.
- Catatan: data aplikasi sempat ter-reset saat instalasi 1.0.5, sehingga share lama
  `Kphn2owm...` sekarang 404 dan token named tunnel harus dimasukkan ulang. Ini bukan regresi.
- Verifikasi kuota: 3× permintaan thumbnail tidak menambah kuota; pratinjau penuh + satu unduhan
  mencapai `2/2`; unduhan berikutnya 404 dan halaman ditandai "Batas unduhan tercapai".
- Perbaikan penting sebelum rilis: `DocumentBuilderFactory.isXIncludeAware` melempar
  `UnsupportedOperationException` pada parser Android sehingga semua pratinjau OOXML/ODF jatuh
  ke "Preview unavailable". Setter `isXIncludeAware` dan `isExpandEntityReferences` sekarang
  dibungkus `runCatching`; unit test JVM tidak menangkap regresi ini karena parser JVM mendukung
  XInclude. Setelah perbaikan, DOCX/XLSX/PPTX/ODT/ODS ter-render terstruktur di perangkat.
- Popup "What's new" 1.0.5 belum teramati pada instalasi bersih karena `firstInstallTime ==
  lastUpdateTime`; perilaku upgrade perlu diuji terpisah (T28).
- Suite QA lengkap lulus sebelum build final: `:core:test :server:test :tunnel:test
  :app:testDebugUnitTest ktlintCheck detekt :app:lint :app:assembleDebug
  :app:assembleDebugAndroidTest` (131 unit test: core 40, server 55, tunnel 36; 0 gagal;
  lint 0 error, 81 warning).

### Verifikasi rilis 1.1.0 (13 November 2026)

Perubahan utama: deteksi tunnel yang diam-diam mati lewat `/ready` cloudflared dan probe
`GET /healthz` end-to-end, status `Degraded` yang mempertahankan URL, quick tunnel dipaksa HTTP/2,
pemulihan otomatis named tunnel setelah proses dibunuh Android (`START_STICKY`), serta probe yang
tidak salah menuduh saat DNS lokal gagal.

Build yang diverifikasi:

- APK: `app/build/outputs/apk/release/app-release.apk` (42.357.806 byte).
- AAB: `app/build/outputs/bundle/release/app-release.aab` (46.644.114 byte).
- `package=dev.pocketshare`, versionCode `16`, versionName `1.1.0`, minSdk `26`, targetSdk `36`.
- Sertifikat sama dengan rilis sebelumnya:
  `CN=PocketShare, OU=Mobile, O=PocketShare, L=Jakarta, ST=DKI Jakarta, C=ID`;
  SHA-256 `9259c9cc0014e7d9e3379c904640a50f1d66ac2db220ae105f726ae40e236fad`.
- `apksigner verify -v`: v2 `true`; v1, v3, v3.1, dan v4 `false` (v2-only).
- SHA-256 artifact: APK `3970690f38d1f00512032e04d6a52a758b364b800c3663d31ed347fafeea7547`,
  AAB `6477f4c81204695f476cb13327cd584024dad983db6888c5672952edde4b0742`.
- Terpasang di Realme 3 sebagai upgrade dari 1.0.5 dengan `adb install --no-streaming -r`;
  `dumpsys package` menunjukkan versionCode 16/versionName 1.1.0, `firstInstallTime` 09:00:52 dan
  `lastUpdateTime` 16:24:39 (data serta token named tunnel tetap).
- Popup "Yang baru" 1.1.0 muncul sekali setelah upgrade (T28 lulus), lalu named tunnel langsung
  aktif dengan URL `https://pocketshare.my.id` dan `/healthz` 204.
- `:app:assembleRelease :app:bundleRelease` sukses; unit test, `ktlintCheck`, dan `detekt` lulus
  sebelum build final. Uji perangkat terperinci ada di `TEST_MATRIX.md`.

## 6. Distribusi

- **GitHub Releases**: unggah APK dan AAB sebagai asset; buat release note singkat.
- **F-Droid**: kirim AAR ke repositori manual (build di server F-Droid via pipeline GitLab/GitHub).
- **Play Store**: unggah AAB, isi Data Safety, dan setujui justifikasi foreground service.

## 7. Pasca-rilis

- Pantau laporan crash dan ulasan.
- Tandai bug fix dan fitur baru sebagai tag; update versionCode/versionName.
- Perbarui README dan CHANGELOG jika ada.
