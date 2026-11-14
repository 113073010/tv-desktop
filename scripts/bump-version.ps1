<#
.SYNOPSIS
    统一升版本号：同步修改 AndroidManifest.xml 里的 versionName / versionCode。

.DESCRIPTION
    插件和独立应用分别维护自己的版本号。默认把 patch 位 +1、versionCode +1；
    也可以用 -Version / -Code 指定确切值。

    改完记得重新构建，并用 scripts/sync-db.ps1 把新 APK 的 MD5/大小写回
    release/launcher_new.db，否则桌面会继续用旧 dex。

.PARAMETER Target
    news | live | douban | radio | stock | news-app | stock-app | all

.PARAMETER Version
    指定 versionName，例如 1.0.18；省略时 patch +1。

.PARAMETER Code
    指定 versionCode；省略时当前值 +1。

.EXAMPLE
    .\scripts\bump-version.ps1 -Target news
    .\scripts\bump-version.ps1 -Target news-app -Version 1.0.2 -Code 3
    .\scripts\bump-version.ps1 -Target all
#>
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('all', 'news', 'live', 'douban', 'radio', 'stock', 'news-app', 'stock-app')]
    [string[]]$Target,
    [string]$Version,
    [int]$Code = -1
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$repo = Split-Path $PSScriptRoot -Parent

function Get-Manifest([string]$name) {
    # news → plugin-news/AndroidManifest.xml；news-app → app-news/AndroidManifest.xml
    if ($name -like '*-app') {
        $dir = Join-Path $repo ('app-' + ($name -replace '-app$', ''))
    } else {
        $dir = Join-Path $repo "plugin-$name"
    }
    $m = Join-Path $dir 'AndroidManifest.xml'
    if (-not (Test-Path $m)) { throw "找不到 $m" }
    return $m
}

$targets = @()
foreach ($t in $Target) {
    if ($t -eq 'all') { $targets += @('news', 'live', 'douban', 'radio', 'stock', 'news-app', 'stock-app') }
    else { $targets += $t }
}
$targets = $targets | Select-Object -Unique

foreach ($t in $targets) {
    $m = Get-Manifest $t
    $text = [IO.File]::ReadAllText($m)

    $mCode = [regex]::Match($text, 'android:versionCode="(\d+)"')
    $mName = [regex]::Match($text, 'android:versionName="([^"]+)"')
    if (-not $mCode.Success -or -not $mName.Success) { throw "$m 里没有 versionCode / versionName" }

    $oldCode = [int]$mCode.Groups[1].Value
    $oldName = $mName.Groups[1].Value

    $newCode = if ($Code -ge 0) { $Code } else { $oldCode + 1 }

    if ($Version) {
        $newName = $Version
    } else {
        $bits = $oldName.Split('.')
        if ($bits.Count -lt 3) { throw "versionName 不是 x.y.z 形式：$oldName（请用 -Version 指定）" }
        $bits[$bits.Count - 1] = [string]([int]$bits[$bits.Count - 1] + 1)
        $newName = $bits -join '.'
    }

    $text = $text -replace 'android:versionCode="\d+"', "android:versionCode=`"$newCode`""
    $text = $text -replace 'android:versionName="[^"]+"', "android:versionName=`"$newName`""
    [IO.File]::WriteAllText($m, $text, (New-Object Text.UTF8Encoding $false))

    Write-Host ("{0,-10} {1,-22} {2} / {3}  →  {4} / {5}" -f
        $t, (Split-Path $m -Parent | Split-Path -Leaf), $oldName, $oldCode, $newName, $newCode)
}

Write-Host ''
Write-Host '下一步：.\build.ps1 -Target <同上> -Release 然后 .\scripts\sync-db.ps1' -ForegroundColor DarkGray
