<#
.SYNOPSIS
    通用独立应用构建脚本（app-news / app-stock 共用）。

.DESCRIPTION
    把「宿主层 + 插件页面源码 + 独立入口」合成一个普通 APK：
      1. 收集源码：shared/plugin-sdk/app（运行时垫片）+ app-host/src（HostActivity）
                    + app-*/src（Activity / 图标资源）+ plugin-*/src（页面代码，去掉 CreateApi）
      2. javac → d8 → aapt2 compile/link → 塞 dex → zipalign → apksigner
    签名用仓库根目录 signing/tvdesk.jks（和插件同一把钥匙，没有就自动生成）。

.PARAMETER Project
    独立应用目录名，例如 app-news。

.PARAMETER Plugin
    该应用复用的插件目录名，例如 plugin-news。

.PARAMETER OutputName
    产物文件名，默认 app.apk。

.PARAMETER Release
    构建成功后复制到 release/。

.EXAMPLE
    .\scripts\build-app.ps1 -Project app-news -Plugin plugin-news -OutputName news-app.apk
#>
param(
    [Parameter(Mandatory = $true)][string]$Project,
    [Parameter(Mandatory = $true)][string]$Plugin,
    [string]$OutputName = 'app.apk',
    [switch]$Release
)

$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$repo = Split-Path $PSScriptRoot -Parent
$proj = Join-Path $repo $Project
$pluginDir = Join-Path $repo $Plugin
$hostDir = Join-Path $repo 'app-host'
foreach ($d in @($proj, $pluginDir, $hostDir)) {
    if (-not (Test-Path $d)) { throw "找不到目录：$d" }
}

# ---------- 工具链 ----------
function Find-ToolsRoot {
    $candidates = @()
    if ($env:TVTOOLS) { $candidates += $env:TVTOOLS }
    $p = $repo
    while ($p) {
        $candidates += (Join-Path $p 'tools')
        $parent = Split-Path $p -Parent
        if (-not $parent -or $parent -eq $p) { break }
        $p = $parent
    }
    foreach ($t in $candidates) {
        if ((Test-Path (Join-Path $t 'android-sdk\build-tools\34.0.0\aapt2.exe')) -and
            (Test-Path (Join-Path $t 'jdk'))) { return $t }
    }
    return $null
}

$tools = Find-ToolsRoot
if (-not $tools) {
    throw "找不到工具链：把 JDK 17 和 Android SDK（build-tools 34 / platform 34）放到仓库同级 tools/ 下，或设置环境变量 TVTOOLS 指向包含 android-sdk 和 jdk 的目录。"
}

$sdk = Join-Path $tools 'android-sdk'
$bt = Join-Path $sdk 'build-tools\34.0.0'
$jdkRoot = Join-Path $tools 'jdk'
$jdk = if (Test-Path (Join-Path $jdkRoot 'bin\javac.exe')) { $jdkRoot } else {
    (Get-ChildItem $jdkRoot -Directory | Select-Object -First 1).FullName
}
$androidJar = Join-Path $sdk 'platforms\android-34\android.jar'
if (-not (Test-Path $androidJar)) { throw "缺 android.jar：$androidJar" }

$out = Join-Path $proj 'build'
$tmp = Join-Path $repo 'build'
New-Item -ItemType Directory -Force -Path $out, $tmp | Out-Null
$env:TMP = $tmp; $env:TEMP = $tmp; $env:JAVA_HOME = $jdk

