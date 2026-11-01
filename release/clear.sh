#!/system/bin/sh
# ============================================================
#  完全清除三 TAB 配置（在电视上以 root 执行）
#
#  用法（PC 侧）：
#    adb push release /data/local/tmp/tvrestore
#    adb shell su -c 'sh /data/local/tmp/tvrestore/clear.sh'
#
#  默认清除内容：
#    · 三个插件 APK
#    · plugins_data 缓存
#    · 桌面数据库里的三个 TAB 记录（替换为 launcher_clean.db）
#
#  加 --all 额外删除：
#    · /sdcard/DIYP影音 频道表目录
#    · /sdcard/launcher_new.before_restore.db 等备份
# ============================================================

DIR=$(dirname "$0")
L=/data/data/com.stv.launcher
P=$L/files/plugins
DB=$L/databases/launcher_new.db
ALL=0
[ "$1" = "--all" ] && ALL=1

echo "== 清除配置 =="
[ $ALL -eq 1 ] && echo "  模式：全部（含频道源与备份）"

if [ ! -d "$L" ]; then
    echo "找不到乐视桌面数据目录 $L，中止"
    exit 3
fi

echo "== 1. 备份当前数据库 =="
cp -f "$DB" /sdcard/launcher_new.before_clear.db 2>/dev/null && \
    echo "  已备份到 /sdcard/launcher_new.before_clear.db"

echo "== 2. 删除插件 =="
for n in news live douban; do
    if [ -f "$P/$n.apk" ]; then
        rm -f "$P/$n.apk" && echo "  已删除 $n.apk"
    else
        echo "  $n.apk 本就不存在"
    fi
    rm -rf "$L/plugins_data/com.dsh.tvdesk.$n"
done

echo "== 3. 清理桌面数据库记录 =="
if [ -f "$DIR/launcher_clean.db" ]; then
    rm -f "$DB-journal"
    cp -f "$DIR/launcher_clean.db" "$DB" || { echo "  数据库替换失败"; exit 5; }
    chown system:system "$DB" 2>/dev/null
    chmod 660 "$DB"
    restorecon "$DB" 2>/dev/null
    echo "  已替换为干净数据库（三个 TAB 记录已删除）"
else
    echo "  缺少 launcher_clean.db，改为只隐藏 TAB"
    # 没有 sqlite3 时无法改库，退化为“插件已删=桌面加载不到”
fi

if [ $ALL -eq 1 ]; then
    echo "== 4. 删除频道源与备份 =="
    rm -rf "/sdcard/DIYP影音" && echo "  已删除 /sdcard/DIYP影音"
    rm -f /sdcard/launcher_new.before_restore.db /sdcard/launcher_new.before_clear.db
    echo "  已删除遗留备份"
fi

echo "== 5. 重启桌面 =="
sync
am force-stop com.stv.launcher
sleep 2
am start -n com.stv.launcher/.activity.Launcher >/dev/null 2>&1

echo "== 校验 =="
left=0
for n in news live douban; do
    [ -f "$P/$n.apk" ] && { echo "  $n.apk 仍存在"; left=$((left+1)); }
done
[ $left -eq 0 ] && echo "  三个插件均已删除"
[ -d "/sdcard/DIYP影音" ] && echo "  频道源目录仍存在" || echo "  频道源目录不存在"

echo "完成。桌面应只剩 信号源 / 应用"
exit 0
