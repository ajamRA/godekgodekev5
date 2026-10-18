# TDashCam — Installation and User Manual

**App:** TDashCam (`com.t_repair.tdashcam`), version 2.00
**Target:** Geely head units (tested on IHU629G, Android 9 / API 28, 1920×1080 landscape)
**Bookmark this file:** `tdashcam/docs/USER_MANUAL.md`

---

## Table of contents

1. [What TDashCam does](#1-what-tdashcam-does)
2. [Requirements](#2-requirements)
3. [Building the APK](#3-building-the-apk)
4. [Installing on the head unit](#4-installing-on-the-head-unit)
5. [First start](#5-first-start)
6. [Preparing storage (USB stick or internal)](#6-preparing-storage-usb-stick-or-internal)
7. [The Video page](#7-the-video-page)
8. [Camera views and tap-to-zoom](#8-camera-views-and-tap-to-zoom)
9. [Settings reference](#9-settings-reference)
10. [Camera aim (pan and squeeze)](#10-camera-aim-pan-and-squeeze)
11. [Replay: list and player](#11-replay-list-and-player)
12. [Photos (snapshots)](#12-photos-snapshots)
13. [Files, folders and file names](#13-files-folders-and-file-names)
14. [Recording behaviour and safety](#14-recording-behaviour-and-safety)
15. [Protection (replay PIN)](#15-protection-replay-pin)
16. [Sharing a clip](#16-sharing-a-clip)
17. [Troubleshooting](#17-troubleshooting)
18. [Defaults, ranges and specifications](#18-defaults-ranges-and-specifications)
19. [Technical appendix](#19-technical-appendix)

---

## 1. What TDashCam does

TDashCam turns the head unit's four-camera EVS/AVM feed into a dashcam:

* Shows all four cameras live (2×2 grid by default) with the date, time and vehicle speed burned
  into the picture.
* Records continuously to a USB stick (or to internal storage) in 1/3/5/10/20-minute files, and
  deletes the oldest files automatically when the chosen storage limit is reached.
* Records the microphone too, if you turn it on.
* Replays, deletes and shares the recorded clips from inside the app.
* Keeps recording even when the app's screen is closed, and resumes by itself after a restart,
  a sleep/wake cycle or a USB stick change.
* Survives a hard power cut (ignition off): the file format is fragmented MP4, so only the last
  moment is lost instead of the whole clip.

The app is signed with the head unit's platform key, so it behaves like a built-in system
application (no user prompts, it can keep recording in the background and it autostarts with the
car system).

---

## 2. Requirements

| Item | Requirement |
| --- | --- |
| Head unit | Android 9 (API 28) or newer, four-camera EVS/AVM feed, platform-signed app support |
| Screen | Landscape, 1920×1080 on the tested unit |
| Storage | FAT32-formatted USB stick (recommended) or the unit's internal storage |
| Build machine | Windows with JDK 17 (Android Studio's JBR works) and the Android SDK |
| Unit access | ADB over Wi-Fi (port 5555) or over USB |

> **Note:** the app must be signed with the platform key of the head unit
> (`platform.pk8` / `keys/` in this project). A normal debug-signed APK will install but will not
> get the system-level behaviour the dashcam needs.

---

## 3. Building the APK

### 3.1 One-command build (recommended)

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd <repo>\tdashcam
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\build-platform-apk.ps1
```

The script:

1. Builds `:app:assembleRelease` (R8 enabled, code shrinking on).
2. Signs the result with the platform test key (`v1` + `v3`).
3. Writes the installable file to `tdashcam\dist\TDashCam-2.00-platform.apk`.

Optional flag:

```powershell
... -File .\tools\build-platform-apk.ps1 -WithTrace
```

`-WithTrace` passes `-PwithTrace`, which keeps the `DebugTrace` diagnostic log. Use it only when a
field problem needs to be investigated; normal builds strip the trace completely.

### 3.2 Build times and known hiccups

* Incremental build: about 1 minute. Clean build: about 3–6 minutes.
* The repository lives inside OneDrive, and Gradle occasionally fails with
  `java.nio.file.AccessDeniedException` or *"Unable to delete directory …"*. Fix:

  ```powershell
  cd <repo>\tdashcam
  .\gradlew.bat --stop
  Remove-Item -Recurse -Force app\build      # retry if it complains
  ```

  then rebuild.

---

## 4. Installing on the head unit

### 4.1 Connect over Wi-Fi (normal case)

Wireless ADB must already be enabled on the head unit (`adb tcpip 5555` was run once, or the ROM
enables it by default).

```powershell
$adb = "C:\Program Files\platform-tools\adb.exe"
& $adb kill-server
& $adb start-server
& $adb connect 10.242.77.95:5555      # use the unit's current IP
& $adb devices                        # must print "<ip>:5555   device"
```

If the connection is refused, wait for the unit to wake up and try again — on the tested unit the
first two or three attempts commonly fail because the unit is still booting.

### 4.2 Install

```powershell
& $adb -s 10.242.77.95:5555 install -r -d --no-streaming `
  "c:\<repo>\tdashcam\dist\TDashCam-2.00-platform.apk"
```

If that fails halfway with `device offline`, push first and install from the unit:

```powershell
& $adb -s 10.242.77.95:5555 push "c:\<repo>\tdashcam\dist\TDashCam-2.00-platform.apk" /data/local/tmp/t.apk
& $adb -s 10.242.77.95:5555 shell "sha256sum /data/local/tmp/t.apk"   # must match the local file
& $adb -s 10.242.77.95:5555 shell "pm install -r -d /data/local/tmp/t.apk"
```

`-r` reinstalls and keeps the app's settings; `-d` allows a version downgrade.

### 4.3 Verify

```powershell
& $adb -s 10.242.77.95:5555 shell "dumpsys package com.t_repair.tdashcam | grep lastUpdateTime"
```

The timestamp must be the time you just installed. Then launch it:

```powershell
& $adb -s 10.242.77.95:5555 shell "am start -n com.t_repair.tdashcam/.LivePreviewActivity"
& $adb -s 10.242.77.95:5555 shell "dumpsys window | grep mCurrentFocus"
```

`mCurrentFocus` should name `com.t_repair.tdashcam/…LivePreviewActivity`.

### 4.4 Updating and removing

* **Update:** run the install command again. Settings, storage choice and camera aim are preserved.
* **Remove:** `adb shell pm uninstall com.t_repair.tdashcam` (recorded clips are not deleted).
* **Reset everything:** `adb shell pm clear com.t_repair.tdashcam` — this removes the settings
  *and* the protection PIN, but leaves the clips on the stick.

---

## 5. First start

1. Insert a FAT32 USB stick (see the next chapter) or accept internal storage.
2. Start TDashCam from the car's app list, or let it autostart (see *Open after restart*).
3. The Video page opens, fills the whole screen and starts recording within a few seconds
   (the red **REC** indicator and the camera picture appear first, then the recording dot is
   already rolling if *Start recording automatically* is on).
4. Open **Settings → System & Storage → Storage place** and confirm the line reads
   `Saving on USB: <stick name>`.
5. Set the **Language** if you do not want English.

---

## 6. Preparing storage (USB stick or internal)

| Storage place | Where clips go | Notes |
| --- | --- | --- |
| **USB** (recommended) | `<USB>\Android\data\com.t_repair.tdashcam\files\DVR\` | Must be **FAT32**. Keeps the unit's own memory free. |
| **Internal** | `/sdcard/Android/data/com.t_repair.tdashcam/files/DVR/` | Always available, but wears the head unit's eMMC. |

* Format the stick as **FAT32** before first use. exFAT/NTFS sticks are detected but refused:
  Settings shows *"USB selected but formatted as …; format it as FAT32 to record."*

> **Why only FAT32?** The head unit's own storage layer decides which filesystems a stick can use.
> On the tested IHU629G the kernel exposes only `vfat` (no NTFS or exFAT driver) and Android's
> volume daemon is configured to mount USB as `vfat` only, so an NTFS or exFAT stick is never given
> a writable mount — no app can record to it, whatever its permissions. The ROM does ship
> `ntfs-3g` and `su`, so an installer could mount an NTFS stick by hand and point the recorder at
> that folder, but NTFS writes then go through a FUSE driver (slower) and an ignition-off cut can
> leave the volume needing a `chkdsk`, which is exactly what the fragmented-MP4 layout is designed
> to avoid. It is also unnecessary here: the largest file the app ever writes is about **1.8 GB**
> (20-minute clip at High quality), well under FAT32's 4 GB limit.
* In-progress recordings are staged in a `.recording` folder on the **same** volume and renamed
  into place when the file is finished, so a sudden power cut or stick removal cannot leave a
  half-written file behind.
* Photos are written to the same `DVR` folder.
* If the stick is removed while recording, the current file is closed safely (the app keeps the
  part it has) and the app switches to reporting *"USB storage unavailable"* until a stick is
  present again.

---

## 7. The Video page

```
┌───────────────────────────────────────────────────────────────┐
│ ● REC                                          date  time km/h │  HUD
│                                                               │
│            FRONT                     REAR                     │  2×2 camera
│                                                               │  grid
│            LEFT                     RIGHT                     │
│                                                               │
│  [USB 89.9 GB]                     TDashCam                   │
│                                Recorded by T-Repair            │
└───────────────────────────────────────────────────────────────┘
  rail (left edge):  camera · replay · settings · info · exit
                     + camera view · lock · record · snapshot
```

| Element | Meaning |
| --- | --- |
| **● REC** (top-left) | Recording is active. |
| **Date / time / speed** (top-right) | Burned into the video as well. |
| **Pane labels** (Front/Rear/Left/Right) | One label per camera, plus thin divider lines. |
| **USB chip** (bottom-left) | Free space on the recording volume. |
| **TDashCam / watermark** (bottom-right) | Brand line and your own watermark text; both are burned into the recording. |
| **Rail** (left edge) | Video, Replay, Settings, About, **Exit**, then the action buttons. |

### 7.1 Rail buttons

| Button | Action |
| --- | --- |
| Camera | Show the Video page |
| Replay | Show the Replay page |
| Gear | Show Settings |
| Info | Show the About page |
| **Exit** (door + arrow, above the divider) | Close the dashboard. **Recording keeps running** — the recorder lives in a background service. |
| Camera view (grid icon) | Pick a camera arrangement (see chapter 8) |
| Lock | Reserved for an emergency lock of the running file. In this build it answers *"Emergency lock is not wired to the recorder yet"* — nothing is locked. |
| Record (red dot) | Start / stop recording manually |
| Snapshot (camera) | Save a still photo of the current view |

The whole app runs **full screen** (immersive, no Android navigation bar). The rail sits on the
side chosen with *Menu side*.

---

## 8. Camera views and tap-to-zoom

Available in **Settings → Display & Camera → Camera view**, or from the *camera view* button in the
rail:

| View | What it shows |
| --- | --- |
| **2×2** | All four cameras in equal panes (default) |
| **Main + Right Column** | One large camera with the other three stacked on the right |
| **Main + Left Column** | One large camera with the other three stacked on the left |
| **Main + Bottom Strip** | One large camera with three small tiles along the bottom |
| **Main + Top Strip** | One large camera with three small tiles along the top |

For the four "main +" views you also choose which camera is the main one: open the dialog again and
tap that camera in the dialog's own preview, then press **Save**.

**Tap to zoom (Video page):** tap any pane to blow that camera up to the chosen "main" position.
A **Save** button appears next to the rail. Press **Save** to make that view permanent, or press
the system **BACK** key to return to the previous view.

---

## 9. Settings reference

Settings are grouped in three tabs. Rows that change a value are normally protected by the child
lock when *Protection* is on — the exceptions are **Camera zoom** and the four **Camera aim** rows,
which apply immediately without a PIN (see chapter 15).

### 9.1 🎥 Recording

| Row | Options | Default | Effect |
| --- | --- | --- | --- |
| **Open after restart** | On / Off | **On** | Launch TDashCam automatically when the car system boots. Turning it off also stops the automatic restarts after a sleep/wake. |
| **Start recording automatically** | On / Off | Off | Begin loop recording as soon as TDashCam opens or wakes. |
| **Record audio** | On / Off | Off | Record the cabin microphone with every video (AAC, mono). |
| **Clip length** | 1, 3, 5, 10, 20 minutes | **5 minutes** | Length of each file. The change applies from the next file; the running file is finished first. Note: 1 minute produces many small files. |
| **Recording plan** (with *Storage place*) | Quality + storage limit | High, 10 GB | See the table below. |

**Recording plan** opens one dialog with two choices:

* **Quality** — High 12 Mbps · Normal 8 Mbps · Long 6 Mbps. Lower quality = smaller files = longer
  history. All levels record the same 1920×1080 / 25 fps picture.
* **Storage limit** — how much space the dashcam may use, as a list of GB values up to 80 % of the
  stick (internal storage offers 5 / 10 / 20 GB). When the limit is reached the **oldest files are
  deleted first**. The dialog shows the current usage and an estimate of how many hours the limit
  gives at the chosen quality.

### 9.2 👁️ Display & Camera

| Row | Options | Default | Effect |
| --- | --- | --- | --- |
| **Camera view** | 2×2, Main + Right/Left Column, Main + Bottom/Top Strip | 2×2 | Arrangement of the four panes (chapter 8). |
| **Camera zoom** | Off, Low, Medium, High, Maximum | **High** | Digital zoom on each camera, used to crop the curved fisheye edges out of the frame. Higher = tighter crop. |
| **Mirror side cameras** | On / Off | Off | Flips the left and right camera pictures. Off (= un-mirrored) shows them the way a driver sees the mirrors. |
| **Camera aim — Front / Rear / Left / Right** | popup, see chapter 10 | Front −5 %, Rear −8 %, Left 0 %, Right 0 % | Vertical aim and squeeze of each camera. The row shows the current value, e.g. `−5%` or `−5% / +10%` (aim / squeeze). |
| **Menu side** | Left, Right | **Left** | Which screen edge the rail is docked to. Applies instantly. |
| **Watermark** | free text | `Recorded by T-Repair` | Text burned into the bottom-right corner. Clear the text to hide it. |

### 9.3 🛡️ System & Storage

| Row | Options | Default | Effect |
| --- | --- | --- | --- |
| **Protection** | On / Off (+ what it covers) | Off | 4-digit PIN that gates the video controls, playback and/or settings (chapter 15). |
| **Storage place** | USB, Internal | Internal | Where new clips are written. USB requires a FAT32 stick; the row warns if the stick is missing or has the wrong format. |
| **Recording plan** | see 9.1 | High, 10 GB | Quality and storage limit — kept in this tab because it belongs to storage. |
| **Language** | English, ລາວ, ไทย | English | App language. Changing it restarts the app. |

---

## 10. Camera aim (pan and squeeze)

Every camera can be aimed individually so the picture shows what you want (less bonnet, more road).

1. Go to **Settings → Display & Camera → Camera aim**.
2. Tap the camera you want (**Front**, **Rear**, **Left**, **Right**).
3. The popup shows **only that camera**, live and full-size — exactly the crop the dashboard and the
   recording use.
4. Use the two rows:

   | Row | Buttons | Step | Range |
   | --- | --- | --- | --- |
   | Aim | ▲ / ▼ | 1 % per press | −10 % … +10 % of a quadrant height |
   | Squeeze / stretch | − / + | 1 % per press | −20 % … +30 % |

   * **▲ / ▼** slides the whole picture up and down inside its own camera. The picture is never
     stretched by this.
   * **−** pulls the sides in and the top/bottom out (the look the car's own AVM uses for its
     cameras); **+** does the opposite.
   * The window can never leave its own camera, so you can never pull in a neighbouring camera.
5. **Save** keeps the values. **Cancel** puts back the values that were there before you opened
   the popup.
6. The values are stored per camera and apply immediately to the live picture *and* to everything
   recorded afterwards.
7. Set all four cameras back to the factory values if needed: clear the app data
   (`adb shell pm clear com.t_repair.tdashcam`) or ask your installer to reset them.

> **Tip:** aim the cameras while the car is parked in your normal parking spot — the picture you
> frame here is exactly what the clip will contain.

---

## 11. Replay: list and player

Open **Replay** from the rail.

### 11.1 The list

* Filters across the top: **All / Today / Yesterday / Range** (custom start and end), and
  **Videos / Photos**.
* **Refresh** re-reads the storage.
* Each tile shows a thumbnail, the start time, and the file name with its size/duration.
* Newest clip first.

### 11.2 The player

Tap a tile to play it.

| Control | Action |
| --- | --- |
| **Back** (top-left) | Leave the player |
| File name (top) | Which clip is playing |
| **Share** (top-right) | Share the clip (chapter 16) |
| **Delete** (top-right) | Delete the clip after a confirmation |
| **Previous / Play-Pause / Next** | Walk through the clips. When a clip ends, playback continues with the next (newer) one. |
| Seek bar | Jump anywhere in the clip; the current and total time are shown |
| Tap the picture | Show or hide the controls |

> The player's own buttons (delete, share, back) are **not** gated by Protection in this build; the
> PIN currently guards the bulk delete on the Replay list, the record/snapshot buttons and the
> settings rows.

---

## 12. Photos (snapshots)

* Press the **snapshot** button in the rail on the Video page.
* The app saves a still of the current camera view as `photo_YYYYMMDD_HHMMSS.jpg` in the same `DVR`
  folder as the videos.
* Photos appear in **Replay** with the **Photos** filter, and can be viewed, shared and deleted like
  a clip.

---

## 13. Files, folders and file names

```
<USB or internal>
└── Android/data/com.t_repair.tdashcam/files/
    ├── DVR/
    │   ├── dvr_20260912_215844.mp4        finished clip
    │   ├── dvr_20260912_215844_01.mp4     second file with the same timestamp
    │   ├── photo_20260912_220013.jpg      snapshot
    │   └── .recording/                    in-progress files (*.mp4.tmp)
    └── debug_trace.log                    only in -WithTrace builds
```

* File name = `dvr_` + `yyyyMMdd_HHmmss` of the moment the file opened.
* Play the clips with any player that handles MP4/H.264. The audio track (if enabled) is AAC.
* Copy the files off the stick with a PC, or share them from the app (chapter 16).

---

## 14. Recording behaviour and safety

| Situation | What TDashCam does |
| --- | --- |
| App screen closed with **Exit** | Recording continues in the background service. |
| Head unit reboot | Restarts itself when *Open after restart* is on, and resumes recording if it was recording before. |
| Sleep / wake (ignition off then on) | A screen-on / power-connected / periodic watchdog brings the recorder back. |
| USB stick removed while recording | The running file is closed safely; the app reports *"USB storage unavailable"* and retries every 5 seconds. |
| System shutdown (ignition off) | The running file is finalised immediately so the stick is not left with an open file. |
| Hard power cut | Fragmented MP4 means only the last fragment (about a second) is lost. |
| Storage limit reached | The oldest files are deleted until the limit is respected. |

> There is no "protect this file" feature in this build: the lock button in the rail reports that it
> is not connected yet, so every clip is subject to the automatic cleanup.

Quality and clip-length changes always take effect **from the next file**, so a running recording is
never interrupted.

---

## 15. Protection (replay PIN)

**Settings → System & Storage → Protection**:

1. Tap the row, choose **Set a new PIN**, enter 4 digits twice.
2. Choose what the PIN covers:

   | Option | What it protects |
   | --- | --- |
   | Video controls | Record button, snapshot, saved camera layout |
   | Replay actions | Playing or deleting saved videos |
   | Settings changes | Changing any app setting |

3. When Protection is on, the PIN is requested before the protected action. In this build the gate
   is wired to:

   | Group | Actually gated |
   | --- | --- |
   | Video controls | The **Record** and **Snapshot** buttons in the rail |
   | Replay actions | The **delete selected** action on the Replay list |
   | Settings changes | The switches and value rows of the Settings tabs |

   Not gated yet: the player's delete/share/back buttons, opening the Replay player, **Camera zoom**
   and the **Camera aim** popups.
4. **Turn off Protection** (or **Change PIN**) from the same row; the current PIN is requested first.

> Protection is a convenience lock, not encryption: the files on the stick remain readable on a PC.

**Lost PIN:** reset it from a PC while connected over ADB:

```powershell
& $adb -s 10.242.77.95:5555 shell "am broadcast -a com.t_repair.tdashcam.RESET_REPLAY_PIN --es confirm CLEAR_REPLAY_PIN -n com.t_repair.tdashcam/.ReplayPinRecoveryReceiver"
```

The receiver is protected by the `DUMP` permission, which `adb shell` holds on the tested unit.

---

## 16. Sharing a clip

1. Open the clip in the player (Replay → tile).
2. Press **Share** in the top bar.
3. Android's share sheet opens — pick WhatsApp, YouTube, *Save as…*, VLC, and so on. If the head
   unit has no app that can receive the clip, the app shows *"No app can receive this clip"*
   instead of failing.

The clip is handed over through the app's own content provider
(`content://com.t_repair.tdashcam.clips/…`), so the receiving app gets read access to exactly that
one file.

---

## 17. Troubleshooting

| Symptom | Cause | What to do |
| --- | --- | --- |
| **No camera picture / black panes**, but the labels and clock are visible | The preview surface lost the EVS feed; the recorder is usually unaffected. | Switch to another page in the rail and back, or restart the app. Fixable in firmware by recreating the preview surface — report it if it happens often. |
| *"USB storage unavailable"* | No stick, or the stick was removed. | Insert a FAT32 stick, or change **Storage place** to *Internal*. |
| *"Format USB as FAT32"* | Stick uses exFAT/NTFS. | Format the stick as FAT32 on a PC. |
| No sound in the clips | *Record audio* was off when they were recorded. | Turn it on; the setting applies to new files. |
| Clip will not play on a PC | Player does not support fragmented MP4. | Use VLC, or play it inside the app. |
| Recording does not start after a restart | *Open after restart* off (and the recorder was stopped manually). | Turn *Open after restart* on and start recording once. |
| Recording stopped after a **long** drive | Storage limit reached and everything else is locked. | Raise the limit in *Recording plan*, or unlock/delete files. |
| PIN forgotten | — | Use the ADB command in chapter 15, or `pm clear` (resets all settings). |
| `adb connect` fails | Unit asleep, or ADB over TCP off. | Wake the unit, retry two or three times; if it never connects, enable ADB over Wi-Fi again from the unit side. |
| `install` fails with *device offline* | Unstable Wi-Fi ADB link. | Use the push + `pm install` route from chapter 4.2. |
| Gradle build fails with *Unable to delete directory* | OneDrive folder locking. | `gradlew --stop`, delete `app\build`, rebuild. |

---

## 18. Defaults, ranges and specifications

### 18.1 Defaults (on a fresh install / after `pm clear`)

| Setting | Default |
| --- | --- |
| Open after restart | On |
| Start recording automatically | Off |
| Record audio | Off |
| Clip length | 5 minutes |
| Quality / storage limit | High (12 Mbps) / 10 GB |
| Storage place | Internal |
| Camera view | 2×2 |
| Camera zoom | High (75) |
| Mirror side cameras | Off (realistic) |
| Camera aim | Front −5 %, Rear −8 %, Left 0 %, Right 0 %, squeeze 0 % |
| Menu side | Left |
| Watermark | `Recorded by T-Repair` |
| Language | English |
| Protection | Off |

### 18.2 Specifications

| Property | Value |
| --- | --- |
| Video | H.264/AVC, **1920×1080**, 25 fps, ~12 / 8 / 6 Mbps (High / Normal / Long) |
| Audio (optional) | AAC-LC, 44.1 kHz, mono, 128 kbps, microphone gain ×3 |
| Container | fragmented MP4 (`.mp4`) |
| Clip lengths | 1, 3, 5, 10, 20 minutes |
| Storage limits | 5 GB up to 80 % of the stick (internal: 5 / 10 / 20 GB) |
| Sources | 4-camera EVS composite, 1920×800 arranged as 2×2 quadrants of 960×400 |
| Camera aim | pan −10 % … +10 %, squeeze −20 % … +30 %, per camera |
| Screen | Full screen, immersive, 1920×1080 |
| Languages | English, Lao, Thai (other ROM locales fall back to English) |

---

## 19. Technical appendix

* **Pipeline** — one GL pipeline (`DvrVideoPipeline`) samples the EVS composite once and draws it
  twice: to the preview surface and to the encoder surface. Pane cropping (the difference between
  the 2.4:1 quadrant and the pane's aspect) is applied while sampling, so the picture reaches both
  the screen and the file already corrected.
* **Recording** — `DirectEvsRecorder` opens the encoder at 1920×1080 (the panel's proportions),
  writes the burnt-in overlay (dividers, labels, brand, date/time/speed, watermark) as a texture and
  muxes video plus optional audio. Segments are staged on the destination volume and renamed into
  place when finished.
* **Service** — `DvrService` is a foreground service; it keeps recording when the UI is closed and
  restarts itself through `BootReceiver` (boot, wake, power, USB mount) plus a periodic watchdog
  alarm. It also repairs the recorder if the EVS feed or the storage disappears.
* **Settings storage** — `SharedPreferences` file `dvr_prefs.xml` inside the app's data directory.
  Read it on the unit with:

  ```powershell
  & $adb -s 10.242.77.95:5555 shell "cat /data/data/com.t_repair.tdashcam/shared_prefs/dvr_prefs.xml"
  ```

* **Diagnostics** — the normal build strips `DebugTrace`; use `-WithTrace` to get
  `<storage>/Android/data/com.t_repair.tdashcam/files/debug_trace.log`, which records pipeline,
  encoder, storage and aim events.
* **Useful ADB commands**

  ```powershell
  # recorder running?
  & $adb -s <ip>:5555 shell "dumpsys activity services com.t_repair.tdashcam"
  # current screen and window size
  & $adb -s <ip>:5555 shell "dumpsys window | grep -E 'mCurrentFocus|mFrame'"
  # recorded frame size of the newest clip (1920x1080 expected)
  & $adb -s <ip>:5555 shell "tail -c 400000 <clip> > /sdcard/t.tail"
  & $adb -s <ip>:5555 pull /sdcard/t.tail .
  # then read the big-endian 16-bit pair at the 'stsd' box offset +40 (width) and +42 (height)
  ```
