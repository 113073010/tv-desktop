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
#    · 系统 / 桌面版本
#    · SCREEN_INFO 表结构能不能用仓库里的数据库模板
#    · 数据库模板里的 FILE_SIZE / MD5 是否和 release/*.apk 对得上
# ============================================================

case "$0" in */*) DIR=${0%/*} ;; *) DIR=. ;; esac
L=/data/data/com.stv.launcher
P=$L/files/plugins
DB=$L/databases/launcher_new.db
TEMPLATE="$DIR/launcher_new.db"
NAMES="news live douban radio stock"

# 文件大小（字节），不依赖 awk（这台电视的 shell 只有 toolbox）
fsize() { set -- $(ls -l "$1" 2>/dev/null); echo "$4"; }

# MD5；电视上没有独立的 md5sum，只有 busybox 里的
apkmd5() { busybox md5sum "$1" 2>/dev/null | { read m rest; echo "$m"; }; }

HAVE_BB=0
busybox md5sum "$0" >/dev/null 2>&1 && HAVE_BB=1

echo "=================================================="
echo " 乐视桌面五 TAB 恢复前体检"
echo "=================================================="

echo ""
echo "[1/7] root 权限"
case "$(id)" in
    uid=0*) echo "  OK  已是 root" ;;
    *)      echo "  失败  当前不是 root，请先 su（电视上会弹 SuperSU，点授权）" ;;
esac

echo ""
echo "[2/7] 系统与桌面"
echo "  固件    $(getprop ro.build.display.id)"
echo "  Android $(getprop ro.build.version.release)  (SDK $(getprop ro.build.version.sdk))"
VN=$(dumpsys package com.stv.launcher 2>/dev/null | busybox grep -m1 versionName)
VC=$(dumpsys package com.stv.launcher 2>/dev/null | busybox grep -m1 versionCode)
[ -n "$VN" ] && echo "  桌面    $VN"
[ -n "$VC" ] && echo "          $VC"
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
echo "[3/7] 现有插件"
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
echo "[4/7] 数据库"
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
echo "[5/7] 模板完整性"
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

SCHEMA_BAD=0
if [ $HAVE_BB -eq 0 ]; then
    echo "  提示  busybox 不可用，跳过表结构检查"
else
    for k in SCREEN_INFO PLUGIN_PATH SCREEN_TAG FILE_SIZE MD5 POSITION OFFLINE_SHOT; do
        if busybox grep -a -q "$k" "$TEMPLATE"; then
            echo "  OK  表结构含 $k"
        else
            echo "  失败  表结构缺 $k"
            SCHEMA_BAD=1
        fi
    done
fi
if [ $SCHEMA_BAD -eq 1 ]; then
    echo "  模板与当前固件的 SCREEN_INFO 结构不一致，不要覆盖数据库，改走方式 B"
    exit 4
fi

echo ""
echo "[6/7] 模板数据库与 APK 是否一致"
MISMATCH=0
if [ $HAVE_BB -eq 0 ]; then
    echo "  提示  busybox 不可用，跳过 MD5 校验"
else
    for n in $NAMES; do
        apk="$DIR/$n.apk"
        md5=$(apkmd5 "$apk")
        sz=$(fsize "$apk")
        bad=""
        [ -n "$md5" ] || bad="$bad MD5算不出"
        [ -n "$md5" ] && ! busybox grep -a -i -q "$md5" "$TEMPLATE" && bad="$bad MD5"
        busybox grep -a -q "$sz" "$TEMPLATE" || bad="$bad 大小"
        busybox grep -a -q "com.dsh.tvdesk.$n" "$TEMPLATE" || bad="$bad 包名"
        if [ -z "$bad" ]; then
            echo "  OK  $n.apk  $sz bytes  $md5"
        else
            echo "  不一致  $n.apk  缺:$bad"
            MISMATCH=1
        fi
    done
fi

echo ""
echo "=================================================="
echo " 结论"
echo "=================================================="
if [ $SCHEMA_BAD -eq 1 ]; then
    echo " 表结构不一致，改走 docs/RESTORE.md 的『方式 B：手动登记』。"
elif [ $MISMATCH -eq 1 ]; then
    echo " 数据库模板和 APK 对不上，不要直接恢复。"
    echo " 在 PC 上执行：python release/check-db.py --fix"
    echo " 然后再把 release/ 推上来重跑 check.sh。"
else
    echo " 可以先执行：sh $DIR/restore.sh"
fi
echo ""
echo " 注意：如果这次刷机换了新固件（不是原来的系统版本），"
echo " 建议改用 docs/RESTORE.md 里的『方式 B：手动登记』，"
echo " 不要直接覆盖数据库，避免桌面起不来。"
echo ""
exit 0
