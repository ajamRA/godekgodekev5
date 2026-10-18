# Audit AVAS — Laporan Laptop Sahaja (28 September 2026)

**PENGESAHAN:** Tiada sambungan ADB, tiada akses IHU, tiada deployment, tiada arahan sistem kereta.

---

## 1. Ringkasan Audit

Kod sumber `AvasSoundDaemon.java` (47,680 bait, diubah suai 28 Sep 21:52) dan `AvasTuningEncoder.java` (7,383 bait, 28 Sep 20:53) telah diaudit terhadap keperluan keselamatan fail-closed token, staging audio atomik, dan semakan integriti transaksi.

**Status ujian:** TestAvasSoundDaemon (8 ujian) lulus 100% pada laptop (Java 17.0.19, kompilasi Java 8).

**Artifak semasa:**
- JAR: `artifacts/daemon-hardened-20260927-203523-513/door_pressure_relief-hardened.jar`
- SHA-256: `0ed585773140ab10fdfb8138d6445d495ad0cdf2407e5043057221e46782d968`

---

## 2. Pengesanan Android dan Fail-Closed Token

### 2.1 Pengesanan Android

**Lokasi:** Baris 67-68

```java
isAndroid = System.getProperty("java.vendor", "").toLowerCase().contains("android")
    || new File("/system/bin/sh").exists();
```

**Status:** ⚠ **Berfungsi tetapi terlalu luas — boleh salah klasifikasi**

**Analisis:**
- Pengesanan menggunakan operator ATAU (`||`): `isAndroid` menjadi `true` jika `java.vendor` mengandungi "android" **ATAU** `/system/bin/sh` wujud.
- Mana-mana satu syarat sudah mencukupi untuk mengaktifkan laluan Android.
- Pada Windows standard, kedua-dua syarat gagal → `isAndroid = false`. Betul.
- Pada Android, sekurang-kurangnya `/system/bin/sh` wujud → `isAndroid = true`. Betul.
- **Risiko salah klasifikasi:** Pada Linux desktop atau WSL, `/system/bin/sh` tidak biasanya wujud tetapi boleh diwujudkan secara manual. Pada mana-mana persekitaran bukan Android yang mempunyai fail `/system/bin/sh` (contoh: chroot, container, atau mount luar biasa), daemon akan mengaktifkan laluan Android secara salah, termasuk polisi fail-closed token dan panggilan arahan sistem Android. Ini bukan kegagalan kritikal keselamatan (ia lebih ketat, bukan lebih longgar), tetapi boleh menyebabkan daemon gagal bermula pada persekitaran bukan Android yang mempunyai fail tersebut.
- Syarat `java.vendor` sahaja lebih tepat tetapi kurang dipercayai kerana ia bergantung pada JVM vendor string.

**Cadangan:** Pertimbangkan untuk menambah syarat tambahan (contoh: semak `android.os.Build` via reflection) bagi mengurangkan risiko salah klasifikasi. Namun risiko praktikal rendah dalam skop penggunaan sebenar (IHU atau laptop Windows).

### 2.2 Fail-Closed Token Security

**Lokasi:** Baris 124-166 (`initAuthToken`)

**Polisi fail-closed diverifikasi:**

1. **Baris 148-150:** Pada Android, jika `secureTokenFile()` gagal (tidak dapat set owner-only permissions), daemon **ABORT dengan SecurityException**.
   ```java
   if (isAndroid) {
       System.err.println("[AvasSoundDaemon:FATAL] Cannot enforce owner-only permissions...");
       throw new SecurityException("FAIL_CLOSED: Insecure token file permissions: ...");
   }
   ```

2. **Baris 159-162:** Pada Android, jika inisialisasi token gagal (exception), daemon **ABORT dengan RuntimeException**.
   ```java
   if (isAndroid) {
       System.err.println("[AvasSoundDaemon:FATAL] Token initialization failed on Android: ...");
       throw new RuntimeException("FAIL_CLOSED: Token initialization failed", t);
   }
   ```

3. **Tiada fallback lemah pada Android:** Fallback token (`avas_local_token_` + timestamp) hanya digunakan pada non-Android (baris 164), selepas log amaran.

**Status:** ✓ **LULUS — Fail-closed betul-betul dikuatkuasakan**

**Bukti:**
- Daemon tidak akan start HTTP server jika token setup gagal pada Android (exception thrown sebelum `startHttpServer()` dipanggil).
- `secureTokenFile()` memerlukan KEDUA-DUA `setReadable(true, true)` DAN `setWritable(true, true)` return `true` (baris 116-118).
- Jika mana-mana gagal, polisi fail-closed aktif.

