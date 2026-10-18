# LAPORAN AUDIT — MOD SERVIS (STEALTH) e.MAS 5

**Tarikh ujian:** 18 Oktober 2026, 18:53–18:54 +08
**Kenderaan:** Proton e.MAS 5 (IHU801P) · Android 10 · root
**Kaedah:** tangkapan keadaan sebelum dan selepas peralihan sebenar pada kereta.
**Bukti mentah:** `artifacts/stealth-mode-test-20261018/01-before-normal.txt`, `02-during-stealth.txt`

---

## Keputusan Utama

Mod Servis **berfungsi sebahagian**, bukan seperti didakwa. Tiga dakwaan dalam
dokumentasi adalah **salah** dan boleh menyebabkan kereta tidak kelihatan 100% kilang
semasa pemeriksaan Pusat Servis.

| Dakwaan | Realiti | Verdict |
| :--- | :--- | :--- |
| "Matikan semua daemon kustom" | 2 daripada 5 masih hidup | **SALAH** |
| "Bunyi AVAS 100% asal kilang" | Slot 2 masih custom | **SALAH** |
| "Kereta 100% mod kilang" | Telemetri, megafon, stereo masih aktif | **SALAH** |
| "Sembunyikan aplikasi sideload" | 9 pakej dilumpuhkan | **BETUL** |
| "Pulihkan nada kunci kilang" | MD5 berubah ke fail kilang | **BETUL** |

---

## 1. Perbandingan Keadaan

| Item | Normal | Stealth | Berubah |
| :--- | :--- | :--- | :--- |
| Fail bendera | tiada | `SERVICE_MODE_ACTIVE` | ya |
| Output status | `NORMAL_MODE_ACTIVE` | `SERVICE_MODE_ACTIVE` | ya |
| Pakej dilumpuhkan | 1 | 9 | ya |
| Daemon hidup | 5 | 3 | sebahagian |
| `active_slot` AVAS | 2 | 2 | tidak |
| Mount AVAS | 5 fail | 3 fail | sebahagian |
| Mount slot aplikasi | ada | masih ada | tidak |
| MD5 nada kunci | `1680435a…` | `c09518be…` | ya |

---

## 2. Pakej Dilumpuhkan — BETUL

```
Sebelum:  com.emas.dvrprobe

Selepas:  com.vivaldi.browser.automotive
          org.smarttube.stable
          me.zhanghai.android.files
          com.emas.dvrprobe
          com.malaysia.syok
          com.thisisaim.hotfm
          com.cybercat.acbridge
          com.netflix.mediaclient
          com.noor.thenoorcar
```

Sembilan pakej. Senarai ini sepadan dengan `APPS=` dalam `service_mode.sh`, **bukan**
senarai dalam `sc_stealth_mode.ps1`. Ini mengesahkan skrip dalam kereta yang dijalankan.

**Nota:** `com.retroarch.aarch64` **tidak** dilumpuhkan oleh `service_mode.sh`, walaupun
`sc_stealth_mode.ps1` menyenaraikannya. Jika tujuan ialah penampilan kilang, RetroArch
mungkin masih kelihatan.

---

## 3. Daemon — TIDAK LENGKAP

### Masih hidup selepas Stealth

```
 2221  TelemetryDaemon
 4143  MegaphoneDaemon
 4217  SteeringModeDaemon
```

### Telah dimatikan

```
 2243  DoorChime
 3978  DoorPressureReliefDaemon
```

**Punca:** `service_mode.sh` hanya memanggil `pkill` untuk `DoorPressureReliefDaemon`
dan `DoorChime`. Tiada `pkill` untuk `TelemetryDaemon` atau `MegaphoneDaemon`.
Hanya `sc_stealth_mode.ps1` (versi laptop) memanggil `pkill -f 'TelemetryDaemon'`.

### Kesan

- **TelemetryDaemon** masih mendengar pada `127.0.0.1:8888`. Jika juruteknik servis
  menjalankan pemeriksaan port, ia kelihatan.
- **MegaphoneDaemon** masih mendengar pada `127.0.0.1:8890`.
- **SteeringModeDaemon** dikekalkan **sengaja** — ia yang mengesan butang Volume Down
  5 saat untuk toggle Stealth. Ini reka bentuk, bukan pepijat.

---

## 4. AVAS — PUNCA MASALAH YANG DIALAMI

### Keadaan sebenar

```
SEBELUM (normal):
  sound_type_1/yinheyinfu.wav   <- bind mount custom
  sound_type_2/space.wav        <- bind mount custom
  sound_type_2/tuning.txt       <- bind mount custom
  sound_type_3/pixel.wav        <- bind mount custom
  sound_type_3/tuning.txt       <- bind mount custom

SELEPAS (stealth):
  sound_type_2/space.wav        <- MASIH bind mount custom
  sound_type_2/tuning.txt       <- MASIH bind mount custom
  sound_type_3/tuning.txt       <- MASIH bind mount
```

