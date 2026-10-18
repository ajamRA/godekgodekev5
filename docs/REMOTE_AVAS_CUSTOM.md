# AVAS Custom melalui eMAS Remote Pilot 2.10

15 Oktober 2026. Pakej calon sudah dibina dan diuji di laptop. Belum dipasang pada VPS, telefon atau IHU. Tiada sambungan ke kereta dibuat dalam kerja ini.

## Fungsi untuk pengguna

Bahagian **AVAS Custom** ditambah pada Remote Pilot telefon, selepas Nada Kunci:

1. Pilih slot: Galactic Note (1), Space Walk (2), atau Pixel Technology (3).
2. Pilih fail `.wav`. Aplikasi menyemak PCM 48,000 Hz, mono, 16-bit, data lengkap dan bukan senyap sepenuhnya. Maksimum 60 saat / 8 MiB.
3. Dengar pratonton pada telefon melalui pemain audio. Ini tidak memainkan audio pada kereta.
4. Tekan **1. Muat Naik WAV**. Audio disimpan pada pelayan; bunyi kereta belum berubah.
5. Apabila **IHU AVAS bersambung**, tekan **2. Gunakan pada IHU**, dan sahkan nama fail serta slot yang dipaparkan.
6. Tunggu keputusan daripada IHU. `applied` bermakna daemon mengesahkan perubahan fail/tetapan, bukan pengesahan bunyi fizikal.
7. **Pulih Bunyi Asal Slot Ini** menggunakan sandaran yang disahkan oleh daemon. Ia tidak memadam koleksi WAV atau konfigurasi komponen lain.

Audio WAV dihantar tanpa transcoding, normalisasi, pemotongan atau penjanaan tuning baharu. Trek tema dalam slot dipetakan kepada fail custom; tuning maju/undur sedia ada dan trek bDM dikekalkan pada operasi apply. Slot yang dipilih turut diaktifkan dalam transaksi yang sama. Restore mengekalkan pilihan slot dan ON/OFF semasa.

## Komponen pakej

| Komponen | Fail |
|---|---|
| APK telefon | `phone/eMAS_Remote_v2.10_AVAS.apk` |
| Modul tambahan VPS | `server/avas_remote.py` |
| Contoh pendaftaran pada FastAPI sedia ada | `server/registration.txt` |
| Daemon IHU dengan bridge opt-in | `ihu/avas_sound_daemon.jar` |
| Hash daemon | `ihu/daemon.sha256` |
| Pelancar dan helper AVAS | `ihu/start_avas_daemon.sh`, `avas_cli.sh`, `resume_avas_on_boot.sh` |
| Contoh konfigurasi tanpa credential | `ihu/remote.example.json` |
| Sandaran audio rujukan | `reference_factory/` — daripada imej vendor kajian setempat; jangan anggap sesuai untuk SKU/build lain. |

Sumber projek berada di `D:/apps/emas-ota/EmasRemoteApp/` dan `D:/apps/emas-ota/_src_modules/src_avas/`. Pakej berada di `D:/apps/emas-ota/artifacts/remote-avas-20261015/release/`.

## Pendaftaran VPS yang diperlukan

Modul ini menambah laluan khusus `/api/remote/avas/`. Jangan menggantikan keseluruhan server produksi dengan `server_candidate.py` lama: fail itu hanya calon integrasi setempat dan boleh ketinggalan daripada produksi. Semak server yang benar-benar aktif sebelum memasukkan pendaftaran kecil dalam `server/registration.txt`.

Daftar menggunakan `app` dan fungsi `check_key` pelayan sedia ada. Pendaftaran mesti berada selepas kedua-duanya ditakrif. `avas_remote.py` mesti berada bersama modul server dan boleh diimport.

Konfigurasi environment:

