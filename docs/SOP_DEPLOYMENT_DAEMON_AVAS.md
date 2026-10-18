> Dikemas kini 13 Oktober 2026: gunakan protokol 2 dalam [AVAS_INSTALLATION.md](AVAS_INSTALLATION.md). Arahan lama di bawah hanya terpakai selepas semua prasyarat/konflik disemak. Pemindahan fail dan pengaktifan kekal dua tindakan berasingan. Pembaikan laptop ini tidak meluluskan deployment ke IHU.
>
> Fail tambahan wajib: `avas_cli.sh`, `daemon.sha256` dan `manifest.json` daripada pakej baharu. Sahkan setiap hash remote terhadap manifest; jangan menggantikan fail aktif melalui push terus. Jangan pasang pakej ini ke SKU lain hanya kerana nama model sama.

# SOP Deployment Daemon AVAS pada IHU Proton e.MAS 5

Dokumen ini merupakan cadangan pindaan SOP khusus untuk deployment daemon AVAS (`avas_sound_daemon.jar`) pada IHU Proton e.MAS 5. Ia melengkapi SOP_PEMASANGAN_APK.md (yang melarang pemasangan daemon) dengan prosedur terkawal bagi kes daemon AVAS sahaja.

**Status:** CADANGAN — Menunggu kelulusan pengguna sebelum dilaksanakan.

---

## 1. Skop & Had Tindakan

### 1.1 Dibenarkan

* Menyambung ADB ke IP yang disahkan oleh pengguna.
* Semakan status read-only (proses daemon, fail sedia ada, versi).
* Memindahkan (push) fail daemon AVAS dan sokongannya ke `/data/local/avas/`.
* Mengaktifkan daemon AVAS menggunakan skrip launcher sedia ada.
* Mengesahkan daemon berjalan (read-only: `pgrep`, `curl 127.0.0.1:8889`).

### 1.2 Dilarang keras

* Menulis ke partition `/system` atau `/vendor` secara langsung (bind mount dibenarkan melalui daemon sahaja).
* Remount `/system` atau `/vendor` sebagai read-write.
* Reboot IHU.
* Memasang, mengemaskini, atau menyahpasang APK (gunakan SOP_PEMASANGAN_APK.md untuk itu).
* Mengubah skrip startup `.rc` sedia ada tanpa arahan pengguna eksplisit.
* Menjalankan skrip deploy umum (`deploy_noor_energy.ps1` dan seumpamanya) yang mengubah komponen lain selain AVAS.
* Menguji kawalan kenderaan, VHAL, atau fungsi pemanduan.
* Mendedahkan token, kunci, atau rahsia.
* Mengimbas subnet atau mencuba IP selain yang diberikan pengguna.

---

## 2. Prasyarat

Sebelum deployment dimulakan, semua perkara berikut mesti dipenuhi:

| # | Prasyarat | Cara Sahkan |
|---|---|---|
| 1 | IP kereta disahkan oleh pengguna | Pengguna nyatakan IP secara eksplisit |
| 2 | ADB bersambung dan dalam keadaan `device` | `adb -s <IP>:5555 get-state` → `device` |
| 3 | Akses root tersedia | `adb -s <IP>:5555 shell id` → `uid=0(root)` |
| 4 | Artifak AVAS disahkan di laptop | SHA-256 `avas_sound_daemon.jar` = `4ee1e5365fde2ee962c70ba38565f2734199e4135049ff2f1b155da51915432a` |
| 5 | Audit AVAS lulus | `AUDIT_AVAS_20260928.md` — 8/8 ujian laptop lulus |
| 6 | Pengguna meluluskan SOP ini | Arahan eksplisit daripada pengguna |

---

## 3. Semakan Awal pada IHU (Read-Only)

Sebelum memindahkan fail, jalankan semakan status tanpa mengubah apa-apa:

```bash
# 3a. Semak daemon AVAS sedia ada
adb -s <IP>:5555 shell pgrep -f com.emas.avas.AvasSoundDaemon

# 3b. Semak fail AVAS sedia ada
adb -s <IP>:5555 shell ls -la /data/local/avas/

# 3c. Semak versi JAR sedia ada (saiz + hash)
adb -s <IP>:5555 shell sha256sum /data/local/avas/avas_sound_daemon.jar 2>/dev/null

# 3d. Semak skrip boot sedia ada
adb -s <IP>:5555 shell ls -la /data/local/avas/avas_boot.sh 2>/dev/null
adb -s <IP>:5555 shell ls -la /data/local/avas/start_avas_daemon.sh 2>/dev/null
```

**Keputusan yang dijangka dan tindakan:**

