#!/system/bin/sh
# ============================================================
#  乐视桌面五 TAB 恢复脚本（在电视上以 root 执行）
#
#  用法（PC 侧）：
#    adb push release/ /data/local/tmp/tvrestore
#    adb shell su -c 'sh /data/local/tmp/tvrestore/restore.sh'
#
#  如果确实要用一份与 APK 不匹配的旧数据库（不推荐）：
#    adb shell su -c 'sh /data/local/tmp/tvrestore/restore.sh --force'
#
#  目录要求：脚本同目录下需有
#    news.apk  live.apk  douban.apk  radio.apk  stock.apk  launcher_new.db
# ============================================================

case "$0" in */*) DIR=${0%/*} ;; *) DIR=. ;; esac
L=/data/data/com.stv.launcher
P=$L/files/plugins
DB=$L/databases/launcher_new.db
FORCE=0
[ "$1" = "--force" ] && FORCE=1

# 插件文件名（也是包名后缀）
NAMES="news live douban radio stock"

# 文件大小（字节），不依赖 awk（这台电视的 shell 只有 toolbox）
fsize() { set -- $(ls -l "$1" 2>/dev/null); echo "$4"; }
# MD5；电视上没有独立的 md5sum，只有 busybox 里的
apkmd5() { busybox md5sum "$1" 2>/dev/null | { read m rest; echo "$m"; }; }

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

echo "== 校验模板数据库与 APK =="
if ! busybox md5sum "$DIR/launcher_new.db" >/dev/null 2>&1; then
    echo "  提示  busybox 不可用，跳过 MD5/大小校验"
else
    BAD=0
    for n in $NAMES; do
        md5=$(apkmd5 "$DIR/$n.apk")
        sz=$(fsize "$DIR/$n.apk")
        if [ -z "$md5" ] || \
           ! busybox grep -a -i -q "$md5" "$DIR/launcher_new.db" || \
           ! busybox grep -a -q "$sz" "$DIR/launcher_new.db"; then
            echo "  不一致  $n.apk  ${sz} bytes  ${md5:-无 MD5}"
            BAD=1
        else
            echo "  ok  $n.apk  ${sz} bytes"
        fi
    done
    if [ $BAD -eq 1 ]; then
        if [ $FORCE -eq 1 ]; then
            echo "  --force：忽略不一致，继续"
        else
            echo "  模板数据库与 APK 不匹配，桌面会继续用旧 dex。"
            echo "  先在 PC 上执行 python release/check-db.py --fix，再重新 push。"
            exit 6
        fi
    fi
fi

echo "== 备份原数据库 =="
cp -f "$DB" /sdcard/launcher_new.before_restore.db 2>/dev/null && \
    echo "  已备份到 /sdcard/launcher_new.before_restore.db"

echo "== 停桌面（否则它会立刻重启并占用旧文件）=="
am force-stop com.stv.launcher 2>/dev/null
sleep 1
i=0
while [ -n "$(ps | grep '[c]om.stv.launcher')" ] && [ $i -lt 5 ]; do
    am force-stop com.stv.launcher 2>/dev/null
    sleep 1
    i=$((i+1))
done
sleep 1

echo "== 安装插件 =="
mkdir -p "$P"
for n in $NAMES; do
    cp -f "$DIR/$n.apk" "$P/$n.apk.new" || { echo "  复制 $n.apk 失败"; exit 4; }
    chown system:system "$P/$n.apk.new" 2>/dev/null
    chmod 600 "$P/$n.apk.new"
    mv -f "$P/$n.apk.new" "$P/$n.apk"
    # 插件 dex 缓存在 files/plugins_data/<包名>/dex/，不删的话换完 APK 跑的
    # 还是旧代码（桌面不比对 APK 新旧，只认这份缓存的 dex）
    rm -rf "$L/files/plugins_data/com.dsh.tvdesk.$n"
    echo "  $n.apk  $(fsize "$P/$n.apk") bytes"
done

echo "== 替换数据库 =="
# 这台机器的桌面会被系统在 1 秒内重新拉起，它一起来就把 SCREEN_INFO 读进内存页缓存；
# 要是中间留了 sleep，之后它写库（HAS_USED 之类）会把旧页刷回来，覆盖下面刚写进去的
# MD5 / 版本号。所以换库前后不留窗口：先再 kill 一次，立刻写、立刻起。
busybox killall -9 com.stv.launcher 2>/dev/null
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
