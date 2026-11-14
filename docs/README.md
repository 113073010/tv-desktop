# 文档索引

| 文档 | 内容 |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | 分层结构、构建链路、插件加载机制、发版流程、目录职责 |
| [STANDALONE.md](STANDALONE.md) | 新闻 / 股票独立应用的产物、宿主适配、构建与安装 |
| [RESTORE.md](RESTORE.md) | 刷机后恢复五个 Tab 的完整步骤、数据库字段、校验 |
| [DEVICE.md](DEVICE.md) | 真机实测记录：踩过的坑、逐条修复过程、电视 shell 限制 |
| [HISTORY.md](HISTORY.md) | 开发历程时间线：从逆向到五件套，按日期串起每个版本做了什么 |
| [edge-tts.md](edge-tts.md) | 局域网 edge-tts 桥服务部署（新闻语音朗读用） |

## 按任务找文档

- **想构建 / 改版本号 / 发版** → [ARCHITECTURE.md](ARCHITECTURE.md) 第 2、4 节，或根 `README.md`「构建」
- **改了代码但电视上没变化** → [ARCHITECTURE.md](ARCHITECTURE.md) 第 3 节第 4、5 条（dex 缓存）
- **电视刷机了，要装回来** → [RESTORE.md](RESTORE.md)
- **想确认某个现象是不是已知问题** → [DEVICE.md](DEVICE.md)，正文按版本号分节
- **想看看这个项目是怎么一步步做出来的** → [HISTORY.md](HISTORY.md)，按日期排的时间线
- **朗读没有声音 / 回退百度** → [edge-tts.md](edge-tts.md)

## 其他位置

- `CHANGELOG.md`（仓库根目录）：版本变更
- `release/README.md`：`release/` 里每个文件的作用
- `signing/README.md`：keystore 的来源与注意事项
