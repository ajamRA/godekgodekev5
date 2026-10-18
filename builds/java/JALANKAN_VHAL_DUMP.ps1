# ==============================================================================
# VhalDump - penyenaraian VHAL (READ-ONLY)
#
# Menyenaraikan SEMUA property yang didedahkan VHAL kereta melalui
# ICarProperty.getPropertyList(), kemudian membaca nilai semasa setiap satu.
#
# Selamat: tiada setProperty, tiada socket, tiada sentuhan MCU/UART.
#
# Guna:
#   .\JALANKAN_VHAL_DUMP.ps1
#   .\JALANKAN_VHAL_DUMP.ps1 -Target 192.168.1.20:5555
# ==============================================================================

param(
    [string]$Target = "",
    [string]$Adb = "",
    [switch]$PullOnly
)

$ErrorActionPreference = 'Stop'

# --- Cari adb ---------------------------------------------------------------
if (-not $Adb) {
    $candidates = @(
        "$PSScriptRoot\..\..\EMAS_TOOLBOX_DESKTOP\tools\adb.exe",
        "$env:USERPROFILE\scoop\apps\android-clt\current\platform-tools\adb.exe",
        "adb"
    )
    foreach ($c in $candidates) {
        if ($c -eq "adb") {
            $cmd = Get-Command adb -ErrorAction SilentlyContinue
            if ($cmd) { $Adb = $cmd.Source; break }
        } elseif (Test-Path $c) { $Adb = (Resolve-Path $c).Path; break }
    }
}
if (-not $Adb) { throw "adb tidak dijumpai. Guna -Adb <laluan adb.exe>" }

Write-Host "adb : $Adb" -ForegroundColor DarkGray

# --- Sasaran -----------------------------------------------------------------
$adbArgs = @()
if ($Target) {
    & $Adb connect $Target | Out-Host
    $adbArgs = @('-s', $Target)
}

$devices = & $Adb devices
Write-Host "Peranti:" -ForegroundColor Cyan
$devices | Out-Host

if (-not $PullOnly) {

    Write-Host "`n[1/4] Menolak VhalDump.jar ke /data/local/tmp ..." -ForegroundColor White
    $jar = Join-Path $PSScriptRoot 'VhalDump.jar'
    if (-not (Test-Path $jar)) { throw "VhalDump.jar tiada di $PSScriptRoot" }

    & $Adb @adbArgs push $jar /data/local/tmp/VhalDump.jar | Out-Host

    Write-Host "`n[2/4] Menjalankan enumerator (read-only, tiada tulisan ke VHAL)..." -ForegroundColor White
    Write-Host "      Ini akan mengambil masa 1-3 minit untuk property yang banyak." -ForegroundColor DarkGray
    & $Adb @adbArgs shell "CLASSPATH=/data/local/tmp/VhalDump.jar app_process /data/local/tmp com.emas.dump.VhalDump" | Out-Host

    Write-Host "`n[3/4] Memeriksa fail output..." -ForegroundColor White
    & $Adb @adbArgs shell "ls -l /data/local/tmp/vhal_dump.json" | Out-Host

    Write-Host "`n[4/4] Menarik hasil ke laptop..." -ForegroundColor White
    $local = Join-Path $PSScriptRoot 'vhal_dump.json'
    & $Adb @adbArgs pull /data/local/tmp/vhal_dump.json $local | Out-Host

} else {
    Write-Host "`n[MODE PULL] Menarik /data/local/tmp/vhal_dump.json sahaja..." -ForegroundColor White
    $local = Join-Path $PSScriptRoot 'vhal_dump.json'
    & $Adb @adbArgs pull /data/local/tmp/vhal_dump.json $local | Out-Host
}

# --- Ringkasan ---------------------------------------------------------------
if (Test-Path $local) {
    $size = (Get-Item $local).Length
    Write-Host "`n=== SELESAI ===" -ForegroundColor Green
    Write-Host "Fail   : $local"
    Write-Host "Saiz   : $([math]::Round($size/1KB,1)) KB"

    try {
        $json = Get-Content $local -Raw | ConvertFrom-Json
        Write-Host "Jumlah property : $($json.total_properties)"
        Write-Host "Entri           : $($json.properties.Count)"

        $vendor = @($json.properties | Where-Object { $_.is_vendor -eq $true })
        $ok     = @($json.properties | Where-Object { $_.read_status -eq 'OK' })

        Write-Host "Vendor (ECARX)  : $($vendor.Count)"
        Write-Host "Baca berjaya    : $($ok.Count)"

        Write-Host "`n--- 20 property vendor pertama ---" -ForegroundColor Cyan
        $vendor | Select-Object -First 20 |
            Format-Table prop_id_hex, data_type, area_id, read_status, value -AutoSize |
            Out-String -Width 200 | Write-Host
    } catch {
        Write-Host "[!] JSON tidak dapat dihuraikan: $_" -ForegroundColor Yellow
    }
} else {
    Write-Host "[!] Fail output tidak dijumpai." -ForegroundColor Yellow
}