- `REMOTE_AVAS_DEVICE_ID`: ID khusus IHU pemilik yang sama dengan `device_id` dalam remote.json IHU. Jika kosong, pertukaran dan upload ditolak.
- `REMOTE_AVAS_AUDIO_DIR`: lalai `/app/avas-audio`, di luar direktori static awam.
- `REMOTE_AVAS_DATABASE`: lalai `/app/avas-state.sqlite3`.

Direktori audio dan fail SQLite mesti berada pada storan yang kekal apabila container dicipta semula. Jangan menimpa pangkalan data, token, fail audio atau environment sedia ada. Modul memerlukan FastAPI/Starlette dan python-multipart yang serasi; versi yang diuji direkodkan dalam manifest pakej.

Semua laluan baharu, termasuk download WAV dan sync IHU, memerlukan `X-API-Key` yang disahkan oleh `check_key`. Tiada credential ACO atau sesi Proton digunakan.

## Komponen IHU yang diperlukan

Daftar sasaran terlebih dahulu: model/root/API/build dan kesesuaian sandaran mesti disemak pada IHU pemilik apabila pemasangan dijalankan. Daemon mengekalkan pemeriksaan root IHU801P API29+ sebelum transaksi; ini tidak membuktikan keserasian semua SKU yang berkongsi nama model.

Konfigurasi opt-in ialah `/data/local/avas/remote.json`, dengan medan:

```json
{
  "base_url": "https://example.invalid",
  "device_id": "owner-ihu801p",
  "api_key": ""
}
```

Contoh ini sengaja tidak boleh beroperasi. Isi origin HTTPS Remote Pilot yang sebenar, ID sasaran yang sepadan dengan VPS, dan kunci pemilik secara persendirian ketika pemasangan. Kunci tidak diletakkan pada URL, log, panduan atau pakej contoh. Token `X-AVAS-Token` kekal dibaca secara setempat daripada `/data/local/window/avas.token` dan tidak dihantar ke VPS.

Bridge tidak bermula apabila remote.json tiada. Apabila diaktifkan, ia bermula selepas daemon berjaya membuka listener loopback dan membuat sync setiap 10 saat. Ia tidak mendaftar atau memasang servis boot lain.

Pemasangan perlu menggunakan staging + hash dan sandaran versi aktif, mengikut `AVAS_INSTALLATION.md`. Pelancar mengekalkan instance lama: ia tidak menggantikan proses yang sedang hidup secara automatik. Sebarang pertukaran proses perlu dilakukan secara eksplisit semasa pemasangan. Jangan anggap mengganti JAR sahaja telah menjalankan kod baharu.

Fail reference_factory ialah WAV rujukan daripada imej vendor kajian, bukan arahan menimpa sandaran IHU. Restore menggunakan `/data/local/avas/factory_backup/{yinheyinfu,space,pixel}.wav`; tuning dan bDM asal menggunakan `factory_state/slot_N/` dengan hash. Sandaran yang sedia ada tidak boleh ditimpa tanpa pengesahan. Jika fail vendor sedang dioverlay tetapi sandaran tuning/bDM belum diketahui, daemon menolak operasi dengan `FACTORY_STATE_UNKNOWN`.

Konfigurasi custom menyimpan sumber immutable dan SHA-256 untuk resume. Reapply selepas reboot bergantung pada helper/hook boot yang benar-benar telah dipasang dan disahkan pada IHU; `reapply_on_boot` dalam respons bukan bukti hook boot tersedia.

## Kontrak dan keputusan

| Laluan | Tujuan |
|---|---|
| GET `/api/remote/avas/state` | Readiness, last_seen, slot semasa dan arahan terakhir |
| POST `/api/remote/avas/upload` | Multipart satu fail WAV; tiada perubahan kereta |
| POST `/api/remote/avas/apply` | JSON slot, upload_id dan request_id; queue apply+activate |
| POST `/api/remote/avas/restore` | JSON slot dan request_id; queue pemulihan |
| GET `/api/remote/avas/jobs/{id}` | Keputusan satu arahan |
| GET `/api/remote/avas/audio/{upload_id}` | WAV immutable dengan pengesahan hash |
| POST `/api/remote/avas/sync` | Handshake IHU, claim satu arahan dan pengesahan hasil |

