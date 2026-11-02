package com.dsh.tvdesk.live;

import android.view.Surface;

/**
 * 预览播放器：用 IjkProxy（借 TVBox 的 IJK 内核）播放，固定 Surface，
 * 换台只换数据源；地址直接交给 ffmpeg，失败再由我们解析 smil 兜底。
 */
public class PreviewPlayer {

    /** true = 所有频道走系统 MediaPlayer（MediaCodec 硬解）；false = 走借来的 IJK */
    private static final boolean USE_SYSTEM_PLAYER = false;

    public interface Listener {
        void onStatus(String text);
        /** 真实解码出来的画面尺寸 */
        void onSize(int width, int height);
        /** 这一路彻底播不了 */
        void onError(int what, int extra);
    }

    private IjkProxy.Player mPlayer;
    private android.media.MediaPlayer mSys;
    private Surface mSurface;
    private String mWanted;
    private boolean mTriedFallback;
    private int mToken;
    private Listener mListener;

    public void setListener(Listener l) {
        mListener = l;
    }

    private void status(String s) {
        Diag.log("player", s);
        if (mListener != null) mListener.onStatus(s);
    }

    public void setSurface(Surface surface) {
        mSurface = surface;
        if (surface != null && mWanted != null && mPlayer == null && mSys == null) {
            open(mWanted);
        }
    }

    public void play(String url) {
        if (url == null) return;
        if (url.equals(mWanted) && (mPlayer != null || mSys != null)) return;
        mWanted = url;
        if (mSurface == null) {
            status("等待画面就绪…");
            return;
        }
        mTriedFallback = false;
        mToken++;
        open(url);
    }

    private void open(final String url) {
        releasePlayer();
        if (USE_SYSTEM_PLAYER) {
            status("连接中（系统播放器）…");
            openSystem(url);
            return;
        }
        if (!IjkProxy.isReady()) {
            // 没装 TVBox（借不到 IJK）时，退回系统播放器；HLS/HTTP 可用，rtsp 多半不行
            status("无 IJK 内核，尝试系统播放器…");
            Diag.log("player", "ijk not ready, fallback to MediaPlayer");
            openSystem(url);
            return;
        }
        status("连接中…");
        final int token = ++mToken;
        String stage = "new";
        try {
            IjkProxy.Player p = new IjkProxy.Player();
            stage = "surface";
            mPlayer = p;
            p.setSurface(mSurface);
            stage = "callback";
            p.setCallback(new IjkProxy.Callback() {
                @Override public void onReady(int w, int h) {
                    if (token != mToken) return;
                    status("已就绪 " + w + "x" + h);
                    if (mListener != null) mListener.onSize(w, h);
                    try {
                        if (mPlayer != null) mPlayer.start();
                    } catch (Throwable t) {
                        Diag.err("player", "start fail " + t);
                    }
                }

                @Override public void onError(int what, int extra) {
                    if (token != mToken) return;
                    if (!mTriedFallback) {
                        mTriedFallback = true;
                        status("直连失败 " + what + "，解析 smil…");
                        new Thread(new Runnable() {
                            @Override public void run() {
                                final String resolved = StreamResolver.resolve(url);
                                new android.os.Handler(android.os.Looper.getMainLooper()).post(
                                        new Runnable() {
                                            @Override public void run() {
                                                if (token != mToken) return;
                                                if (!resolved.equals(url)) open(resolved);
                                            }
                                        });
                            }
                        }, "tvdesk-resolve").start();
                        return;
                    }
                    status("播放错误 " + what + "/" + extra);
                    if (mListener != null) mListener.onError(what, extra);
                }
            });
            stage = "dataSource";
            p.applyTvOptions();
            p.setDataSource(url);
            stage = "prepare";
            p.prepareAsync();
        } catch (Throwable t) {
            status("准备失败(" + stage + ") " + t.getClass().getSimpleName());
            Diag.err("player", "fail at " + stage + ": " + t + " cause=" + t.getCause());
        }
    }

    /** 系统 MediaPlayer 兜底（没有 IJK 时用） */
    private void openSystem(final String url) {
        releasePlayer();
        try {
            mSys = new android.media.MediaPlayer();
            if (mSurface != null) mSys.setSurface(mSurface);
            mSys.setDataSource(url);
            mSys.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(android.media.MediaPlayer mp) {
                    status("已就绪 " + mp.getVideoWidth() + "x" + mp.getVideoHeight());
                    if (mListener != null) mListener.onSize(mp.getVideoWidth(), mp.getVideoHeight());
                    try { mp.start(); } catch (Throwable t) { Diag.err("player", "sys start " + t); }
                }
            });
            mSys.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener() {
                @Override public boolean onError(android.media.MediaPlayer mp, int what, int extra) {
                    if (!mTriedFallback) {
                        mTriedFallback = true;
                        status("直连失败 " + what + "，解析 smil…");
                        final int token = mToken;
                        final String src = url;
                        new Thread(new Runnable() {
                            @Override public void run() {
                                final String resolved = StreamResolver.resolve(src);
                                new android.os.Handler(android.os.Looper.getMainLooper()).post(
                                        new Runnable() {
                                            @Override public void run() {
                                                if (token != mToken) return;
                                                if (!resolved.equals(src)) open(resolved);
                                            }
                                        });
                            }
                        }, "tvdesk-resolve").start();
                        return true;
                    }
                    status("播放失败 " + what + "/" + extra);
                    if (mListener != null) mListener.onError(what, extra);
                    return true;
                }
            });
            mSys.prepareAsync();
        } catch (Throwable t) {
            status("该系统播放器不支持此流");
            Diag.err("player", "sys fail " + t);
        }
    }

    private void releasePlayer() {
        final IjkProxy.Player p = mPlayer;
        mPlayer = null;
        if (p != null) p.release();
        final android.media.MediaPlayer sys = mSys;
        mSys = null;
        if (sys != null) {
            try { sys.reset(); } catch (Throwable ignored) { }
            try { sys.release(); } catch (Throwable ignored) { }
        }
    }

    public void stopPlayback() {
        mWanted = null;
        mToken++;
        releasePlayer();
    }

    public void release() {
        mToken++;
        mWanted = null;
        releasePlayer();
        mSurface = null;
    }
}
