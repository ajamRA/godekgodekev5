# STATUS SISTEM e.MAS 5 — DISAHKAN PADA KERETA

**Kenderaan:** Proton e.MAS 5 (IHU801P) · Android 10 (API 29) · root
**Disahkan pada:** 18 Oktober 2026, `192.168.1.20:5555`
**Kaedah:** pemeriksaan langsung melalui ADB pada peranti — bukan daripada dokumentasi atau andaian.

> [!IMPORTANT]
> Setiap baris dalam dokumen ini ditanda dengan tahap bukti. Tiada dakwaan tanpa
> bukti. Jika sesuatu tidak diperiksa, ia ditanda **UNVERIFIED**, bukan diandaikan
> berfungsi.

---

## Skala Bukti

| Tanda | Maksud |
| :--- | :--- |
| **SOLID** | Diperiksa terus pada kereta hari ini — arahan dan output direkod |
| **STATIC** | Fail wujud dengan saiz/tarikh, tetapi tingkah laku belum diuji |
| **UNVERIFIED** | Didakwa wujud, tetapi tiada bukti langsung diperoleh |
| **ABSENT** | Diperiksa, dan tidak wujud |
| **N/A** | Perkakasan tidak dipasang pada model ini |

---

## 1. Slot Aplikasi — SOLID

```
$ mount | grep -E 'ttincar|JOOX'
/dev/block/mmcblk0p54 on /system/app/ttincar_tiktok_ACO type ext4 (rw,...)
/dev/block/mmcblk0p54 on /system/app/JOOXMusic_ACO type ext4 (rw,...)
```

| Slot | Fail sebenar | Saiz | Tarikh | Buah |
| :--- | :--- | ---: | :--- | :--- |
| `/system/app/ttincar_tiktok_ACO/` | `SmartTube.apk` | 26.2 MB | 2026-09-21 21:53 | TikTok → SmartTube |
| `/system/app/JOOXMusic_ACO/` | `com.netflix.mediaclient.apk` + 2 config split | 28.4 MB | 2026-09-21 22:09 | JOOX → Netflix |
| `/system/app/Noor/Noor.apk` | `Noor.apk` (in-place) | 4.76 MB | 2026-10-17 23:48 | TheNoor → EmasEnergy |

Mount didaftarkan dalam `/system/etc/init/zzz_custom_apps.rc` (858 bait, 2026-10-17 23:52):

```
on post-fs-data
    mount none /data/local/custom_apps/SmartTube /system/app/ttincar_tiktok_ACO bind rec
    mount none /data/local/custom_apps/Netflix /system/app/JOOXMusic_ACO bind rec
    mount none /data/local/avas/Settings_MOLEAD_v8.apk /system/app/Settings_MOLEAD/Settings_MOLEAD.apk bind rec
    mount none /data/local/avas/v8_clean.wav /vendor/etc/avas/sound_type_3/pixel.wav bind rec
```

---

## 2. Daemon Berjalan — SOLID

```
$ ps -A -o PID,ARGS | grep app_process
 2221 app_process /data/local/window com.emas.telemetry.TelemetryDaemon
 2243 app_process /data/local/window DoorChime /data/local/window/door_chime.wav ...
 3978 app_process /data/local/window com.emas.window.DoorPressureReliefDaemon
 4143 app_process /data/local/window com.emas.megaphone.MegaphoneDaemon
 4217 app_process /data/local/window com.emas.steering.SteeringModeDaemon
```

**Lima daemon hidup.** Nota: `pidof` mengembalikan kosong untuk semua — daemon dijalankan
melalui `app_process` dengan nama kelas Java, bukan sebagai proses bernama pakej.
Gunakan `ps -A -o ARGS | grep app_process`, bukan `pidof`.

### Port yang mendengar — SOLID

```
$ netstat -tlnp | grep -E '8885|8888'
tcp6  0  0 ::ffff:127.0.0.1:8888  :::*  LISTEN  2221/app_process
tcp6  0  0 ::ffff:127.0.0.1:8885  :::*  LISTEN  3978/app_process
```

