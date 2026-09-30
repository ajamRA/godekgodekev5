# Dokumentasi Lengkap Sistem & Modifikasi Proton e.MAS 5 / Geely Galaxy E5

> [!CAUTION]
> **DOKUMEN ARKIB SEJARAH (HISTORICAL REFERENCE ONLY)**
> Dokumen ini adalah catatan sejarah eksperimen awal platform e.MAS 5 / Geely E5 sebelum audit keselamatan 27 September 2026.
> **DILARANG DIGUNAKAN SEBAGAI PANDUAN OPERASI SEMASA PROTON e.MAS 5.**
> Untuk panduan dan SOP rasmi semasa e.MAS 5, rujuk [**`README.md`**](README.md), [**`ARAHAN_ASAS_EMAS5.md`**](ARAHAN_ASAS_EMAS5.md), dan [**`SOP_PEMASANGAN_APK.md`**](SOP_PEMASANGAN_APK.md).

---


## 1. Spesifikasi Perkakasan & Seni Bina Sistem

| Parameter | Butiran Teknikal |
| :--- | :--- |
| **Model Kenderaan** | Proton e.MAS 5 / Geely Galaxy E5 (Kod Platform: `SX11RA` / `EMC Series`) |
| **Unit Infotainment (IHU)** | IHU801P (ECARX / Molead Automotive Platform) |
| **Pemproses (SoC)** | MediaTek MT2712 / MT8666 Automotive Octa-Core ARM64 |
| **Sistem Operasi** | Android 10 (API level 29) dengan penyesuaian automotif Flyme Auto / ECARX |
| **Partisi Storan** | Skim partisi dinamik A/B (`super` mengandungi `system`, `vendor`, `product`) |
| **SELinux Mode** | Enforcing (`u:r:init:s0`, `u:r:system_server:s0`, `u:r:top_log:s0`) |
| **Rangkaian Kereta** | WiFi Hotspot / Client, Bluetooth 5.0, 4G T-Box (Telematics Box ACO Tech) |
| **Protokol Diagnostik** | Android Vehicle HAL (VHAL), EVS (Extended View System), Native CAN Bus daemons |

---

## 2. Prosedur Pembukaan Kunci (Root ADB Unlock)

### A. Fail Pakej USB (1.97 GB vs 16.7 MB)
* Fail yang digunakan untuk *initial unlock* melalui pemacu USB (pendrive) ialah **`update.zip` bersaiz 1.97 GB** (2,072,477,685 bait).
* **Mengapa 1.97 GB?** Walaupun kita hanya mengubah suai skrip boot dan konfigurasi adbd (~16.7 MB), servis kemas kini kilang kenderaan (`UpgradeService_MOLEAD`) melakukan semakan integriti struktur penuh OTA (`payload.bin` dan metadata tandatangan RSA kilang). Pakej penuh diperlukan supaya semakan kilang meluluskan pemasangan.

### B. Format Pemacu USB
* Gunakan pemacu kilat USB bersaiz minimum 4GB hingga 32GB diformat kepada **FAT32** (atau exFAT).
* Letakkan fail `update.zip` di punca (*root*) pemacu USB.

### C. Engineering Menu & Kalkulator Kata Laluan Dinamik
1. Buka skrin IHU: **Settings (Tetapan) ➡️ System (Sistem) ➡️ About (Tentang)**.
2. Tekan bertalu-talu pada **WLAN MAC Address** sehingga kotak dialog kod laluan (password) dipaparkan.
3. Salin nombor **IHUID / Serial No** yang terpapar.
4. Masukkan nombor siri tersebut ke dalam kalkulator rolling password (menjana kod 6-digit setiap selang masa 5 minit GMT+8):
   - Alatan Web: `https://ihucode.netlify.app`
5. Masukkan kod 6-digit untuk membuka menu jurutera kilang dan aktifkan USB Debugging.

### D. Akses Root ADB & Persistensi Boot
Sebaik sahaja pakej OTA terpasang, servis `adbd` dikonfigurasi untuk berjalan secara kekal dengan hak akses `root` melalui Wi-Fi:
* Port Wi-Fi ADB: **`5555`**
* Sambung dari komputer riba melalui rangkaian Wi-Fi yang sama:
  ```bash
  adb connect 192.168.1.x:5555
  adb root
  adb remount
  ```
* Skrip persistensi automatik disuntik ke `/system/etc/init/zzz_adbtcp.rc` untuk memastikan port 5555 dibuka semula pada setiap kali kereta dihidupkan (*cold boot*).

---

## 3. Pemasangan Pada Kereta Kawan: Adakah Perlu Mengubah Fail Ikut VIN?

