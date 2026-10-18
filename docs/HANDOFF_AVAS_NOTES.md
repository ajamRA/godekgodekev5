# HANDOFF NOTES — AVAS IHU801P (Proton e.MAS, ECARX platform)
# Untuk AI/engineer seterusnya. Semua fakta di bawah TELAH DIVERIFY secara live via ADB.

## DEVICE
- Head unit: IHU801P (ECARX, MediaTek emc21b, 8-core), Android 10 (SDK 29), build user + **test-keys**
- ADB WiFi: 192.168.0.101:5555, **adbd runs as root** (uid=0, ro.secure=0, ro.debuggable=1)
- /vendor = /dev/block/dm-5 (slot vendor_a, A/B device), ext4, boleh remount rw, dm-verity TAK enforce

## SISTEM AVAS
- Service HAL: `vendor.molead.hardware.avas_service@1.0-service` (vendor Molead, user root, init auto-respawn)
- Config: `/vendor/etc/avas/Config_Android.txt` — **encrypted XOR 0x70** (decode: Module=AVAS, OEM=Proton, Tier1=Molead, 48kHz mono 16-bit, device hw:0,29)
- Mode dipilih oleh prop **`persist.avas.mode=3`** → guna folder `/vendor/etc/avas/sound_type_3/`
- Bunyi utama mode 3: **pixel.wav** (581,788 bytes asal) + bDM_01_01.wav (reverse gear, shared semua mode)
- tuning.txt dalam setiap folder juga XOR 0x70 (pitch/volume curves ikut speed)
- Service pegang FD ke pixel.wav sejak start → tukar fail TANPA restart service = takkan effect

## ⚠️ MASALAH UTAMA: /vendor PENUH — TAK BOLEH REPLACE .WAV
- `/vendor`: 937,092 KB total, **100% penuh**, free ~3.4MB (kemudian 5.9MB selepas pindahkan 2 fail)
- **TAPI: write GAGAL walaupun df kata ada free space** — dd test 512KB pun FAIL "No space left on device"
- Kesimpulan: free space tu tak boleh guna (reserved blocks / fragmentation / dm issue). JANGAN cuba `cp` wav besar ke /vendor — fail akan truncate ke 0 bytes macam yang dah berlaku
- KESILAPAN yang dah berlaku: cp custom_v8.wav → pixel.wav gagal separuh jalan, pixel.wav sekarang **0 bytes**. .bak pon 0 bytes.

## STATE SEMASA DEVICE (selepas insiden)
- /vendor/etc/avas/sound_type_3/pixel.wav = **0 bytes (ROSAK)**
- AVAS service masih running, pegang FD lama → bunyi kemungkinan masih jalan sementara
- yinheyinfu.wav (mode1) & space.wav (mode2) dah dipindah ke /data/local/avas/factory_backup/ (untuk free space, ada backup)
- bDM_01_01.wav x3 (reverse gear) — INTACT, jangan sentuh
- /data/local/avas/ ada: custom_v8.wav (2,287,152B, md5 403cbc396e758260bb7e2fa21826355a), v8_clean.wav, silent.wav, init_avas.sh

## RECOVERY — FACTORY pixel.wav DAH JUMPA & VERIFIED
- Sumber: `D:\apps\emas-ota\ota_emas5.zip` (OTA penuh) → extract `vendor.img` (payload-dumper-go) → 7z extract `etc/avas/sound_type_3/pixel.wav`
- **581,788 bytes — EXACT size match, header byte-for-byte match** dengan asal (RIFF 48kHz mono 16-bit)
- Backup PC: `D:\apps\emas-ota\full_backup_car\FACTORY_ORIGINAL_pixel_581788.wav` (md5 3f796c65355763b8ce16a85ccdae2f34) + salinan di `D:\apps\emas-ota\pixel_factory_original.wav`

## ✅ CARA BETUL TUKAR BUNYI (sebab /vendor penuh) — BIND MOUNT
JANGAN cp ke /vendor. Guna bind mount dari /data (tak perlukan space /vendor langsung):
```
mount -o bind /data/local/avas/custom_v8.wav /vendor/etc/avas/sound_type_3/pixel.wav
kill $(pidof vendor.molead.hardware.avas_service@1.0-service)   # init akan respawn
# service baru buka pixel.wav → dapat file bind-mounted → bunyi baru
```
- Ini sebabnya kedai buat `init_avas.sh` (bind mount) — tapi script tu TAK PERNAH auto-run masa boot (tiada rc entry), sebab tu attempt semalam gagal
- Untuk kekal selepas reboot: kena letak script dalam init rc (boleh, sebab root) ATAU jalankan manual selepas tiap reboot
- Untuk restore factory: umount, kill service semula, pastu fix pixel.wav — tapi fix 0-byte file perlukan space... GUNA bind mount juga untuk "restore": bind factory wav dari /data ke pixel.wav
- PERINGATAN: silent.wav = padamkan AVAS terus — illegal (UN R138) & bahaya. Jangan.

## MAKLUMAT TAMBAHAN YANG MUNGKIN BERGUNA
- Launcher pakai allowlist — app luar dihide. Trick kedai: timpa APK dalam slot system `/system/app/*_ACO/` (contoh: JOOXMusic_ACO = Netflix sebenar, ttincar_tiktok_ACO = SmartTube)
- /system pon boleh remount rw (test-keys)
- Dump XML yang di-encode: `python -c "print(open('f','rb').read().decode('latin1'))"` lepas XOR 0x70 setiap byte
- OTA tools sedia ada: payload-dumper-go.exe, 7z (boleh baca ext4 img terus)
