# ==============================================================================
# pasang_stealth.ps1 - Pasang Mod Servis (Stealth) pada Proton e.MAS 5
#
# SASARAN: IHU801P (Proton e.MAS 5), Android 10, ADB sudah diaktifkan + root.
#
# Skrip ini memasang infrastruktur Mod Servis supaya:
#   - Stealth boleh dihidupkan dari butang stereng (Volume Down 5 saat)
#   - Stealth BERTAHAN selepas reboot (tidak bocor di Pusat Servis)
#   - Semua daemon kustom dimatikan, AVAS dipulihkan ke kilang
#
# Guna:
#   .\pasang_stealth.ps1                        # IP auto dikesan
#   .\pasang_stealth.ps1 -Target 192.168.1.20
#   .\pasang_stealth.ps1 -WhatIf                # tunjuk rancangan, tiada perubahan
#   .\pasang_stealth.ps1 -Rollback              # pulihkan ke keadaan sebelum
# ==============================================================================

param(
    [string]$Target = "",
    [string]$Adb = "",
    [switch]$WhatIf,
    [switch]$Rollback,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
$Payload = Join-Path $Root 'payload'
$BackupDir = '/data/local/init_backup'
$Stamp = Get-Date -Format 'yyyyMMdd-HHmmss'

# ---------------------------------------------------------------------------
# adb
# ---------------------------------------------------------------------------
if (-not $Adb) {
    foreach ($p in @(
        (Join-Path $Root 'adb.exe'),
        "$env:USERPROFILE\scoop\apps\android-clt\current\platform-tools\adb.exe",
        "$Root\..\EMAS_TOOLBOX_DESKTOP\tools\adb.exe"
    )) { if (Test-Path $p) { $Adb = $p; break } }
    if (-not $Adb) {
        $c = Get-Command adb -ErrorAction SilentlyContinue
        if ($c) { $Adb = $c.Source }
    }
}
if (-not $Adb) { throw "adb tidak dijumpai. Letak adb.exe dalam folder ini atau guna -Adb <laluan>" }

function Adb { param([string[]]$Arg) & $Adb @Arg 2>&1 }
function Sh  { param([string]$Cmd) & $Adb -s $script:device shell $Cmd 2>&1 }

# ---------------------------------------------------------------------------
# cari kereta
# ---------------------------------------------------------------------------
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  PEMASANG MOD SERVIS - Proton e.MAS 5 (IHU801P)" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

if (-not $Target) {
    Write-Host "`n[1/7] Mencari kereta pada rangkaian..." -ForegroundColor White
    $subnets = Get-NetIPAddress -AddressFamily IPv4 |
        Where-Object { $_.IPAddress -notmatch '^127\.' -and $_.IPAddress -notmatch '^169\.254\.' } |
        Select-Object -ExpandProperty IPAddress
    foreach ($ip in $subnets) {
        $base = ($ip -split '\.')[0..2] -join '.'
        Write-Host "  mengimbas $base.0/24 port 5555..." -ForegroundColor DarkGray
        foreach ($n in 1..254) {
            $candidate = "$base.$n"
            $t = Test-NetConnection -ComputerName $candidate -Port 5555 -InformationLevel Quiet -WarningAction SilentlyContinue
            if ($t) {
                & $Adb connect "${candidate}:5555" | Out-Null
                Start-Sleep -Milliseconds 400
                $model = Sh "getprop ro.product.model"
                if ($model -match 'IHU801') { $Target = $candidate; break }
            }
        }
        if ($Target) { break }
    }
    if (-not $Target) { throw "Kereta tidak dijumpai. Pastikan Wi-Fi kereta aktif." }
}

$script:device = "${Target}:5555"
& $Adb connect $script:device | Out-Null
Start-Sleep -Milliseconds 1200
$devicesRaw = (& $Adb devices) -join "`n"
if ($devicesRaw -notmatch [regex]::Escape($Target)) {
    throw "Tidak dapat sambung ke $script:device. Output adb devices:`n$devicesRaw"
}
Write-Host "  -> $script:device disambung" -ForegroundColor Green

# ---------------------------------------------------------------------------
# sahkan peranti
# ---------------------------------------------------------------------------
Write-Host "`n[2/7] Mengesahkan peranti..." -ForegroundColor White
$model = (Sh "getprop ro.product.model") -join ''
$sdk   = (Sh "getprop ro.build.version.sdk") -join ''
$uid   = (Sh "id") -join ''

Write-Host "  Model : $model"
Write-Host "  SDK   : $sdk"
Write-Host "  Root  : $(if ($uid -match 'uid=0') { 'YA' } else { 'TIDAK' })"

if ($model -notmatch 'IHU801') {
    if (-not $Force) { throw "Model '$model' bukan IHU801P. Guna -Force untuk paksa." }
    Write-Host "  AMARAN: model berbeza, diteruskan kerana -Force" -ForegroundColor Yellow
}
if ($uid -notmatch 'uid=0') { throw "Root diperlukan. ADB bukan root." }

# ---------------------------------------------------------------------------
# rollback
# ---------------------------------------------------------------------------
if ($Rollback) {
    Write-Host "`n[ROLLBACK] Memulihkan konfigurasi asal..." -ForegroundColor Yellow
    Sh "mount -o remount,rw /" | Out-Null
    foreach ($f in @('zzz_custom_apps.rc','zzz_avas_boot.rc','zzz_telemetry_boot.rc')) {
        $bak = Sh "ls $BackupDir/$f.bak* 2>/dev/null | head -1"
        if ($bak) {
            Sh "cp $bak /system/etc/init/$f"
            Write-Host "  restored $f" -ForegroundColor Green
        } else {
            Write-Host "  tiada backup untuk $f" -ForegroundColor Yellow
        }
    }
    Sh "mount -o remount,ro /" | Out-Null
    Write-Host "`nRollback selesai. Reboot diperlukan." -ForegroundColor Cyan
    exit 0
}

$plan = @(
    '/data/local/window/boot_hook.sh',
    '/data/local/window/telemetry_guard.sh',
    '/data/local/window/service_mode.sh',
    '/data/local/avas/avas_boot.sh',
    '/system/etc/init/zzz_custom_apps.rc',
    '/system/etc/init/zzz_avas_boot.rc',
    '/system/etc/init/zzz_telemetry_boot.rc'
)

if ($WhatIf) {
    Write-Host "`n[WhatIf] Rancangan pemasangan:" -ForegroundColor Cyan
    $plan | ForEach-Object { Write-Host "  -> $_" }
    Write-Host "`nTiada perubahan dibuat." -ForegroundColor Yellow
    exit 0
}

# ---------------------------------------------------------------------------
# backup
# ---------------------------------------------------------------------------
Write-Host "`n[3/7] Membuat backup..." -ForegroundColor White
Sh "mkdir -p $BackupDir"
Sh "mount -o remount,rw /" | Out-Null

foreach ($f in @('zzz_custom_apps.rc','zzz_avas_boot.rc','zzz_telemetry_boot.rc')) {
    $exists = Sh "if [ -f /system/etc/init/$f ]; then echo YES; else echo NO; fi"
    if ($exists -match 'YES') {
        Sh "cp /system/etc/init/$f $BackupDir/$f.bak_$Stamp"
        Write-Host "  backup $f" -ForegroundColor DarkGray
    }
}

# PENTING: backup dalam /system/etc/init/ DIPROSES oleh init sebagai konfigurasi
# aktif. Semua fail .bak mesti dipindahkan keluar.
$strayBak = Sh "ls /system/etc/init/*.bak* 2>/dev/null"
if ($strayBak) {
    Write-Host "  memindahkan fail .bak keluar dari /system/etc/init/ ..." -ForegroundColor Yellow
    Sh "mv /system/etc/init/*.bak* $BackupDir/ 2>/dev/null"
    Write-Host "  -> fail .bak dipindah (init memproses SEMUA fail dalam direktori itu)" -ForegroundColor Green
}

# ---------------------------------------------------------------------------
# push payload
# ---------------------------------------------------------------------------
Write-Host "`n[4/7] Menyalin fail..." -ForegroundColor White

$map = @{
    'boot_hook.sh'            = '/data/local/window/boot_hook.sh'
    'telemetry_guard.sh'      = '/data/local/window/telemetry_guard.sh'
    'service_mode.sh'         = '/data/local/window/service_mode.sh'
    'avas_boot.sh'            = '/data/local/avas/avas_boot.sh'
    'zzz_custom_apps.rc'      = '/system/etc/init/zzz_custom_apps.rc'
    'zzz_avas_boot.rc'        = '/system/etc/init/zzz_avas_boot.rc'
    'zzz_telemetry_boot.rc'   = '/system/etc/init/zzz_telemetry_boot.rc'
}

foreach ($src in $map.Keys) {
    $local = Join-Path $Payload $src
    if (-not (Test-Path $local)) { throw "payload hilang: $src" }
    & $Adb -s $script:device push $local $map[$src] | Out-Null
    Write-Host "  -> $($map[$src])" -ForegroundColor DarkGray
}

# ---------------------------------------------------------------------------
# permission + SELinux
# ---------------------------------------------------------------------------
Write-Host "`n[5/7] Menetapkan permission & konteks SELinux..." -ForegroundColor White
Sh "chmod 755 /data/local/window/boot_hook.sh /data/local/window/telemetry_guard.sh /data/local/window/service_mode.sh /data/local/avas/avas_boot.sh"
Sh "chmod 644 /system/etc/init/zzz_custom_apps.rc /system/etc/init/zzz_avas_boot.rc /system/etc/init/zzz_telemetry_boot.rc"
Sh "restorecon /data/local/window/boot_hook.sh /data/local/window/telemetry_guard.sh /data/local/window/service_mode.sh /data/local/avas/avas_boot.sh 2>/dev/null"
Sh "restorecon /system/etc/init/zzz_custom_apps.rc /system/etc/init/zzz_avas_boot.rc /system/etc/init/zzz_telemetry_boot.rc 2>/dev/null"
Sh "mount -o remount,ro /" | Out-Null
Write-Host "  -> selesai, /system dikunci semula (ro)" -ForegroundColor Green

# ---------------------------------------------------------------------------
# ujian dry-run
# ---------------------------------------------------------------------------
Write-Host "`n[6/7] Ujian dry-run boot hook..." -ForegroundColor White
$dryRaw = Sh "sh /data/local/window/boot_hook.sh --dry-run"
$dryText = ($dryRaw | Out-String)
$dryRaw | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }

if ($dryText -notmatch 'stealth=') {
    throw "boot_hook.sh tidak berjalan dengan betul. Semak output di atas."
}
if ($dryText -match 'STEALTH: skip') {
    Write-Host "  -> boot hook berfungsi (mod Stealth)" -ForegroundColor Green
} else {
    Write-Host "  -> boot hook berfungsi (mod normal)" -ForegroundColor Green
}

# ---------------------------------------------------------------------------
# ringkasan
# ---------------------------------------------------------------------------
Write-Host "`n[7/7] Ringkasan..." -ForegroundColor White
$state = Sh "sh /data/local/window/service_mode.sh status"
$steer = Sh "if pgrep -f SteeringModeDaemon >/dev/null; then echo RUNNING; else echo STOPPED; fi"

Write-Host ""
Write-Host "  Mod servis  : $($state -join '')"
Write-Host "  Butang      : $($steer -join '')"

Write-Host "`n============================================================" -ForegroundColor Green
Write-Host "  PEMASANGAN SELESAI" -ForegroundColor Green
Write-Host "============================================================" -ForegroundColor Green
Write-Host ""
Write-Host "  Cara guna:" -ForegroundColor Cyan
Write-Host "    1. Tahan butang BAWAH stereng (Volume Down) 5 saat"
Write-Host "       -> Stealth ON/OFF, dengan bunyi chime"
Write-Host "    2. Atau jalankan: .\sc_stealth_mode.ps1"
Write-Host ""
Write-Host "  Stealth BERTAHAN selepas reboot." -ForegroundColor Yellow
Write-Host "  Backup asal : $BackupDir" -ForegroundColor DarkGray
Write-Host "  Rollback    : .\pasang_stealth.ps1 -Rollback" -ForegroundColor DarkGray
Write-Host ""
Write-Host "  Disyorkan: reboot kereta sekarang untuk sahkan." -ForegroundColor Cyan
