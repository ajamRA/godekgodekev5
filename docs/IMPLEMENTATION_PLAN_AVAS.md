# IMPLEMENTATION PLAN — AVAS Sound Control via VHAL (IHU801P / Proton e.MAS)

> **Status:** DRAFT — belum execute. Tunggu kelulusan owner.
> **Disediakan:** 2026-09-22, berdasarkan forensik live device + reverse-engineering APK.
> **Sasaran:** Tukar bunyi AVAS kereta TANPA install apa-apa app, TANPA write ke /vendor.

---

## 1. LATAR BELAKANG (context untuk AI seterusnya)

### 1.1 Device
- Head unit: **IHU801P** (ECARX/Geely platform, MediaTek emc21b, 8-core, Android 10 SDK 29)
- Kereta: Proton e.MAS (Proton = anak syarikat Geely; platform sama dengan Geely EX2/IHU629G)
- Build: `user` **test-keys**, `ro.debuggable=1`, `ro.secure=0`
- **ADB WiFi:** `192.168.0.101:5555`, adbd runs as **root** (uid=0)
- WiFi kereta agak lemah (ping ~42ms, kadang drop) — reconnect sebelum command penting

### 1.2 Sistem AVAS (telah diverify live)
- **HAL service:** `vendor.molead.hardware.avas_service@1.0-service` (Molead, root, init auto-respawn bila di-kill)
- **Fail bunyi:** `/vendor/etc/avas/sound_type_{1,2,3}/` — satu folder per "tune"
  - Mode 1 = `yinheyinfu.wav` (银河音符 / "galaxy notes" = Clássico)
  - Mode 2 = `space.wav` (Caminhada Espacial)
  - Mode 3 = `pixel.wav` (Tom Galático) ← **mode aktif kereta ini** (`persist.avas.mode=3`)
  - Setiap folder ada `bDM_01_01.wav` (bunyi reverse gear, dikongsi semua mode) + `tuning.txt` (XOR 0x70 encrypted: pitch/volume ikut speed)
- **Config:** `/vendor/etc/avas/Config_Android.txt` (XOR 0x70) — device `hw:0,29`, 48kHz mono 16-bit
- **Pemilihan mode:** prop `persist.avas.mode` (0/1/2/3) — semak juga `persist.avas.switch`

### 1.3 Insiden / state semasa (PENTING — baca sebelum buat apa-apa)
- `/vendor` partition **100% PENUH** — `df` kata ~6MB free TAPI write 512KB pun GAGAL ("No space left on device"). **JANGAN cuba cp/push fail ke /vendor.**
- **KESALAHAN TELAH BERLAKU:** percubaan `cp custom_v8.wav → pixel.wav` truncate fail asal jadi **0 bytes**. `pixel.wav` dalam device sekarang = 0 bytes (rosak).
- AVAS service masih hidup dengan FD lama — bunyi kemungkinan masih jalan **sampai service restart**. Selepas reboot, mode 3 boleh jadi SENYAP (illegal UN R138 + bahaya).
- **Mode 1 & 2 fail masih INTACT** — ini jalan keluar cepat.
- `yinheyinfu.wav` (mode1) & `space.wav` (mode2) dah dipindahkan ke `/data/local/avas/factory_backup/` sebagai backup (asal dari /vendor, dipindah untuk free space).

### 1.4 Sumber fakta
- Reverse-engineering APK `GeelyAutoSettings-AVAS-WiFi.apk` (`com.flyme.auto.settings`, komuniti Geely EX2 Brazil/`geekitbr`) menunjukkan kawalan AVAS rasmi dilakukan melalui **VHAL properties**:
  - `IVehicle.SETTING_FUNC_AVAS_SOUND_SWITCH` — mute/unmute
  - `IVehicle.SETTING_FUNC_AVAS_SOUND_TYPE` — pilih tune (enum `AVAS_SOUND_TYPE_1/2/3/NONE`)
  - `IVehicle.ACOUSTIC_EFFECT_MODE_OFF` — mode off
  - (`IVehicle` = `android.hardware.automotive.vehicle` HAL interface)
