# e.MAS 5 Head Unit Research 🔧🚗

> Reverse-engineering notes for the **Proton e.MAS 5** (Geely EX2 platform, Malaysia) infotainment head unit.
> Goal: enable ADB / developer access, understand the A-Store install pipeline, and eventually record the 360° (AVM) surround cameras.

> ⚠️ **Disclaimer**: For educational/research purposes only. Use at your own risk — modifying vehicle electronics may void warranty and can brick hardware. Do not perform while driving.

---

## Vehicle / unit identification

| Item | Value |
|---|---|
| Head unit | **IHU801P** (MediaTek `alps` board — *NOT* the Brazil EX2's IHU629G) |
| OS | ATLAS OS (ECARX) over Android 10 (QP1A.190711.020) |
| Build | `SWE22HR0807H0AEJ.00785` (incremental `785`, **test-keys**) |
| Security patch | 2021-09-05 |
| OTA project code | `SX11RA` (upgrade.conf), P-CODE `ME3FERD` |
| Backend | `hu-atlas.acotech.my` (AcoTech Malaysia), OTA files on Huawei OBS `proton-file.obs.my-kualalumpur-1.alphaedge.tmone.com.my` |

Key properties (from `properties` dump / build.props):

```
ro.build.flavor=IHU801P-user
ro.adb.secure=1          # adb requires RSA auth (user build)
ro.debuggable=0
init.svc.adbd=stopped    # adbd not running by default
sys.usb.config=mtp       # USB enumerates as MTP (iAP2+UAC2 persisted) → PC sees no ADB
persist.sys.usb.config=iAP2,uac2
```

## 1. Getting a log dump (no root needed)

The unit ships with `com.debug.loggerui` (MTK logger). Logs accumulate under:

```
/storage/emulated/0/debuglogger/mobilelog/APLog_<date>__NNN/
```

Each folder contains `main_log`, `sys_log`, `events_log`, `kernel_log`, `radio_log`, `properties`, etc.
Copy to a USB stick from the Debug Tools app (**COPY IHU LOG**) or read from `/sdcard/debuglogger` via any file manager.

The `properties` file alone identifies the build, VIN, IMEI, IHUID, USB state, etc.

## 2. The secret password (About IHU → long-press software version)

`Settings → My Vehicle → About IHU` → long-press the software-version row → password dialog.

Algorithm (decompiled `ecarx.settings.utils.HuPasswordUtils` + `AboutIHUDialog`):

```java
input = createSystemDate() + IHUID + "universal168"
md5   = hex(MD5(input))                    // lowercase hex, 32 chars
big   = new BigInteger(md5.toUpperCase())  // hex → huge decimal number
rev   = reverse(big.toString())
odd   = chars of rev at index 0,2,4,...    // every 2nd digit from start
code  = first 6 digits of odd
```

* `createSystemDate()` = `yyyyMMddHHmm` in **GMT+8**, with the **minute tail snapped** into a 5-minute window (`0-4 → 0`, `5-9 → 5`). So 12:53 and 12:57 produce **different slots** (`...1250` vs `...1255`).
* `IHUID` = shown on the same About screen (e.g. `021600000000000000000000`).
* The dialog accepts **3 possible codes**:

| Code | Condition |
|---|---|
| `BX9527` | only while `settings.global engineer_local != "disable"` (disabled on MY units) |
| `yyyyMMdd` + `"aco"` | same condition (e.g. `20260903aco`) |
| the 6-digit rolling code | **always accepted** ← this is the one |

The dialog then launches `ecarx.debugtools/ecarx.debugtools.tbox.tBoxActMain`.

Helper script included: **[`hu-password.ps1`](hu-password.ps1)**

```powershell
.\hu-password.ps1 -IhuId "021600000000000000000000"
```

(Prints the code valid for the current 5-minute window; GMT+8 via machine clock. Verify the IHU clock roughly matches, else use the IHU's current time.)

## 3. Debug Tools app (`ecarx.debugtools`)

Pre-installed in `/system/app/AcoXDebugTools_ACO`. System UID (`sharedUserId=android.uid.system`), so it can toggle secure settings directly.

Highlights (from decompiled `MainActivity` / `tBoxActMain`):

| Function | What it does |
|---|---|
| **ADB MODE** | `Settings.Secure adb_enabled 0/1` — turns on ADB (see §4) |
| CLEAR / COPY IHU LOG | manage the mobilelog folders |
| COPY / RECOVER **AVM LOG** | 360-camera (AVM) logs & data |
| IHU INSTALLATION ANGLE | camera mount-angle parameters used by AVM stitching |
| GET CAMERA TYPE / INPUT TYPE | camera hardware info |
| ENGINEERMODE | launches `com.mediatek.engineermode/.EngineerMode` (requires `development_settings_enabled=1`, else shows a notice dialog) |
| MTK LOGGER | MTK debug logger |
| USB UPDATE / MCU UPDATE / IPK UPDATE | firmware install from USB |
| SCREENSHOT | captures the HU screen |
| FACTORY RESET | ⚠️ obvious |

Per-button password gates inside the app (decompiled constants):

```
PASSWORD      = "95272046"               # ADB-mode button (if Build.TYPE != eng)
PASSWORD_AMAP = "369963"                 # AMAP log tools
enter recovery / mfg mode / switch slot /
prop editor / MCU downgrade: PASSWORD + current-minute (2 digits), e.g. 9527204653
```

## 4. Enabling ADB (current best path)

1. Enter Debug Tools (§2).
2. Press **ADB MODE**; confirm password `95272046` when prompted.
3. The adbd starts with `ro.adb.secure=1` — first connection from a PC pops the usual **RSA allow** dialog on screen. Accept it.
4. Connect over the network: pair the HU to your Wi-Fi (Debug Tools shows the IP; same LAN as your PC), then:

```
adb connect <HU_IP>:5555
```

*(TCP 5555 is the stock adbd port once `adb_enabled=1` on this platform. If wireless doesn't bind, use a USB-A↔USB-A cable — the unit enumerates adbd over the front USB-A port when ADB is on.)*

Once ADB is up you can `adb install` anything, read `/dev/video*` camera nodes, pull the AVM config, etc.

## 5. A-Store / Atlas backend (from logcat HTTP dumps)

Base: `https://hu-atlas.acotech.my/` with headers:

```
X-ENV-TYPE: production   X-SERVICEID: s1
X-OPERATORCODE: proton   X-P-CODE: ME3FERD
X-XDSN: <unit serial>    vin: <VIN>   huid: <IHUID>
Authorization: Bearer <JWT>
```

Endpoints observed: `/mall/goods/hu-list`, `/mall/order/package/download/url`, `/mall/order/application/md5`, `/tbox/fota/v2/queryUpgradeTask`, …
APK download URLs are **public OBS links** (signed, time-limited). Install goes through the store's `installApk4SysApi21` — a silent PackageInstaller session (system privilege).
The MD5-check request body is encrypted, so sideloading *through the store* needs more work; plain `adb install` (§4) is easier.

Full OTA captured: `SWE22HR0807H0AEJ.00785_1786335967000.zip` (1.93 GB, **A/B payload.bin signed with the public AOSP test key** `CN=Android android@android.com`). Partitions extracted with `payload-dumper-go` into [`parts/`](parts/) — system.img, vendor.img, product.img, boot.img, cam_vpu1-3.img, etc.

## 6. 360° camera (AVM) — target analysis

Found in system.img:

* `ecarx.avm.AvmServiceManger` + `ecarx.camera.calibration.CameraAvmService` (inside **XCCamera360.apk**)
* `system/lib64/libAvmAdapter.so`, `libAvmUtil.so` — native bridge to the `ecarx_avm` vendor service
* Broadcasts: `com.ecarx.action.avm.open` / `avm.close` / `avm.openclose`
* Assets: `system/media/avm_img/*` (stitching backgrounds, calibration overlays)
* Debug buttons: copy AVM log/data, mount-angle, camera-type queries

Next steps: dump the AVM service protocol from `libAvmAdapter.so`, enumerate `/dev/video*` under ADB, and prototype a recorder that taps the same buffers the AVM service consumes.

## Repo layout

```
hu-password.ps1      rolling 6-digit code generator (WLAN long-press)
tools/               jadx etc.
parts/               extracted OTA partitions (not committed — huge)
extract/apk/         interesting APKs pulled from system.img
decompile/Settings   jadx output — Settings (password dialog)
decompile/AcoXDebugTools  jadx output — debug tools app
```

## Credits / sources

* Brazil Geely EX2 community (geelyex2.blogspot.com) — original IHU629G unlock write-ups (different unit, but inspired the route)
* MTK EngineerMode / Android AOSP internals
* All analysis from captured IHU logs + OTA payloads of a Malaysian e.MAS 5.
