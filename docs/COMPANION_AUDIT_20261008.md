# Audit EmasEnergyApp / Web Companion — 8 Oktober 2026

Audit dan pembaikan ini meliputi frontend kanonik `EmasEnergyApp/assets`, gateway `energy-web`, kedua-dua `MainActivity`, klien HTTP Java, laluan lock sound MegaphoneDaemon, dan laluan HTTP DoorPressureReliefDaemon. Pemeriksaan dibuat pada kod dalam workspace, bukan pada firmware atau kereta yang sedang berjalan. Perubahan terdahulu dalam workspace dikekalkan; salinan fail sebelum audit tersedia dalam `artifacts/companion-audit-20261008/before`.

## Penemuan dan pembaikan

| Tahap | Penemuan yang disahkan dalam kod sebelum audit | Penyelesaian |
|---|---|---|
| P1 | Pemilih nada hanya menyimpan `selected`; tiada bacaan WAV aktif. `/api/locksound/status` belum wujud dalam daemon ini. | Endpoint status membandingkan hash WAV OEM dengan preset kanonik dan `lock_active.wav`. Pilihan pengguna dan WAV aktif dipaparkan berasingan. |
| P1 | Selepas reboot, WAV OEM boleh berbeza daripada salinan nada tersimpan; UI tiada indikator ketidakpadanan. | `active_sound`, `desired_sound` dan `sync_required`; butang **Sync to Car** menggunakan pemasangan atomik sedia ada. Tiada penulisan atau restart radar ketika membaca status. |
| P1 | `energy-web/serve.cjs` lama mencuba hos seterusnya pada timeout dan sekali lagi pada error. POST yang telah dihantar boleh diulang; badan stream juga tidak boleh dimainkan semula dengan selamat. | Gateway baharu menggunakan satu destinasi bagi setiap request, satu queue bersiri, had queue dan deadline. Arahan lock/cermin dihantar sekali sahaja. |
| P1 | Daemon telemetri 8888 dan cermin 8885 bind ke `127.0.0.1`; input IP kereta sahaja tidak menjadikannya boleh dicapai telefon. | Web Companion menggunakan gateway pada komputer dan port ADB forward. Mod IP LAN disokong untuk destinasi yang benar-benar boleh dicapai gateway. Bind daemon dikekalkan. |
| P1 | `DoorPressureReliefDaemon` menggunakan `path.startsWith("/api/window")`; GET `/api/window/status` termasuk dalam laluan yang membuka cermin. `contains("percent=0")` juga menerima nilai seperti `01`. | `WindowRequestPolicy` memadankan method dan laluan tepat. `/api/window/status` ialah bacaan sahaja. Laluan/parameter lain ditolak. Parser HTTP sedia ada digunakan dengan had saiz dan socket ditutup walaupun gagal. |
| P1 | Kawalan Heat Vent menghantar `/api/ventilate` atau `/api/close` yang ditolak kontrak frontend, kemudian memanggil bridge lama yang turut disekat. UI tetap menukar toggle. | Dua tindakan jelas — **Buka sedikit** / **Tutup semua** — melalui port 8885. Tiada perubahan kedudukan optimistik; kejayaan hanya bermaksud arahan diterima daemon. |
| P1 | Frunk/but menukar status pintu tempatan selepas HTTP berjaya walaupun sensor belum mengesahkan perubahan. | Tunggu telemetri sensor; satu arahan pada satu masa; kawalan memerlukan bacaan sambungan yang masih segar. |
| P1 | Kegagalan `/api/energy` hanya membersihkan bateri. Gear, pintu, tali pinggang dan graf boleh kekal kelihatan hidup. | Model telemetri menormalkan setiap sampel. Sampel hilang, luput atau aplikasi dijeda membersihkan semua medan berkaitan, trip dan sejarah graf. |
| P2 | Interval 800 ms kekal ketika aplikasi tersembunyi; versi web lama tiada guard request bertindih. | Polling selepas request selesai, interval asas 1.2 s, backoff hingga 15 s, pause/resume berasaskan visibility dan lifecycle native. Respons lama selepas pause diabaikan. |
| P2 | Senarai/apply lock berjalan pada thread berasingan daripada request kenderaan. | Kedua-dua varian Java menggunakan queue native bersiri yang sama. Arahan apply yang sudah terlalu lama dalam queue ditolak sebelum dihantar. |
| P2 | `TelemetryHttpClient` tidak menghadkan badan respons, boleh membaca error stream null, dan menutup reader hanya pada laluan berjaya. | Had 32 KiB, deadline, pemeriksaan stream null, dan try-with-resources. `VehicleHttpClient` juga mempunyai deadline bacaan. Ralat JSON/HTTP dipaparkan sebagai kegagalan, bukan kejayaan. |
| P2 | `onResume` varian Noor me-reload WebView, membuang keadaan transaksi. `onDestroy` belum memusnahkan WebView dan callback handler. | Pause/resume tanpa reload rutin; release preview, penutupan executor, handler dan WebView. Watchdog preview menghentikan pemain yang masih menunggu. |
| P2 | Butang tuning ada `onclick` dan event delegation serentak; slider dalam `#main` pula disekat oleh early return. | Satu laluan event bagi butang tersebut; slider tidak lagi ditolak hanya kerana berada dalam main. Kawalan yang disekat native ditandakan tidak tersedia. |
| P2 | Suhu contoh, anggaran jarak gantian, penghuni kabin berdasarkan tali pinggang, serta peratus regen/HVAC dan kadar kos tetap dipaparkan seolah-olah data sebenar. | Medan tanpa data menunjukkan `—` / `Tiada data`; tiada inferens penghuni daripada belt sahaja. Angka peratus/kos tanpa sumber dikeluarkan. HVAC hanya dipaparkan apabila sampelnya tersedia. |
| P2 | Daemon telemetri menggunakan V × I sebagai `motor_power_kw`. UI menamakan kuasa masuk sebagai regen walaupun ia mungkin pengecasan. | Paparan mengenal pasti sumber ini sebagai **Kuasa pek · V × I**. Arah masuk ditandakan cas / rekuperasi; tiada dakwaan kuasa mekanikal motor atau mod pengecasan tanpa medan sumber. |
| P2 | Layout telefon mempunyai scroll bersarang, slider tuning melimpah pada 320 px, dan navbar/toast tidak mengambil kira safe area. | Satu scroll dokumen pada telefon, grid responsif, input 16 px, tindakan utama 48 px, safe-area padding dan feedback visual/haptic jika browser menyokongnya. |

