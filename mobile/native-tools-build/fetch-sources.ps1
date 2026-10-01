param([string]$Destination = (Join-Path $PSScriptRoot '..\..\research\android-sdk-tools'))
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
function Get-Checkout([string]$Url, [string]$Path, [string]$Commit, [string]$Ref) {
    if (!(Test-Path -LiteralPath (Join-Path $Path '.git'))) {
        if (Test-Path -LiteralPath $Path) { throw "Destination exists without a Git checkout: $Path" }
        & git clone --depth 1 --branch $Ref $Url $Path
        if ($LASTEXITCODE -ne 0) { throw "Clone failed: $Url" }
    }
    $actual = & git -C $Path rev-parse HEAD
    if ($LASTEXITCODE -ne 0 -or $actual -ne $Commit) { throw "Unexpected source revision: $Path (expected $Commit, got $actual)" }
}
# The build recipes are also pinned; preserve existing local work on mismatch.
if (!(Test-Path -LiteralPath (Join-Path $Destination '.git'))) {
    & git clone https://github.com/lzhiyong/android-sdk-tools.git $Destination
    if ($LASTEXITCODE -ne 0) { throw 'Recipe clone failed' }
    & git -C $Destination checkout --detach 50713285d4de73dd36735928217523817ad16988
    if ($LASTEXITCODE -ne 0) { throw 'Recipe checkout failed' }
}
Get-Checkout 'https://github.com/lzhiyong/android-sdk-tools.git' $Destination '50713285d4de73dd36735928217523817ad16988' 'master'
foreach ($source in Get-Content (Join-Path $PSScriptRoot 'sources.lock.json') -Raw | ConvertFrom-Json) {
    Get-Checkout $source.url (Join-Path $Destination "src/$($source.name)") $source.commit $source.tag
}
Write-Output 'All locked source revisions verified.'
