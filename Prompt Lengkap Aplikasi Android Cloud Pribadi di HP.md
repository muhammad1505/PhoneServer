# Prompt Lengkap: Aplikasi Android Cloud Pribadi di HP

Salin seluruh bagian **PROMPT** di bawah ke AI coding (mis. Claude Code) di folder repo kosong. Minta AI mengerjakan per milestone dan memverifikasi build di tiap tahap.

## PROMPT

### 1. Peran dan tujuan

Kamu adalah senior Android engineer. Bangun aplikasi Android native bernama sementara **PocketShare** yang mengubah HP menjadi server berbagi file pribadi. Alur pengguna: buka aplikasi, pilih folder atau file, tekan Start, lalu dapat link HTTPS publik. Siapa pun yang punya link bisa membukanya di browser dan mengunduh file, tanpa install apa pun. Kecepatan mengikuti upload koneksi HP.

Prinsip: seluruh server berjalan di HP. Tanpa VPS, tanpa backend milikku, tanpa analytics atau telemetri. Satu-satunya pihak ketiga adalah Cloudflare Tunnel (`cloudflared`) untuk menembus CGNAT operator seluler. Target: siap produksi, bukan prototipe.

### 2. Stack dan arsitektur

- Kotlin, Jetpack Compose (Material 3), minSdk 26, compileSdk dan targetSdk mengikuti syarat terbaru Google Play (cek saat build).
- MVVM, Hilt, Room, DataStore, Coroutines/Flow, Gradle version catalog dengan versi dipin.
- Modul: `app` (UI), `core` (model dan util), `data` (Room, repository), `server` (HTTP), `tunnel` (proses cloudflared).
- Server HTTP: Ktor dengan engine CIO. Secara default bind hanya ke `127.0.0.1` pada port bebas acak, karena tunnel menyambung dari localhost.
- `TunnelProvider` berupa interface agar penyedia tunnel bisa diganti. Implementasi awal: `CloudflaredProvider`.

### 3. Fitur v1

**A. Sumber file**

- Pilih folder lewat Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`) dan pilih beberapa file (`ACTION_OPEN_DOCUMENT`). Simpan izin dengan `takePersistableUriPermission`.
- Jangan pernah memakai path mentah. Semua akses lewat `DocumentFile` atau `ContentResolver`, dan hanya di dalam folder yang dipilih.

**B. Share link**

- Tiap share punya: token acak, target (file atau folder), masa berlaku (1 jam, 24 jam, 7 hari, tanpa batas), password opsional, batas jumlah unduhan opsional, hitungan unduhan, dan tombol cabut (revoke).
- Token: 32 byte dari `SecureRandom`, encode base64url. Link berbentuk `https://<host>/s/<token>`.
- Token tidak ada, kedaluwarsa, atau dicabut harus menghasilkan respons 404 yang identik (cegah enumerasi).
- Password disimpan sebagai hash PBKDF2-HMAC-SHA256 dengan salt per share dan iterasi tinggi. Setelah benar, beri cookie sesi pendek (HttpOnly, Secure, SameSite=Lax).

**C. Halaman web publik (rute `/s/{token}` saja)**

- HTML ringan, responsif, mendukung dark mode, tanpa JavaScript eksternal, dengan daftar file, ukuran, dan tombol unduh. Aplikasi tidak menyediakan halaman admin lewat tunnel. Pengelolaan hanya di dalam aplikasi.
- Unduh streaming dari `ContentResolver` tanpa memuat file ke memori. Dukung file di atas 4 GB (pakai `Long`).
- Dukung HTTP Range (206, `Accept-Ranges`) agar unduhan bisa dilanjutkan. Dukung satu rentang per permintaan.
- Unduh folder sebagai ZIP yang di-stream (`ZipOutputStream`), tanpa file sementara.
- `Content-Disposition: attachment` dengan nama file yang di-encode benar (RFC 5987), nama file di-escape di HTML, dan MIME type yang tepat.

