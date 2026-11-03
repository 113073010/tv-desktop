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
