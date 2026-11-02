package com.dsh.tvdesk.stock;

import java.util.HashMap;
import java.util.Map;

/** 油价简单缓存：同省 10 分钟内不重复抓页面 */
public class OilCache {

    private static final long TTL = 10 * 60 * 1000L;
    private static final Map<String, OilPrice.Oil> DATA = new HashMap<String, OilPrice.Oil>();
    private static final Map<String, Long> AT = new HashMap<String, Long>();

    /** 手动刷新时清空，强制重新抓取 */
    public static void clear() {
        DATA.clear();
        AT.clear();
    }

    public static OilPrice.Oil get(String slug) {
        Long t = AT.get(slug);
        if (t == null) return null;
        if (System.currentTimeMillis() - t > TTL) return null;
        return DATA.get(slug);
    }

    public static void put(String slug, OilPrice.Oil oil) {
        if (oil == null || !oil.ok) return;
        DATA.put(slug, oil);
        AT.put(slug, System.currentTimeMillis());
    }
}