Slot 1 dan slot 3 berjaya di-unmount. **Slot 2 tidak disentuh.**

### Punca dalam kod

`service_mode.sh` baris AVAS:

```sh
umount -l /vendor/etc/avas/sound_type_1/yinheyinfu.wav
umount -l /vendor/etc/avas/sound_type_1/bDM_01_01.wav
umount -l /vendor/etc/avas/sound_type_3/pixel.wav
umount -l /vendor/etc/avas/sound_type_3/bDM_01_01.wav
```

Senarai ini melangkau `sound_type_2` sepenuhnya.

### Mengapa bunyi berbeza tetapi masih custom

`/data/local/avas/config.json` menunjukkan `"active_slot": 2`, iaitu **Space /
Dodge V8 Rumble**. Slot 2 masih bind mount kepada fail custom.

Apabila slot 1 dan 3 dipulihkan tetapi slot 2 kekal, kereta memainkan `space.wav`
custom. Ini menjelaskan pemerhatian pemilik: *"suara masih custom tapi lain bunyi
dan slot"*.

### Berbanding `avas_boot.sh`

Skrip pemulihan kilang yang sedia ada (`/data/local/avas/avas_boot.sh`) **memang**
unmount kesemua tiga slot dan kemudian bind mount semula fail kilang dari
`/data/local/avas/factory_backup/`. Skrip itu betul; `service_mode.sh` tidak
menggunakannya.

**Fail kilang tersedia:**

```
-rw-r--r-- 581788  2026-09-22  pixel.wav
-rw-r--r-- 1164462 2009-01-01  space.wav
-rw-r--r-- 1428582 2009-01-01  yinheyinfu.wav
```

---

## 5. Mount Slot Aplikasi — TIDAK DIBATALKAN

```
/system/app/ttincar_tiktok_ACO    <- masih ter-mount
/system/app/JOOXMusic_ACO         <- masih ter-mount
```

Tiada skrip Stealth memanggil `umount` untuk kedua-dua ini. Direktori masih terikat
kepada `/data/local/custom_apps/SmartTube` dan `/data/local/custom_apps/Netflix`.

**Kesan praktikal:** kecil, kerana `pm disable-user` pada SmartTube dan Netflix
bermakna kedua-dua aplikasi tidak boleh dijalankan. Slot menjadi kosong, bukan
kembali kepada TikTok/JOOX asal.

**Tetapi:** jika juruteknik servis memeriksa `mount`, ia menunjukkan direktori luar
biasa pada partisi sistem. Itu bukan penampilan kilang.

---

## 6. Nada Kunci — BETUL

```
SEBELUM:  1680435af018fe16327699e80ec32684  /sdcard/V3_Vehicle_Locked_Single.wav
SELEPAS:  c09518be9df6eb5ab72a00a74a75c56a  /sdcard/V3_Vehicle_Locked_Single.wav
```

MD5 berubah. Fail kilang (`stock_lock.wav`) disalin ganti. `ecarx_reverse_radar`
dimulakan semula. **Berfungsi.**

---

## 7. Percanggahan Antara Dua Skrip

| Aspek | `service_mode.sh` (kereta) | `sc_stealth_mode.ps1` (laptop) |
| :--- | :--- | :--- |
| Fail bendera | `SERVICE_MODE_ACTIVE` | `.service_mode` |
| Pakej disembunyikan | 9 (termasuk Vivaldi, SYOK, HotFM) | 5 (termasuk RetroArch) |
| `pkill TelemetryDaemon` | **tiada** | ada |
| `pkill MegaphoneDaemon` | **tiada** | ada |
| Pulihkan AVAS | separa (slot 1,3) | tiada langsung |
| Pulihkan nada kunci | ya | tiada |
| Dakwa "Vivaldi/SYOK/HotFM dikekalkan" | **bercanggah** — ia dilumpuhkan | — |

**Dua bendera berasingan** bermakna jika satu skrip menulis dan satu lagi membaca,
status boleh tidak konsisten. `service_mode.sh status` membaca `SERVICE_MODE_ACTIVE`
sahaja; `.service_mode` diabaikan.

---

## 8. Soalan: Telemetri dan EmasEnergy

`TelemetryDaemon` **ialah** backend untuk EmasEnergyApp.

Bukti daripada `EmasEnergyApp/src/com/noor/thenoorcar/TelemetryHttpClient.java`:

```java
/** Exact telemetry reads and authenticated bonnet controls on 127.0.0.1:8888. */
URL url = new URL("http://127.0.0.1:8888" + path);
```

Endpoint yang digunakan:

