# Penyelarasan UI dan jambatan Android — laptop sahaja

## Hasil akhir

- APK calon **unsigned**, tidak dipasang: `artifacts/ui-safe-20260927-205647-646/NoorEnergy-safe-ui-unsigned.apk`
- SHA-256: `311123439bcac2515157cab3c656f68e473b5171035a127ff73ad183a687c4fe`
- Builder tempatan: `build_safe_ui.ps1`
- Pratonton tanpa sambungan: `node tools/preview_safe_ui.cjs`, kemudian `http://127.0.0.1:8767/#drivemode`.
- Pratonton dengan jambatan mock dalam memori: `http://127.0.0.1:8767/?mock=1#drivemode`.

Tiada ADB, deploy, pemasangan APK, signing atau sambungan kereta/VPS dijalankan. JAR daemon hasil kerja terdahulu tidak diubah. Script binaan baharu menggunakan folder unik dan tidak menindih APK lama.

## Kontrak UI

Regen menggunakan satu `POST /api/drive_mode`, Content-Type JSON, body seperti `{"regen":2}`. Tiada query parameter, duplicate dispatch atau retry automatik. Nilai mesti integer 1–3. Klik ketika arahan sedang dihantar tidak menghasilkan permintaan kedua.

UI memisahkan `requested`, bacaan terakhir dan status arahan. Respons berjaya hanya dipaparkan sebagai **arahan dihantar, perubahan belum disahkan**. Nilai yang diminta tidak dimasukkan secara optimistik ke bacaan terakhir. Bacaan yang sama pada poll berikutnya juga tidak dianggap pengesahan sebab daemon tidak menyediakan korelasi/timestamp ECU.

HTTP 4xx/5xx dilaporkan gagal/ditolak. Timeout, kehilangan respons atau envelope kejayaan yang tidak menepati kontrak memberi status tidak diketahui, kerana arahan mungkin telah dilaksanakan. Tiada retry automatik dalam aliran baharu.

Sebelum mengaktifkan regen, UI memerlukan struktur respons daemon baharu: `candidate_controls_enabled:false`, `confirmed:false`, `dispatch_requested:false`, `lock.enabled:false` dan objek `current`. Ini menghalang daemon lama yang hanya memulangkan `ok:true` daripada mengaktifkan UI. Snapshot sambungan lebih 5 saat tidak boleh digunakan untuk menghantar arahan baharu.

Suis enforcement dibuang. Drive mode calon, Comfort Brake dan rasa pedal brek dipaparkan disabled. Untuk versi semakan ini, semua kawalan kenderaan lain pada halaman utama turut disabled sehingga kontrak responsnya disahkan; gunakan UI kilang. Halaman navigasi dan tema masih tersedia. Pemetaan mod 1–4 tidak dipersembahkan sebagai keadaan kereta yang telah disahkan.

## Jambatan Android

`requestVehicle(id, method, path, body)` memberi callback ber-ID `onVehicleResponse`. Destination tetap `127.0.0.1:8888`. Allowlist menerima GET energy/status/drive_mode dan satu POST regen sahaja. Endpoint lain, query command, nombor tidak sah, duplicate field dan payload berbilang arahan ditolak sebelum membuka sambungan.

HTTP status termasuk error stream dipulangkan ke JS. Transport mempunyai connect/read timeout, had respons 32 KiB, redirects dimatikan dan tiada retry aplikasi. Queue native dibatasi; kerja yang menunggu lebih 3 saat ditolak sebelum dihantar. Callback selepas lifecycle activity tamat tidak digunakan. JS mengabaikan callback lewat/tidak dikenali dan mengehadkan permintaan tertunggak.

Laluan mutasi legacy fire-and-forget dalam MainActivity disekat supaya tidak memintas jambatan baharu. LocalVehicleBridge menolak GET kepada endpoint kawalan. WebView hanya menggunakan halaman aset tempatan untuk jambatan; universal file access dan network loads daripada WebView dimatikan. Ciri rangkaian embedded seperti peta iframe tidak tersedia dalam calon ini. HTTPS/native features lain di luar aliran ini bukan skop pengesahan.

Source MainActivity asal di `com/emas/energy` dan salinan package Noor diselaraskan supaya builder lama yang menjana salinan Noor tidak memadam perubahan jambatan. Asset UI kanonik yang dibungkus APK ialah `EmasEnergyApp/assets`. Salinan demo lama dalam `assets/` dan `energy-web/`, serta frontend/backend VPS, tidak dideploy atau diselaraskan sebagai produk berasingan dalam kerja ini. Jangan gunakan preview proxy lama untuk ujian tanpa kereta; gunakan server baharu yang tiada kod proxy.

## Pengesahan

- 13 ujian Node lulus: POST tepat, integer validation, single dispatch, no optimistic update, HTTP rejection, timeout, stale snapshot, failed polling, legacy daemon rejection, callback ordering, browser tanpa bridge dan rendering disabled.
- 36 pemeriksaan Java mock lulus: allowlist, destination, body JSON tepat, GET read-only, HTTP error, timeout, disconnect dan tiada retry.
- Kompilasi semua source package Noor, D8, AAPT packaging dan zip alignment lulus.
- Pratonton browser diuji: mock bacaan Rendah, klik Tinggi menghasilkan status dihantar/belum disahkan dan bacaan kekal Rendah.
- Browser biasa tanpa mock/Android bridge menunjukkan regen disabled dan label PRATONTON / DATA CONTOH.
- Paparan halaman drive diperiksa secara visual. Runtime Android/WebView sebenar belum diuji; APK tidak ditandatangani atau dipasang.

Calon ini bukan pengesahan keselamatan semua kod lama atau semua telemetry. Ia perlu semakan bersama JAR daemon yang sepadan, kemudian ujian terkawal yang diluluskan pengguna sebelum penggunaan pada kereta.