$cls = Join-Path $out 'classes'
$dexDir = Join-Path $out 'dex'
$resOut = Join-Path $out 'res'
foreach ($d in @($cls, $dexDir, $resOut)) {
    if (Test-Path $d) { Remove-Item $d -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}

Write-Host "== 1/7 收集源码 =="
$srcs = @()
$srcs += Get-ChildItem (Join-Path $repo 'shared\plugin-sdk\app') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$srcs += Get-ChildItem (Join-Path $hostDir 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$srcs += Get-ChildItem (Join-Path $proj 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
# CreateApi 只给 launcher 反射用，独立应用不需要
$srcs += Get-ChildItem (Join-Path $pluginDir 'src') -Recurse -Filter *.java |
    Where-Object { $_.Name -ne 'CreateApi.java' } | ForEach-Object { $_.FullName }
Write-Host ("   {0} 个 java（{1} 页面代码 + 独立宿主）" -f $srcs.Count, $Plugin)

Write-Host "== 2/7 javac =="
& "$jdk\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -nowarn `
    -d $cls -cp $androidJar @srcs 2>&1 |
    Where-Object { $_ -notmatch 'bootstrap|source value 8|target value 8|deprecat' }
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "== 3/7 d8 =="
$jar = Join-Path $out 'classes.jar'
if (Test-Path $jar) { Remove-Item $jar -Force }
& "$jdk\bin\jar.exe" cf $jar -C $cls .
& "$bt\d8.bat" --min-api 21 --lib $androidJar --output $dexDir $jar 2>&1 |
    Select-Object -Last 3
if (-not (Test-Path (Join-Path $dexDir 'classes.dex'))) { throw "d8 failed" }
Write-Host ("   dex {0:N0} bytes" -f (Get-Item (Join-Path $dexDir 'classes.dex')).Length)

Write-Host "== 4/7 aapt2 compile =="
$resZip = Join-Path $out 'res.zip'
if (Test-Path $resZip) { Remove-Item $resZip -Force }
$resDir = Join-Path $proj 'res'
if (Test-Path $resDir) {
    & "$bt\aapt2.exe" compile --dir $resDir -o $resZip 2>&1
    if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }
}

Write-Host "== 5/7 aapt2 link =="
$base = Join-Path $out 'base.apk'
if (Test-Path $base) { Remove-Item $base -Force }
$linkArgs = @('link', '-o', $base, '--manifest', (Join-Path $proj 'AndroidManifest.xml'),
    '-I', $androidJar, '--min-sdk-version', '21', '--target-sdk-version', '21')
if (Test-Path $resZip) { $linkArgs += $resZip }
& "$bt\aapt2.exe" @linkArgs 2>&1
if (-not (Test-Path $base)) { throw "aapt2 link failed" }

Write-Host "== 6/7 放入 dex + 对齐 =="
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($base, 'Update')
$old = $zip.GetEntry('classes.dex'); if ($old) { $old.Delete() }
$entry = $zip.CreateEntry('classes.dex', [System.IO.Compression.CompressionLevel]::Optimal)
$in = [System.IO.File]::OpenRead((Join-Path $dexDir 'classes.dex')); $es = $entry.Open()
$in.CopyTo($es); $es.Close(); $in.Close()
$zip.Dispose()

$unsigned = Join-Path $out 'unsigned.apk'
if (Test-Path $unsigned) { Remove-Item $unsigned -Force }
& "$bt\zipalign.exe" -f -p 4 $base $unsigned 2>&1
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "== 7/7 签名 =="
$ks = Join-Path $repo 'signing\tvdesk.jks'
if (-not (Test-Path $ks)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $ks) | Out-Null
    Write-Host "   没有现成签名，生成开发用 keystore：$ks（换机会变签名）"
    & "$jdk\bin\keytool.exe" -genkeypair -v -keystore $ks -alias tvdesk `
        -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=TVDesk, OU=TV, O=DSH, L=CN, ST=CN, C=CN" 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

$signed = Join-Path $out $OutputName
if (Test-Path $signed) { Remove-Item $signed -Force }
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --min-sdk-version 21 --out $signed $unsigned 2>&1
if (-not (Test-Path $signed)) { throw "apksigner failed" }

Write-Host ""
Get-Item $signed | ForEach-Object { "{0}  {1:N0} bytes" -f $_.FullName, $_.Length }
& "$bt\aapt2.exe" dump badging $signed 2>&1 |
    Select-String -Pattern '^package:|^launchable-activity:' | ForEach-Object { $_.Line }

if ($Release) {
    $releaseDir = Join-Path $repo 'release'
    New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null
    Copy-Item $signed (Join-Path $releaseDir $OutputName) -Force
    Write-Host ("已复制到 {0}" -f (Join-Path $releaseDir $OutputName))
}
