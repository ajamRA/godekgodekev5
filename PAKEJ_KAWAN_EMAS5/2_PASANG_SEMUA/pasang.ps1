# ==============================================================================
# PAKEJ PEMASANGAN AUTOMATIK PROTON e.MAS 5 (NETFLIX, SMARTTUBE & STEALTH MODE)
# ==============================================================================

param(
    [string]$TargetIp = ""
)

$ErrorActionPreference = "Continue"

Write-Host "==================================================================" -ForegroundColor Cyan
Write-Host "  PROTON e.MAS 5 - PEMASANGAN AUTOMATIK (NETFLIX + SMARTTUBE)     " -ForegroundColor Cyan
Write-Host "==================================================================" -ForegroundColor Cyan

$PSScriptDir = $PSScriptRoot
$appsDir = Join-Path $PSScriptDir "apps"
$daemonsDir = Join-Path $PSScriptDir "daemons"
$audioDir = Join-Path $PSScriptDir "audio"

# Tentukan binary ADB tempatan (bundle) atau sistem
$adbExe = Join-Path $PSScriptDir "adb.exe"
if (-not (Test-Path $adbExe)) {
    $adbExe = "adb"
}

# 1. Cari atau sahkan IP kereta
if ([string]::IsNullOrWhiteSpace($TargetIp)) {
    Write-Host "`n[1/6] Mengesan peranti ADB secara automatik..." -ForegroundColor Yellow
    
    # Periksa sama ada peranti sudah ada dalam adb devices
    $adbDevs = & $adbExe devices 2>&1 | Out-String
    $ipMatch = [regex]::Match($adbDevs, '(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}):5555\s+device')
    if ($ipMatch.Success) {
        $TargetIp = $ipMatch.Groups[1].Value
        Write-Host "Peranti sedia ada dijumpai: $TargetIp:5555" -ForegroundColor Green
    } else {
        # Imbas subnet tempatan
        $localIps = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | 
                    Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" }
        
        foreach ($lip in $localIps) {
            $parts = $lip.IPAddress.Split('.')
            if ($parts.Length -ne 4) { continue }
            $subnet = "$($parts[0]).$($parts[1]).$($parts[2])"
            Write-Host "Mengimbas rangkaian $subnet.* pada port 5555..." -ForegroundColor Gray

            $clients = New-Object System.Collections.ArrayList
            for ($i = 1; $i -le 254; $i++) {
                $testIp = "$subnet.$i"
                $c = New-Object System.Net.Sockets.TcpClient
                $ar = $c.BeginConnect($testIp, 5555, $null, $null)
                $null = $clients.Add([PSCustomObject]@{ Ip = $testIp; Client = $c; Async = $ar })
            }

            Start-Sleep -Milliseconds 450

            foreach ($entry in $clients) {
                if ($entry.Async.IsCompleted -and $entry.Client.Connected) {
                    $TargetIp = $entry.Ip
                    Write-Host "Jumpa port ADB di: $TargetIp:5555!" -ForegroundColor Green
                    break
                }
            }
            foreach ($entry in $clients) { $entry.Client.Close() }
            if ($TargetIp) { break }
        }
    }
}

if (-not $TargetIp) {
    Write-Host ""
    Write-Host "Imbasan automatik tidak menjumpai kereta." -ForegroundColor Yellow
    $TargetIp = Read-Host "Sila masukkan alamat IP kereta anda (contoh: 192.168.43.150 atau 192.168.1.16)"
}

if (-not $TargetIp) {
    Write-Host "[RALAT] Alamat IP kereta diperlukan. Pemasangan dibatalkan." -ForegroundColor Red
    exit 1
}

Write-Host "`nMenyambung ke $TargetIp:5555..." -ForegroundColor Cyan
& $adbExe disconnect "$TargetIp`:5555" 2>&1 | Out-Null
Start-Sleep -Milliseconds 500
& $adbExe connect "$TargetIp`:5555"
Start-Sleep -Seconds 1

