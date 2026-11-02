package com.dsh.tvdesk.live;

import android.content.Context;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.stv.launcher.sdk.fragment.BaseFragment;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 直播页：左侧预览窗 + 右侧频道墙。
 *
 * 交互约定：
 *  - 进页面自动播默认台（央视新闻，没有就播央视第一个）
 *  - 遥控器在频道上移动只移动焦点，不换台、不改标题
 *  - 按确定才切到选中的台
 *  - 焦点在预览窗上按确定进全屏
 */
public class LiveFragment extends BaseFragment {

    private static final String SRC_DIR = "DIYP影音";
    private static final String[] GROUP_ORDER = {"央视", "地方", "数字", "吉林"};
    private static final int COLS = 2;
    private static final int ROWS = 5;
    private static final int SLOTS = COLS * ROWS;
    private static final int[] LOGO_COLORS = {
            0xFF3A6FF3, 0xFF1E8E5A, 0xFFE07B2A, 0xFF8E44AD,
            0xFFC0392B, 0xFF16A085, 0xFF2C3E9E, 0xFFB8860B
    };

    private int mDensity = 2;
    private Context mCtx;

    private final List<List<Channel>> mGroups = new ArrayList<>();
    private final List<String> mGroupNames = new ArrayList<>();
    private int mGroupIndex = 0;

    private final List<Channel> mFlat = new ArrayList<>();
    private int mFocusIndex = 0;
    private int mOffset = 0;
    private int mPlayingIndex = 0;
    /** 是否已经主动开始播过（避免开机自动播） */
    private boolean mStarted;
    /** 刚开机的前两分钟内不自动播，等用户按键 */
    private boolean mBootGuard = android.os.SystemClock.elapsedRealtime() < 120000L;
    private String mPlayingUrl;

    private PreviewPlayer mPlayer;
    private View mPreviewBox;
    private TextView mNowTitle, mNowDesc, mCount, mStatus, mTagRes;

    private final View[] mSlotViews = new View[SLOTS];
    private final TextView[] mSlotNames = new TextView[SLOTS];
    private final TextView[] mSlotLogos = new TextView[SLOTS];
    private final TextView[] mSlotSubs = new TextView[SLOTS];
    private final TextView[] mChips = new TextView[GROUP_ORDER.length];

    static class Channel {
        String name;
        String url;
        Channel(String n, String u) { name = n; url = u; }
    }

    // ---------------- 数据 ----------------

    private void loadChannels() {
        mGroups.clear();
        mGroupNames.clear();
        File dir = new File(Environment.getExternalStorageDirectory(), SRC_DIR);
        File[] files = dir.listFiles();
        List<String> found = new ArrayList<>();
        List<List<Channel>> parsed = new ArrayList<>();
        if (files != null) {
            java.util.Arrays.sort(files, new java.util.Comparator<File>() {
                @Override public int compare(File a, File b) {
                    return a.getName().compareTo(b.getName());
                }
            });
            for (File f : files) {
                if (!f.getName().toLowerCase(Locale.US).endsWith(".txt")) continue;
                List<Channel> list = parseTxt(f);
                if (!list.isEmpty()) {
                    found.add(f.getName().replace(".txt", ""));
                    parsed.add(list);
                }
            }
        }
        for (String want : GROUP_ORDER) {
            for (int i = 0; i < found.size(); i++) {
                if (found.get(i).startsWith(want)) {
                    mGroupNames.add(found.get(i));
                    mGroups.add(parsed.get(i));
                }
            }
        }
        for (int i = 0; i < found.size(); i++) {
            if (!mGroupNames.contains(found.get(i))) {
                mGroupNames.add(found.get(i));
                mGroups.add(parsed.get(i));
            }
        }
    }

