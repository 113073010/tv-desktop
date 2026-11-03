package com.dsh.tvdesk.news;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.stv.launcher.sdk.fragment.BaseFragment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 新闻页：左栏标题 + 右栏正文；支持语音播报（音色、语速可调）。 */
public class NewsFragment extends BaseFragment {

    private static final String[] TITLES = {"热点", "要闻", "时政", "科技", "财经", "国际", "军事"};
    private static final String[] FEEDS = {
            // 热点：腾讯新闻热榜
            "https://r.inews.qq.com/gw/event/pc_hot_ranking_list?page_size=20&page_num=0",
            // 要闻：中新网 要闻导读
            "http://www.chinanews.com.cn/rss/importnews.xml",
            // 时政：中新网 国内
            "http://www.chinanews.com.cn/rss/china.xml",
            // 科技：IT之家
            "https://www.ithome.com/rss/",
            // 财经：中新网 财经
            "http://www.chinanews.com.cn/rss/finance.xml",
            // 国际：中新网 国际
            "http://www.chinanews.com.cn/rss/world.xml",
            // 军事：凤凰网军事频道（网页抓取，无 RSS）
            "html::https://mil.ifeng.com/",
    };
    private static final int ROWS = 10;
    private static final long TTL_MS = 10 * 60 * 1000L;
    private static final float[] SPEEDS = {0.8f, 1.0f, 1.2f, 1.5f};
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

    private Context mCtx;
    private View mRoot;
    private int mDensity = 2;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mPool = Executors.newFixedThreadPool(2);

    private TextView[] mChips = new TextView[TITLES.length];
    private TextView mListTitle, mStatus;
    private TextView[] mNums = new TextView[ROWS];
    private TextView[] mRowTitles = new TextView[ROWS];
    private View[] mRows = new View[ROWS];
    private View mHeadCard;
    private TextView mHeadKind, mHeadTitle, mHeadDesc, mHeadMeta, mVoiceBar;
    private android.widget.ImageView mHeadImg;
    private android.widget.ScrollView mDetailScroll;
    private LinearLayout mDocBox;
    private android.webkit.WebView mWeb;
    private String mWebUrl;
    private String mDocText = "";
    private final java.util.Map<String, java.util.List<Block>> mDocCache =
            new java.util.HashMap<String, java.util.List<Block>>();
    private FrameLayout mVoicePanel;
    /** 引擎：0=百度在线，1=微软 edge（经 OpenWrt 桥） */
    private int mEngineIdx = 1;
    /** 当前引擎里的音色下标 */
    private int mVoiceSel = 0;
    private static final String[][] EDGE_VOICES = {
        {"zh-CN-YunxiNeural", "微软·云希(男)"},
        {"zh-CN-YunjianNeural", "微软·云健(男)"},
        {"zh-CN-XiaoxiaoNeural", "微软·晓晓(女)"},
        {"zh-CN-YunxiaNeural", "微软·云夏(男)"},
        {"zh-CN-YunyangNeural", "微软·云扬(男)"},
        {"zh-CN-XiaoyiNeural", "微软·晓伊(女)"},
        {"zh-CN-liaoning-XiaobeiNeural", "微软·晓北(女·东北话)"},
        {"zh-CN-shaanxi-XiaoniNeural", "微软·晓妮(女·陕西话)"}
    };
    private static final String[][] BAIDU_VOICES = {
        {"", "百度在线"}
    };
    private TextView[] mVoiceRows = new TextView[4];

    private final List<List<Item>> mData = new ArrayList<>();
    private final long[] mLoadedAt = new long[32];
    private List<Item> mFlat = new ArrayList<>();
    private int mTab = 0, mOffset = 0, mFocus = 0;

    private android.media.MediaPlayer mPlayer;
    private boolean mReading = false, mChain = true;
    private boolean mPaused = false;   // 全文朗读暂停标志（按确定切换）
    private final android.util.LruCache<String, Bitmap> mImgCache =
            new android.util.LruCache<String, Bitmap>(6 * 1024 * 1024);
    private final ExecutorService mImgPool = Executors.newFixedThreadPool(2);
    private final java.util.Map<String, java.util.List<String>> mArticleImgs =
            new java.util.HashMap<String, java.util.List<String>>();
    private final java.util.Map<String, String> mFullCache = new java.util.HashMap<String, String>();
    private java.util.List<String> mChunks = new ArrayList<String>();
    private int mChunkIdx = 0;
    private final Object mPrefLock = new Object();
    /** 预取缓存：key = 条号:块号 → 已合成好的 mp3 */
    private final java.util.LinkedHashMap<String, File> mPrefMap =
            new java.util.LinkedHashMap<String, File>();
    /** 正在后台合成的 key，避免同一条重复合成 */
    private final java.util.HashSet<String> mPrefBusy = new java.util.HashSet<String>();
    /** 代次：清空后迟到的合成结果直接丢弃 */
    private int mPrefGen = 0;
    private int mReadIndex = -1;
    private int mSpeedIdx = 1;
    /** 每次合成一个独立的 mp3 文件名：预取下一段时不会覆盖正在播放的那一段 */
    private static final java.util.concurrent.atomic.AtomicInteger mTtsSeq =
            new java.util.concurrent.atomic.AtomicInteger();
    /** TTS 缓存目录上限：超过就滚动删最旧的（电视存储小，兜底用） */
    private static final long TTS_CACHE_MAX = 8L * 1024 * 1024;
    /** 正在播的文件，滚动清理时跳过 */
    private volatile File mPlayingFile = null;
    // TTS 相关已移除，仅保留百度在线合成
    private int mVoiceIdx = 0;

    /** 正文块：1=段落文字，2=图片 */
    static class Block {
        int type;
        String text;
        String url;
        Block(int t, String x) { type = t; text = x; }
    }

    static class Item {
        String title, desc, link, time, source, img;
    }

    private static void applyTrust(HttpURLConnection c) {
        if (c instanceof javax.net.ssl.HttpsURLConnection && TRUST_ALL != null) {
            javax.net.ssl.HttpsURLConnection h = (javax.net.ssl.HttpsURLConnection) c;
            h.setSSLSocketFactory(TRUST_ALL);
            h.setHostnameVerifier(new javax.net.ssl.HostnameVerifier() {
                @Override public boolean verify(String x, javax.net.ssl.SSLSession s) { return true; }
            });
        }
    }

    // ---------- 抓取 ----------

