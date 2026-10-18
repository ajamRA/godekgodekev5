@echo off
title Pemasangan Automatik Proton e.MAS 5
color 0b

echo ==================================================================
echo   PEMASANGAN AUTOMATIK PROTON e.MAS 5
echo   (Netflix, SmartTube, & Mod Servis Stereng 5 Saat)
echo ==================================================================
echo.
echo Pastikan:
echo 1. Anda telah flash 1_USB_UNLOCK/update.zip pada kereta anda.
echo 2. Laptop telah disambungkan ke Hotspot/Wi-Fi yang sama dengan kereta.
echo.
pause

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0pasang.ps1"

echo.
echo ==================================================================
echo Proses selesai. Tekan sebarang kekunci untuk keluar.
echo ==================================================================
pause
