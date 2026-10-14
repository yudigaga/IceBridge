param(
    [Parameter(Mandatory = $true)]
    [string] $Apk,
    [string] $ExpectedVersion = "",
    [string] $ExpectedCertificateSha256 = "",
    [switch] $AllowUnpinnedCertificate
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot 'release-validation.ps1')
$releaseVersion = Read-ReleaseVersion (Join-Path $PSScriptRoot '../version.properties')
if (-not $ExpectedVersion) { $ExpectedVersion = $releaseVersion.versionName }
if (-not $ExpectedCertificateSha256 -and -not $AllowUnpinnedCertificate) {
    throw 'Provide ExpectedCertificateSha256 for release identity; AllowUnpinnedCertificate is for CI test keys only.'
}

function Require-Tool([string] $Name) {
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        throw "Required Android build tool not found: $Name"
    }
    return $command.Source
}

$apkPath = (Resolve-Path -LiteralPath $Apk -ErrorAction Stop).Path
$aapt2 = Require-Tool "aapt2"
$apksigner = Require-Tool "apksigner"
$zipalign = Require-Tool "zipalign"

Write-Host "APK: $apkPath"
$badging = (& $aapt2 dump badging $apkPath | Out-String)
if ($LASTEXITCODE -ne 0) { throw "aapt2 dump badging failed" }

if ($badging -notmatch ("package: name='" + [regex]::Escape($releaseVersion.applicationId) + "'")) {
    throw "Unexpected applicationId"
}
if ($badging -notmatch ("versionName='" + [regex]::Escape($ExpectedVersion) + "'")) {
    throw "Unexpected versionName; expected $ExpectedVersion"
}
if ($badging -notmatch ("versionCode='" + [regex]::Escape($releaseVersion.versionCode) + "'")) {
    throw 'Unexpected versionCode'
}
if ($badging -match "application-debuggable") {
    throw "Release APK is debuggable"
}

$certOutput = (& $apksigner verify --verbose --print-certs $apkPath | Out-String)
if ($LASTEXITCODE -ne 0) { throw "apksigner verification failed" }
if (-not (Test-ApkV2Signature $certOutput)) {
    throw "APK is not verified with APK Signature Scheme v2"
}
if ($ExpectedCertificateSha256 -and -not (Test-ApkCertificate $certOutput $ExpectedCertificateSha256)) {
    throw "Certificate SHA-256 does not match expected value"
}

& $zipalign -c -v 4 $apkPath
if ($LASTEXITCODE -ne 0) { throw "zipalign verification failed" }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($apkPath)
try {
    $forbidden = $archive.Entries | Where-Object {
        Test-ForbiddenApkEntry $_.FullName
    }
    if ($forbidden) {
        throw "APK contains forbidden debug/build metadata: " +
            (($forbidden | ForEach-Object { $_.FullName }) -join ", ")
    }
    $entry = $archive.GetEntry('META-INF/xposed/module.prop')
    if ($null -eq $entry) { throw 'LSPosed module metadata missing' }
    $reader = [System.IO.StreamReader]::new($entry.Open())
    try { $moduleMetadata = $reader.ReadToEnd() } finally { $reader.Dispose() }
    foreach ($pair in @(@('id', $releaseVersion.applicationId), @('version', $ExpectedVersion), @('versionCode', $releaseVersion.versionCode))) {
        if ($moduleMetadata -notmatch ('(?m)^' + $pair[0] + '=' + [regex]::Escape($pair[1]) + '\r?$')) {
            throw "LSPosed metadata mismatch: $($pair[0])"
        }
    }
} finally {
    $archive.Dispose()
}

$sha256 = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash
Write-Host "SHA-256: $sha256"
Write-Host "Release APK verification passed."
