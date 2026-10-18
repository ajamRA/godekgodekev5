# e.MAS Companion untuk telefon Android

APK ini ialah aplikasi telefon berasingan (`com.emas.companion`). Ia membuka UI responsif daripada gateway Web Companion pada laptop. Ia tidak menggunakan bridge loopback APK IHU.

Gateway mesti berjalan, laptop mesti tersambung ke IHU melalui ADB, dan telefon mesti boleh mencapai laptop melalui Wi-Fi. Daemon telemetry semasa hanya boleh dicapai melalui sambungan ADB; APK ini belum menyediakan sambungan terus telefon ke kereta tanpa laptop.

Alamat semasa: `http://192.168.1.8:8766/`. Aplikasi mengingati alamat gateway. Tukar melalui butang **Sambungan** jika IP laptop berubah. Port 5555 ditolak dengan mesej yang menjelaskan bahawa ia port ADB.

Build: `./build_phone_companion.ps1`. APK ditandatangani menggunakan kunci projek sedia ada, disahkan dengan apksigner dan zipalign. Tiada pemasangan automatik dilakukan oleh skrip ini.

Pelayan boleh menyediakan APK melalui `/downloads/EmasCompanion-phone.apk` apabila pemboleh ubah `EMAS_PHONE_APK` menunjuk kepada fail binaan tersebut.

Ujian laptop mengesahkan kompilasi, tandatangan, validasi alamat serta frontend Chromium. Runtime WebView pada telefon sebenar masih perlu diuji. Tiada kawalan cermin atau bunyi kereta dijalankan semasa menyediakan APK ini.

Rujukan pelaksanaan WebView: https://developer.android.com/develop/ui/views/layout/webapps/webview
