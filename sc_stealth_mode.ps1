# ==============================================================================
# sc_stealth_mode.ps1 - Mod Servis Proton SC (versi laptop)
#
# Selaras dengan /data/local/window/service_mode.sh pada kereta.
# Skrip ini hanya PEMBALUT: ia memanggil skrip dalam kereta supaya logik
# tidak bercabang dua.
#
# Guna:
#   .\sc_stealth_mode.ps1
#   .\sc_stealth_mode.ps1 -Target 192.168.1.20
#   .\sc_stealth_mode.ps1 -Status
# ==============================================================================

param(
    [string]$Target = "192.168.1.20",
    [switch]$Status,
    [string]$Adb = ""
)

$ErrorActionPreference = 'Stop'
$device = "${Target}:5555"

# --- cari adb ---------------------------------------------------------------
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
Write-Host " [SC MODE] Proton e.MAS 5 - $device" -ForegroundColor Yellow
Write-Host "==========================================================" -ForegroundColor Cyan

# --- sambung ----------------------------------------------------------------
Write-Host "`n[1/3] Menyambung..." -ForegroundColor White
& $Adb connect $device | Out-Null
Start-Sleep -Milliseconds 1200
$devRaw = (& $Adb devices) -join "`n"
if ($devRaw -notmatch [regex]::Escape($Target)) {
    Write-Host "RALAT: tidak dapat sambung ke $device" -ForegroundColor Red
    Write-Host $devRaw -ForegroundColor DarkGray
    exit 1
}
Write-Host " -> ADB AKTIF" -ForegroundColor Green

# --- sahkan skrip ada -------------------------------------------------------
$check = Invoke-Adb "if [ -f /data/local/window/service_mode.sh ]; then echo OK; else echo MISSING; fi"
if ($check -notmatch "OK") {
    Write-Host "RALAT: /data/local/window/service_mode.sh tiada pada kereta." -ForegroundColor Red
    Write-Host " Jalankan dahulu: pasang_stealth.ps1" -ForegroundColor Yellow
    exit 1
}

# --- mod status -------------------------------------------------------------
if ($Status) {
    Write-Host "`n[2/2] Status mod servis..." -ForegroundColor White
    Invoke-Adb "sh /data/local/window/service_mode.sh status" | Write-Host
    Write-Host "`nDaemon aktif:" -ForegroundColor Cyan
    Invoke-Adb "ps -A -o ARGS | grep app_process | grep -v grep" | Write-Host
    exit 0
}

# --- tentukan arah ----------------------------------------------------------
$cur = Invoke-Adb "sh /data/local/window/service_mode.sh status"
$isStealth = $cur -match "SERVICE_MODE_ACTIVE"

if ($isStealth) {
    Write-Host "`n[2/3] Keadaan sekarang: STEALTH - memulihkan mod normal..." -ForegroundColor Yellow
    $out = Invoke-Adb "sh /data/local/window/service_mode.sh off"
} else {
    Write-Host "`n[2/3] Keadaan sekarang: NORMAL - mengaktifkan Stealth..." -ForegroundColor Yellow
    $out = Invoke-Adb "sh /data/local/window/service_mode.sh on"
}

$out | Where-Object { $_ -match 'SERVICE_MODE|error|RALAT' } | ForEach-Object {
    Write-Host " -> $_" -ForegroundColor DarkGray
}

# --- pengesahan -------------------------------------------------------------
Write-Host "`n[3/3] Pengesahan..." -ForegroundColor White
Start-Sleep -Seconds 2

$finalState = Invoke-Adb "sh /data/local/window/service_mode.sh status"
$disabled   = Invoke-Adb "pm list packages -d | wc -l"
$daemons    = Invoke-Adb "ps -A -o ARGS | grep app_process | grep -v grep | wc -l"
$mounts     = Invoke-Adb "mount | grep -cE 'ttincar|JOOX'"
$port       = Invoke-Adb "netstat -tln 2>/dev/null | grep -E ':8888|:8890' | grep -c LISTEN"

Write-Host ""
Write-Host "  Status        : $($finalState -join '')" -ForegroundColor Cyan
Write-Host "  Pakej disable : $($disabled -join '')"
Write-Host "  Daemon        : $($daemons -join '')"
Write-Host "  Mount slot    : $($mounts -join '')"
Write-Host "  Port 8888/90  : $($port -join '')"

Write-Host "`n==========================================================" -ForegroundColor Green
if ($finalState -match "STEALTH") {
    Write-Host " STEALTH AKTIF - kereta dalam keadaan kilang" -ForegroundColor Green
    Write-Host " Keluar: tahan Volume Down 5 saat, atau jalankan skrip ini lagi" -ForegroundColor DarkCyan
} else {
    Write-Host " MOD NORMAL - semua ciri kustom dipulihkan" -ForegroundColor Green
}
Write-Host "==========================================================" -ForegroundColor Green
