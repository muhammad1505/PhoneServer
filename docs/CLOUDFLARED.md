# Cloudflared

Dokumen ini mencatat keputusan teknis dan hasil verifikasi tentang `cloudflared`, binary Cloudflare
Tunnel yang dieksekusi sebagai proses anak oleh PocketShare.

## Binary

- Versi dasar upstream: `2026.10.7`; build Pocket Share: `2026.10.7-pocketshare.1`.
- Binary **tidak lagi diunduh sebagai artifact resmi**. `scripts/fetch-cloudflared.sh` memanggil
  `scripts/build-cloudflared-patched.sh`, yang mengunduh source upstream 2026.10.7 (SHA-256
  `d268031cb8906675b6b6353f18a007e6fc028f170094c5ac9220493bc8147016`), menerapkan
  `scripts/cloudflared-dns.patch`, lalu membangun dengan Go `1.27.3` (`CGO_ENABLED=0`,
  `linux/arm64` dan `linux/amd64`).
- Checksum binary yang dikirim: SHA-256 ARM64
  `cfbd697e4fe08acf20d7fcbe10f1e38b2c2b0edfd7fbc6e8d271ae07d77f60bc`; x86_64
  `516a51cd5b9638c8086ee4cdf4f40dbfd2a11385361e48a66b940baebde28987`.
- Untuk referensi, artifact resmi 2026.10.7 yang tidak dipakai lagi memiliki SHA-256 ARM64
  `3df43aedc9ff291d1735b5cb576cb97e0b157488796db2e32b28aecabb445775`; x86_64
  `1f99fb518b83dec2ba8d870a7cc991d82a77e6196295a3d2754ba17ab9f91872`.
- Patch hanya menambahkan resolver DNS; verifikasi TLS edge, token, dan perilaku tunnel tidak
  diubah. Build dari source yang dipin membuat patch dapat diaudit dan dibangun ulang.
- CLI host untuk inspeksi tunnel/DNS: `cloudflared` 2026.10.7 tersedia di
  `~/.local/bin/cloudflared` (di PATH). Kredensial login tidak disimpan di repo; jalankan
  `cloudflared tunnel login` lebih dulu. Token tunnel dan `cert.pem` sengaja dihapus setelah
  verifikasi.

## Eksekusi dan lokasi

- Binary dibangun oleh `scripts/fetch-cloudflared.sh` /
  `scripts/build-cloudflared-patched.sh` dan diinstal ke
  `app/src/main/jniLibs/<abi>/libcloudflared.so`.
- Pada runtime, `tunnel/CloudflaredRuntime.locateBinary()` mencari di
  `applicationInfo.nativeLibraryDir` — satu-satunya lokasi umum di Android 10+ tempat proses anak
  dapat mengeksekusi file yang dimiliki aplikasi.
- Diextract ke lokasi yang sama oleh AGP karena `packaging.useLegacyPackaging = true` /
  `android:extractNativeLibs = true`.

## Mode tunnel

### Quick tunnel (mode default)

```bash
cloudflared tunnel --no-autoupdate --protocol http2 --url http://127.0.0.1:<port>
```

- Sejak 13 November 2026 quick tunnel juga dipaksa HTTP/2. QUIC default sebelumnya diam-diam
  berhenti setelah idle di NAT operator dan baru fallback setelah retry, sehingga link 1033 lebih lama.
- Alamat bersifat sementara di `*.trycloudflare.com` dan berubah setiap kali dimulai.
- API quick tunnel (`api.trycloudflare.com`) memakai resolver Go yang dipatch, jadi DNS Android
  yang tidak punya `/etc/resolv.conf` tidak lagi menjadi penghalang.
- Alamat diekstrak dari log dengan regex banner Cloudflare.

### Named tunnel

```bash
cloudflared tunnel --no-autoupdate --protocol http2 run
```

- `cloudflared version 2026.10.7` **tidak lagi menerima** `--url` saat `TUNNEL_TOKEN` tersedia;
  tunnel cepat harus menggunakan argumen `--url` secara eksplisit.
- Token dikirim lewat environment variable `TUNNEL_TOKEN`, bukan argumen baris perintah, agar tidak
  muncul dalam daftar proses, crash report, atau log kernel.
- Hostname publik ditentukan lewat dashboard Zero Trust; ingress mengarah ke
  `http://127.0.0.1:<port>`.