- Repo rujukan: `D:\apps\emas-ota\drive_assist_repo\` (drive_assist/DriveMem, untuk Geely EX2) — docs/field-catalog ada nota AVAS; `plan/archive/implemented/AVAS-MUTE-ROADMAP.md` dokumentasikan `CarAudioManager.setAVASMode(0/1)` (perlukan platform-signed app; app biasa kena SecurityException `CAR_CONTROL_AUDIO_VOLUME`).

---

## 2. FAZA 0 — EMERGENCY STABILIZATION (buat dulu, paling awal)

**Matlamat:** Pastikan AVAS TIDAK senyap walaupun service restart. Zero write ke /vendor.

```bash
D=192.168.0.101:5555
adb connect $D

# 2.1 Sahkan keadaan semasa
adb -s $D shell "getprop persist.avas.mode persist.avas.switch"
adb -s $D shell "ls -la /vendor/etc/avas/sound_type_3/"

# 2.2 Tukar mode ke 1 (bunyi sihat) — property write, reversible 100%
adb -s $D shell "setprop persist.avas.mode 1"

# 2.3 Restart service supaya baca mode/fail baru
adb -s $D shell "kill $(pidof vendor.molead.hardware.avas_service@1.0-service)"
# init auto-respawn dalam 1-2 saat

# 2.4 Verify: service hidup, FD baru ke tuning mode 1
sleep 3
adb -s $D shell "ps -A | grep avas"
adb -s $D shell "ls -l /proc/$(pidof vendor.molead.hardware.avas_service@1.0-service)/fd | grep avas"

# 2.5 Ujian fizikal: gerakkan kereta <20 km/h, pastikan ada bunyi AVAS keluar.
```

**Kriteria lulus:** service hidup semula, FD tunjuk tuning.txt mode 1, bunyi keluar bila bergerak.
**Kalau gagal (bunyi tak keluar):** `setprop persist.avas.mode 2` + kill service semula (mode 2 fail juga intact).

---

## 3. FAZA 1 — DISCOVERY ✅ SELESAI (buat kat laptop, tanpa kereta!)

**HASIL — tak perlu buat live discovery, semua dah jumpa dari PC:**

### 3.1 Cara official (dari decompile Settings app asal kereta ini, OTA vendor)
`ecarx.settings.receiver.AvasSetReceiver` (broadcast receiver dalam Settings app):
```java
IAvas service = vendor.molead.hardware.avas_service.V1_0.IAvas.getService(false);
// i == 0 (mute):    service.setSwitch(false);
// i == 1/2/3:       service.setSwitch(true); service.setMode(i);
```
Tune mapping: **1 = galaxy** (sound_type_1/yinheyinfu.wav), **2 = space** (sound_type_2/space.wav), **3 = pixel** (sound_type_3/pixel.wav) — disahkan dari `MediaAvasUtils.playAvas()` + binary HAL strings.

### 3.2 Cara trigger dari adb (Faza 2 nanti):
```bash
# Cara A — broadcast official (Settings app yang panggil HIDL):
#   Action = "ecarx.settings.receiver.AvasSetReceiver" (dari AndroidManifest Settings, line 399)
#   Extra int = "AVAS_SET_VALUE" (CarSettingService.java line 107), nilai: 0=mute, 1=galaxy, 2=space, 3=pixel
adb -s $D shell "am broadcast -a ecarx.settings.receiver.AvasSetReceiver --ei AVAS_SET_VALUE 1"

# Cara B — sysprop terus (HAL service baca prop ini — disahkan dalam binary HAL):
adb -s $D shell "setprop persist.avas.mode 1; setprop persist.avas.switch 1"
adb -s $D shell "kill $(pidof vendor.molead.hardware.avas_service@1.0-service)"

# HAL juga subscribe VHAL utk gear+speed (INFO_ID_VDRIVEINFO_GEAR_POSITION, "Get Speed %dkm/h")
# — itu untuk playback timing, BUKAN untuk control. Control = sysprop/HIDL sahaja.
```

### 3.3 Backup bonus yang disahkan
Settings APK ada PREVIEW copies bunyi (`res/raw/sound_pixel.wav` 289KB — versi pendek utk preview UI, BUKAN fail HAL 581KB). Factory HAL file yang sah tetap yang kita extract dari vendor.img OTA: **581,788 bytes, verified**.

---

## 4. FAZA 2 — TEST TUKAR MODE VIA VHAL (reversible, tanpa filesystem)

**Prasyarat:** Faza 0 lulus (AVAS berbunyi dengan mode 1/2), Faza 1 ada property ID.

```bash
# 4.1 Baca current value (contoh; adapt ikut tool yang available)
# Cara A: cmd car_service (kalau build support)
adb -s $D shell "cmd car_service get-car-property <PROP_ID>"