| Keadaan | Tindakan |
|---|---|
| Tiada daemon berjalan, tiada fail AVAS | Pemasangan baharu — teruskan Langkah 4 |
| Daemon berjalan, JAR sedia ada | Pengemaskinian — catat hash lama, teruskan Langkah 4 (skrip launcher akan hentikan instance lama) |
| Hash JAR sedia ada = hash calon laptop | Sudah terkini — laporkan kepada pengguna, jangan pasang semula |

---

## 4. Pemasangan (9 Langkah)

Setiap langkah mesti dilaporkan hasilnya sebelum meneruskan ke langkah berikutnya.

**Nota penting:** Hash SHA-256 artifak (`4ee1e53...15432a`) mengesahkan identiti fail sahaja — ia bukan bukti keselamatan pemasangan, keserasian IHU, atau ketepatan fungsi pada perkakasan sebenar.

### Langkah 4.1 — Cipta direktori sasaran

```bash
adb -s <IP>:5555 shell mkdir -p /data/local/avas/presets /data/local/avas/factory_backup /data/local/avas/sounds
```

### Langkah 4.2 — Sandarkan fail sedia ada pada IHU

Sebelum menghentikan atau menggantikan apa-apa, cipta folder sandaran di laptop dan salin fail sedia ada:

```bash
# Cipta folder sandaran di laptop terlebih dahulu (PowerShell)
New-Item -ItemType Directory -Force -Path "D:\apps\emas-ota\artifacts\backup_ihu_avas" | Out-Null

# Sandarkan JAR lama (jika wujud)
adb -s <IP>:5555 pull /data/local/avas/avas_sound_daemon.jar "D:\apps\emas-ota\artifacts\backup_ihu_avas\avas_sound_daemon.jar.bak" 2>/dev/null

# Sandarkan konfigurasi (jika wujud)
adb -s <IP>:5555 pull /data/local/avas/avas_config.json "D:\apps\emas-ota\artifacts\backup_ihu_avas\avas_config.json.bak" 2>/dev/null

# Sandarkan skrip sedia ada
adb -s <IP>:5555 pull /data/local/avas/start_avas_daemon.sh "D:\apps\emas-ota\artifacts\backup_ihu_avas\start_avas_daemon.sh.bak" 2>/dev/null
adb -s <IP>:5555 pull /data/local/avas/avas_boot.sh "D:\apps\emas-ota\artifacts\backup_ihu_avas\avas_boot.sh.bak" 2>/dev/null
```

Jika tiada fail wujud, `adb pull` akan gagal secara senyap. Itu dijangka untuk pemasangan baharu.

**DILARANG menarik token:** Fail `avas.token` tidak disandarkan. Token ialah rahsia — menyalinnya ke laptop menghasilkan salinan teks biasa di luar kawalan IHU. Token tidak perlu disandarkan untuk mengganti JAR; daemon akan menjana token baharu secara automatik jika fail token tidak wujud, atau terus menggunakan token sedia ada pada IHU jika ia masih ada.

**Wajib:** Catat hash SHA-256 setiap fail yang berjaya disandarkan.

### Langkah 4.3 — Hentikan daemon lama (jika ada)

```bash
adb -s <IP>:5555 shell pkill -f com.emas.avas.AvasSoundDaemon 2>/dev/null
```

**Nota:** Sandaran sudah dibuat dalam Langkah 4.2, jadi JAR lama sudah selamat di laptop sebelum daemon dihentikan. Jika tiada daemon berjalan, arahan ini akan gagal secara senyap.

### Langkah 4.4 — Pindahkan JAR daemon

```bash
adb -s <IP>:5555 push "D:\apps\emas-ota\avas_sound_daemon.jar" /data/local/avas/avas_sound_daemon.jar
```

**Pengesahan segera:**

```bash
adb -s <IP>:5555 shell sha256sum /data/local/avas/avas_sound_daemon.jar
```

Mesti sepadan: `4ee1e5365fde2ee962c70ba38565f2734199e4135049ff2f1b155da51915432a`

Jika tidak sepadan → **BERHENTI**, laporkan ralat, jangan teruskan.

### Langkah 4.5 — Pindahkan skrip launcher

```bash
adb -s <IP>:5555 push "D:\apps\emas-ota\start_avas_daemon.sh" /data/local/avas/start_avas_daemon.sh
adb -s <IP>:5555 shell chmod 755 /data/local/avas/start_avas_daemon.sh
```

### Langkah 4.6 — DIKECUALIKAN: `avas_boot.sh` dan `silent.wav`

Fail-fail berikut **TIDAK dipindahkan** dalam SOP ini:

