param(
    [Parameter(Mandatory)][string]$Sdk,
    [string]$NdkVersion = '27.2.12479018',
    [string]$CMakeVersion = '3.22.1',
    [string]$BuildDirectory,
    [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$source = (Join-Path $repository 'research/android-sdk-tools').Replace('\','/')
& (Join-Path $PSScriptRoot 'fetch-sources.ps1') -Destination $source
$cache = Join-Path $repository '.cache'
$protocRoot = Join-Path $cache 'protoc-21.12'
New-Item -ItemType Directory -Force $protocRoot | Out-Null
$archive = Join-Path $protocRoot 'archive.zip'
if (!(Test-Path -LiteralPath $archive)) {
    Invoke-WebRequest 'https://github.com/protocolbuffers/protobuf/releases/download/v21.12/protoc-21.12-win64.zip' -OutFile $archive
}
if ((Get-FileHash $archive -Algorithm SHA256).Hash -ne '71852A30CF62975358EDFCBBFF93086E8857A079C8E4D6904881AA968D65C7F9') { throw 'protoc archive checksum mismatch' }
Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $protocRoot 'extracted') -Force
$protoc = Join-Path $protocRoot 'extracted/bin/protoc.exe'
$cmake = Join-Path $Sdk "cmake/$CMakeVersion/bin/cmake.exe"
$ninja = Join-Path $Sdk "cmake/$CMakeVersion/bin/ninja.exe"
$ndk = Join-Path $Sdk "ndk/$NdkVersion"
$build = if ($BuildDirectory) { [IO.Path]::GetFullPath($BuildDirectory) } else { Join-Path $cache 'sdk-tools-build' }
& $cmake -S $PSScriptRoot -B $build -G Ninja "-DCMAKE_TOOLCHAIN_FILE=$ndk/build/cmake/android.toolchain.cmake" "-DCMAKE_MAKE_PROGRAM=$ninja" '-DANDROID_ABI=arm64-v8a' '-DANDROID_PLATFORM=android-28' '-DANDROID_STL=c++_static' '-DCMAKE_BUILD_TYPE=Release' "-DFORGE_SDK_SOURCE=$source" '-DFORGE_BUILD_AAPT2=ON' "-DPROTOC_PATH=$protoc"
if ($LASTEXITCODE -ne 0) { throw 'Native tool configuration failed' }
& $cmake --build $build --target aapt2 zipalign -j 8
if ($LASTEXITCODE -ne 0) { throw 'Native tool build failed' }
$destination = if ($OutputDirectory) { [IO.Path]::GetFullPath($OutputDirectory) } else { Join-Path $cache 'native-tools/forge-source-candidate' }
# Prepare the complete bundle before touching the previously verified output.
$output = Join-Path $cache ("native-tools/staging-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $output | Out-Null
foreach ($name in @('aapt2','zipalign')) {
    Copy-Item -LiteralPath (Join-Path $build $name) -Destination (Join-Path $output $name)
    & "$ndk/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe" --strip-debug (Join-Path $output $name)
    if ($LASTEXITCODE -ne 0) { throw "Stripping failed: $name" }
}
& (Join-Path $PSScriptRoot 'collect-notices.ps1') -Source $source -Output $output
$adaptations = [ordered]@{}
foreach ($file in Get-ChildItem $PSScriptRoot -File | Sort-Object Name) {
    $adaptations[$file.Name] = (Get-FileHash $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
}
$tools = [ordered]@{}
foreach ($name in @('aapt2','zipalign')) {
    $tools[$name] = (Get-FileHash (Join-Path $output $name) -Algorithm SHA256).Hash.ToLowerInvariant()
}
$provenance = [ordered]@{
    schemaVersion = 1
    recipeUrl = 'https://github.com/lzhiyong/android-sdk-tools'
    recipeCommit = '50713285d4de73dd36735928217523817ad16988'
    sources = @(Get-Content (Join-Path $PSScriptRoot 'sources.lock.json') -Raw | ConvertFrom-Json)
    adaptations = $adaptations
    ndkVersion = $NdkVersion
    cmakeVersion = $CMakeVersion
    abi = 'arm64-v8a'
    minimumApi = 28
    protocArchiveSha256 = (Get-FileHash $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    tools = $tools
    noticeSha256 = (Get-FileHash (Join-Path $output 'NOTICE.txt') -Algorithm SHA256).Hash.ToLowerInvariant()
    noticeInventorySha256 = (Get-FileHash (Join-Path $output 'notice-inventory.json') -Algorithm SHA256).Hash.ToLowerInvariant()
}
$provenance | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $output 'provenance.json') -Encoding utf8
New-Item -ItemType Directory -Force $destination | Out-Null
# Publish provenance last. If publication is interrupted, the packaging hash
# checks reject mixed contents rather than accepting them as a valid tool set.
foreach ($name in @('aapt2','zipalign','NOTICE.txt','notice-inventory.json','provenance.json')) {
    $temporary = Join-Path $destination (".$name-" + [Guid]::NewGuid().ToString('N') + '.tmp')
    Copy-Item -LiteralPath (Join-Path $output $name) -Destination $temporary
    [IO.File]::Move($temporary, (Join-Path $destination $name), $true)
}
foreach ($name in @('aapt2','zipalign','NOTICE.txt','notice-inventory.json','provenance.json')) {
    [IO.File]::Delete((Join-Path $output $name))
}
[IO.Directory]::Delete($output, $false)
$output = $destination
Get-FileHash (Join-Path $output 'aapt2'),(Join-Path $output 'zipalign') -Algorithm SHA256
Write-Output "Tools prepared at $output. Use -PforgeNativeToolsDir=$output for the host APK build."
