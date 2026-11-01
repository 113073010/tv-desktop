#!/system/bin/sh
# 用法: sh install_tab.sh <apk文件名> <pkg> <db文件名>
APK=$1
PKG=$2
DBF=$3
BB=/system/bin/busybox

echo step1-apk
$BB cp /data/local/tmp/$APK /data/data/com.stv.launcher/files/plugins/$APK
chown system:system /data/data/com.stv.launcher/files/plugins/$APK
chmod 600 /data/data/com.stv.launcher/files/plugins/$APK
$BB rm -rf /data/data/com.stv.launcher/files/plugins_data/$PKG
$BB cp /data/local/tmp/$APK /sdcard/plugin_dev/$APK
ls -l /data/data/com.stv.launcher/files/plugins/$APK

echo step2-kill
for p in /proc/[0-9]*; do
  cmd=$($BB cat "$p/cmdline" 2>/dev/null)
  case "$cmd" in
    *com.stv.launcher*)
      case "$cmd" in
        *smartconnected*) ;;
        *) kill -9 ${p#/proc/} ;;
      esac ;;
  esac
done
sleep 4

echo step3-db
DB=/data/data/com.stv.launcher/databases/launcher_new.db
$BB cp /data/local/tmp/$DBF $DB
rm -f ${DB}-journal ${DB}-wal ${DB}-shm
chown system:system $DB
chmod 660 $DB

echo step4-start
am start -n com.stv.launcher/.activity.Launcher
echo done
