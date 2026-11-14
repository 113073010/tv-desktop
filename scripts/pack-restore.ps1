<#
.SYNOPSIS
    把 release/ 打成可以整个 push 到电视上的恢复包（GitHub Release 附件）。

.DESCRIPTION
    restore.sh 要求脚本和 APK、数据库模板在同一个目录里，所以恢复包是扁平的：
      check-db.py  check.sh  douban.apk  launcher_new.db  live.apk
      news.apk  radio.apk  restore.sh  SHA256SUMS.txt  stock.apk

.PARAMETER Tag
    版本号（默认取 git describe 最近的 tag），产物名 tv-desktop-<Tag>-restore.zip。

.PARAMETER Output
    直接指定 zip 输出路径。

.EXAMPLE
    .\scripts\pack-restore.ps1
    .\scripts\pack-restore.ps1 -Tag v3.1.3 -Output D:\tmp\restore.zip
#>
param(
    [string]$Tag,
    [string]$Output
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$repo = Split-Path $PSScriptRoot -Parent
$rel = Join-Path $repo 'release'

if (-not $Tag) {
    try { $Tag = (& git -C $repo describe --tags --abbrev=0 2>$null) } catch { }
    if (-not $Tag) { $Tag = 'dev' }
}
if (-not $Output) {
    $dir = Join-Path $repo 'build'
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $Output = Join-Path $dir ("tv-desktop-{0}-restore.zip" -f $Tag)
}

$files = @(
    'check-db.py', 'check.sh', 'restore.sh', 'restore.bat',
    'launcher_new.db', 'SHA256SUMS.txt',
    'news.apk', 'live.apk', 'douban.apk', 'radio.apk', 'stock.apk'
)
$missing = $files | Where-Object { -not (Test-Path (Join-Path $rel $_)) }
if ($missing) { throw ("release/ 里缺文件：{0}" -f ($missing -join ', ')) }

if (Test-Path $Output) { Remove-Item $Output -Force }
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($Output, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($f in $files) {
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $zip, (Join-Path $rel $f), $f, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally {
    $zip.Dispose()
}

Get-Item $Output | ForEach-Object { "{0}  {1:N0} bytes" -f $_.FullName, $_.Length }
Write-Host ("SHA256  {0}" -f (Get-FileHash $Output -Algorithm SHA256).Hash)
