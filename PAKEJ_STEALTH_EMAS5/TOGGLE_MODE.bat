@echo off
REM ============================================================================
REM  MOD SERVIS - TUKAR ON/OFF
REM  Double-click untuk toggle Stealth <-> Normal
REM ============================================================================
setlocal

if exist "%~dp0adb.exe" (set "ADB=%~dp0adb.exe") else (
    where adb >nul 2>&1
    if errorlevel 1 (
        echo RALAT: adb.exe tidak dijumpai.
        pause
        exit /b 1
    )
    set "ADB=adb"
)

set /p CARIP=Masukkan IP kereta (contoh 192.168.1.20): 
if "%CARIP%"=="" (
    echo RALAT: IP diperlukan.
    pause
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0sc_stealth_mode.ps1" -Target "%CARIP%" -Adb "%ADB%"

echo.
pause >nul
