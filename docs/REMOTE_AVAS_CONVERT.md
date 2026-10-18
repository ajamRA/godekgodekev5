# eMAS Remote 2.12 — upload lagu dan penukaran WAV 13–15 saat

Pilih lagu dalam AVAS Custom. App menukar audio pada telefon kepada WAV PCM 48 kHz, mono, 16-bit. MP3 dan M4A diuji dengan fail sebenar melalui Web Audio browser. WAV sedia ada yang sudah sah boleh digunakan terus tanpa perubahan byte; format lain bergantung pada decoder Android System WebView telefon. Fail dilindungi DRM atau format yang tidak boleh dinyahkod ditolak dengan mesej.

Fail asal maksimum 20 MiB dan 10 minit. Output AVAS maksimum 15 saat mengikut pilihan pemilik. Panjang lalai 15 saat; boleh dipendekkan kepada 13 saat. Audio asal yang lebih pendek dikekalkan panjangnya. Had 15 saat turut dikenakan kepada WAV yang sudah dalam format betul. Bahagian awal disediakan secara lalai dan masa mula/panjang dipaparkan. Untuk memilih bahagian lain, ubah Mula dan Panjang, kemudian tekan **Sediakan WAV bahagian ini**. Pratonton sentiasa memainkan WAV hasil yang akan dihantar. Perubahan bahagian lagu membatalkan WAV/upload terdahulu supaya fail lama tidak digunakan tanpa disedari.

Selepas mendengar hasil, tekan **1. Muat Naik WAV**, pilih slot, kemudian **2. Gunakan pada IHU**. Penukaran dan pratonton tidak menghubungi kereta atau mengubah AVAS. Upload menyimpan WAV pada server; penggunaan pada IHU masih memerlukan butang dan pengesahan berasingan. Backend dan IHU menyemak format/hash seperti sebelumnya. Penukaran tidak meningkatkan gain, menambah efek atau mengubah tuning.

APK menggunakan package dan sijil tandatangan asal, versionCode 212/versionName 2.12. Pasang sebagai kemas kini pada telefon; tidak perlu uninstall app lama. APK 2.11 yang membenarkan 60 saat telah digantikan oleh calon ini. Tiada APK dipasang pada telefon oleh kerja ini. Server, token, konfigurasi IHU dan persistence AVAS yang telah disahkan selepas reboot tidak diubah dalam kerja penukaran ini.

Sambungan AVAS remote masih memerlukan backend/bridge sedia ada yang dikonfigurasi. Pemasangan pembetulan persistence pada IHU tidak dengan sendirinya menyediakan remote.json atau mengaktifkan AVAS mailbox. Jika app memaparkan “Sambungan AVAS belum disediakan”, jangan anggap penukaran gagal: WAV boleh disediakan, tetapi penghantaran ke IHU memerlukan penyediaan sambungan itu secara berasingan.

## Pengesahan dan batas

Ujian encoder: PCM16, downmix stereo, clipping, pemilihan sampel 13 saat, had 15 saat, senyap/cancellation, nilai rosak dan input terlalu besar. Ujian browser menggunakan MP3/M4A stereo 44.1 kHz serta WAV stereo 22.05 kHz sepanjang 65 saat; hasil 48 kHz mono 16-bit disahkan. Aliran clip/upload/apply, pembatalan WAV lama, pertukaran fail ketika decode masih berjalan, fail rosak, kehilangan respons serta lebar 320/360/390/430 diuji pada laptop.

Decoder sebenar pada telefon pemilik dan bunyi fizikal speaker kereta belum diuji untuk APK ini. Ujian frontend lama test_frontend.cjs mempunyai andaian recorder tanpa window yang tidak lagi sepadan dengan kod recorder semasa; ujian arkib audio-recorder.test.cjs pula merujuk laluan sebelum penyusunan folder. Kedua-duanya bukan bukti kegagalan penukaran dan tidak diubah dalam kerja ini; keputusan ujian berkaitan AVAS direkod berasingan.
