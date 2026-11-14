# PocketShare - Manual Test Matrix

Jalankan matriks ini di perangkat fisik. **Jangan gunakan emulator** untuk uji tunnel akhir.

## Perangkat

| API | Contoh          | Catatan        |
|-----|-----------------|----------------|
| 26  | Android 8.1     | Base line      |
| 30  | Android 10      | Eksekusi binary|
| 33  | Android 13      | Izin notifikasi|
| 34  | Android 14      | FGS pembatasan |
| 35  | Android 15      | specialUse     |
| 36  | Android 16      | Next-gen check |

Perangkat agresif: Xiaomi HyperOS, Oppo ColorOS, Samsung OneUI.

## Skenario

| # | Skenario                                      | Platform/keadaan          | Ekspektasi |
|---|-----------------------------------------------|---------------------------|-----------|
| T1 | Quick tunnel: buat share, mulai, buka link    | Wi-Fi                     | 200 di browser; link berubah tiap start |
| T2 | Named tunnel: token + hostname, port tetap    | Wi-Fi                     | URL `https://<host>` stabil; reconnect bertahan |
| T3 | Unduh file >4 GB dengan Range                 | Wi-Fi                     | Resume berhasil; no OOM |
| T4 | Folder ZIP dinamis, nama Unicode + spasi      | Wi-Fi                     | ZIP terbuka; nama UTF-8 benar |
| T5 | Password proteksi: salah benar, 429           | Wi-Fi                     | 5 percobaan → 429 backoff; akses benar |
| T6 | Token tidak valid / expired / revoked         | Wi-Fi                     | Halaman 404 identik, tidak ada enumerasi |
| T7 | Ganti Wi-Fi → seluler (wifi-only=aktif)       | Data                      | Henti otomatis + notifikasi |
| T8 | Ganti seluler → Wi-Fi / airplane mode         | Seluler                   | Cloudflared reconnect |
| T9 | Layar mati lama → server bertahan             | Android agresif           | Tunnel tetap jalan sampai auto-stop/battery kill |
| T10| Folder dihapus / izin dicabut                 | App running               | Error akurat; UI menampilkan error binary missing |
| T11| Aplikasi dibunuh sistem → restart UI          | Semua                     | Named: service/tunnel pulih otomatis; quick: status "Stopped", service mati |
| T12| Nama file emoji, newline tersembunyi          | Wi-Fi                     | Escape aman, HTML aman |
| T13| Battery optimization diizinkan                 | Xiaomi/Oppo               | Notifikasi tetap, sesi panjang |
| T14| Bahasa Indonesia ↔ Inggris ↔ Sistem           | Settings                  | Semua UI berpindah, settings tersimpan |
| T15| Dark mode                                     | Semua                     | Warna kontras terjaga |
| T16| Export log, clear log, diagnostik             | Diagnostics               | Log tersamar, no-secrets note |
| T17| QR dialog, copy/share link                    | Berbagi                   | QR terbaca; copy tidak mengunggah |
| T18| Ubah masa berlaku                            | Berbagi                   | Nilai tersimpan |
| T19| Port bentrok                                  | Debug                     | Error UI jelas, tunnel stop |
| T20| Tidak ada internet                            | Airplane                  | Cloudflared timeout/error, bukan hang |
| T21| Thumbnail gambar di daftar file publik        | Wi-Fi                     | Thumbnail termuat via token `?thumb`; tidak memakai kuota unduhan |
| T22| Dialog pratinjau gambar: prev/next, fullscreen | Wi-Fi                     | Dialog native terbuka, navigasi antar file, fullscreen, unduh dari dialog |
| T23| Pratinjau video/audio + seek                  | Wi-Fi                     | Media diputar di dialog; seek memakai Range 206 |
| T24| Pratinjau PDF dan teks                        | Wi-Fi                     | PDF tampil di frame; teks ter-escape dan tidak mengeksekusi markup |
| T25| Pratinjau dokumen kantor                      | Wi-Fi                     | DOCX/XLSX/PPTX/ODF/RTF dan .doc/.xls/.ppt ter-render; fallback teks bila gagal |
| T26| Seleksi file + ZIP parsial                    | Wi-Fi                     | Pilih semua/none bekerja; `zip?ids=` hanya memuat file terpilih, nama entry aman |
| T27| Kuota unduhan vs pratinjau                    | Wi-Fi                     | Thumbnail tidak mengurangi kuota; pratinjau/unduh penuh mengurangi kuota |
| T28| Popup "What's new"                             | Upgrade 1.0.4 → 1.0.5, 1.0.5 → 1.1.0 | Popup tampil sekali setelah upgrade dan tidak muncul lagi setelah ditutup |
| T29| DNS Android tanpa hotspot/dnsmasq              | Seluler, hotspot mati     | API quick tunnel, SRV, dan features memakai `TUNNEL_DNS_SERVERS`; edge memakai `TUNNEL_EDGE`; link tetap 200 |

