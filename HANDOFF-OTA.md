# DOKUMEN HANDOFF TEKNIKAL — Geely EX2 (EX2-HU) OTA INJECTION

**Status:** Pakej OTA FINAL siap & disahkan (SHA-256 boot `08f9e860…`). Laluan USB sah berjaya applyPayload.
**Tarikh:** 2026-09-12
**Peranti:** Geely EX2, EX2-HU, MediaTek MT6771 (`emc21b`), Android 10, build 785/625, A/B

---

## 1. OBJEKTIF

Aktifkan **ADB melalui Wi-Fi (TCP 5555)** pada IHU, kekal selepas reboot, tanpa kabel.
Kaedah: suntikan melalui pakej OTA USB rasmi (AOSP A/B `update.zip`), ditandatangani
dengan **test-keys** platform.

---

## 2. APA YANG SEBENARNYA DIBUAT PADA `boot.img`

> ⚠️ **PEMBETULAN:** dokumen sebelum ini menyebut suntikan pada
> `debug_ramdisk/adb_debug.prop`. **ITU SALAH.** Senarai di bawah ialah yang sebenar.

Strategi: modifikasi **ramdisk dalam `boot.img`** sahaja. Partition `system` **tidak** disentuh.

### 2.1 Overlay mount (dalam ramdisk `first_stage`)
| # | Sumber | Sasaran |
|---|---|---|
| 1 | `/first_stage_ramdisk/p` | `/system/etc/prop.default` |
| 2 | `/first_stage_ramdisk/w` | `/system/etc/init/blank_screen.rc` |

Mount guna `MS_BIND 0x1000`.

### 2.2 Hook kod `init`
- Lokasi hook: `init_bin` @ `0xA9F50` (kawasan zero-run, 176 bait)
- Branch: `0x6b6c8 b.ne #0xa9f50`
- Return: `0xa9f94 b #0x6b6d8`
- Patch tambahan: `mov w19, #1` @ `0x5C77C` → paksa `is_debuggable = 1`

### 2.3 Properti yang disuntik (overlay `p` → `/system/etc/prop.default`)
```
ro.secure=0
ro.debuggable=1
ro.adb.secure=0
ro.build.type=userdebug
```
> `ro.*` bersifat **write-once**. Sebab itu kita pilih `prop.default`
> (dimuat di `0x136E4`, **sebelum** `/system/build.prop` @ `0xAD4A`).

### 2.4 Hook rc (overlay `w` → `blank_screen.rc`)
Merentas `on early-init` / `on init` / `on property:sys.boot_completed=1`:
```
setprop service.adb.tcp.port 5555
setprop persist.adb.tcp.port 5555
setprop persist.service.adb.tcp.port 5555
```
Dan pada `sys.boot_completed=1`:
```
start adbd
exec -- /system/bin/settings put global adb_enabled 1
exec -- /system/bin/settings put secure adb_enabled 1
```

### 2.5 Hasil binaan
```
patched_build/boot.img   33,554,432 bait
SHA-256: 08f9e86065b39a8d3d67a8b475ce51d12d2a9a55ecce3cb5288ab17c212c876a
```
Ini **imej yang dipatch** — bukan imej kilang asal (`original_backup/boot.img` = `380a915f…`).

---

## 3. KANDUNGAN `E:\update.zip`

| Perkara | Nilai |
|---|---|
| Partition dalam payload | **`boot` sahaja** (16 ops) |
| `boot.img` dibina semula dari payload | `08f9e860…c876a` → **= `patched_build/boot.img`** ✔ |
| `payload.bin` | 15,610,229 bait |
| `upgrade.conf` / `META-INF` / `GpsUpgrade*` / `fsl_app*` | **TIDAK DIUBAH** |
| Tandatangan metadata & payload | **LULUS** (RSA e=3, `hdr+man+blobs`) |
| OA1 PKCS#7 (SignApk) | **LULUS** |