$state = (& $adbExe -s "$TargetIp`:5555" get-state 2>&1 | Out-String).Trim()
if ($state -ne "device") {
    Write-Host "[RALAT] Gagal menyambung ke $TargetIp:5555 (Status: '$state')." -ForegroundColor Red
    Write-Host "Sila pastikan:" -ForegroundColor Yellow
    Write-Host "1. Langkah 1 (1_USB_UNLOCK/update.zip 2.07 GB) telah berjaya di-flash pada kereta." -ForegroundColor Yellow
    Write-Host "2. Skrin kereta menyala dan disambungkan ke Wi-Fi / Hotspot yang sama." -ForegroundColor Yellow
    Write-Host "3. Jika ada popup 'Allow USB debugging' di skrin kereta, tekan ALLOW." -ForegroundColor Yellow
    exit 1
}
Write-Host "Sambungan ADB BERJAYA! (Akses Root Aktif)" -ForegroundColor Green

# 2. Sediakan direktori & remount partition sistem
Write-Host "`n[2/6] Membuka kunci storan sistem & direktori kustom..." -ForegroundColor Cyan
& $adbExe -s "$TargetIp`:5555" shell "mount -o remount,rw /system 2>/dev/null || mount -o remount,rw / 2>/dev/null"
& $adbExe -s "$TargetIp`:5555" shell "mkdir -p /data/local/custom_apps/SmartTube /data/local/custom_apps/Netflix /data/local/window /data/media/0"

# 3. Pasang & Bind Mount SmartTube ke atas slot TikTok
Write-Host "`n[3/6] Memasang SmartTube (menggantikan slot TikTok)..." -ForegroundColor Cyan
& $adbExe -s "$TargetIp`:5555" push "$appsDir\SmartTube.apk" "/data/local/custom_apps/SmartTube/SmartTube.apk"
& $adbExe -s "$TargetIp`:5555" shell "chmod 644 /data/local/custom_apps/SmartTube/SmartTube.apk; chcon -R u:object_r:system_file:s0 /data/local/custom_apps/SmartTube"
& $adbExe -s "$TargetIp`:5555" shell "pm install -r -d -g /data/local/custom_apps/SmartTube/SmartTube.apk"

# Mengesan folder TikTok rasmi
$tiktokDir = (& $adbExe -s "$TargetIp`:5555" shell "find /system/app /product/app -maxdepth 2 -iname '*tiktok*' 2>/dev/null | head -n 1" | Out-String).Trim()
if ($tiktokDir) {
    Write-Host "Slot TikTok dikesan: $tiktokDir" -ForegroundColor Green
    & $adbExe -s "$TargetIp`:5555" shell "mount -o bind /data/local/custom_apps/SmartTube $tiktokDir 2>/dev/null"
} else {
    Write-Host "Folder TikTok tiada, menggunakan slot lalai /system/app/ttincar_tiktok_ACO" -ForegroundColor Yellow
    $tiktokDir = "/system/app/ttincar_tiktok_ACO"
}

# 4. Pasang & Bind Mount Netflix ke atas slot JOOX Music
Write-Host "`n[4/6] Memasang Netflix (menggantikan slot JOOX Music)..." -ForegroundColor Cyan
& $adbExe -s "$TargetIp`:5555" push "$appsDir\Netflix.apk" "/data/local/custom_apps/Netflix/Netflix.apk"
& $adbExe -s "$TargetIp`:5555" shell "chmod 644 /data/local/custom_apps/Netflix/Netflix.apk; chcon -R u:object_r:system_file:s0 /data/local/custom_apps/Netflix"

# Pasang split APK Netflix jika ada
if (Test-Path "$appsDir\netflix_splits") {
    Write-Host "Memasang arkitektur Netflix penuh..." -ForegroundColor Gray
    $splitFiles = Get-ChildItem "$appsDir\netflix_splits\*.apk" | ForEach-Object { $_.FullName }
    & $adbExe -s "$TargetIp`:5555" install-multiple -r -d -g $splitFiles
} else {
    & $adbExe -s "$TargetIp`:5555" shell "pm install -r -d -g /data/local/custom_apps/Netflix/Netflix.apk"
}

