param([string]$OutputDir='', [string]$ApiKey=$env:EMAS_API_KEY, [int]$VersionCode=0, [string]$VersionName='')
$ErrorActionPreference='Stop'
$project=$PSScriptRoot
$repo=Split-Path $project -Parent
$buildTools='C:\Users\user\scoop\persist\android-clt\build-tools\34.0.0'
$androidJar='C:\Users\user\scoop\persist\android-clt\platforms\android-34\android.jar'
if (!$OutputDir) { $OutputDir=Join-Path $repo ('artifacts\emas-remote-v2-'+[guid]::NewGuid().ToString('N')) }
if (Test-Path -LiteralPath $OutputDir) { throw 'Use a new output directory.' }
$OutputDir=[IO.Path]::GetFullPath($OutputDir)
if (!$ApiKey) { throw 'Set EMAS_API_KEY for a phone build that works with the owner VPS.' }
if ($ApiKey -notmatch '^[A-Za-z0-9_-]{1,200}$') { throw 'Invalid API key format.' }
$classes=Join-Path $OutputDir 'classes';$dex=Join-Path $OutputDir 'dex';$generated=Join-Path $OutputDir 'generated'
New-Item -ItemType Directory -Path $classes,$dex,$generated | Out-Null
function CheckExit([string]$step) { if ($LASTEXITCODE -ne 0) { throw ($step+' failed.') } }
$apkName='eMAS_Remote_v2.13_Minimal.apk'
if ($VersionCode -gt 0) {
    if ($VersionName -notmatch '^\d+\.\d+$') { throw 'Invalid version name' }
    $apkName='eMAS_Remote_v'+$VersionName+'_Sync.apk'
}
$raw=Join-Path $OutputDir 'remote-unaligned.apk';$aligned=Join-Path $OutputDir 'remote-unsigned.apk';$signed=Join-Path $OutputDir $apkName
$manifest = Join-Path $project 'AndroidManifest.xml'
if ($VersionCode -gt 0) {
    $manifest = Join-Path $OutputDir 'AndroidManifest.xml'
    $xml = Get-Content -LiteralPath (Join-Path $project 'AndroidManifest.xml') -Raw
    $xml = $xml -replace 'android:versionCode="[0-9]+"', ('android:versionCode="'+$VersionCode+'"')
    $xml = $xml -replace 'android:versionName="[^"]+"', ('android:versionName="'+$VersionName+'"')
    [IO.File]::WriteAllText($manifest, $xml, (New-Object Text.UTF8Encoding($false)))
}
& "$buildTools/aapt.exe" package -f -M $manifest -I $androidJar -S (Join-Path $project 'res') -A (Join-Path $project 'assets') -J $generated -F $raw
CheckExit 'Resources and assets'
$config=Join-Path $generated 'BuildConfig.java'
[IO.File]::WriteAllText($config, ('package com.azam.emaswidget; public final class BuildConfig { public static final String DEFAULT_API_KEY="'+$ApiKey+'"; }'), (New-Object Text.UTF8Encoding($false)))
$sources=@(Get-ChildItem -LiteralPath (Join-Path $project 'src') -Recurse -Filter '*.java' | ForEach-Object FullName)
$sources+=@(Get-ChildItem -LiteralPath $generated -Recurse -Filter '*.java' | ForEach-Object FullName)
& javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -cp $androidJar -d $classes $sources
CheckExit 'Android compilation'
$classFiles=@(Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class' | ForEach-Object FullName)
& java -cp (Join-Path $repo 'builds/java/r8.jar') com.android.tools.r8.D8 --min-api 26 --lib $androidJar --output $dex $classFiles
CheckExit 'DEX compilation'
Push-Location $dex
try { & "$buildTools/aapt.exe" add $raw classes.dex;CheckExit 'DEX packaging' } finally { Pop-Location }
& "$buildTools/zipalign.exe" -f 4 $raw $aligned;CheckExit 'Alignment'
$keyPath=Join-Path $repo '_archive_experiments/drive_assist_repo/keystore/debug.ks'
$signingConfig=Get-Content -LiteralPath (Join-Path $repo 'tools/powershell/build_noor_clean.ps1') -Raw
$store=[regex]::Match($signingConfig,'--ks-pass\s+pass:([^\s]+)')
$key=[regex]::Match($signingConfig,'--key-pass\s+pass:([^\s]+)')
if (!$store.Success -or !$key.Success -or !(Test-Path -LiteralPath $keyPath)) { throw 'Existing owner signing configuration missing.' }
$env:EMAS_REMOTE_STORE_PASS=$store.Groups[1].Value;$env:EMAS_REMOTE_KEY_PASS=$key.Groups[1].Value
try {
    & "$buildTools/apksigner.bat" sign --ks $keyPath --ks-key-alias d --ks-pass env:EMAS_REMOTE_STORE_PASS --key-pass env:EMAS_REMOTE_KEY_PASS --out $signed $aligned
    CheckExit 'Signing'
} finally { Remove-Item Env:EMAS_REMOTE_STORE_PASS,Env:EMAS_REMOTE_KEY_PASS -ErrorAction SilentlyContinue }
& "$buildTools/apksigner.bat" verify --verbose --print-certs $signed;CheckExit 'APK signature'
& "$buildTools/zipalign.exe" -c 4 $signed;CheckExit 'Signed alignment'
$original=Join-Path $repo 'artifacts/remote-vps-audit-20261008-7b48afe3/eMAS_Remote.apk'
$before=(& "$buildTools/apksigner.bat" verify --print-certs $original | Select-String 'certificate SHA-256 digest').Line
$after=(& "$buildTools/apksigner.bat" verify --print-certs $signed | Select-String 'certificate SHA-256 digest').Line
if ($before -ne $after) { throw 'Signing certificate differs from the existing phone app. Do not install.' }
((Get-FileHash -LiteralPath $signed -Algorithm SHA256).Hash+'  '+$apkName) | Set-Content -LiteralPath (Join-Path $OutputDir 'SHA256SUMS.txt')
Write-Output ('APK='+$signed)
Write-Output 'PASS same package and signing certificate as the existing phone app.'
