package com.dsh.tvdesk.stock;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.stv.launcher.sdk.fragment.BaseFragment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 股票 TAB：自选 / 指数 / 基金 / 国际
 * 数据：腾讯行情（股票、指数、国际指数、分时）+ 天天基金（净值）
 * 自选：/sdcard/tvdesk/自选.txt（一行一个代码，f: 前缀为基金）
 */
public class StockFragment extends BaseFragment {

    private static final String[] TITLES = {"油价", "黄金", "国内", "国际", "基金", "自选"};

    // 分类下标（顺序变了，统一用常量，避免后面再错）
    private static final int TAB_OIL = 0, TAB_GOLD = 1, TAB_INDEX = 2,
            TAB_WORLD = 3, TAB_FUND = 4, TAB_WATCH = 5;

    private static final String[] INDEX_CODES = {
            "sh000001",   // 上证指数（大盘）
            "sz399001",   // 深证成指
            "sh000300",   // 沪深300
            "sh000905",   // 中证500
            "sh000852",   // 中证1000
            "sh000016",   // 上证50
            "sz399006",   // 创业板指
            "sh000688"    // 科创50
    };

    /** 国际指数：恒生 / 国企 / 恒生科技 / 道指 / 纳指 / 标普 / 富时100 */
    private static final String[][] WORLD_REGIONS = {
            {"中国大陆", "sh000300",     "沪深300"},
            {"美国",    "usINX",        "标普500"},
            {"日本",    "int_nikkei",    "日经225"},
            {"韩国",    "znb_KOSPI",     "KOSPI"},
            {"德国",    "znb_DAX",       "DAX 40"},
            {"英国",    "int_ftse",      "富时100"},
            {"中国香港", "hkHSI",        "恒生指数"},
            {"中国台湾", "znb_TWSE",      "台湾加权"},
            {"印度",    "znb_NIFTY",     "Nifty 50"},
            {"澳洲",    "znb_AS51",      "ASX 200"}
    };

    /** 黄金分类：全部走东财（现货 / 期货 / COMEX），实时与历史都能取 */
    private static final String[] GOLD_SECID = {"118.AU9999", "113.au2612", "101.GC00Y", "101.SI00Y"};
    private static final String[] GOLD_NAME = {"黄金9999", "沪金2612", "COMEX黄金", "COMEX白银"};
    private static final String[] GOLD_UNIT = {"元/克", "元/克", "美元/盎司", "美元/盎司"};

    private static final String WATCH_FILE = "/sdcard/tvdesk/自选.txt";
    private static final int ROWS = 10;
    private static final int ACCENT = 0xFF2674EE;

    private Context mCtx;
    private View mRoot;
    private int mDp = 2;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mPool = Executors.newFixedThreadPool(2);
    /** 抓油价专用：31 个省分 4 路取，并发太高会被站点当爬虫（返回 404） */
    private final ExecutorService mOilPool = Executors.newFixedThreadPool(4);

    private TextView[] mChips = new TextView[TITLES.length];
    private TextView mTitle, mStatus;
    private final TextView[] mNames = new TextView[ROWS];
    private final TextView[] mCodes = new TextView[ROWS];
    private final TextView[] mPrices = new TextView[ROWS];
    private final TextView[] mPcts = new TextView[ROWS];
    private final TextView[] mC95 = new TextView[ROWS];
    private final TextView[] mC98 = new TextView[ROWS];
    private final TextView[] mC0 = new TextView[ROWS];
    private LinearLayout mOilHead, mStockHead;
    /** 右侧详情区：左区按左右键把焦点送进来，进来后按左右键回左区 */
    private LinearLayout mRightBox;
    private final View[] mRowsV = new View[ROWS];

    private TextView dName, dCode, dPrice, dChange, dPct, dMeta;
    private TextView mChartHint;
    private MinuteChart dChart;
    private HistoryChart mHistory;
    private final TextView[] dStatKey = new TextView[6];
    private final TextView[] dStatVal = new TextView[6];

    private final List<QuoteApi.Item> mItems = new ArrayList<QuoteApi.Item>();
    private final List<String> mWatch = new ArrayList<String>();
    private final List<String> mFunds = new ArrayList<String>();
    private int mTab = 0, mOffset = 0, mFocus = 0;
    /** 油价分类里当前选中的省份下标 */
    private int mOilSel = 0;
    /** 刷新后要回到的省份（默认吉林） */
    private String mLastOilName = "吉林";
    /** 正在抓全国油价：挡住切栏/切回时叠出来的第二轮请求 */
    private boolean mOilLoading = false;
    /** 抓取代次：上一轮的慢响应回来了也直接丢掉，不会盖掉新数据 */
    private int mOilGen = 0;
    /** 31 省都有价格了（用来判断切回分类时要不要再抓） */
    private boolean mOilComplete = false;
    /** 这一份油价是什么时候抓的，状态栏显示用 */
    private long mOilAt = 0;
    private OilPrice.Oil mOil = null;
    /** 当前详情面板对应的代码，用于丢弃过期的分时回调 */
    private String mSelCode = null;

    // ================= 数据 =================

    /** 把自选写回文件（保留注释头） */
    private void saveWatch() {
        try {
            File f = new File(WATCH_FILE);
            File p = f.getParentFile();
            if (p != null) p.mkdirs();
            StringBuilder sb = new StringBuilder();
            sb.append("# 自选：一行一个代码\n");
            sb.append("# 股票 sh600519 / sz000001；基金 f:005827\n");
            for (String c : mWatch) sb.append(c).append("\n");
            for (String c : mFunds) sb.append("f:").append(c).append("\n");
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Throwable t) {
            Diag.log("watch", "save fail " + t.getMessage());
        }
    }

    /**
     * 智能识别代码：
     *   6 位数字        → A 股（6/5/9 → sh，0/3/1 → sz，8/4 → bj）
     *   5 位数字        → 港股（00700 → hk00700）
     *   4 位数字        → 日股（7203 → jp7203）
     *   1-5 个字母      → 美股（AAPL → usAAPL，支持 BRK.B）
     *   带前缀          → 原样（sh/sz/bj/hk/us/jp/kr/uk）
     */
    private String normalizeCode(String raw) {
        if (raw == null) return null;
        String c = raw.trim().toLowerCase(Locale.US);
        if (c.length() == 0) return null;
        if (c.startsWith("f:")) return "f:" + c.substring(2);
        if (c.matches("f\\d{6}")) return "f:" + c.substring(1);   // f005827

        String[] prefixes = {"sh", "sz", "bj", "hk", "us", "jp", "kr", "uk"};
        for (int i = 0; i < prefixes.length; i++) {
            String p = prefixes[i];
            if (c.startsWith(p) && c.length() > p.length()) return c;
        }
        if (c.matches("\\d{6}")) {
            char h = c.charAt(0);
            if (h == '6' || h == '5' || h == '9') return "sh" + c;
            if (h == '0' || h == '3' || h == '1') return "sz" + c;
            if (h == '8' || h == '4') return "bj" + c;
            return "sh" + c;
        }
        if (c.matches("\\d{5}")) return "hk" + c;
        if (c.matches("\\d{4}")) return "jp" + c;
        if (c.matches("[a-z]{1,5}(\\.[a-z]{1,2})?")) return "us" + c.toUpperCase(Locale.US);
        return null;
    }

    private void addWatch(String raw) {
        String code = normalizeCode(raw);
        if (code != null && code.startsWith("f:")) {
            status("基金请到「基金」分栏添加（按右键切到基金）");
            return;
        }
        if (code == null) {
            status("代码无效，请输入 6 位数字（如 600519）");
            return;
        }
        if (mWatch.contains(code)) {
            status(code + " 已经在自选里了");
            return;
        }
        mWatch.add(code);
        saveWatch();
        status("已添加 " + code);
        loadData();
    }

