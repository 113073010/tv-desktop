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
| `news.apk` | 49551 | `D19E880C4569E5949B8B950C1D0F0BF8` | 新闻 1.0.17 / 67 |
| `live.apk` | 61839 | `4694EA88D87672AC93EA0404C3A6FA4F` | 直播 1.4.0 / 27 |
| `douban.apk` | 24975 | `272F5971C10D3F078A0AA2BCE60A63B5` | 豆瓣荐片 1.0.1 / 46 |
| `radio.apk` | 20879 | `C2B533CD69C260651D96AC08C61EDB11` | 电台 1.4.4 / 26 |
| `stock.apk` | 57743 | `EBA25E36F6D33067F640DB863F8E24A5` | 股票 1.1.6 / 17 |
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

## 全文播完自动接下一条（1.0.15）

以前右栏按「确定」朗读全文，读完就 `stopReading()` 停住；想听下一条得再挪过去按一次。
现在改成「听完一条，停 3 秒没人动，就自动播下一条全文」：

- 一条播完 → 状态栏倒计时：`♪ 本条已播完，3 秒后自动播放下一条全文（按任意键取消）`
- 3 秒内没有任何按键 → 自动切下一条：列表的 `♪` 标记和右栏一起过去，正文用抓来的全文（不是 RSS 摘要），到手就开读
- 倒计时里按任意键 → 取消并停止；键本身照常生效（方向键照走、返回照退，确定会重读当前这条）
- 正文还在抓的时候（`♪ 正在读取下一条全文…`）按键同样算放弃
- 播到本分类最后一条 → 停止，状态栏提示 `已播完本分类全部新闻`，不会跨分类跳
- 暂停/继续（确定）仍然可用；暂停中不会起倒计时

2026-11-03 真机验证过一次完整链路（新闻 1.0.15）：`22:17:46.833` 第 2 条最后一段播完 →
`22:17:49.88` 开始合成第 3 条首段（正好 3.0 秒）→ 列表 `♪` 和右栏都切到 `3/20`。
当时 edge 桥不通，首段还要再等 10 秒超时回退百度，所以从静音到出声约 13 秒；
桥正常时这段只有 1 秒左右。

1.0.16 把桥地址从 `192.168.1.10` 改成 iStoreOS 网关 `192.168.1.22:8123`（电视是 DHCP，
地址会变；网段里固定的是网关）。重新部署后实测每段直接 `play tts_e*.mp3`，
不再出现 `edge fail, fallback baidu`，切条基本无缝。

实现上的几个点（改这块时别踩）：

- **「任意键」只能靠根容器**：`RootFrame extends FrameLayout` 重写 `dispatchKeyEvent()`，
  在子 View 之前看到按键。`rowKeys` / `sideKeys` 会把方向键和返回键吃掉，
  只挂 `setOnKeyListener` 是收不齐的；按住不放的重复键用 `getRepeatCount()==0` 过滤。
- `onSpeakFinished()` 按 `mChain` 分流：标题轮播还是老的「直接下一条标题」，全文才走 `scheduleAutoNext()`。
- `loadFullText()` 多了一个 `Runnable onReady` 重载：缓存命中、抓到全文、抓不到（回退摘要）都会在主线程回调，
  自动续播挂在它上面，避免「先读摘要再补全文」读两遍。
- `mStartingArticle` 标记「正在给下一条抓正文」，这期间按确定不会被 `readDetail()` 当成暂停/继续。
- 上一条开读时顺手 `prefetchBody(index + 1)`，把下一条正文提前抓进 `mFullCache`，3 秒倒计时基本够用。
- `rowKeys` 里的 slot 改成「真正拿到焦点的那一行」，不再用 `mFocus - mOffset`：
  自动续播会把 `mFocus` 挪到下一条，遥控器焦点可能还停在旧行，按旧算法会一次跳两行。

---

## 换数据库别留 sleep 窗口（2026-11-03 踩到）

桌面被 `kill -9` 后大约 **1 秒**就会被系统重新拉起。如果按老流程「kill → sleep 1 → 写库」，桌面起来时已经把旧 `SCREEN_INFO` 读进内存页缓存，等它下一次写库（`HAS_USED` 之类）就用旧页把刚写进去的 MD5 / 版本号刷回去——插件加载的是新 dex，但元数据又变回旧的。

