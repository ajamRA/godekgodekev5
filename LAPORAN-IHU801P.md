# LAPORAN TEKNIKAL — Proton e.MAS 5 (IHU801P) OTA Injection / ADB-over-Wi-Fi
Tarikh: 12 Sept 2026
Status: **GAGAL dari segi strategi suntikan. Flash berjaya, ADB tak hidup. Cause of failure = salah faham Android 10 SAR.**

---

## 1. RINGKASAN (apa nak dicapai, apa yang jadi)

**Matlamat:** hidupkan ADB over Wi-Fi (TCP 5555) pada head unit kereta, dengan suntikan `boot.img` yang dipatch ke dalam pakej OTA `update.zip` (`payload.bin`), di-flash guna butang **USB Update** dalam kereta sendiri.

**Hasil:** pakej di-flash **berjaya** (6 & 7 Sept 2026, log sahkan `onPayloadApplicationComplete() 0`). Tapi selepas boot:
```
[init.svc.adbd]: [stopped]
[ro.debuggable]: [0]
[ro.adb.secure]: [1]
[sys.usb.config]: [mtp]
```
ADB langsung tak hidup.

---

## 2. PUNCA SEBENAR (kenapa suntikan tak jadi) — ini bahagian paling penting

Boot image IHU801P ialah **recovery-style ramdisk + `androidboot.force_normal_boot=1`**. Dalam Android 10 **System-as-Root (SAR)**:

1. First stage: init dari **ramdisk** jalan, mount partition system, lepas tu `switch_root`.
2. Second stage: init **di-`exec` dari partition `system`** (`/system/bin/init` milik kereta), dan init itu baca `/init.rc`, `/init.usb.rc`, `/init.environ.rc`, `/system/etc/init/*.rc` **semuanya dari partition system** — bukan dari ramdisk kita.

Maka **semua** suntingan kita dalam ramdisk (bind-mount `prop.default`, patch binary `init`, `init.rc` tambahan, `default.prop`, `adb_debug.prop`) **mati** sebaik `switch_root`.

### Bukti kukuh dari log kereta sendiri

```
init: Switching root to '/first_stage_ramdisk'
init: Parsing file /init.rc...                       <-- 860+ baris
init: Parsing file /init.usb.rc...                    <-- TIADA dalam ramdisk kita
init: Parsing file /init.environ.rc...
init: Parsing file /FWUpgradeInit.rc...
init: Parsing file /system/etc/init/blank_screen.rc...  <-- fail stok
init: processing action (ro.debuggable=0) from (/init.rc:860)
```

`init.rc` dalam ramdisk kita hanya **203 baris**. Yang log rujuk ialah baris **860** → ia `init.rc` **partition system kilang**.
Hook kita bind-mount `w` over `/system/etc/init/blank_screen.rc` — tapi log tetap `Parsing file /system/etc/init/blank_screen.rc` (fail stok) → bind-mount tak sampai.

**Kesimpulan:** `ro.debuggable=0` datang dari partition `system` slot aktif. Tak ada apa dalam `boot.img` boleh ubah ini, sebab second-stage init bukan milik kita.

---

## 3. PEMBETULAN PENTING — cadangan "tulis terus ke partition system" DITARIK BALIK ⚠️

Cadangan awal (patch `/system/etc/prop.default` + bungkus `system` dalam payload) **berisiko**:

- `parts/vbmeta_system.img` dan `parts/vbmeta_vendor.img` = **`flags=0`** → dm-verity (hashtree) sepatutnya **aktif** untuk `system`/`vendor`.
- Kalau partition `system` diusik mentah-mentah tanpa jana semula hashtree + sign semula `vbmeta_system`, dm-verity boleh trigger → **kernel panic masa mount /system → bootloop / brick**.
- `parts/system.img` = **3,928,526,848 B (3.9 GB)** → payload besar, flash melalui USB kereta lambat + berisiko.

