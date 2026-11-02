#!/system/bin/sh
# ============================================================
#  恢复前体检（在电视上以 root 执行）
#
#  用法：
#    adb push release/ /data/local/tmp/tvrestore
#    adb shell su -c 'sh /data/local/tmp/tvrestore/check.sh'
#
#  只读检查，不修改任何东西。用来判断：
#    · 有没有 root
#    · 桌面是否就位
#    · SCREEN_INFO 表结构能不能用仓库里的数据库模板
# ============================================================

case "$0" in */*) DIR=${0%/*} ;; *) DIR=. ;; esac
L=/data/data/com.stv.launcher
P=$L/files/plugins
DB=$L/databases/launcher_new.db
TEMPLATE="$DIR/launcher_new.db"
NAMES="news live douban radio stock"

# 文件大小（字节），不依赖 awk（这台电视的 shell 只有 toolbox）
fsize() { set -- $(ls -l "$1" 2>/dev/null); echo "$4"; }

echo "=================================================="
echo " 乐视桌面五 TAB 恢复前体检"
echo "=================================================="

echo ""
echo "[1/5] root 权限"
case "$(id)" in
    uid=0*) echo "  OK  已是 root" ;;
    *)      echo "  失败  当前不是 root，请先 su（电视上会弹 SuperSU，点授权）" ;;
esac

echo ""
echo "[2/5] 桌面进程"
if [ -d "$L" ]; then
    echo "  OK  桌面数据目录存在 $L"
else
    echo "  失败  找不到 $L，这可能不是乐视电视"
    exit 3
fi
PID=$(ps | grep '[c]om.stv.launcher')
if [ -n "$PID" ]; then
    echo "  OK  桌面在运行"
else
    echo "  提示  桌面当前没运行（恢复前本来就要停掉它）"
fi

echo ""
echo "[3/5] 现有插件"
if [ -d "$P" ]; then
    for n in $NAMES; do
        if [ -f "$P/$n.apk" ]; then
            echo "  $n.apk  $(fsize "$P/$n.apk") bytes"
        else
            echo "  $n.apk  （未安装）"
        fi
    done
else
    echo "  提示  插件目录不存在，恢复时会自动创建"
fi

echo ""
echo "[4/5] 数据库"
if [ -f "$DB" ]; then
    echo "  OK  现有数据库 $(fsize "$DB") bytes"
else
    echo "  提示  数据库不存在（全新刷机后第一次启动桌面会重建）"
fi
if [ -f "$TEMPLATE" ]; then
    echo "  OK  仓库模板 launcher_new.db 存在"
else
    echo "  失败  缺少 $TEMPLATE，请确认已把整个 release/ 推到 /data/local/tmp/tvrestore"
    exit 2
fi

echo ""
echo "[5/5] 检查模板完整性"
MISS=0
for n in $NAMES; do
    if [ ! -f "$DIR/$n.apk" ]; then
        echo "  缺少 $n.apk"
        MISS=1
    fi
done
if [ $MISS -eq 0 ]; then
    echo "  OK  五个 APK 都在"
else
    echo "  失败  插件文件不全，中止"
    exit 2
fi

echo ""
echo "=================================================="
echo " 结论"
echo "=================================================="
echo " 可以先执行：sh $DIR/restore.sh"
echo ""
echo " 注意：如果这次刷机换了新固件（不是原来的系统版本），"
echo " 建议改用 docs/RESTORE.md 里的『方式 B：手动登记』，"
echo " 不要直接覆盖数据库，避免桌面起不来。"
echo ""
exit 0
