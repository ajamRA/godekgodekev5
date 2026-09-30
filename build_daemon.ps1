$JAVAC = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot\bin\javac.exe"
$JAVA  = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot\bin\java.exe"
$JAR_CMD = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot\bin\jar.exe"
$R8    = "D:\apps\emas-ota\r8.jar"
$AJ    = "D:\apps\emas-ota\android.jar"

$SRC_DIR = "D:\apps\emas-ota\src_window"
$OUT_DIR = "D:\apps\emas-ota\src_window\out"
$DEX_DIR = "D:\apps\emas-ota\src_window\out_dex"

if (Test-Path $OUT_DIR) { Remove-Item -Recurse -Force $OUT_DIR }
if (Test-Path $DEX_DIR) { Remove-Item -Recurse -Force $DEX_DIR }
New-Item -ItemType Directory -Path $OUT_DIR | Out-Null
New-Item -ItemType Directory -Path $DEX_DIR | Out-Null

Write-Host "Compiling DoorPressureReliefDaemon.java..." -ForegroundColor Cyan
& $JAVAC -J-Xmx128m -encoding UTF-8 -source 1.8 -target 1.8 -sourcepath $SRC_DIR -cp $AJ -d $OUT_DIR "$SRC_DIR\com\emas\window\DoorPressureReliefDaemon.java"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Dexing with D8..." -ForegroundColor Cyan
$classes = Get-ChildItem -Path $OUT_DIR -Filter "*.class" -Recurse | ForEach-Object { $_.FullName }
& $JAVA -Xmx128m -cp $R8 com.android.tools.r8.D8 --min-api 28 --lib $AJ --output $DEX_DIR $classes
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Packaging jar..." -ForegroundColor Cyan
Push-Location $DEX_DIR
& $JAR_CMD cf "D:\apps\emas-ota\door_pressure_relief.jar" classes.dex
Pop-Location

Write-Host "Success! Generated D:\apps\emas-ota\door_pressure_relief.jar" -ForegroundColor Green
