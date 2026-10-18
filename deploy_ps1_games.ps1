# ==============================================================================
# deploy_ps1_games.ps1 — Deploy PS1 Core & Games to Proton e.MAS 5
# ==============================================================================

$targetIp = "192.168.1.20"
if ($args.Count -gt 0) { $targetIp = $args[0] }
$device = "${targetIp}:5555"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " [PS1 RETRO DEPLOY] Memasang Core PS1 & Games ke Kereta... " -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Sambung ADB
Write-Host "`n[1/4] Menyemak sambungan ADB ke $device..." -ForegroundColor White
adb connect $device | Out-Null
$devList = adb devices
if (-not ($devList -match [regex]::Escape($targetIp))) {
    Write-Host "RALAT: Tidak dapat menyambung ke $device. Sila pastikan kereta ON dan Wi-Fi aktif." -ForegroundColor Red
    exit 1
}
Write-Host " -> Sambungan ADB AKTIF!" -ForegroundColor Green

# 2. Pasang Core PCSX ReARMed
Write-Host "`n[2/4] Menyuntik Core PS1 (PCSX ReARMed ARM64)..." -ForegroundColor White
adb -s $device push "d:\apps\emas-ota\builds\native_bin\pcsx_rearmed_libretro_android.so" /data/data/com.retroarch.aarch64/cores/pcsx_rearmed_libretro_android.so
adb -s $device shell "chmod 755 /data/data/com.retroarch.aarch64/cores/pcsx_rearmed_libretro_android.so && chown u0_a90:u0_a90 /data/data/com.retroarch.aarch64/cores/pcsx_rearmed_libretro_android.so"
Write-Host " -> Core PS1 berjaya dipasang!" -ForegroundColor Green

# 3. Hantar fail game (Metal Slug X & Marvel vs Capcom)
Write-Host "`n[3/4] Menghantar fail game ke /storage/emulated/0/RetroGames/PS1/..." -ForegroundColor White
adb -s $device shell "mkdir -p /storage/emulated/0/RetroGames/PS1"

Write-Host " -> Menghantar Metal Slug X..." -ForegroundColor Yellow
adb -s $device push "d:\apps\emas-ota\builds\roms_ps1\Metal Slug X (USA)\Metal Slug X (USA).cue" "/storage/emulated/0/RetroGames/PS1/"
adb -s $device push "d:\apps\emas-ota\builds\roms_ps1\Metal Slug X (USA)\Metal Slug X (USA).bin" "/storage/emulated/0/RetroGames/PS1/"

Write-Host " -> Menghantar Marvel vs. Capcom..." -ForegroundColor Yellow
adb -s $device push "d:\apps\emas-ota\builds\roms_ps1\Marvel vs. Capcom - Clash of Super Heroes (USA)\Marvel vs. Capcom - Clash of Super Heroes (USA).cue" "/storage/emulated/0/RetroGames/PS1/"
adb -s $device push "d:\apps\emas-ota\builds\roms_ps1\Marvel vs. Capcom - Clash of Super Heroes (USA)\Marvel vs. Capcom - Clash of Super Heroes (USA).bin" "/storage/emulated/0/RetroGames/PS1/"

# 4. Pasang Playlist Sony PlayStation ke dalam RetroArch
Write-Host "`n[4/4] Memasang Playlist PlayStation ke dalam Menu Ozone..." -ForegroundColor White
$plPath = "d:\apps\emas-ota\builds\roms_ps1\Sony - PlayStation.lpl"
adb -s $device push $plPath /data/data/com.retroarch.aarch64/playlists/
adb -s $device push $plPath /sdcard/Android/data/com.retroarch.aarch64/files/playlists/
adb -s $device shell "chown u0_a90:u0_a90 /data/data/com.retroarch.aarch64/playlists/* && chmod 666 /data/data/com.retroarch.aarch64/playlists/*"

# Kunci config Ozone (supaya tak bertukar jadi RGUI hijau)
Write-Host " -> Mengunci config Ozone UI..." -ForegroundColor Yellow
adb -s $device push "d:\apps\emas-ota\builds\gaming_retro\retroarch_live.cfg" /data/data/com.retroarch.aarch64/retroarch.cfg
adb -s $device push "d:\apps\emas-ota\builds\gaming_retro\retroarch_live.cfg" /sdcard/Android/data/com.retroarch.aarch64/files/retroarch.cfg
adb -s $device shell "chown u0_a90:u0_a90 /data/data/com.retroarch.aarch64/retroarch.cfg && chmod 660 /data/data/com.retroarch.aarch64/retroarch.cfg"

# Restart RetroArch
adb -s $device shell "am force-stop com.retroarch.aarch64 && am start -n com.retroarch.aarch64/com.retroarch.browser.mainmenu.MainMenuActivity"

Write-Host "`n==========================================================" -ForegroundColor Green
Write-Host " SELESAI! DUA-DUA GAME PS1 DAH SIAP ADA KAT SKRIN KERETA! " -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
Write-Host " * Core PS1: PCSX ReARMed (Locked 60 FPS)"
Write-Host " * Game 1  : Metal Slug X"
Write-Host " * Game 2  : Marvel vs. Capcom - Clash of Super Heroes"
Write-Host " * Menu    : Tab 'Sony PlayStation' dah muncul dalam Ozone!" -ForegroundColor Cyan
