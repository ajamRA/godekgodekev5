@echo off
setlocal enabledelayedexpansion
title [DIAGNOSTIK] Tarik Log Netflix & Sistem Kereta
color 0E

echo ==================================================================
echo       TARIK LOG DIAGNOSTIK NETFLIX & SISTEM e.MAS 5
echo ==================================================================
echo.

set "ADB_BIN=%~dp0adb.exe"

:: Format timestamp untuk folder log di dalam subfolder logs
for /f "tokens=2 delims==" %%I in ('wmic os get localdatetime /value') do set "dt=%%I"
set "YYYY=!dt:~0,4!"
set "MM=!dt:~4,2!"
set "DD=!dt:~6,2!"
set "HH=!dt:~8,2!"
set "Min=!dt:~10,2!"
set "Sec=!dt:~12,2!"
set "LOG_DIR=%~dp0LOGS\LOG_%YYYY%%MM%%DD%_%HH%%Min%%Sec%"

:: 1. Tentukan sambungan peranti (Tunnel VPS atau Direct IP)
echo [1/4] Memeriksa sambungan peranti ADB...
set "TARGET_DEV="

"%ADB_BIN%" devices | findstr /R /C:"127\.0\.0\.1:5555.*device" >nul
if %errorlevel% equ 0 (
    set "TARGET_DEV=127.0.0.1:5555"
    echo [+] Dikesan bersambung melalui VPS Tunnel: 127.0.0.1:5555
    goto :PULL_LOGS
)

"%ADB_BIN%" devices | findstr /R /C:"[0-9]*\.[0-9]*\.[0-9]*\.[0-9]*:5555.*device" >nul
if %errorlevel% equ 0 (
    for /f "tokens=1" %%d in ('"%ADB_BIN%" devices ^| findstr /R /C:"[0-9]*\.[0-9]*\.[0-9]*\.[0-9]*:5555.*device"') do (
        set "TARGET_DEV=%%d"
    )
    echo [+] Dikesan bersambung terus: !TARGET_DEV!
    goto :PULL_LOGS
)

:: Jika tiada peranti bersambung, cuba sambung ke VPS tunnel
echo [*] Tiada sambungan aktif dikesan.
echo     [1] Sambung melalui VPS Tunnel (43.159.63.29)
echo     [2] Masukkan Alamat IP Kereta Manual
set /p CONN_CHOICE="Pilih [1/2] (Lalai: 1): "
if "%CONN_CHOICE%"=="2" (
    set /p MANUAL_IP="Masukkan IP Kereta: "
    set "TARGET_DEV=!MANUAL_IP!:5555"
    "%ADB_BIN%" connect !TARGET_DEV!
) else (
    echo Membuka sambungan tunnel ke VPS...
    taskkill /f /im ssh.exe >nul 2>nul
    start /b ssh -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -N -L 5555:127.0.0.1:5555 ubuntu@43.159.63.29
    timeout /t 2 >nul
    set "TARGET_DEV=127.0.0.1:5555"
    "%ADB_BIN%" connect 127.0.0.1:5555
)

:PULL_LOGS
echo.
"%ADB_BIN%" -s !TARGET_DEV! get-state | findstr "device" >nul
if %errorlevel% neq 0 (
    echo [RALAT] Gagal berkomunikasi dengan !TARGET_DEV!. Pastikan kereta menyala dan disambungkan.
    pause
    exit /b 1
)

mkdir "%LOG_DIR%" >nul 2>nul
echo [2/4] Folder log dicipta: %LOG_DIR%
echo.

echo [3/4] Sedang menarik status aplikasi, fail sistem dan logcat...

:: A. Status Stealth / Service Mode
echo --- SEMAKAN STATUS MOD SERVIS --- > "%LOG_DIR%\01_status_servis.txt"
"%ADB_BIN%" -s !TARGET_DEV! shell "ls -la /data/local/window/SERVICE_MODE_ACTIVE 2>/dev/null" >> "%LOG_DIR%\01_status_servis.txt"
echo. >> "%LOG_DIR%\01_status_servis.txt"

