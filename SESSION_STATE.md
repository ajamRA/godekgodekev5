# SESSION STATE — 3 Oktober 2026 (12:20)

Rekod status teknikal rasmi Proton e.MAS 5 (IHU801P) selepas pengemaskinian Megaphone Daemon v1.3 (Edge Repair anti-boom), Trigger Audio Gear D (Mario Coin), Trigger Doa Buka Pintu Kabin, dan Pelaksanaan 1-Click Stealth Service Mode Proton SC.

---

## 🚗 1. KENDERAAN & SAMBUNGAN
* **Model Kenderaan:** Strictly **Proton e.MAS 5** (IHU801P, Android 10 API 29, root access).
* **IP ADB Semasa:** `192.168.1.19:5555` (DHCP Wi-Fi: `AzamRahim`, MAC: `0e-5c-fb-05-3d-56`).
* **PC Local IP:** `192.168.1.16`.
* **VPS Sync Server:** `https://emas.43-159-63-29.nip.io` (API Key: `azam7988` via `X-API-Key`).
* **SSH VPS:** `ubuntu@43.159.63.29` (`/home/ubuntu/emas5/`).

---

## 📢 2. MEGAPHONE DAEMON v1.3 (`megaphone_daemon.jar`)
* **Lokasi IHU:** `/data/local/window/megaphone_daemon.jar` (SHA-256: `8b57b7bdd23077cc5423ac4641669c47cfefaa13a4f74b30a82099f0c2fe3040`).
* **Pelancar Boot:** `/data/local/window/start_megaphone.sh` (Auto-start via `/system/bin/avas_boot.sh` Step 5).
* **Port HTTP:** Terkunci selamat pada `127.0.0.1:8890` (Localhost only).
* **Ciri Pembaikan Utama (Anti-Boom & Concurrency):**
  1. **Queue LinkedBlockingQueue (Cap 5):** Setiap item masuk baris gilir dengan snapshot fail unik `.queue_N.wav` &mdash; fail asal `current.wav` mustahil ditimpa semasa audio lain sedang bermain.
  2. **Edge Repair Automatik (Trim & Fade):** Memotong senyap awal/akhir (threshold 600) + Fade-In 15ms (buang hentakan "boom/pop" speaker) + Fade-Out 40ms (kembali ke sifar dengan licin).
  3. **Atomic Download/Upload:** Guna fail sementara `.downloading` + semakan kesahihan WAV sebelum *atomic rename*.
  4. **Pencegahan Kehilangan Broadcast Cloud:** ID `lastPlayedMegaphoneId` hanya di-ACK selepas berjaya masuk queue.
  5. **Auto Handoff AVAS:** Memberhentikan `avas_service` semasa `tinyplay` ke `/dev/snd/pcmC0D29p`, dan menghidupkan semula `avas_service` serta-merta selepas selesai (exit=0).

---

## 🪙 3. TRIGGER AUDIO GEAR D (`GearAudioPoc.jar`)
* **Lokasi IHU:** `/data/local/window/gear_audio/GearAudioPoc.jar`.
* **Fail Audio:** `/data/local/window/gear_audio/gear_d.wav` (Mario Coin, 48kHz 16-bit mono).
* **Pelancar Boot:** `/data/local/window/start_gear_audio.sh` (Auto-start via `/system/bin/avas_boot.sh` Step 6).
* **Senibina & Logik Pengendalian:**
  * **VHAL Reading (Read-Only):** Membaca gear CAN MCU `678428908` (`2=D, 3=N, 4=R, 5=P`). Tiada sebarang `setProperty` (Zero risk).
  * **Speed Guard:** Membaca kelajuan MCU `678428840` (`raw / 100.0`). Hanya trigger jika kelajuan $\le$ 1.0 km/j dan kenderaan berhenti.
  * **Audio Engine:** Menggunakan `AudioTrack` pra-muat (preload byte array ke memori) dengan `USAGE_ASSISTANCE_SONIFICATION`. Memintas sekatan SELinux `drmserver` SoundPool dengan latensi trigger $\sim$2ms!
  * **One-Shot Per Boot (Ciri Utama):**
    * Hanya berbunyi sekali sahaja pada peralihan `P -> D` pertama selepas IHU boot/hidup.
    * Sebaik sahaja audio selesai, proses Java **tamat dan keluar (exit 0)** dengan sendiri.
    * 0% RAM & CPU sepanjang perjalanan selebihnya, dan mustahil berbunyi lagi sehingga IHU reboot.

---

