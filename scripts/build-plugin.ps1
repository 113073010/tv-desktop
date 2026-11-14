<#
.SYNOPSIS
    通用插件构建脚本（5 个桌面 Tab 插件共用一套流水线）。

.DESCRIPTION
    流程：javac 编译桩 → javac 编译插件 → d8 → aapt2 link → 塞入 dex → zipalign → apksigner。
    - 编译期桩统一来自 shared/plugin-sdk/plugin（不会打进 dex，运行时用桌面自带的类）
    - 签名统一用仓库根目录 signing/tvdesk.jks；没有就现场生成一个开发用 keystore
    - plugin-live 额外把 ../ijk 的播放器 jar 和 .so 打进 APK

.PARAMETER Project
    插件目录名，例如 plugin-news、plugin-live。

.PARAMETER OutputName
    产物文件名，默认由 Project 去掉 plugin- 前缀得到，例如 news.apk。

.PARAMETER Release
    构建成功后复制到 release/。

.EXAMPLE
    .\scripts\build-plugin.ps1 -Project plugin-news
    .\scripts\build-plugin.ps1 -Project plugin-live -Release
#>
param(
    [Parameter(Mandatory = $true)][string]$Project,
    [string]$OutputName,
    [switch]$Release
)

$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$repo = Split-Path $PSScriptRoot -Parent
$proj = Join-Path $repo $Project
if (-not (Test-Path $proj)) { throw "找不到工程目录：$proj" }
if (-not $OutputName) { $OutputName = ($Project -replace '^plugin-', '') + '.apk' }

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

