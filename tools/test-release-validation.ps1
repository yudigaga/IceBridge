$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'release-validation.ps1')

function Assert-That([bool] $Condition, [string] $Message) {
    if (-not $Condition) { throw $Message }
}

Assert-That (Test-ApkV2Signature 'Verified using v2 scheme (APK Signature Scheme v2): true') 'Valid v2 result rejected'
Assert-That (-not (Test-ApkV2Signature 'Verified using v2 scheme (APK Signature Scheme v2): false')) 'False v2 result accepted'
Assert-That (-not (Test-ApkV2Signature 'Verified using v2 scheme')) 'Incomplete v2 result accepted'
foreach ($entry in @('R.txt', 'mapping.txt', 'nested/mapping.txt', '.git/config', '.debug/data', 'debug/info')) {
    Assert-That (Test-ForbiddenApkEntry $entry) "Forbidden entry missed: $entry"
}
foreach ($entry in @('classes.dex', 'META-INF/xposed/module.prop', 'res/drawable/icon.xml', 'assets/mappingXtxt')) {
    Assert-That (-not (Test-ForbiddenApkEntry $entry)) "Valid entry rejected: $entry"
}
$digest = 'a' * 64
$cert = "Signer #1 certificate SHA-256 digest: $digest"
Assert-That (Test-ApkCertificate $cert $digest) 'Matching certificate rejected'
Assert-That (-not (Test-ApkCertificate $cert ('b' * 64))) 'Different certificate accepted'
Assert-That (-not (Test-ApkCertificate "Unrelated: $digest" $digest)) 'Unrelated digest accepted'
Assert-That (-not (Test-ApkCertificate "$cert`nSigner #2 certificate SHA-256 digest: $digest" $digest)) 'Multiple signers accepted'
$version = Read-ReleaseVersion (Join-Path $PSScriptRoot '../version.properties')
Assert-That ($version.versionCode -gt 0) 'Version source not loaded'
Write-Host 'Release validation regression checks passed.'