现在 `restore.sh` 在换库前再 `busybox killall -9` 一次，并去掉写库前的 sleep：`kill → rm -f *-journal → cat → sync → am start` 全在 1 秒内跑完。单插件更新时同理：这次更新新闻（1.0.15）第一次留了 sleep，写完 `SCREEN_INFO` 又变回 1.0.14 / `06927007…`，去掉窗口重写后才稳定在 1.0.15 / `CAA9ED23…`。

顺带一个判断「电视上跑的到底是哪份代码」的笨办法——直接在缓存的 dex 里搜新类的名字：

```bash
adb shell su -c 'busybox grep -a -c RootFrame /data/data/com.stv.launcher/files/plugins_data/com.dsh.tvdesk.news/dex/news.dex'
```

返回 >0 说明新构建已经生效（`RootFrame` 是 1.0.15 才加的根容器类）。

---

## 油价「下次调价」显示成上次日期（1.1.6）

**现象**：股票 TAB → 油价，右侧详情「下次调价」在 11 月 14 日仍显示 `11月12日`，日期已经过了；「预计调整」也是「待公布」。

**原因**：这个格子填的是 `Oil.adjustDate`，来自 qiyoujiage 省级页面的 `油价11月12日24时调整`，是**上一次**调价日。真正的 `OilPrice.computeNextAdjust()` 虽然算了下次日期，但结果没有任何界面读取，属于死代码；而且只按「上次调价 + 10 个工作日」硬推，碰到法定节假日会偏。

**改法**：

- soyoujia 调价日历本来就列出未来窗口（`2026-11-27 … 未公布`），直接取「今天以后、且晚于上次实际调价」的最近一条，写进 `oil.adjusts` 的 `#next=`
- 日历没取到或格式变了，退回「上次调价 + 10 个工作日」
- 存下来的 `#next` 已经过期时，忽略 6 小时缓存强制重抓一次；仍推不出未来日期就显示「待公布」，不再把过去的日期当未来
- 详情面板改读 `OilPrice.cachedNextAdjust()`，每次进油价栏（包括原地刷新）都后台算一次；算完只重绘这一格

2026-11-14 真机验证（1.1.6 / 17）：日志 `nextAdjust=2026-11-27 calendar=2026-11-27`，界面显示 `11月27日（预计）`。

---

## 军事新闻详情不显示发布日期（1.0.17）

**现象**：新闻 TAB → 军事 → 点开任意一条，右下角来源行只有 `凤凰军事`，没有发布时间；其他几个 RSS 分类都正常显示 `· 06:20`。

**原因**：军事分类没有 RSS，走的是网页抓取 `parseHtmlList()`，里面把 `it.time` 直接写死成空串。凤凰军事的列表页确实没有 `<time>` 标签，发布时间只存在于页面内嵌 JSON 的 `"url" … "newsTime"` 字段里，之前完全没解析。

**改法**（`plugin-news/src/.../NewsFragment.java`，独立版共用同一份源码）：

- `parseHtmlList()` 先扫一遍内嵌 JSON，建立「文章 id（`/c/xxxx`）→ 发布时间」映射，命中就填 `Item.time`
- 列表 JSON 里没有的条目（轮播/推荐位），在抓正文时从文章页 `<meta property="article:published_time">` 或 `<time itemProp="datePublished">` 补，抓到后刷新来源行
- 时间统一格式化成 `11月14日 06:20`，来源行由 `metaLine()` 拼接，没有日期时不留下多余的分隔点

2026-11-14 真机验证：

- Letv X3-55（`192.168.1.9`，插件 1.0.17 / 67）：军事详情显示 `凤凰军事 · 11月14日 06:49`
- `192.168.1.3`（独立版 1.0.1 / 2）：显示 `凤凰军事 · 11月14日 06:10`

> 换 APK 后桌面会继续用 `plugins_data/<包名>/dex/news.dex` 里的旧类。
> 只 `am force-stop` 有时杀不掉主进程，要么 `kill -9 <pid>`，要么删掉该包 `plugins_data` 目录再重启桌面。

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