## Hasil uji

Perangkat uji utama: **Realme 3 (RMX1821), Android 10 / API 29, arm64-v8a**, data seluler
(4G). Uji awal 10–11 November memakai VPN NextDNS/RethinkDNS; uji named tunnel 12 November memakai
Private DNS Cloudflare (`one.one.one.one`) setelah RethinkDNS dihentikan. Uji dilakukan pada
10–12 November 2026. Kolom "Hasil" hanya diisi untuk perangkat/API yang benar-benar dijalankan;
API lain belum diuji di perangkat fisik.

Status 13 November 2026: rilis 1.0.5 (code 15) terpasang lalu di-upgrade ke 1.1.0 (code 16).
T21–T27 lulus pada 1.0.5; T28 kini lulus lewat upgrade 1.0.5 → 1.1.0 (popup "Yang baru"
muncul sekali). Sesi ini juga menemukan dan memperbaiki bug preview dokumen khusus Android
(`isXIncludeAware`), lihat catatan di bawah.

Status 13 November 2026 (lanjutan): **1.1.0 (code 16)** menambahkan status `TIDAK STABIL`, probe
`/healthz` end-to-end, watchdog `/ready`, dan pemulihan named tunnel setelah proses dibunuh. Uji
quick + named tunnel, degradasi, soak, layar mati, dan `START_STICKY` lulus; detail di bagian
"Sesi 13 November 2026".

