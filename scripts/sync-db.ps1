<#
.SYNOPSIS
    同步 release/ 里的 APK 元数据到 launcher_new.db（桌面判断插件是否更新只看 MD5/大小）。

.DESCRIPTION
    对每个 release/<name>.apk 计算 MD5 和字节数，写回 launcher_new.db 的 SCREEN_INFO。
    同时重建 release/SHA256SUMS.txt。等价于 check-db.py --fix，但不需要 Python。

    需要 sqlite3.exe：默认找 tools/platform-tools/sqlite3.exe，或用 -Sqlite 指定。

    两个已知坑，脚本会自动绕开：
      · sqlite3 命令行按系统 ANSI 接收参数，路径里有中文会「unable to open database」
        → 平时直接在 release/ 里用相对文件名调用
      · 某些环境（受限账号 / 沙箱）不允许 sqlite3 在仓库目录里建 journal
        → 探测到写不进去就把 launcher_new.db 复制到一个可写的纯英文目录操作，完事再复制回来

.PARAMETER CheckOnly
    只报告差异，不写数据库。

.PARAMETER Sqlite
    sqlite3.exe 的路径。

.PARAMETER WorkDir
    手工指定中转目录（ASCII 路径）；只在默认候选都不行时需要。

.EXAMPLE
    .\scripts\sync-db.ps1 -CheckOnly
    .\scripts\sync-db.ps1