Readiness memerlukan ID tepat, sync kurang 30 saat, bridge protocol 1, daemon protocol 2, audio revision `20261014`, dan status daemon sebenar `ok=true`, `is_android=true`, `protocol_version=2`. Audio revision ini mengekalkan kontrak audio projek sedia ada; bridge baharu dikenal pasti oleh handshake tersendiri.

Arahan queue tamat tempoh selepas 120 saat. Pelayan menandakan `dispatching` secara durable sebelum memulangkan arahan. Arahan tidak dihantar semula selepas claim. `request_id` yang sama menghasilkan arahan sama; ID sama dengan payload lain ditolak.

Bridge mengesahkan origin/path, panjang, SHA-256 dan struktur WAV sebelum memanggil daemon loopback. Redirect ditolak. Ia menyimpan ledger sebelum meminta perubahan. Crash atau keputusan HTTP yang tidak pasti menghasilkan `uncertain`; tiada ulang mutasi. Hasil terminal dihantar semula sebagai ACK jika sambungan hilang, tanpa mengulangi perubahan fail.

Penolakan lokal jelas seperti `AUDIO_BUSY`/validation disimpan sebagai `failed`; timeout, HTTP500 dan respons tidak sah kekal `uncertain`. Keputusan server-timeout boleh dipulihkan oleh ACK tepat yang tiba lewat. Keputusan `uncertain` daripada daemon menghalang arahan baharu sehingga operator menyemak dan merekonsiliasi keadaan sebenar. Jangan padam ledger atau membersihkan lock secara automatik.

Ledger IHU menyimpan maksimum 4,096 ID. Apabila penuh, bridge berhenti menerima operasi baharu sehingga penyelenggaraan eksplisit. Sumber audio operasi yang tidak pasti dikekalkan kerana daemon mungkin masih memprosesnya.

## Pembetulan suis OEM

Kajian binari asal mengesahkan `persist.avas.switch`: **0=ON, 1=OFF**. Konfigurasi dan property shadow projek menggunakan **1=ON**. `AvasProperties` kini membezakan kedua-dua representasi, mengutamakan bacaan OEM berbanding shadow yang ketinggalan, dan menyimpan nilai semantik yang betul dalam config. Ini diuji secara mock; tingkah laku perkakasan belum diukur.

## Bukti setempat dan had

- 16 ujian backend terpencil lulus: auth, WAV, sasaran tepat, idempotency, claim sekali, timeout/ACK lewat, restore dan integriti audio.
- Integrasi server_candidate lulus dengan WAV Pixel asal; bait download sama dengan input, handshake/claim/ACK berfungsi, endpoint Remote lama tersedia dan ACO kekal dimatikan.
- 5 kumpulan ujian parser/label telefon lulus; browser mengesahkan empat lebar 320/360/390/430 px, preview, upload berasingan daripada apply, pemilihan slot restore dan tiada retry apabila respons hilang.
- 37 semakan bridge JVM lulus. Suite AVAS sedia ada meliputi daemon HTTP, encoder, 12 senario transaksi/fault, validasi dan 20 assertion persistence, termasuk apply+activate dan polariti suis OEM.
- Kompilasi terhadap Android API29 sebenar dan pembungkusan DEX/JAR lulus.
- APK telefon ditandatangani dan alignment disahkan; sijil sama dengan aplikasi pemilik sedia ada. Pakej/version code: com.azam.emaswidget / 210 / 2.10.

Semua ujian menggunakan laptop, fixture atau mock. Tiada playback fizikal, ukuran dB, semakan pematuhan akustik, ujian reboot kenderaan, pemasangan telefon, deployment VPS atau deployment IHU dilakukan. Pakej ini belum boleh dianggap aktif hanya kerana APK dipasang: komponen VPS dan bridge IHU juga diperlukan.
