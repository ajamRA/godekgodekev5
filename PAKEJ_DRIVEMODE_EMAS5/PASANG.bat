@echo off
REM ============================================================================
REM  PASANG KAWALAN STERENG - Drive Mode + Regen + Stealth
REM ============================================================================
setlocal

echo.
echo ============================================================
echo   PASANG KAWALAN STERENG - Proton e.MAS 5
echo ============================================================
echo.
echo   Kanan 800ms   - Drive Mode (Comfort/Eco/Sport)
echo   Kiri  800ms   - Regen (Low/Medium/High)
echo   Bawah 5000ms  - Mod Servis (Stealth)
echo.
echo   NOTA: Pasang PAKEJ_STEALTH_EMAS5 dahulu jika belum.
echo.

if exist "%~dp0adb.exe" (set "ADB=%~dp0adb.exe") else (
    where adb >nul 2>&1
    if errorlevel 1 (
        echo RALAT: adb.exe tidak dijumpai.
        echo Letak adb.exe dalam folder ini.
        pause
        exit /b 1
    )
    set "ADB=adb"
)

set /p CARIP=Masukkan IP kereta (contoh 192.168.1.20), ENTER untuk auto-cari: 

echo.
if "%CARIP%"=="" (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0pasang_drivemode.ps1" -Adb "%ADB%"
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0pasang_drivemode.ps1" -Target "%CARIP%" -Adb "%ADB%"
)

echo.
pause >nul
