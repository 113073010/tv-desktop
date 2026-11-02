package com.dsh.tvdesk.radio;

import android.content.Context;
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

import java.util.ArrayList;
import java.util.List;

/**
 * 电台 TAB：左分类（新闻广播置顶）+ 右 4×3 卡片 + 底部播放条
 * 视觉：面板半透明让壁纸透出；卡片显示 FM 频段；台名加大
 */
public class RadioFragment extends BaseFragment {

    private static final int COLS = 4;
    private static final int ROWS = 4;
    private static final int SLOTS = COLS * ROWS;

    private static final int ACCENT = 0xFF2674EE;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int TEXT = 0xFFF2F5FA;
    private static final int SUB = 0xB3C6CEDC;
    private static final int DIM = 0x8CA8B2C4;

    private Context mCtx;
    private View mRoot;
    private int mDp = 2;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private final TextView[] mCatV = new TextView[Stations.CATS.length];
    private final LinearLayout[] mCardV = new LinearLayout[SLOTS];
    private final TextView[] mCardName = new TextView[SLOTS];
    private final TextView[] mCardMeta = new TextView[SLOTS];
    private final TextView[] mCardIcon = new TextView[SLOTS];
    private final TextView[] mCardPlay = new TextView[SLOTS];

    private TextView mBarTitle, mBarSub, mBarBtn, mBarFm;
    private LinearLayout mBar;

    private final List<Stations.S> mAll = Stations.all();
    private List<Stations.S> mShown = new ArrayList<Stations.S>();
    private int mCat = 0;
    private int mFocusSlot = 0;
    private int mOffset = 0;
    private String mPlayingName = null;

    private android.media.MediaPlayer mPlayer;

    // ================= 布局 =================

    @Override
    public View onInflaterContent(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Context ctx = container != null ? container.getContext() : getActivity();
        mCtx = ctx;
        if (ctx == null) return new TextView(getActivity());
        mDp = Math.round(ctx.getResources().getDisplayMetrics().density);
        if (mDp <= 0) mDp = 2;

        FrameLayout root = new FrameLayout(ctx);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setPadding(dp(30), dp(96), dp(30), dp(24));
        root.setClipChildren(false);
        mRoot = root;

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 主体 ----
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyLp.topMargin = dp(8);          // 内容与tab栏留一点间距
        body.setLayoutParams(bodyLp);
        body.setClipChildren(false);
        body.setClipToPadding(false);
        body.addView(buildCats(ctx));
        body.addView(buildGrid(ctx));
        col.addView(body);

        col.addView(buildBar(ctx));

        root.addView(col);
        selectCat(0, true);
        return root;
    }

