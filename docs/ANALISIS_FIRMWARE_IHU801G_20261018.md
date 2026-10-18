# ANALISIS FIRMWARE IHU801G (Geely Xingyuan / Proton e.MAS 5)

**Tarikh kemas kini:** 18 Oktober 2026, 15:35  
**Status:** **SELESAI (Analisis Penuh & Pengesahan Sandbox)**

> [!IMPORTANT]
> **Model Berbeza Tetapi Berkongsi Perkakasan Asas.**
> Firmware ini untuk **IHU801G** (Geely Xingyuan), manakala kereta kita ialah **IHU801P** (Proton e.MAS 5).
> Kedua-duanya berkongsi cip pemproses **MediaTek MT8666**, papan rujukan **ECARX E-OS**, dan protokol UART MCU yang sama. Walau bagaimanapun, lapisan OS berbeza: Xingyuan menggunakan **Android 9 (Pie) Non-A/B**, manakala e.MAS 5 menggunakan **Android 10 (Q) A/B Dynamic Partitions (`super.img`)**. Jangan salin imej sistem mentah Xingyuan ke partisi eMMC e.MAS 5.

---

## 1. Sumber & Tiga Generasi Versi (Changelog)

Sumber asal: `geely-xingyuan-IHU801G.zip` — **8.68 GB**, mengandungi tiga binaan OTA penuh:

| Fail OTA | Build | Timestamp Kilang | Saiz | Status Firmware MCU |
| :--- | :--- | :--- | ---: | :--- |
| `SW0E22H0519H8DZH.03094.zip` | `...03094.user/3094` | 2026-05-18 20:24 UTC | 2.91 GB | `fsl_app.s19` (v3) & `fsl_app_1m.s19` (v2) |
| `SW0E22H0119H8DUH.02884.zip` | `...02884.user/2884` | 2026-01-19 09:55 UTC | 2.90 GB | `fsl_app.s19` (v2) & `fsl_app_1m.s19` (v1 - Baru) |
| `SW0E22H1202H8EWH.02726.zip` | `...02726.user/2726` | 2025-12-01 16:20 UTC | 2.87 GB | `fsl_app.s19` (v1 sahaja, tiada 1MB) |

### Metadata Sepunya:
```ini
pre-device                = IHU801G
post-sdk-level            = 28          (Android 9)
post-security-patch-level = 2021-09-05
ota-type                  = BLOCK
pre-efuse                 = true
post-build                = GEELYGEOME/IHU801G/IHU801G:9/...:user/test-keys
```

### Penemuan Evolusi Kod & Perkakasan MCU:
1. **Peralihan Perkakasan MCU:** Pada versi awal (Build 2726), hanya MCU 768 KB disokong. Bermula Build 2884, Geely memperkenalkan perkakasan MCU baru bersaiz 1 MB (`fsl_app_1m.s19`).
2. **Pengesanan Pin Fizikal:** Di dalam `updater-script`, skrip menyemak status pin GPIO (`get_mcu_hw_gpio()`) untuk memilih sama ada menulis fail 768 KB atau 1 MB:
   ```edify
   ifelse (
       less_than_int(get_mcu_hw_gpio(), "1"),
       (
           assert(package_extract_file("fsl_app.s19", "/tmp/fsl_app.s19"));
           ui_print("mcu upgrade old mcu hw");
       ),
       (
           assert(package_extract_file("fsl_app_1m.s19", "/tmp/fsl_app.s19"));
           ui_print("mcu upgrade new mcu hw");
       )
   );
   ```
3. **Kestabilan Peta Storan:** Fail `scatter.txt` mempunyai checksum MD5 yang **100% identikal** merentasi ketiga-tiga versi (`47a76954df2ce066d0c990e814bb4f29`), membuktikan susunan memori eMMC MT8666 tidak pernah berubah sejak hari pertama pengeluaran.

---

## 2. Peta Memori MediaTek MT8666 (`scatter.txt`)

Peta alamat fizikal storan eMMC/UFS rasmi yang diekstrak daripada firmware pemulihan:

