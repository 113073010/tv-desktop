package com.dsh.tvdesk.stock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 油价历史曲线缓存：同一省 30 分钟内不重复抓调价表 */
public class HistoryCache {

    private static final long TTL = 30 * 60 * 1000L;
    private static final Map<String, List<Float>> DATA = new HashMap<String, List<Float>>();
    private static final Map<String, List<String>> LABELS = new HashMap<String, List<String>>();
    private static final Map<String, Long> AT = new HashMap<String, Long>();

    public static void clear() {
        DATA.clear();
        LABELS.clear();
        AT.clear();
    }

    public static List<Float> get(String key) {
        Long t = AT.get(key);
        if (t == null) return null;
        if (System.currentTimeMillis() - t > TTL) return null;
        return DATA.get(key);
    }

    public static void put(String key, List<Float> list) {
        put(key, list, null);
    }

    public static void put(String key, List<Float> list, List<String> labels) {
        if (list == null || list.size() < 3) return;
        DATA.put(key, list);
        if (labels != null) LABELS.put(key, labels);
        AT.put(key, System.currentTimeMillis());
    }

    public static List<String> getLabels(String key) {
        Long t = AT.get(key);
        if (t == null) return null;
        if (System.currentTimeMillis() - t > TTL) return null;
        return LABELS.get(key);
    }
}
