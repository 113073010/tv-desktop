# 乐视超级电视 X3-55 桌面插件三件套

> 不重做 Launcher，只在乐视原生桌面上增加三个自己想要的 **Tab**：新闻、直播、豆瓣荐片。

![桌面](docs/images/news-main.png)

## 这是什么

乐视（Letv）超级电视的原生桌面 `com.stv.launcher` 有一套**插件机制**：插件 APK 不放桌面图标，
而是被桌面当作「一个 Tab 页」加载，直接显示在顶部 Tab 栏里（信号源 / 应用 / 新闻 / 直播 / 豆瓣荐片）。

本项目就是按这套机制写的三个插件，外加一个**一键恢复工具**：

| 模块 | 包名 | 说明 |
|---|---|---|
| 新闻 | `com.dsh.tvdesk.news` | 7 个分类的纯文字新闻 + 语音朗读 |
| 直播 | `com.dsh.tvdesk.live` | 预览画面 + 2×5 频道墙，读 DIYP 格式频道表 |
| 豆瓣荐片 | `com.dsh.tvdesk.douban` | 豆瓣海报墙，点击交给 TVBox 搜索播放 |
| 恢复工具 | `com.dsh.tvdesk.restore` | 电视端一键安装依赖 / 恢复设置 / 清除 |

## 功能一览

### 新闻

![军事分类](docs/images/news-military.png)

- **7 个分类**：热点 / 要闻 / 时政 / 科技 / 财经 / 国际 / 军事
- **纯文字**：左栏标题列表，右栏抓取文章原页正文（去掉广告、导航、"听全文"等杂质）
- **语音朗读**：连播标题，或在右栏按确定朗读全文；语速可调（0.8/1.0/1.2/1.5）
- **按键**：上下移动、右键进详情、详情页上下滚动、返回分两级退出

### 直播

- 顶部预览画面 + 下方 2×5 频道墙
- 频道表读 `/sdcard/DIYP影音/*.txt`（DIYP 格式），自动分类：央视 / 地方 / 数字 / 吉林
- 默认频道：名称含「新闻」或「CCTV-13」
- 4K 频道会标注并给出提示

### 豆瓣荐片

- 12 个分类（热播电影/剧集/综艺、国产剧、美剧、韩剧、日剧、动画、国漫、日漫、纪录片、高分 Top250）
- 7×2 海报墙，横向滚动分页，默认 250 条高分片单
- 点击海报 → 唤醒 TVBox 并触发搜索

## 怎么用（Agent / 命令行）

刷机、换机、或者只是想让别的 Agent 帮你装回来 —— **不需要编译**，`release/` 目录里就是全部所需文件。

### 方式 A：命令行（Agent 最容易执行）

```bash
# 1) 连接电视（IP 换成实际地址）
adb connect 192.168.1.10:5555
adb devices

# 2) 确认 root（电视上可能弹 SuperSU，点「授权」）
adb shell su -c id            # 期望：uid=0(root) gid=0(root)

# 3) 把 release 目录推到电视
adb push release /data/local/tmp/tvrestore

# 4) 执行恢复脚本
adb shell su -c 'sh /data/local/tmp/tvrestore/restore.sh'
```

脚本会打印每一步结果，并校验三个 APK 和数据库是否就位。
失败时返回具体错误码：`2` 缺文件、`3` 不是乐视桌面、`4` 插件复制失败、`5` 数据库替换失败。

**执行完验证**

```bash
adb shell su -c 'ls -l /data/data/com.stv.launcher/files/plugins/'
adb shell su -c 'ls -l /data/data/com.stv.launcher/databases/launcher_new.db'
```

期望：三个 APK 存在（权限 600，属主 system），数据库权限 660；桌面已自动重启，
顶部 Tab 栏出现 **新闻 / 直播 / 豆瓣荐片**。

### 方式 B：图形界面（遥控器操作）

```bash
adb install -r release/restore-lite.apk
adb shell am start -n com.dsh.tvdesk.restore/.MainActivity
```

电视上用遥控器进入「**② 恢复设置**」，程序自动写入插件和数据库并重启桌面。

界面共 4 个按钮：

| 按钮 | 作用 |
|---|---|
| ① 安装依赖 | 写入频道源到 `/sdcard/DIYP影音/`（内置 TVBox 时一并安装） |
| ② 恢复设置 | 写入三个插件 + 数据库，重启桌面 |
| ③ 清除设置 | 删除插件并从数据库删除这三个 TAB（先备份数据库） |
| ④ 清除依赖 | 卸载 TVBox、删除频道源 |

首次运行会弹 SuperSU 授权框，选「授权」并勾选「记住选择」。

### release/ 里有什么

| 文件 | 说明 |
|---|---|
| `news.apk` / `live.apk` / `douban.apk` | 三个插件（v50 / v24 / v46） |
| `launcher_new.db` | 已登记三个 TAB 的桌面数据库 |
| `restore.sh` | 设备端恢复脚本（幂等，自动备份原库到 `/sdcard/launcher_new.before_restore.db`） |
| `restore-lite.apk` | 电视端图形化工具（4 个按钮，144KB，**不含 TVBox**） |
| `restore.bat` | Windows 双击运行（改里面的 `TV=` 为你的电视 IP） |
| `SHA256SUMS.txt` | 校验和 |

### 关于 TVBox

