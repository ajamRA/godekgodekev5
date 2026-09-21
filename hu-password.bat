@echo off
setlocal
cd /d "%~dp0"
set "IHUID=021600000000000000000000"
if not "%~1"=="" set "IHUID=%~1"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0hu-password.ps1" -IhuId "%IHUID%"
echo.
echo Jika nak mod pantau live (auto-update setiap saat):
echo   powershell -ExecutionPolicy Bypass -File .\hu-password.ps1 -Watch
echo.
pause
