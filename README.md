# Proton e.MAS 5 (Geely EX2 / IHU801P) Repository 🚗⚡

Pusat dokumentasi teknikal induk, artifak teruji di laptop, dan panduan tadbir urus rasmi bagi kenderaan elektrik **Proton e.MAS 5** (Platform `Geely EX2` / `IHU801P` Android 10).

> [!TIP]
> 🗺️ **PETA INDUK DIREKTORI & PANDUAN PANTAS ALATAN:**  
> Untuk mencari sebarang skrip, APK, binary, kod decompile atau dokumentasi, sila rujuk:  
> 🔗 [**`WORKSPACE_INDEX.md` (Panduan Struktur Workspace & Katalog Tool)**](file:///d:/apps/emas-ota/WORKSPACE_INDEX.md)

---

> [!IMPORTANT]
> **PENGESAHAN MODEL KENDERAAN & MOD PEMANDUAN:**
> * Kenderaan ini adalah **Proton e.MAS 5** (bukan e.MAS 7).
> * Mod pemanduan fizikal pengguna adalah **Comfort, Sport, Eco, dan Eco Plus**. Mod "Adaptive" TIDAK wujud pada e.MAS 5.
> * Pemetaan nombor VHAL (1=Comfort, 2=Sport, 3=Eco, 4=Eco Plus) adalah **calon belum disahkan** melalui pemerhatian tetapan kilang, bukan kontrak VHAL rasmi yang dijamin.
> * Sebarang arahan atau fail bertarikh sebelum 27 September 2026 yang merujuk kepada e.MAS 7 atau platform lain adalah arkib sejarah sahaja dan **bukan panduan operasi semasa**.

---

## 🧭 Dokumen Tadbir Urus & SOP Rasmi (Wajib Dipatuhi)

Bagi sebarang tindakan pada projek atau peranti, AI dan pembangun **mesti mematuhi 4 dokumen tadbir urus ini**:

1. 📜 [**`docs/ARAHAN_ASAS_EMAS5.md`**](file:///d:/apps/emas-ota/docs/ARAHAN_ASAS_EMAS5.md)
   * Prinsip teras projek: skop kerja minimum, pengasingan sasaran khusus (`-s <IP>:5555`), larangan imbasan rangkaian (subnet scanning), pematuhan kerahsiaan token/kunci, dan larangan mutasi automatik.
2. 📦 [**`docs/SOP_PEMASANGAN_APK.md`**](file:///d:/apps/emas-ota/docs/SOP_PEMASANGAN_APK.md)
   * Aliran kerja minimum 6 langkah untuk menyambung ADB dan memasang APK sahaja secara selamat tanpa membuang data atau mengusik sistem lain.
3. 🔍 [**`docs/SOP_DIAGNOSIS_MASALAH.md`**](file:///d:/apps/emas-ota/docs/SOP_DIAGNOSIS_MASALAH.md)
   * Tatacara siasatan masalah berstruktur berasaskan bacaan sahaja (*read-only*), terhad kepada maksimum 3 semakan pada satu-satu masa.
4. 📋 [**`docs/KATALOG_TINDAKAN_STANDARD.md`**](file:///d:/apps/emas-ota/docs/KATALOG_TINDAKAN_STANDARD.md)
   * 14 modul tindakan standard berparameter daripada sambungan ADB, semakan versi, kompilasi, persediaan audio, hingga ringkasan serah tugas.

---

## 📊 Status Semasa Projek

* 🧰 [**`EMAS_TOOLBOX_DESKTOP/`**](file:///d:/apps/emas-ota/EMAS_TOOLBOX_DESKTOP) — **EmasTool Desktop Control Center**: Aplikasi desktop mandiri (C# + Node.js Engine) untuk pengesanan automatik kereta Wi-Fi, pengurusan slot APK, audio AVAS & Lock Sound converter, serta sandaran unbrick.
* 📦 [**`PAKEJ_KAWAN_EMAS5/`**](file:///d:/apps/emas-ota/PAKEJ_KAWAN_EMAS5) — **Pakej Mod e.MAS 5 Rakan**: Pakej OTA USB unlock, auto-install Netflix & SmartTube, serta Stealth Mode stereng.
* 🎮 **RetroArch & UI Kokpit:** UI RetroArch dikunci pada tema Ozone gelap (`chmod 0444` + init persistence), dan floating pill `APPS >` dibuang bagi mengekalkan estetik 100% tema asal kilang Proton Flyme Auto.
* 📝 [**`docs/SESSION_STATE.md`**](file:///d:/apps/emas-ota/docs/SESSION_STATE.md) — Rekod status teknikal terkini membezakan keadaan peranti semasa dengan artifak calon laptop.

---

## 🛡️ Laporan Audit & Artifak Calon Terkini (Laptop Sahaja)

Hasil binaan terkini yang telah melalui pengukuhan keselamatan (*hardening*) dan lulus 96+ semakan ujian setempat:

* 📄 [**`docs/HARDENING_REVIEW_20260927.md`**](file:///d:/apps/emas-ota/docs/HARDENING_REVIEW_20260927.md) — Laporan audit forensik VHAL, penyingkiran enforcement loop, dan penyusunan transaksi audio selamat.
  - **JAR Calon:** `artifacts/daemon-hardened-20260927-203523-513/door_pressure_relief-hardened.jar`
  - **Audio Mario Calon (1.30s):** `artifacts/audio-candidate/mario-lock-candidate-1.30s.wav`
* 📄 [**`docs/UI_ALIGNMENT_REVIEW_20260927.md`**](file:///d:/apps/emas-ota/docs/UI_ALIGNMENT_REVIEW_20260927.md) — Laporan penyelarasan UI, penghapusan kemas kini optimistik, dan kawalan allowlist Android Bridge.
  - **APK Calon (Unsigned):** `artifacts/ui-safe-20260927-205647-646/NoorEnergy-safe-ui-unsigned.apk`

---

## 🗄️ Arkib Sejarah (Rujukan Sahaja — Dilarang Digunakan Sebagai SOP Semasa)

Fail-fail berikut mengandungi catatan projek terdahulu, eksperimen awal, atau rujukan silang platform yang kini disimpan dalam folder `docs/`:
* [`docs/DOKUMENTASI_LENGKAP_EMAS5.md`](file:///d:/apps/emas-ota/docs/DOKUMENTASI_LENGKAP_EMAS5.md)
* [`docs/HVAC_VHAL_MAPPING.md`](file:///d:/apps/emas-ota/docs/HVAC_VHAL_MAPPING.md) *(Catatan awal pemetaan HVAC)*
* [`docs/LAPORAN-EX2-OTA.md`](file:///d:/apps/emas-ota/docs/LAPORAN-EX2-OTA.md) & [`docs/HANDOFF-OTA.md`](file:///d:/apps/emas-ota/docs/HANDOFF-OTA.md) *(Sejarah proses root awal)*
