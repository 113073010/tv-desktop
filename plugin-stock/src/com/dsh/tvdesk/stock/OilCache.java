package com.dsh.tvdesk.stock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * 油价缓存（内存 + 落盘）。
 *
 * 为什么必须落盘：插件是乐视桌面用 DexClassLoader 加载的，切走 TAB 再切回来
 * 静态变量会跟着类一起没了（实测每次切回都当第一次进来）。只放内存的缓存
 * 等于没有——于是每切一次就重抓 31 个省，既慢又容易被 qiyoujiage 当爬虫，
 * 返回 404 / 超时，表格里就冒出 "--"。
 *
 * 油价也不是行情：发改委 10 个工作日才调一次价，缓存放 12 小时足够，
 * 真正要新数据时按「确定」手动刷新即可。
 */
public class OilCache {

    /** 12 小时：跨一个自然日足够，手动刷新随时可以覆盖 */
    public static final long TTL = 12 * 60 * 60 * 1000L;

    private static final String FILE = "/sdcard/tvdesk/oil.cache";

    private static final Map<String, OilPrice.Oil> DATA = new HashMap<String, OilPrice.Oil>();
    private static final Map<String, Long> AT = new HashMap<String, Long>();
    private static boolean sLoaded = false;
    private static boolean sDirty = false;
    /** 上一次真正发起抓取的时间：全失败时用来做退避，别一进 TAB 就重锤 */
    private static long sTryAt = 0;

    private static synchronized void ensure() {
        if (sLoaded) return;
        sLoaded = true;
        loadFromDisk();
    }

    private static void loadFromDisk() {
        File f = new File(FILE);
        if (!f.isFile()) return;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            long now = System.currentTimeMillis();
            while ((line = r.readLine()) != null) {
                if (line.startsWith("# try=")) {
                    try { sTryAt = Long.parseLong(line.substring(6).trim()); } catch (Throwable ignored) { }
                    continue;
                }
                if (line.length() == 0 || line.charAt(0) == '#') continue;
                // slug \t 省名 \t 92 \t 95 \t 98 \t 0 \t -10 \t -20 \t -35 \t 调价日 \t 预告 \t 方向 \t 幅度 \t 时间
                String[] p = line.split("\t", -1);
                if (p.length < 14) continue;
                long at;
                try { at = Long.parseLong(p[13]); } catch (Throwable t) { continue; }
                if (now - at > TTL) continue;
                OilPrice.Oil o = new OilPrice.Oil();
                o.province = p[1];
                o.p92 = p[2];
                o.p95 = p[3];
                o.p98 = p[4];
                o.p0 = p[5];
                o.p10 = p[6];
                o.p20 = p[7];
                o.p35 = p[8];
                o.adjustDate = p[9];
                o.forecast = p[10];
                try { o.forecastDir = Integer.parseInt(p[11]); } catch (Throwable t) { }
                try { o.forecastValue = Float.parseFloat(p[12]); } catch (Throwable t) { }
                o.ok = !"--".equals(o.p92) && o.p92 != null;
                if (!o.ok) continue;
                DATA.put(p[0], o);
                AT.put(p[0], at);
            }
        } catch (Throwable t) {
            Diag.log("oil", "cache read fail " + t.getClass().getSimpleName());
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) { }
        }
    }

    /** 把内存里的数据写回 /sdcard/tvdesk/oil.cache（每轮抓完调一次） */
    public static synchronized void flush() {
        ensure();
        if (!sDirty) return;
        File f = new File(FILE);
        File dir = f.getParentFile();
        if (dir != null) dir.mkdirs();
        OutputStreamWriter w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            w.write("# tvdesk oil cache v1\n");
            if (sTryAt > 0) w.write("# try=" + sTryAt + "\n");
            long now = System.currentTimeMillis();
            for (Map.Entry<String, OilPrice.Oil> e : DATA.entrySet()) {
                Long at = AT.get(e.getKey());
                if (at == null) continue;
                if (now - at > TTL) continue;          // 过期的顺手清掉，别让文件越滚越大
                OilPrice.Oil o = e.getValue();
                w.write(e.getKey() + "\t" + o.province + "\t" + o.p92 + "\t" + o.p95 + "\t"
                        + o.p98 + "\t" + o.p0 + "\t" + o.p10 + "\t" + o.p20 + "\t" + o.p35 + "\t"
                        + o.adjustDate + "\t" + o.forecast + "\t" + o.forecastDir + "\t"
                        + o.forecastValue + "\t" + at + "\n");
            }
            sDirty = false;
        } catch (Throwable t) {
            Diag.log("oil", "cache write fail " + t.getClass().getSimpleName());
        } finally {
            try { if (w != null) w.close(); } catch (Throwable ignored) { }
        }
    }

    /** 记下这次真正发起抓取的时间（全失败时用来退避） */
    public static synchronized void markTry() {
        ensure();
        sTryAt = System.currentTimeMillis();
        sDirty = true;
    }

    /** 上一次发起抓取的时间；0 表示从没抓过 */
    public static synchronized long tryAt() {
        ensure();
        return sTryAt;
    }

    public static synchronized OilPrice.Oil get(String slug) {
        ensure();
        Long t = AT.get(slug);
        if (t == null) return null;
        if (System.currentTimeMillis() - t > TTL) return null;
        return DATA.get(slug);
    }

    public static synchronized void put(String slug, OilPrice.Oil oil) {
        ensure();
        if (oil == null || !oil.ok) return;
        DATA.put(slug, oil);
        AT.put(slug, System.currentTimeMillis());
        sDirty = true;
    }

    /** 缓存里最新一次抓取的时间；0 表示没有缓存 */
    public static synchronized long newestAt() {
        ensure();
        long newest = 0;
        for (Long t : AT.values()) {
            if (t != null && t > newest) newest = t;
        }
        return newest;
    }

    /** 整批缓存还没过期？过期就该重抓一次 */
    public static synchronized boolean fresh() {
        long t = newestAt();
        return t > 0 && System.currentTimeMillis() - t <= TTL;
    }
}
