# SESSION STATE — 9 Oktober 2026 (14:10)

Rekod status teknikal rasmi Proton e.MAS 5 (IHU801P) selepas pelaksanaan lengkap Kawalan Steering Wheel (Drive Mode, Regen & Mod Servis), Mod Servis Proton SC Stealth, Penyepaduan Tab Megafon 0ms dalam EmasEnergy IHU, dan Kemas Kini Voice Presets Phone APK v1.4.

---

## 🚗 1. KENDERAAN & SAMBUNGAN
* **Model Kenderaan:** Strictly **Proton e.MAS 5** (IHU801P, Android 10 API 29, root access).
* **IP ADB Semasa:** `192.168.1.13:5555` (DHCP Wi-Fi: `AzamRahim`).
* **PC Local IP:** `192.168.1.12`.
* **VPS Sync Server:** `https://emas.43-159-63-29.nip.io` (API Key: `azam7988` via `X-API-Key`).
* **SSH VPS:** `ubuntu@43.159.63.29` (`/home/ubuntu/emas5/`).
* **Pakej APK Dipasang:**
  * IHU Monitor: `com.noor.thenoorcar` (EmasEnergy v2.0 dengan Megafon Tab).
  * Phone Remote: `com.azam.emaswidget` (eMAS Remote v1.4 Hosted di VPS).

---

## 🎮 2. KAWALAN STEERING WHEEL (`SteeringModeDaemon.jar`)
* **Lokasi IHU:** `/data/local/window/steering_mode_controller.jar`
* **Skrip Pelancar:** `/data/local/window/start_steering_daemon.sh` (PID aktif)
* **Log Fail:** `/data/local/window/steering.log`
* **Seni Bina Interception:**
  * Memintas input secara terus pada peringkat `IInputManager` Android Binder IPC melalui `EmasDirectInputAdapter`.
  * Menggunakan `SystemClock.uptimeMillis()` yang tepat dan bebas daripada delay UI.
  * Replay OEM tidak menyekat atau mengganggu butang asal jika ditekan secara biasa.
* **Pemetaan & Tingkah Laku Butang Steering:**
  1. **Kanan (▶ / HW 305):**
     * **Hold 800ms:** Kitaran Drive Mode (`Comfort 0` ➡️ `Eco 1` ➡️ `Sport 2`) + Audio Chime (`/data/local/window/mode_chime.wav`).
     * **Tekan Sekejap (<800ms):** Replay keyevent 87 (`KEYCODE_MEDIA_NEXT`) untuk langkau lagu seterusnya.
  2. **Kiri (◀ / HW 304):**
     * **Hold 800ms:** Kitaran Regen Level (`Low 1` ➡️ `Medium 2` ➡️ `High 3`) + Audio Chime (`/data/local/window/regen_chime.wav`).
     * **Tekan Sekejap (<800ms):** Replay keyevent 88 (`KEYCODE_MEDIA_PREVIOUS`) untuk lagu sebelumnya.
  3. **Bawah (▼ / HW 302):**
     * **Hold 5000ms (5 saat):** Toggle Mod Servis Proton (Stealth Kilang) + Audio Chime 35% volume.
     * **Tekan Sekejap (<5000ms):** Replay keyevent 25 (`KEYCODE_VOLUME_DOWN`) untuk turunkan volume 1 takuk seperti biasa.
  4. **Atas (▲ / HW 301):**
     * 100% dikecualikan daripada pintasan (`not intercepted`). Volume naik berfungsi secara native kilang tanpa sebarang runaway repeat.

---

## 🛡️ 3. MOD SERVIS PROTON (STEALTH KILANG) (`service_mode.sh`)
* **Lokasi IHU:** `/data/local/window/service_mode.sh` (chmod 755)
* **Fail Bendera:** `/data/local/window/SERVICE_MODE_ACTIVE`
* **Trigger Fizikal:** Tekan dan hold butang **BAWAH** di steering wheel selama **5 saat**.
* **Trigger Baris Arahan:** `sh /data/local/window/service_mode.sh [on|off|toggle|status]`
* **Tindakan Semasa Diaktifkan (`on`):**
  1. Matikan door pressure relief daemon (`pkill -f DoorPressureReliefDaemon`) dan pastikan tingkap rapat.
  2. Matikan door chime / salam daemon (`pkill -f DoorChime`).
  3. Pulihkan lock sound kepada nada asal kilang Proton/Geely (`/data/local/tmp/stock_lock.wav` ➡️ `/sdcard/V3_Vehicle_Locked_Single.wav`).
  4. Buka unmount custom AVAS sound dan kembalikan kepada AVAS factory mode 2.
  5. Sembunyikan semua sideloaded apps (`SmartTube`, `Netflix`, `com.noor.thenoorcar`, `AcBridge`, dll) menggunakan `pm disable-user --user 0 <pkg>`.
  6. Mainkan audio pengesahan chime dua kali (`tinyplay mode_chime.wav`).
  7. Kereta berada dalam keadaan 100% standard kilang (*Showroom Condition*), selamat untuk dihantar ke pusat servis Proton SC.
