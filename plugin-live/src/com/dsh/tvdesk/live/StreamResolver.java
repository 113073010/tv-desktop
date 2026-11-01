package com.dsh.tvdesk.live;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 电信 IPTV 的 rtsp://...smil 其实是一个索引：服务器常常先回 302 跳转到另一台服务器，
 * 再返回 SMIL/SDP。Android 自带播放器两样都不认，会直接报 IO error (1, -1004)。
 * 这里用最小的 RTSP DESCRIBE 把重定向跟完，再把里面的真实媒体地址抠出来。
 */
public class StreamResolver {

    private static final Pattern RTSP_URL = Pattern.compile("rtsp://[^\\s\"'<>]+");
    private static final Pattern LOCATION = Pattern.compile("(?i)Location\\s*:\\s*(rtsp://[^\\s\\r\\n]+)");
    private static final Pattern STATUS = Pattern.compile("RTSP/1\\.0\\s+(\\d{3})");

    public static String resolve(String url) {
        if (url == null) return null;
        if (!url.startsWith("rtsp")) return url;
        if (!url.toLowerCase().endsWith(".smil")) return url;

        String current = url;
        for (int hop = 0; hop < 4; hop++) {
            Resp r = request(current);
            if (r == null) break;

            Matcher st = STATUS.matcher(r.text);
            int code = st.find() ? Integer.parseInt(st.group(1)) : 0;

            if (code >= 300 && code < 400) {
                Matcher loc = LOCATION.matcher(r.text);
                if (loc.find()) {
                    current = loc.group(1);
                    android.util.Log.i("TVDesk", "302 -> " + current);
                    continue;
                }
                android.util.Log.w("TVDesk", "302 without location");
                break;
            }

            Matcher m = RTSP_URL.matcher(r.body);
            while (m.find()) {
                String c = m.group();
                if (c.equals(current) || c.contains("_0.smil")) continue;
                android.util.Log.i("TVDesk", "resolved media -> " + c);
                return c;
            }
            // 有些服务器直接回 SDP，control 属性里带 trackID
            Matcher ctrl = Pattern.compile("a=control:(rtsp://[^\\s\\r\\n]+)").matcher(r.body);
            if (ctrl.find()) {
                android.util.Log.i("TVDesk", "resolved control -> " + ctrl.group(1));
                return ctrl.group(1);
            }
            // 电信 IPTV 常见：SDP 里 m=video 0 + 交织传输，没有独立 media url，
            // 这时把「重定向后的地址」交给播放器，播放器自己会 SETUP/PLAY。
            android.util.Log.i("TVDesk", "use redirect target: " + current);
            return current;
        }
        return current;
    }

    private static String head(String s) {
        int n = Math.min(240, s.length());
        return s.substring(0, n).replace('\r', ' ').replace('\n', '|');
    }

    static class Resp {
        String text;
        String body;
    }

    private static Resp request(String url) {
        Socket socket = null;
        try {
            URI u = new URI(url);
            String host = u.getHost();
            int port = u.getPort() > 0 ? u.getPort() : 554;
            if (host == null) return null;

            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 4000);
            socket.setSoTimeout(4500);

            String req = "DESCRIBE " + url + " RTSP/1.0\r\n"
                    + "CSeq: 1\r\n"
                    + "User-Agent: TvDesk/1.0\r\n"
                    + "Accept: application/sdp\r\n\r\n";
            OutputStream os = socket.getOutputStream();
            os.write(req.getBytes("UTF-8"));
            os.flush();

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            InputStream is = socket.getInputStream();
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                int n;
                try {
                    n = is.read(buf);
                } catch (java.net.SocketTimeoutException te) {
                    break;
                }
                if (n < 0) break;
                bos.write(buf, 0, n);
                if (bos.size() > 256 * 1024) break;
            }
            String all = new String(bos.toByteArray(), "UTF-8");
            Resp r = new Resp();
            r.text = all;
            int sep = all.indexOf("\r\n\r\n");
            r.body = sep >= 0 ? all.substring(sep + 4) : all;
            return r;
        } catch (Throwable t) {
            android.util.Log.w("TVDesk", "rtsp probe fail " + url, t);
            return null;
        } finally {
            try { if (socket != null) socket.close(); } catch (Exception ignored) { }
        }
    }
}
