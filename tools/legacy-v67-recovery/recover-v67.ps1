[CmdletBinding()]
param([switch]$KeepChromeScope)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$legacyClasses = Join-Path $projectRoot 'app\build\intermediates\runtime_app_classes_jar\release\bundleReleaseClassesToRuntimeJar\classes.jar'
$legacyResources = Join-Path $projectRoot 'app\build\intermediates\linked_resources_binary_format\release\processReleaseResources\linked-resources-binary-format-release.ap_'
$legacyJavaRes = Join-Path $projectRoot 'app\build\intermediates\java_res\release\processReleaseJavaRes\out\META-INF\xposed'
$runtimeList = Join-Path $PSScriptRoot 'release-runtime-files.txt'
$jdk = Join-Path $projectRoot 'jdk17\jdk-17.0.20.1+1'
$sdk = 'C:\Users\95300\AppData\Local\Android\Sdk'
$buildTools = Join-Path $sdk 'build-tools\36.0.0'
$platformJar = Join-Path $sdk 'platforms\android-34\android.jar'
$d8 = Join-Path $buildTools 'd8.bat'
$aapt2 = Join-Path $buildTools 'aapt2.exe'
$zipalign = Join-Path $buildTools 'zipalign.exe'
$apksigner = Join-Path $buildTools 'apksigner.bat'
$jarTool = Join-Path $jdk 'bin\jar.exe'
$workDir = Join-Path $PSScriptRoot 'work'
$outputDir = Join-Path $projectRoot 'app\build\outputs\apk\recovery'
$outputApk = Join-Path $outputDir 'IceBridge-v0.0.67-recovered.apk'
$alignedApk = Join-Path $workDir 'IceBridge-v0.0.67-aligned.apk'
$signedApk = Join-Path $workDir 'IceBridge-v0.0.67-signed.apk'

$required = @($legacyClasses,$legacyResources,$legacyJavaRes,$runtimeList,$jdk,$d8,$aapt2,$zipalign,$apksigner,$jarTool,$platformJar)
foreach($item in $required){ if(-not(Test-Path -LiteralPath $item)){throw "Required recovery input is missing: $item"} }

# Restore package contents from the preserved v0.0.67 AGP artifacts; do not compile current source.
if(Test-Path -LiteralPath $workDir){ Remove-Item -LiteralPath $workDir -Recurse -Force }
New-Item -ItemType Directory -Path $workDir,$outputDir -Force | Out-Null
$dexInputDir=Join-Path $workDir 'dex-input'; $aarDir=Join-Path $workDir 'aar'; $dexDir=Join-Path $workDir 'dex'; $moduleDir=Join-Path $workDir 'module'
New-Item -ItemType Directory -Path $dexInputDir,$aarDir,$dexDir,(Join-Path $moduleDir 'META-INF\xposed') -Force | Out-Null
$oldAppJar=Join-Path $dexInputDir 'icebridge-v0.0.67-classes.jar'; Copy-Item -LiteralPath $legacyClasses -Destination $oldAppJar
$inputs=[System.Collections.Generic.List[string]]::new(); $inputs.Add($oldAppJar)
$dependencyFiles=@(Get-Content -LiteralPath $runtimeList | ForEach-Object {$_.Trim()} | Where-Object {$_ -match '^[A-Za-z]:\\.+\.(jar|aar)$' -and (Test-Path -LiteralPath $_)} | Sort-Object -Unique)
if($dependencyFiles.Count -eq 0){throw 'No usable release runtime dependencies were found.'}
$index=0
foreach($dependency in $dependencyFiles){
  $index++
  if([IO.Path]::GetExtension($dependency).Equals('.jar',[StringComparison]::OrdinalIgnoreCase)){ $inputs.Add($dependency); continue }
  $unpackDir=Join-Path $aarDir ('{0:D4}' -f $index); Expand-Archive -LiteralPath $dependency -DestinationPath $unpackDir -Force
  $codeJar=Join-Path $unpackDir 'classes.jar'; if(Test-Path -LiteralPath $codeJar){$inputs.Add($codeJar)}
  $embeddedLibs=Join-Path $unpackDir 'libs'; if(Test-Path -LiteralPath $embeddedLibs){Get-ChildItem -LiteralPath $embeddedLibs -Filter '*.jar' -File -Recurse | ForEach-Object {$inputs.Add($_.FullName)}}
}
$inputs=@($inputs | Sort-Object -Unique)
Write-Host "D8 inputs: $($inputs.Count); runtime dependency files: $($dependencyFiles.Count)"
$env:JAVA_HOME=$jdk; $env:PATH="$(Join-Path $jdk 'bin');$env:PATH"
& $d8 '--min-api' '26' '--lib' $platformJar '--output' $dexDir @inputs
if($LASTEXITCODE -ne 0){throw "D8 failed with exit code $LASTEXITCODE"}
$dexFiles=@(Get-ChildItem -LiteralPath $dexDir -Filter 'classes*.dex' -File | Sort-Object Name)
if($dexFiles.Count -eq 0){throw 'D8 did not produce classes.dex.'}; Write-Host "D8 produced $($dexFiles.Count) dex file(s)."

