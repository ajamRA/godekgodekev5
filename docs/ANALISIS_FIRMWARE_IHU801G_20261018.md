# ANALISIS FIRMWARE IHU801G (Geely Xingyuan / Proton e.MAS 5)

**Tarikh kerja:** 18 Oktober 2026, 09:15 – 11:05
**Status:** Analisis separa — `system` partition **belum siap** diekstrak.

> [!IMPORTANT]
> **Model berbeza.** Firmware ini untuk **IHU801G** (Geely Xingyuan), bukan **IHU801P** (Proton e.MAS 5).
> Nombor model yang berbeza bermaksud partition layout, VHAL, dan aplikasi sistem mungkin **tidak serasi**.
> Jangan salin apa-apa artifak dari folder ini ke dalam kereta tanpa pengesahan model terlebih dahulu.

---

## 1. Sumber & Versi

Sumber asal: `geely-xingyuan-IHU801G.zip` — **8.68 GB**, dimuat turun 18 Okt 09:15.
Isi: tiga pakej OTA penuh (bukan incremental).

| Fail OTA | Build | Timestamp kilang | Saiz |
| :--- | :--- | :--- | ---: |
| `SW0E22H0519H8DZH.03094.zip` | `...03094.user/3094` | 2026-05-18 20:24 UTC | 2.91 GB |
| `SW0E22H0119H8DUH.02884.zip` | `...02884.user/2884` | 2026-01-19 09:55 UTC | 2.90 GB |
| `SW0E22H1202H8EWH.02726.zip` | `...02726.user/2726` | 2025-12-01 16:20 UTC | 2.87 GB |

Metadata sepunya (ketiga-tiga pakej):

```
pre-device                = IHU801G
post-sdk-level            = 28          (Android 9)
post-security-patch-level = 2021-09-05   (lama ~5 tahun)
ota-type                  = BLOCK
pre-efuse                 = true
post-build                = GEELYGEOME/IHU801G/IHU801G:9/...:user/test-keys
```

**Pemerhatian:** build ditandatangani dengan **`test-keys`**, bukan release-keys. Ini bermaksud
imej boleh ditandatangani semula dengan kunci ujian. Ia juga bermakna firmware ini bukan
keluaran produksi rasmi — kemungkinan besar dump internal atau build engineering.

---

## 2. Struktur Pakej OTA (`...03094`)

Diekstrak ke `firmware_analysis/`:

| Fail | Saiz | Peranan |
| :--- | ---: | :--- |
| `vendor.new.dat.br` | 168 MB | Vendor partition, Brotli-compressed |
| `vendor.transfer.list` | 2.7 KB | Peta blok untuk `sdat2img` |
| `vendor.img` | 1.97 GB | Hasil tukar — **sah** (ext4 superblock `53 ef` @ offset 1080) |
| `boot.img` | 13.8 MB | Android boot image, magic `ANDROID!!` — **sah** |
| `upgrade.dat` | 525 KB | Skrip kemas kini peringkat MCU |
| `fsl_app.s19` | 768 KB | Firmware Motorola S-record (MCU Freescale) |
| `fsl_app_1m.s19` | 1.0 MB | Varian 1 MB S-record |
| `scatter.txt` | 946 B | Peta partition MediaTek (offset fizikal) |
| `init_rc/` | 90 fail | Salinan `*.rc` daripada vendor — rujukan servis init |
| `META-INF/` | — | `updater-script`, metadata OTA |

Dua APK/binary sistem turut diekstrak:

* `android.hardware.automotive.vehicle@2.0-service` (1.3 MB) — VHAL daemon
* `vehicleBootService` (69 KB)

---

## 3. Aliran `updater-script`

Pakej ini **OTA penuh (BLOCK-based)**, bukan incremental:

1. Semak `ro.build.efuse == "true"` — gagal jika tidak padan.
2. `block_image_update` → `/dev/block/platform/bootdevice/by-name/system`
3. `block_image_update` → `/dev/block/platform/bootdevice/by-name/vendor`
4. Tulis terus `boot.img` ke partition `boot`
5. Peringkat "general image" (sekali sahaja, ditanda dalam `/cache/recovery/last_mtupdate_stage`):
   `ivres`, `cam_vpu3`, `dtbo`, `cam_vpu2`, `md1img`, `spmfw`, `cam_vpu1`