| ID | Hasil | Catatan |
|----|-------|---------|
| T1 | LULUS | Quick tunnel dibuat 3×; URL berubah tiap start; halaman share 200 lewat tunnel. |
| T2 | LULUS | Named tunnel `pocketshare` + `pocketshare.my.id` (HTTP/2): 4 koneksi edge, halaman 200, unduhan 200 45 B, Range 206, root 404, Firefox ponsel OK. |
| T3 | LULUS | File sparse 4,83 GB; Range di offset 4.831.838.000 dan 1.000.000.000 → 206; suffix range → 206; di luar EOF → 416; server tidak OOM. |
| T4 | LULUS | ZIP folder 4 file (termasuk `Nama Unicode 日本語 é.txt`, `sub dir/nested.txt`); flag UTF-8 (0x800) terpasang; hash isi cocok. |
| T5 | LULUS | 5 percobaan salah → 401, percobaan ke-6 → 429 `Retry-After: 900`; password benar → 303 + cookie `HttpOnly; Secure; SameSite=Lax`. |
| T6 | LULUS (sebagian) | Revoked, exhausted, dan token tidak valid menghasilkan 404 byte-identik (SHA-256 sama). Expired belum diuji manual; logika ditutup unit/integration test. |
| T7 | LULUS | "Hanya Wi-Fi" diaktifkan saat seluler → sesi berhenti, service hilang, tunnel 530, UI menampilkan alasan yang jelas. |
| T8 | LULUS | Data seluler dimatikan ±25 detik → URL quick tunnel tetap sama dan 200 kembali setelah data aktif; cloudflared reconnect tanpa restart. |
| T9 | SEBAGIAN | Layar tidur ±3 menit saat transfer 2×100 MB lewat `adb forward` (tanpa data seluler); service tetap foreground, tunnel 200 setelah bangun. Soak 10–15 menit belum dijalankan. |
| T10 | LULUS | File yang dibagikan dihapus → halaman share tetap 200; unduhan mengembalikan 404 byte-identik. Setelah file dipulihkan, unduhan 200 lagi. |
| T11 | LULUS | `am force-stop dev.pocketshare.debug` mematikan proses dan service; buka ulang → status berhenti, tidak ada service yatim. |
| T12 | BELUM | Belum dijalankan; escaping nama file ditutup unit test. |
| T13 | LULUS | UI menampilkan "Pocket Share tidak dibatasi baterai"; `dumpsys deviceidle whitelist` memuat paket debug. Sesi layar mati 3 menit (T9) tetap hidup. |
| T14 | LULUS | ID ↔ EN ↔ sistem berpindah tanpa restart setelah `AppCompatActivity` + `AppCompatDelegate.setApplicationLocales`; pilihan tersimpan setelah proses dibunuh. |
| T15 | SEBAGIAN | Mode gelap diverifikasi di Beranda, Berbagi, Pengaturan, Diagnostik, dialog QR, dan dialog buat berbagi. Mode terang belum dapat diuji karena ColorOS mengunci `ui_night_mode` (`mNightModeLocked=true`). |
| T16 | LULUS | Ekspor log 33 KB ke Downloads, catatan redaksi ada; hapus log membuat penyimpanan log 0 byte. |
| T17 | LULUS | Dialog QR terbaca; salin tautan dan bagikan lewat chooser bekerja tanpa mengunggah data. |
| T18 | LULUS | Masa berlaku diubah 1 jam → 7 hari; `expiresAtEpochMs` di Room dan UI menunjukkan 19 Nov 2026 15.25. |
| T19 | LULUS | Port 45678 diduduki listener lain → UI menampilkan "Port 45678 sudah dipakai..." dan proses tetap hidup. Setelah listener dihentikan, mulai ulang berhasil di port 45678. Regresi ditutup `PocketShareServerTest`. |
| T20 | TIDAK DIUJI | Percobaan airplane mode tidak memutus internet (VPN/data seluler tetap aktif); pengguna meminta tidak mengulang. Jalur offline ditutup unit test dan fail-fast `error_offline`. |
| T21 | LULUS | Thumbnail ditandatangani (`?thumb=`) dimuat 200 `image/jpeg` 320×240 6.531 byte; tiga permintaan berturut-turut tidak menambah kuota. |
| T22 | LULUS (sebagian) | Dialog native terbuka; gambar 1280×960 termuat; tombol prev/next berpindah ke gambar berikutnya; tombol unduh tersedia. Mode fullscreen belum diuji terpisah. |
| T23 | LULUS (sebagian) | Video diputar di iframe (`readyState 4`, `currentTime` maju, 640×360); Range `bytes=1000-1999` → 206. Audio hanya diperiksa shell `<audio>`; pemutaran audio belum diuji. |
| T24 | LULUS | PDF tampil lewat frame `data:application/pdf`; teks ter-escape dan tampil di iframe; tidak ada markup yang dieksekusi. |
| T25 | LULUS | DOCX (heading+tabel), XLSX (tabel), PPTX (slide berurutan), ODT, ODS, `.doc`, `.xls`, `.ppt` ter-render. RTF hanya dicakup unit test. |
| T26 | LULUS | `zip?ids=` dua ID hanya memuat dua file; nama Unicode flag 0x808; ID tidak valid 404; pilih semua/none memperbarui hitungan 14↔0 dan status tombol. |
| T27 | LULUS | 3× thumbnail tidak menambah kuota; pratinjau penuh + satu unduhan = `2/2`; unduhan berikutnya 404; halaman menjadi 404 dan UI menandai batas tercapai. |
| T28 | LULUS | Upgrade 1.0.5 (15) → 1.1.0 (16) memunculkan popup "Yang baru" dengan judul "Status jujur, tunnel lebih stabil" dan 5 highlight; setelah "Mengerti" popup tertutup dan app langsung menampilkan sesi named tunnel yang aktif. |
| T29 | LULUS (tanpa hotspot-off) | Binary patched `2026.10.7-pocketshare.1` dipakai; `TUNNEL_EDGE` mengatasi penemuan SRV lewat DNS Android, `TUNNEL_DNS_SERVERS` diambil dari netd via netd `getLinkProperties`. Hotspot-off: belum dijalankan karena `ap0` hotspot sedang dipakai client; menunggu persetujuan pengguna. Lihat catatan 14 November + BUILD 14 Nov 2026. |


