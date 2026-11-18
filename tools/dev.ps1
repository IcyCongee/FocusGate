[CmdletBinding(PositionalBinding = $false)]
param(
    [Parameter(Position = 0)]
    [ValidateSet('doctor', 'build', 'test', 'adb')]
    [string]$Action = 'doctor',
    [string]$JdkHome,
    [string]$SdkRoot,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ToolArgs
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot

if (-not $JdkHome) {
    $candidates = @($env:JAVA_HOME)
    $javaDir = Join-Path $env:ProgramFiles 'Java'
    if (Test-Path -LiteralPath $javaDir) {
        $candidates += @(Get-ChildItem -LiteralPath $javaDir -Directory -Filter 'jdk-17*' | ForEach-Object { $_.FullName })
    }
    $JdkHome = $candidates | Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'bin\javac.exe')) } | Select-Object -First 1
}
if (-not $JdkHome -or -not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin\java.exe'))) {
    throw 'JDK not found. Pass -JdkHome with a JDK 17 directory. Global JAVA_HOME is not modified.'
}
if (-not $SdkRoot) {
    $sdkCandidates = @((Join-Path $projectRoot '.tools\sdk'), $env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
    $SdkRoot = $sdkCandidates | Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'platform-tools\adb.exe')) } | Select-Object -First 1
}
if (-not $SdkRoot) { throw 'Android SDK not found. Pass -SdkRoot or install into .tools\sdk.' }
$SdkRoot = (Resolve-Path -LiteralPath $SdkRoot).Path
# These variables affect this PowerShell process and its child tools only.
$env:JAVA_HOME = $JdkHome
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.tools\android-user'
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.tools\gradle-home'
$env:PATH = "$JdkHome\bin;$SdkRoot\platform-tools;$env:PATH"

if ($Action -eq 'doctor') {
    & (Join-Path $JdkHome 'bin\java.exe') -version
    Write-Output "JAVA_HOME (local): $JdkHome"
    Write-Output "SDK (local): $SdkRoot"
    foreach ($part in @('platforms\android-35\android.jar', 'build-tools\35.0.0\aapt2.exe', 'platform-tools\adb.exe')) {
        Write-Output "$part : $(Test-Path -LiteralPath (Join-Path $SdkRoot $part))"
    }
    & (Join-Path $SdkRoot 'platform-tools\adb.exe') version
    & (Join-Path $SdkRoot 'platform-tools\adb.exe') devices -l
    exit $LASTEXITCODE
}
if ($Action -eq 'adb') {
    if (-not $ToolArgs) { $ToolArgs = @('devices', '-l') }
    & (Join-Path $SdkRoot 'platform-tools\adb.exe') @ToolArgs
    exit $LASTEXITCODE
}
# Java .properties must also work with a Windows workspace containing Chinese characters.
$sdkValue = $SdkRoot.Replace('\', '/')
$escaped = -join ($sdkValue.ToCharArray() | ForEach-Object {
    if ([int]$_ -gt 127) { '\u{0:x4}' -f [int]$_ } else { [string]$_ }
})
[System.IO.File]::WriteAllText((Join-Path $projectRoot 'local.properties'), "sdk.dir=$escaped`n", [System.Text.Encoding]::ASCII)
[string[]]$gradleTasks = @(if ($Action -eq 'build') { ':app:assembleDebug' } else { ':app:testDebugUnitTest'; ':app:lintDebug' })
& (Join-Path $projectRoot 'gradlew.bat') @gradleTasks --console=plain @ToolArgs
exit $LASTEXITCODE
