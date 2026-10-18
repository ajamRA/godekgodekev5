# SANDBOX IHU — Simulasi pemasangan tanpa kereta

**Tujuan:** menguji `pasang_stealth.ps1` dan skrip boot tanpa menyentuh kereta
sebenar, dan **menghasilkan semula** keadaan yang menyebabkan Stealth bocor.

---

## Status

| Komponen | Status |
| :--- | :--- |
| `setup-sandbox.cjs` | **berfungsi** — bina rootfs palsu |
| `adb-mock.cjs` | **berfungsi** — devices, connect, push, shell, reboot |
| `run-sh.cjs` | **berfungsi** — jalan skrip dengan laluan dipetakan |
| Simulasi bug `.bak` | **berjaya dihasilkan semula** |
| `pasang_stealth.ps1` penuh | **belum lulus** — lihat Batasan |

---

## Bukti: bug `.bak` dihasilkan semula

```
$ node adb-mock.cjs -s 192.168.1.20:5555 reboot

[sandbox] reboot: mensimulasi init post-fs-data
  [init] zzz_avas_boot.rc -> service avas_boot (/system/bin/avas_boot.sh)
  [init] zzz_custom_apps.rc -> 4 mount (DIPROSES)
  [init] zzz_custom_apps.rc -> exec start_telemetry.sh
  [init] zzz_custom_apps.rc.bak -> 4 mount (DIPROSES)
  [init] zzz_custom_apps.rc.bak -> exec start_telemetry.sh
  [init] zzz_telemetry_boot.rc -> service telemetry_boot (...)
  [init] zzz_telemetry_boot.rc.bak2 -> service telemetry_boot (...)
  [init] daemon: 4, mount: 4
```

Baris `.bak` membuktikan init memproses **semua** fail dalam
`/system/etc/init/` tanpa menapis sambungan. Ini punca sebenar yang
ditemui pada kereta 18 Okt.

---

## Cara Guna

```bash
cd sandbox_ihu

# 1. Sedia semula rootfs (kereta normal, ada .bak)
node setup-sandbox.cjs

# 2. Lihat keadaan
node adb-mock.cjs devices
node adb-mock.cjs -s 192.168.1.20:5555 shell "getprop ro.product.model"

# 3. Simulasi reboot
node adb-mock.cjs -s 192.168.1.20:5555 reboot

# 4. Pasang (guna adb.bat sebagai pengganti adb)
cd ../PAKEJ_STEALTH_EMAS5
powershell -ExecutionPolicy Bypass -File pasang_stealth.ps1 `
  -Target 192.168.1.20 -Adb "D:\apps\emas-ota\sandbox_ihu\adb.bat"
```

---

## Struktur

```
sandbox_ihu/
├─ setup-sandbox.cjs    bina rootfs palsu
├─ adb-mock.cjs         enjin simulasi adb
├─ run-sh.cjs           jalan skrip dengan laluan dipetakan
├─ adb.bat              pembalut untuk PowerShell
├─ rootfs/              sistem fail palsu
│  ├─ system/etc/init/  partition /system (ro sehingga remount)
│  ├─ data/local/       partition /data
│  └─ vendor/etc/avas/  partition /vendor
└─ state/               flag: system_rw, disabled_pkgs, running, mounted
```

---

## Apa Yang Disimulasi Dengan Setia

| Tingkah laku | Disimulasi |
| :--- | :--- |
| `/system` read-only | ya — `push`/`cp` gagal sehingga `mount -o remount,rw /` |
| `.bak` dalam init diproses | **ya** — punca bug sebenar |
| `pm disable-user` | ya — senarai disimpan |
| Daemon hidup/mati | ya — `ps`, `pgrep` membaca state |
| Mount slot | ya — `mount \| grep -c` kira dari state |
| `remount ro` selepas siap | ya |

## Apa Yang **Tidak** Disimulasi

| Perkara | Sebab |
| :--- | :--- |
| SELinux sebenar | tiada `restorecon` — sentiasa berjaya |
| Binder / VHAL | di luar skop |
| Audio (AVAS main) | tiada peranti bunyi |
| Timing boot init | serta-merta, bukan berperingkat |
| `for pair in "a:b"` | `:` dalam laluan Windows merosakkan parsing |

---

## Batasan Semasa

**`pasang_stealth.ps1` belum lulus sepenuhnya dalam sandbox.**

Kegagalan terakhir:

```
[6/7] Ujian dry-run boot hook...
  stealth=0 dry=1
  skip (tiada sumber): D:/.../rootfsD:/.../rootfs/data/local/custom_apps/SmartTube
```

**Punca:** `run-sh.cjs` memetakan `/data/local/custom_apps` ke laluan Windows
`D:\apps\...\rootfs\data\local\custom_apps`. Kemudian penggantian seterusnya
untuk `/data/local` memetakan **semula** sebahagian laluan itu, menghasilkan
laluan bercantum dua kali.

**Mengapa ia tidak berlaku pada kereta:** pada Android, laluan itu hanya
`/data/local/...` — tiada pemacu `D:`, tiada pemetaan berlapis.

**Penyelesaian yang belum dibuat:** `run-sh.cjs` perlu memetakan dengan satu
laluan sahaja (regex beranchor `(^|\s|=)`), bukan `split().join()` yang
memetakan semula hasil pemetaan sebelumnya.

**Kesan:** pemasang berhenti di langkah 6 dan **tidak** menyelesaikan langkah 7.
Pada kereta sebenar tiada masalah ini kerana skrip berjalan dengan laluan
Android asli.

---

## Bug Sebenar Yang Sandbox Temui

Walaupun pemasang belum lulus, sandbox sudah membuktikan nilainya:

| # | Bug | Status |
| :--- | :--- | :--- |
| 1 | `boot_hook.sh` gagal jika `/data/local/window` belum wujud | **dibetulkan** (`mkdir -p`) |
| 2 | `for pair in "src:tgt"` rapuh — `:` dalam laluan memecahkan pemisah | **dibetulkan** (fungsi `do_mount`) |
| 3 | Argumen `do_mount` tidak dipetik | **dibetulkan** |
| 4 | `.bak` dalam init diproses | **dihasilkan semula, disahkan** |

Ketiga-tiga bug 1-3 di atas **hanya ditemui** kerana sandbox. Ia tidak
kelihatan pada kereta kerana laluan Android kebetulan tidak mengandungi `:`.

---

## Nilai Sandbox

1. **Membuktikan bug `.bak`** — boleh diulang tanpa risiko kereta
2. **Menangkap 3 bug skrip** yang hanya muncul dengan laluan luar biasa
3. **Membolehkan ujian regresi** jika skrip boot diubah pada masa hadapan

Untuk ujian penuh pemasang, kereta sebenar masih diperlukan — atau
penyelesaian laluan perlu disiapkan dahulu.