* **Tindakan Semasa Dimatikan (`off`):**
  1. Hidupkan semula semua aplikasi sideloaded melalui `pm enable <pkg>`.
  2. Hidupkan semula door pressure relief daemon dan door chime daemon.
  3. Pulihkan nada kunci custom (Mario) dan AVAS custom.
  4. Mainkan audio pengesahan chime satu kali.

---

## 📢 4. PENYEPADUAN MEGAFON 0ms DALAM IHU (`EmasEnergy`)
* **Pakej IHU:** `com.noor.thenoorcar` (`NoorEnergy_Megafon.apk`, bersaiz 435 KB, ditandatangani dengan `debug.ks`).
* **Modul UI:** `assets/megafon.js`, `assets/companion.css`, `assets/app.js`, `assets/index.html`.
* **Sambungan Daemon:** Berkomunikasi terus dengan `MegaphoneDaemon` pada port `127.0.0.1:8890` (0ms latensi perkakasan ALSA `pcmC0D29p`).
* **Fungsi Utama Tab Megafon:**
  1. **Preset Suara Segera (1-Tap):**
     * 📢 `alihkan` &rarr; "Tolong alihkan kenderaan anda" (`alihkan.wav`)
     * 🕌 `salam` &rarr; "Assalamualaikum" (`salam.wav`)
     * 🚨 `tepi` &rarr; "Minta laluan ke tepi" (`tepi.wav`)
     * ⏳ `datang` &rarr; "Saya datang sekejap" (`datang.wav`)
  2. **Push-to-Talk (Live Mic):**
     * Menggunakan Web Audio API dan MediaRecorder dengan penukaran automatik kepada 16-bit PCM WAV (Mono 44.1kHz).
     * Disiarkan secara terus melalui `POST /api/megafon/upload` ke pembesar suara luar bumper.
  3. **Audio Kabin:**
     * 🤲 Mainkan Doa Naik Kenderaan dalam kabin melalui `GET /api/doa/play`.
  4. **Fallback Native Android:**
     * Disokong oleh kaedah JavascriptInterface `@JavascriptInterface public void playMegafonPreset(String)` dan `playDoa()` serta sokongan kebenaran mikrofon automatik melalui `WebChromeClient.onPermissionRequest`.

---

## 📱 5. PHONE APK v1.4 & WEB APP VOICE PRESETS
* **URL Hosting APK:** `https://emas.43-159-63-29.nip.io/static/apk/eMAS_Remote.apk`
* **Penetapan Cache:** `LOAD_NO_CACHE` dan `clearCache(true)` diaktifkan untuk mengelakkan stale webview cache.
* **Ciri Baharu:**
  * Ikon tetapan gear (⚙️) pada setiap slot preset untuk menamakan semula tajuk dan merakam suara tersuai pemandu.
  * API backend: `GET /api/megafon/presets` dan `POST /api/megafon/presets/<id>` dengan ffmpeg anti-pop normalization (`-map_metadata -1 -fflags +bitexact`).
  * Sokongan 4 slot lalai dengan kebolehan menambah slot baharu.

---

## 🧭 6. PANDUAN PENTING UNTUK SESI SETERUSNYA
1. **Butang Steering Wheel Bawah:** Menahan 5 saat mencetuskan Mod Servis. Jangan kurangkan threshold ke bawah 4 saat untuk mengelakkan salah trigger semasa menurunkan volume.
2. **Butang Steering Wheel Atas (301):** Jangan sesekali dimasukkan ke dalam senarai `requestKeysInterception` untuk mengelakkan isu pengulangan volume.
3. **Partition `/vendor`:** Kekal penuh 100%. Sebarang pengubahsuaian bunyi AVAS mesti melalui *bind-mount* di `/system/bin/avas_boot.sh`.
4. **Pakej IHU EmasEnergy:** Diterbitkan dan dipasang di bawah nama pakej `com.noor.thenoorcar` dengan kebenaran `RECORD_AUDIO` dan `usesCleartextTraffic="true"`.
