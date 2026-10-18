# Proton e.MAS 5 (Geely EX2 / IHU801P) Customization & Telematics Suite 🚗⚡

Pusat dokumentasi teknikal induk, artifak teruji, dan arkib perisian untuk kenderaan elektrik **Proton e.MAS 5** (Platform `Geely EX2` / `IHU801P` Android 10).

Repositori ini merangkumkan keseluruhan modul sistem, aplikasi kokpit tersuai, integrasi audio luaran AVAS, kawalan telematik jarak jauh, perlindungan servis, serta kajian firmware dan keselamatan storan eMMC.

---

> [!TIP]
> 🗺️ **PETA INDUK DIREKTORI & PANDUAN PANTAS ALATAN:**  
> Untuk navigasi pantas fail, direktori, skrip, dan fail APK, rujuk:  
> 🔗 [**`WORKSPACE_INDEX.md` (Panduan Struktur Workspace & Katalog Tool)**](file:///d:/apps/emas-ota/WORKSPACE_INDEX.md)

---

## 🌟 Senarai Modifikasi & Ciri Tersuai Lengkap (Full Features Catalogue)

Projek ini telah menghasilkan ekosistem modifikasi lengkap merangkumi 6 kategori utama:

```
                               ┌─────────────────────────────────┐
                               │   PROTON e.MAS 5 CUSTOM SUITE   │
                               └────────────────┬────────────────┘
         ┌───────────────────┬──────────────────┼──────────────────┬───────────────────┐
         ▼                   ▼                  ▼                  ▼                   ▼
  [1. Audio & AVAS]   [2. Cockpit UI]    [3. Telematics & VPS] [4. SC Stealth]   [5. Media & Games]
  • Nada Kunci Luar   • Flat Cockpit     • EmasRemoteApp       • 1-Click Stealth • SmartTube / TikTok
  • AVAS Slot Sync    • Live Telemetry   • Dual VPS Tunnel     • Stereng Shortcut• Netflix / JOOX
  • Megafon Stereng   • Frunk/Trunk CAN  • Token Anti-Kick     • Revert Kilang   • RetroArch Ozone
  • Pemicu Pemanduan  • Tanpa APPS Pill  • Companion Telefon   • Tamper-Proof    • Wireless Gamepad
```

---

### 🔊 1. Sistem Audio, AVAS & Nada Luaran Kenderaan
Memanfaatkan pembesar suara bumper hadapan (*external bumper speaker*) kenderaan untuk pelbagai fungsi audio tersuai:

* **Penyegerakan Slot AVAS Sebenar (Real-time AVAS Slot Sync):**
  * Daemon latar belakang yang mengesan penukaran profil audio pada skrin IHU secara masa nyata.
  * Menukar slot fail WAV 48kHz Mono 16-bit PCM secara dinamik tanpa perlu reboot IHU.
* **Nada Kunci & Buka Pintu Tersuai (Custom Lock / Unlock Sounds):**
  * Menembak audio pendek (cth: Mario coin sound, hon retro, chime khas) ke speaker bumper hadapan sejurus pintu dikunci/dibuka.
  * Dilengkapi semakan keselamatan gear (hanya berbunyi ketika kenderaan dalam **Gear P**).
* **Megafon Stereng & Audio Luaran 0ms (Steering Wheel Megaphone):**
  * Memancarkan suara atau klip amaran ke pembesar suara luaran secara langsung dengan latensi sifar.
* **Audio Pemicu Pemanduan Pintar (Smart Drive Audio Triggers):**
  * **Pintu Hadapan Terbuka:** Memainkan ucapan *Assalamualaikum* secara mesra.
  * **Tukar Gear D Pertama Selepas Boot:** Memainkan *Doa Naik Kenderaan* & bunyi syiling Mario bagi menandakan sistem bersedia untuk bergerak.

---

### 🖥️ 2. Aplikasi Kokpit & Telemetri Kuasa (`EmasEnergyApp`)
Aplikasi kokpit berdefinisi tinggi yang disepadukan dengan sistem asal kenderaan:

* **Tema Kokpit Rata (Flat Cockpit Clean Theme):**
  * Butang terapung mengganggu `APPS >` dibuang sepenuhnya bagi mengekalkan estetik 100% konsisten dengan tema Proton Flyme Auto asal.
* **Pemantauan Bateri & Graf Kuasa Masa Nyata (Live Telemetry & EV Power Graph):**
  * Membaca SoC sebenar bateri, arus (Amperes), voltan sistem, dan suhu bateri melalui `BatteryTelemetryDecoder.java`.
  * Graf penggunaan kuasa masa nyata serta bacaan brek regeneratif (*regen braking*).
* **Kawalan & Pemantauan Bonet Depan & Belakang (Frunk & Trunk Monitor):**
  * Status terbuka/tertutup Frunk dan Trunk masa nyata dengan fungsi butang pelepas terus dari skrin kokpit.
* **Laci Pintar & Penukar Aplikasi (Overlay Drawer & Recents UI):**
  * Akses pantas ke aplikasi kegemaran tanpa mengubah struktur launcher kilang.

---

### 📱 3. Kawalan Telematik & Aplikasi Jarak Jauh (`EmasRemoteApp` & `REMOTE_KERETA_VPS`)
Sistem kawalan telematik bebas menggunakan pelayan awan peribadi (*private cloud VPS*):

* **Aplikasi Web Telefon Pintar Mandiri (`EmasRemoteApp`):**
  * Memantau baki bateri, mengunci/membuka kunci pintu, menghidupkan penyaman udara (AC), dan mengawal tingkap terus dari telefon.
* **Terowong Jarak Jauh Dua Hala (Workplace <-> Home ADB Tunnel):**
  * Membolehkan penyelenggaraan atau debugging dilakukan dari rumah ke kereta di tempat kerja melalui IP selamat `43.159.63.29` tanpa pendedahan port awam.
* **Perlindungan Anti-Tendang Aplikasi Rasmi (Proton Link Anti-Kick Guarantee):**
  * Logik token ACO Cloud dilindungi sepenuhnya supaya aplikasi rasmi Proton Link pada telefon pemilik tidak pernah tertendang keluar (*never logged out*).

---

### 🛡️ 4. Perlindungan Servis Pusat (Proton SC Stealth Mode)
Ciri penting bagi melindungi jaminan dan melepasi pemeriksaan pusat servis Proton:

* **1-Klik Proton SC Stealth Mode:**
  * Skrip automatik menyembunyikan semua mount aplikasi pihak ketiga, memulihkan JOOX Music & TikTok asal, dan mematikan port debug.
  * Sistem kereta kelihatan 100% standard kilang di hadapan alat pengimbas diagnostik Proton Service Center.
* **Pintasan Stereng Pantas (Steering Wheel Stealth Toggle):**
  * Pemandu boleh mengaktifkan atau mematikan Mod Servis terus dari tempat duduk pemandu hanya dengan **menekan butang Volume Down pada stereng selama 5 saat**!

---

### 🎮 5. Hiburan Papan Pemuka & Permainan Retro
Maksimumkan penggunaan paparan 14.6 inci resolusi tinggi kenderaan:

* **Penukaran Slot Aplikasi Asli (Native Slot Overwrite Mod):**
  * **SmartTube (YouTube Tanpa Iklan):** Dipasang di atas slot aplikasi TikTok.
  * **Netflix & Spotify:** Dipasang di atas slot aplikasi JOOX Music.
  * Menyokong kawalan butang media pada stereng kenderaan dan mainan audio di latar belakang (*background audio*).
* **Pusat Permainan Retro (RetroArch In-Car Console):**
  * Diprapasang dengan tema Ozone gelap (`chmod 0444` berkekalan) dan pek ROM retro (GBA, SNES, Genesis).
* **Hab Alat Kawalan Tanpa Wayar (`EmasWirelessGameHub`):**
  * Gateway Bluetooth untuk menyambungkan alat kawalan gamepad tanpa wayar ke paparan IHU.
* **Aplikasi Dashcam DVR Terbina Dalam (`EmasDashcamApp`):**
  * Akses dan semakan video kamera pemanduan terus dari antara muka skrin.

---

### 🧰 6. Alat Desktop & Analisis Keselamatan (`EMAS_TOOLBOX_DESKTOP` & Sandbox)
Perisian pengurusan lengkap untuk pengguna dan pembangun:

* **e.MAS 5 Desktop Control Center (C# + Node.js):**
  * Antaramuka moden di `http://127.0.0.1:5500`.
  * Imbasan automatik IP peranti kereta pada rangkaian Wi-Fi.
  * Penukar automatik MP3/WAV ke format patuh kenderaan (Mono 48kHz).
  * **Sandaran Identiti & Kalibrasi Unik (~60 MB):** Menyalin 9 partisi memori paling bernilai (`proinfo`, `nvcfg`, `nvdata`, `nvram`, `pki`, `seccfg`) dalam masa 10 saat!
  * **Sandaran Pantas Kecemasan (1.12 GB):** Menyalin `boot_a.img`, `ecarxpara.img`, dan `dtbo_a.img`.
* **Makmal Simulasi Kegagalan & Pemulihan (`simulation_sandbox/`):**
  * Skrip ujian kendiri `simulate_ihu_unbrick.py` untuk mengesahkan integriti pemulihan sebelum menyentuh perkakasan kereta.
  * Disahkan tahan lasak terhadap:
    * Kerosakan kernel boot / kehilangan identiti siri.
    * Sambungan Wi-Fi putus separuh jalan semasa *flash* (*Atomic Staging + Pre-Flash Checksum*).
    * Sambungan CAN bus terputus ketika menulis (*MCU Hardware Watchdog Timeout*).

---

## 🔬 Kajian Seni Bina Firmware & Perkakasan (IHU801G vs IHU801P)

Hasil analisis terperinci terhadap pakej firmware MediaTek MT8666:

* **Peta Memori Fizikal (`scatter.txt`):** Mengesahkan susunan alamat fizikal eMMC adalah 100% sama antara Geely Xingyuan (China) dan Proton e.MAS 5 (Malaysia), membolehkan pemetaan unbrick tepat.
* **Evolusi Firmware MCU (`fsl_app.s19`):** Mengenal pasti peralihan perkakasan kilang dari MCU 768 KB ke 1 MB (`fsl_app_1m.s19`) yang diuruskan melalui semakan pin perkakasan `get_mcu_hw_gpio()`.
* **Rangkaian Komunikasi VHAL:** Menjejaki aliran data CAN dari MCU ➡️ `/dev/ecp_uart_1` ➡️ `vehicleBootService` ➡️ `/dev/socket/mcu_bsp` ➡️ Android VHAL.

Rujukan penuh:
📖 [**`docs/ANALISIS_FIRMWARE_IHU801G_20261018.md`**](file:///d:/apps/emas-ota/docs/ANALISIS_FIRMWARE_IHU801G_20261018.md)

---

## 📦 Pakej Pantas Untuk Rakan (`PAKEJ_KAWAN_EMAS5`)

Untuk rakan pemilik e.MAS 5 yang ingin mod kenderaan dengan mudah:
1. `1_USB_UNLOCK/update.zip` — Unlock ADB TCP 5555 & akses root kekal melalui USB Flash Drive.
2. `2_PASANG_SEMUA/pasang.ps1` — Skrip 1-klik memasang SmartTube, Netflix, dan kawalan stereng.
3. `3_PULIHKAN_JOOX/` — Skrip pemulihan kilang jika ingin mengembalikan aplikasi asal.

---

## 📜 Tadbir Urus & SOP Operasi
Sebelum menjalankan sebarang arahan ADB atau skrip pada kenderaan sebenar, patuhi:
* [**`docs/ARAHAN_ASAS_EMAS5.md`**](file:///d:/apps/emas-ota/docs/ARAHAN_ASAS_EMAS5.md) *(Prinsip Keselamatan & Had Skop)*
* [**`docs/SOP_PEMASANGAN_APK.md`**](file:///d:/apps/emas-ota/docs/SOP_PEMASANGAN_APK.md) *(Tatacara Pemasangan Selamat)*
* [**`docs/SOP_DIAGNOSIS_MASALAH.md`**](file:///d:/apps/emas-ota/docs/SOP_DIAGNOSIS_MASALAH.md) *(Tatacara Diagnosis Isu)*

---

*Hak Cipta Terpelihara (c) 2026 — Pembangunan Komuniti Kejuruteraan Terbuka Proton e.MAS 5.*
