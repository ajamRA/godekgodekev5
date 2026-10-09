# ARAHAN ASAS — PROJEK e.MAS 5

Dokumen ini menggariskan prinsip teras, had tindakan, dan disiplin asas yang wajib dipatuhi dalam semua operasi projek Proton e.MAS 5.

---

## 1. Lokasi Projek & Sasaran Peranti
* **Projek Laptop:** `D:\apps\emas-ota`
* **Alamat ADB Terkini:** `192.168.1.13:5555` (Wi-Fi: `AzamRahim`, MAC: `74:04:f0:c3:28:34`)
* **Pengendalian Perubahan Alamat:** Alamat ini diberikan oleh router DHCP dan boleh berubah antara `.11`, `.12`, `.13`, `.19`. Sentiasa semak `adb devices` sebelum menjalankan arahan.
* **Pengasingan Sasaran:** Sentiasa gunakan parameter `-s <IP_SEMASA>:5555` pada setiap arahan terhadap peranti.

---

## 2. Disiplin Kerja & Skop Semakan
* **Semakan Minimum:** Pilih semakan minimum yang berkaitan secara langsung dengan tugas. Jangan scan rangkaian, jangan audit menyeluruh seluruh sistem, dan jangan mengulang semakan yang sudah memberi jawapan tanpa sebab baharu.
* **Status Fail:** Sentiasa bezakan antara fail yang tersedia di laptop dengan komponen yang benar-benar sudah dipasang atau sedang berjalan pada kereta.
* **Integriti Komponen:** Jangan ubah, sentuh, atau pasang komponen lain hanya kerana ia berada dalam folder projek yang sama.
* **Pengasingan Kebenaran:** Kebenaran untuk satu kategori tugas (contoh: pasang APK) tidak merangkumi kategori lain (contoh: daemon, audio, atau probing VHAL).

---

## 3. Keselamatan & Privasi
* **Kerahsiaan:** Jangan sesekali memaparkan rahsia, token, kata laluan, atau kandungan kunci tandatangan (keystore).

---

## 4. Ketelusan Pelaporan
* **Kejujuran Fakta:** Laporkan hasil sebenar, sertakan bukti ringkas, dan nyatakan dengan jelas perkara yang masih belum disahkan. Jangan buat dakwaan atau andaian tanpa bukti.

---

## 5. Pengendalian Ralat
* **Diagnosis Terarah:** Jika sesuatu langkah gagal, buat diagnosis berdasarkan mesej ralat tersebut sahaja.
* **Tindakan Terlarang Semasa Ralat:** Jangan sesekali bertindak secara automatik untuk uninstall aplikasi, memadam data pengguna, reboot sistem, atau me-restart servis sistem melainkan diarahkan secara eksplisit.
