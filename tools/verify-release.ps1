param(
    [Parameter(Mandatory = $true)]
    [string] $Apk,
    [string] $ExpectedVersion = "0.0.78",
    [string] $ExpectedCertificateSha256 = ""
)

$ErrorActionPreference = "Stop"

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

if ($badging -notmatch "package: name='com\.huanchengfly\.icebridge'") {
    throw "Unexpected applicationId"
}
if ($badging -notmatch ("versionName='" + [regex]::Escape($ExpectedVersion) + "'")) {
    throw "Unexpected versionName; expected $ExpectedVersion"
}
if ($badging -match "application-debuggable") {
    throw "Release APK is debuggable"
}

$certOutput = (& $apksigner verify --verbose --print-certs $apkPath | Out-String)
if ($LASTEXITCODE -ne 0) { throw "apksigner verification failed" }
if ($certOutput -notmatch "Verified using v2 scheme") {
    throw "APK is not verified with APK Signature Scheme v2"
}
if ($ExpectedCertificateSha256 -and
    $certOutput -notmatch [regex]::Escape($ExpectedCertificateSha256)) {
    throw "Certificate SHA-256 does not match expected value"
}

& $zipalign -c -v 4 $apkPath
if ($LASTEXITCODE -ne 0) { throw "zipalign verification failed" }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($apkPath)
try {
    $forbidden = $archive.Entries | Where-Object {
        $_.FullName -match "(^|/)(debug|\\.debug|\\.git)(/|$)" -or
        $_.FullName -match "(^|/)(R\\.txt|mapping\\.txt)$"
    }
    if ($forbidden) {
        throw "APK contains forbidden debug/build metadata: " +
            (($forbidden | ForEach-Object { $_.FullName }) -join ", ")
    }
} finally {
    $archive.Dispose()
}

$sha256 = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash
Write-Host "SHA-256: $sha256"
Write-Host "Release APK verification passed."
