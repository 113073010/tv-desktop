# 安装说明（给 Agent / 命令行用）

目标：把 **新闻 / 直播 / 豆瓣荐片 / 电台 / 股票** 五个 TAB 恢复到一台已 root 的乐视电视上。

整个恢复只做三件事：

1. 把五个插件 APK 放进 `/data/data/com.stv.launcher/files/plugins/`
2. 替换 `/data/data/com.stv.launcher/databases/launcher_new.db`
3. 重启桌面

> **TVBox**（`com.github.tvbox.osc.tk`）不是本仓库的产物，需自行安装。
> 直播的 rtsp / 4K 播放、豆瓣荐片的点播都依赖它；没有它也能恢复，只是这两处用不了。

---

## 实测环境

本仓库当前版本在下面这台机器上跑过（详见 [DEVICE.md](DEVICE.md)）：

| 项 | 值 |
|---|---|
| 型号 | Letv X3-55 |
| 系统 | Android 5.0.1（SDK 21） |
| 固件 | `V2301RCN02C060072B08221N` |
| 桌面 | `com.stv.launcher` 1.0.2（versionCode 20170509） |
| 屏幕 | 1920×1080 / density 240 |
| root | SuperSU，`su -c id` 返回 `uid=0` |
| TVBox | `com.github.tvbox.osc.tk` 已安装 |

不同固件不一定完全一样，刷过机的先按下一节跑 `check.sh`。

---

## 刷机之后能不能直接安装？

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
- 固件 / Android / 桌面版本
- `SCREEN_INFO` 表结构关键列是否齐全
- 插件目录是否可写、现有 APK 情况
- **数据库模板里的 `FILE_SIZE` / `MD5` 是否和 `release/*.apk` 对得上**

`check.sh` 报「可以先执行 restore.sh」才用一键脚本；否则走下面的「方式 B：手动登记」。

---

## 方式 A：命令行一键（推荐给 Agent）

前置：能和电视通信的 adb、电视已 root。下面用 `<TV_IP>` 代表电视地址，实际地址换成 `adb devices` 里看到的那个。

```bash
# 1) 连接
adb connect <TV_IP>:5555
adb devices          # 应看到 device

# 2) 先体检（重要）
adb push release/ /data/local/tmp/tvrestore
adb shell su -c 'sh /data/local/tmp/tvrestore/check.sh'

# 3) 体检通过后恢复
adb shell su -c 'sh /data/local/tmp/tvrestore/restore.sh'

# 4) 等桌面重启，顶部应出现五个 TAB
```

`restore.sh` 会自动：

- **先核对模板数据库与 APK 的大小 / MD5**，不一致直接退出（防止换完 APK 还在跑旧 dex）；确实要强制覆盖时加 `--force`
- 把原数据库备份到 `/sdcard/launcher_new.before_restore.db`
- 停掉桌面进程（不停的话系统会立刻拉起旧进程并占用刚替换的 APK）
- 原子替换 5 个 APK，并清理 `plugins_data` 缓存
- 用 `cat >` 覆盖数据库（避免 `cp` 触发的 `SQLITE_READONLY_DBMOVED`）
- 重启桌面并逐项校验

Windows 下也可以双击 `release/restore.bat`，它会提示输入电视 IP（直接回车用默认 `192.168.1.10:5555`）。

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

然后对每个插件补一条 `SCREEN_INFO` 记录。下面以电台为例，字段值来自真机现有行：

```sql
INSERT INTO SCREEN_INFO (
  TAG, NAME, LOCAL, SORTABLE, REMOVABLE,
  PLUGIN_PATH, PLUGIN_STATE, VERSION_NAME, SCREEN_ORDER, SCREEN_TAG,
  VERSION_CODE, FILE_NAME, PACKAGE_NAME, SHOW_ON_TAB, FILE_SIZE, MD5,
  LOCKED, IS_NEW, HOT, POSITION, HAS_USED
) VALUES (
  '电台', '电台', 0, 1, 1,
  '/data/data/com.stv.launcher/files/plugins/radio.apk', 'available', '1.4.4', 4,
  '{"viewName":[{"content":"电台","lang":"zh-CN"},{"content":"电台","lang":"en"},{"content":"电台","lang":"zh-HK"}]}',
  '26', 'radio.apk', 'com.dsh.tvdesk.radio', 1, '20879', 'C2B533CD69C260651D96AC08C61EDB11',
  0, 0, 100, -1, 1
);
```

几个容易写错的点：

- `TAG` / `NAME` 是**中文显示名**，不是数字。旧版本本文档写成 `TAG=0` 是错的。
- `SCREEN_TAG` 是一段 JSON，控制 Tab 上的文字；漏掉它 Tab 可能不显示名字。
- `LOCAL=0` 表示插件；`SHOW_ON_TAB=1` 才会出现在顶部 Tab 栏。
- `PLUGIN_PATH` / `FILE_NAME` 必须和实际 APK 文件名一致。
- `VERSION_CODE` / `FILE_SIZE` 虽然列类型是 TEXT，但填纯数字字符串。
- `SCREEN_ORDER` 决定顺序，真机当前是 直播 1、新闻 2、豆瓣 3、电台 4、股票 5（内置「应用」0、「首页」6）。
- **`MD5` 与 `FILE_SIZE` 必须与实际 APK 完全一致**，否则桌面会认为插件没变化，继续用旧缓存（或者干脆不加载）。这是最容易踩的坑。

`tools-install_tab.sh` 是早期的手工脚本，**已不推荐**：它用 `cp` 覆盖数据库，也不校验 MD5 / 大小，只适合看个思路。

---

## 各插件版本对照

