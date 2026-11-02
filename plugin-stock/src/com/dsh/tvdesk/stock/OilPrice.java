package com.dsh.tvdesk.stock;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 各省油价：抓 qiyoujiage.com 的省份页面
 * 页面结构：<dl>北京92#汽油 8.89</dl> … 98#汽油、0#柴油
 */
public class OilPrice {

    /** 省份：显示名 → 页面 slug */
    public static final String[][] PROVINCES = {
            {"北京", "beijing"}, {"天津", "tianjin"}, {"河北", "hebei"}, {"山西", "shanxi"},
            {"内蒙古", "neimenggu"}, {"辽宁", "liaoning"}, {"吉林", "jilin"}, {"黑龙江", "heilongjiang"},
            {"上海", "shanghai"}, {"江苏", "jiangsu"}, {"浙江", "zhejiang"}, {"安徽", "anhui"},
            {"福建", "fujian"}, {"江西", "jiangxi"}, {"山东", "shandong"}, {"河南", "henan"},
            {"湖北", "hubei"}, {"湖南", "hunan"}, {"广东", "guangdong"}, {"广西", "guangxi"},
            {"海南", "hainan"}, {"重庆", "chongqing"}, {"四川", "sichuan"}, {"贵州", "guizhou"},
            {"云南", "yunnan"}, {"西藏", "xizang"}, {"陕西", "shanxi-3"}, {"甘肃", "gansu"},
            {"青海", "qinghai"}, {"宁夏", "ningxia"}, {"新疆", "xinjiang"},
    };

    public static class Oil {
        public String province = "";
        public String p92 = "--", p95 = "--", p98 = "--", p0 = "--";
        public String adjustDate = "";   // 上一次调价日期
        public String p10 = "--", p20 = "--", p35 = "--";   // 低标号柴油（按行业差价推算）
        public String forecast = "";     // 下次调价预告，展示用，如「+0.07 元/升」
        public int forecastDir = 0;      // 1 涨 / -1 跌 / 0 搁浅未知
        public float forecastValue = 0f; // 预计调整（元/升），带符号
        public boolean ok = false;
    }

    private static byte[] fetch(String url, int timeoutMs) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            if (c instanceof javax.net.ssl.HttpsURLConnection) {
                // 这个站的证书链在 Android 5 上不可信，直接放行
                javax.net.ssl.SSLSocketFactory f = TrustAll.socketFactory();
                if (f != null) {
                    ((javax.net.ssl.HttpsURLConnection) c).setSSLSocketFactory(f);
                    ((javax.net.ssl.HttpsURLConnection) c).setHostnameVerifier(
                            new javax.net.ssl.HostnameVerifier() {
                                @Override public boolean verify(String h, javax.net.ssl.SSLSession s) { return true; }
                            });
                }
            }
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36");
            c.setRequestProperty("Referer", "https://www.qiyoujiage.com/");
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

    private static boolean sslTried = false;