### Catatan sesi 10 November 2026

Uji T10–T20 ditunda karena ponsel sedang dipakai untuk percakapan dan link quick tunnel aktif
sudah dibagikan. Restart sesi akan mengganti URL `*.trycloudflare.com` dan memutus link tersebut.
Lanjutkan sisa skenario saat ponsel bebas; jangan restart sesi yang sedang dipakai.

Temuan tambahan dari sesi ini:

- `HEAD /s/{token}/download/{id}` mengembalikan 404 karena hanya route `GET` yang didaftarkan.
  Browser dan `curl -O` tidak terpengaruh; ini bukan bagian dari persyaratan, tetapi perlu
  diketahui jika kelak ada klien yang memakai HEAD.
- Saat data seluler mati ±25 detik, UI tetap menampilkan "Aktif" (cloudflared reconnect otomatis).
  Status tunnel yang sesungguhnya baru terlihat dari hasil request; pertimbangkan banner
  "tidak ada internet" bila ingin umpan balik UI yang lebih cepat.
- Link preview WhatsApp sempat menampilkan Cloudflare Error 1033 saat jaringan tidak stabil;
  URL yang sama kembali 200 setelah koneksi pulih. Untuk link yang dibagikan permanen, gunakan
  named tunnel.

## Catatan sesi 12 November 2026

- Redesign premium diverifikasi di perangkat pada mode gelap: Beranda, Berbagi, Pengaturan,
  Diagnostik, dialog buat berbagi, dialog QR, dan halaman publik.
- Quick tunnel diverifikasi ulang dengan URL nyata (antara lain
  `https://butter-singh-olive-suggestion.trycloudflare.com` dan
  `https://eugene-requirement-among-privileges.trycloudflare.com`): halaman share 200, unduhan
  200, Range 206, dan header CSP/keamanan lengkap.
- T10, T11, T13, T14, T15 (gelap), T16, T17, dan T18 lulus. T19 menemukan bug force-close saat
  port manual bentrok; perbaikan dan verifikasinya dijelaskan di bawah.
- APK rilis `dev.pocketshare` (bukan debug) diuji asap dengan quick tunnel nyata
  `https://poll-gonna-mime-reference.trycloudflare.com`: halaman share 200, unduhan 200 (32 byte,
  isi identik), Range `bytes=0-11` → 206, lalu berhenti bersih tanpa proses `libcloudflared`
  tersisa dan tanpa crash.
- T20 tidak dapat diuji karena airplane mode tidak memutus koneksi pada perangkat ini; jangan
  mengulang tanpa persetujuan pengguna.

### Named tunnel + domain `pocketshare.my.id` (12 November 2026)

- Tunnel: `pocketshare` (`f9286505-bebe-42d0-a4c5-582027a81a6b`), ingress
  `pocketshare.my.id` → `http://127.0.0.1:45678`, proxied CNAME ke
  `f9286505-bebe-42d0-a4c5-582027a81a6b.cfargotunnel.com`.