| Fail | Sebab Dikecualikan |
|---|---|
| `avas_boot.sh` | Menetapkan Slot 3 sebagai lalai, memasang `silent.wav` untuk menyenyapkan chime pada Slot 2 dan 3, menghidupkan semula HAL, **dan memulakan daemon pintu/tingkap** (baris 96-98). Kesan sampingannya melampaui skop daemon AVAS sahaja. |
| `silent.wav` | Hanya diperlukan oleh `avas_boot.sh` untuk mute chime. Tanpa boot script, fail ini tidak digunakan oleh daemon secara langsung. Daemon boleh menjana mute melalui API `/api/avas/save` dengan `mute_chime:true`, tetapi itu memerlukan `silent.wav` wujud pada IHU. |

Jika pengguna memerlukan boot persistence atau mute chime, ia perlu diminta sebagai arahan berasingan dengan kesedaran penuh terhadap kesan sampingan.

### Langkah 4.7 — Pindahkan fail audio sokongan

```bash
# Preset audio
adb -s <IP>:5555 push "D:\apps\emas-ota\dist_avas\presets\clean_paddlepop.wav" /data/local/avas/presets/clean_paddlepop.wav
adb -s <IP>:5555 push "D:\apps\emas-ota\dist_avas\presets\clean_dodge_v8.wav" /data/local/avas/presets/clean_dodge_v8.wav
adb -s <IP>:5555 push "D:\apps\emas-ota\dist_avas\presets\clean_slot3_mario.wav" /data/local/avas/presets/clean_slot3_mario.wav

# Factory backup (untuk rollback audio kilang melalui daemon API)
adb -s <IP>:5555 push "D:\apps\emas-ota\dist_avas\factory_backup\yinheyinfu.wav" /data/local/avas/factory_backup/yinheyinfu.wav
adb -s <IP>:5555 push "D:\apps\emas-ota\dist_avas\factory_backup\space.wav" /data/local/avas/factory_backup/space.wav
adb -s <IP>:5555 push "D:\apps\emas-ota\dist_avas\factory_backup\pixel.wav" /data/local/avas/factory_backup/pixel.wav
```

### Langkah 4.8 — Sahkan semua fail dipindahkan

```bash
adb -s <IP>:5555 shell ls -laR /data/local/avas/
```

Bandingkan senarai dan saiz fail dengan `dist_avas/` di laptop.

### Langkah 4.9 — Pengaktifan daemon (tindakan berasingan)

**Langkah ini memerlukan arahan pengguna yang eksplisit.** Memindahkan fail (Langkah 4.1–4.8) dan mengaktifkan daemon ialah **dua tindakan berasingan** mengikut Katalog Tindakan 12.

```bash
adb -s <IP>:5555 shell sh /data/local/avas/start_avas_daemon.sh
```

---

## 5. Pengesahan Selepas Pengaktifan

```bash
# 5a. Daemon berjalan?
adb -s <IP>:5555 shell pgrep -f com.emas.avas.AvasSoundDaemon

# 5b. Port 8889 mendengar?
adb -s <IP>:5555 shell netstat -tlnp 2>/dev/null | grep 8889

# 5c. Token file wujud dan selamat?
adb -s <IP>:5555 shell ls -la /data/local/window/avas.token /data/local/avas/avas.token 2>/dev/null

# 5d. Log daemon (10 baris terakhir)?
adb -s <IP>:5555 shell tail -10 /data/local/avas/daemon.log
```

**Kriteria kejayaan:**

| Semakan | Dijangka |
|---|---|
| `pgrep` | Mengembalikan PID |
| Port 8889 | LISTEN pada 127.0.0.1 |
| Token file | Wujud, saiz > 0 bait |
| Log | Mengandungi "HTTP server listening on 127.0.0.1:8889" |

**Jika mana-mana gagal:** Jangan cuba pembaikan sendiri. Laporkan output sebenar dan minta arahan pengguna.

---

## 6. Rollback

### 6.1 Hentikan proses pengurusan sahaja (bukan pemulihan audio)

```bash
adb -s <IP>:5555 shell pkill -f com.emas.avas.AvasSoundDaemon
```

Daemon tidak menulis WAV terus ke partition vendor. Mount sementara boleh hilang selepas reboot; ini bukan jaminan bunyi kilang jika fail vendor asal telah rosak. Jangan reboot untuk menguji rollback yang belum dikenal pasti.

### 6.2 Pulihkan JAR lama (jika sandaran wujud)

