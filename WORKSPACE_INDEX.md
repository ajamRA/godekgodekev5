# 🗺️ PANDUAN INDUK STRUKTUR WORKSPACE & KATALOG TOOL
**Proton e.MAS 5 (Geely EX2 / IHU801P)**  
*Dokumen navigasi rasmi untuk pembangun & AI Agent.*

---

## 📌 Ringkasan Pantas (Quick Reference)

Jika anda seorang **AI Agent** atau **Developer** yang baru masuk ke workspace ini, gunakan jadual ini untuk mencari sebarang tool atau fail:

| Anda Nak Cari / Buat Apa? | Lokasi Direktori | Keterangan & Fail Penting |
| :--- | :--- | :--- |
| **Bantu kawan unlock & pasang apps** | [`PAKEJ_KAWAN_EMAS5/`](file:///d:/apps/emas-ota/PAKEJ_KAWAN_EMAS5) | Pakej lengkap 1-klik: `1_USB_UNLOCK/update.zip`, `2_PASANG_SEMUA/pasang.ps1`, `3_PULIHKAN_JOOX/`. |
| **Remote debug kereta kawan (Beda network/VPS)** | [`REMOTE_KERETA_VPS/`](file:///d:/apps/emas-ota/REMOTE_KERETA_VPS) | `1_SISI_KERETA_TEMPAT_KERJA/` (kawan jalankan) & `2_LAPTOP_RUMAH_DEBUG/` (anda jalankan). |
| **Cari APK mentah untuk dipasang** | [`apks/`](file:///d:/apps/emas-ota/apks) | 44 fail APK sedia guna: `voiceservice_ACO.apk`, `XCCamera360.apk`, `SmartTube.apk`, dll. |
| **Cari skrip automasi (PowerShell / Python / Shell)** | [`tools/`](file:///d:/apps/emas-ota/tools) | Pecahan: `powershell/`, `python/`, `shell/`, `batch/`, `binaries/` (`yt-dlp`, `payload-dumper-go`). |
| **Cari fail audio, nada kunci, AVAS** | [`media/audio/`](file:///d:/apps/emas-ota/media/audio) | 36 fail WAV/MP3: nada kunci Mario, chime, enjin V8, bDM chime. |
| **Cari screenshot atau video ujian** | [`media/captures/`](file:///d:/apps/emas-ota/media/captures) | 230 fail imej PNG (frame TikTok, UI dash) & video rakaman MP4. |
| **Cari binary arm64 / HAL / C++** | [`builds/native_bin/`](file:///d:/apps/emas-ota/builds/native_bin) | `vehicle-hal-service`, `uinput_gamepad`, `tinyplay_arm64`, pustaka `.so`. |
| **Cari kod Java / JAR / DEX ujian** | [`builds/java/`](file:///d:/apps/emas-ota/builds/java) | 136 fail JAR, class, java (`VhalProbe.java`, `window.jar`, `TestDirectInput.jar`). |
| **Cari laporan audit & dokumen teknikal** | [`docs/`](file:///d:/apps/emas-ota/docs) | 41 fail dokumen: SOP, manual panduan, laporan audit VPS & UI review. |
| **Cari kod decompile JADX / bedah APK** | [`_re_decompiled/`](file:///d:/apps/emas-ota/_re_decompiled) | 28 folder decompile: `jadx_carservice`, `jadx_launcher`, `jadx_hvac`, `jadx_pms`, dll. |
| **Cari backup penuh kereta & dump vendor** | [`_backups/`](file:///d:/apps/emas-ota/_backups) | `full_backup_car/`, `firmware_backup/`, `backup_before_rumble/`, partition dumps. |
| **Cari game retro & ROM emulator** | [`_retro_gaming/`](file:///d:/apps/emas-ota/_retro_gaming) | `CarRetroROMs/`, `IHU_RetroGaming_Pack/`, `pokemon_pack/`, `MetalSlugGBA/`. |
| **Analisis firmware IHU801G (Geely Xingyuan)** | [`Geely Xingyuan/`](file:///d:/apps/emas-ota/Geely%20Xingyuan) | 22 GB dump firmware, 3 versi OTA, `vendor.img`, `boot.img`, `init_rc/`, `scatter.txt`. **Bukan model e.MAS 5.** Rujuk [`docs/ANALISIS_FIRMWARE_IHU801G_20261018.md`](file:///d:/apps/emas-ota/docs/ANALISIS_FIRMWARE_IHU801G_20261018.md). |
| **Simulasi unbrick & ujian kegagalan** | [`simulation_sandbox/`](file:///d:/apps/emas-ota/simulation_sandbox) | Makmal ujian simulasi kerosakan kernel, Wi-Fi terputus semasa flash, dan UDS watchdog timeout. Rujuk `simulate_ihu_unbrick.py`. |

---

## 🚀 1. Projek Aplikasi Utama (Active Production Projects)

Folder-folder ini adalah projek aplikasi mandiri yang aktif dibangunkan:

### 1.1 [`PAKEJ_KAWAN_EMAS5/`](file:///d:/apps/emas-ota/PAKEJ_KAWAN_EMAS5)
Pakej siap pasang untuk kenderaan rakan:
- **`1_USB_UNLOCK/update.zip`** (2.07 GB): Pakej OTA penuh pengaktifan kekal port ADB TCP 5555 & akses root.
- **`2_PASANG_SEMUA/`**: Skrip automatik memasang SmartTube (atas TikTok), Netflix (atas JOOX), dan kawalan mod servis stereng (Hold Volume Down 5 saat).
- **`3_PULIHKAN_JOOX/`**: Skrip pemulihan kilang segera jika kawan ingin mengembalikan JOOX Music asal.

### 1.2 [`REMOTE_KERETA_VPS/`](file:///d:/apps/emas-ota/REMOTE_KERETA_VPS)
Alat sambungan sokongan jarak jauh merentasi rangkaian berbeza (contoh: Laptop rumah <---> Kereta di tempat kerja):
- **`1_SISI_KERETA_TEMPAT_KERJA/BUKA_TUNNEL_KERETA.bat`**: Dijalankan di sisi kereta untuk tolak port ADB ke VPS (`43.159.63.29`).
- **`2_LAPTOP_RUMAH_DEBUG/1_SAMBUNG_KE_KERETA.bat`**: Dijalankan di rumah untuk sambung terus ke shell ADB / peranti kereta.
- **`2_LAPTOP_RUMAH_DEBUG/2_TARIK_LOG_NETFLIX.bat`**: Skrip sedut diagnostik automatik (status Stealth Mode, bind mount JOOX, dan tapisan crash logcat).

### 1.3 [`EMAS_TOOLBOX_DESKTOP/`](file:///d:/apps/emas-ota/EMAS_TOOLBOX_DESKTOP)
Aplikasi desktop GUI rasmi (C# WPF + Node.js Engine) untuk pengurusan penuh peranti e.MAS 5:
- Imbasan automatik IP kereta pada subnet Wi-Fi.
- Pengurusan slot pemasangan aplikasi (SmartTube, Netflix, Spotify).
- Penukar & penyunting profil audio AVAS serta nada kunci.

### 1.4 Projek Aplikasi Android e.MAS:
- [`EmasEnergyApp/`](file:///d:/apps/emas-ota/EmasEnergyApp): Aplikasi pemantauan penggunaan tenaga bateri masa nyata, integrasi UI, dan perkhidmatan terapung EV/AC (100% bersih tanpa floating pill `APPS >`).
- [`EmasRemoteApp/`](file:///d:/apps/emas-ota/EmasRemoteApp): Aplikasi telefon pintar pengawal telematik kenderaan melalui pelayan VPS.
- [`EmasCompanionPhone/`](file:///d:/apps/emas-ota/EmasCompanionPhone): Aplikasi pendamping Android telefon pintar.
- [`EmasDashcamApp/`](file:///d:/apps/emas-ota/EmasDashcamApp): Integrasi perakam DVR/dashcam terbina dalam IHU.
- [`EmasWirelessGameHub/`](file:///d:/apps/emas-ota/EmasWirelessGameHub): Hab penyambungan alat kawalan permainan tanpa wayar (gamepad) pada IHU.

---

## 📁 2. Sumber Daya Tersusun (Organized Resources)

### 2.1 [`apks/`](file:///d:/apps/emas-ota/apks)
Mengandungi semua fail pakej aplikasi APK mentah yang pernah diuji atau dipasang ke peranti:
- `voiceservice_ACO.apk` (Perkhidmatan suara ACO Tech)
- `XCCamera360.apk` (Kamera pandangan 360 darjah)
- `SmartTube.apk`, `NewPipe*.apk` (Pemain video tanpa iklan)
- `Netflix.apk` & variasi arkitektur `netflix_splits/`

### 2.2 [`media/`](file:///d:/apps/emas-ota/media)
- **`audio/`**: Fail WAV/MP3 nada kenderaan:
  - `V3_Vehicle_Locked_Single.wav` (Nada kunci kilang)
  - `lock_mario.wav`, `lock_quack.wav`, `lock_ack.wav` (Nada kunci kustom)
  - `v8_clean_44byte_header.wav`, `custom_v8.wav` (Nada sintesis pejalan kaki AVAS)
  - `mode_chime.wav` (Chime penukaran mod servis stereng)
- **`captures/`**:
  - `tiktok_frame_01.png` hingga `frame_27.png` (Bingkai animasi TikTok IHU)
  - Video MP4 demo fungsi stereng dan aplikasi.

### 2.3 [`tools/`](file:///d:/apps/emas-ota/tools)
- **`powershell/`**: Skrip automasi pentadbiran peranti (`deploy_*.ps1`, `fix_*.ps1`, `sc_stealth_mode.ps1`).
- **`python/`**: Skrip utiliti sistem (`update_vps_routes.py`, `patch_system.py`, `tune_prompt.py`).
- **`shell/`**: Skrip Linux & konfigurasi init Android (`*.sh`, `*.rc` seperti `zzz_custom_apps.rc`, `zzz_telemetry_boot.rc`).
- **`batch/`**: Fail pelancar Windows batch (`deploy_energy_app.bat`, `hu-password.bat`).
- **`binaries/`**: Utiliti boleh laksana seperti `payload-dumper-go.exe` dan `yt-dlp.exe`.

### 2.4 [`builds/`](file:///d:/apps/emas-ota/builds)
- **`java/`**: Fail arkib Java JAR, kelas `.class`, dan sumber `.java` ujian terus ke peranti (`TestDirectInput.jar`, `window.jar`, `vhal_probe.jar`).
- **`native_bin/`**: Fail binary Linux ARM64 asli (`vehicle-hal-service`, `tinyplay_arm64`, `uinput_gamepad`, `update_engine_bin`, pustaka `.so`).
- **`archives/`**: Payload sistem dan firmware bersaiz padat (`.zip`, `.xz`, `.bin`).

### 2.5 [`docs/`](file:///d:/apps/emas-ota/docs)
Semua dokumen analisis dan tadbir urus teknikal:
- [`docs/ARAHAN_ASAS_EMAS5.md`](file:///d:/apps/emas-ota/docs/ARAHAN_ASAS_EMAS5.md) — Prinsip teras tadbir urus projek e.MAS 5.
- [`docs/SOP_PEMASANGAN_APK.md`](file:///d:/apps/emas-ota/docs/SOP_PEMASANGAN_APK.md) — Tatacara selamat sambungan ADB dan pemasangan APK.
- [`docs/SOP_DIAGNOSIS_MASALAH.md`](file:///d:/apps/emas-ota/docs/SOP_DIAGNOSIS_MASALAH.md) — Kaedah penyiasatan ralat berstruktur read-only.
- [`docs/KATALOG_TINDAKAN_STANDARD.md`](file:///d:/apps/emas-ota/docs/KATALOG_TINDAKAN_STANDARD.md) — Senarai modul tindakan berparameter.
- [`docs/PANDUAN_MODIFIKASI_GEELY_EX2.md`](file:///d:/apps/emas-ota/docs/PANDUAN_MODIFIKASI_GEELY_EX2.md) — Panduan mendalam teknik modifikasi hardware, AVAS, dan bind mount.
- [`docs/VPS_WEBAPP_AUDIT_REPORT.md`](file:///d:/apps/emas-ota/docs/VPS_WEBAPP_AUDIT_REPORT.md) — Laporan audit penuh Web App pelayan VPS.
- [`docs/USER_MANUAL_TDASHCAM.md`](file:///d:/apps/emas-ota/docs/USER_MANUAL_TDASHCAM.md) — Manual penggunaan aplikasi perakam dashcam.

### 2.6 [`logs_dumps/`](file:///d:/apps/emas-ota/logs_dumps)
Fail-fail log diagnostik peranti, rekod logcat lama, dan fail pelarasan audio AVAS (`tuning1.txt` hingga `tuning3.txt`).

### 2.7 [`configs/`](file:///d:/apps/emas-ota/configs)
Fail manifes sistem Android (`AndroidManifest.xml`), konfigurasi JSON (`config.json`, `MODS_CATALOG.json`), dan kunci tandatangan pembangunan (`debug.keystore`).

---

## 📦 3. Arkib Forensik & Eksperimen (Deep Dive Folders)

Folder-folder ini mengandungi artifak penyelidikan mendalam (dikelompokkan dengan awalan `_` agar sentiasa tersusun di atas):

- [`_re_decompiled/`](file:///d:/apps/emas-ota/_re_decompiled): 28 folder kod sumber APK yang telah dinyahkompil (*decompiled*) menggunakan JADX (termasuk `jadx_carservice`, `jadx_launcher`, `jadx_hvac`, `decompiled_door_pressure`).
- [`_build_temp/`](file:///d:/apps/emas-ota/_build_temp): 40 folder artifak kompilasi perantara (`build_daemon`, `build_door_chime`, `classes_away`, staging DEX).
- [`_src_modules/`](file:///d:/apps/emas-ota/_src_modules): 11 folder sumber modular eksperimen (`src_avas`, `src_steering_mode`, `src_window`, dll).
- [`_backups/`](file:///d:/apps/emas-ota/_backups): Salinan keselamatan menyeluruh peranti (`full_backup_car/`, fail vendor asal kilang, dan sandaran firmware).
- [`_retro_gaming/`](file:///d:/apps/emas-ota/_retro_gaming): Pakej emulator RetroArch, Game Boy Advance, dan ROM permainan untuk IHU.
- [`_archive_experiments/`](file:///d:/apps/emas-ota/_archive_experiments): Eksperimen pelayan Netlify, alat integrasi fat32, dan runtime PowerShell mudah alih.

---

> *Dokumen ini sentiasa dipelihara pada folder punca (root) `d:\apps\emas-ota\` sebagai indeks rujukan utama bagi setiap sesi pembangunan.*
