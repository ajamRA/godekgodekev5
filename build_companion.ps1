param([string]$OutputDir = '')
$ErrorActionPreference = 'Stop'
$repoRoot = $PSScriptRoot
if (!$OutputDir) { $OutputDir = Join-Path $repoRoot ('artifacts\companion-build-' + [guid]::NewGuid().ToString('N')) }
if (Test-Path -LiteralPath $OutputDir) { throw 'Choose a new output directory to preserve earlier builds.' }
$OutputDir = [System.IO.Path]::GetFullPath($OutputDir)
New-Item -ItemType Directory -Path $OutputDir | Out-Null
$androidJar = Join-Path $repoRoot 'android.jar'
$buildTools = 'C:\Users\user\scoop\persist\android-clt\build-tools\34.0.0'
function Check-Exit([string]$Step) { if ($LASTEXITCODE -ne 0) { throw "$Step failed" } }
Push-Location $repoRoot
try {
    & node --test tests/companion.test.cjs tests/vehicle-client.test.cjs tests/ui-regression.test.cjs tests/lock-sound-ui.test.cjs
    Check-Exit 'Web tests'
    & (Join-Path $repoRoot 'tests/run-locksound.ps1') -OutputDir (Join-Path $OutputDir 'tests')
    $uiClasses = Join-Path $OutputDir 'ui-classes'; $uiDex = Join-Path $OutputDir 'ui-dex'
    $daemonClasses = Join-Path $OutputDir 'daemon-classes'; $daemonDex = Join-Path $OutputDir 'daemon-dex'
    New-Item -ItemType Directory -Path $uiClasses,$uiDex,$daemonClasses,$daemonDex | Out-Null
    $sources = @(Get-ChildItem EmasEnergyApp/src/com/noor/thenoorcar -Filter '*.java' | ForEach-Object FullName)
    & javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -cp $androidJar -d $uiClasses $sources EmasEnergyApp/src/com/emas/energy/MainActivity.java
    Check-Exit 'Both UI variants compilation'
    & javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -cp "$uiClasses;$androidJar" -d $uiClasses tests/TestVehicleHttpClient.java
    Check-Exit 'Native client fixture compilation'
    & java -cp "$uiClasses;$androidJar" com.noor.thenoorcar.TestVehicleHttpClient
    Check-Exit 'Native client tests'
    $classes = @(Get-ChildItem $uiClasses -Recurse -Filter '*.class' | Where-Object Name -NotLike 'TestVehicleHttpClient*' | ForEach-Object FullName)
    & java -cp r8.jar com.android.tools.r8.D8 --min-api 24 --lib $androidJar --output $uiDex $classes
    Check-Exit 'UI D8'
    & javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -cp $androidJar -d $daemonClasses src_megaphone/com/emas/megaphone/MegaphoneDaemon.java src_megaphone/com/emas/megaphone/AvasPlaybackClient.java src_window/com/emas/window/AtomicWavDeployment.java
    Check-Exit 'Daemon compilation'
    $daemonFiles = @(Get-ChildItem $daemonClasses -Recurse -Filter '*.class' | ForEach-Object FullName)
    & java -cp r8.jar com.android.tools.r8.D8 --min-api 29 --lib $androidJar --output $daemonDex $daemonFiles
    Check-Exit 'Daemon D8'
    & jar cf (Join-Path $OutputDir 'megaphone_daemon.jar') -C $daemonDex classes.dex
    Check-Exit 'Daemon packaging'
    $windowClasses = Join-Path $OutputDir 'window-classes'; $windowDex = Join-Path $OutputDir 'window-dex'
    New-Item -ItemType Directory -Path $windowClasses,$windowDex | Out-Null
    $windowSources = @(Get-ChildItem -LiteralPath 'src_door_hub\com\emas\door' -Filter '*.java' | ForEach-Object FullName) +
                     @('DoorPressureReliefDaemon.java','DoorHubHttpServer.java','LocalHttpRequest.java','WindowRequestPolicy.java','DoorState.java','WindowSafetyManager.java' |
                     ForEach-Object { 'src_window/com/emas/window/' + $_ }) +
                     @('tests/TestWindowRequestPolicy.java')
    & javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -cp $androidJar -d $windowClasses $windowSources
    Check-Exit 'Window daemon compilation'
    & java -cp "$windowClasses;$androidJar" com.emas.window.TestWindowRequestPolicy
    Check-Exit 'Window request tests'
    $windowFiles = @(Get-ChildItem $windowClasses -Recurse -Filter '*.class' | Where-Object Name -NotLike 'TestWindowRequestPolicy*' | ForEach-Object FullName)
    & java -cp r8.jar com.android.tools.r8.D8 --min-api 29 --lib $androidJar --output $windowDex $windowFiles
    Check-Exit 'Window D8'
    & jar cf (Join-Path $OutputDir 'door_pressure_relief_daemon.jar') -C $windowDex classes.dex
    Check-Exit 'Window packaging'
    $variants = @(@{Name='NoorEnergy-companion';Manifest='AndroidManifest_Noor.xml'},@{Name='EmasEnergy-companion';Manifest='AndroidManifest.xml'})
    foreach ($variant in $variants) {
        $manifestDir = Join-Path $OutputDir $variant.Name
        New-Item -ItemType Directory -Path $manifestDir | Out-Null
        $manifestPath = Join-Path $manifestDir 'AndroidManifest.xml'
        Copy-Item -LiteralPath (Join-Path $repoRoot ('EmasEnergyApp/' + $variant.Manifest)) -Destination $manifestPath
        $rawApk = Join-Path $OutputDir ($variant.Name + '-unaligned.apk')
        $apk = Join-Path $OutputDir ($variant.Name + '-unsigned.apk')
        & "$buildTools/aapt.exe" package -f -M $manifestPath -I $androidJar -S EmasEnergyApp/res -A EmasEnergyApp/assets -F $rawApk
        Check-Exit 'APK resources'
        Push-Location $uiDex
        try { & "$buildTools/aapt.exe" add $rawApk classes.dex; Check-Exit 'APK classes' } finally { Pop-Location }
        & "$buildTools/zipalign.exe" -f 4 $rawApk $apk
        Check-Exit 'APK alignment'
        & "$buildTools/zipalign.exe" -c 4 $apk
        Check-Exit 'APK alignment verification'
    }
    $files = @('megaphone_daemon.jar','door_pressure_relief_daemon.jar','NoorEnergy-companion-unsigned.apk','EmasEnergy-companion-unsigned.apk')
    $files | ForEach-Object { $file = Join-Path $OutputDir $_; "$((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant())  $_" } | Set-Content (Join-Path $OutputDir 'SHA256SUMS.txt')
    Write-Output "LOCAL_COMPANION_BUILD=$OutputDir (APKs unsigned; no installation performed)"
} finally { Pop-Location }
