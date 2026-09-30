# SESSION STATE — 30 Sep 2026 (18:35)

Rekod status teknikal rasmi Proton e.MAS 5 (IHU801P) selepas pengemaskinian kokpit, sensor pintu/frunk/but, dan pembetulan gear TCU MCU.

---

## 🚗 1. KENDERAAN & SAMBUNGAN
* **Model:** Proton e.MAS 5 (Android 10, QP1A.190711.020 inc. 785).
* **ADB semasa:** `192.168.68.133:5555` (Wi-Fi semasa: `SKMKJ DEWAN TERBUKA`).
* **Skrin Kereta:** Aktif, aplikasi `com.noor.thenoorcar` (.MainActivity) di foreground.

---

## 📡 2. TELEMETRY DAEMON (`telemetry_daemon.jar`)
* **Lokasi:** `/data/local/window/telemetry_daemon.jar`
* **Pelancar:** `/data/local/window/start_telemetry.sh` (PID aktif: 27384).
* **Port HTTP:** `127.0.0.1:8888`
* **Sensor & Kawalan Dikenal Pasti & Aktif:**
  * **SOC Bateri:** `0x287001AF` (678429103)
  * **Voltan Pek:** `0x28700315` (678429461)
  * **Arus Pek:** `0x287001B0` (678429104)
  * **Kelajuan Sebenar (CAN MCU):** `0x287000A8` (678428840) — formula: `raw * 0.01 km/j`
  * **Gear TCU MCU Geely:** `0x287000EC` (678428908) — `2` = D (Drive), `3` = N, `4` = R (Reverse), `5` = P (Park)
  * **Sensor Pintu (FL, FR, RL, RR):** `678428815`, `678428816`, `678428817`, `678428818` (0 = Terbuka, 1 = Tutup)
  * **Sensor But Belakang (Trunk):** `678428819` (0 = Tutup, 1 = Terbuka)
  * **Sensor Bonet Depan (Frunk / Hood):** `678428820` (`0x28700094`, 0 = Tutup, 1 = Terbuka)
  * **Pelepas Kunci Bonet Depan (Frunk Actuator):** `678429406` (`0x287002DE`, `F_MMI_UNLOCK_HOOD`) — hantar `1`
  * **Motor But Belakang (Tailgate Actuator):** `678428745` (`0x28700049`, `PROP_TRUNK_ACTION_REQ`) — `1` = Buka, `2` = Tutup
  * **Sensor Tali Pinggang Keledar:** Pemandu `678429211`, Penumpang `678429212`
* **Endpoints HTTP:**
  * `GET /api/energy` — bacaan penuh telemetri, bateri, gear, kelajuan, pintu & bonet
  * `GET /api/status` — status liveness daemon
  * `GET /api/frunk/open` — buka kunci bonet depan (frunk)
  * `GET /api/trunk/toggle` — tukar keadaan buka/tutup but belakang (trunk)
  * `GET /api/trunk/open` & `GET /api/trunk/close` — kawalan terus but belakang

---

## 📱 3. APLIKASI KOKPIT (`NoorEnergy_clean.apk`)
* **Pakej:** `com.noor.thenoorcar`
* **Kemas Kini Antara Muka (UI):**
  1. **Tab Peta (Maps) & Mod Pemanduan (Drive Mode) dibuang**. Navigasi kini kemas dengan 5 tab: *HV Battery, Power Flow, Trip, Climate, Controls*.
  2. **Kad TPMS tidak tepat di Overview dibuang** untuk mengelakkan salah maklumat.
  3. **Ruang kosong diisi dengan Panel Pintu, Frunk & But (2x3 Grid)**:
     * Baris 1: Depan Kiri (FL) | Depan Kanan (FR)
     * Baris 2: Belakang Kiri (RL) | Belakang Kanan (RR)
     * Baris 3: Bonet Depan (FRUNK) | Bonet Belakang (TRUNK)
  4. **Interaktif / Boleh Diklik**:
     * Petak Frunk & Trunk bertindak sebagai butang tindakan pantas dengan lencana `[BUKA]` / `[TUTUP]` dan arahan sentuhan `👆 Tekan Buka`.
     * Menghantar arahan terus ke perkakasan melalui `vehicleFetch('/api/frunk/open')` dan `vehicleFetch('/api/trunk/toggle')`.
  5. **Isu Gear Stuck di P Diselesaikan**:
     * Status kenderaan kini mengikut kelajuan CAN MCU dan gear TCU MCU secara tepat (`DIPARKIR`, `SEDANG MEMANDU`, `SEDANG MENGUNDUR`, `BERHENTI`).

---

## 🛡️ 4. PEMBERSIHAN DAEMON LATAR BELAKANG
* **`DoorPressureReliefDaemon` lama:** Dinyahaktifkan sepenuhnya (`/data/local/window/start_pressure_relief.sh.disabled`) bagi mengelakkan cubaan memaksa gear atau mod pemanduan.
* **Proses berjalan bersih:** Hanya `TelemetryDaemon` (PID 27384) dan `MegaphoneDaemon` (PID 27414).

---

## 🧭 5. DOKUMEN TADBIR URUS & RUJUKAN
* [`ARAHAN_ASAS_EMAS5.md`](ARAHAN_ASAS_EMAS5.md) · [`SOP_PEMASANGAN_APK.md`](SOP_PEMASANGAN_APK.md) · [`SOP_DIAGNOSIS_MASALAH.md`](SOP_DIAGNOSIS_MASALAH.md) · [`KATALOG_TINDAKAN_STANDARD.md`](KATALOG_TINDAKAN_STANDARD.md)
