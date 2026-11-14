<#
.SYNOPSIS
    tv-desktop 统一构建入口。

.DESCRIPTION
    一个入口构建五个桌面 Tab 插件和两个独立应用，实际工作交给 scripts/build-plugin.ps1
    和 scripts/build-app.ps1。需要 JDK 17 + Android build-tools 34（见 README「构建」）。

.PARAMETER Target
    news | live | douban | radio | stock | news-app | stock-app | all（默认 all=插件 5 个 + 独立应用 2 个）

.PARAMETER Release
    构建成功后把 APK 复制到 release/。

.EXAMPLE
    .\build.ps1                      # 全部构建
    .\build.ps1 -Target news         # 只构建新闻插件
    .\build.ps1 -Target all -Release # 全部构建并复制到 release/
#>
param(
    [ValidateSet('all', 'news', 'live', 'douban', 'radio', 'stock', 'news-app', 'stock-app')]
    [string[]]$Target = @('all'),
    [switch]$Release
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$repo = $PSScriptRoot
$plugins = @('news', 'live', 'douban', 'radio', 'stock')
$apps = @{
    'news-app'  = @{ Project = 'app-news'; Plugin = 'plugin-news'; Output = 'news-app.apk' }
    'stock-app' = @{ Project = 'app-stock'; Plugin = 'plugin-stock'; Output = 'stock-app.apk' }
}

$requested = @()
foreach ($t in $Target) {
    if ($t -eq 'all') {
        $requested += $plugins
        $requested += @('news-app', 'stock-app')
    } else {
        $requested += $t
    }
}
# 去重并保持顺序
$seen = @{}
$list = @()
foreach ($t in $requested) { if (-not $seen.ContainsKey($t)) { $seen[$t] = $true; $list += $t } }

$failed = @()
foreach ($t in $list) {
    Write-Host ''
    Write-Host "########## $t ##########"
    try {
        if ($plugins -contains $t) {
            $params = @{ Project = "plugin-$t" }
            if ($Release) { $params['Release'] = $true }
            & (Join-Path $repo 'scripts\build-plugin.ps1') @params
        } elseif ($apps.ContainsKey($t)) {
            $a = $apps[$t]
            $params = @{ Project = $a.Project; Plugin = $a.Plugin; OutputName = $a.Output }
            if ($Release) { $params['Release'] = $true }
            & (Join-Path $repo 'scripts\build-app.ps1') @params
        } else {
            throw "未知目标：$t"
        }
    } catch {
        Write-Host ("!! {0} 构建失败：{1}" -f $t, $_.Exception.Message) -ForegroundColor Red
        $failed += $t
    }
}

Write-Host ''
if ($failed.Count -gt 0) {
    Write-Host ("失败：{0}" -f ($failed -join ', ')) -ForegroundColor Red
    exit 1
}
Write-Host ("完成：{0}" -f ($list -join ', ')) -ForegroundColor Green
