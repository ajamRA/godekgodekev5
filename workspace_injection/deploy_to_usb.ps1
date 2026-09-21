$src = "D:\apps\emas-ota\workspace_injection\output\update.zip"
if (-not (Test-Path $src)) {
    Write-Error "Source update.zip not found at $src"
    exit 1
}

$srcHash = (Get-FileHash -Path $src -Algorithm SHA256).Hash
Write-Host "[+] Source file: $src"
Write-Host "    Size: $((Get-Item $src).Length) bytes"
Write-Host "    SHA-256: $srcHash"

$drives = Get-Volume | Where-Object { $_.DriveType -eq 'Removable' -and $_.DriveLetter }
if (-not $drives) {
    Write-Warning "[!] No removable drive currently detected. Please plug in the pendrive."
    exit 0
}

foreach ($d in $drives) {
    $targetDir = "$($d.DriveLetter):\"
    $targetFile = Join-Path $targetDir "update.zip"
    Write-Host "[+] Found removable drive $($d.DriveLetter): ($($d.FileSystemType), $($d.SizeRemaining) bytes free)"
    Write-Host "    Copying update.zip to $targetFile..."
    Copy-Item -Path $src -Destination $targetFile -Force
    $dstHash = (Get-FileHash -Path $targetFile -Algorithm SHA256).Hash
    Write-Host "    Target SHA-256: $dstHash"
    if ($srcHash -eq $dstHash) {
        Write-Host ">>> COPY SUCCESSFUL & VERIFIED 100%! Ready for car flashing. <<<" -ForegroundColor Green
    } else {
        Write-Error "Hash mismatch! Please re-run copy."
    }
}
