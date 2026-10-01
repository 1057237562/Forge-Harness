param([string]$Sdk = $env:ANDROID_HOME)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$cache = Join-Path $repository '.cache\native-tools'
New-Item -ItemType Directory -Force $cache | Out-Null
function Get-PinnedFile([string]$Name, [string]$Url, [string]$Sha256) {
    $target = Join-Path $cache $Name
    if (!(Test-Path -LiteralPath $target)) {
        Invoke-WebRequest $Url -OutFile "$target.partial"
        if ((Get-FileHash "$target.partial" -Algorithm SHA256).Hash -ne $Sha256) { throw "Checksum mismatch: $Name" }
        Move-Item -LiteralPath "$target.partial" -Destination $target
    }
    if ((Get-FileHash $target -Algorithm SHA256).Hash -ne $Sha256) { throw "Checksum mismatch: $Name" }
}
Get-PinnedFile 'ecj-3.18.0.jar' 'https://repo.maven.apache.org/maven2/org/eclipse/jdt/ecj/3.18.0/ecj-3.18.0.jar' '69dad18a1fcacd342a7d44c5abf74f50e7529975553a24c64bce0b29b86af497'
if (!$Sdk) {
    $properties = Join-Path $repository 'mobile/local.properties'
    if (Test-Path -LiteralPath $properties) {
        $line = Get-Content $properties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($line) { $Sdk = $line.Substring(8).Replace('\:',':').Replace('\\','\') }
    }
}
if (!$Sdk) { throw 'Pass -Sdk or configure ANDROID_HOME/mobile/local.properties to build the native tools' }
& (Join-Path $repository 'mobile/native-tools-build/build-tools.ps1') -Sdk $Sdk