#>
param(
    [switch]$CheckOnly,
    [string]$Sqlite,
    [string]$WorkDir
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$repo = Split-Path $PSScriptRoot -Parent
$rel = Join-Path $repo 'release'
$dbName = 'launcher_new.db'
if (-not (Test-Path (Join-Path $rel $dbName))) { throw "找不到数据库：$rel\$dbName" }

if (-not $Sqlite) {
    foreach ($c in @(
            (Join-Path $repo 'tools\platform-tools\sqlite3.exe'),
            (Join-Path (Split-Path $repo -Parent) 'tools\platform-tools\sqlite3.exe'))) {
        if (Test-Path $c) { $Sqlite = $c; break }
    }
}
if (-not $Sqlite -or -not (Test-Path $Sqlite)) {
    throw "找不到 sqlite3.exe，请用 -Sqlite 指定（Android platform-tools 里带的那个即可）"
}

function Invoke-Sqlite([string]$workdir, [string]$db, [string]$sql) {
    $psi = New-Object Diagnostics.ProcessStartInfo
    $psi.FileName = $Sqlite
    $psi.Arguments = '{0} "{1}"' -f $db, $sql
    $psi.WorkingDirectory = $workdir
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $p = [Diagnostics.Process]::Start($psi)
    $out = $p.StandardOutput.ReadToEnd()
    $err = $p.StandardError.ReadToEnd()
    $p.WaitForExit()
    if ($p.ExitCode -ne 0) { throw ("sqlite3 失败（{0}）：{1}" -f $p.ExitCode, $err.Trim()) }
    return $out.Trim()
}

function Test-AsciiPath([string]$p) { return ($p -match '^[\x20-\x7E]+$') }

# ---------- 1. 先在原地试写 ----------
$workDbDir = $rel
$workDbName = $dbName
$mirrorBack = $false
try {
    Invoke-Sqlite $rel $dbName 'UPDATE SCREEN_INFO SET FILE_SIZE=FILE_SIZE;' | Out-Null
    Write-Host '直接写 release/launcher_new.db' -ForegroundColor DarkGray
} catch {
    Write-Host '原地写不进去，改用中转目录' -ForegroundColor DarkGray

    # ---------- 2. 找一个 sqlite3 确实能写的纯英文目录 ----------
    $candidates = @()
    if ($WorkDir) { $candidates += $WorkDir }
    $candidates += @(
        (Join-Path $env:TEMP 'tvdesk-db'),
        (Join-Path $env:TMP 'tvdesk-db'),
        (Join-Path $env:SystemDrive 'tvdesk-db'),
        (Join-Path $env:USERPROFILE 'tvdesk-db')
    )
    $tmpDir = $null
    foreach ($c in $candidates) {
        if (-not $c -or -not (Test-AsciiPath $c)) { continue }
        try {
            New-Item -ItemType Directory -Force -Path $c | Out-Null
            $scratch = Join-Path $c 'probe.db'
            if (Test-Path $scratch) { Remove-Item $scratch -Force }
            Invoke-Sqlite $c 'probe.db' 'CREATE TABLE t(x); INSERT INTO t VALUES(1); DELETE FROM t;' | Out-Null
            Remove-Item $scratch -Force -ErrorAction SilentlyContinue
            $tmpDir = $c
            break
        } catch {
            Remove-Item (Join-Path $c 'probe.db') -Force -ErrorAction SilentlyContinue
        }
    }
    if (-not $tmpDir) {
        throw "找不到 sqlite3 能写的纯英文目录；请用 -WorkDir 指定，或用 python release/check-db.py --fix"
    }

    foreach ($f in @($dbName, "$dbName-journal", "$dbName-wal", "$dbName-shm")) {
        $p = Join-Path $tmpDir $f
        if (Test-Path $p) { Remove-Item $p -Force -ErrorAction SilentlyContinue }
    }
    Copy-Item (Join-Path $rel $dbName) (Join-Path $tmpDir $dbName) -Force
    $workDbDir = $tmpDir
    $mirrorBack = $true
    Write-Host ("中转目录：{0}" -f $tmpDir) -ForegroundColor DarkGray
}

# ---------- 3. 比对 / 更新 ----------
# 插件 APK → SCREEN_INFO 记录
$map = @(
    @{ Apk = 'news.apk'; Pkg = 'com.dsh.tvdesk.news' },
    @{ Apk = 'live.apk'; Pkg = 'com.dsh.tvdesk.live' },
    @{ Apk = 'douban.apk'; Pkg = 'com.dsh.tvdesk.douban' },
    @{ Apk = 'radio.apk'; Pkg = 'com.dsh.tvdesk.radio' },
    @{ Apk = 'stock.apk'; Pkg = 'com.dsh.tvdesk.stock' }
)

$drift = 0
foreach ($m in $map) {
    $apk = Join-Path $rel $m.Apk
    if (-not (Test-Path $apk)) {
        Write-Host ("缺失 {0}" -f $m.Apk) -ForegroundColor Yellow
        $drift++
        continue
    }

    $md5 = (Get-FileHash $apk -Algorithm MD5).Hash
    $size = (Get-Item $apk).Length
    $row = Invoke-Sqlite $workDbDir $dbName "SELECT VERSION_CODE||'|'||MD5||'|'||FILE_SIZE FROM SCREEN_INFO WHERE PACKAGE_NAME='$($m.Pkg)';"
    $parts = ($row -split '\|')
    $ok = ($parts.Count -ge 3) -and ($parts[1] -eq $md5) -and ([int]$parts[2] -eq $size)
    if ($ok) {
        Write-Host ("{0,-12} {1}  一致" -f $m.Apk, $row) -ForegroundColor DarkGray
    } else {
        Write-Host ("{0,-12} {1}  →  MD5={2} size={3}" -f $m.Apk, $row, $md5, $size) -ForegroundColor Yellow
        $drift++
        if (-not $CheckOnly) {
            Invoke-Sqlite $workDbDir $dbName "UPDATE SCREEN_INFO SET MD5='$md5', FILE_SIZE=$size WHERE PACKAGE_NAME='$($m.Pkg)';" | Out-Null
        }
    }
}

# ---------- 4. 校验 / 写回 / 重建校验和 ----------
if (-not $CheckOnly) {
    $ic = Invoke-Sqlite $workDbDir $dbName 'PRAGMA integrity_check;'
    Write-Host ("integrity_check: {0}" -f $ic)
    if ($ic -ne 'ok') { throw "数据库校验失败：$ic" }

    if ($mirrorBack) {
        foreach ($f in @("$dbName-journal", "$dbName-wal", "$dbName-shm")) {
            $p = Join-Path $workDbDir $f
            if (Test-Path $p) { Remove-Item $p -Force -ErrorAction SilentlyContinue }
        }
        Copy-Item (Join-Path $workDbDir $dbName) (Join-Path $rel $dbName) -Force
        foreach ($f in @($dbName, "$dbName-journal", "$dbName-wal", "$dbName-shm")) {
            $p = Join-Path $workDbDir $f
            if (Test-Path $p) { Remove-Item $p -Force -ErrorAction SilentlyContinue }
        }
    }

    # 重建 SHA256SUMS.txt（release/ 下所有 apk，按名字排序）
    $sums = Join-Path $rel 'SHA256SUMS.txt'
    $lines = Get-ChildItem $rel -Filter *.apk | Sort-Object Name | ForEach-Object {
        "{0}  {1}" -f (Get-FileHash $_.FullName -Algorithm SHA256).Hash, $_.Name
    }
    [IO.File]::WriteAllText($sums, (($lines -join "`n") + "`n"), (New-Object Text.UTF8Encoding $false))
    Write-Host ("已重建 {0}" -f $sums)
}

Write-Host ''
if ($drift -eq 0) {
    Write-Host '数据库与 APK 完全一致' -ForegroundColor Green
} elseif ($CheckOnly) {
    Write-Host ("{0} 项不一致（去掉 -CheckOnly 可自动同步）" -f $drift) -ForegroundColor Yellow
    exit 1
} else {
    Write-Host ("已同步 {0} 项" -f $drift) -ForegroundColor Green
}