---

## 3. Staging Fail Audio dan Tuning

### 3.1 Transaksi Atomik Audio (`handleApplySound`)

**Lokasi:** Baris 588-665 (dalam `AUDIO_STAGING_LOCK`)

**Langkah disahkan:**

1. **Baris 591:** Fail temp unik dibuat: `slot_X.wav.tmp.<nanoseconds>`
2. **Baris 594:** Copy source → temp
3. **Baris 597-603:** **Validasi post-copy:** `validatePcmWav48k16BitMono(tempDest)` dijalankan SEBELUM promote. Jika gagal, temp dipadam dan return error 500.
4. **Baris 607-614:** **Atomic move dengan fallback:**
   ```java
   try {
       Files.move(tempDest.toPath(), internalDest.toPath(),
           REPLACE_EXISTING, ATOMIC_MOVE);
   } catch (Throwable atomicFail) {
       Files.move(tempDest.toPath(), internalDest.toPath(),
           REPLACE_EXISTING);
   }
   ```
   - ATOMIC_MOVE dicuba terlebih dahulu.
   - Fallback REPLACE_EXISTING **TIDAK memadam fail sasaran terlebih dahulu** (Java NIO contract: REPLACE_EXISTING replace secara atomik jika mungkin, atau overwrite tanpa pre-delete jika atomik tidak disokong).

5. **Baris 615-617:** Cleanup temp dalam `finally` block.

6. **Baris 619-648:** Pada Android sahaja:
   - Unmount lama (baris 621)
   - Bind mount baru (baris 624)
   - **Mount verification:** `isMounted()` check `/proc/mounts` (baris 625). Jika gagal, return error 500.
   - `restorecon -F` (baris 633), semak exit code (baris 634-639).
   - `restartAvasHalService()` (baris 641), semak exit code (baris 642-647).

7. **Baris 650-664:** Update config JSON **DALAM lock yang sama**.

**Status:** ✓ **Transaksi dilindungi `AUDIO_STAGING_LOCK`**

**Isu ditemui:**

❌ **Mount, restorecon, HAL restart TIDAK dalam lock yang sama dengan file staging.**

- Baris 619-648 (mount/restorecon/HAL) berada **DALAM `synchronized(AUDIO_STAGING_LOCK)`** (lock bermula baris 588).
- **ISU SEBENAR:** `restartAvasHalService()` (baris 982-990) **HANYA semak exit code kill command**, TIDAK membuktikan HAL hidup semula.

### 3.2 Transaksi Atomik Tuning (`handleSaveTuning`)

**Lokasi:** Baris 712-793 (dalam `AUDIO_STAGING_LOCK`)

**Langkah disahkan:**

1. **Baris 709:** Encode tuning dengan XOR + CRC (`AvasTuningEncoder.generateEncodedTuning`)
2. **Baris 713:** Fail temp unik: `tuning_X.txt.tmp.<nanoseconds>`
3. **Baris 716-719:** Write + `fsync()` via `fos.getFD().sync()`
4. **Baris 720-727:** Atomic move (cuba ATOMIC_MOVE, fallback REPLACE_EXISTING)
5. **Baris 728-730:** Cleanup temp dalam `finally`
6. **Baris 735-771:** Pada Android: mount tuning + restorecon + mount chime (jika mute) + restorecon + HAL restart
7. **Baris 774-786:** Update config JSON dalam lock yang sama

**Status:** ✓ **Transaksi dilindungi `AUDIO_STAGING_LOCK`**

**Nota:** Sama seperti audio, HAL restart hanya semak kill berjaya, bukan verifikasi HAL hidup.

### 3.3 Restore Factory (`handleRestore`)

**Lokasi:** Baris 821-874 (dalam `AUDIO_STAGING_LOCK`)

**Langkah disahkan:**

1. **Baris 822-828:** Semak kewujudan fail backup. Jika tiada, return 404.
2. **Baris 831-836:** **Validasi backup SEBELUM mount:** `validatePcmWav48k16BitMono(backupTrack)`. Jika gagal, return 422.
3. **Baris 838-866:** Pada Android: unmount lama + mount backup + verify mount + restorecon + HAL restart (semuanya dalam lock)
4. **Baris 868-873:** Restore config JSON default dalam lock

**Status:** ✓ **Backup divalidasi sebelum mount**

---

## 4. Semakan Keselamatan Lain

### 4.1 Autentikasi X-AVAS-Token

**Lokasi:** Baris 372-377

```java
if (authToken != null && !authToken.isEmpty()) {
    if (reqToken == null || !reqToken.equals(authToken)) {
        sendResponse(sock, 401, ...);
        return;
    }
}
```