**Implikasi:** ini bukan pakej yang boleh ditampal pada e.MAS 5. Semakan `pre-device=IHU801G`
akan menggagalkan pemasangan pada IHU801P.

---

## 4. Peta Partition (MediaTek, dari `scatter.txt`)

```
preloader   0x0            protect1    0xb688000
pgpt        0x0            protect2    0xbe88000
boot_para   0x8000         seccfg      0xc800000
recovery    0x108000       sec1        0xd000000
para        0x2108000      proinfo     0xd200000
expdb       0x2188000      md1img      0xd500000
frp         0x3588000      spmfw       0x13900000
nvcfg       0x3688000      scp1/scp2   scp1 0x13a00000, scp2 0x14000000
nvdata      0x5688000      sspm_1/2    0x14600000 / 0x14700000
metadata    0x9688000      cam_vpu1-3  0x14800000 / 0x15700000 / 0x16600000
                           gz1/gz2     0x17500000 / 0x18500000
                           nvram       0x19500000
                           odm2        0x1d500000
                           persist     0x21800000
                           protect3-5  0x24d00000 / 0x64d00000
                                       / 0xa4d00000
                           lk / lk2    0x124d00000 / 0x124e00000
                           boot        0x124f00000
                           mtkdata     0x126f00000
                           logo        0x12c900000
                           dtbo        0x12d100000
                           tee1 / tee2 0x12d900000 / 0x12fc00000
```

Offset `lk` → `lk2` → `boot` berselang tepat `0x100000` (1 MB), jadi peta ini konsisten
secara aritmetik. Ia dibaca terus daripada `firmware_analysis/scatter.txt`.

---

## 5. Status Ekstrak `system` — **TIDAK SIAP**

Folder `extracted_test/`:

| Fail | Saiz | Status |
| :--- | ---: | :--- |
| `SW0E22H0519H8DZH.03094.zip` | 2.91 GB | OK |
| `system.new.dat.br` | 2.47 GB | ✅ **sah** (lihat bukti di bawah) |
| `system.transfer.list` | 92 KB | ✅ sah |
| `system.img` | **0 B** | ❌ kosong |
| `system.new.dat` | tiada | ❌ dekompresi tidak dijalankan |

### Bukti: strim Brotli tidak rosak

Saya uji `system.new.dat.br` dengan decompressor Node (bukan skrip projek):

```
STREAM_OK total_bytes=5081309184 (4.73 GB)
```

Kemudian saya jumlahkan semua julat `new` dalam `system.transfer.list`:

```
sum of new-block ranges     : 1,240,554 blocks
decompressed bytes / 4096   : 1,240,554 blocks
match                       : YES — exact
```

Jadi input Brotli **sihat sepenuhnya**. Kegagalan berlaku pada alat Python, bukan data.

### Punca sebenar: pemasangan Python 3.12 rosak

```
$ ls ".../Programs/Python/Python312/Lib"
site-packages          <-- hanya ini yang tinggal

$ ls ".../Python312/Lib/encodings"
encodings dir MISSING

$ python -c "print('hello')"
ModuleNotFoundError: No module named 'encodings'
```

`decompress_br.py` tidak pernah sempat mengimport `brotli` kerana Python itu sendiri gagal
bootstrap. `sdat2img.py` kemudian menerima `system.new.dat` yang tidak wujud dan menulis
`system.img` 0 bait.

### Pembetulan yang disyorkan (belum dibuat)

Pilihan A — baiki Python dahulu:

```powershell
py -3.14 -c "import brotli"     # atau ps -3.10
```

Jika `brotli` tiada pada mana-mana versi, pilih satu laluan yang berfungsi sebelum
menjalankan semula skrip.

Pilihan B — guna Node (terbukti berfungsi dalam sesi ini):

