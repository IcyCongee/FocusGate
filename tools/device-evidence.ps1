[CmdletBinding()]
param(
    [string]$Serial,
    [string]$TargetPackage,
    [string]$SdkRoot
)
$ErrorActionPreference = 'Stop'
# Windows PowerShell 5.1 may not initialize PSScriptRoot in parameter defaults.
# Resolve the script-relative default in the body, where it is available.
if (-not $SdkRoot) {
    $SdkRoot = Join-Path (Split-Path -Parent $PSScriptRoot) '.tools\sdk'
}
$adb = Join-Path $SdkRoot 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { throw 'ADB missing. Pass -SdkRoot or prepare .tools\sdk.' }
$devices = @(& $adb devices -l)
if ($LASTEXITCODE -ne 0) { throw 'ADB device query failed.' }
$devices | Write-Output
$authorized = @($devices | Where-Object { $_ -match '^\S+\s+device(?:\s|$)' })
if (-not $Serial) {
    if ($authorized.Count -ne 1) { throw 'Connect and authorize exactly one device, or pass -Serial. unauthorized/offline is not a verified connection.' }
    $Serial = ($authorized[0] -split '\s+')[0]
}
$prefix = @('-s', $Serial)
$state = & $adb @prefix get-state
if ($LASTEXITCODE -ne 0 -or $state -ne 'device') { throw 'Selected device is not authorized and ready.' }
foreach ($property in @('ro.product.manufacturer', 'ro.product.model', 'ro.build.version.release', 'ro.build.version.sdk', 'ro.build.display.id', 'ro.build.version.security_patch', 'ro.build.version.oplusrom', 'persist.sys.timezone')) {
    $value = & $adb @prefix shell getprop $property
    if ($LASTEXITCODE -ne 0) { throw "getprop failed: $property" }
    Write-Output "$property=$value"
}
Write-Output 'Foreground activity metadata (open actual target on the phone before this command):'
# Do not dump a UI hierarchy, accessibility nodes, page text, or screenshots.
$activity = @(& $adb @prefix shell dumpsys activity activities)
if ($LASTEXITCODE -ne 0) { throw 'Activity query failed.' }
$activity | Select-String -Pattern 'topResumedActivity|mResumedActivity'
if ($TargetPackage) {
    if ($TargetPackage -notmatch '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$') { throw 'Invalid package identifier.' }
    $package = @(& $adb @prefix shell dumpsys package $TargetPackage)
    if ($LASTEXITCODE -ne 0) { throw 'Package query failed.' }
    Write-Output "Package evidence: $TargetPackage"
    $package | Select-String -Pattern 'versionCode=|versionName=|installerPackageName=|initiatingPackageName='
}
Write-Output 'This output is evidence only; it does not grant permissions, bind a target, or prove E02 passes.'