    /** 添加基金：6 位数字，重复则提示 */
    private void addFund(String raw) {
        if (raw == null) return;
        String c = raw.trim().toLowerCase(Locale.US);
        if (c.startsWith("f:")) c = c.substring(2).trim();
        else if (c.startsWith("f") && c.length() == 7) c = c.substring(1);
        if (!c.matches("\\d{6}")) {
            status("基金代码是 6 位数字，例如 005827");
            return;
        }
        if (mFunds.contains(c)) {
            status(c + " 已经在基金列表里了");
            return;
        }
        mFunds.add(c);
        saveWatch();
        status("已添加基金 " + c);
        loadData();
    }

    private void showAddFundDialog() {
        Context ctx = mCtx != null ? mCtx : getActivity();
        if (ctx == null) return;
        final EditText et = new EditText(ctx);
        et.setHint("输入 6 位基金代码，如 005827");
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setTextSize(24f);
        et.setSingleLine(true);
        et.setPadding(dp(30), dp(18), dp(30), dp(18));
        new AlertDialog.Builder(ctx)
                .setTitle("添加基金")
                .setView(et)
                .setPositiveButton("添加", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        addFund(et.getText().toString());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showDeleteFundDialog(final QuoteApi.Item it) {
        Context ctx = mCtx != null ? mCtx : getActivity();
        if (ctx == null) return;
        new AlertDialog.Builder(ctx)
                .setTitle("删除基金")
                .setMessage("确定把 " + it.name + "（" + it.code + "）从基金列表删掉？")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        mFunds.remove(it.code);
                        saveWatch();
                        status("已删除 " + it.name);
                        loadData();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showAddDialog() {
        Context ctx = mCtx != null ? mCtx : getActivity();
        if (ctx == null) return;
        final EditText et = new EditText(ctx);
        et.setHint("A股600519 · 港股00700 · 美股AAPL · 日股7203");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setTextSize(24f);
        et.setSingleLine(true);
        et.setPadding(dp(30), dp(18), dp(30), dp(18));
        new AlertDialog.Builder(ctx)
                .setTitle("添加自选股票")
                .setView(et)
                .setPositiveButton("添加", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        addWatch(et.getText().toString());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showDeleteDialog(final QuoteApi.Item it) {
        Context ctx = mCtx != null ? mCtx : getActivity();
        if (ctx == null) return;
        new AlertDialog.Builder(ctx)
                .setTitle("删除自选")
                .setMessage("确定把 " + it.name + "（" + it.code + "）从自选里删掉？")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        mWatch.remove(it.code);
                        saveWatch();
                        status("已删除 " + it.name);
                        loadData();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void loadWatchFile() {
        mWatch.clear();
        mFunds.clear();
        try {
            File f = new File(WATCH_FILE);
            if (!f.exists()) {
                File p = f.getParentFile();
                if (p != null) p.mkdirs();
                String def = "# 自选：一行一个代码\n"
                        + "# 股票 sh600519 / sz000001；基金 f:005827\n"
                        + "sh000001\nsz399001\nsh600519\nsz000858\nsh601318\n"
                        + "hkHSI\nusDJI\n"
                        + "f:005827\nf:003095\n";
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(def.getBytes("UTF-8"));
                fos.close();
            }
            byte[] data = new byte[(int) f.length()];
            FileInputStream fis = new FileInputStream(f);
            int off = 0;
            while (off < data.length) {
                int n = fis.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
            fis.close();
            for (String line : new String(data, "UTF-8").split("\\r?\\n")) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#")) continue;
                if (line.startsWith("f:")) {
                    String c = line.substring(2).trim();
                    if (c.length() == 6) mFunds.add(c);
                    continue;
                }
                if (line.length() == 6 && Character.isDigit(line.charAt(0))) {
                    line = (line.charAt(0) == '6' || line.charAt(0) == '5' ? "sh" : "sz") + line;
                }
                if (line.length() >= 7) mWatch.add(line.toLowerCase(Locale.US));
            }
        } catch (Throwable t) {
            android.util.Log.w("TVDeskStock", "watch fail " + t);
        }
        if (mWatch.isEmpty()) { mWatch.add("sh000001"); mWatch.add("sh600519"); }
    }

    private List<String> codesForTab(int tab) {
        List<String> l = new ArrayList<String>();
        if (tab == TAB_INDEX) { for (String c : INDEX_CODES) l.add(c); return l; }
        if (tab == TAB_WORLD) { for (String[] r : WORLD_REGIONS) l.add(r[1]); return l; }
        if (tab == TAB_GOLD) {
            for (String c : GOLD_SECID) l.add(c);
            return l;
        }
        if (tab == TAB_OIL) {
            for (String[] pv : OilPrice.PROVINCES) l.add(pv[0]);
            return l;
        }
        if (tab == TAB_FUND) {
            for (String c : mFunds) l.add("f:" + c);
            return l;
        }
        return new ArrayList<String>(mWatch);
    }

    private void loadData() {
        final int tab = mTab;
        // 油价：一次抓齐所有省份，左侧直接以表格展示
        if (tab == TAB_OIL) {
            // 刷新时列表已经建好，保留旧数据，避免整屏变空
            boolean inPlace = mItems.size() == OilPrice.PROVINCES.length
                    && mItems.get(0).kind == 2;
            if (inPlace) {
                loadAllOil(false);
                return;
            }
            List<QuoteApi.Item> list = new ArrayList<QuoteApi.Item>();
            for (String[] pv : OilPrice.PROVINCES) {
                QuoteApi.Item it = new QuoteApi.Item();
                it.name = pv[0];
                it.displayCode = "";
                it.code = "oil:" + pv[1];
                it.kind = 2;
                list.add(it);
            }
            mItems.clear();
            mItems.addAll(list);
            mOffset = 0;
            mFocus = 0;
            refreshRows();
            if (mOilHead != null) mOilHead.setVisibility(View.VISIBLE);
            if (OilCache.fresh()) {
                status("油价 " + hhmm(OilCache.newestAt()) + " 已取过 · 按「确定」刷新");
            } else {
                status("正在获取全国油价…");
            }
            loadAllOil(false);
            // 下次调价日期需要联网推算，放后台
            mOilPool.execute(new Runnable() {
                @Override public void run() {
                    OilPrice.computeNextAdjust();
                    mMain.post(new Runnable() {
                        @Override public void run() {
                            if (mTab == TAB_OIL && mOil != null) applyOil(mOil);
                        }
                    });
                }
            });
            if (!mItems.isEmpty()) {
                int def = oilIndex("吉林");
                mFocus = def;
                selectRow(def, false);
            }
            return;
        }
        if (mOilHead != null && tab != TAB_OIL) mOilHead.setVisibility(View.GONE);

        final List<String> codes = codesForTab(tab);
        status("刷新中…");
        mPool.execute(new Runnable() {
            @Override public void run() {
                List<QuoteApi.Item> result;
                if (tab == TAB_GOLD) {
                    result = new ArrayList<QuoteApi.Item>();
                    for (int i = 0; i < GOLD_SECID.length; i++) {
                        // COMEX 品种实时报价是 ×10，国内是 ×100
                        float scale = GOLD_SECID[i].startsWith("101.") ? 10f : 100f;
                        QuoteApi.Item g = QuoteApi.fetchEastQuote(GOLD_SECID[i],
                                "gold" + i, GOLD_NAME[i], scale);
                        g.kind = 1;
                        g.extra1 = GOLD_SECID[i];
                        g.extra4 = GOLD_UNIT[i];
                        result.add(g);
                    }
                } else if (tab == TAB_WORLD) {
                    // 腾讯代码（A股/港股/美股）走腾讯，其余走新浪
                    List<String> txCodes = new ArrayList<String>();
                    List<String> sinaCodes = new ArrayList<String>();
                    for (String c : codes) {
                        if (c.startsWith("int_") || c.startsWith("znb_")) sinaCodes.add(c);
                        else txCodes.add(c);
                    }
                    List<QuoteApi.Item> raw = new ArrayList<QuoteApi.Item>();
                    if (!txCodes.isEmpty()) raw.addAll(QuoteApi.fetchStocks(txCodes));
                    if (!sinaCodes.isEmpty()) raw.addAll(QuoteApi.fetchSina(sinaCodes));
                    // 名称换成「地区 / 大盘名」，并按地区表顺序排列
                    List<QuoteApi.Item> ordered = new ArrayList<QuoteApi.Item>();
                    for (String[] r : WORLD_REGIONS) {
                        for (QuoteApi.Item it : raw) {
                            if (r[1].equals(it.code)) {
                                it.name = r[0];
                                it.displayCode = r[2];
                                ordered.add(it);
                                break;
                            }
                        }
                    }
                    result = ordered;
                } else if (tab == TAB_FUND) {
                    List<QuoteApi.Item> raw = QuoteApi.fetchFunds(new ArrayList<String>(mFunds));
                    List<QuoteApi.Item> ordered = new ArrayList<QuoteApi.Item>();
                    for (String c : mFunds) {
                        for (QuoteApi.Item it : raw) {
                            if (c.equals(it.code)) { ordered.add(it); break; }
                        }
                    }
                    result = ordered;
                } else {
                    // 自选里可能混着基金（f: 前缀），要分开抓
                    List<String> stockCodes = new ArrayList<String>();
                    List<String> fundCodes = new ArrayList<String>();
                    for (String c : codes) {
                        if (c.startsWith("f:")) fundCodes.add(c.substring(2));
                        else stockCodes.add(c);
                    }
                    List<QuoteApi.Item> merged = new ArrayList<QuoteApi.Item>();
                    if (!stockCodes.isEmpty()) merged.addAll(QuoteApi.fetchStocks(stockCodes));
                    if (!fundCodes.isEmpty()) {
                        for (QuoteApi.Item f : QuoteApi.fetchFunds(fundCodes)) {
                            f.code = "f:" + f.code;      // 保留 f: 前缀，删除/回写才对得上
                            merged.add(f);
                        }
                    }
                    // 按自选文件里的顺序排列
                    List<QuoteApi.Item> ordered = new ArrayList<QuoteApi.Item>();
                    for (String c : codes) {
                        for (QuoteApi.Item it : merged) {
                            if (c.equals(it.code)) { ordered.add(it); break; }
                        }
                    }
                    result = ordered;
                }
                if (tab == TAB_FUND) {
                    QuoteApi.Item add = new QuoteApi.Item();
                    add.name = "＋ 添加基金";
                    add.displayCode = "输入 6 位基金代码";
                    add.code = "__addfund__";
                    add.kind = 4;
                    result.add(add);
                }
                if (tab == TAB_WATCH) {
                    QuoteApi.Item add = new QuoteApi.Item();
                    add.name = "＋ 添加股票";
                    add.displayCode = "A股/港股/美股/日股";
                    add.code = "__add__";
                    add.kind = 3;
                    result.add(add);
                }
                final List<QuoteApi.Item> got = result;
                mMain.post(new Runnable() {
                    @Override public void run() {
                        if (mTab != tab) return;
                        mItems.clear();
                        mItems.addAll(got);
                        mOffset = 0;
                        mFocus = 0;
                        refreshRows();
                        status(got.isEmpty() ? "没取到行情（检查网络）" : "数据：腾讯行情 · 仅供参考");
                        if (!mItems.isEmpty()) {
                            selectRow(0, true);   // 详情显示第一只，但不抢焦点
                        }
                    }
                });
            }
        });
    }

    // ================= 布局 =================

    @Override
    public View onInflaterContent(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Context ctx = container != null ? container.getContext() : getActivity();
        mCtx = ctx;
        if (ctx == null) return new TextView(getActivity());
        mDp = Math.round(ctx.getResources().getDisplayMetrics().density);
        if (mDp <= 0) mDp = 2;
        loadWatchFile();

        FrameLayout root = new FrameLayout(ctx);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setPadding(dp(26), dp(96), dp(26), dp(14));
        root.setClipChildren(false);
        mRoot = root;

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout chips = new LinearLayout(ctx);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(8);
        chips.setLayoutParams(clp);
        for (int i = 0; i < TITLES.length; i++) {
            final int idx = i;
            TextView chip = new TextView(ctx);
            chip.setText(TITLES[i]);
            chip.setTextSize(17f);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(15), dp(7), dp(15), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            chip.setLayoutParams(lp);
            chip.setFocusable(true);
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
                        if (mRowsV[0] != null) mRowsV[0].requestFocus();
                        return true;
                    }
                    if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_LEFT) {
                        int n = idx + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        if (n >= 0 && n < mChips.length) mChips[n].requestFocus();
                        return true;
                    }
                    if (code == KeyEvent.KEYCODE_BACK) { goToTabBar(); return true; }
                    return false;
                }
            });
            mChips[i] = chip;
            chips.addView(chip);
        }
        col.addView(chips);

        mStatus = new TextView(ctx);
        mStatus.setTextColor(0xFFD9E2F0);
        mStatus.setTextSize(12f);
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

        mTab = TAB_OIL;
        bindTab(TAB_OIL);
        // 等布局完成再要焦点，否则会落到右上角的桌面管理图标
        final View firstChip = mChips[0];
        if (firstChip != null) {
            root.post(new Runnable() {
                @Override public void run() { firstChip.requestFocus(); }
            });
        }
        return root;
    }

    private View buildLeft(Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1.1f);
        lp.rightMargin = dp(14);
        box.setLayoutParams(lp);
        box.setPadding(dp(12), dp(10), dp(12), dp(8));
        box.setBackground(panel());
        box.setClipChildren(false);

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = dp(8);
        head.setLayoutParams(hlp);

        mTitle = new TextView(ctx);
        mTitle.setText("我的自选");
        mTitle.setTextSize(17f);
        mTitle.setTextColor(Color.WHITE);
        mTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.addView(mTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(head);

        // 油价表头（只有油价分类显示）
        mOilHead = new LinearLayout(ctx);
        mOilHead.setOrientation(LinearLayout.HORIZONTAL);
        mOilHead.setVisibility(View.GONE);
        LinearLayout.LayoutParams ohlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ohlp.bottomMargin = dp(4);
        ohlp.leftMargin = dp(10);
        ohlp.rightMargin = dp(10);
        mOilHead.setLayoutParams(ohlp);
        String[] cols = {"省份", "92#", "95#", "98#", "0#柴油"};
        for (int c = 0; c < cols.length; c++) {
            TextView h = new TextView(ctx);
            h.setText(cols[c]);
            h.setTextSize(14f);
            h.setTextColor(0xFF9AA4B4);
            h.setGravity(c == 0 ? Gravity.START : Gravity.END);
            LinearLayout.LayoutParams hlp2 = (c == 0)
                    ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    : new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT);
            h.setLayoutParams(hlp2);
            mOilHead.addView(h);
        }
        box.addView(mOilHead);

        for (int i = 0; i < ROWS; i++) {
            final int slot = i;
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            row.setPadding(dp(10), dp(3), dp(10), dp(3));
            row.setFocusable(true);
            row.setClickable(true);
            row.setBackground(rowBg(false));

            LinearLayout line1 = new LinearLayout(ctx);
            line1.setOrientation(LinearLayout.HORIZONTAL);
            line1.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(line1);

            TextView name = new TextView(ctx);
            name.setTextSize(16f);
            name.setTextColor(0xFFE8ECF2);
            name.setSingleLine(true);
            line1.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView price = new TextView(ctx);
            price.setTextSize(17f);
            price.setTextColor(Color.WHITE);
            price.setGravity(Gravity.END);
            price.setMinWidth(dp(72));
            line1.addView(price);

            TextView pct = new TextView(ctx);
            pct.setTextSize(16f);
            pct.setGravity(Gravity.END);
            pct.setMinWidth(dp(76));
            line1.addView(pct);

            TextView c95 = new TextView(ctx);
            c95.setTextSize(15f);
            c95.setTextColor(0xFFE8ECF2);
            c95.setGravity(Gravity.END);
            c95.setMinWidth(dp(64));
            c95.setVisibility(View.GONE);
            line1.addView(c95);

            TextView c98 = new TextView(ctx);
            c98.setTextSize(15f);
            c98.setTextColor(0xFFE8ECF2);
            c98.setGravity(Gravity.END);
            c98.setMinWidth(dp(64));
            c98.setVisibility(View.GONE);
            line1.addView(c98);

            TextView c0 = new TextView(ctx);
            c0.setTextSize(15f);
            c0.setTextColor(0xFFE8ECF2);
            c0.setGravity(Gravity.END);
            c0.setMinWidth(dp(64));
            c0.setVisibility(View.GONE);
            line1.addView(c0);

            TextView code = new TextView(ctx);
            code.setTextSize(11f);
            code.setTextColor(0xFF6C7486);
            row.addView(code);

            row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean has) {
                    v.setBackground(rowBg(has));
                    if (has) {
                        mFocus = mOffset + slot;
                        selectRow(mFocus, false);
                    }
                }
            });
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    mFocus = mOffset + slot;
                    selectRow(mFocus, true);
                }
            });
            row.setOnKeyListener(rowKeys);
            mRowsV[i] = row;
            mNames[i] = name;
            mCodes[i] = code;
            mPrices[i] = price;
            mPcts[i] = pct;
            mC95[i] = c95;
            mC98[i] = c98;
            mC0[i] = c0;
            box.addView(row);
        }
        return box;
    }

    private View buildRight(Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        box.setPadding(dp(18), dp(12), dp(18), dp(12));
        box.setBackground(panel());
        box.setFocusable(true);
        box.setClickable(true);
        box.setOnKeyListener(sideKeys);
        box.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                v.setBackground(has ? panelFocused() : panel());
            }
        });
        mRightBox = box;

        dName = new TextView(ctx);
        dName.setTextSize(25f);
        dName.setTextColor(Color.WHITE);
        dName.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        box.addView(dName);

        dCode = new TextView(ctx);
        dCode.setTextSize(12f);
        dCode.setTextColor(0xFF7C8494);
        box.addView(dCode);

        LinearLayout priceRow = new LinearLayout(ctx);
        priceRow.setOrientation(LinearLayout.HORIZONTAL);
        priceRow.setGravity(Gravity.BOTTOM);
        LinearLayout.LayoutParams prlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        prlp.topMargin = dp(8);
        box.addView(priceRow, prlp);

        dPrice = new TextView(ctx);
        dPrice.setTextSize(44f);
        dPrice.setTextColor(Color.WHITE);
        dPrice.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        priceRow.addView(dPrice);

        dChange = new TextView(ctx);
        dChange.setTextSize(16f);
        dChange.setPadding(dp(14), 0, 0, dp(7));
        priceRow.addView(dChange);

        dPct = new TextView(ctx);
        dPct.setTextSize(19f);
        dPct.setPadding(dp(8), 0, 0, dp(7));
        priceRow.addView(dPct);

        dChart = new MinuteChart(ctx);
        LinearLayout.LayoutParams chlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        chlp.topMargin = dp(8);
        box.addView(dChart, chlp);

        mHistory = new HistoryChart(ctx);
        LinearLayout.LayoutParams hhlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        hhlp.topMargin = dp(8);
        mHistory.setVisibility(View.GONE);
        box.addView(mHistory, hhlp);

        // 美股/欧股没有分时数据，用它占位给个说明
        mChartHint = new TextView(ctx);
        mChartHint.setTextSize(13f);
        mChartHint.setTextColor(0xFF7C8494);
        mChartHint.setGravity(Gravity.CENTER);
        mChartHint.setVisibility(View.GONE);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        hintLp.topMargin = dp(8);
        box.addView(mChartHint, hintLp);

        for (int r = 0; r < 2; r++) {
            LinearLayout line = new LinearLayout(ctx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            for (int c = 0; c < 3; c++) {
                int idx = r * 3 + c;
                LinearLayout cell = new LinearLayout(ctx);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                cell.setPadding(0, dp(3), 0, dp(3));

                TextView k = new TextView(ctx);
                k.setTextSize(11f);
                k.setTextColor(0xFF7C8494);
                cell.addView(k);

                TextView v = new TextView(ctx);
                v.setTextSize(15f);
                v.setTextColor(0xFFE8ECF2);
                cell.addView(v);

                dStatKey[idx] = k;
                dStatVal[idx] = v;
                line.addView(cell);
            }
            box.addView(line);
        }

        dMeta = new TextView(ctx);
        dMeta.setTextSize(11f);
        dMeta.setTextColor(0xFF6C7486);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dp(6);
        box.addView(dMeta, mlp);
        return box;
    }

    // ---- 背景 ----

    private GradientDrawable panel() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xE610141D);
        d.setCornerRadius(dp(12));
        return d;
    }

    /** 右侧详情区拿到焦点时描一圈，不然电视上看不出焦点跑到右边了 */
    private GradientDrawable panelFocused() {
        GradientDrawable d = panel();
        d.setStroke(dp(2), 0x99FFFFFF);
        return d;
    }

    private GradientDrawable rowBg(boolean f) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(10));
        d.setColor(f ? ACCENT : 0x00000000);
        return d;
    }

    private GradientDrawable chipBg(int idx, boolean f) {
        GradientDrawable d = new GradientDrawable();
        boolean on = idx == mTab;
        d.setColor(f ? ACCENT : (on ? 0x55FFFFFF : 0x22FFFFFF));
        d.setCornerRadius(dp(16));
        if (f) d.setStroke(dp(2), 0xFFFFFFFF);
        return d;
    }

    // ================= 状态 =================

    private void bindTab(int tab) {
        mTab = tab;
        mOffset = 0;
        mFocus = 0;
        mItems.clear();
        if (tab == TAB_OIL) mTitle.setText("各省油价");
        else if (tab == TAB_GOLD) mTitle.setText("黄金 · 贵金属");
        else if (tab == TAB_INDEX) mTitle.setText("国内指数");
        else if (tab == TAB_WORLD) mTitle.setText("国际指数");
        else if (tab == TAB_FUND) mTitle.setText("我的基金");
        else mTitle.setText("我的自选");
        refreshRows();
        for (int i = 0; i < mChips.length; i++) {
            mChips[i].setBackground(chipBg(i, mChips[i].isFocused()));
            mChips[i].setTextColor(i == tab ? Color.WHITE : 0x99FFFFFF);
        }
        loadData();
    }

    /** 刷新当前分类；油价只有手动按「确定」才会真的重抓 */
    private void doRefresh() {
        if (mTab == TAB_OIL) {
            if (mOilLoading) { status("正在刷新全国油价… 稍等"); return; }
            // 注意：这里不清缓存。清掉的话，一旦站点限流返回 404，
            // 整张表会立刻变成 "--"；不清就能「抓不到就沿用上次」。
            HistoryCache.clear();      // 曲线要重算
            status("正在刷新全国油价…");
            // 已经有一份表格就就地刷新，绝不重建（重建会让整屏变空）
            if (mItems.size() == OilPrice.PROVINCES.length) {
                // 刷新后回到顶部并默认选中吉林
                mOffset = 0;
                int def = oilIndex("吉林");
                mFocus = def;
                refreshRows();
                selectRow(def, false);
                loadAllOil(true);
            } else {
                loadData();
            }
            return;
        }
        loadData();
    }

    private void onChip(int idx) {
        if (idx == mTab) { doRefresh(); return; }   // 同一个分类再按确定 = 刷新
        bindTab(idx);
    }

    private void refreshRows() {
        for (int i = 0; i < ROWS; i++) {
            int idx = mOffset + i;
            if (idx < mItems.size()) {
                QuoteApi.Item it = mItems.get(idx);
                mNames[i].setText(it.name);
                mCodes[i].setText(it.kind == 2 ? "" : it.displayCode);
                if (it.kind == 3 || it.kind == 4) {
                    mPrices[i].setText("");
                    mPcts[i].setText("");
                    mPcts[i].setVisibility(View.GONE);
                    if (mC95[i] != null) {
                        mC95[i].setVisibility(View.GONE);
                        mC98[i].setVisibility(View.GONE);
                        mC0[i].setVisibility(View.GONE);
                    }
                } else if (it.kind == 2) {
                    mPcts[i].setVisibility(View.GONE);
                    mPrices[i].setMinWidth(dp(64));
                    mPrices[i].setText(it.price > 0 ? String.format(Locale.US, "%.2f", it.price) : "--");
                    mPrices[i].setTextColor(0xFFE8ECF2);
                    mPcts[i].setText("");
                    if (mC95[i] != null) {
                        mC95[i].setVisibility(View.VISIBLE);
                        mC95[i].setText(it.extra1 == null ? "--" : it.extra1);
                        mC98[i].setVisibility(View.VISIBLE);
                        mC98[i].setText(it.extra2 == null ? "--" : it.extra2);
                        mC0[i].setVisibility(View.VISIBLE);
                        mC0[i].setText(it.extra3 == null ? "--" : it.extra3);
                    }
                } else {
                    mPcts[i].setVisibility(View.VISIBLE);
                    mPrices[i].setMinWidth(dp(72));
                    if (mC95[i] != null) {
                        mC95[i].setVisibility(View.GONE);
                        mC98[i].setVisibility(View.GONE);
                        mC0[i].setVisibility(View.GONE);
                    }
                    mPrices[i].setText(fmt(it.price, it.fund));
                    mPrices[i].setTextColor(0xFFE8ECF2);
                    mPcts[i].setText(pctText(it.pct));
                    mPcts[i].setTextColor(colorOf(it.pct));
                }
                mRowsV[i].setVisibility(View.VISIBLE);
            } else {
                mNames[i].setText("");
                mCodes[i].setText("");
                mPrices[i].setText("");
                mPcts[i].setText("");
                mRowsV[i].setVisibility(View.INVISIBLE);
            }
        }
    }

    private void selectRow(int index, boolean loadChart) {
        if (index < 0 || index >= mItems.size()) return;
        QuoteApi.Item it = mItems.get(index);
        mSelCode = it.code;

        // ---- 「＋ 添加基金」----
        if (it.kind == 4) {
            dName.setText("添加基金");
            dCode.setText("输入 6 位基金代码");
            dPrice.setText("");
            dChange.setText("");
            dPct.setText("");
            dChart.setVisibility(View.GONE);
            mHistory.setVisibility(View.GONE);
            mChartHint.setVisibility(View.VISIBLE);
            mChartHint.setText("按「确定」输入基金代码添加\n例如 005827（易方达蓝筹精选）\n选中某只基金按「菜单」可删除");
            setStats(new String[]{"", "", "", "", "", "", "", "", "", "", "", ""});
            dMeta.setText("");
            return;
        }

        // ---- 「＋ 添加股票」：只给提示，按确定才弹窗 ----
        if (it.kind == 3) {
            dName.setText("添加股票");
            dCode.setText("输入 6 位代码");
            dPrice.setText("");
            dChange.setText("");
            dPct.setText("");
            dChart.setVisibility(View.GONE);
            mHistory.setVisibility(View.GONE);
            mChartHint.setVisibility(View.VISIBLE);
            mChartHint.setText("按「确定」输入代码添加\nA股600519 · 港股00700 · 美股AAPL · 日股7203\n（韩股/英股：kr005930 / ukHSBA）\n基金请用「基金」分栏");
            setStats(new String[]{"", "", "", "", "", "", "", "", "", "", "", ""});
            dMeta.setText("");
            return;
        }

        // ---- 油价：选中省份后抓当地价格 ----
        if (it.kind == 2) {
            mLastOilName = it.name;
            dName.setText(it.name);
            dCode.setText("92#汽油参考价（元/升）");
            dPrice.setText("…");
            dPrice.setTextColor(0xFFE8ECF2);
            dChange.setText("");
            dPct.setText("");
            dChart.setVisibility(View.GONE);
            mHistory.setVisibility(View.VISIBLE);
            mHistory.setData(null, null);          // 先清掉上一个分类的曲线
            mChartHint.setVisibility(View.GONE);
            setStats(new String[]{"92#汽油", "--", "95#汽油", "--",
                    "98#汽油", "--", "0#柴油", "--", "", "", "", ""});
            dMeta.setText("");
            loadOil(index, it.name, it.code.substring(4));
            return;
        }

        // ---- 黄金：现货 / 期货 / COMEX，走势用近一年日 K ----
        if (it.kind == 1) {
            dName.setText(it.name);
            String unit = it.extra4 == null ? "" : it.extra4;
            dCode.setText(it.stale ? ("非交易时段 · 显示昨收 · " + unit) : ("近一年走势 · " + unit));
            dPrice.setText(fmt(it.price, false));
            dPrice.setTextColor(colorOf(it.pct));
            dChange.setText((it.change >= 0 ? "+" : "") + fmt(it.change, false));
            dChange.setTextColor(colorOf(it.pct));
            dPct.setText(pctText(it.pct));
            dPct.setTextColor(colorOf(it.pct));
            dChart.setVisibility(View.GONE);
            mHistory.setVisibility(View.VISIBLE);
            mHistory.setData(null, null);          // 先清掉上一个分类的曲线
            mChartHint.setVisibility(View.GONE);
            setStats(new String[]{"今日开", fmt(it.open, false), "昨收", fmt(it.prevClose, false),
                    "今日最高", fmt(it.high, false), "今日最低", fmt(it.low, false),
                    "单位", unit, "", ""});
            dMeta.setText("");
            loadGoldHistory(it.extra1, it.name);
            return;
        }

        boolean overseas = isOverseas(it.code);   // 美股 / 富时：无分时，成交量字段也不可用
        boolean aShare = isAShare(it.code);       // 只有 A 股的时间与成交量字段可信
        dName.setText(it.name);
        if (aShare) {
            dCode.setText(it.displayCode + "   " + fmtTime(it.time));
        } else {
            dCode.setText(it.displayCode);        // 港股/美股时间字段格式各异，只显示代码
        }
        dPrice.setText(fmt(it.price, it.fund));
        dPrice.setTextColor(colorOf(it.pct));
        dChange.setText((it.change >= 0 ? "+" : "") + fmt(it.change, it.fund));
        dChange.setTextColor(colorOf(it.pct));
        dPct.setText(pctText(it.pct));
        dPct.setTextColor(colorOf(it.pct));

        if (it.fund) {
            // 基金：近一年单位净值走势
            dChart.setVisibility(View.GONE);
            mHistory.setVisibility(View.VISIBLE);
            mHistory.setData(null, null);
            mChartHint.setVisibility(View.GONE);
            setStats(new String[]{"净值日期", it.navDate, "日涨幅", pctText(it.pct),
                    "单位净值", fmt(it.price, true), "", "", "", ""});
            dMeta.setText("");
            loadFundHistory(it.code);
        } else if (isGlobalIndex(it.code)) {
            // 国际指数：新浪只有行情，用近一年日 K
            dChart.setVisibility(View.GONE);
            mHistory.setVisibility(View.VISIBLE);
            mHistory.setData(null, null);
            mChartHint.setVisibility(View.GONE);
            setStats(new String[]{"现价", fmt(it.price, false),
                    "涨跌", (it.change >= 0 ? "+" : "") + fmt(it.change, false),
                    "涨跌幅", pctText(it.pct), "", "", "", ""});
            dMeta.setText("");
            loadStockHistory(it.code);
        } else if (overseas || !aShare) {
            // 港股 / 美股 / 日股 / 韩股 / 英股：给近一年日 K 曲线
            dChart.setVisibility(View.GONE);
            mHistory.setVisibility(View.VISIBLE);
            mHistory.setData(null, null);
            mChartHint.setVisibility(View.GONE);
            setStats(new String[]{"今开", fmt(it.open, false), "昨收", fmt(it.prevClose, false),
                    "最高", fmt(it.high, false), "最低", fmt(it.low, false),
                    "", "", "", ""});
            dMeta.setText("");
            loadStockHistory(it.code);
        } else {
            dChart.setVisibility(View.VISIBLE);
            mHistory.setVisibility(View.GONE);
            mChartHint.setVisibility(View.GONE);
            setStats(new String[]{"今开", fmt(it.open, false), "昨收", fmt(it.prevClose, false),
                    "最高", fmt(it.high, false), "最低", fmt(it.low, false),
                    "成交量", it.volTxt, "成交额", it.amtTxt});
            dMeta.setText("");
            // 换了一只就立刻清掉旧曲线并重新取分时，避免张冠李戴
            dChart.setData(null, it.prevClose);
            loadMinute(it.code, it.prevClose);
        }
    }

    /** 美股 / 欧洲指数：腾讯只给行情，没有分时，成交量字段也错位 */
    private static boolean isOverseas(String code) {
        if (code == null) return false;
        return code.startsWith("us") || code.startsWith("uk");
    }

    /** 国际指数（新浪 int_/znb_ 前缀） */
    private static boolean isGlobalIndex(String code) {
        if (code == null) return false;
        return code.startsWith("int_") || code.startsWith("znb_");
    }

    /** A 股（沪/深）：时间与成交量字段可信 */
    private static boolean isAShare(String code) {
        if (code == null) return false;
        return code.startsWith("sh") || code.startsWith("sz");
    }

    private void setStats(String[] kv) {
        for (int i = 0; i < 6; i++) {
            dStatKey[i].setText(i * 2 < kv.length ? kv[i * 2] : "");
            dStatVal[i].setText(i * 2 + 1 < kv.length ? kv[i * 2 + 1] : "");
            dStatVal[i].setTextColor(0xFFE8ECF2);
        }
    }

    /** 基金近一年净值走势 */
    private void loadFundHistory(final String code) {
        if (code == null) return;
        final String key = "fund@" + code;
        final java.util.List<Float> cached = HistoryCache.get(key);
        if (cached != null && cached.size() > 5) {
            mHistory.setData(cached, HistoryCache.getLabels(key));
            return;
        }
        Diag.log("fundhist", "start " + code);
        mPool.execute(new Runnable() {
            @Override public void run() {
                final QuoteApi.Series series;
                try {
                    series = QuoteApi.fetchFundHistory(code, 250);
                } catch (Throwable t) {
                    mMain.post(new Runnable() {
                        @Override public void run() {
                            dMeta.setText("");
                        }
                    });
                    return;
                }
                final java.util.List<Float> list = series == null ? null : series.values;
                final java.util.List<String> labels = series == null ? null : series.labels;
                if (list != null && list.size() > 5) HistoryCache.put(key, list, labels);
                mMain.post(new Runnable() {
                    @Override public void run() {
                        if (list != null && list.size() > 5) {
                            mHistory.setVisibility(View.VISIBLE);
                            mChartHint.setVisibility(View.GONE);
                            mHistory.setData(list, labels);
                            dMeta.setText("");
                        } else {
                            mHistory.setVisibility(View.GONE);
                            mChartHint.setVisibility(View.VISIBLE);
                            mChartHint.setText("没取到净值历史");
                        }
                    }
                });
            }
        });
    }

    /** 港股 / 美股 / 日股等的近一年日 K 曲线 */
    private void loadStockHistory(final String code) {
        if (code == null) return;
        Diag.log("kline", "loadStockHistory " + code);
        final String key = "sk@" + code;
        final java.util.List<Float> cached = HistoryCache.get(key);
        if (cached != null && cached.size() > 5) {
            mHistory.setData(cached, HistoryCache.getLabels(key));
            return;
        }
        mPool.execute(new Runnable() {
            @Override public void run() {
                final QuoteApi.Series series = QuoteApi.fetchTencentKline(code, 250);
                final java.util.List<Float> list = series == null ? null : series.values;
                final java.util.List<String> labels = series == null ? null : series.labels;
                if (list != null && list.size() > 5) HistoryCache.put(key, list, labels);
                mMain.post(new Runnable() {
                    @Override public void run() {
                        if (list != null && list.size() > 5) {
                            mHistory.setVisibility(View.VISIBLE);
                            mChartHint.setVisibility(View.GONE);
                            mHistory.setData(list, labels);
                            dMeta.setText("");
                        } else {
                            mHistory.setVisibility(View.GONE);
                            mChartHint.setVisibility(View.VISIBLE);
                            if (code.startsWith("int_") || code.startsWith("znb_")) {
                                mChartHint.setText("该地区指数暂无可用的历史曲线\n（数据源只提供实时行情）");
                                dMeta.setText("");
                            } else {
                                mChartHint.setText("没取到历史行情");
                                dMeta.setText("");
                            }
                        }
                    }
                });
            }
        });
    }

    /** 黄金近一年日 K 曲线 */
    private void loadGoldHistory(final String secid, final String name) {
        if (secid == null) return;
        final String key = "gold@" + secid;
        final java.util.List<Float> cached = HistoryCache.get(key);
        if (cached != null && cached.size() > 5) {
            mHistory.setData(cached, HistoryCache.getLabels(key));
            return;
        }
        mPool.execute(new Runnable() {
            @Override public void run() {
                final QuoteApi.Series series = QuoteApi.fetchKline(secid, name, 250);
                final java.util.List<Float> list = series == null ? null : series.values;
                final java.util.List<String> labels = series == null ? null : series.labels;
                if (list != null && list.size() > 5) HistoryCache.put(key, list, labels);
                mMain.post(new Runnable() {
                    @Override public void run() {
                        if (mTab != TAB_GOLD) return;
                        if (list != null && list.size() > 5) {
                            mHistory.setVisibility(View.VISIBLE);
                            mChartHint.setVisibility(View.GONE);
                            mHistory.setData(list, labels);
                            dMeta.setText("");
                        } else {
                            mHistory.setVisibility(View.GONE);
                            mChartHint.setVisibility(View.VISIBLE);
                            mChartHint.setText("没取到历史行情");
                        }
                    }
                });
            }
        });
    }

    /** 抓某个省的油价（异步），带 10 分钟缓存 */
    /** 并发抓 31 个省，边抓边刷新左表 */
    /** 按省名找下标；找不到退回吉林的固定位置（北京0 天津1 河北2 山西3 内蒙古4 辽宁5 吉林6） */
    private int oilIndex(String name) {
        for (int i = 0; i < mItems.size(); i++) {
            if (name.equals(mItems.get(i).name)) return i;
        }
        return Math.min(6, Math.max(0, mItems.size() - 1));
    }

    /**
     * 抓全国油价。
     *
     * force=false：只补缓存里没有的省；全都有缓存时一次网络都不发
     *              （切分类、从别的 TAB 切回来走的就是这条路）
     * force=true ：手动按「确定」，31 省全部重抓（调用方先清缓存）
     *
     * 单飞 + 代次：同一时刻只有一轮在跑；上一轮迟到的响应回来直接丢弃，
     * 不会再出现「新数据刚填好、又被几分钟前的旧数据盖回去」。
     */
    private void loadAllOil(final boolean force) {
        if (mOilLoading && !force) return;

        final int total = OilPrice.PROVINCES.length;
        final int gen = ++mOilGen;
        final java.util.concurrent.atomic.AtomicInteger done =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger gone =
                new java.util.concurrent.atomic.AtomicInteger(0);   // 站点 404：没这个页面
        final java.util.concurrent.atomic.AtomicInteger failed =
                new java.util.concurrent.atomic.AtomicInteger(0);  // 超时 / 断网
        int fromCache = 0;
        boolean needNet = false;

        for (int i = 0; i < total; i++) {
            final int idx = i;
            final String name = OilPrice.PROVINCES[i][0];
            final String slug = OilPrice.PROVINCES[i][1];
            final OilPrice.Oil cached = OilCache.get(slug);
            if (cached != null) {
                fromCache++;
                applyOilRow(idx, cached);   // 先把旧值摆上，手动刷新时屏幕也不会空
            }
            if (cached != null && !force) {
                done.incrementAndGet();
                continue;
            }
            needNet = true;
            final long delay = (idx % 8) * 80L;   // 摊开一点，别一堆请求同时怼过去
            mOilPool.execute(new Runnable() {
                @Override public void run() {
                    try { if (delay > 0) Thread.sleep(delay); } catch (Throwable ignored) { }
                    OilPrice.Oil oil = OilPrice.fetch(name, slug);
                    // 404 是站点上没这个页面，重试没意义；超时/断网才补一次
                    if (oil != null && !oil.ok && !oil.missing) {
                        try { Thread.sleep(800); } catch (Throwable ignored) { }
                        OilPrice.Oil retry = OilPrice.fetch(name, slug);
                        if (retry != null && retry.ok) oil = retry;
                    }
                    OilCache.put(slug, oil);
                    final OilPrice.Oil result = oil;
                    if (oil == null || oil.missing) gone.incrementAndGet();
                    else if (!oil.ok) failed.incrementAndGet();
                    final int n = done.incrementAndGet();
                    mMain.post(new Runnable() {
                        @Override public void run() {
                            if (gen != mOilGen) return;    // 上一轮的，丢掉
                            // 只有真拿到价才覆盖；这一省失败就保留上一轮的值，
                            // 不会因为一次限流把整格刷成 "--"
                            if (result != null && result.ok) applyOilRow(idx, result);
                            if (n < total) status("正在获取全国油价… " + n + "/" + total);
                            else finishOil(gen, total, gone.get(), failed.get());
                        }
                    });
                }
            });
        }

        if (!needNet) {                                    // 全都命中缓存，一次网络都不发
            mOilLoading = false;
            mOilComplete = true;
            if (mOilAt == 0) mOilAt = OilCache.newestAt();
            refreshRows();
            status("油价 " + hhmm(mOilAt) + " 取过（本地缓存）· 按「确定」刷新");
            return;
        }

        // 一个省都没缓存、而且刚抓过：多半是被站点限流了，退避 20 分钟再自动补，
        // 不然每次切回 TAB 都重锤 31 个请求，越抓越抓不到。
        if (fromCache == 0 && !force && OilCache.tryAt() > 0
                && System.currentTimeMillis() - OilCache.tryAt() < 20 * 60 * 1000L) {
            mOilLoading = false;
            mOilComplete = false;
            refreshRows();
            status("上次没取到油价 · 稍后再进，或按「确定」强制刷新");
            return;
        }

        OilCache.markTry();
        mOilLoading = true;
        status("正在获取全国油价… " + fromCache + "/" + total);
        // 兜底：个别省卡死不回，20 秒后也要收尾，别一直挂在「正在获取」
        mMain.postDelayed(new Runnable() {
            @Override public void run() { finishOil(gen, total, gone.get(), failed.get()); }
        }, 20000);
    }

    /** 一轮抓取收尾：统计缺几省、写状态栏 */
    private void finishOil(int gen, int total, int unavailable, int failed) {
        if (gen != mOilGen || !mOilLoading) return;
        mOilLoading = false;
        mOilAt = System.currentTimeMillis();
        OilCache.flush();                                  // 落盘，下次切 TAB 直接用
        refreshRows();
        int blank = 0;
        for (int i = 0; i < mItems.size(); i++) {
            if (mItems.get(i).kind == 2 && mItems.get(i).price <= 0) blank++;
        }
        mOilComplete = blank == 0;
        if (mTab != TAB_OIL) return;
        String t = hhmm(mOilAt);
        if (blank == 0 && unavailable == 0 && failed == 0) {
            status("油价更新于 " + t + " · " + total + " 省 · 按「确定」刷新");
        } else if (blank == 0 && unavailable > 0) {
            status("油价更新于 " + t + " · " + (total - unavailable) + "/" + total
                    + " 省（" + unavailable + " 省站点无数据）");
        } else if (blank == 0) {
            status("油价更新于 " + t + " · 31 省（" + failed + " 省没更新，沿用上次）");
        } else {
            status("油价更新于 " + t + " · " + (total - blank) + "/" + total
                    + " 省，其余没取到");
        }
    }

    private static String hhmm(long at) {
        if (at <= 0) return "--:--";
        return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                .format(new java.util.Date(at));
    }

    /** 把某省的四个价格填进左表对应行 */
    private void applyOilRow(int index, OilPrice.Oil oil) {
        if (oil == null) return;
        if (index < 0 || index >= mItems.size()) return;
        QuoteApi.Item it = mItems.get(index);
        it.price = parseF(oil.p92);
        it.extra1 = oil.p95;
        it.extra2 = oil.p98;
        it.extra3 = oil.p0;
        it.extra4 = oil.adjustDate;
        if (index >= mOffset && index < mOffset + ROWS) {
            int slot = index - mOffset;
            if (mC95[slot] != null) { mC95[slot].setVisibility(View.VISIBLE); mC95[slot].setText(oil.p95); }
            if (mC98[slot] != null) { mC98[slot].setVisibility(View.VISIBLE); mC98[slot].setText(oil.p98); }
            if (mC0[slot] != null) { mC0[slot].setVisibility(View.VISIBLE); mC0[slot].setText(oil.p0); }
            if (mPrices[slot] != null) {
                mPrices[slot].setVisibility(View.VISIBLE);
                mPrices[slot].setText(oil.p92);
                mPrices[slot].setTextColor(0xFFE8ECF2);
            }
            if (mPcts[slot] != null) { mPcts[slot].setVisibility(View.GONE); }
        }
    }

    private static float parseF(String s) {
        try { return Float.parseFloat(s); } catch (Throwable t) { return 0f; }
    }

    private void loadOil(final int index, final String name, final String slug) {
        final OilPrice.Oil cached = OilCache.get(slug);
        if (cached != null) {
            applyOil(cached);
            return;
        }
        mPool.execute(new Runnable() {
            @Override public void run() {
                final OilPrice.Oil oil = OilPrice.fetch(name, slug);
                OilCache.put(slug, oil);
                OilCache.flush();
                mMain.post(new Runnable() {
                    @Override public void run() {
                        if (!("oil:" + slug).equals(mSelCode)) return;
                        applyOil(oil);
                    }
                });
            }
        });
    }

    /** 近一年每次调价后的 92# 价格曲线 */
    private void loadOilHistory(final String province, final String slug, final String base92) {
        final java.util.List<Float> cached = HistoryCache.get(slug + "@" + base92);
        if (cached != null && cached.size() > 2) {
            mHistory.setData(cached, HistoryCache.getLabels(slug + "@" + base92));
            return;
        }
        mOilPool.execute(new Runnable() {
            @Override public void run() {
                final java.util.List<Float> list;
                float base = 0f;
                java.util.List<String> labels = null;
                final String[] latestDate = {""};
                try { base = Float.parseFloat(base92); } catch (Throwable ignored) { }
                if (base <= 0f) {
                    OilPrice.Oil cur = OilCache.get(slug);
                    if (cur != null) {
                        try { base = Float.parseFloat(cur.p92); } catch (Throwable ignored) { }
                    }
                }
                OilPrice.Series series = OilPrice.priceHistory(base > 0 ? base : 8.0f);
                list = series.values;
                labels = series.labels;
                latestDate[0] = series.latestDate;
                Diag.log("oil", "hist " + slug + " base=" + base + " n=" + list.size()
                        + " first=" + list.get(0) + " last=" + list.get(list.size() - 1));
                HistoryCache.put(slug + "@" + base92, list, labels);
                mMain.post(new Runnable() {
                    @Override public void run() {
                        if (!("oil:" + slug).equals(mSelCode)) return;
                        if (list != null && list.size() > 2) {
                            dChart.setVisibility(View.GONE);
                            mChartHint.setVisibility(View.GONE);
                            mHistory.setVisibility(View.VISIBLE);
                            mHistory.setData(list);
                            dMeta.setText("");
                        } else {
                            dChart.setVisibility(View.GONE);
                            mChartHint.setVisibility(View.VISIBLE);
                            mChartHint.setText("没取到调价历史");
                        }
                    }
                });
            }
        });
    }

    private void applyOil(OilPrice.Oil oil) {
        if (oil == null || oil.p92 == null || "--".equals(oil.p92)) return;
        mOil = oil;
        dName.setText(oil.province);
        dPrice.setText(oil.p92);
        dPrice.setTextColor(0xFFFFB454);
        dCode.setText("92#汽油 · 元/升（曲线也是 92#）");
        setStats(new String[]{
                "0#柴油", oil.p0, "-10#柴油", oil.p10,
                "-20#柴油", oil.p20, "-35#柴油", oil.p35,
                "下次调价", oil.adjustDate,
                "预计调整", forecastText(oil)});

        // 预计调整按涨跌上色：涨红跌绿
        if (dStatVal[5] != null) {
            int fc = 0xFFE8ECF2;
            if (oil.forecastDir > 0) fc = 0xFFFF4D4F;
            else if (oil.forecastDir < 0) fc = 0xFF3FBF6F;
            dStatVal[5].setTextColor(fc);
        }

        dMeta.setText("");

        // 拿到真实 92# 价格后再画曲线，避免所有省都用同一个默认基准
        String slug = (mSelCode != null && mSelCode.startsWith("oil:")) ? mSelCode.substring(4) : "";
        if (slug.length() > 0) loadOilHistory(oil.province, slug, oil.p92);
    }

    /** 「+0.06 → 8.94」：预计涨跌 + 调整后价格 */
    private static String forecastText(OilPrice.Oil oil) {
        if (oil == null || oil.forecast == null || oil.forecast.length() == 0) return "待公布";
        if (oil.forecastDir == 0) return "持平";
        float after = 0f;
        try { after = Float.parseFloat(oil.p92) + oil.forecastValue; } catch (Throwable ignored) { }
        return oil.forecast + " → " + String.format(Locale.US, "%.2f", after);
    }

    /** 两个价格字符串的差，保留两位 */
    private static String diff(String a, String b) {
        try {
            return String.format(Locale.US, "%.2f", Float.parseFloat(a) - Float.parseFloat(b));
        } catch (Throwable t) {
            return "--";
        }
    }

    private void loadMinute(final String code, final float fallbackPrev) {
        // 20 秒内重复选同一只，直接用缓存，避免打到限流
        final QuoteApi.Minute cached = QuoteApi.cachedMinute(code);
        if (cached != null && cached.prices.size() > 1) {
            dChart.setData(cached.prices, cached.prevClose > 0f ? cached.prevClose : fallbackPrev);
            return;
        }
        mPool.execute(new Runnable() {
            @Override public void run() {
                final QuoteApi.Minute m = QuoteApi.fetchMinute(code);
                mMain.post(new Runnable() {
                    @Override public void run() {
                        // 期间用户可能已切到别的股票，过期结果直接丢弃
                        if (code == null || !code.equals(mSelCode)) return;
                        // 指数分时接口里的「昨收」字段不可靠，行情里的昨收更准
                        float prev = fallbackPrev > 0f ? fallbackPrev : m.prevClose;
                        dChart.setData(m.prices, prev);
                    }
                });
            }
        });
    }

    private static String fmt(float v, boolean fund) {
        if (v == 0f) return "--";
        return String.format(Locale.US, fund ? "%.4f" : "%.2f", v);
    }

    private static String pctText(float p) {
        return String.format(Locale.US, "%+.2f%%", p);
    }

    private static int colorOf(float p) {
        if (p > 0.0001f) return 0xFFFF4D4F;
        if (p < -0.0001f) return 0xFF3FBF6F;
        return 0xFFAAB0BC;
    }

    private static String fmtTime(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() >= 12) {
            return s.substring(4, 6) + "-" + s.substring(6, 8) + " " + s.substring(8, 10) + ":" + s.substring(10, 12);
        }
        return s;
    }

    private void status(final String s) {
        if (mStatus == null) return;
        mMain.post(new Runnable() {
            @Override public void run() { mStatus.setText(s); }
        });
    }

    // ================= 按键 =================

    private final View.OnKeyListener rowKeys = new View.OnKeyListener() {
        @Override public boolean onKey(View v, int code, KeyEvent e) {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            int size = mItems.size();
            if (size == 0) return false;
            int slot = mFocus - mOffset;
            if (slot < 0 || slot >= ROWS) slot = 0;
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                if (mFocus >= 0 && mFocus < mItems.size()) {
                    int k = mItems.get(mFocus).kind;
                    if (k == 3) showAddDialog();
                    else if (k == 4) showAddFundDialog();
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_MENU) {
                if (mFocus >= 0 && mFocus < mItems.size()) {
                    QuoteApi.Item cur = mItems.get(mFocus);
                    if (cur.kind == 3) showAddDialog();
                    else if (cur.kind == 4) showAddFundDialog();
                    else if (mTab == TAB_FUND) showDeleteFundDialog(cur);
                    else if (mTab == TAB_WATCH) showDeleteDialog(cur);
                }
                return true;
            }
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (slot == ROWS - 1) {
                        if (mOffset + ROWS >= size) return true;
                        mOffset++;
                        mFocus = mOffset + slot;
                        refreshRows();
                        selectRow(mFocus, true);   // 翻页后详情跟上
                    } else {
                        mRowsV[slot + 1].requestFocus();
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
                        selectRow(mFocus, true);
                        mRowsV[0].requestFocus();
                    } else {
                        mRowsV[slot - 1].requestFocus();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    // 左区在任何分类里都往右区送焦点：以前 RIGHT 只认分时图，
                    // 而分时图在油价/基金/国际这些分类是隐藏的，按了没反应
                    if (mRightBox != null && mRightBox.requestFocus()) return true;
                    if (mChips[mTab] != null) mChips[mTab].requestFocus();
                    return true;
                case KeyEvent.KEYCODE_BACK:
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
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_BACK: {
                    int slot = mFocus - mOffset;
                    if (slot < 0 || slot >= ROWS) slot = 0;
                    if (mRowsV[slot] != null) mRowsV[slot].requestFocus();
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_RIGHT: {
                    // 右区最右边按右键：绕回左区第一条
                    mOffset = 0;
                    mFocus = 0;
                    refreshRows();
                    if (mRowsV[0] != null) mRowsV[0].requestFocus();
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    return true;
                default:
                    return false;
            }
        }
    };

    private void goToTabBar() {
        View byText = findByText(mRoot.getRootView(), "股票", 0);
        if (byText != null) {
            View target = byText;
            while (target != null && !target.isFocusable()) {
                android.view.ViewParent p = target.getParent();
                if (!(p instanceof View)) { target = null; break; }
                target = (View) p;
            }
            if (target != null && target.requestFocus()) return;
        }
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

    private int dp(int v) { return v * mDp; }

    // ================= BaseFragment =================

    @Override public boolean onFocusRequested(int direction) {
        if (mChips[mTab] != null) { mChips[mTab].requestFocus(); return true; }
        return false;
    }

    @Override public void onFragmentSeletedPre(boolean selected) { }

    @Override public boolean onHomeKeyEventHandled() { return false; }

    @Override public void setHoverListener(View.OnHoverListener listener) { }

    @Override public void onFragmentShowChanged(boolean shown) {
        if (!shown) return;
        // 油价不是行情：31 省都齐了、缓存也没过期，就只重绘，一次网络都不发
        if (mTab == TAB_OIL && mOilComplete && OilCache.fresh()) {
            refreshRows();
            status("油价 " + hhmm(mOilAt > 0 ? mOilAt : OilCache.newestAt())
                    + " 取过（本地缓存）· 按「确定」刷新");
            return;
        }
        loadData();
    }

    @Override public void onDestroyView() {
        mPool.shutdownNow();
        super.onDestroyView();
    }
}
