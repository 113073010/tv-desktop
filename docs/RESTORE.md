# 恢复说明（给 Agent / 命令行用）

目标：把 **新闻 / 直播 / 豆瓣荐片 / 电台 / 股票** 五个 TAB 恢复到一台已 root 的乐视电视上。

整个恢复只做三件事：

1. 把五个插件 APK 放进 `/data/data/com.stv.launcher/files/plugins/`
2. 替换 `/data/data/com.stv.launcher/databases/launcher_new.db`
3. 重启桌面

> 直播 TAB 额外需要 **TVBox**（`com.github.tvbox.osc.tk`）提供播放内核，
> 它不是本仓库的产物，需自行安装；没有它也能恢复，只是 rtsp/4K 播不了。

---

## 刷机之后能不能直接恢复？

**能，但有一个前提：刷回的固件版本要和原来一致（或至少 `com.stv.launcher` 版本一致）。**

原因是插件依赖桌面向插件暴露的两个包：

```
com.stv.launcher.sdk
android.support.v4
```

插件清单里声明了 `minDependentVersion=1.0.0`。桌面的插件 SDK 版本低于它，插件不会被加载；
`SCREEN_INFO` 表结构如果在固件升级时改过，直接覆盖 `launcher_new.db` 可能导致桌面崩溃甚至进不去。

所以刷机后**先跑 `check.sh`**，它会报告：

- 有没有 root
- 桌面包名 / 版本
- `SCREEN_INFO` 表结构是否与本仓库模板一致
- 插件目录是否可写、现有 APK 情况

`check.sh` 报「结构一致」才用一键脚本；否则走下面的「方式 B：手动登记」。

---

## 方式 A：命令行一键（推荐给 Agent）

前置：能和电视通信的 adb（示例 IP `192.168.1.10`，换成实际地址）、电视已 root。

```bash
# 1) 连接
adb connect 192.168.1.10:5555
adb devices          # 应看到 device

# 2) 先体检（重要）
adb push release/ /data/local/tmp/tvrestore
adb shell su -c 'sh /data/local/tmp/tvrestore/check.sh'

# 3) 体检通过后恢复
adb shell su -c 'sh /data/local/tmp/tvrestore/restore.sh'

# 4) 等桌面重启，顶部应出现五个 TAB
```

`restore.sh` 会自动：

- 把原数据库备份到 `/sdcard/launcher_new.before_restore.db`
- 停掉桌面进程（不停的话系统会立刻拉起旧进程并占用刚替换的 APK）
- 原子替换 5 个 APK，并清理 `plugins_data` 缓存
- 用 `cat >` 覆盖数据库（避免 `cp` 触发的 `SQLITE_READONLY_DBMOVED`）
- 重启桌面并逐项校验

---

## 方式 B：手动登记（固件版本不一致时）

不要覆盖数据库，只放 APK，再往 `SCREEN_INFO` 里补记录。

```bash
adb shell su -c 'mkdir -p /data/data/com.stv.launcher/files/plugins'
for n in news live douban radio stock; do
  adb push release/$n.apk /data/local/tmp/$n.apk
  adb shell su -c "cp /data/local/tmp/$n.apk /data/data/com.stv.launcher/files/plugins/$n.apk"
  adb shell su -c "chown system:system /data/data/com.stv.launcher/files/plugins/$n.apk"
  adb shell su -c "chmod 600 /data/data/com.stv.launcher/files/plugins/$n.apk"
done
```

然后对每个插件补一条 `SCREEN_INFO` 记录：

```sql
INSERT INTO SCREEN_INFO
  (TAG, NAME, PACKAGE_NAME, VERSION_CODE, SHOW_ON_TAB, SCREEN_ORDER,
   PLUGIN_STATE, SORTABLE, REMOVABLE, PLUGIN_PATH, FILE_SIZE, MD5)
VALUES
  (0, '电台', 'com.dsh.tvdesk.radio', 26, 1, 4,
   1, 1, 1, '/data/data/com.stv.launcher/files/plugins/radio.apk', 20879, '<APK 的 MD5>');
```

> **MD5 与 FILE_SIZE 必须与实际 APK 完全一致**，否则桌面会认为插件没变化，
> 继续用旧缓存（或者干脆不加载）。这是最容易踩的坑。

