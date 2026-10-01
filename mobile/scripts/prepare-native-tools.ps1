param()
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
Get-PinnedFile 'build-tools-34.0.4-aarch64.tar.xz' 'https://github.com/AndroidIDEOfficial/androidide-tools/releases/download/v34.0.4/build-tools-34.0.4-aarch64.tar.xz' '4bbbdeee608ff8d3a2c1534d0a5270305a02b1f200403a536c75ac84c41e52fa'
New-Item -ItemType Directory -Force (Join-Path $cache 'arm64') | Out-Null
& tar -xf (Join-Path $cache 'build-tools-34.0.4-aarch64.tar.xz') -C (Join-Path $cache 'arm64')
if ($LASTEXITCODE -ne 0) { throw 'Native tool extraction failed' }
foreach ($name in @('aapt2','zipalign')) {
    $file = Join-Path $cache "arm64\build-tools\34.0.4\$name"
    $stream = [IO.File]::OpenRead($file)
    try { $header = [byte[]]::new(20); if ($stream.Read($header,0,20) -ne 20) { throw 'Truncated ELF' } } finally { $stream.Dispose() }
    if ($header[0] -ne 127 -or $header[1] -ne 69 -or $header[2] -ne 76 -or $header[3] -ne 70 -or $header[4] -ne 2 -or $header[5] -ne 1 -or $header[18] -ne 183 -or $header[19] -ne 0) { throw "Not ARM64 ELF: $name" }
}
Write-Output 'Pinned native tools verified. Only aapt2 and zipalign are packaged; the archive host libc++ is excluded.'
