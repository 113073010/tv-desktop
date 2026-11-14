# 变更记录

只记录对使用者有感知的变化。完整提交历史见 `git log`，附件与校验和见
[Releases](https://github.com/113073010/tv-desktop/releases)。

版本号规则：`vX.Y.Z` 是**整个仓库的发布批次**；每个插件 / 独立应用另有自己的
`versionName / versionCode`，写在各自 `AndroidManifest.xml` 里。

## 未发布

**仓库整理（中度重构，行为不变）**

- 6 份重复的编译期桩合并为 `shared/plugin-sdk/plugin/`（插件）与
  `shared/plugin-sdk/app/`（独立应用运行时垫片）
- 5 份插件构建脚本合并为 `scripts/build-plugin.ps1`，2 份应用构建脚本合并为
  `scripts/build-app.ps1`；模块下的 `build.ps1` 变成一行包装
- 新增统一入口 `build.ps1 -Target … -Release`
- 新增 `scripts/bump-version.ps1`（同步改 versionName / versionCode）、
  `scripts/sync-db.ps1`（APK 的 MD5 / 大小写回 `launcher_new.db` + 重建 SHA256SUMS）、
  `scripts/pack-restore.ps1`（打恢复包）
- 签名统一到 `signing/tvdesk.jks`，首次构建自动生成；不再依赖“先构建一次 live”
- 新增 `docs/ARCHITECTURE.md`、`docs/README.md`、`release/README.md`、`signing/README.md`
- `app-host/build-app.ps1` 删除，逻辑并入 `scripts/build-app.ps1`
- 构建产物与签名不变：同名 APK 重新构建后除签名时间戳外内容一致

## v3.1.2 — 2026-11-14

**新闻 1.0.17 / 67 · 独立新闻 1.0.1 / 2**

- 修复：军事（凤凰军事）详情页来源行没有发布日期，只显示“凤凰军事”
- 列表页改为解析内嵌 JSON 的 `url … newsTime`，建立「文章 id → 时间」映射
- JSON 里没有的条目（轮播 / 推荐位）在抓正文时从 `article:published_time`、
  `time[itemProp=datePublished]` 补，抓到后即时刷新
- 时间统一显示为 `11月14日 06:20`；无日期时不留多余分隔点

## v3.1.1 — 2026-11-14

**股票 1.1.6 / 17**

- 修复：油价「下次调价」显示成上一次的调价日期
- 改用 soyoujia 调价日历里「今天之后、且晚于上次实际调价」的最近一条；
  日历取不到时退回「上次调价 + 10 个工作日」
- 缓存的 `#next` 已过期时忽略 6 小时缓存强制重抓

## v3.1.0 — 2026-11-13

**新增独立应用**

- 新闻 `com.dsh.tvdesk.news.app`（`news-app.apk`）
- 股票 `com.dsh.tvdesk.stock.app`（`stock-app.apk`）
- 页面代码与插件版共用；`app-host` 提供 `HostActivity` 与 support-v4 / launcher SDK 垫片
- 顶部留白从 96/100dp 收到 22dp；返回键退到分类条后再按一次退出应用

## v3.0.1 — 2026-11-05

- 修复：豆瓣荐片在启动瞬间抢焦点失败（`requestFocus` 前补 `getWindowToken()` 判断）

## v3.0.0 — 2026-11-03

**新闻 1.0.16 / 66**

- 语音桥地址从 `192.168.1.10` 改为 iStoreOS 网关 `192.168.1.22:8123`（电视是 DHCP）
- 实测不再出现 `edge fail, fallback baidu`，切条基本无缝

## v2.0.0 — 2026-11-03

**股票**

- 国际 / 国内 / 自选统一为近一年日 K，详情区按「确定」切当日分时
- 国际栏补成交量 / 成交额与表头；左右两栏宽度改为 1:1

## v1.0.0 — 2026-11-02

- 首次发布：新闻 / 直播 / 豆瓣荐片 / 电台 / 股票五个桌面 Tab 插件
- `release/` 提供恢复包、数据库模板与电视端 `restore.sh` / `check.sh` / `clear.sh`
- 文档补全插件机制逆向过程与踩坑表
