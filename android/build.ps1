param(
    [string]$ToolRoot = (Join-Path $PSScriptRoot '..\..\android-build-tools'),
    [ValidateSet('arm64-v8a','x86_64')][string]$Abi = 'arm64-v8a',
    [string]$Proxy = '',
    [string]$KeyStore = '',
    [string]$KeyAlias = 'androiddebugkey',
    [switch]$SmokeTest
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot
$ToolRoot = [IO.Path]::GetFullPath($ToolRoot)
$build = Join-Path $PSScriptRoot "build\v2-$Abi"
$dist = Join-Path $repo 'dist'
New-Item -ItemType Directory -Path $ToolRoot,$build,$dist -Force | Out-Null
if ($Proxy) { $env:HTTPS_PROXY=$Proxy; $env:HTTP_PROXY=$Proxy }
$env:Path = (Join-Path $env:USERPROFILE '.cargo\bin') + ';' + $env:Path
function Fetch([string]$url, [string]$file, [string]$hash, [string]$algorithm='SHA256') {
    if (-not (Test-Path -LiteralPath $file)) {
        Write-Host "Downloading $url"
        $args = @{Uri=$url; OutFile=($file+'.partial'); TimeoutSec=1200}
        if ($Proxy) { $args.Proxy=$Proxy }
        Invoke-WebRequest @args
        Move-Item -LiteralPath ($file+'.partial') -Destination $file
    }
    if ((Get-FileHash -LiteralPath $file -Algorithm $algorithm).Hash -ne $hash) { throw "Checksum mismatch: $file" }
}
function Run([string]$program, [string[]]$arguments) {
    & $program @arguments
    if ($LASTEXITCODE -ne 0) { throw "$program failed with exit code $LASTEXITCODE" }
}
$packages = @(
    @{File='platform-35_r02.zip'; Hash='0bb560a90a7a2cbd0dd8348224d518b638fe7949'; Folder='platforms-android-35'},
    @{File='build-tools_r35_windows.zip'; Hash='af059bb67cf7786f45ee0db85e2d24985df1b4b6'; Folder='build-tools-35-0-0'},
    @{File='android-ndk-r29-windows.zip'; Hash='ab3bb30fbb9e6903666d60c55d11e78b04e07472'; Folder='ndk-29-0-14206865'}
)
foreach($package in $packages) {
    $zip = Join-Path $ToolRoot $package.File
    Fetch ('https://dl.google.com/android/repository/'+$package.File) $zip $package.Hash 'SHA1'
    $folder = Join-Path $ToolRoot $package.Folder
    if (-not (Test-Path -LiteralPath $folder)) { Expand-Archive -LiteralPath $zip -DestinationPath $folder }
}
$androidJar = Join-Path $ToolRoot 'platforms-android-35\android-35\android.jar'
$buildTools = Join-Path $ToolRoot 'build-tools-35-0-0\android-15'
$ndk = Join-Path $ToolRoot 'ndk-29-0-14206865\android-ndk-r29\toolchains\llvm\prebuilt\windows-x86_64\bin'
if ($Abi -eq 'arm64-v8a') {
    $target='aarch64-linux-android'; $clang='aarch64-linux-android26-clang.cmd'
} else {
    $target='x86_64-linux-android'; $clang='x86_64-linux-android26-clang.cmd'
}
$targetEnv=$target.Replace('-','_')
[Environment]::SetEnvironmentVariable('CC_'+$targetEnv, (Join-Path $ndk $clang), 'Process')
[Environment]::SetEnvironmentVariable('AR_'+$targetEnv, (Join-Path $ndk 'llvm-ar.exe'), 'Process')
[Environment]::SetEnvironmentVariable('CARGO_TARGET_'+$targetEnv.ToUpperInvariant()+'_LINKER', (Join-Path $ndk $clang), 'Process')
$env:CARGO_BUILD_JOBS='3'
$env:RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384'
Run 'rustup' @('target','add',$target)
Push-Location $repo
try { Run 'cargo' @('rustc','--release','--locked','--no-default-features','--lib','--crate-type','cdylib','--target',$target) } finally { Pop-Location }
$lib = Join-Path $build "package\lib\$Abi"
New-Item -ItemType Directory -Path $lib -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $repo "target\$target\release\libvproxy.so") -Destination $lib
Run (Join-Path $ndk $clang) @('-shared','-fPIC','-O2','-Wall','-Wextra','-Werror','-Wl,-z,max-page-size=16384',(Join-Path $PSScriptRoot 'native\process.c'),'-o',(Join-Path $lib 'libvproxy_process.so'))
$engine=Join-Path $ToolRoot "mihomo-cmfa\$Abi\libmihomo.so"
if(-not (Test-Path -LiteralPath $engine) -or -not (Test-Path -LiteralPath ($engine+'.json'))) { & (Join-Path $PSScriptRoot 'build-mihomo.ps1') -ToolRoot $ToolRoot -Abi $Abi -Proxy $Proxy }
$engineMetadata=Get-Content -LiteralPath ($engine+'.json') -Raw | ConvertFrom-Json
if($engineMetadata.revision -ne '3f27ef39f8c96bbfbc8b0b9bb10f5c9ef60149d8' -or $engineMetadata.tags -ne 'with_gvisor,cmfa' -or $engineMetadata.sha256 -ne (Get-FileHash -LiteralPath $engine -Algorithm SHA256).Hash.ToLowerInvariant()) { throw 'Cached Mihomo provenance check failed' }
Copy-Item -LiteralPath $engine -Destination (Join-Path $lib 'libmihomo.so')
$assets=Join-Path $build 'assets'
$resources=Join-Path $build 'res'
New-Item -ItemType Directory -Path $assets,(Join-Path $resources 'drawable'),(Join-Path $resources 'xml') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'res\xml\network_security_config.xml') -Destination (Join-Path $resources 'xml')
Copy-Item -LiteralPath (Join-Path $repo 'assets\app-icon-256.png') -Destination (Join-Path $resources 'drawable\icon.png')
$license=Join-Path $ToolRoot 'mihomo-LICENSE'
if (-not (Test-Path -LiteralPath $license)) {
    $args=@{Uri='https://raw.githubusercontent.com/MetaCubeX/mihomo/v1.19.33/LICENSE';OutFile=$license;TimeoutSec=60}
    if ($Proxy) { $args.Proxy=$Proxy }; Invoke-WebRequest @args
}
$notices="VProxy Android`nVProxy https://github.com/mateo-ki/vproxy (MIT)`n"+[IO.File]::ReadAllText((Join-Path $repo 'LICENSE'))+
    "`nMihomo v1.19.33 (GPL-3.0), built with cmfa,with_gvisor`nSource commit: 3f27ef39f8c96bbfbc8b0b9bb10f5c9ef60149d8`nCorresponding source: https://github.com/MetaCubeX/mihomo/tree/3f27ef39f8c96bbfbc8b0b9bb10f5c9ef60149d8`nBuild recipe: android/build-mihomo.ps1`n"+[IO.File]::ReadAllText($license)
