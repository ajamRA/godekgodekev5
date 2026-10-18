# PEMETAAN RASMI VHAL & VENDOR MCU HVAC — PROTON e.MAS 5 (IHU801P)

> [!IMPORTANT]
> **STATUS PENGESAHAN AUDIT: 9 OKTOBER 2026**
> Berdasarkan **HVAC Data Validation Audit**, terbukti bahawa **Standard AOSP VHAL (`0x15...`) hanyalah Mock Default Statik** (timestamp = 0, tidak berhubung dengan perkakasan fizikal kenderaan).
> **Semua telemetri dan kawalan perkakasan HVAC sebenar Proton e.MAS 5 dikendalikan melalui Vendor MCU Properties (`0x28...`)**.

---

## 1. Maklumat Perkakasan & Seni Bina
* **Model Kenderaan:** Proton e.MAS 5 (Geely E171 / Galaxy E5 variant).
* **Platform:** IHU801P, Android 10 (API 29), Flyme Auto CarService.
* **Format Nilai MCU:** `byte[]` array via `CarPropertyManager.getProperty(propId, 0)`.

---

## 2. Jadual Pemetaan Isyarat HVAC Sebenar (Verified Live Hardware)

| Fungsi / Komponen | Property ID (Hex) | Property ID (Dec) | Jenis Data | Formula / Struktur Nilai | Status Pengesahan |
|---|---|---|---|---|---|
| **Kelajuan Kipas (Fan Speed)** | `0x28700005` | `678428677` | `byte[]` | `[0]` (Mati/0) hingga `[8]` (Max 8 bar). | **Disahkan Mengikut OEM (Live)** |
| **Suhu Pemandu (Driver Setpoint)** | `0x28700007` | `678428679` | `byte[]` | $T = 17.0^\circ\text{C} + (\text{byte} \times 0.5)$<br>• `[0]` = 17.0°C (LO)<br>• `[8]` = 21.0°C<br>• `[30]` = 32.0°C (HI) | **Disahkan Mengikut OEM (Live)** |
| **Suhu Penumpang (Passenger Setpoint)** | `0x28700008` | `678428680` | `byte[]` | $T = 17.0^\circ\text{C} + (\text{byte} \times 0.5)$<br>• Bebas daripada suhu pemandu | **Disahkan Mengikut OEM (Live)** |
| **Status Kuasa HVAC (Power)** | `0x28700011` | `678428689` | `byte[]` | `[0]` = Tutup / OFF, `[1]` = Buka / ON | **Disahkan Mengikut OEM (Live)** |
| **Status Pemampat AC (A/C)** | `0x28700001` | `678428673` | `byte[]` | `[0]` = OFF, `[1]` = ON | **Disahkan Mengikut OEM (Live)** |
| **Peredaran Udara (Recirculation)** | `0x2870000E` | `678428686` | `byte[]` | `[0]` = Udara Luar (Fresh), `[1]` = Kitaran Dalam (Recirc) | **Disahkan Mengikut OEM (Live - TelemetryDaemon)** |
| **Arah Tiupan Udara (Air Mode)** | `0x28700006` | `678428678` | `byte[]` | Mode tiupan (Face, Foot, Defrost) | **Disahkan Mengikut OEM (Live)** |
| **Suhu Kabin Sebenar (Cabin Temp)** | `0x15600502` | `358614274` | Float | `NULL` / Tidak disokong pada perkakasan | **Tiada Sensor Hardware Melalui VHAL** |

---

## 3. Standard AOSP VHAL Properties (Mock Statik — JANGAN GUNA)

Sifat-sifat di bawah wujud dalam `CarPropertyService` tetapi **TIDAK DIHUBUNGKAN** kepada ECU/MCU kenderaan sebenar:
* `HVAC_FAN_SPEED` (`0x15400500` / `356517120`): Beku pada nilai `3` dengan timestamp `0`.
* `HVAC_TEMPERATURE_SET` (`0x15600503` / `358614275`): Beku pada `16.0°C` (Area 49) dan `20.0°C` (Area 68) dengan timestamp `0`.
* `HVAC_POWER_ON` / `HVAC_AC_ON`: Sentiasa memulangkan nilai statik permulaan.

---

## 4. Cara Penggunaan dalam Kod (`TelemetryDaemon`)

Untuk membaca data HVAC masa nyata dalam sebarang skrip atau daemon:

```java
// Contoh Java Reflection membaca Vendor MCU Property
CarPropertyValue<?> val = (CarPropertyValue<?>) getPropertyMethod.invoke(carPropertyService, 678428679, 0);
if (val != null && val.getValue() instanceof byte[]) {
    byte[] b = (byte[]) val.getValue();
    if (b.length > 0) {
        int raw = b[0] & 0xFF;
        double driverTemp = 17.0 + (raw * 0.5);
    }
}
```

---

## 5. Kesimpulan Automasi "Auto Fan"
Kerana tiada sensor suhu kabin sebenar (`HVAC_TEMPERATURE_CURRENT` adalah null) dan tiada butang fizikal "AUTO" pada Flyme Auto Proton e.MAS 5:
* Sebarang logik pintar masa depan mestilah berasaskan tetapan profil pemandu (contohnya: *Open-loop Comfort Curve* atau *Solar/Ambient timer*) dan bukan *Closed-loop Feedback Temperature Loop*.
