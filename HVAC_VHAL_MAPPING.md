# MEMORI TEKNIKAL & MAPPING VHAL / HVAC (ARKIB SEJARAH)

> [!CAUTION]
> **DOKUMEN ARKIB SEJARAH (HISTORICAL REFERENCE ONLY)**
> Dokumen ini mengandungi catatan IP lama (192.168.1.8) dan eksperimen dual-zone lama.
> **DILARANG MENGGUNAKAN IP ATAU NILAI DARIPADA FAIL INI UNTUK OPERASI SEMASA.**
> Untuk maklumat dan SOP semasa, rujuk [**`README.md`**](README.md) dan [**`ARAHAN_ASAS_EMAS5.md`**](ARAHAN_ASAS_EMAS5.md).

---


## 1. Maklumat Perkakasan & Sambungan
* **IP Kereta:** `192.168.1.8:5555` (Kekal, JANGAN tukar ke IP lain seperti `.11` atau `.18`).
* **Platform:** Android Automotive OS (IHU801P / Geely E171 / Proton e.MAS 5).
* **Akses:** Root shell aktif melalui ADB.

---

## 2. Pemetaan Sah (Verified) HVAC & VHAL
Data ini telah **disahkan secara langsung** melalui CarPropertyService & VHAL:

| Komponen / Fungsi | Area ID (Kawasan) | Property / Jenis | Julat / Nilai Sah | Catatan |
|---|---|---|---|---|
| **Suhu Pemandu (Kiri)** | `49` (0x31) | Float (`0x15600503` / `358614275`) | `16.0°C` – `32.0°C` | Bitmask pemandu. Disahkan membaca `16.0°C` / `22.0°C`. |
| **Suhu Penumpang (Kanan)**| `68` (0x44) | Float (`0x15600503` / `358614275`) | `16.0°C` – `32.0°C` | Bitmask penumpang. Disahkan membaca `20.0°C`. |
| **Kelajuan Kipas (Blower)**| `117` (0x75)| Int32 (`0x15400500` / `356517120`) | Tahap `1` – `8` | Disahkan membaca Tahap `3`. |
| **AC Power & Kompresor** | Standard | Boolean (`0x15200501`) | `true` (Hidup) / `false` | Disahkan status aktif. |
| **MCU Direct Property** | - | `678428679` (`0x28700007`) | Raw MCU Value | Property dalaman sistem Geely. |

---

## 3. Pantang Larang Keras (STRICT DO NOTS)
Agent **DIHARAMKAN** melakukan perkara berikut:
1. **JANGAN JALANKAN JADX / DECOMPILE APK**:
   * Tiada JadxCLI dipasang, dan reverse engineering APK OEM (`IHvac.java`, `ecarx.hvac.app`) sudah tidak diperlukan kerana VHAL telah ditemui.
2. **JANGAN GANGGU / BUNUH SERVIS OEM (`ecarx.hvac.app`)**:
   * Aplikasi dan servis asal kereta mesti dibiarkan berjalan normal tanpa dimatikan.
3. **JANGAN BUAT PROBE BERULANG KALI**:
   * Data pada Jadual Seksyen 2 di atas sudah muktamad. Guna terus nilai ini dalam kod backend/API.
4. **JANGAN GUNA `adb input tap` / `swipe` UNTUK KAWAL AIRCOND**:
   * Jangan cuba tekan skrin fizikal kereta atau buat automasi sentuhan untuk ubah suhu.
5. **JANGAN SENTUH / PROBE TINGKAP (HEAT VENT / WINDOWS)**:
   * Dilarang membuka atau mengusik tingkap kenderaan.

---

## 4. Cara Interaksi Standard (Untuk Backend / Skrip)
Untuk membaca atau menulis suhu tanpa menyentuh UI OEM:
* **Membaca:** Panggil `CarPropertyService.getProperty(358614275, 49)` untuk suhu pemandu.
* **Menulis:** Panggil `CarPropertyService.setProperty(new CarPropertyValue(358614275, 49, targetTempFloat))`.
