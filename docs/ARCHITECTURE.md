# 架构

一句话：**一份页面源码，两种宿主**。同一套 `plugin-*/src` 既能被乐视桌面的插件机制加载成 Tab，
也能和 `app-host` 一起打成独立 APK。

```
                     ┌─────────────────────────┐
                     │  plugin-news/src        │  ← 页面代码（列表/详情/朗读/图表…）
                     │  plugin-stock/src  …    │
                     └───────────┬─────────────┘
                                 │
              ┌──────────────────┴───────────────────┐
              │                                      │
   ┌──────────▼───────────┐              ┌───────────▼─────────────┐
   │ 插件宿主：乐视桌面    │              │ 独立宿主：app-host       │
   │ com.stv.launcher     │              │ HostActivity（普通 APK） │
   │ DexClassLoader 加载   │              │ 自己驱动 Fragment 生命周期│
   └──────────┬───────────┘              └───────────┬─────────────┘
              │                                      │
   运行时由桌面提供：                    构建时静态链接：
   com.stv.launcher.sdk                  shared/plugin-sdk/app/
   android.support.v4                    （Fragment / BaseFragment / ActivityActionHandler 垫片）
```

## 1. 源码分层

| 层 | 位置 | 说明 |
|---|---|---|
| 页面代码 | `plugin-*/src/com/dsh/tvdesk/<name>/` | 真正的业务实现，插件版和独立版共用 |
| 插件入口 | `plugin-*/src/.../CreateApi.java` | 只给 launcher 反射用，实现 `ILePluginCreateApi`，独立版构建时排除 |
| 编译期桩 | `shared/plugin-sdk/plugin/` | 桌面 `com.stv.launcher.sdk`、`android.support.v4` 的最小签名，仅用于 javac，**不进 dex** |
| 运行时垫片 | `shared/plugin-sdk/app/` | 与上面同名类，但**有实现**：`Fragment` 持有 Activity，`BaseFragment` 补生命周期；只在独立应用构建时编译进去 |
| 独立宿主 | `app-host/src/com/dsh/tvdesk/app/HostActivity.java` | `onCreate → onAttach → onInflaterContent`，`onResume/onPause → onFragmentShowChanged`，`onDestroy → onDestroyView → onDetach` |
| 独立入口 | `app-*/src/.../…Activity.java` + `res/` | 图标、banner、`SharedPreferences` 命名空间 |

两份桩**内容确实不同**（一份是空壳，一份带实现），所以没有合并成一份，而是并列放在
`shared/plugin-sdk/{plugin,app}`，避免以前「5 个插件 + app-host 各存一份」的六重复制。

## 2. 构建链路

`build.ps1` 只做分发，实际逻辑在 `scripts/`：

```
scripts/build-plugin.ps1 -Project plugin-news [-OutputName news.apk] [-Release]
  1. javac shared/plugin-sdk/plugin  → build/stubs.jar
  2. javac plugin-news/src           → build/classes（classpath 含 stubs.jar）
  3. d8 --min-api 21                 → build/dex/classes.dex
  4. aapt2 link --manifest …         → build/base.apk
  5. 把 classes.dex 塞进 base.apk，zipalign
  6. apksigner（signing/tvdesk.jks）  → build/news.apk

scripts/build-app.ps1 -Project app-news -Plugin plugin-news -OutputName news-app.apk [-Release]
  1. 收集源码：shared/plugin-sdk/app + app-host/src + app-news/src
                + plugin-news/src（排除 CreateApi.java）
  2. javac → d8 → aapt2 compile(res/) → aapt2 link → 塞 dex → zipalign → apksigner
```

`plugin-live` 是唯一有额外依赖的插件：编译和 d8 阶段会带上仓库同级的
`ijk/ijkplayer-classes.jar`，并把 `ijk/lib/*.so` 打成 `lib/armeabi-v7a/` 塞进 APK
（不压缩存储）。缺这两个目录时只有 live 构建失败，其他插件不受影响。

`plugin-*/build.ps1` 和 `app-*/build.ps1` 都是三行以内的包装，保持「在模块目录里直接构建」
的老习惯，同时只有一份实现。

## 3. 插件加载机制（逆向结论）

1. 桌面启动时读 `launcher_new.db` 的 `SCREEN_INFO`，按 `SCREEN_ORDER` 排 Tab；
   `PLUGIN_PATH` 指向 `files/plugins/*.apk`。
2. 桌面用 `DexClassLoader` 加载 APK，读 manifest 的 `meta-data`：
   `ILePluginCreateApi` = 入口类全名，然后反射 `create()` 拿到一个 Fragment。
3. 插件容器驱动 Fragment 的生命周期；`android.support.v4` 和 `com.stv.launcher.sdk`
   由桌面的 ClassLoader 提供，所以插件里不能自带这两个包（编译期用桩，打包时不带）。
4. 桌面缓存解出来的 dex 到 `files/plugins_data/<包名>/dex/*.dex`。**换了 APK 但
   `SCREEN_INFO.MD5/FILE_SIZE` 没变，桌面就继续用旧缓存**——这是最常见的「改了没生效」。
5. 替换 APK 时桌面进程可能持有旧文件句柄，需要 `kill -9` 主进程（`am force-stop`
   在这台机器上杀不掉），或者删掉对应 `plugins_data/<包名>` 目录。

## 4. 发布流程

```powershell
.\scripts\bump-version.ps1 -Target all
.\build.ps1 -Target all -Release
.\scripts\sync-db.ps1                      # launcher_new.db + SHA256SUMS.txt
.\scripts\pack-restore.ps1 -Tag vX.Y.Z     # build/tv-desktop-vX.Y.Z-restore.zip
git add -A; git commit -m "…"; git tag -a vX.Y.Z -m "…"
git push origin main; git push origin vX.Y.Z
```

然后在 GitHub 上建 Release，附件固定三个：

| 附件 | 来源 |
|---|---|
| `news-app.apk` / `stock-app.apk`（按需） | `release/` |
| `news.apk` / … 改动的插件 | `release/` |
| `tv-desktop-vX.Y.Z-restore.zip` | `pack-restore.ps1` 产物 |

`release/` 保持**扁平**：`restore.sh` / `check.sh` 会在自己所在目录里找同级的
`*.apk` 和 `launcher_new.db`，所以恢复包就是把这些文件平铺进一个 zip，整目录
`adb push` 到电视即可。这也是没有把脚本拆进 `deploy/` 子目录的原因——拆了就得改
电视端脚本的路径解析，收益不如风险。

## 5. 目录职责边界

| 目录 | 放什么 | 不放什么 |
|---|---|---|
| `scripts/` | 所有 PC 端 PowerShell 构建/发布脚本 | 电视端 shell 脚本 |
| `release/` | 签名产物 + 数据库模板 + 电视端脚本 + 校验和 | 源码、keystore |
| `signing/` | `tvdesk.jks`（gitignore） | 产物 |
| `docs/` | 逆向笔记、运维手册 | 可以运行时生成的东西 |
| `shared/` | 跨模块的编译期/运行时垫片 | 业务代码 |

公共业务代码（HTTP、HTML 解码、日志）目前仍在各插件内各自持有，尚未抽公共模块：
抽取会牵动 5 个插件 + 2 个独立应用的编译路径，属于下一档重构的范围。