    private View buildCats(Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(158),
                ViewGroup.LayoutParams.MATCH_PARENT);
        lp.rightMargin = dp(22);
        lp.topMargin = dp(0);
        box.setLayoutParams(lp);
        // 磨砂感：半透明深色 + 细描边，既能透出壁纸又保证可读
        GradientDrawable catBgPanel = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x1FFFFFFF, 0x0FFFFFFF});
        catBgPanel.setCornerRadius(dp(22));
        catBgPanel.setStroke(dp(1), 0x33FFFFFF);
        box.setBackground(catBgPanel);
        box.setPadding(dp(8), dp(10), dp(8), dp(16));
        box.setClipChildren(false);

        for (int i = 0; i < Stations.CATS.length; i++) {
            final int idx = i;
            TextView v = new TextView(ctx);
            v.setText(Stations.CATS[i]);
            v.setTextSize(24f);
            v.setGravity(Gravity.CENTER);
            v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            v.setPadding(dp(10), dp(9), dp(10), dp(9));
            v.setFocusable(true);
            v.setClickable(true);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            ilp.bottomMargin = dp(4);
            v.setLayoutParams(ilp);
            v.setShadowLayer(4f, 0f, 1f, 0x99000000);
            v.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View x) { selectCat(idx, false); }
            });
            v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View x, boolean f) { styleCat((TextView) x, idx, f); }
            });
            v.setOnKeyListener(new View.OnKeyListener() {
                @Override public boolean onKey(View x, int code, KeyEvent e) {
                    if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                    if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        if (mCardV[0] != null) { mCardV[0].requestFocus(); return true; }
                    }
                    if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                        mCatV[0].requestFocus();
                        return true;
                    }
                    if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                        if (idx < mCatV.length - 1) { mCatV[idx + 1].requestFocus(); return true; }
                        if (mBar != null) mBar.requestFocus();
                        return true;
                    }
                    if (code == KeyEvent.KEYCODE_DPAD_UP) {
                        if (idx > 0) { mCatV[idx - 1].requestFocus(); return true; }
                        goToTabBar();       // 已在最顶端 → 回 TAB 栏
                        return true;
                    }
                    if (code == KeyEvent.KEYCODE_BACK) { goToTabBar(); return true; }
                    if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                        selectCat(idx, false);
                        return true;
                    }
                    return false;
                }
            });
            mCatV[i] = v;
            box.addView(v);
        }
        return box;
    }

    private View buildGrid(Context ctx) {
        LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        grid.setLayoutParams(glp);
        grid.setClipChildren(false);

        for (int r = 0; r < ROWS; r++) {
            LinearLayout line = new LinearLayout(ctx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            for (int c = 0; c < COLS; c++) {
                final int slot = r * COLS + c;
                LinearLayout card = new LinearLayout(ctx);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                clp.rightMargin = dp(10);
                clp.bottomMargin = r == ROWS - 1 ? 0 : dp(8);
                card.setLayoutParams(clp);
                card.setPadding(dp(14), dp(10), dp(12), dp(10));
                card.setFocusable(true);
                card.setClickable(true);
                card.setClipChildren(false);

                TextView icon = new TextView(ctx);
                icon.setTextSize(18f);
                icon.setGravity(Gravity.CENTER);
                icon.setTextColor(WHITE);
                icon.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(48), dp(48));
                ilp.rightMargin = dp(12);
                icon.setLayoutParams(ilp);
                card.addView(icon);

                LinearLayout texts = new LinearLayout(ctx);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setGravity(Gravity.CENTER_VERTICAL);
                texts.setLayoutParams(new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.MATCH_PARENT, 1f));

                TextView name = new TextView(ctx);
                name.setTextSize(16f);
                name.setTextColor(0xFFFFFFFF);
                name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                name.setMaxLines(2);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                name.setShadowLayer(4f, 0f, 1f, 0x99000000);
                texts.addView(name);

                TextView meta = new TextView(ctx);
                meta.setTextSize(11f);
                meta.setShadowLayer(3f, 0f, 1f, 0x99000000);
                meta.setTextColor(0xD9FFFFFF);
                LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                mlp.topMargin = dp(3);
                meta.setLayoutParams(mlp);
                texts.addView(meta);
                card.addView(texts);

                TextView play = new TextView(ctx);
                play.setText("");
                play.setTextSize(12f);
                play.setTextColor(0xFF8FD0FF);
                play.setGravity(Gravity.CENTER);
                play.setLayoutParams(new LinearLayout.LayoutParams(dp(24),
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                card.addView(play);

                card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                    @Override public void onFocusChange(View v, boolean f) {
                        int gi = mOffset + slot;
                        boolean playing = (gi >= 0 && gi < mShown.size())
                                && mShown.get(gi).name.equals(mPlayingName);
                        if (f) v.setBackground(cardBg(true));
                        else if (playing) v.setBackground(cardPlayingBg());
                        else v.setBackground(cardBg(false));
                        if (f) mFocusSlot = gi;
                        styleCardText(slot, f);
                    }
                });
                card.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { playSlot(mOffset + slot); }
                });
                card.setOnKeyListener(cardKeys);

                mCardV[slot] = card;
                mCardIcon[slot] = icon;
                mCardName[slot] = name;
                mCardMeta[slot] = meta;
                mCardPlay[slot] = play;
                line.addView(card);
            }
            grid.addView(line);
        }
        return grid;
    }

    private View buildBar(Context ctx) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        lp.topMargin = dp(8);
        bar.setLayoutParams(lp);
        bar.setPadding(dp(16), 0, dp(18), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(32));
        bg.setColor(0x40000000);
        bg.setStroke(dp(1), 0x26FFFFFF);
        bar.setBackground(bg);
        bar.setFocusable(true);
        bar.setClickable(true);
        mBar = bar;

        bar.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean f) {
                GradientDrawable d = new GradientDrawable();
                d.setCornerRadius(dp(32));
                d.setColor(f ? 0x660E3E9E : 0x40000000);
                d.setStroke(dp(f ? 2 : 1), f ? 0xFFFFFFFF : 0x26FFFFFF);
                v.setBackground(d);
            }
        });
        bar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { togglePlay(); }
        });
        bar.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int code, KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                switch (code) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        if (mCardV[0] != null) { mCardV[0].requestFocus(); return true; }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                        togglePlay();
                        return true;
                    case KeyEvent.KEYCODE_BACK:
                        if (mCatV[mCat] != null) mCatV[mCat].requestFocus();
                        return true;
                    default:
                        return false;
                }
            }
        });

        mBarBtn = new TextView(ctx);
        mBarBtn.setText("▶");
        mBarBtn.setTextSize(15f);
        mBarBtn.setGravity(Gravity.CENTER);
        mBarBtn.setTextColor(WHITE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(40), dp(40));
        blp.rightMargin = dp(14);
        mBarBtn.setLayoutParams(blp);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(ACCENT);
        mBarBtn.setBackground(circle);
        bar.addView(mBarBtn);

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mBarTitle = new TextView(ctx);
        mBarTitle.setTextSize(15f);
        mBarTitle.setTextColor(WHITE);
        mBarTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        mBarTitle.setText("选一个电台开始收听");
        texts.addView(mBarTitle);

        mBarSub = new TextView(ctx);
        mBarSub.setTextSize(11f);
        mBarSub.setTextColor(SUB);
        mBarSub.setText("上下切换分类 · 左右选台 · 确定播放");
        texts.addView(mBarSub);
        bar.addView(texts);

        mBarFm = new TextView(ctx);
        mBarFm.setTextSize(13f);
        mBarFm.setTextColor(0xFF8FD0FF);
        mBarFm.setGravity(Gravity.CENTER_VERTICAL);
        mBarFm.setPadding(dp(10), 0, dp(10), 0);
        bar.addView(mBarFm);

        TextView q = new TextView(ctx);
        q.setText("网络广播");
        q.setTextSize(11f);
        q.setTextColor(DIM);
        bar.addView(q);

        return bar;
    }

    // ================= 状态 =================

    private void styleCat(TextView v, int idx, boolean focused) {
        if (v == null) return;
        boolean selected = idx == mCat;
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(28));
        if (focused) {
            // 焦点态：蓝色，跟遥控器走
            d.setColor(ACCENT);
            d.setStroke(dp(2), 0xCCFFFFFF);
            v.setTextColor(WHITE);
            v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        } else if (selected) {
            // 选中态（当前分类但无焦点）：半透明白
            d.setColor(0x59FFFFFF);
            v.setTextColor(WHITE);
            v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        } else {
            d.setColor(0x00000000);
            v.setTextColor(WHITE);
            v.setTypeface(android.graphics.Typeface.DEFAULT);
        }
        v.setPadding(dp(10), dp(9), dp(10), dp(9));
        v.setBackground(d);
    }

    private void selectCat(int idx, boolean initial) {
        mCat = idx;
        mFocusSlot = 0;
        mOffset = 0;
        mShown = Stations.byCat(mAll, Stations.CATS[idx]);
        for (int i = 0; i < mCatV.length; i++) {
            styleCat(mCatV[i], i, mCatV[i].isFocused());
        }
        refreshCards();
    }

    /** 卡片文字配色：蓝底(聚焦)用白字，浅底用深字 */
    private void styleCardText(int globalIdx, boolean focused) {
        int i = globalIdx - mOffset;
        if (i < 0 || i >= SLOTS || mCardName[i] == null) return;
        boolean playing = mShown.get(Math.max(0, Math.min(mShown.size() - 1,
                globalIdx))).name.equals(mPlayingName);
        if (focused) {
            mCardName[i].setTextColor(0xFFFFFFFF);
            mCardMeta[i].setTextColor(0xE6FFFFFF);
            mCardPlay[i].setTextColor(0xFFFFFFFF);
        } else {
            mCardName[i].setTextColor(0xFFFFFFFF);
            mCardMeta[i].setTextColor(playing ? 0xFFD6E9FF : 0xD9FFFFFF);
            mCardPlay[i].setTextColor(0xFFE8F3FF);
        }
    }

    private void refreshCards() {
        for (int i = 0; i < SLOTS; i++) {
            int idx = mOffset + i;
            if (idx < mShown.size()) {
                Stations.S s = mShown.get(idx);
                mCardV[i].setVisibility(View.VISIBLE);
                mCardName[i].setText(s.name);
                mCardIcon[i].setText(s.name.substring(0, 1));
                mCardIcon[i].setBackground(iconBg(colorFor(s.name)));
                boolean playing = s.name.equals(mPlayingName);
                String fm = s.fm == null ? "" : s.fm;
                mCardMeta[i].setText(playing ? "正在播放" : (fm.isEmpty() ? "网络电台" : fm));
                mCardPlay[i].setText(playing ? "●" : "");
                styleCardText(idx, mCardV[i].isFocused());
                if (playing && !mCardV[i].isFocused()) {
                    mCardV[i].setBackground(cardPlayingBg());
                } else {
                    mCardV[i].setBackground(cardBg(mCardV[i].isFocused()));
                }
            } else {
                mCardV[i].setVisibility(View.INVISIBLE);
            }
        }
    }

    private void playSlot(int slot) {
        if (slot < 0 || slot >= mShown.size()) return;
        Stations.S s = mShown.get(slot);
        stopPlayer();
        mPlayingName = s.name;
        refreshCards();
        mBarTitle.setText(s.name);
        mBarFm.setText(s.fm == null ? "" : s.fm);
        mBarSub.setText("连接中…");
        mBarBtn.setText("…");
        try {
            mPlayer = new android.media.MediaPlayer();
            mPlayer.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
            mPlayer.setDataSource(s.url);
            mPlayer.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(android.media.MediaPlayer mp) {
                    try { mp.start(); } catch (Throwable ignored) { }
                    mBarBtn.setText("❚❚");
                    mBarSub.setText("正在播放 · " + qualityOf(current()));
                    refreshCards();

                }
            });
            mPlayer.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener() {
                @Override public boolean onError(android.media.MediaPlayer mp, int what, int extra) {
                    mBarBtn.setText("!");
                    mBarSub.setText("播不了（" + what + "/" + extra + "），换个台");
                    mPlayingName = null;
                    refreshCards();
                    return true;
                }
            });
            mPlayer.prepareAsync();
        } catch (Throwable t) {
            mBarSub.setText("打不开这条流");
            mBarBtn.setText("!");
            mPlayingName = null;
            refreshCards();
        }
    }

    private String qualityOf(Stations.S s) {
        if (s == null) return "清晰";
        String u = s.url;
        if (u.contains("64k")) return "标准 64kbps";
        if (u.contains(".m3u8")) return "高清 AAC";
        if (u.contains(".mp3")) return "标准 MP3";
        return "清晰";
    }

    private Stations.S current() {
        return Stations.byName(mAll, mPlayingName);
    }

    private void togglePlay() {
        if (mPlayer != null) {
            try {
                if (mPlayer.isPlaying()) {
                    mPlayer.pause();
                    mBarBtn.setText("▶");
                    mBarSub.setText("已暂停");
                } else {
                    mPlayer.start();
                    mBarBtn.setText("❚❚");
                    mBarSub.setText("正在播放 · " + qualityOf(current()));
                }
            } catch (Throwable ignored) { }
            return;
        }
        playSlot(mFocusSlot < mShown.size() ? mFocusSlot : 0);
    }

    private void stopPlayer() {
        if (mPlayer != null) {
            try { mPlayer.stop(); } catch (Throwable ignored) { }
            try { mPlayer.release(); } catch (Throwable ignored) { }
            mPlayer = null;
        }
    }

    // ================= 按键 =================

    private final View.OnKeyListener cardKeys = new View.OnKeyListener() {
        @Override public boolean onKey(View v, int code, KeyEvent e) {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            int slot = mFocusSlot - mOffset;
            if (slot < 0 || slot >= SLOTS) slot = 0;
            int row = slot / COLS, col = slot % COLS;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (col == 0) {
                        if (mCatV[mCat] != null) mCatV[mCat].requestFocus();
                        return true;
                    }
                    mCardV[slot - 1].requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (slot + 1 >= SLOTS) return true;
                    if (mOffset + slot + 1 >= mShown.size()) return true;
                    mCardV[slot + 1].requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (row == 0) {
                        if (mOffset == 0) { goToTabBar(); return true; }   // 卡片最顶 → TAB 栏
                        if (mOffset > 0) {
                            mOffset -= COLS;
                            if (mOffset < 0) mOffset = 0;
                            refreshCards();
                            mFocusSlot = mOffset + slot;
                            mCardV[slot].requestFocus();
                        }
                        return true;
                    }
                    mCardV[slot - COLS].requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: {
                    int target = slot + COLS;
                    boolean lastRow = (row == ROWS - 1) || (mOffset + target >= mShown.size());
                    if (lastRow) {
                        if (mOffset + SLOTS < mShown.size()) {
                            mOffset += COLS;
                            refreshCards();
                            mFocusSlot = mOffset + slot;
                            mCardV[slot].requestFocus();
                        } else if (mBar != null) {
                            mBar.requestFocus();     // 到底了 → 底部播放栏
                        }
                        return true;
                    }
                    mCardV[target].requestFocus();
                    return true;
                }
                case KeyEvent.KEYCODE_BACK:
                    if (mCatV[mCat] != null) mCatV[mCat].requestFocus();
                    return true;
                default:
                    return false;
            }
        }
    };

    private void goToTabBar() {
        View byText = findByText(mRoot.getRootView(), "电台", 0);
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

    // ================= 背景 =================

    private GradientDrawable cardBg(boolean f) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                f ? new int[]{0xFF3B86FF, 0xFF1E5BD6}
                  : new int[]{0x1FFFFFFF, 0x0FFFFFFF});
        d.setCornerRadius(dp(16));
        if (f) d.setStroke(dp(2), 0xCCFFFFFF);
        else d.setStroke(dp(1), 0x33FFFFFF);
        return d;
    }

    /** 播放中但未聚焦：淡蓝底 + 蓝边 */
    private GradientDrawable cardPlayingBg() {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0x332674EE, 0x1A2674EE});
        d.setCornerRadius(dp(16));
        d.setStroke(dp(2), 0xFF5C9BFF);
        return d;
    }

    private GradientDrawable iconBg(int color) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{color, (color & 0x00FFFFFF) | 0x99000000});
        d.setShape(GradientDrawable.OVAL);
        d.setStroke(dp(1), 0x33FFFFFF);
        return d;
    }

    private static int colorFor(String name) {
        int h = name == null ? 0 : Math.abs(name.hashCode());
        int[] pal = {0xFF4A86F7, 0xFF8B5CF6, 0xFFE2574C, 0xFF2FA36B,
                0xFFD98324, 0xFF2C9AA6, 0xFFB4508E, 0xFF5B6BD6};
        return pal[h % pal.length];
    }

    private int dp(int v) { return v * mDp; }

    // ================= BaseFragment =================

    @Override public boolean onFocusRequested(int direction) {
        if (mCatV[mCat] != null) { mCatV[mCat].requestFocus(); return true; }
        return false;
    }

    @Override public void onFragmentSeletedPre(boolean selected) { }

    @Override public boolean onHomeKeyEventHandled() { return false; }

    @Override public void setHoverListener(View.OnHoverListener listener) { }

    @Override public void onFragmentShowChanged(boolean shown) {
        if (!shown) {
            stopPlayer();
            mPlayingName = null;
            if (mBarTitle != null) mBarTitle.setText("选一个电台开始收听");
            if (mBarSub != null) mBarSub.setText("上下切换分类 · 左右选台 · 确定播放");
            if (mBarFm != null) mBarFm.setText("");
            if (mBarBtn != null) mBarBtn.setText("▶");
            refreshCards();
        }
    }

    @Override public void onDestroyView() {
        stopPlayer();
        super.onDestroyView();
    }
}
