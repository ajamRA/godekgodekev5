$ErrorActionPreference = "Stop"

$BUILD_TOOLS = "C:\Users\user\scoop\persist\android-clt\build-tools\34.0.0"
$JAVA        = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot\bin\java.exe"
$JAVAC       = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot\bin\javac.exe"
$ANDROID_JAR = "D:\apps\emas-ota\android.jar"
$R8_JAR      = "D:\apps\emas-ota\r8.jar"
$KEYSTORE    = "D:\apps\emas-ota\drive_assist_repo\keystore\debug.ks"
$APP_DIR     = "D:\apps\emas-ota\EmasEnergyApp"
$utf8NoBom   = New-Object System.Text.UTF8Encoding($false)

Write-Host "=== BUILDING NOOR ENERGY MONITOR APK ===" -ForegroundColor Cyan

# 1. Prepare directory structure for package com.noor.thenoorcar
$srcPkgDir = "$APP_DIR\src\com\noor\thenoorcar"
if (!(Test-Path $srcPkgDir)) { New-Item -ItemType Directory -Path $srcPkgDir -Force | Out-Null }

# Copy MainActivity with package com.noor.thenoorcar (UTF-8 without BOM)
$javaContent = Get-Content "$APP_DIR\src\com\emas\energy\MainActivity.java" -Raw
$javaContent = $javaContent -replace "package com.emas.energy;", "package com.noor.thenoorcar;"
[System.IO.File]::WriteAllText("$srcPkgDir\MainActivity.java", $javaContent, $utf8NoBom)

# Prepare Manifest in build directory named AndroidManifest.xml
$buildDir = "$APP_DIR\build"
if (!(Test-Path $buildDir)) { New-Item -ItemType Directory -Path $buildDir -Force | Out-Null }

$manifestContent = @"
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.noor.thenoorcar"
    android:versionCode="1"
    android:versionName="1.0.0">

    <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="29" />

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <application
        android:label="e.MAS Energy"
        android:icon="@drawable/ic_launcher"
        android:theme="@android:style/Theme.DeviceDefault.NoActionBar"
        android:usesCleartextTraffic="true"
        android:hardwareAccelerated="true">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="landscape"
            android:configChanges="orientation|screenSize|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>
    </application>
</manifest>
"@
[System.IO.File]::WriteAllText("$buildDir\AndroidManifest.xml", $manifestContent, $utf8NoBom)

# 2. Compile Java
Write-Host "1. Compiling Java..." -ForegroundColor Yellow
$outDir = "$APP_DIR\build\classes"
if (Test-Path $outDir) { Remove-Item -Recurse -Force $outDir }
New-Item -ItemType Directory -Path $outDir -Force | Out-Null

& $JAVAC -source 1.8 -target 1.8 -cp $ANDROID_JAR -d $outDir "$srcPkgDir\MainActivity.java"

# 3. Compile DEX with D8
Write-Host "2. Compiling classes.dex..." -ForegroundColor Yellow
$dexOut = "$APP_DIR\build\dex"
if (Test-Path $dexOut) { Remove-Item -Recurse -Force $dexOut }
New-Item -ItemType Directory -Path $dexOut -Force | Out-Null

$classFiles = Get-ChildItem $outDir -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& $JAVA -cp $R8_JAR com.android.tools.r8.D8 --min-api 24 --lib $ANDROID_JAR --output $dexOut $classFiles

# 4. Package resources and assets with AAPT
Write-Host "3. Packaging with AAPT..." -ForegroundColor Yellow
$unalignedApk = "$APP_DIR\build\unaligned.apk"
if (Test-Path $unalignedApk) { Remove-Item -Force $unalignedApk }

& "$BUILD_TOOLS\aapt.exe" package -f `
    -M "$buildDir\AndroidManifest.xml" `
    -I $ANDROID_JAR `
    -S "$APP_DIR\res" `
    -A "$APP_DIR\assets" `
    -F $unalignedApk

# 5. Add classes.dex directly to root of APK
Write-Host "4. Adding classes.dex to APK root..." -ForegroundColor Yellow
Push-Location $dexOut
& "$BUILD_TOOLS\aapt.exe" add $unalignedApk classes.dex
Pop-Location

# 6. Zipalign
Write-Host "5. Running zipalign..." -ForegroundColor Yellow
$alignedApk = "$APP_DIR\build\aligned.apk"
if (Test-Path $alignedApk) { Remove-Item -Force $alignedApk }
& "$BUILD_TOOLS\zipalign.exe" -f 4 $unalignedApk $alignedApk

# 7. Sign APK with apksigner
Write-Host "6. Signing APK with apksigner..." -ForegroundColor Yellow
$finalApk = "D:\apps\emas-ota\NoorEnergy_clean.apk"
if (Test-Path $finalApk) { Remove-Item -Force $finalApk }
Copy-Item $alignedApk $finalApk -Force

& "$BUILD_TOOLS\apksigner.bat" sign `
    --ks $KEYSTORE `
    --ks-pass pass:android `
    --key-pass pass:android `
    --ks-key-alias d `
    $finalApk

# 8. Verify APK
Write-Host "7. Verifying APK..." -ForegroundColor Yellow
& "$BUILD_TOOLS\apksigner.bat" verify --verbose $finalApk

$size = (Get-Item $finalApk).Length
Write-Host "`nSUCCESS! Built $finalApk ($([math]::Round($size/1024)) KB)" -ForegroundColor Green
