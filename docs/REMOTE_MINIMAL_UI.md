# eMAS Remote 2.13 — UI minimal

Reka bentuk menggunakan skill minimalist-ui: latar putih hangat, teks arang, tajuk serif, kad rata berbingkai halus dan aksen status pastel. Tiada font, ikon atau stylesheet luaran perlu dimuat turun.

Megafon, AVAS, nada kunci, hon, suara preset, widget, pautan Proton dan tetapan akses dikekalkan. Navigasi bahagian ditambah; AVAS dipindahkan selepas megafon. Senarai nada dan butiran format boleh dikembangkan; diagnostik ditutup secara lalai tetapi butang asalnya kekal. Kawalan mikrofon masih menggunakan handler rakaman sedia ada. Tiada aliran kebenaran mikrofon, token atau endpoint diubah.

AVAS masih membenarkan pemilihan bahagian sehingga 15 saat, termasuk 13 saat, dan penukaran WAV PCM 48 kHz mono 16-bit. Pilih audio, dengar hasil, muat naik dan gunakan pada IHU kekal tindakan berasingan.

CSS/JS menggunakan pautan relatif. APK membekalkan minimal.css dan minimal-ui.js melalui allowlist aset Android. Status/navigation bar Android dipadankan dengan tema cerah. Package dan sijil tandatangan dikekalkan; versionCode 213, versionName 2.13.

## Pengesahan

Ujian UI pada 320, 360, 390, 430 dan 980 px: tiada overflow, sasaran mikrofon jelas, modal tetapan muat, senarai nada boleh dibuka, diagnostik boleh dibuka/tutup dan navigasi anchor berfungsi. Permulaan halaman dan tindakan navigasi tidak menghantar arahan. Ujian browser AVAS turut meliputi MP3/M4A, pemilihan 13 saat, had 15 saat, fail rosak, pertukaran fail ketika decode berjalan, upload/apply terpisah dan respons hilang. 12 ujian unit audio/WAV lulus.

APK dibina, dijajarkan dan tandatangan dibandingkan dengan APK asal. Kandungan aset dalam APK dibandingkan dengan source. Telefon fizikal belum diuji untuk reka bentuk ini. Tiada pemasangan telefon atau perubahan pada IHU/VPS dibuat dalam kerja reka bentuk ini.

## Pratonton

preview/Remote_Pilot_Preview.html ialah satu fail dengan CSS dan penukaran AVAS terbenam. Ia tidak menghubungi kereta/server dan hanya untuk menilai susun atur serta mencuba penukaran audio. Butang megafon, nada kunci dan hon di pratonton tidak mengendalikan kereta. Dalam APK, fungsi itu menggunakan backend sedia ada. Sambungan AVAS remote masih memerlukan konfigurasi bridge/backend; reka bentuk tidak menyediakan sambungan itu secara automatik.

Fail sebelum perubahan disimpan dalam before pada folder artifact. APK 2.12 dan pakej sebelumnya dikekalkan. Untuk memasang reka bentuk ini, kemas kini telefon menggunakan APK 2.13 tanpa uninstall aplikasi lama.