**D. Tunnel**

- Mode Cepat (quick tunnel): jalankan `cloudflared tunnel --no-autoupdate --url http://127.0.0.1:<port>`, baca URL `https://*.trycloudflare.com` dari output dengan regex, dan tampilkan di layar. URL berubah tiap start. Tampilkan catatan bahwa Cloudflare menempatkan mode ini untuk pengujian.
- Mode Permanen (named tunnel): pengguna menempel token dari dashboard Cloudflare Zero Trust dan mengisi hostname. Jalankan `cloudflared tunnel --no-autoupdate run --token <TOKEN>`. Token disimpan terenkripsi (Android Keystore, mis. via Tink) dan tidak boleh muncul di log.
- Pantau proses: health check, restart otomatis dengan exponential backoff, matikan bersih saat Stop, tangani proses yang mati mendadak. Tampilkan status: Mati, Menyambung, Aktif, Error (dengan alasan).
- Binary `cloudflared` dibundel sebagai native library: `libcloudflared.so` di `jniLibs/arm64-v8a` (plus `x86_64` untuk debug/emulator), dieksekusi dari `applicationInfo.nativeLibraryDir`. Di manifest set `android:extractNativeLibs="true"` dan di Gradle `packaging { jniLibs { useLegacyPackaging = true } }`. Sediakan skrip `scripts/fetch-cloudflared.sh` yang mengunduh versi yang dipin dan memverifikasi checksum SHA-256.

**E. Service dan siklus hidup**

- Foreground service dengan tipe `specialUse` (deklarasikan `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`). Jangan pakai `dataSync` karena Android 15 membatasi durasinya.
- Notifikasi tetap dengan aksi Stop dan Salin Link. Minta izin `POST_NOTIFICATIONS` di Android 13 ke atas.
- Partial wake lock dengan timeout, dilepas saat Stop. Arahkan pengguna ke pengaturan optimasi baterai lewat `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` disertai penjelasan, jangan memaksa.
- Pantau jaringan dengan `ConnectivityManager`: peringatkan saat memakai data seluler, opsi Hanya WiFi, dan restart tunnel saat jaringan berganti bila perlu.

**F. UI (Compose)**

- **Beranda**: tombol Start/Stop besar, status, URL aktif dengan Salin, Bagikan, dan QR code.
- **Berbagi**: daftar share, tambah, ubah masa berlaku, cabut, dan lihat jumlah unduhan.
- **Pengaturan**: mode tunnel, port, Hanya WiFi, timer mati otomatis, masa berlaku default, bahasa.
- **Diagnostik**: log lokal yang bisa diekspor (token dan password disamarkan).
- Bahasa Indonesia dan Inggris, edge-to-edge, dark mode, label aksesibilitas, target sentuh minimal 48dp.

### 4. Keamanan (wajib lolos)

