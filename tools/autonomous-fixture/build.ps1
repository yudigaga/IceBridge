param(
    [string] $SdkRoot = "",
    [string] $JdkRoot = "",
    [string] $OutputDirectory = ""
)

$ErrorActionPreference = 'Stop'

function Invoke-FixtureTool([string] $Executable, [string[]] $Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Fixture tool failed: $Executable ($LASTEXITCODE)" }
}

$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if (-not $SdkRoot) {
    $propertiesFile = Join-Path $repoRoot 'local.properties'
    if (Test-Path -LiteralPath $propertiesFile) {
        $line = [System.IO.File]::ReadAllLines($propertiesFile) | Where-Object { $_.StartsWith('sdk.dir=') } | Select-Object -First 1
        if ($line) { $SdkRoot = $line.Substring(8).Replace('\\', '\') }
    }
    if (-not $SdkRoot) { $SdkRoot = $env:ANDROID_SDK_ROOT }
}
if (-not $SdkRoot -or -not (Test-Path -LiteralPath $SdkRoot)) { throw 'Provide -SdkRoot or a valid local.properties sdk.dir.' }

if (-not $JdkRoot) {
    if ($env:JAVA_HOME) { $JdkRoot = $env:JAVA_HOME }
    else {
        $javacCommand = Get-Command javac -ErrorAction SilentlyContinue
        if ($javacCommand) { $JdkRoot = Split-Path (Split-Path $javacCommand.Source -Parent) -Parent }
    }
}
if (-not $JdkRoot -or -not (Test-Path -LiteralPath (Join-Path $JdkRoot 'bin/javac.exe'))) {
    throw 'Provide -JdkRoot pointing to a JDK containing javac, jar and keytool.'
}

$buildTools = Get-ChildItem -LiteralPath (Join-Path $SdkRoot 'build-tools') -Directory |
    Where-Object { $_.Name -match '^\d+(\.\d+){1,3}$' } |
    Sort-Object { [version] $_.Name } -Descending | Select-Object -First 1
$platform = Get-ChildItem -LiteralPath (Join-Path $SdkRoot 'platforms') -Directory |
    Where-Object { $_.Name -match '^android-\d+$' } |
    Sort-Object { [int] $_.Name.Substring(8) } -Descending | Select-Object -First 1
if (-not $buildTools -or -not $platform) { throw 'Android SDK platform and build-tools are required.' }
$androidJar = Join-Path $platform.FullName 'android.jar'

if (-not $OutputDirectory) { $OutputDirectory = Join-Path $PSScriptRoot 'build' }
$null = New-Item -ItemType Directory -Path $OutputDirectory -Force
$output = (Resolve-Path -LiteralPath $OutputDirectory).Path
$classes = Join-Path $output 'classes'
$dex = Join-Path $output 'dex'
$null = New-Item -ItemType Directory -Path $classes -Force
$null = New-Item -ItemType Directory -Path $dex -Force
$resourceApk = Join-Path $output 'resources.apk'
$classesJar = Join-Path $output 'classes.jar'
$unsignedApk = Join-Path $output 'unsigned.apk'
$alignedApk = Join-Path $output 'aligned.apk'
$signedApk = Join-Path $output 'icebridge-autonomous-fixture.apk'
$debugKey = Join-Path $output 'fixture-debug.keystore'

Invoke-FixtureTool -Executable (Join-Path $buildTools.FullName 'aapt2.exe') -Arguments @(
    'link', '--manifest', (Join-Path $PSScriptRoot 'AndroidManifest.xml'), '-I', $androidJar,
    '--version-code', '1', '--version-name', '1.0', '-o', $resourceApk)
Invoke-FixtureTool -Executable (Join-Path $JdkRoot 'bin/javac.exe') -Arguments @(
    '--release', '8', '-classpath', $androidJar, '-d', $classes,
    (Join-Path $PSScriptRoot 'src/io/github/icebridge/fixture/FixtureActivity.java'))
Invoke-FixtureTool -Executable (Join-Path $JdkRoot 'bin/jar.exe') -Arguments @('cf', $classesJar, '-C', $classes, '.')
$previousJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $JdkRoot
    Invoke-FixtureTool -Executable (Join-Path $buildTools.FullName 'd8.bat') -Arguments @(
        '--min-api', '26', '--lib', $androidJar, '--output', $dex, $classesJar)
} finally { $env:JAVA_HOME = $previousJavaHome }

Copy-Item -LiteralPath $resourceApk -Destination $unsignedApk -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::Open($unsignedApk, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $null = [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,
        (Join-Path $dex 'classes.dex'), 'classes.dex', [System.IO.Compression.CompressionLevel]::Optimal)
} finally { $archive.Dispose() }
Invoke-FixtureTool -Executable (Join-Path $buildTools.FullName 'zipalign.exe') -Arguments @('-f', '4', $unsignedApk, $alignedApk)
if (-not (Test-Path -LiteralPath $debugKey)) {
    Invoke-FixtureTool -Executable (Join-Path $JdkRoot 'bin/keytool.exe') -Arguments @(
        '-genkeypair', '-keystore', $debugKey, '-storepass', 'android', '-keypass', 'android',
        '-alias', 'androiddebugkey', '-dname', 'CN=Android Debug,O=Android,C=US', '-keyalg',
        'RSA', '-keysize', '2048', '-validity', '10000')
}
$previousJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $JdkRoot
    Invoke-FixtureTool -Executable (Join-Path $buildTools.FullName 'apksigner.bat') -Arguments @(
        'sign', '--ks', $debugKey, '--ks-key-alias', 'androiddebugkey', '--ks-pass', 'pass:android',
        '--key-pass', 'pass:android', '--out', $signedApk, $alignedApk)
    Invoke-FixtureTool -Executable (Join-Path $buildTools.FullName 'apksigner.bat') -Arguments @('verify', '--verbose', $signedApk)
} finally { $env:JAVA_HOME = $previousJavaHome }

Write-Output "Fixture APK: $signedApk"
