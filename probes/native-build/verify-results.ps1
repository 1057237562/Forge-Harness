param([string]$Results = (Join-Path $PSScriptRoot 'build\device-results'))
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$cases = @(Get-Content (Join-Path $Results 'summary.json') -Raw | ConvertFrom-Json)
$baseline = @($cases | Where-Object scenario -eq 'baseline')
if ($baseline.Count -lt 1) { throw 'No baseline results' }
if (@($baseline.sha256 | Select-Object -Unique).Count -ne 1) { throw 'Repeated identical inputs produced different APK hashes' }
$baselineHash = $baseline[0].sha256
foreach ($required in @('java-change', 'resource-change', 'gradle-layout', 'explicit-config', 'local-jar', 'broken-java', 'broken-resource', 'cancel-at-resources')) {
    if (@($cases | Where-Object scenario -eq $required).Count -ne 1) { throw "Missing or duplicate scenario: $required" }
}
foreach ($case in $cases) {
    $log = Get-Content (Join-Path $Results "$($case.buildId).log") -Raw
    if ($case.scenario.StartsWith('broken-') -or $case.scenario -eq 'cancel-at-resources') {
        $expected = if ($case.scenario -eq 'cancel-at-resources') { 'CANCELLED' } else { 'FAILED' }
        if ($case.state -ne $expected) { throw 'Negative/cancellation case has wrong terminal state' }
        if ($case.PSObject.Properties.Name -contains 'artifact') { throw 'Failed build reported an artifact' }
        if ($log.Contains('START dex') -or $log.Contains('START sign')) { throw 'Failed compilation continued to DEX/sign' }
        if ($case.scenario -eq 'broken-java' -and !$log.Contains('missingMethodForProbe')) { throw 'Missing expected Java error' }
        if ($case.scenario -eq 'broken-resource' -and !$log.Contains('xml parser error')) { throw 'Missing expected XML error' }
    } else {
        if ($case.state -ne 'SUCCEEDED' -or !$case.signatureVerified -or !$case.activityVerified) { throw 'Success lacks signature or Activity validation' }
        $apk = Join-Path $Results "$($case.buildId).apk"
        if ((Get-FileHash $apk -Algorithm SHA256).Hash.ToLowerInvariant() -ne $case.sha256) { throw 'Artifact changed after test' }
        if ($case.scenario -in @('java-change','resource-change') -and $case.sha256 -eq $baselineHash) { throw 'Change scenario reused the baseline APK' }
        if (!$log.Contains('END verify')) { throw 'Missing final verification stage' }
    }
}
$times = @($baseline.durationMs | Sort-Object)
$middle = [int][Math]::Floor($times.Count / 2)
$median = if ($times.Count % 2 -eq 1) { $times[$middle] } else { ($times[$middle - 1] + $times[$middle]) / 2 }
$cached = @($baseline | Where-Object cacheHit)
[ordered]@{
    passed=$true; cases=$cases.Count; baselineRuns=$baseline.Count;
    baselineMinMs=$times[0]; baselineMedianMs=$median; baselineMaxMs=$times[-1];
    repeatableArtifact=$true; changedArtifacts=$true; failureStopsPipeline=$true;
    activityVerified=@($cases | Where-Object activityVerified).Count;
    visibleUiVerified=@($cases | Where-Object uiVerified).Count;
    cacheHits=$cached.Count;
    cacheHitMinMs=if($cached.Count) { ($cached.durationMs | Measure-Object -Minimum).Minimum } else { $null };
    cacheHitMaxMs=if($cached.Count) { ($cached.durationMs | Measure-Object -Maximum).Maximum } else { $null }
} | ConvertTo-Json | Set-Content (Join-Path $Results 'verification.json') -Encoding utf8
Get-Content (Join-Path $Results 'verification.json')
