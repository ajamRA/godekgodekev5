# PAKEJ KAWALAN STERENG — Proton e.MAS 5

**Untuk:** IHU801P yang sudah ada ADB + root.
**Fungsi:** Drive Mode, Regen Level, dan toggle Mod Servis — semuanya dari butang stereng.

---

## Butang Stereng

| Butang | Tahan | Fungsi |
| :--- | ---: | :--- |
| **Kanan** (Vol+) | 800 ms | Kitar Drive Mode: Comfort -> Eco -> Sport |
| **Kiri** (Vol-) | 800 ms | Kitar Regen: Low -> Medium -> High |
| **Bawah** (Vol-) | 5000 ms | Toggle Mod Servis (Stealth ON/OFF) |
| **Atas** (Vol+) | — | Volume kilang, **tidak diubah** |

Setiap pertukaran disertai bunyi chime sebagai maklum balas.

---

## Pemasangan

```powershell
# 1. Lihat rancangan
.\pasang_drivemode.ps1 -WhatIf

# 2. Pasang
.\pasang_drivemode.ps1 -Target 192.168.1.20

# 3. Sahkan
.\pasang_drivemode.ps1 -Status
```

Pemasang akan:
1. Cari kereta (atau guna -Target)
2. Sahkan IHU801P + root
3. Sahkan SHA256 JAR tempatan padan dengan versi yang diuji
4. Backup JAR dan pelancar lama
5. Hantar ke fail sementara, sahkan hash, baru ganti
6. Daftar auto-start dalam `avas_boot.sh`
7. Mulakan daemon dan sahkan

---

## VHAL Properties

| Fungsi | Property | Nilai |
| :--- | :--- | :--- |
| Drive Mode | `0x287000EA` (678428906) | `0` Comfort, `1` Eco, `2` Sport |
| Regen Level | `0x287001B1` (678429105) | `0` Low, `1` Medium, `2` High |

Kedua-dua nilai `access_mode = 3` (baca-tulis). Disahkan pada kereta.

---

## JAR

| Perkara | Nilai |
| :--- | :--- |
| Fail | `payload/bin/steering_mode_controller.jar` |
| Saiz | 52,735 bait |
| SHA256 | `99441654707109d669c9ba63d3f1ec751d7b700ea724cb4165ebab3030ec253b` |

Hash ini padan dengan versi yang **disahkan pada kereta 15 Okt** — ujian
fizikal VOL- lulus lima tap, auto-start selepas reboot disahkan.

Pemasang akan **menolak** pemasangan jika hash tidak padan. Ini menghalang
JAR yang salah daripada dipasang secara tidak sengaja.

---

## Kebergantungan

Pakej ini **memerlukan** Mod Servis (Stealth) untuk fungsi "Bawah 5 saat".

Jika `service_mode.sh` tiada, butang Bawah tidak akan berbuat apa-apa.
Pasang `PAKEJ_STEALTH_EMAS5` dahulu.

Juga: `SteeringModeDaemon` **mesti** kekal hidup dalam Stealth. `boot_hook.sh`
dalam pakej Stealth sudah memastikan ini. Jika daemon dimatikan, **ketiga-tiga**
butang mati — bukan Stealth sahaja.

---

## Status

```powershell
.\pasang_drivemode.ps1 -Status
```

Memaparkan:
- Daemon RUNNING / STOPPED
- SHA256 JAR pada kereta (dan sama ada padan dengan pakej)
- Pelancar wujud

---

## Buang

```powershell
.\pasang_drivemode.ps1 -Uninstall
```

Ini menghentikan daemon dan membuang JAR + pelancar.

> [!WARNING]
> Selepas uninstall, Mod Servis **tidak lagi boleh** ditukar dari stereng.
> Guna `sc_stealth_mode.ps1` dari laptop.

---

## Backup

Sebelum memasang, pemasang menyimpan:
```
/data/local/window/backup_drivemode/
  steering_mode_controller.jar.<stamp>
  start_steering_daemon.sh.<stamp>
```

Pulihkan secara manual:
```powershell
adb shell "cp /data/local/window/backup_drivemode/steering_mode_controller.jar.<stamp> /data/local/window/steering_mode_controller.jar"
adb shell "sh /data/local/window/start_steering_daemon.sh"
```

---

## Struktur

```
PAKEJ_DRIVEMODE_EMAS5/
├─ pasang_drivemode.ps1    pemasang
├─ README.md               dokumen ini
└─ payload/
   ├─ bin/
   │  └─ steering_mode_controller.jar   52,735 bait, hash disahkan
   ├─ start_steering_daemon.sh          pelancar
   └─ *.java                            sumber untuk rujukan
```

---

## Sumber Java

Kod sumber disertakan untuk rujukan dan audit:

| Fail | Peranan |
| :--- | :--- |
| `SteeringModeController.java` | Logik butang, hold timing, kitar mode |
| `SteeringModeDaemon.java` | Titik masuk proses |
| `EmasDirectInputAdapter.java` | Interception IInputManager |
| `EmasLogcatInputAdapter.java` | Fallback melalui logcat |
| `SteeringInputSession.java` | Antara muka adapter |

---

## Nota Penting

**Jangan tekan butang Atas.** Ia dikecualikan dengan sengaja supaya volume
kilang kekal berfungsi. Jika anda menekannya, tiada apa berlaku.

**Butang Bawah 5 saat** berkongsi fungsi dengan Stealth. Jika anda menahannya
terlalu lama semasa memandu, Mod Servis akan aktif — aplikasi tersembunyi.
Untuk memulihkan, tahan semula 5 saat.

**Tahan 800ms vs 5000ms** — pastikan anda tahan cukup lama untuk fungsi yang
dikehendaki. Tekan biasa (di bawah 800ms) akan diteruskan ke sistem kilang
sebagai volume biasa.
