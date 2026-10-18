# SOP Pemasangan APK pada IHU Proton e.MAS 5

Dokumen ini merupakan panduan dan had tindakan rasmi bagi sebarang tugas penyambungan ADB dan pemasangan APK pada IHU Proton e.MAS 5.

---

## 1. Skop & Had Tindakan
* **Tugas terhad:** Menyambung ADB dan memasang APK sahaja.
* **Dilarang keras:**
  - Meluaskan skop kepada audit, probing VHAL, atau ujian fungsi kenderaan.
  - Membaca `dumpsys car_service`, menjalankan `logcat` panjang, atau membaca `tombstones`.
  - Memasang atau menghidupkan daemon/JAR, mengubah skrip startup, atau mengusik fail audio.
  - Menulis ke partition `/system`, remount, reboot IHU, uninstall, atau memadam data aplikasi.
  - Membuka aplikasi secara automatik atau menguji kawalan kenderaan selepas pemasangan.
  - Menjalankan skrip deploy umum yang mengubah komponen lain.

---

## 2. Maklumat Sambungan & APK
* **Folder Projek:** `D:\apps\emas-ota`
* **Alamat ADB Wi-Fi:** Mengikut IP semasa kereta pada SSID yang sedang bersambung (contoh terakhir: `192.168.0.178:5555`).
* **Sasaran Peranti:** Sentiasa gunakan parameter `-s <IP_KERETA>:5555` bagi setiap arahan.
* **Calon APK:** Ditandatangani menggunakan kunci tandatangan aplikasi sedia ada. Jangan cipta kunci baharu untuk menggantikan aplikasi sedia ada. Jangan paparkan kata laluan atau kandungan kunci.

---

## 3. Aliran Kerja Minimum (6 Langkah)
1. **Cari executable ADB:** Semak melalui `PATH` dahulu. Jika tiada, semak lokasi Android SDK projek yang diketahui sahaja.
2. **Sambung terus:**
   ```bash
   adb connect <IP_KERETA>:5555
   ```
3. **Sahkan sasaran sekali:**
   ```bash
   adb -s <IP_KERETA>:5555 get-state
   ```
4. **Sahkan APK bertandatangan:** Pastikan APK calon sudah ditandatangani dengan kunci yang betul. Jika APK siap bertandatangan sudah tersedia, gunakan terus tanpa bina semula.
5. **Pasang tanpa membuang data:**
   ```bash
   adb -s <IP_KERETA>:5555 install -r "<LALUAN_APK_BERTANDATANGAN>"
   ```
6. **Selesai:** Apabila ADB melaporkan `Success`, pemasangan selesai. Laporkan laluan APK dan keputusan pemasangan, kemudian **berhenti serta-merta**. Jangan buka aplikasi secara automatik.

---

## 4. Pengendalian Ralat
* **Sambungan Gagal:** Laporkan mesej sebenar dan minta pengguna mengesahkan IP atau status ADB di IHU. Jangan scan subnet, cuba banyak IP, atau mengulang tanpa sebab.
* **Unauthorized:** Minta pengguna meluluskan dialog ADB pada skrin IHU.
* **Kunci Tandatangan Tiada:** Minta lokasi kunci atau fail APK bertandatangan.
* **Tandatangan Tidak Sepadan / Versi Ditolak:** Berhenti dan laporkan. Jangan sesekali uninstall, padam data, paksa downgrade, atau menukar kunci sendiri.

---

## 5. Prinsip Pengesahan
* Pemasangan berjaya hanya mengesahkan fail APK telah dipasang pada sistem Android; ia **bukan** pengesahan fungsi atau keselamatan pada kenderaan.
* Kebenaran pemasangan APK tidak merangkumi pemasangan atau pengaktifan daemon.
