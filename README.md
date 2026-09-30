# Proton e.MAS 5 (Geely EX2 / IHU801P) Repository 🚗⚡

Pusat dokumentasi teknikal induk, artifak teruji di laptop, dan panduan tadbir urus rasmi bagi kenderaan elektrik **Proton e.MAS 5** (Platform `Geely EX2` / `IHU801P` Android 10).

> [!IMPORTANT]
> **PENGESAHAN MODEL KENDERAAN & MOD PEMANDUAN:**
> * Kenderaan ini adalah **Proton e.MAS 5** (bukan e.MAS 7).
> * Mod pemanduan fizikal pengguna adalah **Comfort, Sport, Eco, dan Eco Plus**. Mod "Adaptive" TIDAK wujud pada e.MAS 5.
> * Pemetaan nombor VHAL (1=Comfort, 2=Sport, 3=Eco, 4=Eco Plus) adalah **calon belum disahkan** melalui pemerhatian tetapan kilang, bukan kontrak VHAL rasmi yang dijamin.
> * Sebarang arahan atau fail bertarikh sebelum 27 September 2026 yang merujuk kepada e.MAS 7 atau platform lain adalah arkib sejarah sahaja dan **bukan panduan operasi semasa**.

---

## 🧭 Dokumen Tadbir Urus & SOP Rasmi (Wajib Dipatuhi)

Bagi sebarang tindakan pada projek atau peranti, AI dan pembangun **mesti mematuhi 4 dokumen tadbir urus ini**:

1. 📜 [**`ARAHAN_ASAS_EMAS5.md`**](ARAHAN_ASAS_EMAS5.md)
   * Prinsip teras projek: skop kerja minimum, pengasingan sasaran khusus (`-s <IP>:5555`), larangan imbasan rangkaian (subnet scanning), pematuhan kerahsiaan token/kunci, dan larangan mutasi automatik.
2. 📦 [**`SOP_PEMASANGAN_APK.md`**](SOP_PEMASANGAN_APK.md)
   * Aliran kerja minimum 6 langkah untuk menyambung ADB dan memasang APK sahaja secara selamat tanpa membuang data atau mengusik sistem lain.
3. 🔍 [**`SOP_DIAGNOSIS_MASALAH.md`**](SOP_DIAGNOSIS_MASALAH.md)
   * Tatacara siasatan masalah berstruktur berasaskan bacaan sahaja (*read-only*), terhad kepada maksimum 3 semakan pada satu-satu masa.
4. 📋 [**`KATALOG_TINDAKAN_STANDARD.md`**](KATALOG_TINDAKAN_STANDARD.md)
   * 14 modul tindakan standard berparameter daripada sambungan ADB, semakan versi, kompilasi, persediaan audio, hingga ringkasan serah tugas.

---

## 📊 Status Semasa Projek

* 📝 [**`SESSION_STATE.md`**](SESSION_STATE.md) — Rekod status teknikal terkini (27 September 2026) membezakan keadaan peranti semasa dengan artifak calon laptop.

---

## 🛡️ Laporan Audit & Artifak Calon Terkini (Laptop Sahaja)

Hasil binaan terkini yang telah melalui pengukuhan keselamatan (*hardening*) dan lulus 96+ semakan ujian setempat:

* 📄 [**`HARDENING_REVIEW_20260927.md`**](HARDENING_REVIEW_20260927.md) — Laporan audit forensik VHAL, penyingkiran enforcement loop, dan penyusunan transaksi audio selamat.
  - **JAR Calon:** `artifacts/daemon-hardened-20260927-203523-513/door_pressure_relief-hardened.jar`
  - **Audio Mario Calon (1.30s):** `artifacts/audio-candidate/mario-lock-candidate-1.30s.wav`
* 📄 [**`UI_ALIGNMENT_REVIEW_20260927.md`**](UI_ALIGNMENT_REVIEW_20260927.md) — Laporan penyelarasan UI, penghapusan kemas kini optimistik, dan kawalan allowlist Android Bridge.
  - **APK Calon (Unsigned):** `artifacts/ui-safe-20260927-205647-646/NoorEnergy-safe-ui-unsigned.apk`

---

## 🗄️ Arkib Sejarah (Rujukan Sahaja — Dilarang Digunakan Sebagai SOP Semasa)

Fail-fail berikut mengandungi catatan projek terdahulu, eksperimen awal, atau rujukan silang platform:
* `DOKUMENTASI_LENGKAP_EMAS5.md`
* `HVAC_VHAL_MAPPING.md` *(Catatan awal pemetaan HVAC)*
* `LAPORAN-EX2-OTA.md` & `HANDOFF-OTA.md` *(Sejarah proses root awal)*
