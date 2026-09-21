# 6-char IHU Rolling Password Generator for Geely EX2 (EX2-HU)
# Usage:
#   .\hu-password.ps1
#   .\hu-password.ps1 -IhuId "021600000000000000000000"
#   .\hu-password.ps1 -Watch

param(
    [string]$IhuId = "021600000000000000000000",
    [switch]$Watch
)

function Get-IhuPassword($id, $salt, $dateSlot) {
    $md5 = [Security.Cryptography.MD5]::Create()
    $hex = -join ($md5.ComputeHash([Text.Encoding]::UTF8.GetBytes($dateSlot + $id + $salt)) | ForEach-Object { $_.ToString("x2") })
    $dec = [bigint]::Parse("0" + $hex, [Globalization.NumberStyles]::AllowHexSpecifier)
    $chars = $dec.ToString().ToCharArray()
    [array]::Reverse($chars)
    $odd = -join (0..($chars.Length - 1) | Where-Object { $_ % 2 -eq 0 } | ForEach-Object { $chars[$_] })
    return $odd.Substring(0, [Math]::Min(6, $odd.Length))
}

function Show-Codes {
    $tz = [TimeZoneInfo]::FindSystemTimeZoneById("Singapore Standard Time")
    $now = [TimeZoneInfo]::ConvertTimeFromUtc([datetime]::UtcNow, $tz)
    $s = $now.ToString("yyyyMMddHHmm")
    $last = [int]$s.Substring($s.Length - 1, 1)
    $lastSlot = if ($last -lt 5) { 0 } else { 5 }
    $slot = $s.Substring(0, $s.Length - 1) + "$lastSlot"

    $min = $now.Minute
    $sec = $now.Second
    $inSlot = ($min % 5) * 60 + $sec
    $left = 300 - $inSlot

    $codeAtlas = Get-IhuPassword $IhuId "atlas666" $slot
    $codeWlan = Get-IhuPassword $IhuId "universal168" $slot
    $codeDown = Get-IhuPassword $IhuId "clE1o60h" $slot

    Clear-Host
    Write-Host "============================================================" -ForegroundColor Cyan
    Write-Host "       EX2-HU ROLLING CODE GENERATOR (Geely EX2)       " -ForegroundColor Yellow
    Write-Host "============================================================" -ForegroundColor Cyan
    Write-Host " Waktu Sekarang : $($now.ToString('yyyy-MM-dd HH:mm:ss')) (GMT+8)"
    Write-Host " Slot Semasa    : $slot  (Baki masa: ${left}s)" -ForegroundColor Green
    Write-Host " IHU ID         : $IhuId"
    Write-Host "------------------------------------------------------------"
    Write-Host " [1] ATLAS OS / Geely EX2 : " -NoNewline
    Write-Host "$codeAtlas" -ForegroundColor Yellow -NoNewline
    Write-Host "  (Disyorkan untuk Geely EX2)"
    Write-Host " [2] WLAN Long-Press   : " -NoNewline
    Write-Host "$codeWlan" -ForegroundColor White
    Write-Host " [3] USB Downgrade     : " -NoNewline
    Write-Host "$codeDown" -ForegroundColor White
    Write-Host "------------------------------------------------------------"
    Write-Host " Kata Laluan Pintas Statik (Master Bypass):"
    Write-Host "  * Kod Statik 1       : BX9527" -ForegroundColor Magenta
    Write-Host "  * Kod Statik 2       : $($now.ToString('yyyyMMdd'))aco" -ForegroundColor Magenta
    Write-Host "============================================================" -ForegroundColor Cyan
    if ($Watch) {
        Write-Host " [Mod Pantau Aktif] Mengemas kini setiap saat... (Tekan Ctrl+C untuk henti)" -ForegroundColor Gray
    }
}

if ($Watch) {
    while ($true) {
        Show-Codes
        Start-Sleep -Seconds 1
    }
} else {
    Show-Codes
}
