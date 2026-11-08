param(
    [string]$ToolRoot=(Join-Path $PSScriptRoot '..\..\android-build-tools'),
    [ValidateSet('arm64-v8a','x86_64')][string]$Abi='arm64-v8a',
    [string]$Proxy=''
)
$ErrorActionPreference='Stop'
$ToolRoot=[IO.Path]::GetFullPath($ToolRoot)
function Run([string]$program,[string[]]$arguments) {
    & $program @arguments
    if($LASTEXITCODE -ne 0) { throw "$program failed: $LASTEXITCODE" }
}
if($Proxy) { $env:HTTPS_PROXY=$Proxy; $env:HTTP_PROXY=$Proxy }
$go=Join-Path $ToolRoot 'go-runtime\go\bin\go.exe'
if(-not (Test-Path -LiteralPath $go)) {
    $zip=Join-Path $ToolRoot 'go1.27.3.windows-amd64.zip'
    if(-not (Test-Path -LiteralPath $zip)) {
        $args=@{Uri='https://go.dev/dl/go1.27.3.windows-amd64.zip';OutFile=$zip;TimeoutSec=600}
        if($Proxy) { $args.Proxy=$Proxy }; Invoke-WebRequest @args
    }
    if((Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash -ne 'f26081f438319757f8a1b46d7d0b2c5ec7583d4038e485e0aa725cfde7885fff') { throw 'Go archive checksum mismatch' }
    Expand-Archive -LiteralPath $zip -DestinationPath (Join-Path $ToolRoot 'go-runtime') -Force
}
$source=Join-Path $ToolRoot 'mihomo-source'
if(-not (Test-Path -LiteralPath $source)) {
    $gitArgs=@()
    if($Proxy) { $gitArgs+=@('-c',"http.proxy=$Proxy") }
    Run 'git' ($gitArgs+@('clone','--depth','1','--branch','v1.19.33','https://github.com/MetaCubeX/mihomo.git',$source))
}
$revision=& git -C $source rev-parse HEAD
if($revision -ne '3f27ef39f8c96bbfbc8b0b9bb10f5c9ef60149d8') { throw 'Unexpected Mihomo source revision' }
if(& git -C $source status --porcelain) { throw 'Mihomo source must be unmodified' }
$ndk=Join-Path $ToolRoot 'ndk-29-0-14206865\android-ndk-r29\toolchains\llvm\prebuilt\windows-x86_64\bin'
$target=if($Abi -eq 'arm64-v8a') {'aarch64-linux-android26'} else {'x86_64-linux-android26'}
$env:GOOS='android'
$env:GOARCH=if($Abi -eq 'arm64-v8a') {'arm64'} else {'amd64'}
$env:CGO_ENABLED='1'
$env:CC=(Join-Path $ndk 'clang.exe')+" --target=$target"
$env:CXX=(Join-Path $ndk 'clang++.exe')+" --target=$target"
$env:CGO_LDFLAGS='-Wl,-z,max-page-size=16384'
$env:GOMAXPROCS='2'
$env:GOFLAGS='-p=2'
$env:GOCACHE=Join-Path $ToolRoot 'go-cache'
$env:GOMODCACHE=Join-Path $ToolRoot 'go-modules'
$env:GOTOOLCHAIN='local'
$output=Join-Path $ToolRoot "mihomo-cmfa\$Abi\libmihomo.so"
New-Item -ItemType Directory -Path (Split-Path $output) -Force | Out-Null
Push-Location $source
try {
    Run $go @('build','-mod=readonly','-tags','with_gvisor,cmfa','-trimpath','-ldflags','-w -s -buildid= -X github.com/metacubex/mihomo/constant.Version=v1.19.33-vproxy-android','-o',$output,'.')
} finally { Pop-Location }
Get-FileHash -LiteralPath $output -Algorithm SHA256
$metadata=@{
    source='https://github.com/MetaCubeX/mihomo'
    revision=$revision
    version='v1.19.33'
    tags='with_gvisor,cmfa'
    abi=$Abi
    sha256=(Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
}
[IO.File]::WriteAllText(($output+'.json'),($metadata | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
Write-Output "Android VPN-compatible Mihomo: $output"
