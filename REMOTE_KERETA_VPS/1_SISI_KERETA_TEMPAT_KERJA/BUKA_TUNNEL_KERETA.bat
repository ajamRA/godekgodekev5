@echo off
setlocal enabledelayedexpansion
title [SISI KERETA] Buka Sambungan Remote ke VPS
color 0B

echo ==================================================================
echo   PROTON e.MAS 5 - PELANCAR REVERSE TUNNEL (SISI KERETA)
echo ==================================================================
echo.

set "VPS_IP=43.159.63.29"
set "VPS_USER=ubuntu"
set "REMOTE_PORT=5555"
set "LOCAL_ADB_PORT=5555"
set "ADB_BIN=%~dp0adb.exe"

:: 1. Tentukan sambungan ke kereta
echo [1/3] Mengesan sambungan ke skrin IHU kereta...
"%ADB_BIN%" devices | findstr /R /C:"[0-9]*\.[0-9]*\.[0-9]*\.[0-9]*:5555.*device" >nul
if %errorlevel% equ 0 (
    echo [+] Sambungan ADB ke kereta sedia ada dikesan!
) else (
    echo [*] Sila pastikan laptop ini disambungkan ke Hotspot / Wi-Fi kereta.
    set /p CAR_IP="Masukkan IP Kereta (contoh: 192.168.43.150): "
    if not "!CAR_IP!"=="" (
        echo Menyambung ke !CAR_IP!:5555...
        "%ADB_BIN%" disconnect >nul 2>nul
        "%ADB_BIN%" connect !CAR_IP!:5555
    )
)

echo.
echo [2/3] Membuka port forward lokal ADB...
"%ADB_BIN%" forward tcp:%LOCAL_ADB_PORT% tcp:5555 >nul 2>nul

echo.
echo ==================================================================
echo [3/3] Melancarkan Sambungan ke VPS (%VPS_IP%)...
echo ------------------------------------------------------------------
echo Status: SEDANG BERJALAN.
echo Sila biarkan tetingkap hitam ini TERBUKA sepanjang sesi sokongan.
echo (Tekan Ctrl+C atau pangkah tetingkap untuk tamatkan sesi).
echo ==================================================================
echo.

ssh -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -o ExitOnForwardFailure=yes -N -R %REMOTE_PORT%:127.0.0.1:%LOCAL_ADB_PORT% %VPS_USER%@%VPS_IP%

if %errorlevel% neq 0 (
    echo.
    echo [INFO] Sambungan ditamatkan atau terputus.
    pause
)
