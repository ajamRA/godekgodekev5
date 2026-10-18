# Panduan Pembangunan & Senibina: Emas Wireless Game Hub
### Sistem Penstriman Konsol Nintendo Switch Tanpa Wayar (vivo ➡️ Proton e.MAS 5)

Projek ini dibina khas untuk membolehkan telefon pintar **vivo X100 Pro / X300 Pro** (dikuasakan oleh MediaTek Dimensity 9300/9400) menstrim permainan bertaraf konsol moden (**Nintendo Switch melalui Sudachi / Yuzu NCE**) terus ke skrin besar **Proton e.MAS 5 (IHU801P)** secara wayarles pada kadar **60 FPS ultra-low latency**.

---

## 🏗️ Senibina Sistem (System Architecture)

```text
┌────────────────────────────────────────────────────────┐
│             TELEFON (vivo X100 / X300 Pro)             │
│                     [ENJIN SERVER]                     │
├────────────────────────────────────────────────────────┤
│ 1. BluetoothCarReceiver                                │
│    - Mengesan 'Proton e.MAS 5' / 'e.MAS 5' bersambung  │
│ 2. GameStreamService (Foreground Service)              │
│    - MediaProjection (Tangkap paparan game 1080p 60fps)│
│    - MediaCodec H.264 Hardware Encoder (Cip Dimensity) │
│    - TCP Socket Server (Port 8889) ───[Video Stream]───┼──┐ (Wi-Fi 5GHz)
│ 3. InputReceiverThread                                 │  │
│    - UDP Socket (Port 8890) ◄──────[Input 8BitDo]──────┼──┤
└────────────────────────────────────────────────────────┘  │
                                                            │
                                                            ▼
┌────────────────────────────────────────────────────────┐  │
│              KERETA (Proton e.MAS 5 IHU)               │  │
│                    [PAPARAN CLIENT]                    │  │
├────────────────────────────────────────────────────────┤  │
│ 1. FullscreenPlayerActivity (SurfaceView)              │  │
│    - Sambung ke vivo melalui TCP Socket (Port 8889) ◄──┼──┘
│    - MediaCodec H.264 Hardware Decoder (Cip MT8666)    │
│    - Paparan Imersif Skrin Penuh 1080p 60 FPS          │
│ 2. ControllerInputSender                               │
│    - Baca isyarat dongle 8BitDo 2 dari port USB kereta │
│    - Paketkan ke format bitmask padat (16 bytes)       │
│    - Pancarkan semula ke vivo melalui UDP (Port 8890)──┼──► (Sub-1ms lag)
└────────────────────────────────────────────────────────┘
```

---

## 📦 Struktur Kod Projek (`D:\apps\emas-ota\EmasWirelessGameHub`)

* **`app-vivo-server/`** (Aplikasi untuk telefon vivo):
  * [`MainActivity.kt`](file:///D:/apps/emas-ota/EmasWirelessGameHub/app-vivo-server/src/main/java/com/emas/gameserver/MainActivity.kt) – Antaramuka Cockpit Gelap, status Wi-Fi, dan pelancar pantas Sudachi.
  * [`BluetoothCarReceiver.kt`](file:///D:/apps/emas-ota/EmasWirelessGameHub/app-vivo-server/src/main/java/com/emas/gameserver/BluetoothCarReceiver.kt) – Pengesan automatik sambungan Bluetooth Proton e.MAS 5.
  * [`ScreenEncoder.kt`](file:///D:/apps/emas-ota/EmasWirelessGameHub/app-vivo-server/src/main/java/com/emas/gameserver/ScreenEncoder.kt) – Enjin pengkod perkakasan H.264 60 FPS.
  * [`GameStreamService.kt`](file:///D:/apps/emas-ota/EmasWirelessGameHub/app-vivo-server/src/main/java/com/emas/gameserver/GameStreamService.kt) – Servis latar depan penstriman video & penerima kawalan UDP.

* **`app-emas-client/`** (Aplikasi untuk IHU Proton e.MAS 5):
  * [`FullscreenPlayerActivity.kt`](file:///D:/apps/emas-ota/EmasWirelessGameHub/app-emas-client/src/main/java/com/emas/gameclient/FullscreenPlayerActivity.kt) – Paparan skrin penuh tanpa bar navigasi.
  * [`VideoDecoder.kt`](file:///D:/apps/emas-ota/EmasWirelessGameHub/app-emas-client/src/main/java/com/emas/gameclient/VideoDecoder.kt) – Penyahkod video perkakasan MediaTek MT8666 (kelewatan 3-5ms).
  * [`ControllerInputSender.kt`](file:///D:/apps/emas-ota/EmasWirelessGameHub/app-emas-client/src/main/java/com/emas/gameclient/ControllerInputSender.kt) – Pembaca input dongle 8BitDo 2 & pemancar paket UDP pantas.

---

## 🛠️ Cara Membina Projek (Build di Android Studio)

1. Buka perisian **Android Studio**.
2. Pilih **Open Project** dan halakan ke folder:
   `D:\apps\emas-ota\EmasWirelessGameHub`
3. Tunggu proses *Gradle Sync* selesai.
4. **Bina APK Server:**
   * Pilih modul **`app-vivo-server`** ➡️ **Build APK**.
   * Pasang APK hasil binaan ke telefon **vivo X100 Pro / X300 Pro**.
5. **Bina APK Client:**
   * Pilih modul **`app-emas-client`** ➡️ **Build APK**.
   * Pasang ke IHU Proton e.MAS 5 menggunakan arahan ADB:
     ```bash
     adb connect <IP_KERETA>:5555
     adb install -r app-emas-client-debug.apk
     ```

---

## 🚀 Cara Penggunaan Harian di Dalam Kereta

1. **Masuk ke dalam Proton e.MAS 5** bersama telefon vivo anda.
2. Bluetooth kereta dan telefon akan bersambung secara automatik.
3. Sambungkan Wi-Fi antara telefon vivo dan e.MAS 5 (Hotspot).
4. Tekan **MULAKAN PENSTRIMAN** di vivo (atau buka terus game Nintendo Switch Sudachi).
5. Di skrin Proton e.MAS 5, buka aplikasi **e.MAS GameClient**:
   * Visual game Switch anda akan terpapar 60 FPS di skrin kereta!
   * Ambil controller yang bersambung ke dongle 8BitDo 2 pada USB kereta dan mula bermain!