# Cara B: tulis kecil via app 'service' call / binder — paling praktikal untuk build user+root:
# guna 'am' broadcast ke Settings app asal, ATAU
# cara C: setprop persist.avas.mode X + kill service (cara Faza 0 — DAH TERBUKTI path ini yang service baca)
```

> **Nota realiti:** Mungkin `persist.avas.mode` (sysprop) SAHAJA yang service baca, dan VHAL property tu cuma untuk UI sync. Faza 2 sebenarnya: verify hubungan VHAL ↔ sysprop. Kalau setprop je dah cukup (Faza 0 akan tunjuk), VHAL discovery jadi optional/informatif.

**Kriteria lulus:** bunyi berubah antara tune 1 ↔ 2 ↔ 3 dengan kill-service, dan kekal selepas `setprop` tanpa perlu write filesystem.

---

## 5. FAZA 3 — KEKALAN (PERSISTENCE) SELEPAS REBOOT

**Masalah:** `setprop persist.*` kekal across reboot, TAPI kalau mode 3 (pixel.wav rosak) pernah dipilih semula oleh sistem/UI, AVAS senyap. Dan kalau kita nak custom V8, perlu bind mount semula tiap boot.

**Pilihan persistence (pilih SATU):**

### Pilihan 3A — init script rc (paling bersih, guna root)
Buat `/data/local/avas/avas_fix.sh`:
```sh
#!/system/bin/sh
# Guna mode 1 (bunyi sihat) + optional bind mount custom sound
setprop persist.avas.mode 1
# Untuk custom V8 nanti (uncomment bila sedia):
# while [ "$(pidof vendor.molead.hardware.avas_service@1.0-service)" = "" ]; do sleep 1; done
# mount -o bind /data/local/avas/custom_v8.wav /vendor/etc/avas/sound_type_3/pixel.wav
# setprop persist.avas.mode 3
# kill $(pidof vendor.molead.hardware.avas_service@1.0-service)
```
Daftar via init rc — bila root, boleh inject rc sendiri ATAU guna `magisk` style service (tiada magisk — guna cara di bawah).

Cara inject tanpa magisk: edit `/vendor/etc/init/` (ada space untuk fail kecik ~200 bytes — SAHAJA untuk rc, bukan wav!):
```bash
adb -s $D shell "cat > /vendor/etc/init/avas_fix.rc << 'EOF'
on property:sys.boot_completed=1
    exec_background root root -- /system/bin/sh /data/local/avas/avas_fix.sh
EOF"
```
⚠️ Uji dulu di Faza 3 sebelum bergantung; kalau SELinux block exec dari /data, ubah: copy script ke /vendor/bin/ (kecik, muat) dan rujuk sana.

### Pilihan 3B — app kecil sendiri (kalau nak UI)
App platform-signed sendiri (kita ada test-keys platform? SEMAK dulu) ATAU guna trick slot `_ACO` macam kedai buat. **Ni pilihan mahal — buat kemudian.**

### Pilihan 3C — manual
Setiap kali reboot & nak tukar, run 3 arahan. Zero risk, zero persistence complexity.

---

## 6. FAZA 4 — CUSTOM V8 SOUND (matlamat asal owner)

**Prasyarat:** Faza 0-3 stabil, AVAS sentiasa berbunyi.

```bash
# 6.1 Fail V8 dah ada dalam device: /data/local/avas/custom_v8.wav (2,287,152B)
#     Format verify: RIFF 48kHz mono 16-bit ✓ (dah diverify, match spec service)

# 6.2 Bind mount V8 ke path mode 3
adb -s $D shell "mount -o bind /data/local/avas/custom_v8.wav /vendor/etc/avas/sound_type_3/pixel.wav"

# 6.3 Verify bind aktif: ls harus tunjuk 2,287,152 bytes pada pixel.wav
adb -s $D shell "ls -la /vendor/etc/avas/sound_type_3/pixel.wav"

# 6.4 Restart service supaya buka fail baru
adb -s $D shell "kill $(pidof vendor.molead.hardware.avas_service@1.0-service)"

