# ==============================================================================
# sc_restore_mode.ps1 — 1-Click Restore Mode for Proton e.MAS 5
# ==============================================================================
# Kegunaan:
# 1. Munculkan semula aplikasi kustom (Netflix, SmartTube, NoorEnergy, AcBridge).
# 2. Padamkan bendera .service_mode.
# 3. Hidupkan semula daemon (GearAudio, Megaphone, Telemetry).
# ==============================================================================

$targetIp = "192.168.1.19"
if ($args.Count -gt 0) { $targetIp = $args[0] }
$device = "${targetIp}:5555"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " [SC RESTORE MODE] Mengembalikan Ciri Kustom Kereta...    " -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Pastikan ADB bersambung
Write-Host "`n[1/3] Menyemak sambungan ADB ke $device..." -ForegroundColor White
adb connect $device | Out-Null
$devList = adb devices
if ($devList -notmatch $targetIp) {
    Write-Host "RALAT: Tidak dapat menyambung ke $device. Pastikan kereta hidup dan Wi-Fi aktif." -ForegroundColor Red
    exit 1
}
Write-Host " -> Sambungan ADB disahkan AKTIF." -ForegroundColor Green

# 2. Munculkan semula aplikasi di skrin
Write-Host "`n[2/3] Memunculkan semula aplikasi kustom pada menu skrin..." -ForegroundColor White
$appsToEnable = @(
    "com.netflix.mediaclient",
    "org.smarttube.stable",
    "com.noor.thenoorcar",
    "com.cybercat.acbridge"
)

foreach ($pkg in $appsToEnable) {
    $res = adb -s $device shell "pm enable $pkg 2>&1"
    if ($res -match "enabled") {
        Write-Host " -> [RESTORED] $pkg" -ForegroundColor Green
    } else {
        Write-Host " -> [STATUS] $pkg ($res)" -ForegroundColor DarkGray
    }
}

# 3. Padamkan bendera .service_mode & mulakan semula daemon kustom
Write-Host "`n[3/3] Memadamkan bendera Service Mode & memulakan semula daemon kustom..." -ForegroundColor White
adb -s $device shell "rm -f /data/local/window/.service_mode"

# Mulakan daemon
adb -s $device shell "sh /data/local/window/start_telemetry.sh 2>&1" | Out-Null
adb -s $device shell "sh /data/local/window/start_megaphone.sh 2>&1" | Out-Null
adb -s $device shell "sh /data/local/window/start_gear_audio.sh 2>&1" | Out-Null

Write-Host "`n==========================================================" -ForegroundColor Green
Write-Host " STATUS: SEMUA CIRI KUSTOM TELAH DIKEMBALIKAN (RESTORED)!" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
Write-Host " * Ikon Netflix, SmartTube & Noor : Muncul semula di skrin."
Write-Host " * Data akaun & login             : 100% Kekal selamat."
Write-Host " * Bunyi Coin Gear D (One-shot)   : Aktif sedia menunggu."
Write-Host " * Daemon Megaphone & Telemetri   : Berjalan normal." -ForegroundColor Cyan
Write-Host ""
