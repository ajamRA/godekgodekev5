# KATALOG TINDAKAN STANDARD — PROJEK e.MAS 5

Dokumen ini mengandungi 14 protokol operasi standard berstruktur bagi sebarang tindakan dalam projek Proton e.MAS 5. Setiap tindakan mempunyai had skop dan sempadan yang jelas.

---

### 1. SAMBUNG ADB SAHAJA
* **Tindakan:** Sambungkan ADB ke alamat terakhir dan sahkan `get-state` sekali.
* **Had Tindakan:** Apabila status `device` diterima, berhenti serta-merta. Jangan periksa aplikasi, servis, VHAL atau log.

---

### 2. PASANG / KEMAS KINI APK
* **Tindakan:** Pasang APK `[laluan tepat]` menggunakan `adb install -r`.
* **Keperluan:** Pastikan APK bertandatangan; jika belum, gunakan kunci aplikasi sedia ada dan hasilkan salinan bertandatangan.
* **Had Tindakan:** 
  - Jangan bina semula jika APK yang dimaksudkan sudah tersedia.
  - Jangan uninstall, paksa downgrade, buka aplikasi atau pasang daemon.
  - Apabila pemasangan melaporkan `Success`, berhenti serta-merta.

---

### 3. SEMAK MASALAH
* **Input Diperlukan:** 
  - Simptom: `[masalah]`
  - Masa kejadian: `[masa / pencetus]`
* **Tindakan:** Jalankan diagnosis bacaan sahaja (*read-only*), bermula dengan maksimum tiga semakan berkaitan.
* **Had Tindakan:** 
  - Ambil log terhad jika perlu. Tambah semakan hanya berdasarkan bukti.
  - Jangan mencetuskan kawalan kenderaan atau melaksanakan pembaikan.
  - Laporkan punca disahkan atau hipotesis, bukti dan cadangan pembaikan.

---

### 4. AMBIL LOG KEJADIAN
* **Tindakan:** Ambil log berkaitan `[aplikasi / servis]` sekitar `[masa kejadian]`.
* **Had Tindakan:** 
  - Simpan salinan pada laptop dan rumuskan ralat penting.
  - Jangan padam log, jangan jalankan rakaman tanpa had, jangan restart proses atau mencetuskan masalah semula.
  - Jika rekod tidak tersedia, nyatakan dengan jujur; jangan anggap tiada masalah.

---

### 5. SEMAK VERSI YANG DIPASANG
* **Tindakan:** Semak versi dan lokasi pakej `[nama pakej]`. Bandingkan dengan APK laptop `[laluan]` jika diminta.
* **Had Tindakan:** Jangan pasang, buka atau hentikan aplikasi. Laporkan versi dipasang dan versi calon secara berasingan.

---

### 6. SALIN SANDARAN
* **Tindakan:** Salin komponen khusus `[laluan fail / nama pakej]` dari kereta ke folder sandaran baharu di laptop.
* **Had Tindakan:** 
  - Catat lokasi asal, masa, saiz dan SHA-256.
  - Jangan menimpa sandaran lama atau mengubah fail sumber.
  - Jangan anggap salinan APK turut menyandarkan data aplikasi.
  - Labelkan sebagai “salinan keadaan semasa”; jangan panggil known-good tanpa bukti.

---

### 7. SEMAK KOD DI LAPTOP
* **Tindakan:** Semak `[fail / fungsi]` untuk masalah `[skop]`.
* **Had Tindakan:** 
  - Bacaan sahaja; jangan ubah kod atau sambung ADB.
  - Senaraikan penemuan dengan lokasi kod, kesan dan pembaikan yang dicadangkan.
  - Bezakan kecacatan yang terbukti daripada andaian tentang perkakasan.

---

### 8. BAIKI KOD DI LAPTOP
* **Tindakan:** Baiki `[masalah]` dalam `[komponen]`.
* **Had Tindakan:** 
  - Kekalkan perubahan pengguna yang lain.
  - Jalankan ujian setempat yang relevan dan bina calon jika diperlukan.
  - Jangan sambung ke kereta atau jalankan skrip deploy.
  - Laporkan perubahan, keputusan ujian dan batas pengesahan.

---

### 9. BINA APK / JAR SAHAJA
* **Tindakan:** Bina `[komponen]` daripada kod semasa menggunakan aliran binaan laptop sahaja.
* **Had Tindakan:** 
  - Pastikan skrip binaan tidak turut memasang atau menghubungi kereta.
  - Simpan hasil dalam folder calon berasingan dan catat SHA-256.
  - Nyatakan sama ada APK bertandatangan.
  - Jangan pasang, jalankan atau mengaktifkan hasil binaan.

---

### 10. PRATONTON ANTARAMUKA
* **Tindakan:** Buka antaramuka di laptop menggunakan data simulasi.
* **Had Tindakan:** 
  - Pastikan pratonton tiada sambungan atau proksi kepada kereta.
  - Semak susun atur, mesej ralat dan keadaan butang.
  - Jangan anggap ujian simulasi membuktikan fungsi pada IHU sebenar.

---

### 11. SEDIAKAN AUDIO CALON
* **Tindakan:** Sediakan audio daripada `[fail sumber]` di laptop mengikut format dan durasi yang dipersetujui.
* **Had Tindakan:** 
  - Semak struktur WAV, saiz, durasi dan fade; hasilkan fail berasingan serta SHA-256.
  - Jangan timpa audio kereta atau restart servis audio.
  - Jangan menjamin keserasian perkakasan hanya berdasarkan format fail.

---

### 12. SEDIAKAN PELAN PEMASANGAN DAEMON / AUDIO
* **Tindakan:** Sediakan pelan untuk calon `[laluan dan SHA-256]`.
* **Had Tindakan:** 
  - Kenal pasti sasaran, cara sandaran, pengesahan pemindahan, pengaktifan dan pemulihan.
  - Semak skrip supaya tiada perubahan sampingan.
  - Jangan pindahkan fail atau hidupkan daemon lagi.
  - Bentangkan arahan tepat untuk kelulusan; memindahkan JAR dan mengaktifkannya ialah dua tindakan berasingan.

---

### 13. PULIHKAN VERSI TERDAHULU
* **Input Diperlukan:** 
  - Komponen: `[APK / JAR / audio]`
  - Sandaran yang hendak digunakan: `[laluan dan SHA-256]`
* **Tindakan:** Semak kesesuaian sandaran dan kesan terhadap data dahulu.
* **Had Tindakan:** 
  - Jika pemulihan boleh dibuat dalam skop yang dibenarkan, laksanakan pada komponen itu sahaja.
  - Jika memerlukan uninstall, kehilangan data atau pengaktifan daemon, terangkan langkah tersebut dan dapatkan arahan khusus dahulu.
  - Jangan pilih sandaran secara tekaan.

---

### 14. RINGKASAN SERAH TUGAS
* **Tindakan:** Hasilkan nota ringkas daripada bukti yang sudah sedia ada:
  1. Objektif dan skop dibenarkan.
  2. Perubahan yang sudah dibuat.
  3. Fail calon dan SHA-256.
  4. Komponen yang benar-benar dipasang pada peranti.
  5. Keputusan ujian tempatan.
  6. Perkara yang belum disahkan.
  7. Langkah seterusnya.
* **Had Tindakan:** Jangan sambung ke kereta semata-mata untuk menghasilkan ringkasan.
