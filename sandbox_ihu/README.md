# SANDBOX IHU — Simulasi pemasangan tanpa kereta

**Tujuan:** menguji `pasang_stealth.ps1` dan skrip boot tanpa menyentuh kereta,
dan **menghasilkan semula** keadaan yang menyebabkan Stealth bocor.

---

## Ringkasan: Apa Yang Berjaya

| Keupayaan | Status |
| :--- | :--- |
| `pasang_stealth.ps1` penuh | **LULUS** (7/7 langkah, exit 0) |
| Simulasi bug `.bak` | **BERJAYA** — dihasilkan semula |
| `service_mode.sh on` | **BERJAYA** — dijalankan dalam sandbox |
| Reboot: Stealth kekal | **BERJAYA** — daemon 1, mount 0 |
| Reboot: mod normal | **SEPARA** — lihat Batasan |

---

## Bukti 1: pemasang lulus penuh

```
$ powershell -File pasang_stealth.ps1 -Target 192.168.1.20 `
    -Adb "D:\apps\emas-ota\sandbox_ihu\adb.bat"

[2/7] Mengesahkan peranti...
  Model : IHU801P
  SDK   : 29
  Root  : YA
[3/7] Membuat backup...
  memindahkan fail .bak keluar dari /system/etc/init/ ...
[4/7] Menyalin fail... (7 fail)
[5/7] Menetapkan permission & konteks SELinux...
[6/7] Ujian dry-run boot hook... -> berfungsi
[7/7] Ringkasan...
  PEMASANGAN SELESAI
```

Sebelum pembetulan, ia berhenti di langkah 6. Selepas tiga pembetulan di
bawah, ia lulus.

---

## Bukti 2: bug `.bak` dihasilkan semula

```
$ node adb-mock.cjs -s 192.168.1.20:5555 reboot

[init] zzz_custom_apps.rc      -> 4 mount
[init] zzz_custom_apps.rc      -> exec start_telemetry.sh
[init] zzz_custom_apps.rc.bak  -> 4 mount          <-- backup jadi aktif
[init] zzz_custom_apps.rc.bak  -> exec start_telemetry.sh
[init] zzz_telemetry_boot.rc.bak2 -> service telemetry_boot
```

Ini persis apa yang berlaku pada kereta 18 Okt. Sandbox membolehkannya
diulang tanpa risiko.

---

## Bukti 3: Stealth bertahan selepas reboot

```
$ node adb-mock.cjs -s 192.168.1.20:5555 shell \
    "sh /data/local/window/service_mode.sh on"
[SERVICE_MODE] MOD SERVIS AKTIF

$ node adb-mock.cjs -s 192.168.1.20:5555 reboot
[init] post-fs-data -> boot_hook.sh
[init] daemon: 1, mount: 0
```

Selepas pemasangan (yang memindahkan `.bak` keluar), hook berjalan dan
Stealth kekal. Ini padan dengan hasil sebenar pada kereta.

---

## Tiga Bug Yang Sandbox Temui

| # | Bug | Kesan jika tidak ditemui |
| :--- | :--- | :--- |
| 1 | `boot_hook.sh` tulis log sebelum `mkdir -p` | **Gagal pada kereta baharu** yang belum ada `/data/local/window` — pemasangan mati senyap |
| 2 | `for pair in "src:tgt"` | Rapuh; `:` dalam laluan memecahkan pemisah |
| 3 | Argumen `do_mount` tidak dipetik | Sama — pemisahan salah |

Ketiga-tiga **hanya ditemui** kerana sandbox menjalankan laluan yang berbeza
daripada kereta. Bug #1 adalah yang paling serius: ia akan menggagalkan
pemasangan pada kereta kawan yang belum pernah dimod.

---

## Batasan

**Reboot dalam mod NORMAL tidak disimulasi dengan tepat.**

```
mount: too many arguments
Usage: mount [OPTION] [<win32path> <posixpath>]
```

Punca: Git Bash menggunakan `/usr/bin/mount` sendiri (Cygwin), bukan
`mount` Android. `boot_hook.sh` memanggil `mount none SRC TGT bind rec`
yang tidak difahami mount Cygwin.

Percubaan override dengan shim `.cmd` dan skrip `#!/bin/sh` **gagal**
kerana `/usr/bin` mendahului PATH.

**Kesan:** langkah mount dalam mod normal tidak berjaya, jadi `mount: 0`
sentiasa. Dalam Stealth ini tidak mengapa (mount memang di-skip), tetapi
ia bermakna sandbox **tidak boleh** mengesahkan pemasangan mount normal.

**Penyelesaian yang belum dibuat:** jalankan skrip melalui WSL atau MSYS2
dengan `mount` yang boleh diganti, atau bungkus `mount` sebagai fungsi
shell dalam skrip yang dijana.

---

## Cara Guna

```bash
cd sandbox_ihu

node setup-sandbox.cjs                    # sedia semula (kereta normal)
node adb-mock.cjs devices
node adb-mock.cjs -s 192.168.1.20:5555 reboot

# pasang
cd ../PAKEJ_STEALTH_EMAS5
powershell -ExecutionPolicy Bypass -File pasang_stealth.ps1 `
  -Target 192.168.1.20 -Adb "D:\apps\emas-ota\sandbox_ihu\adb.bat"
```

---

## Struktur

```
sandbox_ihu/
├─ setup-sandbox.cjs   bina rootfs palsu
├─ adb-mock.cjs        enjin: devices, connect, push, shell, reboot
├─ run-sh.cjs          jalan skrip, petakan laluan Android -> rootfs
├─ adb.bat             pembalut untuk PowerShell
├─ shim.cjs            ganti binari Android (tidak berkesan dalam Git Bash)
├─ README.md           dokumen ini
├─ rootfs/             (gitignored) sistem fail palsu
└─ state/              (gitignored) flag keadaan
```

---

## Disimulasi Dengan Setia

| Tingkah laku | Disimulasi |
| :--- | :--- |
| `/system` read-only | ya — `push`/`cp` gagal sehingga `remount,rw` |
| `.bak` dalam init diproses | **ya** |
| `pm disable-user` | ya — senarai disimpan |
| Kitab `service_mode.sh` | ya — skrip sebenar dijalankan |
| `boot_hook.sh` | ya — skrip sebenar dijalankan |
| `mount: 0` dalam Stealth | ya |

## Tidak Disimulasi

| Perkara | Sebab |
| :--- | :--- |
| `mount` dalam mod normal | Cygwin mount mengalahkan shim |
| SELinux sebenar | `restorecon` sentiasa berjaya |
| Binder / VHAL | di luar skop |
| Audio | tiada peranti |
| Timing boot | serta-merta |

---

## Kesimpulan

Sandbox ini berjaya untuk tujuannya: **mengesahkan pemasang dan menghasilkan
semula bug `.bak`**, serta menemui tiga bug skrip yang akan menggagalkan
pemasangan pada kereta baharu.

Ia **bukan** pengganti ujian kereta sebenar. Untuk mod normal (mount), ujian
pada kereta masih diperlukan.
