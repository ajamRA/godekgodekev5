@echo off
REM Pembalut supaya PowerShell boleh panggil adb-mock sebagai "adb".
node "%~dp0adb-mock.cjs" %*
