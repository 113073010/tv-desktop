#!/system/bin/sh
# ============================================================
#  乐视桌面五 TAB 恢复脚本（在电视上以 root 执行）
#
#  用法（PC 侧）：
#    adb push release/ /data/local/tmp/tvrestore
#    adb shell su -c 'sh /data/local/tmp/tvrestore/restore.sh'
#
#  目录要求：脚本同目录下需有
#    news.apk  live.apk  douban.apk  radio.apk  stock.apk  launcher_new.db
# ============================================================

DIR=$(dirname "$0")
L=/data/data/com.stv.launcher
P=$L/files/plugins
DB=$L/databases/launcher_new.db

# 插件文件名（也是包名后缀）
NAMES="news live douban radio stock"

echo "== 检查文件 =="
for n in $NAMES; do
    if [ ! -f "$DIR/$n.apk" ]; then
        echo "  缺少 $n.apk，中止"
        exit 2
    fi
done
if [ ! -f "$DIR/launcher_new.db" ]; then
    echo "  缺少 launcher_new.db，中止"
    exit 2
fi
echo "  ok"

if [ ! -d "$L" ]; then
    echo "找不到乐视桌面数据目录 $L，可能不是乐视电视，中止"
    exit 3
fi

echo "== 备份原数据库 =="
cp -f "$DB" /sdcard/launcher_new.before_restore.db 2>/dev/null && \
    echo "  已备份到 /sdcard/launcher_new.before_restore.db"

echo "== 停桌面（否则它会立刻重启并占用旧文件）=="
am force-stop com.stv.launcher 2>/dev/null
sleep 1
for p in $(ps | grep com.stv.launcher | grep -v ':' | awk '{print $2}'); do
    kill -9 "$p" 2>/dev/null
done
sleep 2

echo "== 安装插件 =="
mkdir -p "$P"
for n in $NAMES; do
    cp -f "$DIR/$n.apk" "$P/$n.apk.new" || { echo "  复制 $n.apk 失败"; exit 4; }
    chown system:system "$P/$n.apk.new" 2>/dev/null
    chmod 600 "$P/$n.apk.new"
    mv -f "$P/$n.apk.new" "$P/$n.apk"
    rm -rf "$L/plugins_data/com.dsh.tvdesk.$n"
    echo "  $n.apk  $(ls -l "$P/$n.apk" | awk '{print $5}') bytes"
done

echo "== 替换数据库 =="
rm -f "$DB-journal"
cat "$DIR/launcher_new.db" > "$DB" || { echo "  数据库替换失败"; exit 5; }
chown system:system "$DB" 2>/dev/null
chmod 660 "$DB"
restorecon "$DB" 2>/dev/null

echo "== 重启桌面 =="
sync
am start -n com.stv.launcher/.activity.Launcher >/dev/null 2>&1

echo "== 校验 =="
for n in $NAMES; do
    if [ -f "$P/$n.apk" ]; then echo "  $n.apk 就位"; else echo "  $n.apk 缺失"; fi
done
[ -f "$DB" ] && echo "  launcher_new.db 就位"

echo "完成。桌面应已出现 新闻 / 直播 / 豆瓣荐片 / 电台 / 股票 五个 TAB"
exit 0
