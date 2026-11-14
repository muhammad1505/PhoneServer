# Privacy Policy

**PocketShare (`dev.pocketshare`) — 9 November 2026**

## Ringkasan

PocketShare tidak mengumpulkan, memproses, atau mengirimkan data pribadi pengguna ke pihak mana pun. Aplikasi berjalan sepenuhnya di ponsel pengguna.

## Data yang tidak dikumpulkan

PocketShare tidak mengumpulkan apa pun, termasuk:

- tidak ada data lokasi, kontak, atau isi file,
- tidak ada alamat jaringan pengirim,
- tidak ada nomor perangkat,
- tidak ada log terpusat atau telemetri,
- tidak ada data penggunaan atau analytics,
- tidak ada akun pengguna,
- tidak ada iklan atau pelacak.

Satu-satunya jaringan keluar adalah koneksi yang diinisiasi oleh `cloudflared` ke Cloudflare untuk menjalankan tunnel. Token tunnel disimpan terenkripsi di perangkat dan hanya ditambahkan jika pengguna mengkonfigurasi named tunnel sendiri.

## File yang dibagikan

File tetap berada di ponsel pengguna. Yang dipublikasikan adalah tautan rahasia ke Cloudflare. Pengguna bertanggung jawab mematuhi ketentuan layanan Cloudflare dan hukum yang berlaku untuk file yang dibagikan.

## Izin aplikasi

- `INTERNET`, `ACCESS_NETWORK_STATE` — tunnel dan monitoring jaringan.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` — servis status berbagi.
- `POST_NOTIFICATIONS` — pemberitahuan status (permintaan di Android 13+).
- `WAKE_LOCK` — wake lock parsial untuk sesi panjang.
- Storage Access Framework — dipilih pengguna untuk konten file; tidak ada izin path.

## Penyimpanan dan penghapusan

Semua data tersimpan di perangkat pengguna dan dapat dihapus kapan saja lewat **Pengaturan** atau uninstall aplikasi.

## Model ancaman dan batas privasi

- **TLS berakhir di Cloudflare.** Koneksi browser → Cloudflare dan Cloudflare → ponsel memakai HTTPS,
  tetapi Cloudflare edge memegang plaintext request/response. Secara teori Cloudflare dapat melihat
  isi file, nama file, token di URL, dan password yang dikirim ke form unlock. Kebijakan privasi
  Cloudflare membatasi penyalahgunaan, tetapi itu jaminan kebijakan, bukan jaminan kriptografi.
- **Cloudflare dapat menyuntikkan skrip.** Pada pengujian 13 November 2026, Cloudflare menyisipkan
  `static.cloudflareinsights.com/beacon.min.js` (Web Analytics) ke HTML saat request berasal dari
  browser. CSP PocketShare (`script-src 'unsafe-inline'`) memblokir skrip itu sehingga tidak dapat
  dieksekusi atau mengirim data. Web Analytics untuk zona `pocketshare.my.id` sudah dihapus pada
  13 November 2026; beacon tidak lagi disuntikkan.
- **Bukan end-to-end encryption (E2EE).** PocketShare 1.0.5 tidak memakai E2EE. Pratinjau dokumen,
  thumbnail, Range video, dan ZIP dibuat di sisi server (ponsel), sehingga server harus melihat
  plaintext. Link adalah bearer secret: siapa pun yang memilikinya dapat mengakses file, kecuali
  dilindungi password, masa berlaku, atau batas unduhan.
- **Yang terlindungi:** ISP/jaringan publik tidak dapat membaca isi karena TLS; file tidak pernah
  diunggah atau disimpan di cloud; token dan password disimpan terenkripsi di perangkat; log lokal
  disamarkan; tidak ada analytics/telemetry dari aplikasi.
- **Yang tidak terlindungi:** Cloudflare yang aktif memodifikasi respons, malware atau akses fisik
  ke ponsel, serta link yang bocor. Jika Cloudflare harus dikeluarkan dari model kepercayaan,
  diperlukan E2EE opsional per share dengan kunci di fragment URL dan dekripsi di browser.

## Pembaruan kebijakan ini

Kebijakan privasi dapat diperbarui jika ada perubahan perilaku aplikasi.
