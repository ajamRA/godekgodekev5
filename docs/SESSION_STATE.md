# SESSION STATE — 18 Oktober 2026 (11:30)

Rekod status teknikal rasmi Proton e.MAS 5 (IHU801P) selepas:
1. **Pembangunan EmasTool Desktop Control Center (Standalone Portable Toolbox)**:
   - Terletak di folder: `D:\apps\emas-ota\EMAS_TOOLBOX_DESKTOP\`
   - Mengandungi fail pelancar mandiri: `EmasTool.exe` (C# WebView2 / Edge App launcher) dan `LANCAR_EMAS_TOOL.bat`.
   - Mengandungi binary portable lengkap tanpa bergantung kepada sistem luar: `tools/adb.exe`, `tools/ffmpeg.exe`, `tools/node.exe`.
   - Backend Enjin Node.js di port 5500 (`server.js`) dengan antaramuka Swiss Design (`public/index.html`, `public/style.css`, `public/app.js`).
2. **Auto-Discovery Subnet Kereta Cekap (< 2s)**:
   - Mengimbas semua subnet aktif komputer riba pada port 5555.
   - Mengesahkan identiti model melalui `getprop ro.product.model` (menapis peranti lain seperti telefon/tablet dan terus memilih `IHU801P` di `192.168.1.18:5555`).
3. **Penyelarasan Pengesanan Slot Penuh (Slot Overwrite Detection)**:
   - Mengesan bind-mount aktif:
     * TikTok -> `[DITIMPA: SmartTube]`
     * JOOX Music -> `[DITIMPA: Netflix]`
   - Menyelesaikan kes khas TheNoor: `/system/app/Noor/Noor.apk` bukan bind-mount tetapi *in-place replace* (saiz 4.76MB vs asal ~1.5MB dengan `assets/index.html` Cockpit EV). Dikesan tepat sebagai: `[DITIMPA: EmasEnergy]`.
   - Menyenaraikan semua slot A-Store pihak ketiga (SYOK Radio, Hot FM, Vivaldi, Material Files, AC Bridge) sebagai slot kosong sedia untuk ditimpa.
4. **Pengurus Audio Kustom (Lock Sound & AVAS dengan Auto-Convert FFmpeg)**:
   - **Nada Kunci (Lock Sound)**: Standard kilang dipulihkan dari `/data/local/window/locksounds/lock_original.wav`. Muat naik audio `.mp3`/`.wav` ditukar secara automatik oleh FFmpeg ke spesifikasi radar: `WAV 48,000Hz · Stereo (2-channel) · 16-bit PCM`.
   - **AVAS (Pedestrian Warning Sound)**: 3 slot audio disahkan mengikut struktur firmware `/vendor/etc/avas/`:
     * Slot 1: `yinheyinfu.wav` -> Galaxy Chord (Default)
     * Slot 2: `space.wav` -> Space Sound
     * Slot 3: `pixel.wav` -> Pixel / Dynamic
     * Audio custom ditukar automatik oleh FFmpeg kepada `WAV 48,000Hz · Mono · 16-bit PCM` dan di-bind mount mengikut nombor slot.
5. **Spesifikasi Sandaran Sistem (Backup Dump Architecture)**:
   - **Fast Recovery Rescue (~1.12 GB / 1,116 MB)**: Mengambil `boot_a.img` (32MB), `ecarxpara.img` (1,024MB), dan `dtbo_a.img` (8MB) terus ke laptop untuk perlindungan unbrick pantas.
   - **Full eMMC Archive (~45 GB)**: Mengandungi `super` (14GB) dan `userdata` (31GB) untuk simpanan arkib menyeluruh.

---

## 🚗 1. KENDERAAN & SAMBUNGAN SEMASA
* **Model Kenderaan:** Strictly **Proton e.MAS 5** (IHU801P, Android 10 API 29, root access).
* **IP ADB Semasa:** `192.168.1.20:5555` (Wi-Fi e.MAS 5).
* **Port ADB Terbuka:** `5555`.
* **Desktop Toolbox:** `D:\apps\emas-ota\EMAS_TOOLBOX_DESKTOP\EmasTool.exe` (Port 5500).

---

## 📦 2. STATUS SLOT APLIKASI AKTIF PADA KERETA (12 Okt 2026)
| Slot Asal | Jenis | Laluan Sistem | Status Slot | Ditimpa Oleh |
| :--- | :--- | :--- | :--- | :--- |
| **TikTok** | SYSTEM | `/system/app/ttincar_tiktok_ACO` | 🟢 DITIMPA | **SmartTube** |
| **JOOX Music** | SYSTEM | `/system/app/JOOXMusic_ACO` | 🟢 DITIMPA | **Netflix** |
| **TheNoor** | SYSTEM | `/system/app/Noor/Noor.apk` | 🟢 DITIMPA | **EmasEnergy** |
| **Audio+** | SYSTEM | `/system/app/AudioPlus_ACO` | ⚪ KOSONG (OEM) | Tiada |
| **Vivaldi Browser** | A-STORE | `/data/app/.../base.apk` | ⚪ KOSONG | Sedia untuk ditimpa |
| **Material Files** | A-STORE | `/data/app/.../base.apk` | ⚪ KOSONG | Sedia untuk ditimpa |
| **SYOK Radio** | A-STORE | `/data/app/.../base.apk` | ⚪ KOSONG | Sedia untuk ditimpa |
| **Hot FM** | A-STORE | `/data/app/.../base.apk` | ⚪ KOSONG | Sedia untuk ditimpa |
| **AC Bridge** | A-STORE | `/data/app/.../base.apk` | ⚪ KOSONG | Sedia untuk ditimpa |

---

## 🔊 3. PENGURUS AUDIO (AVAS & LOCK SOUND SPECIFICATIONS)
* **AVAS Pedestrian Sound:**
  - Standard Format: `WAV 48kHz, Mono, 16-bit PCM`.
  - Fail Asal di `/data/local/avas/factory_backup/`:
    * Slot 1: `yinheyinfu.wav` (Galaxy Chord)
    * Slot 2: `space.wav` (Space Sound)
    * Slot 3: `pixel.wav` (Pixel / Dynamic)
  - Lokasi Bind Mount: `/vendor/etc/avas/sound_type_{1,2,3}/`
* **Lock Sound (Nada Kunci):**
  - Standard Format: `WAV 48kHz, Stereo, 16-bit PCM`.
  - Lokasi Aktif: `/sdcard/V3_Vehicle_Locked_Single.wav` & `/data/media/0/V3_Vehicle_Locked_Single.wav`.
  - Servis Radar: `ecarx_reverse_radar` (dimuat semula via SIGHUP/killall).
  - Fail Asal Kilang: `/data/local/window/locksounds/lock_original.wav`.

---

## 💾 4. PENGURUSAN SANDARAN SISTEM (BACKUP SPECS)
* **Unbrick Fast Backup (~1.12 GB)**:
  - `boot_a.img` (32.0 MB) — Kernel & Android Init.
  - `ecarxpara.img` (1,024.0 MB) — Parameter kalibrasi kilang ECARX & token IHU.
  - `dtbo_a.img` (8.0 MB) — Device tree peripheral.
  - Disimpan ke: `Desktop/eMAS5_Full_Backup/`.
* **Full eMMC Image (~45 GB)**:
  - `super.img` (13.96 GB) — Partisi dinamik sistem (`system`, `vendor`, `product`).
  - `userdata.img` (33.60 GB) — Seluruh data pengguna & cache.

---

## 🛣️ 5. SET SEMULA TRIP PERISIAN (`EmasEnergy`)
* **Penyelesaian:**
  * Melaksanakan *Custom Trip Software Offset* dalam `assets/app.js`.
  * Pengguna boleh menekan butang **"Set Semula Trip (Reset ke 0.0 km)"**.
  * Offset odometer disimpan dalam `localStorage.getItem('customTripOffsetKm')` dan diselaraskan secara dinamik.

---

## 🎮 6. KAWALAN STEERING WHEEL (`SteeringModeDaemon.jar`)
* **Lokasi IHU:** `/data/local/window/steering_mode_controller.jar`
* **Pemetaan Butang Steering:**
  1. **Kanan (▶ / HW 305):** Hold 800ms &rarr; Kitaran Drive Mode (`Comfort` ➡️ `Eco` ➡️ `Sport`) + Audio Chime.
  2. **Kiri (◀ / HW 304):** Hold 800ms &rarr; Kitaran Regen Level (`Low` ➡️ `Medium` ➡️ `High`) + Audio Chime.
  3. **Bawah (▼ / HW 302):** Hold 5000ms (5 saat) &rarr; Toggle Mod Servis Proton (Stealth Kilang) + Audio Chime.
  4. **Atas (▲ / HW 301):** 100% dikecualikan daripada pintasan (Volume naik kilang native).

---

## 🛡️ 7. MOD SERVIS PROTON (STEALTH KILANG) (`service_mode.sh`)
* **Lokasi IHU:** `/data/local/window/service_mode.sh`
* **Trigger:** Tekan dan hold butang **BAWAH** di steering wheel selama **5 saat** atau jalankan `sh /data/local/window/service_mode.sh toggle`.
* **Semasa Aktif (`on`):**
  * Matikan daemons custom, unmount AVAS custom, pulihkan lock sound asal kilang, sembunyikan aplikasi sideloaded (`pm disable-user --user 0`).
  * Kereta 100% dalam mod standard kilang untuk pemeriksaan di Pusat Servis Proton (SC).

---

## 🕹️ 8. TETAPAN KUNCI RETROARCH OZONE UI (17–18 Okt 2026)
* **Isu Asal:** Bila RetroArch di-resume daripada *Recent Apps* widget (`com.malaysia.launcher3`), Android memanggil `RetroActivityFuture` secara terus tanpa Intent Extras. Tanpa config fallback pada peringkat sistem, RetroArch jatuh balik ke RGUI (menu teks hijau 8-bit usang) dengan amaran *"Config directory not set"*.
* **Penyelesaian & Perlindungan Bertingkat:**
  1. **Konfigurasi Master:**
     - Disalin ke `/system/etc/retroarch.cfg` (sistem root), `/data/data/com.retroarch.aarch64/retroarch.cfg`, dan `/data/local/retroarch_golden.cfg`.
     - `menu_driver = "ozone"` (Antaramuka moden gelap gaya Nintendo Switch).
     - `config_save_on_exit = "false"` (Mencegah menu menimpa config semasa keluar).
  2. **Kunci Kebenaran (`chmod 0444 - Read Only`):**
     - Fail disetkan read-only untuk menghalang aplikasi mengubah semula tetapan.
  3. **Auto-Restore Persistent Semasa Boot (`/system/etc/init/zzz_custom_apps.rc`):**
     ```bash
     copy /data/local/retroarch_golden.cfg /data/data/com.retroarch.aarch64/retroarch.cfg
     chmod 0444 /data/data/com.retroarch.aarch64/retroarch.cfg
     chown u0_a90 u0_a90 /data/data/com.retroarch.aarch64/retroarch.cfg
     ```
     - Setiap kali sistem but, salinan asal akan dipulihkan dan dikunci secara automatik.

---

## 🧼 9. PENYELARASAN UI FLYME AUTO & PENYINGKIRAN FLOATING PILL (17–18 Okt 2026)
* **Permintaan Pengguna:** Membuang floating pill `APPS >` di sebelah kiri skrin kerana reka bentuknya tidak sepadan dengan tema asal Proton Flyme Auto.
* **Tindakan:**
  * Diubah suai dalam `FloatingDrawerService.java` (`com.noor.thenoorcar`):
    - `initRecentsTabView()` dinonaktifkan daripada ditambah ke `WindowManager`.
  * Dibina dan disahkan melalui `build-verified.ps1` (`NoorEnergy_clean.apk`).
  * Dipasang terus ke `/system/app/Noor/Noor.apk` dengan kebenaran `644` dan konteks SELinux `system_file:s0`.
  * Hasil: Paparan kokpit kini 100% bersih, mengekalkan estetik OEM Flyme Auto asal tanpa elemen terapung pihak ketiga di tepi kiri.


---

## 🔬 10. ANALISIS FIRMWARE GEELY XINGYUAN / IHU801G (18 Okt 2026, 09:15–11:05)

> [!IMPORTANT]
> **Bukan kereta kita.** Firmware ini untuk **IHU801G** (Geely Xingyuan). e.MAS 5 ialah
> **IHU801P**. Metadata `pre-device=IHU801G` akan menggagalkan pemasangan pada IHU801P.
> Tiada apa-apa dari folder ini dipasang pada kereta.

* **Sumber:** `Geely Xingyuan/geely-xingyuan-IHU801G.zip` — 8.68 GB, tiga pakej OTA penuh.
* **Versi:** `SW0E22H0519H8DZH.03094` (2026-05-18), `SW0E22H0119H8DUH.02884` (2026-01-19),
  `SW0E22H1202H8EWH.02726` (2025-12-01). Android 9 (SDK 28), security patch 2021-09-05.
* **Tandatangan:** `user/test-keys` — build ujian, bukan release-keys.
* **Diekstrak:** `vendor.img` (1.97 GB, ext4 sah), `boot.img` (13.8 MB, `ANDROID!!` sah),
  90 fail `init_rc/`, `scatter.txt` (peta MTK), `upgrade.dat`, `fsl_app*.s19`,
  3 APK sistem Geely (~394 MB).
* **Belum siap:** `system.img` masih 0 bait.
* **Punca sebenar kegagalan:** pemasangan **Python 3.12 pada mesin ini rosak** —
  `Lib/` hanya tinggal `site-packages`, `encodings` hilang, jadi `python -c "print(1)"`
  pun gagal. Strim Brotli sendiri **disahkan sah**: 4.73 GB terdekompresi penuh dan
  padan tepat 1,240,554 blok dengan `system.transfer.list`.
* **Skrip baharu:** `sdat2img.py`, `decompress_br.py` (rujuk dokumen analisis).

📄 Dokumen penuh: [`docs/ANALISIS_FIRMWARE_IHU801G_20261018.md`](ANALISIS_FIRMWARE_IHU801G_20261018.md)
