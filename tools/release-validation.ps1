# Pure predicates shared by the real verifier and its regression tests.
function Test-ApkV2Signature([string] $Output) {
    return $Output -match '(?im)^Verified using v2 scheme \(APK Signature Scheme v2\):\s*true\s*$'
}

function Test-ForbiddenApkEntry([string] $Name) {
    return $Name -match '(^|/)(debug|\.debug|\.git)(/|$)' -or
        $Name -match '(^|/)(R\.txt|mapping\.txt)$'
}

function Test-ApkCertificate([string] $Output, [string] $Expected) {
    $expectedNormalized = $Expected.Replace(':', '').Trim().ToLowerInvariant()
    if ($expectedNormalized -notmatch '^[0-9a-f]{64}$') { return $false }
    $certificates = [regex]::Matches($Output, '(?im)^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-f:]+)\s*$')
    return $certificates.Count -eq 1 -and
        $certificates[0].Groups[1].Value.Replace(':', '').ToLowerInvariant() -eq $expectedNormalized
}

function Read-ReleaseVersion([string] $Path) {
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^([^#=]+)=(.*)$') { $values[$Matches[1].Trim()] = $Matches[2].Trim() }
    }
    if ($values.versionName -notmatch '^\d+\.\d+\.\d+$' -or
        $values.versionCode -notmatch '^[1-9]\d*$' -or
        $values.applicationId -notmatch '^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$') {
        throw 'Invalid release version.properties'
    }
    return $values
}
