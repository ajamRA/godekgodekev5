# SESSION STATE — 24 Sep 2026 (Live Verified)

Catatan status terkini bagi keseluruhan sistem Proton e.MAS 7 / Geely Galaxy E5 (IHU801P).

---

## 🚗 MAKLUMAT PERANTI & RANGKAIAN
* **Peranti:** IHU801P (MediaTek Octa-core ARM64, Android 10 API 29, SELinux Enforcing).
* **Sambungan Semasa:** `192.168.1.18:5555` (WiFi ADB root aktif).
* **Akses Root:** Aktif secara kekal melalui `persist.service.adb.tcp.port=5555` dalam `/system/etc/init/zzz_adbtcp.rc`.
* **Skrip Imbasan:** `scan_adb.ps1` untuk mencari alamat IP IHU sekiranya bertukar mengikut DHCP router.

---

## ✅ KOMPONEN SELESAI & TELAH DIVERIFIKASI SECARA LIVE

### 1. Aplikasi Kokpit `e.MAS Energy` (com.noor.thenoorcar)
* **Status:** Berjaya dipasang dan disahkan berjalan lancar pada skrin kereta.
* **Tangkapan Skrin Live:** `screen_ui_live2.png` (Bateri: 85.8%, DTE: 280 km, Kuasa: +1.17 kW, A/C: 1070 W, 12V: 100 W).
* **Penyelesaian Telemetri:**
  - Membetulkan masalah JSON parsing bug (`audio_w`) dalam daemon yang sebelum ini membekukan paparan WebView.
  - Memperbetulkan balutan struktur JSON agar nilai `getEnergyJson()` terus dibaca oleh `index.html`.
  - Menggantikan sensor `VCU_DrvPwrAct` dengan pengiraan fizik sebenar $P = V \times I$ (`BMSH_HVBusVolt` 678429461 * `BMSH_BattCurr` 678429104).
  - Semasa memandu (*Drive*), kuasa motor bertukar menjadi positif (**`+kW`**).
  - Semasa membrek (*Regen*), arus menjadi negatif dan dashboard memaparkan kuasa regeneratif (**`-n kW`**) berlampu hijau zamrud secara automatik.
  - Pendingin hawa (*HVAC*) membaca kompresor + blower secara dinamik mengikut mod A/C (`VCU_ThermalPwrAct` 678429413).
  - Butang penutup aplikasi (`closeApp()`) berfungsi pantas memanggil `finish()`.
* **Fail Binaan:** `NoorEnergy_clean.apk` (33 KB) di `d:\apps\emas-ota\NoorEnergy_clean.apk`.

### 2. Ejen Latar Belakang `DoorPressureReliefDaemon`
* **Lokasi Kereta:** `/data/local/window/door_pressure_relief.jar` (berjalan sebagai root background daemon).
* **Ciri-Ciri Aktif:**
  - **Pelepas Tekanan Pintu:** Tingkap turun 10-15% bila mana-mana pintu dibuka, dan naik semula bila semua pintu ditutup rapat.
  - **Mod Khemah (Camping Mode):** Menghalang sistem kuasa daripada tertutup (*keep-awake*), menyediakan butang *Skrin Tidur* (skrin malap hitam pekat), butang *Retak Tingkap 5%*, dan pengiraan ketahanan bateri (jam).
  - **Penukar Nada Kunci (Lock Sound Switcher):** Ejen menyemak `/api/car/sync` ke VPS setiap 2 saat. Mengemas kini fail `/sdcard/V3_Vehicle_Locked_Single.wav`, mengunci dengan `chmod 444`, dan memulakan semula `ecarx_reverse_radar`. Ujian penukaran ke lagu *Tamatlah Sudah* dan *Quack* disahkan berjaya dalam 3 saat.
  - **Pelayan Telemetri Tempatan:** Port `8888` menyediakan data JSON tenaga kepada aplikasi `e.MAS Energy`.

### 3. Modifikasi AVAS V8 (Acoustic Vehicle Alerting System)
* **Slot 3:** Dipetakan ke fail enjin V8 (`/vendor/etc/avas/sound_type_3/pixel.wav`).
* **Lengkung Kelantangan (Rumble):** `tuning3_rumble.txt` telah disahkan dengan semakan integriti CRC16-CCITT dan penyulitan XOR 0x70.
* **Persistensi Boot:** Diuruskan oleh `/system/etc/init/zzz_avas_boot.rc` dan `/system/bin/avas_boot.sh`.

### 4. Pembersihan App Drawer
* **DVRProbe:** Telah dinyahaktifkan dengan arahan `adb shell pm disable com.emas.dvrprobe` bagi memastikan hanya ikon rasmi kemas terpapar di laci aplikasi.

---

## 🔒 POLISI PENSTORAN & REPOSITORI
* **GitHub:** **TIDAK DITOLAK (DO NOT PUSH TO GITHUB).**
* Semua kod sumber dan pengubahsuaian disimpan secara setempat (*offline local repository*) di `D:\apps\emas-ota` dan `D:\apps\emas5`.
* Penyelarasan awan persendirian hanya berlaku antara kereta dan VPS peribadi di `emas.43-163-3-153.nip.io`.

---

## 📦 PANDUAN KERETA KAWAN (UNIVERSAL PLUG & PLAY)
* **Pakej USB Awal:** Menggunakan fail kemas kini kilang penuh **`update.zip` (1.97 GB)** pada pemacu USB FAT32.
* **Semakan VIN:** **Tidak perlu sebarang perubahan fail.** Sistem IHU801P / SX11RA adalah 100% universal merentas semua kenderaan Proton e.MAS 7.
* **Pilihan Pemasangan:**
  - *Pilihan A:* Pakej Penuh (Netflix, SmartTube, e.MAS Energy, Mod Khemah, Nada Kunci, AVAS V8).
  - *Pilihan B:* Minimalist (Hanya e.MAS Energy + Root ADB).
