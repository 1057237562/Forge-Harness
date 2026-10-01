param(
    [string]$Adb = 'adb',
    [string]$Serial = '',
    [int]$BaselineRuns = 5,
    [switch]$BuildOnly
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($BaselineRuns -lt 1 -or $BaselineRuns -gt 20) { throw 'BaselineRuns must be between 1 and 20' }
$package = 'dev.forge.nativeprobe'
$out = Join-Path $PSScriptRoot 'build\device-results'
New-Item -ItemType Directory -Force $out | Out-Null
$adbPrefix = @()
if ($Serial) { $adbPrefix = @('-s', $Serial) }
function Invoke-Adb([string[]]$Arguments) {
    $result = & $Adb @adbPrefix @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($Arguments -join ' ')" }
    return $result
}
function Export-DeviceFile([string]$DevicePath, [string]$LocalPath) {
    # Copy bytes without a PowerShell text pipeline, including on Windows PowerShell.
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $Adb
    foreach ($arg in ($adbPrefix + @('exec-out', 'run-as', $package, 'cat', $DevicePath))) { $info.ArgumentList.Add($arg) }
    $info.UseShellExecute = $false
    $info.RedirectStandardOutput = $true
    $process = [Diagnostics.Process]::Start($info)
    $file = [IO.File]::Create($LocalPath)
    try { $process.StandardOutput.BaseStream.CopyTo($file) } finally { $file.Dispose() }
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw "Cannot export $DevicePath" }
    $process.Dispose()
}
Invoke-Adb @('get-state') | Out-Null
Invoke-Adb @('install', '-r', (Join-Path $PSScriptRoot 'build\forge-native-probe.apk')) | Write-Output
$results = @()
$scenarios = @((1..$BaselineRuns | ForEach-Object { 'baseline' })) + @('java-change', 'resource-change', 'gradle-layout', 'explicit-config', 'local-jar', 'broken-java', 'broken-resource', 'cancel-at-resources')
foreach ($scenario in $scenarios) {
    Invoke-Adb @('shell', 'am', 'force-stop', $package) | Out-Null
    Invoke-Adb @('shell', 'run-as', $package, 'rm', '-f', 'files/result.json') | Out-Null
    Invoke-Adb @('shell', 'am', 'start', '-W', '-n', "$package/.ProbeActivity", '--ez', 'run', 'true', '--es', 'scenario', $scenario) | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    $result = $null
    do {
        Start-Sleep -Milliseconds 300
        $json = & $Adb @adbPrefix shell run-as $package cat files/result.json 2>$null
        if ($LASTEXITCODE -eq 0) { $result = ($json -join "`n") | ConvertFrom-Json }
        if ($null -ne $result -and $result.state -ne 'RUNNING') { break }
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($null -eq $result -or $result.state -eq 'RUNNING') { throw "Timed out: $scenario" }
    $expected = if ($scenario.StartsWith('broken-')) { 'FAILED' } elseif ($scenario -eq 'cancel-at-resources') { 'CANCELLED' } else { 'SUCCEEDED' }
    $result | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $out "$($result.buildId).json") -Encoding utf8
    Export-DeviceFile "files/runs/$($result.buildId)/build.log" (Join-Path $out "$($result.buildId).log")
    if ($result.state -ne $expected) { throw "Unexpected result for ${scenario}: $($result | ConvertTo-Json -Depth 8)" }
    if ($result.termux -or $result.proot -or $result.gradle) { throw 'Incorrect build backend' }
    $log = Get-Content -Raw (Join-Path $out "$($result.buildId).log")
    if ($scenario -eq 'broken-java' -and $log -notmatch 'missingMethodForProbe') { throw 'Missing Java diagnostic' }
    if ($scenario -eq 'broken-resource' -and $log -notmatch 'error:') { throw 'Missing resource diagnostic' }
    if ($expected -eq 'SUCCEEDED') {
        $apk = Join-Path $out "$($result.buildId).apk"
        Export-DeviceFile $result.artifact $apk
        if ((Get-FileHash $apk -Algorithm SHA256).Hash.ToLowerInvariant() -ne $result.sha256) { throw 'Exported artifact SHA-256 mismatch' }
        # Installing each successful result catches malformed manifests, resources, DEX and signatures.
        Invoke-Adb @('install', '-r', $apk) | Out-Null
        Invoke-Adb @('shell', 'am', 'force-stop', 'dev.forge.sample') | Out-Null
        Invoke-Adb @('shell', 'run-as', 'dev.forge.sample', 'rm', '-f', 'files/activity-result.txt') | Out-Null
        $launch = Invoke-Adb @('shell', 'am', 'start', '-W', '-n', 'dev.forge.sample/.MainActivity')
        if (($launch -join "`n") -notmatch 'Status: ok') { throw "Sample failed to start: $launch" }
        $activity = (Invoke-Adb @('shell', 'run-as', 'dev.forge.sample', 'cat', 'files/activity-result.txt')) -join "`n"
        $marker = if ($scenario -eq 'java-change') { 'native-build-java-change-ok' } else { 'native-build-ok' }
        if (!$activity.Contains($marker)) { throw "Inflated Activity marker missing for $scenario" }
        if ($scenario -eq 'resource-change' -and !$activity.Contains('Resource change verified on Android')) { throw 'Activity used stale resources' }
        if ($scenario -eq 'local-jar' -and !$activity.Contains('Local JAR compiled on Android')) { throw 'Activity did not execute the external JAR' }
        $activity | Set-Content (Join-Path $out "$($result.buildId).activity.txt") -Encoding utf8
        if (!$BuildOnly) {
        Invoke-Adb @('shell', 'uiautomator', 'dump', '/data/local/tmp/forge-sample-ui.xml') | Out-Null
        $ui = (Invoke-Adb @('shell', 'cat', '/data/local/tmp/forge-sample-ui.xml')) -join "`n"
        $marker = if ($scenario -eq 'java-change') { 'native-build-java-change-ok' } else { 'native-build-ok' }
        if (!$ui.Contains($marker)) { throw "Sample UI marker missing for $scenario" }
        if ($scenario -eq 'resource-change' -and !$ui.Contains('Resource change verified on Android')) { throw 'Stale resource packaged' }
        $ui | Set-Content (Join-Path $out "$($result.buildId).ui.xml") -Encoding utf8
        }
    }
    $result | Add-Member -NotePropertyName uiVerified -NotePropertyValue (!$BuildOnly -and $expected -eq 'SUCCEEDED')
    $result | Add-Member -NotePropertyName activityVerified -NotePropertyValue ($expected -eq 'SUCCEEDED')
    $results += $result
    $results | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $out 'summary.json') -Encoding utf8
    Write-Output "$scenario $($result.state) $($result.durationMs)ms buildId=$($result.buildId)"
}
Write-Output "PASS: $($results.Count) device build cases and successful artifact hashes verified. UI validation skipped: $BuildOnly. Results: $out"
& (Join-Path $PSScriptRoot 'verify-results.ps1') -Results $out
