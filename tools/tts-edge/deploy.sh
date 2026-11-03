#!/bin/sh
# ============================================================
# 一键部署 edge-tts 男声 HTTP 桥（iStoreOS / OpenWrt + Docker）
# 用法：把本脚本放到 OpenWrt 上（如 /root/），然后执行：
#       sh deploy.sh
# 会自动：建目录 -> 写服务源码 -> 构建镜像 -> 启动容器(开机自启) -> 自检
# 容器：tts-edge / 端口 8123 / --restart=unless-stopped
# ============================================================
set -e

DIR=/root/tts-edge
PORT=8123
echo "==> [1/4] 创建目录 $DIR"
mkdir -p "$DIR"

echo "==> [2/4] 写入服务脚本与 Dockerfile"
cat > "$DIR/tts_server.py" <<'PY'
# -*- coding: utf-8 -*-
# edge-tts HTTP 桥接服务：电视/任何内网设备 GET /tts?voice=..&text=.. 取 mp3
import asyncio, io, urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import edge_tts

# 别名 -> 完整 voice name；也接受直接传完整 name
VOICES = {
    "yunxi":    "zh-CN-YunxiNeural",
    "yunjian":  "zh-CN-YunjianNeural",
    "xiaoxiao": "zh-CN-XiaoxiaoNeural",
    "yunyang":  "zh-CN-YunyangNeural",
    "yunxia":   "zh-CN-YunxiaNeural",
    "xiaoyi":   "zh-CN-XiaoyiNeural",
}

class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        try:
            u = urllib.parse.urlparse(self.path)
            q = urllib.parse.parse_qs(u.query)
            text = q.get("text", [""])[0]
            v = q.get("voice", ["yunxi"])[0]
            rate = q.get("rate", ["+0%"])[0]
            pitch = q.get("pitch", ["+0Hz"])[0]
            full = VOICES.get(v.lower(), v)
            if not text:
                self.send_response(400); self.end_headers(); return
            mp3 = asyncio.run(self.synth(text, full, rate, pitch))
            self.send_response(200)
            self.send_header("Content-Type", "audio/mpeg")
            self.send_header("Content-Length", str(len(mp3)))
            self.end_headers()
            self.wfile.write(mp3)
        except Exception as ex:
            body = ("edge tts error: %r" % ex).encode("utf-8", "replace")
            self.send_response(500)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    async def synth(self, text, voice, rate, pitch):
        c = edge_tts.Communicate(text, voice, rate=rate, pitch=pitch)
        b = io.BytesIO()
        async for chunk in c.stream():
            if chunk["type"] == "audio":
                b.write(chunk["data"])
        return b.getvalue()

if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8123), H).serve_forever()
PY

cat > "$DIR/Dockerfile" <<'EOF'
FROM python:3-alpine
WORKDIR /app
COPY tts_server.py /app/tts_server.py
RUN pip install --no-cache-dir edge-tts
EXPOSE 8123
CMD ["python", "/app/tts_server.py"]
EOF

echo "==> [3/4] 构建镜像 tts-edge（首次需拉基础镜像+装 edge-tts，约几分钟）"
cd "$DIR"
docker build -t tts-edge .

echo "==> [4/4] 启动容器（--restart=unless-stopped 开机自启）"
docker rm -f tts-edge 2>/dev/null || true
docker run -d --name tts-edge --restart=unless-stopped -p ${PORT}:${PORT} tts-edge

echo "==> 自检"
sleep 4
docker ps --format '{{.Names}} {{.Status}}' | grep tts-edge || { echo "容器未运行"; exit 1; }
curl -s -o /tmp/t1.mp3 -w 'HTTP %{http_code}  bytes=%{size_download}\n' \
  "http://127.0.0.1:${PORT}/tts?voice=yunxi&text=hello"
echo "==> 部署完成。桥地址：http://<OPENWRT_IP>:${PORT}/tts?voice=yunxi&text=..."
