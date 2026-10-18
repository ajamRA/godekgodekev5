# ==============================================================================
# pasang_semua.ps1 — Jalankan ketiga-tiga pakej berturut-turut
#
# Untuk kereta yang SUDAH ada ADB + root. Jika belum, jalankan
# PAKEJ_UNLOCK_ADB_EMAS5 dahulu (melalui USB, bukan skrip ini).
#
# Guna:
#   .\pasang_semua.ps1 -Target 192.168.1.20
#   .\pasang_semua.ps1 -Target 192.168.1.20 -Drivemode
# ==============================================================================

param(
    [Parameter(Mandatory = $true)][string]$Target,
    [switch]$Drivemode,
    [switch]$StealthOnly,
    [string]$Adb = ""
)

$ErrorActionPreference = 'Stop'
$Repo = $PSScriptRoot

# cari adb dari pakej unlock
if (-not $Adb) {
    foreach ($p in @(
        "$Repo\PAKEJ_STEALTH_EMAS5\adb.exe",
        "$Repo\PAKEJ_UNLOCK_ADB_EMAS5\2_PASANG_SEMUA\adb.exe",
        "$env:USERPROFILE\scoop\apps\android-clt\current\platform-tools\adb.exe"
    )) { if (Test-Path $p) { $Adb = $p; break } }
}
if (-not $Adb) { throw "adb.exe tidak dijumpai." }

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  PASANG SEMUA - Proton e.MAS 5" -ForegroundColor Cyan
Write-Host "  Sasaran : $Target" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

$steps = @()

if (-not $StealthOnly) {
    $steps += @{
        Name = 'Mod Servis (Stealth)'
        Path = "$Repo\PAKEJ_STEALTH_EMAS5\pasang_stealth.ps1"
    }
}

if ($Drivemode) {
    $steps += @{
        Name = 'Kawalan Stereng (Drive Mode)'
        Path = "$Repo\PAKEJ_DRIVEMODE_EMAS5\pasang_drivemode.ps1"
    }
}

$failed = @()

foreach ($s in $steps) {
    Write-Host ""
    Write-Host "------------------------------------------------------------" -ForegroundColor DarkCyan
    Write-Host "  $($s.Name)" -ForegroundColor Cyan
    Write-Host "------------------------------------------------------------" -ForegroundColor DarkCyan

    if (-not (Test-Path $s.Path)) {
        Write-Host "  SKIP: $($s.Path) tiada" -ForegroundColor Yellow
        $failed += $s.Name
        continue
    }

    & powershell -NoProfile -ExecutionPolicy Bypass -File $s.Path -Target $Target -Adb $Adb
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  GAGAL: $($s.Name)" -ForegroundColor Red
        $failed += $s.Name
    } else {
        Write-Host "  OK: $($s.Name)" -ForegroundColor Green
    }
}

Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
if ($failed.Count -eq 0) {
    Write-Host "  SEMUA SELESAI" -ForegroundColor Green
} else {
    Write-Host "  SEBAHAGIAN GAGAL:" -ForegroundColor Yellow
    $failed | ForEach-Object { Write-Host "    - $_" -ForegroundColor Yellow }
}
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Langkah seterusnya:" -ForegroundColor Cyan
Write-Host "    1. Reboot kereta untuk sahkan Stealth bertahan"
Write-Host "    2. Tahan butang stereng untuk uji:"
Write-Host "         Kanan 800ms   -> Drive Mode"
Write-Host "         Kiri  800ms   -> Regen"
Write-Host "         Bawah 5000ms  -> Stealth ON/OFF"
