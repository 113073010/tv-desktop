package com.dsh.tvdesk.stock;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 行情数据源（公开接口，无需密钥）
 *   股票 / 指数 / 国际指数：腾讯 qt.gtimg.cn（GBK 文本）
 *   分时：腾讯 web.ifzq.gtimg.cn（JSON）
 *   基金：天天基金 fundmobapi.eastmoney.com（JSON）
 */
public class QuoteApi {

    private static final javax.net.ssl.SSLSocketFactory TRUST_ALL = buildTrustAll();

    private static javax.net.ssl.SSLSocketFactory buildTrustAll() {
        try {
            javax.net.ssl.SSLContext c = javax.net.ssl.SSLContext.getInstance("TLS");
            c.init(null, new javax.net.ssl.TrustManager[]{new javax.net.ssl.X509TrustManager() {
                @Override public void checkClientTrusted(java.security.cert.X509Certificate[] x, String a) { }
                @Override public void checkServerTrusted(java.security.cert.X509Certificate[] x, String a) { }
                @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new java.security.cert.X509Certificate[0];
                }
            }}, new java.security.SecureRandom());
            return c.getSocketFactory();
        } catch (Throwable t) {
            return null;
        }
    }

    static class Item {
        String code = "";
        String name = "";
        String displayCode = "";
        float price, prevClose, open, high, low, pct, change;
        String time = "";
        String volTxt = "";
        String amtTxt = "";
        boolean fund = false;
        int kind = 0;   // 0=股票/指数 1=黄金 2=油价
        boolean stale = false;   // 非交易时段，价格取昨收
        String extra1, extra2, extra3, extra4;   // 油价：95 / 98 / 0# / 调价日期
        String navDate = "";
    }

    private static byte[] fetch(String url, String referer, int timeoutMs) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            if (c instanceof javax.net.ssl.HttpsURLConnection && TRUST_ALL != null) {
                ((javax.net.ssl.HttpsURLConnection) c).setSSLSocketFactory(TRUST_ALL);
                ((javax.net.ssl.HttpsURLConnection) c).setHostnameVerifier(
                        new javax.net.ssl.HostnameVerifier() {
                            @Override public boolean verify(String h, javax.net.ssl.SSLSession s) { return true; }
                        });
            }
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 5.0.1) Chrome/60.0");
            if (referer != null) c.setRequestProperty("Referer", referer);
            InputStream is = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            return bos.toByteArray();
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---------------- 股票 / 指数 / 国际 ----------------

    static List<Item> fetchStocks(List<String> codes) {
        List<Item> out = new ArrayList<Item>();
        if (codes == null || codes.isEmpty()) return out;
        StringBuilder q = new StringBuilder();
        for (int i = 0; i < codes.size() && i < 40; i++) {
            if (i > 0) q.append(',');
            q.append(codes.get(i));
        }
        try {
            byte[] raw = fetch("https://qt.gtimg.cn/q=" + q, "https://gu.qq.com/", 8000);
            String text = new String(raw, "GBK");
            for (String line : text.split(";")) parseLine(line, out);
        } catch (Throwable t) {
            android.util.Log.w("TVDeskStock", "quote fail " + t);
        }
        return out;
    }

    private static void parseLine(String line, List<Item> out) {
        int eq = line.indexOf('=');
        if (eq < 0) return;
        String left = line.substring(0, eq).trim();
        int vIdx = left.indexOf('_');
        if (vIdx < 0) return;
        String code = left.substring(vIdx + 1).trim();
        String body = line.substring(eq + 1).trim();
        if (body.startsWith("\"")) body = body.substring(1);
        if (body.endsWith("\"")) body = body.substring(0, body.length() - 1);
        String[] f = body.split("~");
        if (f.length < 6 || f[1].length() == 0) return;

        Item it = new Item();
        it.code = code;
        it.name = f[1];
        it.displayCode = f[2].length() > 0 ? f[2] : code;
        it.price = num(f, 3);
        it.prevClose = num(f, 4);
        it.open = num(f, 5);
        it.change = num(f, 31);
        it.pct = num(f, 32);
        it.high = num(f, 33);
        it.low = num(f, 34);
        if (f.length > 30) it.time = f[30];
        it.volTxt = fmtVol(num(f, 36));
        it.amtTxt = fmtAmt(num(f, 37));
        out.add(it);
    }

    private static float num(String[] f, int i) {
        if (i >= f.length) return 0f;
        try {
            String s = f[i].trim();
            return s.length() == 0 ? 0f : Float.parseFloat(s);
        } catch (Throwable t) {
            return 0f;
        }
    }

    static String fmtVol(float hand) {
        if (hand <= 0) return "--";
        if (hand >= 10000) return String.format(java.util.Locale.US, "%.2f万手", hand / 10000f);
        return String.format(java.util.Locale.US, "%.0f手", hand);
    }

    static String fmtAmt(float wan) {
        if (wan <= 0) return "--";
        if (wan >= 10000) return String.format(java.util.Locale.US, "%.2f亿", wan / 10000f);
        return String.format(java.util.Locale.US, "%.0f万", wan);
    }

    // ---------------- 分时 ----------------

    static class Minute {
        List<Float> prices = new ArrayList<Float>();
        float prevClose;
        String date = "";
    }

    /** 分时接口：先 https，失败再 http（Android 5 的 TLS 有时握不上手） */
    private static byte[] fetchMinuteRaw(String code) throws Exception {
        String path = "/appstock/app/minute/query?code=" + code;
        String last = "";
        for (String scheme : new String[]{"https", "http"}) {
            try {
                byte[] raw = fetch(scheme + "://web.ifzq.gtimg.cn" + path, "https://gu.qq.com/", 8000);
                if (raw != null && raw.length > 20) {
                    Diag.log("minute", scheme + " ok " + raw.length + "B code=" + code);
                    return raw;
                }
                last = "empty";
            } catch (Throwable t) {
                last = t.getClass().getSimpleName() + ":" + t.getMessage();
                Diag.log("minute", scheme + " fail " + last);
            }
        }
        throw new Exception("both schemes failed: " + last);
    }

    static Minute fetchMinute(String code) {
        Minute m = new Minute();
        try {
            byte[] raw = fetchMinuteRaw(code);
            if (parseTencent(raw, code, m)) {
                Diag.log("minute", "tencent ok code=" + code + " points=" + m.prices.size());
                cachePut(code, m);
                return m;
            }
        } catch (Throwable t) {
            Diag.log("minute", "tencent fail " + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
        // 腾讯被限流时换东财
        try {
            Minute em = fetchMinuteEastmoney(code);
            if (em != null && em.prices.size() > 1) {
                Diag.log("minute", "eastmoney ok code=" + code + " points=" + em.prices.size());
                cachePut(code, em);
                return em;
            }
        } catch (Throwable t) {
            Diag.log("minute", "eastmoney fail " + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
        return m;
    }

    private static boolean parseTencent(byte[] raw, String code, Minute m) {
        try {
            JSONObject root = new JSONObject(new String(raw, "UTF-8"));
            JSONObject data = root.optJSONObject("data");
            if (data == null) return false;
            JSONObject one = data.optJSONObject(code);
            if (one == null) return false;
            JSONObject d = one.optJSONObject("data");
            if (d == null) return false;
            m.date = d.optString("date");
            JSONArray arr = d.optJSONArray("data");
            if (arr == null) return false;
            for (int i = 0; i < arr.length(); i++) {
                String[] p = arr.optString(i).split(" ");
                if (p.length >= 2) {
                    try { m.prices.add(Float.parseFloat(p[1])); } catch (Throwable ignored) { }
                }
            }
            JSONObject qt = one.optJSONObject("qt");
            if (qt != null) {
                JSONArray qa = qt.optJSONArray(code);
                if (qa != null && qa.length() > 4) {
                    try { m.prevClose = Float.parseFloat(qa.optString(4)); } catch (Throwable ignored) { }
                }
            }
            return m.prices.size() > 1;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 腾讯代码 → 东财 secid：sh600519→1.600519，sz000858→0.000858，hkHSI→100.HSI */
    private static String toSecid(String code) {
        if (code == null) return null;
        String c = code.toLowerCase(java.util.Locale.US);
        if (c.startsWith("hk")) return "100." + code.substring(2).toUpperCase(java.util.Locale.US);
        if (c.startsWith("sh")) return "1." + code.substring(2).toUpperCase(java.util.Locale.US);
        if (c.startsWith("sz")) return "0." + code.substring(2).toUpperCase(java.util.Locale.US);
        // 国际指数：新浪代码 → 东财 secid
        return worldSecid(code);
    }

    // 新浪/腾讯国际指数代码 → 东财 secid（全项目唯一一份，StockFragment 也调这里）
    static String worldSecid(String code) {
        if (code == null) return null;
        if (code.equals("int_nikkei") || code.equals("znb_NKY")) return "100.N225";
        if (code.equals("znb_KOSPI")) return "100.KS11";
        if (code.equals("znb_DAX")) return "100.GDAXI";
        if (code.equals("int_ftse") || code.equals("znb_FTSE")) return "100.FTSE";
        if (code.equals("znb_TWSE")) return "100.TWII";
        if (code.equals("znb_SENSEX") || code.equals("znb_NIFTY")) return "100.SENSEX";
        if (code.equals("znb_AS51")) return "100.AORD";
        if (code.equals("usINX")) return "100.SPX";
        if (code.equals("hkHSI")) return "100.HSI";
        if (code.equals("sh000300")) return "1.000300";
        return null;
    }

    private static Minute fetchMinuteEastmoney(String code) throws Exception {
        String secid = toSecid(code);
        if (secid == null) return null;
        String url = "http://push2his.eastmoney.com/api/qt/stock/trends2/get"
                + "?secid=" + secid + "&fields1=f1,f2,f3,f4&fields2=f51,f53&iscr=0&ndays=1";
        byte[] raw = fetch(url, "https://quote.eastmoney.com/", 8000);
        JSONObject root = new JSONObject(new String(raw, "UTF-8"));
        JSONObject data = root.optJSONObject("data");
        if (data == null) return null;
        Minute m = new Minute();
        m.date = data.optString("name");
        JSONArray tr = data.optJSONArray("trends");
        if (tr == null) return null;
        for (int i = 0; i < tr.length(); i++) {
            String[] p = tr.optString(i).split(",");
            if (p.length >= 2) {
                try { m.prices.add(Float.parseFloat(p[1])); } catch (Throwable ignored) { }
            }
        }
        return m;
    }

    // 20 秒内同一代码不重复请求，减少被限流的概率
    private static final java.util.Map<String, Minute> CACHE =
            new java.util.HashMap<String, Minute>();
    private static final java.util.Map<String, Long> CACHE_AT =
            new java.util.HashMap<String, Long>();

    static Minute cachedMinute(String code) {
        Long at = CACHE_AT.get(code);
        if (at == null) return null;
        if (System.currentTimeMillis() - at > 20_000L) return null;
        return CACHE.get(code);
    }

    private static void cachePut(String code, Minute m) {
        CACHE.put(code, m);
        CACHE_AT.put(code, System.currentTimeMillis());
    }

    // ---------------- 黄金 / 外盘（腾讯 hf_*） ----------------

    /**
     * 外盘格式：
     * v_hf_GC="3905.20,0.16,买,卖,最高,最低,时间,昨收,今开,...,日期,名称"
     */
    static List<Item> fetchHf(List<String> codes) {
        List<Item> out = new ArrayList<Item>();
        if (codes == null || codes.isEmpty()) return out;
        StringBuilder q = new StringBuilder();
        for (int i = 0; i < codes.size(); i++) {
            if (i > 0) q.append(',');
            q.append(codes.get(i));
        }
        try {
            byte[] raw = fetch("https://qt.gtimg.cn/q=" + q, "https://gu.qq.com/", 8000);
            for (String line : new String(raw, "GBK").split(";")) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String body = line.substring(eq + 1).trim();
                if (body.startsWith("\"")) body = body.substring(1);
                if (body.endsWith("\"")) body = body.substring(0, body.length() - 1);
                String[] f = body.split(",");
                if (f.length < 14) continue;
                Item it = new Item();
                it.code = line.substring(0, eq).replace("v_", "").trim();
                it.name = f[13].trim();
                it.displayCode = it.name;
                it.price = num2(f, 0);
                it.pct = num2(f, 1);
                it.high = num2(f, 4);
                it.low = num2(f, 5);
                it.prevClose = num2(f, 7);
                it.open = num2(f, 8);
                it.change = it.price - it.prevClose;
                it.time = (f.length > 12 ? f[12] : "") + " " + (f.length > 6 ? f[6] : "");
                it.volTxt = "--";
                it.amtTxt = "--";
                out.add(it);
            }
        } catch (Throwable t) {
            Diag.log("hf", "fail " + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
        return out;
    }

    private static float num2(String[] f, int i) {
        if (i >= f.length) return 0f;
        try {
            String s = f[i].trim();
            return s.length() == 0 ? 0f : Float.parseFloat(s);
        } catch (Throwable t) {
            return 0f;
        }
    }

    /**
     * 东财单条行情（黄金现货 / 期货用）
     * f43 现价 / f60 昨收 / f169 涨跌额 / f170 涨跌幅，内部放大 100 倍
     */
    static Item fetchEastQuote(String secid, String code, String fallbackName) {
        return fetchEastQuote(secid, code, fallbackName, 100f);
    }

    static Item fetchEastQuote(String secid, String code, String fallbackName, float scale) {
        Item it = new Item();
        it.code = code;
        it.name = fallbackName;
        it.displayCode = fallbackName;
        it.volTxt = "--";
        it.amtTxt = "--";
        try {
            String url = "http://push2.eastmoney.com/api/qt/stock/get?secid=" + secid
                    + "&fields=f43,f57,f58,f60,f169,f170,f44,f45,f46";
            byte[] raw = fetch(url, "https://quote.eastmoney.com/", 8000);
            JSONObject root = new JSONObject(new String(raw, "UTF-8"));
            JSONObject d = root.optJSONObject("data");
            if (d == null) return it;
            String nm = d.optString("f58");
            if (nm != null && nm.length() > 0) { it.name = nm; it.displayCode = nm; }
            double live = d.optDouble("f43", 0);
            if (live == 0) {                 // 非交易时段（如期货日盘收盘后、夜盘开盘前）
                live = d.optDouble("f60", 0); // 退回昨收
                it.stale = true;
            }
            it.price = (float) (live / scale);
            it.prevClose = (float) (d.optDouble("f60", 0) / scale);
            it.open = (float) (d.optDouble("f46", 0) / scale);
            it.high = (float) (d.optDouble("f44", 0) / scale);
            it.low = (float) (d.optDouble("f45", 0) / scale);
            it.change = (float) (d.optDouble("f169", 0) / scale);
            it.pct = (float) (d.optDouble("f170", 0) / 100.0);
        } catch (Throwable t) {
            Diag.log("gold", code + " fail " + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
        return it;
    }

    /**
     * 东财批量行情：一次拿回多个指数的 现价/成交量/成交额（f2/f5/f6）
     * 国际分类的量额只有东财给，且只有部分市场有，返回 secid → {成交量, 成交额}
     */
    static java.util.Map<String, float[]> fetchEastVolumes(java.util.List<String> secids) {
        java.util.Map<String, float[]> out = new java.util.HashMap<String, float[]>();
        if (secids == null || secids.isEmpty()) return out;
        StringBuilder sb = new StringBuilder();
        for (String s : secids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        try {
            String url = "http://push2.eastmoney.com/api/qt/ulist.np/get?fltt=1&secids=" + sb
                    + "&fields=f2,f5,f6,f12,f13,f14";
            byte[] raw = fetch(url, "https://quote.eastmoney.com/", 8000);
            JSONObject root = new JSONObject(new String(raw, "UTF-8"));
            JSONObject data = root.optJSONObject("data");
            if (data == null) return out;
            JSONArray arr = data.optJSONArray("diff");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String code = o.optString("f12");
                if (code == null || code.length() == 0) continue;
                String secid = (o.optInt("f13", 0) == 1 ? "1." : "100.") + code;
                double v = o.optDouble("f5", 0);
                double a = o.optDouble("f6", 0);
                if (v <= 0 && a <= 0) continue;
                out.put(secid, new float[]{(float) v, (float) a});
            }
            Diag.log("emvol", "n=" + out.size() + "/" + secids.size());
        } catch (Throwable t) {
            Diag.log("emvol", "fail " + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
        return out;
    }

    /**
     * 东财单条行情（国际指数用）：缩放倍数取 f59（小数位数），不写死
     * 新浪的台股是旧快照，台湾那一条改走这里，保证行情和日 K 是同一个指数
     */
    static Item fetchEastQuoteAuto(String secid, String code, String fallbackName) {
        Item it = new Item();
        it.code = code;
        it.name = fallbackName;
        it.displayCode = fallbackName;
        it.volTxt = "--";
        it.amtTxt = "--";
        try {
            String url = "http://push2.eastmoney.com/api/qt/stock/get?secid=" + secid
                    + "&fields=f43,f44,f45,f46,f57,f58,f59,f60,f86,f169,f170";
            byte[] raw = fetch(url, "https://quote.eastmoney.com/", 8000);
            JSONObject root = new JSONObject(new String(raw, "UTF-8"));
            JSONObject d = root.optJSONObject("data");
            if (d == null) return it;
            String nm = d.optString("f58");
            if (nm != null && nm.length() > 0) { it.name = nm; it.displayCode = nm; }
            int dec = d.optInt("f59", 2);
            if (dec < 0 || dec > 6) dec = 2;
            float scale = (float) Math.pow(10, dec);
            double live = d.optDouble("f43", 0);
            if (live == 0) {
                live = d.optDouble("f60", 0);
                it.stale = true;
            }
            it.price = (float) (live / scale);
            it.prevClose = (float) (d.optDouble("f60", 0) / scale);
            it.open = (float) (d.optDouble("f46", 0) / scale);
            it.high = (float) (d.optDouble("f44", 0) / scale);
            it.low = (float) (d.optDouble("f45", 0) / scale);
            it.change = (float) (d.optDouble("f169", 0) / scale);
            it.pct = (float) (d.optDouble("f170", 0) / 100.0);
            long ts = d.optLong("f86", 0);
            if (ts > 0) {
                it.time = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                        .format(new java.util.Date(ts * 1000L));
            }
            Diag.log("emquote", code + " price=" + it.price + " dec=" + dec);
        } catch (Throwable t) {
            Diag.log("emquote", code + " fail " + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
        return it;
    }


    /** K 线序列：价格 + 日期（另带最后一根的今开/最高/最低，详情里缺数时补上） */
    static class Series {
        List<Float> values = new ArrayList<Float>();
        List<String> labels = new ArrayList<String>();
        float lastOpen, lastHigh, lastLow;
    }

    private static float f(String s) {
        if (s == null) return 0f;
        try {
            s = s.trim();
            return s.length() == 0 ? 0f : Float.parseFloat(s);
        } catch (Throwable t) {
            return 0f;
        }
    }

    /** 东财日 K 近一年（黄金 / 国际指数 / 个股用），返回时间正序 */
    static Series fetchKline(String secid, String code, int limit) {
        Series out = new Series();
        Diag.log("kline", "em fetch " + secid + " for " + code);
        try {
            String url = "http://push2his.eastmoney.com/api/qt/stock/kline/get?secid=" + secid
                    + "&fields1=f1,f2,f3&fields2=f51,f52,f53,f54,f55"
                    + "&klt=101&fqt=1&beg=0&end=20500101&lmt=" + limit;
            byte[] raw = fetch(url, "https://quote.eastmoney.com/", 9000);
            JSONObject root = new JSONObject(new String(raw, "UTF-8"));
            JSONObject data = root.optJSONObject("data");
            if (data == null) return out;
            JSONArray ks = data.optJSONArray("klines");
            if (ks == null) return out;
            for (int i = 0; i < ks.length(); i++) {
                String[] p = ks.optString(i).split(",");
                if (p.length < 2) continue;
                try {
                    // f51 日期 / f52 开 / f53 收 / f54 高 / f55 低
                    // 接口万一只回「日期,收」两列也能用
                    int ci = p.length >= 3 ? 2 : 1;
                    out.values.add(Float.parseFloat(p[ci]));
                    String d = p[0];
                    if (d.length() >= 10) d = d.substring(5);   // 2026-11-02 -> 11-02
                    out.labels.add(d);
                    if (p.length >= 5) {
                        out.lastOpen = f(p[1]);
                        out.lastHigh = f(p[3]);
                        out.lastLow = f(p[4]);
                    }
                } catch (Throwable ignored) { }
            }
            // 接口的 lmt 参数不一定生效，自己截取最后 limit 根
            if (out.values.size() > limit) {
                Series tail = new Series();
                int from = out.values.size() - limit;
                for (int i = from; i < out.values.size(); i++) {
                    tail.values.add(out.values.get(i));
                    tail.labels.add(out.labels.get(i));
                }
                tail.lastOpen = out.lastOpen;
                tail.lastHigh = out.lastHigh;
                tail.lastLow = out.lastLow;
                out = tail;
            }
            if (out.values.size() > 0) {
                Diag.log("kline", code + " bars=" + out.values.size()
                        + " first=" + out.values.get(0)
                        + " last=" + out.values.get(out.values.size() - 1));
            } else {
                Diag.log("kline", code + " empty");
            }
        } catch (Throwable t) {
            Diag.log("kline", code + " fail " + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
        return out;
    }


    /**
     * 腾讯日 K（支持 sh/sz/hk/us/jp/kr/uk）：
     * data.<code>.qfqday 每行 = [日期, 开, 收, 高, 低, 量]
     */
    static Series fetchTencentKline(String code, int count) {
        Series out = new Series();
        if (code == null || code.length() == 0) return out;
        Diag.log("tkline", "start " + code);
        try {
            // 腾讯不同市场用不同路径：美股 usfqkline、港股 hkfqkline、A 股 fqkline
            String path = "fqkline";
            if (code.startsWith("us")) path = "usfqkline";
            else if (code.startsWith("hk")) path = "hkfqkline";
            String url = "http://web.ifzq.gtimg.cn/appstock/app/" + path
                    + "/get?param=" + code + ",day,,," + count + ",qfq";
            byte[] raw = fetch(url, "https://gu.qq.com/", 9000);
            JSONObject root = new JSONObject(new String(raw, "UTF-8"));
            JSONObject data = root.optJSONObject("data");
            if (data == null) return out;
            JSONObject one = data.optJSONObject(code);
            if (one == null) return out;
            JSONArray arr = one.optJSONArray("qfqday");
            if (arr == null) arr = one.optJSONArray("day");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONArray row = arr.optJSONArray(i);
                if (row == null || row.length() < 3) continue;
                String d = row.optString(0);
                try {
                    out.values.add(Float.parseFloat(row.optString(2)));   // 收盘
                    if (row.length() >= 5) {
                        out.lastOpen = f(row.optString(1));
                        out.lastHigh = f(row.optString(3));
                        out.lastLow = f(row.optString(4));
                    }
                    if (d != null && d.length() >= 10) d = d.substring(5);
                    out.labels.add(d == null ? "" : d);
                } catch (Throwable ignored) { }
            }
            Diag.log("tkline", code + " bars=" + out.values.size());
        } catch (Throwable t) {
            Diag.log("tkline", code + " fail " + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
        return out;
    }


    /**
     * 基金历史净值：东财 pingzhongdata/<code>.js 里的 Data_netWorthTrend
     * 每项 {x: 毫秒时间戳, y: 单位净值}
     */
    static Series fetchFundHistory(String code, int limit) {
        Series out = new Series();
        if (code == null) return out;
        String num = code.startsWith("f:") ? code.substring(2) : code;

        // 主接口：fundmobapi 历史净值（快，250 条）
        try {
            String url = "https://fundmobapi.eastmoney.com/FundMNewApi/FundMNHisNetList"
                    + "?pageIndex=1&pageSize=" + Math.min(limit, 60)
                    + "&FCODE=" + num
                    + "&plat=Android&appType=ttjj&product=EFund&Version=1&deviceid=tvdesk";
            byte[] raw = fetch(url, "https://fund.eastmoney.com/", 12000);
            JSONObject root = new JSONObject(new String(raw, "UTF-8"));
            JSONArray arr = root.optJSONArray("Datas");
            if (arr != null) {
                java.util.List<Float> vals = new ArrayList<Float>();
                java.util.List<String> labs = new ArrayList<String>();
                for (int i = arr.length() - 1; i >= 0; i--) {   // 最新在前，反转成时间正序
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    try {
                        float v = Float.parseFloat(o.optString("DWJZ"));
                        String d = o.optString("FSRQ");
                        if (d != null && d.length() >= 10) d = d.substring(5);
                        vals.add(v);
                        labs.add(d == null ? "" : d);
                    } catch (Throwable ignored) { }
                }
                out.values = vals;
                out.labels = labs;
            }
            Diag.log("fundhist", num + " n=" + out.values.size());
        } catch (Throwable t) {
            Diag.log("fundhist", num + " fail " + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
        return out;
    }


    /**
     * 新浪行情：国际指数（int_/znb_ 前缀）与 A 股指数（sh/sz）
     * 国际短格式：名称,现价,涨跌额,涨跌幅
     * 国际长格式（znb_）：名称,现价,涨跌额,涨跌幅,时,戳,日期,时间,今开,昨收,最高,最低,...
     * A股 ：名称,今开,昨收,现价,最高,最低,...
     */
    static List<Item> fetchSina(List<String> codes) {
        List<Item> out = new ArrayList<Item>();
        if (codes == null || codes.isEmpty()) return out;
        StringBuilder q = new StringBuilder();
        for (int i = 0; i < codes.size(); i++) {
            if (i > 0) q.append(',');
            q.append(codes.get(i));
        }
        try {
            byte[] raw = fetch("https://hq.sinajs.cn/list=" + q, "https://finance.sina.com.cn/", 9000);
            String t = new String(raw, "GBK");
            for (String line : t.split(";")) {
                line = line.trim();
                int k = line.indexOf("hq_str_");
                if (k < 0) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String code = line.substring(k + 7, eq).trim();
                String body = line.substring(eq + 1).trim();
                if (body.startsWith("\"")) body = body.substring(1);
                if (body.endsWith("\"")) body = body.substring(0, body.length() - 1);
                if (body.length() == 0) continue;
                String[] f = body.split(",");
                if (f.length < 4) continue;
                Item it = new Item();
                it.code = code;
                it.name = f[0].trim();
                it.displayCode = it.name;
                it.volTxt = "--";
                it.amtTxt = "--";
                boolean global = code.startsWith("int_") || code.startsWith("znb_");
                if (global) {
                    it.price = num2(f, 1);
                    it.change = num2(f, 2);
                    it.pct = num2(f, 3);
                    it.prevClose = it.price - it.change;
                    // 长格式带今开 / 昨收 / 最高 / 最低，补齐后行情区和国内一致
                    if (f.length >= 12) {
                        float o = num2(f, 8), pc = num2(f, 9);
                        float hi = num2(f, 10), lo = num2(f, 11);
                        if (pc > 0f && hi > 0f && lo > 0f) {
                            it.open = o;
                            it.prevClose = pc;
                            it.high = hi;
                            it.low = lo;
                            it.change = it.price - pc;
                            it.pct = it.change / pc * 100f;
                        }
                        String d = f[6].trim(), tm = f[7].trim();
                        if (d.length() >= 8 && tm.length() >= 5) it.time = d + " " + tm;
                    }
                } else {
                    it.open = num2(f, 1);
                    it.prevClose = num2(f, 2);
                    it.price = num2(f, 3);
                    it.high = num2(f, 4);
                    it.low = num2(f, 5);
                    it.change = it.price - it.prevClose;
                    if (it.prevClose != 0) it.pct = it.change / it.prevClose * 100f;
                }
                if (it.price == 0) continue;
                out.add(it);
            }
            Diag.log("sina", "n=" + out.size() + "/" + codes.size());
        } catch (Throwable t) {
            Diag.log("sina", "fail " + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
        return out;
    }

    // ---------------- 基金 ----------------

    static List<Item> fetchFunds(List<String> codes) {
        List<Item> out = new ArrayList<Item>();
        if (codes == null || codes.isEmpty()) return out;
        StringBuilder q = new StringBuilder();
        for (int i = 0; i < codes.size() && i < 20; i++) {
            if (i > 0) q.append(',');
            q.append(codes.get(i));
        }
        try {
            String url = "https://fundmobapi.eastmoney.com/FundMNewApi/FundMNFInfo"
                    + "?pageIndex=1&pageSize=20&plat=Android&appType=ttjj&product=EFund"
                    + "&Version=1&deviceid=tvdesk&Fcodes=" + q;
            byte[] raw = fetch(url, "https://fund.eastmoney.com/", 8000);
            JSONObject root = new JSONObject(new String(raw, "UTF-8"));
            JSONArray arr = root.optJSONArray("Datas");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Item it = new Item();
                it.fund = true;
                it.code = o.optString("FCODE");
                it.displayCode = it.code;
                it.name = o.optString("SHORTNAME");
                it.price = (float) o.optDouble("NAV", 0);
                it.pct = (float) o.optDouble("NAVCHGRT", 0);
                it.navDate = o.optString("PDATE");
                it.prevClose = it.price / (1f + it.pct / 100f);
                it.change = it.price - it.prevClose;
                it.time = it.navDate;
                out.add(it);
            }
        } catch (Throwable t) {
            android.util.Log.w("TVDeskStock", "fund fail " + t);
        }
        return out;
    }
}
