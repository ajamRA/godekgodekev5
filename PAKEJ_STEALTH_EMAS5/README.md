# PAKEJ MOD SERVIS (STEALTH) — Proton e.MAS 5

**Untuk:** Proton e.MAS 5 (IHU801P) yang **sudah** mempunyai ADB + root.
**Tujuan:** Memasang Mod Servis yang menyembunyikan semua modifikasi sebelum
kereta dihantar ke Pusat Servis Proton.

> [!IMPORTANT]
> Pakej ini **tidak** membuka ADB atau root. Ia menganggap kedua-duanya sudah
> ada. Jika belum, gunakan `PAKEJ_UNLOCK_ADB_EMAS5/1_USB_UNLOCK` dahulu.

---

## Apa Yang Ia Buat

Apabila Mod Servis diaktifkan:

| Komponen | Tindakan |
| :--- | :--- |
| Aplikasi kustom | 10 pakej dilumpuhkan (SmartTube, Netflix, RetroArch, dll.) |
| Bind mount slot | Dibatal — APK asal tidak tersembunyi di sebalik mount |
| AVAS luar | Ketiga-tiga slot dipulihkan ke fail kilang dari `factory_backup` |
| Nada kunci | Ditukar kepada fail kilang |
| Daemon kustom | Telemetri, megafon, salam, pintu — semua dimatikan |
| Steering daemon | **Dikekalkan** (perlu untuk butang Volume Down) |

**Bertahan selepas reboot.** Ini perbezaan utama berbanding versi lama yang
bocor apabila kereta dihidupkan semula di SC.

---

## Keperluan

| Perkara | Status |
| :--- | :--- |
| Model | IHU801P sahaja |
| Android | 10 (SDK 29) |
| ADB | Diaktifkan, port 5555 |
| Root | `adb shell id` -> `uid=0` |
| Laptop | Windows, PowerShell 5+ |
| Rangkaian | Laptop dan kereta pada Wi-Fi sama |

---

## Pemasangan

### 1. Sambung ke kereta

```powershell
adb connect 192.168.1.20:5555
adb devices
```

Ganti IP mengikut kereta anda. Cari IP di Settings → System → About.

### 2. Semak rancangan dahulu

```powershell
.\pasang_stealth.ps1 -WhatIf
```

Ia menyenaraikan fail yang akan ditulis tanpa mengubah apa-apa.

### 3. Pasang

```powershell
.\pasang_stealth.ps1 -Target 192.168.1.20
```

Skrip akan:
1. Sahkan model dan root
2. Backup konfigurasi asal ke `/data/local/init_backup/`
3. **Pindahkan semua fail `.bak` keluar dari `/system/etc/init/`**
4. Salin 7 fail
5. Tetapkan permission dan konteks SELinux
6. Jalankan ujian dry-run
7. Kunci semula `/system` kepada `ro`

### 4. Reboot dan sahkan

```powershell
adb -s 192.168.1.20:5555 reboot
```

Selepas boot, jalankan:

```powershell
.\sc_stealth_mode.ps1 -Target 192.168.1.20 -Status
```

---

## Penggunaan Harian

### Melalui butang stereng

Tahan **butang BAWAH (Volume Down)** stereng selama **5 saat**.
Bunyi chime akan menandakan pertukaran.

### Melalui laptop

```powershell
.\sc_stealth_mode.ps1                  # toggle
.\sc_stealth_mode.ps1 -Status          # lihat keadaan
.\sc_restore_mode.ps1                  # pulihkan mod normal
```

---

## Pengesahan Selepas Pemasangan

Keadaan Stealth yang betul:

```
Status        : SERVICE_MODE_ACTIVE
Pakej disable : 10
Daemon        : 1   (SteeringModeDaemon sahaja)
Mount slot    : 0
Port 8888/90  : 0
```

Jika `Daemon` lebih daripada 1, atau `Mount slot` bukan 0, semak
`/data/local/window/boot_hook.log`.

---

## Rollback