| Port | PID | Daemon | Ujian |
| ---: | :--- | :--- | :--- |
| 8888 | 2221 | TelemetryDaemon | **HTTP 200** — `{"ok":true,"daemon":"telemetry_only"}` |
| 8885 | 3978 | DoorPressureReliefDaemon | mendengar; tiada respons pada `GET /` |

Bukti 8888:

```
$ printf 'GET /api/status HTTP/1.0\r\n\r\n' | nc -w 3 127.0.0.1 8888
HTTP/1.1 200 OK
Content-Type: application/json
{"ok":true,"daemon":"telemetry_only"}
```

Log daemon: `[TelemetryDaemon] HTTP listening on 127.0.0.1:8888`

**Kedua-dua port diikat pada `127.0.0.1` sahaja** — tidak didedahkan ke rangkaian.

### Gamepad — SOLID

```
$ ps -A -o ARGS | grep uinput
uinput_gamepad
$ ls -l /dev/input/event4
crw-rw---- 1 root input 13, 68 2026-10-18 18:23 event4
```

`event4` dicipta 18 Okt 18:23 — peranti input baharu didaftarkan oleh `uinput_gamepad`.
Gerbang gamepad berjalan.

---

## 3. Audio Luar & AVAS — SOLID (fail) / STATIC (mainan)

### AVAS slot aktif

`/data/local/avas/config.json`:

```json
{
  "active_slot": 2,
  "slots": {
    "1": { "name": "Slot 1 (Galaxy)", "custom_name": "Paddle Pop",     ... },
    "2": { "name": "Slot 2 (Space)",  "custom_name": "Dodge V8 Rumble", ... },
    "3": { ... }
  }
}
```

**Slot aktif = 2 (Space / Dodge V8 Rumble)**

| Slot | Fail | Saiz | Tarikh | Status |
| :--- | :--- | ---: | :--- | :--- |
| 1 | `yinheyinfu.wav` | 6.34 MB | 2026-10-15 21:56 | diganti |
| 2 | `space.wav` | 960 KB | 2026-10-15 21:56 | **AKTIF** |
| 3 | `pixel.wav` | 3.74 MB | 2026-10-15 21:56 | diganti |

Slot 3 juga di-bind mount daripada `/data/local/avas/v8_clean.wav` melalui `zzz_custom_apps.rc`.

**STATIC:** fail wujud dan bertarikh. Mainan sebenar melalui speaker bumper **tidak
didengar/diukur** dalam sesi ini.

### Nada kunci

| Fail | Saiz | Tarikh |
| :--- | ---: | :--- |
| `/sdcard/V3_Vehicle_Locked_Single.wav` | 384 KB | 2026-10-17 22:43 |
| `/data/local/window/locksounds/lock_active.wav` | 384 KB | 2026-10-16 21:03 |
| `lock_original.wav` (kilang) | 250 KB | 2026-09-27 16:19 |

`lock_selection.properties` — pilihan terakhir yang dikomit:

```
sound=animewow
sha256=a375a5e0de1b739d6b4270106f9bae14d05611b628cfb061d2aea3eb7572d511
remote_revision=1792120066270
remote_deploy_id=lock_75bd3b9f6adf45e396550eb180bd2edb
```

Komen dalam fail itu sendiri: *"Last committed lock sound; no playback claim"*.
**Ia rekod pilihan, bukan bukti mainan.**

---

## 4. Butang Stereng — SOLID (fail) / UNVERIFIED (tingkah laku)

```
$ ls -l /data/local/window/steering_mode_controller.jar
-rw-rw-rw- 1 root root 52735 2026-10-15 16:47
```

Proses **4217** `com.emas.steering.SteeringModeDaemon` sedang berjalan.