### Jawapan: TIDAK PERLU SAMA SEKALI (100% Universal Plug-and-Play)
* **Pengesahan Perkakasan:** Pakej sistem dan OTA hanya mengesahkan pengenalan perkakasan (`IHU801P`), kod projek (`SX11RA`), dan kunci tandatangan pengeluar.
* **Peranan VIN:** Nombor VIN kenderaan hanya digunakan oleh pelayan awan rasmi ACO Tech / Proton untuk aplikasi telefon pintar (telematik jauh). Unit utama kereta tidak mengunci aplikasi dalaman, daemon, atau ubah suai audio kepada nombor VIN tertentu.
* **Kesimpulan:** Fail `update.zip` (1.97 GB), fail `door_pressure_relief.jar`, dan fail `NoorEnergy_clean.apk` boleh dipasang pada mana-mana Proton e.MAS 5 atau Geely Galaxy E5 lain secara terus tanpa mengubah walau satu baris kod pun.

---

## 4. Aplikasi e.MAS Energy & Penyahkodan CAN Bus

### A. Senibina Aplikasi
* **Pakej Sistem:** Dipasang di atas slot whitelist selamat `com.noor.thenoorcar` bagi memintas sekatan launcher kilang dan memaparkan ikon neon secara natif dalam App Drawer.
* **Antaramuka:** Web dashboard HTML5/CSS/JavaScript ultra-ringan dengan kemasan Cockpit Gelap EV Moden berprestasi tinggi.
* **Java Bridge:** Komunikasi dua hala melalui `Android.getLatestEnergy()` dan `AndroidEnergy` dengan sela masa segar semula **350ms**.

### B. Formula Fizik Bateri Sebenar (Penyelesaian Masalah 82% & Regen Negatif)
Sebelum ini, sistem membaca sifat generik Android VHAL yang tidak diselaraskan oleh pengeluar, menyebabkan bacaan tersangkut pada 82% dan kuasa traksi terkunci kepada 0 semasa membrek.

Sistem kini membaca daftar CAN Bus OEM yang sebenar daripada `EnergyCenter_MOLEAD`:

| Parameter | CAN Bus Property ID | Formula Penyahkodan | Julat / Unit |
| :--- | :--- | :--- | :--- |
| **BMS Battery SOC** | `678429103` = `0x287001AF` (`INFO_ID_BATTERY_REMAINING_CAPACITY`) | `uint16 / 10.0f` | **0.0% – 100.0%**. Bukan `0x2870014F` (itu media) |
| **HV Bus Voltage** | `678429461` = `0x28700315` (`INFO_ID_BMSH_BATTVOLT`) | `uint16 / 10.0f` | **~380V – 410V**. Bukan `0x287002B5` (itu radar) |
| **HV Battery Current** | `678429104` = `0x287001B0` (`INFO_ID_BATTERY_CURR`) | `(uint16 - 5000) * 0.1f` | **Ampere (A)**<br>Positif = Nyahcas<br>Negatif = Caj / Regen |
| **Kuasa Segera Sebenar** | Formula Fizik $P = V \times I$ | `(HVBusVolt * BattCurr) / 1000` | **Kilowatt (kW)**<br>Pecutan: **`+kW`** (Biru/Merah)<br>Regen: **`-n kW`** (Hijau Zamrud) |
| **Baki Jarak (DTE)** | `678429101` (`VCU_EVDTEodometer`) | `uint16` | **Kilometer (km)** |
| **HVAC Aircond Load** | `678429413` (`VCU_ThermalPwrAct`) | Byte 0 & 1 Little-Endian + Algoritma Suhu Kabin | **Watt (W)** (Contoh: 1070W = Kompresor 950W + Kipas 120W) |
| **12V Auxiliary Load** | `678429414` (`VCU_DCDCPwrAct`) | `raw * 100` (DC-DC Converter 13.8V / 14.5A) | **100W – 250W** |

---

## 5. Ejen Latar Belakang (DoorPressureReliefDaemon)

Ejen latar belakang Java berjalan di `/data/local/window/door_pressure_relief.jar` (diuruskan melalui `start_pressure_relief.sh`) dan menyediakan empat fungsi kritikal:

### A. Pelepas Tekanan Pintu (Pressure Relief)
* Membaca status pintu kereta melalui CAN bus:
  - Pintu Hadapan Kiri: `0x2870008F`
  - Pintu Hadapan Kanan: `0x28700090`
  - Pintu Belakang Kiri: `0x28700091`
  - Pintu Belakang Kanan: `0x28700092`
* Apabila mana-mana pintu dibuka, tingkap akan diturunkan sedikit (~10-15%) untuk melepaskan tekanan udara kabin supaya pintu mudah ditutup rapat dengan lembut tanpa perlu dihempas kuat.
* Apabila semua pintu ditutup, tingkap dinaikkan semula secara automatik.

### B. Mod Khemah Pintar (Camping Mode)
* **Kekal Hidup (*Keep-Awake*):** Menahan sistem kuasa IHU dan pendingin hawa daripada ditutup secara automatik apabila pemandu keluar dari kereta.
* **Skrin Tidur Malap (*Blackout Mode*):** Menggelapkan skrin IHU sepenuhnya menjadi hitam pekat pada waktu malam untuk keselesaan tidur di dalam kereta dan menjimatkan elektrik 12V. Sentuh mana-mana bahagian skrin untuk menghidupkan paparan semula.
* **Anggaran Ketahanan:** Mengira jam penggunaan berterusan secara automatik berasaskan baki kWh dan penggunaan HVAC semasa (cth: baki 52.5 kWh = ~131 jam pengudaraan sejuk).
* **Pengudaraan Tingkap 5%:** Membuka celahan tingkap 5% secara serentak untuk aliran udara segar.

