$ErrorActionPreference = "Stop"

$CAR_IP = "192.168.1.18:5555"
$APK_PATH = "D:\apps\emas-ota\NoorEnergy_clean.apk"

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "   EMAS ENERGY MONITOR - DEPLOY TO NOOR   " -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

if (!(Test-Path $APK_PATH)) {
    Write-Error "APK fail tidak ditemui: $APK_PATH"
    exit 1
}

Write-Host "`n1. Menyambung ke kereta ($CAR_IP)..." -ForegroundColor Yellow
& adb connect $CAR_IP
Start-Sleep -Seconds 2

# Check connected
$devices = & adb devices
if ($devices -notmatch "192.168.1.18:5555\s+device") {
    Write-Host "Kereta belum bersambung. Sila pastikan kereta ON dan WiFi aktif." -ForegroundColor Red
    exit 1
}
Write-Host "   Tersambung ke kereta!" -ForegroundColor Green

Write-Host "`n2. Mengesahkan akses root..." -ForegroundColor Yellow
& adb root
Start-Sleep -Seconds 1
& adb connect $CAR_IP | Out-Null

Write-Host "`n3. Menyediakan direktori sistem..." -ForegroundColor Yellow
# Remount /system rw
& adb shell "mount -o remount,rw /system 2>/dev/null || mount -o remount,rw / 2>/dev/null"
& adb shell "mkdir -p /system/app/Noor"

Write-Host "`n4. Memuat naik APK ke /system/app/Noor/Noor.apk..." -ForegroundColor Yellow
& adb push $APK_PATH /system/app/Noor/Noor.apk

Write-Host "`n5. Menetapkan kebenaran fail (chmod 644)..." -ForegroundColor Yellow
& adb shell "chmod 755 /system/app/Noor"
& adb shell "chmod 644 /system/app/Noor/Noor.apk"
& adb shell "chown -R root:root /system/app/Noor"

# Juga sediakan dalam /data/local/custom_apps/Noor sekiranya bind-mount diperlukan
& adb shell "mkdir -p /data/local/custom_apps/Noor"
& adb shell "cp /system/app/Noor/Noor.apk /data/local/custom_apps/Noor/Noor.apk"
& adb shell "chmod 644 /data/local/custom_apps/Noor/Noor.apk"

Write-Host "`n6. Memastikan daemon pembaca tenaga aktif..." -ForegroundColor Yellow
$daemonRunning = & adb shell "ps -ef | grep door_pressure_relief | grep -v grep"
if ([string]::IsNullOrWhiteSpace($daemonRunning)) {
    Write-Host "   Memulakan daemon tenaga (port 8888)..." -ForegroundColor Cyan
    & adb shell "sh /data/local/window/start_pressure_relief.sh &"
    Start-Sleep -Seconds 2
} else {
    Write-Host "   Daemon tenaga sedang berjalan." -ForegroundColor Green
}

Write-Host "`n7. Mengimbas semula aplikasi sistem..." -ForegroundColor Yellow
# Restart Android runtime (system_server) so PackageManagerService scans new /system/app/Noor
Write-Host "   Memulakan semula system_server (skrin IHU akan refresh dalam 10-15 saat)..." -ForegroundColor Cyan
& adb shell "killall system_server"

Write-Host "`nMenunggu sistem IHU sedia semula..." -ForegroundColor Yellow
Start-Sleep -Seconds 12

# Wait for boot completion
for ($i = 0; $i -lt 30; $i++) {
    $booted = & adb shell "getprop sys.boot_completed"
    if ($booted -match "1") {
        Write-Host "   Sistem IHU sudah sedia!" -ForegroundColor Green
        break
    }
    Start-Sleep -Seconds 2
}

Write-Host "`n8. Melancarkan Emas Energy Monitor..." -ForegroundColor Yellow
& adb shell "am start -n com.noor.thenoorcar/.MainActivity"

Write-Host "`n=======================================================" -ForegroundColor Green
Write-Host " BERJAYA! Emas Energy Monitor telah dipasang pada Noor." -ForegroundColor Green
Write-Host " Sila periksa skrin IHU kereta anda!" -ForegroundColor Green
Write-Host "=======================================================" -ForegroundColor Green
