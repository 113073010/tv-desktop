@echo off
REM ============================================================
REM  三 TAB 恢复脚本（Windows / PC 端）
REM  用法：把本文件放在 release 目录里，双击运行
REM  前提：adb 可用、电视已 root、电视 IP 见下方 TV
REM ============================================================
setlocal
set TV=192.168.1.10:5555
set ADB=adb
if exist "%~dp0..\..\tools\platform-tools\adb.exe" set ADB=%~dp0..\..\tools\platform-tools\adb.exe

echo.
echo  恢复 新闻 / 直播 / 豆瓣荐片 三个 TAB
echo  目标电视: %TV%
echo.

"%ADB%" connect %TV% >nul 2>&1
"%ADB%" devices | findstr /r "device$" >nul
if errorlevel 1 (
  echo [错误] 连不上电视，检查 IP / 网络 / 电视是否开启 ADB
  pause & exit /b 1
)

echo [1/3] 验证 root ...
"%ADB%" shell su -c id
echo       ^(没看到 uid=0 的话，到电视上点 SuperSU 的「授权」^)
pause

echo [2/3] 推送文件 ...
"%ADB%" push "%~dp0." /data/local/tmp/tvrestore || goto :err

echo [3/3] 执行恢复 ...
"%ADB%" shell su -c "sh /data/local/tmp/tvrestore/restore.sh" || goto :err

echo.
echo  ============================================
echo   完成。电视上应出现 新闻 / 直播 / 豆瓣荐片
echo   直播若无画面：确认已安装 TVBox，频道源在 /sdcard/DIYP影音/
echo  ============================================
pause
exit /b 0

:err
echo.
echo [失败] 请查看上面的输出
pause
exit /b 1
