# PDF TTD — pembuka PDF ringan + tanda tangan

Fitur:
- Buka PDF (termasuk PDF ber-password)
- Zoom halaman dengan cubit (pinch). Double-tap untuk zoom in/out cepat.
- Putar HP ke landscape untuk membaca lebih lega
- Tempel tanda tangan (foto JPG/PNG, background putih dihapus otomatis)
- Simpan salinan atau timpa file asli
- Tanpa iklan, tanpa izin internet

Butuh Android 10 ke atas.

## Fitur baru (v1.1)
1. **Zoom** — Cubit layar untuk memperbesar/perkecil halaman PDF. Geser saat di-zoom untuk pan. Double-tap = zoom 2.5x / kembali normal.
2. **PDF ber-password** — Saat buka PDF terkunci, akan muncul dialog minta password. Password salah bisa dicoba lagi.

## Cara membuat APK (tanpa install apa pun di laptop)
1. Buat akun di github.com, lalu **New repository** (nama bebas, mis. `pdf-ttd`, Private boleh).
2. Ekstrak ZIP ini, lalu upload SEMUA isinya ke repository (Add file > Upload files).
   - Pastikan folder tersembunyi `.github` ikut ter-upload. Kalau tidak ikut:
     Add file > Create new file, ketik nama `.github/workflows/build.yml`, lalu isi dengan isi file `build.yml.COPY`.
3. Buka tab **Actions**. Build jalan otomatis (3–6 menit). Kalau tidak, pilih "Build APK" > Run workflow.
4. Setelah hijau, klik run tersebut, scroll ke **Artifacts**, download `PDF-TTD-apk` (ZIP berisi `app-debug.apk`).
5. Kirim APK ke HP, buka, izinkan "Install dari sumber ini", lalu instal.

## Pemakaian
- Tap file PDF di Download / notifikasi. Pilih **PDF TTD** > **Selalu** supaya jadi aplikasi default.
- **TTD**: pertama kali akan minta foto tanda tangan (kertas putih, tinta gelap, cahaya rata). Tersimpan otomatis.
  Tekan TTD lagi untuk menempel di halaman yang sedang terlihat. Geser = pindah, cubit = ukuran, tekan lama = hapus.
- **Cubit layar** (bukan di atas TTD) untuk zoom halaman. Putar ke landscape lebih nyaman.
- **Ganti TTD**: pilih foto tanda tangan baru.
- **Simpan**: salinan masuk ke `Dokumen/TTD PDF/` atau menimpa file asli.