```js
const fs = require('fs'), zlib = require('zlib');
fs.createReadStream('system.new.dat.br')
  .pipe(zlib.createBrotliDecompress())
  .pipe(fs.createWriteStream('system.new.dat'));
```

Selepas `system.new.dat` siap (jangkaan 4.73 GB), baru jalankan:

```bash
python sdat2img.py system.transfer.list system.new.dat system.img
```

**Keperluan ruang:** `system.new.dat` 4.73 GB + `system.img` ~6.0 GB (1,540,097 blok × 4096)
di samping sumber 2.47 GB yang sedia ada.

> [!WARNING]
> `D:` hanya tinggal **20 GB bebas** pada masa analisis. Jumlah keperluan di atas (~13 GB)
> mencukupi, tetapi jangan mulakan sehingga ruang disahkan semula — proses akan gagal di
> tengah jalan dan meninggalkan fail separa.

---

## 6. Skrip Pembantu (ditambah 18 Okt)

| Fail | Fungsi | Status |
| :--- | :--- | :--- |
| `sdat2img.py` | Tukar `*.new.dat` + `*.transfer.list` → imej ext4 mentah | Logik sah; tidak sempat berjaya kerana input 0 bait |
| `decompress_br.py` | Brotli → `.new.dat` | **Gagal** — bukan kerana logik, tetapi Python 3.12 pada mesin ini rosak (§5) |

Kedua-duanya masih ada fallback laluan Windows yang di-hardcode ke
`D:\apps\emas-ota\Geely Xingyuan\extracted_test\`. Jalankan dengan argumen eksplisit
lebih selamat daripada bergantung pada fallback.

---

## 7. APK Sistem Yang Diekstrak

`extracted_apps/` (3 fail, ~394 MB):

| Fail | Saiz | Peranan (anggaran dari nama) |
| :--- | ---: | :--- |
| `GeelyAutoLauncher.apk` | 35.6 MB | Launcher IHU |
| `GeelyAutoCarExhibit.apk` | 197.8 MB | Pameran/UI kereta (showroom mode) |
| `GeelyAutoSceneDirector.apk` | 160.3 MB | Pengarah babak/scene kenderaan |

**Belum diterokai:** manifest, permission, service, intent — belum dibedah.

Fail media: `extracted_media/3y.mp4` (88.8 MB), `extracted_media/GV.mp4` (96.8 MB).
Imej partition `ivres.img` (326 MB) — kemungkinan partition sumber/gambar (IVI resources),
belum dianalisis.

---

## 8. Perkara Yang Belum Dibuat

- [ ] Siapkan `system.img` (guna Node atau baiki Python — lihat §5)
- [ ] Mount/ekstrak `system.img` → bandingkan dengan `/system/app` e.MAS 5
- [ ] Bedah `GeelyAutoLauncher.apk` (jadual `jadx`) — bandingkan dengan `com.malaysia.launcher3`
- [ ] Bandingkan `init_rc/` dengan `/system/etc/init/` e.MAS 5 (cari daemon tambahan)
- [ ] Analisis `upgrade.dat` dan `fsl_app.s19` (firmware MCU — risiko tinggi, baca sahaja)
- [ ] Ekstrak `boot.img` → `ramdisk` → bandingkan `default.prop` / `init.rc`
- [ ] Tentukan sama ada partition `ivres` wujud pada e.MAS 5

---

## 9. Nota Keselamatan

* Folder ini **22 GB**. Ia dikecualikan daripada git melalui `.gitignore` (lihat entri
  `Geely Xingyuan/`). Backup perlu dibuat secara manual, bukan melalui repo.
* `test-keys` bermaksud imej boleh diubah dan ditandatangani semula. Jangan pasang
  sebarang imej yang ditandatangani semula pada kereta tanpa backup `boot_a`,
  `ecarxpara`, dan `dtbo_a` yang disahkan (rujuk `docs/SESSION_STATE.md` §4).
* `pre-efuse=true` — peranti mesti dalam keadaan efuse yang betul. Salah fuse =
  kemungkinan brick kekal.