## Struktur kod yang boleh terus digunakan

- `EmasEnergyApp/assets/companion.css`: gaya tambahan; tema gelap, susunan telefon, focus, safe area dan touch target.
- `EmasEnergyApp/assets/companion-client.js`: HTTP gateway, queue, timeout, retry bacaan sahaja, polling dan adapter pemilih lock bagi browser.
- `EmasEnergyApp/assets/telemetry-model.js`: normalisasi data dan penolakan sampel luput.
- `EmasEnergyApp/assets/lock-sound.js`: pilihan nada, WAV aktif, status sync, callback berkorelasi dan watchdog.
- `EmasEnergyApp/assets/app.js`, `index.html`, `power-graph.js`: integrasi dan paparan yang dikemas kini.
- `EmasEnergyApp/src/com/noor/thenoorcar/WindowHttpClient.java`: dua tindakan cermin melalui loopback 8885.
- `LockSoundHttpClient.java`, `TelemetryHttpClient.java`, `VehicleHttpClient.java` dan kedua-dua `MainActivity.java`: integrasi native dan lifecycle.
- `energy-web/gateway.cjs`, `serve.cjs`: frontend kanonik dan gateway dengan sesi cookie, pemeriksaan origin/hos, had badan dan routing port tepat.
- `src_megaphone/com/emas/megaphone/MegaphoneDaemon.java`: endpoint status WAV.
- `src_window/com/emas/window/WindowRequestPolicy.java`, `DoorPressureReliefDaemon.java`: routing status/cermin yang tepat.
- `build_companion.ps1`: bina kedua-dua APK dan dua daemon ke direktori baharu; tidak memasang atau menjalankannya pada kereta.

`LocalVehicleBridge` juga diperiksa. Laluan kawalan legasinya masih ditolak oleh `VehicleRequestPolicy`; pembaikan ini tidak menghidupkan tool HVAC/AI yang kontraknya belum disahkan.

## Kontrak lock sound

```json
{
  "ok": true,
  "active_sound": "original",
  "desired_sound": "mario",
  "target_sha256": "<hash WAV OEM yang dibaca>",
  "sync_required": true,
  "active_match_count": 1,
  "playback_verified": false,
  "observed_at_ms": 0
}
```

