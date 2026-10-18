# HVAC VHAL & BACKEND MAPPING (PROTON e.MAS 5)

> [!NOTE]
> Standard AOSP VHAL properties (0x15...) adalah mock statik pada platform IHU801P.
> Pengendalian HVAC sebenar diuruskan melalui Vendor MCU properties (0x28...) dan didedahkan melalui route khusus di loopback port 8888.

| Kawalan | Vendor Property (Hex) | Property ID (Dec) | Jenis & Julat | Route HTTP Loopback (Port 8888) |
|---|---|---|---|---|
| Suhu pemandu | `0x28700007` | 678428679 | `byte[]`, 17.0°C–32.0°C | `GET /api/climate/temp?target=<17.0-32.0>` |
| Suhu penumpang | `0x28700008` | 678428680 | `byte[]`, 17.0°C–32.0°C | Dilaraskan melalui ECU |
| Kelajuan blower | `0x28700005` | 678428677 | `byte[]`, 1–8 | `GET /api/climate/fan?level=<1-8>` |
| Kuasa HVAC (Power) | `0x28700011` | 678428689 | `byte[]`, 0=OFF, 1=ON | `GET /api/climate/power?state=<01>` |
| Pemampat AC | `0x28700001` | 678428673 | `byte[]`, 0=OFF, 1=ON | `GET /api/climate/ac?state=<01>` |
| Peredaran udara | `0x2870000E` | 678428686 | `byte[]`, 0=Fresh, 1=Recirc | `GET /api/climate/recirc?state=<01>` |

* Frontend UI (`EmasEnergyApp/assets/app.js`): Menggunakan `vehicleRequest()` ke `/api/climate/temp?target=` dan `/api/climate/fan?level=`. Route lama `/api/hvac` telah dimansuhkan.
* Backend Daemon (`TelemetryDaemon.java`): Menerima arahan melalui handler loopback dan menulis terus ke `CarPropertyService` menggunakan Vendor MCU Property IDs di atas.
