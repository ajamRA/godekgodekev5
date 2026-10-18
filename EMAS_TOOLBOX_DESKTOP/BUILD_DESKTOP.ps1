param([string]$Output = 'EmasTool.exe')
$ErrorActionPreference = 'Stop'
$compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
$core = Join-Path $PSScriptRoot 'Microsoft.Web.WebView2.Core.dll'
$forms = Join-Path $PSScriptRoot 'Microsoft.Web.WebView2.WinForms.dll'
foreach ($file in @($compiler, $core, $forms, (Join-Path $PSScriptRoot 'WebView2Loader.dll'))) {
    if (!(Test-Path -LiteralPath $file)) { throw "Required build dependency not found: $file" }
}
# SDK 1.0.3351.48; the managed assemblies and x64 loader ship beside the executable.
& $compiler /nologo /target:winexe /platform:x64 /optimize+ "/out:$(Join-Path $PSScriptRoot $Output)" "/win32manifest:$(Join-Path $PSScriptRoot 'Launcher.manifest')" /reference:System.Windows.Forms.dll /reference:System.Drawing.dll "/reference:$core" "/reference:$forms" (Join-Path $PSScriptRoot 'Launcher.cs')
if ($LASTEXITCODE -ne 0) { throw "Desktop launcher build failed (exit $LASTEXITCODE)." }
Write-Output "Built $Output"
