# MOD SERVIS BERTAHAN REBOOT — 18 Oktober 2026

**Disahkan pada kereta:** Stealth kekal selepas reboot penuh.
**Fail konfigurasi yang dipasang:** `artifacts/stealth-mode-test-20261018/device-config/`

---

## Hasil Ujian Reboot

| Aspek | Sebelum | Selepas reboot |
| :--- | :--- | :--- |
| Daemon hidup | 6 | **1** (steering sahaja) |
| Bind mount slot | 8 | **0** |
| AVAS slot 1/2/3 | CUSTOM | **FACTORY** |
| Port 8888/8890 | LISTEN | **tutup** |
| Butang Volume Down | — | **berfungsi** |
| Salam/Doa | berbunyi | **senyap** |

Log `boot_hook.log` selepas reboot:

```
=== boot_hook start Sun Oct 18 19:50:14 +08 2026 ===
2026-10-18 19:50:14 stealth=1 dry=0
2026-10-18 19:50:14 STEALTH: skip semua mount slot aplikasi
2026-10-18 19:50:14 STEALTH: skip retroarch cfg
Steering Daemon started with PID 468
2026-10-18 19:50:14 steering daemon: RUNNING
2026-10-18 19:50:14 STEALTH: skip semua daemon kustom
2026-10-18 19:50:14 === boot_hook end ===
```

---

## Punca Utama: Fail `.bak` Dalam `/system/etc/init/`

> [!CAUTION]
> **Init memproses SEMUA fail dalam `/system/etc/init/`, tanpa menapis sambungan.**
> Fail seperti `zzz_custom_apps.rc.bak_20261018` **bukan** backup pasif — ia
> konfigurasi aktif yang diproses pada setiap boot.

Ini menyebabkan enam fail `.bak` memasang semula bind mount dan menghidupkan
semula daemon walaupun `.rc` utama sudah dibetulkan. Log menunjukkan hook
"skip" tetapi daemon tetap muncul.

**Penyelesaian:** semua fail `.bak` dipindahkan ke `/data/local/init_backup/`,
keluar daripada direktori yang diproses init.

```
/system/etc/init/          -> hanya 4 fail .rc aktif
/data/local/init_backup/   -> 6 fail .bak (tidak diproses)
```

---

## Seni Bina

```
post-fs-data (init)
    |
    +-- zzz_custom_apps.rc
    |       exec u:r:su:s0 ... timeout 25 sh /data/local/window/boot_hook.sh
    |
    +-- boot_hook.sh  (semua logik di sini)
            |-- semak SERVICE_MODE_ACTIVE / .service_mode
            |-- Stealth : skip mount, skip daemon kustom
            |-- Normal  : mount slot, kunci RetroArch, start daemon
            +-- SENTIASA start SteeringModeDaemon
```

`boot_hook.sh` boleh diuji tanpa risiko:

```sh
sh /data/local/window/boot_hook.sh --dry-run    # tunjuk apa yang akan dibuat
sh /data/local/window/boot_hook.sh --status     # keadaan sekarang
```

---

## Fail Yang Diubah

| Fail | Perubahan |
| :--- | :--- |
| `zzz_custom_apps.rc` | 858 → **785** bait. Satu baris `exec` sahaja; semua logik dalam `boot_hook.sh` |
| `zzz_avas_boot.rc` | 282 → **287** bait. Titik ke `/data/local/avas/avas_boot.sh`, bukan `/system/bin/avas_boot.sh` (yang tiada semakan bendera) |
| `zzz_telemetry_boot.rc` | 320 bait. Panggil `telemetry_guard.sh` sebagai pembalut |
| `avas_boot.sh` | Steering daemon dimulakan **sebelum** `exit 0`; AVAS mode dipaksa 1 dalam Stealth |
| `service_mode.sh` | Urutan `pkill` (pelancar dahulu), `umount` retry 4×, kedua-dua bendera ditulis |
| `boot_hook.sh` | **Baru.** Semua logik boot + `--dry-run` + `--status` |
| `telemetry_guard.sh` | **Baru.** Pembalut semakan bendera untuk telemetry |

---

## Butiran Teknikal

### Kenapa `timeout 25`

`exec` dalam init adalah **synchronous**. Jika skrip hang, PID 1 tersekat,
zygote tidak naik, watchdog reboot. `timeout 25` membunuh skrip jika ia
melebihi 25 saat, membolehkan boot diteruskan.

### Kenapa `u:r:su:s0`

Domain `init` (`-`) mempunyai `neverallow` terhadap execute fail di `/data`.
Domain `system` juga disekat. Hanya `su` (unconfined pada peranti root)
dibenarkan execute dari `/data/local/window/`.

### Kenapa `post-fs-data`

`PackageManagerService` mengimbas `/system/app/` pada fasa `zygote-start`.
Bind mount yang dibuat selepas itu tidak dikenali launcher. `post-fs-data`
ialah satu-satunya masa sah.

### Kenapa SteeringModeDaemon sentiasa hidup

Ia satu-satunya cara toggle Volume Down 5s berfungsi. Tanpa ia, Stealth
terkunci dan hanya boleh dimatikan melalui ADB. Ini berlaku pada 18 Okt
selepas reboot pertama — butang mati sepenuhnya.

---

## Rollback

Backup asal di kereta:

```
/data/local/init_backup/
```

Pulihkan:

```sh
mount -o remount,rw /
cp /data/local/init_backup/zzz_custom_apps.rc.bak_20261018 /system/etc/init/zzz_custom_apps.rc
mount -o remount,ro /
```

> [!WARNING]
> Jangan letak backup kembali dalam `/system/etc/init/`. Pindahkan ke direktori
> lain, atau init akan memprosesnya sebagai konfigurasi aktif.

---

## Batasan Yang Tinggal

| Perkara | Status |
| :--- | :--- |
| `com.retroarch.aarch64` dilumpuhkan | ya (dalam senarai `APPS`) |
| Mount slot dibatalkan semasa Stealth | ya, disahkan 0 |
| APK asal TikTok/JOOX muncul semula | tidak — bind mount dibatalkan tetapi `pm disable-user` mengekalkan slot kosong |
| Dua bendera (`.service_mode` + `SERVICE_MODE_ACTIVE`) | kedua-dua ditulis dan dipadam serentak |
| `sc_stealth_mode.ps1` (versi laptop) | tidak diselaraskan — gunakan `service_mode.sh` dalam kereta |

---

*Dokumen ini berdasarkan ujian reboot sebenar, bukan andaian. Setiap angka
mempunyai bukti dalam `artifacts/stealth-mode-test-20261018/`.*