    private List<Channel> parseTxt(File f) {
        List<Channel> out = new ArrayList<>();
        try {
            byte[] data = new byte[(int) f.length()];
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            int off = 0;
            while (off < data.length) {
                int n = fis.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
            fis.close();
            String text = decode(data);
            for (String line : text.split("\\r?\\n")) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#")) continue;
                int comma = line.indexOf(',');
                if (comma <= 0 || comma >= line.length() - 1) continue;
                String name = line.substring(0, comma).trim();
                String url = line.substring(comma + 1).trim();
                int hash = url.indexOf('#');
                if (hash > 0) url = url.substring(0, hash);
                int dollar = url.indexOf('$');
                if (dollar > 0) url = url.substring(0, dollar);

                if (name.length() == 0 || !(url.startsWith("http") || url.startsWith("rtsp")
                        || url.startsWith("rtmp") || url.startsWith("udp") || url.startsWith("igmp"))) {
                    continue;
                }
                out.add(new Channel(cleanName(name), url));
            }
        } catch (Throwable t) {
            Diag.err("load", "parse fail " + f + " " + t);
        }
        return out;
    }

    /**
     * 清洗频道显示名：
     *   高清 / 高清现网 / 超高清 / 现网 / 标清 / HD 这类后缀不是频道名的一部分，去掉；
     *   4K 属于频道标识，保留并单独分隔。
     */
    private static final String[] NAME_SUFFIX = {
            "高清现网", "超高清", "高清台", "高清", "标清", "超清", "现网", "HD", "hd"
    };