echo --- SEMAKAN PAKEJ NETFLIX (AKTIF / DISABLE) --- >> "%LOG_DIR%\01_status_servis.txt"
"%ADB_BIN%" -s !TARGET_DEV! shell "pm list packages -d | grep -i netflix" >> "%LOG_DIR%\01_status_servis.txt"
"%ADB_BIN%" -s !TARGET_DEV! shell "pm list packages -u com.netflix.mediaclient" >> "%LOG_DIR%\01_status_servis.txt"
"%ADB_BIN%" -s !TARGET_DEV! shell "pm path com.netflix.mediaclient" >> "%LOG_DIR%\01_status_servis.txt"

:: B. Semakan Bind Mount & Fail APK
echo --- STATUS BIND MOUNT SYSTEM APPS --- > "%LOG_DIR%\02_status_mount.txt"
"%ADB_BIN%" -s !TARGET_DEV! shell "mount | grep -iE 'custom_apps|joox|tiktok'" >> "%LOG_DIR%\02_status_mount.txt"
echo. >> "%LOG_DIR%\02_status_mount.txt"
echo --- SENARAI FAIL DALAM /data/local/custom_apps/Netflix --- >> "%LOG_DIR%\02_status_mount.txt"
"%ADB_BIN%" -s !TARGET_DEV! shell "ls -la /data/local/custom_apps/Netflix/" >> "%LOG_DIR%\02_status_mount.txt"
echo. >> "%LOG_DIR%\02_status_mount.txt"
echo --- SENARAI FAIL DALAM /system/app/JOOXMusic_ACO --- >> "%LOG_DIR%\02_status_mount.txt"
"%ADB_BIN%" -s !TARGET_DEV! shell "ls -la /system/app/JOOXMusic_ACO/" >> "%LOG_DIR%\02_status_mount.txt"
echo. >> "%LOG_DIR%\02_status_mount.txt"
echo --- KANDUNGAN zzz_custom_apps.rc --- >> "%LOG_DIR%\02_status_mount.txt"
"%ADB_BIN%" -s !TARGET_DEV! shell "cat /system/etc/init/zzz_custom_apps.rc 2>/dev/null" >> "%LOG_DIR%\02_status_mount.txt"

:: C. Logcat Crash / Ralat Netflix
echo --- LOGCAT RALAT NETFLIX ^& CRASH --- > "%LOG_DIR%\03_logcat_crash.txt"
"%ADB_BIN%" -s !TARGET_DEV! logcat -d -b main,system,crash -v time | findstr /i "netflix AndroidRuntime FATAL MediaDrm" >> "%LOG_DIR%\03_logcat_crash.txt"

:: D. Logcat Penuh (Simpan untuk arkib)
echo Menyimpan logcat penuh sistem (sila tunggu beberapa saat)...
"%ADB_BIN%" -s !TARGET_DEV! logcat -d -v time > "%LOG_DIR%\04_logcat_penuh.txt"

echo.
echo ==================================================================
echo [4/4] ANALISIS PANTAS DIAGNOSTIK:
echo ==================================================================

findstr /i "SERVICE_MODE_ACTIVE" "%LOG_DIR%\01_status_servis.txt" >nul
if %errorlevel% equ 0 (
    echo [*] STATUS STEALTH: AKTIF! (Netflix sedang disembunyikan oleh Mod Servis!)
    echo     --^> Kawan cuma perlu Hold Volume Down pada stereng 5 saat.
) else (
    echo [+] STATUS STEALTH: TIDAK AKTIF (Mod Normal).
)

findstr /i "package:com.netflix.mediaclient" "%LOG_DIR%\01_status_servis.txt" >nul
if %errorlevel% equ 0 (
    echo [+] PAKEJ NETFLIX: Dikesan terpasang dalam sistem.
) else (
    echo [!] PAKEJ NETFLIX: Tidak dijumpai / belum terpasang dengan lengkap!
)

findstr /i "JOOXMusic" "%LOG_DIR%\02_status_mount.txt" >nul
if %errorlevel% equ 0 (
    echo [+] BIND MOUNT JOOX: Aktif.
) else (
    echo [!] BIND MOUNT JOOX: Tidak aktif (Mount hilang selepas reboot atau belum di-mount).
)

echo.
echo Log penuh telah disimpan dalam folder:
echo %LOG_DIR%
echo.
explorer "%LOG_DIR%"
pause
