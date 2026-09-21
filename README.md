# Geely EX2 / Geometry IHU Research & Modding 🚗⚡

Koleksi skrip, alat diagnostik, dokumentasi teknikal, dan kaedah modifikasi untuk Head Unit (IHU) **Geely EX2** (Platform Geely Geometry / EMC Series).

---

## 📖 Dokumentasi Lengkap

Sila rujuk panduan langkah demi langkah penuh di:
👉 **[PANDUAN MODIFIKASI LENGKAP GEELY EX2](PANDUAN_MODIFIKASI_GEELY_EX2.md)**

---

## 🌐 Alat Web Dalam Talian (Online Password Generator)

Bagi menjana kod kalkulator 6-digit rolling password secara pantas tanpa perlu memasang sebarang perisian:
👉 **[https://ihucode.netlify.app](https://ihucode.netlify.app)**

* *Menjana kod 6-digit untuk slot 5-minit secara masa nyata (GMT+8).*
* *Menyimpan nombor siri IHU dalam `localStorage` pelayar peranti anda supaya tidak perlu diisi berulang kali.*

---

## 🎯 Ciri-Ciri & Modifikasi yang Disokong

1. **Akses Debug & Root ADB:**
   - Kaedah pengiraan kata laluan dinamik `Engineering Menu`.
   - Akses wireless ADB (`192.168.1.x:5555`) dan root shell.
   - Boot persistence menggunakan skrip `init.rc`.

2. **Pemasangan Aplikasi Hiburan (Native IHU Launcher Integration):**
   - **Netflix** (menggantikan slot JOOX asal).
   - **SmartTube / YouTube Ad-Free** (menggantikan slot TikTok asal).
   - Menggunakan teknik *clean system bind-mount* tanpa merosakkan partition kilang.
   - Ikon paparan, tajuk, dan interaksi skrin sentuh berfungsi lancar pada Flyme Auto / IHU Launcher.

3. **Kawalan Audio & AVAS (Acoustic Vehicle Alerting System):**
   - Penukaran bunyi enjin tiruan / pejalan kaki (Low Speed Pedestrian Warning).
   - Skrip suntingan fail konfigurasi audio kenderaan (`sound_pool` & audio HAL).

4. **Sistem Kemas Kini OTA:**
   - Cara simulasi kemas kini perisian tempatan (Local OTA Payload Server).
   - Pengekstrakan dan analisis `payload.bin` Android A/B.

---

## 📂 Struktur Repositori

```text
├── PANDUAN_MODIFIKASI_GEELY_EX2.md    # Panduan teknikal penuh & rujukan pantas
├── tools/                             # Skrip automasi (Python, ADB, mount tools)
├── workspace_injection/               # Skrip payload & mount persistence
├── netlify_deploy/                    # Antara muka web untuk alatan diagnostik
└── README.md                          # Dokumentasi utama
```

---

## ⚠️ Penafian (Disclaimer)

Projek ini adalah untuk tujuan kajian, penyelidikan peribadi, dan pendidikan sahaja (*proof of concept*). Sebarang modifikasi pada kenderaan adalah atas tanggungjawab pemilik sendiri. Jangan pasang APK pihak ketiga yang mencurigakan atau fail yang tidak diketahui puncanya.