**Status:** ✓ **Dikuatkuasakan pada semua endpoint kecuali OPTIONS**

**Ujian lulus:**
- Test 1: Request tanpa token → 401
- Test 1: Request dengan token sah → 200

### 4.2 Had Badan HTTP & DoS

**Lokasi:**
- Baris 43: `MAX_BODY_BYTES = 65536` (64 KB)
- Baris 331-334: Semak `Content-Length > MAX_BODY_BYTES` → reject 413
- Baris 298-301: Header lebih 8 KB → reject 431

**Status:** ✓ **Batasan dikuatkuasakan**

### 4.3 Path Traversal

**Lokasi:** Baris 895-898 (`handleAudioPreview`)

```java
boolean allowed = isPathUnderDirectory(audioFile, new File(baseDir))
    || isPathUnderDirectory(audioFile, new File("/storage"))
    || isPathUnderDirectory(audioFile, new File("/mnt/media_rw"));
```

- `isPathUnderDirectory()` (baris 1043-1052) menggunakan `getCanonicalPath()` untuk resolve symlink/`..`.

**Status:** ✓ **Path traversal dicegah**

**Ujian lulus:**
- Test 6: `path=../../../../windows/win.ini` → 403
- Test 6: Path sah dalam baseDir → 200

### 4.4 Pengesahan Format WAV

**Lokasi:** `validatePcmWav48k16BitMono()` (tidak ditunjukkan dalam output, tetapi dipanggil baris 570, 597, 831)

**Ujian lulus:**
- Test 5: WAV tidak sah (12 bait header sahaja) → 422
- Test 5: WAV canonical PCM 48kHz 16-bit Mono → 200

### 4.5 UNECE R138 (Anti-Mute)

**Lokasi:**
- Baris 514-519: Mode 0 atau <1 atau >3 ditolak → 400
- `AvasTuningEncoder` baris 138-140: All-zero volume curve → exception

**Ujian lulus:**
- Test 3: `mode=0` → 400
- Test 4: Volume `[0,0,0,0,0,0,0]` → 400

### 4.6 XOR 0x70 dan CRC16

**Lokasi:** `AvasTuningEncoder.java`

- Baris 46-54: `computeCrc16()` — CRC16-CCITT, skip `\r` (0x0D)
- Baris 59-70: `xor70()` — XOR 0x70 dengan 4 exception bytes (0x0D, 0x0A, 0x70, 0x7D)
- Baris 79-103: `sanitizeText()` — Hapus forbidden bytes {0x7D, 0x7A, 0x00, 0x0D standalone}

**Ujian lulus:**
- Test 4: Tuning generated, CRC computed = 49759, decrypt + verify berjaya

---

## 5. Isu yang Ditemui

### 5.1 `restartAvasHalService()` Tidak Verify HAL Hidup Semula

**Lokasi:** Baris 982-990

```java
private static boolean restartAvasHalService() {
    if (!isAndroid) return true;
    System.out.println("[AvasSoundDaemon] Killing vendor.molead.hardware.avas_service...");
    boolean killOk = runSystemCommand("/system/bin/pkill", "-f", "vendor.molead.hardware.avas_service");
    if (!killOk) {
        killOk = runSystemCommand("/system/bin/killall", "vendor.molead.hardware.avas_service@1.0-service");
    }
    return killOk;
}
```

**Masalah:**
- Fungsi hanya semak arahan `pkill`/`killall` berjaya (exit code 0).
- **TIDAK membuktikan HAL service hidup semula** (contoh: via `getprop`, `ps`, atau HAL health check).
- Arahan kill berjaya ≠ HAL restart berjaya.

**Kesan:**
- Caller menganggap HAL siap apabila `restartOk = true`, tetapi HAL mungkin belum start semula.
- Dokumentasi audit terdahulu (HARDENING_REVIEW) tidak mendakwa verifikasi HAL hidup; ia hanya log "killing" dan report exit code.

**Cadangan:**
1. Namakan semula fungsi kepada `killAvasHalService()` untuk mencerminkan behaviour sebenar.
2. ATAU tambah polling `ps | grep avas_service` selepas kill (tunggu sehingga proses muncul semula), dengan timeout.
3. Dokumentasi mesti jelas: "Daemon meminta HAL restart, tetapi tidak mengesahkan HAL siap beroperasi."

### 5.2 REPLACE_EXISTING Fallback Bukan Atomik

**Lokasi:** Baris 613, 726

