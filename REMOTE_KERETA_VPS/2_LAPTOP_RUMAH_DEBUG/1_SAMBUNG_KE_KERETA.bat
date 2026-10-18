@echo off
setlocal enabledelayedexpansion
title [RUMAH] Sambung ADB ke Kereta Melalui VPS
color 0A

echo ==================================================================
echo   SAMBUNG KE KERETA DARI RUMAH MELALUI VPS (43.159.63.29)
echo ==================================================================
echo.

set "VPS_IP=43.159.63.29"
set "VPS_USER=ubuntu"
set "LOCAL_PORT=5555"
set "REMOTE_PORT=5555"
set "ADB_BIN=%~dp0adb.exe"

echo [1/3] Menutup sebarang proses SSH tunnel lama...
taskkill /f /im ssh.exe >nul 2>nul

echo.
echo [2/3] Membuka SSH Local Tunnel ke VPS di latar belakang...
start /b ssh -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -N -L %LOCAL_PORT%:127.0.0.1:%REMOTE_PORT% %VPS_USER%@%VPS_IP%

timeout /t 2 >nul

echo.
echo [3/3] Menyambung ADB ke 127.0.0.1:%LOCAL_PORT%...
"%ADB_BIN%" disconnect >nul 2>nul
"%ADB_BIN%" connect 127.0.0.1:%LOCAL_PORT%

timeout /t 1 >nul
"%ADB_BIN%" devices

echo.
echo ==================================================================
echo   BERJAYA SAMBUNG KE KERETA!
echo ==================================================================
echo.
echo Pilih tindakan:
echo [1] Buka ADB Shell terus
echo [2] Pantau Logcat (Check ralat Netflix secara langsung)
echo [3] Pulihkan Netflix (Enable dari Stealth Mode)
echo [4] Jalankan scrcpy (Mirror skrin kereta jika dipasang)
echo [5] Keluar
echo.
set /p OPT="Pilihan [1-5]: "

if "%OPT%"=="1" (
    "%ADB_BIN%" -s 127.0.0.1:%LOCAL_PORT% shell
)
if "%OPT%"=="2" (
    echo Mengesan ralat Netflix...
    "%ADB_BIN%" -s 127.0.0.1:%LOCAL_PORT% logcat -c
    "%ADB_BIN%" -s 127.0.0.1:%LOCAL_PORT% logcat *:E | findstr /i "netflix AndroidRuntime FATAL"
)
if "%OPT%"=="3" (
    echo Memulihkan Netflix dari Stealth Mode...
    "%ADB_BIN%" -s 127.0.0.1:%LOCAL_PORT% shell "pm enable com.netflix.mediaclient"
    "%ADB_BIN%" -s 127.0.0.1:%LOCAL_PORT% shell "am start -n com.netflix.mediaclient/.ui.launch.UIWebViewActivity"
    echo Selesai!
    pause
)
if "%OPT%"=="4" (
    scrcpy -s 127.0.0.1:%LOCAL_PORT%
)

pause
