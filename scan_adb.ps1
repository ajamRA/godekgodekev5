$base = "10.105.97."
$found = @()
1..254 | ForEach-Object {
    $ip = "$base$_"
    $tcp = New-Object System.Net.Sockets.TcpClient
    $iar = $tcp.BeginConnect($ip, 5555, $null, $null)
    $wait = $iar.AsyncWaitHandle.WaitOne(200, $false)
    if ($wait -and $tcp.Connected) {
        Write-Host "FOUND_ADB: $ip"
        $tcp.EndConnect($iar)
        $found += $ip
    }
    $tcp.Close()
}
Write-Host "Scan completed. Total found: $($found.Count)"