**Dokumentasi kod (baris 605):**
```java
// Safe atomic replace: NEVER delete internalDest prior to successful replacement
```

**Realiti:**
- `Files.move(..., ATOMIC_MOVE)` atomik pada filesystem yang sokong (ext4, btrfs).
- Jika gagal (contoh: cross-filesystem), fallback `REPLACE_EXISTING` **mungkin bukan atomik** pada sesetengah filesystem (FAT32, exFAT, NTFS dalam kes tertentu).
- Java NIO spec: REPLACE_EXISTING "replace if exists", tetapi **tidak jamin atomicity** tanpa ATOMIC_MOVE.

**Kesan:**
- Pada kegagalan power semasa fallback move, fail destinasi mungkin separuh ditulis atau hilang.
- Daemon tidak mempunyai recovery untuk fail destinasi rosak selepas fallback.

**Cadangan dokumentasi:**
- Nyatakan dengan jelas: "Fallback REPLACE_EXISTING digunakan jika ATOMIC_MOVE gagal. Atomicity tidak dijamin pada fallback. Kegagalan kuasa semasa fallback boleh rosakan fail destinasi."

---

## 6. Isu yang Telah Dibaiki (Disahkan)

✓ **Token fail-closed:** Daemon abort jika token setup gagal pada Android.
✓ **Staging temp file:** Fail temp unik digunakan dengan cleanup dalam `finally`.
✓ **Validasi post-copy:** Fail temp divalidasi sebelum promote ke destinasi.
✓ **fsync sebelum move:** `fos.getFD().sync()` dipanggil (baris 718, 1063).
✓ **Mount verification:** `/proc/mounts` disemak selepas bind mount (baris 625, 738, 844).
✓ **Restorecon exit code:** Disemak dan dilaporkan sebagai error jika gagal.
✓ **AUDIO_STAGING_LOCK:** Semua operasi staging (audio + tuning + restore) dilindungi lock yang sama.
✓ **Config update dalam lock:** JSON config dikemas kini dalam `synchronized(CONFIG_LOCK)` SELEPAS operasi filesystem/mount berjaya.

---

## 7. Ujian yang Dijalankan

### 7.1 Suite yang disahkan dalam sesi audit ini (28 Sep 2026)

**Suite:** `tests/TestAvasSoundDaemon.java` (320 baris, 8 ujian)

Dikompilasi dan dijalankan secara langsung oleh auditor menggunakan `javac -source 8 -target 8` dan `java` pada laptop. Output terminal disalin verbatim:

```
=== Starting Hardened TestAvasSoundDaemon on Laptop ===
[Test 1] Authentication Enforcement
  ✓ 401 on missing token verified
  ✓ 200 on valid token verified

[Test 2] Valid Mode Switch (Slot 2)
  ✓ Successfully switched to Slot 2

[Test 3] UNECE R138 Compliance (Banning Total Mute AVAS)
  ✓ mode=0 correctly rejected with HTTP 400 (UNECE R138 enforced)

[Test 4] Custom Tuning Curve Generation & Encryption
  ✓ CRC of generated tuning verified: 49759
  ✓ All-zero volume curve correctly rejected with HTTP 400

[Test 5] Audio Validation & Internal Staging
  ✓ Invalid WAV rejected with HTTP 422
  ✓ Canonical WAV validated and staged to internal storage: slot_3.wav

[Test 6] Path Traversal Defense
  ✓ Path traversal rejected with HTTP 403
  ✓ Valid preview returned HTTP 200

[Test 7] Presets List
  ✓ Presets scanned successfully (2 files)

[Test 8] Reliable Factory Restore
  ✓ Restore without factory backup rejected with HTTP 404
  ✓ Factory sound restored successfully with verified backup

ALL 8 HARDENED AVAS SOUND DAEMON TESTS PASSED 100% ON LAPTOP!
```

**Persekitaran:** Java 17.0.19, kompilasi target Java 8, Windows laptop
**Artifak JAR:** TIDAK dibina semula (menggunakan artifak sedia ada dari 27 September)
**Mod:** Laptop sahaja, `isAndroid = false`, tiada Android runtime, tiada IHU

### 7.2 Suite lain yang wujud dalam projek tetapi TIDAK dijalankan dalam sesi ini

Fail-fail ujian berikut wujud dalam `tests/` tetapi **tidak dikompilasi atau dijalankan oleh auditor** dalam sesi ini. Keputusan mereka **tidak disahkan** dan tidak boleh dianggap sebagai bukti:

