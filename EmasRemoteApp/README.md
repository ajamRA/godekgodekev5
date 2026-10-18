# eMAS Remote 2.1

APK telefon untuk `com.azam.emaswidget`. Pakej dan tandatangan sama dengan aplikasi lama; boleh dipasang sebagai update tanpa uninstall. Ia menggunakan VPS HTTPS terus dan tidak memerlukan komputer atau IP ADB kereta.

Release 2.1 dipasang pada VPS pada 9 Oktober 2026. [Muat turun APK telefon 2.1](https://emas.43-159-63-29.nip.io/download/eMAS_Remote_v2.1.apk). Muat turun penuh disahkan sepadan dengan build. Backup VPS: `/home/ubuntu/emas5/releases/remote-v2-2.1-20261009-f39b62/backup`. SHA-256 APK: `0d928ff7955848b6041c13acdbbf61b2a0a71e078a5927b3324f439d7c91fd1e`.

Laluan versi khusus menyokong GET, HEAD dan sambungan semula Range; service worker memintas laluan APK/API. GET lengkap, hash, HEAD 200 dan Range 206 disahkan pada release 2.1. Finalisasi download pada telefon pengguna masih perlu disahkan pada telefon itu sendiri.

Butang **Buka aplikasi Proton** dikekalkan pada halaman utama dan widget. ACO kekal dimatikan. Tiada login atau refresh token Proton dijalankan oleh versi ini.

Perubahan utama:

- Megafon PTT, maksimum 15 saat, cleanup apabila aplikasi disembunyikan dan pembatalan permission yang lambat.
- Preset yang mempunyai WAV sah sahaja dipaparkan. Preset yang memberi 404 disembunyikan.
- Queue FIFO terhad, ID permintaan untuk mengelak pendua, expiry 45 saat dan pembatalan mesej belum diterima apabila suis OFF.
- Dispatch menunggu ACK, maksimum lima percubaan dengan jarak minimum tiga saat. Respons HTTP yang hilang boleh dipadankan semula menggunakan ID permintaan.
- Butang Semak sambungan menggunakan semakan status sahaja. Sebab mikrofon/preset tidak boleh ditekan dinyatakan di bawah mikrofon. Suis boleh diaktifkan apabila VPS tersedia; penghantaran suara memerlukan IHU bersambung.
- WAV PCM 16-bit 48 kHz mono disahkan sebelum dihantar. Conversion gagal tidak dianggap berjaya.
- Tiada SOC contoh. Bacaan terakhir mempunyai masa; VPS dan IHU mempunyai status berasingan.
- Paparan dimuat daripada aset APK walaupun rangkaian gagal; API mempunyai timeout dan polling dihentikan apabila background.
- Widget membuka megafon dalam aplikasi sebelum menggunakan microphone; rakaman tidak bermula secara senyap dari background. Bacaan widget dilabel sebagai bacaan terakhir.

## Pasang dan gunakan

1. Muat turun APK versi 2.1 daripada pautan di atas atau `artifacts/remote-fix-20261009/phone-release/eMAS_Remote_v2.1.apk`.
2. Buka APK pada telefon dan pilih **Update / Kemas kini**. Jika Android meminta, benarkan pemasangan untuk sumber muat turun itu.
3. Buka eMAS Remote. Kunci VPS pemilik tersedia dalam build ini; boleh ditukar melalui Tetapan.
4. Butang Proton membuka `com.malaysia.emas`; jika belum dipasang, ia menawarkan halaman Play Store.
5. Apabila IHU aktif, aktifkan megafon dan tekan/tahan mikrofon. Benarkan permission apabila diminta. Lepaskan untuk menghantar; jika permission pertama membatalkan cubaan, tekan semula.
6. “Diterima IHU” bermaksud pemain kereta menerima kerja. Uji bunyi sebenar dengan pemerhati di kereta sebelum menganggap speaker berfungsi.

## Build dan ujian

`build.ps1` menggunakan Android API 34, javac, D8, aapt dan kunci tandatangan pemilik yang sedia ada. Kunci tidak disertakan dalam projek ini. Build memerlukan `EMAS_API_KEY` atau parameter ApiKey; jangan masukkan nilai itu ke source control atau log.

`tools/prepare_current_server.py` menghasilkan candidate daripada snapshot semasa yang mempunyai fingerprint tepat. `tools/package_repair.py` membungkus hanya 13 fail yang disemak. `tools/deploy_repair.py` menggunakan credential efektif Docker, memeriksa checksum, membuat sandaran dan memulihkan fail jika semakan gagal. Fail token dan kereta tidak diubah. Skrip `prepare_server.py` dan `deploy_vps.py` ialah aliran release 2.0 terdahulu.

Ujian yang dilaksanakan:

- 32 ujian backend/kontrak terpencil, termasuk FFmpeg sebenar, FIFO, retry, expiry, auth efektif dan pengekalan handler lain.
- 17 semakan logik frontend.
- Ujian browser pada lebar 320/360/390/430 px, tiada POST ketika page load, shortcut Proton, preset gagal, race permission, upload, cleanup dan keadaan offline.
- Kompilasi Android, verifikasi signature v2/v3, alignment APK dan kesepadanan certificate dengan APK lama.
- Semakan HTTPS produksi menggunakan GET sahaja: auth 200/401, API 2.1, tiada POST/ACO pada startup, tiada ralat JavaScript dan empat lebar skrin telefon.

Tampalan daemon untuk mengelak penggunaan WAV lama selepas download gagal telah disediakan secara berasingan. Ia lulus 14 semakan keputusan DEX; belum dipasang pada IHU dan belum diuji pada Android VM/perkakasan. Rujuk `artifacts/remote-fix-20261009/daemon-candidate/PELAN_PEMASANGAN.md` untuk skop tepat.

## Had yang masih perlu diketahui

- Daemon kereta semasa memberi ACK ketika audio masuk ke queue, bukan selepas bunyi disahkan. UI menyatakan had ini; ia tidak mendakwa “Dimainkan”. Memperbaiki pengesahan playback sepenuhnya memerlukan versi daemon dan ujian IHU berasingan.
- Suis OFF menghalang dispatch baharu dan membatalkan kerja belum diterima; audio yang sudah diqueue dalam IHU mungkin selesai dimainkan.
- Daemon semasa tidak menghantar credential pada sync. Kompatibiliti dikekalkan dan hanya klien bernama MegaphoneDaemon menerima kerja megafon; nama klien bukan pengesahan identiti kriptografi. Credential daemon memerlukan kemas kini kedua-dua belah pada masa lain.
- SQLite queue disimpan dalam filesystem container, bertahan selepas restart container. Jika container dibuang/dicipta semula, volume khusus diperlukan untuk mengekalkan sejarah. Pending suara luput dengan cepat supaya tidak dipancarkan lewat.
- Android minimum 8.0. Rakaman, widget dan pelancaran aplikasi Proton sebenar belum diuji pada telefon fizikal yang bersambung ADB; ujian browser/kompilasi tidak menggantikannya.

Sumber projek baharu ialah `D:/apps/emas-ota/EmasRemoteApp/`. Semua perubahan IHU/daemon terdahulu berada di luar release telefon ini.