    /** 简单 HTML 解析：抓 92/95/98/0# 四个价格 */
    public static Oil fetch(String name, String slug) {
        Oil o = new Oil();
        o.province = name;
        try {
            byte[] raw = fetch("https://www.qiyoujiage.com/" + slug + ".shtml", 6000);
            String html = new String(raw, "UTF-8");
            if (html.indexOf("汽油") < 0) {
                html = new String(raw, "GBK");
            }
            String plain = html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ");
            o.p92 = pick(plain, "92#汽油");
            o.p95 = pick(plain, "95#汽油");
            o.p98 = pick(plain, "98#汽油");
            o.p0 = pick(plain, "0#柴油");
            o.ok = !"--".equals(o.p92);
            String d = findAdjust(plain);
            o.adjustDate = d;
            // 低标号柴油：按行业惯例比 0# 贵，-10/+0.10、-20/+0.20、-35/+0.32
            // 抓「下次油价X月X日24时调整，目前预计上调/下调N元/吨」
            java.util.regex.Matcher fm = java.util.regex.Pattern
                    .compile("预计\\s*(上调|下调|搁浅)[^0-9]{0,6}(\\d+)?")
                    .matcher(plain);
            if (fm.find()) {
                String dir = fm.group(1);
                String num = fm.group(2);
                if ("搁浅".equals(dir)) {
                    o.forecastDir = 0;
                    o.forecast = "持平";
                } else if (num != null) {
                    float perTon = Float.parseFloat(num);
                    float perLiter = perTon / 1388f;
                    o.forecastDir = "上调".equals(dir) ? 1 : -1;
                    o.forecastValue = o.forecastDir * perLiter;
                    o.forecast = String.format(java.util.Locale.US, "%+.2f", o.forecastValue);
                } else {
                    o.forecastDir = "上调".equals(dir) ? 1 : -1;
                    o.forecast = "上调".equals(dir) ? "+" : "-";
                }
            }
            o.p10 = shift(o.p0, 0.10f);
            o.p20 = shift(o.p0, 0.20f);
            o.p35 = shift(o.p0, 0.32f);
            Diag.log("oil", name + " 92=" + o.p92 + " 95=" + o.p95 + " 98=" + o.p98 + " 0=" + o.p0);
        } catch (Throwable t) {
            Diag.log("oil", name + " fail " + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
        return o;
    }


    /** 一条调价记录 */
    public static class Adjust {
        public String date = "";
        public int gasolinePerTon = 0;   // 汽油每吨涨跌（元/吨），0 表示搁浅
    }

    /** 抓调价时间表：优先 soyoujia（数据新），失败退回 qiyoujiage */
    public static List<Adjust> fetchAdjusts() {
        List<Adjust> list = fetchFromSoyoujia();
        if (list.size() >= 8) {
            Diag.log("oil", "adjusts(soyoujia)=" + list.size()
                    + " latest=" + list.get(0).date);
            return list;
        }
        List<Adjust> old = fetchFromQiyoujiage();
        Diag.log("oil", "adjusts(qiyoujiage)=" + old.size()
                + (old.isEmpty() ? "" : " latest=" + old.get(0).date));
        return old;
    }

    /**
     * soyoujia 调价日历，行结构：
     *   <span>2026-10-30</span> ... 汽油每吨上涨345元,柴油每吨上涨335元
     */
    private static List<Adjust> fetchFromSoyoujia() {
        List<Adjust> out = new ArrayList<Adjust>();
        try {
            byte[] raw = fetch("https://www.soyoujia.cn/tiaojia", 9000);
            String html = new String(raw, "UTF-8");
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "<span>(20\\d\\d-\\d{2}-\\d{2})</span>"
                    + "(.{0,500}?)"
                    + "汽油每吨(上涨|下跌|不变)?\\s*(\\d+)?\\s*元",
                    java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher m = p.matcher(html);
            while (m.find()) {
                Adjust a = new Adjust();
                a.date = m.group(1);
                String dir = m.group(3);
                int v = 0;
                try {
                    String num = m.group(4);
                    if (num != null && num.length() > 0) v = Integer.parseInt(num);
                } catch (Throwable ignored) { }
                if ("下跌".equals(dir)) v = -v;
                else if ("不变".equals(dir)) v = 0;
                a.gasolinePerTon = v;
                out.add(a);
                if (out.size() >= 40) break;
            }
        } catch (Throwable t) {
            Diag.log("oil", "soyoujia fail " + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
        return out;
    }

    /** 旧源：qiyoujiage 调价表（数据只到 2024 年底，仅作备用） */
    private static List<Adjust> fetchFromQiyoujiage() {
        List<Adjust> out = new ArrayList<Adjust>();
        try {
            byte[] raw = fetch("https://www.qiyoujiage.com/tz/2025.shtml", 8000);
            String html = new String(raw, "UTF-8");
            java.util.regex.Matcher tr = java.util.regex.Pattern
                    .compile("<tr[^>]*>(.*?)</tr>", java.util.regex.Pattern.DOTALL)
                    .matcher(html);
            java.util.regex.Pattern dateP = java.util.regex.Pattern
                    .compile("(\\d{4})年(\\d{1,2})月(\\d{1,2})日");
            java.util.regex.Pattern numP = java.util.regex.Pattern
                    .compile("(涨|跌)\\s*(\\d+)");
            while (tr.find()) {
                String row = tr.group(1).replaceAll("<[^>]+>", "|");
                row = row.replaceAll("\\|+", "|");
                java.util.regex.Matcher dm = dateP.matcher(row);
                if (!dm.find()) continue;
                Adjust a = new Adjust();
                a.date = dm.group(1) + "-" + two(dm.group(2)) + "-" + two(dm.group(3));
                java.util.regex.Matcher nm = numP.matcher(row);
                if (nm.find()) {
                    int v = Integer.parseInt(nm.group(2));
                    a.gasolinePerTon = "涨".equals(nm.group(1)) ? v : -v;
                }
                out.add(a);
                if (out.size() >= 26) break;
            }
        } catch (Throwable t) {
            Diag.log("oil", "qiyoujiage fail " + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
        return out;
    }

    private static String two(String s) {
        return s.length() < 2 ? "0" + s : s;
    }

    /**
     * 以当前 92# 价格为终点的历史序列（时间正序）
     * 汽油按 1388 升/吨折算（发改委口径），误差约 ±0.01 元
     */
    /** 价格序列 + 对应的调价日期（时间正序） */
    public static class Series {
        public java.util.List<Float> values = new java.util.ArrayList<Float>();
        public java.util.List<String> labels = new java.util.ArrayList<String>();
        public String latestDate = "";     // 调价表里最新一条的日期
    }

    public static Series priceHistory(float current92) {
        java.util.List<Adjust> list = fetchAdjusts();
        java.util.List<Float> rev = new java.util.ArrayList<Float>();
        java.util.List<String> revDate = new java.util.ArrayList<String>();
        rev.add(current92);
        // 最新点用「最近一次调价日期」，作为当前价格的生效日
        String lastDate = "";
        if (!list.isEmpty()) {
            String d0 = list.get(0).date;
            // 带上年份后两位，避免和今年混淆：2024-12-18 -> 24-12-18
            if (d0 != null && d0.length() >= 10) lastDate = d0.substring(2);
            else if (d0 != null) lastDate = d0;
        }
        revDate.add(lastDate);      // 末端标数据日期（调价表最新一条）
        float v = current92;
        for (int i = 0; i < list.size(); i++) {
            v = v - list.get(i).gasolinePerTon / 1388f;
            rev.add(v);
            String d = list.get(i).date;
            if (d != null && d.length() >= 10) d = d.substring(2);   // 2024-12-18 -> 24-12-18
            revDate.add(d == null ? "" : d);
        }
        Series out = new Series();
        out.latestDate = lastDate;
        for (int i = rev.size() - 1; i >= 0; i--) {
            out.values.add(rev.get(i));
            out.labels.add(revDate.get(i));
        }
        return out;
    }

    private static String shift(String base, float delta) {
        try {
            return String.format(java.util.Locale.US, "%.2f", Float.parseFloat(base) + delta);
        } catch (Throwable t) {
            return "--";
        }
    }

    /** 下次调价：上次调价日 + 10 个工作日（发改委规则），标「预计」 */
    private static String sNextCache = null;

    /** 主线程调用：只读缓存，不联网 */
    public static String cachedNextAdjust() {
        return sNextCache == null ? "计算中…" : sNextCache;
    }

    /** 后台线程调用：真正联网推算 */
    public static String computeNextAdjust() {
        if (sNextCache != null) return sNextCache;
        try {
            java.util.List<Adjust> list = fetchAdjusts();
            if (list.isEmpty()) return "待公布";
            String d = list.get(0).date;              // yyyy-MM-dd
            java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            java.util.Date dt = in.parse(d);
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTime(dt);
            int work = 0;
            while (work < 10) {
                c.add(java.util.Calendar.DAY_OF_MONTH, 1);
                int dow = c.get(java.util.Calendar.DAY_OF_WEEK);
                if (dow != java.util.Calendar.SATURDAY && dow != java.util.Calendar.SUNDAY) work++;
            }
            sNextCache = (c.get(java.util.Calendar.MONTH) + 1) + "月" + c.get(java.util.Calendar.DAY_OF_MONTH) + "日（预计）";
            return sNextCache;
        } catch (Throwable t) {
            Diag.log("oil", "nextAdjust fail " + t.getClass().getSimpleName() + ":" + t.getMessage());
            return "待公布";
        }
    }

    private static String pick(String plain, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(java.util.regex.Pattern.quote(key) + "\\s*([0-9]+\\.[0-9]+)")
                .matcher(plain);
        if (m.find()) return m.group(1);
        return "--";
    }

    private static String findAdjust(String plain) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{4}-\\d{2}-\\d{2}|\\d{1,2}月\\d{1,2}日)")
                .matcher(plain);
        return m.find() ? m.group(1) : "";
    }
}
