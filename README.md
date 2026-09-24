# Proton e.MAS 7 / Geely Galaxy E5 (IHU801P) System Architecture & Research 🚗⚡

Pusat dokumentasi teknikal induk, koleksi skrip automasi, penyahkod telemetri CAN Bus, ejen latar belakang, dan perisian kokpit untuk kenderaan elektrik **Proton e.MAS 7 / Geely Galaxy E5** (Platform `SX11RA` / `IHU801P`).

> **Nota Privasi & Storan:**
> Projek ini disimpan secara setempat (*offline local repository*) dan diselaraskan bersama pelayan persendirian VPS (`emas.43-163-3-153.nip.io`). Tiada sebarang kod atau data sensitif dipaparkan secara umum di GitHub.

---

## 📖 Dokumentasi Lengkap Sistem

Sila rujuk panduan teknikal penuh yang merangkumi formula fizik bateri, kod CAN Bus, sistem pelepasan tekanan, dan panduan pasang kereta kawan di:
👉 **[DOKUMENTASI LENGKAP SISTEM EMAS 7](DOKUMENTASI_LENGKAP_EMAS7.md)**

---

## 🚀 Komponen Utama Yang Dibina

### 1. `e.MAS Energy` Cockpit Dashboard (Native IHU App)
* **Slot Pakej Selamat:** Dipasang di atas slot whitelist `com.noor.thenoorcar` menggantikan "The Noor" dengan ikon neon elektrik moden.
* **Penyahkodan CAN Bus Sebenar:**
  - **BMS Battery SOC:** `678429103` (`BMSH_BattSOC`) $\rightarrow$ Membaca tepat sepadan meter (contoh: **87.2%**, bukan lagi 82%).
  - **Voltan Pek Bateri (HV):** `678429461` (`BMSH_HVBusVolt`) $\rightarrow$ ~398.9 V.
  - **Arus Bateri HV:** `678429104` (`BMSH_BattCurr`) $\rightarrow$ Mengira kuasa segera fizik sebenar $P = V \times I$.
  - **Brek Regeneratif (Regen):** Memaparkan kuasa negatif (**`-n kW`**) berlampu hijau zamrud semasa membrek.
  - **Beban Pendingin Hawa Dinamik:** `678429413` (`VCU_ThermalPwrAct`) $\rightarrow$ Membaca kuasa pemampat (*compressor*) dan kipas (*blower*) secara langsung (contoh: 1070 W).
  - **Sistem 12V Aux:** `678429414` (`VCU_DCDCPwrAct`) $\rightarrow$ Penggunaan DC-DC Converter (100W – 250W).
  - **Baki Jarak (DTE):** `678429101` (`VCU_EVDTEodometer`) $\rightarrow$ Jarak perbatuan tepat dalam km.

### 2. `DoorPressureReliefDaemon` (Ejen Latar Belakang Java)
* **Pelepas Tekanan Pintu (*Pressure Relief*):** Tingkap turun secara automatik 10-15% semasa mana-mana pintu dibuka dan naik semula semasa ditutup untuk penutupan pintu yang lembut tanpa tekanan udara.
* **Mod Khemah Pintar (*Camping Mode*):**
  - Mencegah kereta daripada mati (*keep-awake*).
  - Mengira baki jam penggunaan bateri (cth: baki 52.5 kWh = ~131 jam sejuk).
  - Mod **Skrin Tidur** (skrin malap hitam pekat waktu tidur, sentuh untuk nyalakan semula).
  - Butang **Retak Tingkap 5%** untuk peredaran udara segar.
* **Penukar Nada Kunci Kustom (*Custom Lock Sound Switcher*):**
  - Polling berkala ke pelayan VPS (`/api/car/sync`).
  - Menukar fail audio `/sdcard/V3_Vehicle_Locked_Single.wav` secara langsung.
  - Mengunci fail dengan `chmod 444` daripada ditimpa oleh aplikasi sistem `ecarx.settings`.
  - Memulakan semula servis natif `/system/bin/ecarx_reverse_radar` dalam masa 3 saat tanpa perlu reboot.

### 3. Pemasangan Kereta Kawan (100% Universal Plug-and-Play)
* Pakej kemas kini USB **1.97 GB (`update.zip`)** adalah serasi sepenuhnya dengan mana-mana Proton e.MAS 7 atau Geely Galaxy E5.
* **Tiada perubahan fail atau nombor VIN diperlukan** kerana perkakasan unit utama berkongsi sijil tandatangan kilang dan seni bina `SX11RA` yang sama.

### 4. Modifikasi Audio Pejalan Kaki (AVAS V8 Sound)
* Menggantikan slot nada 3 (*Pixel Technology*) dengan ngauman enjin V8.
* Penalaan lengkung audio `tuning3_rumble.txt` dengan algoritma penyulitan proprietary XOR 0x70 dan semakan integriti CRC16-CCITT.
* Persistensi boot kekal melalui `zzz_avas_boot.rc` dan `avas_boot.sh`.

---

## 🛠️ Fail & Skrip Automasi Utama

| Fail / Skrip | Fungsi |
| :--- | :--- |
| `DOKUMENTASI_LENGKAP_EMAS7.md` | Dokumentasi induk komprehensif sistem e.MAS 7 / Geely E5 |
| `SESSION_STATE.md` | Log status teknikal terkini dan daftar pembolehubah sistem |
| `build_daemon.ps1` | Mengompilasi `DoorPressureReliefDaemon.java` $\rightarrow$ `door_pressure_relief.jar` |
| `build_noor_clean.ps1` | Membina pakej bersih `NoorEnergy_clean.apk` (33 KB) |
| `deploy_noor_energy.ps1` | Memuat naik dan memasang APK kokpit terus ke IHU kereta |
| `scan_adb.ps1` | Mengimbas IP IHU kereta pada rangkaian tempatan port 5555 |

---

## ⚠️ Penafian (Disclaimer)
Dokumentasi dan skrip dalam repositori ini dibangunkan untuk tujuan penyelidikan peribadi, pendidikan, dan peningkatan fungsi kenderaan elektrik pemilik sendiri.