- Named tunnel dipaksa `--protocol http2` karena QUIC berulang kali mati dengan
  `timeout: no recent network activity`. Setelah HTTP/2: 4 koneksi edge (cgk/sin) dan monitor
  kedua 10/10 request 200 (interval 20 detik). Monitor pertama 4/6 200; satu iterasi gagal
  bersamaan dengan `LTE_RRC_ABNORMAL_TIMEOUT` di radio ponsel, lalu cloudflared reconnect otomatis.
- Halaman share `hello.txt` 200 (12.904 byte HTML), unduhan 200 45 byte, Range `bytes=0-9` → 206
  `Content-Range: bytes 0-9/45`, root `/` 404, dan Firefox ponsel menampilkan
  `hello.txt · Pocket Share`.
- DNS: zone Cloudflare sehat (A + AAAA via DoH; check-host 200 dari Spanyol, Iran, Serbia).
  Kegagalan awal berasal dari Private DNS AdGuard (`7ef7e9fa.d.adguard-dns.com`) + VPN RethinkDNS
  yang mengembalikan NXDOMAIN hanya untuk A. Setelah Private DNS diubah ke `one.one.one.one` dan
  RethinkDNS dihentikan, domain resolve di ponsel dan host.
- Release 1.0.1 (code 2) terpasang. `adb install -r` gagal
  `INSTALL_FAILED_VERIFICATION_FAILURE` di ColorOS; `adb install --no-streaming -r` berhasil.
- Restart penuh (force-stop → buka lagi → mulai berbagi): share `hello.txt` dan token bertahan;
  link `https://pocketshare.my.id/s/Kphn2owmVUhNUquloC2n38DTzrP1muJJF_46U8yQGqU` yang sama kembali
  200, unduhan 200 45 byte, Range 206, root 404. Ini membuktikan link named tunnel tetap stabil
  lintas restart aplikasi.

### T19: force-close saat port manual bentrok

Gejala: port lokal diisi 45678, kembali ke Beranda, lalu "Mulai berbagi" langsung menutup aplikasi.

Penyebab: Ktor CIO melakukan `bind()` secara asinkron di `Dispatchers.IO`. Saat port sudah dipakai,
`BindException` dilempar dari coroutine internal engine, bukan dari `startSuspend()`, sehingga
exception tidak tertangkap dan mematikan proses Android.

Perbaikan:

- `PocketShareServer` memeriksa ketersediaan port tetap sebelum memulai Ktor dan melempar
  `PortUnavailableException` yang dapat ditampilkan ke pengguna.
- Coroutine engine diberi `CoroutineExceptionHandler` dan `SupervisorJob`, sehingga kegagalan bind
  yang lolos dari preflight dicatat dan tidak mematikan proses.
- `ShareRuntime` memetakan `PortUnavailableException`/`BindException` ke fase `ERROR` dengan pesan
  "Port 45678 sudah dipakai..." dan tidak lagi melempar exception startup ke service.
- Regresi ditutup `PocketShareServerTest` (port terpakai dan port otomatis).

Verifikasi: dengan listener `nc` di 45678, UI menampilkan pesan port dan proses tetap hidup; setelah
listener dihentikan, mulai ulang berhasil memakai port 45678. Port kemudian direset ke 0 (otomatis).

## Catatan sesi 13 November 2026

- Rilis 1.0.5 (code 15) dipasang di Realme 3 dan diuji lewat quick tunnel
  `https://supervision-throw-cardiovascular-fundamentals.trycloudflare.com` (sesi pagi) dan
  `https://resolution-parks-cakes-investors.trycloudflare.com` (sesi setelah perbaikan).