```powershell
.\pasang_stealth.ps1 -Rollback
```

Atau manual pada kereta:

```sh
adb shell "mount -o remount,rw /"
adb shell "cp /data/local/init_backup/zzz_custom_apps.rc.bak_<stamp> /system/etc/init/zzz_custom_apps.rc"
adb shell "mount -o remount,ro /"
adb reboot
```

---

## Amaran Penting

> [!CAUTION]
> **Jangan letak fail backup dalam `/system/etc/init/`.**
>
> Init memproses **SEMUA** fail dalam direktori itu tanpa menapis sambungan.
> `zzz_custom_apps.rc.bak` ialah konfigurasi **aktif**, bukan backup pasif.
> Ini punca utama Stealth bocor selepas reboot dalam versi lama.

> [!WARNING]
> **Jangan matikan `SteeringModeDaemon` dalam Stealth.**
>
> Ia satu-satunya cara butang Volume Down berfungsi. Tanpanya, Stealth
> terkunci dan hanya boleh dimatikan melalui ADB.

---

## Struktur Fail

```
PAKEJ_STEALTH_EMAS5/
├─ pasang_stealth.ps1      pemasang utama
├─ sc_stealth_mode.ps1     toggle dari laptop
├─ sc_restore_mode.ps1     pulihkan mod normal
├─ README.md               dokumen ini
└─ payload/
   ├─ boot_hook.sh              logik boot (dry-run capable)
   ├─ telemetry_guard.sh        pembalut semakan bendera
   ├─ service_mode.sh           toggle on/off dalam kereta
   ├─ avas_boot.sh              AVAS + daemon pada boot
   ├─ zzz_custom_apps.rc        satu baris: panggil boot_hook.sh
   ├─ zzz_avas_boot.rc          panggil avas_boot.sh
   └─ zzz_telemetry_boot.rc     panggil telemetry_guard.sh
```

---

## Cara Ia Berfungsi

```
                    post-fs-data  (init)
                          │
                          ▼
              zzz_custom_apps.rc   (1 baris)
                          │
                          ▼
           boot_hook.sh  ── semak bendera ──┐
                          │                 │
              ┌───────────┴───────────┐     │
              ▼                       ▼     │
        STEALTH aktif            Normal     │
        skip mount               mount slot │
        skip daemon              start daemon
              │                       │     │
              └───────────┬───────────┘     │
                          ▼                 │
              SteeringModeDaemon  ◄─────────┘
              (SENTIASA dimulakan)
```

`boot_hook.sh` boleh diuji tanpa risiko:

```sh
adb shell "sh /data/local/window/boot_hook.sh --dry-run"
adb shell "sh /data/local/window/boot_hook.sh --status"
```

---

## Masalah Lazim

| Gejala | Punca | Penyelesaian |
| :--- | :--- | :--- |
| Butang Volume Down tak berfungsi | SteeringModeDaemon mati | `adb shell "sh /data/local/window/start_steering_daemon.sh"` |
| Stealth bocor selepas reboot | Fail `.bak` dalam `/system/etc/init/` | `adb shell "mv /system/etc/init/*.bak* /data/local/init_backup/"` |
| AVAS masih custom | Slot 2 tidak diurus | Semak `mount \| grep avas`; jalankan `service_mode.sh off` kemudian `on` |
| Daemon bangkit semula | Skrip pelancar masih hidup | Semak `ps -A -o ARGS \| grep start_` |
| Semua daemon mati termasuk steering | Versi lama avas_boot.sh | Pasang semula pakej ini |

---

## Diuji Pada

| Perkara | Nilai |
| :--- | :--- |
| Model | IHU801P |
| Android | 10 (SDK 29) |
| Tarikh | 18 Oktober 2026 |
| Reboot diuji | ya, 4 kali |
| Hasil | Stealth kekal, butang berfungsi, AVAS kilang |

Dokumen penuh: `docs/STEALTH_REBOOT_PERSISTENCE_20261018.md`
Bukti ujian: `artifacts/stealth-mode-test-20261018/`
