# Panduan Lengkap Modifikasi & Eksploitasi Geely EX2 (EMC / IHU Series)

Panduan teknikal komprehensif merangkumi proses pembongkaran sistem, perolehan akses root, pengaktifan Wi-Fi ADB kekal, pintasan sekatan launcher (whitelist bypass) untuk pemasangan aplikasi Netflix & SmartTube, serta pengubahsuaian bunyi luaran AVAS.

---

## 1. Spesifikasi Perkakasan & Sistem (Hardware & System Specs)

* **Kenderaan:** Geely EX2 (Geometry Platform Architecture)
* **Unit Infotainment (IHU):** EX2-HU / EMC21B Automotive Head Unit
* **Pemproses (SoC):** MediaTek MT2712 / MT8666 Automotive ARM64
* **Sistem Operasi:** Android 10 (API level 29), Automotif AOSP fork (ECARX / Molead)
* **Sistem Partisi:** A/B Dynamic Partitions (Super partition: `system_a/b`, `vendor_a/b`, `product_a/b`)
* **SELinux:** Enforcing (`u:r:init:s0`, `u:r:system_server:s0`, `u:r:top_log:s0`)

---

## 2. Struktur Root & Wi-Fi TCP ADB Kekal (Persistent Root ADB)

Bagi membolehkan capaian terminal tanpa kabel dan akses penuh `root`, satu init script telah disuntik terus ke dalam `/system/etc/init/zzz_adbtcp.rc`:

```rc
# Persistent Wi-Fi TCP ADB auto-start
on early-init
    setprop persist.adb.tcp.port 5555
    setprop persist.service.adb.tcp.port 5555
    setprop service.adb.tcp.port 5555

on post-fs-data
    setprop persist.adb.tcp.port 5555
    setprop persist.service.adb.tcp.port 5555
    setprop service.adb.tcp.port 5555

on property:sys.boot_completed=1
    setprop persist.adb.tcp.port 5555
    setprop persist.service.adb.tcp.port 5555
    setprop service.adb.tcp.port 5555
    start adbd
    exec -- /system/bin/settings put global adb_enabled 1

on property:init.svc.adbd=stopped
    setprop service.adb.tcp.port 5555
    start adbd
```

* Port ADB: `5555`
* Cara sambung dari PC melalui Wi-Fi:
  ```bash
  adb connect <IP_KERETA>:5555
  adb root
  adb shell
  ```

---

## 3. Rahsia & Mekanisme Whitelist Bypass Launcher Geely

### Masalah Sekatan Asal
Launcher kilang OEM Geely (`Launcher3 / ecarx.launcher`) mengandungi *hardcoded whitelist package*. Jika aplikasi biasa seperti Netflix dipasang melalui `pm install` ke dalam `/data/app/`, launcher akan menyembunyikan ikon aplikasi tersebut sepenuhnya daripada skrin dan app drawer.

### Jalan Penyelesaian: Kuda Trojan (Bind Mount Over System Apps)
Sistem OEM membenarkan aplikasi sistem rasmi tertentu terpapar secara lalai:
1. **JOOX Music** (`/system/app/JOOXMusic_ACO/`)
2. **TikTok In-Car** (`/system/app/ttincar_tiktok_ACO/`)

Dengan menindih (*bind-mount*) direktori ini menggunakan aplikasi luar yang serasi dengan seni bina Android TV / Android Automotive:
* **Netflix** dipetakan ke atas `/system/app/JOOXMusic_ACO`
* **SmartTube** (YouTube ad-free) dipetakan ke atas `/system/app/ttincar_tiktok_ACO`

PackageManagerService (PMS) akan mengimbas manifest aplikasi baru dan mendaftarkannya secara rasmi dengan nama dan ikon asli (*native*) pada laci aplikasi (App Drawer Page 2).

---

## 4. Pelaksanaan Autostart Kekal (Boot Persistence)

Oleh kerana perintah `mount -o bind` biasa di dalam kernel Linux akan hilang setiap kali kereta dimatikan (*cold reboot*), integrasi kekal dibuat menggunakan dua komponen:

### A. Skrip Pelaksana: `/system/bin/ex2_mount.sh`
```bash
#!/system/bin/sh
# Fix SELinux contexts
chcon -R u:object_r:system_file:s0 /data/local/custom_apps

# Mount SmartTube over TikTok
if [ -d /data/local/custom_apps/SmartTube ]; then
    mount -o bind /data/local/custom_apps/SmartTube /system/app/ttincar_tiktok_ACO
fi

# Mount Netflix over JOOX
if [ -d /data/local/custom_apps/Netflix ]; then
    mount -o bind /data/local/custom_apps/Netflix /system/app/JOOXMusic_ACO
fi
```
* Kebenaran: `chmod 755 /system/bin/ex2_mount.sh`
* Konteks SELinux: `chcon u:object_r:system_file:s0 /system/bin/ex2_mount.sh`

### B. Init Trigger: `/system/etc/init/zzz_custom_apps.rc`
```rc
# Persistent custom apps auto-mount (Netflix & SmartTube)
on post-fs-data
    exec -- /system/bin/sh /system/bin/ex2_mount.sh

on property:sys.boot_completed=1
    exec -- /system/bin/sh /system/bin/ex2_mount.sh
```
* Trigger dijalankan pada fasa **`post-fs-data`** (sebelum Zygote dan PackageManager memulakan imbasan), memastikan aplikasi dikesan secara automatik tanpa perlu restart framework.

---

## 5. Ringkasan Pengubahsuaian AVAS (Acoustic Vehicle Alerting System)

Sistem amaran pejalan kaki Geely EX2 dikawal oleh daemon perkakasan `/vendor/bin/hw/vendor.molead.hardware.avas_service@1.0-service` bersama konfigurasi di `/vendor/etc/avas/`:
* **Slot 1 (Galactic Note):** `sound_type_1/yinheyinfu.wav` (Factory sound)
* **Slot 2 (Space Walk):** `sound_type_2/space.wav` (Factory sound)
* **Slot 3 (Pixel Technology):** `sound_type_3/pixel.wav` (Custom engine / V8 sound)
* **Settings Preview:** Disimpan dalam `res/raw/sound_pixel.wav` di dalam `Settings_MOLEAD.apk`.
* **Konteks SELinux Wajib Vendor:** Fail audio di bawah `/vendor/etc/avas/` wajib dilabel `u:object_r:vendor_configs_file:s0` supaya `hal_avas_default` tidak menerima ralat *Permission Denied*.

---

## 6. Struktur Direktori Projek (Repository Layout)

```
D:\apps\ex2-ota/
├── PANDUAN_MODIFIKASI_GEELY_EX2.md   # Panduan teknikal ini
├── README.md                            # Penerangan projek
├── workspace_injection/                 # Skrip pembina payload & patcher OTA
│   ├── patch_system.py                  # Skrip suntikan system.img
│   ├── package_and_sign_full_ota.py     # Pakej & tandatangan RSA payload OTA
│   ├── ota_signer.py                    # Enjin cryptographic signing
│   └── deploy_to_usb.ps1                # Skrip pemindahan ke pemacu USB
├── full_backup_car/                     # [OFFLINE BACKUP] 32 partition penuh eMMC kereta
└── ota_stock.zip                        # [OFFLINE ARCHIVE] Arkib asal OTA kilang
```