Pemetaan yang didakwa (belum diuji dalam sesi ini):
- Kanan ▶ tahan 800 ms → kitar mod pemanduan
- Kiri ◀ tahan 800 ms → kitar tahap regen
- Bawah ▼ tahan 5 s → toggle mod servis
- Atas ▲ → volume kilang (dikecualikan)

**UNVERIFIED:** tiada ujian tekan butang dilakukan hari ini.

---

## 5. Mod Servis (Stealth) — STATIC

```
$ ls -l /data/local/window/service_mode.sh
-rwxr-xr-x 1 root root 5023 2026-10-09 18:37
```

**STATIC:** skrip wujud dan boleh laksana. Kesan terhadap sistem (menyembunyikan aplikasi,
membatalkan mount, memulihkan bunyi kilang) **tidak dijalankan** dalam sesi ini.

Nota: `pm list packages -d` hanya menunjukkan **satu** pakej dilumpuhkan
(`com.emas.dvrprobe`). Ini **keadaan biasa**, bukan keadaan Stealth aktif. Jika Stealth
aktif, senarai itu sepatutnya mengandungi Netflix, SmartTube, Noor, RetroArch.

---

## 6. RetroArch — SOLID

```
package:/data/app/com.retroarch.aarch64-XnzxzBEAJk_NVChrwJIVIg==/base.apk
-r--r--r-- 1 u0_a90 u0_a90 109772 2026-10-17 14:31  /data/data/com.retroarch.aarch64/retroarch.cfg
-r--r--r-- 1 root   root   109772 2026-10-17 23:52  /data/local/retroarch_golden.cfg
```

```
$ grep -E 'menu_driver|config_save_on_exit' /data/local/retroarch_golden.cfg
menu_driver = "ozone"
config_save_on_exit = "false"
```

Kedua-dua fail `chmod 0444`. Pemulihan automatik pada boot didaftarkan dalam
`zzz_custom_apps.rc`. **Disahkan.**

---

## 7. Init Scripts — SOLID

| Fail | Saiz | Tarikh |
| :--- | ---: | :--- |
| `/system/etc/init/zzz_adbtcp.rc` | 630 | 2026-09-18 16:54 |
| `/system/etc/init/zzz_avas_boot.rc` | 282 | 2026-09-22 18:33 |
| `/system/etc/init/zzz_custom_apps.rc` | 858 | 2026-10-17 23:52 |
| `/system/etc/init/zzz_telemetry_boot.rc` | 320 | 2026-09-28 01:29 |

---

## 8. Akses & Root — SOLID

```
$ adb connect 192.168.1.20:5555
connected to 192.168.1.20:5555
$ adb shell "id"
uid=0(root) gid=0(root) groups=0(root),... context=u:r:su:s0
```

---

## 9. Apa Yang TIDAK Disahkan

Bahagian ini penting. Ini dakwaan yang **tidak** dapat dibuktikan dalam sesi ini:

| Dakwaan | Sebab |
| :--- | :--- |
| Nada kunci **berbunyi** melalui speaker bumper | Tiada ujian mainan; fail sahaja |
| AVAS slot 2 **berbunyi** pada pejalan kaki | Tiada ujian mainan |
| Butang stereng **berfungsi** seperti dipetakan | Tiada ujian tekan |
| Megafon 0 ms | Tiada ujian; `megaphone_daemon.jar` hidup, tetapi latensi tidak diukur |
| Assalamualaikum pada buka pintu | `start_salam.sh` wujud; mainan tidak diuji |
| Doa Naik Kenderaan pada Gear D | Sama — tidak diuji |
| Gamepad berfungsi dengan pengawal sebenar | `uinput_gamepad` hidup dan `event4` wujud; tetapi tiada pengawal disambung untuk uji |
| Aplikasi dashcam berfungsi | Tiada ujian |
| Telematik telefon (VPS) | VPS tidak disemak dari sini |
| Proton Link tidak tendang | Di luar skop pemeriksaan hari ini |
| DoorPressureReliefDaemon (8885) berfungsi | Port mendengar, tetapi tiada respons pada `GET /` |

---