直播 TAB 的播放内核借用 TVBox（`com.github.tvbox.osc.tk`）。
`release/` **不包含** TVBox（避免二次分发第三方应用）：

- 已装 TVBox → 直接按上面步骤恢复，一切正常
- 没装 → 恢复照样成功，但 rtsp / 4K 频道播不了（HLS / HTTP 可用）

详细排查、回滚方法见 **[docs/RESTORE.md](docs/RESTORE.md)**。
## 快速开始

### 前提

- 乐视电视（本项目在 **Letv X3-55 / Android 5.0.1 / MStar monaco** 上验证）
- 电视已 **root**（插件安装需要写 `/data/data/com.stv.launcher/`）
- 直播功能需要 **TVBox**（借用它的 IJK 播放内核）

### 安装方式一览

| 方式 | 适合场景 | 命令 |
|---|---|---|
| **命令行** | Agent / 有 adb 环境 | `adb push release /data/local/tmp/tvrestore` 后跑 `restore.sh` |
| **电视端图形工具** | 遥控器操作 | `adb install -r release/restore-lite.apk` |
| **Windows 脚本** | 双击省事 | `release/restore.bat` |
| **手动** | 完全掌控 | 见下方说明 |

**手动安装**：把 APK 放进 `/data/data/com.stv.launcher/files/plugins/`，
并往 `launcher_new.db` 的 `SCREEN_INFO` 表插入对应记录

### 编译

每个插件目录下有独立的 `build.ps1`，依赖：

- **JDK 17**：设置环境变量 `JAVA_HOME`，或放到 `<repo>\tools\jdk\`
- **Android SDK**：需要 build-tools 34 与 `platforms/android-34/android.jar`，
  设置环境变量 `ANDROID_SDK_ROOT`，或放到 `<repo>\tools\android-sdk\`
- Launcher SDK 的桩代码（各目录 `stubs/`，已随仓库提供）
- 签名密钥：脚本首次运行会自动生成 `build\tvdesk.jks`（仅调试用）

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:ANDROID_SDK_ROOT = "C:\Android\sdk"
cd plugin-news
.\build.ps1
```

```powershell
cd plugin-news
.\build.ps1     # 产出 build\news.apk
```

编译链：`javac → d8 → aapt2 link → 塞入 dex → zipalign → apksigner`

## 原理要点

### 插件是怎么被桌面认出来的

`AndroidManifest.xml` 里的 meta-data：

```xml
<meta-data android:name="ILePluginCreateApi" android:value="com.dsh.tvdesk.news.CreateApi" />
<meta-data android:name="minDependentVersion" android:value="1.0.0" />
<meta-data android:name="widgetName" android:value="新闻" />
<meta-data android:name="dependentPackages" android:value="com.stv.launcher.sdk;android.support.v4" />
```

`dependentPackages` 必须声明，否则桌面报 `start failed by newInstance error`。

插件入口实现 `ILePluginCreateApi`，返回一个继承 `BaseFragment` 的 Fragment；
桌面的 `SCREEN_INFO` 表通过 `TAG / PACKAGE_NAME / VERSION_CODE / SHOW_ON_TAB` 决定是否显示这个 Tab。

> `VERSION_CODE` 必须和 APK 的 `versionCode` 一致，否则桌面不会重新加载插件。

### 数据源

| 分类 | 来源 |
|---|---|
| 热点 | 腾讯新闻热榜 `r.inews.qq.com`（JSON） |
| 要闻 | 中新网 要闻导读 RSS |
| 时政 | 中新网 国内 RSS |
| 科技 | IT之家 RSS |
| 财经 | 中新网 财经 RSS |
| 国际 | 中新网 国际 RSS |
| 军事 | 凤凰网军事（网页抓取，无 RSS） |

全部为公开接口，无需密钥。

> 人民网 `politics/it/finance` 三个 RSS 已停更于 2025-06，故弃用。

### Android 5 的坑

- 系统根证书过旧，访问 https 常报 `Trust anchor for certification path not found` → 插件内使用宽松 `X509TrustManager`
- `MediaPlayer` 无法直接播放 https 音频流 → 先下载到缓存目录再本地播放
- `AssetManager` 不认 assets 子目录 → 恢复工具的资源全部平铺在 assets 根目录
- `su -c` 传多行脚本会失败 → 先写文件再 `sh 文件`

## 已知限制

- 仅在一台 X3-55 上验证，其他机型/固件可能不兼容（尤其桌面数据库结构）
- 凤凰军事的正文在 JS 里渲染，部分文章右栏可能抓不到全文
- 4K 频道依赖 TVBox 的 IJK 内核；没有 TVBox 时回退系统播放器，rtsp 多路会失败
- 朗读使用百度在线合成，机械感较强；未找到可用的免密钥男声方案

## 目录结构

```
plugin-news/      新闻插件（源码 + 构建脚本 + SDK 桩）
plugin-live/      直播插件
plugin-douban/    豆瓣荐片插件
plugin-restore/   电视端恢复工具
docs/images/      截图
tools-install_tab.sh  调试用：替换插件并重启桌面
```

## 许可

代码以 MIT 许可发布，详见 [LICENSE](LICENSE)。

注意事项：

- 本项目**不包含** TVBox、豆瓣、各新闻站点的任何二进制或内容
- 抓取的新闻内容版权归原站点所有，仅供个人阅读使用
- 随意刷机有风险，root 与修改系统数据库请自行承担后果