    private List<Item> fetchFeed(String url) {
        HttpURLConnection conn = null;
        try {
            final String real = url.startsWith("html::") ? url.substring(6) : url;
            conn = (HttpURLConnection) new URL(real).openConnection();
            applyTrust(conn);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 5.0.1) Chrome/60.0");
            InputStream is = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            String body = new String(bos.toByteArray(), "UTF-8");
            if (url.startsWith("html::")) return parseHtmlList(body, real);
            return url.contains("inews.qq.com") ? parseTencent(body) : parseRss(body, url);
        } catch (Throwable t) {
            Log.w("feed fail " + t);
            return new ArrayList<Item>();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static final Pattern ITEM = Pattern.compile("<item[^>]*>(.*?)</item>", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.DOTALL);
    private static final Pattern DESC = Pattern.compile("<description[^>]*>(.*?)</description>", Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile("<link[^>]*>(.*?)</link>", Pattern.DOTALL);
    private static final Pattern PUB = Pattern.compile("<pubDate[^>]*>(.*?)</pubDate>", Pattern.DOTALL);

    private List<Item> parseRss(String xml, String feedUrl) {
        List<Item> out = new ArrayList<>();
        Matcher m = ITEM.matcher(xml);
        while (m.find() && out.size() < 60) {
            String b = m.group(1);
            Item it = new Item();
            it.title = clean(pick(TITLE, b));
            String rawDesc = clean(pick(DESC, b));
            it.img = firstImage(rawDesc);
            if (it.img != null && !isContentImage(it.img)) it.img = null;
            it.desc = plainText(rawDesc);
            it.link = clean(pick(LINK, b));
            it.time = shortTime(clean(pick(PUB, b)));
            it.source = sourceOf(feedUrl);
            if (it.title != null && it.title.length() > 0) out.add(it);
        }
        return out;
    }

    /** 抓网页列表页（没有 RSS 的站点）：取 a 标签里的中文标题 */
    private List<Item> parseHtmlList(String html, String baseUrl) {
        List<Item> out = new ArrayList<Item>();
        try {
            java.util.Set<String> seen = new java.util.HashSet<String>();
            Matcher m = Pattern.compile(
                    "<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>([^<]{8,80})</a>",
                    Pattern.DOTALL).matcher(html);
            while (m.find() && out.size() < 40) {
                String href = m.group(1).trim();
                String title = stripHtml(clean(m.group(2)));
                if (title.length() < 8) continue;
                if (!title.matches(".*[\\u4e00-\\u9fff].*")) continue;
                if (href.startsWith("//")) href = "https:" + href;
                else if (href.startsWith("/")) href = baseUrl + href.substring(1);
                boolean looksArticle = href.matches(".*/[0-9A-Za-z_]+/[0-9]{6,}\\.html.*")
                        || href.matches(".*/c/[A-Za-z0-9]{8,}.*");
                if (!looksArticle) continue;
                if (seen.contains(href)) continue;
                seen.add(href);
                Item it = new Item();
                it.title = title;
                it.desc = "";
                it.link = href;
                it.time = "";
                it.source = sourceOf(baseUrl);
                out.add(it);
            }
        } catch (Throwable t) {
            Log.w("html list fail " + t);
        }
        return out;
    }

    private List<Item> parseTencent(String json) {
        List<Item> out = new ArrayList<>();
        try {
            org.json.JSONObject root = new org.json.JSONObject(json);
            org.json.JSONArray ids = root.optJSONArray("idlist");
            if (ids == null || ids.length() == 0) return out;
            org.json.JSONArray list = ids.optJSONObject(0).optJSONArray("newslist");
            if (list == null) return out;
            for (int i = 0; i < list.length(); i++) {
                org.json.JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id");
                if (id != null && id.startsWith("TIP")) continue;
                Item it = new Item();
                it.title = o.optString("title");
                it.desc = plainText(o.optString("abstract"));
                it.link = o.optString("url");
                String t = o.optString("time");
                Matcher m = Pattern.compile("(\\d{2}):(\\d{2})").matcher(t);
                it.time = m.find() ? m.group(0) : t;
                org.json.JSONObject card = o.optJSONObject("card");
                String src = card != null ? card.optString("chlname") : "";
                if (src == null || src.length() == 0) src = o.optString("source");
                it.source = (src == null || src.length() == 0) ? "腾讯新闻" : src;
                it.img = firstNonEmpty(o.optString("thumbnails_big"), o.optString("thumbnails"),
                        o.optString("fimgUrl"), o.optString("bigImage"));
                if (it.title != null && it.title.length() > 0) out.add(it);
            }
        } catch (Throwable t) {
            Log.w("tencent parse fail " + t);
        }
        return out;
    }

    private static String firstNonEmpty(String... vals) {
        if (vals == null) return null;
        for (String v : vals) {
            if (v != null && v.length() > 0 && !"null".equals(v)) return v;
        }
        return null;
    }

    private static String firstImage(String html) {
        if (html == null) return null;
        Matcher m = Pattern.compile("<img[^>]+(?:data-src|data-original|data-lazy-src|src)=[\"']([^\"']+)[\"']",
                Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find()) return m.group(1);
        Matcher m2 = Pattern.compile("(https?://[^\\s\"']+\\.(?:jpg|jpeg|png|webp))",
                Pattern.CASE_INSENSITIVE).matcher(html);
        if (m2.find()) return m2.group(1);
        return null;
    }

    private static String pick(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : "";
    }

    private static final Pattern ENTITY_PATTERN =
            Pattern.compile("&(#x?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);");

    /** 常见 HTML 命名实体 → 实际字符（空格类统一给普通空格，避免 TTS 读出/显示异常） */
    private static final java.util.HashMap<String, String> NAMED_ENTITIES = new java.util.HashMap<String, String>();
    static {
        NAMED_ENTITIES.put("nbsp", " ");
        NAMED_ENTITIES.put("emsp", " ");
        NAMED_ENTITIES.put("ensp", " ");
        NAMED_ENTITIES.put("lt", "<");
        NAMED_ENTITIES.put("gt", ">");
        NAMED_ENTITIES.put("amp", "&");
        NAMED_ENTITIES.put("quot", "\"");
        NAMED_ENTITIES.put("apos", "'");
        NAMED_ENTITIES.put("ldquo", "\u201c");
        NAMED_ENTITIES.put("rdquo", "\u201d");
        NAMED_ENTITIES.put("lsquo", "\u2018");
        NAMED_ENTITIES.put("rsquo", "\u2019");
        NAMED_ENTITIES.put("mdash", "\u2014");
        NAMED_ENTITIES.put("ndash", "\u2013");
        NAMED_ENTITIES.put("hellip", "\u2026");
        NAMED_ENTITIES.put("middot", "\u00b7");
    }

    /** 解码 HTML 实体：命名实体查表，数字实体（十进制/十六进制）按码位转换；无法识别的原样保留 */
    private static String decodeEntities(String s) {
        if (s == null) return "";
        Matcher m = ENTITY_PATTERN.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String e = m.group(1);
            String rep;
            if (e.charAt(0) == '#') {
                try {
                    int code;
                    if (e.length() > 1 && (e.charAt(1) == 'x' || e.charAt(1) == 'X')) {
                        code = Integer.parseInt(e.substring(2), 16);
                    } else {
                        code = Integer.parseInt(e.substring(1));
                    }
                    rep = new String(Character.toChars(code));
                } catch (Exception ex) {
                    rep = m.group(0);
                }
            } else {
                String mapped = NAMED_ENTITIES.get(e.toLowerCase(Locale.ROOT));
                rep = mapped != null ? mapped : m.group(0);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 把各类空格（含不换行/全角/em 空格）归一为普通空格并压成单个 */
    private static String normalizeSpaces(String s) {
        return s.replaceAll("[\\s\u00a0\u2002\u2003\u2009\u200a\u3000]+", " ");
    }

    private static String clean(String s) {
        if (s == null) return "";
        s = s.replace("<![CDATA[", "").replace("]]>", "");
        s = normalizeSpaces(decodeEntities(s));
        return s.trim();
    }

    private static String stripHtml(String s) {
        if (s == null) return "";
        s = s.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        if (s.length() > 1200) s = s.substring(0, 1200) + "…";
        return s;
    }

    /** 不截断的纯文本：正文/摘要要完整，不能像标题那样截到 1200 字 */
    private static String plainText(String s) {
        if (s == null) return "";
        s = s.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|li|h[1-6]|tr)>", "\n")
                .replaceAll("<[^>]+>", "");
        s = decodeEntities(s);
        // 行内空格（含不换行/全角/em 空格）归一，但保留换行
        s = s.replaceAll("[ \\t\u00a0\u2002\u2003\u2009\u200a\u3000]+", " ");
        s = s.replaceAll("\\n\\s*\\n+", "\n");
        return s.trim();
    }

    private static String sourceOf(String url) {
        if (url.contains("chinanews")) return "中新网";
        if (url.contains("people")) return "人民网";
        if (url.contains("ithome")) return "IT之家";
        if (url.contains("ifeng.com")) return "凤凰军事";
        if (url.contains("inews.qq.com")) return "腾讯新闻";
        return "新闻";
    }

    private static String shortTime(String s) {
        if (s == null || s.length() == 0) return "";
        Matcher m = Pattern.compile("(\\d{2}):(\\d{2})").matcher(s);
        if (m.find()) return m.group(0);
        return s.length() > 16 ? s.substring(0, 16) : s;
    }

    private void ensureLoaded(final int tab) {
        boolean has = tab < mData.size() && !mData.get(tab).isEmpty();
        long age = System.currentTimeMillis() - mLoadedAt[tab];
        if (has && age < TTL_MS) {
            bindTab(tab);
            return;
        }
        if (has) {
            bindTab(tab);
            status("刷新中…");
        } else {
            status("加载中…");
        }
        mPool.execute(new Runnable() {
            @Override public void run() {
                final List<Item> items = fetchFeed(FEEDS[tab]);
                mMain.post(new Runnable() {
                    @Override public void run() {
                        while (mData.size() <= tab) mData.add(new ArrayList<Item>());
                        if (!items.isEmpty()) {
                            mData.set(tab, items);
                            mLoadedAt[tab] = System.currentTimeMillis();
                            status("");
                        } else if (mData.get(tab).isEmpty()) {
                            status("没取到新闻（检查网络）");
                        }
                        if (mTab == tab) bindTab(tab);
                    }
                });
            }
        });
    }

    private void bindTab(int tab) {
        mTab = tab;
        mOffset = 0;
        mFocus = 0;
        mFlat = (tab < mData.size()) ? mData.get(tab) : new ArrayList<Item>();
        if (mListTitle != null) {
            switch (tab) {
                case 0: mListTitle.setText("腾讯热点"); break;
                case 1: mListTitle.setText("今日要闻速览"); break;
                case 2: mListTitle.setText("时政要闻"); break;
                case 3: mListTitle.setText("科技动态"); break;
                case 4: mListTitle.setText("财经快讯"); break;
                case 5: mListTitle.setText("国际要闻"); break;
                case 6: mListTitle.setText("军事观察"); break;
                default: mListTitle.setText("今日要闻速览"); break;
            }
        }
        refreshRows();
        updateSide();
        for (int i = 0; i < mChips.length; i++) {
            if (mChips[i] == null) continue;
            mChips[i].setBackground(chipBg(i, mChips[i].isFocused()));
            mChips[i].setTextColor(i == tab ? Color.WHITE : 0x99FFFFFF);
        }
    }

    private void status(final String s) {
        if (mStatus == null) return;
        mMain.post(new Runnable() {
            @Override public void run() {
                if (mStatus != null) mStatus.setText(s == null ? "" : s);
            }
        });
    }

    // ---------- UI ----------

    @Override
    public View onInflaterContent(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Context ctx = container != null ? container.getContext() : getActivity();
        mCtx = ctx;
        if (ctx == null) return new TextView(getActivity());
        mDensity = Math.round(ctx.getResources().getDisplayMetrics().density);
        if (mDensity <= 0) mDensity = 2;
        try {
            mSpeedIdx = ctx.getSharedPreferences("tvdesk_news", 0).getInt("speed", 1);
            mEngineIdx = ctx.getSharedPreferences("tvdesk_news", 0).getInt("engine", 1);
            mVoiceSel = ctx.getSharedPreferences("tvdesk_news", 0).getInt("voice", 0);
        } catch (Throwable ignored) { }
        if (mEngineIdx < 0 || mEngineIdx > 1) mEngineIdx = 1;
        mVoiceSel = clampVoice(mVoiceSel);
        // 插件每次被桌面重新加载都会走到这里：把上次崩溃/拔电留下的 tts 残留清一遍
        trimTtsCache();
        FrameLayout root = new FrameLayout(ctx);        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setPadding(dp(26), dp(100), dp(26), dp(10));
        root.setClipChildren(false);
        mRoot = root;

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout chips = new LinearLayout(ctx);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        chips.setPadding(dp(6), 0, 0, 0);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(6);
        chips.setLayoutParams(clp);
        for (int i = 0; i < TITLES.length; i++) {
            final int idx = i;
            TextView chip = new TextView(ctx);
            chip.setText(TITLES[i]);
            chip.setTextSize(17f);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(15), dp(6), dp(15), dp(7));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            chip.setLayoutParams(lp);
            chip.setFocusable(true);
            chip.setFocusableInTouchMode(false);
            chip.setClickable(true);
            chip.setBackground(chipBg(i, false));
            chip.setTextColor(i == 0 ? Color.WHITE : 0x99FFFFFF);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { onChip(idx); }
            });
            chip.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean f) { v.setBackground(chipBg(idx, f)); }
            });
            chip.setOnKeyListener(new View.OnKeyListener() {
                @Override public boolean onKey(View v, int code, KeyEvent e) {
                    if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                    if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                        if (mRows[0] != null) mRows[0].requestFocus();
                        return true;
                    }
                    if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_LEFT) {
                        int next = idx + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        if (next >= 0 && next < mChips.length) mChips[next].requestFocus();
                        return true;
                    }
                    if (code == KeyEvent.KEYCODE_BACK) {
                        goToTabBar();
                        return true;
                    }
                    return false;
                }
            });
            mChips[i] = chip;
            chips.addView(chip);
        }
        col.addView(chips);

        mStatus = new TextView(ctx);
        mStatus.setTextColor(0xFFFFD479);
        mStatus.setTextSize(12f);
        mStatus.setGravity(Gravity.CENTER_VERTICAL);
        mStatus.setText("");
        mStatus.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(20)));
        col.addView(mStatus);

        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        content.setClipChildren(false);
        content.addView(buildLeft(ctx));
        content.addView(buildRight(ctx));
        col.addView(content);
        root.addView(col);
        root.addView(buildVoicePanel(ctx));

        bindTab(0);
        ensureLoaded(0);
        return root;
    }

    private View buildLeft(Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1.18f);
        lp.rightMargin = dp(12);
        box.setLayoutParams(lp);
        box.setPadding(dp(10), dp(10), dp(10), dp(8));
        box.setBackground(panelBg());
        box.setClipChildren(false);

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = dp(8);
        head.setLayoutParams(hlp);

        TextView t = new TextView(ctx);
        t.setText("今日要闻速览");
        mListTitle = t;
        t.setTextSize(17f);
        t.setTextColor(Color.WHITE);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView cnt = new TextView(ctx);
        cnt.setText("实时");
        cnt.setTextSize(12f);
        cnt.setTextColor(0xFF7C8494);
        head.addView(cnt);
        box.addView(head);

        for (int i = 0; i < ROWS; i++) {
            final int slot = i;
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            row.setPadding(dp(8), 0, dp(8), 0);
            row.setFocusable(true);
            row.setFocusableInTouchMode(false);
            row.setClickable(true);
            row.setBackground(rowBg(false));

            TextView num = new TextView(ctx);
            num.setTextSize(14f);
            num.setTextColor(0xFF6C7486);
            num.setMinWidth(dp(24));
            row.addView(num);

            TextView title = new TextView(ctx);
            title.setTextSize(14.5f);
            title.setTextColor(0xFFE8ECF2);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(dp(6), 0, 0, 0);
            row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean f) {
                    v.setBackground(rowBg(f));
                    if (f) {
                        mFocus = mOffset + slot;
                        updateSide();
                    }
                }
            });
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    int i2 = mOffset + slot;
                    if (i2 < mFlat.size()) toggleRead(i2);
                }
            });
            row.setOnKeyListener(rowKeys);
            mRows[i] = row;
            mNums[i] = num;
            mRowTitles[i] = title;
            box.addView(row);
        }
        return box;
    }

    private View buildRight(Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        box.setClipChildren(false);

        // ---- 顶部：音色 / 语速 ----
        LinearLayout topBar = new LinearLayout(ctx);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = dp(8);
        topBar.setLayoutParams(tlp);

        mVoiceBar = new TextView(ctx);
        mVoiceBar.setTextSize(12.5f);
        mVoiceBar.setTextColor(0xFFB8C0CC);
        mVoiceBar.setGravity(Gravity.CENTER);
        mVoiceBar.setPadding(dp(10), dp(7), dp(10), dp(7));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mVoiceBar.setLayoutParams(vlp);
        mVoiceBar.setFocusable(true);
        mVoiceBar.setFocusableInTouchMode(false);
        mVoiceBar.setClickable(true);
        mVoiceBar.setBackground(panelBg());
        mVoiceBar.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean f) {
                v.setBackground(f ? headBg(true) : panelBg());
            }
        });
        mVoiceBar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openVoicePanel(); }
        });
        mVoiceBar.setOnKeyListener(sideKeys);
        topBar.addView(mVoiceBar);

        box.addView(topBar);
        updateVoiceBar();

        // ---- 下面：详情（可上下滚动）----
        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        top.setPadding(dp(16), dp(14), dp(16), dp(14));
        top.setBackground(panelBg());
        top.setFocusable(true);
        top.setFocusableInTouchMode(false);
        top.setClickable(true);
        mHeadCard = top;

        mHeadKind = new TextView(ctx);
        mHeadKind.setText("头条");
        mHeadKind.setTextSize(12f);
        mHeadKind.setTextColor(0xFFFF5C5C);
        top.addView(mHeadKind);

        mHeadTitle = new TextView(ctx);
        mHeadTitle.setTextSize(21f);
        mHeadTitle.setTextColor(Color.WHITE);
        mHeadTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        mHeadTitle.setMaxLines(3);
        mHeadTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(8);
        top.addView(mHeadTitle, hlp);

        mHeadDesc = new TextView(ctx);
        mHeadDesc.setTextSize(13.5f);
        mHeadDesc.setTextColor(0xFFBAC0CC);
        mHeadDesc.setLineSpacing(dp(4), 1f);
        mHeadDesc.setMaxLines(Integer.MAX_VALUE);
        mHeadDesc.setEllipsize(null);

        mDetailScroll = new android.widget.ScrollView(ctx);
        mDetailScroll.setVerticalScrollBarEnabled(false);
        mDetailScroll.setFocusable(false);
        mDetailScroll.setFocusableInTouchMode(false);
        mDetailScroll.setPadding(0, dp(8), 0, 0);

        mDocBox = new LinearLayout(ctx);
        mDocBox.setOrientation(LinearLayout.VERTICAL);
        mDocBox.addView(mHeadDesc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mDetailScroll.addView(mDocBox, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        top.addView(mDetailScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        mHeadMeta = new TextView(ctx);
        mHeadMeta.setTextSize(11.5f);
        mHeadMeta.setTextColor(0xFF7C8494);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dp(8);
        top.addView(mHeadMeta, mlp);

        top.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean f) {
                v.setBackground(f ? headBg(true) : panelBg());
            }
        });
        top.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { readDetail(); }
        });
        top.setOnKeyListener(sideKeys);
        box.addView(top);
        return box;
    }

    private View buildVoicePanel(Context ctx) {
        mVoicePanel = new FrameLayout(ctx);
        mVoicePanel.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mVoicePanel.setBackgroundColor(0xCC000000);
        mVoicePanel.setVisibility(View.GONE);
        mVoicePanel.setClickable(true);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(430),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.CENTER;
        box.setLayoutParams(blp);
        box.setPadding(dp(18), dp(16), dp(18), dp(16));
        box.setBackground(panelBg());

        TextView h = new TextView(ctx);
        h.setText("朗读设置");
        h.setTextSize(18f);
        h.setTextColor(Color.WHITE);
        h.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.bottomMargin = dp(10);
        box.addView(h, hl);

        for (int i = 0; i < mVoiceRows.length; i++) {
            final int idx = i;
            TextView row = new TextView(ctx);
            row.setTextSize(15f);
            row.setTextColor(0xFFE8ECF2);
            row.setPadding(dp(12), dp(9), dp(12), dp(9));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            row.setLayoutParams(lp);
            row.setFocusable(true);
            row.setFocusableInTouchMode(false);
            row.setClickable(true);
            row.setBackground(rowBg(false));
            row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean f) { v.setBackground(rowBg(f)); }
            });
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { onVoiceRow(idx); }
            });
            row.setOnKeyListener(new View.OnKeyListener() {
                @Override public boolean onKey(View v, int code, KeyEvent e) {
                    if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                    if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                        int n2 = idx + 1;
                        if (n2 < mVoiceRows.length && mVoiceRows[n2] != null) mVoiceRows[n2].requestFocus();
                        return true;
                    }
                    if (code == KeyEvent.KEYCODE_DPAD_UP) {
                        int n2 = idx - 1;
                        if (n2 >= 0 && mVoiceRows[n2] != null) mVoiceRows[n2].requestFocus();
                        return true;
                    }
                    if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_LEFT
                            || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        closeVoicePanel();
                        return true;
                    }
                    return false;
                }
            });
            mVoiceRows[i] = row;
            box.addView(row);
        }
        mVoicePanel.addView(box);
        refreshVoiceRows();
        return mVoicePanel;
    }

    /** 右栏配图：有就显示，没有就隐藏 */
    private void loadHeadImage(final String url) {
        if (mHeadImg == null) return;
        if (url == null || url.length() == 0) {
            mHeadImg.setVisibility(View.GONE);
            mHeadImg.setImageDrawable(null);
            mHeadImg.setTag(null);
            return;
        }
        mHeadImg.setTag(url);
        Bitmap cached = mImgCache.get(url);
        if (cached != null) {
            mHeadImg.setImageBitmap(cached);
            mHeadImg.setVisibility(View.VISIBLE);
            return;
        }
        // 还没图：先隐藏，等解码成功再显示，避免留空白
        mHeadImg.setVisibility(View.GONE);
        mHeadImg.setImageDrawable(null);
        mImgPool.execute(new Runnable() {
            @Override public void run() {
                final Bitmap bm = downloadBitmap(url);
                mMain.post(new Runnable() {
                    @Override public void run() {
                        if (mHeadImg == null || !url.equals(mHeadImg.getTag())) return;
                        if (bm == null) {
                            mHeadImg.setVisibility(View.GONE);
                            return;
                        }
                        mImgCache.put(url, bm);
                        mHeadImg.setImageBitmap(bm);
                        mHeadImg.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    private Bitmap downloadBitmap(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            applyTrust(c);
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 5.0.1) Chrome/60.0");
            c.setRequestProperty("Referer", "https://news.qq.com/");
            InputStream is = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            byte[] data = bos.toByteArray();
            if (data.length < 200) return null;
            android.graphics.BitmapFactory.Options op = new android.graphics.BitmapFactory.Options();
            op.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, op);
            int sample = 1;
            while (op.outWidth / sample > 900) sample *= 2;
            op.inJustDecodeBounds = false;
            op.inSampleSize = sample;
            return android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, op);
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 用 WebView 显示文章原页 */
    private void showWeb(String url) {
        if (mWeb == null) return;
        mDetailScroll.setVisibility(View.GONE);
        mWeb.setVisibility(View.VISIBLE);
        if (!url.equals(mWebUrl)) {
            mWebUrl = url;
            mWeb.loadUrl(url);
        }
    }

    private void hideWeb() {
        if (mWeb == null) return;
        mWeb.setVisibility(View.GONE);
        mDetailScroll.setVisibility(View.VISIBLE);
        mWebUrl = null;
    }

    /** 隐藏广告/推荐/评论，改深色，图片自适应宽度 */
    private void injectStyle(android.webkit.WebView v) {
        String js =
            "(function(){"
          + "var s=document.createElement('style');"
          + "s.innerHTML='"
          + "body,html{background:#10141D !important;color:#BAC0CC !important;}"
          + "*{max-width:100% !important;}"
          + "img,video{height:auto !important;}"
          + ".ad,.ads,.advert,[class*=advert],[id*=advert],"
          + "[class*=recommend],[id*=recommend],"
          + "[class*=comment],[id*=comment],"
          + "[class*=footer],[class*=nav-bar],[class*=app-download],"
          + "[class*=share-bar]{display:none !important;}"
          + "a{color:#7FA8FF !important;}"
          + "';"
          + "(document.head||document.body).appendChild(s);"
          + "})();";
        try {
            v.evaluateJavascript(js, null);
        } catch (Throwable t) {
            Log.w("inject fail " + t);
        }
    }

    private void refreshVoiceRows() {
        if (mVoiceRows[0] == null) return;
        mVoiceRows[0].setText("引擎：" + engineName() + "  （按确定切换）");
        mVoiceRows[1].setText("音色：" + currentVoiceName() + "  （按确定切换）");
        mVoiceRows[2].setText("语速：" + speedLabel() + "  （按确定切换）");
        mVoiceRows[3].setText("关闭");
    }

    private String speedLabel() {
        return "x" + String.format(Locale.US, "%.1f", SPEEDS[mSpeedIdx]);
    }

    private String engineName() {
        return mEngineIdx == 1 ? "微软 Edge" : "百度在线";
    }

    /** 当前引擎可选的音色表：{接口参数, 显示名} */
    private String[][] voices() {
        return mEngineIdx == 1 ? EDGE_VOICES : BAIDU_VOICES;
    }

    private int clampVoice(int v) {
        int n = voices().length;
        return (v < 0 || v >= n) ? 0 : v;
    }

    private void openVoicePanel() {
        refreshVoiceRows();
        mVoicePanel.setVisibility(View.VISIBLE);
        if (mVoiceRows[3] != null) mVoiceRows[3].requestFocus();
    }

    private void closeVoicePanel() {
        mVoicePanel.setVisibility(View.GONE);
        if (mVoiceBar != null) mVoiceBar.requestFocus();
    }

    // ---------------- 引擎 / 音色 / 语速 ----------------
    private String currentKind() {
        return mEngineIdx == 1 ? "edge" : "baidu";
    }

    private String currentParam() {
        return voices()[clampVoice(mVoiceSel)][0];
    }

    /** 当前音色的可读名（语音条/面板显示） */
    private String currentVoiceName() {
        return voices()[clampVoice(mVoiceSel)][1];
    }

    /** 音色选项数 */
    private int voiceOptionCount() {
        return voices().length;
    }

    private void onVoiceRow(int idx) {
        if (idx == 3) { closeVoicePanel(); return; }
        if (idx == 0) {
            mEngineIdx = (mEngineIdx + 1) % 2;
            mVoiceSel = 0;
            try {
                mCtx.getSharedPreferences("tvdesk_news", 0).edit()
                        .putInt("engine", mEngineIdx).putInt("voice", 0).apply();
            } catch (Throwable ignored) { }
            refreshVoiceRows();
            updateVoiceBar();
            restartSpeech();
            return;
        }
        if (idx == 1) {
            int n = voiceOptionCount();
            if (n <= 1) { status("这个引擎只有一种音色"); return; }
            mVoiceSel = (mVoiceSel + 1) % n;
            try {
                mCtx.getSharedPreferences("tvdesk_news", 0).edit().putInt("voice", mVoiceSel).apply();
            } catch (Throwable ignored) { }
            refreshVoiceRows();
            updateVoiceBar();
            restartSpeech();
            return;
        }
        // 语速
        mSpeedIdx = (mSpeedIdx + 1) % SPEEDS.length;
        try {
            mCtx.getSharedPreferences("tvdesk_news", 0).edit().putInt("speed", mSpeedIdx).apply();
        } catch (Throwable ignored) { }
        refreshVoiceRows();
        updateVoiceBar();
        restartSpeech();
    }

    /** 换了引擎/音色/语速：立刻用新参数重播当前这一条（轮播时只重播标题） */
    private void restartSpeech() {
        if (!mReading || mReadIndex < 0 || mReadIndex >= mFlat.size()) return;
        releasePlayer();
        clearPrefetch();
        Item it = mFlat.get(mReadIndex);
        String text = mChain ? speakTitle(it.title) : currentText(it);
        mChunks = splitChunks(text);
        mChunkIdx = 0;
        speakChunk(mReadIndex);
    }

    private void updateVoiceBar() {
        if (mVoiceBar == null) return;
        mVoiceBar.setText(engineName() + " · " + currentVoiceName() + "  ·  "
                + speedLabel() + "  ▪ 按确定设置");
    }

    private GradientDrawable panelBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xE610141D);
        d.setCornerRadius(dp(12));
        return d;
    }

    private GradientDrawable headBg(boolean f) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(12));
        d.setColor(0xE610141D);
        if (f) d.setStroke(dp(3), 0xFF2674EE);
        return d;
    }

    private GradientDrawable rowBg(boolean f) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(8));
        d.setColor(f ? 0xFF2674EE : 0x00000000);
        return d;
    }

    private GradientDrawable chipBg(int idx, boolean f) {
        GradientDrawable d = new GradientDrawable();
        boolean on = idx == mTab;
        d.setColor(f ? 0xFF2674EE : (on ? 0x44FFFFFF : 0x1AFFFFFF));
        d.setCornerRadius(dp(15));
        if (f) d.setStroke(dp(2), 0xFFFFFFFF);
        return d;
    }

    private void onChip(int idx) {
        if (idx == mTab) mLoadedAt[idx] = 0;
        bindTab(idx);
        ensureLoaded(idx);
    }

    private int readBase() {
        return Math.max(0, Math.min(mFocus, Math.max(0, mFlat.size() - 1)));
    }

    private void refreshRows() {
        for (int i = 0; i < ROWS; i++) {
            int idx = mOffset + i;
            if (idx < mFlat.size()) {
                Item it = mFlat.get(idx);
                mNums[i].setText(String.format(Locale.US, "%02d", idx + 1));
                mNums[i].setTextColor(idx < 3 ? 0xFFFF5C5C : 0xFF6C7486);
                mRowTitles[i].setText((idx == mReadIndex ? "♪ " : "") + it.title);
                mRows[i].setVisibility(View.VISIBLE);
            } else {
                mNums[i].setText("");
                mRowTitles[i].setText("");
                mRows[i].setVisibility(View.INVISIBLE);
            }
        }
    }

    private void updateSide() {
        if (mFlat.isEmpty()) {
            mHeadTitle.setText("没有新闻");
            mHeadDesc.setText("");
            mHeadMeta.setText("");
            return;
        }
        int i = readBase();
        Item cur = mFlat.get(i);
        mHeadKind.setText(i < 3 ? "头条" : "速览");
        mHeadTitle.setText(cur.title);
        mDocText = trimArticleTail(cur.desc == null ? "" : cur.desc);
        hideWeb();
        java.util.List<Block> cachedDoc = (cur.link == null) ? null : mDocCache.get(cur.link);
        if (cachedDoc != null && !cachedDoc.isEmpty()) {
            renderDoc(cachedDoc);
        } else {
            mHeadDesc.setText(mDocText);
            if (mDocBox != null) {
                mDocBox.removeAllViews();
                mDocBox.addView(mHeadDesc, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }
        mHeadMeta.setText(cur.source + (cur.time == null || cur.time.isEmpty() ? "" : " · " + cur.time));
        if (mDetailScroll != null) mDetailScroll.scrollTo(0, 0);
        loadFullText(cur, i);
    }

    /** 抓正文：先显示摘要，拿到全文后替换 */
    private void loadFullText(final Item it, final int index) {
        if (it == null || it.link == null || !it.link.startsWith("http")) return;
        String cached = mFullCache.get(it.link);
        if (cached != null) {
            if (mHeadDesc != null && index == readBase()) {
                mHeadDesc.setText(cached);
                if (mDetailScroll != null) mDetailScroll.scrollTo(0, 0);
            }
            return;
        }
        mPool.execute(new Runnable() {
            @Override public void run() {
                final String full = fetchBody(it.link);
                if (full == null || full.length() < 200) return;
                mFullCache.put(it.link, full);
                mMain.post(new Runnable() {
                    @Override public void run() {
                        if (mHeadDesc != null && index == readBase()) {
                            java.util.List<Block> doc =
                                    (it.link == null) ? null : mDocCache.get(it.link);
                            if (doc != null && !doc.isEmpty()) {
                                renderDoc(doc);
                            } else {
                                mHeadDesc.setText(full);
                            }
                            if (mDetailScroll != null) mDetailScroll.scrollTo(0, 0);
                        }
                    }
                });
            }
        });
    }

    private String fetchBody(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            applyTrust(c);
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 5.0.1; LeTV) AppleWebKit/537.36 Chrome/60.0");
            InputStream is = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            String html = new String(bos.toByteArray(), "UTF-8");
            java.util.List<Block> doc = extractDoc(html);
            if (doc != null && !doc.isEmpty()) mDocCache.put(url, doc);
            encodeText(doc);
            return joinText(doc);
        } catch (Throwable t) {
            Log.w("body fail " + t);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 从网页里抽正文：优先 <p> 段落，退路是 class 含 content 的大块 */
    /** 页面上的“听全文/多少人听过/相关阅读”等杂物，以及 CSS/JS 片段，不进正文 */
    private static boolean isNoiseLine(String t) {
        if (t == null || t.length() == 0) return true;
        String[] bad = {"人听过", "听全文", "问AI", "相关阅读", "免责声明", "责任编辑",
                "举报", "热门推荐", "打开APP", "下载客户端", "扫码", "分享到", "返回顶部",
                "阅读原文", "查看更多", "广告", "rgba(", "--weui", "data_color", "px;",
                "font-size", "margin", "padding", "@media", "var(--"};
        for (String b : bad) {
            if (t.contains(b)) return true;
        }
        if (t.contains("{") && t.contains("}")) return true;
        // 中文字符占比过低 → 多半是代码/样式
        int cn = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) cn++;
        }
        if (cn * 4 < t.length()) return true;
        return t.length() < 12 && (t.endsWith(">>") || t.startsWith("来源"));
    }
    /** 从正文区域抽图片，剔除 logo/图标/二维码/广告等 */
    private void extractImages(String html, String link) {
        try {
            String clean = html;
            clean = clean.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
            clean = clean.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
            // 优先在正文容器里找
            String scope = null;
            Matcher d = Pattern.compile(
                    "<div[^>]+class=\"[^\"]*(?:content|article|Cnt|text)[^\"]*\"[^>]*>(.*?)</div>",
                    Pattern.DOTALL).matcher(clean);
            String best = null;
            while (d.find()) {
                String seg = d.group(1);
                if (best == null || seg.length() > best.length()) best = seg;
            }
            scope = (best != null && best.length() > 400) ? best : clean;
            java.util.List<String> imgs = new ArrayList<String>();
            Matcher m = Pattern.compile("<img[^>]+(?:data-src|data-original|data-lazy-src|src)=[\"']([^\"']+)[\"']",
                    Pattern.CASE_INSENSITIVE).matcher(scope);
            while (m.find() && imgs.size() < 8) {
                String u = m.group(1);
                if (isContentImage(u) && !imgs.contains(u)) imgs.add(u);
            }
            // 懒加载页面的正文图常只出现在脚本/JSON 里，再按域名扫一遍
            Matcher g = Pattern.compile(
                    "https?://inews\\.gtimg\\.com/(?:om_ls|om_bt|newsapp_bt)/[^\"')\\s<>]+",
                    Pattern.CASE_INSENSITIVE).matcher(clean);
            while (g.find() && imgs.size() < 8) {
                String u = g.group(0);
                if (isContentImage(u) && !imgs.contains(u)) imgs.add(u);
            }
            // 二轮：整页找图片链接（很多图只在脚本 JSON 里出现）
            Matcher any = Pattern.compile(
                    "https?://[^\"'\\s<>()]+?\\.(?:jpg|jpeg|png|webp)",
                    Pattern.CASE_INSENSITIVE).matcher(clean);
            while (any.find() && imgs.size() < 8) {
                String u = any.group(0);
                if (isContentImage(u) && !imgs.contains(u)) imgs.add(u);
            }
            if (!imgs.isEmpty()) mArticleImgs.put(link, imgs);
        } catch (Throwable t) {
            Log.w("extractImages fail " + t);
        }
    }

    /** 是否是“内容图片”：排除头像、logo、二维码、表情、广告等 */
    private static boolean isContentImage(String u) {
        if (u == null || u.length() < 12) return false;
        String l = u.toLowerCase();
        if (!l.startsWith("http")) return false;
        String[] bad = {"logo", "icon", "avatar", "qrcode", "emoji", "emoticon",
                "advert", "/ad/", "banner_small", "placeholder",
                "share_", "sprite", "1x1", "spacer", "blank.gif", "favicon",
                "static/", ".js", ".css", "min.js", "polyfill", "mat1.gtimg.com/qqcdn"};
        for (String b : bad) {
            if (l.contains(b)) return false;
        }
        return l.contains(".jpg") || l.contains(".jpeg") || l.contains(".png") || l.contains(".webp")
                || l.contains("/img") || l.contains("image") || l.contains("gtimg")
                || l.contains("sinaimg") || l.contains("people.com.cn") || l.contains("chinanews");
    }


    private static void encodeText(java.util.List<Block> doc) { }

    private static String joinText(java.util.List<Block> doc) {
        if (doc == null) return null;
        StringBuilder sb = new StringBuilder();
        for (Block b : doc) {
            if (b.type == 1) sb.append(b.text).append("\n\n");
        }
        return sb.length() == 0 ? null : sb.toString().trim();
    }

    /** 正文容器关键词：class/id 命中这些才当前候选 */
    private static final Pattern CONTAINER_KW = Pattern.compile(
            "article|content|main|text|detail|post|body|cont_|news_txt|TRS_Editor",
            Pattern.CASE_INSENSITIVE);

    /**
     * 先找到正文所在的 div（整页 <p> 会把相关推荐、页脚一起抓进来），
     * 返回容器内 HTML；找不到就退回整页。
     */
    private static String pickScope(String clean) {
        Matcher m = Pattern.compile("(?is)<div\\b[^>]*(?:class|id)=\"([^\"]*)\"[^>]*>").matcher(clean);
        int bestA = -1, bestB = -1, bestText = -1;         // 密度达标里文字最多的
        int altA = -1, altB = -1, altDensity = -1;         // 退路：密度最高的
        while (m.find()) {
            if (!CONTAINER_KW.matcher(m.group(1)).find()) continue;
            int start = m.end();
            int end = divEnd(clean, start);
            if (end <= start) continue;
            String inner = clean.substring(start, end);
            int text = plainText(inner).length();
            if (text < 200) continue;
            int density = (int) ((long) text * 1000 / Math.max(1, inner.length()));
            if (density >= 180 && text > bestText) { bestText = text; bestA = start; bestB = end; }
            if (density > altDensity) { altDensity = density; altA = start; altB = end; }
        }
        if (bestA >= 0) return clean.substring(bestA, bestB);
        if (altA >= 0) return clean.substring(altA, altB);
        return clean;
    }

    /** 从 div 开标签之后找到配对的 </div>，返回其起始位置 */
    private static int divEnd(String s, int from) {
        int depth = 1, i = from;
        while (depth > 0) {
            int o = s.indexOf("<div", i);
            int c = s.indexOf("</div", i);
            if (c < 0) return -1;
            if (o >= 0 && o < c) { depth++; i = o + 4; }
            else { depth--; i = c + 5; }
        }
        return i - 6;
    }

    /** 把正文拆成“段落 / 图片”的顺序块 */
    private static java.util.List<Block> extractDoc(String html) {
        if (html == null) return null;
        String clean = html.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        clean = clean.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        clean = clean.replaceAll("(?is)<noscript[^>]*>.*?</noscript>", " ");
        String scope = pickScope(clean);

        java.util.List<Block> out = new ArrayList<Block>();
        Matcher m = Pattern.compile("<p\\b[^>]*>(.*?)</p>|<img\\b[^>]*>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(scope);
        while (m.find()) {
            if (m.group(1) != null) {
                String t = plainText(clean(m.group(1)));
                if (isTailMarker(t)) break;          // 相关阅读/责任编辑…：后面全是别的标题
                int cut = tailCutIndex(t);           // 尾巴常和正文挤在同一段里
                if (cut >= 0) {
                    String head = t.substring(0, cut).trim();
                    if (head.length() > 8 && !isNoiseLine(head)) out.add(new Block(1, head));
                    break;
                }
                if (t.length() > 8 && !isNoiseLine(t)) {
                    out.add(new Block(1, t));
                }
            }
        }
        // 有的站正文不用 <p>：按 <br>/块标签切一遍兜底
        int total = 0;
        for (Block b : out) if (b.type == 1) total += b.text.length();
        if (total < 150) {
            String plain = plainText(scope);
            for (String line : plain.split("\n")) {
                String t = line.trim();
                if (t.length() > 8 && !isTailMarker(t) && !isNoiseLine(t)) {
                    out.add(new Block(1, t));
                }
            }
        }
        // 结尾常被塞一串别的新闻标题（没有句号、十几个字）：连着两条以上才当尾巴剔掉
        int tail = 0;
        for (int i = out.size() - 1; i >= 0; i--) {
            Block b = out.get(i);
            if (b.type == 1 && isTitleLike(b.text)) tail++;
            else break;
        }
        if (tail >= 2) {
            for (int k = 0; k < tail; k++) out.remove(out.size() - 1);
        }
        return out;
    }

    /** 正文结束标志：这些行之后就是相关推荐 / 其他标题 */
    private static boolean isTailMarker(String t) {
        if (t == null) return false;
        String s = t.trim();
        if (s.length() == 0 || s.length() > 20) return false;
        String[] marks = {"相关阅读", "相关推荐", "相关新闻", "热门推荐", "猜你喜欢",
                "延伸阅读", "推荐阅读", "更多新闻", "更多精彩", "责任编辑",
                "原标题", "编辑：", "编辑:", "来源：", "来源:", "点击查看更多"};
        for (String mk : marks) if (s.contains(mk)) return true;
        return false;
    }

    /** 尾巴标记出现在段落中间时，返回截断位置；没有则 -1 */
    private static int tailCutIndex(String t) {
        if (t == null) return -1;
        String s = t;
        String[] marks = {"（完）", "(完)", "【编辑", "责任编辑", "更多精彩内容",
                "更多新闻", "相关阅读", "相关推荐", "推荐阅读", "热门推荐",
                "发表评论", "文明上网理性发言", "国际新闻精选", "国内新闻精选",
                "原标题", "点击查看更多", "扫一扫", "打开APP"};
        int best = -1;
        for (String mk : marks) {
            int i = s.indexOf(mk);
            if (i > 0 && (best < 0 || i < best)) best = i;
        }
        // 太靠前说明整段都是这类内容（导航/声明），交给 isNoiseLine 处理
        return (best > 12) ? best : -1;
    }

    /** 像标题的短句：没有句末标点（正文段落一般都有句号） */
    private static boolean isTitleLike(String t) {
        String s = t.trim();
        if (s.length() > 42) return false;
        if (s.length() < 6) return true;
        char c = s.charAt(s.length() - 1);
        return "。！？…”』」）)!?.".indexOf(c) < 0;
    }

    /** 按块渲染：文字段落 + 图片，像网页一样顺序排 */
    private void renderDoc(final java.util.List<Block> doc) {
        if (mDocBox == null) return;
        mDocBox.removeAllViews();
        if (doc == null || doc.isEmpty()) {
            mDocBox.addView(mHeadDesc, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        StringBuilder plain = new StringBuilder();
        for (final Block b : doc) {
            if (b.type == 1) {
                TextView tv = new TextView(mCtx);
                tv.setText(b.text);
                tv.setTextSize(13.5f);
                tv.setTextColor(0xFFBAC0CC);
                tv.setLineSpacing(dp(5), 1f);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(6);
                lp.bottomMargin = dp(6);
                mDocBox.addView(tv, lp);
                plain.append(b.text).append("\n\n");
            }
        }
        mDocText = plain.toString().trim();
    }

    /** 单张图片异步加载，成功才占位 */
    private void loadInto(final android.widget.ImageView iv, final String url) {
        Bitmap cached = mImgCache.get(url);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        iv.setVisibility(View.GONE);
        mImgPool.execute(new Runnable() {
            @Override public void run() {
                final Bitmap bm = downloadBitmap(url);
                mMain.post(new Runnable() {
                    @Override public void run() {
                        if (bm == null) {
                            iv.setVisibility(View.GONE);
                            return;
                        }
                        mImgCache.put(url, bm);
                        iv.setImageBitmap(bm);
                        iv.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    private static String extractBody(String html) {
        if (html == null) return null;
        // 先把 style/script 整段去掉，避免把 CSS 当成正文
        html = html.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        html = html.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        html = html.replaceAll("(?is)<noscript[^>]*>.*?</noscript>", " ");
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("<p[^>]*>(.*?)</p>", Pattern.DOTALL).matcher(html);
        while (m.find()) {
            String t = stripHtml(clean(m.group(1)));
            if (t.length() > 12 && !isNoiseLine(t)) sb.append(t).append("\n\n");
        }
        if (sb.length() > 300) return sb.toString().trim();
        String best = "";
        Matcher d = Pattern.compile("<div[^>]+class=\"[^\"]*(?:content|article|Cnt|text)[^\"]*\"[^>]*>(.*?)</div>",
                Pattern.DOTALL).matcher(html);
        while (d.find()) {
            String t = stripHtml(clean(d.group(1)));
            if (t.length() > best.length()) best = t;
        }
        return best.length() > 200 ? best : null;
    }

    private final View.OnKeyListener rowKeys = new View.OnKeyListener() {
        @Override public boolean onKey(View v, int code, KeyEvent e) {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            int size = mFlat.size();
            if (size == 0) return false;
            int slot = mFocus - mOffset;
            if (slot < 0 || slot >= ROWS) slot = 0;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (slot == ROWS - 1) {
                        if (mOffset + ROWS >= size) return true;
                        mOffset++;
                        mFocus = mOffset + slot;
                        refreshRows();
                        updateSide();
                    } else {
                        mRows[slot + 1].requestFocus();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (slot == 0) {
                        if (mOffset == 0) {
                            if (mChips[mTab] != null) mChips[mTab].requestFocus();
                            return true;
                        }
                        mOffset--;
                        mFocus = mOffset;
                        refreshRows();
                        updateSide();
                        mRows[0].requestFocus();
                    } else {
                        mRows[slot - 1].requestFocus();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (mHeadCard != null) mHeadCard.requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    // 先在左侧区域回到第一条新闻，再按一次才到分类条
                    if (mOffset != 0 || mFocus != 0) {
                        mOffset = 0;
                        mFocus = 0;
                        refreshRows();
                        updateSide();
                        if (mRows[0] != null) mRows[0].requestFocus();
                    } else if (mChips[mTab] != null) {
                        mChips[mTab].requestFocus();
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (mReading) { stopReading(); return true; }
                    if (mChips[mTab] != null) mChips[mTab].requestFocus();
                    return true;
                default:
                    return false;
            }
        }
    };

    private final View.OnKeyListener sideKeys = new View.OnKeyListener() {
        @Override public boolean onKey(View v, int code, KeyEvent e) {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT: {
                    int slot = mFocus - mOffset;
                    if (slot < 0 || slot >= ROWS) slot = 0;
                    if (mRows[slot] != null) mRows[slot].requestFocus();
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return true;   // 最右侧不再外泄
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (v == mVoiceBar) {
                        if (mHeadCard != null) mHeadCard.requestFocus();
                    } else if (mWeb != null && mWeb.getVisibility() == View.VISIBLE) {
                        mWeb.scrollBy(0, dp(110));
                    } else if (mDetailScroll != null) {
                        mDetailScroll.smoothScrollBy(0, dp(110));
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (v != mHeadCard) {
                        return true;
                    }
                    if (mWeb != null && mWeb.getVisibility() == View.VISIBLE) {
                        if (mWeb.getScrollY() > 0) {
                            mWeb.scrollBy(0, -dp(110));
                        } else if (mVoiceBar != null) {
                            mVoiceBar.requestFocus();
                        }
                    } else if (mDetailScroll != null && mDetailScroll.canScrollVertically(-1)) {
                        mDetailScroll.smoothScrollBy(0, -dp(110));
                    } else if (mVoiceBar != null) {
                        mVoiceBar.requestFocus();
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (mReading) { stopReading(); return true; }
                    if (mChips[mTab] != null) mChips[mTab].requestFocus();
                    return true;
                default:
                    return false;
            }
        }
    };

    private void goToTabBar() {
        View byText = findByText(mRoot.getRootView(), "新闻", 0);
        if (byText != null) {
            View target = byText;
            while (target != null && !target.isFocusable()) {
                android.view.ViewParent p = target.getParent();
                if (!(p instanceof View)) { target = null; break; }
                target = (View) p;
            }
            if (target != null && target.requestFocus()) return;
        }
        View up = mRoot.focusSearch(View.FOCUS_BACKWARD);
        if (up != null && !isInside(mRoot, up)) up.requestFocus();
    }

    private static View findByText(View v, String text, int depth) {
        if (v == null || depth > 14 || !v.isShown()) return null;
        if (v instanceof TextView) {
            CharSequence c = ((TextView) v).getText();
            if (c != null && text.contentEquals(c)) return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findByText(g.getChildAt(i), text, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static boolean isInside(View root, View v) {
        View cur = v;
        while (cur != null) {
            if (cur == root) return true;
            if (!(cur.getParent() instanceof View)) break;
            cur = (View) cur.getParent();
        }
        return false;
    }

    // ---------- 播报 ----------


    /** 收集引擎的中文音色（装了引擎后可用它切男声/女声） */

    private void toggleRead(int index) {
        if (mReading) { stopReading(); return; }
        mReading = true;
        playItem(index);
    }

    /** 暂停当前全文朗读（保留位置，再按确定可继续） */
    private void pauseReading() {
        if (!mReading || mPaused) return;
        mPaused = true;
        try { if (mPlayer != null && mPlayer.isPlaying()) mPlayer.pause(); } catch (Throwable ignored) { }
        status("<< 已暂停 >>（再按确定继续）");
    }

    /** 从暂停处继续朗读全文 */
    private void resumeReading() {
        if (!mReading || !mPaused) return;
        mPaused = false;
        try {
            if (mPlayer != null && mPlayingFile != null) {
                mPlayer.start();
                status("♪ 继续播放");
                return;
            }
        } catch (Throwable ignored) { }
        status("♪ 继续播放");
        speakChunk(mReadIndex);
    }

    /** 详情卡按确定：停止轮播，朗读右栏显示的详细内容；正在读这条全文时按确定=暂停/继续 */
    private void readDetail() {
        int idx = readBase();
        if (idx < 0 || idx >= mFlat.size()) return;
        boolean sameItem = mReading && !mChain && mReadIndex == idx;
        if (sameItem) {   // 正在读这条全文：按确定 = 暂停/继续切换
            if (mPaused) resumeReading(); else pauseReading();
            return;
        }
        stopReading();
        Item it = mFlat.get(idx);
        String head = speakTitle(it.title);
        String body = (mDocText != null && mDocText.length() > 0)
                ? mDocText : (mHeadDesc == null ? "" : mHeadDesc.getText().toString().trim());
        String text;
        if (body.length() == 0) {
            text = head;
        } else if (body.startsWith(head)) {
            text = body;
        } else {
            text = head + "。" + body;
        }
        mReading = true;
        mReadIndex = idx;
        mChain = false;
        status("♪ 正在朗读全文");
        speak(text, idx);
    }

    private void readFullAt(int index) {
        if (index < 0 || index >= mFlat.size()) return;
        if (mReading) { stopReading(); return; }
        Item it = mFlat.get(index);
        String body = trimArticleTail(it.desc == null ? "" : it.desc);
        mReading = true;
        mReadIndex = index;
        mChain = false;
        status("♪ 正在朗读全文");
        speak(it.title + "。" + body, index);
    }

    private String currentText(Item it) {
        if (it == null) return "";
        String body = trimArticleTail(it.desc == null ? "" : it.desc);
        String full = (it.link == null) ? null : mFullCache.get(it.link);
        if (full != null && full.length() > body.length()) body = trimArticleTail(full);
        return body.length() == 0 ? speakTitle(it.title) : (speakTitle(it.title) + "。" + body);
    }

    /**
     * 摘要类文本（RSS/接口给的 desc）也常带一串别的标题：
     * 先按「（完）/责任编辑/更多精彩内容…」截断，再剔掉末尾连续 2 条以上的标题行。
     */
    private static String trimArticleTail(String text) {
        if (text == null) return "";
        String t = text;
        int cut = tailCutIndex(t);
        if (cut > 0) t = t.substring(0, cut);
        String[] lines = t.split("\n");
        int end = lines.length;
        while (end > 1 && isTitleLike(lines[end - 1].trim())) end--;
        if (lines.length - end >= 2) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < end; i++) {
                if (i > 0) sb.append('\n');
                sb.append(lines[i]);
            }
            t = sb.toString();
        }
        return t.trim();
    }

    /** 朗读时去掉“视频丨/图集丨/直播丨”这类标签前缀 */
    private static String speakTitle(String t) {
        if (t == null) return "";
        return t.replaceFirst("^(视频|图集|直播|高清图|动图)\\s*[丨|｜:：]\\s*", "");
    }

    private void playItem(int index) {
        if (index < 0 || index >= mFlat.size()) { stopReading(); return; }
        mPaused = false;
        mReadIndex = index;
        Item it = mFlat.get(index);
        if (index < mOffset || index >= mOffset + ROWS) {
            mOffset = Math.max(0, Math.min(index, Math.max(0, mFlat.size() - ROWS)));
            refreshRows();
        }
        mFocus = index;
        int slot = index - mOffset;
        // 设置面板开着的时候别抢焦点，否则面板里的选中框会"消失"
        boolean panelOpen = mVoicePanel != null && mVoicePanel.getVisibility() == View.VISIBLE;
        if (!panelOpen && slot >= 0 && slot < ROWS && mRows[slot] != null) {
            mRows[slot].requestFocus();
        }
        updateSide();
        refreshRows();
        mChain = true;
        status("♪ 正在播报 " + (index + 1) + "/" + mFlat.size());
        speak(speakTitle(it.title), index);
        // 本条刚开始就把后面几条的标题都合成好，轮播切条不空档
        for (int k = 1; k <= 3 && index + k < mFlat.size(); k++) {
            prefetchChunk(index + k, 0, speakTitle(mFlat.get(index + k).title));
        }
    }

    private void speak(final String text, final int index) {
        mChunks = splitChunks(text);
        mChunkIdx = 0;
        // 不清预取：轮播时上一条最后一块播完时，已经顺手把这条的标题合成好了
        speakChunk(index);
    }

    /** 把长文本按标点切成 <=80 字的小段（百度在线合成对长度敏感） */
    private java.util.List<String> splitChunks(String text) {
        java.util.List<String> out = new ArrayList<String>();
        if (text == null) return out;
        String t = text.replaceAll("\\s+", " ").trim();
        int start = 0;
        while (start < t.length()) {
            int end = Math.min(start + 80, t.length());
            if (end < t.length()) {
                int cut = -1;
                for (int k = end; k > start + 20; k--) {
                    char c = t.charAt(k - 1);
                    if (c == '。' || c == '！' || c == '？' || c == '；' || c == '，' || c == '.') {
                        cut = k;
                        break;
                    }
                }
                if (cut > start) end = cut;
            }
            out.add(t.substring(start, end));
            start = end;
        }
        return out;
    }

    private void speakChunk(final int index) {
        if (!mReading) return;
        if (mChunkIdx >= mChunks.size()) {
            onSpeakFinished(index);
            return;
        }
        final int ci = mChunkIdx;
        final String part = mChunks.get(ci);
        final String kind = currentKind();
        // 预取命中：直接播放该块并预取下一块（消除块间合成等待）
        File ready = takePrefetched(index, ci);
        if (ready != null) {
            playFile(ready, index);
            prefetchNext(index, ci + 1);
            return;
        }
        // 未预取（首块/跳变）：后台合成，播完再接下一块
        new Thread(new Runnable() {
            @Override public void run() {
                final File f = synthChunk(kind, part);
                mMain.post(new Runnable() {
                    @Override public void run() {
                        if (!mReading || mChunkIdx != ci || index != mReadIndex) return;
                        if (f == null) { Log.w("tts synth fail"); stopReading(); return; }
                        playFile(f, index);
                        prefetchNext(index, ci + 1);
                    }
                });
            }
        }, "tts-dl").start();
    }

    /** 合成一块：edge 走 OpenWrt 桥、失败回退百度；baidu 直连。返回 mp3 文件或 null */
    private File synthChunk(String kind, String part) {
        if ("edge".equals(kind)) {
            File f = edgeSpeak(part, currentParam());
            if (f != null) return f;
            Log.w("edge fail, fallback baidu");
        }
        return download(baiduUrl(part), "tts_b" + mTtsSeq.incrementAndGet() + ".mp3");
    }

    /**
     * 预取：本条的下一块；本条最后一块时，顺手预取**下一条的标题**，
     * 轮播切条时就不用再等一次合成（之前切条有 1 秒左右的空档）
     */
    private void prefetchNext(final int index, final int ci) {
        if (ci < mChunks.size()) {
            prefetchChunk(index, ci, mChunks.get(ci));
        } else if (mChain && index + 1 < mFlat.size()) {
            prefetchChunk(index + 1, 0, speakTitle(mFlat.get(index + 1).title));
        }
    }

    private static String prefKey(int item, int ci) {
        return item + ":" + ci;
    }

    /** 后台预取 (item, ci) 这一块；同一块不重复合成，结果按代次丢弃 */
    private void prefetchChunk(final int item, final int ci, final String part) {
        final String key = prefKey(item, ci);
        final String kind = currentKind();
        final int gen;
        synchronized (mPrefLock) {
            if (mPrefMap.containsKey(key) || mPrefBusy.contains(key)) return;
            mPrefBusy.add(key);
            gen = mPrefGen;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                final File f = synthChunk(kind, part);
                synchronized (mPrefLock) {
                    mPrefBusy.remove(key);
                    if (gen == mPrefGen && f != null) {
                        while (mPrefMap.size() >= 12) {          // 只留最近几块
                            String old = mPrefMap.keySet().iterator().next();
                            File of = mPrefMap.remove(old);
                            try { if (of != null) of.delete(); } catch (Throwable ignored) { }
                        }
                        mPrefMap.put(key, f);
                    }
                }
            }
        }, "tts-prefetch").start();
    }

    /** 取走已预取的 (item, ci) 块；没有则返回 null */
    private File takePrefetched(int item, int ci) {
        synchronized (mPrefLock) {
            return mPrefMap.remove(prefKey(item, ci));
        }
    }

    private void clearPrefetch() {
        synchronized (mPrefLock) {
            mPrefGen++;
            for (File f : mPrefMap.values()) {
                try { if (f != null) f.delete(); } catch (Throwable ignored) { }
            }
            mPrefMap.clear();
            mPrefBusy.clear();
        }
    }

    /** edge-tts 桥接服务根地址：局域网内 24h 常开的 OpenWrt。
     *  部署到你自己的网络时，把下面地址改成 OpenWrt 的实际 IP（端口默认 8123）。 */
    private static final String TTS_BRIDGE_URL = "http://192.168.1.10:8123";

    /** 微软男/女声：经 OpenWrt 常开桥服务（edge-tts）合成，返回 mp3 文件或 null。
     *  说明：Android 5 的 TLS 指纹被微软 Edge 服务拉黑（403），电视无法自己直连微软，
     *  故改调局域网内 24h 常开的 OpenWrt 上的 edge-tts 桥接服务。
     *  语速走 edge 的 rate 参数（"+20%" / "-20%"），不传就等于默认 1.0。 */
    private File edgeSpeak(String text, String voice) {
        if (text == null || text.isEmpty()) return null;
        try {
            String url = TTS_BRIDGE_URL + "/tts?voice="
                    + java.net.URLEncoder.encode(voice, "UTF-8")
                    + "&rate=" + java.net.URLEncoder.encode(edgeRate(), "UTF-8")
                    + "&text=" + java.net.URLEncoder.encode(text, "UTF-8");
            return download(url, "tts_e" + mTtsSeq.incrementAndGet() + ".mp3");
        } catch (Throwable t) {
            Log.w("edge http fail " + t);
            return null;
        }
    }

    /** x0.8 → -20%，x1.0 → +0%，x1.2 → +20%，x1.5 → +50% */
    private String edgeRate() {
        switch (mSpeedIdx) {
            case 0: return "-20%";
            case 2: return "+20%";
            case 3: return "+50%";
            default: return "+0%";
        }
    }

/** 生成 edge-tts date_to_string() 同款时间戳（UTC，固定 GMT+0000 字样）。 */
    private static String dateToString() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date());
    }

    private static String sha256Hex(String s) throws Exception {
        byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes("ASCII"));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }

    private static void wsSend(java.io.OutputStream os, int opcode, byte[] payload) throws java.io.IOException {
        byte[] mask = new byte[4];
        new java.util.Random().nextBytes(mask);
        int n = payload.length;
        java.io.ByteArrayOutputStream h = new java.io.ByteArrayOutputStream();
        h.write(0x80 | opcode);
        if (n < 126) h.write(0x80 | n);
        else if (n < 65536) { h.write(0x80 | 126); h.write((n >> 8) & 0xFF); h.write(n & 0xFF); }
        else { h.write(0x80 | 127); for (int i = 7; i >= 0; i--) h.write((n >> (8 * i)) & 0xFF); }
        h.write(mask);
        byte[] body = new byte[n];
        for (int i = 0; i < n; i++) body[i] = (byte) (payload[i] ^ mask[i % 4]);
        os.write(h.toByteArray());
        os.write(body);
        os.flush();
    }

    /** 读一个 WebSocket 帧（服务端未掩码），返回 opcode 并写入 payload；-1=断开 */
    private static int readFrame(java.io.InputStream is, java.io.OutputStream payloadOut) throws java.io.IOException {
        int b0 = is.read();
        if (b0 < 0) return -1;
        int b1 = is.read();
        if (b1 < 0) return -1;
        int opcode = b0 & 0x0F;
        int ln = b1 & 0x7F;
        if (ln == 126) {
            ln = (is.read() << 8) | is.read();
        } else if (ln == 127) {
            long l = 0;
            for (int i = 0; i < 8; i++) l = (l << 8) | is.read();
            ln = (int) l;
        }
        byte[] p = new byte[ln];
        int off = 0;
        while (off < ln) {
            int r = is.read(p, off, ln - off);
            if (r < 0) return -1;
            off += r;
        }
        payloadOut.write(p, 0, ln);
        return opcode;
    }

    private static int indexOfBytes(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    /** 一段播完后：接着下一段，全部播完才进下一条 */
    private void onChunkDone(final int index) {
        mMain.post(new Runnable() {
            @Override public void run() {
                if (!mReading) return;
                mChunkIdx++;
                if (mChunkIdx < mChunks.size()) {
                    speakChunk(index);
                } else {
                    onSpeakFinished(index);
                }
            }
        });
    }

    private void onSpeakFinished(int index) {
        if (!mReading) return;
        if (!mChain) { stopReading(); return; }
        int next = index + 1;
        if (next < mFlat.size()) playItem(next);
        else stopReading();
    }

    private void playFile(final File f, final int index) {
        releasePlayer();
        try {
            mPlayingFile = f;
            mPlayer = new android.media.MediaPlayer();
            mPlayer.setDataSource(f.getAbsolutePath());
            final long t0 = System.currentTimeMillis();
            mPlayer.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(android.media.MediaPlayer mp) {
                    Log.w("done " + f.getName() + " after " + (System.currentTimeMillis() - t0) + "ms");
                    if (mPlayingFile == f) mPlayingFile = null;
                    try { f.delete(); } catch (Throwable ignored) { }   // 这段播完就删
                    onChunkDone(index);
                }
            });
            mPlayer.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener() {
                @Override public boolean onError(android.media.MediaPlayer mp, int w, int x) {
                    Log.w("play error " + w + "/" + x);
                    stopReading();
                    return true;
                }
            });
            mPlayer.prepare();
            Log.w("play " + f.getName() + " dur=" + mPlayer.getDuration() + "ms");
            mPlayer.start();
        } catch (Throwable t) {
            Log.w("play fail " + t);
            stopReading();
        }
    }

    private File download(String url, String name) {
        HttpURLConnection c = null;
        FileOutputStream fos = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            applyTrust(c);
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 5.0.1) Chrome/60.0");
            InputStream is = c.getInputStream();
            File out = new File(mCtx.getCacheDir(), name);
            fos = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            fos = null;
            is.close();
            if (name.startsWith("tts_")) trimTtsCache();   // 边下边控总量
            return out;
        } catch (Throwable t) {
            Log.w("dl fail " + t);
            return null;
        } finally {
            try { if (fos != null) fos.close(); } catch (Throwable ignored) { }
            if (c != null) c.disconnect();
        }
    }

    /** 百度在线合成对 text 长度敏感，超长会 404，这里截到 90 字 */
    private static String clipForOnline(String text) {
        if (text == null) return "";
        String t = text.replaceAll("\\s+", " ").trim();
        if (t.length() > 90) t = t.substring(0, 90);
        return t;
    }

    private String baiduUrl(String text) {
        try {
            int spd = mSpeedIdx == 0 ? 3 : (mSpeedIdx == 1 ? 5 : (mSpeedIdx == 2 ? 7 : 9));
            return "https://fanyi.baidu.com/gettts?lan=zh&source=web&spd=" + spd + "&text="
                    + java.net.URLEncoder.encode(text, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private String googleUrl(String text) {
        try {
            return "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=zh-CN&q="
                    + java.net.URLEncoder.encode(text, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private void stopReading() {
        mReading = false;
        mPaused = false;
        mReadIndex = -1;
        clearPrefetch();
        releasePlayer();
        mPlayingFile = null;
        cleanTtsFiles();
        status("");
        refreshRows();
    }

    /** 每段一个文件，播完/停下就删，避免缓存目录越积越大 */
    private void cleanTtsFiles() {
        try {
            File dir = mCtx.getCacheDir();
            File[] fs = dir.listFiles();
            if (fs == null) return;
            for (File f : fs) {
                if (isTtsFile(f)) f.delete();
            }
        } catch (Throwable ignored) { }
    }

    private static boolean isTtsFile(File f) {
        String n = f.getName();
        return n.endsWith(".mp3")
                && (n.startsWith("tts_") || n.equals("tts.mp3") || n.equals("tts_edge.mp3"));
    }

    /**
     * 滚动清理：目录里 TTS 文件超过上限（默认 8MB）就从最旧的开始删，
     * 正在播放的、刚预取还没播的都跳过；超过 24 小时的也一并清掉。
     * 电视只有 8G，不能指望"播完就删"永远不出岔子（崩溃/拔电都会留残file）。
     */
    private void trimTtsCache() {
        try {
            File dir = mCtx.getCacheDir();
            File[] fs = dir.listFiles();
            if (fs == null) return;
            long now = System.currentTimeMillis();
            long total = 0;
            java.util.List<File> list = new java.util.ArrayList<File>();
            for (File f : fs) {
                if (!f.isFile() || !isTtsFile(f)) continue;
                if (now - f.lastModified() > 24L * 3600 * 1000) {
                    f.delete();
                    continue;
                }
                list.add(f);
                total += f.length();
            }
            if (total <= TTS_CACHE_MAX) return;
            final java.util.HashSet<File> keep = new java.util.HashSet<File>();
            if (mPlayingFile != null) keep.add(mPlayingFile);
            synchronized (mPrefLock) { keep.addAll(mPrefMap.values()); }
            java.util.Collections.sort(list, new java.util.Comparator<File>() {
                @Override public int compare(File a, File b) {
                    return Long.compare(a.lastModified(), b.lastModified());
                }
            });
            for (File f : list) {
                if (total <= TTS_CACHE_MAX / 2) break;
                if (keep.contains(f)) continue;
                long n = f.length();
                if (f.delete()) total -= n;
            }
            Log.w("tts cache trimmed to " + total + "B");
        } catch (Throwable ignored) { }
    }

    private void releasePlayer() {
        try {
            if (mPlayer != null) {
                mPlayer.reset();
                mPlayer.release();
            }
        } catch (Throwable ignored) { }
        mPlayer = null;
    }

    static class Log {
        static void w(String m) {
            android.util.Log.w("TVDeskNews", m);
        }
    }

    @Override public boolean onFocusRequested(int direction) {
        TextView chip = (mTab >= 0 && mTab < mChips.length) ? mChips[mTab] : null;
        if (direction == 18 && chip != null && chip.isFocused()) return false;
        if (chip != null) {
            chip.requestFocus();
            return true;
        }
        return false;
    }

    @Override public void onFragmentSeletedPre(boolean selected) { }

    @Override public void onFragmentShowChanged(boolean shown) {
        if (!shown) {
            stopReading();
            return;
        }
        if (!mFlat.isEmpty()) {
            mOffset = 0;
            mFocus = 0;
            refreshRows();
            updateSide();
        }
    }

    @Override public boolean onHomeKeyEventHandled() { return false; }

    @Override public void setHoverListener(View.OnHoverListener listener) { }

    @Override public void onDestroyView() {
        stopReading();
        try { if (mWeb != null) { mWeb.destroy(); mWeb = null; } } catch (Throwable ignored) { }
        mPool.shutdownNow();
        super.onDestroyView();
    }

    int dp(int v) { return v * mDensity; }
}
