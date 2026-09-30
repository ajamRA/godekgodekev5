# HVAC VHAL MAPPING (PROTON e.MAS 5)

JANGAN BEDAH APK. JANGAN PROBE. JANGAN JADX. JANGAN INPUT TAP/SWIPE. JANGAN SENTUH ECARX.HVAC.APP. JANGAN BUKA TINGKAP.

| Kawalan | Property (Hex) | Property (Dec) | Kawasan (Area ID) | Jenis |
|---|---|---|---|---|
| Suhu pemandu | 0x15600503 | 358614275 | 49 | float, °C |
| Suhu penumpang | 0x15600503 | 358614275 | 68 | float, °C |
| Blower | 0x15400500 | 356517120 | 117 | int |
| AC on | 0x15200505 | 354419973 | 117 | boolean |

* Kereta IP: 192.168.1.8:5555
* UI (`energy-web/app.js`): Panggil `/api/hvac`
* Daemon Backend: Tulis & baca guna property dan kawasan di atas melalui VHAL (`CarPropertyService`).
