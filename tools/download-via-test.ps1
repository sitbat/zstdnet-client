$ErrorActionPreference = 'Stop'
$runtime = Join-Path $PSScriptRoot '../runtime'
[System.IO.Directory]::CreateDirectory($runtime) | Out-Null
$archivePath = Join-Path $runtime 'ViaFabricPlus-5.1.1.jar'
$expected = '790f733029b6ce5a0ea01aa5b50234d3fcfe7438da4c9f7ec9e5b271a43b79d8'
if (-not (Test-Path -LiteralPath $archivePath)) {
    Invoke-WebRequest 'https://github.com/ViaVersion/ViaFabricPlus/releases/download/5.1.1/ViaFabricPlus-5.1.1.jar' -OutFile $archivePath
}
if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
    throw 'ViaFabricPlus 5.1.1 SHA256 mismatch'
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($archivePath)
try {
    $entry = $archive.GetEntry('META-INF/jars/viaversion-common-5.12.1-SNAPSHOT.jar')
    if ($null -eq $entry) { throw 'Pinned ViaVersion test fixture is missing' }
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $runtime 'viaversion-common-vfp-5.1.1.jar'), $true)
} finally {
    $archive.Dispose()
}
Write-Output 'ViaFabricPlus 5.1.1 test fixture ready (test-only; not redistributed).'
