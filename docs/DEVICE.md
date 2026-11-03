# 真机实测记录（Letv X3-55）

这份文档记录仓库当前版本在真实电视上的状态，方便以后排查「代码没问题、机器上不对」的情况。
探测时间：2026-11-03，通过局域网 adb + root 只读检查。

---

## 机器

| 项 | 值 |
|---|---|
| 型号 | `Letv X3-55` |
| 设备代号 | `X3_55` |
| 系统 | Android 5.0.1（SDK 21） |
| 固件 | `V2301RCN02C060072B08221N` |
| 桌面 | `com.stv.launcher` 1.0.2，versionCode `20170509`，targetSdk 21 |
| 屏幕 | 1920×1080，density 240 |
| root | `su -c id` → `uid=0(root) gid=0(root)` |
| 播放内核 | TVBox `com.github.tvbox.osc.tk` 已安装 |

刷机后如果固件/桌面版本和上表不同，先跑 `release/check.sh`，不要直接覆盖数据库。

---

## 插件实际安装状态

`/data/data/com.stv.launcher/files/plugins/`：

| 文件 | 大小 | MD5 | 说明 |
|---|---:|---|---|
| `news.apk` | 45455 | `627850DC8CDFCC7E301D551192844EC1` | 新闻 1.0.11 / 61 |
| `live.apk` | 61839 | `4694EA88D87672AC93EA0404C3A6FA4F` | 直播 1.4.0 / 27 |
| `douban.apk` | 24975 | `272F5971C10D3F078A0AA2BCE60A63B5` | 豆瓣荐片 1.0.1 / 46 |
| `radio.apk` | 20879 | `C2B533CD69C260651D96AC08C61EDB11` | 电台 1.4.4 / 26 |
| `stock.apk` | 57743 | `1A87D2A72CACFD908CD299E1ADAE8DBC` | 股票 1.1.5 / 16 |
| `com.stv.plugin.app.apk` | 758751 | - | 桌面自带「应用」 |
| `com.stv.plugin.tvlive.apk` | 14591306 | - | 桌面自带「首页 / 信号源」 |

五个自建 APK 与仓库 `release/*.apk` **逐字节一致**（MD5 相同）。

`SCREEN_INFO` 里五条插件记录的 `SCREEN_ORDER`：

```
应用 0  →  直播 1  →  新闻 2  →  豆瓣荐片 3  →  电台 4  →  股票 5  →  首页 6
```

---

## 实际发现的数据库漂移

探测时 APK 已经更新，但 `SCREEN_INFO` 里还留着旧构建的元数据：

| 插件 | APK 实际 | 设备 DB 记录（修复前） | 结果 |
|---|---|---|---|
| 新闻 | 45455 / `F41992C7…` | 41359 / `10AA5190…` | 大小、MD5 都是旧的 |
| 股票 | 57743 / `1A87D2A7…` | 53647 / `5279FEC9…` | 大小、MD5 都是旧的 |
| 直播 / 豆瓣 / 电台 | - | - | 一致 |

仓库里的 `release/launcher_new.db` 漂移更早：新闻还停在 1.0.0 / 50 / 33167 字节，股票停在 1.0.7 / 10。

已经做的处理：

- 用 `release/check-db.py --fix` 把模板库五条记录的 `FILE_SIZE` / `MD5` / 版本号全部对齐到当前 APK
- 2026-11-03 已把电视上 news / stock 两条记录同步为实际 APK 值（写前备份、停桌面，写后 `integrity_check` 通过，新闻 / 股票页面实测可正常加载）
- `release/check.sh` 增加「模板数据库 vs APK」一致性检查
- `release/restore.sh` 在不一致时拒绝覆盖（`--force` 可跳过）
- `docs/RESTORE.md` 的版本表和手动登记 SQL 按真机字段重写

> 这次设备的 `plugins_data/com.dsh.tvdesk.news/dex/news.dex` 时间戳和 APK 一致，
> 说明 dex 缓存是新的；但 DB 元数据仍是旧的。两者不一致时，行为取决于桌面有没有重新生成缓存，
> 所以不要靠「看起来能跑」来判断，直接对 MD5。

---

## 语音朗读：小数点被“断句”吞掉（1.0.10 已修）