- Hanya rute `/s/...` yang dilayani. Semua rute lain 404.
- Cegah path traversal dan akses di luar folder terpilih. Tambahkan tes khusus.
- Header: `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `X-Robots-Tag: noindex`, dan CSP ketat pada halaman HTML.
- Rate limiting per IP klien, terutama untuk percobaan password (balas 429 dengan backoff). Ambil IP dari `CF-Connecting-IP` hanya jika permintaan datang dari loopback (tunnel).
- Bandingkan token dengan constant-time compare. Batasi koneksi bersamaan dan ukuran header.
- `android:allowBackup="false"`. Network security config yang hanya mengizinkan cleartext ke localhost. Tidak ada izin yang tidak diperlukan.
- Jangan log token, password, atau token Cloudflare.

### 5. Keandalan dan performa

- Unduhan besar tidak boleh menyebabkan OOM. Gunakan buffer tetap dan backpressure.
- Batalkan pekerjaan dengan benar saat klien memutus koneksi.
- Tangani: folder dihapus atau izin dicabut, penyimpanan tidak tersedia, port bentrok, dan proses tunnel gagal start.
- Setelah proses aplikasi dibunuh sistem, pulihkan state yang benar (server mati, status akurat di UI).

### 6. Pengujian

- Unit test: pembuatan token, kedaluwarsa, hashing password, resolusi path, parser Range, sanitasi nama file, rate limiter.
- Integration test server (Ktor test host): 200, 206, 404 identik untuk token tidak valid, 429, integritas ZIP, streaming file besar dengan batas memori.
- UI test Compose untuk alur utama.
- Dokumentasikan matriks uji manual: Android 8, 10, 13, 14, 15, 16 dan perangkat dengan pembunuh baterai agresif (Xiaomi, Oppo, Samsung). Skenario: file lebih dari 4 GB, nama file Unicode dan spasi, batal di tengah unduhan, ganti WiFi ke data seluler, memori rendah.

### 7. Rilis produksi

- R8/minify dengan keep rules yang benar, signing lewat keystore dari variabel lingkungan (jangan masuk repo), versionCode/versionName otomatis.
- CI GitHub Actions: build, unit test, lint, detekt, ktlint, dan `assembleRelease` saat tag.
- Dokumen: `README.md`, `SECURITY.md`, `PRIVACY.md` (tidak ada data yang dikumpulkan), `THIRD_PARTY_NOTICES.md` (cloudflared dan Ktor berlisensi Apache-2.0), `docs/CLOUDFLARED.md`, `docs/DECISIONS.md`.
- Siapkan teks justifikasi deklarasi foreground service `specialUse`, jawaban formulir Data Safety, dan aset listing. Siapkan juga distribusi alternatif lewat GitHub Releases (APK) dan F-Droid.
- Tambahkan catatan di aplikasi bahwa pengguna bertanggung jawab atas kepatuhan terhadap syarat layanan Cloudflare dan hukum yang berlaku untuk file yang dibagikan.

### 8. Risiko yang harus diverifikasi di perangkat nyata

- **DNS cloudflared di Android**: binary Go statis bisa gagal me-resolve DNS karena Android tidak punya `/etc/resolv.conf`. Verifikasi di HP sungguhan. Jika gagal, build cloudflared dari source untuk target Android atau patch resolver (rujuk paket cloudflared di Termux) dan catat di `docs/CLOUDFLARED.md`.
- **Eksekusi binary di Android 10 ke atas**: pastikan jalur `nativeLibraryDir` benar-benar berfungsi di semua API yang didukung.
- **Foreground service dibunuh vendor**: ukur seberapa lama server bertahan dengan layar mati.
- **Batas Cloudflare quick tunnel**: dokumentasikan perilaku dan batas yang ditemukan.

### 9. Cara kerja yang saya inginkan

Kerjakan per milestone. Di akhir tiap milestone: pastikan build dan tes lulus, ringkas hasilnya, catat keputusan di `docs/DECISIONS.md`, lalu lanjut. Tanya saya hanya jika benar-benar terblokir. Jangan mengarang API library: cek dokumentasi dan versi sebelum dipakai.

- **M0**: scaffolding proyek, version catalog, CI, lint.
- **M1**: server lokal, pemilih folder SAF, unduh dengan Range (uji lewat WiFi lokal).
- **M2**: model share di Room, token, kedaluwarsa, password, halaman web publik, ZIP.
- **M3**: foreground service, notifikasi, siklus hidup, pemantauan jaringan.
- **M4**: tunnel cepat (cloudflared), parsing URL, restart otomatis.
- **M5**: tunnel permanen dengan token, layar pengaturan.
- **M6**: poles UI, QR code, lokalisasi, aksesibilitas.
- **M7**: hardening keamanan, tes lengkap, optimasi performa.
- **M8**: persiapan rilis dan dokumentasi.

### 10. Di luar cakupan v1

Unggah dari klien, akun multi-pengguna, sinkronisasi dua arah, iOS, dan penyimpanan cloud eksternal. Boleh dicatat sebagai ide untuk v2.