- Bug ditemukan: seluruh pratinjau DOCX/XLSX/PPTX/ODT/ODS menampilkan "Preview unavailable" di
  Android, meskipun unit test JVM lulus dan unduhan byte-identik. Penyebab:
  `DocumentBuilderFactory.isXIncludeAware = false` melempar `UnsupportedOperationException` pada
  parser Android karena XInclude tidak diimplementasikan; setter berada di luar `runCatching`
  sehingga seluruh `parse` gagal. Perbaikan: kedua setter (`isXIncludeAware`,
  `isExpandEntityReferences`) dibungkus `runCatching`. Setelah build ulang dan instalasi,
  DOCX/XLSX/PPTX/ODT/ODS ter-render terstruktur.
- Verifikasi halaman publik: 14 entri, thumbnail bertanda tangan, toolbar seleksi, dialog preview
  gambar (prev/next), teks di iframe, video diputar, ZIP semua/parsial, Range, favicon, dan 404.
- Kuota: thumbnail tidak memakai kuota; pratinjau penuh dan unduhan memakai kuota. Share berbatas
  2 unduhan berakhir `2/2` dan permintaan berikutnya 404.
- Popup "What's new" belum teramati karena 1.0.5 terpasang sebagai instalasi bersih.
- Named tunnel diuji ulang pada 1.0.5 setelah token dimasukkan kembali: `https://pocketshare.my.id`
  halaman 200 (14 entri), unduhan PDF 200, Range `bytes=1000-1999` → 206, ZIP parsial 200
  (2 file), root 404, favicon 200, dan preview DOCX ter-render. Restart sesi (hentikan → mulai)
  mempertahankan URL dan token; halaman kembali 200. Share lama `Kphn2owm...` 404 karena data
  aplikasi ter-reset saat instalasi, bukan karena regresi kode.
- Cloudflare menyuntikkan beacon Web Analytics (`static.cloudflareinsights.com/beacon.min.js`)
  ke HTML untuk request browser. CSP `script-src 'unsafe-inline'` memblokir eksekusinya; tidak ada
  data analytics yang terkirim. Setelah Web Analytics dinonaktifkan/dihapus untuk zona ini pada
  13 November 2026, dua request browser-like tidak lagi memuat beacon (0 kemunculan; ukuran HTML
  turun dari 64.986 ke 64.619 byte).

## Sesi 13 November 2026: degradasi, readiness, dan pemulihan

Uji perangkat: Realme 3 (RMX1821), Android 10 / API 29, arm64-v8a, data seluler 4G, VPN RethinkDNS
aktif. Build: 1.0.5 (code 15) untuk uji awal dan **1.1.0 (code 16)** untuk build final, dipasang
sebagai `dev.pocketshare.debug` (applicationId terpisah, data aplikasi rilis tidak tersentuh).

- **Quick tunnel + HTTP/2 terkonfirmasi.** Log cloudflared memuat `Settings: ... p:http2
  protocol:http2`, `Initial protocol http2`, dan `Registered tunnel connection ... protocol=http2`.
  Jadi `--protocol http2` diterima dan dihormati juga untuk quick tunnel, bukan hanya named.
- **`/ready` tersedia untuk quick tunnel.** Metrics server di `127.0.0.1:40201`; melalui
  `adb forward`, `GET /ready` membalas `200 {"status":200,"readyConnections":1,...}`.
- **`GET /healthz` end-to-end.** `https://<quick>.trycloudflare.com/healthz` → 204 (~0,9 detik),
  root `/` tetap 404.
- **Putus koneksi (data seluler dimatikan ±40 detik).** `/ready` menjadi 503 dan setelah 15 detik
  supervisor mencatat `Tunnel is not reachable: No active connection to the Cloudflare edge`. UI
  berubah ke `TIDAK STABIL` / "Tautan sementara tidak bisa dibuka", URL publik tetap ditampilkan,
  log cloudflared mencatat `Serve tunnel error`, `Connection terminated`, dan `Retrying connection`.
  `GET /healthz` dari luar membalas **530**. Android masih melaporkan jaringan `VALIDATED` (jalur
  VPN), jadi ini benar-benar menguji kasus "jaringan kata online tapi edge mati", bukan banner
  offline biasa.