| Partisi | Alamat Mula (Hex) | Fungsi / Catatan Keselamatan |
| :--- | :--- | :--- |
| `preloader` | `0x0` | Bootloader peringkat pertama MediaTek (dalam `mmcblk0boot0/1`) |
| `recovery` | `0x108000` | Recovery mode AOSP (16 MB) |
| `nvcfg` | `0x3688000` | Konfigurasi modem radio NVDATA |
| `nvdata` | `0x5688000` | Kalibrasi RF dan IMEI / eSIM |
| `proinfo` | `0xd200000` | **Nombor siri unit & Hardware ID kilang** |
| `md1img` | `0xd500000` | Firmware Modem 4G Baseband |
| `cam_vpu1..3` | `0x14800000` | Vision Processing Unit (Kamera AVM 360) |
| `gz1` / `gz2` | `0x17500000` | GenieZone Hypervisor / Microkernel (A/B) |
| `nvram` | `0x19500000` | Kalibrasi Wi-Fi & Alamat MAC Bluetooth |
| `lk` / `lk2` | `0x124d00000` | Little Kernel Bootloader (A/B Redundant) |
| `boot` | `0x124f00000` | Kernel Linux Android |
| `tee1` / `tee2` | `0x12d900000` | TrustZone OS (Keselamatan kriptografi) |
| `vendor` | `0x13a000000` | Pemandu perkakasan & servis HAL |
| `system` | `0x1af800000` | OS Android Flyme Auto |
| `pki` | `0x34a800000` | **Public Key Infrastructure (Sijil digital kenderaan)** |
| `vechilecfg` | `0x40aa00000` | **Vehicle Configuration (VIN & pilihan varian casis)** |
| `ivres` | `0x691e00000` | Sumber model AI Baidu DuerOS (326 MB) |
| `userdata` | `0x6d2800000` | Storan data pengguna |

> [!WARNING]
> Jangan ubah atau padam partisi `proinfo`, `nvcfg`, `nvdata`, `nvram`, `pki`, dan `vechilecfg` (`ecarxpara`). Kehilangan partisi ini akan melumpuhkan fungsi sambungan rangkaian dan identiti rasmi kenderaan.

---

## 3. Rantai Perkakasan ke Perisian (VHAL & CAN Bus IPC)

Hasil bedah siasat binari `/vendor/bin/hw/android.hardware.automotive.vehicle@2.0-service`, `/vendor/bin/hw/vehicleBootService`, dan `fsl_app_1m.s19`:

```text
[Rangkaian CAN Kenderaan (Stereng / Hawa Dingin / Pintu / TPMS)]
                               │
                               ▼
            [MCU Freescale/NXP - fsl_app_1m.s19]
             • MCU SWRC_L (Kluster Stereng Kiri)
             • MCU SWRC_R (Kluster Stereng Kanan)
             • Can ACC ON / Can Sleep
                               │
                               ▼ (Port Serial: /dev/ecp_uart_1)
            [Daemon: /vendor/bin/hw/vehicleBootService]
             • Fungsi: sendDataToMCU / readDataFromMcu
             • Mengurus sambungan awal serial
                               │
                               ▼ (Unix Socket: /dev/socket/mcu_bsp)
    [Android VHAL: android.hardware.automotive.vehicle@2.0-service]
             • Menggunakan ECarXSocketComm_v2_0
             • Memetakan event SWC_IncaNet & SWC_Cancel
             • Mendedahkan VehicleProperty HIDL
                               │
                               ▼
        [Aplikasi Pengguna Flyme Auto / ACO Stack]
         (GeelyAutoClimate, GeelyAutoSettings, Launcher)
```

---

## 4. Bedah Siasat Grafik & Model Kenderaan

1. **`ivres.img` (326 MB):**
   * Disahkan **BUKAN** mengandungi model 3D kenderaan.
   * Mengandungi enjin pengecaman suara **Baidu DuerOS / CoDriver** (`vrres/asr_model/`, `vrres/tts_model/`, `vrres/offline_nlu_model/`) bersama fail wake-word Mandarin (`esis_xiaodu_wak.pkg`).
