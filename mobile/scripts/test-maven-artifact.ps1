param([string]$Adb = 'adb', [string]$Java = 'E:\OpenJDK\bin\java.exe', [string]$Sdk = 'E:\Android_SDK', [ValidateSet('maven','module','aar','androidx','kotlin','desugar')][string]$Fixture = 'maven')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$out = Join-Path $repository "mobile\app\build\$Fixture-device-result"
New-Item -ItemType Directory -Force $out | Out-Null
$text = & $Adb shell run-as dev.forge.mobile cat "files/$Fixture-integration-result.json"
if ($LASTEXITCODE -ne 0) { throw 'Run NativeBuildIntegrationTest first' }
$result = ($text -join "`n") | ConvertFrom-Json
if ($result.state -ne 'SUCCEEDED' -or !$result.offline -or $result.cacheHit) { throw 'Expected a real offline Maven compilation' }
if ($result.artifact -notmatch '^/data/(user/0|data)/dev\.forge\.mobile/files/native-builds/[a-f0-9-]{36}/output/app\.apk$') { throw 'Unexpected artifact path' }
$info = [Diagnostics.ProcessStartInfo]::new()
$info.FileName = $Adb
foreach ($arg in @('exec-out','run-as','dev.forge.mobile','cat',$result.artifact)) { $info.ArgumentList.Add($arg) }
$info.UseShellExecute = $false; $info.RedirectStandardOutput = $true
$process = [Diagnostics.Process]::Start($info)
$apk = Join-Path $out "$Fixture-offline-sample.apk"
$stream = [IO.File]::Create($apk)
try { $process.StandardOutput.BaseStream.CopyTo($stream) } finally { $stream.Dispose() }
$process.WaitForExit()
if ($process.ExitCode -ne 0) { throw 'APK export failed' }
$process.Dispose()
if ((Get-FileHash $apk -Algorithm SHA256).Hash.ToLowerInvariant() -ne $result.sha256) { throw 'APK checksum mismatch' }
& $Java -jar (Join-Path $Sdk 'build-tools\30.0.3\lib\apksigner.jar') verify --verbose $apk
if ($LASTEXITCODE -ne 0) { throw 'Independent APK signature check failed' }
& $Adb install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'Sample install failed' }
& $Adb shell am force-stop dev.forge.integration
& $Adb shell run-as dev.forge.integration rm -f "files/$Fixture-result.txt"
& $Adb shell am start -W -n dev.forge.integration/.MainActivity
if ($LASTEXITCODE -ne 0) { throw 'Sample launch failed' }
$actual = (& $Adb shell run-as dev.forge.integration cat "files/$Fixture-result.txt") -join "`n"
$expected = switch ($Fixture) { 'module' { 'module-on-android' } 'aar' { 'aar-on-android|asset-from-aar|dev.forge.integration.merged' } 'androidx' { 'androidx-on-android' } 'kotlin' { 'kotlin-apk:43' } 'desugar' { 'desugar:42:41' } default { '"maven-on-android"' } }
if ($LASTEXITCODE -ne 0 -or $actual.Trim() -ne $expected) { throw "Library did not execute in the installed app: $actual" }
$result | Add-Member -NotePropertyName runtimeResult -NotePropertyValue $actual.Trim()
$result | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $out 'verified.json') -Encoding utf8
Write-Output "PASS: offline-compiled APK installed and executed $Fixture fixture; $out"