**现象**：微软 Edge 朗读全文时，`3.5` 读成“三五”，小数点的“点”不见了；
同一段文字直接送进 Edge 桥却读得好好的，只有长文才复现。

**定位**：不是 Edge 的问题。用桥实测：

| 文本 | 音频时长 | 说明 |
|---|---:|---|
| `同比增长3.5倍` | 1.296s | 与 `同比增长三点五倍` 完全一致，点正常读 |
| `同比增长3.` | 1.080s | 段尾的点被当成句号 |
| `5倍` | 0.936s | 下一段从数字开头 |

`NewsFragment.splitChunks()` 按 `。！？；，.` 切段，把 ASCII 的 `.` 也当断句符。
切点落在小数点上时，`3.` 和 `5` 被送成两次合成，Edge 把段尾的 `.` 当句号，
“点”就丢了；标题短、不会触发切段，所以只有“朗读全文”能复现。

**改法**（`plugin-news/src/.../NewsFragment.java`）：

- `findCut()`：ASCII `.` 只有后面跟空格（真正的英文句号）才允许断句；
  小数点、`www.xxx.com` 里的点一律跳过
- `snapTokenEnd()`：切点如果落在一个「不可劈开」的串中间（数字 / 字母 /
  `.` `,` `:` `%` `/` `-` `_` `?` `&` `=` `#` `@` `~` `+`），就把切点挪到整串外面，
  保证 `3.5`、`12:30`、`v1.2.3`、`1,234.5`、网址都不会被劈成两段
- 新增 `isDigit()` / `isTokenChar()` 辅助方法，`splitChunks()` 改为静态方法便于离线自测
- 新增离线回归测试 `plugin-news/test/TestSplitChunks.java`：反射调用真正的
  `splitChunks()`，覆盖硬切碰数字、标点切点、时间、版本号、千分位、百分数、网址、短句

> 桥上的对照数据：`3.5` 与 `三点五` 的 mp3 长度完全相同（15408 字节），
> 说明只要数字完整，Edge 自己会读成“三点五”。

真机上还能直接看发出去的文本：电视 root，`tcpdump -i wlan0 -w /data/local/tmp/tts.pcap tcp port 8123`，
朗读后把 pcap 拉回电脑，解出每条 `GET /tts?...text=` 的分段。1.0.10 实测“科技”头条，一共 7 段，
`7,665.2 亿元人民币`、`同比增长4.5%`、`回升0.2个百分点` 都完整落在段内。

把 7 段拼回原文再用**旧算法**跑一遍，第 2 段正好停在 `…现汇率约合 7,665.`，
第 3 段从数字开头——这就是“点”被吞掉的现场；新算法把切点提前到前一个逗号，数字保持完整。

跑一遍离线测试（先执行过 `plugin-news/build.ps1`）：

```powershell
$root  = "D:\deepseek\tv-desktop"
$proj  = "$root\repo\plugin-news"
$cp    = "$proj\build\classes;$proj\build\stubs.jar;$root\tools\android-sdk\platforms\android-34\android.jar"
& "$root\tools\jdk\jdk-17.0.20.1+1\bin\javac.exe" -encoding UTF-8 `
    -cp $cp -d "$root\tmp\chunktest" "$proj\test\TestSplitChunks.java"