    private static String cleanName(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        boolean is4k = s.contains("4K") || s.contains("4k") || s.contains("超高清");

        boolean changed = true;
        while (changed && s.length() > 1) {
            changed = false;
            for (String d : NAME_SUFFIX) {
                if (s.length() > d.length() && s.toLowerCase(Locale.US).endsWith(d.toLowerCase(Locale.US))) {
                    s = s.substring(0, s.length() - d.length()).trim();
                    changed = true;
                }
            }
        }

        s = s.replaceAll("\\s+", " ").trim();

        // CCTV4 → CCTV-4
        s = s.replaceAll("(?i)^CCTV(\\d)", "CCTV-$1");
        // CCTV-16奥林匹克 → CCTV-16 奥林匹克
        s = s.replaceAll("(?i)(CCTV[- ]?\\d+\\+?)([\\u4e00-\\u9fa5])", "$1 $2");
        // 北京卫视4K → 北京卫视 4K
        s = s.replaceAll("([\\u4e00-\\u9fa5])(\\d+[Kk])$", "$1 $2");
        // 已经是 4K/超高清 但名字里没体现的，补上标识
        if (is4k && !s.contains("4K") && !s.contains("4k")) {
            s = s + " 4K";
        }
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    /** 源文件编码不统一：先严格 UTF-8，失败再退回 GBK。 */
    private String decode(byte[] data) {
        try {
            java.nio.charset.CharsetDecoder dec = java.nio.charset.StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            String s = dec.decode(java.nio.ByteBuffer.wrap(data)).toString();
            if (s.startsWith("\uFEFF")) s = s.substring(1);
            return s;
        } catch (Throwable ignored) { }
        try {
            String s = new String(data, "GBK");
            if (s.startsWith("\uFEFF")) s = s.substring(1);
            return s;
        } catch (Throwable ignored) { }
        return new String(data);
    }

    // ---------------- UI ----------------

    @Override
    public View onInflaterContent(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Context ctx = container != null ? container.getContext() : getActivity();
        mCtx = ctx;
        if (ctx == null) return new TextView(getActivity());
        mDensity = Math.round(ctx.getResources().getDisplayMetrics().density);
        if (mDensity <= 0) mDensity = 2;
        IjkProxy.init(ctx);
        // 必须先把频道列表读出来，否则分类条建不出来（列表还是空的）
        loadChannels();

        FrameLayout root = new FrameLayout(ctx);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setPadding(dp(26), dp(104), dp(26), dp(12));
        root.setClipChildren(false);
        root.setClipToPadding(false);
        // 任何按键都算“用户来了”：解除开机保护并开始播默认台；BACK 另有处理
        root.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_BACK) {
                    mBootGuard = false;
                    ensurePlaying();
                    return false;
                }
                TextView chip = (mGroupIndex >= 0 && mGroupIndex < mChips.length)
                        ? mChips[mGroupIndex] : null;
                if (chip != null && chip.isFocused()) {
                    // 已经在分类上，再按一次返回就回到顶部 tab 条
                    View up = chip.focusSearch(View.FOCUS_UP);
                    if (up != null && up != chip && !isInside(root, up)) {
                        up.requestFocus();
                    }
                    return true;
                }
                if (chip != null) {
                    chip.requestFocus();
                }
                return true;
            }
        });

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        row.setClipChildren(false);

        LinearLayout left = new LinearLayout(ctx);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1.92f);
        leftLp.setMargins(0, 0, dp(20), 0);
        left.setLayoutParams(leftLp);
        left.addView(buildPreview(ctx));
        left.addView(buildInfoBar(ctx));

        LinearLayout right = new LinearLayout(ctx);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1.0f));
        right.setClipChildren(false);
        right.addView(buildChips(ctx));
        right.addView(buildGrid(ctx));

        row.addView(left);
        row.addView(right);
        root.addView(row);

        mPlayer = new PreviewPlayer();
        mPlayer.setListener(new PreviewPlayer.Listener() {
            @Override public void onStatus(final String text) {
                if (mStatus == null) return;
                mStatus.post(new Runnable() {
                    @Override public void run() {
                        mStatus.setText(text);
                        boolean ready = text != null && text.startsWith("已就绪");
                        mStatus.setVisibility(ready ? View.GONE : View.VISIBLE);
                    }
                });
            }

            @Override public void onSize(final int width, final int height) {
                if (mTagRes == null) return;
                mTagRes.post(new Runnable() {
                    @Override public void run() {
                        // 真实分辨率，不再写死「高清」
                        if (height >= 2160) mTagRes.setText("4K");
                        else if (height >= 1080) mTagRes.setText("1080P");
                        else if (height >= 720) mTagRes.setText("720P");
                        else if (height > 0) mTagRes.setText(height + "P");
                        else mTagRes.setText("--");
                    }
                });
            }

            @Override public void onError(int what, int extra) {
                markPlayingChannelDead();
            }
        });

        if (mGroups.isEmpty()) {
            mNowTitle.setText("没找到频道列表");
            mNowDesc.setText("/sdcard/" + SRC_DIR + "/*.txt");
        } else {
            bindGroup(0, false);
        }
        return root;
    }

    private View buildPreview(Context ctx) {
        FrameLayout box = new RatioBox(ctx, 9f / 16f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        box.setLayoutParams(lp);
        box.setBackground(previewBg(false));
        box.setClipToOutline(true);
        box.setFocusable(true);
        box.setFocusableInTouchMode(false);
        box.setClickable(true);
        mPreviewBox = box;

        // 这台机器的 SurfaceView 合成层有问题（会出现画面镜像递归），只能用 TextureView
        TextureView video = new TextureView(ctx);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        tlp.setMargins(dp(3), dp(3), dp(3), dp(3));
        video.setLayoutParams(tlp);
        video.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int hh) {
                if (mPlayer != null) mPlayer.setSurface(new Surface(st));
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int hh) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
        box.addView(video);

        mStatus = new TextView(ctx);
        mStatus.setTextColor(0xFF7FE0A0);
        mStatus.setTextSize(11f);
        mStatus.setPadding(dp(16), dp(12), dp(16), 0);
        mStatus.setShadowLayer(4f, 0, 1, 0xCC000000);
        mStatus.setVisibility(View.GONE);
        FrameLayout.LayoutParams stlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp.gravity = Gravity.TOP | Gravity.START;
        mStatus.setLayoutParams(stlp);
        box.addView(mStatus);

        // 台名不放视频上，避免压住台标和字幕，改到下方信息条里
        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openFullscreen(); }
        });
        // 焦点在小窗时按右/下，回到频道墙
        box.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if ((keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
                        && mSlotViews[0] != null) {
                    int slot = mFocusIndex - mOffset;
                    if (slot < 0 || slot >= SLOTS) slot = 0;
                    mSlotViews[slot].requestFocus();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    focusCurrentChip();
                    return true;
                }
                return false;
            }
        });
        box.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                v.setBackground(previewBg(hasFocus));
            }
        });
        return box;
    }

    private GradientDrawable previewBg(boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFF000000);
        d.setCornerRadius(0);
        d.setStroke(dp(focused ? 3 : 2), focused ? 0xFFFFFFFF : 0x44FFFFFF);
        return d;
    }

    private View buildInfoBar(Context ctx) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(10), 0, 0);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout names = new LinearLayout(ctx);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mNowTitle = new TextView(ctx);
        mNowTitle.setTextColor(Color.WHITE);
        mNowTitle.setTextSize(17f);
        mNowTitle.setSingleLine(true);

        mNowDesc = new TextView(ctx);
        mNowDesc.setTextColor(0x99FFFFFF);
        mNowDesc.setTextSize(11f);
        mNowDesc.setPadding(0, dp(2), 0, 0);

        names.addView(mNowTitle);
        names.addView(mNowDesc);
        bar.addView(names);

        TextView time = new TextView(ctx);
        time.setTextColor(0xE6FFFFFF);
        time.setTextSize(14f);
        time.setText(new SimpleDateFormat("HH:mm", Locale.US).format(new Date()));
        bar.addView(time);

        mTagRes = tag(ctx, "--", 0xFF2B6BF3);
        bar.addView(mTagRes);

        mCount = new TextView(ctx);
        mCount.setTextColor(0x99FFFFFF);
        mCount.setTextSize(13f);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(14);
        mCount.setLayoutParams(clp);
        bar.addView(mCount);
        return bar;
    }

    private TextView tag(Context ctx, String text, int color) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(10f);
        tv.setPadding(dp(7), dp(2), dp(7), dp(3));
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(5));
        tv.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(9);
        tv.setLayoutParams(lp);
        return tv;
    }

    private View buildChips(Context ctx) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        bar.setLayoutParams(lp);

        int n = Math.min(GROUP_ORDER.length, mGroups.size());
        for (int i = 0; i < n; i++) {
            final int idx = i;
            TextView chip = new TextView(ctx);
            chip.setTextSize(14f);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(15), dp(6), dp(15), dp(7));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = dp(9);
            chip.setLayoutParams(clp);
            chip.setFocusable(true);
            chip.setFocusableInTouchMode(false);
            chip.setClickable(true);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { bindGroup(idx, false); }
            });
            chip.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean hasFocus) {
                    v.setBackground(chipBg(idx, hasFocus));
                    float s = hasFocus ? 1.06f : 1f;
                    v.animate().scaleX(s).scaleY(s).setDuration(110).start();
                }
            });
            chip.setOnKeyListener(new View.OnKeyListener() {
                @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        if (mSlotViews[0] != null) mSlotViews[0].requestFocus();
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_BACK) {
                        goToTabBar(v.getRootView());
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        int next = idx + (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        if (next >= 0 && next < mChips.length && mChips[next] != null) {
                            mChips[next].requestFocus();
                        } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && mPreviewBox != null) {
                            mPreviewBox.requestFocus();
                        }
                        return true;
                    }
                    return false;
                }
            });
            mChips[i] = chip;
            bar.addView(chip);
        }
        return bar;
    }

    private GradientDrawable chipBg(int idx, boolean hasFocus) {
        GradientDrawable d = new GradientDrawable();
        boolean on = idx == mGroupIndex;
        d.setColor(hasFocus ? 0xFF2B6BF3 : (on ? 0x33FFFFFF : 0x1AFFFFFF));
        d.setCornerRadius(dp(13));
        if (hasFocus) d.setStroke(dp(2), 0xFFFFFFFF);
        return d;
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
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                clp.setMargins(c == 0 ? 0 : dp(6), dp(5), c == 0 ? dp(6) : 0, dp(5));
                card.setLayoutParams(clp);
                card.setBackground(cardBg(false));
                card.setFocusable(true);
                card.setFocusableInTouchMode(false);
                card.setClickable(true);

                LinearLayout inner = new LinearLayout(ctx);
                inner.setOrientation(LinearLayout.HORIZONTAL);
                inner.setGravity(Gravity.CENTER_VERTICAL);
                inner.setPadding(dp(12), 0, dp(8), 0);
                inner.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                TextView logo = new TextView(ctx);
                logo.setTextColor(Color.WHITE);
                logo.setTextSize(14f);
                logo.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(36), dp(36));
                llp.rightMargin = dp(10);
                logo.setLayoutParams(llp);

                LinearLayout texts = new LinearLayout(ctx);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setLayoutParams(new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView name = new TextView(ctx);
                name.setTextColor(Color.WHITE);
                name.setTextSize(15f);
                name.setMaxLines(2);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);

                TextView sub = new TextView(ctx);
                sub.setTextColor(0x99FFFFFF);
                sub.setTextSize(11f);
                sub.setText("");
                sub.setVisibility(View.GONE);

                texts.addView(name);
                texts.addView(sub);
                inner.addView(logo);
                inner.addView(texts);
                card.addView(inner);

                card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                    @Override public void onFocusChange(View v, boolean hasFocus) {
                        v.setBackground(cardBg(hasFocus));
                        float s = hasFocus ? 1.02f : 1f;
                        v.animate().scaleX(s).scaleY(s).setDuration(110).start();
                        if (hasFocus) {
                            int i = mOffset + slot;
                            if (i < mFlat.size()) {
                                mFocusIndex = i;
                                if (mCount != null) {
                                    mCount.setText(String.format(Locale.US, "%d/%d",
                                            i + 1, mFlat.size()));
                                }
                            }
                        }
                    }
                });
                card.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        int i = mOffset + slot;
                        if (i < mFlat.size()) playChannel(i);
                    }
                });
                card.setOnKeyListener(gridKeys);

                mSlotViews[slot] = card;
                mSlotNames[slot] = name;
                mSlotLogos[slot] = logo;
                mSlotSubs[slot] = sub;
                line.addView(card);
            }
            grid.addView(line);
        }
        return grid;
    }

    /** 这一路彻底播不了：把卡片上的副标题标成「源失效」 */
    private void markPlayingChannelDead() {
        final int slot = mPlayingIndex - mOffset;
        if (slot < 0 || slot >= SLOTS || mSlotSubs[slot] == null) return;
        mSlotSubs[slot].post(new Runnable() {
            @Override public void run() {
                TextView sub = mSlotSubs[slot];
                if (sub == null) return;
                sub.setText("源失效");
                sub.setTextColor(0xFFFF6B6B);
                sub.setVisibility(View.VISIBLE);
            }
        });
    }

    /** 把焦点交回顶部 tab 条：先试焦点搜索，不行就派发一个「上」键给宿主 Activity */
    private void goToTabBar(View root) {
        // 先按名字精确找自己那个 tab（"直播"），最可靠
        View byText = findByText(root.getRootView(), "直播", 0);
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
        View up = root.focusSearch(View.FOCUS_BACKWARD);
        if (up != null && !isInside(root, up)) {
            up.requestFocus();
            return;
        }
        final android.app.Activity act = unwrapActivity(mCtx);
        if (act == null) return;
        // 必须在本次按键回调之外派发，否则会被当前事件分发吞掉
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
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

    private static android.app.Activity unwrapActivity(Context c) {
        while (c != null) {
            if (c instanceof android.app.Activity) return (android.app.Activity) c;
            if (!(c instanceof android.content.ContextWrapper)) return null;
            c = ((android.content.ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    /** v 是不是 root 的后代（用来判断 focusSearch 是否跑出了本页） */
    private static boolean isInside(View root, View v) {
        View cur = v;
        while (cur != null) {
            if (cur == root) return true;
            if (!(cur.getParent() instanceof View)) break;
            cur = (View) cur.getParent();
        }
        return false;
    }

    private void focusCurrentChip() {
        // 回到分类的同时，把频道墙滚回第一行第一格，省得手动翻回来
        if (mOffset != 0 || mFocusIndex != 0) {
            mOffset = 0;
            mFocusIndex = 0;
            refreshSlots();
            if (mCount != null && !mFlat.isEmpty()) {
                mCount.setText(String.format(Locale.US, "1/%d", mFlat.size()));
            }
        }
        if (mGroupIndex >= 0 && mGroupIndex < mChips.length) {
            TextView chip = mChips[mGroupIndex];
            if (chip != null) chip.requestFocus();
        }
    }

    /** 强制 16:9 的容器：宽度定了之后高度按比例算，避免预览窗被拉成 4:3 */
    private static class RatioBox extends FrameLayout {
        private final float mRatio;
        RatioBox(Context ctx, float ratio) {
            super(ctx);
            mRatio = ratio;
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int height = (int) (width * mRatio);
            super.onMeasure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
    }

    private GradientDrawable cardBg(boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(focused ? 0xFF2B6BF3 : 0x16FFFFFF);
        d.setCornerRadius(dp(10));
        if (focused) d.setStroke(dp(2), 0xFFFFFFFF);
        return d;
    }

    private GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    private static int colorFor(String name) {
        int h = name == null ? 0 : Math.abs(name.hashCode());
        return LOGO_COLORS[h % LOGO_COLORS.length];
    }

    // ---------------- 逻辑 ----------------

    private void bindGroup(int idx, boolean autoplay) {
        if (idx < 0 || idx >= mGroups.size()) return;
        mGroupIndex = idx;
        mFlat.clear();
        mFlat.addAll(mGroups.get(idx));
        mOffset = 0;
        mFocusIndex = 0;
        for (int i = 0; i < mChips.length; i++) {
            if (mChips[i] == null) continue;
            boolean on = i == idx;
            mChips[i].setText(on ? "● " + mGroupNames.get(i) : mGroupNames.get(i));
            mChips[i].setTextColor(on ? Color.WHITE : 0x99FFFFFF);
            mChips[i].setBackground(chipBg(i, false));
        }
        refreshSlots();
        if (autoplay) playChannel(defaultIndex(mGroupNames.get(idx)));
    }

    private int defaultIndex(String group) {
        if (mFlat.isEmpty()) return 0;
        if (group.startsWith("央视")) {
            for (int i = 0; i < mFlat.size(); i++) {
                String n = mFlat.get(i).name;
                if (n.contains("新闻") || n.contains("CCTV-13")) return i;
            }
        }
        return 0;
    }

    private void refreshSlots() {
        for (int s = 0; s < SLOTS; s++) {
            int i = mOffset + s;
            if (i < mFlat.size()) {
                Channel ch = mFlat.get(i);
                mSlotNames[s].setText(ch.name);
                mSlotLogos[s].setText(logoText(ch.name));
                mSlotLogos[s].setBackground(circle(colorFor(ch.name)));
                if (mSlotSubs[s] != null) {
                    if (is4k(ch.name)) {
                        mSlotSubs[s].setText("4K 台");
                        mSlotSubs[s].setTextColor(0xFFFFB020);
                        mSlotSubs[s].setVisibility(View.VISIBLE);
                    } else {
                        mSlotSubs[s].setText("");
                        mSlotSubs[s].setVisibility(View.GONE);
                    }
                }
                mSlotViews[s].setVisibility(View.VISIBLE);
            } else {
                mSlotNames[s].setText("");
                mSlotLogos[s].setText("");
                if (mSlotSubs[s] != null) mSlotSubs[s].setVisibility(View.GONE);
                mSlotViews[s].setVisibility(View.INVISIBLE);
            }
        }
    }

    private static String logoText(String name) {
        if (name == null) return "";
        String n = name.replace("高清", "").replace("现网", "").trim();
        if (n.startsWith("CCTV")) {
            String rest = n.substring(4).replace("-", "").replace(" ", "");
            boolean plus = rest.toLowerCase(Locale.US).contains("plus");
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (Character.isDigit(c)) digits.append(c);
                else break;
            }
            if (digits.length() == 0) return "C";
            return plus ? digits + "+" : digits.toString();
        }
        return n.length() >= 2 ? n.substring(0, 2) : n;
    }

    private void updateHeader(Channel ch) {
        if (mNowTitle != null) mNowTitle.setText(ch.name);
        if (mNowDesc != null) mNowDesc.setText("正在直播：" + mGroupNames.get(mGroupIndex));
    }

    private void playChannel(int index) {
        if (index < 0 || index >= mFlat.size()) return;
        Channel ch = mFlat.get(index);
        mPlayingUrl = ch.url;
        mPlayingIndex = index;
        mStarted = true;
        mBootGuard = false;
        updateHeader(ch);
        if (mCount != null) {
            mCount.setText(String.format(Locale.US, "%d/%d", index + 1, mFlat.size()));
        }
        if (mStatus != null && is4k(ch.name)) {
            mStatus.setVisibility(View.VISIBLE);
            mStatus.setText("4K 台：本机通用内核解码可能花屏");
        }
        if (mPlayer != null) mPlayer.play(ch.url);
    }

    private static boolean is4k(String name) {
        if (name == null) return false;
        String n = name.toUpperCase(Locale.US);
        return n.contains("4K") || name.contains("超高清") || n.contains("2160");
    }

    private void openFullscreen() {
        if (mFlat.isEmpty() || mCtx == null) return;
        final Channel ch = mFlat.get(Math.min(mPlayingIndex, mFlat.size() - 1));
        // 全屏前先停掉预览，否则两路同时解码，这台机器扛不住
        if (mPlayer != null) mPlayer.stopPlayback();
        NativePlayer.show(mCtx, ch.name, ch.url, new Runnable() {
            @Override public void run() {
                // 从全屏退出后，把预览接回来
                if (mPlayer != null && mPlayingUrl != null) mPlayer.play(mPlayingUrl);
            }
        });
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
                    // 只有左列能往右，右列原地不动
                    if (fi % COLS == 0 && fi + 1 < size) fi++;
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (fi % COLS == 0) {
                        if (mPreviewBox != null) mPreviewBox.requestFocus();
                        return true;
                    }
                    fi--;
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (fi + COLS >= size) return true;
                    fi += COLS;
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (fi < COLS) {
                        focusCurrentChip();
                        return true;
                    }
                    fi -= COLS;
                    break;
                case KeyEvent.KEYCODE_BACK:
                    focusCurrentChip();
                    return true;
                default:
                    return false;
            }
            // 保证 fi 落在窗口内，窗口始终按整行移动，左右列不会错位
            int oldOffset = mOffset;
            while (fi < mOffset) mOffset -= COLS;
            while (fi >= mOffset + SLOTS) mOffset += COLS;
            if (mOffset < 0) mOffset = 0;
            if (mOffset % COLS != 0) mOffset -= mOffset % COLS;
            mFocusIndex = fi;
            int slot = fi - mOffset;
            if (slot < 0 || slot >= SLOTS) slot = fi % COLS;
            // 只有窗口真的移动了才重绑内容；否则会把当前焦点视图刷成不可见，焦点会被系统带走
            if (mOffset != oldOffset) refreshSlots();
            if (mSlotViews[slot] != null) mSlotViews[slot].requestFocus();
            if (mCount != null) mCount.setText(String.format(Locale.US, "%d/%d", fi + 1, size));
            return true;
        }
    };

    // ---------------- BaseFragment ----------------

    @Override public boolean onFocusRequested(int direction) {
        // 18 = FOCUS_TOP_IN：焦点已经在分类条上时不要再抢，
        // 否则桌面永远没法把焦点交给顶部 tab 条
        TextView chip = (mGroupIndex >= 0 && mGroupIndex < mChips.length)
                ? mChips[mGroupIndex] : null;
        if (direction == 18 && chip != null && chip.isFocused()) {
            return false;
        }
        if (chip != null && chip.getVisibility() == View.VISIBLE) {
            chip.requestFocus();
            return true;
        }
        if (mSlotViews[0] != null && mSlotViews[0].getVisibility() == View.VISIBLE) {
            mSlotViews[0].requestFocus();
            return true;
        }
        return false;
    }

    @Override public void onFragmentSeletedPre(boolean selected) { }

    @Override public void onFragmentShowChanged(boolean shown) {
        if (!shown) {
            // 离开直播页就停，不占解码资源
            if (mPlayer != null) mPlayer.stopPlayback();
        } else if (!mBootGuard) {
            // 切到直播页才开播；刚开机的前两分钟不算（等用户按键）
            ensurePlaying();
        }
    }

    /** 第一次进直播页时播默认台，之后恢复上次的台 */
    private void ensurePlaying() {
        if (mPlayer == null || mFlat.isEmpty()) return;
        if (!mStarted) {
            mStarted = true;
            playChannel(defaultIndex(mGroupNames.get(mGroupIndex)));
        } else if (mPlayingUrl != null) {
            mPlayer.play(mPlayingUrl);
        }
    }

    @Override public boolean onHomeKeyEventHandled() { return false; }

    @Override public void setHoverListener(View.OnHoverListener listener) { }

    @Override public void onDestroyView() {
        if (mPlayer != null) mPlayer.release();
        mPlayer = null;
        super.onDestroyView();
    }

    int dp(int v) { return v * mDensity; }
}