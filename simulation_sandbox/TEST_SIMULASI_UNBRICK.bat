@echo off
title Simulator IHU Brick & Unbrick Verification
cd /d "%~dp0"
echo ==================================================================
echo         MENJALANKAN SIMULASI IHU BRICK & UJIAN SKRIP UNBRICK
echo ==================================================================
"C:\Users\user\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe" simulate_ihu_unbrick.py
echo.
pause
