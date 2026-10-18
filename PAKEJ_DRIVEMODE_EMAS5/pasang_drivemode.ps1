# ==============================================================================
# pasang_drivemode.ps1 — Pasang kawalan stereng (Drive Mode + Regen + Stealth)
#                       pada Proton e.MAS 5 (IHU801P)
#
# SASARAN: IHU801P, Android 10, ADB + root sudah ada.
#
# Apa yang dipasang:
#   /data/local/window/steering_mode_controller.jar
#   /data/local/window/start_steering_daemon.sh
#   + daftar auto-start dalam /data/local/avas/avas_boot.sh
#
# Butang stereng selepas pemasangan:
#   Kanan (Vol+) tahan 800ms  -> kitar Drive Mode (Comfort -> Eco -> Sport)
#   Kiri  (Vol-) tahan 800ms  -> kitar Regen (Low -> Medium -> High)
#   Bawah (Vol-) tahan 5000ms -> toggle Mod Servis (Stealth)
#   Atas                        -> volume kilang (dikecualikan)
#
# Guna:
#   .\pasang_drivemode.ps1 -Target 192.168.1.20
#   .\pasang_drivemode.ps1 -WhatIf
#   .\pasang_drivemode.ps1 -Status
#   .\pasang_drivemode.ps1 -Uninstall
# ==============================================================================

