package com.dsh.tvdesk.douban;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.stv.launcher.sdk.fragment.BaseFragment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 「豆瓣荐片」页：热播电影 / 热播剧集 / 热播综艺 三栏海报墙。
 * 数据取自豆瓣 rexxar 接口；点海报通过广播让 TVBox 打开搜索，由 TVBox 接管播放。
 *
 * 说明：这里无法嵌入 TVBox 的界面（Android 5 没有这个能力），
 * 所以是照着它的豆瓣荐片页复刻，播放动作交给 TVBox。
 */
public class DoubanFragment extends BaseFragment {

    private static final String HOST_APP = "com.github.tvbox.osc.tk";
    private static final String ACTION_SEARCH = "android.content.movie.search.Action";
    private static final String TVBOX_HOME = "com.github.tvbox.osc.ui.activity.HomeActivity";

    private static final String[] TITLES = {"热播电影", "热播剧集", "热播综艺", "国产剧", "美剧", "韩剧", "日剧", "动画电影", "国漫", "日漫", "纪录片", "高分"};
    // 和 TVBox 豆瓣荐片同源：movie.douban.com/j/search_subjects
    // 一个分类可以用多个标签，用 | 分隔（前面的优先展示）
    // 类型写 coll 时，标签值当作豆瓣集合 ID（用于 Top250 这种榜单）
    private static final String[] TYPES = {"movie", "tv", "tv", "tv", "tv", "tv", "tv", "movie", "movie", "tv", "movie", "coll"};
    private static final String[] TAGS = {"热门", "热门", "综艺", "国产剧", "美剧", "韩剧", "日剧", "动画", "国漫", "日本动画", "纪录片", "movie_top250"};

    // 这套接口的封面是 3:4（540x719），格子按竖版排，7 列裁切最少
    private static final int COLS = 7;
    private static final int ROWS = 2;
    private static final int SLOTS = COLS * ROWS;

    private int mDensity = 2;
    private Context mCtx;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mPool = Executors.newFixedThreadPool(4);
    /** 详情接口补出来的海报地址，按 id 缓存 */
    private final java.util.HashMap<String, String> mPicCache = new java.util.HashMap<String, String>();
    private final LruCache<String, Bitmap> mCache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private final List<List<Item>> mData = new ArrayList<>();
    private View mRoot;
    private int mTab = 0;
    private final List<Item> mFlat = new ArrayList<>();
    private int mOffset = 0;
    private int mFocusIndex = 0;

    private TextView[] mChips = new TextView[TITLES.length];
    private TextView mStatus;
    private View[] mSlots = new View[SLOTS];
    private ImageView[] mImgs = new ImageView[SLOTS];
    private TextView[] mNames = new TextView[SLOTS];
    private TextView[] mRates = new TextView[SLOTS];

    static class Item {
        String id;
        String title;
        String pic;
        String rate;
        String sub;
    }

    // ---------------- 数据 ----------------

    /** 与 TVBox「豆瓣荐片」同源：movie.douban.com/j/search_subjects，条目都自带封面 */
    /** type/tag 里可以用 | 写多个，按顺序合并、按片名去重（前面的优先） */
    private List<Item> fetch(String type, String tag) {
        List<Item> out = new ArrayList<>();
        String[] types = type.split("\\|");
        String[] tags = tag.split("\\|");
        for (int i = 0; i < tags.length; i++) {
            String ty = types.length > i ? types[i] : types[0];
            List<Item> one = fetchOne(ty, tags[i]);
            for (Item it : one) {
                boolean dup = false;
                for (Item have : out) {
                    if (have.title != null && have.title.equals(it.title)) { dup = true; break; }
                }
                if (!dup) out.add(it);
            }
        }
        return out;
    }

    private List<Item> fetchOne(String type, String tag) {
        // coll = 豆瓣集合接口（Top250 这类榜单只在这里）
        if ("coll".equals(type)) return fetchCollection(tag);
        // 普通分类也分页拉，最多 3 页 x 100 条
        List<Item> out = new ArrayList<>();
        for (int start = 0; start < 300; start += 100) {
            List<Item> page = fetchSearchPage(type, tag, start, 100);
            if (page.isEmpty()) break;
            for (Item it : page) {
                boolean dup = false;
                for (Item have : out) {
                    if (have.title != null && have.title.equals(it.title)) { dup = true; break; }
                }
                if (!dup) out.add(it);
            }
            if (page.size() < 100) break;
        }
        return out;
    }