2. **Visual 3D & Papan Pemuka (`GeelyAutoCarExhibit.apk` & `GeelyAutoSceneDirector.apk`):**
   * Flyme Auto pada platform ini menggunakan pendekatan cekap tenaga: menggabungkan **Tencent PAG Vector Animations (`libpag.so`)** dan **gelung video MP4 60fps** berdefinisi tinggi.
   * Fail video utama: `res/3y.mp4` (88.8 MB) dan `res/GV.mp4` (96.8 MB) memaparkan reka bentuk penuh Geely Xingyuan yang 100% sepadan dengan Proton e.MAS 5.
   * `GeelyAutoSceneDirector.apk` mengandungi mod khas: *Car Wash Mode* (`v_car_wash.mp4`), *Screen Cleaning Mode* (`v_screen_clean.mp4`), dan *Relaxation Mode* lengkap dengan audio alam sekitar (`aurora.mp3`, `waves.mp3`, `rain.mp3`).

---

## 5. Ujian Makmal Simulasi Kegagalan (Sandbox Fault-Tolerance)

Untuk memastikan keselamatan perisian sebelum diuji pada kereta sebenar, makmal simulasi telah dibina di `simulation_sandbox/`:

### Senario 1 & 2: Pemulihan Kernel Rosak & Proinfo Hilang
* **Kerosakan Disuntik:** Magic header `boot_a` dirosakkan (`CORRUPTED_BRICKED_DATA`) dan `proinfo` dipadam ke sifar.
* **Hasil Pemulihan:** Skrip pemulihan mengesan isu, memulihkan partisi daripada sandaran laptop, dan mengesahkan SHA256 padan 100%.

### Senario 3: Sambungan Wi-Fi Terputus Semasa Tengah Menulis (Flash)
* **Ujian Kaedah Berbahaya (Direct Stream ke eMMC `| dd of=/dev/block`):**
  * Terputus pada 40%. Partisi eMMC terpotong pada 409 KB ➡️ **Hard-brick kekal!**
* **Ujian Kaedah Toolbox (`Atomic Staging + Pre-Flash Checksum`):**
  * Fail dimuat naik ke staging `/data/local/tmp`. Wi-Fi terputus pada 40%.
  * Pengesahan checksum gagal ➡️ Arahan `dd` **dibatalkan secara automatik**.
  * eMMC kenderaan **tidak disentuh langsung (100% Selamat)**.

### Senario 4: Sambungan CAN Bus / MCU Terputus Semasa Read/Write
* Apabila sambungan terputus di tengah sesi pengaturcaraan UDS (`0x10 0x03`):
* Isyarat *Tester Present (`0x3E`)* terhenti.
* Dalam masa **2.5 saat**, **Hardware Watchdog MCU** terpicu secara automatik:
  1. Menutup semula Security Access.
  2. Membatalkan semua arahan penulisan CAN.
  3. Mengembalikan ECU Stereng dan Aircond ke mod pemanduan biasa (Failsafe).
  4. Tiada ECU yang terkunci atau rosak.

---

## 6. Kemas Kini e.MAS Toolbox Desktop

Ciri baharu telah disepadukan ke dalam antaramuka:
* **Pilihan 1: `SANDARAN IDENTITI & KALIBRASI UNIK (~60 MB)`**
  * Menyalin 9 partisi utama: `proinfo`, `nvcfg`, `nvdata`, `nvram`, `protect1`, `protect2`, `seccfg`, `sec1`, `pki`.
  * Siap dalam masa ~10 saat terus ke folder `Desktop/eMAS5_Identity_Backup/`.

---

## 7. Rujukan Alat Simulasi
Skrip simulator sedia untuk dijalankan bila-bila masa:
* Python: `simulation_sandbox/simulate_ihu_unbrick.py`
* Batch Launcher: `simulation_sandbox/TEST_SIMULASI_UNBRICK.bat`