- Sejak 12 November 2026, named tunnel memakai `--protocol http2`. Di jaringan seluler ini QUIC
  berulang kali mati dengan `timeout: no recent network activity` (UDP NAT operator), sedangkan
  HTTP/2 bertahan pada 4 koneksi edge (cgk/sin). Monitor 10×/20 detik: 10/10 request 200.

## DNS di Android

### Akar masalah (13–14 November 2026)

Android tidak memiliki `/etc/resolv.conf` yang bisa dibaca proses native. Resolver Go di
cloudflared karena itu jatuh ke `[::1]:53`; tidak ada proses yang mendengarkan di sana pada
perangkat normal, sehingga **semua** lookup gagal, bukan hanya SRV:

```text
lookup region1.v2.argotunnel.com. on [::1]:53: read udp [::1]:<port>->[::1]:53: read: connection refused
lookup cfd-features.argotunnel.com on [::1]:53: dial udp [::1]:53: i/o timeout
```

Log 13–14 November mencatat 153 retry cloudflared dan 306 kegagalan resolusi target SRV. Kegagalan
bertahan saat ponsel berpindah seluler/Wi-Fi/VPN, jadi VPN dan konfigurasi Cloudflare bukan
penyebabnya. Di rumah, menyalakan hotspot Wi-Fi memulai `dnsmasq` milik Android pada
`127.0.0.1:53`/`::1:53`; setelah itu DNS cloudflared lolos dan empat koneksi edge named tunnel
terdaftar. Itu adalah efek samping hotspot, bukan perbaikan produk.

`--dns-resolver-addrs` / `TUNNEL_DNS_RESOLVER_ADDRS` **bukan** solusi: flag itu mengatur resolver
virtual untuk layanan origin DNS cloudflared, bukan resolver Go yang dipakai SRV, API quick tunnel,
atau feature selector. Flag tetap dikirim untuk kompatibilitas, tetapi tidak diandalkan.

### Perbaikan yang dipakai

1. **Binary cloudflared dipatch.** `scripts/cloudflared-dns.patch` menambahkan resolver loopback
   di dalam proses. Aplikasi membaca DNS aktif dari
   `ConnectivityManager.getLinkProperties()` dan mengirimkannya lewat `TUNNEL_DNS_SERVERS`.
   Resolver Go memakai server tersebut untuk SRV, `api.trycloudflare.com`, dan
   `cfd-features.argotunnel.com`. Patch mencoba server berikutnya bila terjadi timeout,
   SERVFAIL, atau REFUSED.
2. **Alamat edge diresolusi aplikasi.** `CloudflaredRuntime.edgeAddresses()` me-resolve
   `region1.v2.argotunnel.com` dan `region2.v2.argotunnel.com` lewat resolver Android
   (`InetAddress`), lalu `TUNNEL_EDGE` diisi `IP:7844` numerik. Ini melewati SRV sepenuhnya dan
   menjadi lapisan kedua bila DNS kustom gagal. Sertifikat edge tetap diverifikasi, jadi jawaban
   DNS palsu tidak bisa menyamar sebagai Cloudflare.
3. **Hotspot tidak lagi diperlukan.** `dnsmasq` boleh tetap berjalan, tetapi cloudflared tidak
   bergantung padanya.

### Verifikasi perangkat 14 November 2026

Perangkat: Realme 3 (RMX1821), Android 10 / API 29, arm64-v8a, data seluler 4G, VPN RethinkDNS
aktif, hotspot `ap0` menyala saat pengujian awal.

- `TUNNEL_DNS_SERVERS=127.0.0.1:9` (port tertutup) → quick tunnel gagal
  `context deadline exceeded`, meskipun `dnsmasq` sedang mendengarkan di `127.0.0.1:53`. Ini
  membuktikan resolver yang dipatch tidak diam-diam kembali ke DNS sistem.
- `TUNNEL_DNS_SERVERS=192.168.168.30:53,8.8.8.8:53` → quick tunnel dibuat, `cfd-features`
  berhasil, dan precheck `DNS Resolution region1/region2` PASS tanpa error `[::1]:53`.
- `TUNNEL_EDGE=198.41.192.107:7844` → koneksi terdaftar langsung ke IP tersebut; precheck DNS
  memakai target numerik dan tidak melakukan SRV. Dua alamat dipisahkan koma juga diterima.
