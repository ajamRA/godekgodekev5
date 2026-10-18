# Pembaikan daemon — calon laptop sahaja

Tiada ADB, sambungan IHU, deploy, restart servis atau ujian fizikal digunakan semasa kerja ini.
JAR lama pada akar projek tidak ditulis semula. Perubahan sedia ada dalam projek tidak direset.

## Hasil akhir

- JAR: `artifacts/daemon-hardened-20260927-203523-513/door_pressure_relief-hardened.jar`
- SHA-256: `0ed585773140ab10fdfb8138d6445d495ad0cdf2407e5043057221e46782d968`
- Audio: `artifacts/audio-candidate/mario-lock-candidate-1.30s.wav`
- Audio SHA-256: `33603d81146803681a32f8e50d959f7b8023f0093d5a906af95fe97be0369fec`
- Java compilation, D8 API 29, packaging dan 96 pemeriksaan harness lulus.
- Harness tidak memanggil `main`, tidak membuka server dan semua dispatch kenderaan diuji melalui mock.
- Binaan percubaan terdahulu mungkin ada dalam direktori artifacts; hanya laluan di atas ialah hasil akhir laporan ini.

## Perubahan

1. Regen menerima integer 1–3 sahaja. Tepat satu payload satu byte dihantar ke area 0. Kegagalan dispatch dipulangkan tanpa retry. `true` bermakna dispatch diterima, bukan pengesahan ECU atau slider.
2. Semua dispatch melalui satu guard jenis property dan area. INT32 kepada BYTES ditolak. Semua penulisan bukan area 0 ditolak, bukan dialihkan ke area 0. Senarai area yang masih ada dalam source hanya digunakan untuk bacaan.
3. Kod calon drive mode ialah Comfort=1, Sport=2, Eco=3, Eco Plus=4. Drive mode, CST dan VBF DISABLING secara lalai (`candidateContractsVerified=false`) sehingga kontrak disahkan. Harness sahaja mengaktifkan kandidat pada mock untuk menguji encoding.
4. Fungsi enforcement, suis enforcement, pemuatan/simpanan nilai locked dan panggilan berkala dibuang. Arahan cloud drive/regen/brake tidak dilaksanakan kerana protokol belum mempunyai ID, expiry dan deduplication yang disahkan.
5. Trunk yang menggunakan payload tekaan dinyahaktifkan. Suhu HVAC yang memerlukan area bukan sifar ditolak tanpa terlebih dahulu menukar dual-zone. Group window write yang diikuti duplicate per-window write diganti dengan satu write per tingkap. Automasi tingkap lain masih memerlukan audit keselamatan fizikal berasingan.
6. Trust-all certificate/hostname override dibuang. Sambungan HTTPS menggunakan pengesahan lalai Android. Keserasian chain sijil VPS belum diuji dan TLS failure tidak dipintas.
7. HTTP terikat pada `127.0.0.1`, 4 pekerja dengan queue 16, socket read timeout 3 saat. Parser mengehadkan saiz baris/header/body, membaca Content-Length sebagai bait, menolak duplicate length dan Transfer-Encoding. Origin browser ditolak; wildcard CORS dibuang. GET dibenarkan hanya pada endpoint bacaan terpilih. Endpoint drive menerima POST JSON satu arahan sahaja, menolak lock/berbilang arahan dan melaporkan kegagalan. UI/bridge lama yang bergantung pada GET/query atau browser fetch terus perlu disesuaikan sebelum deploy. Loopback bukan pengesahan identiti aplikasi Android; token/per-app IPC masih penambahbaikan yang perlu dipertimbangkan.
8. Audio aktif dipasang menggunakan fail sementara unik dalam direktori sama, lock antara proses, checksum, file descriptor sync dan atomic move tanpa fallback delete/copy. Restart radar manual dan killall dibuang. Tiada restart automatik sebagai pengganti.

## Polisi audio dan rollback

Validator menerima WAV canonical sahaja: header 44 bait, PCM format 1, stereo, 48 kHz, 16-bit, byte rate 192000, block alignment 4, chunk RIFF/data sepadan dengan saiz fizikal. Had aplikasi ialah 2 saat, bukan dakwaan had buffer kilang. Metadata WAV tambahan ditolak secara sengaja.

