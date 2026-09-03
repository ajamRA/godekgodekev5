# 6-char IHU password used by About IHU WLAN long-press
# and by debugtools USB-downgrade button.
# Usage:
#   .\hu-password.ps1 -IhuId "YOUR_IHU_ID"
# IHU ID is shown on the same About IHU screen.

param(
    [Parameter(Mandatory = $true)][string]$IhuId,
    [ValidateSet("wlan", "downgrade")][string]$Kind = "wlan"
)

$salt = if ($Kind -eq "wlan") { "universal168" } else { "clE1o60h" }

$tz = [TimeZoneInfo]::FindSystemTimeZoneById("Singapore Standard Time")
$now = [TimeZoneInfo]::ConvertTimeFromUtc([datetime]::UtcNow, $tz)
$s = $now.ToString("yyyyMMddHHmm")
$last = [int]$s.Substring($s.Length - 1, 1)
if ($last -lt 5) { $last = 0 } elseif ($last -gt 5) { $last = 5 }
$date = $s.Substring(0, $s.Length - 1) + "$last"

$md5 = [Security.Cryptography.MD5]::Create()
$hex = -join ($md5.ComputeHash([Text.Encoding]::UTF8.GetBytes($date + $IhuId + $salt)) | ForEach-Object { $_.ToString("x2") })
$dec = [bigint]::Parse("0" + $hex, [Globalization.NumberStyles]::AllowHexSpecifier)
$chars = $dec.ToString().ToCharArray()
[array]::Reverse($chars)
$odd = -join (0..($chars.Length - 1) | Where-Object { $_ % 2 -eq 0 } | ForEach-Object { $chars[$_] })
$code = $odd.Substring(0, [Math]::Min(6, $odd.Length))

Write-Host "kind=$Kind  dateSlot=$date (GMT+8, 5-min window)"
Write-Host "code=$code"
Write-Host "alt static (if engineer_local is not disable): BX9527"
Write-Host "alt date (if engineer_local is not disable): $($now.ToString('yyyyMMdd'))aco"