- Build aplikasi berikutnya diuji ulang pada named tunnel produksi; lihat
  `docs/TEST_MATRIX.md` untuk hasil akhir dan status hotspot-off.

### Hasil uji perangkat (10 November 2026)

- Perangkat: Realme 3 (RMX1821), Android 10 / API 29, arm64-v8a, data seluler 4G.
- VPN RethinkDNS/NextDNS aktif; resolver terdeteksi `10.111.222.3, 8.8.8.8`.
- **Quick tunnel berhasil tanpa override DNS** di jaringan seluler ini; URL
  `*.trycloudflare.com` terbentuk dan dapat diakses. Jadi kegagalan resolusi tidak selalu terjadi;
  sejak 14 November 2026 resolver tetap dipatch agar tidak bergantung pada keberuntungan ini.
- Named tunnel belum diuji pada tanggal 10 November karena belum ada token/hostname Zero Trust;
  pengujian named tunnel dilakukan pada 12 November 2026 (lihat bagian berikut).

### Sesi 12 November 2026: kegagalan sementara dan backoff

Quick tunnel tetap bersifat best-effort. Pada sesi ini teramati:

- **HTTP 429 dari Tunnelstore.** Log memuat `Tunnelstore request failed` (429) saat meminta tunnel
  baru; supervisor mencoba ulang dan berhasil pada percobaan ke-5. Ini normal saat Cloudflare
  membatasi pembuatan quick tunnel; tidak perlu restart aplikasi.
- **DNS internal cloudflared.** Muncul `lookup api.trycloudflare.com on [::1]:53: connection refused`
  dan `lookup cfd-features.argotunnel.com ... i/o timeout` saat jaringan berubah. Setelah resolver
  sistem stabil, koneksi pulih tanpa mengubah konfigurasi.
- **QUIC idle timeout.** Muncul `timeout: no recent network activity`; cloudflared reconnect dan
  URL quick tunnel yang sama kembali melayani request.
- **Backoff.** `ExponentialBackoff` memakai 1 detik → 60 detik dengan jitter 20%; status UI
  bergerak antara `Starting` dan `Reconnecting`. URL quick tunnel tetap dipertahankan selama
  reconnect, tetapi berubah jika sesi dihentikan lalu dimulai lagi.

Perbaikan parser: respons gagal yang memuat string `api.trycloudflare.com` tidak lagi dianggap
sebagai URL live. URL hanya diambil dari banner tunnel yang valid; regresi ditutup
`CloudflaredLogParserTest` (termasuk kasus 429/failed request).

### Sesi 12 November 2026: named tunnel, HTTP/2, dan DNS domain

- Named tunnel `pocketshare` (`f9286505-bebe-42d0-a4c5-582027a81a6b`) dipakai dengan hostname
  `pocketshare.my.id` dan ingress `http://127.0.0.1:45678`.
- **QUIC flapping.** Log memuat `timeout: no recent network activity` berulang; connector Cloudflare
  sempat hilang dan halaman mengembalikan 530/1033. Setelah `CloudflaredCommand` menambahkan
  `--protocol http2`, connector bertahan dengan 4 koneksi ke edge cgk/sin dan monitor kedua
  membalas 10/10 request 200.
- **Gangguan LTE singkat.** Monitor pertama (6×/20 detik) mencatat 4/6 200; satu iterasi gagal
  (`code 000`) bersamaan dengan `LTE_RRC_ABNORMAL_TIMEOUT` di radio ponsel. cloudflared reconnect
  otomatis dan monitor kedua (10×/20 detik) kembali 10/10 200. Ini gangguan jaringan operator,
  bukan kegagalan tunnel.
- **Akar masalah akses domain bukan tunnel.** Zone dan record A/AAAA Cloudflare benar (diverifikasi
  lewat DoH 1.1.1.1/8.8.8.8 dan check-host.net dari Spanyol, Iran, dan Serbia). Ponsel memakai
  Private DNS `7ef7e9fa.d.adguard-dns.com` dan VPN RethinkDNS (`com.celzero.bravedns`) yang
  mengembalikan NXDOMAIN hanya untuk query A (AAAA tetap ada) sekaligus memblokir IPv6. Setelah
  Private DNS diubah ke `one.one.one.one` dan RethinkDNS dihentikan, A record resolve dan Firefox
  ponsel membuka share.
