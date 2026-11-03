# 给电视新闻配个微软男声：OpenWrt（iStoreOS）上的 edge-tts 桥

电视上的新闻插件想用微软 Edge 的中文男声，但电视是 Android 5，系统 TLS 的 ClientHello 指纹会被微软服务直接 403，改代码绕不过去——同样的请求在电脑上能通，在电视上握手就断。

家里的 iStoreOS 本来就 7×24 开着，所以干脆在它上面跑一个很小的 HTTP 服务：电视用局域网请求它，它去调微软，把 mp3 回给电视。这样电视不依赖常开的电脑，只依赖已经常开的路由器。

文中的 `<OPENWRT_IP>` 换成你路由器的局域网地址，比如 192.168.1.10。密码不在文档里，用你自己的 root 密码。

## 需要什么

一台能跑 Docker 的 OpenWrt / iStoreOS（aarch64 上实测通过，别的架构也行），和电视在同一网段，8123 端口没被占用。

## 部署

推荐用脚本，两条命令：

```sh
scp deploy.sh root@<OPENWRT_IP>:/root/
ssh root@<OPENWRT_IP> "sh /root/deploy.sh"
```

脚本依次做四件事：建 `/root/tts-edge`、写入 `tts_server.py` 和 `Dockerfile`、`docker build`、起容器（`--restart=unless-stopped`，开机自启），最后自己 curl 一次自检。看到 `HTTP 200 bytes=1xxxx` 就是好了。

服务源码是脚本里用 here-doc 直接写进去的，不下载任何外部文件，离线也能装。

想手动来一遍的话，等价操作是：

```sh
ssh root@<OPENWRT_IP>
mkdir -p /root/tts-edge && cd /root/tts-edge
# 把 tts_server.py 和 Dockerfile 放进来（内容见 tools/tts-edge/）
docker build -t tts-edge .
docker rm -f tts-edge 2>/dev/null
docker run -d --name tts-edge --restart=unless-stopped -p 8123:8123 tts-edge
```

## 验证

先在路由器本机试：

```sh
curl -s -o /tmp/t1.mp3 -w '%{http_code} %{size_download}\n' \
  'http://127.0.0.1:8123/tts?voice=yunxi&text=hello'
```

再到电视上试（电视自带 curl，这一步验证的是「电视 → 路由器 → 微软」整条链路）：

```sh
curl -s -o /tmp/t2.mp3 -w '%{http_code} %{size_download}\n' \
  'http://<OPENWRT_IP>:8123/tts?voice=yunxi&text=hello'
```

正常都返回 200，文件十来 KB。几个音色的实测大小：yunxi 约 12.5 KB，yunjian 约 12.7 KB，xiaoxiao 约 11.7 KB。

## 接口

`GET http://<OPENWRT_IP>:8123/tts`

| 参数 | 说明 | 默认 |
|---|---|---|
| `voice` | 别名或完整的 voice name | `yunxi` |
| `text` | 要合成的文本，需要 URL 编码 | 必填 |
| `rate` | 语速，如 `+20%`、`-20%` | `+0%` |
| `pitch` | 音高，如 `+20Hz` | `+0Hz` |

返回 `audio/mpeg` 的 mp3 二进制。别名有 yunxi / yunjian / xiaoxiao / yunyang / yunxia / xiaoyi，也可以直接传 `zh-CN-YunxiNeural` 这种完整名字。

## 电视插件怎么调

新闻插件的「朗读设置」里引擎选「微软 Edge」，语速会换算成 rate 一起传过来（x0.8 是 -20%，x1.5 是 +50%）：

```java
String url = "http://<OPENWRT_IP>:8123/tts?voice=" + encode(voice)
        + "&rate=" + encode(rate)
        + "&text=" + encode(text);
```

每段合成到一个独立的 mp3 文件再播，播完就删；缓存目录另有 8 MB 上限，不会把电视的存储塞满。

## 日常维护

```sh
docker logs --tail 50 tts-edge     # 看日志
docker ps | grep tts-edge          # 看容器在不在
docker restart tts-edge            # 重启
```

改过 `tts_server.py` 之后要重新构建：

```sh
cd /root/tts-edge && docker build -t tts-edge .
docker rm -f tts-edge
docker run -d --name tts-edge --restart=unless-stopped -p 8123:8123 tts-edge
```

不要了：

```sh
docker rm -f tts-edge && docker rmi tts-edge
```

## 常见问题

**电视上没声音，插件 logcat 里是 `edge http fail` 或者 `edge fail, fallback baidu`**

说明桥没通，插件这时候会自动退回百度在线，所以不会完全没声。先在电视上 curl 一下：电视能通就查插件里的地址和编码，电视也不通就看 `docker ps` 里容器在不在、电视和路由器是不是同一网段。

**路由器重启后没声音**

确认容器是 `--restart=unless-stopped`。如果之前手动 `docker stop` 过，它不会自己起来，`docker start tts-edge` 就行。

**改了 voice 名字不生效**

别名表在 `tts_server.py` 开头的 `VOICES` 里；也可以直接传完整的 voice name。