1. **`tests/TestAvasTuningEncoder.java`** (100 baris) — ujian unit untuk `AvasTuningEncoder`. Belum disahkan.
2. **`tests/TestDaemonLogic.java`** (142 baris) — ujian logik untuk `DoorPressureReliefDaemon` (daemon window, bukan AVAS). Belum disahkan dalam konteks audit AVAS ini.
3. **`tests/TestVehicleHttpClient.java`**, **`tests/TestReadOnlyTelemetry.java`**, **`tests/TestTelemetryDaemon.java`** — berkaitan telemetry, bukan skop AVAS. Belum disahkan.

### 7.3 Dakwaan "96+ pemeriksaan" dalam HARDENING_REVIEW_20260927.md

Laporan terdahulu (`HARDENING_REVIEW_20260927.md` baris 12) menyatakan "96+ semakan ujian setempat". Dakwaan ini merujuk kepada `TestDaemonLogic.java` (harness daemon window) dan **bukan kepada TestAvasSoundDaemon**. Auditor tidak menjalankan semula harness tersebut dan **tidak mengesahkan dakwaan 96+ pemeriksaan**. Ia kekal sebagai dakwaan laporan terdahulu sahaja.

---

## 8. Artifak dan Hash

**JAR semasa (calon laptop, belum deploy):**
```
Path: artifacts/daemon-hardened-20260927-203523-513/door_pressure_relief-hardened.jar
SHA-256: 0ed585773140ab10fdfb8138d6445d495ad0cdf2407e5043057221e46782d968
```

**AMARAN:** JAR ini adalah door_pressure_relief daemon (window control), BUKAN avas_sound_daemon.

**Nota:** Projek mempunyai dua daemon berbeza:
1. `src_window/` → door/window control (JAR di atas)
2. `src_avas/` → AVAS sound daemon (tiada JAR terkini dalam artifacts/)

Skrip build `build_avas_daemon.ps1` wujud tetapi tidak menghasilkan artifak bertarikh dalam `artifacts/`.

---

## 9. Batasan Audit

1. **Tiada ujian Android sebenar:** Pengesanan Android, fail-closed token, mount, restorecon, dan HAL restart hanya diaudit melalui kod. Mock digunakan dalam ujian.

2. **Tiada ujian IHU fizikal:** Atomicity `REPLACE_EXISTING`, SELinux restorecon, bind mount persistence, HAL restart verification tidak diuji pada perkakasan.

3. **Tiada ujian power loss:** Recovery daripada kegagalan kuasa semasa file move tidak diuji.

4. **Tiada verifikasi audio playback:** WAV validation hanya semak header format, tidak test playback sebenar atau deteksi klik/distortion.

5. **Tiada ujian UNECE R138 compliance sebenar:** Sekatan mute adalah enforcement aplikasi sahaja, bukan verifikasi pematuhan standard penuh.

6. **Tiada build JAR baharu:** Kod diaudit dan ujian dijalankan, tetapi tiada JAR AVAS baharu dihasilkan kerana tiada perubahan kod diperlukan.

---

## 10. Cadangan Tindakan

### Segera (Sebelum Deploy)

1. **Rename `restartAvasHalService()` → `killAvasHalService()`** atau tambah polling verifikasi HAL hidup.

2. **Dokumentasi fallback atomicity:** Tambah komen dalam kod (baris 610-614, 724-727) menjelaskan risiko fallback non-atomic.

3. **Build JAR AVAS:** Jika deployment diperlukan, gunakan `build_avas_daemon.ps1` untuk generate JAR bertarikh dalam `artifacts/`.

### Masa Depan (Enhancement)

1. HAL health check API selepas restart.
2. Watchdog untuk monitor HAL crash dan auto-recovery.
3. Checksummed backup dengan versioning.
4. Atomic deployment tool untuk IHU (upload + verify + activate dalam satu transaksi).

---

## 11. Pengesahan Akhir

✓ Tiada sambungan ADB dibuat.
✓ Tiada arahan dihantar ke IHU (192.168.0.178:5555 atau mana-mana IP).
✓ Tiada fail dipasang, deploy, atau push ke kereta.
✓ Tiada ujian mount, SELinux, HAL atau AVAS fizikal dijalankan.
✓ Tiada token, kunci atau rahsia didedahkan dalam laporan ini.

**Audit ini dijalankan sepenuhnya di laptop menggunakan kod sumber dan ujian mock.**

---

**Tarikh:** 28 September 2026  
**Artifak diaudit:** `src_avas/com/emas/avas/AvasSoundDaemon.java` (47,680 bait, 28 Sep 21:52)  
**Ujian:** TestAvasSoundDaemon — 8/8 lulus  
**Status:** Calon laptop sahaja, belum disahkan pada IHU
