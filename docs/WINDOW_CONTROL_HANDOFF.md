# 🚗 WINDOW CONTROL — Handoff Notes (Geely/Proton ECARX IHU801P)

> **Tarikh**: 2026-09-23  
> **Status**: ✅ BERJAYA — Window control dari ADB/app_process confirmed working  
> **Kereta**: Proton e.MAS / Geely ECARX IHU801P (IHU629G variant), Android 10, rooted  
> **ADB IP**: `192.168.1.19:5555` (home Wi-Fi `AzamRahim_5G`)

---

## 1. RINGKASAN PENEMUAN

Window control berjaya melalui **direct VHAL vendor property write** ke `ICarProperty` binder service. Motor tingkap CONFIRMED bergerak — position sensor readback tracking tepat (0% → 49% → 0%).

### Tool yang berjaya:
- **File**: [`D:\apps\emas-ota\src_window\com\emas\window\WindowDirect.java`](file:///D:/apps/emas-ota/src_window/com/emas/window/WindowDirect.java)
- **JAR**: `D:\apps\emas-ota\window_direct.jar`
- **On-device**: `/data/local/tmp/window_direct.jar`

### Command format:
```bash
# Read all window positions
adb shell "export CLASSPATH=/data/local/tmp/window_direct.jar; app_process /data/local/tmp com.emas.window.WindowDirect read"

# Single window control (fl|fr|rl|rr) with percent (0=close, 10=crack, 50=half, 100=full)
adb shell "export CLASSPATH=/data/local/tmp/window_direct.jar; app_process /data/local/tmp com.emas.window.WindowDirect fl 10"

# All 4 windows at once (group command via byte[8] to prop 0x287000A2)
adb shell "export CLASSPATH=/data/local/tmp/window_direct.jar; app_process /data/local/tmp com.emas.window.WindowDirect all 10"
```

---

## 2. VENDOR PROPERTY MAP (OEM VHAL)

Semua props type **byte array** (`byte[]`, len=1 untuk single, len=8 untuk group).

### READ props (current position from BCM sensor):
| Window | Prop ID (hex) | Prop ID (dec) | Notes |
|--------|--------------|---------------|-------|
| FL (Driver) | `0x2870009D` | 678428829 | Reads current % position |
| FR (Passenger) | `0x2870009E` | 678428830 | Reads current % position |
| RL (Rear Left) | `0x2870009F` | 678428831 | Reads current % position |
| RR (Rear Right) | `0x287000A0` | 678428832 | Reads current % position |
| Sunroof | `0x2870009B` | 678428827 | 0xFF = not available |
| Rollshade | `0x2870009C` | 678428828 | |
| Rollshade A3 | `0x28700310` | 678429456 | |

### WRITE props (command to BCM):

> [!IMPORTANT]
> **FL/FR are SWAPPED in write vs read!** This is confirmed from OEM source code (`DoorAndWindowImpl.setWindowStatus`).

| Window | Write Prop ID (hex) | Write Prop ID (dec) | Notes |
|--------|-------------------|---------------------|-------|
| FL (window=16) | `0x2870009E` | 678428830 | ⚠️ Writes to "FR read prop" |
| FR (window=32) | `0x2870009D` | 678428829 | ⚠️ Writes to "FL read prop" |
| RL (window=256) | `0x2870009F` | 678428831 | Same as read |
| RR (window=512) | `0x287000A0` | 678428832 | Same as read |
| Group (all) | `0x287000A2` | 678428834 | byte[8]: idx 0=FL, 1=FR, 2=RL, 3=RR, 0xFF=no change |

### Values:
- `0` = fully closed
- `10` = crack/ventilation (~10%)
- `50` = half open
- `100` = fully open
- `112` (0x70) = stop rolling (sent to sunroof prop 0x2870009B)
- `0xFF` = no change (group command only)

### VehicleWindow bitmask constants (from AdapterAPI.jar):
```
WINDOW_ROW_1_LEFT  = 16  (0x10)  — FL / Driver
WINDOW_ROW_1_RIGHT = 32  (0x20)  — FR / Passenger
WINDOW_ROW_2_LEFT  = 256 (0x100) — RL
WINDOW_ROW_2_RIGHT = 512 (0x200) — RR
WINDOW_ROOF_TOP    = 4           — Sunroof
WINDOW_ROOF_ABAT_VENT = 8       — Rollshade
```

---

## 3. BINDER PATH (How it works)

```
app_process → ServiceManager.getService("car_service")
            → ICar.Stub.asInterface(binder)
            → icar.getCarService("property")  
            → ICarProperty.Stub.asInterface(propBinder)
            → propService.setProperty(CarPropertyValue)
            → VehicleHalManager → MCU/CAN → BCM → Window Motor
```

### CarPropertyValue construction:
```java
Class<?> cpvClass = Class.forName("android.car.hardware.CarPropertyValue");
// Constructor: CarPropertyValue(int propertyId, int areaId, T value)
Constructor<?> ctor = cpvClass.getConstructors()[0]; // 3-arg version
// For single window write:
Object cpv = ctor.newInstance(propId, 0, new byte[]{(byte) percent});
// For group write:
byte[] data = new byte[]{10, 10, 10, 10, -1, -1, -1, -1}; // all 4 windows to 10%
Object cpv = ctor.newInstance(678428834, 0, data);
```

**Area ID is always 0** for these vendor props (not the AOSP area bitmask).

---

## 4. DEAD ENDS (What DOESN'T work)

### ❌ AOSP WINDOW_POS (0x13400BC0 / 322964416)
- `Property.service: propId is not in config list:0x13400bc0`
- AOSP standard property NOT registered in Geely VHAL config
- Works ONLY from proper Android app context (e.g., `drive_assist.apk`) because the Geely Car framework adaptation layer translates AOSP→vendor props internally

### ❌ AdapterAPI / VehicleImpl
- `Vehicle.create(ctx)` → `VehicleImpl` constructor → `Car.connect()` → **SecurityException**: "Unable to find app for caller" when called from `app_process`
- Requires proper Android app context with registered UID in PackageManager
- NOT usable from `app_process` / shell

### ❌ drive_assist.apk CarPropertyManager path
- `drive_assist` uses `CarPropertyManager.setIntProperty(WINDOW_POS, area, value)` 
- This works because it runs as a real app with proper permissions
- The Geely adaptation layer in `car-frameworks-service.jar` translates AOSP property IDs to vendor `0x28700xxx` IDs
- Cannot replicate from `app_process` — `Car.createCar()` needs app context

### ⚠️ What we DID get working but doesn't move windows
- `ICarProperty.setProperty()` with `CarPropertyValue` — this **DOES update VHAL cache** but early tests seemed like it wasn't reaching MCU. HOWEVER, subsequent tests with longer wait times showed the window DID move (motor takes time to respond). The BSP layer logs `processWindowInfo` confirming the write reaches hardware.

---

## 5. OEM CODE REFERENCES

### DoorAndWindowImpl (decompiled from AdapterAPIImpl.jar)
- **File**: [`D:\apps\emas-ota\decompiled_api\AdapterAPIImpl\sources\com\malaysia\xui\adaptapi\vehicle\bcm\DoorAndWindowImpl.java`](file:///D:/apps/emas-ota/decompiled_api/AdapterAPIImpl/sources/com/malaysia/xui/adaptapi/vehicle/bcm/DoorAndWindowImpl.java)
- `setWindowStatus(int window, int percent)` — main window control method
- `genWindowGroupData(byte[] data, int index, int valve)` — builds byte[8] for group command
- `getWindowValue(int window)` — reads current position via `getIntByPropID`
- `setWindowRolling(boolean rollingContinue)` — sends 112 to stop rolling

### VehicleSignalManager
- **File**: [`D:\apps\emas-ota\decompiled_api\AdapterAPIImpl\sources\com\malaysia\xui\adaptapi\vehicle\modules\VehicleSignalManager.java`](file:///D:/apps/emas-ota/decompiled_api/AdapterAPIImpl/sources/com/malaysia/xui/adaptapi/vehicle/modules/VehicleSignalManager.java)
- `sendIntToMCUByPropID(propid, value)` → converts to `sendBytesToMcuByPropID(propid, 0, new byte[]{(byte)value})`
- `sendBytesToMcuByPropID(propid, zone, data)` → `CarPropertyManager.setProperty(byte[].class, propid, zone, data)`
- Confirms: all vendor props are **byte[] type**, not int

### drive_assist DoorWindow (working reference from real app)
- **File**: [`D:\apps\emas-ota\drive_assist_repo\drivemem\src\main\java\com\geely\drivemem\controls\DoorWindow.java`](file:///D:/apps/emas-ota/drive_assist_repo/drivemem/src/main/java/com/geely/drivemem/controls/DoorWindow.java)
- Uses AOSP `WINDOW_POS` (322964416) with areas 16/64/256/1024
- CRACK=10, SHUT=0
- Door-to-window area mapping: `{1→16, 4→64, 16→256, 64→1024}`

### Framework JARs (pulled from device):
- `D:\apps\emas-ota\AdapterAPI.jar` — interface definitions
- `D:\apps\emas-ota\AdapterAPIImpl.jar` — implementations (contains DoorAndWindowImpl)
- `D:\apps\emas-ota\ecarxSignalAPI.jar` — ECarXSignalManager
- `D:\apps\emas-ota\ecarx.jar`

---

## 6. BUILD TOOLCHAIN

```powershell
$JAVAC = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot\bin\javac.exe"
$JAVA  = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot\bin\java.exe"
$R8    = "D:\apps\emas-ota\r8.jar"
$AJ    = "D:\apps\emas-ota\android.jar"

# Compile
& $JAVAC -source 1.8 -target 1.8 -cp $AJ -d out WindowDirect.java

# DEX
& $JAVA -cp $R8 com.android.tools.r8.D8 --min-api 28 --lib $AJ --output . out/com/emas/window/WindowDirect.class

# Package
jar cf window_direct.jar -C . classes.dex

# Deploy & run
adb push window_direct.jar /data/local/tmp/
adb shell "export CLASSPATH=/data/local/tmp/window_direct.jar; app_process /data/local/tmp com.emas.window.WindowDirect fl 10"
```

> [!NOTE]
> Python environment on this laptop is **broken** (`ModuleNotFoundError: No module named 'encodings'`). Use Java tooling only.

---

## 7. CONFIRMED TEST RESULTS

| Time | Action | FL Read | FR Read | Physical / Cloud Sync |
|------|--------|---------|---------|-----------------------|
| 00:06:05 | read baseline | 0x00 | 0x00 | Closed |
| 00:06:37 | write FL 10% | 0x09 | 0x00 | - |
| 00:07:08 | write FL 0% | 0x00 | 0x00 | Closed back |
| 00:12:04 | write FL 50% | 0x0B→0x31 | 0x00 | Motor moving, reached 49% |
| 00:13:02 | write FL 0% | 0x30→0x00 | 0x00 | Motor closing, reached 0% |
| 17:23:08 | write FR 15% | 0x00 | 0x0E (14%) | ✅ **PHYSICAL OPEN & PROTON MOBILE APP SLIDER SYNCED 14%** |
| 17:26:44 | write FR 0% | 0x00 | 0x00 | ✅ Closed back to 0% |
| 17:44:00 | Door Event (FR) | 0x00 | 10% -> 0% | ✅ **DOOR PRESSURE RELIEF: Auto-cracked 10% on open, auto-closed 0% on shut!** |

> [!TIP]
> **DoorPressureReliefDaemon Status: ✅ RUNNING & PERSISTED**
> Daemon log: `/data/local/window/pressure_relief.log`
> Executable: `/data/local/window/door_pressure_relief.jar`
> Auto-boot entry: `/system/bin/avas_boot.sh`

---

## 8. WEB REMOTE / DASHBOARD ARCHITECTURE (KEPUTUSAN REKABENTUK)

> [!IMPORTANT]
> **Dasar Keselamatan Webapp: VIEW-ONLY (Read-Only Telemetry)**
> Untuk dashboard / PWA yang boleh diakses melalui rangkaian tempatan/hotspot, status tingkap adalah **PAPARAN SAHAJA (View Only)**.
> - **Tiada slider atau butang kawalan buka/tutup pada web UI** untuk mengelakkan penumpang atau orang lain membuka/menutup tingkap kereta sewenang-wenangnya.
> - Data ditarik melalui endpoint JSON:
>   ```bash
>   /data/local/tmp/window.sh json
>   # Output: {"fl":0,"fr":0,"rl":0,"rr":0,"status":"ok"}
>   ```
> - Kawalan fizikal (write) hanya boleh dibuat melalui arahan terus root/terminal ADB tempatan (`WindowDirect` / `window.sh`).

---

## 9. NEXT STEPS

1. **Integrate Window Telemetry View into Web Remote Dashboard** (View-only gauge / indicator).
2. **AVAS sound deployment** (v8_clean_44byte_header.wav + tuning3_verified_rumble.txt).
3. **Local HTTP Daemon / Bridge** on IHU for serving telemetry to the PWA.

