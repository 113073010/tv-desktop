#!/system/bin/sh
# ============================================================
#  乐视桌面三 TAB 恢复脚本（在电视上以 root 执行）
#
#  用法（PC 侧）：
#    adb push release/ /data/local/tmp/tvrestore
#    adb shell su -c 'sh /data/local/tmp/tvrestore/restore.sh'
#
#  目录要求：脚本同目录下需有
#    news.apk  live.apk  douban.apk  launcher_new.db
# ============================================================

DIR=$(dirname "$0")
L=/data/data/com.stv.launcher
P=$L/files/plugins
DB=$L/databases/launcher_new.db

echo "== 检查文件 =="
for f in news.apk live.apk douban.apk launcher_new.db; do
    if [ ! -f "$DIR/$f" ]; then
        echo "  缺少 $f，中止"
        exit 2
    fi
done
echo "  ok"

if [ ! -d "$L" ]; then
    echo "找不到乐视桌面数据目录 $L，可能不是乐视电视，中止"
    exit 3
fi

echo "== 备份原数据库 =="
cp -f "$DB" /sdcard/launcher_new.before_restore.db 2>/dev/null && \
    echo "  已备份到 /sdcard/launcher_new.before_restore.db"

echo "== 安装插件 =="
mkdir -p "$P"
for n in news live douban; do
    cp -f "$DIR/$n.apk" "$P/$n.apk" || { echo "  复制 $n.apk 失败"; exit 4; }
    chown system:system "$P/$n.apk" 2>/dev/null
    chmod 600 "$P/$n.apk"
    rm -rf "$L/plugins_data/com.dsh.tvdesk.$n"
    echo "  $n.apk  $(ls -l "$P/$n.apk" | awk '{print $5}') bytes"
done

echo "== 替换数据库 =="
rm -f "$DB-journal"
cp -f "$DIR/launcher_new.db" "$DB" || { echo "  数据库替换失败"; exit 5; }
chown system:system "$DB" 2>/dev/null
chmod 660 "$DB"
restorecon "$DB" 2>/dev/null

echo "== 重启桌面 =="
sync
am force-stop com.stv.launcher
sleep 2
am start -n com.stv.launcher/.activity.Launcher >/dev/null 2>&1

echo "== 校验 =="
for n in news live douban; do
    if [ -f "$P/$n.apk" ]; then echo "  $n.apk 就位"; else echo "  $n.apk 缺失"; fi
done
[ -f "$DB" ] && echo "  launcher_new.db 就位"

echo "完成。桌面应已出现 新闻 / 直播 / 豆瓣荐片 三个 TAB"
exit 0