> ⚠️ **PEMBETULAN:** beza 136 bait (`15,610,229` vs `15,610,365`) antara pakej kita dan
> `update.zip.OLD_backup` **bukan** sekadar "tetapan mampatan". `boot.img` kedua-duanya
> **berlainan hash sepenuhnya** (`08f9e860…` vs `baaea874…`). Ia build berbeza.

---

## 4. SEJARAH FLASH & KEPUTUSAN LOG SEBENAR

Berdasarkan analisis log forensik kenderaan (`_logs/m1` & `latest_car_log`):

### 4.1 Butang USB Factory MEMANG Menjalankan `applyPayload` (Berjaya 2 Kali)

Sebelum ini timbul salah faham kononnya butang USB hanya mengemas kini MCU/GPS. **Log membuktikan sebaliknya**:

| Tarikh & Masa | Trigger | Saiz Payload | Status `applyPayload` | Tindakan Seterusnya |
|---|---|---|---|---|
| 06-09-2026 15:44:48 | `judgeUpdate: 1` (USB) | 15,608,689 B | `onPayloadApplicationComplete() 0` (**BERJAYA**) | McuUpdateCallback → Slot A aktif |
| 07-09-2026 15:13:48 | `judgeUpdate: 1` (USB) | 15,609,641 B | `onPayloadApplicationComplete() 0` (**BERJAYA**) | McuUpdateCallback → Slot A aktif |

Kedua-dua percubaan ini **berjaya menulis ke partition `boot_a`** tanpa sebarang ralat dari `update_engine`.

### 4.2 Kenapa Selepas Reboot ke Slot A, ADB Masih Tiada?

Apabila kereta reboot ke Slot A:
1. Pakej OTA kita **hanya mengandungi partition `boot`** (saiz ~15.6 MB).
2. Partition `system_a` di dalam Slot A adalah milik asal kilang iaitu **Build 625** (`ro.build.version.incremental=625`, `ro.build.type=user`, `ro.secure=1`, `ro.debuggable=0`).
3. **Punca Utama:** Pakej yang di-flash pada 6 dan 7 September **BUKAN Build Final kita (`08f9e860…`)**, sebaliknya adalah **Build Interim**:

| Boot SHA-256 (Awalan) | Saiz Payload | Punca Pakej | Status Flash di Kereta |
|---|---|---|---|
| `1087678f…` | 15,608,689 B | Arkib interim awal | **Pernah di-flash (6 Sept)** |
| `36549569…` | 15,609,641 B | `update.unsigned.zip` | **Pernah di-flash (7 Sept)** |
| `08f9e860…` | 15,610,229 B | `output/update.zip` (**BUILD FINAL KITA**) | **❌ BELUM PERNAH DI-FLASH DI KERETA** |

Build interim 6 & 7 September kekurangan patch kritikal pada binari `init` (string redirection `0x136E4` ke `/first_stage_ramdisk/p`) serta tiada tetapan automatik `settings put global adb_enabled 1`.

---

## 5. FORENSIK AVB — FAKTA

| Fail | `flags` | Kesan |
|---|---|---|
| `vbmeta.img` (induk) | **2** | Verifikasi AVB induk **DIMATIKAN** dari kilang (`VerificationDisabled`) |
| `vbmeta_system.img` | 0 | dm-verity **AKTIF** (`system`, `product`) |
| `vbmeta_vendor.img` | 0 | dm-verity **AKTIF** (`vendor`) |

**AVB tidak menyekat `boot.img`.** Flag `2` melangkau verifikasi kernel/bootloader. Kerana itu:
- **TIDAK PERLU** ubah descriptor `boot` dalam `vbmeta`.
- **TIDAK PERLU** cari private key OEM Geely.
- **TIDAK PERLU** usik partition `system` secara langsung (dm-verity aktif).
- ⚠️ **JANGAN** sesekali set `vbmeta_system`/`vendor` kepada `flags=2` kerana bootloader LK akan terus menolak dan menandakan slot sebagai *unbootable*.

---

## 6. MEKANISME SUNTIKAN KUKUH DALAM BUILD FINAL (`08f9e860…`)

Build final kita (`output/update.zip`, SHA-256 boot `08f9e860…`) direka untuk mengatasi isu *System-as-Root / Second Stage Init*:

1. **Patch Binari `init` (First Stage):**
   - Offset `0x136E4`: String `/system/etc/prop.default` dialihkan terus ke `/first_stage_ramdisk/p`.
   - Offset `0x5C77C`: Arahan `mov w19, #1` dipaksa untuk memastikan `is_debuggable = 1`.
   - Offset `0x3EBC8` & `0x5BDA8`: NOP untuk bypass semakan debuggable ketika memuatkan prop.
   - Offset `0xA9F50`: Hook dwi-mount `MS_BIND`:
     * `/first_stage_ramdisk/p` → `/system/etc/prop.default`
     * `/first_stage_ramdisk/w` → `/system/etc/init/blank_screen.rc`
2. **Properti `p` yang Disuntik:**
   - `ro.secure=0`
   - `ro.debuggable=1`
   - `ro.adb.secure=0`
   - `ro.build.type=userdebug`
   - `service.adb.tcp.port=5555`
   - `persist.adb.tcp.port=5555`
3. **Skrip RC `w` (Overlay `/system/etc/init/blank_screen.rc`):**
   - Memulakan `adbd` secara automatik apabila boot selesai (`sys.boot_completed=1`).
   - Melaksanakan perintah sistem:
     ```sh
     settings put global adb_enabled 1
     settings put secure adb_enabled 1
     ```
4. **Pemulihan Butang ADB di AcoXDebugTools:**
   - Dalam `MainActivity.java:361`, butang ADB hanya disorokkan jika `ro.secure==1 && ro.debuggable==0`.
   - Dengan `ro.secure=0` dan `ro.debuggable=1`, butang ADB dalam aplikasi Debug Tools akan **muncul semula secara automatik**.

---

## 7. SENARAI FAIL & ARTIFAK

| Fail | Lokasi | Saiz | Keterangan |
|---|---|---|---|
| **Pakej OTA Final** | `workspace_injection\output\update.zip` | 16,774,747 B | Sedia untuk disalin ke USB (Root Pendrive) |
| **Boot Image Patched** | `workspace_injection\patched_build\boot.img` | 33,554,432 B | SHA-256: `08f9e860…c876a` |
| **Boot Image Asal** | `workspace_injection\original_backup\boot.img` | 33,554,432 B | SHA-256: `380a915f…` (Backup kilang) |
| **Pakej Rollback** | `workspace_injection\update_revert.zip` | 16,737,842 B | Untuk pulihkan Slot ke boot asal jika perlu |
| **Skrip Pengesahan** | `workspace_injection\verify_candidate.py` | — | Skrip semakan integriti & tandatangan RSA |

---

## 8. LANGKAH PELAKSANAAN (SOP UNTUK USER)

1. **Sediakan Pendrive:**
   - Format pendrive dalam format **FAT32**.
   - Salin fail dari `D:\apps\ex2-ota\workspace_injection\output\update.zip` terus ke punca utama pendrive (`[USB]:\update.zip`).
2. **Cucuk ke Kenderaan:**
   - Pasang pendrive pada port USB konsol depan EX2-HU.
3. **Picu Kemas Kini Melalui AcoXDebugTools:**
   - Buka aplikasi **AcoXDebugTools** pada skrin kereta.
   - Pergi ke bahagian kemas kini sistem / T-Box.
   - Tekan butang **Full Update / USB Update**.
4. **Tunggu Proses Selesai & Kereta Reboot:**
   - Sistem akan menyalin pakej, mengesahkan tandatangan testkey, menjalankan `update_engine` ke Slot A/B bertentangan, dan reboot automatik.
5. **Sambung ADB Melalui Wi-Fi:**
   - Pastikan laptop dan kereta berada di dalam rangkaian Wi-Fi yang sama (contohnya hotspot telefon).
   - Dapatkan alamat IP kereta (contoh `192.168.43.xxx`).
   - Jalankan perintah dari laptop:
     ```sh
     adb connect <IP_KERETA>:5555
     adb devices
     adb shell
     ```