**Nota jujur (supaya orang lain tak silap):** log kereta **ini** ada tulis
`[libfs_avb]Returning avb_handle with status: VerificationDisabled` dan
`[libfs_avb]AVB HASHTREE disabled on: /vendor | /product | /vbmeta_system`
— jadi pada unit ini dm-verity nampak **tidak diaktifkan**. **TETAPI** jangan bergantung pada ini. Kalau nak usik `system`, **buat cara betul**: jana semula hashtree + sign semula `vbmeta_system.img`.

---

## 4. DUA JALAN PENYELESAIAN

### Pilihan A — Kekal dalam `boot.img` (murah, tapi aku tak boleh janji jadi)
- Kernel command line 100% dalam kawalan `boot.img`.
- `first_stage_ramdisk/fstab.emc21b` (ada dalam ramdisk, 5,243 B) boleh jadi tempat arahkan overlay/bind mount yang **kekal merentas `switch_root`**.
- **Build FINAL (`08f9e860…`) yang belum pernah di-flash** mengandungi patch string offset `0x136E4` dalam binary `init` (`/system/etc/prop.default` → `/first_stage_ramdisk/p`).
- ⚠️ **Amaran jujur:** patch `0x136E4` itu ada pada `init` **dalam ramdisk**. Kalau second-stage init yang jalan ialah milik **partition system**, patch itu **takkan berkesan** — sama macam yang berlaku pada build Sept-7. Ia tetap murah untuk diuji (satu flash, risiko rendah), tapi ia bukan penyelesaian yang dijamin.

### Pilihan B — Patch partition `system` (cara sebenar)
- Patch `prop.default` / fail berkaitan dalam `system.img`, **jana semula hashtree**, dan **sign semula `vbmeta_system.img`** supaya dm-verity tak trigger panic.
- Ini jalan paling pasti, tapi melibatkan payload besar (system 3.9 GB) dan kerja signing AVB.
- **Alternatif lebih kecil:** guna `partial_update` dalam manifest (`ops` untuk blok yang berubah sahaja) supaya payload tak perlu bawa 3.9 GB penuh — perlu dibangunkan + diuji.

### Pilihan C (perlu penelitian lanjut)
Betulkan **lokasi hook** dalam `init` first stage supaya bind-mount `p` → `/system/etc/prop.default` **benar-benar tercapai** sebelum second stage baca prop. Build Sept-7 ada hook (offset `0xA9F50`, branch dari `0x6B6C8`) tetapi ia tidak tercapai/dilaksanakan. Ini kerja reverse-engineering tambahan.

---

## 5. APA YANG TELAH DIBINA (artifak)

Asas: `workspace_injection/patch_boot_with_hook.py`

**A. Patch binary `system/bin/init` dalam ramdisk:**

| Offset | Patch | Tujuan |
|---|---|---|
| `0x3EBC8` | NOP | matikan semakan `/force_debuggable` |
| `0x5BDA8` | NOP | matikan semakan debuggable masa muat `/debug_ramdisk/adb_debug.prop` |
| `0x5C77C` | `mov w19,#1` | paksa `is_debuggable=1` |
| `0x136E4` | tukar string | `/system/etc/prop.default` → `/first_stage_ramdisk/p` |
| `0x6B6C8` | branch | lompat ke hook |
| `0xA9F50` | kod ARM64 | bind-mount `p` → `/system/etc/prop.default`; `w` → `/system/etc/init/blank_screen.rc` |

**B. Fail disuntik ke ramdisk:** `p` (build.prop dipatch), `w` (rc Wi-Fi ADB), `force_debuggable`, `debug_ramdisk/adb_debug.prop`.

**C. `prop.default` dipatch:** `ro.secure=0`, `ro.debuggable=1`, `ro.adb.secure=0`, `ro.*.build.type=userdebug`, `service.adb.tcp.port=5555`, `persist.adb.tcp.port=5555`, `persist.service.adb.tcp.port=5555`.

**D. `init.rc` ramdisk:** `on boot` + `sys.boot_completed=1` → setprop port 5555, `start adbd`, `settings put global/secure adb_enabled 1`.

**E. Kernel cmdline:** `buildvariant=userdebug androidboot.debuggable=1 androidboot.force_normal_boot=1`.

**F. Dibungkus** jadi `update.zip` (A/B, `payload.bin`), ditandatangan RSA (**e=3**), verifikasi lulus (`verify_candidate.py` exit 0).