`tools-install_tab.sh` 是早期的手工登记脚本，可作参考。

---

## 各插件版本对照

| 插件 | 包名 | versionCode | APK |
|---|---|---|---|
| 新闻 | `com.dsh.tvdesk.news` | 50 | `release/news.apk` |
| 直播 | `com.dsh.tvdesk.live` | 27 | `release/live.apk` |
| 豆瓣荐片 | `com.dsh.tvdesk.douban` | 46 | `release/douban.apk` |
| 电台 | `com.dsh.tvdesk.radio` | 26 | `release/radio.apk` |
| 股票 | `com.dsh.tvdesk.stock` | 9 | `release/stock.apk` |

数据库模板里这五条都已登记好，`MD5` / `FILE_SIZE` / `PLUGIN_PATH` 与实际 APK 对应。

---

## 校验

```bash
adb shell su -c 'ls -l /data/data/com.stv.launcher/files/plugins/'
adb shell su -c 'ls -l /data/data/com.stv.launcher/databases/launcher_new.db'
```

期望：五个 APK 存在（600 system:system），数据库 660。

`SCREEN_INFO` 里应有 新闻 / 直播 / 豆瓣荐片 / 电台 / 股票 五行且 `SHOW_ON_TAB=1`。

> 电视上没有 `sqlite3` 时，用 `adb pull` 把数据库拉到 PC 上读。

---

## 卸载 / 回退

```bash
adb shell su -c 'sh /data/local/tmp/tvrestore/clear.sh'
```

`clear.sh` 会清掉 5 个插件 APK、`plugins_data` 缓存和对应日志，并把 `SCREEN_INFO` 里这五条记录一并删掉（替换成 `release/launcher_clean.db`，只留「首页 / 应用」），原库备份到 `/sdcard/launcher_new.before_clear.db`。桌面不会留下指向已删 APK 的空 TAB。

如果恢复后桌面起不来，把备份推回去：

```bash
adb shell su -c 'cat /sdcard/launcher_new.before_restore.db > /data/data/com.stv.launcher/databases/launcher_new.db'
adb shell su -c 'rm -f /data/data/com.stv.launcher/databases/launcher_new.db-journal'
adb shell am force-stop com.stv.launcher
adb shell am start -n com.stv.launcher/.activity.Launcher
```

---

## 开发调试残留

反复 `adb push` + 换 APK 会在电视上堆出一批临时物（最狠的是 `/data/local/tmp`：旧插件 APK、28MB 的恢复工具副本、上百个 launcher DB 快照、dalvik 缓存）。它们不影响使用，但白占 `/data`。清一次：

```bash
adb push release/clean-dev.sh /data/local/tmp/
adb shell su -c 'sh /data/local/tmp/clean-dev.sh'
```

默认删 `/data/local/tmp`、桌面 `cache/ijkdex`、`/sdcard` 根目录的 `tvdesk*.log` / 调试截图 / `ui.xml` / `plugin_dev`；加 `--all` 连数据库快照一起删。插件 APK、`plugins_data`、`/sdcard/DIYP影音`、`/sdcard/tvdesk/自选.txt` 都不会动。

---

## 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| 替换 APK 后界面没变化 | 桌面进程没杀掉，还在用旧 dex | `kill -9` 桌面进程后再替换 |
| 替换 APK 后还是旧版 | ① 桌面把插件 dex 缓存在 `files/plugins_data/<包名>/dex/`，只换 APK 不删它会继续跑旧代码；② `SCREEN_INFO` 里 MD5 / 大小没更新 | 先 `rm -rf files/plugins_data/<包名>`（`restore.sh` 已自动做），再同步 MD5 与 FILE_SIZE |
| 桌面一直「正在获取…」 | 插件内部状态没刷新 | 分类条上按「确定」刷新 |
| 桌面进不去 | DB 结构与固件不匹配 | 用备份回退，改走方式 B |
| 直播播不了 rtsp/4K | 没装 TVBox | 装 `com.github.tvbox.osc.tk` |
| 基金/国际曲线空白 | 历史接口在部分网络不可达 | 属已知问题，其余分类正常 |