[IO.File]::WriteAllText((Join-Path $assets 'THIRD_PARTY_NOTICES.txt'),$notices,[Text.UTF8Encoding]::new($false))
$compiled=Join-Path $build 'resources.zip'
Run (Join-Path $buildTools 'aapt2.exe') @('compile','--dir',$resources,'-o',$compiled)
$manifest=Join-Path $PSScriptRoot 'AndroidManifest.xml'
if ($SmokeTest) {
    $manifest=Join-Path $build 'AndroidManifest.xml'
    $xml=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'AndroidManifest.xml')).Replace('</manifest>','<instrumentation android:name=".SmokeTest" android:targetPackage="io.github.heavencanceller.vproxy" android:label="Local smoke tests" /><instrumentation android:name=".VpnSmokeTest" android:targetPackage="io.github.heavencanceller.vproxy" android:label="Local VPN tests" /></manifest>')
    $xml=$xml.Replace('android:allowBackup="false"','android:allowBackup="false" android:debuggable="true"')
    $xml=$xml.Replace('</manifest>','<instrumentation android:name=".ManagementTest" android:targetPackage="io.github.heavencanceller.vproxy" android:label="v2 management tests" /></manifest>')
    [IO.File]::WriteAllText($manifest,$xml,[Text.UTF8Encoding]::new($false))
}
$unsigned=Join-Path $build 'unsigned.apk'
Run (Join-Path $buildTools 'aapt2.exe') @('link','-o',$unsigned,'-I',$androidJar,'--manifest',$manifest,'-A',$assets,$compiled)
$classes=Join-Path $build 'classes'
$dex=Join-Path $build 'dex'
New-Item -ItemType Directory -Path $classes,$dex -Force | Out-Null
$sources=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -Recurse -Filter '*.java' | ForEach-Object FullName)
if ($SmokeTest) { $sources+=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'test') -Recurse -Filter '*.java' | ForEach-Object FullName) }
Run 'javac' (@('-encoding','UTF-8','--release','8','-classpath',$androidJar,'-d',$classes)+$sources)
$classJar=Join-Path $build 'classes.jar'
Run 'jar' @('cf',$classJar,'-C',$classes,'.')
Run 'java' @('-cp',(Join-Path $buildTools 'lib\d8.jar'),'com.android.tools.r8.D8','--release','--min-api','26','--lib',$androidJar,'--output',$dex,$classJar)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apk=[IO.Compression.ZipFile]::Open($unsigned,[IO.Compression.ZipArchiveMode]::Update)
try {
    foreach($file in (Get-ChildItem -LiteralPath (Join-Path $build 'package') -Recurse -File)) {
        $entry=$file.FullName.Substring((Join-Path $build 'package').Length+1).Replace('\','/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($apk,$file.FullName,$entry,[IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
    foreach($file in (Get-ChildItem -LiteralPath $dex -Filter '*.dex')) {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($apk,$file.FullName,$file.Name,[IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $apk.Dispose() }
$aligned=Join-Path $build 'aligned.apk'
Run (Join-Path $buildTools 'zipalign.exe') @('-P','16','-f','4',$unsigned,$aligned)
if (-not $KeyStore) {
    $KeyStore=Join-Path $ToolRoot 'vproxy-android-dev.jks'
    if (-not (Test-Path -LiteralPath $KeyStore)) { Run 'keytool' @('-genkeypair','-keystore',$KeyStore,'-alias',$KeyAlias,'-storepass','android','-keypass','android','-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=VProxy Development') }
    $env:VPROXY_STORE_PASSWORD='android'
}
if (-not $env:VPROXY_STORE_PASSWORD) { throw 'Set VPROXY_STORE_PASSWORD for the supplied keystore' }
[xml]$versionManifest=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'AndroidManifest.xml') -Raw
$versionName=$versionManifest.manifest.GetAttribute('versionName','http://schemas.android.com/apk/res/android')
$output=Join-Path $dist "vproxy-$versionName-$Abi.apk"
Run 'java' @('-jar',(Join-Path $buildTools 'lib\apksigner.jar'),'sign','--ks',$KeyStore,'--ks-key-alias',$KeyAlias,'--ks-pass','env:VPROXY_STORE_PASSWORD','--out',$output,$aligned)
Run 'java' @('-jar',(Join-Path $buildTools 'lib\apksigner.jar'),'verify','--verbose','--print-certs',$output)
Run (Join-Path $buildTools 'zipalign.exe') @('-c','-P','16','4',$output)
Get-FileHash -LiteralPath $output -Algorithm SHA256
Write-Host "APK: $output"
