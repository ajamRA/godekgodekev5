# SOP Diagnosis & Semakan Masalah IHU Proton e.MAS 5

Dokumen ini merupakan panduan dan had tindakan rasmi bagi sebarang tugas mencari punca masalah (*troubleshooting*) pada IHU Proton e.MAS 5 secara terarah.

---

## 1. Templat Permulaan Aduan
Setiap sesi diagnosis dimulakan dengan maklumat berikut:
* **Masalah yang dialami:** [Simptom sebenar]
* **Bila ia berlaku:** [Contoh: selepas buka aplikasi / selepas pemasangan]
* **Hasil yang sepatutnya:** [Tingkah laku yang dijangka]

---

## 2. Maklumat Sambungan & Akses
* **Folder Projek:** `D:\apps\emas-ota`
* **Sambung terus ke sasaran khusus:**
  ```bash
  adb connect <IP_KERETA>:5555
  adb -s <IP_KERETA>:5555 get-state
  ```
* **Had Tindakan Utama:** Diagnosis ini membenarkan **BACAAN SAHAJA (*Read-Only*)** pada kereta.

---

## 3. Disiplin & Kaedah Diagnosis
1. **Rujuk Laporan & Kod Tempatan Dahulu:** Gunakan laporan audit dan kod tempatan terlebih dahulu. Bezakan maklumat lama daripada keadaan semasa.
2. **Pilih Semakan Paling Kecil:** Pilih semakan paling kecil yang boleh menjelaskan simptom. Jangan audit seluruh sistem.
3. **Maksimum 3 Semakan Pertama:** Mulakan dengan maksimum tiga semakan berkaitan. Selepas itu, rumuskan bukti sebelum menambah semakan lain.
4. **Log Bersasar:** Jika perlukan log, ambil petikan terhad mengikut masa kejadian, proses, atau jenis ralat. Jangan jalankan logcat tanpa had atau memadamkan sebarang log.
5. **Ketetapan Semakan Seterusnya:** Tambah semakan hanya apabila keputusan sebelumnya memberi sebab khusus. Jangan mengulang semakan yang sudah memberikan jawapan.

---

## 4. Panduan Tindakan Mengikut Simptom
* **ADB Gagal:** Semak sambungan dan mesej ralat sahaja. Jangan sesekali scan subnet.
* **APK Gagal Dipasang:** Gunakan mesej ralat pemasangan; semak tandatangan, versi, atau storan hanya jika berkaitan.
* **Aplikasi Tertutup Sendiri (Force Close):** Cari rekod crash aplikasi tersebut dahulu.
* **Paparan Skrin Kosong:** Semak ralat aplikasi/WebView dan aset berkaitan.
* **Butang Tidak Berfungsi:** Semak sama ada butang sengaja dinyahaktifkan, jambatan aplikasi tersedia, dan daemon berjalan. Jangan tekan butang untuk mencetuskan arahan kenderaan.
* **Audio / Radio Bermasalah:** Semak servis dan petikan log berkaitan. Baca tombstone hanya jika ada bukti crash yang relevan.

---

## 5. Had Tindakan Keras (Prohibitions)
* **Dilarang Menulis / Probe VHAL:** Jangan tulis atau probe nilai VHAL, mencuba payload alternatif, atau menguji brek, regen, mod pemanduan, tingkap dan kunci.
* **Dilarang Mengubah Servis/Sistem:** Jangan hidupkan daemon yang dimatikan, restart servis, reboot, memasang fail, mengubah konfigurasi, atau memadam data.
* **Dilarang Memanggil Endpoint Kawalan:** Jangan panggil endpoint kawalan kenderaan walaupun menggunakan HTTP GET (kerana sesetengah endpoint lama mungkin memicu mutasi).
* **Privasi:** Jangan mengambil fail peribadi atau memaparkan token, kata laluan, dan kunci.
* **Pencetusan Semula:** Jika simptom perlu dicetuskan semula melalui tindakan fizikal pada kereta, minta pemilik melakukannya.

---

## 6. Format Laporan Akhir
Laporan diagnosis mesti menyatakan dengan jujur dan jelas:
1. **Apa yang diperiksa dan bukti penting yang ditemui.**
2. **Punca yang disahkan**, atau **hipotesis** jika bukti belum mencukupi.
3. **Apa yang masih belum diketahui.**
4. **Cadangan pembaikan paling kecil.**

*Peraturan Pelaporan:* Jangan mendakwa satu ralat ialah punca utama hanya kerana ia berlaku pada waktu yang hampir sama. Jangan mendakwa kereta selamat sepenuhnya berdasarkan beberapa semakan terhad.
*Kelulusan:* Berhenti apabila bukti cukup untuk menjelaskan masalah atau apabila langkah seterusnya memerlukan perubahan pada kereta. Bentangkan pembaikan yang dicadangkan kepada pemilik sebelum melaksanakannya.