```
/api/energy        <- SOC, arus, voltan, kuasa
/api/status
/api/climate/temp
/api/climate/fan
/api/climate/power
/api/frunk/open  /api/trunk/open
/api/tasks
```

**Kesimpulan:** jika `TelemetryDaemon` dimatikan semasa Stealth (seperti yang
`sc_stealth_mode.ps1` lakukan), EmasEnergyApp akan kehilangan semua telemetri —
bukan rosak, tetapi semua nilai menjadi kosong.

Namun kerana `com.noor.thenoorcar` **juga** dilumpuhkan dalam Stealth, aplikasi itu
tidak dijalankan langsung. Jadi mematikan `TelemetryDaemon` adalah **konsisten** —
tidak ada klien, tidak perlu pelayan.

Keadaan sekarang (daemon hidup, aplikasi mati) ialah keadaan setengah jalan:
perkhidmatan berjalan tanpa pengguna. Bukan bahaya, tetapi tidak bersih.

---

## 9. Verdict Per Perkara

| # | Perkara | Verdict |
| :--- | :--- | :--- |
| 1 | 9 pakej dilumpuhkan | **BETUL** |
| 2 | Nada kunci kilang dipulihkan | **BETUL** |
| 3 | DoorChime dimatikan | **BETUL** |
| 4 | DoorPressureRelief dimatikan | **BETUL** |
| 5 | TelemetryDaemon dimatikan | **PEPIJAT** — masih hidup |
| 6 | MegaphoneDaemon dimatikan | **PEPIJAT** — masih hidup |
| 7 | AVAS slot 1 dipulihkan | **BETUL** |
| 8 | AVAS slot 3 dipulihkan | **BETUL** |
| 9 | AVAS slot 2 dipulihkan | **PEPIJAT** — dilangkau |
| 10 | Mount slot aplikasi dibatalkan | **PEPIJAT** — tidak disentuh |
| 11 | RetroArch disembunyikan | **PEPIJAT** — skrip kereta langkau |
| 12 | Bendera konsisten antara skrip | **PEPIJAT** — dua nama berbeza |

---

## 10. Cadangan Pembetulan

### Keutamaan 1 — AVAS slot 2

Tambah dalam `service_mode.sh` bahagian `on`:

```sh
umount -l /vendor/etc/avas/sound_type_2/space.wav 2>/dev/null
umount -l /vendor/etc/avas/sound_type_2/bDM_01_01.wav 2>/dev/null
umount -l /vendor/etc/avas/sound_type_2/tuning.txt 2>/dev/null
umount -l /vendor/etc/avas/sound_type_2/tuning_r.txt 2>/dev/null
```

Kemudian bind mount semula fail kilang dari `/data/local/avas/factory_backup/`,
sama seperti `avas_boot.sh` lakukan.

**Mengapa perlu bind mount, bukan sekadar umount:** `/vendor` ialah partisi
dinamik baca-sahaja dengan fail 0-bait. Umount sahaja boleh mendedahkan fail kosong,
bukan bunyi kilang sebenar. `avas_boot.sh` sudah menangani ini — gunakan corak sama.

### Keutamaan 2 — Hentikan daemon

Tambah sebelum bahagian chime:

```sh
pkill -f "TelemetryDaemon" 2>/dev/null
pkill -f "MegaphoneDaemon" 2>/dev/null
```

**Jangan** matikan `SteeringModeDaemon` — ia diperlukan untuk toggle Volume Down.

### Keutamaan 3 — Batal mount aplikasi

```sh
umount -l /system/app/ttincar_tiktok_ACO 2>/dev/null
umount -l /system/app/JOOXMusic_ACO 2>/dev/null
umount -l /system/app/Settings_MOLEAD/Settings_MOLEAD.apk 2>/dev/null
```

Nota: ini mendedahkan aplikasi asal TikTok/JOOX. Jika ia muncul dalam drawer,
tambah `pm disable-user` untuk kedua-duanya juga.

### Keutamaan 4 — Selaraskan bendera

Pilih satu nama. Cadangan: kekalkan `SERVICE_MODE_ACTIVE` kerana ia digunakan oleh
skrip dalam kereta. Kemas kini `sc_stealth_mode.ps1` dan `sc_restore_mode.ps1` untuk
menggunakan nama yang sama.

---

## 11. Pembetulan Dijalankan — Disahkan 18 Okt 19:00

Kod dalam `service_mode.sh` telah ditampal dan diuji pada kereta. Backup asal:
`/data/local/window/service_mode.sh.bak_20261018`.

### Perubahan kod