Contoh di atas menerangkan keadaan selepas WAV OEM diganti semula sedangkan salinan tersimpan masih Mario. Nilai hash dan masa sebenar datang daripada daemon. `active_sound` ialah nama yang sepadan dengan fail, bukan pengesahan bunyi speaker. Jika dua preset mempunyai PCM yang sama, nama aktif ialah `null`. Jika mirror tiada, `sync_required` ialah `null`, bukan `false`. Daemon lama yang membalas 404 menghasilkan status belum disahkan.

Endpoint `POST /api/locksound/apply` dikekalkan. Respons hilang tidak mencetuskan penulisan semula; UI membaca status untuk menentukan keadaan fail. Pemasangan masih memerlukan baseline asal yang sah, format WAV serasi dan syarat pemasang atomik sedia ada. Restart radar dan pengesahan OEM playback kekal tertangguh.

## Menjalankan Web Companion di telefon

Dalam PowerShell di `D:\apps\emas-ota`, gunakan serial peranti yang telah disahkan sendiri. Arahan di bawah ialah panduan; ia tidak dijalankan semasa audit ini.

```powershell
adb -s SERIAL_KERETA forward tcp:18888 tcp:8888
adb -s SERIAL_KERETA forward tcp:18890 tcp:8890
adb -s SERIAL_KERETA forward tcp:18885 tcp:8885
node energy-web/serve.cjs
```

1. Komputer gateway mesti mempunyai sambungan ADB yang dibenarkan ke IHU. Telefon dan komputer mesti berada pada rangkaian yang boleh saling mencapai.
2. Buka `http://IP_KOMPUTER:8766` dalam Chrome Android atau Safari iOS. Benarkan akses gateway pada rangkaian tempatan jika sistem meminta; jangan gunakan IP loopback telefon sebagai alamat gateway.
3. Tekan **Cari kereta**. Gateway memeriksa sambungan terakhir dan ADB forward/default yang dikonfigurasi; ia tidak mengimbas keseluruhan subnet.
4. Untuk daemon yang memang boleh dicapai melalui LAN, masukkan IP kereta dan tekan **Sambung**. Untuk laluan ADB, gunakan `127.0.0.1` pada input — alamat ini dinilai oleh komputer gateway.
5. Buka **Controls → Nada kunci**. Pratonton browser dimainkan pada telefon; pratonton native dimainkan dalam kabin.
6. Pemeriksaan WAV sebenar memerlukan daemon Megaphone yang mengandungi endpoint status baharu. Binaan tersedia dalam pakej hasil; daemon terpasang belum diubah.

Auto-detect ini ialah carian alamat yang sudah diketahui/tunnel, bukan penemuan IP rawak merentasi subnet. IP baharu yang belum diketahui memerlukan input manual atau konfigurasi router/DHCP. `EMAS_CAR_IP` boleh menetapkan destinasi lalai LAN, dan `EMAS_COMPANION_PORT` boleh menukar port gateway.

Gateway ini untuk rangkaian tempatan dan tidak mempunyai akaun pengguna. Ia menggunakan sesi browser serta pemeriksaan same-origin untuk menolak request daripada laman lain. Jangan menganggapnya gateway internet atau sistem kawalan jauh berautentikasi. `energy-web/app.js` dan `index.html` lama dikekalkan sebagai arkib; pelayan baharu menyajikan aset kanonik daripada `EmasEnergyApp/assets`.

## Ujian penerimaan di telefon dan kereta

