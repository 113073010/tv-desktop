# 恢复说明（给 Agent / 命令行用）

目标：把 **新闻 / 直播 / 豆瓣荐片** 三个 TAB 恢复到一个已 root 的乐视电视上。

整个恢复只做三件事：

1. 把三个插件 APK 放进 `/data/data/com.stv.launcher/files/plugins/`
2. 替换 `/data/data/com.stv.launcher/databases/launcher_new.db`
3. 重启桌面

> 直播 TAB 额外需要 **TVBox**（`com.github.tvbox.osc.tk`）提供播放内核，
> 它不是本仓库的产物，需自行安装；没有它也能恢复，只是 rtsp/4K 播不了。

---

## 方式 A：命令行一键（推荐给 Agent）

前置：能和电视通信的 adb（示例 IP `192.168.1.10`，换成实际地址）、电视已 root。

```bash
# 1) 连接
adb connect 192.168.1.10:5555
adb devices          # 应看到 device

# 2) 验证 root（会弹 SuperSU，需在电视上点「授权」）
adb shell su -c id   # 期望输出 uid=0(root) gid=0(root)

# 3) 把 release 目录整个推到电视
adb push release /data/local/tmp/tvrestore

# 4) 执行恢复脚本
adb shell su -c 'sh /data/local/tmp/tvrestore/restore.sh'
```

脚本会打印每一步结果，最后校验三个 APK 和数据库是否就位。
失败时返回非 0：`2` 缺文件、`3` 不是乐视桌面、`4` 插件复制失败、`5` 数据库失败。

**验证**

```bash
adb shell su -c 'ls -l /data/data/com.stv.launcher/files/plugins/'
adb shell su -c 'ls -l /data/data/com.stv.launcher/databases/launcher_new.db'
adb shell "sqlite3 /data/data/com.stv.launcher/databases/launcher_new.db \
  'select NAME,PACKAGE_NAME,VERSION_CODE,SHOW_ON_TAB from SCREEN_INFO'"
```

期望：三个 APK 存在（600 system:system），数据库 660，
`SCREEN_INFO` 里有 新闻/直播/豆瓣荐片 三行且 `SHOW_ON_TAB=1`。

> 没有 sqlite3 命令时，用 `adb pull` 把数据库拉到 PC 上用工具读。

---

## 方式 B：电视上点按（无需命令行）

```bash
adb install -r release/restore-lite.apk
adb shell am start -n com.dsh.tvdesk.restore/.MainActivity
```

电视上会出现 4 个按钮，用遥控器：

| 按钮 | 作用 |
|---|---|
| ① 安装依赖 | 安装 TVBox（若内置）+ 写入频道源到 `/sdcard/DIYP影音/` |
| ② 恢复设置 | 写入三个插件 + 数据库，自动重启桌面 |
| ③ 清除设置 | 删除插件 + 从数据库删除三个 TAB（先备份数据库） |
| ④ 清除依赖 | 卸载 TVBox + 删除频道源 |

首次运行弹 SuperSU，选「授权」。

`restore-lite.apk` **不含** TVBox（避免二次分发第三方应用）；
需要 TVBox 时自行安装到电视，按钮 ① 会自动跳过。

---

## 回滚

恢复前脚本会把原数据库备份到 `/sdcard/launcher_new.before_restore.db`。

```bash
adb shell su -c 'cp /sdcard/launcher_new.before_restore.db \
  /data/data/com.stv.launcher/databases/launcher_new.db'
adb shell su -c 'rm -f /data/data/com.stv.launcher/databases/launcher_new.db-journal'
adb shell su -c 'am force-stop com.stv.launcher; am start -n com.stv.launcher/.activity.Launcher'
```

插件回滚：删掉 `files/plugins/{news,live,douban}.apk` 即可（桌面加载不到就不显示）。

---

## 常见问题

| 现象 | 原因 / 处理 |
|---|---|
| `su: not found` | 电视没 root，先 root |
| `uid=0` 但脚本报权限错误 | SuperSU 未授权该 shell，电视上点「授权」 |
| 桌面没出现 TAB | `SCREEN_INFO.VERSION_CODE` 与 APK `versionCode` 不一致；确认用的是 release 里配套的 db |
| TAB 出现但空白 | 插件未随桌面重启加载，`am force-stop` 后再拉起 |
| 直播黑屏 | 没装 TVBox（缺 IJK 内核），或频道源不在 `/sdcard/DIYP影音/` |
| 桌面崩溃 | 固件桌面版本与 `launcher_new.db` 不匹配，用回滚步骤还原 |

---

## 文件说明

| 文件 | 用途 |
|---|---|
| `news.apk` | 新闻插件 v50（含军事分类） |
| `live.apk` | 直播插件 v24 |
| `douban.apk` | 豆瓣荐片插件 v46 |
| `launcher_new.db` | 桌面数据库（三个 TAB 已登记，版本号对齐） |
| `restore-lite.apk` | 电视端图形化恢复工具（不含 TVBox，144KB） |
| `restore.sh` | 设备端恢复脚本（方式 A 使用） |
| `restore.bat` | Windows 端一键脚本（adb + root） |
| `SHA256SUMS.txt` | 校验和 |

> 更早的完整版 `restore.apk`（内置 TVBox 28MB）只在本地保留，不随仓库分发。