- **Pemulihan otomatis.** Setelah data dinyalakan, cloudflared menyambung lagi ±75 detik kemudian
  (backoff internal cloudflared, bukan restart proses), log mencatat `Tunnel reachable again`,
  `/healthz` kembali 204, URL **tidak berubah**, dan UI kembali `AKTIF` tanpa restart sesi.
- **Berhenti bersih.** Tombol hentikan → `Tunnel stopped` + `HTTP server stopped`, tidak ada proses
  `libcloudflared` tersisa, dan `/healthz` menjadi 530.
- **Soak putus-sambung (±16 menit, 3 siklus valid).** `svc data disable` 30–60 detik per siklus,
  sesi quick tunnel yang sama (tanpa restart):
  - Siklus A (pid 2278): `/ready` 503, healthz 530, UI `TIDAK STABIL`, pulih **135 detik**.
  - Siklus B (pid 2278): pulih **±207 detik** (15:14:02 → 15:17:31), karena resolver carrier sempat
    `lookup region1.v2.argotunnel.com on [::1]:53: server misbehaving` dan backoff cloudflared sampai
    64 detik. UI baru `AKTIF` ±60 detik setelah healthz 204 (satu interval probe publik 45 detik).
  - Siklus C (build final, pid 10418): `/ready` 503 dalam **3 detik**, healthz 530, UI `TIDAK
    STABIL`, pulih **23 detik**, UI `AKTIF` 24 detik.
  - PID cloudflared dan URL **tidak berubah** di semua siklus. Satu siklus 60 detik dibuang karena
    `svc data disable` belum efektif (healthz tetap 204) — kegagalan metode uji, bukan aplikasi.
  - MTTR teramati **23–207 detik**, ditentukan backoff cloudflared dan waktu modem kembali.
- **Probe publik tidak menuduh DNS lokal.** `UnknownHostException` diabaikan; hanya balasan 5xx atau
  timeout setelah host resolve yang dihitung `UNREACHABLE`. Ini ketahuan saat soak: resolver host
  uji sempat hanya mengembalikan AAAA untuk hostname quick tunnel, padahal jalur IPv4 sehat.
### Named tunnel dan pemulihan proses

- **Named tunnel produksi.** `pocketshare.my.id`, port lokal 45678, token tersimpan (Keystore).
  Empat koneksi edge HTTP/2 (`cgk01`, `cgk02`, `sin15`, `sin23`), `/ready` 200 dengan
  `readyConnections:4`, `https://pocketshare.my.id/healthz` 204, root 404, UI `AKTIF`.
- **Degradasi named.** Data dimatikan 30 detik: `/ready` 503 dalam 4 detik, setelah 19 detik
  healthz membalas **502** (bukan 530 seperti quick), UI `TIDAK STABIL`, URL tetap. Setelah data
  dinyalakan: healthz 204 dalam **89 detik**, UI `AKTIF` 92 detik, PID cloudflared tetap.
- **`START_STICKY` setelah proses dibunuh.** `am crash dev.pocketshare.debug`: proses aplikasi
  mati, service dijalankan ulang Android, log mencatat `Tunnel stopped` → `Starting NAMED tunnel`,
  empat koneksi terdaftar kembali dalam **±14 detik**, healthz 204, hostname tetap, UI `AKTIF`.
- **Layar mati ±4 menit.** `mWakefulness=Asleep` terkonfirmasi di t+1m dan t+2m; healthz tetap 204,
  `/ready` 200, dan PID cloudflared tidak berubah. Perangkat sedang USB-powered, jadi Doze tidak
  aktif dan app debug tidak ada di battery whitelist; uji Doze/LMK sebenarnya masih perlu charger
  dicabut.
- **Belum diuji:** handover Wi-Fi ↔ seluler saat named tunnel aktif (tidak ada jaringan Wi-Fi;
  perangkat hanya memakai data seluler), dan pembunuhan proses oleh LMK saat Doze (butuh perangkat
  dicabut dari charger).