| 插件 | 包名 | versionName | versionCode | APK | 大小 | MD5 |
|---|---|---|---|---|---|---|
| 新闻 | `com.dsh.tvdesk.news` | 1.0.15 | 65 | `release/news.apk` | 49551 | `CAA9ED234F02AC7279040BDD03783C8E` |
| 直播 | `com.dsh.tvdesk.live` | 1.4.0 | 27 | `release/live.apk` | 61839 | `4694EA88D87672AC93EA0404C3A6FA4F` |
| 豆瓣荐片 | `com.dsh.tvdesk.douban` | 1.0.1 | 46 | `release/douban.apk` | 24975 | `272F5971C10D3F078A0AA2BCE60A63B5` |
| 电台 | `com.dsh.tvdesk.radio` | 1.4.4 | 26 | `release/radio.apk` | 20879 | `C2B533CD69C260651D96AC08C61EDB11` |
| 股票 | `com.dsh.tvdesk.stock` | 1.1.5 | 16 | `release/stock.apk` | 57743 | `1A87D2A72CACFD908CD299E1ADAE8DBC` |

> 重建 APK 后大小和 MD5 一定会变，不要手抄上表；跑 `python release/check-db.py --fix` 自动同步。

---

## 换了 APK 之后：同步数据库

桌面判断插件是否更新，只看 `SCREEN_INFO.FILE_SIZE` 和 `SCREEN_INFO.MD5`。只换 APK 不改这两列，桌面会继续用 `plugins_data` 里的旧 dex。

在 PC 上执行：

```bash
python release/check-db.py          # 只检查，逐项打印是否一致
python release/check-db.py --fix    # 把大小 / MD5 / 版本号写回 launcher_new.db
```

它会：

- 从 `release/*.apk` 计算真实大小和 MD5
- 从 `plugin-*/AndroidManifest.xml` 读 `versionName` / `versionCode`
- 对比 `launcher_new.db` 里的五条记录
- `--fix` 时只改这五条，不动「首页 / 应用」

然后重新 `adb push release/`，再跑 `restore.sh`。

---

## 校验

```bash
adb shell su -c 'ls -l /data/data/com.stv.launcher/files/plugins/'
adb shell su -c 'ls -l /data/data/com.stv.launcher/databases/launcher_new.db'
adb shell su -c 'busybox md5sum /data/data/com.stv.launcher/files/plugins/*.apk'
```

期望：五个 APK 存在（600 system:system），数据库 660，MD5 与上表 / 模板一致。

`SCREEN_INFO` 里应有 新闻 / 直播 / 豆瓣荐片 / 电台 / 股票 五行且 `SHOW_ON_TAB=1`。

> 电视上没有 `sqlite3`，也没有独立的 `md5sum`；MD5 用 `busybox md5sum`。
> 要读表就把数据库拉到 PC：
>
> ```bash
> adb shell su -c 'cat /data/data/com.stv.launcher/databases/launcher_new.db > /sdcard/_probe.db'
> adb pull /sdcard/_probe.db
> python release/check-db.py --db _probe.db
> ```

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

反复 `adb push` + 换 APK 会在电视上堆出一批临时物（最狠的是 `/data/local/tmp`：旧插件 APK、恢复工具副本、launcher DB 快照、dalvik 缓存）。它们不影响使用，但白占 `/data`。清一次：

```bash
adb push release/clean-dev.sh /data/local/tmp/
adb shell su -c 'sh /data/local/tmp/clean-dev.sh'
```

默认删 `/data/local/tmp`、桌面 `cache/ijkdex`、`/sdcard` 根目录的 `tvdesk*.log` / `tv_now*.png` 等调试截图 / `ui.xml` / `plugin_dev`；加 `--all` 连 `launcher_new*.db`、`db_now.db`、`db_before_swap.db`、`launcher_backup_*.db` 这些数据库快照一起删。插件 APK、`plugins_data`、`/sdcard/DIYP影音`、`/sdcard/tvdesk/自选.txt` 都不会动。

---

## 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| 替换 APK 后界面没变化 | 桌面进程没杀掉，还在用旧 dex | `am force-stop com.stv.launcher` 后再替换 |
| 替换 APK 后还是旧版 | ① 桌面把插件 dex 缓存在 `files/plugins_data/<包名>/dex/`；② `SCREEN_INFO` 里 MD5 / 大小没更新 | 删 `plugins_data/<包名>`，再跑 `check-db.py --fix` |
| `check.sh` 报「数据库模板和 APK 对不上」 | 模板里的 MD5 / 大小是旧构建的 | PC 上 `python release/check-db.py --fix`，重新 push `release/` |
| 桌面一直「正在获取…」 | 插件内部状态没刷新 | 分类条上按「确定」刷新 |
| 桌面进不去 | DB 结构与固件不匹配 | 用备份回退，改走方式 B |
| 直播播不了 rtsp/4K | 没装 TVBox | 装 `com.github.tvbox.osc.tk` |
| 豆瓣点播没反应 | 没装 TVBox | 同上 |
| 直播频道表放哪 | 实际读的是目录，不是单个文件 | `/sdcard/DIYP影音/*.txt`，每个文件一组 |
| 基金/国际曲线空白 | 历史接口在部分网络不可达 | 属已知问题，其余分类正常 |

> **真机踩过的实例**：探测时这台 X3-55 的 APK 已经是 news v109 / stock v16，但设备数据库里
> news 还记着 `41359` 字节、stock 还记着 `53647` 字节，都是旧构建（现已同步修复）。
> 这种情况 `check.sh` 现在会直接报出来，`check-db.py --fix` 修好模板后再恢复。