param(
    [string]$Target = "",
    [string]$Adb = "",
    [switch]$WhatIf,
    [switch]$Status,
    [switch]$Uninstall,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
$Payload = Join-Path $Root 'payload'
$Bin = Join-Path $Payload 'bin'

$JAR_REMOTE = '/data/local/window/steering_mode_controller.jar'
$LAUNCHER_REMOTE = '/data/local/window/start_steering_daemon.sh'
$AVAS_BOOT = '/data/local/avas/avas_boot.sh'
$EXPECTED_SHA = '99441654707109d669c9ba63d3f1ec751d7b700ea724cb4165ebab3030ec253b'

# --- adb ---------------------------------------------------------------------
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
if (-not $Adb) { throw "adb tidak dijumpai. Guna -Adb <laluan>" }

function Adb { param([string[]]$Arg) & $Adb @Arg 2>&1 }
function Sh  { param([string]$Cmd) & $Adb -s $script:device shell $Cmd 2>&1 }

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  KAWALAN STERENG - Proton e.MAS 5 (IHU801P)" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

# --- cari kereta -------------------------------------------------------------
if (-not $Target) {
    Write-Host "`n[1/5] Mencari kereta..." -ForegroundColor White
    $nets = Get-NetIPAddress -AddressFamily IPv4 |
        Where-Object { $_.IPAddress -notmatch '^127\.' -and $_.IPAddress -notmatch '^169\.254\.' } |
        Select-Object -ExpandProperty IPAddress
    foreach ($ip in $nets) {
        $base = ($ip -split '\.')[0..2] -join '.'
        foreach ($n in 1..254) {
            $cand = "$base.$n"
            if (Test-NetConnection -ComputerName $cand -Port 5555 -InformationLevel Quiet -WarningAction SilentlyContinue) {
                & $Adb connect "${cand}:5555" | Out-Null
                Start-Sleep -Milliseconds 400
                if ((Sh "getprop ro.product.model") -match 'IHU801') { $Target = $cand; break }
            }
        }
        if ($Target) { break }
    }
    if (-not $Target) { throw "Kereta tidak dijumpai." }
}

$script:device = "${Target}:5555"
& $Adb connect $script:device | Out-Null
Start-Sleep -Milliseconds 1200
$devRaw = (& $Adb devices) -join "`n"
if ($devRaw -notmatch [regex]::Escape($Target)) { throw "Tidak dapat sambung ke $script:device" }
Write-Host "  -> $script:device" -ForegroundColor Green

# --- sahkan peranti ----------------------------------------------------------
Write-Host "`n[2/5] Mengesahkan peranti..." -ForegroundColor White
$model = (Sh "getprop ro.product.model") -join ''
$uid = (Sh "id") -join ''
Write-Host "  Model : $model"
Write-Host "  Root  : $(if ($uid -match 'uid=0') { 'YA' } else { 'TIDAK' })"
if ($model -notmatch 'IHU801' -and -not $Force) { throw "Model bukan IHU801P. Guna -Force." }
if ($uid -notmatch 'uid=0') { throw "Root diperlukan." }

# --- status ------------------------------------------------------------------
$running = (Sh "if pgrep -f SteeringModeDaemon >/dev/null; then echo RUNNING; else echo STOPPED; fi") -join ''
$jarExists = (Sh "if [ -f $JAR_REMOTE ]; then echo YES; else echo NO; fi") -join ''
$jarSha = ''
if ($jarExists -eq 'YES') {
    $raw = Sh "sha256sum $JAR_REMOTE"
    if ($raw) { $jarSha = ($raw -join ' ').Split(' ')[0].Trim() }
}

if ($Status) {
    Write-Host "`n=== STATUS ===" -ForegroundColor Cyan
    Write-Host "  Daemon      : $running"
    Write-Host "  JAR sha256  : $(if ($jarSha) { $jarSha } else { 'TIADA' })"
    if ($jarSha -eq $EXPECTED_SHA) {
        Write-Host "  JAR         : SAH (padan dengan versi diuji)" -ForegroundColor Green
    } elseif ($jarSha) {
        Write-Host "  JAR         : BERBEZA daripada versi pakej" -ForegroundColor Yellow
    }
    $launch = (Sh "if [ -f $LAUNCHER_REMOTE ]; then echo YES; else echo NO; fi") -join ''
    Write-Host "  Pelancar    : $(if ($launch -eq 'YES') { 'ada' } else { 'TIADA' })"
    Write-Host "`n  Butang stereng:" -ForegroundColor DarkCyan
    Write-Host "    Kanan 800ms  -> Drive Mode (Comfort/Eco/Sport)"
    Write-Host "    Kiri  800ms  -> Regen (Low/Medium/High)"
    Write-Host "    Bawah 5000ms -> Mod Servis (Stealth)"
    exit 0
}

# --- uninstall ---------------------------------------------------------------
if ($Uninstall) {
    Write-Host "`n[UNINSTALL] Membuang daemon stereng..." -ForegroundColor Yellow
    Sh "pkill -f SteeringModeDaemon 2>/dev/null"
    Sh "rm -f $LAUNCHER_REMOTE $JAR_REMOTE /data/local/window/steering.pid"
    Write-Host "  -> daemon dihentikan, fail dibuang" -ForegroundColor Green
    Write-Host "`n  NOTA: Mod Servis (Stealth) tidak lagi boleh ditukar dari stereng." -ForegroundColor Yellow
    Write-Host "  Guna sc_stealth_mode.ps1 dari laptop." -ForegroundColor DarkCyan
    exit 0
}

$plan = @($JAR_REMOTE, $LAUNCHER_REMOTE)

if ($WhatIf) {
    Write-Host "`n[WhatIf] Rancangan:" -ForegroundColor Cyan
    $plan | ForEach-Object { Write-Host "  -> $_" }
    Write-Host "  daftar auto-start dalam $AVAS_BOOT"
    Write-Host "`nTiada perubahan dibuat." -ForegroundColor Yellow
    exit 0
}

# --- sahkan jar tempatan -----------------------------------------------------
Write-Host "`n[3/5] Mengesahkan JAR tempatan..." -ForegroundColor White
$localJar = Join-Path $Bin 'steering_mode_controller.jar'
if (-not (Test-Path $localJar)) { throw "payload/bin/steering_mode_controller.jar tiada" }
$localSha = (Get-FileHash $localJar -Algorithm SHA256).Hash.ToLower()
Write-Host "  sha256 : $localSha"
if ($localSha -ne $EXPECTED_SHA) {
    throw "JAR tempatan tidak padan dengan hash diuji. Jangkaan: $EXPECTED_SHA"
}
Write-Host "  -> SAH, padan dengan versi yang diuji pada kereta" -ForegroundColor Green

# --- backup + pasang ---------------------------------------------------------
Write-Host "`n[4/5] Memasang..." -ForegroundColor White
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
Sh "mkdir -p /data/local/window/backup_drivemode" | Out-Null

$oldJar = (Sh "if [ -f $JAR_REMOTE ]; then echo YES; else echo NO; fi") -join ''
if ($oldJar -eq 'YES') {
    Sh "cp $JAR_REMOTE /data/local/window/backup_drivemode/steering_mode_controller.jar.$stamp"
    Write-Host "  backup JAR lama" -ForegroundColor DarkGray
}
$oldLaunch = (Sh "if [ -f $LAUNCHER_REMOTE ]; then echo YES; else echo NO; fi") -join ''
if ($oldLaunch -eq 'YES') {
    Sh "cp $LAUNCHER_REMOTE /data/local/window/backup_drivemode/start_steering_daemon.sh.$stamp"
    Write-Host "  backup pelancar lama" -ForegroundColor DarkGray
}

# hantar ke fail sementara dahulu, sahkan hash, kemudian ganti
& $Adb -s $script:device push $localJar "$JAR_REMOTE.new" | Out-Null
$remoteRaw = Sh "sha256sum $JAR_REMOTE.new"
$remoteSha = if ($remoteRaw) { ($remoteRaw -join ' ').Split(' ')[0].Trim() } else { '' }
if ($remoteSha -ne $EXPECTED_SHA) { throw "Hash selepas push tidak padan. Dibatalkan." }
Sh "mv $JAR_REMOTE.new $JAR_REMOTE"
Write-Host "  JAR dihantar dan disahkan" -ForegroundColor Green

& $Adb -s $script:device push (Join-Path $Payload 'start_steering_daemon.sh') $LAUNCHER_REMOTE | Out-Null
Sh "chmod 755 $LAUNCHER_REMOTE"
Sh "chmod 666 $JAR_REMOTE"
Sh "chown root:root $JAR_REMOTE $LAUNCHER_REMOTE 2>/dev/null"
Sh "restorecon $JAR_REMOTE $LAUNCHER_REMOTE 2>/dev/null"
Write-Host "  pelancar dipasang" -ForegroundColor Green

# daftar auto-start jika belum
$hasHook = (Sh "grep -c start_steering_daemon $AVAS_BOOT 2>/dev/null") -join ''
if ($hasHook -eq '0') {
    Sh "printf '\n# Steering daemon (Drive Mode + Regen + Stealth toggle)\nif [ -f /data/local/window/start_steering_daemon.sh ]; then\n    sh /data/local/window/start_steering_daemon.sh >> \`$LOG 2>&1\nfi\n' >> $AVAS_BOOT"
    Write-Host "  auto-start didaftarkan dalam avas_boot.sh" -ForegroundColor Green
} else {
    Write-Host "  auto-start sudah ada" -ForegroundColor DarkGray
}

# --- mula + sahkan -----------------------------------------------------------
Write-Host "`n[5/5] Memulakan daemon..." -ForegroundColor White
$startOut = Sh "sh $LAUNCHER_REMOTE"
$startOut | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
Start-Sleep -Seconds 2

$now = (Sh "if pgrep -f SteeringModeDaemon >/dev/null; then echo RUNNING; else echo STOPPED; fi") -join ''
$newRaw = Sh "sha256sum $JAR_REMOTE"
$newSha = if ($newRaw) { ($newRaw -join ' ').Split(' ')[0].Trim() } else { '' }

Write-Host ""
Write-Host "  Daemon      : $now"
Write-Host "  JAR sha256  : $newSha"

Write-Host "`n============================================================" -ForegroundColor Green
if ($now -eq 'RUNNING' -and $newSha -eq $EXPECTED_SHA) {
    Write-Host "  PEMASANGAN SELESAI" -ForegroundColor Green
} else {
    Write-Host "  AMARAN: semak nilai di atas" -ForegroundColor Yellow
}
Write-Host "============================================================" -ForegroundColor Green
Write-Host ""
Write-Host "  Butang stereng:" -ForegroundColor Cyan
Write-Host "    Kanan 800ms   -> Drive Mode  (Comfort -> Eco -> Sport)"
Write-Host "    Kiri  800ms   -> Regen       (Low -> Medium -> High)"
Write-Host "    Bawah 5000ms  -> Mod Servis  (Stealth ON/OFF)"
Write-Host "    Atas          -> volume kilang (tidak diubah)"
Write-Host ""
Write-Host "  Backup : /data/local/window/backup_drivemode/" -ForegroundColor DarkGray
Write-Host "  Status : .\pasang_drivemode.ps1 -Status" -ForegroundColor DarkGray
Write-Host "  Buang  : .\pasang_drivemode.ps1 -Uninstall" -ForegroundColor DarkGray