# 6.5 Set mode 3 & test drive
adb -s $D shell "setprop persist.avas.mode 3"
# Test: gerakkan kereta, bunyi V8 patut keluar
```

**Rollback:**
```bash
adb -s $D shell "umount /vendor/etc/avas/sound_type_3/pixel.wav"
adb -s $D shell "kill $(pidof vendor.molead.hardware.avas_service@1.0-service)"
# → balik ke bunyi mode 1/2 sihat, atau restore pixel factory via bind mount
#   dari /data/local/avas/pixel_factory.wav (581,788B — PULL DAHULU dari PC, sedia di
#   D:\apps\emas-ota\full_backup_car\FACTORY_ORIGINAL_pixel_581788.wav)
```

**Tuning warna bunyi:** kalau V8 terlalu lantang/cikis, edit `tuning.txt` (XOR 0x70) dalam sound_type_3 — tapi tuning.txt ASAL tu pun 0-kosong? TIDAK — tuning.txt intact (343B). Edit versi XOR-decoded, re-encode, bind mount fail baru ke atasnya juga boleh.

---

## 7. PROTOKOL KESELAMATAN (WAJIB ikut semua faza)

1. **PULL BEFORE WRITE** — sebelumubah apa-apa fail, salin ke PC dulu. Insiden pixel.wav adalah pengajaran.
2. **JANGAN write apa-apa ke /vendor** — kecuali fail teks kecik <1KB (Faza 3A rc) dengan verify space dulu: `dd if=/dev/zero of=/vendor/.t bs=1024 count=4` test, `rm /vendor/.t`.
3. **Selalu backup ke /data/local/avas/factory_backup/** sebelum pindah/buang.
4. **AVAS mesti sentiasa berbunyi** selepas setiap langkah. Kalau ragu — test drive <20 km/h.
5. **JANGAN guna silent.wav** — AVAS senyap = illegal (UN R138) + bahaya pejalan kaki.
6. **Reversibility check** — setiap faza ada rollback yang ditulis. Kalau rollback tak jelas, jangan execute.
7. **Kill service = safe** (init respawn) tapi JANGAN kill masa kereta bergerak — buat masa parked.
8. Semua operasi **masa kereta parked**, bukan masa driving.

---

## 8. STATE RINGKAS (untuk rujukan pantas)

| Item | Nilai | Lokasi |
|---|---|---|
| pixel.wav (mode 3) | **0 bytes ROSAK** | /vendor/etc/avas/sound_type_3/ |
| factory pixel.wav | 581,788B verified | PC: `D:\apps\emas-ota\full_backup_car\FACTORY_ORIGINAL_pixel_581788.wav` |
| custom_v8.wav | 2,287,152B md5 403cbc... | device: `/data/local/avas/custom_v8.wav` |
| v8_clean.wav | 2,287,118B | device: `/data/local/avas/v8_clean.wav` |
| silent.wav | 288,044B (JANGAN GUNA) | device: `/data/local/avas/silent.wav` |
| yinheyinfu.wav (mode1) | INTACT dalam vendor; backup di /data/local/avas/factory_backup/ | |
| space.wav (mode2) | INTACT dalam vendor; backup di /data/local/avas/factory_backup/ | |
| bDM_01_01.wav ×3 | INTACT — JANGAN SENTUH (reverse gear) | /vendor/etc/avas/sound_type_*/ |
| Mode aktif | persist.avas.mode=3 (pixel rosak) | |
| /vendor free space | ~6MB df TAPI write FAIL — anggap zero | |
| APK bedah | com.flyme.auto.settings | PC: `D:\apps\emas-ota\bedah apk\avas_settings.apk` |
| OTA vendor.img | sudah extract | PC: `D:\apps\emas-ota\vendor_extract\vendor.img` |
| Handoff notes | — | `D:\apps\emas-ota\HANDOFF_AVAS_NOTES.md` |

---

## 9. URUTAN YANG DILULUSKAN UNTUK EXECUTE (bila owner kata GO)

1. **Faza 0** (stabilize ke mode 1) — 5 minit
2. **Faza 1** (discovery, separuh di PC) — 20 minit
3. **Faza 2** (verify mode switch) — 10 minit
4. **Faza 3** (persistence pilihan owner) — 15-30 minit
5. **Faza 4** (V8 bind mount) — 10 minit
6. Test drive final + rollback plan sedia.

Setiap faza: laporan sebelum sambung. Owner boleh stop bila-bila.
