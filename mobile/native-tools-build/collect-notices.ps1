param([Parameter(Mandatory)][string]$Source, [Parameter(Mandatory)][string]$Output)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$records = [Collections.Generic.List[object]]::new()
$text = [Collections.Generic.List[string]]::new()
$text.Add('Forge native tool source notice inventory. Includes repository notices beyond the linked source subset. This inventory does not replace review of source-file headers and distribution obligations.')
$repositories = @([pscustomobject]@{name='recipes';url='https://github.com/lzhiyong/android-sdk-tools';commit='50713285d4de73dd36735928217523817ad16988'})
$repositories += @(Get-Content (Join-Path $PSScriptRoot 'sources.lock.json') -Raw | ConvertFrom-Json)
$withoutNotice = [Collections.Generic.List[string]]::new()
foreach ($repository in $repositories) {
    $root = if ($repository.name -eq 'recipes') { $Source } else { Join-Path $Source "src/$($repository.name)" }
    $tracked = & git -C $root ls-files
    if ($LASTEXITCODE -ne 0) { throw "Cannot enumerate notices: $root" }
    $paths = @($tracked | Where-Object { ($_ -split '/')[-1] -match '^(LICENSE|NOTICE|COPYING|COPYRIGHT)([._-].*)?$' } | Sort-Object)
    if ($paths.Count -eq 0) { $withoutNotice.Add($repository.name) }
    foreach ($path in $paths) {
        $file = Join-Path $root $path
        if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing tracked notice: $file" }
        $digest = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
        $records.Add([ordered]@{repository=$repository.name;url=$repository.url;commit=$repository.commit;path=$path;sha256=$digest})
        $text.Add("Source: $($repository.url) @ $($repository.commit) / $path`nSHA-256: $digest")
        $text.Add((Get-Content -LiteralPath $file -Raw))
    }
}
New-Item -ItemType Directory -Force $Output | Out-Null
[IO.File]::WriteAllText((Join-Path $Output 'NOTICE.txt'), ($text -join "`n`n"))
[ordered]@{schemaVersion=1;scope='tracked standalone notices, including nested directories';sourceHeaderReviewComplete=$false;repositoriesWithoutStandaloneNotice=@($withoutNotice);files=@($records)} |
    ConvertTo-Json -Depth 8 | Set-Content (Join-Path $Output 'notice-inventory.json') -Encoding utf8
Write-Output "Collected $($records.Count) notices; source-header review still required."