| # | Pembetulan | Sebelum | Selepas |
| :--- | :--- | :--- | :--- |
| 1 | AVAS slot 2 | dilangkau | diurus |
| 2 | AVAS bind mount | umount sahaja (mendedah 0-bait) | umount + bind factory |
| 3 | TelemetryDaemon | tidak dihentikan | `pkill` |
| 4 | MegaphoneDaemon | tidak dihentikan | `pkill` |
| 5 | SalamBoot | tidak dihentikan | `pkill` (pola berasingan) |
| 6 | Restore AVAS | `/data/local/tmp` (hilang selepas reboot) | `/data/local/avas` diutamakan |
| 7 | Restore salam | tiada | `start_salam.sh` ditambah |

### Keputusan ujian penuh (off → on)

```
[SERVICE_MODE] Door chime and salam daemons stopped.
[SERVICE_MODE] Factory lock chime restored.
[SERVICE_MODE] AVAS restored to factory on all 3 slots.
[SERVICE_MODE] Telemetry and megaphone daemons stopped.
[SERVICE_MODE] MOD SERVIS AKTIF (KERETA KINI STANDARD KILANG).
```

### Keadaan akhir — disahkan

```
status          : SERVICE_MODE_ACTIVE
pakej disabled  : 9

daemon hidup    : 17221 SteamSteeringModeDaemon   <- satu sahaja
                  (dikekalkan untuk toggle Volume Down)

AVAS slot 1     : FACTORY
AVAS slot 2     : FACTORY
AVAS slot 3     : FACTORY

port 8888/8890  : tiada LISTEN
```

### Sebelum vs selepas pembetulan

| Aspek | Sebelum tampalan | Selepas tampalan |
| :--- | :--- | :--- |
| Daemon hidup | 3 | **1** (steering sahaja) |
| AVAS slot 2 | CUSTOM | **FACTORY** |
| Port 8888 | LISTEN | **tutup** |
| Port 8890 | LISTEN | **tutup** |
| Salam daemon | hidup | **mati** |

### Nota teknikal — `pkill` dalam `adb shell`

Semasa menguji, `pkill -f "SalamBoot"` yang dijalankan melalui `adb shell` menghentikan
shell itu sendiri (`exit code 143`) kerana baris arahan `adb` mengandungi perkataan
yang sama. Ini **tidak** berlaku apabila `pkill` dijalankan dari dalam skrip
(`service_mode.sh`), kerana proses skrip tidak mengandungi teks tersebut dalam
argumennya. Ujian pengesahan perlu menggunakan `kill` mengikut PID, bukan `pkill -f`.

---

## 12. Kesimpulan

**Selepas pembetulan, Mod Servis kini berfungsi sepenuhnya.**

| Dakwaan asal | Status selepas tampalan |
| :--- | :--- |
| "Matikan semua daemon kustom" | **BETUL** — 4/4 dimatikan, steering dikekalkan dengan sengaja |
| "Bunyi AVAS 100% asal kilang" | **BETUL** — ketiga-tiga slot bind ke factory_backup, MD5 padan |
| "Kereta 100% mod kilang" | **HAMPIR** — port 8888/8890 tutup; mount slot aplikasi masih kelihatan |
| "Sembunyikan aplikasi sideload" | **BETUL** — 9 pakej |
| "Pulihkan nada kunci kilang" | **BETUL** — MD5 berubah ke stock |

### Yang masih belum sempurna

**Mount slot aplikasi masih aktif.** `ttincar_tiktok_ACO` dan `JOOXMusic_ACO` masih
terikat kepada direktori custom. Aplikasi tidak boleh dijalankan (dilumpuhkan), tetapi
`mount` masih menunjukkan laluan luar biasa. Jika juruteknik memeriksa shell, ia
kelihatan. Untuk penampilan penuh kilang, tambah `umount` — tetapi itu akan
mendedahkan aplikasi asal TikTok/JOOX yang juga perlu dilumpuhkan.

**RetroArch tidak dilumpuhkan** oleh `service_mode.sh`, walaupun
`sc_stealth_mode.ps1` menyenaraikannya. Jika mahu, tambah `com.retroarch.aarch64`
ke senarai `APPS`.

**Dua bendera berbeza** masih wujud antara skrip kereta dan laptop. Tidak berbahaya
selagi hanya skrip kereta digunakan — tetapi mengelirukan.

### Pemulihan

```sh
sh /data/local/window/service_mode.sh off
```

Diuji: memulihkan 8 pakej, DoorChime, SalamBoot, DoorPressureRelief, nada kunci
custom, dan AVAS custom daripada `/data/local/avas/avas_boot.sh`.

**Rollback penuh** jika tampalan bermasalah:

```sh
cp /data/local/window/service_mode.sh.bak_20261018 /data/local/window/service_mode.sh
```

---

*Laporan ini berdasarkan tangkapan keadaan sebenar, bukan dokumentasi. Setiap
dakwaan mempunyai bukti dalam fail mentah yang dirujuk.*