    private List<Item> fetchSearchPage(String type, String tag, int start, int limit) {
        List<Item> out = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            String url = "https://movie.douban.com/j/search_subjects?type=" + type
                    + "&tag=" + java.net.URLEncoder.encode(tag, "UTF-8")
                    + "&sort=recommend&page_limit=" + limit + "&page_start=" + start;
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Referer", "https://movie.douban.com/");
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0");
            InputStream is = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            JSONObject root = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            JSONArray arr = root.optJSONArray("subjects");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Item it = new Item();
                it.id = o.optString("id");
                it.title = o.optString("title");
                String cover = o.optString("cover");
                // 列表给的是 s_ratio（270x359 太模糊），换成 m_ratio（540x719）
                if (cover != null && cover.length() > 0) {
                    it.pic = cover.replace("s_ratio_poster", "m_ratio_poster");
                }
                String rate = o.optString("rate");
                it.rate = (rate != null && rate.length() > 0 && !"0".equals(rate)) ? rate : "";
                it.sub = "";
                if (it.title != null && it.title.length() > 0) out.add(it);
            }
        } catch (Throwable t) {
            Log.w("fetch " + type + "/" + tag + " fail " + t);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return out;
    }

    /** 豆瓣集合接口：movie_top250 等榜单，按官方排序返回；分页拉取保证拿全 */
    private List<Item> fetchCollection(String cid) {
        List<Item> out = new ArrayList<>();
        try {
            for (int start = 0; start < 300; start += 100) {
                List<Item> page = fetchCollectionPage(cid, start, 100);
                if (page.isEmpty()) break;
                for (Item it : page) {
                    boolean dup = false;
                    for (Item have : out) {
                        if (have.title != null && have.title.equals(it.title)) { dup = true; break; }
                    }
                    if (!dup) out.add(it);
                }
                if (page.size() < 100) break;
            }
        } catch (Throwable t) {
            Log.w("collection " + cid + " fail " + t);
        }
        return out;
    }

    private List<Item> fetchCollectionPage(String cid, int start, int count) {
        List<Item> out = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            String url = "https://m.douban.com/rexxar/api/v2/subject_collection/" + cid
                    + "/items?start=" + start + "&count=" + count + "&items_only=1&for_mobile=1";
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Referer", "https://m.douban.com/");
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 5.0.1; LeTV) AppleWebKit/537.36 Chrome/60.0");
            InputStream is = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            JSONObject root = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            JSONArray arr = root.optJSONArray("subject_collection_items");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Item it = new Item();
                it.id = o.optString("id");
                it.title = o.optString("title");
                JSONObject pic = o.optJSONObject("pic");
                it.pic = pic != null ? pic.optString("large") : null;
                JSONObject rate = o.optJSONObject("rating");
                double v = rate != null ? rate.optDouble("value", 0) : 0;
                it.rate = v > 0 ? String.format(java.util.Locale.US, "%.1f", v) : "";
                it.sub = o.optString("card_subtitle");
                if (it.title != null && it.title.length() > 0) out.add(it);
            }
        } catch (Throwable t) {
            Log.w("collection page " + cid + " start=" + start + " fail " + t);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return out;
    }

    static class Log {
        static void w(String m) {
            android.util.Log.w("TVDeskDouban", m);
        }
    }

    /** 每个分类上次拉取的时间戳；超过 TTL 就后台重新拉一次 */
    private final long[] mLoadedAt = new long[32];
    private static final long TTL_MS = 20 * 60 * 1000L;

    private void ensureLoaded(final int tab) {
        boolean hasCache = tab < mData.size() && !mData.get(tab).isEmpty();
        long age = System.currentTimeMillis() - mLoadedAt[tab];
        if (hasCache && age < TTL_MS) {
            mFlat.clear();
            mFlat.addAll(mData.get(tab));
            refreshSlots();
            return;
        }
        if (hasCache) {
            // 有旧数据先显示，后台刷新
            mFlat.clear();
            mFlat.addAll(mData.get(tab));
            refreshSlots();
            status("刷新中…");
        } else {
            status("加载中…");
        }
        mPool.execute(new Runnable() {
            @Override public void run() {
                final List<Item> items = fetch(TYPES[tab], TAGS[tab]);
                mMain.post(new Runnable() {
                    @Override public void run() {
                        while (mData.size() <= tab) mData.add(new ArrayList<Item>());
                        if (!items.isEmpty()) {
                            mData.set(tab, items);
                            mLoadedAt[tab] = System.currentTimeMillis();
                            status("");
                        } else if (mData.get(tab).isEmpty()) {
                            status("没取到数据（检查网络）");
                        }
                        if (mTab == tab) {
                            mFlat.clear();
                            mFlat.addAll(mData.get(tab));
                            mOffset = 0;
                            mFocusIndex = 0;
                            refreshSlots();
                        }
                    }
                });
            }
        });
    }

    private void status(final String s) {
        if (mStatus == null) return;
        mMain.post(new Runnable() {
            @Override public void run() {
                mStatus.setText(s);
                mStatus.setVisibility(s == null || s.length() == 0 ? View.GONE : View.VISIBLE);
            }
        });
    }

    // ---------------- UI ----------------

    @Override
    public View onInflaterContent(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Context ctx = container != null ? container.getContext() : getActivity();
        mCtx = ctx;
        if (ctx == null) return new TextView(getActivity());
        mDensity = Math.round(ctx.getResources().getDisplayMetrics().density);
        if (mDensity <= 0) mDensity = 2;

        FrameLayout root = new FrameLayout(ctx);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setPadding(dp(26), dp(100), dp(26), dp(10));
        root.setClipChildren(false);
        mRoot = root;

        // 返回键：第一次从海报回到分类，第二次从分类回到顶部 tab 条
        root.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_BACK) return false;
                TextView chip = (mTab >= 0 && mTab < mChips.length) ? mChips[mTab] : null;
                if (chip != null && chip.isFocused()) {
                    goToTabBar(root);
                    return true;
                }
                if (chip != null) {
                    chip.requestFocus();
                    return true;
                }
                return false;
            }
        });

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        col.setClipChildren(false);

        LinearLayout chips = new LinearLayout(ctx);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        // 比第一张海报左边缘再往右一点点（约 4dp）
        chips.setPadding(dp(6), 0, 0, 0);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(12);
        chips.setLayoutParams(clp);
        for (int i = 0; i < TITLES.length; i++) {
            final int idx = i;
            TextView chip = new TextView(ctx);
            chip.setText(TITLES[i]);
            chip.setTextSize(18.5f);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), dp(7), dp(14), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            chip.setLayoutParams(lp);
            chip.setFocusable(true);
            chip.setFocusableInTouchMode(false);
            chip.setClickable(true);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { onClickChip(idx); }
            });
            chip.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean hasFocus) {
                    v.setBackground(chipBg(idx, hasFocus));
                }
            });
            chip.setOnKeyListener(new View.OnKeyListener() {
                @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        // 格子还没准备好（开机时数据没回来）就顺便重试一次
                        if (!mFlat.isEmpty() && mSlots[0] != null
                                && mSlots[0].getVisibility() == View.VISIBLE) {
                            mSlots[0].requestFocus();
                        } else {
                            mData.clear();
                            ensureLoaded(mTab);
                        }
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        int next = idx + (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        if (next >= 0 && next < mChips.length) mChips[next].requestFocus();
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_BACK) {
                        // 在分类条上按返回 → 焦点交回顶部 tab 条
                        goToTabBar(mRoot != null ? mRoot : v.getRootView());
                        return true;
                    }
                    return false;
                }
            });
            mChips[i] = chip;
            chips.addView(chip);
        }
        // 分类多了放不下一行 → 用横向滚动容器，焦点移动时自动滚动
        android.widget.HorizontalScrollView hsv = new android.widget.HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hsv.setClipChildren(false);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = dp(12);
        hsv.setLayoutParams(hlp);
        chips.setLayoutParams(new android.widget.HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        hsv.addView(chips);
        col.addView(hsv);

        mStatus = new TextView(ctx);
        mStatus.setTextColor(0xFFFFD479);
        mStatus.setTextSize(12f);
        mStatus.setVisibility(View.GONE);
        col.addView(mStatus);

        col.addView(buildGrid(ctx));
        root.addView(col);

        switchTab(0);
        return root;
    }

    private View buildGrid(Context ctx) {
        LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        grid.setClipChildren(false);

        for (int r = 0; r < ROWS; r++) {
            LinearLayout line = new LinearLayout(ctx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            line.setClipChildren(false);
            for (int c = 0; c < COLS; c++) {
                final int slot = r * COLS + c;
                FrameLayout card = new FrameLayout(ctx);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                lp.setMargins(dp(5), dp(5), dp(5), dp(5));
                card.setLayoutParams(lp);
                card.setBackground(cardBg(false));
                card.setFocusable(true);
                card.setFocusableInTouchMode(false);
                card.setClickable(true);

                ImageView img = new ImageView(ctx);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                ilp.setMargins(dp(6), dp(6), dp(6), dp(6));   // 海报内缩，白框才有地方画
                img.setLayoutParams(ilp);
                card.addView(img);

                LinearLayout bottom = new LinearLayout(ctx);
                bottom.setOrientation(LinearLayout.VERTICAL);
                bottom.setGravity(Gravity.BOTTOM);
                bottom.setPadding(dp(8), 0, dp(8), dp(8));
                bottom.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                TextView rate = new TextView(ctx);
                rate.setTextColor(0xFFFFD479);
                rate.setTextSize(13f);
                rate.setShadowLayer(4f, 0, 1, 0xFF000000);

                TextView name = new TextView(ctx);
                name.setTextColor(Color.WHITE);
                name.setTextSize(13f);
                name.setMaxLines(1);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                name.setShadowLayer(4f, 0, 1, 0xFF000000);

                bottom.addView(rate);
                bottom.addView(name);
                card.addView(bottom);



                card.setOnKeyListener(gridKeys);
                card.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        int i = mOffset + slot;
                        if (i < mFlat.size()) sendToTvBox(mFlat.get(i).title);
                    }
                });
                card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                    @Override public void onFocusChange(View v, boolean hasFocus) {
                        v.setBackground(cardBg(hasFocus));
                        v.animate().scaleX(hasFocus ? 1.05f : 1f).scaleY(hasFocus ? 1.05f : 1f)
                                .setDuration(110).start();
                        if (hasFocus) {
                            int i = mOffset + slot;
                            if (i < mFlat.size()) mFocusIndex = i;
                        }
                    }
                });

                mSlots[slot] = card;
                mImgs[slot] = img;
                mNames[slot] = name;
                mRates[slot] = rate;
                line.addView(card);
            }
            grid.addView(line);
        }
        return grid;
    }

    private GradientDrawable cardBg(boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(7));
        if (focused) {
            // 只有选中才画白框；海报内缩 6dp，所以框在放大的海报外面
            d.setColor(0x00000000);
            d.setStroke(dp(5), 0xFFFFFFFF);
        } else {
            d.setColor(0x00000000);   // 未选中：完全透明，不能有任何边
        }
        return d;
    }

    /** 焦点框画在最上层（卡片背景会被海报盖住） */
    private GradientDrawable ringBg(boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x00000000);
        d.setCornerRadius(dp(9));
        if (focused) {
            d.setStroke(dp(6), 0xFFFFD34D);
            d.setColor(0x22FFD34D);
        }
        return d;
    }

    private GradientDrawable chipBg(int idx, boolean hasFocus) {
        GradientDrawable d = new GradientDrawable();
        boolean on = idx == mTab;
        d.setColor(hasFocus ? 0xFF2B6BF3 : (on ? 0x33FFFFFF : 0x1AFFFFFF));
        d.setCornerRadius(dp(16));
        if (hasFocus) d.setStroke(dp(2), 0xFFFFFFFF);
        return d;
    }

    /** 把焦点交回顶部 tab 条 */
    private void goToTabBar(View root) {
        // 先按名字精确找自己那个 tab（"豆瓣荐片"），最可靠
        View byText = findByText(root.getRootView(), "豆瓣荐片", 0);
        if (byText != null) {
            View target = byText;
            while (target != null && !target.isFocusable()) {
                android.view.ViewParent p = target.getParent();
                if (!(p instanceof View)) { target = null; break; }
                target = (View) p;
            }
            if (target != null && target.requestFocus()) {
                return;
            }
        }
        // 顺着视图树往上爬：tab 条一定在某个祖先的兄弟节点里
        View cur = root;
        for (int depth = 0; depth < 8 && cur != null; depth++) {
            android.view.ViewParent vp = cur.getParent();
            if (!(vp instanceof ViewGroup)) break;
            ViewGroup parent = (ViewGroup) vp;
            View best = null;
            for (int i = 0; i < parent.getChildCount(); i++) {
                View c = parent.getChildAt(i);
                if (c == cur || !c.isShown()) continue;
                if (c.isFocusable()) { best = c; break; }
                if (c instanceof ViewGroup && findFocusable((ViewGroup) c, 0) != null) {
                    best = findFocusable((ViewGroup) c, 0);
                    break;
                }
            }
            if (best != null) {
                flog("goToTabBar: depth=" + depth + " pick " + best.getClass().getName());
                if (best.requestFocus()) {
                    flog("goToTabBar: focused ok");
                    return;
                }
            }
            cur = parent;
        }
        flog("goToTabBar: 没找到 tab 条，改用上键派发");
        final android.app.Activity act = unwrapActivity(mCtx);
        if (act == null) return;
        mMain.post(new Runnable() {
            @Override public void run() {
                long now = android.os.SystemClock.uptimeMillis();
                KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 0);
                KeyEvent up = new KeyEvent(now, now + 30, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, 0);
                act.dispatchKeyEvent(down);
                act.dispatchKeyEvent(up);
            }
        });
    }

    /** 在视图树里找文本等于 text 的可聚焦控件（用来定位顶部 tab） */
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

    /** 在 vg 里找第一个可见且可聚焦的控件 */
    private static View findFocusable(ViewGroup vg, int depth) {
        if (depth > 4) return null;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View c = vg.getChildAt(i);
            if (!c.isShown()) continue;
            if (c.isFocusable()) return c;
            if (c instanceof ViewGroup) {
                View r = findFocusable((ViewGroup) c, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static android.app.Activity unwrapActivity(Context c) {
        while (c != null) {
            if (c instanceof android.app.Activity) return (android.app.Activity) c;
            if (!(c instanceof android.content.ContextWrapper)) return null;
            c = ((android.content.ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    /** v 是不是 root 的后代 */
    private static boolean isInside(View root, View v) {
        View cur = v;
        while (cur != null) {
            if (cur == root) return true;
            if (!(cur.getParent() instanceof View)) break;
            cur = (View) cur.getParent();
        }
        return false;
    }

    /** 点分类：换分类就切过去；再点当前分类 = 强制重新拉取 */
    private void onClickChip(int idx) {
        if (idx == mTab) {
            mLoadedAt[idx] = 0;          // 让缓存失效
            switchTab(idx);
            return;
        }
        switchTab(idx);
    }

    private void switchTab(int idx) {
        mTab = idx;
        for (int i = 0; i < mChips.length; i++) {
            if (mChips[i] == null) continue;
            // 关键：按真实焦点状态重绘，否则会把正在选中的这个分类的高亮刷掉
            mChips[i].setBackground(chipBg(i, mChips[i].isFocused()));
            mChips[i].setTextColor(i == idx ? Color.WHITE : 0x99FFFFFF);
        }
        mOffset = 0;
        mFocusIndex = 0;
        mFlat.clear();
        refreshSlots();
        // 切换后焦点留在刚选的这个分类上。
        // 但页面创建时（onInflaterContent 末尾的 switchTab(0)）视图还没 attach，
        // 此时 requestFocus() 会让一个屏幕外的分类抢走焦点，导致开机后 Tab 栏没有焦点框。
        // 只有已经 attach 到窗口（getWindowToken() != null）时才主动聚焦。
        if (idx >= 0 && idx < mChips.length && mChips[idx] != null
                && mChips[idx].getWindowToken() != null) {
            mChips[idx].requestFocus();
        }
        ensureLoaded(idx);
    }

    private void refreshSlots() {
        for (int s = 0; s < SLOTS; s++) {
            int i = mOffset + s;
            if (i < mFlat.size()) {
                Item it = mFlat.get(i);
                mNames[s].setText(it.title);
                mRates[s].setText(it.rate);
                mSlots[s].setVisibility(View.VISIBLE);
                loadImage(it, mImgs[s]);
            } else {
                mNames[s].setText("");
                mRates[s].setText("");
                mImgs[s].setImageDrawable(null);
                mSlots[s].setVisibility(View.INVISIBLE);
            }
        }
    }

    private void loadImage(final Item it, final ImageView iv) {
        if (it == null) {
            iv.setImageDrawable(null);
            return;
        }
        String url = it.pic;
        if (url != null && url.length() > 0) {
            Bitmap cached = mCache.get(url);
            if (cached != null) {
                iv.setImageBitmap(cached);
                return;
            }
            iv.setImageDrawable(null);
            iv.setTag(url);
            final String u = url;
            mPool.execute(new Runnable() {
                @Override public void run() {
                    final Bitmap bm = downloadBitmap(u);
                    if (bm == null) return;
                    mMain.post(new Runnable() {
                        @Override public void run() {
                            mCache.put(u, bm);
                            if (u.equals(iv.getTag())) iv.setImageBitmap(bm);
                        }
                    });
                }
            });
            return;
        }
        // 列表里没有图（比如「影院热映」集合），去详情接口补一张
        iv.setImageDrawable(null);
        iv.setTag("detail:" + it.id);
        mPool.execute(new Runnable() {
            @Override public void run() {
                final String picUrl = resolvePic(it);
                if (picUrl == null) return;
                final Bitmap bm = downloadBitmap(picUrl);
                if (bm == null) return;
                mMain.post(new Runnable() {
                    @Override public void run() {
                        mCache.put(picUrl, bm);
                        if (("detail:" + it.id).equals(iv.getTag())) iv.setImageBitmap(bm);
                    }
                });
            }
        });
    }

    /** 用 movie/{id} 详情接口拿到海报地址，结果写回 Item 并缓存 */
    private String resolvePic(Item it) {
        if (it.pic != null && it.pic.length() > 0) return it.pic;
        if (it.id == null || it.id.length() == 0) return null;
        synchronized (mPicCache) {
            String hit = mPicCache.get(it.id);
            if (hit != null) { it.pic = hit; return hit; }
        }
        HttpURLConnection conn = null;
        try {
            String url = "https://m.douban.com/rexxar/api/v2/movie/" + it.id;
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Referer", "https://m.douban.com/");
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 5.0.1; LeTV) AppleWebKit/537.36 Chrome/60.0");
            InputStream is = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            JSONObject o = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            JSONObject pic = o.optJSONObject("pic");
            String u = pic != null ? pic.optString("large") : null;
            if (u != null && u.length() > 0) {
                it.pic = u;
                synchronized (mPicCache) { mPicCache.put(it.id, u); }
                return u;
            }
        } catch (Throwable t) {
            Log.w("detail pic fail " + it.id + " " + t);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private Bitmap downloadBitmap(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Referer", "https://m.douban.com/");
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 5.0.1; LeTV) AppleWebKit/537.36 Chrome/60.0");
            InputStream is = conn.getInputStream();
            Bitmap bm = BitmapFactory.decodeStream(is);
            is.close();
            return bm;
        } catch (Throwable t) {
            Log.w("img fail " + url + " " + t);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 把片名交给 TVBox，由它搜索并接管播放。
     * 注意：TVBox 的搜索页在“冷启动”时必崩（它自己的 bug），
     * 所以先把它拉起来，等它进前台后再发广播。
     */
    /**
     * 一键直达：先用 root 的 am 命令把 TVBox 拉到前台，确认它真的到前台了，
     * 再用 am broadcast 让它按片名搜索（复刻手动验证成功的流程）。
     */
    private void sendToTvBox(final String title) {
        flog("=== click: " + title);
        // 不用 root：直接通过包启动方式唤起 TVBox，再发广播让它按片名搜索
        try {
            Intent home = mCtx.getPackageManager().getLaunchIntentForPackage(HOST_APP);
            if (home != null) {
                home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mCtx.startActivity(home);
                flog("launch tvbox ok");
            } else {
                flog("tvbox not installed");
                return;
            }
        } catch (Throwable t) {
            flog("launch fail " + t);
        }
        mMain.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    Intent i = new Intent(ACTION_SEARCH);
                    i.setComponent(new android.content.ComponentName(HOST_APP,
                            "com.github.tvbox.osc.receiver.SearchReceiver"));
                    i.putExtra("title", title);
                    mCtx.sendBroadcast(i);
                    flog("broadcast sent: " + title);
                } catch (Throwable t) {
                    flog("broadcast fail " + t);
                }
            }
        }, 1600);
    }
    /** 执行一条 shell（root），返回输出 */
    private String sh(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            p.waitFor();
            return sb.toString();
        } catch (Throwable t) {
            return "EXEC_ERR " + t;
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** 写 /sdcard/tvdesk_douban.log，方便排查 */
    private void flog(String msg) {
        android.util.Log.w("TVDeskDouban", msg);
        try {
            java.io.File f = new java.io.File(
                    android.os.Environment.getExternalStorageDirectory(), "tvdesk_douban.log");
            if (f.length() > 256 * 1024) f.delete();
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            w.write(new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                    .format(new java.util.Date()) + " " + msg + "\n");
            w.close();
        } catch (Throwable ignored) { }
    }

    private final View.OnKeyListener gridKeys = new View.OnKeyListener() {
        @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int fi = mFocusIndex;
            int size = mFlat.size();
            if (size == 0) return false;
            if (fi < 0 || fi >= size) fi = 0;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (fi % COLS != COLS - 1 && fi + 1 < size) fi++;
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (fi % COLS == 0) return true;
                    fi--;
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    // 先选本页下一行；已经在最后一行就整体上翻一行
                    if (fi + COLS >= size) return true;
                    fi += COLS;
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (fi < COLS) {
                        if (mChips[mTab] != null) mChips[mTab].requestFocus();
                        return true;
                    }
                    fi -= COLS;
                    break;                case KeyEvent.KEYCODE_BACK:
                    if (mChips[mTab] != null) mChips[mTab].requestFocus();
                    return true;
                default:
                    return false;
            }
            int oldOffset = mOffset;
            while (fi < mOffset) mOffset -= COLS;
            while (fi >= mOffset + SLOTS) mOffset += COLS;
            if (mOffset < 0) mOffset = 0;
            mFocusIndex = fi;
            int slot = fi - mOffset;
            if (slot < 0 || slot >= SLOTS) slot = fi % COLS;
            if (mOffset != oldOffset) refreshSlots();
            if (mSlots[slot] != null) mSlots[slot].requestFocus();
            return true;
        }
    };

    // ---------------- BaseFragment ----------------

    @Override public boolean onFocusRequested(int direction) {
        // 从 tab 条按下时，先进分类条（热播电影/剧集/综艺），
        // 再从分类条按下才进海报墙——和 TVBox 的操作顺序一致
        TextView chip = (mTab >= 0 && mTab < mChips.length) ? mChips[mTab] : null;
        if (chip == null) chip = mChips[0];
        // 18 = FOCUS_TOP_IN：焦点已经在本页最上面（分类条）时不要再抢，
        // 否则桌面永远没法把焦点交给顶部 tab 条
        if (direction == 18 && chip != null && chip.isFocused()) {
            return false;
        }
        if (chip != null && chip.getVisibility() == View.VISIBLE) {
            chip.requestFocus();
            return true;
        }
        // 分类条还没建立（极端情况）才退而求其次落到海报
        for (int i = 0; i < SLOTS; i++) {
            if (mSlots[i] != null && mSlots[i].getVisibility() == View.VISIBLE) {
                mSlots[i].requestFocus();
                return true;
            }
        }
        return false;
    }

    @Override public void onFragmentSeletedPre(boolean selected) { }

    @Override public void onFragmentShowChanged(boolean shown) { }

    @Override public boolean onHomeKeyEventHandled() { return false; }

    @Override public void setHoverListener(View.OnHoverListener listener) { }

    @Override public void onDestroyView() {
        mPool.shutdownNow();
        mCache.evictAll();
        super.onDestroyView();
    }

    int dp(int v) { return v * mDensity; }
}