#!/system/bin/sh
# ============================================================
#  彻底卸载三 TAB（在电视上以 root 执行）
#
#  用法（PC 侧）：
#    adb push release /data/local/tmp/tvrestore
#    adb shell su -c 'sh /data/local/tmp/tvrestore/clear.sh'
#    adb shell su -c 'sh /data/local/tmp/tvrestore/clear.sh --all'
#
#  默认清掉（装机过程产生的全部痕迹）：
#    · 三个插件 APK
#    · plugins_data 插件数据目录
#    · 插件偏好文件 tvdesk_news.xml
#    · 桌面 cache 里的插件缓存（ijkdex / tts 临时文件）
#    · /sdcard/plugin_dev 调试副本
#    · /sdcard/tvdesk.log 调试日志
#    · 桌面数据库里的三个 TAB 记录（替换为 launcher_clean.db）
#
#  加 --all 额外删除：
#    · /sdcard/DIYP影音 频道表
#    · /sdcard/launcher_new.before_*.db 备份
# ============================================================

DIR=$(dirname "$0")
L=/data/data/com.stv.launcher
P=$L/files/plugins
DB=$L/databases/launcher_new.db
ALL=0
[ "$1" = "--all" ] && ALL=1

echo "======================================"
echo " 彻底卸载 新闻 / 直播 / 豆瓣荐片"
echo "======================================"
[ $ALL -eq 1 ] && echo " 模式：全部（含频道表与备份）"

if [ ! -d "$L" ]; then
    echo "找不到乐视桌面数据目录 $L，中止"
    exit 3
fi

echo "[0/7] 备份当前数据库"
cp -f "$DB" /sdcard/launcher_new.before_clear.db 2>/dev/null && \
    echo "     -> /sdcard/launcher_new.before_clear.db"

echo "[1/7] 删除插件 APK"
for n in news live douban; do
    if [ -f "$P/$n.apk" ]; then
        rm -f "$P/$n.apk" && echo "     已删 $n.apk"
    else
        echo "     $n.apk 不存在"
    fi
done

echo "[2/7] 删除插件数据目录"
for n in news live douban; do
    d="$L/plugins_data/com.dsh.tvdesk.$n"
    if [ -d "$d" ]; then
        rm -rf "$d" && echo "     已删 plugins_data/com.dsh.tvdesk.$n"
    fi
done

echo "[3/7] 删除插件偏好"
for f in tvdesk_news.xml tvdesk_live.xml tvdesk_douban.xml; do
    if [ -f "$L/shared_prefs/$f" ]; then
        rm -f "$L/shared_prefs/$f" && echo "     已删 shared_prefs/$f"
    fi
done

echo "[4/7] 清理插件缓存"
for c in ijkdex tts.mp3 tts_0.mp3 tts_1.mp3; do
    t="$L/cache/$c"
    if [ -e "$t" ]; then
        rm -rf "$t" && echo "     已删 cache/$c"
    fi
done

echo "[5/7] 清理 /sdcard 调试文件"
if [ -d /sdcard/plugin_dev ]; then
    rm -rf /sdcard/plugin_dev && echo "     已删 /sdcard/plugin_dev"
fi
if [ -f /sdcard/tvdesk.log ]; then
    rm -f /sdcard/tvdesk.log && echo "     已删 /sdcard/tvdesk.log"
fi

echo "[6/7] 清除桌面 TAB 记录"
if [ -f "$DIR/launcher_clean.db" ]; then
    rm -f "$DB-journal"
    cp -f "$DIR/launcher_clean.db" "$DB" || { echo "     数据库替换失败"; exit 5; }
    chown system:system "$DB" 2>/dev/null
    chmod 660 "$DB"
    restorecon "$DB" 2>/dev/null
    echo "     已替换为干净数据库（三个 TAB 记录已删除）"
else
    echo "     缺 launcher_clean.db，跳过（插件已删，桌面加载不到）"
fi

if [ $ALL -eq 1 ]; then
    echo "[7/7] 删除频道表与备份"
    rm -rf "/sdcard/DIYP影音" && echo "     已删 /sdcard/DIYP影音"
    rm -f /sdcard/launcher_new.before_restore.db
    rm -f /sdcard/launcher_new.before_clear.db
    echo "     已删数据库备份"
else
    echo "[7/7] 保留频道表与备份（加 --all 可一并删除）"
fi

echo "重启桌面…"
sync
am force-stop com.stv.launcher
sleep 2
am start -n com.stv.launcher/.activity.Launcher >/dev/null 2>&1

echo "======================================"
echo " 残留检查"
echo "======================================"
left=0
for n in news live douban; do
    if [ -f "$P/$n.apk" ]; then echo " ! $n.apk 仍存在"; left=$((left+1)); fi
done
if [ -d "$L/plugins_data/com.dsh.tvdesk.news" ]; then echo " ! plugins_data 仍有残留"; left=$((left+1)); fi
if [ -f "$L/shared_prefs/tvdesk_news.xml" ]; then echo " ! tvdesk_news.xml 仍存在"; left=$((left+1)); fi
if [ -f /sdcard/tvdesk.log ]; then echo " ! tvdesk.log 仍存在"; left=$((left+1)); fi

if [ $left -eq 0 ]; then
    echo " OK 插件、数据、偏好、日志、TAB 记录 —— 全部清除"
else
    echo " 还有 $left 处残留（见上）"
fi
if [ -d /sdcard/DIYP影音 ]; then
    echo " 频道表: 保留（/sdcard/DIYP影音）"
else
    echo " 频道表: 已删除"
fi
echo "完成。桌面应只剩 信号源 / 应用"
exit 0