## 10. Kawalan HVAC — DISAHKAN LIVE (ujian A/C, 18 Okt)

Ujian: A/C OFF → ON → fan +1 → recirc off → recirc on. Setiap perubahan direkod dengan cap masa.

| ID | Fungsi | Tukar diperhatikan | Status |
| :--- | :--- | :--- | :--- |
| `0x28700011` | HVAC Power | `01→00→01` | **LIVE** |
| `0x28700001` | A/C | `01→00→01` | **LIVE** |
| `0x28700005` | Fan Speed | `01→00→01→02` | **LIVE** |
| `0x2870000E` | Recirculation | `01→00→01` | **LIVE** |
| `0x28700007` | Suhu pemandu | baca `06` = 20.0°C | terbaca |
| `0x28700008` | Suhu penumpang | baca `00` | terbaca |

Dokumen `docs/HVAC_VHAL_MAPPING.md` **disahkan tepat** untuk lima property di atas.

ID baharu dikesan berubah semasa ujian, **belum dilabel**:
`0x287002E5` (03↔04), `0x287002E6` (02↔03), `0x287002FD` (1000↔995),
`0x28700090`/`0x2870009E` (tukar berpasangan).

---

## 11. Kaveat Penting — Deklarasi ≠ Perkakasan

`MyVehicleProperty.java` (921 baris, daripada APK Settings) menyenaraikan **semua
kemungkinan platform**, bukan ciri model ini. APK yang sama dipasang pada e.MAS 5,
e.MAS 7 dan Geometry E.

Contoh disahkan:

| Property | Dalam senarai | Dalam VHAL | Dalam MCU | Perkakasan e.MAS 5 |
| :--- | :--- | :--- | :--- | :--- |
| `0x28700028` SWHEAT | ya | ya (`access=3`) | **tiada** | **tiada** |
| `INFO_ID_DUAL` | ya | ya | — | satu zon sahaja |
| PM2.5 | ya | ya | — | **tiada sensor** |

Rekod dalam `_archive_experiments/drive_assist_repo/docs/OEM-MODULES.md`:

> *"Function tables, IPC interfaces, and property declarations in OEM binaries reflect
> platform-level capabilities. On the Geely EX2 / Geometry E (IHU629G), several declared
> features lack physical hardware wiring (e.g., multi-zone climate, seat
> heating/ventilation/massage, **steering wheel heating**, PM2.5 air sensors, fragrance
> dispensers, and motorized charge port flaps)."*

**Kesimpulan:** senarai 621 property yang dinamakan itu ialah senarai **harapan**.
Hanya ~10 yang disahkan LIVE. Selebihnya belum diuji.

---

## 12. Ringkasan

| Komponen | Status |
| :--- | :--- |
| Slot aplikasi (3) | **SOLID** — dipasang dan di-mount |
| Daemon berjalan (5) | **SOLID** — PID aktif |
| Telemetry port 8888 | **SOLID** — HTTP 200 diuji |
| Door pressure port 8885 | **STATIC** — mendengar, tiada respons |
| Gamepad `uinput` | **SOLID** — proses + `event4` |
| Gamepad dengan pengawal | **UNVERIFIED** |
| AVAS konfigurasi | **SOLID** — slot 2 aktif |
| AVAS mainan | **UNVERIFIED** |
| Nada kunci fail | **SOLID** |
| Nada kunci mainan | **UNVERIFIED** |
| Butang stereng | **UNVERIFIED** |
| Mod servis | **STATIC** — skrip ada, belum dijalankan |
| RetroArch Ozone | **SOLID** |
| Init scripts | **SOLID** |
| Root ADB | **SOLID** |
| HVAC 5 property | **LIVE** — diuji |
| 621 property lain | **UNKNOWN** — belum diuji |

---

*Dokumen ini dijana daripada pemeriksaan langsung pada 18 Oktober 2026. Tiada dakwaan
tanpa bukti. Kemas kini apabila ujian baharu dijalankan.*