$stubCls = Join-Path $out 'stub-classes'
$plugCls = Join-Path $out 'classes'
$dexDir = Join-Path $out 'dex'
foreach ($d in @($stubCls, $plugCls, $dexDir)) {
    if (Test-Path $d) { Remove-Item $d -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}

# ---------- 编译期桩 ----------
$stubRoot = Join-Path $repo 'shared\plugin-sdk\plugin'
$stubSrc = Get-ChildItem $stubRoot -Recurse -Filter *.java | ForEach-Object { $_.FullName }
if (-not $stubSrc) { throw "缺少编译期桩：$stubRoot" }

Write-Host "== 1/6 编译桩 =="
& "$jdk\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -nowarn -d $stubCls -cp $androidJar @stubSrc 2>&1 |
    Where-Object { $_ -notmatch 'bootstrap|source value 8|target value 8' }
& "$jdk\bin\jar.exe" cf "$out\stubs.jar" -C $stubCls .
Write-Host "   ok"

# ---------- 编译插件 ----------
Write-Host "== 2/6 编译 $Project =="
$plugSrc = Get-ChildItem (Join-Path $proj 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName }

# 直播插件额外依赖 ijk 播放器：编译、d8、打包都要带上
$extraDex = @()
$extraLibDir = $null
$plugCp = "$androidJar;$out\stubs.jar"
if ($Project -eq 'plugin-live') {
    $ijkJar = Join-Path (Split-Path $repo -Parent) 'ijk\ijkplayer-classes.jar'
    $extraLibDir = Join-Path (Split-Path $repo -Parent) 'ijk\lib'
    if (-not (Test-Path $ijkJar)) { throw "直播插件缺 ijk 依赖：$ijkJar" }
    $extraDex += $ijkJar
    $plugCp = "$plugCp;$ijkJar"
}

& "$jdk\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -nowarn -d $plugCls -cp $plugCp @plugSrc 2>&1 |
    Where-Object { $_ -notmatch 'bootstrap|source value 8|target value 8|deprecat' }
$pkg = $Project -replace '^plugin-', ''
if (-not (Test-Path (Join-Path $plugCls "com\dsh\tvdesk\$pkg"))) { throw "javac failed" }
Write-Host "   ok"

# ---------- d8 ----------
Write-Host "== 3/6 d8 =="
& "$jdk\bin\jar.exe" cf "$out\plugin.jar" -C $plugCls .

$d8Args = @('--min-api', '21', '--lib', $androidJar, '--lib', "$out\stubs.jar", '--output', $dexDir, "$out\plugin.jar") + $extraDex
& "$bt\d8.bat" @d8Args 2>&1 | Select-Object -Last 3
if (-not (Test-Path "$dexDir\classes.dex")) { throw "d8 failed" }
Write-Host ("   dex {0:N0} bytes" -f (Get-Item "$dexDir\classes.dex").Length)

# ---------- aapt2 link ----------
Write-Host "== 4/6 aapt2 link =="
$base = "$out\base.apk"
if (Test-Path $base) { Remove-Item $base -Force }
& "$bt\aapt2.exe" link -o $base --manifest (Join-Path $proj 'AndroidManifest.xml') `
    -I $androidJar --min-sdk-version 21 --target-sdk-version 21 2>&1
if (-not (Test-Path $base)) { throw "aapt2 link failed" }

# ---------- 放入 dex / so，对齐 ----------
Write-Host "== 5/6 放入 dex + 对齐 =="
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($base, 'Update')
$old = $zip.GetEntry('classes.dex'); if ($old) { $old.Delete() }
$entry = $zip.CreateEntry('classes.dex', [System.IO.Compression.CompressionLevel]::Optimal)
$in = [System.IO.File]::OpenRead("$dexDir\classes.dex"); $es = $entry.Open()
$in.CopyTo($es); $es.Close(); $in.Close()

if ($extraLibDir -and (Test-Path $extraLibDir)) {
    Get-ChildItem $extraLibDir -Filter *.so | ForEach-Object {
        $name = "lib/armeabi-v7a/" + $_.Name
        $o = $zip.GetEntry($name); if ($o) { $o.Delete() }
        $ne = $zip.CreateEntry($name, [System.IO.Compression.CompressionLevel]::NoCompression)
        $fs = [System.IO.File]::OpenRead($_.FullName); $os = $ne.Open()
        $fs.CopyTo($os); $os.Close(); $fs.Close()
        Write-Host ("   + {0} ({1:N1} MB)" -f $name, ($_.Length / 1MB))
    }
}
$zip.Dispose()

$unsigned = "$out\unsigned.apk"
if (Test-Path $unsigned) { Remove-Item $unsigned -Force }
& "$bt\zipalign.exe" -f -p 4 $base $unsigned 2>&1
if (-not (Test-Path $unsigned)) { throw "zipalign failed" }

# ---------- 签名 ----------
Write-Host "== 6/6 签名 =="
$ks = Join-Path $repo 'signing\tvdesk.jks'
if (-not (Test-Path $ks)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $ks) | Out-Null
    Write-Host "   没有现成签名，生成开发用 keystore：$ks（同机自用即可，换机会变签名）"
    & "$jdk\bin\keytool.exe" -genkeypair -keystore $ks -alias tvdesk -keyalg RSA -keysize 2048 `
        -validity 10000 -storepass android -keypass android -dname "CN=TVDesk, O=TVDesk, C=CN" 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

$signed = "$out\$OutputName"
if (Test-Path $signed) { Remove-Item $signed -Force }
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --min-sdk-version 21 --out $signed $unsigned 2>&1
if (-not (Test-Path $signed)) { throw "apksigner failed" }

Write-Host ""
Get-Item $signed | ForEach-Object { "{0}  {1:N0} bytes" -f $_.FullName, $_.Length }
& "$bt\aapt2.exe" dump badging $signed 2>&1 |
    Select-String -Pattern '^package:' | ForEach-Object { $_.Line }

if ($Release) {
    $releaseDir = Join-Path $repo 'release'
    New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null
    Copy-Item $signed (Join-Path $releaseDir $OutputName) -Force
    Write-Host ("已复制到 {0}" -f (Join-Path $releaseDir $OutputName))
}