## 🚪 4. TRIGGER DOA BUKA PINTU (`DoorDoaPoc.jar`)
* **Lokasi IHU:** `/data/local/tmp/DoorDoaPoc.jar`.
* **Sensor VHAL Pintu (RHD Malaysia):**
  * `PROP_DOOR_FR = 678428816` &rarr; Pintu Pemandu Kanan (RHD Malaysia)
  * `PROP_DOOR_FL = 678428815` &rarr; Pintu Penumpang Kiri
* **Tingkah Laku Dual Front Door (Zero Double Play):**
  * Memantau kedua-dua pintu hadapan sebagai satu kesatuan status (`CLOSED -> OPEN`).
  * Mana-mana satu pintu depan dibuka dahulu, doa naik kenderaan dimainkan serta-merta dalam kabin.
  * Fungsi playback berjalan secara *synchronous* (blocking), dan status kekal `OPEN`, menjamin **100% mustahil berlaku double play** walaupun pintu sebelah lagi dibuka beberapa saat kemudian.
  * Selepas audio tamat, proses tamat dengan sendirinya (`SESSION_DONE one-shot exit`).
* **Pengecualian:** Bonet belakang (`Trunk: 678428819`) dan Bonet depan (`Frunk: 678428820`) diabaikan sepenuhnya agar tidak mengganggu urusan memasukkan barang pasaraya.

---

## 🔊 5. SISTEM AVAS & NADA KUNCI (LOCK CHIME)
* **AVAS Pejalan Kaki Kilang:** 100% Asli Kilang dipulihkan (`commit 8a973e5`).
  * Slot 1: `yinheyinfu.wav` (Galaxy Chord)
  * Slot 2: `space.wav` (Space)
  * Slot 3: `pixel.wav` (Pixel)
  * Bunyi undur/perlahan: `bDM_01_01.wav` (Factory curve & tuning asal tanpa ubah suai).
* **Sistem Nada Kunci (Lock Chime):**
  * Dijalankan secara natif oleh servis sistem kilang `/system/bin/ecarx_reverse_radar` yang membaca `/sdcard/V3_Vehicle_Locked_Single.wav`.
  * Tiada daemon kustom diperlukan untuk lock sound asas kilang.

---

## 🛡️ 6. 1-CLICK PROTON SC STEALTH SERVICE MODE
Untuk menjaga waranti dan mengelakkan sebarang isu semasa pemeriksaan servis berkala di Proton Service Centre (SC):

### A. Skrip Masuk Mod Kilang (`sc_stealth_mode.ps1`):
* Menjalankan `pm disable-user --user 0` pada aplikasi *sideloaded*:
  1. `com.netflix.mediaclient` (Netflix)
  2. `org.smarttube.stable` (SmartTube)
  3. `com.noor.thenoorcar` (NoorEnergy)
  4. `com.cybercat.acbridge` (AcBridge)
  *(Ikon ghaib sepenuhnya daripada skrin IHU, data login & akaun kekal selamat).*
* Aplikasi rasmi ACO App Store seperti **Vivaldi Browser**, **Spotify**, **SYOK**, dan **HotFM** dikekalkan terlihat seperti biasa.
* Mewujudkan fail bendera `/data/local/window/.service_mode`.
* `/system/bin/avas_boot.sh` secara automatik menyekat permulaan semua daemon kustom (`GearAudio`, `Megaphone`, `Telemetry`).
* Semua daemon dimatikan, zero open ports &mdash; kereta 100% standard kilang (*Showroom Condition*).

### B. Skrip Pulihkan Semula (`sc_restore_mode.ps1`):
* Menjalankan `pm enable` untuk memunculkan semula Netflix, SmartTube, dan NoorEnergy di skrin.
* Memadam fail `.service_mode` dan memulakan semula daemon kustom dalam masa 3 saat.

---

## 🧭 7. PANDUAN UNTUK AI SETERUSNYA
1. **Jangan sesekali menukar VHAL Drive Mode secara gelung berterusan (Zero Drive Mode Clamping).**
2. **Jangan menulis fail sementara atau WAV ke direktori shell/tmp jika melibatkan AudioTrack/SoundPool**, kerana SELinux `drmserver` menyekat `shell_data_file`. Gunakan laluan `/data/local/window/gear_audio/`.
3. **Partition `/vendor` adalah 100% penuh (0 bytes free)**. Sebarang penukaran fail AVAS mestilah menggunakan kaedah *bind-mount* melalui `/system/bin/avas_boot.sh`.
4. **Semua skrip pelancar daemon diletakkan di `/data/local/window/`** dan dipautkan ke `/system/bin/avas_boot.sh`.
