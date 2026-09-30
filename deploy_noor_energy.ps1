param (
    [string]$CAR_IP = "192.168.0.101:5555"
)

$ErrorActionPreference = "Stop"
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
$devStr = (& adb devices | Out-String)
$escapedIp = [regex]::Escape($CAR_IP)
if ($devStr -notmatch "$escapedIp\s+device") {
    # Check if any device is connected
    $lines = (& adb devices) | Where-Object { $_ -match "\bdevice\b" -and $_ -notmatch "attached" -and $_ -notmatch "69:5555" }
    if ($lines) {
        $firstLine = if ($lines -is [array]) { $lines[0] } else { $lines }
        $CAR_IP = ($firstLine -split "\s+")[0].Trim()
        Write-Host "Menggunakan peranti aktif: $CAR_IP" -ForegroundColor Cyan
    } else {
        Write-Host "Kereta belum bersambung ($CAR_IP). Sila pastikan kereta ON dan WiFi aktif." -ForegroundColor Red
        exit 1
    }
}
Write-Host "   Tersambung ke kereta ($CAR_IP)!" -ForegroundColor Green

Write-Host "`n2. Mengesahkan akses root..." -ForegroundColor Yellow
& adb -s $CAR_IP root
Start-Sleep -Seconds 1
& adb connect $CAR_IP | Out-Null

Write-Host "`n3. Memasang APK terus ke sistem..." -ForegroundColor Yellow
& adb -s $CAR_IP install -r $APK_PATH

Write-Host "`n4. Menyediakan direktori sistem dan persistensi Noor.apk..." -ForegroundColor Yellow
& adb -s $CAR_IP shell "mount -o remount,rw /system 2>/dev/null || mount -o remount,rw / 2>/dev/null"
& adb -s $CAR_IP shell "mkdir -p /system/app/Noor /data/local/custom_apps/Noor /data/local/window /data/local/avas"
& adb -s $CAR_IP push $APK_PATH /system/app/Noor/Noor.apk
& adb -s $CAR_IP push $APK_PATH /data/local/custom_apps/Noor/Noor.apk
& adb -s $CAR_IP shell "chmod 755 /system/app/Noor; chmod 644 /system/app/Noor/Noor.apk /data/local/custom_apps/Noor/Noor.apk"

Write-Host "`n5. Mengemaskini daemon latar belakang dan skrip auto-buka semasa start kereta..." -ForegroundColor Yellow
& adb -s $CAR_IP push "D:\apps\emas-ota\door_pressure_relief.jar" "/data/local/window/door_pressure_relief.jar"
& adb -s $CAR_IP push "D:\apps\emas-ota\start_pressure_relief.sh" "/data/local/window/start_pressure_relief.sh"
& adb -s $CAR_IP push "D:\apps\emas-ota\avas_boot.sh" "/data/local/avas/avas_boot.sh"
& adb -s $CAR_IP shell "chmod 755 /data/local/window/start_pressure_relief.sh /data/local/avas/avas_boot.sh"

Write-Host "`n6. Memulakan semula daemon sistem..." -ForegroundColor Yellow
& adb -s $CAR_IP shell "sh /data/local/window/start_pressure_relief.sh"

Write-Host "`n7. Memulakan semula aplikasi..." -ForegroundColor Yellow
& adb -s $CAR_IP shell "am force-stop com.noor.thenoorcar"
Start-Sleep -Seconds 1

Write-Host "`n8. Melancarkan Emas Energy Monitor..." -ForegroundColor Yellow
& adb -s $CAR_IP shell "am start -n com.noor.thenoorcar/.MainActivity"

Write-Host "`n=======================================================" -ForegroundColor Green
Write-Host " BERJAYA! Emas Energy Monitor telah dipasang pada Noor." -ForegroundColor Green
Write-Host " Sila periksa skrin IHU kereta anda!" -ForegroundColor Green
Write-Host "=======================================================" -ForegroundColor Green
