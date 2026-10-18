# AVAS protocol 2 — pakej laptop, 14 Oktober 2026

Kemas kini pilihan 15 Oktober 2026: gunakan set lengkap dalam [panduan penyelarasan](AVAS_SELECTION_ALIGNMENT_20261015.md). Helper baharu memerlukan `selection.revision` 20261015, JAR/checksum sepadan dan pemerhati OEM baharu. Pakej ini masih calon laptop; pemasangan pada kereta belum dibuat.

Untuk IHU801P, Android 10/API 29 dan ADB root sahaja. Bukti laptop tidak membuktikan keserasian SKU/HAL atau bunyi fizikal pada kereta lain. Fail vendor asal tidak ditulis terus. Mount sementara dipasang semula melalui boot helper menggunakan sumber/hash dan pilihan slot/suis yang disimpan. Hook pada IHU mesti disahkan berasingan; fail laptop sahaja tidak memasang persistence pada kereta.

## Toolbox

Buka EMAS_TOOLBOX_DESKTOP/EmasTool.exe dan tab Sound Manager & AVAS. Preset dan upload kini menggunakan enjin transaksi Java yang sama dengan daemon. Toolbox mengesahkan format audio, manifest, SHA-256, model dan root; menghantar JAR dan audio ke laluan unik; kemudian menjalankan enjin sekali melalui ADB. Ia tidak perlu menghentikan atau memasang daemon HTTP lama. Ia tidak memasang init/boot persistence.

Jika bunyi atau servis gagal, baca status ROLLBACK_VERIFIED / ROLLBACK_UNVERIFIED. Jangan cuba berulang kali jika keadaan tidak diketahui. Kejayaan fail/mount/servis bukan pengesahan bunyi pembesar suara.

## Pemasangan daemon HTTP berasingan

Panduan tiga push lama tidak lengkap. Selepas sasaran/model/root/SKU disahkan dan pemasangan diluluskan secara berasingan, pindahkan semua item allowlist manifest.json: JAR, launcher, avas_cli.sh, daemon.sha256, tiga preset dan tiga sandaran. Semak SHA-256 setiap fail remote terhadap manifest sebelum pengaktifan. Jangan menggantikan fail aktif atau token/config/sandaran yang sedia ada tanpa pemeriksaan konflik; gunakan staging dan simpan versi lama.

Launcher memerlukan /data/local/avas/avas_sound_daemon.jar dan daemon.sha256 yang sepadan. Ia tidak mematikan instance lama. Ia menolak instance lama yang tidak sihat/tidak serasi untuk pemulihan eksplisit. API ialah 127.0.0.1:8889, semua endpoint kecuali OPTIONS memerlukan X-AVAS-Token dari /data/local/window/avas.token, 48 aksara hex. Jangan menyalin token ke laporan atau URL.

## Transaksi dan pemulihan

Audio senyap, WAV terpotong, saiz RIFF/data salah dan format bukan PCM16 mono 48kHz ditolak. Sumber baharu disimpan dengan nama unik; sumber aktif tidak ditimpa. Bind baharu dipasang sebagai lapisan di atas mount lama. Jika transaksi gagal, hanya lapisan yang dibuat transaksi itu boleh dibuka; hash dan depth lama disemak, dan HAL dipulihkan selepas reload yang gagal. Lapisan dihadkan kepada 8 per sasaran; apabila had tercapai, operasi ditolak untuk pemeriksaan manual, tanpa reboot automatik.

Tuning/chime vendor disalin sebelum perubahan pertama hanya jika sasaran itu belum mempunyai overlay. Setiap salinan disertakan hash. Overlay lama tanpa sandaran yang dikenal pasti menyebabkan FACTORY_STATE_UNKNOWN; jangan anggap tuning custom itu fail kilang. Restore memasang WAV sandaran serta tuning/chime yang telah disimpan. Ia tidak membuka semua mount secara membuta tuli. Muting chime melalui API/skrip lama disekat sementara kontrak warning reverse belum disahkan secara fizikal.

Konfigurasi disimpan secara atomik selepas transaksi berjaya. Proses HTTP, CLI dan guard megafon berkongsi direktori /data/local/avas/output.BOOT_ID.lock; operasi lain ditolak ketika kunci wujud. Kunci pada boot yang sama tidak dianggap stale secara automatik. Boot ID baharu menggunakan kunci baharu. Guard melepaskan kunci miliknya hanya selepas pemain berhenti, servis OEM berjalan dan property dipulihkan; kunci operasi AVAS yang crash perlu diperiksa sebelum dibersihkan.

Hentikan daemon bukan pemulihan audio: mount aktif boleh kekal. Jangan gunakan arahan umount pukal dan kill HAL dari SOP lama apabila fail vendor asal belum disahkan. Snapshot rollback dan fail staging disimpan untuk pemulihan; jangan padam folder AVAS secara pukal.

## Boot dan skrip lama

resume_avas_on_boot.sh menyemak checksum dan menjalankan satu operasi resume untuk semua sumber custom/tuning yang disimpan. Semua path/hash/WAV disahkan sebelum mount pertama. Ia tidak menggantikan config dengan factory. Pemilihan slot dan suis ON/OFF disimpan berasingan; reload HAL menyemak semula property supaya reset dalaman tidak meninggalkan pilihan ON sebagai OFF.

avas_fix.sh dan init_avas_bind.sh ialah alias kepada helper tersebut. Bahagian AVAS dalam wrapper resources/thermal juga menggunakan helper yang sama; tugas asal wrapper dikekalkan. avas_boot.sh mengekalkan pelancar mod lain. Helper berjaya sekali bagi setiap boot; kegagalan tidak dimainkan semula secara automatik. sync_avas.sh menyimpan pilihan UI tanpa kill/restart HAL, dan menunggu jika output sedang sibuk.

Konfigurasi lama tanpa hash tidak dimigrasikan secara senyap ketika boot. Selepas memasang pakej ini, pasang semula custom/tuning yang dipilih melalui enjin baharu dan sahkan config mengandungi persistence_version=1 serta hash sumber. Jangan padam sandaran/config lama. Sebelum mengganti hook, padankan fail yang dipasang pada IHU dengan snapshot dan sahkan boot integration. Pakej ini belum membuktikan persistence pada kereta atau pematuhan akustik secara fizikal.
