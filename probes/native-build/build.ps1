param([string]$Jdk = $env:JAVA_HOME, [string]$NdkVersion = '27.2.12479018')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if ([string]::IsNullOrWhiteSpace($Jdk)) {
    $bundled = Join-Path $repository '.cache\jdk17'
    if (Test-Path -LiteralPath $bundled) { $Jdk = (Get-ChildItem -LiteralPath $bundled -Directory | Select-Object -First 1).FullName }
}
if ([string]::IsNullOrWhiteSpace($Jdk) -or !(Test-Path -LiteralPath (Join-Path $Jdk 'bin\java.exe'))) { throw 'Set JAVA_HOME or pass -Jdk with a JDK 17 installation.' }
$env:JAVA_HOME = $Jdk
& (Join-Path $repository 'mobile\scripts\prepare-native-tools.ps1')
if (!$?) { throw 'Native tool preparation failed' }
Push-Location (Join-Path $repository 'mobile')
try {
    & ./gradlew.bat :native-probe:assembleDebug "-PmhNdkVersion=$NdkVersion" --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Probe build failed' }
} finally { Pop-Location }
$out = Join-Path $PSScriptRoot 'build'
New-Item -ItemType Directory -Force $out | Out-Null
$apk = Join-Path $out 'forge-native-probe.apk'
Copy-Item -LiteralPath (Join-Path $repository 'mobile\native-probe\build\outputs\apk\debug\native-probe-debug.apk') -Destination $apk -Force
$profile = Get-Content (Join-Path $repository 'mobile\app\build\generated\forge-native\assets\forge-native\profile.json') -Raw | ConvertFrom-Json
[ordered]@{ kind='development-probe'; buildSystem='desktop-gradle'; deviceCompiler='android-native'; compilerIdentity=$profile.compilerIdentity; apkSha256=(Get-FileHash $apk -Algorithm SHA256).Hash.ToLowerInvariant(); nativeFiles=$profile.nativeFiles } | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $out 'toolchain-manifest.json') -Encoding utf8
Write-Output "Built: $apk"