## Catatan sesi 14 November 2026: DNS Android

- **Gejala.** Dari 13 November malam sampai 14 November pagi, named/quick tunnel gagal berulang
  dengan `lookup region1.v2.argotunnel.com ... [::1]:53: connection refused` dan
  `lookup cfd-features.argotunnel.com ... i/o timeout`. Log mencatat 153 retry dan 306 kegagalan
  resolusi target SRV. Perpindahan seluler/Wi-Fi/VPN tidak mengubah hasil.
- **Penyebab.** Go tidak membaca `/etc/resolv.conf` di Android (file tidak ada) dan jatuh ke
  `[::1]:53`. Hotspot Wi-Fi di rumah memulai `dnsmasq` Android pada `127.0.0.1:53`/`::1:53`
  (`startSoftAp` 09:13:21; DNS PASS 09:21:47), sehingga tunnel tiba-tiba berhasil. VPN RethinkDNS
  tetap aktif sebelum dan sesudah, jadi VPN bukan penyebab.
- **Perbaikan.** Cloudflared dibangun dari source 2026.10.7 dengan
  `scripts/cloudflared-dns.patch`; aplikasi mengirim `TUNNEL_DNS_SERVERS` dan `TUNNEL_EDGE`.
  `--dns-resolver-addrs` tidak dipakai sebagai solusi karena hanya mengatur resolver virtual.
- **Bukti patch.** `TUNNEL_DNS_SERVERS=127.0.0.1:9` → quick tunnel gagal meski `dnsmasq` hidup
  (tidak fallback). `TUNNEL_DNS_SERVERS=192.168.168.30:53,8.8.8.8:53` → API quick tunnel, SRV,
  dan `cfd-features` PASS; `TUNNEL_EDGE=198.41.192.107:7844,198.41.200.233:7844` → koneksi
  terdaftar ke edge numerik.
- **Status 14 Nov 2026.** Aplikasi final 1.1.0 (code 16) dengan binary patched telah
  dibundle/release dan terpasang di Realme 3 (`GEQKOJAYRC8HXOWG`). Sisa uji: mematikan `ap0`
  untuk membuktikan tunnel tetap mendaftarkan diri tanpa `dnsmasq`; menunggu persetujuan pengguna
  karena hotspot sedang dipakai.

## Catatan DNS cloudflared

Catat di sini jika quick tunnel gagal resolve DNS di seluler, dan langkah perbaikannya:

| Perangkat/API | Quick DNS | Named DNS | Catatan |
|---------------|-----------|-----------|---------|
| Realme 3 / API 29 | Berhasil tanpa override | Belum diuji | 10 Nov: seluler 4G + VPN RethinkDNS; resolver terdeteksi 10.111.222.3, 8.8.8.8 |
| Realme 3 / API 29 | Berhasil setelah retry 429 | Belum diuji | 12 Nov: `Tunnelstore request failed` 429 lalu sukses pada percobaan ke-5; lihat `CLOUDFLARED.md` |
| Realme 3 / API 29 | Berhasil (Private DNS Cloudflare) | Berhasil (HTTP/2) | 12 Nov: RethinkDNS dihentikan; A record pulih setelah Private DNS `one.one.one.one`; connector bertahan di 4 edge |
| Realme 3 / API 29 | Gagal massal → berhasil setelah `dnsmasq` hotspot | Gagal massal → berhasil setelah `dnsmasq` hotspot | 13–14 Nov: akar masalah `[::1]:53`; lihat catatan sesi 14 November |
| Realme 3 / API 29 | Berhasil dengan binary patched | Berhasil dengan binary patched | 14 Nov: `TUNNEL_DNS_SERVERS` + `TUNNEL_EDGE`; uji hotspot-off menyusul (menunggu izin pengguna karena hotspot dipakai) |