$baseApk=Join-Path $workDir 'base.apk'; Copy-Item -LiteralPath $legacyResources -Destination $baseApk
Copy-Item -LiteralPath (Join-Path $legacyJavaRes 'java_init.list') -Destination (Join-Path $moduleDir 'META-INF\xposed\java_init.list')
@('id=com.huanchengfly.icebridge','name=Ice Bridge','version=0.0.67','versionCode=67','minApiVersion=102','targetApiVersion=102') | Set-Content -LiteralPath (Join-Path $moduleDir 'META-INF\xposed\module.prop') -Encoding ascii -NoNewline
if($KeepChromeScope){Copy-Item -LiteralPath (Join-Path $legacyJavaRes 'scope.list') -Destination (Join-Path $moduleDir 'META-INF\xposed\scope.list'); Write-Warning 'Using historical 0.0.67 LSPosed scope: system + Chrome.'}
else{'system' | Set-Content -LiteralPath (Join-Path $moduleDir 'META-INF\xposed\scope.list') -Encoding ascii -NoNewline; Write-Host 'Using safe LSPosed scope: system only.'}
$assets=Join-Path $projectRoot 'app\src\main\assets'; if(Test-Path -LiteralPath $assets){& $jarTool uf $baseApk -C $assets '.'; if($LASTEXITCODE -ne 0){throw "Could not add assets: $LASTEXITCODE"}}
& $jarTool uf $baseApk -C $dexDir '.'; if($LASTEXITCODE -ne 0){throw "Could not add dex: $LASTEXITCODE"}
& $jarTool uf $baseApk -C $moduleDir '.'; if($LASTEXITCODE -ne 0){throw "Could not add LSPosed resources: $LASTEXITCODE"}
& $zipalign '-f' '4' $baseApk $alignedApk; if($LASTEXITCODE -ne 0){throw "zipalign failed: $LASTEXITCODE"}

$signArgs=@('sign','--v1-signing-enabled','true','--v2-signing-enabled','true','--v3-signing-enabled','true'); $signingKind='debug'; $propertiesPath=Join-Path $projectRoot 'keystore.properties'
if(Test-Path -LiteralPath $propertiesPath){$properties=@{}; foreach($line in Get-Content -LiteralPath $propertiesPath){if($line -match '^\s*([^#=]+?)\s*=\s*(.*?)\s*$'){$properties[$matches[1].Trim()]=$matches[2].Trim()}}; $storeValue=$properties['storeFile']; if($storeValue){$storePath=if([IO.Path]::IsPathRooted($storeValue)){$storeValue}else{Join-Path $projectRoot $storeValue}; if((Test-Path -LiteralPath $storePath) -and $properties['keyAlias'] -and $properties['storePassword'] -and $properties['keyPassword']){$signArgs+=@('--ks',$storePath,'--ks-key-alias',$properties['keyAlias'],'--ks-pass',"pass:$($properties['storePassword'])",'--key-pass',"pass:$($properties['keyPassword'])"); $signingKind='project'}}}
if($signingKind -eq 'debug'){$debugKeystore=Join-Path $env:USERPROFILE '.android\debug.keystore'; if(-not(Test-Path -LiteralPath $debugKeystore)){& (Join-Path $jdk 'bin\keytool.exe') '-genkeypair' '-keystore' $debugKeystore '-storepass' 'android' '-alias' 'androiddebugkey' '-keypass' 'android' '-keyalg' 'RSA' '-keysize' '2048' '-validity' '10000' '-dname' 'CN=Android Debug,O=Android,C=US';if($LASTEXITCODE -ne 0){throw 'Could not create debug keystore'}}; $signArgs+=@('--ks',$debugKeystore,'--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android')}
$signArgs+=@('--out',$signedApk,$alignedApk); & $apksigner @signArgs; if($LASTEXITCODE -ne 0){throw "apksigner failed: $LASTEXITCODE"}; & $apksigner 'verify' '--verbose' '--print-certs' $signedApk; if($LASTEXITCODE -ne 0){throw "signature verify failed: $LASTEXITCODE"}
Copy-Item -LiteralPath $signedApk -Destination $outputApk -Force
$badging=& $aapt2 'dump' 'badging' $outputApk; if($LASTEXITCODE -ne 0){throw "aapt2 badging failed: $LASTEXITCODE"}; if(-not($badging -match "versionCode='67'" -and $badging -match "versionName='0\.0\.67'")){throw 'Recovered APK does not report 0.0.67 / 67.'}
$entries=& $jarTool tf $outputApk; foreach($entry in @('classes.dex','META-INF/xposed/module.prop','META-INF/xposed/scope.list','META-INF/xposed/java_init.list')){if(-not($entries -contains $entry)){throw "Recovered APK missing $entry"}}
Write-Host ''; Write-Host 'Recovered IceBridge v0.0.67 package created successfully.' -ForegroundColor Green; Write-Host "APK: $outputApk"; Write-Host "Signing: $signingKind"; Write-Host "Scope: $(if($KeepChromeScope){'system + Chrome historical'}else{'system only safe'})"; Write-Host "Dex files: $($dexFiles.Count)"