# Mengesan folder JOOX rasmi
$jooxDir = (& $adbExe -s "$TargetIp`:5555" shell "find /system/app /product/app -maxdepth 2 -iname '*joox*' 2>/dev/null | head -n 1" | Out-String).Trim()
if ($jooxDir) {
    Write-Host "Slot JOOX dikesan: $jooxDir" -ForegroundColor Green
    & $adbExe -s "$TargetIp`:5555" shell "mount -o bind /data/local/custom_apps/Netflix $jooxDir 2>/dev/null"
} else {
    Write-Host "Folder JOOX tiada, menggunakan slot lalai /system/app/JOOXMusic_ACO" -ForegroundColor Yellow
    $jooxDir = "/system/app/JOOXMusic_ACO"
}

# 5. Pasang Steering Mode Daemon & Service Mode (Hold 5 Saat Butang Bawah)
Write-Host "`n[5/6] Memasang Pengawal Butang Stereng (Hold 5 Saat = Sorok/Muncul Mod)..." -ForegroundColor Cyan
& $adbExe -s "$TargetIp`:5555" push "$daemonsDir\steering_mode_controller.jar" "/data/local/window/steering_mode_controller.jar"
& $adbExe -s "$TargetIp`:5555" push "$daemonsDir\service_mode.sh" "/data/local/window/service_mode.sh"
& $adbExe -s "$TargetIp`:5555" push "$daemonsDir\start_steering_daemon.sh" "/data/local/window/start_steering_daemon.sh"
if (Test-Path "$audioDir\mode_chime.wav") {
    & $adbExe -s "$TargetIp`:5555" push "$audioDir\mode_chime.wav" "/data/local/window/mode_chime.wav"
}
& $adbExe -s "$TargetIp`:5555" shell "chmod 755 /data/local/window/*.sh; chmod 644 /data/local/window/*.jar"

# Jalankan daemon stereng sekarang
& $adbExe -s "$TargetIp`:5555" shell "sh /data/local/window/start_steering_daemon.sh"

# 6. Pastikan Mount Kekal Selepas Reboot (zzz_custom_apps.rc)
Write-Host "`n[6/6] Mengkonfigurasi ketahanan autorun & persistensi reboot..." -ForegroundColor Cyan
$rcContent = @"
on post-fs-data
    mount none /data/local/custom_apps/SmartTube $tiktokDir bind rec
    mount none /data/local/custom_apps/Netflix $jooxDir bind rec

on property:sys.boot_completed=1
    exec_background -- /system/bin/sh /data/local/window/start_steering_daemon.sh
"@

$tmpRc = [System.IO.Path]::GetTempFileName()
[System.IO.File]::WriteAllText($tmpRc, $rcContent, [System.Text.Encoding]::ASCII)
& $adbExe -s "$TargetIp`:5555" push $tmpRc "/system/etc/init/zzz_custom_apps.rc"
& $adbExe -s "$TargetIp`:5555" shell "chmod 644 /system/etc/init/zzz_custom_apps.rc; chcon u:object_r:system_file:s0 /system/etc/init/zzz_custom_apps.rc"
Remove-Item $tmpRc -Force -ErrorAction SilentlyContinue

# Kunci semula partition /system ke read-only untuk keselamatan
& $adbExe -s "$TargetIp`:5555" shell "mount -o remount,ro /system 2>/dev/null || mount -o remount,ro / 2>/dev/null"

Write-Host "`n==================================================================" -ForegroundColor Green
Write-Host "             PEMASANGAN SELESAI DENGAN JAYANYA!                  " -ForegroundColor Green
Write-Host "==================================================================" -ForegroundColor Green
Write-Host "1. SmartTube : Siap dipasang di atas slot TikTok." -ForegroundColor White
Write-Host "2. Netflix   : Siap dipasang di atas slot JOOX Music." -ForegroundColor White
Write-Host "3. Mod Stereng: Tekan & HOLD butang BAWAH (Volume -) selama 5 saat" -ForegroundColor White
Write-Host "               untuk sembunyikan/munculkan app apabila ke Pusat Servis." -ForegroundColor Yellow
Write-Host "`nSila reboot skrin IHU kereta sekarang untuk menyegarkan ikon desktop." -ForegroundColor Cyan
