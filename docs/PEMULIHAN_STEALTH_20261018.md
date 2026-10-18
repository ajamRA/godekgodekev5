# PEMULIHAN — Mod Servis (Stealth) e.MAS 5

**Untuk:** kalau sesuatu tidak berjalan selepas pemasangan.
**Prinsip:** ADB tidak hilang. Selagi ADB boleh disambung, semua boleh dibaiki.

---

## Urutan Pemulihan (PENTING)

Ramai tersilap di sini. Urutan betul:

```
1. service_mode.sh off     <- pulihkan apps + daemon + AVAS
2. pasang_stealth.ps1 -Rollback   <- pulihkan .rc
3. reboot
```

**Jangan buat 2 dulu.** Rollback pulihkan `.rc` tetapi **tidak** menghidupkan
semula aplikasi yang dilumpuhkan. Jika kau rollback dahulu, kereta dalam keadaan
pelik: hook baru hilang, tetapi apps masih tersembunyi.

---

## Diagnosis Pantas

Sambung dan semak satu baris:

```powershell
adb connect 192.168.1.20:5555
adb shell "sh /data/local/window/boot_hook.sh --status"
```

Keluaran yang mungkin:

| Output | Maksud |
| :--- | :--- |
| `state : STEALTH` | Stealth aktif — ini keadaan normal |
| `state : NORMAL` | Mod biasa |
| `steering: RUNNING` | Butang stereng berfungsi |
| `steering: STOPPED` | **Butang akan mati** — lihat §4 |
| `No such file` | `boot_hook.sh` tiada — pemasangan tidak lengkap |

---

## 1. Bootloop / Kereta Tidak Naik

**Kebarangkalian: sangat rendah.**

Punca perlindungan:

```sh
exec u:r:su:s0 root root -- /system/bin/timeout 25 /system/bin/sh /data/local/window/boot_hook.sh
```

`timeout 25` memastikan init tidak tersekat. Jika `boot_hook.sh` hang atau
tiada, selepas 25 saat init **abaikan** dan teruskan boot. `boot_hook.sh` juga
berakhir dengan `exit 0` tanpa mengira kegagalan.

**Jika masih bootloop**, bermakna isu bukan pada hook. Punca paling mungkin:
`.rc` yang rosak sintaks sehingga init enggan memprosesnya.

Pulihkan melalui ADB (kereta perlu naik sedikit untuk ADB hidup):

```powershell
adb connect 192.168.1.20:5555
adb shell "mount -o remount,rw /"
adb shell "ls /data/local/init_backup/"
adb shell "cp /data/local/init_backup/zzz_custom_apps.rc.bak_<stamp> /system/etc/init/zzz_custom_apps.rc"
adb shell "mount -o remount,ro /"
adb reboot
```

Jika ADB tidak hidup langsung — ini di luar skop dokumen ini. Rujuk
`docs/PANDUAN_MODIFIKASI_GEELY_EX2.md` untuk pemulihan peringkat partition.

---

## 2. Aplikasi Masih Tersembunyi

Stealth masih aktif. Matikan:

```powershell
adb shell "sh /data/local/window/service_mode.sh off"
```

Pengesahan:

```powershell
adb shell "sh /data/local/window/service_mode.sh status"
adb shell "pm list packages -d"
```

Selepas `off`, `status` patut `NORMAL_MODE_ACTIVE` dan `pm list packages -d`
patut hanya menunjukkan `com.emas.dvrprobe` (atau kosong).

**Jika masih tersembunyi**, hidupkan secara manual satu-satu:

```powershell
$apps = @(
  "org.smarttube.stable","com.netflix.mediaclient","com.noor.thenoorcar",
  "com.vivaldi.browser.automotive","me.zhanghai.android.files",
  "com.malaysia.syok","com.thisisaim.hotfm","com.cybercat.acbridge",
  "com.retroarch.aarch64"
)
foreach ($a in $apps) { adb shell "pm enable $a" }
```

---

## 3. AVAS Masih Berbunyi Custom

Semak slot mana yang masih custom:

