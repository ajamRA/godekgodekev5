@echo off
REM ============================================================================
REM  PASANG MOD SERVIS (STEALTH) - Proton e.MAS 5
REM  Double-click fail ini untuk memasang. Ikut arahan di skrin.
REM ============================================================================
setlocal

echo.
echo ============================================================
echo   PEMASANG MOD SERVIS - Proton e.MAS 5
echo ============================================================
echo.

REM --- cari adb dalam folder ini, atau dari PATH ---
if exist "%~dp0adb.exe" (
    set "ADB=%~dp0adb.exe"
) else (
    where adb >nul 2>&1
    if errorlevel 1 (
        echo RALAT: adb.exe tidak dijumpai.
        echo.
        echo Letak adb.exe dalam folder ini, atau install Android Platform Tools.
        echo Muat turun: https://developer.android.com/tools/releases/platform-tools
        pause
        exit /b 1
    )
    set "ADB=adb"
)

echo adb: %ADB%
echo.

set /p CARIP=Masukkan IP kereta (contoh 192.168.1.20), atau tekan ENTER untuk auto-cari: 

echo.
echo Menjalankan pemasang...
echo.

if "%CARIP%"=="" (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0pasang_stealth.ps1" -Adb "%ADB%"
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0pasang_stealth.ps1" -Target "%CARIP%" -Adb "%ADB%"
)

echo.
echo ============================================================
echo   Tekan mana-mana kekunci untuk tutup.
echo ============================================================
pause >nul
