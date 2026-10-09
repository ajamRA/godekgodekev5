param(
    [string]$Repo = (Split-Path $PSScriptRoot -Parent),
    [string]$OutputDir = ''
)
$ErrorActionPreference = 'Stop'
if (!$OutputDir) { $OutputDir = Join-Path $Repo ('artifacts\lock-tests-' + [guid]::NewGuid().ToString('N')) }
New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
$classes = Join-Path $OutputDir 'classes'
New-Item -ItemType Directory -Path $classes | Out-Null
$jdk = 'C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot\bin'
$android = Join-Path $Repo 'android.jar'
$log = Join-Path $OutputDir 'test-results.log'
$sources = @(
    (Join-Path $Repo 'src_megaphone/com/emas/megaphone/MegaphoneDaemon.java'),
    (Join-Path $Repo 'src_megaphone/com/emas/megaphone/AvasPlaybackClient.java'),
    (Join-Path $Repo 'src_window/com/emas/window/AtomicWavDeployment.java'),
    (Join-Path $Repo 'EmasEnergyApp/src/com/noor/thenoorcar/LockSoundHttpClient.java')
) + @(Get-ChildItem -LiteralPath (Join-Path $Repo 'tests/locksound') -Filter '*.java' | ForEach-Object FullName)
& "$jdk\javac.exe" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -cp $android -d $classes $sources 2>&1 | Tee-Object -FilePath $log -Append
if ($LASTEXITCODE -ne 0) { throw 'Lock Sound test compilation failed' }
$ErrorActionPreference = 'Continue'
$tests = @('com.emas.megaphone.TestLockCanonical','com.emas.megaphone.TestLockInstallContract',
    'com.emas.megaphone.TestLockHardening','com.emas.megaphone.TestLockAtomicFailures',
    'com.noor.thenoorcar.TestLockSoundHttpClient','TestUiResponsePolicy','com.emas.megaphone.TestLockSoundFixes','com.emas.megaphone.TestLockStatus')
foreach ($test in $tests) {
    $arg = if ($test -eq 'TestUiResponsePolicy') { Join-Path $Repo 'EmasEnergyApp/src/com/noor/thenoorcar/MainActivity.java' }
           else { Join-Path $OutputDir ('fixtures/' + $test) }
    & "$jdk\java.exe" -cp "$classes;$android" $test $arg 2>&1 | Tee-Object -FilePath $log -Append
    if ($LASTEXITCODE -ne 0) { throw "Lock Sound test failed: $test" }
}
& node --test (Join-Path $Repo 'tests/lock-sound-ui.test.cjs') (Join-Path $Repo 'tests/vehicle-client.test.cjs') (Join-Path $Repo 'tests/ui-regression.test.cjs') 2>&1 | Tee-Object -FilePath $log -Append
if ($LASTEXITCODE -ne 0) { throw 'Web UI tests failed' }
$count = @(Select-String -LiteralPath $log -Pattern '^PASS ').Count
"ACTIVE_SOURCE_JAVA_ASSERTIONS=$count; LAPTOP_ONLY=True" | Tee-Object -FilePath $log -Append
Write-Output "LOCK_SOUND_TEST_RESULTS=$log"
