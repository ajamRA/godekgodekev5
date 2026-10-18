# ==============================================================================
# sc_restore_mode.ps1 - Pulihkan mod normal (versi laptop)
#
# Selaras dengan service_mode.sh pada kereta. Skrip ini hanya memanggil
# `service_mode.sh off` supaya logik tidak bercabang dua.
#
# Guna:
#   .\sc_restore_mode.ps1
#   .\sc_restore_mode.ps1 -Target 192.168.1.20
# ==============================================================================

param(
    [string]$Target = "192.168.1.20",
    [string]$Adb = ""
)

$ErrorActionPreference = 'Stop'
$device = "${Target}:5555"

if (-not $Adb) {
    $c = @(
        "$PSScriptRoot\EMAS_TOOLBOX_DESKTOP\tools\adb.exe",
        "$env:USERPROFILE\scoop\apps\android-clt\current\platform-tools\adb.exe"
    )
    foreach ($p in $c) { if (Test-Path $p) { $Adb = $p; break } }
    if (-not $Adb) {
        $cmd = Get-Command adb -ErrorAction SilentlyContinue
        if ($cmd) { $Adb = $cmd.Source }
    }
}
if (-not $Adb) { throw "adb tidak dijumpai. Guna -Adb <laluan>" }

function Invoke-Adb { param([string]$Cmd) & $Adb -s $device shell $Cmd 2>&1 }

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " [SC RESTORE] Memulihkan mod normal - $device" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Cyan

Write-Host "`n[1/3] Menyambung..." -ForegroundColor White
& $Adb connect $device | Out-Null
Start-Sleep -Milliseconds 1200
$devRaw = (& $Adb devices) -join "`n"
if ($devRaw -notmatch [regex]::Escape($Target)) {
    Write-Host "RALAT: tidak dapat sambung ke $device" -ForegroundColor Red
    exit 1
}
Write-Host " -> ADB AKTIF" -ForegroundColor Green

Write-Host "`n[2/3] Menjalankan service_mode.sh off..." -ForegroundColor White
$out = Invoke-Adb "sh /data/local/window/service_mode.sh off"
$out | Where-Object { $_ -match 'SERVICE_MODE' } | ForEach-Object {
    Write-Host " -> $_" -ForegroundColor DarkGray
}

Write-Host "`n[3/3] Pengesahan..." -ForegroundColor White
Start-Sleep -Seconds 3

$state    = Invoke-Adb "sh /data/local/window/service_mode.sh status"
$disabled = Invoke-Adb "pm list packages -d | wc -l"
$daemons  = Invoke-Adb "ps -A -o ARGS | grep app_process | grep -v grep | wc -l"
$mounts   = Invoke-Adb "mount | grep -cE 'ttincar|JOOX'"

Write-Host ""
Write-Host "  Status        : $($state -join '')" -ForegroundColor Cyan
Write-Host "  Pakej disable : $($disabled -join '')"
Write-Host "  Daemon        : $($daemons -join '')"
Write-Host "  Mount slot    : $($mounts -join '')"

Write-Host "`n==========================================================" -ForegroundColor Green
if ($state -match "NORMAL") {
    Write-Host " MOD NORMAL - semua ciri kustom dipulihkan" -ForegroundColor Green
} else {
    Write-Host " AMARAN: masih dalam Stealth - semak log di atas" -ForegroundColor Yellow
}
Write-Host "==========================================================" -ForegroundColor Green
