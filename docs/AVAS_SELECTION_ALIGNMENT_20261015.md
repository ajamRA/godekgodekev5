# Penyelarasan pilihan AVAS — 15 Oktober 2026

Status: calon laptop sahaja. Tiada pemasangan, ADB atau perubahan pada kereta dibuat semasa penyelarasan ini.

## Perubahan

- OEM `persist.avas.switch`: 0 = ON, 1 = OFF. Config dan shadow projek: 1 = ON, 0 = OFF. Penukaran kini dibuat secara jelas.
- Boot memulihkan slot terakhir dan ON/OFF yang disimpan selepas sumber audio disahkan. Ia tidak memaksa slot 3 atau ON. Pilihan OFF yang disengajakan dikekalkan.
- Pemerhati membaca pasangan property OEM sebenar, bukannya shadow lama. Dua bacaan berurutan mesti stabil; enjin menyemak pasangan sekali lagi sebelum menyimpan. Pemerhatian tidak memulakan semula HAL.
- Helper baharu memerlukan `selection.revision` bernilai 20261015 dan checksum JAR yang sepadan. Penanda boot lama tidak mengesahkan pakej baharu. Kegagalan resume tidak diulang secara automatik.
- Wrapper mengekalkan pelancar lain serta panggilan gamepad yang sebelum ini berada dalam helper audio lama. Laluan audio tidak lagi memanggil helper ORIG yang memaksa suis OFF.

## Bukti laptop

Build Java Android API 29 dan DEX berjaya. Ujian persistence 24 assertions, bridge 37 checks, transaksi 12 senario kegagalan, HTTP 8 ujian serta validation/tuning lulus. Integrasi shell/JVM: 11 ujian lulus, termasuk slot 1–3 dengan ON/OFF, pertukaran Settings OEM ketika shadow lapuk, boot kedua, pilihan sementara, keadaan sibuk, servis tiada dan pakej lama ditolak.

Integrasi menjalankan helper shell sebenar dengan adapter app_process/getprop/HAL pada laptop dan kelas pemetaan/boot sebenar. Ia bukan ujian Android, reboot IHU atau bunyi fizikal. Log: `artifacts/avas-selection-aligned-20261015/shell-jvm-integration.log`; hasil build: subfolder `native`.

## Kandungan dan sempadan pemasangan

Pakej ZIP mengandungi JAR, checksum, selection.revision, CLI, launcher, helper boot, pemerhati, alias dan wrapper sistem sebagai satu set. `manifest.json` merekod SHA-256 setiap fail. Salinan sebelum penyelarasan Toolbox/JAR disimpan dalam `before-laptop-sync`.

Ini bukan installer automatik. `avas_boot.sh` ialah wrapper sistem yang perlu dibandingkan dengan hook sebenar sebelum sebarang pemasangan kemudian; menyalinnya ke folder data sahaja tidak memasang hook. Hook yang diperiksa sebelum ini ialah `/system/etc/init/zzz_avas_boot.rc` -> `/system/bin/avas_boot.sh` -> `/data/local/avas/resume_avas_on_boot.sh`.

Sebelum pemasangan yang diberi kebenaran kemudian: semak identiti IHU/SKU, snapshot fail aktif/config/mount/property, bandingkan hash dengan bukti asal, pentaskan set lengkap dan sediakan rollback. Jangan gantikan remote.json, token, audio, tuning, sandaran atau config pengguna. Config lama mungkin mempunyai slot/suis lapuk; sahkan pilihan pengguna sebelum resume pertama. Jangan jalankan observer lama serentak dengan yang baharu. Proses HTTP lama yang tidak serasi perlu ditangani secara eksplisit; launcher tidak mematikannya secara automatik.

Pakej Remote 2.10 terdahulu kekal sebagai rekod sejarah; jangan campurkan JAR lama dengan helper penyelarasan ini. Gunakan set lengkap daripada calon penyelarasan. Fail WAV preset dan sandaran Toolbox tidak diubah.