- **Verifikasi end-to-end.** Halaman `hello.txt` 200, unduhan 200 45 byte dengan
  `Content-Disposition: attachment; filename="hello.txt"`, Range `bytes=0-9` → 206
  `Content-Range: bytes 0-9/45`, root `/` 404, dan halaman tampil di Firefox ponsel.
- **Persistensi restart.** `force-stop` → buka ulang → mulai berbagi memulihkan share `hello.txt`
  dan token named tunnel yang sama; link
  `https://pocketshare.my.id/s/Kphn2owmVUhNUquloC2n38DTzrP1muJJF_46U8yQGqU` tetap 200, unduhan
  200 45 byte, Range 206, root 404. Monitor pasca-restart 5×/20 detik membalas 200 seluruhnya.

### Port lokal

Cloudflared hanya menghubungi `http://127.0.0.1:<port>`. Jika port tetap bentrok, kegagalan berasal
dari server Ktor, bukan cloudflared. Sejak 12 November 2026, `PocketShareServer` memeriksa port lebih
dulu, memasang `CoroutineExceptionHandler` pada engine CIO, dan meneruskan kegagalan sebagai
`PortUnavailableException` agar UI menampilkan pesan yang jelas tanpa force-close.

## Manajemen proses

`CloudflaredTunnelProvider` adalah supervisor:

- `start()` mengganti tunnel sebelumnya, `stop()` menghentikan proses dengan grace 4s, force 1s.
- On-crash: backoff eksponensial (1s – 60s, jitter 20%) dengan penundaan sebelum restart.
- Status UI: `Stopped` / `Starting` / `Running` / `Reconnecting` / `Failed`.
- `Failed` hanya untuk masalah konfigurasi permanen (token tidak valid, binary hilang).
- Watchdog 150 detik melabeli kegagalan koneksi awal; waktu saat ponsel offline tidak dihitung dan
  percobaan berikutnya menunggu jaringan kembali, agar URL quick tunnel tidak berganti-ganti.
- **`Degraded`**: setelah koneksi awal berhasil, supervisor mem-poll endpoint loopback `/ready`
  milik metrics cloudflared tiap 5 detik. HTTP 503 selama 15 detik berturut-turut berarti tidak ada
  koneksi edge aktif, jadi status turun ke `Degraded` tanpa mematikan proses/URL. HTTP 200
  mengembalikan status ke `Running`. Satu `Lost connection with the edge` dari empat koneksi HA
  tidak langsung dianggap outage; `connIndex` dilacak sebagai fallback bila port metrics tidak ada.
- Log cloudflared diparsing; URL quick tunnel, koneksi terdaftar/terputus, alamat metrics, dan fragmen
  error/fatal diterjemahkan ke status UI. Semua baris disamarkan lewat `Redactor`.

## Environment yang dibangun

```properties
HOME                     -> /data/.../cloudflared/home   (credentials, certs)
TMPDIR                   -> /data/.../cloudflared/tmp
XDG_CACHE_HOME           -> /data/.../cloudflared/xdg
NO_AUTOUPDATE            -> true
TUNNEL_LOGLEVEL          -> info
TUNNEL_METRICS           -> 127.0.0.1:0
TUNNEL_TOKEN             -> <encrypted named-tunnel token> (named only)
SSL_CERT_FILE            -> bundle.pem (concatenated system CA stores)
TUNNEL_DNS_SERVERS       -> detected DNS, comma-separated (patched binary)
TUNNEL_EDGE              -> numeric region1/region2 edge IPs:7844 (both modes)
TUNNEL_DNS_RESOLVER_ADDRS -> detected DNS (compatibility only; not used for SRV)
```

## Catatan keamanan

- `--no-autoupdate` menonaktifkan update otomatis cloudflared.
- CA bundle di-merge dari lokasi CA Android karena Go crypto/x509 hanya membaca satu bundle.
- Patch DNS hanya mengganti jalur resolusi; verifikasi TLS edge dan token tetap dilakukan
  cloudflared. Server DNS yang dipakai adalah server yang sudah dikonfigurasi Android/VPN.
- Patch tidak mencatat nama domain atau isi query; ia hanya mencetak jumlah upstream saat mulai.
- Tidak ada data pengguna yang masuk ke log cloudflared; hanya tag, URL tunnel, dan log sistem.
