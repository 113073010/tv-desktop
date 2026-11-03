@echo off
chcp 65001 >nul
setlocal
REM ============================================================
REM  五 TAB 恢复脚本（Windows / PC 端）
REM  用法：双击本文件，或 restore.bat 192.168.1.10:5555
REM  前提：adb 可用、电视已 root、和电视在同一网段
REM ============================================================

set "TV=%~1"
if "%TV%"=="" set /p "TV=电视 IP [直接回车用 192.168.1.10:5555]: "
if "%TV%"=="" set "TV=192.168.1.10:5555"

set "ADB=adb"
if exist "%~dp0adb.exe" set "ADB=%~dp0adb.exe"

echo.
echo  恢复 新闻 / 直播 / 豆瓣荐片 / 电台 / 股票
echo  目标电视: %TV%
echo.

REM 先核对数据库模板和 APK，不匹配就别推上去
where python >nul 2>&1
if errorlevel 1 (
  echo [提示] 未找到 python，跳过数据库一致性检查
) else (
  python "%~dp0check-db.py"
  if errorlevel 1 (
    echo.
    echo [错误] release\launcher_new.db 与 APK 不一致。
    echo        先执行: python release\check-db.py --fix
    echo.
    pause
    exit /b 1
  )
)

"%ADB%" connect %TV% >nul 2>&1
"%ADB%" devices | findstr /r "device$" >nul
if errorlevel 1 (
  echo [错误] 没连上电视。检查 IP / 网络 / 电视是否开启 ADB 调试。
  pause
  exit /b 1
)

echo [1/3] 验证 root ...
"%ADB%" shell su -c id
echo       ^(没有看到 uid=0 的话，去电视上点 SuperSU 的「允许」^)
pause

echo [2/3] 推送文件 ...
"%ADB%" push "%~dp0." /data/local/tmp/tvrestore || goto :err

echo [3/3] 执行恢复 ...
"%ADB%" shell su -c "sh /data/local/tmp/tvrestore/restore.sh" || goto :err

echo.
echo  ============================================
echo   完成。桌面顶部应出现 新闻 / 直播 / 豆瓣荐片 / 电台 / 股票
echo   直播无画面：确认已装 TVBox，频道表在 /sdcard/DIYP影音/
echo  ============================================
pause
exit /b 0

:err
echo.
echo [失败] 查看上面的输出。
pause
exit /b 1
