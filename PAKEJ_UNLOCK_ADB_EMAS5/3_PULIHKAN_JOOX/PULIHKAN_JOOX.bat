@echo off
title Pulihkan JOOX & TikTok Proton e.MAS 5
color 0a

echo ==================================================================
echo   PEMULIHAN PANTAS SLOT JOOX MUSIC ^& TIKTOK PROTON e.MAS 5
echo ==================================================================
echo.
echo Skrip ini akan mencabut bind-mount dan mengembalikan fail asal kilang.
echo.

set ADB="%~dp0adb.exe"
if not exist %ADB% set ADB=adb

set /p TARGET_IP="Masukkan Alamat IP Kereta (contoh: 192.168.1.16 atau 192.168.43.xxx): "

if "%TARGET_IP%"=="" (
    echo [RALAT] Alamat IP diperlukan.
    pause
    exit /b 1
)

echo.
echo Menyambung ke %TARGET_IP%:5555...
%ADB% disconnect %TARGET_IP%:5555 >nul 2>&1
%ADB% connect %TARGET_IP%:5555

echo.
echo Memulihkan partition sistem dan mencabut mount...
%ADB% -s %TARGET_IP%:5555 shell "mount -o remount,rw /system 2>/dev/null || mount -o remount,rw / 2>/dev/null"
%ADB% -s %TARGET_IP%:5555 shell "rm -f /system/etc/init/zzz_custom_apps.rc"
%ADB% -s %TARGET_IP%:5555 shell "umount -l /system/app/JOOXMusic_ACO 2>/dev/null"
%ADB% -s %TARGET_IP%:5555 shell "umount -l /system/app/ttincar_tiktok_ACO 2>/dev/null"
%ADB% -s %TARGET_IP%:5555 shell "mount -o remount,ro /system 2>/dev/null || mount -o remount,ro / 2>/dev/null"

echo.
echo Selesai! Me-reboot skrin IHU kereta untuk memuatkan semula JOOX ^& TikTok kilang...
%ADB% -s %TARGET_IP%:5555 reboot

echo.
echo ==================================================================
echo Skrin IHU sedang reboot. Selepas menyala, JOOX ^& TikTok akan kembali!
echo ==================================================================
pause
