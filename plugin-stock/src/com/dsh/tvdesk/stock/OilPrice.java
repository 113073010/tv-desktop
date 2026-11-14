package com.dsh.tvdesk.stock;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
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
        /** 页面 404：这个省没有页面，重试没意义 */
        public boolean missing = false;
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
        } catch (java.io.FileNotFoundException e) {
            // 404：这个省在站上没有页面，别重试、也别算进「缺几个省」
            o.missing = true;
            Diag.log("oil", name + " 404");
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

    private static final String ADJ_FILE = "/sdcard/tvdesk/oil.adjusts";
    private static final long ADJ_TTL = 6 * 60 * 60 * 1000L;
    private static List<Adjust> sAdj = null;
    private static long sAdjAt = 0;
    private static boolean sAdjLoaded = false;
    /** soyoujia 调价日历给出的下一个调价窗口（yyyy-MM-dd），空表示这次没取到 */
    private static String sNextDate = "";

    /**
     * 调价时间表：优先 soyoujia（数据新），失败退回 qiyoujiage。
     *
     * 这张表只在每次调价后变，曲线和「下次调价」都靠它，但插件每次切 TAB
     * 都会被重新加载（静态变量跟着没），所以缓存也落到 /sdcard/tvdesk/oil.adjusts，
     * 6 小时内不再联网。
     */
    public static synchronized List<Adjust> fetchAdjusts() {
        if (!sAdjLoaded) {
            sAdjLoaded = true;
            sAdj = loadAdjusts();
        }
        long now = System.currentTimeMillis();
        if (sAdj != null && !sAdj.isEmpty() && now - sAdjAt < ADJ_TTL) {
            return sAdj;
        }
        List<Adjust> list = fetchFromSoyoujia();
        if (list.size() < 8) {
            List<Adjust> old = fetchFromQiyoujiage();
            if (!old.isEmpty()) list = old;
        }
        if (!list.isEmpty()) {
            sAdj = list;
            sAdjAt = now;
            saveAdjusts(list, now);
            Diag.log("oil", "adjusts=" + list.size() + " latest=" + list.get(0).date);
        }
        return list;
    }

    private static List<Adjust> loadAdjusts() {
        List<Adjust> out = new ArrayList<Adjust>();
        File f = new File(ADJ_FILE);
        if (!f.isFile()) return out;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            long at = 0;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#ts=")) {
                    try { at = Long.parseLong(line.substring(4).trim()); } catch (Throwable ignored) { }
                    continue;
                }
                if (line.startsWith("#next=")) {
                    sNextDate = line.substring(6).trim();
                    continue;
                }
                if (line.length() == 0 || line.startsWith("#")) continue;
                String[] p = line.split("\t", -1);
                if (p.length < 2 || p[0].length() < 8) continue;
                Adjust a = new Adjust();
                a.date = p[0];
                try { a.gasolinePerTon = Integer.parseInt(p[1]); } catch (Throwable ignored) { }
                out.add(a);
            }
            if (System.currentTimeMillis() - at > ADJ_TTL) out.clear();
            else sAdjAt = at;
            // 缓存的「下次调价」已经过去：别继续拿出来当未来日期用，
            // 交给 computeNextAdjust() 重新联网取，取不到就按上次调价日推算。
            if (sNextDate.length() > 0 && sNextDate.compareTo(today(0)) < 0) sNextDate = "";
        } catch (Throwable t) {
            Diag.log("oil", "adjusts read fail " + t.getClass().getSimpleName());
            out.clear();
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) { }
        }
        return out;
    }

    private static void saveAdjusts(List<Adjust> list, long at) {
        File f = new File(ADJ_FILE);
        File dir = f.getParentFile();
        if (dir != null) dir.mkdirs();
        OutputStreamWriter w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            w.write("#ts=" + at + "\n");
            if (sNextDate != null && sNextDate.length() == 10) w.write("#next=" + sNextDate + "\n");
            for (Adjust a : list) {
                w.write(a.date + "\t" + a.gasolinePerTon + "\n");
            }
        } catch (Throwable t) {
            Diag.log("oil", "adjusts write fail " + t.getClass().getSimpleName());
        } finally {
            try { if (w != null) w.close(); } catch (Throwable ignored) { }
        }
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
            // 日历里已经列出下一个调价窗口（结果还是「未公布」），
            // 直接用它比按工作日硬算更准（遇到法定节假日也不会错）
            String next = pickNextDate(html, out);
            if (next.length() > 0) sNextDate = next;
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

    /** 下次调价：优先用 soyoujia 日历里的窗口，取不到再按上次调价日 + 10 个工作日推算 */
    private static String sNextCache = null;
    private static long sNextCacheAt = 0;
    private static final long NEXT_TTL = 30 * 60 * 1000L;

    /** 主线程调用：只读缓存，不联网 */
    public static String cachedNextAdjust() {
        return sNextCache == null ? "计算中…" : sNextCache;
    }

    /** 后台线程调用：真正联网推算 */
    public static String computeNextAdjust() {
        long now = System.currentTimeMillis();
        if (sNextCache != null && now - sNextCacheAt < NEXT_TTL) return sNextCache;
        try {
            java.util.List<Adjust> list = fetchAdjusts();
            String next = sNextDate;
            // 缓存的日期已经过去：调价表可能还没跟上，忽略 6 小时 TTL 再抓一次
            if (next.length() > 0 && next.compareTo(today(0)) < 0) {
                sAdjAt = 0;
                list = fetchAdjusts();
                next = sNextDate;
            }
            if (next.length() < 10) next = addWorkdays(list, 10);
            // 实在推不出未来日期就别把过去的日期当「下次」显示
            if (next.length() < 10 || next.compareTo(today(0)) < 0) {
                Diag.log("oil", "nextAdjust stale, latest="
                        + (list.isEmpty() ? "-" : list.get(0).date));
                sNextCache = "待公布";
                sNextCacheAt = 0;
                return sNextCache;
            }
            sNextCache = formatNext(next);
            sNextCacheAt = now;
            Diag.log("oil", "nextAdjust=" + next + " calendar=" + sNextDate);
            return sNextCache;
        } catch (Throwable t) {
            Diag.log("oil", "nextAdjust fail " + t.getClass().getSimpleName() + ":" + t.getMessage());
            sNextCache = "待公布";
            sNextCacheAt = 0;
            return sNextCache;
        }
    }

    /** 今天的 yyyy-MM-dd，offsetDays 为偏移天数 */
    private static String today(int offsetDays) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.add(java.util.Calendar.DAY_OF_MONTH, offsetDays);
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(c.getTime());
    }

    /**
     * 从 soyoujia 调价日历里找最近一个还没到的窗口。
     * 页面把未来月份也列出来了（结果写「未公布」），所以比按工作日硬算更准；
     * 跳过今天以前的、以及上次实际调价日（含）之前的日期。
     */
    private static String pickNextDate(String html, java.util.List<Adjust> actual) {
        String latest = "";
        if (actual != null) {
            for (Adjust a : actual) {
                if (a != null && a.date.compareTo(latest) > 0) latest = a.date;
            }
        }
        String now = today(0);
        String best = "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<span>(20\\d\\d-\\d{2}-\\d{2})</span>").matcher(html);
        while (m.find()) {
            String d = m.group(1);
            if (d.compareTo(now) < 0) continue;
            if (latest.length() > 0 && d.compareTo(latest) <= 0) continue;
            if (best.length() == 0 || d.compareTo(best) < 0) best = d;
        }
        return best;
    }

    /** 上次调价日 + 10 个工作日（发改委规则，不含法定节假日修正） */
    private static String addWorkdays(java.util.List<Adjust> list, int days) {
        String latest = "";
        if (list != null) {
            for (Adjust a : list) {
                if (a != null && a.date.compareTo(latest) > 0) latest = a.date;
            }
        }
        if (latest.length() < 10) return "";
        try {
            java.text.SimpleDateFormat in =
                    new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTime(in.parse(latest));
            int work = 0;
            while (work < days) {
                c.add(java.util.Calendar.DAY_OF_MONTH, 1);
                int dow = c.get(java.util.Calendar.DAY_OF_WEEK);
                if (dow != java.util.Calendar.SATURDAY && dow != java.util.Calendar.SUNDAY) work++;
            }
            return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(c.getTime());
        } catch (Throwable t) {
            return "";
        }
    }

    /** yyyy-MM-dd → 11月27日（预计）；跨年时带年份 */
    private static String formatNext(String ymd) {
        try {
            int month = Integer.parseInt(ymd.substring(5, 7));
            int day = Integer.parseInt(ymd.substring(8, 10));
            String prefix = ymd.substring(0, 4).equals(today(0).substring(0, 4))
                    ? "" : ymd.substring(0, 4) + "年";
            return prefix + month + "月" + day + "日（预计）";
        } catch (Throwable t) {
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