```powershell
adb shell "for i in 1 2 3; do f=`$(ls /vendor/etc/avas/sound_type_`$i/*.wav | grep -v bDM | head -1); echo -n \"slot `$i: \"; md5sum `$f /data/local/avas/factory_backup/`$(basename `$f) 2>/dev/null | awk '{print `$1}' | uniq -c; done"
```

Jika ada `1` sahaja (bukan `2`), fail itu bukan kilang.

**Pembetulan:**

```powershell
adb shell "sh /data/local/window/service_mode.sh off"   # pulih custom
adb shell "sleep 3"
adb shell "sh /data/local/window/service_mode.sh on"    # paksa kilang
```

`service_mode.sh on` mengikat ketiga-tiga slot kepada `factory_backup/`.

**Jika `factory_backup/` hilang:**

```powershell
adb shell "ls -l /data/local/avas/factory_backup/"
```

Jika kosong, fail kilang tidak boleh dipulihkan dari peranti. Perlu salin
semula daripada backup laptop.

---

## 4. Butang Stereng Tidak Berfungsi

Ini masalah yang **paling kerap** berlaku. Punca: `SteeringModeDaemon` mati.

Semak:

```powershell
adb shell "pgrep -f SteeringModeDaemon"
```

Tiada output = mati.

**Pembetulan:**

```powershell
adb shell "sh /data/local/window/start_steering_daemon.sh"
```

Sahkan:

```powershell
adb shell "ps -A -o ARGS | grep -i steering"
adb shell "cat /data/local/window/steering.log"
```

Log patut ada `Direct Native Android IInputManager key interception active.`

**Punca berulang:** jika daemon mati setiap reboot, `boot_hook.sh` tidak
berjalan atau tidak memulakannya. Semak:

```powershell
adb shell "cat /data/local/window/boot_hook.log"
```

---

## 5. Rollback ke Keadaan Asal

**Urutan betul:**

```powershell
# 1. Keluar Stealth dahulu
adb shell "sh /data/local/window/service_mode.sh off"

# 2. Pulihkan .rc
.\pasang_stealth.ps1 -Target 192.168.1.20 -Rollback

# 3. Reboot (skrip TIDAK reboot sendiri)
adb shell "reboot"
```

### Apa yang rollback buat

- Remount `/system` rw
- Salin semula 3 fail `.rc` dari `/data/local/init_backup/`
- Remount `/system` ro
- Cetak "Reboot diperlukan" — **tidak** reboot sendiri

### Apa yang rollback **tidak** buat

| Perkara | Status |
| :--- | :--- |
| Padam `/data/local/window/boot_hook.sh` | tidak |
| Padam `/data/local/window/service_mode.sh` | tidak |
| Padam `/data/local/window/telemetry_guard.sh` | tidak |
| Padam `/data/local/avas/avas_boot.sh` | tidak |
| Hidupkan semula aplikasi | **tidak** — guna `service_mode.sh off` |
| Pulihkan AVAS custom | tidak — `off` yang buat |
| Reboot | tidak |

Jadi selepas rollback, kereta berfungsi normal tetapi **fail baru masih ada**.
Jika mahu bersih sepenuhnya:

```powershell
adb shell "rm -f /data/local/window/boot_hook.sh /data/local/window/telemetry_guard.sh"
```

> [!NOTE]
> `service_mode.sh` dan `avas_boot.sh` sebaiknya **jangan** dipadam jika
> pakej Drive Mode masih dipasang — butang Bawah 5 saat bergantung padanya.

---

## 6. Backup Yang Wujud

| Lokasi | Kandungan | Dibuat oleh |
| :--- | :--- | :--- |
| `/data/local/init_backup/` | 3 fail `.rc` asal + semua `.bak` yang dipindah | pemasang Stealth |
| `/data/local/window/backup_drivemode/` | JAR + pelancar stereng lama | pemasang Drive Mode |
| `/data/local/avas/factory_backup/` | 3 fail WAV kilang | persediaan AVAS asal |

Senarai penuh:

```powershell
adb shell "ls -l /data/local/init_backup/ /data/local/window/backup_drivemode/ /data/local/avas/factory_backup/"
```

---

## 7. Kembali ke Mod Normal (tanpa rollback)

Jika kereta hanya perlu keluar dari Stealth untuk memandu:

```powershell
adb shell "sh /data/local/window/service_mode.sh off"
```

Atau tahan **Volume Down 5 saat**.

Stealth disyorkan hanya untuk lawatan Pusat Servis, bukan penggunaan harian —
aplikasi kustom tersembunyi dan tidak boleh digunakan.

---

## 8. Jika ADB Tidak Boleh Disambung

Urutan semakan:

```powershell
# 1. Ping kereta
ping 192.168.1.20

# 2. Cuba connect semula
adb disconnect
adb connect 192.168.1.20:5555

# 3. Jika IP berubah, cari
#    Settings kereta -> System -> About -> IP
```

Jika ADB mati sepenuhnya:

- Port 5555 didedahkan oleh `/system/etc/init/zzz_adbtcp.rc`
- Jika `.rc` itu tidak disentuh (pemasang Stealth tidak menyentuhnya), ADB
  patut hidup kembali selepas reboot
- Jika masih mati, perlu akses USB atau pemulihan partition

---

## 9. Ringkasan Jaring Keselamatan

| Lapisan | Perlindungan |
| :--- | :--- |
| 1 | `timeout 25` — hook hang tidak sebabkan bootloop |
| 2 | `exit 0` — kegagalan skrip tidak menghentikan boot |
| 3 | `/system` dikunci `ro` selepas pemasangan |
| 4 | Backup `.rc` dalam `/data/local/init_backup/` |
| 5 | **ADB kekal** — semua boleh dibaiki dari laptop |
| 6 | `.bak` dipindah keluar dari init (punca bug lama) |

Lapisan **5** ialah yang paling penting. Bukan rollback, bukan backup —
tetapi keupayaan masuk semula melalui ADB.

---

## 10. Yang Tidak Dijamin

Dokumen ini **tidak** menjamin:

- Pemulihan jika partition `/data` rosak (perlu tools khusus)
- Pemulihan jika `/system` ditulis rosak tanpa backup
- Pemulihan tanpa ADB langsung

Untuk kes tersebut, rujuk `EMAS_TOOLBOX_DESKTOP` (sandaran identiti 60 MB)
dan `docs/PANDUAN_MODIFIKASI_GEELY_EX2.md`.

---

*Dokumen ini ditulis berdasarkan kod sebenar dalam `PAKEJ_STEALTH_EMAS5/`,
bukan andaian. Setiap arahan telah disahkan wujud dalam skrip.*
