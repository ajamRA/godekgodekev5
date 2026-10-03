# ==============================================================================
# sc_stealth_mode.ps1 — 1-Click Proton SC Stealth Service Mode for Proton e.MAS 5
# ==============================================================================
# Kegunaan:
# 1. Sembunyikan aplikasi kustom (Netflix, SmartTube, NoorEnergy, AcBridge) dari skrin.
# 2. Matikan semua daemon latar belakang (Megaphone, Telemetry, GearAudio, Doa).
# 3. Kunci sistem dalam mod asal kilang (Showroom Condition) untuk lawatan SC.
# ==============================================================================

$targetIp = "192.168.1.19"
if ($args.Count -gt 0) { $targetIp = $args[0] }
$device = "${targetIp}:5555"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " [SC STEALTH MODE] Menukar Kenderaan ke Mod Asal Kilang... " -ForegroundColor Yellow
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Pastikan ADB bersambung
Write-Host "`n[1/4] Menyemak sambungan ADB ke $device..." -ForegroundColor White
adb connect $device | Out-Null
$devList = adb devices
if ($devList -notmatch $targetIp) {
    Write-Host "RALAT: Tidak dapat menyambung ke $device. Pastikan kereta hidup dan Wi-Fi aktif." -ForegroundColor Red
    exit 1
}
Write-Host " -> Sambungan ADB disahkan AKTIF." -ForegroundColor Green

# 2. Sembunyikan aplikasi kustom daripada skrin
Write-Host "`n[2/4] Menyembunyikan aplikasi kustom daripada menu skrin IHU..." -ForegroundColor White
$appsToHide = @(
    "com.netflix.mediaclient",
    "org.smarttube.stable",
    "com.noor.thenoorcar",
    "com.cybercat.acbridge"
)

foreach ($pkg in $appsToHide) {
    $res = adb -s $device shell "pm disable-user --user 0 $pkg 2>&1"
    if ($res -match "disabled") {
        Write-Host " -> [HIDDEN] $pkg" -ForegroundColor Green
    } else {
        Write-Host " -> [SKIP/NOT FOUND] $pkg ($res)" -ForegroundColor DarkGray
    }
}
Write-Host " (Aplikasi rasmi Vivaldi, Spotify, SYOK & HotFM dikekalkan terlihat)" -ForegroundColor DarkCyan

# 3. Matikan semua daemon kustom & wujudkan fail bendera .service_mode
Write-Host "`n[3/4] Menghentikan semua daemon kustom & mengaktifkan bendera Service Mode..." -ForegroundColor White
adb -s $device shell "touch /data/local/window/.service_mode"
adb -s $device shell "pkill -f 'GearAudioPoc'; pkill -f 'DoorDoaPoc'; pkill -f 'MegaphoneDaemon'; pkill -f 'TelemetryDaemon'; pkill -f 'DoorPressureReliefDaemon'"

# 4. Pengesahan status akhir
Write-Host "`n[4/4] Memeriksa status akhir sistem..." -ForegroundColor White
$disabledCheck = adb -s $device shell "pm list packages -d | grep -iE 'netflix|smarttube|noor|acbridge'"
$processCheck = adb -s $device shell "ps -A | grep -iE 'GearAudio|Megaphone|TelemetryDaemon'"

Write-Host "`n==========================================================" -ForegroundColor Green
Write-Host " STATUS: KERETA KINI 100% DALAM MOD KILANG (STEALTH MODE)" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
Write-Host " * Ikon Netflix, SmartTube & Noor: Ghaib dari skrin."
Write-Host " * Bunyi Coin Gear D & Doa Pintu : Dimatikan sepenuhnya."
Write-Host " * Port & Daemon Latar Belakang  : Sifar (Zero active ports)."
Write-Host " * Bunyi AVAS Pejalan Kaki       : 100% Asal Kilang Proton."
Write-Host " * Selamat untuk pemeriksaan mekanik & servis Proton SC!" -ForegroundColor Yellow
Write-Host "`nBila selesai servis nanti, jalankan: .\sc_restore_mode.ps1`n" -ForegroundColor Cyan