```bash
# Hentikan daemon baharu
adb -s <IP>:5555 shell pkill -f com.emas.avas.AvasSoundDaemon 2>/dev/null

# Pulihkan JAR lama dari sandaran laptop
adb -s <IP>:5555 push "D:\apps\emas-ota\artifacts\backup_ihu_avas\avas_sound_daemon.jar.bak" /data/local/avas/avas_sound_daemon.jar

# Pulihkan skrip launcher lama (jika ada)
adb -s <IP>:5555 push "D:\apps\emas-ota\artifacts\backup_ihu_avas\start_avas_daemon.sh.bak" /data/local/avas/start_avas_daemon.sh 2>/dev/null
```

### 6.3 Buang daemon sahaja (tanpa memadam data pengguna)

Jika rollback ke versi lama tidak dikehendaki dan daemon perlu dibuang sepenuhnya:

```bash
adb -s <IP>:5555 shell rm /data/local/avas/avas_sound_daemon.jar
adb -s <IP>:5555 shell rm /data/local/avas/start_avas_daemon.sh
```

**DILARANG:** `rm -rf /data/local/avas/` — arahan ini akan memadam token, konfigurasi (`avas_config.json`), sandaran kilang (`factory_backup/`), fail audio pengguna, dan log daemon sekali gus. Pemadaman pukal tidak dibenarkan tanpa arahan pengguna yang menyenaraikan setiap item yang hendak dipadam.

### 6.4 Pulihkan audio/tuning/chime melalui transaksi protokol 2

Jangan membuka semua bind mount dan kemudian membunuh HAL. Fail vendor asal mungkin kosong/rosak; langkah itu boleh meninggalkan slot tanpa audio.

Selepas sasaran dan tindakan pemulihan diluluskan secara berasingan, gunakan helper bagi SATU slot yang dimaksudkan:

```bash
adb -s <IP>:5555 shell sh /data/local/avas/avas_cli.sh restore <SLOT_1_2_ATAU_3>
```

Helper memerlukan JAR/checksum baharu serta WAV sandaran dan snapshot tuning/chime yang dikenal pasti. Jika `FACTORY_STATE_UNKNOWN`, `ROLLBACK_UNVERIFIED` atau keadaan operasi tidak diketahui dilaporkan, hentikan percubaan dan periksa proses/mount/sandaran dahulu. Tiada retry atau reboot automatik.

`ROLLBACK_VERIFIED` mengesahkan keadaan fail/mount dan pemulihan servis yang diperiksa; bunyi pembesar suara masih memerlukan pengesahan fizikal. Hentikan daemon sahaja tidak memulihkan mount/audio.

### 6.5 Langkah yang BELUM mempunyai rollback automatik

| Langkah | Status Rollback |
|---|---|
| Fail `.rc` dipasang dalam `/system/etc/init/` | ⚠ Memerlukan remount `/system` (rw) untuk buang — di luar skop SOP ini |
| HAL service gagal respawn selepas kill | ⚠ Mungkin memerlukan reboot — di luar skop SOP ini |
| Token file permissions rosak | ⚠ Daemon akan refuse to start (fail-closed) — selamat tetapi memerlukan pemadaman dan restart manual |

---

## 7. Hubungan dengan SOP Lain

| Dokumen | Hubungan |
|---|---|
| `SOP_PEMASANGAN_APK.md` | Melarang pemasangan daemon (baris 12). SOP ini mengatasi larangan tersebut **khusus untuk daemon AVAS sahaja** apabila pengguna meluluskannya. |
| `KATALOG_TINDAKAN_STANDARD.md` | Tindakan 12 (Pelan Pemasangan Daemon) membenarkan penyediaan pelan. SOP ini ialah pelan tersebut. |
| `ARAHAN_ASAS_EMAS5.md` | Prinsip skop minimum dan pengasingan sasaran kekal berkuat kuasa. |
| `AUDIT_AVAS_20260928.md` | Artifak mesti lulus audit sebelum deployment. |

---

## 8. Penerimaan

SOP ini berkuat kuasa hanya apabila pengguna memberikan arahan eksplisit yang mengandungi sekurang-kurangnya:

1. IP kereta yang disahkan.
2. Pengesahan bahawa SOP ini diterima.
3. Arahan sama ada memindahkan fail sahaja (Langkah 4.1–4.8) atau memindahkan dan mengaktifkan (Langkah 4.1–4.9).

Tanpa ketiga-tiga perkara di atas, tiada tindakan boleh dimulakan.

---

**Semakan SOP:** 13 Oktober 2026
**Artifak sasaran:** protokol 2; hash penuh semasa dalam `dist_avas/manifest.json` dan `daemon.sha256`.
**Versi:** Cadangan v1.3 — arahan mkdir diganti PowerShell, belum diluluskan
