#!/system/bin/sh
# ============================================================
#  清理「开发调试」留在电视上的垃圾（在电视上以 root 执行）
#
#  和 clear.sh 的区别：
#    clear.sh    = 卸载产品（删插件 APK、删 TAB 记录）
#    clean-dev.sh = 只扫开发过程产生的临时物，插件与用户数据一律不动
#
#  用法（PC 侧）：
#    adb push release/clean-dev.sh /data/local/tmp/
#    adb shell su -c 'sh /data/local/tmp/clean-dev.sh'          # 默认
#    adb shell su -c 'sh /data/local/tmp/clean-dev.sh --all'    # 连数据库快照一起删
#
#  清理内容：
#    · /data/local/tmp 下的调试文件（旧 APK、DB 快照、部署脚本、dalvik 缓存）
#    · 桌面 cache 里的 ijkdex / tts 临时文件（用时自动重建）
#    · /sdcard 根目录的调试日志、截图、uiautomator dump、plugin_dev 副本
#    · --all：/sdcard 上的 launcher 数据库快照
#
#  不会动：/sdcard/DIYP影音（直播频道表）、/sdcard/tvdesk/自选.txt（股票自选）、
#          插件 APK、plugins_data、SuperSU。
#
#  说明：这台电视的 shell 只有 toolbox，没有 awk / sed / wc，
#  下面只用 ls / du / grep / test 和 shell 内建。
# ============================================================

L=/data/data/com.stv.launcher
ALL=0
[ "$1" = "--all" ] && ALL=1

# 文件大小（字节），不依赖 awk
fsize() { set -- $(ls -l "$1" 2>/dev/null); echo "$4"; }
# 目录占用（KB）
dusage() { du -k "$1" 2>/dev/null | { read k rest; echo "$k"; }; }

echo "======================================"
echo " 清理开发调试残留"
echo "======================================"
echo "清理前:"; df /data

# --- 1. /data/local/tmp
tmpn=$(ls /data/local/tmp 2>/dev/null | grep -c .)
if [ "$tmpn" -gt 0 ]; then
    echo "[1/4] /data/local/tmp：$tmpn 项，$(dusage /data/local/tmp)KB"
    rm -rf /data/local/tmp/*
    rm -rf /data/local/tmp/.[!.]* 2>/dev/null
    echo "      已清空"
else
    echo "[1/4] /data/local/tmp：已经是空的"
fi

# --- 2. 桌面私有缓存
echo "[2/4] 桌面缓存"
for c in ijkdex tts.mp3 tts_0.mp3 tts_1.mp3; do
    if [ -e "$L/cache/$c" ]; then
        k=$(dusage "$L/cache/$c")
        rm -rf "$L/cache/$c" && echo "      已删 cache/$c（${k}KB）"
    fi
done

# --- 3. /sdcard 根目录调试残留
echo "[3/4] /sdcard 调试残留"
for f in tvdesk.log tvdesk_stock.log tvdesk_douban.log \
         ui.xml packages.xml LauncherCommon.bak.xml \
         _d9.png dangbei.png dangbei2.png; do
    if [ -f "/sdcard/$f" ]; then
        k=$(fsize "/sdcard/$f")
        rm -f "/sdcard/$f" && echo "      已删 /sdcard/$f（${k} bytes）"
    fi
done
# 通配符必须用绝对路径，否则在 su -c 的当前目录下不会展开
for f in /sdcard/tv_now*.png; do
    [ -f "$f" ] || continue
    k=$(fsize "$f")
    rm -f "$f" && echo "      已删 $f（${k} bytes）"
done
for d in plugin_dev plugin backups; do
    if [ -d "/sdcard/$d" ]; then
        rm -rf "/sdcard/$d" && echo "      已删 /sdcard/$d/"
    fi
done

# --- 4. 可选：数据库快照
if [ $ALL -eq 1 ]; then
    echo "[4/4] 数据库快照"
    for f in /sdcard/launcher_new.db /sdcard/launcher_new.before_restore.db \
             /sdcard/launcher_backup_*.db /sdcard/db_before_swap.db /sdcard/db_now.db; do
        [ -f "$f" ] || continue
        rm -f "$f" && echo "      已删 $f"
    done
else
    echo "[4/4] 数据库快照保留（加 --all 可删）"
fi

sync
echo "======================================"
echo "清理后:"; df /data
echo "插件未改动："
for n in news live douban radio stock; do
    if [ -f "$L/files/plugins/$n.apk" ]; then
        echo "  $n.apk  $(fsize "$L/files/plugins/$n.apk") bytes"
    else
        echo "  $n.apk  不存在"
    fi
done
echo "完成"