& "$root\tools\jdk\jdk-17.0.20.1+1\bin\java.exe" -cp "$root\tmp\chunktest;$cp" TestSplitChunks
```

---

## 存储布局

### 频道表

直播插件读的是**目录**，不是单个文件：

```
/sdcard/DIYP影音/
├── 央视.txt
├── 地方.txt
├── 数字.txt
└── 吉林.txt
```

源码里的固定分组顺序是 `央视 / 地方 / 数字 / 吉林`，文件名去掉 `.txt` 就是分组名。
网上有些说明写成 `/sdcard/tvdesk/直播源.txt`，与当前代码不符。

### 插件自己的数据

```
/sdcard/tvdesk/oil.cache      油价缓存（12 小时）
/sdcard/tvdesk/oil.adjusts    调价历史缓存（6 小时）
/sdcard/tvdesk/自选.txt       股票自选
```

### 偏好

只有新闻插件写 SharedPreferences：

`/data/data/com.stv.launcher/shared_prefs/tvdesk_news.xml`

```xml
<int name="voice"  value="1" />
<int name="speed"  value="2" />
<int name="engine" value="1" />
<string name="voice_name">微软·云健(男)</string>
```

即当前是「微软 Edge + 第二个音色 + 1.2 倍速」。其它四个插件没有偏好文件。

`voice_name` 是 1.0.11 加的：音色表以后再加人时，按名字找回上次用的音色；
`voice` 下标继续保留，**前三条顺序不能动**（老版本只存了下标，0/1/2 分别是云希/云健/晓晓）。

Edge 中文音色表（`EDGE_VOICES`，8 个，全部实测桥上返回 200）：

| 下标 | voice | 显示名 |
|---:|---|---|
| 0 | `zh-CN-YunxiNeural` | 云希(男) |
| 1 | `zh-CN-YunjianNeural` | 云健(男) |
| 2 | `zh-CN-XiaoxiaoNeural` | 晓晓(女) |
| 3 | `zh-CN-YunxiaNeural` | 云夏(男) |
| 4 | `zh-CN-YunyangNeural` | 云扬(男) |
| 5 | `zh-CN-XiaoyiNeural` | 晓伊(女) |
| 6 | `zh-CN-liaoning-XiaobeiNeural` | 晓北(女·东北话) |
| 7 | `zh-CN-shaanxi-XiaoniNeural` | 晓妮(女·陕西话) |

粤语（`zh-HK-*`）和台湾（`zh-TW-*`）的音色桥也支持，但没有放进新闻页的循环里。
`zh-CN-XiaobeiNeural` / `zh-CN-XiaoniNeural` 是上面两个方言音色的短名，桥也认。

### 调试残留

真机 `/sdcard` 根目录当时有：

```
tvdesk.log  tvdesk_stock.log
tv_now5.png … tv_now11.png
db_before_swap.db  db_now.db
launcher_backup_143638.db
launcher_new.before_restore.db
```

`release/clean-dev.sh` 已补上 `tv_now*.png` 和 `launcher_backup_*.db` 的清理规则；
`--all` 才会动数据库快照。

---

## 电视 shell 的限制

这台机器的 `/system/bin/sh` 是很老的 toolbox：

- 没有 `md5sum`、`which`、`head`、`awk`、`sed`、`wc`、`toybox`
- 有 `busybox`，MD5 用 `busybox md5sum`
- `ls -l` 的第 4 列是文件大小，脚本里用 `set -- $(ls -l ...)` 取
- `busybox grep -a` 可以直接在 SQLite 文件里搜字符串，用来做只读的表结构 / 元数据检查
- `dumpsys package com.stv.launcher` 能读到桌面版本

所以仓库里的电视端脚本都避开 `awk` 等工具，只用 `ls / grep / test / du` 和 shell 内建。

---

## 复现探测

```bash
adb connect <TV_IP>:5555
adb shell su -c id
adb shell getprop ro.build.display.id
adb shell getprop ro.build.version.release
adb shell dumpsys package com.stv.launcher | busybox grep -E 'versionName|versionCode'
adb shell su -c 'ls -l /data/data/com.stv.launcher/files/plugins/'
adb shell su -c 'busybox md5sum /data/data/com.stv.launcher/files/plugins/*.apk'
adb shell su -c 'ls -l /sdcard/DIYP影音/ /sdcard/tvdesk/'
```

数据库结构：

```bash
adb shell su -c 'cat /data/data/com.stv.launcher/databases/launcher_new.db > /sdcard/_probe.db'
adb pull /sdcard/_probe.db
python release/check-db.py --db _probe.db
```

`SCREEN_INFO` 共 42 列，关键列：

```
TAG NAME LOCAL SORTABLE REMOVABLE PLUGIN_PATH PLUGIN_STATE VERSION_NAME
SCREEN_ORDER SCREEN_TAG VERSION_CODE FILE_NAME PACKAGE_NAME SHOW_ON_TAB
FILE_SIZE MD5 LOCKED IS_NEW HOT POSITION HAS_USED OFFLINE_SHOT
```

手动登记时不要只写旧文档里的 12 个字段，至少把 `SCREEN_TAG`、`VERSION_NAME`、
`LOCAL`、`LOCKED`、`HOT`、`POSITION`、`HAS_USED` 一起写上；完整示例见
[RESTORE.md](RESTORE.md#方式-b手动登记固件版本不一致时)。
