# release/

**这个目录是「可以直接搬上电视」的部署包**，保持扁平：电视端脚本会在自己所在目录里
找同级的 `*.apk` 和 `launcher_new.db`，所以不要加子目录、不要改名。

## 文件

| 文件 | 用途 |
|---|---|
| `news.apk` | 新闻 Tab 插件 `com.dsh.tvdesk.news` |
| `live.apk` | 直播 Tab 插件 `com.dsh.tvdesk.live` |
| `douban.apk` | 豆瓣荐片 Tab 插件 `com.dsh.tvdesk.douban` |
| `radio.apk` | 电台 Tab 插件 `com.dsh.tvdesk.radio` |
| `stock.apk` | 股票 Tab 插件 `com.dsh.tvdesk.stock` |
| `news-app.apk` | 新闻独立应用 `com.dsh.tvdesk.news.app`（普通 APK，`adb install`） |
| `stock-app.apk` | 股票独立应用 `com.dsh.tvdesk.stock.app` |
| `launcher_new.db` | 桌面数据库模板，`SCREEN_INFO` 已登记五个插件 |
| `launcher_clean.db` | 「只剩首页 / 应用」的干净库，`clear.sh` 用它 |
| `restore.sh` | 电视端：校验并安装五个插件 + 覆盖数据库 + 重启桌面 |
| `restore.bat` | PC 端：Windows 上一键 push + 执行 `restore.sh` |
| `check.sh` | 电视端：只读体检（root、版本、表结构、MD5 / 大小与 APK 是否一致） |
| `clear.sh` | 电视端：彻底卸载五个 Tab 及残留 |
| `clean-dev.sh` | 电视端：清理调试残留文件 |
| `check-db.py` | PC 端：核对 / 修正 `launcher_new.db` 与 APK 的元数据（需 Python） |
| `SHA256SUMS.txt` | 上面所有 APK 的 SHA256，`sha256sum -c` 可校验 |

## 同步与发版

APK 变了就必须同步数据库，否则桌面继续用旧的 dex 缓存：

```powershell
.\scripts\sync-db.ps1             # 需要 Python 的话：python release/check-db.py --fix
.\scripts\pack-restore.ps1 -Tag vX.Y.Z
```

`sync-db.ps1` 会重算每个 APK 的 MD5 / 字节数写回 `SCREEN_INFO`，并重建
`SHA256SUMS.txt`；`pack-restore.ps1` 把本目录摊平打成一个 zip，作为 GitHub Release 附件。

## 注意

- 这里只有**签名后的产物**，源码不在这里；构建见仓库根 `README.md`。
- 覆盖数据库前先跑 `check.sh`，它会在 MD5 / 大小不一致时拒绝执行（`--force` 可跳过）。
- 刷机恢复的完整步骤见 `docs/RESTORE.md`。
