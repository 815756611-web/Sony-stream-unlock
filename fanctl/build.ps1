param(
    [string]$JavaHome = $env:JAVA_HOME,
    [Parameter(Mandatory = $true)][string]$BuildTools,
    [Parameter(Mandatory = $true)][string]$AndroidJar,
    [string]$Src = "",
    [string]$Out = "",
    [string]$StorePass = "fanctl123"
)

$ErrorActionPreference = "Stop"

# Defaults are relative to this script so the repository stays portable.
if (-not $Src) { $Src = $PSScriptRoot }
if (-not $Out) { $Out = Join-Path (Split-Path $Src -Parent) "StreamFanCtl.apk" }
if (-not $JavaHome) { throw "JavaHome is not set: pass -JavaHome or set JAVA_HOME" }

function Find-One([string]$Root, [string]$Name) {
    $hit = Get-ChildItem -Path $Root -Recurse -Filter $Name -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -eq $hit) { throw "not found: $Name under $Root" }
    return $hit.FullName
}

$javac = Find-One $JavaHome "javac.exe"
$keytool = Find-One $JavaHome "keytool.exe"
$aapt2 = Find-One $BuildTools "aapt2.exe"
$d8 = Find-One $BuildTools "d8.bat"
$apksigner = Find-One $BuildTools "apksigner.bat"
$zipalign = Find-One $BuildTools "zipalign.exe"

$env:JAVA_HOME = (Split-Path (Split-Path $javac -Parent) -Parent)
Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host "aapt2=$aapt2"
Write-Host "d8=$d8"

$build = Join-Path $Src "build"
if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Path $build | Out-Null
New-Item -ItemType Directory -Path (Join-Path $build "classes") | Out-Null
New-Item -ItemType Directory -Path (Join-Path $build "dex") | Out-Null

# 1) compile
$sources = Get-ChildItem -Path (Join-Path $Src "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Write-Host "sources:"; $sources | ForEach-Object { Write-Host "  $_" }
& $javac --release 8 -Xlint:-options -classpath $AndroidJar -d (Join-Path $build "classes") $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# 2) dex
$classes = Get-ChildItem -Path (Join-Path $build "classes") -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& $d8 --lib $AndroidJar --min-api 29 --output (Join-Path $build "dex") $classes
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }
$dexFile = Join-Path $build "dex\classes.dex"
if (-not (Test-Path $dexFile)) { throw "classes.dex not produced" }

# 3) link resources/manifest
$baseApk = Join-Path $build "base.apk"
Push-Location $Src
try {
    & $aapt2 link -o $baseApk --manifest "AndroidManifest.xml" -I $AndroidJar
    if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }
} finally {
    Pop-Location
}

# 4) inject classes.dex at the archive root
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($baseApk, "Update")
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $dexFile, "classes.dex") | Out-Null
$zip.Dispose()

# 5) align
$aligned = Join-Path $build "aligned.apk"
& $zipalign -f 4 $baseApk $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

# 6) keystore + sign
# Keep the keystore OUTSIDE build/ so every rebuild uses the same signing key
# (otherwise updates fail with INSTALL_FAILED_UPDATE_INCOMPATIBLE).
$ks = Join-Path $Src "fanctl.jks"
if (-not (Test-Path $ks)) {
    & $keytool -genkeypair -keystore $ks -alias fanctl -keyalg RSA -keysize 2048 `
        -validity 10000 -storepass $StorePass -keypass $StorePass `
        -dname "CN=StreamFanCtl, O=FanCtl" -noprompt
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}
if (Test-Path $Out) { Remove-Item $Out -Force }
& $apksigner sign --ks $ks --ks-pass "pass:$StorePass" --key-pass "pass:$StorePass" --out $Out $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed" }

# 7) verify
& $apksigner verify --print-certs $Out
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed" }
& $aapt2 dump badging $Out | Select-Object -First 6

$fi = Get-Item $Out
$hash = (Get-FileHash $Out -Algorithm SHA256).Hash
Write-Host ""
Write-Host "APK      : $($fi.FullName)"
Write-Host "size     : $($fi.Length) bytes"
Write-Host "sha256   : $hash"