### C. Penukar Nada Kunci Kustom (Custom Lock Sound Switcher)
1. Ejen berhubung dengan pelayan VPS (`/api/car/sync`) setiap 2 saat melalui sambungan keluar (*outbound-only*).
2. Apabila nada baru dipilih dari webapp:
   - Fail WAV dimuat turun ke `/data/local/window/locksounds/lock_active.wav`.
   - Fail disalin ke `/sdcard/V3_Vehicle_Locked_Single.wav`.
   - Ditetapkan kebenaran **`chmod 444`** bagi menghalang servis sistem `ecarx.settings` daripada menimpa semula fail dengan bunyi asal kilang saat boot.
   - Servis native `/system/bin/ecarx_reverse_radar` dimulakan semula untuk memuatkan nada kunci baharu serta-merta tanpa perlu reboot kereta.

### D. Enjin Telemetri Tempatan (Local HTTP Telemetry Server)
* Menyediakan endpoint HTTP setempat di `http://127.0.0.1:8888/api/energy` untuk disalurkan terus kepada aplikasi antaramuka `e.MAS Energy`.

---

## 6. Pengubahsuaian Bunyi Pejalan Kaki (AVAS V8 Sound Mod)

Sistem amaran pejalan kaki berkelajuan rendah (*Acoustic Vehicle Alerting System*) dikawal oleh daemon vendor `/vendor/bin/hw/vendor.molead.hardware.avas_service@1.0-service`:
* **Slot Pilihan 3 (Pixel Technology):** Dipetakan ke fail enjin V8 garang: `/data/local/avas/v8_loud.wav` (bind-mount ke `/vendor/etc/avas/sound_type_3/pixel.wav`).
* **Lengkung Kelantangan Kustom (Rumble Tuning):** Fail `tuning3_rumble.txt` dilaras kepada volum lebih tinggi (4, 6, 8, 10, 8, 4, 0) bagi membolehkan bunyi V8 didengari jelas pada kelajuan rendah 0–30 km/j.
* **Penyulitan & CRC:** Algoritma proprietari kilang telah berjaya di-reverse engineer (penyulitan XOR 0x70 dengan 4 pengecualian bait + semakan CRC16-CCITT jadual 0x6b50).
* **Persistensi Boot:** Diuruskan oleh `/system/etc/init/zzz_avas_boot.rc` dan `/system/bin/avas_boot.sh`.

---

## 7. Senibina Storan Tempatan & Pelayan VPS (Tanpa GitHub)

Selaras dengan polisi privasi dan sekuriti projek, kod dan dokumentasi **TIDAK DITERBITKAN KE GITHUB**:
* **Storan Tempatan (Laptop):** Semua kod sumber, skrip kompilasi, fail sandaran (*full eMMC partition dump*), dan dokumentasi disimpan dalam `D:\apps\emas-ota` dan `D:\apps\emas5`.
* **Pelayan VPS Awan (`emas.43-163-3-153.nip.io`):**
  - Menjalankan pelayan FastAPI `server.py`.
  - Berfungsi sebagai jambatan penyelarasan arahan selamat antara telefon pintar/jam tangan dan kereta.
  - Menyimpan pustaka fail audio nada kunci (*presets: Original, Tamatlah Sudah, Quack, Gop Gop Gop, Ack!, Guru Hentikan*).

---

## 8. Panduan Langkah Demi Langkah Pasang di Kereta Kawan

Untuk memasang pada kereta Proton e.MAS 5 lain:

1. **Sediakan Pemacu USB (1.97 GB):**
   - Salin fail `update.zip` asal (1.97 GB) ke pemacu USB FAT32.
   - Cucuk pada port USB konsol hadapan kereta kawan.
   - Jalankan kemas kini USB melalui menu tetapan atau sistem pemulihan kilang untuk mengaktifkan akses root & Wi-Fi ADB.

2. **Sambung Komputer Riba ke Hotspot Kereta:**
   - Sambung ke Wi-Fi kereta kawan.
   - Cari IP IHU kereta:
     ```powershell
     powershell -ExecutionPolicy Bypass -File .\scan_adb.ps1
     adb connect <IP_KERETA>:5555
     ```

3. **Tolak Ejen Latar Belakang & Aplikasi e.MAS Energy:**
   ```powershell
   # Jalankan skrip binaan dan pemasangan terus:
   .\build_daemon.ps1
   .\deploy_noor_energy.ps1
   ```

4. **Nyahaktifkan Ikon Yang Tidak Berkaitan:**
   ```bash
   adb shell pm disable com.emas.dvrprobe
   ```

5. **Selesai:** Buka laci aplikasi (*App Drawer*) pada skrin kereta kawan dan tekan ikon **e.MAS Energy** untuk mula menikmati bacaan telemetri bateri masa nyata dan mod khemah!