---

## 6. FAKTA YANG DISAHKAN

| Soalan | Jawapan | Bukti |
|---|---|---|
| Pakej kita pernah di-flash? | **YA**, 6 & 7 Sept | `onPayloadApplicationComplete() 0` |
| Partition dalam payload kita? | **`['boot']` sahaja (count=1)** | parse manifest `payload.bin` |
| Laluan USB sampai ke `update_engine`? | **YA** (sangkaan lama "tidak" = SALAH) | log |
| AVB halang? | **Tidak** pada unit ini | `VerificationDisabled`, `AVB HASHTREE disabled` |
| `VERSION_CODE`/`SystemUpdateVersionCompare` halang? | **Tidak** (fail-open, gated `isCanDownVer`) | baca kod |
| ada penghantar `intent.action.UPDATE_BROADCAST`? | **TIADA** dalam mana-mana APK | scan NUL-terminated `_scan2.py` |
| App OTA rasmi guna apa? | **AIDL `IOtaVerifyService`** (bukan broadcast) | `UpgradeServiceManager.java` |
| ADB button hilang sebab? | `AcoXDebugTools/MainActivity.java:361` `IsUserVersion()` → sembunyi bila `ro.secure=1 && ro.debuggable=0`; ada juga `initIsHideBtnForVersion()`/`IsOfficialVersion` | baca kod |

---

## 7. STATUS FAIL

| Fail | Butir |
|---|---|
| `workspace_injection/output/update.zip` | **Build FINAL**, 16,774,747 B, sha256 `868913d6518de5cdd50f7878e9307a91632ed40a9e9770f06ca60cb96db416e6` |
| payload final | 15,610,229 B, boot sha `08f9e86065b39a8d3d67a8b475ce51d12d2a9a55ecce3cb5288ab17c212c876a` |
| `output/update.unsigned.zip` | Build Sept-7 (**pernah di-flash**), boot `36549569**` |
| `D:/Mama/Reconstructed/Archives/zip/9 files_000009.zip` | Build Sept-6 (**pernah di-flash**), boot `1087678f**` |
| `workspace_injection/revert_package/update_revert.zip` | Rollback, boot kilang `380a915f**` |
| `parts/boot.img` | Boot kilang, 33,554,432 B |
| `parts/system.img` | 3,928,526,848 B (raw) |
| `parts/vbmeta.img` | `flags=2` (VERIFICATION_DISABLED) |
| `parts/vbmeta_system.img`, `parts/vbmeta_vendor.img` | `flags=0` |
| Kunci | testkey `55d55d05**`; chain boot Proton `9d808b09**`; otacert kita `0c2440c0**`; otacert kilang Geely `06cac910**` |

**Soalan terbuka:** otacert pakej kita ≠ otacert kilang. `RecoverySystem.verifyPackage` semak `/system/etc/security/otacerts.zip`. Belum diselesaikan.

---

## 8. SKRIP YANG DITINGGALKAN

- `patch_boot_with_hook.py` — pembina boot patched (utama)
- `_sar1.py`…`_sar4.py`, `_struct.py` — bedah struktur ramdisk/system
- `_hookcheck.py` — banding ramdisk ORIG vs PATCHED vs Sept-7
- `_sysrc.py` — cari `init.rc` sistem dalam `system.img`
- `_scan2.py` — scan penghantar `UPDATE_BROADCAST` (muktamad)
- `_ramdisk.py`, `_ramdiff.py` — parse/diff ramdisk
- `_otacert_cmp.py` — banding otacert
- `verify_candidate.py` — verifikasi pakej akhir
- `_logs/m1/`, `_timeline.txt` — log terurai

---

## 9. KESIMPULAN

Teknikal flash **betul** (pakej & tandatangan sah, flash berjaya). **Strategi suntikan salah** untuk Android 10 SAR — suntingan ramdisk tak boleh mengubah second-stage init yang datang dari partition `system`.

Cadangan langkah seterusnya: cuba **Pilihan A** dahulu (murah, tutup kes), dan jika gagal, buat **Pilihan B** dengan betul (hashtree + sign semula `vbmeta_system`).
