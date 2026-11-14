# 新闻 / 股票独立应用

把原来的两个桌面 Tab 插件改成普通 APK：装完在电视的应用列表里启动，不依赖 `com.stv.launcher` 的插件机制。

## 产物

| 应用 | 包名 | Activity | 源码 | APK |
|---|---|---|---|---|
| 新闻 | `com.dsh.tvdesk.news.app` | `com.dsh.tvdesk.news.app.NewsActivity` | `app-news/` | `release/news-app.apk` |
| 股票 | `com.dsh.tvdesk.stock.app` | `com.dsh.tvdesk.stock.app.StockActivity` | `app-stock/` | `release/stock-app.apk` |

插件版（`plugin-news` / `plugin-stock`）保留，两者可以同时安装。

## 结构

```
shared/plugin-sdk/app/
  android/support/v4/app/Fragment.java                    support-v4 Fragment 垫片（有实现：持有 Activity）
  com/stv/launcher/sdk/fragment/BaseFragment.java         桌面 BaseFragment 的运行时垫片
  com/stv/launcher/sdk/plugin/ActivityActionHandler.java
app-host/
  src/com/dsh/tvdesk/app/HostActivity.java                独立应用的宿主
app-news/
  AndroidManifest.xml
  src/com/dsh/tvdesk/news/app/NewsActivity.java
  res/drawable-nodpi/{ic_launcher,banner}.png
  build.ps1                                               转发到 scripts/build-app.ps1
app-stock/
  ... 同上
scripts/build-app.ps1                                     两个应用共用的构建流水线
```

页面代码没有复制：`app-news` 编译时直接把 `plugin-news/src` 拿进来（排除只给 launcher 反射用的 `CreateApi.java`），股票同理。

> 插件版的同名类在 `shared/plugin-sdk/plugin/`，那是**空壳**（只有方法签名，用来过 javac）；
> 独立应用用的这份**带实现**。两份内容不同，所以分开存放而不是共用一份。

## 宿主做了什么

桌面里 Fragment 的生命周期由 launcher 的插件容器驱动；独立应用里由 `HostActivity` 补齐：

| 时机 | 调用 |
|---|---|
| `onCreate` | `onAttach(activity)` → `onInflaterContent(...)` → 塞进窗口 |
| `onResume` | `onFragmentShowChanged(true)`（股票在这里开始拉行情） |
| `onPause` | `onFragmentShowChanged(false)`（新闻在这里停止朗读） |
| `onDestroy` | `onDestroyView()` → `onDetach()` |

另外两处针对独立应用的适配：

- 插件页面顶部留了 96/100dp 给桌面 Tab 条，独立版在 `HostActivity` 里压到 22dp
- 页面返回键退到分类条后，`goToTabBar()` 发现宿主实现了 `StockFragment.Host` / `NewsFragment.Host`，就调用 `finish()` 退出；桌面插件宿主不实现这个接口，行为不变

## 构建

和插件一样需要 JDK 17 + Android build-tools 34：

```powershell
.\app-news\build.ps1
.\app-stock\build.ps1
```

或者从仓库根目录一起构建：

```powershell
.\build.ps1 -Target news-app
.\build.ps1 -Target stock-app -Release
```

模块目录下的 `build.ps1` 只是包装，实际逻辑在 `scripts/build-app.ps1`。
工具链默认找仓库同级（或任意上级目录）的 `tools\android-sdk` 和 `tools\jdk`；也可以设置环境变量 `TVTOOLS` 指向包含这两个目录的位置。

产物在 `app-news\build\news-app.apk` / `app-stock\build\stock-app.apk`，加 `-Release` 会复制到 `release\`：

```powershell
.\app-news\build.ps1 -Release
.\app-stock\build.ps1 -Release
```

签名统一用 `signing\tvdesk.jks`（和插件同一把钥匙），找不到时自动生成一个开发用 keystore。换机器构建时要保留同一个 keystore，否则覆盖安装会报签名不一致。

## 安装

```bash
adb install -r release/news-app.apk
adb install -r release/stock-app.apk
```

装完从电视的应用列表启动。桌面顶部 Tab 栏不会多出这两项（独立版没有插件 `meta-data`）。

## 和插件版的差异

- 新闻的语音设置存在 `com.dsh.tvdesk.news.app` 自己的 `SharedPreferences` 里，和插件版（存在 launcher 的 `tvdesk_news`）不互通
- 自选股 `/sdcard/tvdesk/自选.txt`、油价缓存 `/sdcard/tvdesk/oil.cache` 仍然共用
- 返回键在分类条上再按一次退出应用，而不是切回桌面 Tab