Pemasangan memerlukan fail `V3_Vehicle_Locked_Single.wav.known-good.sha256` di sebelah fail aktif, mengandungi satu hash SHA-256 fail yang telah disahkan berfungsi. Fail itu belum diwujudkan pada IHU. Kod tidak menganggap setiap WAV dengan header sah sebagai known-good.

Jika `.bak` belum ada, fail aktif mesti sepadan dengan hash known-good sebelum disalin secara staging ke `.bak`. Backup sedia ada mesti sah dan sepadan; ia tidak ditindih dengan calon baru. Kandidat disahkan sebelum atomic move, kemudian disahkan semula. Kegagalan selepas pemasangan mencetuskan pemulihan daripada `.bak`; kegagalan rollback dilaporkan, bukan disembunyikan.

Checksum source yang dihitung daemon mengesan perubahan antara bacaan/staging, bukan pengesahan penerbit audio. Kunci hanya melindungi penulis yang menggunakan protokol yang sama. Penulis OEM/luar, permission/SELinux, atomic move pada sdcardfs, hard power loss dan restart semasa transaksi belum diuji pada IHU.

Directory fsync menghasilkan amaran pada Windows; file descriptor sync berjaya. Tiada dakwaan power-loss-proof. Rollback automatik ini meliputi kegagalan transaksi yang dikesan semasa proses hidup, bukan pemulihan crash ketika boot atau kegagalan playback. Tiada monitor kesihatan radar/playback ditambah.

Calon Mario dipotong daripada fail pengguna `lock_mario.wav`, PCM 48 kHz/16-bit/stereo, 62400 frame (1.30 s), 249644 bait. Fade-in 10 ms, fade-out 1.25–1.30 s dan headroom amplitud disediakan. Sample awal/akhir sifar disahkan. Ia belum diuji secara pendengaran atau pada speaker kereta, bukan spesifikasi kilang dan bukan jaminan tiada klik.

## Ujian yang dilaksanakan

- Nilai regen tidak sah tidak menghasilkan write; setiap nilai sah menghasilkan satu byte pada property/area betul.
- Dispatch gagal/disconnected dipulangkan sebagai false tanpa retry.
- Candidate controls tidak menghasilkan write dalam daemon lalai; mapping calon diuji dengan mock diaktifkan.
- Guard menolak nonzero area dan Integer kepada BYTES.
- Enforcement method/field/call sites tiada; hanya satu lokasi reflection VHAL write wujud.
- WAV kosong, header rosak, truncated, oversize, hash salah dan baseline tidak diluluskan ditolak.
- Fail saiz sama tetapi kandungan berbeza benar-benar diganti.
- Backup dipelihara, post-rename failure disuntik dan rollback disahkan melalui hash.
- Lock bersaing ditolak, backup rosak ditolak, fail staging milik transaksi dibersihkan.
- Parser HTTP diuji untuk UTF-8 byte count, request truncated/oversize, Origin, duplicate length dan Transfer-Encoding.
- Calon Mario lulus validator produksi.

Bina semula dengan `build_hardened_daemon.ps1` selepas menghasilkan audio menggunakan `tools/make_mario_candidate.py`. Builder baharu tidak memadam direktori lama, tidak menggunakan ADB dan hanya membungkus JAR setelah harness lulus. Script lama hanya ditambah sourcepath/encoding supaya dependency helper dapat dikompilasi; ia tidak dijalankan untuk kerja ini.

## Batas bukti crash

Tombstone membuktikan VHAL crash dalam `write_bytes`. Log CST menunjukkan DeadObjectException ketika enforcement melakukan write. Kedua-duanya alasan kukuh untuk membuang penulisan spekulatif dan enforcement, tetapi belum membuktikan property/area tertentu ialah punca tunggal, atau crash audio/radio semuanya akibat satu panggilan CST.

## Bukan kelulusan deploy

Ini calon pembangunan. Ia bukan audit lengkap semua feature lama dalam daemon. Laluan AVAS/custom playback, arahan cloud lain, WebView, ketepatan telemetry, hak akses fail dan window anti-pinch masih memerlukan kerja berasingan. Kesesuaian runtime Android, pemetaan mode, pengesahan fizikal audio dan UI sync belum diuji. Kereta tidak disentuh dan deploy memerlukan arahan pengguna seterusnya.