| Ujian | Langkah | Hasil yang dijangka |
|---|---|---|
| Skrin telefon | Buka Overview, Battery, Power, Trip, Climate dan Controls; cuba portrait/landscape, dark/light, zoom teks dan scroll hingga hujung. | Tiada scroll mendatar; lock/cermin boleh disentuh; bar bawah tidak menutup tindakan terakhir. |
| Pilihan vs aktif | Pastikan Mario aktif, pilih Fah tanpa menyimpan. | Pilihan ialah Fah; WAV aktif kekal Mario. |
| Sync | Tekan Sync to Car sekali, kemudian Semak status. | WAV sepadan dengan pilihan selepas pengesahan fail. Uji bunyi sebenar ketika mengunci; file commit sahaja tidak membuktikan playback. |
| Reboot | Dengan kereta diparkir, reboot IHU dan buka semula aplikasi selepas daemon tersedia. | Jika fail kembali Original, UI menunjukkan Original dan Perlu sync apabila mirror tersimpan berbeza. |
| Respons terputus | Putuskan sambungan ketika apply sedang berlangsung. | Tiada apply kedua automatik. Status belum disahkan atau hasil pemeriksaan fail sebenar dipaparkan. |
| Tukar rangkaian | Tukar hotspot ke Wi-Fi rumah/sekolah; pulihkan ADB dan buka alamat gateway baharu jika IP komputer berubah. | Banner offline muncul, bacaan lama dibersihkan; Cari kereta/input manual menyambung semula. |
| Sleep | Biarkan IHU tidur, kemudian bangunkan. | Timeout terbatas, backoff, banner offline/mungkin sleep; polling pulih apabila destinasi sama boleh dicapai semula. Sleep tidak didakwa sebagai punca yang pasti. |
| Background | Minimize/lock telefon selama 30–60 s, kembali ke aplikasi. | Polling berhenti ketika hidden; hanya satu kitaran bersambung semula; tiada data lama ditandakan aktif. |
| JSON/HTTP | Gunakan fixture untuk respons kosong, bukan JSON, 404, 500 dan saiz melebihi had. | UI tidak hang, tidak menunjukkan kejayaan palsu, dan kawalan dilepaskan selepas timeout. |
| Cermin | Dengan kawasan cermin diperiksa, tekan Buka sedikit/Tutup semua sekali. | Arahan dihantar melalui 8885; semak pergerakan sebenar. Tiada toggle kedudukan optimistik. |
| Status cermin | Selepas daemon baharu dipasang, baca `/api/window/status` beberapa kali melalui loopback. | Hanya metadata status; tiada pergerakan cermin. |
| Daemon lama | Gunakan daemon tanpa endpoint status lock. | UI menerangkan bahawa WAV aktif belum boleh disahkan. |

## Hasil verifikasi tempatan

- **35/35** ujian Node frontend/gateway lulus: serialisasi, had queue, timeout, respons kosong, retry bacaan sahaja, status reboot, asal request dan routing.
- **71** semakan Java lock sound/installer lulus, termasuk perbandingan fail sebenar dan reboot overwrite pada filesystem laptop.
- **42** semakan native HTTP/decoder lulus menggunakan sambungan mock.
- **16** semakan parser/routing cermin lulus; tiada VHAL atau panggilan ke kereta dibuat.
- **36** kombinasi halaman/lebar skrin diuji dalam Chromium headless: enam halaman pada 320, 360, 390, 430, 768 dan 1280 px. Tiada horizontal overflow atau page error. Aliran pilihan/aktif, kehilangan respons apply, dispatch cermin, pause/resume dan offline turut diuji dengan daemon fixture.
- Kedua-dua varian aplikasi dikompil, ditukar kepada DEX, dibungkus dan disemak alignment APK. Dua daemon turut dibina sebagai DEX JAR. APK masih **unsigned**.

Log: `artifacts/companion-audit-20261008/final-build.log`, `web-tests.log`, `browser-tests.log`. Screenshot menggunakan data simulasi fixture; bukan bukti bacaan atau bunyi kereta.

Binaan akhir: `artifacts/companion-audit-20261008/final-build/` mengandungi `NoorEnergy-companion-unsigned.apk`, `EmasEnergy-companion-unsigned.apk`, `megaphone_daemon.jar`, `door_pressure_relief_daemon.jar` dan `SHA256SUMS.txt`.

Untuk membina semula:

```powershell
./build_companion.ps1
node tests/companion-browser.cjs
```

Skrip bergantung pada `android.jar`, `r8.jar`, JDK dan Android build tools yang sedia ada dalam workspace ini. APK perlu ditandatangani dengan kunci yang serasi sebelum digunakan untuk menggantikan aplikasi terpasang. Pemasangan daemon/APK, reboot sebenar, bunyi speaker, pergerakan cermin, Android 10 WebView sebenar dan Safari/iOS sebenar belum diuji.

## Batas browser dan PWA

Frontend yang sama boleh digunakan dalam shell WebView/PWA, tetapi audit ini tidak menambah service worker atau pemasangan PWA baharu. Gateway LAN yang disediakan menggunakan HTTP pada origin yang sama. Hosting HTTPS awam yang membuat request terus ke HTTP kereta memerlukan pertimbangan mixed content, izin rangkaian tempatan dan konfigurasi origin gateway; jangan menganggap ia berfungsi sama pada semua browser. Rujukan: [Chrome Local Network Access](https://developer.chrome.com/blog/local-network-access), [MDN mixed content](https://developer.mozilla.org/en-US/docs/Web/Security/Defenses/Mixed_content). Pembatalan HTTP menggunakan [AbortController](https://developer.mozilla.org/en-US/docs/Web/API/AbortController), bersama deadline untuk memastikan promise UI tetap selesai.
