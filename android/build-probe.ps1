param([string]$ToolRoot=(Join-Path $PSScriptRoot '..\..\android-build-tools'))
$ErrorActionPreference='Stop'
$ToolRoot=[IO.Path]::GetFullPath($ToolRoot)
$output=Join-Path $PSScriptRoot 'build\probe'
$tools=Join-Path $ToolRoot 'build-tools-35-0-0\android-15'
$androidJar=Join-Path $ToolRoot 'platforms-android-35\android-35\android.jar'
New-Item -ItemType Directory -Path $output,(Join-Path $output 'classes'),(Join-Path $output 'dex') -Force | Out-Null
function Run([string]$program,[string[]]$arguments) {
    & $program @arguments
    if($LASTEXITCODE -ne 0) { throw "$program failed: $LASTEXITCODE" }
}
Run (Join-Path $tools 'aapt2.exe') @('link','-o',(Join-Path $output 'unsigned.apk'),'-I',$androidJar,'--manifest',(Join-Path $PSScriptRoot 'test-probe\AndroidManifest.xml'))
$sources=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'test-probe') -Filter '*.java' | ForEach-Object FullName)
Run 'javac' (@('-encoding','UTF-8','--release','8','-classpath',$androidJar,'-d',(Join-Path $output 'classes'))+$sources)
Run 'jar' @('cf',(Join-Path $output 'classes.jar'),'-C',(Join-Path $output 'classes'),'.')
Run 'java' @('-cp',(Join-Path $tools 'lib\d8.jar'),'com.android.tools.r8.D8','--release','--min-api','26','--lib',$androidJar,'--output',(Join-Path $output 'dex'),(Join-Path $output 'classes.jar'))
$apk=[IO.Compression.ZipFile]::Open((Join-Path $output 'unsigned.apk'),[IO.Compression.ZipArchiveMode]::Update)
try { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($apk,(Join-Path $output 'dex\classes.dex'),'classes.dex',[IO.Compression.CompressionLevel]::Optimal) | Out-Null } finally { $apk.Dispose() }
Run (Join-Path $tools 'zipalign.exe') @('-f','4',(Join-Path $output 'unsigned.apk'),(Join-Path $output 'aligned.apk'))
$signed=Join-Path $output 'vproxy-tunnel-probe.apk'
Run 'java' @('-jar',(Join-Path $tools 'lib\apksigner.jar'),'sign','--ks',(Join-Path $ToolRoot 'vproxy-android-dev.jks'),'--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--out',$signed,(Join-Path $output 'aligned.apk'))
Write-Output $signed
