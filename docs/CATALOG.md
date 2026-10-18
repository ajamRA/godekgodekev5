# 🚗 Proton e.MAS 5 - Mod & Tooling Catalog

Katalog rasmi semua mod, automasi, dan daemon yang dibangunkan untuk sistem infotainmen (IHU) **Proton e.MAS 5** (Android 10 API 29).

---

## 📋 Senarai Penuh Mod (Mod Catalog)

| ID | Nama Mod | Fungsi & Shortcut | Target Lokasi | Status |
| :--- | :--- | :--- | :--- | :--- |
| **01** | **Steering Shortcuts** | **Double-Click ▶**: Cycle Drive Mode (*Comfort ➔ Sport ➔ Eco*)<br>**Double-Click ◀**: Cycle Regen (*Low ➔ Med ➔ High*) | `/data/local/window/` | 🟢 **Ready** |
| **02** | **Pressure Relief** | Auto turunkan cermin 10mm bila buka pintu & tutup balik bila pintu rapat (hilangkan rasa kedap telinga). | `/data/local/window/` | 🟢 **Ready** |
| **03** | **Cabin Salam & Doa** | Bunyi *"Assalamu'alaikum"* & Doa Naik Kenderaan automatik dalam kabin bila kereta dihidupkan. | `/data/local/window/salam/` | 🟢 **Ready** |
| **04** | **Gear 'D' Audio** | Peringatan audio automatik apabila pemandu shift ke gear Drive (D). | `/data/local/window/gear_audio/` | 🟢 **Ready** |
| **05** | **Door Chime** | Nada mewah berbunyi apabila mana-mana pintu dibuka / ditutup. | `/data/local/window/` | 🟢 **Ready** |
| **06** | **Telemetry Daemon** | Siarkan data kelajuan, bateri, dan gear kenderaan ke web dashboard / HUD secara live. | `/data/local/window/` | 🟢 **Ready** |
| **07** | **AVAS Speaker Sync** | Kawal & sync speaker luar kenderaan (Pedestrian Warning Sound) dengan sistem skrin. | `/data/local/avas/` | 🟢 **Ready** |
| **08** | **Universal APK Sideload** | Pasang sebarang fail `.apk` (YouTube, Waze, Spotify, dll) terus ke skrin kereta. | `/data/app/` | 🟢 **Ready** |

---

## 🛠️ Cara Penggunaan Pantas

1. Sambungkan laptop ke **WiFi Kereta** atau hotspot bersama.
2. Buka folder `D:\apps\emas-ota`.
3. **Double-click** pada:
   ```
   EmasModManager.bat
   ```
4. Tekan **"1. Scan & Connect ADB"**.
5. Pilih mod yang dikehendaki dari dropdown list, kemudian tekan **"2. Install Selected Mod"**.
6. Untuk pasang aplikasi luar, tekan **"📦 Sideload Any APK File"** dan pilih fail `.apk` yang ingin dipasang.

---

## 📁 Struktur Fail Utama

* `EmasModManager.bat` / `EmasModManager.ps1` - GUI Control Panel rasmi.
* `MODS_CATALOG.json` - Pangkalan data konfigurasi & laluan fail mod.
* `avas_boot.sh` - Skrip master boot loader yang memulakan semua mod secara automatik ketika enjin dihidupkan.
* `src_steering_mode/` - Kod sumber Java untuk Steering Mode Controller.
